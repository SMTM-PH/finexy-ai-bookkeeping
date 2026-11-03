# Android 候选输入与 CI 接续

日期：2026-11-02。对应 [完整计划 F11](NEXT_DEVELOPMENT_PLAN_2026-11-01.md)。本批改进候选校验的可复用性，并接入工作流配置；没有触发远程 CI，不等于正式发布门禁通过。

## 源码范围与实际 APK 分开证明

报告候选此前依赖本机 `artifacts/` 中的旧测试 metadata。新完整候选改用仓库内的 [测试源码版本登记](../.github/testdata/android-report-api-revisions.json)，固定测试路径、类、三个必要方法和已审查源码哈希。登记只说明源码范围，不宣称编译或 HTTP 通过。

[候选校验器](../.github/scripts/android-report-candidate.py) 仍须分别核验：

- 源码前后清单和摘要一致，所选完整门禁通过；JVM 无失败、错误或跳过，Debug/QA lint 无错误。
- QA 应用与测试 APK 来自同一候选目录，实际文件哈希与门禁、打包证明一致。
- 实际 DEX 定义包含指定类和三个必要方法；不把偶然字符串当类或方法定义。
- 测试包实际指向独立 `.qa`，标准 coroutine test 服务及类只在 QA target 中存在，不在测试 APK 重复。
- `strict-current` 核对当前 Android 文件集和内容；`--frozen-source` 必须持有完整 `source-snapshot/android/...` 副本，不把仅有 manifest 当冻结源码，也不宣称当前工作树已验证。

历史默认 207 文件候选仍显式使用其旧 metadata，保留复现能力；不自动选择“最新”。其中旧币种测试预期的失败不因登记而变成通过。新源码版本按共用 `currency-either-leg` oracle 保留转账两侧金额，实际 HTTP 结果见 [Android 报告专题](REPORT_ANDROID_CONTRACT_2026-10-22.md)。

## SDK 与工作流

`aapt2` 从 `FINEXY_ANDROID_AAPT2` 或 runner 的 `ANDROID_SDK_ROOT` / `ANDROID_HOME` 读取；默认使用项目 SDK 35 的 build-tools。仅 Windows 保留 `E:/Android/Sdk` 回退，不要求 Linux runner 存在 Windows 盘符。SDK 或工具缺失明确失败。

[Android 工作流](../.github/workflows/android.yml) 增加候选校验脚本、测试及版本登记文件的 push/PR 路径触发，构建前执行 [候选输入测试](../.github/scripts/tests/test_android_report_candidate.py)。合成证据目录固定在工作区 `artifacts/` 内，随验证报告上传；不上传凭据或真实账本内容。

## 本机实际验证

| 范围 | 实际结果 | 证据与限制 |
| --- | --- | --- |
| 输入合同 | 12 项通过 | 原有源码/包配对、冻结副本等负例，以及未审查源码、替换方法/类/路径、SDK 工具选择等；是校验器测试，不是业务测试 |
| YAML 与触发 | 解析通过，六项新增路径触发及检查步骤存在 | 本地解析配置，没有执行 GitHub Actions |
| 无历史 metadata 的实际包校验 | 通过 | 独立目录仅复制校验器、仓库登记、完整源码副本、门禁、QA 打包证明与两个实际 APK；未复制历史测试 metadata，仍通过实际 DEX、包名和配对校验 |
| 运行平台 | Windows / SDK 35 | 没有执行 Linux runner，不将 SDK 路径支持当 Linux 运行结果 |

[本轮汇总](../artifacts/android-candidate-contract-20261102/summary.json) 和 [实际隔离目录证明](../artifacts/android-candidate-contract-20261102/portable-proof.json) 保留具体哈希与边界。该包校验使用历史冻结候选 `41b17a23c3cbeb3dfd3f9dd96342064777aa12136a4a796820e2c939a6f68252`；其输入合法不意味着未通过的图片恢复功能已验收。

## 剩余正式出口

F11 仍须同一最终提交的远程 Go/CGO、Web、Android 构建和隔离业务/设备检查实际运行，记录 runner、提交、源码、双包、产物与日志，并使失败阻止发布。新导航/SAF、原图恢复和原流水恢复须按各自实际范围接入，不能只保持早期设备用例。

当前仍有大量未提交开发成果，不能用远程旧提交的绿灯替代本机候选。正式签名、保留数据升级、真实服务部署和回退继续属于 F12。本机 `artifacts/` 通常不随 Git 分发，跨机器交接须提供所选候选完整源码与证据，而不是仅复制一个汇总文件。
