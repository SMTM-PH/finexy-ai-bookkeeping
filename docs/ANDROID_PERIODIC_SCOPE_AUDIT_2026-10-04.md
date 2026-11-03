# Android 周期账本范围审计

> 本文保留 2026-10-04 修改前的静态审计。2026-10-05 的源码已为 Room 23 / 备份 17，并加入账本请求、周期入口与持久动作；最新实现及验收边界见 [移动周期接续](PERIODIC_MOBILE_ANDROID_2026-10-05.md)。

日期：2026-10-04。本文件记录本轮源码检查，不能作为 Android 共享周期功能的安装或运行验收。

## 当前源码事实

- `data/FinexyDatabase.kt` 仍为 Room v22。`TemplateEntity` 和 `ScheduledOccurrenceEntity` 没有 `ledgerId`；周期缓存不能区分同一账号的不同账本。
- `FinexyApi.listTemplates` 未发送账本参数。待确认读取、确认、忽略和恢复仍使用旧默认账本请求，`occurrencePayload` 没有账本字段。
- `SyncEngine` 拉取默认模板及默认待确认列表，`TransactionRepository.replaceOccurrences` 替换账号周期缓存；DAO 观察接口没有按账本筛选。
- `LedgerBackup` 当前 schema 16，导入允许 schema 8–16。增加周期账本字段时必须同时处理备份兼容，不能只改新备份序列化。
- 当前 Android 非默认账本隐藏默认账号的待确认入口，设置入口仍明确指向默认个人账本。这条限制继续保留，不能因服务端已支持账本就开放未隔离的客户端缓存。

## 后续实现与门禁

1. 为模板、到期快照加入默认值 `0` 的账本归属，新增 Room 22→23 migration。历史行仍属于默认个人账本；服务器和用户继续由 `LedgerScope` 隔离。
2. API 请求携带打开时捕获的账本，校验回包与快照账本一致。DAO、完整列表替换及 Compose Flow 都按账本观察，切换期间不展示前一账本数据。
3. 模板管理只允许所有者/管理员；所有有效成员可读；可记账成员确认/忽略/恢复，到期只生成待确认记录。确认结果未知时保留原记录标识并重试，不改变目标账本。
4. 新备份 schema 与历史 schema 兼容用例、字典资源归属、原币双金额和退款、删除引用保护、离线与进程恢复必须专项验证。同步失败或无效列表不能清空既有缓存。
5. 使用独立 QA 包与无挂载 Docker 服务验证四角色和 A/B/默认账本。主应用只读核验，禁止卸载、清数据或在主 AVD 上运行 `connectedDebugAndroidTest`。

本轮没有修改上述 Android 周期源码，没有构建或安装新的 Android 包，也没有改变主应用数据。既有预算 QA 安装证据仍见 [账本预算专项](LEDGER_BUDGET_2026-10-04.md)。
