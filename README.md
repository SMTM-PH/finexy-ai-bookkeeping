# Finexy AI Bookkeeping

[![GitHub](https://img.shields.io/badge/GitHub-Public-2ea44f?logo=github)](https://github.com/SMTM-PH/finexy-ai-bookkeeping)
[![Releases](https://img.shields.io/badge/GitHub-Releases-8250df?logo=github)](https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases)
[![Docker Image](https://img.shields.io/badge/Docker%20Hub-ph97%2Ffinexy--bookkeeping-2496ed?logo=docker)](https://hub.docker.com/r/ph97/finexy-bookkeeping)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-AMD64-blue)](#部署要求)

Finexy 是基于 [ezBookkeeping](https://github.com/mayswind/ezbookkeeping) 二次开发的自托管 AI 个人财务管理应用。本仓库增加了 Finexy 桌面工作台、AI 报告与复核、月度预算、资产清单、本地 OCR、完整备份恢复、Windows 桌面客户端，以及 Docker/NAS 部署支持。

> 本项目为公开源码仓库，任何人均可查看和克隆。当前发布到 Docker Hub 的镜像仅支持 `linux/amd64`，不适用于 ARM64 NAS。

## 主要功能

- 日常收入、支出、转账、账户、分类、标签及周期交易管理
- AI 文本记账、AI 财务报告和待复核项目
- 本地 OCR 服务，可识别中文票据并交给大模型结构化
- 月度预算、资产清单、统计图表及桌面财务工作台
- SQLite 数据持久化及完整备份/恢复
- MCP 接口，可连接 Codex 等支持 MCP 的客户端
- Web、PWA 和 Windows 桌面客户端
- Docker Compose、群晖、威联通及其他 AMD64 NAS 部署

## 下载与发布

版本说明、Windows 客户端和其他发行文件将发布在 [**Releases**](https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases) 页面。Docker 镜像继续通过 [Docker Hub](https://hub.docker.com/r/ph97/finexy-bookkeeping) 提供。

## 部署要求

| 项目 | 要求 |
| --- | --- |
| Docker 镜像架构 | AMD64 / x86_64 |
| NAS 内存 | 建议 8 GB 或以上；启用 OCR 时至少预留约 5 GB |
| Docker | Docker Engine 与 Docker Compose |
| 访问端口 | 默认 `8080` |
| AI 服务 | 可选 DeepSeek API Key |

## Docker 镜像

公开镜像无需登录 Docker Hub，可直接下载：

| 服务 | 镜像 |
| --- | --- |
| Finexy 主服务 | `ph97/finexy-bookkeeping:1.6.1-amd64` |
| 本地 OCR 服务 | `ph97/finexy-bookkeeping:ocr-1.0-amd64` |

```bash
docker pull ph97/finexy-bookkeeping:1.6.1-amd64
docker pull ph97/finexy-bookkeeping:ocr-1.0-amd64
```

完整的环境变量、Compose 文件、数据目录、健康检查和群晖操作步骤请阅读：

- [NAS 公开镜像部署指南](docs/NAS_PUBLIC_IMAGE_DEPLOYMENT.md)
- [NAS 通用部署说明](docs/NAS_DEPLOYMENT.md)
- [DeepSeek 配置说明](docs/DEEPSEEK_CONFIG.md)

## 从源码运行

复制环境变量示例并填写随机应用密钥。真实密钥不得提交到 Git：

```bash
cp .env.example .env
docker compose up -d --build
```

启动后访问：

```text
http://你的服务器IP:8080/
```

查看服务状态与日志：

```bash
docker compose ps
docker compose logs -f bookkeeping ocr
```

## Windows 桌面客户端

安装 Node.js 后可在 Windows PowerShell 中构建：

```powershell
npm install
npm run desktop:build
```

桌面客户端连接到已经部署的 Finexy 服务；相关 Electron 文件位于 `desktop/`。

## 数据与安全

- 首次部署必须为 `APP_SECRET_KEY` 设置长期保存的随机值，例如运行 `openssl rand -hex 32`。
- 不要把 `.env`、DeepSeek API Key、数据库文件、日志或备份上传到公开仓库。
- 建议定期备份 `data/`、`storage/` 和应用生成的完整备份文件。
- 公网访问时应使用 HTTPS 反向代理，并限制管理端口的访问范围。

## 项目文档

- [AI 记账 MVP 说明](docs/AI_BOOKKEEPING_MVP.md)
- [产品设计系统](design-system/ai-bookkeeping/MASTER.md)
- [ezBookkeeping Agent Skill](skills/ezbookkeeping/SKILL.md)
- [MCP 配置说明](skills/ezbookkeeping/references/mcp-setup.md)

## 开源说明

Finexy 基于 ezBookkeeping 开发并继续采用 [MIT License](LICENSE)。原项目版权及许可证声明予以保留。下方为上游项目的原始介绍和使用文档。

---

# ezBookkeeping（上游项目说明）
[![License](https://img.shields.io/badge/license-MIT-green.svg)](https://github.com/mayswind/ezbookkeeping/blob/master/LICENSE)
[![Latest Release](https://img.shields.io/github/release/mayswind/ezbookkeeping.svg?style=flat)](https://github.com/mayswind/ezbookkeeping/releases)
[![Latest Build](https://img.shields.io/github/actions/workflow/status/mayswind/ezbookkeeping/build-snapshot.yml?branch=main)](https://github.com/mayswind/ezbookkeeping/actions)
[![Latest Docker Image Size](https://img.shields.io/docker/image-size/mayswind/ezbookkeeping.svg?style=flat)](https://hub.docker.com/r/mayswind/ezbookkeeping)
[![Docker Pulls](https://img.shields.io/docker/pulls/mayswind/ezbookkeeping)](https://hub.docker.com/r/mayswind/ezbookkeeping)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/mayswind/ezbookkeeping)

[![Recommend By HelloGitHub](https://api.hellogithub.com/v1/widgets/recommend.svg?rid=ded5af09da574ec1811ddb154f1b2093&claim_uid=LT7EZxeBukCnh0K)](https://hellogithub.com/en/repository/mayswind/ezbookkeeping)
[![Trending](https://trendshift.io/api/badge/repositories/12917)](https://trendshift.io/repositories/12917)

## Introduction
ezBookkeeping is a lightweight, self-hosted personal finance app with a user-friendly interface and powerful bookkeeping features. It helps you record daily transactions, import data from various sources, and quickly search and filter your bills. You can analyze historical data using built-in charts or perform custom queries with your own chart dimensions to better understand spending patterns and financial trends. ezBookkeeping is easy to deploy, and you can start it with just one single Docker command. Designed to be resource-efficient, it runs smoothly on devices such as Raspberry Pi, NAS, and MicroServers.

ezBookkeeping offers tailored interfaces for both mobile and desktop devices. With support for PWA (Progressive Web Apps), you can even [add it to your mobile home screen](https://raw.githubusercontent.com/wiki/mayswind/ezbookkeeping/img/mobile/add_to_home_screen.gif) and use it like a native app.

Live Demo: [https://ezbookkeeping-demo.mayswind.net](https://ezbookkeeping-demo.mayswind.net)

## Features
- **Open Source & Self-Hosted**
    - Built for privacy and control
- **Lightweight & Fast**
    - Minimal resource usage, runs smoothly even on low-resource devices
- **Easy Installation**
    - Docker support
    - Supports SQLite, MySQL, PostgreSQL
    - Cross-platform (Windows, macOS, Linux)
    - Works on x86, amd64, ARM architectures
- **User-Friendly Interface**
    - UI optimized for both mobile and desktop
    - PWA support for native-like mobile experience
    - Dark mode
- **AI-Powered Features**
    - Receipt image recognition
    - MCP (Model Context Protocol) support for AI integration
    - Agent Skill and API command-line script tools support for AI integration
- **Powerful Bookkeeping**
    - Two-level accounts and categories
    - Image attachments for transactions
    - Location tracking with maps
    - Scheduled transactions
    - Advanced filtering, search, visualization and analysis
- **Localization & Internationalization**
    - Multi-language and multi-currency support
    - Multiple exchange rate sources with automatic updates
    - Multi-timezone support
    - Custom formats for dates, numbers and currencies
- **Security**
    - Two-factor authentication (2FA)
    - OIDC external authentication
    - Login rate limiting
    - Application lock (PIN code / WebAuthn)
- **Data Import & Export**
    - Supports CSV, OFX, QFX, QIF, IIF, Camt.052, Camt.053, MT940, GnuCash, Firefly III, Beancount and more

For a full list of features, visit the [Full Feature List](https://ezbookkeeping.mayswind.net/features/).

## Screenshots
### Desktop Version
[![ezBookkeeping](https://raw.githubusercontent.com/wiki/mayswind/ezbookkeeping/img/desktop/en.png)](https://raw.githubusercontent.com/wiki/mayswind/ezbookkeeping/img/desktop/en.png)

### Mobile Version
[![ezBookkeeping](https://raw.githubusercontent.com/wiki/mayswind/ezbookkeeping/img/mobile/en.png)](https://raw.githubusercontent.com/wiki/mayswind/ezbookkeeping/img/mobile/en.png)

## Installation
### Run with Docker
Visit [Docker Hub](https://hub.docker.com/r/mayswind/ezbookkeeping) to see all images and tags.

**Latest Release:**

    $ docker run -p8080:8080 mayswind/ezbookkeeping

**Latest Daily Build:**

    $ docker run -p8080:8080 mayswind/ezbookkeeping:latest-snapshot

### Install from Binary
Download the latest release: [https://github.com/mayswind/ezbookkeeping/releases](https://github.com/mayswind/ezbookkeeping/releases)

**Linux / macOS**

    $ ./ezbookkeeping server run

**Windows**

    > .\ezbookkeeping.exe server run

By default, ezBookkeeping listens on port 8080. You can then visit `http://{YOUR_HOST_ADDRESS}:8080/` .

### Build from Source
Make sure you have [Golang](https://golang.org/), [GCC](https://gcc.gnu.org/), [Node.js](https://nodejs.org/) and [NPM](https://www.npmjs.com/) installed. Then download the source code, and follow these steps:

**Linux / macOS**

    $ ./build.sh package -o ezbookkeeping.tar.gz

All the files will be packaged in `ezbookkeeping.tar.gz`.

**Windows**

    > .\build.bat package -o ezbookkeeping.zip

or

    PS > .\build.ps1 package -Output ezbookkeeping.zip

All the files will be packaged in `ezbookkeeping.zip`.

You can also build a Docker image. Make sure you have [Docker](https://www.docker.com/) installed, then follow these steps:

**Linux**

    $ ./build.sh docker

## Contributing
We welcome contributions of all kinds.

If you find a bug, please [submit an issue](https://github.com/mayswind/ezbookkeeping/issues) on GitHub.

If you would like to contribute code, you can fork the repository and open a pull request.

Improvements to documentation, feature suggestions, and other forms of feedback are also appreciated.

You can view existing contributors on the [Contributor Graph](https://github.com/mayswind/ezbookkeeping/graphs/contributors).

## Translating
Help make ezBookkeeping accessible to users around the world. We welcome help to improve existing translations or add new ones. If you would like to contribute a translation, please refer to the [translation guide](https://ezbookkeeping.mayswind.net/translating).

Currently available translations:

| Tag | Language | Progress | Contributors |
| --- | --- | --- | --- |
| de | Deutsch | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fde.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/de.json) | [@chrgm](https://github.com/chrgm), [@1270o1](https://github.com/1270o1), [@martinschilliger](https://github.com/martinschilliger) |
| en | English | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fen.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/en.json) | / |
| es | Español | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fes.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/es.json) | [@Miguelonlonlon](https://github.com/Miguelonlonlon), [@abrugues](https://github.com/abrugues), [@AndresTeller](https://github.com/AndresTeller), [@diegofercri](https://github.com/diegofercri) |
| fr | Français | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Ffr.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/fr.json) | [@brieucdlf](https://github.com/brieucdlf) |
| it | Italiano | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fit.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/it.json) | [@waron97](https://github.com/waron97) |
| ja | 日本語 | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fja.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/ja.json) | [@tkymmm](https://github.com/tkymmm), [@Mink16](https://github.com/Mink16) |
| kn | ಕನ್ನಡ | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fkn.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/kn.json) | [@Darshanbm05](https://github.com/Darshanbm05) |
| ko | 한국어 | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fko.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/ko.json) | [@overworks](https://github.com/overworks) |
| nl | Nederlands | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fnl.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/nl.json) | [@automagics](https://github.com/automagics) |
| pt-BR | Português (Brasil) | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fpt-BR.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/pt-BR.json) | [@thecodergus](https://github.com/thecodergus), [@balaios](https://github.com/balaios) |
| ro | Română | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fro.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/ro.json) | [@gg64nou](https://github.com/gg64nou) |
| ru | Русский | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fru.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/ru.json) | [@artegoser](https://github.com/artegoser), [@dshemin](https://github.com/dshemin) |
| sl | Slovenščina | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fsl.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/sl.json) | [@thehijacker](https://github.com/thehijacker) |
| ta | தமிழ் | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fta.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/ta.json) | [@hhharsha36](https://github.com/hhharsha36) |
| th | ไทย | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fth.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/th.json) | [@natthavat28](https://github.com/natthavat28) |
| tr | Türkçe | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Ftr.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/tr.json) | [@aydnykn](https://github.com/aydnykn), [@snizamaddinov](https://github.com/snizamaddinov) |
| uk | Українська | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fuk.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/uk.json) | [@nktlitvinenko](https://github.com/nktlitvinenko), [@grid-pilot](https://github.com/grid-pilot), [@infinit1ve](https://github.com/infinit1ve) |
| vi | Tiếng Việt | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fvi.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/vi.json) | [@f97](https://github.com/f97) |
| zh-Hans | 中文 (简体) | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fzh-Hans.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/zh-Hans.json) | / |
| zh-Hant | 中文 (繁體) | [![Translation Progress](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fmayswind%2FezBookkeeping-i18n-badge%2Fmain%2Fbadges%2Fzh-Hant.json)](https://github.com/mayswind/ezBookkeeping-i18n-badge/blob/main/untranslated/zh-Hant.json) | / |

## Documentation
1. [English](https://ezbookkeeping.mayswind.net)
1. [中文 (简体)](https://ezbookkeeping.mayswind.net/zh_Hans)

## License
[MIT](https://github.com/mayswind/ezbookkeeping/blob/master/LICENSE)
