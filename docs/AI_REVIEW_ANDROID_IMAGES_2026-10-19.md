# Android AI 原图加密存储与备份恢复

日期：2026-10-19。承接 [持久请求与 Worker](AI_REVIEW_ANDROID_STORAGE_2026-10-19.md) 和 [后续计划](NEXT_DEVELOPMENT_PLAN_2026-10-19.md) 的 F03。本专题记录原图数据层；F04 生产来源、裁剪、复核与确认页面仍待接入，不能据此认定 Android AI 完整功能已交付。

## 存储与身份隔离

[AIReviewImages.kt](../android/app/src/main/java/com/finexy/mobile/data/AIReviewImages.kt) 使用应用 Android Keystore AES-256-GCM 密钥。每块使用独立随机 IV；认证数据绑定身份数据库名、账本、图片/草稿 ID、图片元数据及块序号，替换密文或跨身份/账本搬运不能解密。读取不因密钥缺失生成替代密钥或空图片，返回可见的恢复错误。

原图字节保持不变，支持 JPEG、PNG、WebP；限制为 8 MiB、每边至多 16000 像素、总计至多 4000 万像素，并校验可解码性。元数据保留 MIME、宽高、原字节数、SHA-256 和块数。尺寸校验后仅进行采样解码验证，不把采样结果覆盖原图。

Room 升为 **v26**，新增 `MIGRATION_25_26`：

| 变更 | 用途 |
| --- | --- |
| `ai_review_images` | `(ledgerId, imageId)`；不可变原图元数据与所属草稿 ID |
| `ai_review_image_chunks` | `(ledgerId, imageId, chunkIndex)`；每块原始字节最多 64 KiB，数据库中仅保存带版本前缀的密文，避免多兆单行超过 CursorWindow |
| `ai_review_requests.sourceImageId` | 默认空字符串；冻结创建请求所依赖的原图引用，旧请求保持原字节与状态 |

迁移保留旧流水、AI 缓存、模板草稿和请求/回执，不从旧来源推断图片或制造新操作。身份数据库继续由 `LedgerScope` 分区，默认账本 `0` 保留原语义。

## 草稿、请求与图片生命周期

[AIReviewPersistence](../android/app/src/main/java/com/finexy/mobile/data/AIReviewPersistence.kt) 提供 `saveSourceImage`、`sourceImage` 和 `requestSourceImage`，生产 Repository 继续通过 `aiReview` 暴露同一存储。

- 保存原图与来源草稿引用在同一 Room 事务完成；比较当前完整草稿及版本，旧异步保存不能覆盖新输入。失败整体回滚，不留下半张图片。
- 草稿通过原始字符串字段 `sourceImageId` 引用图片；裁剪/旋转参数、原始表达式与建议结果可保留在同版本草稿中。具体页面状态及识别结果版本校验由 F04 接续。
- 明确创建请求冻结其图片引用，并验证原图可读取。尚未确认的原图/草稿不会由 Worker 上传或自动记账；已有请求仍只重放原 payload。
- 替换或移除草稿图片时，当前来源和未核对请求仍引用的原图保留。未知请求不能被丢弃；读取旧草稿或已移除请求的图片会被拒绝。
- 回执与原条目核对完成后，只清理对应版本草稿和已解除引用的图片，保留较新草稿及其图片。其他账本、默认账本与其他身份数据库不受影响。
- 明确丢弃和现有本地清理流程同时清理无引用的元数据与密文块；没有额外明文图片文件或明文图片偏好存储。

本轮没有修改相机临时文件或旧页面内存 Bitmap 流程。F04 必须将选择/拍照、临时文件清理、裁剪、旋转、识别和恢复真正接入以上合同后，才能验收生产来源流程。

## 加密备份 schema 20

[LedgerBackup](../android/app/src/main/java/com/finexy/mobile/data/LedgerBackup.kt) 升为 **schema 20**，继续支持 schema 8–19。schema 19 缺少新表及请求图片字段时按旧格式验证和恢复，不制造图片引用。

备份导出时，在内存中将图片分块解密为可迁移内容，与其他账本数据一起由既有密码备份加密保护；不会导出应用 Keystore 密钥、凭据或 PIN，也不生成明文图片文件。整个备份仍受原有 16 MiB 文档限制，超限明确失败，不静默丢图片或截断。

预览及恢复校验表/字段类型、数值边界、图片元数据、完整连续分块、哈希、实际可解码内容和草稿/请求引用。缺块、重复、跨账本/草稿引用或内容损坏，均在任何财务恢复前拒绝。

恢复时使用目标安装的 Keystore 重新加密，因此备份不依赖原安装的密钥或数据库文件名。同设备密钥丢失或分块损坏时，完整校验的备份可修复同一不可变原图的密文；元数据冲突拒绝恢复。财务行、较新草稿和已保存回执继续只添加缺失行，不覆盖设备状态。恢复后清理没有当前草稿或请求引用的导入图片。

## 验证与交付范围

本轮证据目录：`artifacts/ai-review-android-images-20261019/`。独立 QA 包用于安装与 instrumentation；Debug 仅构建。详细通过项以该目录最终 `summary.json` 及严格解析结果为准。

本轮 **130 条测试/阶段检查通过**，按 10 + 116 + 2 + 两阶段 2 统计，不累加构建或复跑。QA/Debug lint 均为 0 错误、20 警告。仅原图数据层与表中实际范围已验收。

| 检查 | 覆盖范围 | 证据 |
| --- | --- | --- |
| 原图专项 10 项 | 25→26 保留数据、多兆原图重开、小密文行、认证篡改/归属、旧请求保留原图、替换/清理、输入失败回滚、密钥丢失恢复、旧 schema 19、坏备份与较新设备状态 | `images-01.log`、`images-verified.json` |
| 关联回归 116 项 | AI 存储/API、同步、旧迁移、schema 8 起兼容、隐私、模板、周期、账本写入及其他既有数据合同 | `regression-01.log`、`regression-verified.json` |
| 隔离投递 2 项 | 生产 Worker 撤权保留请求、恢复权限后入账一次；旧服务缺能力时保留原请求，无替代写入 | `delivery.log`、`delivery-verified.json` |
| 真实跨进程两阶段 | 成功响应实际丢失、prepare 退出并终止、系统新 PID 生产 Worker、独立 verify；原请求图片清理、较新/默认/其他账本图片可恢复 | `process/`、`process-run.log` |
| 构建与候选 | Java 17，QA/Debug 的 assemble、AndroidTest 与 lint；源码与已安装 QA APK 哈希一致 | `build-qa-final.log`、`build-debug.log`、`source-before-tests.json`、`installed-qa.json` |
| 严格门禁 | 固定类/方法/数量，拒绝失败、跳过、缺失、截断和错误范围 | `protocol-images/`、`protocol-regression/` |

实际进程恢复沿用 [恢复脚本](../.github/scripts/verify-android-ai-storage-recovery.mjs) 的无卷候选、固定回包丢失代理、随机账号/命名空间及原 WorkSpec 观察。新增图片断言在 [prepare/verify](../android/app/src/qaAndroidTest/java/com/finexy/mobile/data/AIReviewProcessRecoveryE2ETest.kt) 中执行：原请求图片保留到 Worker 核对回执，随后清理；较新来源、默认账本和其他账本的三张未确认原图由独立进程读取验证，不调用模型。

## 剩余工作

F03 数据层具备原图持久能力，F04 仍需生产页面、导航和图片导入接入、活动重建、识别迟到响应保护、明确三步动作、复核与原子确认迁移、四角色和实际 UI 验收。完整主题/字号/横屏/TalkBack/指纹、远程 CI、签名包和正式升级部署仍待完成。

本轮未升级主 Android、未部署本地用户服务或 NAS，也未修改 Web/Go 功能或重建服务端候选。隔离服务运行结果只证明已核验的冻结镜像和本轮请求；相关 Go/Web 全套检查未在本轮重跑。F03/F04 及整个开发计划保持未完成状态。
