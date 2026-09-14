# k8s-demo 云原生实战项目 — 设计文档

> 创建日期：2026-09-14
> 目标：在一台 Windows 机器（≥32G 内存）上，用 Java 技术栈完整演练云原生部署全链路——代码刻意保持简单，重心全部放在"部署那一套"。

## 1. 背景与目标

- 复习 Kubernetes 概念与实操（曾学过尚硅谷教程，久未使用）
- 最大程度模拟**真实线上操作**：多服务、中间件、CI/CD、Helm、监控
- 代码从简（每服务 2~4 个类），部署链路要全（构建 → 镜像 → 仓库 → 编排 → 发布 → 监控）

## 2. 架构

```
GitLab CE（Docker 部署在 WSL2，自建代码仓库 + CI/CD 平台）
   │  git push
   ▼
GitLab Runner（shell executor，跑在 WSL2 宿主机）
   │  mvn package → docker build → push localhost:5000 → kubectl 滚动发布
   ▼
kind 集群（WSL2 内，多节点，自带 local registry）
   ├── ingress-nginx ──► gateway-service ──► order-service ──► user-service
   │                        │ 发消息            │                │
   ├── RabbitMQ ◄───────────┘ (order.created)  │ 监听消费        │
   │        ▲──────────────────────────────────┘                │
   ├── MySQL (StatefulSet + PVC + Headless Service)   └── Redis │
   ├── SkyWalking（OAP + UI：链路追踪，java agent 由 initContainer 注入）│
   ├── EFK：Filebeat(DaemonSet) → Elasticsearch(StatefulSet) → Kibana      │
   └── Prometheus + Grafana（监控，Phase 5）
```

**业务链路**：`POST /api/order/orders` → order-service 查 Redis 缓存（未命中则 Feign 调 user-service 查用户并写缓存）→ 生成订单存 Redis → 发 `order.created` 消息到 RabbitMQ → user-service 消费消息（"用户加积分"，打印日志）。演示：同步调用（Feign）、缓存（Redis）、异步解耦（RabbitMQ）三种服务间通信方式。

## 3. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| JDK | 17（WSL2 内安装 openjdk-17） | Spring Boot 3 硬性要求；现代云原生标配 |
| 框架 | Spring Boot 3.2.5 + Spring Cloud 2023.0.1 | SB3 原生支持优雅停机、actuator 健康分组 |
| 网关 | Spring Cloud Gateway（reactor-netty） | 路由目标直接用 k8s 服务名，无需注册中心 |
| 服务间调用 | OpenFeign（url 直连 k8s 服务名） | 演示 k8s DNS 服务发现替代 Nacos/Eureka |
| 数据访问 | Spring Data JPA | 零 XML 最少代码；生产建议 Flyway（文档注明） |
| 缓存/消息 | spring-boot-starter-data-redis / -amqp | 标准 |
| 构建 | Maven 多模块 monorepo | 代码少时最好管理；CI 按模块打镜像 |
| 镜像 | 多阶段 Dockerfile（maven:3.9-temurin-17 → eclipse-temurin:17-jre） | 镜像最小化教学点 |
| 集群 | kind 多节点（WSL2） | 秒级建/毁，反复练习 |
| 镜像仓库 | kind 内建 local registry（localhost:5000） | Phase 3 跑通零成本；Harbor 为 Phase 5 升级项 |
| CI/CD | GitLab CE + GitLab Runner（shell executor，WSL2） | 国内企业真实线标配；全自建 |
| APM 链路追踪 | SkyWalking 9.2（OAP + UI，Helm/裸 YAML 部署） | 国内企业真实线标配；java agent 由 initContainer 注入共享卷，不改镜像——云原生 sidecar 模式教学点 |
| 日志采集 | EFK：Filebeat 7.17（DaemonSet）→ ES 7.17（StatefulSet 单节点）→ Kibana（Ingress） | 国内企业真实线标配；DaemonSet 每节点一采集器；业务零改动（12-factor stdout） |
| 存储 | local-path provisioner（kind 自带） | Phase 5 升级 NFS provisioner |
| 监控 | kube-prometheus-stack（Helm） | 真实线上标准配置 |

## 4. 模块与代码结构

```
k8s-demo/
├── pom.xml                    # parent，SB 3.2.5 + SC 2023.0.1
├── gateway-service/           # Spring Cloud Gateway，路由 /api/user/** 和 /api/order/**
├── user-service/              # GET /users/{id} 读 MySQL；@RabbitListener 消费 order.created
├── order-service/             # POST /orders（Redis 缓存 → Feign 调 user → 发 MQ）；GET /orders/{id}
├── docker/                    # Dockerfile.gateway / Dockerfile.user / Dockerfile.order
├── k8s/
│   ├── raw/                   # 手写 YAML（教学主战场，编号即部署顺序）
│   └── helm/k8s-demo/         # Helm chart（Phase 4，把 raw/ 全部 Chart 化）
├── .gitlab-ci.yml             # 三段流水线：build → 镜像 → 部署
└── docs/                      # 环境搭建、部署手册、CI/CD、进阶手册 + 本设计文档
```

各服务代码清单（刻意精简）：

- **user-service**：`Application`、`User`（实体）、`UserRepository`（JPA 接口）、`UserController`（GET /users/{id}）、`OrderEventListener`（MQ 消费，打印加分日志）、`DataSeeder`（启动时种 3 条演示数据）
- **order-service**：`Application`、`OrderController`（POST/GET）、`UserClient`（Feign）、`MqConfig`（声明交换机/队列/绑定）
- **gateway-service**：`Application`、`application.yml`（两条路由）

## 5. 部署覆盖的知识点清单

| 知识点 | 载体 |
|---|---|
| Deployment 无状态部署、滚动更新、回滚 | 3 个业务服务 + Redis + RabbitMQ |
| StatefulSet 有状态部署（稳定标识/PVC 模板/Headless） | MySQL |
| 有状态两种流派对比 | MySQL（StatefulSet）vs RabbitMQ（Deployment+PVC） |
| Service（ClusterIP / Headless）与 k8s DNS 服务发现 | 各服务；Feign 用服务名直连 |
| Ingress 域名路由 | gateway 入口，`/api/user`、`/api/order` 分流 |
| ConfigMap / Secret 配置外置 | 各服务 application 配置、MySQL/RabbitMQ 密码 |
| 探针三件套（startup/liveness/readiness） | 业务服务 actuator + 中间件容器探针 |
| 优雅停机（graceful shutdown + preStop + terminationGracePeriod） | 业务服务 Deployment |
| HPA 自动扩缩容 | order-service（需 metrics-server） |
| 资源 requests/limits 与 QoS | 全部 Deployment |
| InitContainer 启动依赖 | user-service 等 MySQL 就绪 |
| PVC / StorageClass 动态供给 | MySQL 数据盘、RabbitMQ 数据盘 |
| RBAC 最小权限 | 专用 ServiceAccount + Role 示例 |
| NetworkPolicy 网络隔离 | 需要 Calico 才生效（kindnet 不支持，教学点） |
| Helm 打包发布 | k8s/helm/k8s-demo |
| CI/CD 全自动发布 | GitLab + Runner + .gitlab-ci.yml |
| DaemonSet 每节点运行 | Filebeat 日志采集器（与 Deployment/StatefulSet 三种控制器对比） |
| 日志集中采集（EFK 全链路） | stdout → Filebeat → ES → Kibana 检索，k8s 元数据自动打标，按服务/命名空间筛选 |
| APM 链路追踪（跨服务调用链可视化） | SkyWalking OAP/UI + initContainer 注入 java agent（emptyDir 共享卷，不改业务镜像） |
| 监控指标 | Prometheus 抓 actuator /metrics，Grafana 面板 |

## 6. 镜像与 CI 设计

- **镜像标签**：`localhost:5000/k8s-demo/<service>:<git short sha>`，不可变标签
- **多阶段构建**：阶段 1 `maven:3.9-eclipse-temurin-17` 编译打包；阶段 2 `eclipse-temurin:17-jre` 仅含 JRE 运行
- **CI 三段**：
  1. `build` — mvn 打包（JDK 17）
  2. `docker-image` — 三个镜像 build + push 到 localhost:5000
  3. `deploy` — `kubectl -n k8s-demo set image` 滚动更新 + `rollout status` 等就绪
- **Runner 选型**：shell executor 跑在 WSL2 宿主机——可直接用宿主机的 docker/mvn/kubectl，零 DIND 复杂度，也是自建场景常见做法

## 7. 部署阶段（今晚按序推进，做不完不慌）

| Phase | 内容 | 产出 |
|---|---|---|
| 0 | WSL2 + Docker + kind + kubectl + helm 安装 | 可用的 kind 集群 + registry |
| 1 | 代码本地跑通（可选，验证业务正确性） | 可运行代码 |
| 2 | 手动全链路：build → push → apply → 浏览器验证 | 核心成果 |
| 3 | GitLab CE 安装 + 建仓 push + Runner 注册 + CI 自动发布 | CI/CD 闭环 |
| 4 | Helm 化 + HPA + RBAC + NetworkPolicy + SkyWalking 链路追踪 | 全知识点覆盖 |
| 5 | 监控（Prometheus/Grafana）、NFS、Harbor、ArgoCD | 真实线上全貌 |

## 8. 简化声明（真实线上会不一样，文档中会注明）

- 生产用 Flyway/Liquibase 管表结构，本项目用 JPA `ddl-auto=update` 简化
- 生产 Redis 用主从/哨兵/集群，本项目单副本
- 生产 RabbitMQ 用镜像队列/集群，本项目单副本
- 生产仓库用 Harbor（账号体系/镜像扫描），本项目 kind 内建 registry
- 生产 CI 用 docker-in-docker 或 Kaniko，本项目 shell executor 直调宿主 docker
- SkyWalking 存储用 H2（重启即丢，仅演示）；生产用 Elasticsearch 持久化
- EFK 的 ES 单节点且关闭 security/ILM（教学简化）；生产多节点集群 + 认证 + 生命周期管理
- 镜像仓库、java agent 镜像等官方镜像在国内网络下需配置镜像加速（文档 00 环境搭建中有说明）
