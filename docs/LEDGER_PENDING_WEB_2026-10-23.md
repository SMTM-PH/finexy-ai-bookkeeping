# 当前账本待处理与 Web 恢复入口

日期：2026-10-23；2026-10-26 接续。对应 F05，区分源码、单元测试、浏览器验收与发布。Android 设备按用户安排暂缓，本批不运行 ADB。

## 实现合同

桌面首页、桌面通知和移动首页复用只读聚合，固定 **AI 待复核、周期待确认、本机恢复** 三组，不增加底栏。首页不再根据最近流水的“非已完成”推断待办；已入账的只读流水不计入待处理。

十一项来源分别为 AI/周期队列，普通记账原请求/草稿，模板原请求/普通草稿/周期草稿，AI 创建请求/确认请求/复核草稿/来源草稿。每项独立表示加载、完整读取、失败、不支持或权限失效。全部来源完整才能给准确总数，否则显示已知数量和待核验，失败不记为零。

AI 游标与周期 offset 完整分页，各源上限 10,000 行，超限明确失败；界面分批展示不改变完整计数。各来源分别读取，显示读取时间，不宣称跨来源原子快照。同一条目、原请求及关联草稿去重，保留各恢复入口。摘要仅含定位元数据，不保存金额、备注、来源内容、原图、请求 DTO 或凭据。

## 打开与恢复

聚合和 `verifyTarget` 只执行读取，不调用模型、重试写入、确认、忽略或清理回执。打开前核对当前身份、原账本及实时权限；默认个人账本 `0` 通过登录资料端点校验，共享账本通过成员端点。原账本不匹配时拒绝，不自动切换后继续。

本机定位绑定请求键或草稿版本，无历史版本的普通草稿用内容摘要。恢复面板在异步读取完成后再次核对；旧通知不能打开替代输入。服务端条目重新读取并核验仍存在。离开原页面、换账号或账本后作废迟到动作；移动路由核对完整路径及参数，失败进入说明页，不回落新增表单。

周期入口仅定位原模板 ID 与到期时间对应行，不确认入账。所有写入继续由原页面的明确操作、预览及实时权限检查负责。

普通新增打开先读原账本持久请求；已有请求时锁定编辑。未知结果提供“重试原请求”；已知成功先持久保存流水 ID，后续“重新读取原结果”只 GET。读回失败、附件不完整、原流水失效或回执写盘失败均保留恢复记录，不把已知成功降回新增。清理仅删除匹配原请求及同键草稿，关闭保存绑定打开时的身份和账本。

保存等待期间另一页面出现原请求时，当前表单只显示原请求并锁定，用户须再次明确重试。提交在权限、能力和本机锁每个等待边界核验捕获的页面动作；取消、离页、卸载及离开后返回均不能复活旧动作。尚未发出的请求停止，已发出的成功写入仍在原身份下保存回执。模板采用相同规则，本机草稿收尾保存与财务动作失效分别处理。

聚合读取普通草稿不迁移、不删除。旧未分区草稿、损坏内容、账本不匹配或应用锁未解锁均报告待核验，不静默归到默认账本。无键旧草稿须在用户明确提交时绑定本次稳定键，防成功后残留成可再次入账的草稿。

## 源码与证据

- [聚合模型](../src/models/ledger_pending.ts)、[store](../src/stores/ledgerPending.ts)、[聚合与定位](../src/lib/ledger_pending.ts)。
- [共享面板](../src/components/common/LedgerPendingPanel.vue)、[通知模态](../src/components/common/LedgerPendingDialog.vue)、[导航](../src/lib/ledger_pending_navigation.ts)、[移动保护](../src/lib/mobile_pending_route.ts)。
- [普通提交合同](../src/lib/transaction_submission.ts)、[事务 store](../src/stores/transaction.ts)、[桌面编辑器](../src/views/desktop/transactions/list/dialogs/EditDialog.vue)。
- [模板定位](../src/lib/template_recovery_locator.ts)、[模板面板](../src/components/common/TemplateCreationPanel.vue)、[AI 来源会话](../src/views/base/ai/AIReviewSourceSessionBase.ts)。

聚合专项 62 项通过，证据为 `artifacts/ledger-pending-20261023/contract/summary.json`；之后普通请求校验已改为复用共用合同，最终候选须以合并门禁核验。普通提交、模板定位及移动深链专项另有记录，不累加重复测试。全门禁、源码 manifest、浏览器及失败迭代集中在 `artifacts/ledger-pending-20261023/`。

权限/锁等待与页面动作修复另通过 3 文件 86 项专项及 8 文件 lint，冻结记录为 `guard-review/source-and-test-summary.json`。缺盘后的明确重试仍使用原键及原快照，与服务端幂等回放合同衔接；该结果不代表动态浏览器或全平台完整验收。

完整验收仍需逐项闭合四角色、A/B/0、坏本机记录、丢回包及已知回执只读、跨标签身份、页面离开与旧定位替换；Android 首页/通知和完整人工听读/适配另行接续。未部署真实服务、未升级主 Android 应用。完整安排见 [后续计划](NEXT_DEVELOPMENT_PLAN_2026-10-26.md)。


## 2026-10-26 浏览器与冷启动修复

旧冻结源码 `73764cf3cea22e6802183512f5d52036f89ac4a537e8613d8d6b0fcde7393b30` 已通过 `browser/attempt-candidate7/summary.json` 的 23 项真实浏览器检查：A/B/0、四角色、撤权、离线、分页 101 条、两端原 AI/周期定位、模板丢回包、跨标签身份和两端普通恢复各 4 组。普通恢复在独立合成账本产生 6 条支出，余额 `10000→9358`；其他基线账本余额不变。26 个只读窗口没有新增财务写入；JavaScript 和未预期 console 错误均为零，临时容器/网络已清理。

已知回执使用一次原生 IndexedDB acknowledgement 故障注入，再重载实际读取原记录；此为受控故障，不伪称自然事故。丢回包先让真实请求在服务端成功，再丢弃回包。没有注入 Pinia、恢复 journal、凭据文件或伪造服务响应；布局截图只证明所采集视口，不代替完整人工读屏。

独立诊断区分两件事：已打开模态时跳路由可触发 Vuetify `closeOnBack` 的 history 回滚；真正冷文档则存在生产初始化缺口。后者未在打开前恢复持久账本偏好：pendingTarget 以初始 `0` 拒绝真实 A；快捷记账先打开 `0` 后恢复 A，原表单被上下文保护关闭。诊断在 `browser/reload-diagnostic2`/`reload-diagnostic3` 留存，新 page 从 about:blank 真正加载，与热 hash 导航分别记录。

修复新增严格只读偏好检查和 `ensureStrictSelection`：从原身份偏好恢复，经完整账本列表及实际成员/默认登录资料核验后才设置当前选择；不从 target 选择账本，不写默认回退。损坏、撤权、读失败或原选择失效均保留原记录，页面固定中文错误、重试与返回总览。快捷操作等当前账本和页面数据成功后才消费 action；pendingTarget 保留原 URL，每次重新进入/明确重试核验同一最小定位符。身份、偏好、路由代次和 A→B→A 选择变化拒绝迟到结果。

严格恢复专项 25 条、6 文件 lint 和类型检查通过，见 `artifacts/ledger-pending-20261026-cold-route/verified/summary.json`。新冻结候选正在补完整浏览器回归，不能把 candidate7 的结果移贴到新源码。候选8的 7 组真正冷启动已通过；其后驱动尝试重新打开保留 URL 的旧 locator 时未发生导航，已改为先返回首页再重新打开；失败证据保留。协调中止的 candidate9 已清理，不计通过。最终候选证据继续记录于上述 artifacts。


后续验收区分记录：candidate11 在本机 Docker 引擎不可用且驱动进程消失后没有整轮结果，按中断处理；引擎恢复后逐项核验 owned network/container 并清理。运行脚本现要求 Docker 健康，inspect 仅明确 not-found 才能证明资源不存在，不能把连接失败记成清理成功。

candidate12 虽走完 30 项业务检查，但最终捕获未处理 plain Object，整轮判失败。真实受控诊断在独立无卷服务把汇率代理指向其自身不可连接端口：实际 `/api/v1/exchange_rates/latest.json` 返回 `400` / `200005`，与后台自动刷新的未处理拒绝对应。修复仅在 [exchangeRates.ts](../src/stores/exchangeRates.ts) 自动刷新入口显式消费可选请求的拒绝，前台读取失败继续传播，失败不替换有效快照或时间。

最终修复候选 `341f96c8ef2d001556670e693d8abaef0f68883d5b20e24ca3741a5f1f3aed0a` 已通过类型、lint、46 文件 39,090 条单测、共用夹具和生产构建，625 文件 before/after 一致；证据为 `gate-rates-verified/status.json` 和 `verified-tests.json`。新增汇率 5 条已包含在总数，不能重复累加。首次全量失败的另两个旧隐私测试，把时间戳中合法的 `999` 子串误作金额泄漏；现固定该时间戳作为回归条件，仍禁止私有字段/原文，并递归禁止精确金额值，旧失败 `gate-rates-final` 保留。

同一最终 dist 在 `artifacts/ledger-pending-20261026-rates-diagnostic/attempt3-handled/summary.json` 中再次以真实代理连接失败复验：正常登录与真正冷文档仍收到实际 400，但 unhandled rejection 和 pageerror 均为零，临时容器/网络已清理。该受控配置不是正常候选配置；正常 30 项矩阵另行记录，不将已知可选 provider 的资源/日志 400 伪称所有 console 均为零。


### 最终 Web 候选结果

`browser/attempt-candidate14/summary.json` 与 `run-status.json` 已记录整轮 `passed`、driver/cleanup 均退出 `0`，源码仍为上述 `341f96…`。正常配置下 30 项检查通过，含 7 项真正冷文档、两端 8 组普通恢复、33 个只读窗口和 10 组布局；6 条合成支出余额 `10000→9358`，A/B/0 原基线不变。未预期 JavaScript、console 错误均为零；7 条精确对应已知故障的日志另行记录，不能说所有 console 均为零。

运行时二进制/静态文件、625 文件候选及脚本快照均有哈希核验，`runtime-candidate14/cleanup.json` 证明 owned 容器/网络已不存在。完整索引为 `artifacts/ledger-pending-20261023/final-web-acceptance-20261026.json`。本批 Web 核心可交接；F05 Android、物理手机软键盘、完整人工读屏/适配及正式部署仍待接续，F05 与整个计划均未关闭。
