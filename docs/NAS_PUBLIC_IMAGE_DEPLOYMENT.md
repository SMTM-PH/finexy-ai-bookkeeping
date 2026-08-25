# Finexy NAS 公开镜像部署指南

本文档用于在 AMD64 NAS 上，从 Docker Hub 公开仓库下载并部署 Finexy。适用于群晖 Container Manager、威联通 Container Station，以及支持 Docker Compose 的其他 NAS。

## 1. 部署要求

- NAS CPU 架构：AMD64 / x86_64
- 建议内存：8 GB 或以上
- Docker 与 Docker Compose
- 可访问 Docker Hub
- 可匿名访问 Docker Hub 公开仓库 `ph97/finexy-bookkeeping`
- 可选：DeepSeek API Key，用于自然语言记账和 OCR 文本结构化

通过 SSH 检查 NAS 架构：

```bash
uname -m
```

输出应为 `x86_64`。当前镜像不适用于 ARM64 NAS。

## 2. 使用的公开镜像

| 服务 | 公开镜像 |
| --- | --- |
| Finexy 记账服务 | `ph97/finexy-bookkeeping:1.7.3-amd64` |
| 本地 OCR 服务 | `ph97/finexy-bookkeeping:ocr-1.0-amd64` |

两个镜像位于同一个公开仓库，通过不同标签区分，任何人都可以直接拉取。

## 3. 创建部署目录

群晖示例：

```bash
mkdir -p /volume1/docker/finexy/data
mkdir -p /volume1/docker/finexy/log
mkdir -p /volume1/docker/finexy/storage
cd /volume1/docker/finexy
```

威联通可使用类似路径：

```text
/share/Container/finexy
```

如果容器提示目录无写入权限，可将数据目录交给容器用户 UID 1000：

```bash
chown -R 1000:1000 data log storage
```

## 4. 验证 Docker Hub 访问

公开镜像不需要执行 `docker login`。在 NAS SSH 中验证镜像是否可读取：

```bash
docker manifest inspect ph97/finexy-bookkeeping:1.7.3-amd64 >/dev/null
docker manifest inspect ph97/finexy-bookkeeping:ocr-1.0-amd64 >/dev/null
```

两条命令都没有报错即可继续。

## 5. 创建环境变量文件

在部署目录创建 `.env`：

```env
APP_PORT=8080
APP_DOMAIN=192.168.1.10
APP_SECRET_KEY=替换为随机生成的长密钥
DEEPSEEK_API_KEY=替换为你的DeepSeek密钥
DEEPSEEK_MODEL=deepseek-v4-flash
```

说明：

- `APP_DOMAIN` 改为 NAS 的局域网 IP 或局域网域名。
- `APP_SECRET_KEY` 一经投入使用应长期保存。更换它可能导致现有登录状态和加密数据不可用。
- 当前 Compose 要求提供 `DEEPSEEK_API_KEY`。不要把真实密钥提交到 Git。

生成随机密钥：

```bash
openssl rand -hex 32
```

限制环境文件权限：

```bash
chmod 600 .env
```

## 6. 创建 Compose 文件

在同一目录创建 `compose.yaml`：

```yaml
services:
  bookkeeping:
    image: ph97/finexy-bookkeeping:1.7.3-amd64
    platform: linux/amd64
    restart: unless-stopped
    ports:
      - "${APP_PORT:-8080}:8080"
    environment:
      TZ: Asia/Shanghai
      EBK_SERVER_DOMAIN: "${APP_DOMAIN:-localhost}"
      EBK_SERVER_ROOT_URL: "http://${APP_DOMAIN:-localhost}:${APP_PORT:-8080}/"
      EBK_SECURITY_SECRET_KEY: "${APP_SECRET_KEY:?请在.env中设置APP_SECRET_KEY}"
      EBK_LLM_TRANSACTION_FROM_AI_TEXT_RECOGNITION: "true"
      EBK_LLM_TEXT_RECOGNITION_LLM_PROVIDER: openai_compatible
      EBK_LLM_TEXT_RECOGNITION_OPENAI_COMPATIBLE_BASE_URL: https://api.deepseek.com
      EBK_LLM_TEXT_RECOGNITION_OPENAI_COMPATIBLE_API_KEY: "${DEEPSEEK_API_KEY:?请在.env中设置DEEPSEEK_API_KEY}"
      EBK_LLM_TEXT_RECOGNITION_OPENAI_COMPATIBLE_MODEL_ID: "${DEEPSEEK_MODEL:-deepseek-v4-flash}"
      EBK_LLM_TEXT_RECOGNITION_REQUEST_TIMEOUT: "120000"
      EBK_OCR_SERVER_URL: http://ocr:8000
      EBK_OCR_REQUEST_TIMEOUT: "120000"
      EBK_MCP_ENABLE_MCP: "true"
    volumes:
      - ./data:/ezbookkeeping/data
      - ./log:/ezbookkeeping/log
      - ./storage:/ezbookkeeping/storage
    depends_on:
      ocr:
        condition: service_healthy
    mem_limit: 1536m
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "-", "http://127.0.0.1:8080/healthz.json"]
      interval: 30s
      timeout: 5s
      retries: 5

  ocr:
    image: ph97/finexy-bookkeeping:ocr-1.0-amd64
    platform: linux/amd64
    restart: unless-stopped
    environment:
      TZ: Asia/Shanghai
    volumes:
      - ocr-models:/root/.paddlex
    mem_limit: 5g
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=5)"]
      interval: 30s
      timeout: 10s
      retries: 10
      start_period: 180s

volumes:
  ocr-models:
```

OCR 服务没有映射 NAS 端口，只能被 Compose 内部网络访问。

## 7. 下载并启动

```bash
cd /volume1/docker/finexy
docker compose pull
docker compose up -d
```

旧版 NAS 使用：

```bash
docker-compose pull
docker-compose up -d
```

第一次启动 OCR 时会下载中文 OCR 模型，可能需要数分钟。查看进度：

```bash
docker compose logs -f ocr bookkeeping
```

按 `Ctrl+C` 只会退出日志查看，不会停止容器。

## 8. 验证部署

查看容器：

```bash
docker compose ps
```

两个服务最终都应显示 `healthy`。

检查后端：

```bash
curl http://127.0.0.1:8080/healthz.json
```

正常响应包含：

```json
{"success":true}
```

浏览器访问：

```text
http://192.168.1.10:8080/
```

将地址中的 IP 替换为 `.env` 中的 `APP_DOMAIN`。

## 9. 群晖 Container Manager

1. 安装并打开 **Container Manager**。
2. 进入 **项目**，选择 **新增**。
3. 项目路径选择 `/volume1/docker/finexy`。
4. 使用现有 `compose.yaml` 创建项目。
5. 确认 `.env` 位于同一目录。
6. 启动项目并等待两个容器变为健康。

本仓库为公开仓库，群晖注册表凭据可以留空。

## 10. 威联通 Container Station

1. 在 Container Station 中选择创建应用或导入 Compose。
2. 导入本指南中的 `compose.yaml`。
3. 把应用目录设置为 `/share/Container/finexy`。
4. 启动并检查容器健康状态。

## 11. 更新镜像

发布新标签后，先修改 `compose.yaml` 中的镜像标签，然后执行：

```bash
docker compose pull
docker compose up -d
docker image prune
```

不要使用浮动的 `latest` 标签。固定版本标签更容易回滚。

## 12. 备份与恢复

必须备份：

- `data/`：SQLite 数据库
- `storage/`：附件和应用文件
- `.env`：应用密钥与 API 配置

可选备份：

- `log/`：运行日志

建议备份前短暂停止服务：

```bash
docker compose stop bookkeeping
```

备份完成后启动：

```bash
docker compose start bookkeeping
```

不要执行 `docker compose down -v`，否则会删除 Docker 管理的 OCR 模型卷。金融数据使用绑定目录保存，但仍应避免不必要的卷删除操作。

## 13. 配置 HTTPS

不建议把 NAS 的 8080 端口直接暴露到公网。推荐使用 NAS 自带的反向代理和证书：

```text
https://finance.example.com -> http://127.0.0.1:8080
```

启用 HTTPS 后，将 Compose 中的服务器根地址改为：

```yaml
EBK_SERVER_ROOT_URL: "https://finance.example.com/"
```

然后重新创建容器：

```bash
docker compose up -d
```

## 14. Windows App 连接 NAS

Windows App 默认连接本机 `http://127.0.0.1:8080`。在客户端服务器设置中改为：

```text
http://192.168.1.10:8080
```

如果 NAS 已配置 HTTPS，则使用：

```text
https://finance.example.com
```

## 15. 常见问题

### 拉取镜像提示 denied 或超时

```bash
docker logout
docker compose pull
```

该仓库允许匿名下载。若仍失败，请检查 NAS 的 DNS、系统时间、防火墙及 Docker Hub 网络连接。

### OCR 长时间 starting

```bash
docker compose logs --tail=200 ocr
docker stats
```

首次启动需要下载模型。确认 NAS 能访问模型下载源，并有足够内存与磁盘空间。

### bookkeeping 无法启动

```bash
docker compose logs --tail=200 bookkeeping
```

重点检查 `.env` 中的 `APP_SECRET_KEY`、`DEEPSEEK_API_KEY` 和目录写入权限。

### 页面无法从其他设备访问

检查 NAS 防火墙是否允许 TCP 8080，并确认：

```bash
docker compose ps
curl http://127.0.0.1:8080/healthz.json
```
