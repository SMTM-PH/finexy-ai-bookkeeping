# Finexy 第一轮正确性与可信度修复

日期：2026-10-03。承接 [设计评审与开发计划](DESIGN_REVIEW_2026-10-02.md) 的 M0/M1。本轮修改当前工作树，保留已有账户卡面、Agent 管理与历史流水等用户成果；没有提交、推送、升级主 Android 应用或部署到真实服务。后续仅安装独立 UID 的 QA 包，设备验收见 [Android 恢复专项](ANDROID_RECOVERY_2026-10-03.md)。

## 交付范围

| 主题 | 当前实现 | 边界 |
| --- | --- | --- |
| 桌面金额 | 收支、趋势、构成按来源账户币种汇总整数最小货币单位；账户余额精确累加，超大正负数抵消不丢精度。CSV 为带年份、币种与主单位的月度小计，下载前确认 | 不做隐式汇率折算；缺账户/分类、超限或读取失败显示不完整。完整五维报告留后续 |
| 移动 Web 账本 | 查看详情/接受邀请与全局切换分开；总览、账户、流水及详情携带当前账本；缓存与旧响应隔离，异常列表保留有效数据；已有存钱目标按账本读取，失败与有效空态分开 | 非默认账本普通新增/编辑、账户管理、统计、分类/标签/模板等流程暂不支持，含大小写和尾斜线深链接。说明客户端能力，不误称权限不足 |
| 空账本与刷新 | 隔离浏览器复现了空账本被显示成失败：重置后的空缓存误触发“未变化”哨兵，桌面未识别哨兵。缓存只在原有有效同账本时发出该信号；桌面仅在当前账本有效缓存匹配时接受 | 缺失/空值/畸形响应和权限错误继续失败，不用空列表覆盖有效缓存 |
| Android 金额与分类 | 原币种累计收支与可见末级账户余额分开；本地钱包用全量流水；超限可见。分类加载、失败、空态及重试独立，余额调整不要求分类 | 首页仍是明确标注的累计口径，月度改版未交付；完整读屏/设备适配未验收 |
| Android 在线新增 | 非默认账本确认后先保存 Room 队列：稳定 localId、账本、不可变提交快照；POSTED 状态在刷新前持久化；WorkManager/SyncEngine 恢复原请求。独立 PID Worker 已验证丢失响应后的单次入账 | 每账本至多一笔待确认写入；结果未知先处理原记录，不能新生成请求绕过。仅在隔离 QA 包及随机账号验收，主应用升级另验 |
| 服务端幂等 | 普通新增 API 的 clientSessionId 永久绑定所有者分片、实际操作者、账本和业务指纹；回执与流水/余额同一事务提交；同键不同内容拒绝；删后不重建 | 没有键的请求不自动去重；共享成员仍受当前权限和所有者分类、标签、附件边界约束 |
| 回放与附件 | 已确认回执先回放，再执行只适用于新建的时间/未关联图片检查；回包取实际持久标签与图片 | 其他写入入口沿用各自合同，不能将本项扩大解释为所有接口均永久幂等 |
| 范围与状态 | Web 非默认账本隐藏默认个人账本预算、周期、AI 待复核；Android 非默认账本首页隐藏账号待确认卡，账号设置保留明确标注范围的入口；去除 Web 模拟备份/设备/同步/版本肯定状态 | 未有状态接口的项目显示未核验；预算/周期/AI 的真正账本化需服务端模型、迁移与权限一起实现 |
| AI 与 Electron | 模型日志移除请求/响应正文与可能携密的错误字符串，保留无内容的错误类型/状态；桌面精确比对服务 origin、只允许指定离线文件、验证 IPC sender 与主 frame | Electron 已验证策略单测，尚未验收 Windows 正式安装包的完整运行流程 |
| CI 发布门禁 | 可复用 CI 覆盖 Web/Go/Android；Go 显式 CGO=1。Android 保留 Debug 编译/lint/JVM，新增独立 QA 两 APK及相同检查；GitHub 专用 AVD + 无卷 Docker 门禁要求七类 53 条与两阶段恢复全部成功，拒绝跳过/部分执行；镜像发布依赖同提交质量结果和浏览器/API 门禁 | 本地已验证工作流语法及实际设备专项；未触发远程 GitHub，不能宣称 CI、签名或正式发布验收完成 |

## 数据与兼容合同

- 服务端增加 [TransactionSubmission](../pkg/models/transaction_submission.go)，不改历史流水/余额。回执只保存身份、幂等键、哈希和结果 ID，不保存另一份交易内容。永久保留防止删除后的旧请求重新入账。
- [服务实现](../pkg/services/transaction_submissions.go) 在写入事务内复核账本成员和源/目标账户真实归属，含默认 ID 0；复合主键防重复，进程内按分片串行提交。多实例仍须不同 `UuidServerId`。
- SQLite 并发可能出现短暂锁错误；客户端须保持原快照和原键再重试。隔离 HTTP 专项包括有界退避，不能换键来“解决”结果未知。
- Android Room 21→22 增量迁移新增队列；备份 schema 16，源码支持 schema 8–15 读取。隔离设备已通过 Room 21→22 保留数据、schema 16 队列备份与 schema 15 兼容，以及 schema 8–12、14 和原有隐私专项；schema 13 仍没有单独用例。未用 destructive migration。旧 APK 不能直接读取 v22 数据库，正式升级/回退需保留升级前备份并单独验证，不能卸载或清数据。
- 源码版本仍为 Web/服务端 1.9.3、Android 1.0.1 / 10902；仅安装独立 `com.finexy.mobile.qa` 开发验证包，未完成正式签名发布，主应用保持原版本与 Room v21。

## 已执行验证

- Go Windows `go test ./...`、`go vet ./...` 通过；前者 CGO=0。
- 无卷 Linux 构建镜像 CGO=1 `go test ./...` 通过，包含 SQLite 事务、权限、幂等和回滚；`go test -race ./pkg/services -run '^TestTransactionSubmission' -count=1` 通过。
- [HTTP 幂等专项](../.github/scripts/verify-transaction-retries.mjs) 8 组通过：服务重启、不同请求拒绝、八路并发、安全作用域、默认账本账户保护、附件/标签回放、实时降权、删除后重试。
- AI DEBUG 隐私专项通过；四种实际 HTTP provider 使用本机模拟成功/错误/畸形响应，Google request adapter 不联系外部模型。
- Electron `node --test desktop/navigation-policy.node-test.cjs` 4 项通过；主程序语法检查通过。
- Android Java 17 下 Debug 与 QA 各自的两 APK 编译、lint、JVM 均通过；各 13 条 JVM 专项无失败。设备七类 53 条全部通过，包含新增 9 条；两阶段进程恢复另通过 prepare/verify 两条，生产 Worker 在独立新 PID 内完成。
- 最终 Web `npm run check` 通过，包含类型检查、ESLint、40 文件/77075 条 Vitest 与生产构建；新增六条空缓存/刷新专项和精确余额求和专项均通过。
- 最终隔离候选的 `/desktop`、`/mobile` 冷启动通过：登录入口挂载、前后端 1.9.3 一致，无捕获到的浏览器错误。
- Agent 接入 21 组、MCP 管理 7 组回归通过，含授权、实时成员权限、导入及实际生成/撤销令牌；只使用无卷服务内随机账号。
- [桌面币种浏览器专项](../.github/scripts/verify-desktop-currency.mjs) 6 组通过，含原币小计、USD 报表/确认 CSV、非默认范围限制、真实状态、503 与缺币种失败保护。
- [钱包回归](../.github/scripts/verify-wallet-carousel.mjs) 9 组、[流水历史回归](../.github/scripts/verify-workspace-history.mjs) 7 组通过；原有账户卡面/隐私/键盘流程、普通不变刷新、真正空账本切换、全量分页、日期筛选及异常响应均验证。原空账本失败已在更新候选中关闭。
- [移动账本浏览器专项](../.github/scripts/verify-mobile-ledger-context.mjs) 10 组通过，含查看/切换、选定账本读取、旧响应隔离、异常结果保护、深链限制、44px 控件及默认表单可达。导入仅检查打开时绑定；本脚本未提交账单或新增流水，接受邀请不切换另由 Vitest 检查。
- 三个工作流通过 actionlint，Android 脚本通过 Bash 语法检查；本地检查不能替代 GitHub 实跑。
- [设备结果解析器](../.github/scripts/verify-android-instrumentation.py) 使用官方 runner 1.6.2 状态协议；八组合成日志及 CLI 退出码验证通过（完整成功接受，其余拒绝）。本轮实际七类 53 条日志亦通过严格解析；[恢复门禁](../.github/scripts/verify-android-process-recovery.mjs) 实际完成 prepare → 退出/终止进程 → 新 PID Worker → verify。

证据主要在被忽略的 `artifacts/first-iteration-20261003/`、`artifacts/agent-access/`、`artifacts/desktop-currency/` 与 `android/build/reports/first-round/`。候选镜像、源码版本和 APK/入口文件哈希见 `artifacts/first-iteration-20261003/candidate-manifest.json`。

生产构建仍有 Sass/构建选项弃用提示及大于 500 KB 的 chunk 警告。后续优化依赖与拆包时须保留 Agent/AI/分类关联组件的 common chunk 初始化约束，并重新验证真实入口冷启动。

## 后续顺序

1. 移动 Web 普通写入、草稿/账本偏好与待确认恢复，以及四角色和标签/附件边界已在 [2026-10-04 专项](MOBILE_LEDGER_WRITE_2026-10-04.md) 接续完成；上文移动端能力限制是 2026-10-03 的历史基线，现状以专项记录为准。
2. 账本预算已在 [2026-10-04 专项](LEDGER_BUDGET_2026-10-04.md) 接续完成跨端隔离验收；继续周期/AI 合同与完整筛选报表，再完善月度首页。各阶段以 [开发计划状态](DEVELOPMENT_PLAN_STATUS_2026-10-04.md) 及专题证据为准，不在客户端假造范围。
3. 完成 TalkBack、指纹成功路径、主题/字体/横屏，递增正式版本并验证签名升级；远程 CI、主应用升级、备份和回退均有证据后再交付安装/部署。

本轮未修改主 Android 账本、本地用户服务 18080 或 NAS 8080。临时 E2E 服务无挂载且仅用随机账号，全部验收结束后已清理本轮容器；本地用户容器仍运行。候选镜像和证据留作审查，不视为已部署版本。
