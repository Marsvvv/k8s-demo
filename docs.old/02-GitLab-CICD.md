# 02-GitLab-CICD：自建 GitLab + Runner 全自动发布

> 前置：01 手册已跑通（集群里有完整应用）
> 目标：git push 后 GitLab Runner 自动完成 编译 → 打镜像 → 滚动发布
> 总耗时：约 30~40 分钟（GitLab 镜像约 1.5G + 首次启动 5 分钟）

---

## 1. 部署 GitLab CE（WSL2 终端）

```bash
# 用 8929/2224 端口避免和 Windows 常用端口冲突
docker run -d \
  --name gitlab \
  --restart always \
  --publish 8929:8929 \
  --publish 2224:22 \
  --volume gitlab-config:/etc/gitlab \
  --volume gitlab-logs:/var/log/gitlab \
  --volume gitlab-data:/var/opt/gitlab \
  --hostname localhost \
  --shm-size 256m \
  gitlab/gitlab-ce:17.5.1-ce.0
```

> - 首次启动 5~10 分钟（初始化数据库），期间页面 502 属正常
> - 观察启动：`docker logs -f gitlab` 看到 "GitLab is ready" 即完成

## 2. 首次登录 + 建项目 + 建 Token

```bash
# 获取 root 初始密码（保存 24 小时，尽快改）
docker exec gitlab grep 'Password:' /etc/gitlab/initial_root_password
```

1. 浏览器打开 `http://localhost:8929`，root + 上面的密码登录
2. 左上角 **Create a project → Create blank project**，名字填 `k8s-demo`，可见性 Private，创建
3. 右上角头像 → **Edit profile → Access Tokens**（或个人项目 Settings → Access Tokens）：
   - 名称 `push-token`，勾选 `write_repository`，创建后**复制 token**（只显示一次）
   - 之后 push 用 `root:<token>` 认证，不用每次输密码

## 3. 把项目推送到 GitLab

```bash
cd ~/k8s-demo
git init -b main 2>/dev/null || git checkout -b main
git add .
git commit -m "init: k8s 云原生实战项目"

# 用 token 推送（把 <你的token> 替换为上一步复制的）
git remote add origin http://root:<你的token>@localhost:8929/root/k8s-demo.git
git push -u origin main
```

> 坑：`git commit` 若提示配置身份，先执行：
> `git config --global user.name "你的名字" && git config --global user.email "you@example.com"`

## 4. 安装并注册 GitLab Runner（WSL2 终端）

```bash
# 4.1 装 runner（官方源）
curl -L https://packages.gitlab.com/install/repositories/runner/gitlab-runner/script.deb.sh | sudo bash
sudo apt install -y gitlab-runner

# 4.2 runner 要能调 docker 和 kubectl：加入 docker 组 + 复制 kubeconfig
sudo usermod -aG docker gitlab-runner
sudo mkdir -p /home/gitlab-runner/.kube
sudo cp ~/.kube/config /home/gitlab-runner/.kube/config
sudo chown -R gitlab-runner:gitlab-runner /home/gitlab-runner/.kube
sudo service gitlab-runner restart

# 4.3 注册：URL 填 http://localhost:8929，token 去 GitLab 页面拿
#     项目 → Settings → CI/CD → Runners → New project runner（创建后复制 token）
sudo gitlab-runner register --url http://localhost:8929 --token <runner的token>
# 交互式问答全部回车用默认值，executor 输入 shell，tag 输入 k8s-demo-runner
```

注册后刷新 Runners 页面，应看到绿灯 runner 在线。

> 坑：runner 执行 docker 时若报 permission denied，确认 `sudo usermod -aG docker gitlab-runner` 已执行且重启了 runner 服务；shell executor 的每次 job 都以 gitlab-runner 用户运行。

## 5. 触发流水线

```bash
cd ~/k8s-demo
# 随便改点东西（比如 README 加一行）
echo "" >> README.md
git add . && git commit -m "trigger: 第一次 CI 流水线" && git push
```

浏览器打开项目 → **Build → Pipelines**，观察三个阶段依次变绿：

| 阶段 | 干什么 | 多久 |
|---|---|---|
| build | mvn 编译打包（快速失败反馈） | 1~3 分钟 |
| docker-image | 三个镜像多阶段构建 + push | 2~5 分钟 |
| deploy | set image 滚动更新 + rollout status 等待 | 1~2 分钟 |

```bash
# 流水线跑完后验证集群里的镜像已更新（标签应是 commit 短哈希）
kubectl -n k8s-demo get deployment -o wide | grep image
# 或者：
kubectl -n k8s-demo describe deployment/order-service | grep Image
```

## 6. 全自动发布验证（核心成就感时刻）

```bash
# 改一行业务代码 → push → 全自动上线
# 例如改 user-service 的日志文案，push 后在 Pipelines 页看着它自己发版
```

## 7. 常见坑速查

| 现象 | 解决 |
|---|---|
| 流水线一直 pending | runner 没上线/没 tag 匹配：回 4.3 检查；项目 Settings→CI/CD→Runners 确认可用 |
| build 阶段报 mvn 找不到 | runner 用 shell executor 但 PATH 没 mvn：`sudo ln -s /usr/share/maven/bin/mvn /usr/local/bin/mvn`（或确认 apt 装的 maven 路径） |
| docker-image 阶段 push 报拒绝 | runner 用户无 docker 权限：回 4.2 的 usermod + 重启 |
| deploy 阶段 kubectl 报权限 | kubeconfig 没复制/属主不对：回 4.2 |
| GitLab 内存吃紧（32G 机器一般无感） | `docker stats gitlab` 观察；可加 `GITLAB_OMNIBUS_CONFIG` 调低 puma/unicorn worker |
| 改完 push 没触发 | `.gitlab-ci.yml` 在根目录且文件名拼写正确；项目已 Enable CI |
