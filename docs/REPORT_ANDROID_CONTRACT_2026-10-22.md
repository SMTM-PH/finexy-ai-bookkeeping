# Android 报告数据合同与 API 接续

日期：2026-10-22；controller 核验于 2026-10-23，生产接入源码更新于 2026-10-26。对应 [后续计划](NEXT_DEVELOPMENT_PLAN_2026-10-26.md) 的 F08。目前完成只读数据合同、网络方法、内存 controller、生产页面/生命周期及系统文件代码的本地 JVM/构建门禁；实际 HTTP、生产页面与系统文件设备验收未完成。设备继续按用户“先继续代码和构建，设备稍后使用”安排暂缓。

## 实现范围

- [LedgerReportContract.kt](../android/app/src/main/java/com/finexy/mobile/data/LedgerReportContract.kt) 定义查询、页、明细、原币种合计/分组、签名预览和经校验的下载字节；[FinexyApi.kt](../android/app/src/main/java/com/finexy/mobile/data/FinexyApi.kt) 接入查询、预览和 CSV 三个既有端点，沿用登录会话，不新增令牌或财务写入。
- ID、整数最小单位金额、Unix 秒及序号从十进制字符串读取为 `Long`；合计中间运算用 `BigInteger`，拒绝最终越界。两位小数展示不经过浮点；调整差额与目标余额分离，转账双账户/双金额及负数退款保持原义。
- 查询保持当前账本、IANA 自然日及五维筛选，保留默认账本 `0` 和精确无分类条件 `0`。整页验证资源归属、完整源标志、分页/快照、排序、逻辑/物理 ID 与分组；累计分页继续绑定原 hash，不将部分明细当全量。
- 预览绑定所见快照和到期时间，下载需明确确认，隐藏金额另行确认。网络方法在请求与响应后复核服务器、身份和上下文，禁止重定向；下载还核对请求会话、确认到期、HTTP 200、附件头、UTF-8、37 列 CSV、原始整数/主单位金额、已知明细及汇总。上限为 128 MiB，实际设备内存仍需测量。
- 不新增 Room 表、migration、备份字段、依赖或 APK 夹具资源；不持久化报告内容或预览。返回下载字节不表示系统文件已保存。API 不能自行观察界面当前账本；controller 已提供上下文失效控制，生产页面仍须在账号、服务器、账本或模式切换时绑定，并在离开时关闭，不能仅创建实例后不接生命周期。

## Controller 接续

[LedgerReportController.kt](../android/app/src/main/java/com/finexy/mobile/data/LedgerReportController.kt) 新增 `LedgerReportTransport` 及调用既有三个 API 方法的生产适配器。单一 `StateFlow` 发布当前账本的来源、操作、页、已加载明细、导出确认及固定中文错误状态；只保存在内存，不接 Room、备份、SavedState 或本机流水。完整在线合计与“明细尚未分页读完”分别表达，离线、本机模式、同步中不冒充完整在线报告。

- 每次绑定（包括字段相同的上下文）、重读、翻页、取消及关闭均按对应操作使旧确认或请求失效；请求前后复核当前服务器、身份与账本，迟到成功、失败或下载不能覆盖新会话。新 controller 不恢复旧金额或导出证明。
- 续页沿用原 hash，校验页元数据、重复逻辑/物理 ID；失败会清除原报告及确认，避免旧金额继续显示为当前结果。`0` 是明确的个人账本上下文，不回落到另一账本。
- 导出只接受用户所见的同一个确认对象；即使两次预览字段相同，旧对话框也不能确认新预览。需要明确确认，隐藏金额另行确认，并在下载前、返回后及领取字节时复核到期和会话。
- 下载向生产 API 的 CSV 校验传入所有已加载明细，不仅首个网络页。返回可领取一次的当前会话 delivery，字节不发布到 `StateFlow`；提示明确为“尚未取得系统文件保存回执”。系统目标选择、写入、取消、重新打开与分享仍未接入。
- 401、403、404/405、409、合同异常与网络失败分别映射为可区分状态；不把原始响应、私有网络诊断或财务内容放进错误提示。

以上是 2026-10-23 的 controller 阶段合同，当时尚未接入生产报告页面。`currentContext` 的检查依赖页面提供实时上下文，模式切换与离开页面也必须由使用方触发；后续生产接入见下文，JVM 的人工绑定不能证明实际页面已覆盖这些事件。

## 实际验证

Java 为 Microsoft OpenJDK 17.0.15，实际执行：

```powershell
.\android\gradlew.bat -p android :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

数据合同阶段候选构建成功，10 个 JVM 测试类共 80 个测试，0 失败、0 错误、0 跳过；其中新增 [LedgerReportContractTest.kt](../android/app/src/test/java/com/finexy/mobile/data/LedgerReportContractTest.kt) 为 11 个测试。直接读取仓库共用 19 组财务筛选 oracle 与 12 组 IANA 日期夹具；基准合计/分组、分页/快照、历史调整差额、超过 `2^53` 的金额、宽整数抵消、损坏 JSON、预览/隐藏确认及 CSV 正反例另有断言。19 组筛选并非 19 次 Android 真实 HTTP。

该阶段日志、XML、源码和 APK 哈希保留在 `artifacts/android-report-contract-20261022/summary.json`；最初测试编译失败及修正日志未删除。该批 APK 是数据合同阶段候选，不与后续 controller APK 混用。

接续 controller 后，同一命令的最终日志为 `BUILD SUCCESSFUL in 42s`，退出码 `0`；11 个 JVM 测试类共 **101 个测试，0 失败、0 错误、0 跳过**。新增 [LedgerReportControllerTest.kt](../android/app/src/test/java/com/finexy/mobile/data/LedgerReportControllerTest.kt) 为 21 个测试，使用可挂起的类型化 transport 替身覆盖上述竞态、分页、确认、错误与 delivery 合同，不是 Android 真实 HTTP，也没有运行系统文件操作。替身返回的是已校验下载对象，不能把它当作真实 CSV 字节校验或网络头验收；CSV 解析正反例仍属于数据合同测试范围。

第一次 controller 门禁为 101 个测试、2 个失败：两个续页测试在构造替身页时漏传必需的 `expectedHash`，在进入 controller 前即抛错。失败日志、该次 controller XML 和源码哈希均保留；controller 与测试的最终哈希均不同于首次候选，验收只采用最终候选的通过结果，不将失败批次改写为通过。

2026-10-23 已核对保留日志、退出码、全部最终 XML，以及 gate 前记录的 controller、controller 测试、数据合同、API 与 Gradle 配置五个源码哈希，均与当前文件一致；本次核验没有重复执行已经成功的 Gradle 门禁。归档 APK SHA-256 为 `d4108fc9661802097cead800e8554e5cf218adf3ecaaac3c9da3f9ef6c8c2188`，与构建输出一致。五文件比对不是整个 Android 源码树的 gate 前快照，不能扩展这一证明范围。

controller 最终证据汇总为 `artifacts/android-report-controller-20261022/summary.json`，其中记录两轮结果、五文件哈希、11 份 XML、APK 哈希和未运行项目；原始文件与归档 APK 一并保留。Lint 门禁完成，报告任务为 `UP-TO-DATE`，保留报告仍有 0 条 Error、5 条 Warning 和 21 条 Information，不宣称无告警。APK 仅归档，没有安装，没有 ADB、设备运行或真实账本操作；也没有正式发布签名与部署。

2026-10-23 另执行 Java 17 / SDK 35 下的 `:app:assembleDebugAndroidTest --offline --console=plain`，退出码 `0`，`BUILD SUCCESSFUL in 1m 24s`。50 项任务中 3 项执行、47 项使用增量结果；AndroidTest Kotlin 实际编译，打包任务为 `UP-TO-DATE`。八文件构建前后哈希一致，其中五文件与上述 101 条 JVM 候选相同。测试 APK 仅归档并校验 ZIP，SHA256 为 `034038eddc5114465729f9159f26a506167fb71662ee0d6f910c7c4807deabb1`；证据为 `artifacts/android-report-controller-20261023-test-apk/summary.json` 和 `verification.json`。保留 SDK XML 工具版本警告；没有安装或执行 instrumentation，不计设备通过。

## 后续出口

1. 在无卷一次性服务和随机账号上实际调用上述 Android 网络方法，覆盖完整分页、四角色、旧服务、撤权、数据变化、快照与预览失效；本地解析测试不能替代网络接入验收。
2. 验证已接入现有导航的生产报告页面、当前账本和查询/金额模型，实际核对绑定、模式变化与关闭事件；完整在线、同步中和离线来源须由真实页面验收。
3. 验证已接入的系统文件目标选择、取消/失败和分享；只有实际写入成功并可重新打开才显示保存成功，不能把字节返回当作系统回执。
4. 用户安排设备可用后补返回/离开、旋转、进程恢复、迟到响应、主题/字号/安全区和读屏，以及实际保存/打开/分享；F03/F04 的 AI 恢复优先收尾。
5. 规模/峰值内存、正式签名、保留数据升级及发布门禁继续按 F10–F13 执行。F08 和完整开发计划均未关闭。

原生产接入顺序（2026-10-26 已据此接入源码，动态出口仍待补验）：先以独立内存 `ReportViewModel` 包装 controller，并把实时服务器、会话、账本、在线模式和实际同步过程接成可观察上下文。现有 `SecureStore` 无会话 Flow，仅在重组时重算身份不足；部分直接 `SyncEngine.sync()` 路径也不能仅凭 Room 上次状态代表“同步中”。随后按 `MainActivity.kt` 的现有二级页面模式从总览/账户进入报告，保持五项底栏。旧 `StatisticsCard` 的本机列表、`Double` 汇总和七列文字 CSV 不可充当新合同结果。

系统文件优先复用 `CreateDocument`，在回调时核对实时上下文后领取当前 delivery；成功写入、关闭并重新读取核验后才提示保存成功。取消、过期或失败有独立状态。优先分享已保存的同一 document URI，并传 `EXTRA_STREAM`、`ClipData` 和临时只读权限；启动选择器只能说明已交给系统。外部文件选择器和旋转不应被简单的 `ON_STOP` 当成内部离页，同时不能让迟到回调跨身份写入。

## 2026-10-26 生产页面与生命周期源码

[LedgerReportPage.kt](../android/app/src/main/java/com/finexy/mobile/LedgerReportPage.kt) 已从总览和账户进入现有二级页，不新增底栏。页面使用统一查询、固定顺序的五维筛选、服务端元数据及整数结果，显示原币种合计、每日趋势、分类与标签组、分页明细、分组下钻和完整自然月的上月比较。原账户页本机统计明确标为部分本机数据，不能替代该在线报告。

[LedgerReportForm.kt](../android/app/src/main/java/com/finexy/mobile/data/LedgerReportForm.kt) 规范化查询；ID 不经过浮点，账户/分类支持包含、排除及子级，标签条件按合同相交，无分类精确为 `0`。下钻保留其余筛选及币种，比较仅允许完整自然月且保持所有非日期条件。编辑筛选立即失效旧结果、比较、详情和导出确认，用户明确读取后才执行新查询。

[LedgerReportViewModel.kt](../android/app/src/main/java/com/finexy/mobile/LedgerReportViewModel.kt) 包装 [生命周期核心](../android/app/src/main/java/com/finexy/mobile/data/LedgerReportLifecycle.kt)。`SecureStore` 为服务器、令牌和本机模式变化发布进程内修订号，按 namespace 隔离，不广播凭据值；实际同步和互斥写入由 [SyncActivity](../android/app/src/main/java/com/finexy/mobile/data/SyncActivity.kt) 发布活动及修订号。网络使用实际活动网络而非公网探测结果，支持局域网服务；新增 `ACCESS_NETWORK_STATE` 权限。旧环境失效立即清结果、确认和 delivery，不自动读取新报告。

内部返回/离页清会话，相同 Activity 旋转重挂接保留内存会话，系统文件选择器的 `ON_STOP` 不被当作离页。筛选草稿和金额可见偏好也只保存在内存状态持有者，避免“用户隐藏后旋转又显示”；不把报告、确认或文件 URI 放进 Room、备份或 SavedState。真实旋转、进程退出及系统返回行为仍需设备核验。

文件流程使用 `CreateDocument("text/csv")`；delivery 与原下载操作 ticket 原子绑定，回调必须再核对，且字节只能领取一次。[LedgerReportDocumentWriter.kt](../android/app/src/main/java/com/finexy/mobile/data/LedgerReportDocumentWriter.kt) 在写前、分块、独立读回和结束核对环境，关闭输出后再按完整字节数与 SHA256 校验。只有验证成功才提示保存成功并提供打开/分享同一 document URI；失败提示文件可能部分写入，取消不写。分享使用 `EXTRA_STREAM`、`ClipData` 与临时只读权限，不把选择器启动当成接收成功。

最终本地门禁实际运行：

```powershell
.\android\gradlew.bat -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain
```

Java 17 / SDK 35 下终态退出码 `0`，`BUILD SUCCESSFUL in 2m 16s`。206 个 Android 源码/配置文件构建前后清单一致，SHA256 `f841fcc766ceb81cde3f05fb7fc46ed9146b4d8db28f822af0fa45eadfa9d087`。16 类共 **133 条 JVM 测试，0 失败、0 错误、0 跳过**；相比 101 条 controller 候选，新增生命周期 14 条、筛选/下钻/日期 6 条、文件 writer 5 条、实际同步 tracker 4 条和会话通知 registry 3 条。这些使用 JVM 内存流或类型化 transport，不代表真实 Android HTTP、SAF 或页面交互。

证据为 `artifacts/android-report-production-20261026/attempt2/summary.json`，含完整 before/after、XML、命令、日志与 APK。Debug APK SHA256 `71ab2eaa5e830b1ccc82ff19a738a3278eeb15f5e88ba992ebdecda1cae110ba`；测试 APK 为 `034038eddc5114465729f9159f26a506167fb71662ee0d6f910c7c4807deabb1`，后者增量打包仍为前次相同字节，不冒充新增 UI 自动化测试。两包 ZIP 校验通过，均未安装。Lint 为 0 Error、5 Warning、22 Information，保留告警。此前 attempt1 的 Gradle 虽成功且 132 条测试通过，但执行期间源码仍变动，已明确标为非最终候选并保留，不能替代 attempt2。

本批没有 ADB、设备探测/运行、主账本操作、真实服务部署或正式签名。实际 HTTP/旧服务/撤权、系统选择器、旋转、文件打开/分享、取消/失败及完整适配/读屏继续按上述出口补验，F08 尚未关闭。


同一冻结源码随后已构建独立 QA 变体：`:app:assembleQa :app:assembleQaAndroidTest -PfinexyIsolatedQa=true --offline --console=plain`，Java 17 下退出码 `0`，`BUILD SUCCESSFUL in 1m 17s`，68 项任务中 15 项执行、53 项增量。206 文件 before/after 仍与 Debug 最终候选完全一致。证据为 `artifacts/android-report-qa-build-20261026/attempt1/summary.json`；应用包 `com.finexy.mobile.qa`、测试包 `com.finexy.mobile.qa.test` 及 instrumentation 目标 `com.finexy.mobile.qa` 已用 aapt 核对，两包 ZIP/Manifest/classes.dex 通过检查。应用 APK SHA256 `c06c219eea3efa77da17443126178d427b601669eaed3f8642a249607ac3b42f`，测试 APK `6e3122b3acba375b4f3150932f4b8e4d6bfd65a129e6572a64be00c62c36a781`。仅编译归档，没有安装或运行 instrumentation，不能计作 QA 设备验收。

## 真实 HTTP 测试代码准备（仅编译）

新增 [LedgerReportApiE2ETest.kt](../android/app/src/qaAndroidTest/java/com/finexy/mobile/data/LedgerReportApiE2ETest.kt) 三组测试代码，直接调用生产 `FinexyApi` 的 query、preview 和 download，不使用 transport 替身。它们计划验证 A/B/0、CNY/USD 原币种、退款、内部双币及外部转账、公开 API 能创建的开户调整、分页原 hash、真实 CSV、四角色/撤权、快照变化和 token/服务器/账本/本机模式的旧上下文拒绝。

测试只允许 `com.finexy.mobile.qa`，要求显式 disposable 标记和固定专用地址；将来设备由用户安排可用后，宿主侧还须先启动并核验 owned 无卷隔离服务，不能仅因该参数存在便相信目标安全。运行参数为 `finexy.e2e.url=http://10.0.2.2:18155` 与 `finexy.e2e.disposable=true`，缺 URL 的代码按设计使用 Assume 跳过；本批没有运行，不能说实际观察到 skip 或 pass。账号和 SecureStore namespace 随机生成，仅清理本轮 namespace，不访问主账本。

Java 17 下仅执行 `:app:assembleQaAndroidTest -PfinexyIsolatedQa=true --offline --console=plain`，实际退出码 `0`，`BUILD SUCCESSFUL in 35s`；50 项任务中 5 项执行、45 项增量。207 文件构建前后 SHA256 `9ff51033796d1518c849ea5ae18566e57f38325953e5b7e09e19a8dbcb9e789e` 一致，旧 206 文件逐一匹配，仅新增该 QA 测试。测试 APK SHA256 `fe34273146266242552ae05459c66f662e483f6d0776a9afd3b2726f17c22a1d`，包名、目标 QA、ZIP 及 DEX 中的测试类/三个方法均核验。证据为 `artifacts/android-report-http-tests-20261026/attempt1/summary.json` 和 `code-verification.json`，保留 Kotlin daemon 首次启动异常后 Gradle 继续成功的日志，不宣称无告警。

上述是测试代码和编译证据，没有执行 HTTP、instrumentation、ADB 或设备操作。三组不能冒充共用 19 组 oracle：本批用真实业务 API 合成独立案例；历史目标余额与调整差额不等、旧服务、离线网络、Compose、SAF 和设备生命周期仍须独立补验。133 条 JVM 结果属于此前生产源码候选，本次仅确认旧文件未变，没有重跑这些测试。


当前页面源码仍有两项明确的交互接续：环境修订（包括同账本的断网/同步）会把筛选草稿重置为本月，应区分身份/账本变化并保留同上下文的用户筛选；文件写入时内部返回已被逻辑阻止，返回按钮还需同步 disabled 语义及可见状态。它们属于生产页面与 F10 的待修正/验收范围，不因本地构建通过而关闭。

## 2026-11-02 真实 Android HTTP 接续

设备已恢复使用，实际协议严格拒绝 Assume/跳过、缺方法和中断。首次 [11-01 HTTP3](../artifacts/android-development-20261101/report-http1/summary.json) 全部停在预览解析；[时钟探针](../artifacts/android-development-20261101/report-http1/clock-comparison.json) 证明模拟器比宿主/Docker 慢约 1.6 秒。Go 签发期限为服务端时钟加 600 秒，客户端不能再用本机时钟要求“剩余不超过 600 秒”。已移除这个上界，保留正 int64、未到期、原查询/快照/金额/隐藏确认和全部服务端签名、期限与权限校验；新增 601/630 秒设备时差及负例的 JVM 回归。

修正后 [11-02 首轮](../artifacts/android-development-20261102/report-http1/summary.json) 仅剩币种断言失败：独立用例误要求 USD 筛选只留下 USD 合计。共用 `currency-either-leg` oracle 明确按整笔逻辑转账匹配，必须保留 CNY 转出与 USD 转入；已修正测试，未改财务实现或删掉转账双侧断言。新 [测试版本与实际 DEX](../artifacts/android-report-http-tests-20261102/currency-oracle/code-verification.json) 单独归档，旧失败及旧 metadata 不改写。

[最终 HTTP3](../artifacts/android-development-20261102/report-http2/summary.json) 已在真实生产 FinexyApi、专用 `18155` 无卷服务和随机合成账号全部通过，无跳过；QA 双包实际安装 SHA 与 [冻结候选 attempt3](../artifacts/android-development-20261102/attempt3/summary.json) 相同。Java 17 Debug/QA、lint 与 240 条 JVM 通过，完整源码副本及 QA 打包证明存在。本次覆盖该三方法的财务语义/筛选/分页/CSV、角色与实时撤权/快照变化，以及旧 session 上下文拒绝；不冒充共用 19 组真实 HTTP 全集、历史不等调整差额或旧服务/离线。宿主与设备证据分开，临时报告容器/网络已清理。

同上下文筛选草稿保留与写文件返回禁用早已接入冻结构建；前文末尾的“仍需修正”保留为旧候选状态，当前剩余为实际生产入口、SAF 保存/打开/分享/取消/失败、重建及无障碍。对应导航 3 方法和 SAF 草稿正在独立编写，尚未整合编译或设备验收；主应用、真实服务均未升级，F08 完整出口仍未关闭。
