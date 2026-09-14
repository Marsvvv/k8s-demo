# k8s-demo — Kubernetes 云原生实战项目

一个**代码简单、部署链路完整**的 Java 云原生练习项目：三个微服务 + 三个中间件 + APM + CI/CD，从代码提交到滚动发布的每一步都按真实线上流程走。

```
GitLab CE（自建，WSL2 Docker） → git push → GitLab Runner（shell executor）
  → mvn 编译 → Dockerfile 多阶段构建 → 推 localhost:5000 registry
  → kubectl set image 滚动更新 → ingress-nginx 对外服务

kind 集群（WSL2，1 控制面 + 2 工作节点）
├── ingress-nginx ──► gateway ──► order-service ──► user-service
│                       │ 发消息      │                │
├── RabbitMQ ◄──────────┘ order.created │ 消费          │
├── MySQL (StatefulSet+PVC)     └─ Redis └──────────────┘
├── SkyWalking（initContainer 注入 java agent，链路追踪）
├── EFK（Filebeat DaemonSet → ES StatefulSet → Kibana，日志检索）
└── Prometheus + Grafana（Phase 5）
```

## 业务链路

`POST /api/order/orders` → order-service 查 Redis 缓存（未命中 Feign 调 user-service 并回填）→ 订单写 Redis → 发 `order.created` 消息 → user-service 异步消费（积分 +10）。
三种服务间通信方式一单覆盖：**同步 Feign / 缓存 Redis / 异步 RabbitMQ**。

## 技术栈

Java 17 · Spring Boot 3.2 · Spring Cloud 2023（Gateway/OpenFeign）· MySQL 8 · Redis 7 · RabbitMQ 3 · SkyWalking 9 · kind · Helm · GitLab CI · RKE2 · Rancher · MetalLB · Harbor · NFS · cert-manager · ArgoCD · Velero · Alertmanager

## 文档导航（按顺序执行）

| 文档 | 内容 | 耗时 |
|---|---|---|
| [docs/design.md](docs/design.md) | 设计文档：架构、选型、知识点覆盖清单 | 阅读 |
| **第一阶段：本地 kind 实战** | | |
| [docs/00-环境搭建.md](docs/00-环境搭建.md) | 从零装 WSL2/Docker/kind/kubectl/helm/ingress/metrics-server | ~45 分钟 |
| [docs/01-部署手册.md](docs/01-部署手册.md) | 手动全链路：构建→推送→部署→验证→滚动/回滚实验 | ~40 分钟 |
| [docs/02-GitLab-CICD.md](docs/02-GitLab-CICD.md) | GitLab CE + Runner 全自动发布 | ~40 分钟 |
| [docs/03-进阶手册.md](docs/03-进阶手册.md) | Helm / HPA / RBAC / NetworkPolicy / SkyWalking / 监控 | 按需 |
| **第二阶段：真实自建集群（Rancher）** | | |
| [docs/04-自建集群-单机.md](docs/04-自建集群-单机.md) | RKE2 装集群 + Rancher 管理面 + 纳管（真机） | ~90 分钟 |
| [docs/05-单机生产化.md](docs/05-单机生产化.md) | MetalLB / NFS / Harbor / cert-manager + k8s-demo 全量部署 | ~2~3 小时 |
| [docs/06-扩展高可用-3节点.md](docs/06-扩展高可用-3节点.md) | 3 节点高可用 + keepalived VIP + 故障演练 | ~2 小时 |
| [docs/07-运维配套.md](docs/07-运维配套.md) | ArgoCD GitOps / Velero 备份恢复 / Alertmanager 告警 | ~2~3 小时 |
| [docs/08-离线内网安装.md](docs/08-离线内网安装.md) | 无外网环境全套离线部署（政企内网交付） | 按需 |

## 目录结构

```
├── gateway-service/    # Spring Cloud Gateway（/api/user、/api/order 路由）
├── user-service/       # JPA + MySQL + MQ 消费者（端口 8080）
├── order-service/      # Redis + Feign + MQ 生产者（端口 8081）
├── docker/             # 三个多阶段 Dockerfile
├── k8s/
│   ├── raw/            # 手写 YAML 全套（编号即部署顺序，教学主战场）
│   └── helm/k8s-demo/  # Helm chart（与 raw 等价、参数化）
├── .gitlab-ci.yml      # 三段流水线：build → docker-image → deploy
└── docs/               # 九份手册（第一阶段 00~03 + 第二阶段 04~08）+ 设计文档
```

## 部署覆盖的知识点

Deployment 滚动更新/回滚 · StatefulSet+Headless+PVC 模板 · Deployment+PVC 对比 · DaemonSet（Filebeat）· ConfigMap/Secret · 探针三件套 · 优雅停机（preStop+graceful shutdown）· HPA · InitContainer 启动依赖 · initContainer 注入 java agent（不改镜像）· Service DNS 服务发现（替代 Nacos）· Ingress · RBAC · NetworkPolicy · Helm · GitLab CI/CD · EFK 日志采集全链路 · 资源 requests/limits · 镜像多阶段构建 · **第二阶段（真实自建）**：RKE2 集群安装 · Rancher 纳管 · MetalLB 裸金属负载均衡 · NFS 共享存储 · Harbor 镜像仓库 · cert-manager 证书自动化 · keepalived VIP 高可用 · etcd 奇数仲裁 · ArgoCD GitOps · Velero 备份恢复 · Alertmanager 告警 · 离线内网部署
