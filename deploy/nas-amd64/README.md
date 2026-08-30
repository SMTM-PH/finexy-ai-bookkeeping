# Finexy NAS AMD64 部署包

本目录使用 Docker Hub 公开镜像，适用于 AMD64 / x86_64 的群晖、威联通和其他 Docker NAS。

## 启动

```bash
cp .env.example .env
openssl rand -hex 32
```

将生成结果填入 `.env` 的 `APP_SECRET_KEY`，并把 `APP_DOMAIN` 改为 NAS IP。需要 DeepSeek 时填写 API Key，并将 `ENABLE_AI_TEXT_RECOGNITION` 改为 `true`。

```bash
mkdir -p data log storage
docker compose pull
docker compose up -d
docker compose ps
```

浏览器访问 `http://NAS-IP:8080/`。完整说明见仓库的 [NAS 公开镜像部署指南](../../docs/NAS_PUBLIC_IMAGE_DEPLOYMENT.md)。

离线镜像包用户先运行：

```bash
docker load -i finexy-nas-amd64.tar
```

正式发布的离线包文件名为 `Finexy-NAS-1.7.3-linux-amd64.tar`，其中已包含
`ph97/finexy-bookkeeping:1.7.3-amd64` 和
`ph97/finexy-bookkeeping:ocr-1.0-amd64`。加载后可直接使用同目录的
`compose.yaml` 启动，无需修改镜像名称。
