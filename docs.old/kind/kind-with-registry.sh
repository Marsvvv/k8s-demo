#!/bin/sh
# ============================================================
# kind-with-registry.sh — 创建带内建镜像仓库的多节点 kind 集群
# （基于 kind 官方脚本，注释为教学添加）
#
# 效果：
#   - WSL2 宿主机：docker push localhost:5000/... 直接进仓库
#   - 集群内 Pod：拉 localhost:5000/... 时自动路由到该仓库
# ============================================================
set -o errexit

reg_name='kind-registry'
reg_port='5000'

# 1) 创建 registry 容器（已存在则跳过）
if [ "$(docker inspect -f '{{.State.Running}}' "${reg_name}" 2>/dev/null || true)" != 'true' ]; then
  docker run \
    -d --restart=always -p "127.0.0.1:${reg_port}:5000" --network bridge --name "${reg_name}" \
    registry:2
  echo "registry 容器已创建: ${reg_name}"
fi

# 2) 创建 3 节点集群（1 控制面 + 2 工作节点）
#    containerdConfigPatches 开启 registry 配置目录（让节点信任本地 HTTP 仓库）
cat <<EOF | kind create cluster --name kind --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
  - role: worker
  - role: worker
containerdConfigPatches:
- |-
  [plugins."io.containerd.grpc.v1.cri".registry]
    config_path = "/etc/containerd/certs.d"
EOF

# 3) 每个节点写入 hosts.toml：
#    节点内访问 localhost:5000 → 转发到 kind-registry 容器
REGISTRY_DIR="/etc/containerd/certs.d/localhost:${reg_port}"
for node in $(kind get nodes); do
  docker exec "${node}" mkdir -p "${REGISTRY_DIR}"
  cat <<EOF | docker exec -i "${node}" cp /dev/stdin "${REGISTRY_DIR}/hosts.toml"
[host."http://${reg_name}:5000"]
  capabilities = ["pull", "resolve"]
EOF
done

# 4) registry 接入集群网络（各节点才能路由到它）
if [ "$(docker inspect -f='{{json .NetworkSettings.Networks.kind}}' "${reg_name}")" = 'null' ]; then
  docker network connect "kind" "${reg_name}"
fi

echo "================ 完成 ================"
echo "本地仓库: localhost:${reg_port}"
echo "集群节点: $(kind get nodes | tr '\n' ' ')"
