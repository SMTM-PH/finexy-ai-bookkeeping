# Android 当前账本待处理数据与入口合同

日期：2026-10-27；更新：2026-11-01。接续 [当前计划 F05](NEXT_DEVELOPMENT_PLAN_2026-11-01.md) 与 [Web 待处理合同](LEDGER_PENDING_WEB_2026-10-23.md)。原生数据层及首页/通知、部分原目标页面已经接线；统一构建和实际入口验收正在接续，流水与旧记录的完整恢复入口仍待完成。

## 数据来源与展示约束

固定分为 AI、周期、本机恢复三组，仅处理当前服务器、身份、存储分区与账本。摘要只包含原 ID、请求键、版本、摘要哈希及状态，不含金额、备注、图片、来源或请求 DTO。

| 来源 | 实际读取范围 | 组别与边界 |
| --- | --- | --- |
| AI_QUEUE | 当前账本服务器待复核，全游标分页 | AI；失败、不支持、无权限不作零条 |
| OCCURRENCES | 当前账本服务器周期待确认，全分页 | 周期；只定位模板和到期时刻 |
| AI_REQUESTS / AI_DRAFTS | 当前分区当前账本 Room 请求与原稿 | AI；保留请求键、回执和原版本 |
| CAPTURE | 当前分区当前账本私有拍照 journal | AI；只读归属，不读图片、不建目录、不修复 AtomicFile |
| LEGACY_AI_CACHE | 默认账本分区的旧 `ai_review_items` | AI；仅旧缓存，不能冒充完整服务器队列 |
| LEDGER_WRITES | 当前共享账本不可变写入请求 | 本机恢复；区分未知结果与已知回执 |
| LOCAL_TRANSACTIONS | 默认账本全部 Room 行中待同步/失败/冲突及待解决旧 AI 关联 | 本机恢复或 AI；包括未同步删除，不把已处理历史行列为待办 |
| TEMPLATE_REQUESTS / TEMPLATE_DRAFTS | 当前账本普通/周期模板请求与原表单 | 本机恢复；请求键、模板类型、原版本分别定位 |
| PERIODIC_ACTIONS | 当前账本持久周期动作 | 周期；导航不执行确认或忽略 |

十一项来源各自保留未读、读取中、完整、失败、无权限和不可核验状态。读取无效响应、分页超限或坏本机记录时，只使对应来源失败；不以空列表补齐。服务器来源单项有 10,000 条有界上限，超限不宣称计数完整。

只有权限核验、所有来源及操作关联都完整时才提供 `exactTotal`；否则只给已读取定位组和待核验状态。未知 AI 创建回包没有回执时，服务端列表也不返回 `clientSessionId`，不能凭文字或金额猜它与哪个服务器条目相同。此时若有未关联 AI 身份，`identityComplete=false`；`knownCount` 是已读取的定位组数，不是实际待办下限。

## 关联与原定位

- 相同服务器 AI ID、模板原稿版本、周期模板/到期时刻、原请求及回执可精确关联，合并时保留全部 locator。已知回执优先于未知请求；不能用原稿替换已发送请求。
- 已加密图片的原稿带 `captureOperationId`；相同账本、draftId、operationId 跨版本合并，避免“加密已提交、暂存未清理”重复计数。已提交原稿优先，旧暂存定位保留；没有标记或不同操作的旧照片与新来源不误合并。坏标记使原稿来源失败。
- 任何仍保留 `reviewItemId` 的旧 AI 关联单独进入恢复，包括已删除的未知结果 tombstone；已经核实并清除关联的普通历史流水不进入待办。不能在打开时静默改为普通新增、新 AI 草稿或自动解决。
- 导航前重新核验当前身份、服务器、账本与真实成员角色，然后读取原 ID/键/版本/哈希。联网默认账本 `0` 也要真实 profile 请求；不以本地固定 owner 常量代替联网权限。
- 核验返回可失效票据，绑定、刷新、身份 ABA 或关闭后拒绝旧票据；目标页面初始化后还须再次核验原定位。UI 接线不得仅拿“已核验”布尔值跨异步等待。

本机模式可显示本机已知组，但远端不可核验，不能显示完整服务器总数。后台观察只能刷新本机摘要，不触发同步、Worker、确认、忽略、重试、回执清理或模型识别。

## 实现入口与接续

- [合同与关联](../android/app/src/main/java/com/finexy/mobile/data/LedgerPendingContract.kt)、[记录映射](../android/app/src/main/java/com/finexy/mobile/data/LedgerPendingRecords.kt)。
- [只读 controller](../android/app/src/main/java/com/finexy/mobile/data/LedgerPendingController.kt)、[生产 adapter](../android/app/src/main/java/com/finexy/mobile/data/LedgerPendingTransport.kt)。
- [journal 纯读取](../android/app/src/main/java/com/finexy/mobile/data/LedgerPendingCaptureInspection.kt) 通过 `AIPendingCaptureStore.inspect()` 在写锁内执行；原 `read()` 的恢复语义单独保留。

下一步在总览与二级通知页接同一摘要，并逐项连回 AI 来源/复核、原流水、模板和周期到期行；打开只导航。再以独立 QA、随机合成账号及无卷临时服务核对当前账本总数、分来源失败、撤权、身份切换、原定位变更及零重复入账。没有原生实际入口证据时，F05 保持进行中。

本批构建、JVM 与设备结果统一记录在 `artifacts/android-development-20261027/` 及拍照接续专题；各候选的通过与失败分开保留。

## 2026-11-01 原生接线与旧记录隔离

[首页/通知状态](../android/app/src/main/java/com/finexy/mobile/LedgerPendingViewModel.kt) 与 [待处理页面](../android/app/src/main/java/com/finexy/mobile/LedgerPendingScreens.kt) 共用同一摘要，首页保留紧凑三组，通知为二级页面。只有所有来源与身份关联都完整才显示完整总数；否则使用“已读取 N 个定位组·范围待核验”，不把可能重复的组数称为待办下限。缓存变化仅失效旧远端摘要并重读本机，不自动开始新的远端刷新或同步。

[目标页面保护](../android/app/src/main/java/com/finexy/mobile/data/LedgerPendingPageGuard.kt) 绑定原服务器/身份/存储分区/账本和不可变定位。在异步初始化前后重新核验，身份 ABA 永久作废旧票据；来源、复核、模板和周期页面再核对原 draft/request/版本/哈希，替代草稿不展示为原内容。只有明确恢复或编辑成功后才能释放旧版本定位，原身份约束继续有效。旋转保留定位元数据；进程恢复若失去原定位，则显示固定不可恢复入口，不退化为普通新增表单。

来源的明确恢复/丢弃会重读 expected Room 实体；复核仅 GET 原条目，已知回执只读核对，PENDING 才使用 AI 专用持久任务。模板的只读加载不通过整批 snapshot 方法暗中 ACK 创建回执；周期定位保留原模板/到期时刻和原动作。打开与预览不替代用户的明确提交。

[启动初始化](../android/app/src/main/java/com/finexy/mobile/LedgerBootstrapViewModel.kt) 按当前身份激活周期保留，首次进入真实账本内容时启动；来源/复核与首页来回、旋转不重复排全量同步，A→B→A 的重新激活可重新接续。挂起迁移和手动同步绑定原 activation、identity 与 namespace；等待后身份已变化或任务取消时不向新的账号排程。新增纯测试验证这些状态和排程意图，不冒充真实 WorkManager 或设备通过。

旧未知上传即使已在本机删除仍保留防重复确认 barrier；不再由旧待复核列表缺项推断财务关联可清理，也不再由普通同步自动 legacy resolve。已成功的旧关联可能继续显示待核验：后续须明确读取原流水和原 AI 状态、核验原快照，再用事务 CAS 清关联；无原回执的旧未知记录不能靠普通新增重试、同名或金额猜测解除隔离。新增 6 项真实 in-memory Room 和 2 项纯投影测试；本节写入时仅完成源码，统一门禁与设备结果另记。Room v26、备份 schema 20 及原财务行字段不变。

完整 F05 仍需精确流水恢复入口、上述旧关联核验入口，以及实际首页/通知/目标页面、四角色/撤权、失败与身份切换设备证据。当前统一构建保存于 `artifacts/android-development-20261101/`，不能沿用历史 177 条 JVM 或相机通过外推本批页面验收。

2026-11-01 本批统一门禁与设备接续：[236 文件候选](../artifacts/android-development-20261101/attempt1/summary.json) Java 17 Debug/QA 构建、lint 与 239 条 JVM 全部通过，完整源文件副本和 QA 打包证明已保存；[43 条设备数据专项](../artifacts/android-development-20261101/device-data1/summary.json) 实际通过，包含当前拍照 8 条与旧关联隔离 6 条。主应用未安装或升级，真实服务未部署。报告首次真实 HTTP 3 方法在预览响应解析失败，仍须修正/新候选复验；生产首页/目标页面、系统文件与整个 F03/F04/F05/F08 均不因此关闭。
