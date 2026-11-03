# 账户卡片视觉更新

2026-10-02。桌面 Web 账户管理继续每次展示一张真实账户卡片。

## 变化

- 四种材质使用金属底纹、环形镭射徽章、细密光纹与随视角移动的反光。保留账户图标，名称、类型、余额、币种及状态使用清晰深色底。
- 鼠标移动/触摸拖动倾斜卡面，支持翻面与复位及键盘操作；不自动轮播或持续播放装饰动画。
- 光泽为零关闭反光及镭射层；减少动态效果固定视角。已有图片、主题、景深与服务器保存合同保持兼容，不修改账户、流水或余额。
- 窄屏桌面账户工作台可横向滚动访问完整卡片、操作与详情；没有新增移动 Web 或原生 Android 卡面。

## 验证与部署

- `npm run check` 通过：类型检查、ESLint、18 个测试文件、38,508 项测试与生产构建；最后的对比度及窄屏样式调整另经生产构建、两个修改组件的 ESLint 和最终浏览器验收。
- 无挂载隔离 Docker 容器、随机账号及合成图片上通过 11 组卡面 HTTP/浏览器检查，包含四种材质、倾斜、键盘翻面/复位、减少动态效果、零/最大光泽、375/768/1024px、桌面宽度与 125% 缩放、上传图片、跨浏览器保存、并发冲突、离线与快速切换账户、服务重启和余额/流水数量不变。缩放检查等待实际计算样式稳定后读取尺寸。
- NAS 容器为 `finexy-bookkeeping-holo-effects-1-9-3`，镜像 `ai-bookkeeping-bookkeeping:holo-effects-candidate`，版本 `1.9.3`；服务端未改动，仍为 `card-designer-20261002`。三项原挂载、环境与财务表行哈希保持一致，附件哈希一致，SQLite 完整性正常。
- 备份位于 `/vol2/1000/Docker/finexy-import-20261001/backup/holo-effects-20261002-203811`；旧容器 `finexy-bookkeeping-wallet-carousel-1-9-3` 停止保留。回退时停止新容器，按备份配置恢复旧容器的 RestartPolicy 并启动；不删除用户数据。
- NAS 桌面/移动 Web 入口引用最终构建资源，8 项卡面及已有功能相关 JS/CSS 与候选镜像逐字一致；匿名浏览器冷启动、前后端版本和无控制台错误检查通过。认证后的卡面交互在隔离服务验收，没有在真实 NAS 账本注册测试账号或写入测试数据。
- 本地用户 Docker、GitHub 和公共镜像源本轮未更新；没有修改服务端或 Android，也未运行这些平台的测试。临时测试容器、NAS 临时镜像文件及 SSH 会话已清理。

本机证据（Git 忽略）：`artifacts/agent-access/holo-effects-check.txt`、`holo-effects-build-final.txt`、`holo-effects-browser.txt`、`card-designer-http-browser-final.json`、`holo-effects-nas-deploy-final.json`、`holo-effects-nas-online-final.json`、`holo-effects-nas-browser-final.txt`；四种材质截图为 `holo-effects-{classic,midnight,mint,copper}.png`。镜像与部署脚本保留用于审查及回退。
