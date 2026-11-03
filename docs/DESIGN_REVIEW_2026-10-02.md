# Finexy 当前设计分析与后续开发建议

评审日期：2026-10-02。范围：当前工作树中的 Go、Vue Web/PWA、Electron、OCR 与原生 Android，以及项目设计基线和交付文档。

本次是源码与文档审计，未运行应用、构建、测试、ADB 或部署，也未检查真实账本和当前线上版本。源码中存在多项未提交及未跟踪成果，本报告将其视为当前实现，不等同于公开发布版本。历史验收记录只作为已有证据入口，不代表本次重新验收。下面明确区分源码事实、静态推断和建议。

## 1. 总体判断

Finexy 已有可继续演进的财务与同步底座，下一阶段应优先完成金额正确性、账本范围、失败恢复和发布质量，再扩展预算、报告与智能能力。

产品定位建议继续保持“以账本为中心的自托管财务管理”：日常记账是核心，导入降低整理成本，共享服务共同记账，AI/OCR 帮助生成可确认的草稿。珊瑚橙与中性表面、桌面工作台与 Android 五项底栏都已有明确规范，适合持续完善。

目前最明显的问题是：功能推进速度快于跨端合同和验收收敛。部分功能已经接入，仍有错误状态、币种、账本归属与发布门禁的缺口；部分旧文档则继续把已实现操作列为待开发。

## 2. 当前架构与设计长处

| 层面 | 当前实现 | 应继续保留的设计 |
| --- | --- | --- |
| Go 服务端 | API、服务、数据模型及独立事务专项；共享账本成员和所有者分片 | 模块化服务边界、财务事务、权限复核与引用保护 |
| 桌面 Web | Vue 工作台、全局账本切换、统一账单入口、账户卡面、独立 AI/Agent 设置 | 高密度信息展示、明确的账本上下文、显式映射与用户确认 |
| 移动 Web/PWA | Framework7 页面；账本和存钱目标路由已存在 | 手机独立交互；补齐同一业务语义与实际账本范围 |
| Android | Compose、Room v21、Repository Flow、WorkManager、迁移链及备份兼容代码 | 离线持久化、稳定 localId、上传快照保护、现有深浅主题 |
| Agent | API 白名单和 MCP 分权；令牌绑定账本；导入预览、映射、确认及事务回滚 | 最小权限、真实成员权限、预览 hash 和同令牌确认 |
| AI/OCR | 可确认识别流程、AI 用量计量、独立 OCR 服务 | 确认前不入账、用量记录只保存计量元数据、识别失败可转手工 |
| 交付 | Web/Go CI、发布冷启动和部分隔离 Agent 验收、专题交付记录 | 保留专项证据，继续核对实际入口、版本与可回退产物 |

这些是源码中已有的能力或测试实现，不是本次测试通过的声明。暂时没有证据支持为解决当前问题拆分微服务；应先统一跨端合同、页面状态与交付流程。

证据入口：[设计基线](UI_UX_DESIGN.md)、[Agent 权限](../pkg/middlewares/agent_permissions.go)、[导入事务](../pkg/services/agent_imports.go)、[Android 账本分区](../android/app/src/main/java/com/finexy/mobile/data/LedgerScope.kt)、[同步引擎](../android/app/src/main/java/com/finexy/mobile/data/SyncEngine.kt)、[流水 Repository](../android/app/src/main/java/com/finexy/mobile/data/TransactionRepository.kt)。

## 3. 近期应处理的问题

P0 表示应优先修复并验证的金额、写入目标或可靠性风险；P1 表示近期可信度、权限、安全及交付缺口；P2 表示核心风险收敛后的体验和维护优化。静态推断仍需隔离复现，不能写成用户已经遭遇的事故。

### 3.1 P0：统一多币种与财务汇总口径

源码事实：桌面首页按收入/支出类型直接累加原始 `sourceAmount`，再显示为用户默认币种；工作台月度汇总存在相同路径。Android 总览也直接累加流水，跨币种转账计算目标金额与来源金额之差，格式化函数固定使用 `¥`。

多币种金额不能在没有折算的情况下直接相加。Android 当前标签是“累计收入/累计支出”，并没有把累计误写为本月；月度总览是需要另行落地的设计目标。

优化：建立同一汇总合同，明确账本、时间范围、时区、币种、退款、余额调整、内部转账和目标资金的口径。默认按币种展示；提供折合总额时标明汇率来源、报价时间和未折算部分。账户余额以账户合同为准。业务金额继续使用整数最小货币单位。

验收：用合成 CNY/USD 数据覆盖跨月、退款、期初余额、余额调整和跨币种转账；Web、Android、报告与导出结果一致。缺少汇率时不展示完整折合总额。

证据：[桌面首页](../src/views/desktop/HomePage.vue) 397–403 行；[工作台](../src/views/desktop/FinexyWorkspacePage.vue) 3104–3115、3779–3783 行；[Android 计算](../android/app/src/main/java/com/finexy/mobile/MainActivity.kt) 401–409 行；[Android 格式化](../android/app/src/main/java/com/finexy/mobile/FinexyScreens.kt) 70 行。

### 3.2 P0：移动 Web 的切换必须传递到读写链路

源码事实：移动账本管理页浏览账本和接受邀请会直接调用全局 `select`；核心总览、账户、流水与保存入口仍有未传递 `ledgerId` 的路径，账户 Store 的缺省范围为 `0`。

风险：用户的全局选择与实际读取或写入目标可能不一致。本次没有运行浏览器复现，不宣称已经发生错账。

优化：以同一账本上下文贯穿查询、保存、筛选、导入、草稿与缓存。浏览详情只改变正在查看的对象；明确点击切换才改变正在使用的账本。接受邀请后提供切换操作。合同尚未接通时，应限制入口并说明能力范围。

验收：A→B→A 连续切换、浏览详情、接受邀请、创建/编辑流水与导入全部核对实际目标账本；旧请求不覆盖新选择，草稿不自动迁移。

证据：[移动账本管理](../src/views/mobile/ledgers/ListPage.vue) 89–99 行；[移动流水列表](../src/views/mobile/transactions/ListPage.vue) 1022 行附近；[移动流水编辑](../src/views/mobile/transactions/EditPage.vue) 1109 行附近；[账户 Store](../src/stores/account.ts) 775 行附近。

### 3.3 P0：Android 非默认账本新增的幂等标识应持久稳定

源码事实：新增保存每次生成新 UUID；非默认账本先提交，再执行全量同步；两者共用异常分支。提交成功而回拉失败时，表单显示保存失败，重试会生成新标识。

静态推断：这可能导致重复入账。默认个人账本的 Repository/WorkManager 链路已有稳定 localId 机制，这个问题需要针对旁路在线提交处理。

优化：草稿创建时分配并持久化 ID，绑定原账本，整个重试周期保持相同幂等键。分开“写入失败”“写入成功、刷新失败”“写入结果待确认”；成功后的刷新失败只重试读取。

验收：隔离服务注入“POST 成功、后续 GET 失败”，再点击重试，服务器只存在一笔；补响应丢失与进程重启恢复，核对余额和记录数量。

证据：[Android 主流程](../android/app/src/main/java/com/finexy/mobile/MainActivity.kt) 496–543 行。

### 3.4 P0：Android 空分类应进入可恢复状态

源码事实：非默认账本录入页以远端末级分类构造列表，并直接调用 `availableCategories.first()`；权限读取成功后，分类仍可能处于加载中。

静态推断：无分类或分类尚未到达时，新建录入可能抛出 `NoSuchElementException`。本次未运行设备复现。

优化：分类加载中、真实为空、失败、权限不足分别建模；不从空列表初始化选中项。需要分类的类型提供恢复操作，余额调整不依赖分类。

验收：空账本、分类延迟、断网、403、失败后重试均不崩溃；提交入口正确禁用并说明原因。

证据：[Android 录入页](../android/app/src/main/java/com/finexy/mobile/FinexyScreens.kt) 404–416 行；[分类读取](../android/app/src/main/java/com/finexy/mobile/MainActivity.kt) 379–399 行。

### 3.5 P1：账号级功能与账本级功能要明确区分

源码事实：预算模型/查询以用户和月份为范围；周期模板、周期待确认及 AI 待复核尚无完整 `ledgerId` 合同。Web 非默认账本首页停止读预算，但预算缓存与卡片仍可能展示；不支持账本筛选的待办被置零并显示队列已清空。周期和设置入口仍有账号级操作，范围说明不足。

Android 还以 `selectedLedgerId != 0` 判断 `familyLedgerSelected`，导致所有非默认账本按同一受限模式处理，即使只有自己且是所有者，也禁用部分账户管理。

近期优化：对未支持的功能明确标“账号预算/账号待办”，或在不适用账本限制入口并解释；清理不匹配缓存。权限依实际成员角色判断；客户端能力不足应说明当前版本限制。

后续开发：先补服务端账本字段、所有者分片、成员权限与确认事务，再接 UI。旧数据兼容归入默认账本 `0`，补 Android migration 和备份兼容。预算、周期与 AI 队列可以分批迁移。

验收：个人 A/B、共享 C、四角色、移除/退出、离线与未上传草稿矩阵；预览后降权禁止确认；不把账号队列误称当前账本队列。当前源码不能据此被描述为服务端已经向共享账本串写。

证据：[预算模型](../pkg/models/monthly_budget.go) 16–47 行；[预算查询](../pkg/services/monthly_budgets.go) 39 行；[周期确认](../pkg/services/scheduled_occurrences.go) 118 行附近；[AI 待办模型](../pkg/models/ai_review_item.go) 21–33 行；[首页预算与待办](../src/views/desktop/HomePage.vue) 149、368、395、684 行附近；[Android 能力判断](../android/app/src/main/java/com/finexy/mobile/MainActivity.kt) 227、546–566 行。

### 3.6 P1：生产页面去掉模拟状态，失败不能当作空数据

源码事实：账号/应用设置配置仍包含固定同步时间、可信设备数、存储占用、备份完成时间和旧版本状态。账本 Store 存在缺 `result` 时当空列表，以及把多个 HTTP 状态合并为不支持的路径；部分页面只有短 Toast，没有持续错误和重试。

优化：运行指标只使用真实来源，标明更新时间；没有能力获取时显示未知或删去该指标。模块状态统一为加载、有效数据、真实空态、失败、失去访问及暂不支持。错误不能使用成功图标；无效响应不能清空已知有效上下文。

验收：断网、缺 result、403、删除账本、服务不可达时，各模块不显示虚假成功或零值；错误持续可见且可重试，最后有效数据有过期说明。

证据：[工作台设置指标](../src/views/desktop/FinexyWorkspacePage.vue) 2764–2775、2883–2894 行；[账本 Store](../src/stores/ledger.ts) 39–47 行；工作台错误处理与 Toast 在 4088、2352 行附近。

### 3.7 P1：AI 日志与 Electron 来源校验需要收口

源码事实：AI 计量表只存元数据，但模型 adapter 存在完整请求正文的 Debug 日志，公共 provider 也可输出完整响应。默认 info 不代表已经泄露；风险条件是启用相应调试日志。

Electron 已启用 sandbox/contextIsolation，但导航允许条件使用 `url.startsWith(serverUrl)`。审计中的无网络字符串验证表明，包含 URL 用户信息的目标可满足前缀比较而拥有不同 origin。应解析 URL 精确比较 origin，限定离线文件、外部协议及 IPC 发起来源。

验收：隔离 Debug 服务用合成唯一标记覆盖成功、错误与解析失败，日志不出现提示、图片、账单、响应或密钥；桌面测试覆盖正常同源、伪装用户名 URL、前缀域名、任意 file 和不可信 IPC。无需读取真实隐私内容验证。

证据：[AI 请求日志](../pkg/llm/provider/openai/openai_common_compatible_large_language_model_adapter.go) 235 行；[AI 响应日志](../pkg/llm/provider/common/common_http_large_language_model_provider.go) 71–72 行；[桌面导航](../desktop/main.cjs) 153–159 行。

### 3.8 P1：报告、筛选和导出使用同一查询合同

源码事实：工作台报表的日期选择影响趋势与导出，支出构成仍固定本月；路由部分筛选参数未被该页面统一消费。月度 CSV 直接输出最小货币单位，列名未说明单位和币种，也缺导出确认。

优化：定义统一 ReportQuery，包含账本、日期、时区、类型、账户、分类、标签及币种。图表、指标、构成、列表和导出共同使用；报告数字下钻保持相同条件。若页面明确保留“本月摘要＋长期趋势”两种范围，应分别标清，不能让筛选器暗示整页同范围。

验收：五维筛选、深链接、退款/转账/调整、完整年月及币种正确；导出前说明范围、行数、单位，混合币种分别展示；失败保留筛选。

证据：[桌面路由](../src/router/desktop.ts) 115 行附近；[报表实现](../src/views/desktop/FinexyWorkspacePage.vue) 674–732、3692–3748、5316–5329 行。

### 3.9 P1：发布门禁与实际交付绑定

源码事实：Web/Go CI 已有构建、lint、测试及 vet；镜像发布另有浏览器冷启动与 Agent 隔离验收。但发布任务没有显式依赖同提交 CI 成功，镜像构建启用 `SKIP_TESTS=true`；MCP 管理和 AI 设置专项尚未完整进入发布链路。Android workflow 仅构建并上传 debug APK，release 签名配置和版本化 Room schema 产物仍需补齐。

优化：建立可复用质量 workflow，发布依赖同一提交的结果；显式核验 CGO 专项入选，按变更范围运行事务、Agent、管理和浏览器验收。Android 加入 lint、迁移/备份兼容、关键 instrumentation、独立 CI 测试 AVD、隔离服务与进程恢复。签名密钥从 Secret 注入，验证 release APK/AAB 原位升级。

验收：人为引入类型、Go、事务或管理失败时发布被阻止；报告保留版本、commit、产物摘要和通过/跳过清单；主账本 AVD 不参加破坏性测试。现有 Ubuntu Go CI 可能已运行 CGO，不能写成“SQLite 测试从未进 CI”。

证据：[基础 CI](../.github/workflows/ci.yml)、[镜像发布](../.github/workflows/publish-main-image-temporary.yml) 57、72–78 行；[Android CI](../.github/workflows/android.yml) 25–26 行；[Android 构建](../android/app/build.gradle.kts) 21–25 行；[Room 配置](../android/app/src/main/java/com/finexy/mobile/data/FinexyDatabase.kt) 630 行。

## 4. 体验与架构优化方案

### 4.1 视觉与信息层级

保留现有品牌及用户指定的账户卡面定制。首页优先呈现当前账本、时间与币种，其次是收支结果和待处理任务，再展示近期流水、预算与目标。钱包是账户入口，材质与光泽是可选体验；继续支持减少动态效果、金额显隐与键盘操作。

单账户卡片适合查看和定制，账户较多时比较成本可能较高。这是待用户验证的体验假设，建议增加紧凑账户列表/快速选择提案，不直接替换当前卡面。先验证寻找指定账户、比较余额和进入详情的任务，再决定是否需要双视图。

移动 Web 仍保留紫色主色及旧玻璃视觉样式，应按现有规范收敛为珊瑚与语义 token。桌面浅色仍是生产基线；完整 Web 深色可单独规划，避免局部组件形成第二套主题。

证据：[移动主题](../src/MobileApp.vue) 48 行；[移动全局样式](../src/styles/mobile/global.scss) 980 行附近；[账户卡面交付](ACCOUNT_CARD_DESIGNER.md)、[首页比例交付](HOME_PROPORTIONS.md)。

### 4.2 断点、无障碍与可恢复交互

初始入口当前主要依据 UA，平板与窄桌面未必进入移动体验。应选择并实现统一策略：紧凑桌面布局，或可保留深链接、草稿及账本的移动入口切换。不能仅把桌面容器缩成仍超宽的工作台。

阶段 E 应完整覆盖 TalkBack 听读、最大字体、横屏、IME、安全区、隐藏金额语义、按钮目标与实际指纹成功路径。现有 Android 深浅主题、640dp 内容限制和大字体单列应保留；同步角标、失败摘要及重试应进入总览和流水，与设置共用状态。

Web 验收至少覆盖项目规定的 375/768/1024/1440px、100%/125% 缩放、键盘、减少动态效果与 44px 目标；Android 覆盖窄屏/横屏/最大字体/深浅主题和实测 48dp 目标。键盘焦点不应被固定头尾或弹层完全遮挡，参照 [W3C 焦点可见范围](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html)。Android 的语义与辅助技术检查参照 [Compose 无障碍文档](https://developer.android.com/develop/ui/compose/accessibility)，窗口变化适配参照 [Android 自适应布局](https://developer.android.com/develop/ui/compose/layouts/adaptive/get-started-with-adaptive-apps)。

证据：[入口分流](../src/index-main.ts)、[Android 页面](../android/app/src/main/java/com/finexy/mobile/FinexyScreens.kt)、[设计验收基线](UI_UX_DESIGN.md)。本次没有视觉或读屏实测。

### 4.3 渐进拆分页面和状态层

桌面工作台约 9,800 行，包含多个业务域、数据加载、操作状态与大量局部样式。Android 主 Activity 与页面集合也混合导航和业务状态，设置仍有长页面。应按功能逐步提取：公共 Shell → 账本/查询/状态模块 → 账户、流水、报告、计划、账本与设置页面。

每次迁移一条完整流程，保留既有路由、键盘行为与测试。公共组件优先涵盖 LedgerContext、AmountDisplay、AsyncState、SyncStatus、ConfirmDialog 和筛选状态；具体名称可沿用项目约定。Android 用明确导航和状态持有层代替不断增长的布尔页面开关。

提取公共模块时继续保持 AgentAccessPanel、AISettingsPanel、StatementMappingPanel 的 common chunk 约束，每次生产构建都验证实际冷启动，避免重新引入初始化循环。

### 4.4 数据规模、OCR 与安装体验

桌面历史读取目前顺序加载每页 50 条，再在客户端处理；Android 同步也有完整账本遍历。先用合成 1k/10k/50k 流水建立请求数、首屏、内存及筛选耗时基线，再决定服务器筛选/分页、列表虚拟化或带删除标记的增量同步。统计结果不应以“已加载页数”代替完整范围。

可把 LCP ≤2.5s、INP ≤200ms、CLS ≤0.1 作为 Web 体验目标；这些是官方建议的第 75 百分位指标，本次没有测量，实验室结果也不能冒充真实用户分布。[Web Vitals 官方说明](https://web.dev/articles/vitals)

默认 Compose 强制 AI 密钥并等待 OCR healthy，与可选能力定位存在偏差。建议提供独立基础配置，通过 profile/override 启用 AI/OCR；无模型、OCR 启动失败时基础记账仍可用。OCR 同步推理在 async 路由内执行，先测 1/2/4 并发的 P95、峰值内存和健康响应，再采用有界执行/排队与图片像素限制，避免盲目增加多模型 worker。

证据：[历史读取](../src/lib/workspace_transactions.ts)、[Android 同步](../android/app/src/main/java/com/finexy/mobile/data/SyncEngine.kt)、[Compose](../compose.yaml) 19、36、53 行；[OCR 路由](../ocr-service/app.py) 54 行附近。本次未做容量实测。

## 5. 后续开发计划

按里程碑交付，具体工期以负责人数量和第一轮复现结果估算。阶段之间存在依赖，不以固定日历承诺代替验收。

| 顺序 | 交付主题 | 主要开发内容 | 出口条件 |
| --- | --- | --- | --- |
| M0：建立可复现基线 | 能力矩阵与隔离验收 | 标明每端每账本支持范围；固化合成夹具；接入相关 CI；核对文档与源码差异 | 关键风险均有复现方案，已实现/已验收/原型分列 |
| M1：正确性与可信度 | 优先处理 P0/P1 | 修混币、移动账本目标、Android 幂等和空分类；去模拟状态；限制账号级功能范围；清理 AI 内容日志与桌面来源校验 | 错币、错账本、重试重复、空分类与敏感日志隔离验收通过 |
| M2：跨端账本闭环 | 统一合同与报表 | 实际成员权限/客户端能力分开；草稿绑定和选择持久化；五维报告/导出；分批增加预算、周期、AI 的账本范围 | 多账本/四角色、迁移/备份、降权与重试完整闭环 |
| M3：阶段 E 交付 | 适配、无障碍、正式发布 | 移动主题、断点、同步可见性；TalkBack/最大字体/横屏/指纹；Android 签名与升级；同提交发布质量门禁 | 签名包可保留数据升级，完整人工项有记录，冷启动与回退可验证 |
| M4：规模与增量价值 | 基线驱动优化 | 渐进拆工作台/设置；分页与同步优化；可选 OCR 配置；账本预算/月报完善；再评估通知、小组件与账户双视图 | 有性能或任务完成证据，保持财务合同与发布门禁 |

M0/M1 同期可以整理公共金额与状态模块、准备阶段 E 夹具；M2 的 UI 依赖服务端合同；M3 的 CI 建设应从 M0 开始，不能到发布前才补。组件拆分应穿插每轮功能修改，避免积累成一次全量重写。

第一轮建议拆成以下可审查任务：

1. 多币种汇总合同与 Web/Android 共用验收数据集。
2. 移动 Web 的 ledgerId 贯穿读写、草稿、导入与缓存。
3. Android 在线新增稳定草稿 ID、成功/刷新失败分离与进程恢复。
4. Android 分类空态、加载与错误恢复。
5. 账号级预算/周期/AI 范围说明、缓存隔离及模拟状态清理。
6. AI 日志脱敏、Electron origin/IPC 检查与专项验证。
7. 发布依赖同提交质量结果，Android 迁移/备份及专用 CI AVD 门禁。

下一轮优先排报表合同、跨端能力补齐和阶段 E；通知、小组件、更多卡面效果及智能分析放在核心闭环验收后。

## 6. 文档与完成定义

现有文档需要逐项核对：移动账本/目标路由已经接入；桌面账本操作 Dialog、Android 移除确认/邀请撤销/角色选择也已有实现。设计基线仍有“仅个人账本导入”等旧条目，与后续账本授权和源码不一致。Android README 对登录/同步的说明同样落后。应更新对应入口和状态，不再次开发已存在流程。

这次只新增本评审文档，不修改设计基线、应用源码或历史记录。后续实现时仍以 AGENTS.md、现行 UI/UX 基线和实际合同为约束。

每个开发任务应交付：问题与预期行为、API/数据库合同、权限及范围、状态与恢复、相关专项结果、执行和跳过项、适用平台、版本/产物及部署目标。涉及财务或同步时用无卷临时容器与随机账号；Android 使用符合项目边界的测试环境。构建成功、局部截图或 HTTP 200 都不能代替业务验收。

本次未部署任何目标，也没有替代完整浏览器、读屏、设备、性能与隔离 E2E 验收。
