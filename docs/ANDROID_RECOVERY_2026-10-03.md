# Android 隔离设备与进程恢复验收

日期：2026-10-03。承接 [第一轮修复](FIRST_ITERATION_2026-10-03.md)。本轮关闭“新增设备用例未运行”和“非默认账本待确认写入未验证独立进程恢复”两项，未升级主应用、提交、推送或部署真实服务。

## 隔离方式

- 新增 QA build type：`com.finexy.mobile.qa` / `com.finexy.mobile.qa.test`，名称 `Finexy QA`。主应用 `com.finexy.mobile` 保留原包名、名称与数据；QA 通过独立 UID 隔离 Room、Keystore 和 WorkManager。
- 本机复用已有 `Pixel_8_API_35`，未新建或 wipe AVD。每条 ADB 指定 `-s emulator-5554`，仅 QA 两 APK 使用 `install -r`；未运行 `connectedDebugAndroidTest`、卸载或清除应用数据。
- E2E 仅连接无挂载的 `finexy-android-recovery-e2e`，宿主端口 `18086`，模拟器地址 `http://10.0.2.2:18086`；账号与记录全部随机夹具。候选镜像为 `sha256:b425985352caa646f32c9133c7a59898252ae115dbcfc6d337454f5643e71a3b`，健康接口版本 `1.9.3`。本地用户服务 `18080` 与 NAS `8080` 未操作。
- Debug/Release 默认名称仍为 `Finexy`，Debug 测试目标保持原合同；`-PfinexyIsolatedQa=true` 才选择 QA instrumentation。

## 实际结果

Java 17 下 Debug 与 QA 均通过两 APK 编译、lint、JVM 检查；各 13 条 JVM 用例无失败。设备七类强制用例全部成功，无跳过：

| 类 | 数量 | 覆盖 |
| --- | ---: | --- |
| `LedgerWritePersistenceTest` | 3 | 队列重开、Room 21→22 保留数据、schema 16 队列备份及 15 兼容 |
| `EntryReliabilityUiTest` | 5 | 分类加载/失败/真实空态与入口金额状态 |
| `DockerLedgerWriteE2ETest` | 1 | 隔离服务非默认账本在线写入与回放 |
| `TransactionConsistencyTest` | 28 | 映射、转账、金额/ID/时间合同、合并、冲突及既有迁移 |
| `SyncWorkerTest` | 2 | 持久 Worker 同步状态 |
| `LedgerScopeTest` | 2 | 服务器/用户隔离与局部清理 |
| `PrivacyDataTest` | 12 | 隐私、加密备份、schema 8–12/14、恢复原子性 |
| 合计 | 53 | 严格解析真实 runner 日志，拒绝跳过和部分发现 |

进程恢复另执行两个强制方法。prepare 创建随机用户、非默认个人账本、明确映射账户/分类和稳定请求；服务端实际入账后，测试注入响应丢失，验证 Room 仍保存原键、原账本及不可变快照。随后退出 instrumentation 并终止旧进程，再启动 QA 应用，让持久 WorkManager 请求在新 PID 中运行真实生产 `SyncWorker`；后台完成后才启动 verify。

最终 QA 源集包重跑证据：prepare PID `6313`，恢复 Worker PID `6362`；Worker 结束后 verify 通过，且断言 Worker PID 不同于 prepare 与 verify。确认原服务端 ID 只出现一次、余额只变化一次、Room 队列已确认清空、非默认账本缓存正确、默认账本流水与余额摘要未变化。夹具清理仅涉及自己的数据库、偏好和 WorkManager tag。此前首次验收 PID `5414` → `5463` 的证据亦保留。

测试探针只在 QA 源集中，两阶段用例也仅在 `-PfinexyIsolatedQa=true` 时加入 instrumentation；实际 APK DEX 核验确认探针与专项类在 QA 包中存在、在 Debug 包中不存在。探针只记录固定五字段的 PID、单调时钟与状态，不保存账号、令牌、请求、响应或财务内容。生产 `SyncWorker` 未为测试改写。

## 主应用只读核对

QA 验收前后，主应用数据库、WAL、加密偏好指纹一致。随后主应用实际冷启动成功，UI 树有主包节点，检查到的 logcat 无 fatal/ANR；只读 SQLite 核验 Room 仍为 v21，当前流水为 0 条，数据库与 WAL 指纹仍一致。加密偏好文件在主应用自身冷启动时变化，已单独记录，不将这项描述为全文件完全未变化。本轮未操作主应用的编辑、同步或恢复入口，没有重建主账本或恢复历史 4 条记录。

## CI 与复现

[Android 工作流](../.github/workflows/android.yml) 保留 Debug 门禁，补充 QA 构建、QA APK/报告产物与 Node 22。设备任务使用全新 GitHub 专用 AVD 和无卷随机账号服务，53 条严格解析成功后再运行 [恢复脚本](../.github/scripts/verify-android-process-recovery.mjs)。push/PR 路径覆盖 Android 验证脚本。actionlint、Bash/Node 语法、路径及 diff 检查通过；远程 GitHub CI 未触发。

恢复脚本只接受两个保留的临时容器名，检查无挂载、注册开关、端口、QA UID 和 instrumentation 目标；两阶段各恰好一条且必须成功，Worker 必须在旧进程退出后完成。不要将其改为主包，或指向本地用户服务/NAS。

本机复现先重新创建并检查上述保留名称的无卷临时服务，再使用已安装 QA APK：

```powershell
$env:FINEXY_ANDROID_E2E_CONTAINER = 'finexy-android-recovery-e2e'
$env:FINEXY_ANDROID_RECOVERY_EVIDENCE = 'artifacts/android-recovery-20261003/process-recovery'
node .github/scripts/verify-android-process-recovery.mjs
```

证据保存在被忽略的 `artifacts/android-recovery-20261003/`：构建日志、最终 `instrumentation-final-53.txt` / `instrumentation-final-summary.json`、`process-recovery-final/` 两阶段日志与 PID witness、APK 类隔离/哈希、主应用指纹及只读冷启动摘要。CI 语法证据在 `artifacts/first-iteration-20261003/qa-recovery-*`。

验收后按容器 ID、候选镜像、无挂载与端口复核，只清理本轮临时容器；本地用户服务仍在 `18080` 运行，候选镜像与证据保留。QA 包保留安装但已停止；没有卸载任何应用。复现命令需先重新创建并核验保留名称的无卷临时服务，不能将已清理的服务描述为仍可连接。

## 交付边界

本轮通过的是上述隔离专项。schema 13 单独备份用例、指纹已录入设备成功路径、完整 TalkBack、全部深浅主题/最大字体/横屏、正式签名、主应用升级与回退、远程 CI 和真实部署仍未验收。安装 QA 包不表示主应用已升级；本地构建与设备成功不表示正式发布完成。

后续开发优先补齐移动 Web 非默认账本写入与完整映射合同，随后推进账本预算/周期/AI 与报表。正式安装和部署仍需按目标检查备份、签名升级、实际版本与冷启动，并保留可验证回退。
