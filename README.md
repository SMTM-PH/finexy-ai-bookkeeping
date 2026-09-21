# Finexy AI Bookkeeping

[![GitHub](https://img.shields.io/badge/GitHub-Public-2ea44f?logo=github)](https://github.com/SMTM-PH/finexy-ai-bookkeeping)
[![Releases](https://img.shields.io/badge/GitHub-Releases-8250df?logo=github)](https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases)
[![Docker Image](https://img.shields.io/badge/Docker%20Hub-ph97%2Ffinexy--bookkeeping-2496ed?logo=docker)](https://hub.docker.com/r/ph97/finexy-bookkeeping)
[![CI](https://github.com/SMTM-PH/finexy-ai-bookkeeping/actions/workflows/ci.yml/badge.svg)](https://github.com/SMTM-PH/finexy-ai-bookkeeping/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-AMD64-blue)](#部署要求)

Finexy 是以**账本**为中心的自托管财务管理系统。你可以为个人生活、家庭共同开支或独立目标创建不同账本，在账本之间快速切换，并邀请其他成员一起记录。

数据保存在自己的服务中。Web、PWA、Windows 客户端和 Android App 使用同一套账户、账本与同步体系；AI 和 OCR 是可选能力，不影响基础记账。

## 产品界面

### 当前账本总览

![Finexy 当前账本总览](docs/screenshots/finexy-dashboard.png)

### 登录界面

![Finexy 登录界面](docs/screenshots/finexy-login.png)

## 你可以用 Finexy 做什么

### 管理不同账本

- 单人也可以创建多个独立账本
- 顶部快速切换当前账本，余额、流水、账户和目标随账本切换
- “个人账本”和“家庭共享”是创建模板，创建后使用相同的账本规则
- 每个账本拥有独立的账户、流水、成员和数据范围

### 与成员共同记账

- 通过一次性邀请码邀请成员
- 加入前先核对账本、邀请人和权限
- 支持所有者、管理员、普通成员和只读成员
- 在账本详情中管理成员、角色、邀请和删除影响

### 完成日常财务闭环

- 记录收入、支出、账户间转账和余额调整
- 管理账户、分类、标签、模板与多币种汇率
- 使用日期、类型、账户、分类和标签组合筛选
- 查看月度收支、趋势、统计，并按当前筛选导出 CSV

### 为目标和固定收支做计划

- 在当前账本中创建存钱目标并记录存入、取出
- 目标资金流不计入日常收入或支出
- 设置周期交易；到期后进入待确认队列，确认后才会入账
- 设置月度预算并查看进度

### 可选的 AI 与 OCR

- 使用自然语言生成记账草稿
- 在本地 OCR 服务中识别中文票据
- 识别结果先进入待复核流程，由用户确认后入账
- 可连接 DeepSeek，也可以只使用基础财务功能

### 自己掌握数据

- 使用 SQLite 持久化，并提供备份与恢复能力
- 可通过 Docker Compose 部署到服务器或 AMD64 NAS
- 基础记账无需依赖第三方云端财务服务
- MCP 接口可连接 Codex 等兼容客户端

## 支持的平台

| 平台 | 说明 |
| --- | --- |
| Web / PWA | 完整桌面工作台，可通过浏览器访问或安装为 PWA |
| Windows | Electron 安装版与便携版，连接自托管服务 |
| Android | 原生 Kotlin + Jetpack Compose App，当前提供测试版 APK |
| Docker / NAS | AMD64 镜像，支持 Docker Compose、群晖和威联通 |
| MCP | 可连接 Codex 等支持 MCP 的客户端进行记账和查询 |

## 开始使用

1. 部署 Finexy 并注册账号。
2. 在默认个人账本中添加第一个账户，或新建一个账本。
3. 需要共同记账时，在账本详情生成邀请码并发送给成员。
4. 回到首页记录流水、查看本月收支，也可以设置存钱目标和周期计划。

## 下载

可直接使用的文件发布在 [**GitHub Releases**](https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases)：

| 发布文件 | 用途 |
| --- | --- |
| `Finexy-Windows-1.9.0-x64-Setup.exe` | Windows x64 安装版 |
| `Finexy-Windows-1.9.0-x64-Portable.exe` | Windows x64 便携版 |
| `Finexy-NAS-1.9.0-amd64-deploy.zip` | NAS 在线部署配置包 |
| `Finexy-NAS-1.9.0-linux-amd64.tar` | Finexy 与 OCR 的 AMD64 Docker 离线镜像 |
| `Finexy-Android-1.0.0-debug.apk` | Android 测试版（调试签名，客户端自身版本号） |
| `SHA256SUMS.txt` | 发布文件完整性校验值 |

Windows 包当前未进行代码签名，首次运行时 SmartScreen 可能显示提示。Android APK 使用调试签名，请只从本仓库 Releases 下载并核对 SHA-256；正式发布签名将在后续版本提供。

## 部署要求

| 项目 | 要求 |
| --- | --- |
| Docker 镜像架构 | AMD64 / x86_64 |
| NAS 内存 | 建议 8 GB 或以上；启用 OCR 时至少预留约 5 GB |
| Docker | Docker Engine 与 Docker Compose |
| 默认端口 | `8080` |
| AI 服务 | 可选 DeepSeek API Key |

## 使用 Docker Hub 镜像

公开镜像仓库：[**ph97/finexy-bookkeeping**](https://hub.docker.com/r/ph97/finexy-bookkeeping)

Docker Hub 网页搜索时请使用包含命名空间的完整名称 `ph97/finexy-bookkeeping`。公开镜像无需登录即可拉取：

```bash
docker pull ph97/finexy-bookkeeping:1.9.0-amd64
docker pull ph97/finexy-bookkeeping:latest-amd64
docker pull ph97/finexy-bookkeeping:ocr-1.0-amd64
```

部署资料：

- [NAS AMD64 Compose 配置](deploy/nas-amd64/README.md)
- [NAS 公开镜像部署指南](docs/NAS_PUBLIC_IMAGE_DEPLOYMENT.md)
- [DeepSeek 配置说明](docs/DEEPSEEK_CONFIG.md)

## 从源码运行

```bash
cp .env.example .env
docker compose up -d --build
```

启动后访问 `http://服务器IP:8080/`。真实 `.env`、API Key、数据库、日志和备份不得提交到 Git。

## 构建 Windows 客户端

```powershell
npm ci
npm run desktop:build
```

构建结果位于 `release/windows/`。Windows 客户端连接已部署的 Finexy 服务。

## 开发与协作

所有变更通过功能分支和 Pull Request 合并，`main` 禁止直接强推和删除。

```bash
npm ci
npm run check
go test ./...
```

- [贡献指南](CONTRIBUTING.md)
- [分支策略](CONTRIBUTING.md#分支策略)
- [安全政策](SECURITY.md)
- [行为准则](CODE_OF_CONDUCT.md)
- [AI / 开发者必读项目手册](AGENTS.md)
- [Web / Android UI/UX 设计基线](docs/UI_UX_DESIGN.md)

## 开源许可

本项目基于 [MIT License](LICENSE) 开源。
