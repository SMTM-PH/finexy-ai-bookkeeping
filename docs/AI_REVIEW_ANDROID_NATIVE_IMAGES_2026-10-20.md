# Android 系统图片入口与导入活动恢复

日期：2026-10-20。接续 [生产来源与复核](AI_REVIEW_ANDROID_SOURCE_2026-10-20.md) 和 [后续计划](NEXT_DEVELOPMENT_PLAN_2026-10-19.md) 的 F03/F04。本批补真实系统入口与导入任务的 Activity 恢复，整个开发计划仍在进行中。

## 修复与行为

[来源页](../android/app/src/main/java/com/finexy/mobile/LedgerAISourcePage.kt) 原先在页面协程中读取并加密保存图片，Activity 重建会取消这段工作。现由 [来源 ViewModel](../android/app/src/main/java/com/finexy/mobile/AIReviewSourceViewModel.kt) 持有导入任务及其状态，读取使用应用 Context，避免导入工作依赖旧 Activity。

- 导入期间阻止编辑、替换、丢弃和离开；文件读取与加密保存有明确进度。旧页面退出或身份切换时取消对应任务，结果不显示到新会话。
- 读取或格式/大小检查失败时保留原草稿、原图和所选 URI，显示可见错误及“重新读取所选图片”。只有明确点击才重试，不自动识别、创建队列或入账。
- 相机原图成功加密保存后，在不可取消的清理段删除对应私有缓存；失败保留原有重读入口。无法启动系统选择器或相机时显示说明并保留原来源。
- 不变更 Room v26 或备份 schema 20，不升级依赖，不替换系统相册/相机的返回结果。主应用及真实服务未在本批升级。

## 新增专项与证据

证据目录：`artifacts/ai-review-android-native-20261020/`。仅使用独立 `com.finexy.mobile.qa` 包、随机身份、无卷隔离服务及合成图片；模拟器后置相机配置为 `emulated`，不使用真实摄像头或用户票据。

| 专项 | 已通过范围 | 证据 |
| --- | --- | --- |
| 系统图片入口 5 项 | 真实相册选择并比对原始字节、Activity 重建恢复及预览可见；取消相册保留原图；取消相机保留原图并清理本次缓存；真实相机返回后加密、清缓存且公共相册无新增；超限拒绝后明确重试同 URI | `ai-source-native-ui-preview-final.log`、`ai-source-native-ui-preview-final-verified.json` |
| 导入生命周期 3 项 | 用生产 ViewModelProvider 和导入方法，暂停读取后重建 Activity，再继续加密提交；失败保留原图与确切重试对象；离开旧来源取消任务且不向新会话注入结果 | `ai-source-import-lifecycle-preview-final.log`、`ai-source-import-lifecycle-preview-final-verified.json` |

这 8 项新增专项已整组通过。当前 QA 候选共 **161 项测试/阶段检查通过**，按以下范围统计，单项诊断或复跑不累加：

| 相关门禁 | 实际结果与边界 |
| --- | --- |
| 来源数据 / 原图 / 关联回归 | 15 + 10 + 116 项通过，含迁移/备份、AI、同步、周期、模板、隐私及账本写入；原始日志与解析结果为 `*-preview-final.log` / `*-preview-final-verified.json` |
| 既有生产页面 / 账本导航 | 8 + 2 项通过；完整范围仍按 [来源与复核专题](AI_REVIEW_ANDROID_SOURCE_2026-10-20.md) 的角色及场景说明 |
| 新增系统入口 / 导入生命周期 | 上表 5 + 3 项通过，恢复后实际预览可见与可滚动到达 |
| 新 PID 请求与图片恢复 | 两阶段通过，见 `process-preview-final/`；成功回包实际丢失、系统调度新 PID 生产 Worker、独立 verify，不重复入账 |
| QA / Debug 构建与 lint | Java 17；QA assemble/AndroidTest 与 lint 通过，Debug assemble/AndroidTest/lint 通过，两种 lint 均 0 错误、20 警告。仅安装独立 QA 包 |
| 候选与源码 | `source-before-preview.json`、`installed-preview-final.json` 记录该批冻结源码及已安装 APK 哈希；相关专项以各自严格解析结果和 `process-preview-final/summary.json` 为准，不以尚未形成的批次汇总作为完成依据 |

系统入口保留普通动画及先前关闭动画的宽窄屏记录。加强“恢复后实际预览可见”断言后的补充矩阵中，360dp（540×1200、density 240）5 项通过，见 `native-ui-preview-narrow*` 和 `ui-preview-narrow-stable/`；宽屏复跑在拍照后的 Activity 重建等待 RESUMED 超时，后续一项缺少 Compose 层级，见 `native-ui-preview-wide.log`，该次宽屏不计通过。设备设置已由脚本 finally 恢复，见 `preview-motion-after.json`；复跑不增加 161 的计数。后续候选须重新验证这项宽屏缺口。

本批未改 Go 或 Web 产品源码，隔离后端复用已核验的 `finexy-ai-android-source:20261019-root1` 镜像。其 Go/Web 验证边界见上一批来源专题，本批不宣称重新运行这些全套门禁；图片配置脚本仅增加分目录保存证据的能力。后续修改须重新核对相关范围。

相册夹具由 shell 在公共下载目录独占创建，使用随机文件名并先检查文件/媒体行不存在。系统索引后先验证应用无权直接读取，再通过真实 PhotoPicker → DocumentsUI 选择并取得临时 URI 授权；导入后的解密字节与夹具完全相同。结束时只删除该随机夹具及对应媒体行，并断言清理成功。

超限用例将有效 PNG 扩展至 8 MiB + 1 字节，经实际系统选择器返回后验证拒绝、原图不变及无财务副作用；随后仅修复本轮拥有的测试文件，再点击“重新读取所选图片”，验证同一 URI 的新内容保存成功。此用例证明明确重试，不承诺恢复已经永久撤销的外部文件权限。

所有入口场景均核对默认/当前账本的余额与流水 ID 集合、服务器 AI 队列、本机请求队列及合成模型计数：导入和取消不发送模型、不排队、不入账。相机正向场景另核对公共图片集合不变。

## 测试驱动修正

保留了失败日志：初版夹具复用登录前客户端，被会话快照保护拒绝；后改为登录后创建客户端。登录辅助函数等待键盘关闭后的可见、可用按钮，再点击一次，避免布局变化时误判。

系统命令需要处理引号、管道和错误状态，测试采用 `executeShellCommandRwe("sh")`，从标准输入传入受控脚本，并关闭三条管道、检查返回状态；接口用途及管道顺序见 [Android 官方说明](https://developer.android.com/reference/android/app/UiAutomation#executeShellCommandRwe(java.lang.String))。这是 API 35 QA 测试实现，生产代码不依赖该测试接口。

DocumentsUI 图片网格可能隐藏标题，见 [AOSP 网格实现](https://android.googlesource.com/platform/frameworks/base/+/fd27ae961197d03f2e24dd88d2e94dadefad896a/packages/DocumentsUI/src/com/android/documentsui/dirlist/GridDocumentHolder.java)。测试先按唯一文件名搜索，再只定位目录列表中该文件的完整无障碍名称；无点击动作的图片卡片使用 UI 树提供的实际边界中心点击。没有选择首张图片或用坐标常量代替目标识别。

## 仍需完成

- 本批证明已开始导入任务的 Activity 重建接续，尚未证明相机在前台时进程死亡、回调丢失或导入前冷启动的完整恢复；未加密的拍照缓存还需持久、按身份/账本归属的恢复及明确丢弃合同。
- 来源、识别、复核及确认生产页面的完整新 PID 恢复，旧版未上传 AI 记录的明确处置，以及各类型/四角色完整财务操作仍需继续。
- 深色文字按钮对比度、筛选芯片配色、完整 TalkBack、已录入指纹与所有适配组合未由本批泛化为通过。远程 CI、签名、正式升级、部署及回退继续按 F10–F12 推进。
