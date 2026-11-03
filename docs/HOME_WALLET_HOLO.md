# 首页钱包复用账户卡面

2026-10-02。桌面 Web 首页钱包改用账户页同一个 `AccountHoloCard`，提供适合钱包区域的紧凑尺寸；旧的纯色小卡片已替换。

## 行为

- 默认金属底纹、环形镭射徽章、视角光泽与正背翻面相同；服务器上保存的材质、背景与透明主体图在首页同步读取。
- 每次显示一张，左右按钮及方向键切换全部可见末级账户，首尾禁用；F 翻面、R 复位，换账户回正面。保留查看全部和添加账户。
- 拖动用于查看光泽，“查看详情”按钮打开当前账户实际详情。隐藏金额同时覆盖正面、背面和详情，卡片无障碍名称不含金额。
- 正常登录会话按用户、明确账本与账户调用已有只读卡面接口。快速切换、切换账本或卸载组件后丢弃旧响应，不混用卡面；读取失败显示默认样式、可见错误和重试。刷新余额同时重新读取服务器卡面。
- 没有新增财务写入、移动 Web 或 Android 卡面入口。

## 验证

- `npm run check` 通过：类型检查、ESLint、18 个测试文件/38,508 项测试和生产构建；最终图标尺寸微调另经生产构建及修改组件 ESLint。
- 最终候选镜像在无挂载隔离容器和随机账号上通过首页钱包 7 组浏览器/HTTP 检查，覆盖 18 个可见账户、隐藏账户、多币种及负余额、键盘/详情、金额隐私、刷新与账本隔离、深浅主题、100%/125% 缩放、375/768/1024px、44px 控件、服务器卡面与账户页一致、翻面/复位、旧响应丢弃和失败重试。账户余额及流水数量未改变。
- 原账户卡面编辑器 11 组 HTTP/浏览器回归通过，包含真实合成图片上传、两浏览器保存、冲突、离线、重启持久化、四种材质、减少动态效果和窄屏操作。
- 候选镜像及 NAS 的桌面/移动 Web 匿名冷启动、前后端版本及无浏览器错误检查通过。认证后的功能使用隔离账本验证；NAS 未注册测试账号或写入测试数据。服务端、Agent 权限和 Android 本轮未改动，未重新运行 Go/Agent/Android 专项。

## NAS 部署

- 容器 `finexy-bookkeeping-home-holo-1-9-3`，镜像 `ai-bookkeeping-bookkeeping:home-holo-candidate`。版本 `1.9.3`，后端仍为 `card-designer-20261002`。
- 沿用全部原环境及 `data/storage/log` 三项挂载。财务表行哈希与附件哈希一致，SQLite 完整性正常；6 项相关 JS/CSS 与候选镜像逐字一致，线上入口引用本轮资源。
- 备份 `/vol2/1000/Docker/finexy-import-20261001/backup/home-holo-20261002-210134`；旧容器 `finexy-bookkeeping-holo-effects-1-9-3` 已停止保留。回退时先停止新容器，按备份配置恢复旧容器 RestartPolicy 并启动，不删除用户数据。
- 本地用户 Docker、GitHub 和公共镜像源未更新。临时隔离容器、NAS 临时镜像文件及 SSH 会话已清理。

本机验收证据（Git 忽略）在 `artifacts/agent-access/`：`home-holo-check.txt`、`home-holo-build-final.txt`、`home-holo-browser.txt`、`home-holo-designer-regression.txt`、`wallet-carousel-final.json`、`home-holo-wallet-final.png`、`home-holo-nas-deploy-final.json`、`home-holo-nas-online-final.json`、`home-holo-nas-browser-final.txt`。构建镜像、归档及部署脚本保留用于审查和回退。
