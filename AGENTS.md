# Finexy 项目 AI 必读手册

> 最后核验：2026-09-26。任何 AI、自动化代理或新开发者开始修改前，必须完整阅读本文。本文是当前项目状态、Android 开发约束、测试方法和后续计划的唯一权威入口；README 负责面向用户介绍产品。涉及任何界面工作时，还必须完整阅读 `docs/UI_UX_DESIGN.md`。

## 1. 项目与当前状态

Finexy 是自托管个人财务系统。仓库同时包含 Go 服务端、Vue Web/PWA、Electron Windows 客户端、OCR 服务和原生 Android App。

Android 当前状态：

| 阶段 | 状态 | 已交付范围 |
| --- | --- | --- |
| A 数据一致性 | 已验收 | Room Flow、结构化流水、毫秒/秒边界、完整字段保留、上传 ID 回写、响应丢失幂等恢复、用户/服务器账本隔离、v1→v10 迁移链 |
| B 完整双向同步 | 已验收 | 全量分页、删除语义、账户/分类显式映射、冲突完整字段解决、WorkManager 持久重试与进程恢复 |
| 用户、安全和数据管理 | 功能完成，部分人工验收待办 | 注册登录、会话、密码、2FA、应用 PIN、生物识别入口、加密备份恢复、清理本地数据 |
| C 现有业务闭环 | 本地功能完成，服务端 E2E 待补 | 账户详情、标签、模板、统一筛选、统计与 CSV 已交付；C2/C3/C5 等待隔离 Docker 验收 |
| D Web 能力补齐 | 开发中（D1–D4 已形成闭环，D6–D8 已完成 Android 首轮闭环，D5 仍有验收项） | 周期待确认、资产、汇率与用户偏好已接入 Android；AI/OCR 首版已落地但尚未完成真实服务与完整无障碍验收 |
| E 设计与发布 | 待开发 | 全面适配与无障碍、CI 测试门禁、签名、发布包验证 |

阶段 A/B 的最终门禁为 Android lint 通过及 `OK (50 tests)`。普通全量运行中，视觉停留夹具和进程恢复两阶段测试会按参数门禁跳过；它们已单独通过。用户、安全阶段仍缺已录入指纹设备上的生物识别成功路径，以及完整 TalkBack 人工听读，不得宣称整阶段完全人工验收。

## 2. 仓库地图

- `android/`：原生 Kotlin + Jetpack Compose App。
- `android/app/src/main/java/com/finexy/mobile/`：页面、主题、主 Activity、隐私与安全 UI。
- `android/app/src/main/java/com/finexy/mobile/data/`：API、Room、Repository、同步、WorkManager、加密存储与备份。
- `android/app/src/androidTest/`：Room、同步、安全、Compose UI 和 Docker E2E 测试。
- `android/app/src/debug/`：非导出的测试宿主 Activity；不得放入 release manifest。
- `src/`：Vue Web 前端；`pkg/`、`cmd/`：Go 服务端；`desktop/`：Electron；`ocr-service/`：OCR。
- `compose.yaml`：本地完整服务；`data/`、`storage/`、`log/` 是真实运行数据，禁止测试清空。
- `artifacts/android-ui/`：本地忽略的最终验证证据，不属于产品资源。
- `docs/UI_UX_DESIGN.md`：当前 Web + Android UI/UX 唯一设计基线；旧版设计只保存在 `archive/ui-legacy/`。

关键 Android 数据链路：Compose 只观察 `TransactionRepository` 暴露的 Room Flow；写入使用 `TransactionDraft`；`SyncEngine` 负责双向合并；`SyncScheduler`/`SyncWorker` 负责持久队列。不要重新引入 SharedPreferences 流水读写。

## 3. 不可破坏的规则

1. 不卸载 App、不清除主应用数据。安装测试包只用 `adb -s emulator-5554 install -r ...`。
2. 所有 ADB 命令显式指定 `-s emulator-5554`，避免误操作其他设备。
3. 当前主应用账本有 4 条用户流水。测试前后冷启动都应仍显示 4 条；不得编辑、同步、删除或拿它做 E2E。
4. Docker E2E 只允许连接无卷、一次性的隔离容器和随机测试账号。绝不对 `localhost:8080` 的用户服务运行破坏性测试。
5. 不删除或重置脏工作树。当前 Android 工作包含大量未提交文件，均视为用户成果。
6. `.env`、token、PIN、恢复码、财务数据、`data/`、`storage/`、`log/` 不得输出或提交。
7. Room schema 变更必须新增 migration，不得使用 destructive migration；同时更新备份 schema 的前后兼容测试。
8. 服务端 ID 使用 `Long`/字符串传输，不能经过 JS 浮点；Room 时间为毫秒，API 边界为 Unix 秒。
9. 本地钱包 `-1` 不能自动匹配第一个服务端账户；分类不能仅凭同名猜测。必须使用显式映射。
10. 构建成功不等于验收。至少需要相关专项测试；涉及同步、登录或服务端数据时还需要隔离 Docker E2E。

## 4. 已踩过的坑与解决方案

### 响应丢失造成重复流水

问题：服务端已经完成新增，但客户端在写回 server ID 前丢失响应。旧流程先 pull，再重试 push，远端行会以 `server-{id}` 插入 Room，原本的 localId 行随后又绑定同一 server ID，形成两条本地记录。

解决：`SyncEngine.sync()` 在 pull 前只预处理“未删除、serverId 为空、账户和分类映射完整”的新记录，使用稳定的 `localId` 作为 `clientSessionId` 幂等重放，先把服务端 ID 绑定原 Room 行，再 pull。已有记录的修改/删除仍先 pull，保留冲突检测语义。失败测试应能看到 2 条，修复后本地和服务端都必须严格为 1 条。

### 回包覆盖请求期间的新编辑

问题：上传请求发出后用户继续编辑，若直接标记 SYNCED 会丢失后续修改。

解决：`markSynced` 比较上传快照与当前 Room 快照；内容变化时只补 server ID，状态继续保持 PENDING，后续再上传修改。

### 秒/毫秒混用

问题：服务端时间为秒，Android 内部为毫秒，混用会生成错误日期或统计。

解决：只在 API 边界转换。v6→v7 migration 仅规范具有 serverId 且明显为秒的历史行，不猜测修改合法本地历史时间。

### 同名分类和默认账户误匹配

问题：按名称匹配分类、默认取第一个可见账户会把流水上传到错误对象。

解决：Room v8 使用“本地分类名 + 收支类型”的显式分类映射；Room v10 增加 `account_mappings`。隐藏账户、根分类和类型不符分类不可选择。映射只回填尚未上传的 pending 行，不能反向改写已同步历史。

### pull 覆盖本地修改/删除

问题：没有服务器基线时无法区分单边更新和双边冲突。

解决：保存 `syncedSnapshotJson`；单边本地变化保持 pending，双边变化创建 `CONFLICT`，服务端删除与本地编辑也进入冲突 UI。冲突对话框覆盖 13 组业务字段，“保留本机”和“保留服务器”都必须有测试。

### 断网或进程退出后同步丢失

问题：仅内存协程和按钮状态不能跨进程恢复。

解决：Room v9 持久化同步状态；WorkManager 使用网络约束、指数退避、唯一工作和身份校验。进程恢复必须分 prepare/kill/verify 两阶段测试，不能用同一 instrumentation 进程假装验收。

### 用户或服务器切换混账

问题：只按 token 或单一数据库保存会在换账号后展示前一个账本。

解决：`LedgerScope` 由规范化服务器地址 + JWT 用户身份生成稳定数据库名；token 刷新保持同一账本，不同用户/服务器使用不同 Room 文件。旧 `finexy.db` 只允许首次绑定一个身份。真实 UI 测试已验证 A 用户流水在 B 不可见，切回 A 后恢复。

### 测试夹具与生产页面背景不一致

问题：直接渲染局部 Composable 会出现透明/错误背景，截图不能代表真实页面。

解决：视觉夹具必须包在生产一致的 `FinexyTheme + Scaffold(containerColor = CanvasBlack)` 中，并遵守安全区。Debug 测试 Activity 保持 `exported=false`，不要为方便 shell 启动而导出。

### 模拟器与工具链细节

- `emulator` 当前不在 PATH，使用 `E:\Android\Sdk\emulator\emulator.exe`。
- JDK 24 跑 Gradle 8.10.2 会输出 native-access 警告，目前不等于失败；Android 源码/CI 目标是 Java 17。若出现真正兼容问题，优先切 JDK 17，而不是压掉警告。
- 模拟器偶发断连时重启现有 `Pixel_8_API_35`，不要新建或 wipe data。可用 `-no-snapshot-save` 降低快照干扰。
- **禁止在保存主账本的 AVD 上运行 Gradle `connectedDebugAndroidTest`**：2026-09-07 实测该任务会卸载/重装目标包（应用 UID 变化），从而清空全部偏好与 Room 文件。应先 `assembleDebugAndroidTest`，再对两个 APK 分别执行 `adb install -r`，最后用 `adb shell am instrument` 运行；更稳妥的是使用专用测试 AVD。此次事故前的只读证据确认主账本为 4 笔、总支出 103.07；事故后设备数据目录只剩新建的空 `finexy.db`。服务器仍保留其中两笔（ServerConflict2 67.89、AndroidUploadE2E 12.34），另两笔仅有 UI 证据，恢复需要原服务器登录态或用户确认后重建，禁止凭截图静默伪造。
- 非导出的 QA Activity 不能从 adb shell 直接启动，这是正确的安全行为；应通过 instrumentation rule 启动。

## 5. 当前开发环境

| 项目 | 当前值 |
| --- | --- |
| 工作区 | `E:\project\ai-bookkeeping` |
| 操作系统 | Windows 11 x64 |
| Node / npm | 24.19.0 / 11.14.1 |
| Go | 1.27.0 windows/amd64 |
| Java | Oracle JDK 24.0.1；编译与 CI 目标 Java 17 |
| Gradle / Kotlin | 8.10.2 / 1.9.24 |
| Android SDK | `E:\Android\Sdk` |
| Android | compile/target 35，min 26，Compose BOM 2025.01.00，Room 2.6.1；当前数据库 v21，备份 schema 15 |
| AVD | `Pixel_8_API_35` |
| 当前设备 | `emulator-5554`，API 35，1080×2400，420 dpi，x86_64 |
| Docker | Client/Server 29.7.2 |
| 主服务 | `ai-bookkeeping-bookkeeping-1`：8080；`ai-bookkeeping-ocr-1`：内部 8000 |

启动模拟器（仅在未运行时）：

```powershell
& 'E:\Android\Sdk\emulator\emulator.exe' -avd Pixel_8_API_35 -no-snapshot-save
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell getprop sys.boot_completed
```

## 6. 构建与测试

### Web / 服务端常规门禁

```powershell
npm ci
npm run check
go test ./...
docker compose up -d --build
docker compose ps
```

### Android 构建、lint 和安装

从仓库根目录运行：

```powershell
.\android\gradlew.bat -p android :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s emulator-5554 install -r android\app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5554 install -r android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
```

### 不访问服务器的 Android 回归

```powershell
adb -s emulator-5554 shell am instrument -w -r com.finexy.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

Docker 测试没有 `finexy.e2e.url` 时会按设计跳过。不要把 assumption skip 当失败，也不要把这些跳过项算成已在本轮重测。

### 真实 Docker E2E

使用当前服务镜像启动无卷临时容器；端口可换，但必须避开 8080：

```powershell
docker run --rm -d --name finexy-android-e2e -p 18086:8080 ai-bookkeeping-bookkeeping
adb -s emulator-5554 shell am instrument -w -r `
  -e finexy.e2e.url http://10.0.2.2:18086 `
  com.finexy.mobile.test/androidx.test.runner.AndroidJUnitRunner
docker stop finexy-android-e2e
```

模拟器访问宿主机必须使用 `10.0.2.2`，不能使用 `localhost`。测试会创建随机账号；停止无卷容器即清理全部临时服务器数据。若测试中断，也必须核对并停止自己创建的容器。

### 进程恢复专项

`SyncProcessRecoveryE2ETest` 是两阶段测试：先运行 prepare，让 WorkManager 入队；确认 instrumentation 退出并杀掉 App 进程；等待系统以新 PID 拉起 Worker；再运行 verify。必须使用专用临时容器和唯一 namespace。不可合并成普通单进程测试。

### UI 验收最小清单

- 深色与浅色主题；窄屏、横屏、最大字体；状态栏/导航栏安全区。
- 所有可点击目标至少 48dp；错误状态有可见文案和无障碍 live-region/error 语义。
- 输入键盘出现时保存按钮可到达；空态、加载、失败、重试、冲突都可操作。
- UI 树用 `uiautomator dump`，截图用 `screencap`；截图不能替代功能断言。
- 最后冷启动主 App，确认 4 条现有流水仍在，并检查 logcat 无 Room migration/FATAL EXCEPTION。

## 7. 最终证据（保留，不要随意删除）

- `artifacts/android-ui/a-stage-a-final-50-tests.txt`：A/B/安全相关最终全量回归，`OK (50 tests)`。
- `artifacts/android-ui/a-response-loss-red-test.txt` 与 `a-response-loss-fixed-test.txt`：响应丢失问题的失败/修复对照。
- `artifacts/android-ui/a-v1-v10-migration-test.txt`：v1→v10 连续迁移。
- `artifacts/android-ui/a-ledger-switch-ui-e2e.txt`：真实双用户 UI 隔离。
- `artifacts/android-ui/b2-v10-process-recovery-prepare.txt` 与 `b2-v10-process-recovery-verify.txt`：进程恢复。
- `artifacts/android-ui/b2-account-mapping-page-scaffold.png`：账户映射浅色主题最终视觉证据。
- `artifacts/android-ui/light-theme-final-verified.png`、`home-landscape.png`、`home-large-font.png`：主题与适配证据。
- `artifacts/android-ui/stage6-final-tests.txt`、`stage6-privacy.xml`：用户、安全和数据管理证据。

这些文件被 `.gitignore` 忽略，只作为本机验收记录。若重新生成，优先覆盖同名“final”证据，不再创建 `final2`、`stage2` 一类版本文件。

## 8. 后续开发计划

### 已知 Web 问题

- 新账户的初始余额目前在近期活动/流水中可能显示成普通支出，虽然余额和支出统计未被污染。阶段 D 实现余额调整时，应统一为“期初余额/余额调整”语义，并检查列表、详情、统计和导出。
- Web 流水列表可能把同一交易描述同时渲染为标题和副标题，造成重复显示。修改列表时只保留一次描述，或仅在确有独立备注字段时显示第二行。

### 下一步：阶段 C

1. **已完成（C1）**：账户详情页已包含余额、账户流水和近 7 天/近 30 天/全部筛选；支持默认账户选择、失效/隐藏账户回退本地钱包，以及从账户详情预选账户记账。专项设备测试 2 项通过；2026-09-04 全量 52 项中该功能通过，既有系统文件选择器用例首次偶发超时、单独复测通过。
2. **开发中（C2）**：标签新增、改名、停用、确认删除、失败反馈和 Room 回写已接入真实 API；流水编辑已有标签选择与 `tagIds` 往返。Compose 专项测试已通过，lint 已通过；仍需在无卷隔离 Docker 服务上运行完整标签 CRUD E2E 后才能标记完成。
3. **开发中（C3，本地功能完成）**：已删除“从第一条流水隐式生成模板”的旧行为；新增/编辑模板显式处理名称、收支类型、金额、账户、末级分类、备注和最多 10 个标签，并在调用服务端前预览确认。失败后保留完整草稿并可一键重试，永久删除需要二次确认。Compose 专项测试 3 项和 payload 合同测试 1 项通过，lint 通过；仅剩隔离 Docker 服务端 E2E。
4. **开发中（C4，本地收支口径完成）**：流水列表与统计页均按日期、收入/支出类型、账户、分类和标签五维筛选；统计筛选同时驱动汇总、7 日趋势、月度趋势、分类/标签统计与 CSV。列表组合筛选 UI 测试 1 项、筛选/CSV/时区逻辑测试 3 项、统计 UI/导出确认测试 1 项通过；转账、退款口径随阶段 D 的结构化类型补齐。
5. **开发中（C5，本地功能完成）**：CSV 导出已与屏幕筛选一致，包含账户 ID、日期、类型、分类、金额、描述和标签；导出前显示条数及收入/支出汇总，正确转义用户文本。日期范围以设备时区的自然日为边界，显示日期和筛选边界共用相同时区；仅剩隔离 Docker 真实数据 E2E。

阶段 C 每个功能按 API 合同 → Room entity/DAO/migration → repository → Compose UI → 自动化 → 隔离 Docker E2E 的顺序交付。不要先做只有静态假数据的页面。

### 阶段 D

1. **D1 本地闭环已完成，Docker E2E 待补**：记账页支持支出、收入、转账和余额调整。转账显式选择转出/转入账户与服务端末级转账分类；同币种自动沿用金额，跨币种要求转入金额。余额调整显式选择增加/减少并保存有符号金额，不发送分类或目标账户。列表、账户详情、筛选、余额计算、编辑回填和 API payload 已覆盖结构化字段。Repository/SQLite 专项 `OK (25 tests)`，Android 构建与 lint 通过。Docker 不可用时不得把此项写成服务端验收完成。
2. **D2 本地功能完成，Docker E2E 待补**：Room v11 已保留账户父级、类别、类型、图标、颜色、备注、排序和信用卡账单日；账户页支持单账户与多子账户新增、编辑、信用卡账单日、子账户增删、停用/恢复、确认删除和根账户排序。父账户不会用于记账；停用父账户后子账户也不会进入选择器。编辑多子账户提交完整集合并保留已有子账户 ID，服务端删除后 Room 会清除缺失子账户。构建与 lint 通过；流水/迁移、账户合同和账户 UI 组合回归 `OK (33 tests)`。Docker daemon 于 2026-09-04 再次确认不可连接，因此真实 CRUD E2E 仍未验收；在隔离无卷容器通过前不得标记 D2 全面完成。
3. **D3 本地功能完成，Docker E2E 待补**：Room v12 已保留服务端分类备注与排序；设置页支持收入、支出、转账两级分类的新增、编辑、同级排序、停用、恢复和确认删除。分类类型编辑后保持不可变，子分类只可移动到同类型根分类；停用根分类后，其子分类不会进入记账、模板或分类映射选择器。API/解析合同、分类 UI、流水一致性及 v1→v12 迁移组合回归 `OK (31 tests)`，构建与 lint 通过。2026-09-04 Docker daemon 仍不可连接，因此真实分类 CRUD E2E 未验收。
4. **D4 开发中（数据底座完成）**：周期交易沿用服务端 `templateType=2` 模板合同。Room v13 已保存频率类型与参数、起止日期、时区、服务端执行分钟、下次执行时间、转账双账户/双金额、隐藏金额和排序；API 已支持按模板类型读取，并完整生成/解析周期模板字段，所有服务端 ID 继续按字符串传输。周期 payload/解析与 v1→v13 迁移组合回归 `OK (29 tests)`，构建与 lint 通过；管理 UI、启停/排序和隔离 Docker E2E 仍待完成。
   2026-09-05 补充：周期提交前已校验星期、月日、闰日、每 N 天必填开始日期、日期先后和显式时区；专项 `OK (4 tests)`。`SyncEngine` 已接入周期列表拉取，仅对 HTTP 400 / 服务端错误码 210003（功能未启用）跳过；其他错误继续传播。普通与周期模板使用独立 Room Flow；成功拉取周期全量列表后事务性清除缺失项，保留普通模板。流水一致性与周期替换专项 `OK (28 tests)`，构建与 lint 通过。管理 UI、功能状态展示和真实服务端验收尚未完成。
   管理操作底座补充：已提供模板隐藏/恢复与同类型排序 API，以及保留业务字段的暂停草稿转换；周期规则与 payload 专项 `OK (5 tests)`，构建和 lint 通过。服务端定时执行查询不检查 `hidden`，所以隐藏绝不能标为暂停；真正暂停必须提交 `scheduledFrequencyType=0` 和空频率。管理界面尚未接入上述操作。
   周期全量刷新安全边界：删除本地缺失项前，必须验证 success、result 数组、每项有效正整数 ID、ID 唯一及 templateType=2。缺失或异常 result 不得视为空列表；真实空数组才允许清空周期缓存。模板响应专项 `OK (3 tests)`，构建和 lint 通过。
   **D4 实施顺序修正（设计一致性核验）**：`docs/UI_UX_DESIGN.md` 第 12/16 节要求周期到期后确认入账。当前 `TransactionService.CreateScheduledTransactions` 直接调用 `CreateTransaction`，与目标不一致；不能仅交付周期 CRUD UI 就宣称 D4 完成。已有 `AIReviewItem` 只接受来源 1–3，且缺少周期执行唯一键与原子确认，不能直接冒用导入来源。

   D4 剩余交付顺序与门禁：

   1. 服务端增加周期执行待确认记录，保存模板 ID、计划发生时间、完整流水快照及状态；同一用户/模板/发生时间使用数据库唯一约束防止重复派发。待确认记录不得改变账户余额。已新增 `ScheduledOccurrence` 模型及数据库结构同步注册，使用上述三字段复合主键；快照保留双账户、双金额、标签、时区和隐藏金额。`go test ./pkg/models ./cmd` 及快照精度专项通过。尚未实际迁移运行库，未接入派发/确认服务；数据库并发去重仍需集成测试。
   2. 到期任务生成待确认记录；确认接口校验所属用户，并保证重复点击、请求重放或并发确认只创建一次流水，确认事务中同时记录流水 ID。忽略/恢复保留原执行标识。2026-09-06 已实现 `ScheduledOccurrenceService.Enqueue`：保存首次完整快照，插入冲突后按复合键读取已有记录，不覆盖已确认/忽略状态；拒绝暂停、删除和日期范围外模板。服务尚未接入 cron；确认接口及数据库并发集成测试未完成。
   确认服务进展：已新增 `ScheduledOccurrenceService.Confirm`，按认证用户和复合键查询快照；条件更新 pending 状态、创建流水/更新余额、写回流水 ID 均在同一事务内。重复已确认请求返回已有 ID。`CreateTransaction` 通过内部事务钩子复用原有创建逻辑。服务包与 cmd 测试通过，但尚缺数据库并发/回滚专项验证，未接 HTTP 接口或 cron，不能视作原子确认已验收。
   队列服务进展：新增按用户/状态过滤的有界分页（每页 1–100 项），按发生时间和模板 ID 稳定排序；忽略/恢复仅允许 pending 与 dismissed 互转，已确认记录不允许重新置为待确认，重复目标状态请求可重放。服务包与 cmd 测试通过；状态竞争、跨用户数据库隔离仍需集成验收。
   HTTP 接口进展：已在认证 v1 路由注册 `/schedule/review/list.json`、`confirm.json`、`dismiss.json`、`restore.json`。查询支持 status/offset/limit（默认 pending、50 项）；操作以字符串 templateId 和秒级 scheduledUnixTime 定位，用户 ID 只取会话，响应流水 ID 为字符串。API、services、cmd 包测试通过；尚未完成 HTTP/数据库端到端验证，cron 和客户端仍未接入。
   Cron 接入进展：到期任务现已调用 `ScheduledOccurrences.Enqueue`，不再直接创建流水，日志同步改为待确认语义。此变更尚未部署到运行服务；发布前必须先完成客户端待确认入口、数据库迁移与余额不变/重复派发/确认一次的集成验收。上文“cron 未接入”描述为历史进展，以此条为准。
   数据库验收进展：新增 `pkg/datastore/scheduled_occurrence_test.go`，检查真实 SQLite 复合主键、跨用户独立记录及状态回滚。该测试要求 CGO（文件有 cgo build tag）；本机首次执行因 CGO_ENABLED=0 的 sqlite stub 失败，尚未验收，不得计为通过。2026-09-06 Docker 服务已重新可连接（29.7.2），可继续用无卷隔离容器执行 CGO 测试和联合验收。
   2026-09-06 SQLite 专项已在 `golang:1.27.0-alpine3.24` 无卷容器 `finexy-cgo-review` 中以 CGO_ENABLED=1 执行并通过：`TestScheduledOccurrenceCompositeKeyAndRollback`。验证真实复合主键拒绝重复、不同用户记录独立、回滚保留 pending 与原快照。容器已退出并删除；此测试不覆盖完整 Confirm 服务的账户余额和并发请求，后两项仍待验收。
   确认输入校验补充：确认前验证持久快照的交易类型、账户/分类正 ID、金额范围、时区、标签数及转账目标字段，拒绝缺损快照。实际账户/分类/标签有效性由原流水创建逻辑在事务内复核。models/services/api/cmd 测试通过；仍不代替余额/并发端到端验收。
   2026-09-06 余额与并发验收补充：新增 `pkg/services/scheduled_occurrences_cgo_test.go`（cgo build tag），在 `golang:1.27.0-alpine3.24` 无卷容器（阿里云 apk 镜像源装 gcc，本机默认 CDN 被网络阻断）以 CGO_ENABLED=1 全部通过，共 6 项：Confirm 后恰好一条流水且余额精确变化（支出扣减、转账 OUT/IN 双行含 RelatedId 链接与跨币种金额）、重复确认返回同一流水 ID、重复派发保留首次快照、已确认记录拒绝 dismiss/restore；8 路并发确认恰好入账一次且全部返回同一流水 ID；跨用户列表隔离与跨用户确认拒绝（确认前后皆拒绝）、dismiss 门禁与重复 dismiss/restore 幂等重放；真实 cron 函数体 `CreateScheduledTransactions` 到期入队不动账、二次派发不重复、暂停模板不入队；闰日/月末/时区边界（月 29 仅闰年入队、月 31 仅 31 天月入队、年 0229 非闰年不入队、UTC+8 模板按模板时区判定日界）；缺损快照（非法类型、超额金额、非转账带目标字段）确认时被拒且 occurrence 保持 pending。连同既有 `TestScheduledOccurrenceCompositeKeyAndRollback`，上文"尚缺数据库并发/回滚专项验证""后两项仍待验收""状态竞争、跨用户数据库隔离仍需集成验收"均以本条为准，服务端数据库级验收已完成。
   2026-09-06 HTTP/数据库联合验收补充：用工作树构建的后端二进制（未重建运行服务镜像）在无卷隔离容器 `finexy-d4-e2e`（SQLite、EBK_WORK_DIR=/work、EBK_AUTH_ENABLE_REGISTER=true，数据随容器销毁）按真实 15 分钟 cron 周期完成端到端验收，22 项全部通过：到期模板由 cron 实际派发入待确认队列、入队后余额不变（仅存在建账时的 MODIFY_BALANCE 期初行）、无支出流水；用户 B 跨用户确认被拒且列表不可见；dismiss 后确认被拒、恢复后确认成功；确认恰好一条支出 420000、余额 1000000−420000=580000；重复确认返回同一 transactionId；待确认列表 status 过滤与已确认/已忽略状态联动正确。驱动脚本 `artifacts/d4-accept/occurrence_e2e.go`，证据 `artifacts/d4-accept/http-joint-acceptance-final.txt` 与 `cgo-occurrence-tests-final.txt`。回归：本地 `go test ./...`（CGO_ENABLED=0）全部通过，`npm run check` 通过。上文"尚未完成 HTTP/数据库端到端验证"以此条为准。
   2026-09-06 服务端行为核实（供客户端 D4 UI 使用）：模板 API 无执行时刻入参，`scheduledAt` 由服务端按"模板时区午夜"固定推导（负 utcOffset 得 `-offset` 分钟、正 utcOffset 得 `1440-offset` 分钟），即周期交易在模板时区午夜的 15 分钟 cron 窗口派发；管理页与文案不得宣称可自选执行时刻。确认接口响应为 `{"transactionId":"..."}` 对象。创建带初始余额的账户会生成一条 MODIFY_BALANCE（type=1）期初流水，客户端列表/统计需按类型区分。运行服务（8080）尚未部署含本变更的镜像；cron 变更发布前仍须先完成客户端待确认入口。
   2026-09-06 Android 客户端交付（D4 第 3/4 项）：数据层新增 Room v14 `scheduled_occurrences` 表（templateId+scheduledUnixTime 复合主键，与服务器唯一约束一致）及 `MIGRATION_13_14`（同时为模板表增加暂停前频率备份列 pausedFromFrequencyType/pausedFromFrequency，恢复暂停时还原规则而非要求重填）；备份 schema 升至 10 并保持 8/9 旧备份可恢复。`FinexyApi` 新增 `/schedule/review/` list（有界分页全量拉取、逐项校验标识/状态/快照、重复检测、仅真实空数组允许清空）、confirm（解析字符串 transactionId）、dismiss/restore；SyncEngine 复用周期功能开关探测（210003 时模板与队列均保持本地现状），拉取 pending+dismissed 并事务性对账（服务器已确认的记录本地移除）。UI 新增 `ScheduleScreens.kt`：待确认入账队列页（待确认/已忽略分区、确认入账预览对话框、忽略、恢复、刷新与失败反馈）与周期计划管理页（列表、新增/编辑完整表单：类型含转账双金额、账户/末级分类选择、五类频率、±HH:MM 时区（执行时刻=该时区午夜）、起止日期、备注、隐藏金额，编辑前预览确认，暂停/恢复、确认删除、上移/下移排序）；设置页新增"周期与待确认"入口，总览在待确认数 >0 时显示角标卡。自动化：`ScheduleOccurrenceTest` 8 项（解析合同、Room 对账与状态回写、暂停/恢复规则、v13→v14 迁移）、`ScheduleUiTest` 5 项（队列页对话框、计划页表单/暂停/排序语义、频率与时区标签合同）、备份 schema 10 往返与 9/8 旧格式恢复、迁移链全部补齐至 v14；全量回归 `OK (97 tests)`，lint 通过。测试过程中模拟器 system_server 曾 DeadSystemException 崩溃一次（已知模拟器偶发问题），重启后全量重跑通过。
   2026-09-06 客户端隔离 Docker E2E 补充：新增 `DockerOccurrenceE2ETest`（无 `finexy.e2e.url` 时按设计跳过），在无卷一次性容器上以真实 15 分钟 cron 周期完整通过：客户端创建两个到期周期模板 → cron 实际派发入队 → SyncEngine 拉取待确认记录且账户余额不变、无支出流水 → dismiss/restore 状态回写 → 确认入账返回流水 ID 且重复确认幂等 → 同步拉回恰好一条支出 420000、余额 1000000−420000=580000 → 跨用户确认被拒。证据 `artifacts/d4-accept/docker-occurrence-e2e-final.txt`。注册接口必需 `categories` 字段（否则 HTTP 400）。冷启动主 App 验证：主账本仍为 4 条流水，无 FATAL EXCEPTION、无 Room migration 错误。D4 的深浅主题/无障碍人工验收仍待完成；运行服务（8080）已于同日部署本变更（见下条部署记录）。
   2026-09-06 运行服务部署：完成上述全部客户端验收门禁（Android/Web 待确认入口 + 余额不变/重复派发/确认一次 + 隔离 Docker 联合验收）后，已将含周期待确认变更的镜像部署到 8080 运行服务。由于本机 apk 默认 CDN 被网络阻断（标准 compose 构建会在 `apk add` 卡死），部署采用分层覆盖：以部署前镜像打回滚标签 `ai-bookkeeping-bookkeeping:rollback-pre-scheduled-review`（102MB，含旧二进制与旧前端），在其上 COPY 新编译的静态链接后端二进制（`./build.sh backend --no-test --no-lint`，CGO_ENABLED=1，与 Dockerfile 构建方式一致）与 `dist/` 新前端，构建为 `ai-bookkeeping-bookkeeping:latest`，再用 `docker-compose up -d --no-deps --force-recreate bookkeeping` 重建容器（本机只有 docker-compose v5.3.1，`docker compose` v2 插件不可用）。验证：healthz commit 变为 `8fae01d`，cron `CreateScheduledTransaction` 已注册，`/schedule/review/list.json` 返回 202012 "token is empty"（到达认证层；不存在的路径为 404）。用户数据卷未触碰。回滚方式：`docker tag ai-bookkeeping-bookkeeping:rollback-pre-scheduled-review ai-bookkeeping-bookkeeping:latest` 后重建容器即可。此部署后 cron 到期将真实生成待确认记录（不再自动入账）。
   2026-09-06 Web 端待确认展示（D4 第 3 项 Web 部分）：新增 `src/models/scheduled_occurrence.ts`（响应模型 + `ScheduledOccurrence.of` 严格校验：正模板 ID、正发生时间、状态 1–3、快照必含正账户/分类 ID，缺损即抛错）、`src/stores/scheduledOccurrence.ts`（pending+dismissed 分页拉取与本地分区；HTTP 400/403/404 视为功能未启用呈现空队列而非错误态；confirm 成功后移除该记录；dismiss/restore 成功后整队重拉，避免本地猜测状态）。`services.ts` 注册 `/schedule/review/` list/confirm/dismiss/restore 四端点。工作台"周期计划"页（program）在计划列表上方新增"待确认入账"区块：待确认行（计划日、名称、账户→账户与备注、金额或 ····、确认入账/忽略按钮）与已忽略行（恢复按钮、降透明度），无待确认时显示明确文案"到期计划会出现在这里，确认后才入账"；确认后自动刷新页面数据拉回新流水。`toggleSchedule` 由 hide 冒充暂停改为真实语义：暂停时提交 `scheduledFrequencyType=0`+空频率并在本地 stash 原频率（页面级 Map，恢复时还原后重新提交），无备份时提示编辑重设而非猜错；toast 文案区分"暂停，到期后不再生成待确认记录"/"启用，到期后将进入待确认队列"。门禁：`npm run check`（lint + vitest + build）通过。2026-09-06 浏览器真实渲染验证已通过：在无卷一次性容器（部署同款镜像，端口 18087）以真实账号走完"登录 → 计划页 → 待确认入账区块渲染（两条到期记录、金额/账户/备注/日期齐全）→ 忽略（行进入已忽略态并出现恢复按钮，aria-live 播报"已忽略，可在此恢复"）→ 恢复路径存在 → 确认入账（该行从队列消失）→ 流水页恰好一条支出 ¥4,200/备注"确认入账"→ 余额与统计正确"。所有按钮带 aria-label（如"确认入账：房租计划"）。深浅主题结论：工作台页面为浅色定制基线（`.workspace` 变量硬编码浅色、无 v-theme--dark 覆盖，为既有设计），新增区块复用同一变量体系，强制切换 v-theme--dark 后页面渲染一致、无破损、可读性不变，与页面其余部分行为完全相同。验证容器已删除。未做（按计划归入人工验收）：TalkBack/读屏完整听读与已录入指纹设备的生物识别成功路径。
   3. Web 与 Android 展示相同待确认状态。Android 周期管理归属设置二级页；待确认入口沿用总览与设置。启用计划文案必须明确到期后待确认，不能把隐藏表述为暂停。
   4. 管理页完成新增/编辑、明确时区、暂停/恢复、排序、确认删除、加载与失败重试；保留转账双金额，账户/分类以 ID 选择。
   5. 服务端测试覆盖重复派发、并发确认、跨用户拒绝、暂停、月末/闰日和时区；隔离 Docker 联合验收必须证明"到期余额不变 → 用户确认 → 恰好一条流水与正确余额"，再补 UI 深浅主题及无障碍验收。2026-09-06：上述服务端专项（CGO 6 项 + datastore 复合主键）与隔离 Docker HTTP 联合验收（22 项）均已完成并留存证据，见上方两条验收补充；本项剩余部分仅为 UI 深浅主题及无障碍验收，且服务端变更尚未部署到运行服务（发布前必须先完成客户端待确认入口）。
5. **D5 开发中（Android 首版与真实服务链路已闭环）**：Room v15 新增 `ai_review_items`，交易增加 `reviewItemId`；SyncEngine 拉取服务端 `/ai/review/list.json`，严格拒绝缺失、重复或非法 ID/状态。记账页与设置页已有 AI/OCR 入口，支持自然语言识别、相册或系统相机票据、四角拖动裁剪、90° 旋转、EXIF 方向纠正、自托管 OCR 后再结构化、待复核 Room Flow 列表、失败原因、忽略、手动补全/核对后入账。相机原图只写入应用缓存并在解码后删除，裁剪结果以内存 JPEG 发送 OCR，不写公共相册。隐私授权统一使用 D8 当前账本偏好，不再读取跨账本的旧 `cloud_recognition_consent`；OCR 完成后先展示提取文字与总置信度，用户明确点击“发送并结构化”才调用模型，原图不发送给模型或持久化。识别 API 使用独立 130 秒读取/写入超时，普通 API 仍使用默认超时。裁剪区会朗读当前边界，并提供扩大、缩小、上下左右移动及重置的 TalkBack 自定义操作；2026-09-08 已在 Pixel 8 的实际 TalkBack 服务绑定状态下复核语义树，随后恢复服务关闭状态。确认草稿先进入正常离线同步，只有流水上传成功后才 resolve 服务端复核项；2026-09-08 真实 E2E 发现上传后立即 pull 会丢失本机 `reviewItemId`，现已在远端合并时保留该客户端元数据直到 resolve，并由专项回归锁定。备份为 schema 11 并兼容 8–10。Android build/lint 通过；AI/OCR UI、图片变换与合同测试组合通过，无卷隔离 Docker 使用独立 OCR 容器与真实 DeepSeek 完成“合成票据 OCR → 自然语言结构化 → 创建/回拉待复核 → Android 上传流水 → 服务端 resolve → 双端队列清空”`OK (1 test)`，临时容器、网络和随机账号已随无卷环境删除。浅色识别页、深色 OCR 确认对话框、横屏 + 系统 1.3× 字体及裁剪编辑器已人工核验，证据位于 `artifacts/android-ui/`。尚未完成：字段级置信度（服务合同当前只有 OCR 总置信度）。云端识别开关已由 D8 用户偏好页承担。2026-09-07 的 `connectedDebugAndroidTest` 数据清空事故使“主账本仍为 4 条”门禁失败，恢复前不得把 D5 标记全面完成。
6. **D6 资产（Android 首轮闭环完成）**：Room v16 新增 `product_assets`，缓存服务端完整业务字段与整数金额估值快照；列表全量成功后才删除本地缺失项，非法、重复或不完整响应不会清空缓存。`FinexyApi` 已按字符串 ID / Unix 秒边界接入 list/add/modify/sell/delete，`SyncEngine.sync()` 拉取资产。账户页新增“资产与折旧”入口；资产页支持持有/已售出/全部筛选、累计购买/账面/参考价值、缓存离线查看、新增编辑、清除手动估值、登记售出和永久删除确认。出售与资产记录都不会静默创建或删除流水。备份升至 schema 12 并兼容 8–11。Android 构建与 lint 通过；合同、v15→v16、UI、备份组合 `OK (15 tests)`，历史迁移链专项 `OK (6 tests)`；无卷隔离 Docker 真实走通随机账号创建 → SyncEngine 回拉 → 修改 → 清除估值 → 售出 → 删除 `OK (1 test)`。浅色、深色、横屏 + 1.3× 字体及安全区已人工核验，证据为 `artifacts/android-ui/d6-assets-final.png`、`d6-assets-dark-final.png`、`d6-assets-landscape-large-font-final.png`。服务端当前资产合同没有币种字段，Android 沿用用户默认币种展示，禁止虚构每项资产币种；来源/售出流水 ID 合同已保留，但首版 UI 与 Web 一致暂传 0。完整 TalkBack 听读归阶段 E。
7. **D7 汇率（Android 首轮闭环完成）**：Room v17 新增 `exchange_rates`，以十进制字符串保留服务端精度，并保存基准币、报价币、数据来源、参考链接、服务端更新时间和本地抓取时间；完整快照通过严格校验后才替换，非法/重复/缺少基准币的响应不会清空旧缓存。外部行情超过 96 小时标记过期且不参与转账建议，`user_custom` 永久明确标记为“用户维护、非实时市场价”。`FinexyApi` 已接入 latest 与 user_custom update/delete，`SyncEngine.sync()` 在第三方行情源失败时保留旧缓存且不阻断流水同步。账户页新增汇率入口；汇率页支持来源/时间/新鲜度、基准币切换换算、自定义汇率增改删与离线缓存；跨币种转账只显示可用汇率建议，由用户主动采用，缺失/过期时仍要求手填。备份升至 schema 13 并兼容 8–12。Android build/lint 通过；汇率合同/UI/备份组合 `OK (16 tests)`，历史迁移链/流水/周期/资产组合 `OK (39 tests)`；无卷隔离 Docker 的 `user_custom` 真实完成新增 → 精度回读 → SyncEngine 回拉 → 快速修改 → 连续删除 `OK (1 test)`。E2E 同时发现并修复服务端同币种同秒连续写入时 `deleted_unix_time` 复合主键冲突，Go services/models/api 测试通过。浅色、深色、横屏 + 1.3× 字体及安全区已人工核验，证据为 `artifacts/android-ui/d7-exchange-rates-light-final.png`、`d7-exchange-rates-dark-final.png`、`d7-exchange-rates-landscape-large-font-final.png`。完整 TalkBack 听读归阶段 E。
8. **D8 用户偏好（Android 首轮闭环完成）**：已按 Go `user_application_cloud_setting` 合同建立显式 typed key 注册表；云端只接受允许列表内的布尔、数值、字符串和 `Map<String, Boolean>`，未知键、非法类型与非法图表颜色均降级到本地有效值。默认账户/币种写入用户资料并供多设备共用；账户余额、首页金额、自动汇率、图表颜色与统计账户/分类筛选可选择同步，服务器拉取值优先于这些本地副本。主题、字号、启动页、定期同步策略、AI 文本/云端结构化授权、PIN、token 与生物识别状态只保存在设备或当前账本范围，永不上传；退出后不会应用到另一用户，重新登录同一账本时保留本地专属设置。设置页已提供账户/币种、外观、启动与同步、显示与统计、AI 隐私、云端偏好分组，具备保存中、错误、重试与隐私边界说明；手动策略只取消周期任务，不禁用事件驱动离线队列。云更新采用读取后合并的完整写入，避免 Android 擦除 Web 端尚未展示的合法键；关闭云设置走服务端 disable API。`UserPreferencesContractTest` 与 `UserPreferencesUiTest` 合计 `OK (8 tests)`，无卷隔离 Docker 真实完成创建偏好 → 部分覆盖保留 → 第二设备回拉 → 默认账户/币种更新 → 隐私字段排除 → 关闭云设置 `OK (1 test)`；build/lint 通过。浅色、深色、横屏 + 系统 1.3× 字体已人工核验，证据为 `artifacts/android-ui/d8-user-preferences-light-final.png`、`d8-user-preferences-dark-final.png`、`d8-user-preferences-landscape-large-font-final.png`。完整 TalkBack 听读归阶段 E。

所有阶段 D 项目必须核对实际 Go API 和 Web 行为，不能凭名称猜 payload。

### 阶段 F：家庭组记账、多账本与存钱计划

交互原型已完成并单独验收（`docs/prototypes/family-web/`，2026-09-12 存钱计划补齐后 72 项 Playwright 检查全部通过，证据 `artifacts/family-web/family-goals-list.png`）。服务端数据层、API 合同、Web 桌面生产 UI、Android 首轮闭环、真实存钱转账、全局账本切换及账户跨账本迁移已交付；移动 Web 路由与阶段 E 人工适配验收仍未完成，不得宣称阶段 F 全面完成。

**2026-09-16 产品方向修正（后续实现的最高优先级）**：信息架构改为“以账本为中心”。任何已登录用户都可以直接创建多个账本，不要求先创建家庭；账本详情统一承载名称、描述、成员、角色、邀请、退出/删除及其他账本级设置。现有“家庭共享”不再作为独立页面或独立一级业务对象呈现，“家庭账本”仅保留为创建账本时的推荐模板，用于预填名称、共享说明和默认角色建议，创建完成后与普通账本进入同一详情和成员管理流程。后续页面、接口和客户端状态不得继续增加对“先有 FamilyGroup、再建 Ledger”流程的依赖。

迁移按以下顺序执行：① 服务端把账本成员关系、角色和邀请变为 Ledger 直接资源，并允许单人创建显式账本；② 提供旧 FamilyGroup/FamilyMember/FamilyInvitation 到账本成员的兼容读取与幂等迁移，迁移期间旧接口只作兼容层；③ Web/Android 增加统一“创建账本”和“账本详情”，将成员与邀请入口迁入详情；④ 移除导航中的独立“家庭共享”入口，创建页提供“个人账本”“家庭共享”等模板；⑤ 完成真实隔离 Docker E2E 后再删除旧家庭接口和客户端缓存表。迁移不得改变默认账本 id 0、既有账本/流水/账户/目标 ID、余额或成员访问权限，也不得把模板类型持久化成新的权限分支。

1. **服务端数据模型与合同（已完成）**：`pkg/models/family.go`（FamilyGroup/FamilyMember/FamilyInvitation，角色 OWNER/ADMIN/MEMBER/VIEWER、成员状态、邀请一次性 token 生命周期）、`pkg/models/ledger.go`（Ledger，类型 PERSONAL/FAMILY）、`pkg/models/savings_goal.go`（SavingsGoal/SavingsGoalFund）。关键合同决策：`DefaultLedgerId=0` 表示用户默认个人账本，既有数据行无需迁移即归属默认账本；`Account` 新增 `LedgerId` 列（NOT NULL DEFAULT 0，创建账户时可经 `ledgerId` 入参指定并做 CanManage 访问校验；既有账户可通过专用迁移接口整体迁移，限制见第 9 条）；目标不存余额，`savedAmount` 永远由 `SavingsGoalFund` 动账记录派生（存入加、取出减），存入/取出不计收入/支出、不产生流水、不改账户余额（`TransactionId` 为保留字段，转账流水后续交付）；取出不得超过净存入；有动账记录的目标禁止删除。`uuid` 类型 4-bit 槽位已满，新增 `UUID_TYPE_FAMILY=15` 由家庭域全部六张表共享（生成后从不解码校验，唯一性不受影响）。家庭域行（组/成员/邀请/家庭账本/家庭账本目标与动账）全部存放在**家庭所有者的 UserDataStore 分片**，成员通过自己的 membership 行定位分片（每分片一次索引查询，单库部署代价为一次查询）；默认个人账本目标与动账落在操作者自己的分片。错误码子类 Family/Ledger/SavingsGoal = 25/26/27（`pkg/errs/family.go`、`ledger.go`、`savings_goal.go`）。六张新表已在 `cmd/database.go` 注册，`auto_update_database=true` 时随 web server 启动自动建表。
2. **服务端 API（已完成）**：认证 v1 路由新增 `/family/group/{create,list,modify,delete}.json`、`/family/member/{list,change_role,remove,leave}.json`、`/family/invitation/{create,list,revoke,accept}.json`、`/ledger/{list,create,modify,delete}.json`、`/savings_goal/{list,get,create,modify,delete,deposit,withdraw}.json` 与 `/savings_goal/funds/list.json`（`cmd/webserver.go`）。权限：家庭与目标的创建/编辑/删除需要 CanManage（所有者/管理员），存入/取出需要 CanWrite（不含只读成员），读取对全部活跃成员开放；所有者角色不可改删、所有者不可退出家庭；邀请默认 24 小时有效、可撤销、过期懒标记、token 重放拒绝、重复加入拒绝、离队/移除保留历史行。
3. **验收与证据（2026-09-12）**：模型/服务单测随 `go test ./...` 44 包全部通过（CGO_ENABLED=0）；`pkg/services/families_cgo_test.go`（cgo build tag）在 `golang:1.27.0-alpine3.24` 无卷容器以 CGO_ENABLED=1 通过，覆盖建家→邀请→加入→改角色→撤销/过期→账本隔离→目标存取→删除限制→跨用户隔离全流程，证据 `artifacts/family-server/family-ledger-goal-cgo-test-final.txt`；HTTP 联合验收在无卷一次性容器 `finexy-family-e2e`（SQLite、EBK_WORK_DIR=/work、EBK_AUTH_ENABLE_REGISTER=true，数据随容器销毁，已删除）以真实二进制完成 36 项检查全部通过：四个随机账号注册、邀请/加入/重复拒绝、角色管理、家庭账本与家庭账户（`ledgerId` 入参）、目标创建/存入/取出/超额拒绝/达成判定/删除门禁/动账记录、动账不改余额、默认个人账本隔离与家庭账户不能为个人目标注资、只读成员只读、陌生人无访问。驱动 `artifacts/family-accept/family_e2e.go`，证据 `artifacts/family-accept/http-joint-acceptance-final.txt`。临时容器已核对删除，运行服务（8080）与真实数据卷未触碰。
4. **踩坑补充**：apk 默认 CDN 被阻断再次实测复现（容器内 `apk add` 30 分钟无进度），必须先 `sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories`；Git Bash 的 MSYS 路径转换会把 `docker create -e EBK_WORK_DIR=/work` 的值改写为 `C:/Program Files/Git/work`（env 在 create 时固化，exec 阶段改不了），必须对 create/exec 全程使用 `MSYS_NO_PATHCONV=1`。静态链接构建命令：`CGO_ENABLED=1 go build -trimpath -ldflags '-w -s -linkmode external -extldflags -static' -o <out> ezbookkeeping.go`（容器内需 gcc musl-dev）。运行期除 `EBK_WORK_DIR` 外还需存在 `public/`、`storage/`、`log/`、`data/` 目录。
4. **Web 生产 UI（桌面工作台，2026-09-12 交付）**：新增路由 `/savings/goals`（存钱计划）与 `/family/manage`（家庭共享），挂入工作台工具入口（`FinexyWorkspacePage` 新 pageKey 分支 + `src/router/desktop.ts`）。新文件：`src/models/family.ts`、`ledger.ts`、`savings_goal.ts`（`.of` 严格校验：非法 ID/角色/状态/金额即抛错，缓存不清空）、`src/stores/family.ts`、`ledger.ts`、`savingsGoal.ts`（400/403/404 降级为"功能不可用"空态）、`src/lib/services.ts` 注册全部家庭/账本/存钱端点。存钱计划页：页内账本选择（默认个人账本隐式 id 0 + 显式账本）、目标卡（进度条、已存/目标、达成 pill、截止日期）、新建/编辑（内联表单层）、存入/取出（账户下拉来自新端点 `/savings_goal/accounts.json?ledgerId=`，家庭账本共享账户跨成员可见——见第 5 条服务端补充）、删除需无资金记录且有确认对话框；指标区随数据联动。家庭共享页：创建/修改家庭、成员列表（昵称+角色，来自服务端新 nickname 字段）、权限行内修改（所有者/管理员）、移除/退出/解散均有确认、邀请生成（页面展示一次性邀请码）与撤销、邀请码加入。服务端为支撑 UI 补充：`AccountInfoResponse` 增加 `ledgerId`（向后兼容）、成员响应增加 `nickname`（服务端从 UserStore 解析）、新增 `GET /family/member/me.json`（当前用户角色）与 `GET /savings_goal/accounts.json`（账本内可用账户，含家庭共享账户）。浏览器真实渲染验证在无卷一次性容器完成（真实登录表单/邀请码加入/表单建目标/真实存入/进度联动），证据 `artifacts/family-web/web-production-verification-2026-09-12.md` 与两张截图。门禁：`npm run check` 完整通过（typecheck + eslint + vitest 38477 + build）；此前阻塞 build 的两处 ReconciliationStatement 预存类型错误以最小显式泛型修复（不改行为）。未做：移动端路由接入、全局账本切换器（依赖服务端流水/统计的 ledgerId 过滤，见第 5 条①）、读屏完整听读。
5. **Android 端（2026-09-12 交付，首轮闭环）**：Room v17→v18 非破坏迁移新增 4 张表（`family_groups`、`family_members`、`ledgers`、`savings_goals`，`MIGRATION_17_18` 纯 CREATE TABLE），备份 schema 13→14 且 8–13 旧备份仍可恢复（旧 schema 恢复测试同步剔除四张新表）。`FamilyLedgerData.kt` 定义远程模型（严格解析：字符串 ID、角色 1–4、状态 1–3、账本类型与 familyId 一致性、目标金额/达成字段校验、邀请一次性 token）、`SavingsGoalDraft` payload 与 `toEntity/toRemote` 转换；`FinexyApi` 接入全部 `/family/*`、`/ledger/*`、`/savings_goal/*` 端点，`listLedgersIfEnabled/listFamilyGroupsIfEnabled` 对旧服务端的 400/403/404 返回 null（保持本地缓存，不视为空态）。`TransactionRepository` 新增 replace 家族（重复/非法 ID 拒绝、仅真实空列表清空、账本列表禁止包含默认账本、成员按 familyId 对账）与 `cacheSavingsGoal/removeSavingsGoal`；`SyncEngine.sync()` 在汇率之后拉取账本 → 家庭组 → 每家庭成员 → 合并所有可见账本（含默认账本）的存钱目标为一次完整快照，任一账本失败即整体失败保持旧缓存。UI：设置页新增"家庭与存钱"分组，`SavingsGoalScreens.kt` 提供存钱计划页（账本 FilterChip 切换、目标卡进度条、新建/编辑对话框、存入/取出对话框（账户来自 `/savings_goal/accounts.json`）、删除确认、口径文案）与家庭共享页（空态创建/邀请码加入、家庭卡、成员昵称+角色、管理员行内权限/移除、邀请生成展示邀请码与撤销、退出/解散确认）。门禁：`:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 通过；专项组合回归 `OK (62 tests)`（`FamilyGoalContractTest` 9 项 API 严格解析/Room 对账/失败保旧缓存/仅真实空数组清空/v17→v18 迁移，加迁移链补齐 17→18 的既有一致性、备份 schema 14 往返与 8–13 旧格式恢复、周期/资产/汇率合同），证据 `artifacts/family-accept/android-combined-regression-final.txt`；无卷一次性容器（工作树分层镜像 `finexy-family-e2e-server`，端口 18093）真实双用户 E2E `DockerFamilyGoalE2ETest` `OK (1 test)`：注册→邀请→加入→升级管理员→家庭账本+共享账户（ledgerId）→目标创建→存入→成员端 SyncEngine 缓存与账本/目标/家庭一致→取出→所有者端快照一致→陌生人 403 拒绝→动账不改余额不产生流水，证据 `artifacts/family-accept/docker-family-goal-e2e-final.txt`，容器与分层镜像已删除。冷启动主 App：logcat 无 FATAL EXCEPTION、无 Room migration 错误，v17→v18 迁移在既有 `finexy.db` 上正常执行；**主账本"4 条流水"门禁维持 2026-09-07 事故后未恢复状态（设备现展示引导页），本轮未新增破坏，恢复仍需原服务器登录态或用户确认**。
6. **踩坑补充（Android 专项）**：Room 的 Flow 在测试中 `collect` 永不完成，必须用 `first()` 取首个发射值（挂起等待会卡死 instrumentation）；JUnit4 测试方法 `= runBlocking { ... }` 尾表达式必须为 Unit（assertThrows 返回值会触发 "should be void"）；嵌套 `runBlocking` 包 suspend 断言会死锁 instrumentation 主线程，改用 try/catch 捕获期望异常；Gradle clean 后的 lint 缓存可能被守护进程锁定（`FileSystemException: lint-cache`），先 `--stop` 再构建。
7. **存钱计划真实转账与账本查询（2026-09-15 完成）**：`SavingsGoalService` 的存入/取出现在与资金记录、账户余额和 `SavingsGoalFund.TransactionId` 在同一数据库事务内提交；存入生成外部 `TRANSFER_OUT`，取出生成外部 `TRANSFER_IN`，仅有账本内账户一侧，分类 ID 为 0，并通过 `SavingsGoalFundId` 形成不可编辑、不可删除的一一关联。并发取出先锁定目标并重新计算净存入，任一步失败会整体回滚；旧资金记录的零 `TransactionId` 继续兼容。家庭流水落在家庭所有者数据分片，保留实际记账人/付款人；家庭账户期初余额流水同步继承 `ledgerId`。流水 count/list、账户 list、统计与月度趋势接口新增 `ledgerId` 查询，先做成员访问校验，再按账本及家庭所有者分片过滤；未传参数时显式限制为默认个人账本，避免家庭数据泄漏。Web 请求模型与 Android 资金记录/外部转账解析已跟进，存取成功后两端都会刷新账户余额。验证：`go test ./...` 全部通过；CGO SQLite `TestFamilyLedgerAndGoalServiceFlow` 通过，覆盖余额、TransactionId、成员/陌生人隔离、流水/账户/统计账本过滤；`npm run check` 通过（10 files、38477 tests）；Android assembleDebug/assembleDebugAndroidTest/lintDebug 通过，`FamilyGoalContractTest` `OK (10 tests)`；无卷隔离容器 `finexy-goal-e2e` 上 `DockerFamilyGoalE2ETest` `OK (1 test)`，真实双用户链路验证 1000000 存入、200000 取出后账户余额为 200000，家庭账本含两条目标转账及一条期初余额，默认个人流水/账户/统计为空。证据：`artifacts/family-accept/family-goal-transfer-cgo-final.txt`、`android-family-goal-contract-final.txt`、`docker-family-goal-e2e-final.txt`。
8. **全局账本切换与账本页数据（2026-09-15 完成）**：Web 桌面总览及工作台顶栏共用全局账本选择状态，切换后总览余额/账户/近期活动、流水、账户管理和报表按所选账本重新加载；家庭账本写入口保持只读并给出明确反馈。流水月度接口补齐 `ledgerId`，与既有流水分页、账户、统计和趋势接口一样先校验成员权限，再从家庭所有者分片读取；Web 月度请求也携带字符串 ID。Android Room v18→v19 非破坏迁移新增 `ledger_account_cache` 与 `ledger_transaction_cache`，以 `(ledgerId,id)` 复合主键隔离相同服务端 ID；SyncEngine 在所有显式账本响应完整成功并严格校验后才更新账本清单，再逐账本原子替换账户/流水缓存，失败保留旧快照。主界面顶栏新增账本切换器，首页、流水、账户及指标观察所选账本 Flow；家庭账本明确标注只读并禁用/拦截编辑入口。验证：`go test ./...` 全部通过；`npm run check` 通过（10 files、38477 tests 与生产构建）；Android assembleDebug/assembleDebugAndroidTest/lintDebug 通过，全量 `OK (146 tests)`，缓存隔离与 v18→v19 专项 `OK (11 tests)`；无卷隔离容器的 `DockerFamilyGoalE2ETest` `OK (1 test)`，真实同步家庭账本 1 个账户和 3 条流水。Web 真实浏览器在当前工作树一次性服务验证默认/家庭两个选项、跨总览/流水/账户/报表保持选择、家庭余额 1234.56、只读提示及 ledgerId 请求，1024×768 无横向溢出。一次性容器与镜像已删除；8080 用户服务未触碰。Android 深浅主题、横屏大字体和完整 TalkBack 人工听读仍归阶段 E。
9. **账户跨账本迁移与删除语义（2026-09-16 完成）**：新增认证接口 `POST /accounts/move_ledger.json`，以字符串 `id` 与必传 `targetLedgerId` 操作完整根账户组；服务端在同一事务内迁移根/子账户及完全属于该组的历史流水，保留显示秒和余额，目标为家庭账本时切换数据所有者并补齐实际记账人/付款人。跨账户转账、存钱资金记录、交易模板及待确认/已忽略周期快照仍引用账户时拒绝迁移并完整回滚；家庭账户只有原创建者可迁回自己的默认个人账本，目标或来源分片不同时拒绝非原子复制。账户与子账户删除现按账本 CanManage 权限定位家庭所有者分片，同时检查流水的转出、转入两侧；仅有期初余额调整时可连同期初行删除，其余引用继续阻断。Web 账户详情新增迁移弹窗、风险说明、目标账本选择及失败反馈，成功后切换目标账本并重新加载；Android 账户页提供等价对话框，调用成功后全量同步并切换账本。验证：`go test ./...`、`npm run check`、Android assembleDebug/assembleDebugAndroidTest/lintDebug 通过；真实 SQLite CGO `TestFamilyLedgerAndGoalServiceFlow` 通过；账户管理 UI `OK (4 tests)`，普通全量回归 `OK (147 tests)`（视觉停留、Docker 与进程恢复专项按参数门禁跳过）；无卷 tmpfs 隔离服务 `DockerFamilyGoalE2ETest` `OK (1 test)`，覆盖个人↔家庭迁移、家庭删除、目标关联拒绝；Web 真实浏览器在 1024×768 下验证迁移后余额与期初流水保留、请求 200、无横向溢出及无控制台错误。2026-09-16 已更新本地 8080 运行服务：以部署前镜像标签 `ai-bookkeeping-bookkeeping:rollback-pre-account-ledger-migration-20260916` 保留回滚点，分层覆盖当前静态链接后端与 `dist/`，仅用 `docker-compose up -d --no-deps --force-recreate bookkeeping` 重建 bookkeeping；healthz 为 `1c4d07a-local-20260916`、容器 healthy，后端/前端 SHA-256 与构建产物一致，账户迁移路由到达认证层，数据/日志/附件三个宿主机绑定目录保持不变，OCR 未重建。
10. **未完成（按依赖顺序）**：① 按上述产品方向完成账本成员模型、兼容迁移、统一创建页与账本详情；② 数据管理 `DeleteAll` 接入迁移前后相关表及两张账本缓存表（含 Android 备份语义）；③ Web 移动端路由接入；④ Android 账本/存钱页及全局账本切换器的深浅主题、横屏大字体与 TalkBack 人工验收（归阶段 E 口径）；⑤ 存钱计划的离线操作队列（当前 Android 存入/取出/建删目标为在线操作，离线队列归后续）。
11. **账本中心迁移第一步（2026-09-16）**：服务端新增 `LedgerMember` 直接成员表，使用 `(ledgerId, uid)` 唯一约束并复用既有 OWNER/ADMIN/MEMBER/VIEWER 与 ACTIVE/LEFT/REMOVED 语义；数据库自动结构维护已注册。新建显式个人账本会在同一事务写入创建者 OWNER 成员，新建旧式家庭账本会把当时所有活跃家庭成员及角色快照写入账本成员，任一成员写入失败则账本创建整体回滚。账本访问校验优先读取直接成员，旧个人账本以 owner 兼容、旧家庭账本以 FamilyMember 兼容；列表按 ID 去重。新增只读接口 `GET /ledger/member/list.json?ledgerId=`，新账本返回直接成员，迁移前旧账本返回兼容成员视图和昵称。模型/服务/API/cmd 专项及 `go test ./...` 全部通过；`golang:1.27.0-alpine3.24` 无卷容器中 CGO SQLite `TestFamilyLedgerAndGoalServiceFlow` 通过，覆盖个人账本自动所有者成员、家庭账本三成员快照及既有完整流程。尚未实现账本邀请、角色调整、移除/退出和持久化旧数据迁移，运行服务 8080 未部署本步变更。
12. **账本直接成员管理（2026-09-16）**：新增 `LedgerInvitation`，邀请 token 一次有效并支持过期、撤销和接受；新增 `/ledger/member/change_role.json`、`remove.json`、`leave.json` 与 `/ledger/invitation/create.json`、`list.json`、`revoke.json`、`accept.json`。所有者/管理员可邀请普通或只读成员、调整非所有者角色和移除成员；所有者角色不可修改且所有者不能退出；退出或移除后直接失去账本访问。旧显式个人账本与家庭账本第一次发生成员写操作时会幂等补齐直接成员关系。个人账本真实流程已覆盖：邀请协作者→接受→可写访问→降为只读后写访问被拒→主动退出→所有者退出被拒。Web 已新增 `/ledger/manage`，旧 `/family/manage` 重定向至该页；支持直接创建单人账本、家庭模板预填、账本切换、成员/权限/邀请管理。Android 设置入口及管理页也已改为统一账本流程，直接创建个人账本并在详情管理成员。成员响应新增 `isCurrentUser` 供客户端可靠判断管理权限。验证：`go test ./...`、`npm run check`（38,477 项）以及 Android `compileDebugKotlin`/`lintDebug` 通过；CGO SQLite `TestFamilyLedgerAndGoalServiceFlow` 在无卷容器通过。8080 已用分层镜像覆盖后端与 `dist/` 并仅重建 bookkeeping 容器，容器 healthy，成员路由到达认证层，数据卷未触碰。仍待旧数据批量迁移命令、隔离 HTTP E2E 与 Android 专项 UI 自动化。

13. **账本页面重新设计（2026-09-16，设计交付）**：`docs/UI_UX_DESIGN.md` 第 7 节“账本中心流程”已扩充为完整目标方案：账本列表与概览/成员/设置、两步模板创建、浏览详情和全局切换分离、邀请预览及完整角色表单、当前账本首页与条件存钱卡、仅所有者删除空账本及关联预检查。目标矩阵要求仅所有者任免管理员；当前 `CanManage` 删除及角色接口未达到该目标，必须先补服务端校验再开放对应 UI。规范末尾记录实现差距及验收场景。本次仅更新设计文档，未将新设计标记为已实现、未部署。
14. **账本页面第一轮实现（2026-09-16）**：服务端修复直接账本成员被降权/移除后又回退旧 FamilyMember 权限的问题，账本列表同样过滤已明确退出或移除的直接成员；只有所有者可任免/移除管理员，管理员仍可管理普通与只读成员。删除收紧为仅所有者删除空的显式账本，默认账本禁止删除，任一账户、流水或存钱目标（含软删除历史行）仍关联时返回冲突；成功删除同时撤销待接受邀请。Web 账本页已将“查看详情”和全局切换拆开，成员/邀请快速切换丢弃过期响应，普通/只读成员不再请求管理者专用邀请列表；浏览器 prompt/confirm 已替换为应用内邀请、加入、角色、移除和输入账本名删除 Dialog，创建账本改为个人/家庭共享模板两步流程。首页新增当前账本状态条，显示账本名称、本人角色和有效成员数，并提供账本管理入口；共享账本仍可读取余额、账户、流水和目标，但写入链路尚未改为成员身份与账本所有者分片的安全模型，因此首页将旧“家庭账本只读”改为准确的“共享写入能力正在接入”提示，未冒险开放写入。Android 已补完整角色选择、成员移除确认、邀请撤销、只读成员加载和输入名称删除对话框，并统一账本文案。验证：`go test ./...`、Web lint/typecheck、全量 38,480 项测试与生产构建、Android `compileDebugKotlin`/`lintDebug` 通过；首页增量后 Web lint/typecheck、账本 store 4 项测试及生产构建再次通过。真实 SQLite CGO 在本机既有含 gcc 的无卷构建缓存镜像上通过 `TestLedger*` 与 `TestFamilyLedgerAndGoalServiceFlow`，临时容器均已自动删除。已将部署前镜像标记为 `ai-bookkeeping-bookkeeping:rollback-pre-ledger-page-20260916`；首页增量部署另保留 `ai-bookkeeping-bookkeeping:rollback-pre-ledger-home-20260916`。两次均以分层方式覆盖产物并只重建 8080 bookkeeping 容器；容器 healthy、成员路由到达认证层，数据卷未触碰，OCR 未重建。尚未完成共享账本安全写入链路、删除影响预览接口、账本详情数据概览及隔离 HTTP 双用户 E2E。

15. **邀请码加入前预览（2026-09-17）**：新增认证接口 `POST /ledger/invitation/preview.json`，使用与接受邀请相同的 token 请求但不消费邀请码；响应只包含账本基本信息、将获得的普通/只读角色、邀请备注、邀请者昵称和过期时间。服务端统一复用跨分片 token 定位，预览时校验待接受状态、有效期和账本未删除；真实 SQLite 专项证明预览后状态仍为 pending、随后仍可接受，已接受或过期 token 均被拒绝。Web 与 Android 的“用邀请码加入”均改为输入邀请码 → 查看账本/权限/邀请人/有效期 → 明确确认加入，Web 严格解析响应，Android 增加对应远程模型和解析合同测试。验证：`go test ./...`、Web lint/typecheck、账本 store `5/5`、生产构建、Android debug/test APK 构建及 lint 均通过；Android instrumentation 因 `emulator-5554` 未运行而未执行，未启动或改动 AVD。CGO SQLite `TestLedgerInvitationPreviewDoesNotConsumeToken|TestLedger*` 通过。8080 已以 `ai-bookkeeping-bookkeeping:rollback-pre-ledger-invite-preview-20260917` 为回滚点分层部署后端与 `dist/`，bookkeeping/ocr 均 healthy，新路由到达认证层，静态产物哈希一致，数据卷未触碰。尚未完成共享账本安全写入链路、账本详情数据概览及隔离 HTTP 双用户 E2E。

16. **账本删除影响预览（2026-09-17）**：新增仅所有者可访问的 `GET /ledger/delete/preview.json?id=`，默认账本拒绝。响应严格返回账本名、有效成员、待接受邀请、账户、流水、存钱目标、目标资金记录数量、阻断项和 `canDelete`；账户/流水/目标的统计口径与删除事务一致，包含仍关联账本的软删除历史，预览后实际删除仍在事务内再次检查。Web 删除 Dialog 与 Android 删除对话框均先加载影响数据：有财务关联时显示“暂时无法删除”和逐项数量且不提供确认输入；真正空账本才显示成员失效说明并要求输入完整账本名。预算与周期计划目前仍是账号级合同、没有 `ledgerId`，本轮未虚构关联数量，待合同账本化后再纳入预览。验证：`go test ./...`、Web lint/typecheck、账本 store `6/6`、生产构建、Android debug/test APK、编译及 lint 均通过；CGO SQLite `TestLedgerDeleteRequiresOwnerAndEmptyLedger` 通过，覆盖非所有者/默认账本拒绝、四类关联计数、阻断删除、物理清除测试夹具后允许删除及邀请撤销。模拟器仍未运行，未执行 instrumentation、未启动或改动 AVD。8080 已以 `ai-bookkeeping-bookkeeping:rollback-pre-ledger-delete-preview-20260917` 为回滚点分层部署后端和 `dist/`，bookkeeping/ocr healthy，路由到达认证层、静态产物哈希一致，数据卷未触碰。剩余重点是共享账本安全写入、账本详情数据概览和隔离 HTTP 双用户 E2E。

17. **账本详情数据概览（2026-09-20）**：新增只读认证接口 `GET /ledger/overview.json?ledgerId=`，不会改变调用者当前账本。默认账本按当前用户分片读取；显式账本先校验直接成员或旧家庭兼容成员访问，再从账本所有者分片聚合。返回账本名、有效成员数、可用末级账户数、去重后的业务流水数、存钱目标数及按币种排序汇总的可用账户余额；隐藏账户和多子账户父节点不重复计入，转账 OUT/IN 对只计一次，期初余额保留为余额调整流水。Web 账本页新增四项概览和多币种余额，不切换全局账本即可浏览；Android 账本管理页提供同样卡片，并补齐显式账本返回默认账本的入口。严格解析拒绝负计数、非法/重复币种和非法 ID。验证：`go test ./...`、Web 账本 store `7/7`、lint/typecheck、生产构建、Android `assembleDebug`、`assembleDebugAndroidTest` 与 `lintDebug` 通过；CGO SQLite `TestLedgerOverviewAggregatesVisibleDataAndEnforcesMembership` 通过。无卷隔离容器 `finexy-ledger-overview-e2e` 使用三个随机账号完成 14 项 HTTP 检查：所有者建账本、只读成员接受邀请、账户期初余额、存钱目标、成员读取概览及陌生人拒绝，证据 `artifacts/family-accept/ledger-overview-http-e2e-final.txt`，容器已停止并自动删除。Android instrumentation 因 `emulator-5554` 未运行而未执行，未启动或改动 AVD。8080 已以 `ai-bookkeeping-bookkeeping:rollback-pre-ledger-overview-20260920` 为回滚点部署候选镜像，bookkeeping/ocr 均 healthy，后端与 `desktop.html` 哈希和构建产物一致，概览路由到达认证层，三个宿主机数据绑定保持不变。共享账本安全写入链路仍是下一重点。

18. **共享账本普通流水写入第一轮（2026-09-20，Web 与服务端）**：流水新增、读取、修改和删除合同增加可选字符串 `ledgerId`。显式账本写入先按直接成员权限执行 `CanWrite` 校验，只读成员在服务端拒绝；实际流水、账户余额、分类和关联行统一使用账本所有者数据分片，流水保存 `LedgerId`、实际 `RecorderUid` 与 `PayerUid`，并拒绝引用其他账本账户。普通成员目前只可修改/删除自己记录的流水，所有者可处理本账本全部流水；共享成员暂不允许附加账本所有者私有命名空间中的标签或图片。分类列表支持 `ledgerId`，共享成员读取账本所有者分类；显式账本流水响应恢复分类信息与按上述范围计算的 `editable`。Web 桌面记账对话框携带账本上下文加载账户/分类并调用新增、读取、修改、删除接口；普通成员开放记账入口，只读成员显示准确拒绝文案；共享记账暂禁标签与图片入口。验证：`go test ./pkg/models ./pkg/services ./pkg/api ./cmd` 通过；`npm run check` 通过（11 个测试文件、38484 项及生产构建）；无卷 `golang:1.27.0-alpine3.24` 容器 CGO SQLite `TestLedgerMemberCanPostIntoOwnerShardAndViewerCannotWrite` 通过，验证成员流水落入所有者分片、记账人/付款人保留、余额 1000→750、共享分类可见、只读成员和陌生人拒绝。首次容器命令误用登录 shell导致 `go` 不在 PATH，未运行测试；修正后容器均 `--rm` 自动删除。尚未完成 Android 写入入口、隔离 HTTP 双用户 E2E、共享标签/图片命名空间及 8080 部署；本轮未操作模拟器，主账本状态未变。

19. **账本入口与邀请体验收口（2026-09-20）**：Web 总览页与工作台左侧工具栏都补齐“存钱计划”和“账本”图标入口；原“家庭共享”页面、页头指标与主操作统一为“账本”，只保留页头一个“创建账本”入口，家庭共享作为两步创建流程中的预填模板。旧创建家庭/旧家庭邀请/旧接受邀请弹窗已从 Web 页面移除。账本页明确展示受邀方操作说明；加入弹窗采用“输入邀请码→预览账本、邀请人和权限→确认加入”三步流程；生成邀请码结果改成与现有账本弹窗一致的结果卡、一次有效说明和整行复制按钮。Android 第一轮共享普通流水新增已接入：可写成员按选中账本加载账户及所有者分类并携带 `ledgerId` 在线新增，成功后同步刷新；只读成员被拦截，既有共享流水编辑仍保持禁用，文案统一为“账本”。验证：`npm run check` 通过（11 个测试文件、38484 项、生产构建），Android `compileDebugKotlin` 与 `lintDebug` 通过。浏览器在无卷隔离容器 `finexy-ledger-ui-e2e` 上使用两个随机账号完成真实流程：所有者从家庭模板创建账本并生成邀请码，受邀账号看到正确账本/邀请人/备注/普通成员权限及有效期，确认后成员数 1→2 且顶部切换器出现新账本；邀请码视觉证据为 `artifacts/android-ui/web-ledger-invite-dialog-final.png`。本次未部署 8080；共享标签/图片、Android 既有共享流水编辑/删除、HTTP 自动化脚本化与 Web 移动端入口仍待完成。

20. **本地 Docker 更新（2026-09-20）**：完成第 18/19 项门禁后更新 8080 运行服务。部署前镜像保留为 `ai-bookkeeping-bookkeeping:rollback-pre-ledger-ui-20260920`（`67ce0b4a…`）。标准 `docker-compose build bookkeeping` 因 Alpine 官方源 TLS 失败而未生成候选镜像；随后在 `golang:1.27.0-alpine3.24` 一次性容器中切换中科大 Alpine 源、以 `CGO_ENABLED=1` 编译 Linux 后端，并用已通过 `npm run check` 的 `dist/` 做分层覆盖，生成 `ai-bookkeeping-bookkeeping:latest`（`528cf8e9…`）。仅执行 `docker-compose up -d --no-deps --force-recreate bookkeeping`；新 bookkeeping 容器 healthy，OCR 容器 ID 未变化，`data/`、`log/`、`storage/` 三个宿主机绑定路径未变化。`/healthz.json` 返回 1.8.0/status ok；账本概览、成员列表和带 ledgerId 的流水接口均返回 202012 token empty，证明新路由到达认证层；运行镜像前端包含“用邀请码加入账本”和“存钱计划”新版文案。未运行破坏性用户数据测试。

21. **全局账本切换器样式统一（2026-09-20）**：Web 总览与工作台顶栏移除会展开 Windows 原生灰色菜单的 `<select>`，共用 `GlobalLedgerSwitcher.vue` 应用内菜单；触发器与账本页的圆角、图标和层级一致，菜单展示账本名称、可见性/描述、当前项高亮及选中标记，并保留键盘焦点、listbox/option 和 aria-selected 语义。`npm run check` 通过（11 个测试文件、38484 项与生产构建）。无卷隔离容器 `finexy-ledger-switch-e2e` 中用随机账号创建第二个账本后，浏览器验证账本页与总览均显示两个选项、切换状态一致且控制台 0 error；证据 `artifacts/web-ui/ledger-switcher-final.png`，测试容器和 Vite/Playwright 会话均已清理。8080 已以 `ai-bookkeeping-bookkeeping:rollback-pre-ledger-switch-style-20260920` 保留回滚点，前端分层镜像更新为 `3cb04f02…` 并仅重建 bookkeeping；容器 healthy、healthz HTTP 200、OCR 容器未变化，三个宿主机数据绑定路径未变化。未操作 Android 模拟器，主账本 4 条状态未重测也未改动；最大字体、窄屏及完整读屏人工验收仍归阶段 E。

22. **README 与 Web 首页内容更新（2026-09-21）**：根 README 改为以账本为中心的产品说明，补齐多账本切换、成员邀请与角色、存钱目标、周期待确认、AI/OCR 可选能力、平台矩阵和四步使用路径；下载、Docker、源码构建与协作入口继续保留。Web 首页开场改为按时间问候当前用户，并明确所有信息属于当前账本；四项指标统一为本月结余、本月支出、本月收入和账户余额，图表文案改为近八个月收入/支出，待办说明明确周期交易到期后需要确认且不会自动改变余额。月度指标同时修复为从完整月度返回集汇总，近期活动仍只展示前五条，不再把五条活动误当整月口径。`npm run check` 通过（11 个测试文件、38484 项与生产构建）。浏览器在无卷隔离容器 `finexy-readme-home-e2e` 的随机账号中实测 1440×1000 无横向溢出，并替换 `docs/screenshots/finexy-dashboard.png`；空白隔离服务因未配置汇率产生既有汇率初始化错误，不影响首页数据请求、内容或截图。临时容器、Vite 和 Playwright 会话均已清理；为验收启动的 Docker Desktop 已恢复关闭。本次未部署 8080，未操作 Android 模拟器，主账本 4 条状态未重测也未改动。

23. **Android 共享流水编辑/删除与多用户权限 E2E（2026-09-26）**：Android 解析并缓存共享流水的 `recorderUid`、`payerUid` 与服务端 `editable`，Room v19→v20 非破坏迁移为 `ledger_transaction_cache` 增加对应列；共享流水页按每条记录权限显示编辑/删除入口，只读成员无操作入口，编辑保留原时间与时区并携带 `ledgerId` 在线提交，删除在线完成后同步刷新，失败提供可见反馈。共享记账同时改为使用账本所有者的真实末级分类。隔离 E2E 暴露并修复服务端单笔查询固定 `ledger_id=0` 的缺陷，读取、幂等新增回读、修改与删除现按请求账本定位所有者数据分片。无卷容器 `finexy-ledger-permission-e2e` 使用所有者、普通成员、只读成员和陌生人验证：成员只能改删自己的流水，不能处理所有者流水；所有者可处理全部流水；只读成员不能增改删；陌生人不能新增；SyncEngine 缓存的逐条 `editable` 与身份一致。门禁：`go test ./...` 全部通过；Android debug/test APK 与 lint 通过；合同与迁移专项 `OK (14 tests)`、权限 UI `OK (3 tests)`、隔离 Docker E2E `OK (1 test)`，证据分别为 `artifacts/family-accept/android-ledger-permission-contract-final.txt`、`android-ledger-permission-ui-final.txt`、`docker-ledger-permission-e2e-final.txt`。测试 APK 均用 `adb -s emulator-5554 install -r` 安装，未卸载或清除数据；冷启动无 FATAL EXCEPTION/Room migration 错误，当前主账本显示 0 笔，仍是 2026-09-07 事故后未恢复状态。临时容器与测试镜像已删除；本轮未部署运行服务，深浅主题、最大字体与 TalkBack 人工验收仍归阶段 E。

24. **旧家庭数据迁移清理与移动端补齐（2026-09-26）**：服务启动会幂等地把旧 `FamilyGroup/FamilyMember/FamilyInvitation` 转成直接的 `Ledger/LedgerMember/LedgerInvitation`；一个家庭的成员覆盖其全部旧账本，无账本家庭会保留为一个新账本，旧家庭级待接受邀请在接受时一次性加入该家庭迁出的全部账本，直接账本侧已有的移除/改权结果不会被重启迁移覆盖。`/family/*` 路由已退役为 404，账本访问、列表与成员权限只认直接成员关系；旧表仅保留为启动迁移输入，避免升级时丢数据。Android Room v20→v21 删除只读 `family_groups/family_members` 缓存，备份 schema 15 不再导出旧缓存但仍能读取 schema 14，并在“清空当前账本”时同步清理显式账本账户/流水缓存和同步状态；旧家庭页面及网络调用已移除。移动 Web 新增账本管理与存钱计划页面，并从首页和设置页进入，覆盖建账、邀请码预览/接受、成员邀请、目标创建与存取。门禁：`go test ./...`、`npm run check`（11 个测试文件、38484 项与生产构建）、Android debug/test APK、lint 全部通过；Room/备份专项 `OK (26 tests)`，普通 Android 全量回归 `OK (152 tests)`，服务端真实 SQLite 迁移专项通过；无卷隔离容器联合 E2E `OK (1 test)`，同时确认旧家庭路由为 404。证据为 `artifacts/family-accept/android-ledger-migration-final.txt`、`android-full-regression-final.txt`、`cgo-ledger-migration-final.txt`、`docker-direct-ledger-goal-e2e-final.txt` 与 `legacy-family-route-retired-final.txt`。APK 仅使用 `adb -s emulator-5554 install -r`，未卸载或清空主应用；冷启动没有 FATAL EXCEPTION 或 Room migration 错误，主账本仍显示 0 笔的既有未恢复状态。临时容器和测试镜像均已删除，本轮未部署 8080。

25. **Android App 更新提醒（2026-09-26）**：Android 自身版本升至 `1.0.1`，`versionCode=10902`（覆盖已安装的此前验收包 10901，后续 Android 发布须继续递增）。启动后至多每 24 小时检查一次项目 GitHub Releases，只识别名称包含 Android 自身版本号的正式 APK；产品主版本更新但 APK 不变、草稿、预发布和无 APK 发布均不提醒。断网时保留已缓存版本，不阻断启动；总览顶部显示新版本卡片，支持查看官方发布页、忽略当前 APK 版本，新 Android 版本仍可再次提醒。Android debug/test APK 构建、lint 通过，解析与界面专项 10 项通过，另 1 项视觉停留夹具在普通专项按参数跳过；该夹具深浅主题各单独 `OK (1 test)` 并检查截图，证据 `artifacts/android-ui/app-update-reminder-final.txt`、`app-update-visual-light-final.txt`、`app-update-visual-dark-final.txt` 及同名 PNG。APK 均用 `adb -s emulator-5554 install -r` 覆盖安装，无 Docker 容器或 8080 部署；冷启动没有 FATAL EXCEPTION/Room migration 错误，主账本仍显示 0 笔（2026-09-07 事故后未恢复），目前发布的 Android APK 仍为 `1.0.0`，所以总览没有误报更新。本轮未跑普通全量回归、需要参数的 Docker E2E 或进程恢复专项；最大字体与 TalkBack 人工验收仍归阶段 E。

26. **Web 更新提醒（2026-09-26）**：桌面总览、工作台和移动 Web 总览顶部接入正式 Release 更新卡片，优先与当前自托管服务版本比较，服务版本不可用时明确标为页面版本；仅更高的正式语义化版本提示，支持查看官方发布页、忽略当前版本。自动检查使用 24 小时缓存并静默处理网络失败；桌面和移动关于页的“检查更新”可主动刷新并显示结果或失败反馈。版本解析、顺序、忽略、服务版本回退与网络失败专项 `5 passed`；`npm run check` 通过（12 个测试文件、38489 项、类型检查、ESLint 和生产构建）。本轮未部署 8080、未启动 Docker E2E、未操作 Android 模拟器；主账本仍为此前未恢复的 0 笔状态，未在本轮重测。深浅主题/最大字体/读屏浏览器人工验收尚待进行。

27. **支付宝/微信支付账单导入（2026-09-30）**：桌面 Web 流水页新增支付宝与微信专用入口，复用现有解析/校对/确认导入流程；支付宝 App 加密 ZIP 在浏览器内凭用户输入密码解出唯一 CSV，限制 20 MB，密码不发送服务器。Android 流水页新增个人账本在线导入入口，支持支付宝 ZIP/CSV 与微信 XLSX/CSV；ZIP 在设备内解密，服务端解析预览后对未匹配账户/末级分类要求显式映射，支持按原始名称成组映射、逐笔修改与取消勾选，再以稳定 clientSessionId 批量提交；共享账本与本地模式暂不显示入口，避免写入错误账本。用户样本仅在内存中只读核对格式，支付宝与微信可由既有解析器识别，未导入真实用户账本。Web `npm run check` 通过（13 文件、38491 项及生产构建）；Android debug/test APK、lint、合同与入口专项 `OK (3 tests)`；无卷容器 `finexy-import-e2e` 的随机账号完成支付宝/微信合成账单解析、显式映射和两笔入账 `OK (1 test)`，容器已停止删除。APK 只用 `adb -s emulator-5554 install -r` 覆盖；冷启动主账本仍是此前未恢复的 0 笔，logcat 无 FATAL EXCEPTION/Room migration 错误。未部署运行服务、未人工完成深浅主题/最大字体/TalkBack 全面验收。移动 Web 独立入口与预览随后补齐，见下一条。

28. **移动 Web 支付账单导入补齐（2026-09-30）**：移动 Web 流水页新增“导入”入口，支付宝 ZIP/CSV、微信 XLSX/CSV 可经个人账本解析预览、成组映射账户与末级分类、逐笔勾选及更改、二次确认后批量提交；ZIP 密码只在浏览器内使用，关闭弹窗清除密码。仅在服务端启用导入功能时显示入口，使用现有个人账本批量导入 API；提交成功使账户、流水、总览与统计缓存失效并刷新流水。移动 Web 尚无共享账本流水路由，因此该入口目前只访问个人账本。`npm run check` 通过（13 文件、38491 项、类型检查、ESLint、生产与 PWA 构建）。深浅主题、最大字体和读屏人工验收仍待完成。

29. **支付账单导入 Web 部署（2026-09-30）**：按用户要求将上述 `dist/` 前端部署至本机运行服务（当前宿主端口 18080）。部署前镜像 `07f96ae543d7` 保留为 `ai-bookkeeping-bookkeeping:rollback-pre-web-import-20260930`；以此为基础仅覆盖 `/ezbookkeeping/public/` 的前端候选镜像 `ai-bookkeeping-bookkeeping:web-import-20260930`（`0d483821797d`），并标为 latest 后仅重建 bookkeeping 容器。候选与旧镜像的后端二进制 SHA-256 均为 `cbfcddc4087778d7efd7b36c555d14cc0d25c17870268eeea45faf95fa560d46`。部署后 bookkeeping/ocr 均 healthy，`/healthz.json` HTTP 200（1.9.1），`/desktop` 与 `/mobile.html` 的响应 SHA-256 分别和本轮 `dist/desktop.html`、`dist/mobile.html` 完全一致，`/sw.js` HTTP 200，导入服务开关 `i=1`；OCR 容器 ID 和 `data/`、`log/`、`storage/` 三个绑定路径保持原值。回滚可将 `rollback-pre-web-import-20260930` 标签重新标为 latest，然后仅重建 bookkeeping 容器。未向真实用户账本导入样本数据。

30. **导入入口与生产启动修正（2026-09-30）**：第 29 项部署后浏览器实际启动出现 `C is not a function` 空白页，立即回滚并验证旧版登录页恢复。原因是未分组的 `ReleaseUpdateNotice.vue` 被构建器合入 preload-helper，形成 vendor/common/helper 初始化循环；`vite.config.ts` 将该共享组件显式划入 common 后修复。桌面 `/transaction/list` 实际使用 `FinexyWorkspacePage.vue`，此前旧 `transactions/ListPage.vue` 的按钮不在当前路由上；现已在新版流水页接入支付宝/微信入口和 ImportDialog，只对默认个人账本（DefaultLedgerId）开放。`npm run check` 通过（13 文件、38491 项与生产/PWA 构建）。无卷 `finexy-web-import-preview` 的独立账号经真实浏览器完成桌面支付宝 AES ZIP 错误密码提示/正确密码解析/账户分类选择/确认一笔入账，以及移动 Web 微信 XLSX 解析/映射/确认一笔入账；桌面微信入口也验证 XLSX 预览。服务端精确核对两笔金额 123、234，账户余额 -357，证据 `artifacts/web-import-browser-e2e-final.json` 与 `web-import-check-final.txt`。通过后部署修复镜像 `ai-bookkeeping-bookkeeping:web-import-fixed-20260930`（`8139df4f4d09`）到本地 18080；浏览器全新上下文验证 `http://localhost:18080/desktop` 登录页正常挂载、控制台无 error，健康 HTTP 200。第 29 项仅校验哈希/HTTP 并不足以证明前端可运行，今后 Web 部署必须验证生产构建的浏览器冷启动与本轮实际入口。NAS 更新镜像包已生成但连接目标尚待用户提供，不能声称 NAS 部署完成。未向真实账本写入测试数据。

31. **NAS 账本迁移与导入版部署（2026-10-01）**：用户完成 fnOS OAuth 授权后，在 192.168.31.184 查到旧 `finexy-bookkeeping` 容器已退出（255），且没有持久化挂载；用户明确确认其中账本必须保留。旧容器保持原状，读取其停止状态下的可写层，将 data/storage 复制至 `/vol2/1000/Docker/finexy-import-20261001/backup/`，再复制出独立运行副本。只读 Python 临时容器验证备份与运行副本数据库字节一致、SQLite integrity_check=ok；新版迁移后再次验证两个数据库完整性及 user/account/transaction 三表全部原字段、全部行完全一致。导入本地已通过浏览器 E2E 的镜像包（SHA256 `03AE3DC3BFAFE30E41A7FD63D2734D4699D37686D50B663EC52D0AFE772760CE`），NAS 镜像层与本地逐层一致（不同 Docker 存储实现显示的镜像 ID 不同）。新容器 `finexy-bookkeeping-import` 使用 data/storage/log 三个独立 bind mount、自动重启与原 8080 端口；secret 在进程内随机生成，仅存 NAS 容器配置。健康接口 HTTP 200，版本 1.9.1；全新浏览器上下文验证 NAS `/desktop`、`/mobile` 登录页均挂载正常、控制台无 error。未登录或向真实 NAS 账本写入测试流水，登录后的导入功能依据部署前隔离浏览器 E2E 验证。旧容器及独立备份保留用于回退；数据迁移和附件验证临时容器验收后删除。访问地址 `http://192.168.31.184:8080/desktop`。第 30 项 NAS 待连接描述以本项为准。

32. **忘记密码字体修复与部署（2026-10-01）**：白色表单在深色主题下继承浅色文字，导致标题、说明和邮箱字段难以辨认。页面局部固定浅色主题，使用设计基线的中文正文字体及语义色，保持 Vuetify 浮动标签行为。lint、类型检查、生产/PWA 构建通过；浏览器分别在隔离候选镜像、本地 18080 与 NAS 8080 验证浅/深系统主题、375/768/1024/1440px、100%/125% 缩放各 16 个场景，文字颜色、字体、无横向溢出与无页面异常断言通过。镜像 `ai-bookkeeping-bookkeeping:forgot-password-font-20261001` 仅覆盖前端；本地保留 `rollback-pre-forgot-password-font-20261001`，NAS 新容器 `finexy-bookkeeping-import-fontfix` 沿用原环境变量及 data/storage/log 挂载，原导入容器停止保留。两处健康接口均 HTTP 200；无卷临时容器 `finexy-forgot-font-preview` 已停止并自动删除。未发送重置邮件、未写入真实账本，本轮未运行 Android 测试。1.9.2 GitHub 发布仍等待独立审核或用户明确授权管理员合并。

33. **Web 记住登录信息（2026-10-01）**：桌面与移动 Web 登录页新增默认关闭的“记住账号和密码”，成功登录后保存到当前浏览器并自动填入，取消勾选立即清除；账号切换页初始化不回填旧账号。复用 CryptoJS 加密本地保存内容，密钥同样属于浏览器资料，仅提供设备便利，不宣称抵御浏览器资料访问者。未完成两步验证不保存。移动复选框恢复原生键盘焦点与读屏语义，两端点击区域至少 48px。全量 lint/类型检查、保存逻辑专项 3 项、生产/PWA 构建通过；无卷容器 finexy-remember-login-e2e 随机账号完成 11 项浏览器流程断言（两步验证挑战为响应合同模拟，不计完整 2FA E2E），深浅系统主题、四种宽度、100%/125% 缩放共 32 场景含键盘空格切换通过。证据 artifacts/remember-login-browser-e2e-final.json 与 remember-login-layout-final.json。部署镜像 ai-bookkeeping-bookkeeping:remember-login-20261001 至本地 18080，保留 rollback-pre-remember-login-20261001；桌面/移动冷启动无浏览器异常。未写入真实账本，临时容器已停止自动删除，未运行 Android 测试。NAS 已导入镜像，但自动审批拒绝容器替换操作且未给出具体原因，仍运行 finexy-bookkeeping-import-fontfix，用户明确授权后，fnOS CLI 替换命令仍被工具策略拒绝且未运行；用户随后明确指定 SSH 22 登录。经 SSH/sudo 使用 Docker API 创建 finexy-bookkeeping-remember-login，完整沿用原 Config 环境及 HostConfig，核验候选镜像基础层与最终层、三个数据挂载和环境变量一致后切换服务。NAS 健康 HTTP 200，桌面/移动浏览器冷启动无异常，新复选框默认关闭且键盘切换通过。原 finexy-bookkeeping-import-fontfix 停止保留，独立备份保持原状。SSH 会话已退出并清除本次 sudo 缓存；登录凭据未写入仓库或输出。此前 NAS 未部署状态以此为准，本地与 NAS 均已部署本功能。

34. **登录勾选框尺寸修正（2026-10-01）**：根据用户实际 Edge 截图，将桌面“记住账号和密码”改为原生 18px 复选框，文字间距 8px、整行点击区至少 44px，去掉主题背景色块。未改变保存逻辑；桌面点击区以本项 44px 为准，第 33 项 48px 为此前实现。修改文件 lint、生产/PWA 构建通过；无卷 finexy-remember-checkbox-e2e 的 11 项登录流程与两端主题/四宽度/两缩放含键盘的 32 场景通过，并断言桌面可见输入框 18px。镜像 ai-bookkeeping-bookkeeping:remember-checkbox-20261001 部署至本地 18080，保留 rollback-pre-remember-checkbox-20261001；经用户已授权的 SSH 更新 NAS 容器 finexy-bookkeeping-remember-checkbox，完整沿用原环境、HostConfig 与 data/storage/log 挂载，旧 remember-login 容器停止保留。两处健康 HTTP 200，真实匿名浏览器断言框为 18px、背景透明、无页面异常且勾选操作正常。临时测试容器已停止自动删除，SSH 已退出并清除 sudo 缓存；未向真实账本写入测试数据，未运行 Android 测试。

35. **桌面账单导入视觉统一（2026-10-01）**：修复导入弹窗继承深色主题且未接统一样式的断层。主弹窗及三个批量创建/替换子弹窗接入 light + finexy-dialog；上传/完成最大 780px，核对/自定义列最大 1200px；内容独立滚动、底部操作保持可达，步骤采用语义珊瑚/中性分隔，恢复文件选择占位提示，导入按钮使用与工作台一致的墨黑主操作。相关 Vue lint、生产/PWA 构建通过。无卷 finexy-import-style-e2e 随机账号验证主弹窗浅/深系统主题、375/768/1024/1440px、100%/125% 缩放 16 场景（窄尺寸通过打开后调整视口验证弹窗，不宣称桌面工作台本身适配手机）；真实微信 XLSX 解析、显式分类/账户选择、确认导入、完成页与余额 -234 分联合验收通过。证据 artifacts/import-style-browser-final.json 及 upload/preview/complete 截图；批量子弹窗本轮未逐一执行新增或替换功能 E2E。镜像 ai-bookkeeping-bookkeeping:import-style-20261001 部署本地 18080，保留 rollback-pre-import-style-20261001；经已授权 SSH 切换 NAS 至 finexy-bookkeeping-import-style，环境/HostConfig/三个持久化挂载完全保留，旧容器停止保留。两处健康 HTTP 200，部署 CSS SHA256 与验收产物一致；NAS desktop/mobile 匿名浏览器启动无异常。未登录或向真实 NAS 账本导入测试数据。隔离测试容器已停止自动删除，SSH 已退出并清除 sudo 缓存，未运行 Android 测试。

36. **前后端版本一致性修正（2026-10-01）**：用户发现关于页显示页面 v1.9.2，但更新提醒显示服务 v1.9.1。实际 `/healthz.json` 返回 1.9.1/02b36b1，原因是此前多个 UI 分层镜像只覆盖前端，后端仍来自 1.9.1；更新比较逻辑正确，不能用页面版本掩盖旧服务。候选镜像 `ai-bookkeeping-bookkeeping:version-sync-1.9.2-20261001` 保留已验收的导入/登录前端，覆盖已发布 `ph97/finexy-bookkeeping:1.9.2-amd64` 中的 1.9.2/374568b 后端。部署脚本 `.github/scripts/verify-release-web.mjs` 增加健康响应版本及 desktop/mobile 页面版本一致性断言；部署前在旧服务上实测拒绝 1.9.1。更新提醒专项 5 项通过；无卷隔离 `finexy-version-sync-e2e` 随机账号登录并手动检查更新，模拟官方同版本 Release 后提示已是 1.9.2 且无误报卡片；两端冷启动无浏览器错误。通过后部署本地 18080 与 NAS 8080，两处 healthz 均为 1.9.2/374568b，两端匿名浏览器版本一致校验通过。本地保留 `rollback-pre-version-sync-20261001`，OCR 与三项数据挂载未变；NAS 经此前授权的 SSH 新建 `finexy-bookkeeping-version-1-9-2`，保持环境、端口、重启策略及挂载，停止旧容器后独立备份 data，升级后真实 SQLite integrity_check=ok，user/account/transaction 三表全部原行哈希一致，旧 import-style 容器保留。用户 Edge 关于页刷新后旧 1.9.1 提示与误报更新卡片均消失。临时测试容器已停止自动删除，NAS 上传镜像临时文件已清理，SSH sudo 缓存已清除。未导入真实账单、未修改账户余额、未运行 Android 验收。

37. **Agent 接入与受控账单导入（2026-10-01，候选实现）**：桌面账户安全设置支持 `?tab=securitySetting` 并新增共用 `AgentAccessPanel`；移动 Web 设置增加 Agent 接入二级页及登录门禁。专用 API/MCP 令牌名称和权限保存于既有 TokenRecord.Context，正常登录会话不变；旧令牌默认只读，损坏授权拒绝权限。API 路径显式白名单和 MCP 工具派发分别检查 read、transactions:write、accounts:manage、bills:import；账户/分类管理当前通过 API，MCP 面板不允许勾选该项，禁止 API 令牌生成其他令牌、清空数据或绕过预览调用旧直接导入。新增 AgentImportBatch/Fingerprint 两表并纳入数据库结构同步；支付宝 CSV、微信 CSV/XLSX 共享 preview/map/confirm 流程，只支持默认个人账本、显式字符串 ID 映射、一小时预览和相同令牌确认。最新预览 hash、重复/疑似重复、期初日期及账户/分类有效性均核对；批次领取、去重、流水、余额同事务，失败回滚，成功删除预览快照并保留幂等回执。同用户确认使用有界条带锁避免真实 SQLite HTTP 并发锁竞争，数据库条件更新和唯一键继续作为最终门禁。Web `npm run check` 通过（38,494 项、类型、lint、生产/PWA 构建），后续类型/lint 复核通过；无卷构建容器中 CGO=1 `go test ./...` 通过，包含过期/跨用户/跨令牌/旧 hash/未确认拒绝、8 路并发一次入账、隐藏账户失败及插入触发器失败的完整事务回滚。`.github/scripts/verify-agent-access.mjs` 在无卷 `finexy-agent-e2e:18110` 随机账号完成 12 组真实 HTTP/MCP/浏览器验收，包括三种合成账单、重启幂等、到期/撤销、密码错误保留、Enter 生成、Esc 取消撤销、18px 勾选框、深浅系统主题、125% 缩放和移动 375/768/1024 布局；证据在忽略的 `artifacts/agent-access/`。用户说明见 `docs/AGENT_ACCESS.md`，更新日志归属“未发布”。本轮没有读写真实财务数据，没有运行 Android/ADB 验收；候选镜像尚未部署到本地用户服务或 NAS，尚未推送 GitHub/Docker 发布源，原真实数据与已有工作树成果保留。

   最终镜像核验：`ai-bookkeeping-bookkeeping:agent-access-candidate`（c742e22c159b）按 `main.Version` / `main.CommitHash` 注入版本并静态链接；最终重新运行上述 12 组验收及 `.github/scripts/verify-release-web.mjs`，desktop/mobile 与后端版本均为 1.9.2，浏览器无错误。测试容器 `finexy-agent-e2e` 已停止自动删除，构建容器 `finexy-agent-build` 已停止删除；原本地账本和 OCR 容器继续健康运行。

### 阶段 E

完成 TalkBack、最大字体、多尺寸/横屏、主题语义色、导航一致性；将 lint、Room migration、关键 instrumentation 和发布签名验证纳入 CI；生成并验证 release APK/AAB。生物识别成功路径需在已录入指纹的设备上人工验收。

## 9. 完成定义与交接格式

每次交接必须写清：改了什么、未改什么、运行了哪些测试及准确结果、哪些测试因参数跳过、使用和清理了哪个临时容器、主账本是否仍为 4 条、仍存在哪些人工验收项。不得用“全部完成”概括只通过构建或局部测试的工作。

38. **当前账本导入、API 令牌入口与 NAS 部署（2026-10-01）**：Web 桌面/移动导入绑定打开时选中的 ledgerId 与名称，所有解析请求和批量提交传同一字符串账本 ID；切换账本后旧预览拒绝提交。账户与分类按目标账本加载，缓存“已是最新”作为正常结果；修复分类缓存跨账本同值比较未记录目标账本的问题。桌面账本切换菜单使用锚定按钮的菜单，支持方向键、Escape、外部点击，避免 Vuetify 菜单首次定位偏出视窗。Android 解除非默认账本导入限制，parseStatement multipart 和 importStatement 顶层 JSON 显式传当前 ledgerId。服务端解析/导入确认成员 CanWrite，账户必须属于目标账本；共享成员数据落到账本所有者分片并保存 RecorderUid/PayerUid。Agent 批次增加 ledgerId，preview/map/confirm 同账本、同用户、同令牌；指纹含账本 ID，同一账本跨成员查重。提交事务先锁账本/成员后重新检查权限，预览后降为只读或移除即拒绝写入；同所有者使用有界条带锁。MCP 增加 query_import_context 返回可访问账本、目标账户和末级分类的字符串 ID；Agent 无法读取浏览器当前选择，须显式指定 ledgerId。
   验收：npm run check 通过（38,494 项、类型、lint、生产/PWA 构建）；无卷构建容器 CGO=1 go test ./... 通过，追加共享账本所属/记账人及事务内撤权测试通过；14 组无卷 HTTP/MCP/浏览器测试通过，实际选择 API 连接生成令牌，验证密码错误、权限拒绝、关闭完整令牌显示、撤销/到期，并通过 Web 当前账本上传解析预览。Android assembleDebug/lintDebug/assembleDebugAndroidTest 通过，两个 APK 使用 install -r，StatementImportContractTest 与 DockerStatementImportE2ETest 在 18110 无卷容器完成 OK (3 tests)，包括选定账本导入、默认账本流水不变；冷启动无 AndroidRuntime 错误。本轮未重测原主账本“四条”数量，不得据此宣称完整人工验收。
   NAS 192.168.31.184:8080 已部署经过验收的工作树镜像，版本 1.9.2；新容器 finexy-bookkeeping-agent-1-9-2，原 finexy-bookkeeping-version-1-9-2 保留为回退并关闭自动重启避免端口争用。沿用 data/storage/log 三个原挂载，停机完整备份在 /vol2/1000/Docker/finexy-import-20261001/backup/agent-access-20261001-223034；原财务表行摘要一致、附件哈希一致、SQLite integrity_check=ok、镜像层与本机一致。API/MCP 开关启用；上线桌面/移动登录页面前后端均 1.9.2、浏览器错误零，API/导入/MCP 无令牌请求按既有语义返回 400/202012。证据在忽略的 artifacts/agent-access/（nas-deploy-final.json、nas-online-features-final.json、http-browser-final.json 与各测试日志）。本地 18080 用户容器未替换，本次功能尚未推送 GitHub 或公开镜像源。
39. **MCP 开启/关闭入口（2026-10-01）**：账户 → 安全设置 → Agent 接入，以及移动 Web 设置 → Agent 接入新增“此账号的 MCP 接入”开关。独立 UserStore AgentAccessSetting 表保存账号偏好，缺少记录时沿用原安装开启行为，不改财务表结构。登录会话可读取/更新，API/MCP Agent 令牌不能修改接入设置；服务器全局 EBK_MCP_ENABLE_MCP 和账号管理员限制继续生效。关闭后所有 MCP 后续请求及新令牌生成被拒绝，API、其他账号和账目不受影响；重新开启恢复未过期且未撤销的令牌，不中断已经执行的请求。界面覆盖未知/加载/保存/错误/重试状态、switch 键盘语义，关闭时按回车也不会生成令牌。手机纵向排列说明与开关，避免通用按钮满宽规则导致横向溢出。
   验收：npm run check（14 文件、38,494 测试、类型/lint、生产/PWA 构建）通过；CGO=1 go test ./... 与最终 services 回归通过，追加前端守卫后单文件 ESLint、生产构建及最终 18 组隔离 HTTP/MCP/浏览器验收通过。覆盖旧令牌阻断、重启持久化、账号隔离、API 保留、恢复访问、失败不伪装成功、桌面键盘与手机入口及 375/768/1024px 无溢出。未改原生 Android MCP 设置，未在真实 NAS 创建测试账号或录入测试流水。
   NAS 已部署最终镜像至 finexy-bookkeeping-mcp-1-9-2，服务版本 1.9.2 / commit mcp-switch-20261001；完整备份 /vol2/1000/Docker/finexy-import-20261001/backup/agent-access-20261001-230735，旧容器保留且禁用自动重启。部署中一次挂载数组顺序核验失败已自动回退，修正为按挂载路径排序核验后成功；数据目录没有删除。原财务表摘要、附件哈希、SQLite 完整性全部通过，原 data/storage/log 挂载保留。线上桌面/移动启动零浏览器错误，MCP 开关资源与本地一致，设置与 MCP 未认证请求均被拒绝。证据 artifacts/agent-access/http-browser-final.json、nas-deploy-final.json、nas-online-features-final.json、nas-browser-startup-final.txt。GitHub/公开镜像源及本地 18080 用户容器本轮未更新。


### 2026-10-01 1.9.3 账本授权与导入分组映射

- Agent API/MCP 令牌持久授权包含字符串 ledgerId，一个令牌对应一个账本。服务端检查声明的账本、实际账户/流水归属及当前成员权限；旧上下文缺少账本字段时限制为默认个人账本。普通登录会话保持原有权限。
- MCP 普通账户查询、流水查询、普通记账使用令牌绑定账本。记账经 BatchImportTransactions 的成员权限/归属事务校验，dry_run 不入账；跨所有者共享账本普通记账暂不支持标签。
- 尚未具备完整账本边界的旧全量导出、资产趋势和金额汇总 API 不向 Agent 开放，使用分页流水/账本统计替代。
- 桌面及移动 Web 检查数据页新增 StatementMappingPanel，按原账户名/币种及分类名/类型分组，明确点击应用；默认仅补全未映射项，覆盖需勾选，保持选中状态且不提交。
- 共享 StatementMappingPanel 与 AgentAccessPanel 必须归入 Vite common chunk，避免生产模块初始化循环。账户隐藏接口必须检查真实账本，不能只信任请求的 ledgerId。
- 本轮验收证据写入 artifacts/agent-access/；发布镜像前必须运行无卷 finexy-agent-e2e（18110）的 API/MCP/真实浏览器门禁。NAS 更新保留三处数据挂载、完整备份及原容器回退，不在真实账本创建验收数据。

   本轮发布前验收：npm run check（14 文件、38,494 测试、类型/lint、生产/PWA 构建）通过；CGO=1 go test ./... 全部通过；21 组无卷 HTTP/MCP/真实浏览器验收通过，包括指定账本 UI 生成、MCP 查询/记账、跨账本账户隐藏与普通记账拒绝、撤销/到期、成员降权/移除、分组应用和覆盖门禁、桌面与移动 Web 布局。发现共享组件生产分块循环及账户隐藏接口归属遗漏后均已修复，并重建最终镜像重新验收。原生 Android 本轮未改新增分组映射或 Agent 设置，未重测主账本四条计数。
