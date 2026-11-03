# Android 模板创建：能力合同与新进程 Worker

日期：2026-10-05。接续 [Room 24 / 备份 18 存储底座](TEMPLATE_CREATION_ANDROID_STORAGE_2026-10-05.md)。本轮接入生产 API、SyncEngine 与 SyncWorker，并完成独立 QA 新进程验收；本轮当时的普通/周期表单仍使用原直接创建路径。后续生产表单已接入，见 [页面接续](TEMPLATE_CREATION_ANDROID_EDITOR_2026-10-05.md)；后续证据不替换本页冻结包或测试数字。

## 已接通合同

- 新增正常鉴权的只读 `GET /api/v1/transaction/templates/capabilities.json`，`Cache-Control: no-store`。回包提供合同版本、持久回执、内容绑定、删除回执、键长度、所有者/创建者/账本作用域、专用路径与已启用模板类型。能力不授予管理权限，不根据产品版本 `1.9.3` 推断旧部署能力。
- 新增 `POST /api/v1/transaction/templates/add_durable.json`，必须携带非空稳定键；与既有新增路径共用原子服务，保留实时成员复核、内容绑定和回执合同。旧服务没有这条路径，客户端不降级到旧新增接口，防止能力读取与写入之间切换到旧实例时误创建。
- Android `createTemplate(TemplateCreationEntity)` 先验证存储快照，再实时读取能力和成员权限，仅调用专用路径。旧服务、功能停用、撤权或无效回包保留原确认请求；未经确认草稿不进入发送器。
- 活跃回执完整校验模板；删除回执严格核对 ID、账本、类型、不可编辑和无未来到期时间，仅确认原创建结果，不把 tombstone 放进活跃列表缓存。
- SyncEngine 在读取前投递确认队列，先持久化回执，再刷新同账本快照。独立请求继续处理，最后报告失败。生产 SyncWorker 沿用持久 WorkManager 调度和服务器/用户身份检查；界面可调用 `performTemplateCreation` 发送指定原请求，不能重建新键。

源码：`pkg/models/transaction_template.go`、`pkg/api/transaction_templates.go`、`cmd/webserver.go`；Android 的 `FinexyApi.kt`、`PeriodicContract.kt`、`SyncEngine.kt`。未改变 Room/备份版本。

## 本轮证据

证据目录 `artifacts/template-creation-worker-20261005/`，保留完整原始输出、冻结 APK、源码清单、摘要、PID 见证与清理记录。

- Java 17 QA 构建和 lint 通过；69 条 JVM 测试通过，无跳过，其中新增 4 条能力、作用域和删除回执合同测试。
- 91 条设备关联回归通过，无跳过，覆盖 Room/备份新旧兼容、同步状态、服务器/用户隔离、普通/周期模板、流水一致性及相关数据合同。
- 2 条真实 API 设备兼容用例通过：旧服务保持确认队列且无新增模板；管理员降为成员后不能重放共享模板，但其默认个人账本的独立确认请求完成，后续删除重放返回原 ID 的 tombstone。账户、流水与余额均保持实际基线，测试不假定初始流水为空。
- prepare → instrumentation 退出 → 终止准备进程 → 启动新的 QA 进程 → 生产 Worker 完成 → verify 全序列通过。准备 PID 21396，Worker/启动 PID 21446，验证进程另起；固定结构见证只保存 PID、单调时间与状态，不保存凭据或财务内容。
- 准备阶段普通模板已经在专用 API 提交，回执尚未存入 Room 时注入丢失；周期模板已确认但尚未发送。新进程 Worker 重放原普通请求得到原 ID，只生成一个周期模板，完整刷新后确认队列清空。请求期间的新草稿版本、另一账本未确认草稿保留；默认与原账本完整财务快照不变。
- 隔离 HTTP 门禁通过：未登录响应保持项目既有 `400 / 202012` 合同；登录后的能力不缓存；缺失、空白和超长键拒绝；同键重放只有原对象、不同内容拒绝；旧服务的两个新接口均不存在；财务快照不变。首次门禁误期望未登录返回 401，修正为实际鉴权合同，不修改生产鉴权或降低门禁。
- Linux `CGO_ENABLED=1 go test ./... -count=1` 全部通过；模板创建、周期、到期待确认和流水创建关联 race 测试通过。577 个 Go 源码/配置文件与构建镜像内实际源码一致；Windows 工作区已有松散 artifact Go 辅助程序，未把该目录的本机全遍历当作本轮通过依据。
- `npm run check` 通过，包括类型、lint、38,637 条 Web 测试与构建。它不替代浏览器冷启动或新 Web 发布包验收；本轮运行候选仍继承上轮冻结前端。
- 144 个 Android 源码/配置文件冻结；设备应用/测试 APK 与冻结文件哈希一致。QA APK SHA-256 `f2db4c6a13adbff5185535f2f5907ce58dacd1055530012089ac8305bae07fb6`；测试 APK `29bfed25d1ac424af6a929c51e6c241827ae59fc4fed5b2d5d2832e4c4abac46`。
- 主应用只读冷启动成功，无该 PID 的 `FATAL EXCEPTION`；前后 SQLite 完整性 `ok`、Room 21、0 条流水，记录表行数/哈希一致（Room 标识与同步状态元数据除外）。主应用、真实用户服务和 NAS 未升级。

候选 `finexy-template-creation-worker-candidate:20261005-root2`，ID `sha256:56e53787747c76e5fce56030a0ff078b03128354d0071d9d5ac77190f0a045bd`。新服务无卷容器只绑定 `127.0.0.1:18117`，UUID 241；旧服务对照只绑定 18118，UUID 242。内存去重及周期自动派发均关闭。两服务容器和本轮构建导出容器完成身份、镜像、挂载复核后已清理，镜像与证据保留。

## 尚待完成

1. 普通/周期表单、明确确认及原请求状态已在 [生产表单接续](TEMPLATE_CREATION_ANDROID_EDITOR_2026-10-05.md) 完成；修改已有模板仍保持独立修改合同。
2. 后续生产页面已补验活动重建、当前/默认账本隔离、权限撤销和旧服务重试；真正 UI 进程终止/重启与完整键盘/错误恢复仍待补齐。本轮新 PID 证据仅证明数据与 Worker 路径。
3. 完整深浅主题、最大字体、横屏与 TalkBack，正式签名、远程 CI、主包保留数据升级、真实服务迁移、冷启动与回退仍待完成。AI 账本化、报告和月度首页继续遵守 [整个开发计划](DEVELOPMENT_PLAN_STATUS_2026-10-04.md)。
