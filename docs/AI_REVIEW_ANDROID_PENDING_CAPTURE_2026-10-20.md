# Android 未保存拍照内容的持久恢复

> 本文前部保留 2026-10-20 的历史失败与暂缓安排；设备已获用户恢复使用，最新结果及边界见文末 2026-11-01 接续。

日期：2026-10-20。承接 [后续计划 A1](NEXT_DEVELOPMENT_PLAN_2026-10-20.md) 和 [系统图片入口](AI_REVIEW_ANDROID_NATIVE_IMAGES_2026-10-20.md)。**当前为实现接入与构建阶段，设备验收尚未闭合，F03/F04 不关闭。**

## 行为与存储合同

原先拍照文件位于缓存目录，文件名依赖页面保存状态；Activity 重建已有专项，完整冷启动缺少持久归属。本批新增 [设备拍照暂存](../android/app/src/main/java/com/finexy/mobile/data/AIPendingCapture.kt)，并接入 [来源会话](../android/app/src/main/java/com/finexy/mobile/data/AIReviewSourceSession.kt) 与 [生产来源页](../android/app/src/main/java/com/finexy/mobile/LedgerAISourcePage.kt)。

- 启动系统相机前先保存来源草稿，再以 AtomicFile 写入操作 ID、账本、草稿 ID/版本及表单摘要，最后创建相机可写文件。目录按现有身份数据库分区及账本隔离，不保存登录凭据或原始表单。
- 拍照中尚未加密的内容位于应用私有持久目录，仅通过对应 FileProvider URI 授权给相机，不进入公共相册。应用禁止系统自动备份；这类设备暂存也不进入密码账本备份。用户需先在来源页处理，已保存原图继续遵守原图加密与可迁移备份合同。
- 重开来源页先读取同一分区的暂存记录；只有原草稿 ID、版本和摘要仍匹配时，才允许明确“重新读取拍照图片”。旧草稿、其他身份或其他账本的内容不能替代当前来源。
- 归属记录本身及导入会话也校验身份分区，来源 ViewModel 的 key 包含该分区，避免页面重建与换账号同时发生时复用旧模型。对应拒绝测试已写入当前 7 项范围，尚待设备验证。
- 暂存尚待处理时阻止替换图片、编辑来源、识别、保存队列及丢弃整份来源。用户可保留暂存返回；单独丢弃拍照内容需确认，原有文字、加密原图和队列不受影响。
- 关键动作执行时重新读取导入/暂存的忙碌状态，防止上一帧的可点击状态越过保护；返回、编辑、识别及确认也执行该检查。连续点击的生产设备专项仍需补验。
- 成功导入时，原图密文、来源引用及 `captureOperationId` 标记在同一 Room 事务内提交，再清理相应暂存。如果进程在落库后、清理前退出，重开可根据该操作标记仅清理文件，不重放导入。
- 每个拍照操作单独注册 ActivityResult；旧回调核对原操作，不指向后来的拍照内容。缺文件、坏图片、超限和记录损坏保留原来源并可见提示，不自动发模型、创建队列或入账。
- [数据与备份页](../android/app/src/main/java/com/finexy/mobile/DataPrivacyScreen.kt) 已说明暂存不包含在备份中；明确清空当前身份的本机数据时，先校验该分区全部暂存记录，再按记录清理。归属损坏时停止，不跨分区删除，不根据任意图片路径猜测归属。

本批未改变 Room v26 或备份 schema 20：暂存是设备中的未完成外部操作，不新增财务表，也不把不可迁移的私有路径导出到备份。已保存原图仍使用既有 Room 分块加密；新增测试覆盖 schema 20 导出和 schema 19 兼容读取边界。

## 测试与当前证据

本机证据位于 `artifacts/ai-review-android-capture-20261020/`，只用于独立 QA 包、随机身份、合成图片与无卷隔离服务。不得把前批 161 项通过沿用为本批候选全量通过。

| 项目 | 实际状态与证据 |
| --- | --- |
| 暂存数据专项 | 首版 6 项在 `capture-data-01.log` 通过。随后加强损坏记录断言，`capture-data-rejected-01.log` 中 1 项因错误预期异常类型而失败，断言已改为实际的 `JSONException`。再新增明确清空的分区隔离测试；当前 [7 项源码](../android/app/src/androidTest/java/com/finexy/mobile/data/AIPendingCaptureTest.kt) 尚未整组设备复验 |
| 真实相机跨进程准备 | `process-02/prepare.log` 通过，真实系统相机打开、拍摄，持久记录与原草稿匹配；`camera-before-exit.xml` 与 `process-exit.json` 证明相机在前台、原 QA PID 退出 |
| 新进程验证 | `process-02/verify.log` 未通过：新进程能读取原暂存记录和原加密图片，但拍照输出文件为空，不能宣称实际照片恢复成功 |
| 测试运行器修正 | 默认运行器会在退出时结束 Activity，见 [Android 官方说明](https://developer.android.com/reference/androidx/test/runner/MonitoringInstrumentation) 及 [官方实现](https://github.com/android/android-test/blob/main/runner/monitor/java/androidx/test/runner/MonitoringInstrumentation.java)。准备阶段已改用 `waitForActivitiesToComplete=false`，避免运行器主动结束 Activity，并增加宿主已停止的状态断言；实际任务存续与图片恢复尚未验证 |
| 设备安排 | 随后 QA 应用和测试包被外部操作停用，`process-03/prepare.log` 无法解析测试宿主。用户已明确选择“先继续代码和构建，设备稍后使用”，此后不再安装、启用或运行设备测试 |
| 严格日志解析 | 新增 `ai-pending-capture`（7 项）和 `ai-pending-capture-process`（2 阶段）范围；`protocol-capture-final/` 与 `protocol-process-final/` 的 16 组协议自测分别通过。协议自测不等于 Android 功能通过 |
| 构建 | 最终 QA/Debug 的 assemble、AndroidTest assemble 和 lint 均通过，见 `build-qa-candidate.log` / `build-debug-candidate.log`；两种 lint 均 0 错误、0 致命项、5 警告和 21 信息项。前序构建另存 `build-*-final-identity.log` 和 `build-*-code-final.log`，不替代当前源码检查；构建不替代设备验收 |

`process/` 保留最初测试时钟未推进、未启动相机的失败；后续等待系统窗口时推进 Compose 待处理帧，`process-02` 已实际启动相机。失败日志均保留，不以复跑累计通过数。

当前连接过的设备为专用 `Finexy_API_35_Clean`，其中没有主应用 `com.finexy.mobile`，本机 AVD 配置后置相机为 `emulated`、前置相机为 `none`。本批仅使用 `.qa` 与 `.qa.test`，不能声称已只读核验其他 AVD 上的主账本，更不能沿用历史零条流水作为当前结果。`source-candidate.json` 记录最终源码；前序 `installed-final.json` 仅证明当时的安装，不是最后一次测试包安装哈希或当前设备状态的证明。本批未安装设备暂停后的最新 APK，最终构建产物另记录于汇总。

交付汇总为 `summary.json`：169 份 Android 源码的冻结哈希与最终文件一致，四个 QA/Debug 应用及测试 APK 已核对包名并记录 SHA256。当前代码的 7 项数据测试和 2 个拍照跨进程阶段未闭合，`goalComplete`、`F03Complete`、`F04Complete` 均保持 `false`。5 条 lint 警告分别为 ExifInterface、OldTargetApi、SuspiciousIndentation、DataExtractionRules 和 ConstantLocale，未作为本批功能验收通过依据。

本轮两个无卷临时服务已按创建时的完整容器 ID、镜像与挂载核对后停止，用户原有服务容器保留，见 `cleanup.json`。未部署真实服务；历史失败、设备设置与隔离证据继续保留。

## 设备可用后的验收出口

以下未完成项保留在总计划内；用户明确恢复设备验收前，仅继续代码、构建、协议自测及文档工作。

1. 按最终冻结源码构建并仅 `install -r` 独立 QA；先核验 AVD、包身份、启用状态及原设备设置，不与另一项验收争用设备，不自动重新启用外部停用的包。
2. 整组运行 7 项暂存专项，复验损坏记录、旧版本拒绝、分区隔离、空/坏文件、落库后中断、密码备份边界和明确清空。
3. 运行 [跨进程驱动](../.github/scripts/verify-android-ai-capture-process.py)，验证修正后的真实相机任务存续、URI 可写与实际图片字节；记录 prepare/新 PID/明确重读/清理及零模型、零队列、零财务写入。该场景属于页面恢复，不替代既有生产 Worker 丢回包专项。
4. 补回调丢失、相机取消、保存失败后的明确重读/丢弃，以及另一个操作的迟到回调；补连续点击保护与“已加密保存、但暂存清理失败”的提示及重试，不能把清理失败表述为全部保存成功。核对原来源不变、公共相册无新增，清空数据的生产确认路径也需实际验证。
5. 重跑相关来源、原图、迁移/备份、同步/模板/旧 AI、生产页面、导航与新 PID Worker 请求恢复。补上一批加强预览断言后的宽屏 Activity 重建失败，不把旧矩阵结果泛化到新候选。
6. 补新恢复提示和确认框的深浅主题、窄屏/横屏、最大字体、键盘、安全区与 TalkBack；本批构建或协议自测不证明这些人工项通过。

后续仍有 F04 完整角色/类型/旧记录处置，F05 待处理、F06–F09 报告与首页，以及 F10–F13 的完整体验、CI、签名升级部署和测量优化。主应用升级及真实服务部署未由本批实施。

设备暂不可用期间，可先推进 F06 查询合同与共用合成夹具、F11 构建/发布门禁等独立代码工作；F03/F04 保留未完成状态，整个开发目标继续进行。

## 2026-11-01 接续与候选边界

用户已恢复模拟器使用，现有专用 AVD 的实际参数与网络读回见 [设备专题](ANDROID_EMULATOR_SETUP_2026-10-27.md)。本节取代前文作为“当前”的设备暂缓结论；历史失败和各批证据保留。主应用和真实服务仍未升级。

10-27 的 [218 文件冻结候选](../artifacts/android-development-20261027/attempt2/summary.json) 已通过 Java 17 的 Debug/QA 构建、177 条 JVM 与 lint，以及 [QA 打包核验](../artifacts/android-development-20261027/attempt2/qa-packaging.json)。仅 QA 目标包含标准 coroutine test 服务发现依赖，生产 Debug 不引入测试框架；不采用异常吞掉或放宽 FileProvider 导出。原 7 条暂存数据在独立 QA 实跑通过，之后增加的第 8 条曾失败于授权测试前置条件，不能称 8 条通过。

10-30 的真实外部相机在冻结 QA 包完成 prepare/verify 各 1 条协议通过：相机前台下仅终止已核验 UID/PID 的 QA 进程，等待实际照片写入，再在新 PID 明确重读。原照片 75,850 字节，原始文件与加密原图 SHA-256 相同，暂存已清理，财务内容不变且模型请求未增加。[10-31 独立解析证据](../artifacts/ai-review-android-capture-20261030/process3/independent-verification-20261031.json) 已再次核验这些事实。整个驱动的 shell 退出码在重连后未保留，不补写为 0；两阶段日志和结果文件独立成立。

测试运行器必须 `--no-restart` 附着已启动 QA 进程，准备后保留真实相机；普通 instrumentation 结束会 force-stop 目标并撤销暂时 URI 授权。正常 `am kill` 对尚未缓存的进程不保证退出，当前驱动仅在核验 QA 完整 cmdline/UID/PID 后由自身 UID 结束该 PID，绝不终止主应用。验证前等待相机完成与实际字节，避免过早启动新 instrumentation 撤销相机写入授权。

源码新增逐 URI 撤销后再清理暂存，不影响其他拍照操作。第 8 条通过 QA-only 包可见性与 API 35 `checkContentUriPermissionFull` 验证两个 URI 的独立 grant/revoke，当前修正尚待本批新包实跑。系统图片生产测试已扩到 9 条，新增坏暂存重试、旧版本保护、坏 journal 与已加密但清理失败；来源 expected 恢复新增 4 条。上述新增源码及 F05 原定位接线正在统一构建，不能把旧候选相机事实或旧 7/5 条通过当作本批完整验收。

F03/F04 尚须本批新候选的数据、系统图片、相机新 PID 与完整生产页面/四角色/旧记录回归。完整适配、真实文件、CI、签名与发布按 [当前完整计划](NEXT_DEVELOPMENT_PLAN_2026-11-01.md) 继续，不把设备配置、协议或构建等同整个功能完成。

2026-11-01 本批统一门禁与设备接续：[236 文件候选](../artifacts/android-development-20261101/attempt1/summary.json) Java 17 Debug/QA 构建、lint 与 239 条 JVM 全部通过，完整源文件副本和 QA 打包证明已保存；[43 条设备数据专项](../artifacts/android-development-20261101/device-data1/summary.json) 实际通过，包含当前拍照 8 条与旧关联隔离 6 条。主应用未安装或升级，真实服务未部署。报告首次真实 HTTP 3 方法在预览响应解析失败，仍须修正/新候选复验；生产首页/目标页面、系统文件与整个 F03/F04/F05/F08 均不因此关闭。
