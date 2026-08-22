# Docker 镜像上传脚本设计

## 目标

提供一个最小化 Shell 脚本，由用户手动把当前已经构建并验证的后端镜像推送到 Docker Hub。

## 范围

- 脚本路径：`scripts/push-bitpongoapi.sh`。
- 依次推送 `docker.io/corbettzhang/bitpongoapi:3a493c7` 和 `docker.io/corbettzhang/bitpongoapi:latest`。
- 任意一次推送失败时立即返回非零状态，不掩盖失败。
- 依赖用户事先执行 `docker login`，脚本不读取、输出或保存凭据。
- 脚本不构建或重新标记镜像，不修改 Docker Desktop、系统或 Git 代理配置。

## 使用方式

```bash
./scripts/push-bitpongoapi.sh
```

## 验证

- 使用 `bash -n` 检查脚本语法。
- 使用非执行式命令检查脚本包含两个预期标签，且没有凭据或代理配置操作。
- 实际上传由用户手动执行。
