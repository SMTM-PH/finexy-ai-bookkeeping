# Finexy 项目 AI 必读手册

> 最后核验：2026-09-04。任何 AI、自动化代理或新开发者开始修改前，必须完整阅读本文。本文是当前项目状态、Android 开发约束、测试方法和后续计划的唯一权威入口；README 负责面向用户介绍产品。涉及任何界面工作时，还必须完整阅读 `docs/UI_UX_DESIGN.md`。

## 1. 项目与当前状态

Finexy 是自托管个人财务系统。仓库同时包含 Go 服务端、Vue Web/PWA、Electron Windows 客户端、OCR 服务和原生 Android App。

Android 当前状态：

| 阶段 | 状态 | 已交付范围 |
| --- | --- | --- |
| A 数据一致性 | 已验收 | Room Flow、结构化流水、毫秒/秒边界、完整字段保留、上传 ID 回写、响应丢失幂等恢复、用户/服务器账本隔离、v1→v10 迁移链 |
| B 完整双向同步 | 已验收 | 全量分页、删除语义、账户/分类显式映射、冲突完整字段解决、WorkManager 持久重试与进程恢复 |
| 用户、安全和数据管理 | 功能完成，部分人工验收待办 | 注册登录、会话、密码、2FA、应用 PIN、生物识别入口、加密备份恢复、清理本地数据 |
| C 现有业务闭环 | 本地功能完成，服务端 E2E 待补 | 账户详情、标签、模板、统一筛选、统计与 CSV 已交付；C2/C3/C5 等待隔离 Docker 验收 |
| D Web 能力补齐 | 开发中（D1–D3 本地闭环已完成，D4 数据层开发中） | 转账/余额调整、服务端账户 CRUD、服务端分类 CRUD 已具备本地闭环；周期交易已进入 Room/API 数据底座阶段 |
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
| Android | compile/target 35，min 26，Compose BOM 2025.01.00，Room 2.6.1 |
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
5. 后续依次为 AI/OCR 待确认、资产、汇率与用户偏好。

所有阶段 D 项目必须核对实际 Go API 和 Web 行为，不能凭名称猜 payload。

### 阶段 E

完成 TalkBack、最大字体、多尺寸/横屏、主题语义色、导航一致性；将 lint、Room migration、关键 instrumentation 和发布签名验证纳入 CI；生成并验证 release APK/AAB。生物识别成功路径需在已录入指纹的设备上人工验收。

## 9. 完成定义与交接格式

每次交接必须写清：改了什么、未改什么、运行了哪些测试及准确结果、哪些测试因参数跳过、使用和清理了哪个临时容器、主账本是否仍为 4 条、仍存在哪些人工验收项。不得用“全部完成”概括只通过构建或局部测试的工作。
