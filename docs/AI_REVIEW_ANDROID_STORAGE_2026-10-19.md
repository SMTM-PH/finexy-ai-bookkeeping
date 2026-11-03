# Android AI 持久存储与 Worker

> 本文保留 Room 25 / 备份 19 阶段的验收范围。后续原图数据层已接续至 Room 26 / 备份 20，见 [原图加密存储与恢复](AI_REVIEW_ANDROID_IMAGES_2026-10-19.md)；生产页面仍未完成。

日期：2026-10-19。承接 [账本 API](AI_REVIEW_ANDROID_API_2026-10-10.md) 和 [后续计划](NEXT_DEVELOPMENT_PLAN_2026-10-10.md) 的 F03。本文记录数据、备份和后台恢复专项；F04 生产来源/复核页面、图片裁剪与原图密文保存、完整人工适配及正式升级继续接续。

## 数据与请求合同

[AIReviewPersistence.kt](../android/app/src/main/java/com/finexy/mobile/data/AIReviewPersistence.kt) 通过 `TransactionRepository.aiReview` 使用同一身份数据库，提供 Room Flow、草稿、请求和回执操作。

| 表 | 隔离与职责 |
| --- | --- |
| `ledger_ai_review_cache` | `(ledgerId, id)`；保存完整、严格校验的队列对象与状态。已确认或已删除状态不被迟到的待复核回包复活；确认流水 ID/确认人不可被替换 |
| `ai_review_drafts` | `(ledgerId, itemId)`；`0` 为本账本来源，正 ID 为条目编辑器；保留原始字符串、草稿 ID 和版本。未确认草稿不作为上传来源 |
| `ai_review_requests` | `(ledgerId, itemId)`；只有明确确认才创建，冻结操作、请求字节、稳定键所在 payload、草稿版本和请求 ID。`pending` 与有明确回执的 `posted` 分开 |

服务器/用户由既有 `LedgerScope` 数据库分区隔离，默认账本 `0` 保留其语义。编辑采用版本比较，旧异步保存不能覆盖较新输入；同一条目的并发确认只保存一份请求。结果未知时不能改写快照或丢弃原操作；回执完成后仅清理对应版本的草稿，保留后续编辑。

缓存只接受完整、有效、同账本/状态的快照；失败、重复条目或错误归属不能用于清空。列表刷新不代替原条目回执核对，不因队列为空清除待确认请求。

## 迁移与备份

[FinexyDatabase.kt](../android/app/src/main/java/com/finexy/mobile/data/FinexyDatabase.kt) 升到 **Room v25**，新增 `MIGRATION_24_25` 并注册完整路径。迁移只新增三张表，保留旧 `ai_review_items`、原流水、模板草稿和其他队列，不从旧缓存推断共享归属，也不制造确认请求。

[LedgerBackup.kt](../android/app/src/main/java/com/finexy/mobile/data/LedgerBackup.kt) 升到 **schema 19**，保留 schema 8–18 读取合同；新表逐行校验账本、原始输入、不可变请求及完整回执，拒绝错误状态、错误字段和无效错误码。加密备份恢复仍是事务内“只添加缺失行”，不覆盖设备上的较新草稿或已保存回执。

迁移与旧备份夹具同步更新了后续 migration 和旧版表集合；新增独立 schema 18 兼容用例。旧格式没有三张新表时，不能自动生成共享 AI 缓存或上传任务。

## 生产投递与恢复

`AIReviewDelivery` 接入 [SyncEngine](../android/app/src/main/java/com/finexy/mobile/data/SyncEngine.kt)，由原生产 `SyncWorker` / WorkManager 执行：

1. 仅读取明确确认的持久请求，要求服务端可靠创建/原子确认能力，使用专用接口和原始字节。
2. 成功回执先落 Room，再读取原账本、原条目；核对来源或确认流水后，事务内更新缓存并清理对应请求与同版本草稿。
3. 丢回包重试原请求；已保存回执后读取失败，仅重读，不重新创建或确认。取消继续抛出，不伪装成功。
4. 失败只在请求表保存固定错误码，不保存异常正文、模型内容或凭据；降权保留原快照，恢复权限后显式重试。旧服务缺少能力端点时不回退普通新增或旧 `resolve`。

来源及回执核对按 Go 的实际 Unicode 空白表处理，不使用 Java 的额外控制字符空白规则；U+001C–U+001F 保留为原文。单元及真实创建请求覆盖此边界，避免来源未变却陷入回执不一致。

旧生产 AI 页面仍保留原入口及默认账本限制；新生产界面将在 F04 迁入该合同。本轮没有把旧页面验收、API 专项或后台测试宿主泛化为共享 AI 产品界面已完成。

## 验证范围与结果

证据：`artifacts/ai-review-android-storage-20261019/`。仅安装独立 `com.finexy.mobile.qa` / `.qa.test`，全部 ADB 显式 `-s emulator-5554`、安装仅 `install -r`，未卸载或清除主应用。

| 检查 | 验收范围 | 证据 |
| --- | --- | --- |
| 持久层 10 条 | 24→25、旧数据保留、草稿重开、并发确认、快照不可变、未知结果、明确回执、较新输入、缓存终态、schema 19 与旧 18 | `storage-verified.log` |
| 关联回归 106 条 | 旧 AI、严格 API 解析、模板/周期/写入队列、同步、身份、旧迁移、备份/隐私、产品资产和汇率 | `regression-verified.log` |
| 真实服务投递 2 条 | 生产 Worker 降权后保留原请求、恢复权限后原请求入账一次；真实旧服务能力 404 时保留创建/确认且无替代写入 | `delivery-verified.log` |
| 跨进程 prepare / verify 两阶段 | 实际成功响应被网络代理丢弃；旧进程退出；系统调度新 PID 的生产 Worker；完成后再启动独立 verify | `process-verified/prepare.log`、`worker-witness.json`、`verify.log`、`summary.json` |
| QA / Debug 构建与 lint | Java 17，相关 assemble / AndroidTest / lint；两端均 0 错误 / 20 警告；仅构建 Debug，未安装主包 | `build-unicode-qa.log`、`build-unicode-debug.log` |
| 严格结果解析 | 固定类/方法/数量，拒绝失败、跳过、缺失和截断；两阶段各必须独立通过 | `protocol/`、`protocol-process/` |
| 安装与来源 | 已安装 QA 主包/测试包 SHA-256 与构建产物一致；源码与 APK 留存哈希 | `source-apk-sha256.json`、`summary.json` |
| 主应用只读核对 | 前后有效流水 0→0、SQLite 完整性 `ok`、实际前台运行且无该 PID 致命崩溃；未安装主包 | `main-cold-start-verified.json` |
| 夹具清理 | 两个本轮无卷容器及代理已清理，用户服务未更新 | `isolation.json`、`cleanup.json` |

以上合计 **120 条测试/阶段检查**，不把多次复跑累加。安装包哈希、主应用只读冷启动和清理结果另见本轮 `summary.json`；不能用构建成功代替功能验收。

跨进程准备了三个独立操作：创建成功但丢回包、确认成功但丢回包、确认回执已落盘但读取失败。代理在上游 HTTP 200 及完整响应结束后断开客户端连接，计数仅保留接口路径和数量，不记录正文或凭据。新 Worker 恰好重放一次创建和一次确认，已知回执只读取；最终核对原条目/流水 ID、两笔负支出、余额只增加一次、较新编辑和默认/其他账本未确认草稿均保留，旧普通新增与 `resolve` HTTP 调用为零。

## 隔离候选与复跑

| 目标 | 核验约束 |
| --- | --- |
| 当前合同服务 | `finexy-ai-mobile-source:20261007-root2`，ID `sha256:0549baad737ffb95f06fcd2abd1ed05e97664391fc9ce7ef4952564f1b429fc3`；`127.0.0.1:18141`，UUID server 249、无卷、随机账号，关闭周期自动生成 |
| 旧服务 | `ph97/finexy-bookkeeping:1.9.3-amd64`，ID `sha256:471e41e1d5f671d1597b195ea95dcf225e85a06ff64358a2511d22c37f029fa5`；`127.0.0.1:18143`，UUID server 248、无卷；旧队列读取实际 200，能力实际 404 |
| 丢回包代理 | [固定目标夹具](../.github/scripts/fixtures/ai-storage-loss-proxy.mjs)，仅监听 `127.0.0.1:18142`，仅转发上述 18141；不接真实服务或 NAS |
| 恢复见证 | 复用 QA-only `SyncRecoveryProbeWorker`，只记录 PID、时间和状态并委托生产 Worker；未导出新的调试 Activity |

[恢复脚本](../.github/scripts/verify-android-ai-storage-recovery.mjs) 核验镜像、无卷、端口、QA UID 和 instrumentation，使用随机私有命名空间；先终止 prepare 进程，再观察同一个已保存 WorkSpec，系统实际完成后才运行 verify。新证据目录需位于 `artifacts/`，已有结果不覆盖；恢复观察已有任务时使用 `FINEXY_ANDROID_RECOVERY_NAMESPACE`，不重新 prepare。

```powershell
$env:FINEXY_ANDROID_RECOVERY_EVIDENCE = 'artifacts/ai-review-android-storage-20261019/recheck-01'
node .github/scripts/verify-android-ai-storage-recovery.mjs
```

复跑前需按脚本固定端口启动无卷服务/代理并安装当前 QA 包；缺少这些条件会失败，不跳过。角色/旧服务测试使用严格解析器的 `ai-storage-delivery`；数据与关联回归分别为 `ai-storage`、`ai-storage-regression`。

## 失败记录与交接边界

首轮恢复脚本主动触发系统作业时观察失败，但原 WorkSpec 随后由系统在新 PID 成功完成。保留 `process-run.log`、原 witness 和失败记录，继续观察原任务后完成 verify；随后用新命名空间完整重跑，未把失败计为通过，也未重新生成原请求。

补充验收构建发现全局 Gradle 缓存的 `metadata.bin` 缺失。保留失败日志；单项缓存移动因占用失败，没有清空全局缓存、改 SDK 或依赖版本。最终使用任务独立 Gradle 生成缓存，复用下载依赖，离线执行同样构建与 lint。缓存隔离是环境修复，不降低门禁。

本轮只更新独立 QA 包和一次性夹具，未部署本地用户服务/NAS，未升级主 Android。来源页原图密文存储与恢复、生产导航/表单、主题/字号/横屏/TalkBack/指纹、签名/远程 CI、正式升级与报告等后续计划均不属于本轮验收完成范围。

本轮未修改 Go/Web 生产功能，未重跑其全套构建/测试，也未重建服务镜像；服务端验证仅对应表中冻结候选与已运行的隔离请求。F03 的原图持久合同和 F04 页面仍保持未完成，不将 120 条检查扩展为整个开发计划完成。
