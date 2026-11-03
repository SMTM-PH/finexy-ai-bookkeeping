# 账户卡面自定义

2026-10-02：桌面 Web 账户页已接入卡面编辑器，并部署到 NAS `192.168.31.184:8080`。本地用户服务未更新。现有全息卡面的翻面、拖动、键盘与减少动态效果行为继续可用。

## 用户行为

- 账户卡片下方进入“自定义卡面”，选择背景图和可选主体图；透明 PNG 主体保留透明度。
- 支持四种材质、图片位置和缩放、光泽与主体景深，直接预览。取消、切换账户和恢复默认均不会自动保存。
- JPG、PNG、WebP 文件最多 10 MB；浏览器重新编码、缩小并移除原始图片元数据后，点击保存才上传到自托管服务。服务端完整解码并限制 PNG/JPEG、尺寸和大小。
- 在其他设备的桌面 Web 登录同一账号，进入相同账本与账户或点击“刷新卡面”读取服务器版本。没有实时推送；本轮未增加 Android 或移动 Web 编辑入口。
- 并发编辑使用版本号，旧版本保存返回 HTTP 409，保留草稿。读取最新版本后需再次明确保存，才覆盖服务器卡面。

## 存储与权限

新增 `AccountCardAppearance` 用户数据表，以用户、账本、账户作为复合主键；图片和参数存入 `appearance_json`，不依赖浏览器本地存储。部署时由现有数据库结构同步创建该表。常规服务器数据库备份包含卡面，本轮没有扩展业务导出/移动端备份格式。

`GET /api/v1/accounts/card/get.json` 要求显式 `ledgerId` 和 `accountId`。`POST /api/v1/accounts/card/update.json` 接受同样的字符串 ID 和完整 `appearance`。接口只接受正常登录会话，Agent API/MCP token 不开放卡面接口。每次读取、保存均重新验证账本成员权限和实际叶子账户归属。

共享账本成员各有个人卡面；成员失去账本权限后不能继续读写对应卡面。卡面不改变账户余额或流水。清空账户数据的既有操作同步删除该用户的卡面；普通账户删除后的孤立卡面无法再通过接口访问。

## 本轮验收

- `npm run check` 通过：类型检查、ESLint、17 个测试文件/38504 个测试及生产构建；最终布局微调后另行生产构建通过。
- 无卷构建容器中 `CGO_ENABLED=1 go test ./...` 全部通过，含真实 SQLite 卡面更新、冲突、跨用户/跨账本权限和余额不变专项。
- `.github/scripts/verify-account-card-designer.mjs` 在无卷隔离容器和随机测试用户上通过 9 组 HTTP/Edge 联合检查，覆盖实际图片选择、压缩、预览、两个浏览器同步、并发保存、断网、切换账户、重启持久化、恢复默认及余额/流水数量不变。
- 最终候选镜像上的原 Agent 权限回归通过 21 项、MCP 管理通过 7 组、账户工作台通过 10 组。未运行 Android 测试、未连接真实账本执行写操作。

忽略的本机截图：`artifacts/agent-access/card-designer-editor-final.png`、`card-designer-card-final.png`；联合检查报告：`card-designer-http-browser-final.json`。

初次验收镜像：`97bf6cf8b120`。部署前发现 NAS 已被另一项工作更新到剪贴板修复版本，因此重新构建最新前端，保留 HTTP 复制功能。合并镜像上的卡面 9 组、剪贴板 7 组、Agent 21 项及账户工作台 10 组再次通过。

## NAS 部署记录

- 镜像 `ai-bookkeeping-bookkeeping:card-designer-candidate`，本地 Docker 镜像 ID `1f7344dbb218`；NAS 使用镜像配置摘要 `sha256:4e306b3e865978a2f3cd961bf15e870b4eeef86686480eb2d463bf9b3110799b`。两种摘要来自同一已校验的镜像归档。
- 当前容器 `finexy-bookkeeping-card-designer`，健康接口版本 `1.9.3`、commit `card-designer-20261002`。所有原环境变量与三项 `data/storage/log` 挂载沿用，用户数据库中卡面表及 `appearance_json` 列核验通过。
- 备份 `/vol2/1000/Docker/finexy-import-20261001/backup/card-designer-20261002-110422` 包含切换前数据库、附件、日志与受限权限容器配置。此目录名由 NAS 时钟产生，不作为用户时区时间戳。
- 旧容器 `finexy-bookkeeping-agent-clipboard-1-9-3` 停止保留，旧镜像保留。回退时先停止新容器，再恢复备份中旧容器的 RestartPolicy 并启动旧容器；新增卡面表为附加结构，不因回退删除或重置用户数据。不要将备份中的密钥配置输出到日志。
- 原财务表行内容哈希、附件整体哈希和 AI 配置哈希核对一致，全部数据库 `integrity_check=ok`。没有在 NAS 注册测试账号或修改真实账户/流水；真实卡面上传保存由用户正常操作，跨浏览器保存的功能验收在隔离服务完成。
- 线上卡面与剪贴板 JS/CSS 与合并构建逐字一致；卡面接口要求登录；桌面和移动 Web 匿名冷启动、版本一致及浏览器无错误检查通过。移动 Web 检查不表示新增了卡面编辑器。
- 第一次切换因临时容器内健康检查地址使用 loopback 自动回退，修正为 NAS 地址后重执行并完成全部核验。临时部署/状态容器及本地无卷 E2E 容器用毕清理；旧运行容器不属于临时测试容器。

部署证据：`artifacts/agent-access/card-designer-nas-deploy-final.json`、`card-designer-nas-online-final.json`、`card-designer-nas-browser-final.txt`。镜像归档和可审查的部署脚本保留在忽略目录 `artifacts/card-designer/`。
