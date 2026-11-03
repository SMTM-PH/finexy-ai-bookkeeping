# Android AI 账本 API 合同

日期：2026-10-10。对应 [后续计划](NEXT_DEVELOPMENT_PLAN_2026-10-10.md) 的 F03 第一段：账本 API 和严格解析已完成隔离专项。本文保留 API 切片当日的验收边界；后续 Room/备份、持久请求及生产 Worker 专项见 [2026-10-19 接续](AI_REVIEW_ANDROID_STORAGE_2026-10-19.md)。原图持久恢复与 F04 页面继续接续，主 Android 与真实服务未升级。

## 实现与边界

- [FinexyApi.kt](../android/app/src/main/java/com/finexy/mobile/data/FinexyApi.kt) 增加账本能力、完整分页、读取原条目、持久创建接口、原子确认、忽略/恢复/删除、文字识别和 multipart 图片识别；显式传递账本，包括默认个人账本 `0`。创建和确认发送调用方提供的原始请求字节，不生成替代稳定键，不回退到旧普通新增或 `resolve`。
- [AIReviewLedgerContract.kt](../android/app/src/main/java/com/finexy/mobile/data/AIReviewLedgerContract.kt) 使用独立模型保留账本、提交人、实际确认流水、权限及删除回执。字符串 ID 解析为 Long，不经过浮点；负支出退款、转账双金额和未匹配字段保留，不填首个账户。
- 回包必须有字面量 `success: true` 和完整 `result`；拒绝错误账本/对象、数值 ID、乱序/重复游标、状态不一致和不完整确认回执。完整分页成功后才交出快照；后页失败不能返回部分数据，超过读取上限显式失败。
- 网络返回后复核捕获的服务器与登录令牌；切换会话期间到达的旧回包不得交给调用方。JSON 解析异常替换为不包含原文的错误，避免把回包正文保存到后续草稿错误字段。
- 能力要求可靠创建、原子确认、内容绑定与删除回执；识别额外要求账本上下文。旧服务不满足时明确失败，原内容的持久保留仍由下一段 Repository/Room 实现。
- 创建回执按服务端 Unicode 空白与 `omitempty` 规则核对来源、错误和识别数据；确认检查账本/条目、字段集合、128 字节稳定键、金额、账户方向、时间转换范围、时区、标签/图片与位置。
- 现有默认账本 API 和页面保留关联回归；**新 API 尚未替换生产 SyncEngine 的旧 AI 入账链**。不能将这次 API 专项视为持久恢复或生产页面验收。

## 隔离环境

Java 17：`C:\Users\abc123\.jdks\ms-17.0.15`；SDK：`E:\Android\Sdk`。安装仅 `adb -s emulator-5554 install -r` 独立 `com.finexy.mobile.qa` 和 `.qa.test`，未安装/卸载主应用、未清主应用数据。

| 目标 | 候选及约束 |
| --- | --- |
| 被测服务 | `finexy-ai-mobile-source:20261007-root2`，ID `sha256:0549baad737ffb95f06fcd2abd1ed05e97664391fc9ce7ef4952564f1b429fc3`；`127.0.0.1:18139`，无卷，UUID server 255，随机注册账号 |
| 模型夹具 | `finexy-ai-recognition-mock:20261005-root1`，ID `sha256:30ad4a77748749840966cb84820259ec631fe479131bff899f5f6e82d8eb8ae2`；`127.0.0.1:18140`，无卷，只处理合成来源与 PNG |
| 模型配置 | 仅被测容器的 `data/ai-configuration.json` 指向隔离网络内夹具；未调用外部模型，未改变用户服务配置 |
| 模拟器 | 现有 `Pixel_8_API_35` / `emulator-5554`，未新建或 wipe；QA 私有偏好与测试数据库不使用主账本 |

本轮使用已有冻结服务候选验证 Android 调用合同，未修改 Go/Web 功能，未重新构建该服务候选。运行前核对实际镜像、无卷、端口和环境；结束按本轮容器/网络 ID 清理，保留镜像与证据。

## 结果与证据

证据目录：`artifacts/ai-review-android-api-20261010/`。新增 [数据合同测试](../android/app/src/androidTest/java/com/finexy/mobile/data/AIReviewLedgerApiContractTest.kt)、[真实 API 专项](../android/app/src/qaAndroidTest/java/com/finexy/mobile/data/AIReviewLedgerApiE2ETest.kt)，并在 [严格 instrumentation 解析器](../.github/scripts/verify-android-instrumentation.py) 固定类、方法和数量，拒绝失败、跳过、缺方法及截断。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| QA assemble / AndroidTest / lint | 通过，lint 0 错误 / 20 警告 | 最终源码 `build-session-qa.log` |
| Debug assemble / AndroidTest / lint | 通过，lint 0 错误 / 20 警告；仅构建，未安装主包 | 最终源码 `build-session-debug.log` |
| 严格解析专项 | 12/12 | `session-run/ai-ledger-api.log` |
| 真实 Android HTTP 专项 | 3/3 | `session-run/ai-ledger-api-e2e.log` |
| 关联回归 | 14/14：旧 AI 3、模板持久化 7、SyncWorker 2、LedgerScope 2 | `session-run/ai-ledger-api-regression.log` |
| 结果解析器自检 | 通过，坏输出均拒绝 | `protocol/`、`protocol-e2e/` |
| 主应用只读冷启动 | 前后有效流水均 0，实际前台运行，无该 PID 致命崩溃；主包仍 `1.0.1` / `10902` | `main-cold-start-final.json`；不据此宣称遗失流水已恢复 |
| 来源与构建标识 | 保存源码/APK SHA-256，已安装 QA APK 与构建哈希一致 | `source-apk-sha256-session.json`、`isolation.json`、`summary-final.json` |

三组真实 API 覆盖：

1. 默认 `0` 与 A/B 隔离、稳定创建与内容冲突、原子退款重复确认、跨币种内部转账及外部双向转账、余额只变化一次、忽略/恢复、删除回执重放、101 条完整分页。
2. 所有者/管理员/普通成员/只读成员的真实读写权限，管理员确认、普通成员仅处理自己的条目、外部用户拒绝、错误账本对象读取拒绝、确认前降权不入账。
3. 当前账本文字与 multipart 图片请求、只读成员识别、真实账户/分类映射、负退款识别与创建回执往返；夹具核对所选资源必需、其他账本账户不可进入上下文，基础文字/图片阶段 2 次模型请求、违规 0；识别与保存待复核不改变余额。随后另行实际挂起一次识别、切换服务器、放行回包，验证旧会话结果被拒绝。

初始 `build.log` 的四处 Kotlin 声明错误已修复，失败日志保留。`api-e2e.log` / `api-e2e-diagnostic.log` 的确认失败已定位为夹具期初余额与流水在同秒的序列边界，服务正确拒绝“在余额调整之前记账”（205028）；将合成期初时间固定到更早日期后通过，未放宽服务保护。初始失败不计入通过数。

复跑使用 [守卫脚本](../.github/scripts/verify-android-ai-ledger-api.ps1)：要求上述隔离服务与模型夹具已配置并运行、当前 QA 包已安装；核对镜像 ID、无卷、端口、环境及模型配置只指向夹具；脚本不启动/停止服务、不安装主包，固定模拟器与三套测试范围。使用新的证据目录，保留旧结果：

```powershell
.\.github\scripts\verify-android-ai-ledger-api.ps1 -EvidenceDirectory artifacts/ai-review-android-api-20261010/recheck-01
```

## 未完成范围与下一步

1. 新增账本隔离 AI 缓存、原始来源/编辑草稿、不可变请求与回执的 Room 表及 migration，更新备份兼容；旧队列归属和旧确认状态不能静默推断。
2. Repository 原子持久化与版本核对、SyncEngine/WorkManager 专用投递；未知结果重放原请求，已知回执只读恢复，并迁出旧“普通新增后 resolve”路径。
3. 活动重建、实际丢回包、数据库重开和新 PID Worker 证据；本次 API 重复调用不代替这些恢复场景。
4. F04 生产来源、裁剪、复核、确认与 F05 待处理提示；主题/字号/横屏/TalkBack/指纹，真实模型质量、正式签名、主应用升级及服务部署尚未验收。

下一段继续 F03 持久层，不把 API 切片通过标为整个开发计划完成。
