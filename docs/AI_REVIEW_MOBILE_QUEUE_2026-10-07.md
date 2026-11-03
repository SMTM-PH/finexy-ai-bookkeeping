# 移动 Web AI 队列与确认收尾

日期：2026-10-07。接续 [Web 恢复合同](AI_REVIEW_WEB_RECOVERY_CONTRACT_2026-10-06.md) 和 [桌面原子确认](AI_REVIEW_DESKTOP_CONFIRMATION_2026-10-06.md)，对应 [后续开发计划](NEXT_DEVELOPMENT_PLAN_2026-10-07.md) 的 F01。本轮只处理移动 Web AI 队列与确认切片的阻塞项；**F01 尚未验收**，正式服务、NAS 与 Android 主应用均未更新。

## 本轮定位的阻塞项

原移动专项在“真实存储失败 → 丢弃草稿 → 返回队列”一步失败：点击“确认丢弃”后立即返回队列，队列页仍显示“恢复原条目”，与“离开不重建草稿、队列恢复提示同步消失”的出口不符。

诊断结论是**脚本时序问题，不是产品行为缺陷**：

- 产品侧丢弃走 `withTransactionCreateLock`（真实 IndexedDB 读写事务序列化），删除在点击后的若干任务才提交；[队列页](../src/views/mobile/ai/ReviewPage.vue) 进入时**同步**读取本机草稿与待确认日志来决定是否显示恢复入口。
- 原脚本在点击“确认丢弃”后立刻 `navigate`，队列页读到尚未删除的草稿，因此渲染恢复入口。
- 隔离候选上的插桩证据：点击后草稿键 `…ai-review-draft:<ledgerId>` 仍存在（revision `4ef681f3-29e8-81f9-8930-da8ba982d472`，输入为合成草稿 B），面板无错误提示；800 ms 后该键消失；返回队列后本机草稿键为空、恢复入口为 0。**没有**观察到“离开时重新创建草稿”，也**没有**丢弃被拒绝（无 `原草稿已变化` / `结果待确认` 提示）。

## 修改

只改验证脚本，不改产品源码：

- [.github/scripts/verify-ai-review-mobile.mjs](../.github/scripts/verify-ai-review-mobile.mjs) 新增 `discardDraft(p)`，两处丢弃（未匹配资源的残缺草稿、存储失败场景）统一使用。它在点击“确认丢弃”后等待两个已提交信号才离开页面：生产面板不再显示“丢弃本机草稿”，且当前账本范围内已无 `ai-review-draft` 本机键。丢弃被服务端或存储拒绝时该等待会超时失败，不会静默通过。
- 本轮没有 `src/`、`pkg/` 或 Android 变更，已记录的桌面、来源、账本与模板结果保持各自范围。

## 验证与证据

证据目录：`artifacts/ai-review-mobile-20261006/`。

- 候选：2026-10-06 使用的镜像链（`finexy-ai-review-mobile/source/desktop/web-contract`）已被清理，本轮按当前工作树用仓库 [Dockerfile](../Dockerfile) 重建，镜像 `finexy-ai-review-mobile:20261006-root2`，ID `sha256:a08107ceee19df343ac70735c76841e36a6c26f4048df0aa518f2134df389d90`，构建日志 `build-candidate-20261007.log`。构建使用 `SKIP_TESTS=true`，未在镜像内重复执行 Go/Web 测试；与前一轮镜像链的差异仅此一项。
- 隔离方式：容器 `finexy-ai-review-mobile-e2e`，无任何挂载，`127.0.0.1:18138 → 8080`，`EBK_UUID_SERVER_ID=255`、`EBK_AUTH_ENABLE_REGISTER=true`，随机账号与合成数据，每轮结束停止并删除容器；未接触本地用户服务与主账本。
- 插桩诊断：`instrumented-verify.mjs` + `instrumented-run.log`（含上述三条 `PROBE` 记录）。
- 生产专项：修复后 `production-run-1.log`、`production-run-2.log` 两次连续 **10/10**；补充场景后 `production-run-7-extended.log`、`production-run-8-extended.log` 两次连续 **14/14**；补齐最后三组后 `production-run-16-full.log`、`production-run-17-full.log` 两次连续 **17/17**（`pageErrors` 0、旧普通新增与 `resolve` 写入 0 次、原子确认请求 7 次）。结果 `browser-final/summary.json`、`browser-extended/summary.json`、`browser-full/summary.json`。
- 17 组用例：共享账本队列与未完成计算式跨重载、确认回包丢失后原请求重放且只改一次余额、已知回执只读恢复、忽略与恢复、未匹配账户/分类不默认填入、双标签页版本冲突、真实存储失败与丢弃退出、账本切换/读取失败与真实空态、四角色入口、跨币种转账双金额、负支出退款、外部转账双向、默认个人账本 `0`、预览后降权、迟到队列响应不覆盖新账本上下文、真实浏览器进程重启后草稿仍可恢复并可丢弃、换账号不继承上一账号的本机草稿/恢复入口/写控件；跨币种一项同时覆盖深浅主题、最大字体、横屏与 44px 操作。
- 本轮修正的测试侧假设（产品源码未改）：①确认成功后面板随页面返回而移除，用例必须先回到队列页，否则会落在上一个编辑器页上；②会话中经 API 新建的账户不在编辑器已加载的账户列表里，会被判为不可用，用例需在打开条目前重载；③退款是负支出且使余额增加（与 `pkg/services/ai_review_ledger_cgo_test.go` 的 money contract 一致），断言应以确认前余额为基准；④进程重启后要丢弃草稿，需先重载回到恢复分支（`resume` 之后面板已不在该分支）；⑤退出登录后必须等待 App 自身的失效令牌重载与登录页出现，不能用 `page.goto` 并发强制导航。
- 关于退出登录的页面错误：会话失效时 App 会清除令牌、`location.reload()` 并以非 Error 的 `{ processed: true }` 信号拒绝在途请求（[services.ts](../src/lib/services.ts) 的响应拦截器）。测试并发导航时它表现为未处理的 promise 拒绝（同一阶段还会出现 `tokens/refresh.json` 401）；改为等待登录页后该页面错误消失，说明它由测试竞争触发，不改变产品代码。脚本同时把 `consoleErrors`（含来源位置）写入 `summary.json`，并把阶段名并入页面错误文本，便于后续定位。

## 同一候选上的关联回归

四个相邻生产套件针对**同一镜像 ID** `sha256:a08107ceee19df343ac70735c76841e36a6c26f4048df0aa518f2134df389d90` 运行（仅标签不同），因此“同一候选”比逐套件独立构建更强：

| 套件 | 结果 | 证据 |
| --- | --- | --- |
| 桌面 AI 原子确认 `verify-ai-review-desktop.mjs` | 10/10 | `artifacts/ai-review-desktop-20261007/desktop-regression.log` |
| 普通移动记账 `verify-mobile-ledger-write.mjs` | 12/12 | `artifacts/mobile-ledger-write-20261004/mobile-write-regression-20261007.log` |
| 模板创建（桌面 + 375px 移动 + 旧服务 404）`verify-template-creation-web-editor.mjs` | 13/13 | `artifacts/template-creation-web-editor-20261005/template-editor-regression-20261007b.log` |
| AI 来源录入 `verify-ai-review-source.mjs` | 13/13 | `artifacts/ai-review-source-20261006/source-regression-20261007.log` |
| Agent 接入 `verify-agent-access.mjs` | 21 项 | `artifacts/agent-access/agent-gate-20261007b.log` |

候选来源与替换方式（10/06 的镜像链已被清理，按各脚本允许的候选名重建；未删除历史证据，覆盖前先备份到各自的 `pre-20261007/`）：

- 被测应用镜像由当前工作树用仓库 [Dockerfile](../Dockerfile) 构建（`SKIP_TESTS=true`，即未在镜像内重复跑 Go/Web 测试），本轮所有套件复用同一镜像 ID。
- 旧服务角色：模板套件要求 `finexy-template-submission-candidate:20261005-root1`（18129）对能力端点返回真实 404；该历史候选不可得，替换为已发布的 `ph97/finexy-bookkeeping:1.9.3-amd64`（2026-10-02），并实测 `/api/v1/transaction/templates/capabilities.json` 与 `/api/v1/ai/review/capabilities.json` 均返回 **404**，因此仍承担真实旧后端角色。
- 来源 mock：`finexy-ai-recognition-mock:20261005-root1` 按仓库夹具 [.github/scripts/fixtures/ai-recognition-mock.go](../.github/scripts/fixtures/ai-recognition-mock.go) 重建（上下文 `artifacts/ai-review-source-20261006/mock-build/`），并向被测容器写入 `data/ai-configuration.json` 将识别指向该 mock（该文件读取失败会阻止服务启动，字段 `enableThinking` 必须省略或为字符串枚举）。
- Agent 门禁容器需要 `EBK_SECURITY_ENABLE_API_TOKEN=true` 与 `EBK_MCP_ENABLE_MCP=true`，否则令牌创建返回 403；首次未设置导致失败，已保留该失败日志 `agent-gate-20261007.log`。

## 门禁与未运行项

- `npm run check`（typecheck + eslint + vitest + 生产构建）：34 个测试文件 / **38,782** 条通过，见 `web-check-20261007.log`。
- `go test ./...`：全部通过、无 FAIL，见 `host-go-20261007.log`。
- 未运行并单列：CGO=1 的 SQLite/事务与 race 专项（本轮无服务端改动）、Android 仪器化（无 Android 改动）、远程 CI、真实服务部署与主应用升级。这些未运行项不得由本记录推断为通过。

## 范围与后续

- **F01 已按本记录验收**：移动专项 17 组两次连续通过，四个关联套件与 Agent 门禁在同一候选上通过，Web/Go 门禁通过，未运行项已单列。候选为按当前工作树重建（构建参数与替换方式见上），不是 10/06 镜像链原物。
- 下一项是 F02（移动 Web AI 来源录入），复用桌面来源合同并按移动页面适配。
- 同一候选上的关联回归（桌面确认与来源、普通移动记账/模板，涉及 Agent 公共入口时补 Agent 门禁）尚未在本轮重跑。
- 丢弃等待是脚本同步修复，不代表产品在极端速度下不存在“提交前返回”的短暂窗口；该窗口小于人工返回操作所需时间，且恢复入口只反映已提交状态。
- 各类型、深浅主题、最大字体、窄屏/横屏、键盘与完整读屏的组合仍未全部人工验收；整个开发计划仍在进行中。
