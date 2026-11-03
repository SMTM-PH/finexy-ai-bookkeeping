# Android AI 来源与原子复核接续

日期：2026-10-20。承接 [原图持久恢复](AI_REVIEW_ANDROID_IMAGES_2026-10-19.md) 与 [后续计划](NEXT_DEVELOPMENT_PLAN_2026-10-19.md) 的 F03/F04。本文记录生产页面接入与实际隔离验证；F03/F04 尚未全部完成，主应用和真实服务未在本批升级。

同日后续的 [系统图片入口与活动恢复](AI_REVIEW_ANDROID_NATIVE_IMAGES_2026-10-20.md) 已补真实相册/相机、取消、超限明确重试，以及导入任务的 Activity 重建专项。下文 153 项及其候选保留原批次范围，后续候选与关联回归单列，不累加复跑。

## 页面与状态合同

- [主入口](../android/app/src/main/java/com/finexy/mobile/MainActivity.kt) 已使用当前账本的 [来源页](../android/app/src/main/java/com/finexy/mobile/LedgerAISourcePage.kt) 与 [复核队列](../android/app/src/main/java/com/finexy/mobile/LedgerAIReviewPage.kt)，支持默认个人账本 `0` 和选定共享账本；保持五项底栏。进入来源再返回时保留手动记账的未完成输入，不自动提交。
- [来源会话](../android/app/src/main/java/com/finexy/mobile/data/AIReviewSourceSession.kt) 将原始文字、原图引用、裁剪/旋转与建议结果保存到同一版本草稿；按服务器、用户、账本隔离。[ViewModel](../android/app/src/main/java/com/finexy/mobile/AIReviewSourceViewModel.kt) 接续 Activity 重建中的保存与识别任务。重新打开先明确继续编辑，恢复不发送模型。
- “发送识别”“保存待复核”“确认入账”分别确认。模型建议不直接创建财务流水；只读成员可识别和保留本机来源，不能创建服务器队列或入账。取消、迟到响应、身份变化和旧预览不能覆盖较新内容。
- 图片通过有界读取接入原图加密存储，发送用图片按原始裁剪/旋转生成。相机缓存仅在成功加密保存后清理；失败保留重读入口。当前页面专项使用预置的合成原图，真实相册/相机返回仍待补验。
- [复核编辑器](../android/app/src/main/java/com/finexy/mobile/LedgerAIReviewEditor.kt) 与 [字段合同](../android/app/src/main/java/com/finexy/mobile/data/AIReviewEditorForm.kt) 保留原始金额、真实账户/末级分类、日期、IANA 时区、UTC 偏移、标签及其他既有字段。退款保留负支出，内部及外部转账保留对应账户和双金额；未匹配项保持待选择，DST 缺失/歧义须明确处理。
- 预览确认保存不可变请求，使用原子确认端点；前台与生产 Worker 通过同一同步互斥和回执合同投递。未知结果只重试原快照，已知回执只重新读取；条目不在当前列表时仍能恢复本机请求和草稿。
- 已有旧版 AI 关联且未上传的流水继续隔离，禁止普通新增与新原子确认重复投递。缓存刷新不解除这些关联；入队和实际发送前均复核。历史记录的完整核对与处置流程仍需接续。

## 图片识别配置修复与构建修复

[路由注册](../cmd/webserver.go) 原先只在服务启动时图片能力已开启的情况下注册图片识别接口；运行中开启配置后仍返回 404。现改为固定注册，由现有处理器检查实时功能配置、身份与权限。

[实时配置专项](../.github/scripts/verify-ai-image-hot-configuration.mjs) 已在新候选完成 8 项检查：启动关闭时返回结构化拒绝；运行中开启、关闭、再次开启均立即生效；关闭图片不影响文字；匿名请求不调用模型；识别不创建队列或改变财务数据；整个切换过程的 PID 和启动时间不变。合成模型只输出请求数与约束违例数，测试不保存凭据或提示词。

后端隔离构建曾遗漏根目录 `testdata/` 中的 Excel 夹具，导致三个转换测试包失败。补齐受控夹具后全套通过；同时修复两处 `excelize.OpenReader` 出错后仍对空对象执行 `Close` 的崩溃，新增空文件、非工作簿和截断 ZIP 的拒绝测试。保留失败日志，不将修复前的运行计为通过。

## 候选与已运行专项

本机证据目录：`artifacts/ai-review-android-source-20261019/`，部分恢复检查跨日完成。该目录通常被 Git 忽略，交接时须另附脱敏汇总。

| 检查 | 实际结果与范围 | 证据 |
| --- | --- | --- |
| 来源数据 | 15 项通过；原始输入/恢复、取消/迟到、只读识别、版本/权限、裁剪编码、退款/跨币种、DST、旧关联隔离 | `ai-source-data-final.log`、`ai-source-data-final-verified.json` |
| 原图 | 10 项通过；迁移、分块加密、备份、引用、替换/清理及损坏拒绝 | `ai-images-final.log`、`ai-images-final-verified.json` |
| 关联数据回归 | 116 项通过；AI API/存储、同步、旧迁移与备份、隐私、模板、周期、账本写入 | `ai-images-regression-final.log`、`ai-images-regression-final-verified.json` |
| 生产页面 | 8 项通过；所有者明确识别/保存/原子退款一次、只读识别、管理员状态操作、成员自有范围、预览后撤权、图片恢复/裁剪/识别、手动输入接续、深色横屏 2.0 系统字号的操作可达 | `ai-source-ui-e2e-final.log`、`ai-source-ui-e2e-final-verified.json`、`ui-final/` |
| 账本导航 | 2 项通过；共享队列不暴露默认条目、默认原子确认、切账本保留原始编辑且不注入其他队列 | `ai-review-navigation-final.log`、`ai-review-navigation-final-verified.json` |
| 真实新进程恢复 | 两阶段通过；成功回包实际丢失，prepare 退出并终止 App，新 PID 生产 Worker 重放原创建/确认各一次，已知回执只读，独立 verify 核对余额和图片生命周期 | `process-final-20261020/summary.json`、`prepare.log`、`verify.log` |
| 图片配置 | 8 项 HTTP 检查通过；同 PID 实时配置与无财务副作用 | `image-hot-configuration/summary.json` |
| Linux Go | `CGO_ENABLED=1 go test -json ./...` 通过，48 个有测试包、1538 个测试事件（含子测试），0 个测试跳过；另 18 个包无测试文件 | `go-tests-final.jsonl`、`backend-candidate-final.json` |
| Web | `npm run check` 退出 0；类型、lint、34 个文件/38782 项测试及生产构建通过 | `web-check.log`、`web-check-exit.json` |
| Android 构建 | Java 17，QA/Debug 的 assemble、AndroidTest 与 lint 任务通过；两种 lint 均 0 错误、20 警告，仅安装独立 QA 应用/测试包 | `build-qa-final.log`、`build-debug-final.log`、`installed-qa-final.json` |

以上 Android 数据、页面及恢复共 **153 项测试/阶段检查通过**，按 15 + 10 + 116 + 8 + 2 + 两阶段 2 统计，不累加复跑。新 PID 恢复以 `process-final-20261020/summary.json`、原始阶段日志及 Worker witness 为依据；不把 Worker 数据层恢复泛化为全部生产页面已跨进程验收。

后端候选为 `finexy-ai-android-source:20261019-root1`，镜像 ID `sha256:55acb245ad0a04e786762dd9b5155348b663ba792457a2bec12ab84616e123fd`。其 Go 二进制来自本次受控源码上下文，前端资源继承已冻结的旧验收镜像，专用于 Android/API 隔离验证；Web 本地构建通过不能将此候选解释为全量 Web 发布包。

Android 源码仍为 `1.0.1` / `10902`、Room v26、备份 schema 20。源码、构建及已安装 QA APK 哈希分别留存，主包未安装。主机重启后原无卷 `--rm` 夹具已消失，恢复专项重新核验同一镜像和 QA APK 后使用新的随机身份；不冒充延续旧服务数据库。

结束后已清理本批临时服务和丢回包代理，运行容器仅剩原用户服务与 OCR。主应用只读冷启动后数据库完整、流水仍为 0，当前进程 crash buffer 未见致命异常；系统字号恢复为 1.0。证据见 `verification-final.json`、`main-after-final.json`、`cleanup-final.json`。这些检查不证明历史遗失数据已经恢复。

## 失败记录与未完成范围

早期 UI 脚本曾错误滚动固定底栏、把对话框标题当作按钮，已修正测试驱动。随后整组 7 项中图片项失败，定位为旧服务启动时未注册图片路由；单项重跑仅作诊断。最终 8 项及导航 2 项在修复后的新候选通过。图片配置脚本初版列表请求超出 API 的 50 条上限，已按合同修正并保留失败记录，再使用全新的合成服务配置所有者运行整组。

真实系统图片正向/取消/超限重试及导入 Activity 恢复已在上述后续专题补验。尚未完成：外部相机在前台时的进程退出/回调丢失、未保存拍照缓存的持久归属、生产页面完整跨进程恢复、识别正在进行时的全部恢复组合、各类型与四角色的完整财务操作、旧版未上传记录的明确处置。页面截图和操作断言不能代替完整 TalkBack 听读、已录入指纹成功路径及全部主题/字号/方向组合。截图还暴露默认筛选芯片配色与深色文字按钮对比度待统一，应随下一批 UI 收尾修正并复验。

主 Android、本地用户服务和 NAS 均未在本批升级。远程 CI、签名包、真实升级、部署和回退仍按 F10–F12 推进；本专题不关闭 F03/F04 或整个开发计划。
