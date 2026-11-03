# SQLite 并发读写修复

日期：2026-10-05。接续 [Android 模板页面跨进程恢复](TEMPLATE_CREATION_ANDROID_UI_PROCESS_2026-10-05.md) 中实测的 `database table is locked: ledger` / HTTP 500。本文区分源码修复、隔离运行结果与真实部署；开发计划仍在进行中。

## 问题与实现

旧 SQLite DSN 为 `cache=shared&mode=rwc`。共享缓存的表锁使一个连接尚未提交的写事务阻止其他连接读取 `ledger`，返回 `SQLITE_LOCKED`。普通 busy timeout 无法解决这一类表锁；官方也不建议使用共享缓存，见 [SQLite shared cache](https://www.sqlite.org/sharedcache.html)。

`pkg/datastore/datastore_container.go` 的 SQLite 连接改为：

```text
cache=private&mode=rwc&_busy_timeout=5000&_txlock=immediate
```

独立缓存使并发读取返回已提交值。写事务通过 `BEGIN IMMEDIATE` 在业务回调开始前取得写入保留锁，避免 deferred 事务读取后升级写锁的竞争。5 秒等待由 SQLite 驱动执行；没有重跑业务回调。事务语义见 [SQLite transactions](https://www.sqlite.org/lang_transaction.html)，驱动参数见 [go-sqlite3](https://github.com/mattn/go-sqlite3)。

只修改 SQLite 连接选项；MySQL/PostgreSQL、数据库 schema、金额及账本合同未变。没有切换 WAL，也没有把连接池强制限制为一个连接。此修复需要服务进程重新创建连接后生效。

## 可复现证据

新增 `pkg/datastore/sqlite_concurrency_cgo_test.go`，使用生产数据库工厂、临时 SQLite 文件及四个连接；每个连接池正常关闭。四项测试覆盖：

1. 写事务更新 `ledger` 后保持未提交，另一连接必须及时读到原来的已提交值；同一写事务也先确认自己已读到未提交替换值，防止夹具误放开锁。
2. 两个独立连接池竞争写入，后一个事务须先等待，再以最新已提交值运行回调且只运行一次。
3. 四个写入者通过两个连接池执行 48 次读后写，两个读取者并发观察；最终计数必须为 48，无丢更新、读错误或重复回调。
4. 业务拒绝后的写入全部回滚，回调不重放。

将第一项有效夹具放回旧连接配置后，稳定得到 `database table is locked: ledger`，测试退出码为 1，记录为 `baseline-repro.log`。早期夹具曾误用自动关闭的查询 session；那一轮无效通过保存在 `baseline-initial-invalid-fixture.log`，不计入结论。最终夹具使用可持有事务的 `engineGroup.NewSession()`，并验证未提交值。

隔离 API 脚本 `.github/scripts/verify-sqlite-concurrency.mjs` 在写入前核验容器名、ID、镜像哈希、无卷挂载、localhost 端口与 UUID/注册/定时任务环境；只用随机账号。业务请求不自动重试。运行结果：

| 范围 | 结果 |
| --- | --- |
| 旧版对照：12 路、480 个并发请求 | 125 次 HTTP 500，均来自 `ledger/modify.json`；模板列表本轮没有出现 500。它证明了真实并发异常，具体表读锁由上述有效夹具直接证明 |
| 修复版：同样 480 个并发请求 | 零异常；包含账本修改、普通/周期模板读取及四角色 |
| 修复版：两个独立服务进程 | UUID 246/248，共 120 次同键创建；十个稳定键各产生一个模板，无客户端重试；改变内容返回 400 |
| 权限、归属与财务 | 普通成员/只读拒绝创建；管理员降权后第二进程立即拒绝重放；所有者与创建人、默认/A/B 账本相互隔离；完整合成账户/流水快照哈希相同 |
| 修复版全部 API 请求 | 671 次：657 次正常成功、14 次预期拒绝、零意外结果 |

## 门禁与冻结候选

证据目录为 `artifacts/sqlite-concurrency-20261005/`，最终汇总见 `final-summary.json`。

- Linux `CGO_ENABLED=1`：四项 SQLite 专项各运行十次，共 **40 次**通过；`go test ./... -count=1` 的 **48 个含测试包**通过；`go test -race ./pkg/datastore ./pkg/services -count=1` 两包通过。
- `npm run check`：类型、lint、29 个测试文件 / **38,637 个用例**及生产构建通过。已有 Sass、chunk 大小与构建选项弃用提示保留在日志中。
- Windows `go test ./...` 最终通过，CGO 为 0，不能代替 SQLite 专项。首次失败因历史证据源码副本被纳入模块并缺失原包上下文/测试数据；新增仅本机使用的 `artifacts/go.mod` 模块边界后重跑，所有历史文件保留。首次失败另存 `host-go-test-before-evidence-boundary.log`。
- **578 个** Go 源码/模块文件分别核验工作树、冻结构建上下文和实际构建镜像内的哈希，完全一致。专项脚本另存独立哈希。
- 候选镜像：`finexy-sqlite-concurrency-candidate:20261005-root1`；镜像 ID `sha256:13159b99a1d75699128da6e562028cb510bc769588a78b9bc0fd1b21f33da134`。
- 服务端二进制 SHA-256：`d6dfebf7df2050c1f5651bb45176674c0deefb2277b1db31e3dac3bf24510b9f`；版本仍为 1.9.3，构建标记 `sqlite-concurrency-local`。运行镜像沿用上一冻结候选的静态资源，属于本次服务端隔离验证候选。
- 两个 HTTP 临时容器及提取/清单临时容器均已移除；原本地用户服务和 OCR 容器保留。本轮没有 Android 安装/运行，没有访问 NAS，没有真实财务写入或部署。

## 边界与后续

5 秒等待有上限；超长写事务、长时间阻止提交的读取及外部竞争仍可能返回 `SQLITE_BUSY`，本次不宣称任意负载下无数据库错误。跨连接池及两个独立服务进程的专项通过，不等于完成持续负载性能基线、所有数据库类型或所有客户端的重新验收。

真实服务仍需同版本完整发布门禁、备份/完整性核验、保留环境和挂载的升级及回退验证。Android 主应用没有升级。本次先排除了恢复专项发现的运行阻塞，下一项继续 Web 普通/周期模板新增的持久确认与跨进程恢复，再接续 AI 账本合同、完整报告、月度首页、人工适配及正式发布，见 [开发计划状态](DEVELOPMENT_PLAN_STATUS_2026-10-04.md)。
