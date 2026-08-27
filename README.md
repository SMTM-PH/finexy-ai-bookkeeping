# Finexy AI Bookkeeping

[![GitHub](https://img.shields.io/badge/GitHub-Public-2ea44f?logo=github)](https://github.com/SMTM-PH/finexy-ai-bookkeeping)
[![Releases](https://img.shields.io/badge/GitHub-Releases-8250df?logo=github)](https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases)
[![Docker Image](https://img.shields.io/badge/Docker%20Hub-ph97%2Ffinexy--bookkeeping-2496ed?logo=docker)](https://hub.docker.com/r/ph97/finexy-bookkeeping)
[![CI](https://github.com/SMTM-PH/finexy-ai-bookkeeping/actions/workflows/ci.yml/badge.svg)](https://github.com/SMTM-PH/finexy-ai-bookkeeping/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-AMD64-blue)](#部署要求)

Finexy 是独立维护的自托管 AI 个人财务管理项目，提供 Web、PWA、Windows 桌面客户端、Docker 和 NAS 部署方式。项目将记账、预算、资产、AI 分析、本地 OCR 和自动化能力整合到统一工作台中。

## 软件截图

### 财务工作台

![Finexy 财务工作台](docs/screenshots/finexy-dashboard.png)

### 登录界面

![Finexy 登录界面](docs/screenshots/finexy-login.png)

## 主要功能

- 收入、支出、转账、账户、分类、标签与周期交易
- AI 文本记账、财务报告与待复核项目
- 中文票据本地 OCR 与大模型结构化
- 月度预算、资产清单、统计图表与桌面财务工作台
- SQLite 数据持久化、完整备份与恢复
- MCP 接口，可连接 Codex 等支持 MCP 的客户端
- Docker Compose、群晖、威联通和其他 AMD64 NAS 部署

## 下载

可直接使用的文件发布在 [**GitHub Releases**](https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases)：

| 发布文件 | 用途 |
| --- | --- |
| `Finexy-Windows-1.7.0-x64-Setup.exe` | Windows x64 安装版 |
| `Finexy-Windows-1.7.0-x64-Portable.exe` | Windows x64 便携版 |
| `Finexy-NAS-1.7.0-amd64-deploy.zip` | NAS 在线部署配置包 |
| `Finexy-NAS-1.7.0-linux-amd64.tar` | Finexy 与 OCR 的 AMD64 Docker 离线镜像 |
| `SHA256SUMS.txt` | 发布文件完整性校验值 |

Windows 包当前未进行代码签名，首次运行时 SmartScreen 可能显示提示。请只从本仓库 Releases 下载并核对 SHA-256。

## 部署要求

| 项目 | 要求 |
| --- | --- |
| Docker 镜像架构 | AMD64 / x86_64 |
| NAS 内存 | 建议 8 GB 或以上；启用 OCR 时至少预留约 5 GB |
| Docker | Docker Engine 与 Docker Compose |
| 默认端口 | `8080` |
| AI 服务 | 可选 DeepSeek API Key |

## Docker Hub

公开镜像仓库：[**ph97/finexy-bookkeeping**](https://hub.docker.com/r/ph97/finexy-bookkeeping)

Docker Hub 网页搜索时请使用包含命名空间的完整名称 `ph97/finexy-bookkeeping`，并关闭 Official Images、Verified Publisher 或 Trusted Content 等筛选条件。也可以通过命令行验证：

```bash
docker search ph97/finexy-bookkeeping
```

公开镜像无需登录即可拉取：

```bash
docker pull ph97/finexy-bookkeeping:1.7.3-amd64
docker pull ph97/finexy-bookkeeping:latest-amd64
docker pull ph97/finexy-bookkeeping:ocr-1.0-amd64
```

部署资料：

- [NAS AMD64 可直接使用的 Compose](deploy/nas-amd64/README.md)
- [NAS 公开镜像部署指南](docs/NAS_PUBLIC_IMAGE_DEPLOYMENT.md)
- [DeepSeek 配置说明](docs/DEEPSEEK_CONFIG.md)

## 从源码运行

```bash
cp .env.example .env
docker compose up -d --build
```

启动后访问 `http://服务器IP:8080/`。真实 `.env`、API Key、数据库、日志和备份不得提交到 Git。

## Windows 桌面客户端

```powershell
npm ci
npm run desktop:build
```

构建结果位于 `release/windows/`。客户端连接到已部署的 Finexy 服务。

## 开发与协作

所有变更通过功能分支和 Pull Request 合并，`main` 禁止直接强推和删除。

```bash
npm ci
npm run check
go test ./...
```

- [贡献指南](CONTRIBUTING.md)
- [安全政策](SECURITY.md)
- [行为准则](CODE_OF_CONDUCT.md)
- [项目文档](docs/AI_BOOKKEEPING_MVP.md)

## 开源许可

本项目基于 [MIT License](LICENSE) 开源。
