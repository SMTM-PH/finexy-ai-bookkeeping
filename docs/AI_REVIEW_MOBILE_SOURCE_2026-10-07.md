# 移动 Web AI 来源录入

日期：2026-10-07。接续 [桌面来源合同](AI_REVIEW_SOURCE_2026-10-06.md) 与 [移动 Web 队列](AI_REVIEW_MOBILE_QUEUE_2026-10-07.md)（F01），对应 [后续开发计划](NEXT_DEVELOPMENT_PLAN_2026-10-07.md) 的 F02。**F02 已验收**；正式服务、NAS 与 Android 主应用均未更新。

## 页面与入口

- 新增移动来源页 [SourcePage.vue](../src/views/mobile/ai/SourcePage.vue)（路由 `/ai/source`，按 `asyncResolve` + `checkLogin` 模板注册）：文字与票据统一入口；「粘贴剪贴板文字」读取失败只显示可见提示，**不会自动发送给模型**；图片支持四角百分比、上/下/左/右移、扩缩、顺时针 90°、重置与真实裁剪预览（渲染前释放 object URL）；隐私说明 → 明确「确认发送并识别」；识别结果标记为 AI 猜测并显示**本账本**实际账户币种与分类；预览 → 「确认保存待复核」；另有继续编辑/丢弃来源草稿、重试原保存请求、重新读取原条目、取消识别、关闭并保留本机记录。
- 旧移动入口全部迁入：首页剪贴板文字、首页票据图片、PWA 分享目标三处改为把文字或原图作为**内存路由 props** 交给来源页；编辑器「更多操作」的 AI 动作改为「AI 来源录入」（仅新增模式，避免与编辑模式的手动输入混用）。删除旧的「先调模型 → 把金额/账户/分类/标签/备注拼进 URL → 走普通新增」路径、旧文字识别 sheet、`autoRecognizeClipboardText`/`autoUploadPicture` 路由 props 与「AI 识别仅用于默认个人账本」硬门禁。
- 入口改为**当前账本**：是否可用由全局能力（`llmtr`/`llmir`）与每账本 `v1/ai/review/capabilities.json`（识别额外要求 `recognitionLedgerContext === true`）把关；能力缺失时保留草稿并给出可见提示，**不静默回退**到不带 `ledgerId` 的旧识别接口。
- 顺带修正首页长按守卫漏判文字能力（只开启文字 AI 时文字入口不可达）的问题。

## 共享状态层

- 抽出 [AIReviewSourceSessionBase.ts](../src/views/base/ai/AIReviewSourceSessionBase.ts)（沿用 `views/base/**/*Base.ts` 约定）：来源草稿与创建回执、恢复/丢弃、识别取消与迟到结果忽略、持久化串行与版本校验、双标签页 `storage` 监听、账本与账号变化时关闭会话、object URL 释放都在这里。桌面 [AIReviewSourceDialog.vue](../src/views/desktop/transactions/list/dialogs/AIReviewSourceDialog.vue) 改为复用它（`<script>` 从 206 行缩到 12 行，**模板与样式零改动**），移动来源页使用同一实现，因此两端不会出现两套恢复逻辑。
- 会话在核验能力后确保加载当前账本的账户与分类（忽略 `isUpToDate`/`stale` 信号）：来源页被直接进入时，识别摘要同样显示真实账户币种与分类。此前摘要只依赖调用方页面已加载，直接进入会显示「账户及币种待选择」。

## 本机数据与恢复

沿用桌面合同：`AIReviewSourceDraft` 按服务器路径/用户/账本隔离，同一账本只保留一个活动草稿；原图以每图随机密钥加密存 IndexedDB；确认保存前持久化不可变快照；结果未知时只允许重试原请求；已知回执只读取原条目并在核验后清理日志与同版本草稿（同时移除本机原图）；明确丢弃同样清理本机文字、原图与裁剪设置。页面在会话被账本/账号切换关闭后，再次进入会按当前账本重新打开，只对首次进入应用分享/剪贴板预填。

## 验证与证据

候选：`finexy-ai-mobile-source:20261007-root2`，镜像 ID `sha256:0549baad737ffb95f06fcd2abd1ed05e97664391fc9ce7ef4952564f1b429fc3`，由当前工作树用仓库 [Dockerfile](../Dockerfile) 构建（`SKIP_TESTS=true`，未在镜像内重复执行 Go/Web 测试）。隔离方式：无卷一次性容器 + 合成 OpenAI 兼容夹具（`http://<mock>:8080/v1`），随机账号与合成 PNG，跑完删除容器与网络；未接触本地用户服务与真实数据。

| 套件 | 结果 | 证据 |
| --- | --- | --- |
| 移动来源专项（新）`verify-ai-review-mobile-source.mjs` | 12/12 | `artifacts/ai-review-mobile-source-20261007/run.log`、`summary.json` |
| 桌面来源回归 `verify-ai-review-source.mjs` | 13/13 | `artifacts/ai-review-source-20261006/source-regression-20261007-f02.log` |
| 移动 AI 队列专项（F01）`verify-ai-review-mobile.mjs` | 17/17 | `artifacts/ai-review-mobile-20261006/production-run-f02.log` |
| 普通移动记账 `verify-mobile-ledger-write.mjs` | 12/12 | `artifacts/mobile-ledger-write-20261004/mobile-write-regression-f02.log` |
| Agent 接入 `verify-agent-access.mjs` | 21 项 | `artifacts/agent-access/agent-gate-f02.log` |
| 模板创建（桌面 + 移动）`verify-template-creation-web-editor.mjs` | 13/13 | `artifacts/template-creation-web-editor-20261005/template-editor-regression-f02.log` |
| `npm run check` / `go test ./...` | 34 文件 / **38,782** 条通过并完成生产构建；Go 48 个包 `ok`、0 `FAIL` | `web-check-f02.log`、`host-go-f02.log` |

移动来源专项覆盖：文字先存本机且不发模型、重载后明确恢复、隐私确认前不发、确认后带本账本上下文（夹具校验必需账户/分类名称，违规 0）并显示真实币种与分类、识别结果跨重载不重复调用、加密原图与四角/旋转/裁剪预览、裁剪后图片识别、创建丢回包后原 payload 重试一次且本机原图清除、改为手动复核与已知回执只读恢复、held 请求取消后迟到结果被忽略、真实存储配额失败可见并保留输入、账本隔离、换账号不继承；全程财务写入 0、旧普通新增/`resolve` 调用 0，且文本识别请求都携带本账本 `ledgerId`。

候选替换与脚本改动（均为验证侧，不影响产品源码）：模板套件的「旧服务」角色沿用已发布的 `ph97/finexy-bookkeeping:1.9.3-amd64`（实测能力端点返回真实 404）并重命名为脚本要求的候选名；移动 AI 队列与桌面来源脚本的**候选名允许列表**新增本轮候选；识别夹具按仓库 `.github/scripts/fixtures/ai-recognition-mock.go` 重建，并向被测容器写入 `data/ai-configuration.json` 指向夹具。各证据目录覆盖前先备份到各自的 `pre-20261007*` 子目录，未删除历史证据。

## 范围与后续

- 未覆盖并单列：Android AI 来源与队列（F03/F04）、首页与通知的当前账本待处理角标（F05）、批量图片旧入口、来源页的整个浏览器进程重启（本轮覆盖队列侧的浏览器进程重启）、完整主题/字号/读屏组合、真实服务部署与主应用升级。
- `AITextRecognitionSheet.vue` / `AIImageRecognitionSheet.vue` 迁移后已无引用（全局注册仍在 `mobile-main.ts`）：本轮保留未删除，待 Android 接续或专门清理时统一处理。
- 下一项按计划为 F03 Android AI 持久存储与同步（F05 角标可在其后接续）。
