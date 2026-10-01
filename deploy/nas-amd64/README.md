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
docker load -i Finexy-NAS-1.9.2-linux-amd64.tar
```

本版本的镜像包包含 `ph97/finexy-bookkeeping:1.9.2-amd64` 记账服务。
OCR 镜像沿用 `ph97/finexy-bookkeeping:ocr-1.0-amd64`，需要单独拉取；
完全离线部署时，请同时准备 OCR 镜像包。加载后可使用同目录的 `compose.yaml` 启动。

## 从旧版本升级

先备份现有 `data/`、`storage/`、`.env`，保持密钥和挂载路径不变，
将记账镜像更新为 `1.9.2-amd64` 后执行：

```bash
docker compose pull bookkeeping
docker compose up -d --no-deps bookkeeping
docker compose ps
```

如果旧容器没有挂载数据目录，必须先迁出容器内的账本和附件，再重建服务。

## 导入支付宝与微信

登录默认个人账本，进入“流水”，点击“导入支付宝”或“导入微信”。
支付宝支持 ZIP/CSV（ZIP 密码只在本地解压时使用），微信支持 XLSX/CSV。
预览后显式选择账户和分类，勾选记录并确认导入。同一账单重复导入会产生重复流水。
