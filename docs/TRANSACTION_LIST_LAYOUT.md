# 流水列表布局

2026-10-02。修复长交易名称挤占金额、日期等列的显示问题。

- 固定列布局，顺序为选择、交易、金额、日期、类型、分类、账户、状态。
- 交易名称及辅助说明分两行，各行超长省略；账户、分类也保留在各自列内。悬停可查看文字提示，点击或 Enter 打开完整详情。
- 金额完整显示币种与正负号，日期直接展示，不需要在正常桌面宽度横向滚动查找。
- 本轮只修改桌面 Web 排版，不更改原始备注、流水、日期、金额及账户关联。

## 验收与部署

- `npm run check` 通过：类型检查、ESLint、18 个测试文件 / 38,508 项测试及生产构建。
- 无卷一次性 Docker、随机账号和合成流水通过 7 组浏览器专项：超长名称、完整金额和日期、1396/1440px 无表格横向溢出、深浅主题偏好、125% 缩放、紧凑切换、键盘查看完整详情；历史游标分页、日期筛选、账本隔离、失败重试和窄屏筛选入口继续正常。
- 截图：`artifacts/agent-access/transaction-layout-table-final.png`（隔离示例数据）。日志及线上检查结果保存在同目录 `transaction-layout-*.txt` / `*.json`。
- 已部署 NAS `192.168.31.184:8080`，容器 `finexy-bookkeeping-transaction-layout-1-9-3`，镜像 `ai-bookkeeping-bookkeeping:transaction-layout-candidate`。前后端 `1.9.3`，后端构建标识保持 `card-designer-20261002`，本轮仅更新 Web 静态资源。
- 环境、端口及 data/storage/log 挂载完整沿用；原财务表完整行哈希、附件哈希一致，SQLite 完整性 `ok`。线上四个相关静态资源与构建一致，桌面及移动 Web 匿名冷启动登录入口正常，无浏览器错误；NAS 已登录业务交互未作为自动化测试目标。
- 备份：`/vol2/1000/Docker/finexy-import-20261001/backup/transaction-layout-20261002-223503`。旧容器 `finexy-bookkeeping-home-illustrated-1-9-3` 及旧镜像保留。回退时停止新容器，按备份配置恢复旧容器重启策略，再启动旧容器。
- 临时测试容器、NAS 上传文件已清理。未更新本地用户 Docker、GitHub 或镜像仓库；Go、Android 和 Agent 功能未修改，未运行其专项。
