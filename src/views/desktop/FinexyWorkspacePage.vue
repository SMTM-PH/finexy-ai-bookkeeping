<template>
    <div class="workspace">
        <div class="shell">
            <header class="topbar">
                <router-link class="brand" to="/"
                    ><b>F</b><span>Finexy</span></router-link
                >
                <nav class="topnav" aria-label="一级导航">
                    <router-link
                        v-for="tab in topTabs"
                        :key="tab.path"
                        :to="tab.path"
                        :class="{ active: tab.key === pageKey }"
                        >{{ tab.label }}</router-link
                    >
                </nav>
                <div class="top-actions">
                    <button
                        class="icon-btn"
                        aria-label="搜索"
                        @click="searchOpen = !searchOpen"
                    >
                        <v-icon :icon="mdiMagnify" size="17" />
                    </button>
                    <button
                        class="icon-btn"
                        aria-label="通知"
                        @click="showToast('目前没有需要立即处理的通知')"
                    >
                        <v-icon :icon="mdiBellOutline" size="17" /><i></i>
                    </button>
                    <router-link class="icon-btn" to="/about" aria-label="帮助"
                        ><v-icon :icon="mdiHelpCircleOutline" size="17"
                    /></router-link>
                    <button
                        class="profile"
                        type="button"
                        :aria-expanded="accountMenuOpen"
                        @click="accountMenuOpen = !accountMenuOpen"
                    >
                        <span>{{ currentUserInitial }}</span
                        ><b>{{ currentUserName }}<small>个人账本</small></b
                        ><v-icon :icon="mdiChevronDown" size="14" />
                    </button>
                    <transition name="drop"
                        ><div v-if="accountMenuOpen" class="account-menu">
                            <div class="account-current">
                                <span>{{ currentUserInitial }}</span>
                                <div>
                                    <small>当前账号</small
                                    ><strong>{{ currentUserName }}</strong>
                                </div>
                                <i>使用中</i>
                            </div>
                            <router-link to="/user/settings"
                                ><v-icon :icon="mdiCogOutline" size="17" /><span
                                    ><b>账号设置</b
                                    ><small>资料、安全与显示偏好</small></span
                                ></router-link
                            >
                            <button type="button" @click="switchAccount">
                                <v-icon
                                    :icon="mdiSwapHorizontal"
                                    size="17"
                                /><span
                                    ><b>切换账号</b
                                    ><small>登录另一个已有账号</small></span
                                >
                            </button>
                            <button
                                type="button"
                                :disabled="accountActionBusy"
                                @click="logoutAccount"
                            >
                                <v-icon :icon="mdiLogout" size="17" /><span
                                    ><b>{{
                                        accountActionBusy
                                            ? "正在退出"
                                            : "退出登录"
                                    }}</b
                                    ><small>结束当前账号会话</small></span
                                >
                            </button>
                        </div></transition
                    >
                </div>
            </header>

            <div class="body">
                <aside class="rail" aria-label="工具导航">
                    <div class="rail-group">
                        <router-link
                            v-for="item in toolItems"
                            :key="item.path"
                            :to="item.path"
                            :class="{ active: route.path === item.path }"
                            :aria-label="item.label"
                            :title="item.label"
                            ><v-icon :icon="item.icon" size="18"
                        /></router-link>
                    </div>
                    <div class="rail-group bottom">
                        <router-link
                            to="/app/settings"
                            :class="{ active: route.path === '/app/settings' }"
                            aria-label="应用设置"
                            title="应用设置"
                            ><v-icon :icon="mdiCogOutline" size="18"
                        /></router-link>
                        <router-link
                            to="/about"
                            :class="{ active: route.path === '/about' }"
                            aria-label="帮助与关于"
                            title="帮助与关于"
                            ><v-icon :icon="mdiInformationOutline" size="18"
                        /></router-link>
                    </div>
                </aside>

                <main>
                    <transition name="drop"
                        ><label v-if="searchOpen" class="global-search"
                            ><v-icon :icon="mdiMagnify" size="18" /><input
                                v-model.trim="query"
                                autofocus
                                :placeholder="`搜索${config.title}中的内容`" /><button
                                aria-label="关闭搜索"
                                @click="searchOpen = false"
                            >
                                <v-icon
                                    :icon="mdiClose"
                                    size="17"
                                /></button></label
                    ></transition>

                    <header class="page-head">
                        <div>
                            <p>{{ config.eyebrow }}</p>
                            <h1>{{ config.title }}</h1>
                            <span>{{ config.description }}</span>
                        </div>
                        <div>
                            <button
                                v-if="pageKey !== 'rates'"
                                class="secondary"
                                :disabled="busy"
                                @click="refresh"
                            >
                                <v-icon
                                    :icon="busy ? mdiLoading : mdiRefresh"
                                    :class="{ spin: busy }"
                                    size="16"
                                />刷新</button
                            ><button
                                class="primary"
                                :disabled="busy"
                                @click="primaryAction"
                            >
                                <v-icon :icon="primaryActionIcon" size="16" />{{
                                    primaryActionLabel
                                }}
                            </button>
                        </div>
                    </header>

                    <section class="metrics">
                        <article
                            v-for="(metric, index) in config.metrics"
                            :key="metric.label"
                            :class="{ featured: index === 0 }"
                        >
                            <span
                                >{{ metric.label
                                }}<i
                                    ><v-icon
                                        :icon="metric.icon"
                                        size="15" /></i></span
                            ><strong>{{ metric.value }}</strong
                            ><small :class="metric.tone">{{
                                metric.note
                            }}</small>
                        </article>
                    </section>

                    <section v-if="pageKey === 'activity'" class="panel">
                        <PanelHead eyebrow="TRANSACTIONS" title="全部流水"
                            ><div class="head-actions">
                                <button
                                    class="pending-filter"
                                    :class="{ active: query === '待确认' }"
                                    :disabled="!aiReviewItemsStore.items.length"
                                    @click="togglePendingFilter"
                                >
                                    待确认 {{ aiReviewItemsStore.items.length }}
                                </button>
                                <button
                                    :class="{ active: filterOpen }"
                                    @click="filterOpen = !filterOpen"
                                >
                                    <v-icon
                                        :icon="mdiFilterOutline"
                                        size="15"
                                    />筛选</button
                                ><button @click="compact = !compact">
                                    <v-icon
                                        :icon="mdiViewListOutline"
                                        size="15"
                                    />{{ compact ? "舒展" : "紧凑" }}
                                </button>
                            </div></PanelHead
                        >
                        <div v-if="filterOpen" class="filters">
                            <label
                                >类型<select v-model="typeFilter">
                                    <option value="all">全部类型</option>
                                    <option value="支出">支出</option>
                                    <option value="收入">收入</option>
                                    <option value="转账">转账</option>
                                </select></label
                            ><label
                                >账户<select v-model="accountFilter">
                                    <option value="all">全部账户</option>
                                    <option
                                        v-for="name in transactionAccounts"
                                        :key="name"
                                        :value="name"
                                    >
                                        {{ name }}
                                    </option>
                                </select></label
                            ><label
                                >月份<input
                                    v-model="monthFilter"
                                    type="month" /></label
                            ><button @click="resetFilters">清除筛选</button>
                        </div>
                        <div class="table-wrap">
                            <table>
                                <thead>
                                    <tr>
                                        <th>
                                            <input
                                                v-model="allSelected"
                                                type="checkbox"
                                                aria-label="全选"
                                                @change="toggleAll"
                                            />
                                        </th>
                                        <th>交易</th>
                                        <th>类型</th>
                                        <th>分类</th>
                                        <th>账户</th>
                                        <th>日期</th>
                                        <th>状态</th>
                                        <th class="right">金额</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <tr
                                        v-for="item in pagedTransactions"
                                        :key="`${item.title}-${item.date}`"
                                        :class="{ compact }"
                                        @click="openDetail(item)"
                                    >
                                        <td @click.stop>
                                            <input
                                                v-model="item.selected"
                                                type="checkbox"
                                            />
                                        </td>
                                        <td><ItemName :item="item" /></td>
                                        <td>{{ item.kind }}</td>
                                        <td>{{ item.category }}</td>
                                        <td>{{ item.account }}</td>
                                        <td>{{ item.date }}</td>
                                        <td><StatusPill :item="item" /></td>
                                        <td
                                            class="right amount"
                                            :class="{
                                                income: item.amount.startsWith(
                                                    '+',
                                                ),
                                            }"
                                        >
                                            {{ item.amount }}
                                        </td>
                                    </tr>
                                    <tr v-if="!pagedTransactions.length">
                                        <td class="empty-row" colspan="8">
                                            没有符合当前条件的流水
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                        <footer class="table-foot">
                            <span
                                >共 {{ filteredTransactions.length }} 笔 ·
                                已选择 {{ selectedCount }} 笔</span
                            >
                            <div>
                                <button
                                    :disabled="currentPage === 1"
                                    @click="currentPage--"
                                >
                                    上一页</button
                                ><b>{{ currentPage }} / {{ totalPages }}</b
                                ><button
                                    :disabled="currentPage === totalPages"
                                    @click="currentPage++"
                                >
                                    下一页
                                </button>
                            </div>
                        </footer>
                    </section>

                    <section
                        v-else-if="pageKey === 'manage'"
                        class="account-layout"
                    >
                        <div class="account-cards">
                            <article
                                v-for="(item, index) in accounts"
                                :key="item.title"
                                :class="[
                                    `tone-${index}`,
                                    {
                                        selected:
                                            selectedAccount?.title === item.title,
                                    },
                                ]"
                                tabindex="0"
                                @click="selectedAccount = item"
                                @keyup.enter="selectedAccount = item"
                            >
                                <i>{{ item.icon }}</i
                                ><b>{{ item.title }}</b
                                ><small>{{ item.meta }}</small
                                ><strong>{{ item.amount }}</strong
                                ><em>{{ item.status }}</em>
                            </article>
                        </div>
                        <aside v-if="selectedAccount" class="panel account-detail">
                            <PanelHead
                                eyebrow="CURRENT ACCOUNT"
                                title="账户详情"
                            />
                            <h2>{{ selectedAccount.title }}</h2>
                            <p>{{ selectedAccount.meta }}</p>
                            <div class="balance">
                                <small>可用余额</small
                                ><strong>{{ selectedAccount.amount }}</strong>
                            </div>
                            <div class="split-buttons">
                                <router-link to="/transaction/list"
                                    >查看流水</router-link
                                ><button @click="editAccount">编辑账户</button>
                            </div>
                        </aside>
                        <aside v-else class="panel account-detail empty-state">
                            <PanelHead eyebrow="CURRENT ACCOUNT" title="账户详情" />
                            <h2>还没有账户</h2>
                            <p>添加第一个真实账户后，余额和账户详情会显示在这里。</p>
                            <button class="primary" type="button" @click="primaryAction">添加账户</button>
                        </aside>
                    </section>

                    <section
                        v-else-if="pageKey === 'program'"
                        class="two-col program"
                    >
                        <div class="panel">
                            <PanelHead eyebrow="AUTOMATION" title="周期计划"
                                ><button
                                    class="text-btn"
                                    @click="toggleScheduleSort"
                                >
                                    {{
                                        scheduleAscending
                                            ? "日期正序"
                                            : "日期倒序"
                                    }}
                                </button></PanelHead
                            >
                            <article
                                v-for="item in sortedSchedules"
                                :key="item.title"
                                class="schedule-row"
                            >
                                <time
                                    ><b>{{ item.day }}</b
                                    ><small>每月</small></time
                                ><span
                                    ><b>{{ item.title }}</b
                                    ><small>{{ item.meta }}</small></span
                                ><strong>{{ item.amount }}</strong
                                ><SwitchControl
                                    :model-value="item.enabled"
                                    :label="`启用${item.title}`"
                                    @update:model-value="
                                        toggleSchedule(item, $event)
                                    "
                                />
                            </article>
                            <p v-if="!sortedSchedules.length" class="empty-copy">
                                还没有周期计划。创建计划后，执行日期会显示在日历中。
                            </p>
                        </div>
                        <div class="panel calendar">
                            <PanelHead
                                :eyebrow="calendarEyebrow"
                                title="执行日历"
                                ><div class="month-buttons">
                                    <button
                                        aria-label="上个月"
                                        @click="changeCalendarMonth(-1)"
                                    >
                                        <v-icon
                                            :icon="mdiChevronLeft"
                                        /></button
                                    ><button
                                        aria-label="下个月"
                                        @click="changeCalendarMonth(1)"
                                    >
                                        <v-icon :icon="mdiChevronRight" />
                                    </button></div
                            ></PanelHead>
                            <div class="week">
                                <span
                                    v-for="day in [
                                        '一',
                                        '二',
                                        '三',
                                        '四',
                                        '五',
                                        '六',
                                        '日',
                                    ]"
                                    :key="day"
                                    >{{ day }}</span
                                >
                            </div>
                            <div class="days">
                                <span
                                    v-for="blank in calendarLeadingDays"
                                    :key="`blank-${blank}`"
                                ></span
                                ><button
                                    v-for="day in calendarDays"
                                    :key="day"
                                    :class="{
                                        marked: scheduleDays.includes(day),
                                        today: isToday(day),
                                        selected: selectedCalendarDay === day,
                                    }"
                                    :aria-label="`${calendarDate.getMonth() + 1}月${day}日${scheduleDays.includes(day) ? '，有周期计划' : ''}`"
                                    :aria-pressed="selectedCalendarDay === day"
                                    @click="selectCalendarDay(day)"
                                >
                                    {{ day }}
                                </button>
                            </div>
                            <div class="calendar-note">
                                <i></i
                                ><span
                                    >{{
                                        scheduleDays.length
                                    }}
                                    项计划待执行</span
                                ><b>{{ scheduleTotal }}</b>
                            </div>
                            <section
                                class="calendar-selection"
                                :key="selectedCalendarLabel"
                                aria-live="polite"
                            >
                                <header>
                                    <div>
                                        <small>所选日期</small
                                        ><b>{{ selectedCalendarLabel }}</b>
                                    </div>
                                    <span
                                        >{{
                                            selectedCalendarSchedules.length
                                        }}
                                        项计划</span
                                    >
                                </header>
                                <article
                                    v-for="item in selectedCalendarSchedules"
                                    :key="item.title"
                                >
                                    <time>{{ item.day }} 日</time
                                    ><span
                                        ><b>{{ item.title }}</b
                                        ><small>{{ item.meta }}</small></span
                                    ><strong>{{ item.amount }}</strong>
                                </article>
                                <p v-if="!selectedCalendarSchedules.length">
                                    当天没有周期计划，选择带圆点的日期可查看执行内容。
                                </p>
                                <button
                                    v-if="!selectedCalendarSchedules.length"
                                    class="calendar-add"
                                    type="button"
                                    @click="primaryAction"
                                >
                                    为 {{ selectedCalendarLabel }} 新建计划
                                </button>
                            </section>
                        </div>
                    </section>

                    <section
                        v-else-if="pageKey === 'reports'"
                        class="report-layout"
                    >
                        <div class="panel">
                            <PanelHead
                                eyebrow="INCOME & EXPENSE"
                                title="收支趋势"
                                ><select v-model="reportPeriod" class="period" aria-label="选择报表日期范围">
                                    <option>近 6 个月</option>
                                    <option>今年</option>
                                    <option>近 12 个月</option>
                                </select></PanelHead
                            >
                            <div class="legend">
                                <span><i></i>收入</span><span><i></i>支出</span>
                            </div>
                            <div v-if="reportBars.length" class="chart" role="img" :aria-label="`${reportPeriod}收入与支出趋势`">
                                <div
                                    v-for="line in 5"
                                    :key="line"
                                    class="gridline"
                                    :style="{ bottom: `${line * 17}%` }"
                                ></div>
                                <div
                                    v-for="bars in reportBars"
                                    :key="bars.label"
                                    class="bar"
                                >
                                    <i
                                        :style="{ height: `${bars.income}%` }"
                                    ></i
                                    ><i
                                        :style="{ height: `${bars.expense}%` }"
                                    ></i
                                    ><span>{{ bars.label }}</span>
                                </div>
                            </div>
                            <p v-else class="empty-copy">暂无收支数据，记录流水后会自动生成趋势。</p>
                        </div>
                        <aside class="panel breakdown">
                            <PanelHead eyebrow="BREAKDOWN" title="支出构成" />
                            <div class="donut">
                                <span><b>{{ formatBookkeepingMoney(reportExpenseTotal) }}</b><small>本月总支出</small></span>
                            </div>
                            <ul>
                                <li
                                    v-for="item in expenseBreakdown"
                                    :key="item.title"
                                >
                                    <i :style="{ background: item.color }"></i
                                    ><span>{{ item.title }}</span
                                    ><b>{{ item.percent }}%</b>
                                </li>
                                <li v-if="!expenseBreakdown.length" class="breakdown-empty">
                                    暂无支出分类数据
                                </li>
                            </ul>
                        </aside>
                    </section>

                    <section
                        v-else-if="
                            pageKey === 'account' || pageKey === 'settings'
                        "
                        class="settings-layout"
                    >
                        <nav>
                            <button
                                v-for="item in settingsMenu"
                                :key="item.title"
                                type="button"
                                :class="{
                                    active: selectedSetting === item.title,
                                }"
                                :aria-current="selectedSetting === item.title ? 'page' : undefined"
                                @click="selectedSetting = item.title"
                            >
                                <i :style="{ background: item.color }">{{
                                    item.icon
                                }}</i
                                ><span
                                    ><b>{{ item.title }}</b
                                    ><small>{{ item.meta }}</small></span
                                ><v-icon :icon="mdiChevronRight" size="16" />
                            </button>
                        </nav>
                        <form @submit.prevent="saveSettings">
                            <PanelHead
                                :eyebrow="
                                    pageKey === 'account'
                                        ? 'ACCOUNT SETTINGS'
                                        : 'APP SETTINGS'
                                "
                                :title="selectedSetting"
                            />
                            <p class="form-note">{{ settingsDescription }}</p>
                            <template v-if="selectedSetting === '个人资料'"
                                ><div class="form-grid">
                                    <label
                                        >显示名称<input
                                            v-model.trim="profileName"
                                            autocomplete="name"
                                            @blur="nameTouched = true"
                                        /><small
                                            v-if="nameTouched && !profileName"
                                            >请输入显示名称</small
                                        ></label
                                    ><label
                                        >默认币种<select v-model="currency">
                                            <option value="CNY">
                                                人民币 CNY
                                            </option>
                                            <option value="USD">
                                                美元 USD
                                            </option>
                                            <option value="EUR">
                                                欧元 EUR
                                            </option>
                                        </select></label
                                    >
                                </div>
                                <label
                                    >邮箱地址<input
                                        v-model.trim="profileEmail"
                                        type="email"
                                        autocomplete="email" /></label></template
                            ><template
                                v-else-if="
                                    selectedSetting === '显示偏好' ||
                                    selectedSetting === '显示设置'
                                "
                                ><div class="setting-row">
                                    <span
                                        ><b>显示首页金额</b
                                        ><small>关闭后隐藏敏感金额</small></span
                                    ><SwitchControl
                                        v-model="showAmounts"
                                        label="显示首页金额"
                                    />
                                </div>
                                <div class="setting-row">
                                    <span
                                        ><b>界面动效</b
                                        ><small
                                            >页面和弹窗使用平滑过渡</small
                                        ></span
                                    ><SwitchControl
                                        v-model="animations"
                                        label="界面动效"
                                    /></div></template
                            ><template
                                v-else-if="selectedSetting === '通知设置'"
                                ><div class="setting-row">
                                    <span
                                        ><b>桌面通知</b
                                        ><small
                                            >预算、计划和账户状态变化时提醒我</small
                                        ></span
                                    ><SwitchControl
                                        v-model="notifications"
                                        label="桌面通知"
                                    /></div></template
                            ><template
                                v-else-if="selectedSetting === '安全设置'"
                                ><div class="setting-row">
                                    <span
                                        ><b>当前会话</b
                                        ><small
                                            >账号已在此浏览器安全登录</small
                                        ></span
                                    ><b class="income">正常</b>
                                </div>
                                <div class="split-buttons setting-actions">
                                    <button
                                        type="button"
                                        @click="switchAccount"
                                    >
                                        切换账号</button
                                    ><button
                                        type="button"
                                        @click="logoutAccount"
                                    >
                                        退出登录
                                    </button>
                                </div></template
                            ><template
                                v-else-if="selectedSetting === '基础设置'"
                                ><div class="form-grid">
                                    <label
                                        >时区<select v-model="timeZone">
                                            <option value="system">
                                                跟随系统
                                            </option>
                                            <option value="Asia/Shanghai">
                                                中国标准时间
                                            </option>
                                            <option value="UTC">
                                                协调世界时 UTC
                                            </option>
                                        </select></label
                                    ><label
                                        >默认币种<select v-model="currency">
                                            <option value="CNY">
                                                人民币 CNY
                                            </option>
                                            <option value="USD">
                                                美元 USD
                                            </option>
                                            <option value="EUR">
                                                欧元 EUR
                                            </option>
                                        </select></label
                                    >
                                </div>
                                <div class="setting-row">
                                    <span
                                        ><b>自动更新汇率</b
                                        ><small
                                            >定期同步常用货币汇率</small
                                        ></span
                                    ><SwitchControl
                                        v-model="autoUpdateRates"
                                        label="自动更新汇率"
                                    /></div></template
                            ><template
                                v-else-if="selectedSetting === '数据管理'"
                                ><div class="setting-row">
                                    <span
                                        ><b>导出当前报表</b
                                        ><small
                                            >下载可由表格软件打开的 CSV
                                            文件</small
                                        ></span
                                    ><button
                                        class="secondary"
                                        type="button"
                                        @click="downloadReport"
                                    >
                                        立即导出
                                    </button>
                                </div></template
                            ><template
                                v-else-if="selectedSetting === 'AI 自动配置'"
                                ><section
                                    class="ai-config-card"
                                    :class="{ ready: aiAutoConfigured }"
                                >
                                    <div class="ai-config-icon">
                                        <v-icon :icon="mdiRobotOutline" size="24" />
                                    </div>
                                    <div>
                                        <small>SERVER-SIDE API</small>
                                        <h3>{{ aiAutoConfigured ? "AI 服务已自动接入" : "等待检测 AI 服务" }}</h3>
                                        <p>浏览器不保存 API 密钥。Finexy 会自动使用自托管服务端已经配置的模型接口。</p>
                                    </div>
                                    <span>{{ aiAutoConfigured ? "已连接" : "未检测" }}</span>
                                </section>
                                <dl class="service-status ai-status">
                                    <div><dt>文本识别</dt><dd :class="{ income: aiTextRecognitionReady }">{{ aiTextRecognitionReady ? "可用" : "未配置" }}</dd></div>
                                    <div><dt>图片识别</dt><dd :class="{ income: aiImageRecognitionReady }">{{ aiImageRecognitionReady ? "可用" : "未配置" }}</dd></div>
                                    <div><dt>密钥存储</dt><dd>仅服务端</dd></div>
                                </dl>
                                <button class="primary save" type="button" @click="detectAIConfiguration">
                                    <v-icon :icon="mdiRobotOutline" size="16" />自动检测并配置
                                </button></template
                            ><template v-else
                                ><dl class="service-status">
                                    <div>
                                        <dt>应用服务</dt>
                                        <dd class="income">运行正常</dd>
                                    </div>
                                    <div>
                                        <dt>数据存储</dt>
                                        <dd>本地自托管</dd>
                                    </div>
                                    <div>
                                        <dt>连接状态</dt>
                                        <dd class="income">已连接</dd>
                                    </div>
                                </dl></template
                            ><button
                                v-if="
                                    ![
                                        '安全设置',
                                        '数据管理',
                                        '本地服务',
                                        'AI 自动配置',
                                    ].includes(selectedSetting)
                                "
                                class="primary save"
                                type="submit"
                                :disabled="
                                    saving ||
                                    (selectedSetting === '个人资料' &&
                                        !profileName)
                                "
                            >
                                <v-icon
                                    :icon="saving ? mdiLoading : mdiCheck"
                                    :class="{ spin: saving }"
                                    size="16"
                                />{{ saving ? "正在保存" : "保存更改" }}
                            </button>
                        </form>
                    </section>

                    <section
                        v-else-if="
                            pageKey === 'categories' || pageKey === 'tags'
                        "
                        class="panel catalog"
                        :class="`catalog-${pageKey}`"
                    >
                        <PanelHead
                            :eyebrow="
                                pageKey === 'categories'
                                    ? 'STRUCTURE'
                                    : 'LABELS'
                            "
                            :title="
                                pageKey === 'categories'
                                    ? '分类结构'
                                    : '交易标签'
                            "
                            ><label class="small-search"
                                ><v-icon :icon="mdiMagnify" size="15" /><input
                                    v-model="query"
                                    placeholder="搜索名称" /></label
                        ></PanelHead>
                        <p class="catalog-help">
                            {{
                                pageKey === "categories"
                                    ? "主分类与子分类会在新增交易时用于归类；点击卡片可查看并维护子分类。"
                                    : "标签用于跨分类标记报销、项目或场景；新增交易时可多选。"
                            }}
                        </p>
                        <div class="catalog-grid">
                            <button
                                v-for="item in catalogItems"
                                :key="item.title"
                                @click="openDetail(item)"
                            >
                                <i :style="{ background: item.color }">{{
                                    item.icon
                                }}</i
                                ><span
                                    ><b>{{ item.title }}</b
                                    ><small>{{ item.meta }}</small
                                    ><em
                                        ><i
                                            :style="{
                                                width: `${item.percent}%`,
                                                background: item.color,
                                            }"
                                        ></i></em></span
                                ><strong>{{ item.amount }}</strong
                                ><v-icon :icon="mdiChevronRight" size="18" />
                            </button>
                        </div>
                        <div v-if="!catalogItems.length" class="catalog-empty">
                            <v-icon :icon="pageKey === 'categories' ? mdiLayersOutline : mdiTagOutline" size="26" />
                            <b>{{ pageKey === "categories" ? "还没有交易分类" : "还没有交易标签" }}</b>
                            <span>{{ pageKey === "categories" ? "新增一级分类后，可继续维护它的子分类。" : "新增标签后，可在交易编辑页中多选使用。" }}</span>
                            <button type="button" @click="primaryAction">{{ pageKey === "categories" ? "新增第一个分类" : "新增第一个标签" }}</button>
                        </div>
                    </section>

                    <section
                        v-else-if="pageKey === 'templates'"
                        class="template-grid"
                    >
                        <article v-for="item in templates" :key="item.title">
                            <i :style="{ background: item.color }">{{
                                item.icon
                            }}</i
                            ><small>交易模板</small
                            ><button
                                class="template-edit"
                                type="button"
                                @click.stop="editTemplate(item)"
                            >
                                编辑
                            </button>
                            <h2>{{ item.title }}</h2>
                            <p>{{ item.meta }}</p>
                            <dl>
                                <div>
                                    <dt>默认金额</dt>
                                    <dd>{{ item.amount }}</dd>
                                </div>
                                <div>
                                    <dt>使用次数</dt>
                                    <dd>{{ item.uses }} 次</dd>
                                </div>
                            </dl>
                            <button type="button" @click="useTemplate(item)">
                                <v-icon
                                    :icon="mdiPlayOutline"
                                    size="17"
                                />使用模板
                            </button>
                        </article>
                        <div v-if="!templates.length" class="template-empty">
                            <v-icon :icon="mdiFileDocumentOutline" size="28" />
                            <b>常用模板推荐</b>
                            <span>推荐项不包含虚构金额或账户；选择后请绑定自己的真实账户并保存。</span>
                            <div class="recommended-template-list">
                                <button v-for="item in recommendedTemplates" :key="item.title" type="button" @click="createRecommendedTemplate(item)">
                                    <b>{{ item.title }}</b><small>{{ item.description }}</small>
                                </button>
                            </div>
                        </div>
                    </section>

                    <section
                        v-else-if="pageKey === 'rates'"
                        class="panel rates"
                    >
                        <PanelHead eyebrow="EXCHANGE RATES" title="常用汇率"
                            ><span class="sync"
                                ><i></i>数据已同步</span
                            ></PanelHead
                        >
                        <div class="base-rate">
                            <span>基准货币</span><b>人民币 CNY</b
                            ><small>所有换算结果均以人民币显示</small>
                        </div>
                        <table>
                            <thead>
                                <tr>
                                    <th>货币</th>
                                    <th>代码</th>
                                    <th>1 单位可兑换</th>
                                    <th>今日变化</th>
                                    <th>状态</th>
                                </tr>
                            </thead>
                            <tbody>
                                <tr v-for="item in rates" :key="item.code">
                                    <td>
                                        <span class="currency"
                                            ><i>{{ item.symbol }}</i
                                            ><b>{{ item.name }}</b></span
                                        >
                                    </td>
                                    <td>{{ item.code }}</td>
                                    <td>
                                        <strong>{{ item.rate }} CNY</strong>
                                    </td>
                                    <td
                                        :class="{
                                            income: item.change.startsWith('+'),
                                            negative:
                                                item.change.startsWith('-'),
                                        }"
                                    >
                                        {{ item.change }}
                                    </td>
                                    <td>
                                        <span class="sync"
                                            ><i></i>自动更新</span
                                        >
                                    </td>
                                </tr>
                            </tbody>
                        </table>
                        <p class="rate-help">
                            页面顶部“更新汇率”会主动拉取一次最新数据；自动更新由应用设置控制，不再提供重复的逐行刷新按钮。
                        </p>
                    </section>

                    <section
                        v-else-if="pageKey === 'assets'"
                        class="asset-layout"
                    >
                        <aside class="asset-summary">
                            <small>产品资产账面价值</small
                            ><strong>{{ assetSummary.bookValue }}</strong
                            ><span
                                >共 {{ assetSummary.count }} 件产品 ·
                                {{ assetSummary.activeCount }} 件使用中</span
                            >
                            <div class="asset-summary-grid">
                                <div>
                                    <small>购入总额</small
                                    ><b>{{ assetSummary.purchaseAmount }}</b>
                                </div>
                                <div>
                                    <small>累计折旧</small
                                    ><b>{{ assetSummary.depreciation }}</b>
                                </div>
                            </div>
                            <div class="allocation">
                                <i
                                    :style="{
                                        width: `${assetSummary.valueRatio}%`,
                                    }"
                                ></i
                                ><i></i>
                            </div>
                            <footer>
                                <span
                                    >当前价值
                                    {{ assetSummary.valueRatio }}%</span
                                ><span
                                    >已折旧
                                    {{ 100 - assetSummary.valueRatio }}%</span
                                >
                            </footer>
                        </aside>
                        <div class="panel asset-list">
                            <PanelHead
                                eyebrow="PRODUCT LIBRARY"
                                title="资产清单"
                                ><label class="asset-search"
                                    ><v-icon
                                        :icon="mdiMagnify"
                                        size="15" /><input
                                        v-model.trim="assetQuery"
                                        placeholder="搜索名称、品牌或型号" /></label
                            ></PanelHead>
                            <div class="asset-filters">
                                <button
                                    v-for="filter in assetCategoryFilters"
                                    :key="filter.value"
                                    type="button"
                                    :class="{
                                        active:
                                            assetCategoryFilter ===
                                            filter.value,
                                    }"
                                    @click="assetCategoryFilter = filter.value"
                                >
                                    {{ filter.label }}</button
                                ><select
                                    v-model="assetStatusFilter"
                                    aria-label="资产状态"
                                >
                                    <option value="all">全部状态</option>
                                    <option value="active">使用中</option>
                                    <option value="closed">已售出或处置</option>
                                </select>
                            </div>
                            <div class="asset-items">
                                <button
                                    v-for="item in filteredAssets"
                                    :key="item.id"
                                    class="asset-row"
                                    type="button"
                                    @click="openDetail(item)"
                                >
                                    <span
                                        class="asset-thumb"
                                        :class="`category-${item.category}`"
                                        ><v-icon
                                            :icon="item.visual"
                                            size="29" /></span
                                    ><span class="asset-copy"
                                        ><b>{{ item.title }}</b
                                        ><small>{{ item.meta }}</small
                                        ><em :class="item.tone"
                                            >{{ item.status }} · 持有
                                            {{ item.heldDays }} 天</em
                                        ></span
                                    ><span class="asset-value"
                                        ><small>当前账面价值</small
                                        ><strong>{{ item.amount }}</strong
                                        ><em
                                            >日均成本 {{ item.dailyCost }}</em
                                        ></span
                                    ><v-icon
                                        :icon="mdiChevronRight"
                                        size="17"
                                    />
                                </button>
                                <div
                                    v-if="!filteredAssets.length"
                                    class="asset-empty"
                                >
                                    <v-icon
                                        :icon="mdiPackageVariantClosed"
                                        size="26"
                                    /><b>{{
                                        assets.length
                                            ? "没有匹配的产品资产"
                                            : "还没有产品资产"
                                    }}</b
                                    ><span>{{
                                        assets.length
                                            ? "调整搜索或筛选条件后重试"
                                            : "添加购买过的设备或耐用品，系统会持续计算价值"
                                    }}</span
                                    ><button
                                        v-if="!assets.length"
                                        type="button"
                                        @click="primaryAction"
                                    >
                                        添加第一件资产
                                    </button>
                                </div>
                            </div>
                        </div>
                    </section>

                    <section v-else class="about-layout">
                        <div class="about-hero">
                            <i>F</i>
                            <p>FINEXY PERSONAL FINANCE</p>
                            <h2>清楚地记录，安心地生活。</h2>
                            <span
                                >基于Finexy，为个人自托管场景提供简洁可靠的财务管理体验。</span
                            ><b>版本 1.6.1</b>
                        </div>
                        <div class="panel status">
                            <PanelHead eyebrow="SYSTEM STATUS" title="运行状态"
                                ><span class="sync"
                                    ><i></i>全部正常</span
                                ></PanelHead
                            >
                            <dl>
                                <div>
                                    <dt>应用服务</dt>
                                    <dd>运行正常</dd>
                                </div>
                                <div>
                                    <dt>本地数据库</dt>
                                    <dd>已连接</dd>
                                </div>
                                <div>
                                    <dt>数据存储</dt>
                                    <dd>自托管</dd>
                                </div>
                                <div>
                                    <dt>最近备份</dt>
                                    <dd>今天 03:00</dd>
                                </div>
                            </dl>
                            <footer>
                                <button @click="openAboutInfo('privacy')">
                                    隐私与数据</button
                                ><button @click="openAboutInfo('license')">
                                    开源许可</button
                                ><button @click="checkForUpdates">
                                    检查更新
                                </button>
                            </footer>
                        </div>
                    </section>
                </main>
            </div>
        </div>

        <Teleport to="body"
            ><transition name="drawer"
                ><aside
                    v-if="detail"
                    class="drawer"
                    :class="{ 'asset-drawer': selectedAssetDetail }"
                    role="dialog"
                    aria-modal="true"
                    :aria-label="selectedAssetDetail ? '资产详情' : '详情'"
                >
                    <button
                        class="drawer-close"
                        aria-label="关闭详情"
                        @click="detail = null"
                    >
                        <v-icon :icon="mdiClose" /></button
                    ><template v-if="selectedAssetDetail"
                        ><small>ASSET DETAIL / 资产详情</small>
                        <div class="asset-detail-head">
                            <span
                                class="asset-thumb large"
                                :class="`category-${selectedAssetDetail.category}`"
                                ><v-icon
                                    :icon="
                                        assetVisual(
                                            selectedAssetDetail.category,
                                        )
                                    "
                                    size="34"
                            /></span>
                            <div>
                                <h2>{{ selectedAssetDetail.name }}</h2>
                                <p>
                                    {{
                                        assetCategoryLabel(
                                            selectedAssetDetail.category,
                                        )
                                    }}
                                    ·
                                    {{
                                        [
                                            selectedAssetDetail.brand,
                                            selectedAssetDetail.model,
                                        ]
                                            .filter(Boolean)
                                            .join(" ") || "未填写品牌型号"
                                    }}
                                </p>
                                <em
                                    :class="
                                        selectedAssetDetail.status ===
                                        ProductAssetStatus.Active
                                            ? 'success'
                                            : 'neutral'
                                    "
                                    >{{
                                        assetStatusLabel(
                                            selectedAssetDetail.status,
                                        )
                                    }}</em
                                >
                            </div>
                        </div>
                        <div class="asset-hero-values">
                            <div>
                                <small>日均成本</small
                                ><strong>{{
                                    formatAssetMoney(
                                        selectedAssetDetail.valuation
                                            .averageDailyCost,
                                    )
                                }}</strong
                                ><span>/ 天</span>
                            </div>
                            <div>
                                <small>购入价格</small
                                ><strong>{{
                                    formatAssetMoney(
                                        selectedAssetDetail.purchaseAmount,
                                    )
                                }}</strong
                                ><span
                                    >已持有
                                    {{
                                        selectedAssetDetail.valuation.heldDays
                                    }}
                                    天</span
                                >
                            </div>
                        </div>
                        <section class="asset-detail-section">
                            <h3>价值</h3>
                            <div class="asset-value-grid">
                                <div>
                                    <small>当前账面价值</small
                                    ><b>{{
                                        formatAssetMoney(
                                            selectedAssetDetail.valuation
                                                .bookValue,
                                        )
                                    }}</b>
                                </div>
                                <div>
                                    <small>预计残值</small
                                    ><b>{{
                                        formatAssetMoney(
                                            selectedAssetDetail.residualAmount,
                                        )
                                    }}</b>
                                </div>
                                <div>
                                    <small>累计折旧</small
                                    ><b>{{
                                        formatAssetMoney(
                                            selectedAssetDetail.valuation
                                                .accumulatedDepreciation,
                                        )
                                    }}</b>
                                </div>
                                <div>
                                    <small>每日折旧</small
                                    ><b>{{
                                        formatAssetMoney(
                                            selectedAssetDetail.valuation
                                                .dailyDepreciation,
                                        )
                                    }}</b>
                                </div>
                            </div>
                        </section>
                        <section class="asset-detail-section">
                            <h3>产品信息</h3>
                            <dl>
                                <div>
                                    <dt>购买时间</dt>
                                    <dd>
                                        {{
                                            formatAssetDate(
                                                selectedAssetDetail.purchaseTime,
                                            )
                                        }}
                                    </dd>
                                </div>
                                <div>
                                    <dt>预计使用寿命</dt>
                                    <dd>
                                        {{
                                            selectedAssetDetail.usefulLifeDays
                                        }}
                                        天
                                    </dd>
                                </div>
                                <div>
                                    <dt>品牌 / 型号</dt>
                                    <dd>
                                        {{
                                            [
                                                selectedAssetDetail.brand,
                                                selectedAssetDetail.model,
                                            ]
                                                .filter(Boolean)
                                                .join(" / ") || "未填写"
                                        }}
                                    </dd>
                                </div>
                                <div>
                                    <dt>备注</dt>
                                    <dd>
                                        {{
                                            selectedAssetDetail.comment ||
                                            "暂无备注"
                                        }}
                                    </dd>
                                </div>
                            </dl>
                        </section>
                        <button class="primary" @click="editDetail">
                            编辑资产信息
                        </button></template
                    ><template v-else
                        ><small>{{
                            selectedAIReviewItem
                                ? "PENDING REVIEW / 待确认"
                                : "DETAIL / 详情"
                        }}</small
                        ><i :style="{ background: detail.color }">{{
                            detail.icon
                        }}</i>
                        <h2>{{ detail.title }}</h2>
                        <p>{{ detail.meta }}</p>
                        <section v-if="selectedCategory" class="category-children">
                            <small>子分类</small>
                            <div v-if="selectedCategory.subCategories?.length">
                                <span
                                    v-for="child in selectedCategory.subCategories"
                                    :key="child.id"
                                >{{ child.name }}</span>
                            </div>
                            <p v-else>该一级分类还没有子分类。</p>
                        </section>
                        <div v-if="selectedAIReviewItem" class="review-source">
                            <small>原始内容</small>
                            <p>{{ selectedAIReviewItem.sourceText }}</p>
                            <em v-if="selectedAIReviewItem.failureReason">{{
                                selectedAIReviewItem.failureReason
                            }}</em>
                        </div>
                        <dl>
                            <div>
                                <dt>金额</dt>
                                <dd>{{ detail.amount }}</dd>
                            </div>
                            <div>
                                <dt>状态</dt>
                                <dd>{{ detail.status || "正常" }}</dd>
                            </div>
                            <div>
                                <dt>更新时间</dt>
                                <dd>{{ detail.date || "今天" }}</dd>
                            </div>
                        </dl>
                        <div v-if="selectedAIReviewItem" class="review-actions">
                            <button
                                class="secondary"
                                :disabled="busy"
                                @click="dismissReviewItem"
                            >
                                忽略</button
                            ><button
                                class="primary"
                                :disabled="busy"
                                @click="postReviewItem"
                            >
                                编辑并入账
                            </button>
                        </div>
                        <button v-else class="primary" @click="editDetail">
                            编辑详情
                        </button></template
                    >
                </aside></transition
            >
            <div v-if="detail" class="mask" @click="detail = null"></div
        ></Teleport>
        <Teleport to="body"
            ><transition name="drawer"
                ><div
                    v-if="createOpen && pageKey === 'assets'"
                    class="asset-editor-layer"
                >
                    <button
                        class="asset-editor-mask"
                        type="button"
                        aria-label="关闭资产编辑窗口"
                        @click="closeCreateEditor"
                    ></button>
                    <form
                        class="asset-editor"
                        role="dialog"
                        aria-modal="true"
                        :aria-label="editingRaw ? '编辑资产' : '添加资产'"
                        @submit.prevent="createItem"
                    >
                        <header class="asset-editor-head">
                            <button
                                type="button"
                                aria-label="返回资产清单"
                                @click="closeCreateEditor"
                            >
                                <v-icon :icon="mdiChevronLeft" size="20" />
                            </button>
                            <div>
                                <small>PRODUCT ASSET</small>
                                <h2>
                                    {{ editingRaw ? "编辑资产" : "添加资产" }}
                                </h2>
                            </div>
                            <button
                                type="button"
                                aria-label="关闭"
                                @click="closeCreateEditor"
                            >
                                <v-icon :icon="mdiClose" size="19" />
                            </button>
                        </header>
                        <div class="asset-editor-scroll">
                            <section class="asset-name-card">
                                <span
                                    class="asset-thumb"
                                    :class="`category-${assetFormCategory}`"
                                    ><v-icon
                                        :icon="assetVisual(assetFormCategory)"
                                        size="31" /></span
                                ><label
                                    ><span>物品名称 <i>*</i></span
                                    ><input
                                        v-model.trim="newName"
                                        required
                                        autofocus
                                        placeholder="请输入物品名称"
                                    /><small
                                        >填写便于识别的名称，例如“工作笔记本”</small
                                    ></label
                                >
                            </section>
                            <section class="asset-form-card">
                                <header>
                                    <span>01</span>
                                    <div>
                                        <h3>基础信息</h3>
                                        <small>用于建立资产与购入记录</small>
                                    </div>
                                </header>
                                <div class="asset-form-grid">
                                    <label class="wide"
                                        ><span>分类 <i>*</i></span
                                        ><select
                                            v-model.number="assetFormCategory"
                                            required
                                        >
                                            <option
                                                v-for="item in assetCategoryOptions"
                                                :key="item.value"
                                                :value="item.value"
                                            >
                                                {{ item.label }}
                                            </option>
                                        </select></label
                                    ><label
                                        ><span>购买价格 <i>*</i></span>
                                        <div class="amount-input">
                                            <b>¥</b
                                            ><input
                                                v-model="newAmount"
                                                type="number"
                                                min="0"
                                                step="0.01"
                                                required
                                                placeholder="请输入价格"
                                            /></div></label
                                    ><label
                                        ><span>购买日期 <i>*</i></span
                                        ><input
                                            v-model="assetPurchaseDate"
                                            type="date"
                                            required
                                    /></label>
                                </div>
                            </section>
                            <button
                                class="advanced-toggle"
                                type="button"
                                :aria-expanded="assetAdvancedOpen"
                                @click="assetAdvancedOpen = !assetAdvancedOpen"
                            >
                                <span>更多</span>
                                <div>
                                    <b>{{
                                        assetAdvancedOpen
                                            ? "收起高级设置"
                                            : "展开高级设置"
                                    }}</b
                                    ><small>品牌、型号、折旧、残值与备注</small>
                                </div>
                                <v-icon
                                    :icon="
                                        assetAdvancedOpen
                                            ? mdiChevronDown
                                            : mdiChevronRight
                                    "
                                    size="18"
                                /></button
                            ><transition name="drop"
                                ><section
                                    v-if="assetAdvancedOpen"
                                    class="asset-form-card asset-advanced"
                                >
                                    <header>
                                        <span>02</span>
                                        <div>
                                            <h3>详细配置</h3>
                                            <small>完善估值计算所需信息</small>
                                        </div>
                                    </header>
                                    <div class="asset-form-grid">
                                        <label
                                            ><span>品牌</span
                                            ><input
                                                v-model.trim="assetBrand"
                                                placeholder="例如 Apple、华为" /></label
                                        ><label
                                            ><span>型号</span
                                            ><input
                                                v-model.trim="assetModel"
                                                placeholder="例如 MateBook 14" /></label
                                        ><label class="wide"
                                            ><span
                                                >预计使用时间
                                                <small
                                                    >用于计算每日折旧</small
                                                ></span
                                            >
                                            <div class="duration-input">
                                                <input
                                                    v-model="
                                                        assetUsefulLifeValue
                                                    "
                                                    type="number"
                                                    min="1"
                                                    step="1"
                                                    required
                                                    placeholder="输入数值"
                                                /><select
                                                    v-model="
                                                        assetUsefulLifeUnit
                                                    "
                                                    aria-label="预计使用时间单位"
                                                >
                                                    <option value="year">
                                                        年
                                                    </option>
                                                    <option value="month">
                                                        月
                                                    </option>
                                                    <option value="day">
                                                        天
                                                    </option>
                                                </select>
                                            </div></label
                                        ><label class="wide"
                                            ><span
                                                >预计残值
                                                <small
                                                    >使用期满后的预计价值</small
                                                ></span
                                            >
                                            <div class="amount-input">
                                                <b>¥</b
                                                ><input
                                                    v-model="
                                                        assetResidualAmount
                                                    "
                                                    type="number"
                                                    min="0"
                                                    step="0.01"
                                                    placeholder="0.00"
                                                /></div></label
                                        ><label class="wide"
                                            ><span>备注信息</span
                                            ><textarea
                                                v-model.trim="newNote"
                                                rows="4"
                                                placeholder="记录购买渠道、保修期限或其他信息（选填）"
                                            ></textarea>
                                        </label>
                                    </div></section
                            ></transition>
                        </div>
                        <footer class="asset-editor-footer">
                            <div>
                                <small>{{
                                    newName ? "资产名称" : "尚未命名"
                                }}</small
                                ><strong>{{
                                    newName || "填写名称后保存"
                                }}</strong>
                            </div>
                            <button
                                class="asset-save"
                                type="submit"
                                :disabled="!assetFormValid || busy"
                            >
                                <v-icon
                                    :icon="busy ? mdiLoading : mdiCheck"
                                    :class="{ spin: busy }"
                                    size="17"
                                />{{
                                    busy
                                        ? "正在保存"
                                        : editingRaw
                                          ? "保存修改"
                                          : "保存资产"
                                }}
                            </button>
                        </footer>
                    </form>
                </div></transition
            ></Teleport
        >
        <transition name="drop"
            ><div
                v-if="createOpen && pageKey !== 'assets'"
                class="modal-mask"
                @click.self="closeCreateEditor"
            >
                <form class="modal" @submit.prevent="createItem">
                    <header>
                        <div>
                            <small>{{ editingRaw ? "EDIT" : "CREATE" }}</small>
                            <h2>
                                {{ editingRaw ? "编辑标签" : config.action }}
                            </h2>
                        </div>
                        <button
                            type="button"
                            aria-label="关闭"
                            @click="closeCreateEditor"
                        >
                            <v-icon :icon="mdiClose" />
                        </button>
                    </header>
                    <label
                        >名称<input
                            v-model.trim="newName"
                            required
                            autofocus
                            placeholder="输入名称" /></label
                    ><label
                        >备注<textarea
                            v-model.trim="newNote"
                            rows="3"
                            placeholder="可选"
                        ></textarea>
                    </label>
                    <footer>
                        <button type="button" @click="closeCreateEditor">
                            取消</button
                        ><button
                            class="primary"
                            type="submit"
                            :disabled="!newName || busy"
                        >
                            {{ busy ? "正在保存" : "保存" }}
                        </button>
                    </footer>
                </form>
            </div></transition
        >
        <transition name="drop"
            ><div
                v-if="aboutInfo"
                class="modal-mask"
                @click.self="aboutInfo = null"
            >
                <article class="modal info-modal">
                    <header>
                        <div>
                            <small>FINEXY</small>
                            <h2>{{ aboutInfo.title }}</h2>
                        </div>
                        <button
                            type="button"
                            aria-label="关闭"
                            @click="aboutInfo = null"
                        >
                            <v-icon :icon="mdiClose" />
                        </button>
                    </header>
                    <p
                        v-for="paragraph in aboutInfo.paragraphs"
                        :key="paragraph"
                    >
                        {{ paragraph }}
                    </p>
                    <footer>
                        <button
                            class="primary"
                            type="button"
                            @click="aboutInfo = null"
                        >
                            我知道了
                        </button>
                    </footer>
                </article>
            </div></transition
        >
        <transition name="toast"
            ><div v-if="toast" class="toast" role="status">
                <v-icon :icon="mdiCheckCircleOutline" size="17" />{{ toast }}
            </div></transition
        >
        <TransactionEditDialog
            ref="transactionEditDialog"
            :type="TransactionEditPageType.Transaction"
        />
        <TransactionEditDialog
            ref="templateEditDialog"
            :type="TransactionEditPageType.Template"
        />
        <AccountEditDialog ref="accountEditDialog" />
        <CategoryEditDialog ref="categoryEditDialog" />
    </div>
</template>

<script setup lang="ts">
import {
    computed,
    defineComponent,
    h,
    nextTick,
    onMounted,
    ref,
    useTemplateRef,
    watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { VIcon } from "vuetify/components";
import TransactionEditDialog from "@/views/desktop/transactions/list/dialogs/EditDialog.vue";
import { TransactionEditPageType } from "@/views/base/transactions/TransactionEditPageBase.ts";
import AccountEditDialog from "@/views/desktop/accounts/list/dialogs/EditDialog.vue";
import CategoryEditDialog from "@/views/desktop/categories/list/dialogs/EditDialog.vue";
import { useAccountsStore } from "@/stores/account.ts";
import { useTransactionCategoriesStore } from "@/stores/transactionCategory.ts";
import { useTransactionTagsStore } from "@/stores/transactionTag.ts";
import { useTransactionTemplatesStore } from "@/stores/transactionTemplate.ts";
import { useExchangeRatesStore } from "@/stores/exchangeRates.ts";
import { useProductAssetsStore } from "@/stores/productAsset.ts";
import { useSettingsStore } from "@/stores/setting.ts";
import { useTransactionsStore } from "@/stores/transaction.ts";
import { useAIReviewItemsStore } from "@/stores/aiReviewItem.ts";
import { useOverviewStore } from "@/stores/overview.ts";
import { useUserStore } from "@/stores/user.ts";
import { useRootStore } from "@/stores/index.ts";
import { TransactionTag } from "@/models/transaction_tag.ts";
import { TransactionCategory } from "@/models/transaction_category.ts";
import { TransactionTemplate } from "@/models/transaction_template.ts";
import { Transaction } from "@/models/transaction.ts";
import type { AIReviewItemInfoResponse } from "@/models/ai_review_item.ts";
import {
    ProductAssetCategory,
    ProductAssetStatus,
    type ProductAssetInfoResponse,
} from "@/models/product_asset.ts";
import { CategoryType } from "@/core/category.ts";
import { TemplateType } from "@/core/template.ts";
import { TransactionType } from "@/core/transaction.ts";
import { AMOUNT_FACTOR } from "@/consts/numeral.ts";
import { getCurrentUnixTime } from "@/lib/datetime.ts";
import {
    isTransactionFromAIImageRecognitionEnabled,
    isTransactionFromAITextRecognitionEnabled,
} from "@/lib/server_settings.ts";
import { useI18n } from "@/locales/helpers.ts";
import {
    mdiBellOutline,
    mdiCameraOutline,
    mdiCashMultiple,
    mdiCellphone,
    mdiCheck,
    mdiCheckCircleOutline,
    mdiChevronDown,
    mdiChevronLeft,
    mdiChevronRight,
    mdiClose,
    mdiCogOutline,
    mdiControllerClassicOutline,
    mdiCreditCardOutline,
    mdiDownloadOutline,
    mdiFileDocumentOutline,
    mdiFilterOutline,
    mdiFridgeOutline,
    mdiHelpCircleOutline,
    mdiInformationOutline,
    mdiLaptop,
    mdiLayersOutline,
    mdiLoading,
    mdiLogout,
    mdiMagnify,
    mdiPackageVariantClosed,
    mdiPlayOutline,
    mdiPlus,
    mdiRefresh,
    mdiRobotOutline,
    mdiSackPercent,
    mdiSwapHorizontal,
    mdiTablet,
    mdiTagOutline,
    mdiViewListOutline,
    mdiWeb,
} from "@mdi/js";

type PageKey =
    | "activity"
    | "manage"
    | "program"
    | "account"
    | "reports"
    | "categories"
    | "tags"
    | "templates"
    | "rates"
    | "assets"
    | "settings"
    | "about";
type Tone = "success" | "warning" | "neutral";
interface Item {
    title: string;
    meta: string;
    amount: string;
    status: string;
    tone: Tone;
    icon: string;
    color: string;
    percent?: number;
    selected?: boolean;
    kind?: string;
    category?: string | number;
    account?: string;
    date?: string;
    month?: string;
    return?: string;
    raw?: unknown;
}
interface AssetItem extends Item {
    id: string;
    category: number;
    visual: string;
    heldDays: number;
    dailyCost: string;
    raw: ProductAssetInfoResponse;
}
interface Metric {
    label: string;
    value: string;
    note: string;
    tone: "up" | "down" | "neutral";
    icon: string;
}
interface Config {
    eyebrow: string;
    title: string;
    description: string;
    action: string;
    actionIcon: string;
    metrics: Metric[];
}
interface RecommendedTemplate {
    title: string;
    description: string;
    type: number;
}

const PanelHead = defineComponent({
    props: {
        eyebrow: { type: String, required: true },
        title: { type: String, required: true },
    },
    setup(props, { slots }) {
        return () =>
            h("header", { class: "panel-head" }, [
                h("div", [
                    h("small", props.eyebrow),
                    h("h2", { class: "panel-title" }, props.title),
                ]),
                slots["default"]?.(),
            ]);
    },
});
const ItemName = defineComponent({
    props: { item: { type: Object as () => Item, required: true } },
    setup(props) {
        return () =>
            h("span", { class: "item-name" }, [
                h(
                    "i",
                    { style: { background: props.item.color } },
                    props.item.icon,
                ),
                h("span", [
                    h("b", props.item.title),
                    h("small", props.item.meta),
                ]),
            ]);
    },
});
const StatusPill = defineComponent({
    props: { item: { type: Object as () => Item, required: true } },
    setup(props) {
        return () =>
            h("em", { class: ["status-pill", props.item.tone] }, [
                h("i"),
                props.item.status,
            ]);
    },
});
const SwitchControl = defineComponent({
    props: { modelValue: Boolean, label: { type: String, required: true } },
    emits: ["update:modelValue"],
    setup(props, { emit }) {
        return () =>
            h("label", { class: "switch" }, [
                h("input", {
                    type: "checkbox",
                    checked: props.modelValue,
                    "aria-label": props.label,
                    onChange: (e: Event) =>
                        emit(
                            "update:modelValue",
                            (e.target as HTMLInputElement).checked,
                        ),
                }),
                h("i"),
            ]);
    },
});

const route = useRoute();
const router = useRouter();
type TransactionDialogType = InstanceType<typeof TransactionEditDialog>;
type AccountDialogType = InstanceType<typeof AccountEditDialog>;
type CategoryDialogType = InstanceType<typeof CategoryEditDialog>;
const transactionEditDialog = useTemplateRef<TransactionDialogType>(
    "transactionEditDialog",
);
const templateEditDialog =
    useTemplateRef<TransactionDialogType>("templateEditDialog");
const accountEditDialog =
    useTemplateRef<AccountDialogType>("accountEditDialog");
const categoryEditDialog =
    useTemplateRef<CategoryDialogType>("categoryEditDialog");
const accountsStore = useAccountsStore();
const categoriesStore = useTransactionCategoriesStore();
const tagsStore = useTransactionTagsStore();
const templatesStore = useTransactionTemplatesStore();
const exchangeRatesStore = useExchangeRatesStore();
const productAssetsStore = useProductAssetsStore();
const settingsStore = useSettingsStore();
const transactionsStore = useTransactionsStore();
const aiReviewItemsStore = useAIReviewItemsStore();
const overviewStore = useOverviewStore();
const userStore = useUserStore();
const rootStore = useRootStore();
const { formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
const topTabs = [
    { label: "总览", path: "/", key: "home" },
    { label: "流水", path: "/transaction/list", key: "activity" },
    { label: "管理", path: "/account/list", key: "manage" },
    { label: "计划", path: "/schedule/list", key: "program" },
    { label: "账户", path: "/user/settings", key: "account" },
    { label: "报表", path: "/statistics/transaction", key: "reports" },
];
const toolItems = [
    { label: "交易分类", path: "/category/list", icon: mdiLayersOutline },
    { label: "交易标签", path: "/tag/list", icon: mdiTagOutline },
    { label: "交易模板", path: "/template/list", icon: mdiFileDocumentOutline },
    { label: "汇率数据", path: "/exchange_rates", icon: mdiWeb },
    {
        label: "产品资产",
        path: "/product/assets",
        icon: mdiPackageVariantClosed,
    },
];
const pageMap: Record<string, PageKey> = {
    "/transaction/list": "activity",
    "/account/list": "manage",
    "/schedule/list": "program",
    "/user/settings": "account",
    "/statistics/transaction": "reports",
    "/insights/explorer": "reports",
    "/category/list": "categories",
    "/tag/list": "tags",
    "/template/list": "templates",
    "/exchange_rates": "rates",
    "/product/assets": "assets",
    "/app/settings": "settings",
    "/about": "about",
};
const pageKey = computed<PageKey>(() => pageMap[route.path] || "activity");
const metricIcons = [
    mdiCashMultiple,
    mdiCreditCardOutline,
    mdiSwapHorizontal,
    mdiSackPercent,
];
type RawConfig = Omit<Config, "metrics"> & {
    values: Array<[string, string, string, "up" | "down" | "neutral"]>;
};
const configs: Record<PageKey, RawConfig> = {
    activity: {
        eyebrow: "ACTIVITY / 流水",
        title: "每一笔，都清楚。",
        description: "筛选、核对并处理你的全部资金流动。",
        action: "新增交易",
        actionIcon: mdiPlus,
        values: [
            ["本月收入", "—", "正在读取账本", "neutral"],
            ["本月支出", "—", "正在读取账本", "neutral"],
            ["待确认", "—", "正在读取待处理记录", "neutral"],
            ["净收入", "—", "正在计算", "neutral"],
        ],
    },
    manage: {
        eyebrow: "MANAGE / 管理",
        title: "账户，井然有序。",
        description: "集中查看余额、收支和多币种钱包。",
        action: "添加账户",
        actionIcon: mdiPlus,
        values: [
            ["账户数量", "—", "正在读取账户", "neutral"],
            ["可见账户", "—", "正在读取账户", "neutral"],
            ["已隐藏", "—", "正在读取账户", "neutral"],
            ["账户币种", "—", "正在读取账户", "neutral"],
        ],
    },
    program: {
        eyebrow: "PROGRAM / 计划",
        title: "让固定支出自动发生。",
        description: "管理周期交易、预算和未来账单。",
        action: "新建计划",
        actionIcon: mdiPlus,
        values: [
            ["计划数量", "—", "正在读取周期计划", "neutral"],
            ["已启用", "—", "正在读取周期计划", "neutral"],
            ["已暂停", "—", "正在读取周期计划", "neutral"],
            ["最近执行日", "—", "正在读取周期计划", "neutral"],
        ],
    },
    account: {
        eyebrow: "ACCOUNT / 账户",
        title: "按照你的方式使用。",
        description: "管理个人资料、通知和安全选项。",
        action: "保存设置",
        actionIcon: mdiCheck,
        values: [
            ["账户状态", "正常", "全部服务可用", "up"],
            ["数据同步", "已完成", "今天 12:30", "up"],
            ["安全等级", "良好", "建议定期备份", "neutral"],
            ["登录设备", "2 台", "均为可信设备", "neutral"],
        ],
    },
    reports: {
        eyebrow: "REPORTS / 报表",
        title: "从数字里看见趋势。",
        description: "对比收支、分类和资产变化。",
        action: "导出报表",
        actionIcon: mdiDownloadOutline,
        values: [
            ["本月净收入", "—", "正在读取账本", "neutral"],
            ["本月收入", "—", "正在读取账本", "neutral"],
            ["本月支出", "—", "正在读取账本", "neutral"],
            ["交易笔数", "—", "正在读取流水", "neutral"],
        ],
    },
    categories: {
        eyebrow: "STRUCTURE / 分类",
        title: "让每笔钱都有归属。",
        description: "管理收支分类、预算占比和使用情况。",
        action: "新增分类",
        actionIcon: mdiPlus,
        values: [
            ["分类总数", "24 个", "18 个支出分类", "neutral"],
            ["覆盖交易", "98.4%", "12 笔未分类", "up"],
            ["预算分类", "8 个", "本月已设置", "neutral"],
            ["最高支出", "居住", "占本月 42%", "down"],
        ],
    },
    tags: {
        eyebrow: "LABELS / 标签",
        title: "用标签补充你的视角。",
        description: "跨分类组织项目、场景与报销记录。",
        action: "新增标签",
        actionIcon: mdiPlus,
        values: [
            ["标签总数", "16 个", "12 个本月使用", "neutral"],
            ["已标记", "86 笔", "占本月 72%", "up"],
            ["待报销", "¥1,280", "4 笔交易", "neutral"],
            ["项目支出", "¥3,420", "2 个活跃项目", "down"],
        ],
    },
    templates: {
        eyebrow: "TEMPLATES / 模板",
        title: "高频记录，一次设置。",
        description: "保存常用交易，减少重复输入。",
        action: "新增模板",
        actionIcon: mdiPlus,
        values: [
            ["模板数量", "12 个", "8 个本月使用", "neutral"],
            ["本月使用", "38 次", "节省约 42 分钟", "up"],
            ["支出模板", "9 个", "覆盖高频消费", "neutral"],
            ["转账模板", "3 个", "均可直接使用", "up"],
        ],
    },
    rates: {
        eyebrow: "CURRENCY / 汇率",
        title: "多币种换算，始终有数。",
        description: "查看常用币种汇率与最近变化。",
        action: "更新汇率",
        actionIcon: mdiRefresh,
        values: [
            ["基准币种", "CNY", "人民币", "neutral"],
            ["常用币种", "5 种", "全部自动更新", "up"],
            ["美元汇率", "7.1824", "今日 +0.12%", "up"],
            ["更新时间", "09:30", "数据状态正常", "up"],
        ],
    },
    assets: {
        eyebrow: "ASSETS / 资产",
        title: "产品资产，一处掌握。",
        description: "记录设备、家电和耐用品，持续了解使用成本与剩余价值。",
        action: "添加资产",
        actionIcon: mdiPlus,
        values: [
            ["当前账面价值", "—", "正在汇总产品资产", "neutral"],
            ["购入总额", "—", "正在读取购买信息", "neutral"],
            ["累计折旧", "—", "正在计算价值变化", "neutral"],
            ["日均使用成本", "—", "正在计算持有成本", "neutral"],
        ],
    },
    settings: {
        eyebrow: "SYSTEM / 设置",
        title: "系统为你而工作。",
        description: "管理应用显示、数据和本地行为。",
        action: "保存设置",
        actionIcon: mdiCheck,
        values: [
            ["运行状态", "正常", "全部服务可用", "up"],
            ["存储占用", "486 MB", "剩余空间充足", "neutral"],
            ["最近备份", "今天", "03:00 完成", "up"],
            ["应用版本", "1.6.1", "当前为最新版", "up"],
        ],
    },
    about: {
        eyebrow: "FINEXY / 关于",
        title: "简洁、清楚、由你掌控。",
        description: "查看版本、隐私原则与运行状态。",
        action: "检查更新",
        actionIcon: mdiRefresh,
        values: [
            ["应用版本", "1.6.1", "当前为最新版", "up"],
            ["运行时间", "99.9%", "过去 30 天", "up"],
            ["数据位置", "本地", "完全自托管", "neutral"],
            ["开源许可", "已公开", "可随时查看", "up"],
        ],
    },
};
const config = computed<Config>(() => {
    const source = configs[pageKey.value];
    let values = source.values;
    if (pageKey.value === "activity") {
        const month = overviewStore.transactionOverview.thisMonth;
        const income = month?.incomeAmount ?? 0;
        const expense = month?.expenseAmount ?? 0;
        values = [
            ["本月收入", formatBookkeepingMoney(income), "来自当前账本", "up"],
            ["本月支出", formatBookkeepingMoney(expense), "来自当前账本", "down"],
            ["待确认", `${aiReviewItemsStore.items.length} 笔`, aiReviewItemsStore.items.length ? "完成入账或忽略后自动更新" : "当前没有待处理记录", "neutral"],
            ["净收入", formatBookkeepingMoney(income - expense), "收入减去支出", income >= expense ? "up" : "down"],
        ];
    } else if (pageKey.value === "manage") {
        const list = accountsStore.allVisiblePlainAccounts;
        const currencies = new Set(list.map(item => item.currency));
        values = [
            ["账户数量", `${accountsStore.allPlainAccounts.length} 个`, "来自当前账本", "neutral"],
            ["可见账户", `${list.length} 个`, "可用于记账", "neutral"],
            ["已隐藏", `${accountsStore.allPlainAccounts.filter(item => item.hidden).length} 个`, "可在账户管理中恢复", "neutral"],
            ["账户币种", `${currencies.size} 种`, "不直接混合不同币种余额", "neutral"],
        ];
    } else if (pageKey.value === "program") {
        const list = schedules.value;
        const enabled = list.filter(item => item.enabled);
        values = [
            ["计划数量", `${list.length} 项`, "来自周期交易模板", "neutral"],
            ["已启用", `${enabled.length} 项`, "将在设定日期执行", "up"],
            ["已暂停", `${list.length - enabled.length} 项`, "不会自动执行", "neutral"],
            ["最近执行日", enabled.length ? `${Math.min(...enabled.map(item => Number(item.day)))} 日` : "暂无", "本月计划", "neutral"],
        ];
    } else if (pageKey.value === "reports") {
        const month = overviewStore.transactionOverview.thisMonth;
        const income = month?.incomeAmount ?? 0;
        const expense = month?.expenseAmount ?? 0;
        const transactionCount = transactions.value.length;
        values = [
            ["本月净收入", formatBookkeepingMoney(income - expense), "收入减去支出", income >= expense ? "up" : "down"],
            ["本月收入", formatBookkeepingMoney(income), "来自当前账本", "up"],
            ["本月支出", formatBookkeepingMoney(expense), "来自当前账本", "down"],
            ["交易笔数", `${transactionCount} 笔`, "本月已入账流水", "neutral"],
        ];
    } else if (pageKey.value === "assets" && productAssetsStore.loaded) {
        const list = productAssetsStore.assets;
        const purchase = list.reduce(
            (sum, item) => sum + item.purchaseAmount,
            0,
        );
        const book = list.reduce(
            (sum, item) => sum + item.valuation.bookValue,
            0,
        );
        const depreciation = list.reduce(
            (sum, item) => sum + item.valuation.accumulatedDepreciation,
            0,
        );
        const active = list.filter(
            (item) => item.status === ProductAssetStatus.Active,
        ).length;
        values = [
            [
                "当前账面价值",
                formatAssetMoney(book),
                `${active} 件产品使用中`,
                "up",
            ],
            [
                "购入总额",
                formatAssetMoney(purchase),
                `共 ${list.length} 件产品`,
                "neutral",
            ],
            [
                "累计折旧",
                formatAssetMoney(depreciation),
                purchase
                    ? `价值保留 ${Math.round((book * 100) / purchase)}%`
                    : "暂无折旧数据",
                "down",
            ],
            [
                "日均使用成本",
                formatAssetMoney(
                    list.reduce(
                        (sum, item) => sum + item.valuation.averageDailyCost,
                        0,
                    ),
                ),
                `按 ${active} 件使用中产品计算`,
                "neutral",
            ],
        ];
    } else if (pageKey.value === "categories") {
        const primary = Object.values(
            categoriesStore.allTransactionCategories,
        ).flat();
        const secondary = primary.flatMap((item) => item.subCategories || []);
        const visible = [...primary, ...secondary].filter((item) => item.visible);
        values = [
            ["一级分类", `${primary.length} 个`, "按收支类型组织", "neutral"],
            ["子分类", `${secondary.length} 个`, "用于交易精确归类", "neutral"],
            ["正在使用", `${visible.length} 个`, "已在交易编辑页显示", "up"],
            ["已隐藏", `${primary.length + secondary.length - visible.length} 个`, "可在编辑中重新启用", "neutral"],
        ];
    } else if (pageKey.value === "tags") {
        const list = Object.values(tagsStore.allTransactionTagsMap);
        const visible = list.filter((item) => !item.hidden).length;
        values = [
            ["标签总数", `${list.length} 个`, "来自当前账本", "neutral"],
            ["正在使用", `${visible} 个`, "可在交易中选择", "up"],
            ["已隐藏", `${list.length - visible} 个`, "不会出现在选择器", "neutral"],
            ["分组标签", `${list.filter((item) => item.groupId !== "0").length} 个`, "已归入标签组", "neutral"],
        ];
    } else if (pageKey.value === "templates") {
        const list = templatesStore.allVisibleTemplates[TemplateType.Normal.type] || [];
        values = [
            ["模板数量", `${list.length} 个`, "来自当前账本", "neutral"],
            ["可直接使用", `${list.length} 个`, "点击卡片底部即可记账", "up"],
            ["编辑入口", list.length ? "可用" : "待创建", "只对真实模板显示", "neutral"],
            ["数据状态", "已同步", "编辑后自动刷新", "up"],
        ];
    } else if (pageKey.value === "rates") {
        const count = exchangeRatesStore.latestExchangeRateMap
            ? Object.keys(exchangeRatesStore.latestExchangeRateMap).length
            : 0;
        values = [
            ["基准币种", userStore.currentUserDefaultCurrency || "CNY", "跟随账本默认币种", "neutral"],
            ["可用汇率", `${count} 种`, "统一由服务端同步", "up"],
            ["刷新入口", "1 个", "顶部更新汇率", "neutral"],
            ["自动更新", settingsStore.appSettings.autoUpdateExchangeRatesData ? "已开启" : "已关闭", "可在应用设置调整", "neutral"],
        ];
    }
    return {
        ...source,
        metrics: values.map((m, i) => ({
            label: m[0],
            value: m[1],
            note: m[2],
            tone: m[3],
            icon: metricIcons[i]!,
        })),
    };
});

const transactions = ref<Item[]>([]);
const accounts = computed<Item[]>(() => {
    const colors = ["#12141A", "#F05537", "#4F46E5"];
    const live = accountsStore.allVisiblePlainAccounts
        .slice(0, 3)
        .map((account, index) => ({
            title: account.name,
            meta: `${account.currency} · ${account.comment || "资金账户"}`,
            amount: formatAmountToLocalizedNumeralsWithCurrency(
                account.balance,
                account.currency,
            ),
            status: account.hidden ? "已隐藏" : "正常",
            tone: (account.hidden ? "warning" : "success") as Tone,
            icon: account.name.slice(0, 1),
            color: colors[index]!,
            raw: account,
        }));
    return live;
});
const selectedAccount = ref<Item | null>(null);
const schedules = computed(() => {
    const live = (
        templatesStore.allTransactionTemplates[TemplateType.Schedule.type] || []
    )
        .slice(0, 8)
        .map((item, index) => ({
            day: String(item.scheduledAt || index + 1)
                .padStart(2, "0")
                .slice(-2),
            title: item.name,
            meta: item.comment || "周期交易",
            amount: formatAmountToLocalizedNumeralsWithCurrency(
                item.sourceAmount,
                item.sourceAccount?.currency || userStore.currentUserDefaultCurrency,
            ),
            enabled: !item.hidden,
            raw: item,
        }));
    return live;
});
const categories = computed<Item[]>(() => {
    const flat = Object.values(
        categoriesStore.allTransactionCategories,
    ).flat();
    const live = flat
        .slice(0, 8)
        .map((item) => ({
            title: item.name,
            meta: item.comment || `${item.subCategories?.length || 0} 个子分类`,
            amount: item.visible ? "使用中" : "已隐藏",
            status: item.visible ? "正常" : "已隐藏",
            tone: (item.visible ? "success" : "warning") as Tone,
            icon: item.name.slice(0, 1),
            color: String(item.color),
            raw: item,
        }));
    return live;
});
const tags = computed<Item[]>(() => {
    const colors = ["#F05537", "#4F46E5", "#12141A", "#2E9BFF", "#149C63"];
    const live = Object.values(tagsStore.allTransactionTagsMap)
        .slice(0, 10)
        .map((item, index) => ({
            title: item.name,
            meta: item.groupId === "0" ? "未分组" : "标签组",
            amount: item.hidden ? "已隐藏" : "使用中",
            status: item.hidden ? "已隐藏" : "正常",
            tone: (item.hidden ? "warning" : "success") as Tone,
            icon: item.name.slice(0, 1),
            color: colors[index % colors.length]!,
            raw: item,
        }));
    return live;
});
const templates = computed(() => {
    const colors = ["#F05537", "#2E9BFF", "#149C63", "#4F46E5"];
    const live = (
        templatesStore.allVisibleTemplates[TemplateType.Normal.type] || []
    )
        .slice(0, 8)
        .map((item, index) => ({
            title: item.name,
            meta: item.comment || "交易模板",
            amount: formatAmountToLocalizedNumeralsWithCurrency(
                item.sourceAmount,
                item.sourceAccount?.currency || userStore.currentUserDefaultCurrency,
            ),
            uses: 0,
            icon: item.name.slice(0, 1),
            color: colors[index % colors.length]!,
            raw: item,
        }));
    return live;
});
const rates = computed(() => {
    const names: Record<string, [string, string]> = {
        USD: ["美元", "$"],
        EUR: ["欧元", "€"],
        GBP: ["英镑", "£"],
        JPY: ["日元", "¥"],
        HKD: ["港币", "HK$"],
    };
    const live = (
        exchangeRatesStore.latestExchangeRates.data?.exchangeRates || []
    )
        .filter((item) => names[item.currency])
        .slice(0, 5)
        .map((item) => ({
            name: names[item.currency]![0],
            code: item.currency,
            symbol: names[item.currency]![1],
            rate: item.rate,
            change: "已更新",
        }));
    return live;
});
const recommendedTemplates: RecommendedTemplate[] = [
    { title: "日常餐饮", description: "适合早餐、午餐等高频生活支出", type: TransactionType.Expense },
    { title: "通勤交通", description: "适合公交、地铁和打车支出", type: TransactionType.Expense },
    { title: "工资收入", description: "适合每月固定工资或劳务收入", type: TransactionType.Income },
    { title: "固定订阅", description: "适合软件、影音等周期订阅", type: TransactionType.Expense },
];
const assets = computed<AssetItem[]>(() =>
    productAssetsStore.assets.map((item) => ({
        id: item.id,
        title: item.name,
        meta: [assetCategoryLabel(item.category), item.brand, item.model]
            .filter(Boolean)
            .join(" · "),
        amount: formatAssetMoney(item.valuation.bookValue),
        status: assetStatusLabel(item.status),
        tone: (item.status === ProductAssetStatus.Active
            ? "success"
            : "neutral") as Tone,
        icon: item.name.slice(0, 1),
        color: "#12141A",
        category: item.category,
        visual: assetVisual(item.category),
        heldDays: item.valuation.heldDays,
        dailyCost: formatAssetMoney(item.valuation.averageDailyCost),
        raw: item,
    })),
);
const accountMenu = [
    {
        title: "个人资料",
        meta: "姓名、邮箱与地区",
        icon: "我",
        color: "#12141A",
    },
    {
        title: "显示偏好",
        meta: "币种、数字和语言",
        icon: "显",
        color: "#4F46E5",
    },
    { title: "通知设置", meta: "提醒和消息渠道", icon: "通", color: "#F05537" },
    { title: "安全设置", meta: "密码与应用锁", icon: "安", color: "#149C63" },
];
const appMenu = [
    {
        title: "基础设置",
        meta: "语言、币种与时区",
        icon: "基",
        color: "#12141A",
    },
    { title: "显示设置", meta: "金额和动效选项", icon: "显", color: "#4F46E5" },
    {
        title: "数据管理",
        meta: "备份、导入与导出",
        icon: "数",
        color: "#F05537",
    },
    { title: "本地服务", meta: "连接和存储状态", icon: "服", color: "#149C63" },
    {
        title: "AI 自动配置",
        meta: "检测并接入服务端 API",
        icon: "AI",
        color: "#7C3AED",
    },
];

const initialSearch =
    typeof route.query["search"] === "string" && route.query["search"] !== "1"
        ? route.query["search"]
        : "";
const query = ref(initialSearch);
const searchOpen = ref(route.query["search"] !== undefined);
const accountMenuOpen = ref(false);
const accountActionBusy = ref(false);
const filterOpen = ref(false);
const compact = ref(false);
const typeFilter = ref("all");
const accountFilter = ref("all");
const monthFilter = ref("");
const currentPage = ref(1);
const pageSize = 10;
const allSelected = ref(false);
const reportPeriod = ref("近 6 个月");
const selectedSetting = ref("个人资料");
const profileName = ref(userStore.currentUserNickname || "当前用户");
const profileEmail = ref(userStore.currentUserBasicInfo?.email || "");
const currency = ref(userStore.currentUserDefaultCurrency || "CNY");
const notifications = ref(
    localStorage.getItem("finexy.desktopNotifications") !== "false",
);
const showAmounts = ref(settingsStore.appSettings.showAmountInHomePage);
const animations = ref(settingsStore.appSettings.animate);
const timeZone = ref(settingsStore.appSettings.timeZone || "system");
const autoUpdateRates = ref(
    settingsStore.appSettings.autoUpdateExchangeRatesData,
);
const aiTextRecognitionReady = isTransactionFromAITextRecognitionEnabled();
const aiImageRecognitionReady = isTransactionFromAIImageRecognitionEnabled();
const aiAutoConfigured = ref(
    localStorage.getItem("finexy.aiAutoConfigured") === "true" &&
        (aiTextRecognitionReady || aiImageRecognitionReady),
);
const nameTouched = ref(false);
const saving = ref(false);
const busy = ref(false);
const detail = ref<Item | null>(null);
const createOpen = ref(false);
const editingRaw = ref<unknown>(null);
const newName = ref("");
const newAmount = ref<number | null>(null);
const newNote = ref("");
const scheduleAscending = ref(true);
const calendarDate = ref(new Date());
const selectedCalendarDay = ref(new Date().getDate());
const aboutInfo = ref<{ title: string; paragraphs: string[] } | null>(null);
const toast = ref("");
let toastTimer: ReturnType<typeof setTimeout> | undefined;
const assetQuery = ref("");
const assetDetail = ref<ProductAssetInfoResponse | null>(null);
const assetCategoryFilter = ref<number | "all">("all");
const assetStatusFilter = ref<"all" | "active" | "closed">("all");
const assetFormCategory = ref<number>(ProductAssetCategory.Other);
const assetPurchaseDate = ref(new Date().toISOString().slice(0, 10));
const assetBrand = ref("");
const assetModel = ref("");
const assetUsefulLifeValue = ref<number | null>(3);
const assetUsefulLifeUnit = ref<"year" | "month" | "day">("year");
const assetResidualAmount = ref<number | null>(0);
const assetAdvancedOpen = ref(true);
const assetCategoryOptions = [
    { label: "其他产品", value: ProductAssetCategory.Other },
    { label: "数码产品 · 手机", value: ProductAssetCategory.Phone },
    { label: "数码产品 · 电脑", value: ProductAssetCategory.Computer },
    { label: "数码产品 · 平板", value: ProductAssetCategory.Tablet },
    { label: "数码产品 · 相机", value: ProductAssetCategory.Camera },
    { label: "数码产品 · 游戏设备", value: ProductAssetCategory.GameConsole },
    { label: "家用电器", value: ProductAssetCategory.Appliance },
];
const assetCategoryFilters: Array<{ label: string; value: number | "all" }> = [
    { label: "全部类别", value: "all" },
    { label: "手机", value: ProductAssetCategory.Phone },
    { label: "电脑", value: ProductAssetCategory.Computer },
    { label: "平板", value: ProductAssetCategory.Tablet },
    { label: "相机", value: ProductAssetCategory.Camera },
    { label: "其他", value: ProductAssetCategory.Other },
];
const filteredAssets = computed(() => {
    const search = assetQuery.value.toLowerCase();
    return assets.value.filter(
        (item) =>
            (assetCategoryFilter.value === "all" ||
                item.category === assetCategoryFilter.value) &&
            (assetStatusFilter.value === "all" ||
                (assetStatusFilter.value === "active"
                    ? item.raw.status === ProductAssetStatus.Active
                    : item.raw.status !== ProductAssetStatus.Active)) &&
            (!search ||
                `${item.title}${item.meta}`.toLowerCase().includes(search)),
    );
});
const assetSummary = computed(() => {
    const list = productAssetsStore.assets;
    const purchase = list.reduce((sum, item) => sum + item.purchaseAmount, 0);
    const book = list.reduce((sum, item) => sum + item.valuation.bookValue, 0);
    const depreciation = list.reduce(
        (sum, item) => sum + item.valuation.accumulatedDepreciation,
        0,
    );
    return {
        count: list.length,
        activeCount: list.filter(
            (item) => item.status === ProductAssetStatus.Active,
        ).length,
        purchaseAmount: formatAssetMoney(purchase),
        bookValue: formatAssetMoney(book),
        depreciation: formatAssetMoney(depreciation),
        valueRatio: purchase
            ? Math.max(0, Math.min(100, Math.round((book * 100) / purchase)))
            : 0,
    };
});
const selectedAssetDetail = computed<ProductAssetInfoResponse | null>(
    () => assetDetail.value,
);
const selectedCategory = computed<TransactionCategory | null>(() =>
    pageKey.value === "categories" &&
    detail.value?.raw instanceof TransactionCategory
        ? detail.value.raw
        : null,
);
const assetFormValid = computed(
    () =>
        !!newName.value &&
        newAmount.value !== null &&
        newAmount.value >= 0 &&
        !!assetPurchaseDate.value &&
        assetUsefulLifeValue.value !== null &&
        assetUsefulLifeValue.value > 0 &&
        (assetResidualAmount.value || 0) <= (newAmount.value || 0),
);
const currentUserName = computed(
    () => userStore.currentUserNickname || "当前用户",
);
const currentUserInitial = computed(
    () => currentUserName.value.trim().slice(0, 1).toUpperCase() || "用",
);
const primaryActionLabel = computed(() =>
    selectedSetting.value === "安全设置"
        ? "切换账号"
        : selectedSetting.value === "数据管理"
          ? "导出报表"
          : selectedSetting.value === "本地服务"
            ? "刷新状态"
            : selectedSetting.value === "AI 自动配置"
              ? "自动配置"
              : config.value.action,
);
const primaryActionIcon = computed(() =>
    selectedSetting.value === "安全设置"
        ? mdiSwapHorizontal
        : selectedSetting.value === "数据管理"
          ? mdiDownloadOutline
          : selectedSetting.value === "本地服务"
            ? mdiRefresh
            : selectedSetting.value === "AI 自动配置"
              ? mdiRobotOutline
              : config.value.actionIcon,
);
const pendingTransactions = computed<Item[]>(() =>
    aiReviewItemsStore.items.map(reviewItemToItem),
);
const activityTransactions = computed(() => [
    ...pendingTransactions.value,
    ...transactions.value,
]);
const settingsMenu = computed(() =>
    pageKey.value === "account" ? accountMenu : appMenu,
);
const transactionAccounts = computed(() => [
    ...new Set(
        activityTransactions.value
            .map((item) => item.account)
            .filter((name): name is string => !!name),
    ),
]);
const filteredTransactions = computed(() =>
    activityTransactions.value.filter(
        (i) =>
            (typeFilter.value === "all" || i.kind === typeFilter.value) &&
            (accountFilter.value === "all" ||
                i.account === accountFilter.value) &&
            (!monthFilter.value || i.month === monthFilter.value) &&
            (!query.value ||
                `${i.title}${i.meta}${i.category}${i.account}`
                    .toLowerCase()
                    .includes(query.value.toLowerCase())),
    ),
);
const totalPages = computed(() =>
    Math.max(1, Math.ceil(filteredTransactions.value.length / pageSize)),
);
const pagedTransactions = computed(() =>
    filteredTransactions.value.slice(
        (currentPage.value - 1) * pageSize,
        currentPage.value * pageSize,
    ),
);
const selectedCount = computed(
    () => activityTransactions.value.filter((i) => i.selected).length,
);
const catalogItems = computed(() =>
    (pageKey.value === "tags" ? tags.value : categories.value).filter(
        (i) => !query.value || `${i.title}${i.meta}`.includes(query.value),
    ),
);
const sortedSchedules = computed(() =>
    [...schedules.value].sort(
        (a, b) =>
            (Number(a.day) - Number(b.day)) *
            (scheduleAscending.value ? 1 : -1),
    ),
);
const scheduleDays = computed(() => [
    ...new Set(
        schedules.value
            .filter((item) => item.enabled)
            .map((item) => Number(item.day))
            .filter((day) => day >= 1 && day <= calendarDays.value),
    ),
]);
const selectedCalendarSchedules = computed(() =>
    schedules.value.filter(
        (item) =>
            item.enabled && Number(item.day) === selectedCalendarDay.value,
    ),
);
const selectedCalendarLabel = computed(
    () =>
        `${calendarDate.value.getMonth() + 1}月${selectedCalendarDay.value}日`,
);
const scheduleTotal = computed(
    () => `共 ${schedules.value.length} 项周期计划`,
);
const calendarDays = computed(() =>
    new Date(
        calendarDate.value.getFullYear(),
        calendarDate.value.getMonth() + 1,
        0,
    ).getDate(),
);
const calendarLeadingDays = computed(() => {
    const day = new Date(
        calendarDate.value.getFullYear(),
        calendarDate.value.getMonth(),
        1,
    ).getDay();
    return day === 0 ? 6 : day - 1;
});
const calendarEyebrow = computed(() =>
    calendarDate.value
        .toLocaleDateString("zh-CN", { year: "numeric", month: "long" })
        .toUpperCase(),
);
const reportMonthKeys = [
    "monthBeforeLast10Months",
    "monthBeforeLast9Months",
    "monthBeforeLast8Months",
    "monthBeforeLast7Months",
    "monthBeforeLast6Months",
    "monthBeforeLast5Months",
    "monthBeforeLast4Months",
    "monthBeforeLast3Months",
    "monthBeforeLast2Months",
    "monthBeforeLastMonth",
    "lastMonth",
    "thisMonth",
] as const;
const reportDataset = computed(() => {
    const items = reportMonthKeys.map((key, index) => {
        const date = new Date();
        date.setMonth(date.getMonth() - (reportMonthKeys.length - 1 - index));
        const overview = overviewStore.transactionOverview[key];
        return {
            label: `${date.getMonth() + 1}月`,
            incomeAmount: overview?.incomeAmount ?? 0,
            expenseAmount: overview?.expenseAmount ?? 0,
        };
    });
    const maximum = Math.max(0, ...items.flatMap(item => [item.incomeAmount, item.expenseAmount]));
    return items.map(item => ({
        ...item,
        income: maximum ? Math.max(2, item.incomeAmount * 100 / maximum) : 0,
        expense: maximum ? Math.max(2, item.expenseAmount * 100 / maximum) : 0,
    }));
});
const reportBars = computed(() => {
    const all = reportDataset.value;
    const selected = reportPeriod.value === "近 6 个月"
        ? all.slice(-6)
        : reportPeriod.value === "今年"
          ? all.filter((_, index) => {
                const date = new Date();
                date.setMonth(date.getMonth() - (all.length - 1 - index));
                return date.getFullYear() === new Date().getFullYear();
            })
          : all;
    return selected.some(item => item.incomeAmount || item.expenseAmount) ? selected : [];
});
const expenseBreakdown = computed(() => {
    const totals = new Map<string, { title: string; color: string; amount: number }>();
    for (const item of transactions.value) {
        if (!(item.raw instanceof Transaction) || item.raw.type !== TransactionType.Expense) continue;
        const title = item.raw.category?.name || "未分类";
        const current = totals.get(title) || { title, color: String(item.raw.category?.color || "#9AA1AD"), amount: 0 };
        current.amount += item.raw.sourceAmount;
        totals.set(title, current);
    }
    const sum = [...totals.values()].reduce((total, item) => total + item.amount, 0);
    return [...totals.values()]
        .sort((a, b) => b.amount - a.amount)
        .slice(0, 4)
        .map(item => ({ ...item, percent: sum ? Math.round(item.amount * 100 / sum) : 0 }));
});
const reportExpenseTotal = computed(() => overviewStore.transactionOverview.thisMonth?.expenseAmount ?? 0);
const settingsDescription = computed(() => {
    if (pageKey.value === "account") return "更改会保存到当前个人账本。";
    if (selectedSetting.value === "AI 自动配置")
        return "自动读取服务端能力，不把 API 密钥保存到浏览器。";
    return "更改会保存在当前设备并立即生效。";
});
const selectedAIReviewItem = computed(() =>
    isAIReviewItem(detail.value?.raw) ? detail.value.raw : null,
);
function showToast(message: string) {
    toast.value = message;
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (toast.value = ""), 2600);
}
function showError(error: unknown) {
    const value = error as {
        message?: string;
        error?: { errorMessage?: string };
    };
    showToast(
        value?.error?.errorMessage || value?.message || "操作失败，请稍后重试",
    );
}
function formatAssetMoney(value: number) {
    return formatAmountToLocalizedNumeralsWithCurrency(
        Math.round(Number.isFinite(value) ? value : 0),
        "CNY",
    );
}
function formatBookkeepingMoney(value: number) {
    return formatAmountToLocalizedNumeralsWithCurrency(
        Math.trunc(Number.isFinite(value) ? value : 0),
        userStore.currentUserDefaultCurrency || "CNY",
    );
}
function formatAssetDate(unixTime: number) {
    return new Date(unixTime * 1000).toLocaleDateString("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
    });
}
function isAIReviewItem(value: unknown): value is AIReviewItemInfoResponse {
    return (
        !!value &&
        typeof value === "object" &&
        "sourceText" in value &&
        "createdUnixTime" in value &&
        "id" in value
    );
}
function reviewItemToItem(item: AIReviewItemInfoResponse): Item {
    const recognized = item.recognizedData;
    const kind =
        recognized?.type === TransactionType.Income
            ? "收入"
            : recognized?.type === TransactionType.Transfer
              ? "转账"
              : "支出";
    const amount = recognized?.sourceAmount
        ? formatAmountToLocalizedNumeralsWithCurrency(
              recognized.sourceAmount,
              userStore.currentUserDefaultCurrency,
          )
        : "待补充";
    const date = new Date(item.createdUnixTime * 1000);
    return {
        title:
            recognized?.comment || item.sourceText.slice(0, 24) || "待确认记录",
        meta: item.failureReason || "AI 识别结果需要人工核对",
        amount: recognized?.sourceAmount
            ? `${kind === "收入" ? "+" : "-"}${amount}`
            : amount,
        status: "待确认",
        tone: "warning",
        icon: "待",
        color: "#B54708",
        kind,
        category: "待确认",
        account: "待处理队列",
        date: date.toLocaleString("zh-CN", {
            month: "numeric",
            day: "numeric",
            hour: "2-digit",
            minute: "2-digit",
            hour12: false,
        }),
        month: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`,
        raw: item,
    };
}
function formatAssetDateInput(unixTime: number) {
    const date = new Date(unixTime * 1000);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
function assetUsefulLifeDays() {
    const value = Math.max(1, Math.round(assetUsefulLifeValue.value || 1));
    return assetUsefulLifeUnit.value === "year"
        ? value * 365
        : assetUsefulLifeUnit.value === "month"
          ? value * 30
          : value;
}
function setAssetUsefulLife(days: number) {
    if (days >= 365 && days % 365 === 0) {
        assetUsefulLifeUnit.value = "year";
        assetUsefulLifeValue.value = days / 365;
    } else if (days >= 30 && days % 30 === 0) {
        assetUsefulLifeUnit.value = "month";
        assetUsefulLifeValue.value = days / 30;
    } else {
        assetUsefulLifeUnit.value = "day";
        assetUsefulLifeValue.value = Math.max(1, days);
    }
}
function resetAssetEditor(asset?: ProductAssetInfoResponse) {
    newName.value = asset?.name || "";
    newAmount.value = asset ? asset.purchaseAmount / AMOUNT_FACTOR : null;
    newNote.value = asset?.comment || "";
    assetFormCategory.value = asset?.category || ProductAssetCategory.Other;
    assetPurchaseDate.value = asset
        ? formatAssetDateInput(asset.purchaseTime)
        : new Date().toISOString().slice(0, 10);
    assetBrand.value = asset?.brand || "";
    assetModel.value = asset?.model || "";
    assetResidualAmount.value = asset
        ? asset.residualAmount / AMOUNT_FACTOR
        : 0;
    setAssetUsefulLife(asset?.usefulLifeDays || 1095);
    assetAdvancedOpen.value = true;
}
function closeCreateEditor() {
    createOpen.value = false;
    editingRaw.value = null;
}
function assetStatusLabel(status: number) {
    if (status === ProductAssetStatus.Active) return "使用中";
    if (status === ProductAssetStatus.Sold) return "已售出";
    return "已处置";
}
function assetCategoryLabel(category: number) {
    const labels: Record<number, string> = {
        [ProductAssetCategory.Other]: "其他产品",
        [ProductAssetCategory.Phone]: "数码产品 · 手机",
        [ProductAssetCategory.Computer]: "数码产品 · 电脑",
        [ProductAssetCategory.Tablet]: "数码产品 · 平板",
        [ProductAssetCategory.Camera]: "数码产品 · 相机",
        [ProductAssetCategory.GameConsole]: "数码产品 · 游戏设备",
        [ProductAssetCategory.Appliance]: "家用电器",
    };
    return labels[category] || "其他产品";
}
function assetVisual(category: number) {
    const icons: Record<number, string> = {
        [ProductAssetCategory.Phone]: mdiCellphone,
        [ProductAssetCategory.Computer]: mdiLaptop,
        [ProductAssetCategory.Tablet]: mdiTablet,
        [ProductAssetCategory.Camera]: mdiCameraOutline,
        [ProductAssetCategory.GameConsole]: mdiControllerClassicOutline,
        [ProductAssetCategory.Appliance]: mdiFridgeOutline,
        [ProductAssetCategory.Other]: mdiPackageVariantClosed,
    };
    return icons[category] || mdiPackageVariantClosed;
}
function openQuickTransactionFromRoute() {
    if (pageKey.value !== "activity") return;
    const action = route.query["action"];
    const type =
        action === "income"
            ? TransactionType.Income
            : action === "expense"
              ? TransactionType.Expense
              : undefined;
    if (type === undefined) return;
    const nextQuery = { ...route.query };
    delete nextQuery["action"];
    void router.replace({ path: route.path, query: nextQuery });
    transactionEditDialog.value
        ?.open({ type, noTransactionDraft: true })
        .then(() => loadPageData(false))
        .catch((error) => {
            if (error) showError(error);
        });
}
function switchAccount() {
    accountMenuOpen.value = false;
    router.push({ path: "/login", query: { switch: "1" } });
}
async function logoutAccount() {
    if (accountActionBusy.value) return;
    accountActionBusy.value = true;
    try {
        await rootStore.logout();
        await router.replace("/login");
        window.location.reload();
    } catch (error) {
        accountActionBusy.value = false;
        showError(error);
    }
}
async function loadPageData(force = false) {
    busy.value = true;
    try {
        if (pageKey.value === "manage")
            await accountsStore.loadAllAccounts({ force });
        else if (pageKey.value === "categories")
            await categoriesStore.loadAllCategories({ force });
        else if (pageKey.value === "tags")
            await Promise.all([
                tagsStore.loadAllTagGroups({ force }),
                tagsStore.loadAllTags({ force }),
            ]);
        else if (pageKey.value === "templates")
            await templatesStore.loadAllTemplates({
                templateType: TemplateType.Normal.type,
                force,
            });
        else if (pageKey.value === "program")
            await templatesStore.loadAllTemplates({
                templateType: TemplateType.Schedule.type,
                force,
            });
        else if (pageKey.value === "rates")
            await exchangeRatesStore.getLatestExchangeRates({
                silent: !force,
                force,
            });
        else if (pageKey.value === "assets")
            await productAssetsStore.loadAll(force);
        else if (pageKey.value === "reports") {
            await Promise.all([
                accountsStore.loadAllAccounts({ force: false }),
                categoriesStore.loadAllCategories({ force }),
                overviewStore.loadTransactionOverview({ force, loadLast11Months: true }),
            ]);
            const now = new Date();
            const result = await transactionsStore.loadMonthlyAllTransactions({
                year: now.getFullYear(),
                month: now.getMonth() + 1,
                autoExpand: true,
                defaultCurrency: userStore.currentUserDefaultCurrency,
            });
            transactions.value = result.items.map(transactionToItem);
        }
        else if (pageKey.value === "activity") {
            await Promise.all([
                accountsStore.loadAllAccounts({ force: false }),
                categoriesStore.loadAllCategories({ force: false }),
                tagsStore.loadAllTags({ force: false }),
                templatesStore.loadAllTemplates({
                    templateType: TemplateType.Normal.type,
                    force: false,
                }),
                aiReviewItemsStore.load(),
                overviewStore.loadTransactionOverview({ force, loadLast11Months: true }),
            ]);
            const now = new Date();
            const result = await transactionsStore.loadMonthlyAllTransactions({
                year: now.getFullYear(),
                month: now.getMonth() + 1,
                autoExpand: true,
                defaultCurrency: userStore.currentUserDefaultCurrency,
            });
            transactions.value = result.items.map(transactionToItem);
        }
        if (force) showToast(`${config.value.title}已刷新`);
    } catch (error) {
        showError(error);
    } finally {
        busy.value = false;
    }
}
function transactionToItem(transaction: Transaction): Item {
    const kind =
        transaction.type === TransactionType.Income
            ? "收入"
            : transaction.type === TransactionType.Transfer
              ? "转账"
              : "支出";
    const currency =
        transaction.sourceAccount?.currency ||
        userStore.currentUserDefaultCurrency;
    const amount = formatAmountToLocalizedNumeralsWithCurrency(
        transaction.sourceAmount,
        currency,
    );
    const date = new Date(transaction.time * 1000);
    return {
        title: transaction.comment || transaction.category?.name || kind,
        meta: transaction.comment || "无备注",
        amount: `${transaction.type === TransactionType.Income ? "+" : "-"}${amount}`,
        status: transaction.editable ? "已完成" : "只读",
        tone: "success",
        icon: (transaction.category?.name || kind).slice(0, 1),
        color:
            transaction.type === TransactionType.Income
                ? "#149C63"
                : transaction.type === TransactionType.Transfer
                  ? "#4F46E5"
                  : "#F05537",
        kind,
        category: transaction.category?.name || "未分类",
        account: transaction.sourceAccount?.name || "未知账户",
        date: date.toLocaleDateString("zh-CN"),
        month: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`,
        raw: transaction,
    };
}
function refresh() {
    void loadPageData(true);
}
function resetFilters() {
    typeFilter.value = "all";
    accountFilter.value = "all";
    monthFilter.value = "";
    query.value = "";
    currentPage.value = 1;
    showToast("筛选条件已清除");
}
function togglePendingFilter() {
    if (!aiReviewItemsStore.items.length) return;
    query.value = query.value === "待确认" ? "" : "待确认";
    currentPage.value = 1;
}
function toggleAll() {
    pagedTransactions.value.forEach((i) => (i.selected = allSelected.value));
}
function openDetail(item: Item) {
    assetDetail.value =
        pageKey.value === "assets" &&
        item.raw &&
        typeof item.raw === "object" &&
        "valuation" in item.raw
            ? (item.raw as ProductAssetInfoResponse)
            : null;
    detail.value = item;
}
function toggleScheduleSort() {
    scheduleAscending.value = !scheduleAscending.value;
    showToast(`已切换为日期${scheduleAscending.value ? "正序" : "倒序"}`);
}
function changeCalendarMonth(offset: number) {
    calendarDate.value = new Date(
        calendarDate.value.getFullYear(),
        calendarDate.value.getMonth() + offset,
        1,
    );
    selectedCalendarDay.value = 1;
}
function selectCalendarDay(day: number) {
    selectedCalendarDay.value = day;
}
function isToday(day: number) {
    const today = new Date();
    return (
        today.getFullYear() === calendarDate.value.getFullYear() &&
        today.getMonth() === calendarDate.value.getMonth() &&
        today.getDate() === day
    );
}
function useTemplate(item: { title: string; uses: number; raw?: unknown }) {
    const template =
        (
            templatesStore.allVisibleTemplates[TemplateType.Normal.type] || []
        ).find((value) => value === item.raw || value.name === item.title) ||
        (templatesStore.allVisibleTemplates[TemplateType.Normal.type] || [])[0];
    transactionEditDialog.value
        ?.open({ template })
        .then(() => {
            item.uses++;
            showToast(`已使用模板：${item.title}`);
        })
        .catch((error) => {
            if (error) showError(error);
        });
}
function createRecommendedTemplate(item: RecommendedTemplate) {
    if (!accountsStore.allVisiblePlainAccounts.length) {
        showToast("请先添加账户，再创建可用模板");
        void router.push("/account/list");
        return;
    }
    templateEditDialog.value
        ?.open({
            templateType: TemplateType.Normal.type,
            type: item.type,
            comment: item.title,
        })
        .then(() => loadPageData(false))
        .catch((error) => {
            if (error) showError(error);
        });
}
function editTemplate(item: { title: string; raw?: unknown }) {
    const template =
        item.raw instanceof TransactionTemplate
            ? item.raw
            : (
                  templatesStore.allVisibleTemplates[
                      TemplateType.Normal.type
                  ] || []
              ).find((value) => value.name === item.title);
    if (!template) {
        showToast("请先创建模板");
        return;
    }
    templateEditDialog.value
        ?.open({ id: template.id, currentTemplate: template })
        .then(() => loadPageData(false))
        .catch((error) => {
            if (error) showError(error);
        });
}
function toggleSchedule(
    item: { title: string; enabled: boolean; raw?: unknown },
    enabled: boolean,
) {
    if (item.raw instanceof TransactionTemplate) {
        busy.value = true;
        templatesStore
            .hideTemplate({ template: item.raw, hidden: !enabled })
            .then(() => {
                showToast(`${item.title}已${enabled ? "启用" : "暂停"}`);
                return loadPageData(false);
            })
            .catch(showError)
            .finally(() => (busy.value = false));
    } else {
        item.enabled = enabled;
        showToast(`${item.title}已${enabled ? "启用" : "暂停"}`);
    }
}
function primaryAction() {
    if (pageKey.value === "activity") {
        transactionEditDialog.value
            ?.open({})
            .then(() => loadPageData(false))
            .catch((error) => {
                if (error) showError(error);
            });
        return;
    }
    if (pageKey.value === "manage") {
        accountEditDialog.value
            ?.open()
            .then(() => loadPageData(false))
            .catch((error) => {
                if (error) showError(error);
            });
        return;
    }
    if (pageKey.value === "program" || pageKey.value === "templates") {
        templateEditDialog.value
            ?.open({
                templateType:
                    pageKey.value === "program"
                        ? TemplateType.Schedule.type
                        : TemplateType.Normal.type,
            })
            .then(() => loadPageData(false))
            .catch((error) => {
                if (error) showError(error);
            });
        return;
    }
    if (pageKey.value === "categories") {
        categoryEditDialog.value
            ?.open({ parentId: "0", type: CategoryType.Expense })
            .then(() => loadPageData(false))
            .catch((error) => {
                if (error) showError(error);
            });
        return;
    }
    if (pageKey.value === "tags") {
        editingRaw.value = null;
        newName.value = "";
        newAmount.value = null;
        newNote.value = "";
        createOpen.value = true;
        return;
    }
    if (pageKey.value === "assets") {
        editingRaw.value = null;
        resetAssetEditor();
        createOpen.value = true;
        return;
    }
    if (pageKey.value === "reports") {
        downloadReport();
        return;
    }
    if (pageKey.value === "rates") {
        void loadPageData(true);
        return;
    }
    if (pageKey.value === "account" && selectedSetting.value === "安全设置") {
        switchAccount();
        return;
    }
    if (pageKey.value === "settings" && selectedSetting.value === "数据管理") {
        downloadReport();
        return;
    }
    if (pageKey.value === "settings" && selectedSetting.value === "本地服务") {
        showToast("本地服务连接正常");
        return;
    }
    if (pageKey.value === "settings" && selectedSetting.value === "AI 自动配置") {
        detectAIConfiguration();
        return;
    }
    if (pageKey.value === "account" || pageKey.value === "settings") {
        void saveSettings();
        return;
    }
    if (pageKey.value === "about") {
        checkForUpdates();
        return;
    }
    showToast("当前已是最新版本");
}
async function createItem() {
    busy.value = true;
    try {
        if (pageKey.value === "tags") {
            const tag =
                editingRaw.value instanceof TransactionTag
                    ? editingRaw.value.clone()
                    : TransactionTag.createNewTag();
            tag.name = newName.value;
            await tagsStore.saveTag({ tag });
            showToast(editingRaw.value ? "标签已保存" : "标签已创建");
        } else if (pageKey.value === "assets") {
            const asset = editingRaw.value as ProductAssetInfoResponse | null;
            const purchaseTime =
                Math.floor(
                    new Date(`${assetPurchaseDate.value}T12:00:00`).getTime() /
                        1000,
                ) || getCurrentUnixTime();
            const common = {
                sourceTransactionId: asset?.sourceTransactionId || "",
                category: assetFormCategory.value,
                name: newName.value,
                brand: assetBrand.value,
                model: assetModel.value,
                purchaseAmount: Math.round(
                    (newAmount.value || 0) * AMOUNT_FACTOR,
                ),
                purchaseTime,
                utcOffset: -new Date(purchaseTime * 1000).getTimezoneOffset(),
                comment: newNote.value,
            };
            const usefulLifeDays = assetUsefulLifeDays();
            const residualAmount = Math.round(
                (assetResidualAmount.value || 0) * AMOUNT_FACTOR,
            );
            if (asset) {
                await productAssetsStore.modify({
                    ...common,
                    id: asset.id,
                    usefulLifeDays,
                    residualAmount,
                    clearManualMarketValue: false,
                    manualMarketValue: asset.manualMarketValue,
                });
                showToast("产品资产已保存");
            } else {
                await productAssetsStore.create({
                    ...common,
                    usefulLifeDays,
                    residualAmount,
                });
                showToast("产品资产已创建");
            }
        }
        closeCreateEditor();
        await loadPageData(false);
    } catch (error) {
        showError(error);
    } finally {
        busy.value = false;
    }
}
function editAccount() {
    if (!selectedAccount.value) {
        primaryAction();
        return;
    }
    const selectedAccountName = selectedAccount.value.title;
    const account =
        accountsStore.allVisiblePlainAccounts.find(
            (item) => item.name === selectedAccountName,
        ) || accountsStore.allVisiblePlainAccounts[0];
    if (!account) {
        primaryAction();
        return;
    }
    accountEditDialog.value
        ?.open({ id: account.id, currentAccount: account })
        .then(() => loadPageData(false))
        .catch((error) => {
            if (error) showError(error);
        });
}
function editDetail() {
    if (!detail.value) return;
    if (
        pageKey.value === "activity" &&
        detail.value.raw instanceof Transaction
    ) {
        const transaction = detail.value.raw;
        detail.value = null;
        void nextTick().then(() => transactionEditDialog.value
            ?.open({ id: transaction.id, currentTransaction: transaction })
            .then(() => {
                return loadPageData(false);
            })
            .catch((error) => {
                if (error) showError(error);
            }));
        return;
    }
    if (pageKey.value === "categories") {
        const category = detail.value.raw instanceof TransactionCategory
            ? detail.value.raw
            : Object.values(categoriesStore.allTransactionCategoriesMap).find(
                  (item) => item.name === detail.value?.title,
              );
        if (category) {
            detail.value = null;
            void nextTick().then(() => categoryEditDialog.value
                ?.open({ id: category.id, currentCategory: category })
                .then(() => {
                    return loadPageData(false);
                })
                .catch((error) => {
                    if (error) showError(error);
                }));
        }
        return;
    }
    if (pageKey.value === "tags") {
        const tag = detail.value.raw instanceof TransactionTag
            ? detail.value.raw
            : Object.values(tagsStore.allTransactionTagsMap).find(
                  (item) => item.name === detail.value?.title,
              );
        if (tag) {
            editingRaw.value = tag;
            newName.value = tag.name;
            newAmount.value = null;
            newNote.value = "";
            detail.value = null;
            createOpen.value = true;
            return;
        }
    }
    if (pageKey.value === "assets" && detail.value.raw) {
        const asset = detail.value.raw as ProductAssetInfoResponse;
        editingRaw.value = asset;
        resetAssetEditor(asset);
        detail.value = null;
        createOpen.value = true;
        return;
    }
    showToast("该详情已是最新状态");
}

function detectAIConfiguration() {
    const ready = aiTextRecognitionReady || aiImageRecognitionReady;
    aiAutoConfigured.value = ready;
    localStorage.setItem("finexy.aiAutoConfigured", String(ready));
    showToast(
        ready
            ? "已自动接入服务端 AI API，密钥不会下发到浏览器"
            : "未检测到 AI API，请先在服务端环境变量中配置模型",
    );
}
async function dismissReviewItem() {
    const item = selectedAIReviewItem.value;
    if (!item || busy.value) return;
    busy.value = true;
    try {
        await aiReviewItemsStore.dismiss(item.id);
        detail.value = null;
        showToast("已忽略，该记录已移出待确认队列");
    } catch (error) {
        showError(error);
    } finally {
        busy.value = false;
    }
}
function postReviewItem() {
    const item = selectedAIReviewItem.value;
    if (!item || busy.value) return;
    const recognized = item.recognizedData;
    const options = recognized
        ? {
              type: recognized.type,
              time: recognized.time,
              categoryId: recognized.categoryId,
              accountId: recognized.sourceAccountId,
              destinationAccountId: recognized.destinationAccountId,
              amount: recognized.sourceAmount,
              destinationAmount: recognized.destinationAmount,
              tagIds: recognized.tagIds?.join(","),
              comment: recognized.comment,
              noTransactionDraft: true,
          }
        : { autoRecognizeText: item.sourceText, noTransactionDraft: true };
    transactionEditDialog.value
        ?.open(options)
        .then(async (response) => {
            if (!response?.transactionId) return;
            await aiReviewItemsStore.resolve(item.id);
            detail.value = null;
            await loadPageData(false);
            showToast("已入账，待确认数量已更新");
        })
        .catch((error) => {
            if (error && !(error as { canceled?: boolean }).canceled)
                showError(error);
        });
}
async function saveSettings() {
    if (saving.value) return;
    if (selectedSetting.value === "个人资料" && !profileName.value) {
        nameTouched.value = true;
        return;
    }
    saving.value = true;
    try {
        if (selectedSetting.value === "个人资料") {
            await userStore.updateUserProfile({
                nickname: profileName.value,
                email: profileEmail.value,
                defaultCurrency: currency.value,
            });
        } else if (selectedSetting.value === "通知设置") {
            localStorage.setItem(
                "finexy.desktopNotifications",
                String(notifications.value),
            );
        } else if (selectedSetting.value === "基础设置") {
            settingsStore.setTimeZone(
                timeZone.value === "system" ? "" : timeZone.value,
            );
            settingsStore.setAutoUpdateExchangeRatesData(autoUpdateRates.value);
        } else if (
            selectedSetting.value === "显示偏好" ||
            selectedSetting.value === "显示设置"
        ) {
            settingsStore.setShowAmountInHomePage(showAmounts.value);
            settingsStore.setEnableAnimate(animations.value);
        }
        showToast("设置已保存并生效");
    } catch (error) {
        showError(error);
    } finally {
        saving.value = false;
    }
}
function downloadReport() {
    const rows = reportBars.value.map(
        (item) => `${item.label},${item.incomeAmount},${item.expenseAmount}`,
    );
    const blob = new Blob([`\uFEFF月份,收入,支出\n${rows.join("\n")}`], {
        type: "text/csv;charset=utf-8",
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `Finexy-${reportPeriod.value}-收支报表.csv`;
    link.click();
    URL.revokeObjectURL(url);
    showToast(`${reportPeriod.value}报表已导出`);
}
function openAboutInfo(type: "privacy" | "license") {
    aboutInfo.value =
        type === "privacy"
            ? {
                  title: "隐私与数据",
                  paragraphs: [
                      "所有账本数据由你部署的Finexy服务保存，Finexy 页面不会把财务数据发送到第三方。",
                      "账号、备份和网络访问权限均由你的自托管环境控制。",
                  ],
              }
            : {
                  title: "开源许可",
                  paragraphs: [
                      "本应用界面基于Finexy构建，并遵循项目所采用的开源许可。",
                      "第三方组件的版权与许可信息随对应依赖包一并提供。",
                  ],
              };
}
function checkForUpdates() {
    busy.value = true;
    setTimeout(() => {
        busy.value = false;
        showToast("当前已是最新版本 1.6.1");
    }, 400);
}
onMounted(() => {
    void loadPageData(false);
    openQuickTransactionFromRoute();
});
watch(pageKey, () => {
    detail.value = null;
    assetDetail.value = null;
    query.value = "";
    currentPage.value = 1;
    selectedSetting.value =
        pageKey.value === "account" ? "个人资料" : "基础设置";
    void loadPageData(false);
});
watch(() => route.query["action"], openQuickTransactionFromRoute);
watch([typeFilter, accountFilter, monthFilter, query], () => {
    currentPage.value = 1;
});
watch(totalPages, (value) => {
    if (currentPage.value > value) currentPage.value = value;
});
watch(accounts, (value) => {
    if (!value.length) {
        selectedAccount.value = null;
    } else if (
        !selectedAccount.value ||
        !value.some((item) => item.title === selectedAccount.value?.title)
    ) {
        selectedAccount.value = value[0]!;
    }
});
</script>

<style scoped>
.workspace {
    --bg: #f1f2f6;
    --panel: #f7f8fa;
    --line: #e9ebf0;
    --ink: #12141a;
    --muted: #687181;
    --faint: #959ca8;
    --orange: #f05537;
    --green: #149c63;
    --red: #d94040;
    position: fixed;
    inset: 0;
    z-index: 1000;
    min-width: 1160px;
    overflow: auto;
    padding: 34px 92px;
    background: var(--bg);
    color: var(--ink);
    font-family: Inter, "Microsoft YaHei UI", "PingFang SC", sans-serif;
}
.workspace * {
    box-sizing: border-box;
}
.workspace button,
.workspace input,
.workspace select,
.workspace textarea {
    font: inherit;
}
.workspace button {
    border: 0;
    color: inherit;
    cursor: pointer;
}
.workspace button:disabled {
    opacity: 0.4;
    cursor: not-allowed;
}
.workspace a {
    color: inherit;
    text-decoration: none;
}
.shell {
    min-height: calc(100vh - 68px);
    padding: 16px 22px 22px;
    border-radius: 30px;
    background: #fff;
}
.topbar {
    position: relative;
    display: flex;
    height: 56px;
    align-items: center;
}
.brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 7px 14px 7px 8px;
    border-radius: 14px;
    background: var(--panel);
    font-size: 15px;
}
.brand > b,
.about-hero > i {
    display: grid;
    place-items: center;
    color: #fff;
    background: linear-gradient(135deg, #f36447, #ee4e31);
    font-style: italic;
    font-weight: 900;
}
.brand > b {
    width: 34px;
    height: 34px;
    border-radius: 11px;
    font-size: 19px;
}
.topnav {
    display: flex;
    gap: 34px;
    margin-left: 64px;
}
.topnav a {
    padding: 9px 4px;
    border-radius: 999px;
    color: var(--muted);
    font-size: 13px;
    transition: 0.18s;
}
.topnav a:hover {
    color: var(--ink);
    transform: translateY(-1px);
}
.topnav a.active {
    padding: 9px 22px;
    color: #fff;
    background: var(--ink);
    font-weight: 700;
}
.top-actions {
    display: flex;
    align-items: center;
    gap: 9px;
    margin-left: auto;
}
.icon-btn {
    position: relative;
    display: grid !important;
    width: 42px;
    height: 42px;
    place-items: center;
    border: 1px solid var(--line) !important;
    border-radius: 50%;
    background: #fff;
}
.icon-btn:hover {
    background: var(--panel);
}
.icon-btn > i {
    position: absolute;
    top: 9px;
    right: 9px;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--orange);
}
.profile {
    display: flex;
    min-height: 44px;
    align-items: center;
    gap: 9px;
    padding: 4px 12px 4px 5px;
    border: 1px solid var(--line);
    border-radius: 999px;
}
.profile > span {
    display: grid;
    width: 34px;
    height: 34px;
    place-items: center;
    border-radius: 50%;
    color: #fff;
    background: #714522;
    font-size: 11px;
}
.profile > b {
    display: grid;
    font-size: 11.5px;
}
.profile small {
    color: var(--faint);
    font-size: 9.5px;
    font-weight: 400;
}
.body {
    display: flex;
    gap: 26px;
    margin-top: 14px;
}
.rail {
    display: flex;
    width: 56px;
    flex: none;
    flex-direction: column;
}
.rail-group {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 5px;
    padding: 8px 0;
    border-radius: 18px;
    background: var(--panel);
}
.rail-group.bottom {
    margin-top: auto;
}
.rail-group a {
    display: grid;
    width: 44px;
    height: 44px;
    place-items: center;
    border-radius: 50%;
    color: #4a4f5a;
    transition: 0.18s;
}
.rail-group a:hover {
    background: #eaecef;
}
.rail-group a.active {
    color: #fff;
    background: var(--ink);
}
main {
    min-width: 0;
    flex: 1;
    animation: pageIn 0.3s ease;
}
.global-search {
    display: flex;
    height: 50px;
    align-items: center;
    gap: 10px;
    margin-bottom: 14px;
    padding: 0 15px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--panel);
}
.global-search input {
    min-width: 0;
    flex: 1;
    border: 0;
    outline: 0;
    background: transparent;
}
.global-search button {
    display: grid;
    width: 34px;
    height: 34px;
    place-items: center;
    border-radius: 50%;
    background: #fff;
}
.page-head {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 20px;
    padding: 8px 0 18px;
}
.page-head p {
    margin: 0 0 6px;
    color: var(--orange);
    font-size: 10px;
    font-weight: 800;
    letter-spacing: 0.12em;
}
.page-head h1 {
    margin: 0;
    font-size: 34px;
    letter-spacing: -0.04em;
}
.page-head > div > span {
    display: block;
    margin-top: 6px;
    color: var(--muted);
    font-size: 12.5px;
}
.page-head > div:last-child {
    display: flex;
    gap: 9px;
}
.primary,
.secondary {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    gap: 7px;
    padding: 0 18px !important;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 700;
}
.primary {
    color: #fff !important;
    background: var(--ink);
}
.secondary {
    background: #f2f3f5;
}
.primary:hover,
.secondary:hover {
    transform: translateY(-2px);
}
.metrics {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 12px;
}
.metrics article {
    padding: 16px;
    border: 1px solid var(--line);
    border-radius: 17px;
    background: #fff;
}
.metrics article.featured {
    color: #fff;
    border-color: transparent;
    background: linear-gradient(135deg, #f36447, #ee4e31);
}
.metrics article > span {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 11.5px;
    font-weight: 650;
}
.metrics article > span i {
    display: grid;
    width: 29px;
    height: 29px;
    place-items: center;
    border-radius: 50%;
    background: var(--panel);
    font-style: normal;
}
.featured > span i {
    background: rgba(255, 255, 255, 0.17) !important;
}
.metrics strong {
    display: block;
    margin: 9px 0 5px;
    font-size: 23px;
}
.metrics small {
    font-size: 10px;
}
.metrics .up,
.income {
    color: var(--green);
}
.metrics .down,
.negative {
    color: var(--red);
}
.metrics .neutral {
    color: var(--faint);
}
.featured small {
    color: rgba(255, 255, 255, 0.8) !important;
}
.panel {
    margin-top: 14px;
    padding: 20px;
    border: 1px solid var(--line);
    border-radius: 20px;
    background: #fff;
}
.panel-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
}
.panel-head small,
.modal small,
.drawer > small {
    color: var(--faint);
    font-size: 9.5px;
    font-weight: 800;
    letter-spacing: 0.1em;
}
.panel-head h2,
.modal h2 {
    margin: 4px 0 0;
    font-size: 16px;
}
.head-actions {
    display: flex;
    gap: 7px;
}
.head-actions button,
.text-btn,
.month-buttons button {
    display: flex;
    height: 38px;
    align-items: center;
    gap: 6px;
    padding: 0 11px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: #fff;
    font-size: 11px;
}
.head-actions button.active,
.head-actions button:hover {
    background: var(--panel);
}
.filters {
    display: grid;
    grid-template-columns: repeat(3, 1fr) auto;
    gap: 9px;
    align-items: end;
    margin-top: 14px;
    padding: 13px;
    border-radius: 13px;
    background: var(--panel);
}
.filters label,
.modal label,
.settings-layout form > label,
.form-grid label {
    display: grid;
    gap: 6px;
    color: var(--muted);
    font-size: 10px;
}
.filters select,
.filters input {
    height: 38px;
    padding: 0 9px;
    border: 1px solid var(--line);
    border-radius: 9px;
    background: #fff;
}
.filters > button {
    height: 38px;
    padding: 0 11px;
    border-radius: 9px;
    background: #fff;
    color: var(--orange);
    font-size: 10.5px;
    font-weight: 700;
}
.table-wrap {
    overflow: auto;
    margin-top: 13px;
}
table {
    width: 100%;
    border-collapse: collapse;
}
th,
td {
    padding: 11px 8px;
    border-bottom: 1px solid var(--line);
    font-size: 10.5px;
    text-align: left;
    white-space: nowrap;
}
th {
    color: var(--faint);
    font-weight: 650;
}
td {
    color: var(--muted);
}
tbody tr {
    cursor: pointer;
}
tbody tr:hover {
    background: #fafbfc;
}
tr.compact td {
    padding-top: 7px;
    padding-bottom: 7px;
}
.right {
    text-align: right;
}
.amount {
    color: var(--ink);
    font-weight: 700;
}
.item-name,
.currency {
    display: flex;
    align-items: center;
    gap: 9px;
}
.item-name > i,
.currency > i {
    display: grid;
    width: 34px;
    height: 34px;
    place-items: center;
    border-radius: 9px;
    color: #fff;
    font-size: 10.5px;
    font-style: normal;
    font-weight: 800;
}
.item-name > span {
    display: grid;
    gap: 2px;
}
.item-name b {
    color: var(--ink);
    font-size: 11px;
}
.item-name small {
    color: var(--faint);
    font-size: 9px;
}
.status-pill,
.sync {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 9.5px;
    font-style: normal;
}
.status-pill > i,
.sync > i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--faint);
}
.status-pill.success > i,
.sync > i {
    background: var(--green);
}
.status-pill.warning > i {
    background: #a7a530;
}
.table-foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-top: 12px;
    color: var(--faint);
    font-size: 9.5px;
}
.table-foot div {
    display: flex;
    gap: 5px;
}
.table-foot button,
.table-foot b {
    display: grid;
    min-width: 31px;
    height: 31px;
    place-items: center;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: #fff;
}
.table-foot b {
    color: #fff;
    background: var(--ink);
}
.account-layout,
.two-col,
.report-layout,
.asset-layout,
.about-layout {
    display: grid;
    grid-template-columns: minmax(0, 1.4fr) minmax(290px, 0.6fr);
    gap: 14px;
    margin-top: 14px;
}
.account-cards {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 12px;
    padding: 17px;
    border-radius: 20px;
    background: var(--panel);
}
.account-cards article {
    position: relative;
    min-height: 230px;
    padding: 18px;
    border: 3px solid transparent;
    border-radius: 18px;
    color: #fff;
    cursor: pointer;
    transition: 0.18s;
}
.account-cards article:hover,
.account-cards article.selected {
    transform: translateY(-4px);
    box-shadow: 0 12px 25px rgba(18, 20, 26, 0.14);
}
.account-cards article.selected {
    border-color: rgba(255, 255, 255, 0.7);
}
.account-cards .tone-0 {
    background: #16191f;
}
.account-cards .tone-1 {
    background: linear-gradient(135deg, #f36447, #ee4e31);
}
.account-cards .tone-2 {
    background: linear-gradient(135deg, #574edd, #312a8d);
}
.account-cards i {
    display: grid;
    width: 34px;
    height: 34px;
    place-items: center;
    border-radius: 10px;
    background: rgba(255, 255, 255, 0.17);
    font-style: normal;
}
.account-cards b,
.account-cards small {
    display: block;
}
.account-cards b {
    margin-top: 11px;
    font-size: 13px;
}
.account-cards small {
    margin-top: 4px;
    color: rgba(255, 255, 255, 0.7);
    font-size: 9.5px;
}
.account-cards strong,
.account-cards em {
    position: absolute;
    left: 18px;
}
.account-cards strong {
    bottom: 44px;
    font-size: 20px;
}
.account-cards em {
    bottom: 19px;
    color: rgba(255, 255, 255, 0.72);
    font-size: 9.5px;
    font-style: normal;
}
.account-detail {
    margin: 0;
}
.account-detail > h2 {
    margin: 20px 0 3px;
    font-size: 18px;
}
.account-detail > p {
    margin: 0;
    color: var(--muted);
    font-size: 10px;
}
.balance {
    display: grid;
    gap: 5px;
    margin: 18px 0;
    padding: 14px;
    border-radius: 13px;
    background: var(--panel);
}
.balance small {
    color: var(--faint);
    font-size: 9.5px;
}
.balance strong {
    font-size: 21px;
}
dl {
    margin: 0;
}
dl > div {
    display: flex;
    justify-content: space-between;
    padding: 10px 0;
    border-bottom: 1px solid var(--line);
    font-size: 10.5px;
}
dt {
    color: var(--muted);
}
dd {
    margin: 0;
    font-weight: 700;
}
.split-buttons {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 7px;
    margin-top: 16px;
}
.split-buttons a,
.split-buttons button {
    display: grid;
    height: 40px;
    place-items: center;
    border-radius: 999px;
    background: var(--panel);
    font-size: 10.5px;
    font-weight: 700;
}
.split-buttons button {
    color: #fff;
    background: var(--ink);
}
.program {
    grid-template-columns: minmax(0, 1.2fr) minmax(330px, 0.8fr);
}
.schedule-row {
    display: grid;
    grid-template-columns: 46px 1fr auto 42px;
    align-items: center;
    gap: 11px;
    padding: 12px 0;
    border-bottom: 1px solid var(--line);
}
.schedule-row time {
    display: grid;
    width: 43px;
    height: 43px;
    place-items: center;
    border-radius: 11px;
    background: var(--panel);
    line-height: 1;
}
.schedule-row time b {
    font-size: 14px;
}
.schedule-row time small,
.schedule-row span small {
    color: var(--faint);
    font-size: 8px;
}
.schedule-row span {
    display: grid;
    gap: 3px;
}
.schedule-row span b,
.schedule-row > strong {
    font-size: 10.5px;
}
.switch input {
    position: absolute;
    opacity: 0;
}
.switch i {
    position: relative;
    display: block;
    width: 39px;
    height: 23px;
    border-radius: 99px;
    background: #d9dde4;
    cursor: pointer;
}
.switch i:after {
    content: "";
    position: absolute;
    top: 3px;
    left: 3px;
    width: 17px;
    height: 17px;
    border-radius: 50%;
    background: #fff;
    transition: 0.18s;
}
.switch input:checked + i {
    background: var(--ink);
}
.switch input:checked + i:after {
    transform: translateX(16px);
}
.month-buttons {
    display: flex;
    gap: 5px;
}
.month-buttons button {
    width: 36px;
    padding: 0;
    justify-content: center;
}
.week,
.days {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: 5px;
}
.week {
    margin-top: 17px;
    color: var(--faint);
    font-size: 9px;
    text-align: center;
}
.days {
    margin-top: 6px;
}
.days button {
    position: relative;
    height: 32px;
    border-radius: 8px;
    background: #fff;
    font-size: 10px;
}
.days button:hover {
    background: var(--panel);
}
.days button.marked:after {
    content: "";
    position: absolute;
    bottom: 3px;
    left: 50%;
    width: 4px;
    height: 4px;
    border-radius: 50%;
    background: var(--orange);
}
.days button.today {
    color: #fff;
    background: var(--ink);
}
.calendar-note {
    display: flex;
    align-items: center;
    gap: 7px;
    margin-top: 13px;
    padding: 11px;
    border-radius: 11px;
    background: var(--panel);
    font-size: 9.5px;
}
.calendar-note > i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--orange);
}
.calendar-note b {
    margin-left: auto;
}
.report-layout {
    grid-template-columns: minmax(0, 1.5fr) minmax(280px, 0.5fr);
}
.report-layout > .panel {
    margin: 0;
}
.period {
    height: 36px;
    padding: 0 9px;
    border: 1px solid var(--line);
    border-radius: 9px;
    background: #fff;
    font-size: 10px;
}
.legend {
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    margin-top: 12px;
    color: var(--muted);
    font-size: 9px;
}
.legend span {
    display: flex;
    align-items: center;
    gap: 5px;
}
.legend i {
    width: 8px;
    height: 8px;
    border-radius: 2px;
    background: var(--orange);
}
.legend span:last-child i {
    background: var(--ink);
}
.chart {
    position: relative;
    display: flex;
    height: 250px;
    align-items: flex-end;
    justify-content: space-around;
    padding: 20px 20px 25px;
}
.gridline {
    position: absolute;
    left: 0;
    right: 0;
    border-top: 1px dashed #e5e7ec;
}
.bar {
    position: relative;
    z-index: 1;
    display: flex;
    width: 40px;
    height: 100%;
    align-items: flex-end;
    gap: 5px;
}
.bar > i {
    width: 16px;
    border-radius: 5px 5px 0 0;
    background: var(--orange);
}
.bar > i:nth-child(2) {
    background: var(--ink);
}
.bar span {
    position: absolute;
    bottom: -19px;
    left: 50%;
    transform: translateX(-50%);
    color: var(--faint);
    font-size: 9px;
}
.donut {
    display: grid;
    width: 150px;
    height: 150px;
    place-items: center;
    margin: 18px auto;
    border-radius: 50%;
    background: conic-gradient(
        var(--orange) 0 42%,
        #4f46e5 42% 76%,
        #2e9bff 76% 89%,
        var(--ink) 89%
    );
}
.donut:before {
    content: "";
    grid-area: 1/1;
    width: 96px;
    height: 96px;
    border-radius: 50%;
    background: #fff;
}
.donut span {
    z-index: 1;
    grid-area: 1/1;
    display: grid;
    text-align: center;
}
.donut b {
    font-size: 15px;
}
.donut small {
    color: var(--faint);
    font-size: 8px;
}
.breakdown ul {
    display: grid;
    gap: 9px;
    margin: 0;
    padding: 0;
    list-style: none;
}
.breakdown li {
    display: grid;
    grid-template-columns: 8px 1fr auto;
    gap: 7px;
    align-items: center;
    font-size: 9.5px;
}
.breakdown li > i {
    width: 8px;
    height: 8px;
    border-radius: 2px;
}
.breakdown li span {
    color: var(--muted);
}
.settings-layout {
    display: grid;
    grid-template-columns: 280px 1fr;
    margin-top: 14px;
    border: 1px solid var(--line);
    border-radius: 20px;
    overflow: hidden;
}
.settings-layout nav {
    padding: 12px;
    background: var(--panel);
}
.settings-layout nav button {
    display: grid;
    width: 100%;
    grid-template-columns: 34px 1fr 18px;
    align-items: center;
    gap: 9px;
    padding: 10px;
    border-radius: 11px;
    background: transparent;
    text-align: left;
}
.settings-layout nav button:hover,
.settings-layout nav button.active {
    background: #fff;
}
.settings-layout nav button > i {
    display: grid;
    width: 32px;
    height: 32px;
    place-items: center;
    border-radius: 9px;
    color: #fff;
    font-size: 10px;
    font-style: normal;
    font-weight: 800;
}
.settings-layout nav button > span {
    display: grid;
    gap: 2px;
}
.settings-layout nav b {
    font-size: 10.5px;
}
.settings-layout nav small {
    color: var(--muted);
    font-size: 9px;
}
.settings-layout form {
    display: grid;
    align-content: start;
    gap: 14px;
    padding: 24px;
}
.form-note {
    margin: -3px 0 0;
    color: var(--muted);
    font-size: 10px;
}
.form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 11px;
}
.settings-layout input:not([type="checkbox"]),
.settings-layout select,
.modal input,
.modal textarea {
    width: 100%;
    padding: 0 11px;
    border: 1px solid var(--line);
    border-radius: 9px;
}
.settings-layout input:not([type="checkbox"]),
.settings-layout select,
.modal input {
    height: 41px;
}
.settings-layout label small {
    color: var(--red);
}
.setting-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 0;
    border-top: 1px solid var(--line);
}
.setting-row > span {
    display: grid;
    gap: 3px;
}
.setting-row b {
    font-size: 10.5px;
}
.setting-row small {
    color: var(--muted);
    font-size: 9px;
}
.save {
    justify-self: start;
}
.small-search {
    display: flex;
    width: 210px;
    height: 37px;
    align-items: center;
    gap: 6px;
    padding: 0 10px;
    border: 1px solid var(--line);
    border-radius: 9px;
}
.small-search input {
    min-width: 0;
    flex: 1;
    border: 0;
    outline: 0;
    font-size: 10px;
}
.catalog-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 9px;
    margin-top: 15px;
}
.catalog-grid button {
    display: grid;
    grid-template-columns: 37px 1fr auto 17px;
    align-items: center;
    gap: 10px;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: 13px;
    background: #fff;
    text-align: left;
}
.catalog-grid button:hover {
    transform: translateY(-2px);
    box-shadow: 0 7px 16px rgba(18, 20, 26, 0.07);
}
.catalog-grid button > i,
.asset-list article > i {
    display: grid;
    width: 36px;
    height: 36px;
    place-items: center;
    border-radius: 10px;
    color: #fff;
    font-size: 10px;
    font-style: normal;
    font-weight: 800;
}
.catalog-grid button > span {
    display: grid;
    gap: 2px;
}
.catalog-grid b,
.catalog-grid strong {
    font-size: 10.5px;
}
.catalog-grid small {
    color: var(--muted);
    font-size: 8.5px;
}
.catalog-grid em {
    height: 3px;
    margin-top: 4px;
    border-radius: 99px;
    background: var(--panel);
    overflow: hidden;
}
.catalog-grid em i {
    display: block;
    height: 100%;
}
.template-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 11px;
    margin-top: 14px;
}
.template-grid article {
    padding: 16px;
    border: 1px solid var(--line);
    border-radius: 17px;
}
.template-grid article > i {
    display: grid;
    width: 38px;
    height: 38px;
    place-items: center;
    border-radius: 11px;
    color: #fff;
    font-size: 11px;
    font-style: normal;
    font-weight: 800;
}
.template-grid article > small {
    display: block;
    margin-top: 16px;
    color: var(--faint);
    font-size: 8.5px;
}
.template-grid h2 {
    margin: 5px 0;
    font-size: 13px;
}
.template-grid p {
    min-height: 29px;
    margin: 0;
    color: var(--muted);
    font-size: 8.5px;
    line-height: 1.5;
}
.template-grid dl {
    margin: 12px 0;
}
.template-grid dl > div {
    padding: 4px 0;
    font-size: 8.5px;
}
.template-grid button {
    display: flex;
    width: 100%;
    height: 37px;
    align-items: center;
    justify-content: center;
    gap: 5px;
    border-radius: 99px;
    background: var(--panel);
    font-size: 9.5px;
    font-weight: 700;
}
.template-grid button:hover {
    color: #fff;
    background: var(--ink);
}
.base-rate {
    display: grid;
    grid-template-columns: auto 1fr auto;
    gap: 14px;
    margin-top: 15px;
    padding: 13px;
    border-radius: 12px;
    background: var(--panel);
    font-size: 9.5px;
}
.base-rate span,
.base-rate small {
    color: var(--muted);
}
.currency > i {
    color: var(--ink);
    background: var(--panel);
}
.mini-btn {
    display: grid;
    width: 33px;
    height: 33px;
    place-items: center;
    border-radius: 50%;
    background: var(--panel);
}
.asset-layout {
    grid-template-columns: minmax(260px, 0.6fr) minmax(0, 1.4fr);
}
.asset-summary {
    padding: 22px;
    border-radius: 20px;
    color: #fff;
    background: #15181e;
}
.asset-summary small {
    color: rgba(255, 255, 255, 0.65);
}
.asset-summary strong {
    display: block;
    margin-top: 10px;
    font-size: 26px;
}
.asset-summary > span {
    display: block;
    margin-top: 4px;
    color: rgba(255, 255, 255, 0.65);
    font-size: 9px;
}
.allocation {
    display: flex;
    height: 8px;
    margin-top: 32px;
    overflow: hidden;
    border-radius: 99px;
}
.allocation i:nth-child(1) {
    width: 48%;
    background: #fff;
}
.allocation i:nth-child(2) {
    width: 32%;
    background: var(--orange);
}
.allocation i:nth-child(3) {
    width: 20%;
    background: #4f46e5;
}
.asset-summary footer {
    display: flex;
    justify-content: space-between;
    margin-top: 10px;
    color: rgba(255, 255, 255, 0.65);
    font-size: 8.5px;
}
.asset-list {
    margin: 0;
}
.asset-list article {
    display: grid;
    grid-template-columns: 38px 1fr auto 17px;
    align-items: center;
    gap: 10px;
    padding: 11px 0;
    border-bottom: 1px solid var(--line);
    cursor: pointer;
}
.asset-list article:last-child {
    border: 0;
}
.asset-list article > span,
.asset-list article > div {
    display: grid;
    gap: 3px;
}
.asset-list article > div {
    text-align: right;
}
.asset-list b,
.asset-list strong {
    font-size: 10.5px;
}
.asset-list small {
    color: var(--muted);
    font-size: 8.5px;
}
.about-hero {
    padding: 27px;
    border-radius: 20px;
    background: var(--panel);
}
.about-hero > i {
    width: 56px;
    height: 56px;
    border-radius: 16px;
    font-size: 27px;
}
.about-hero p {
    margin: 22px 0 7px;
    color: var(--orange);
    font-size: 9px;
    font-weight: 800;
    letter-spacing: 0.12em;
}
.about-hero h2 {
    margin: 0;
    font-size: 24px;
}
.about-hero > span {
    display: block;
    max-width: 500px;
    margin-top: 11px;
    color: var(--muted);
    font-size: 10.5px;
    line-height: 1.7;
}
.about-hero > b {
    display: inline-block;
    margin-top: 18px;
    padding: 7px 10px;
    border-radius: 99px;
    background: #fff;
    font-size: 9px;
}
.status {
    margin: 0;
}
.status footer {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 6px;
    margin-top: 14px;
}
.status footer button {
    height: 37px;
    border-radius: 9px;
    background: var(--panel);
    font-size: 8.5px;
}
.mask {
    position: fixed;
    z-index: 2999;
    inset: 0;
    background: rgba(18, 20, 26, 0.18);
    backdrop-filter: blur(2px);
}
.drawer {
    position: fixed;
    z-index: 3000;
    top: 18px;
    right: 18px;
    bottom: 18px;
    width: 360px;
    padding: 27px;
    border-radius: 23px;
    background: #fff;
    box-shadow: 0 24px 70px rgba(18, 20, 26, 0.24);
}
.drawer-close {
    position: absolute;
    right: 20px;
    top: 20px;
    display: grid;
    width: 40px;
    height: 40px;
    place-items: center;
    border-radius: 50%;
    background: var(--panel);
}
.drawer > i {
    display: grid;
    width: 47px;
    height: 47px;
    place-items: center;
    margin-top: 24px;
    border-radius: 13px;
    color: #fff;
    font-style: normal;
    font-weight: 800;
}
.drawer h2 {
    margin: 15px 0 4px;
    font-size: 22px;
}
.drawer p {
    margin: 0;
    color: var(--muted);
    font-size: 10px;
}
.drawer dl {
    margin: 22px 0;
}
.drawer .primary {
    width: 100%;
}
.modal-mask {
    position: fixed;
    z-index: 4000;
    inset: 0;
    display: grid;
    place-items: center;
    background: rgba(18, 20, 26, 0.24);
    backdrop-filter: blur(3px);
}
.modal {
    width: 430px;
    padding: 21px;
    border-radius: 21px;
    background: #fff;
}
.modal header {
    display: flex;
    justify-content: space-between;
    margin-bottom: 18px;
}
.modal header button {
    display: grid;
    width: 38px;
    height: 38px;
    place-items: center;
    border-radius: 50%;
    background: var(--panel);
}
.modal label {
    margin-top: 11px;
}
.modal textarea {
    padding-top: 9px;
    resize: vertical;
}
.modal footer {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 18px;
}
.modal footer > button:not(.primary) {
    min-height: 44px;
    padding: 0 16px;
    border-radius: 99px;
    background: var(--panel);
}
.toast {
    position: fixed;
    z-index: 5000;
    left: 50%;
    bottom: 27px;
    display: flex;
    align-items: center;
    gap: 7px;
    transform: translateX(-50%);
    padding: 11px 16px;
    border-radius: 99px;
    color: #fff;
    background: var(--ink);
    box-shadow: 0 10px 28px rgba(18, 20, 26, 0.22);
    font-size: 11px;
}
.drop-enter-active,
.drop-leave-active,
.toast-enter-active,
.toast-leave-active,
.drawer-enter-active,
.drawer-leave-active {
    transition: 0.2s;
}
.drop-enter-from,
.drop-leave-to {
    opacity: 0;
    transform: translateY(-7px);
}
.toast-enter-from,
.toast-leave-to {
    opacity: 0;
    transform: translate(-50%, 8px);
}
.drawer-enter-from,
.drawer-leave-to {
    opacity: 0;
    transform: translateX(30px);
}
.spin {
    animation: spin 0.8s linear infinite;
}
button:focus-visible,
a:focus-visible,
input:focus-visible,
select:focus-visible,
textarea:focus-visible,
[tabindex]:focus-visible {
    outline: 3px solid rgba(240, 85, 55, 0.32);
    outline-offset: 2px;
}
@keyframes pageIn {
    from {
        opacity: 0;
        transform: translateY(9px);
    }
    to {
        opacity: 1;
        transform: none;
    }
}
@keyframes spin {
    to {
        transform: rotate(360deg);
    }
}
@media (max-width: 1370px) {
    .workspace {
        padding: 28px 40px;
    }
    .topnav {
        gap: 21px;
        margin-left: 38px;
    }
    .template-grid {
        grid-template-columns: repeat(2, 1fr);
    }
    .account-cards {
        grid-template-columns: 1fr;
    }
    .account-cards article {
        min-height: 170px;
    }
}
@media (prefers-reduced-motion: reduce) {
    *,
    *:before,
    *:after {
        animation-duration: 0.01ms !important;
        transition-duration: 0.01ms !important;
    }
}
.template-grid article {
    position: relative;
}
.template-grid .template-edit {
    position: absolute;
    top: 16px;
    right: 16px;
    width: auto;
    height: 32px;
    padding: 0 10px;
    border: 1px solid var(--line);
    border-radius: 99px;
    background: #fff;
    color: var(--muted);
    font-size: 9px;
}
.template-grid .template-edit:hover {
    color: var(--ink);
    background: var(--panel);
}
.empty-row {
    height: 110px !important;
    color: var(--muted);
    text-align: center !important;
}
.setting-actions {
    max-width: 380px;
}
.setting-actions button:first-child {
    color: var(--ink);
    background: var(--panel);
}
.service-status {
    max-width: 560px;
}
.info-modal p {
    margin: 10px 0;
    color: var(--muted);
    font-size: 11px;
    line-height: 1.75;
}
.info-modal footer .primary {
    min-width: 110px;
}
.days > span {
    height: 32px;
}
/* Product asset library: reference visuals and real valuation data. */
.drawer {
    --panel: #f7f8fa;
    --line: #e9ebf0;
    --ink: #12141a;
    --muted: #4f5b6b;
    --faint: #667085;
    --orange: #c83f28;
    --green: #087a49;
    --red: #b42318;
    box-sizing: border-box;
    color: var(--ink);
    font-family: Inter, "Microsoft YaHei UI", "PingFang SC", sans-serif;
}
.drawer * {
    box-sizing: border-box;
}
.drawer button {
    font: inherit;
    border: 0;
    color: inherit;
    cursor: pointer;
}
.drawer .primary {
    display: inline-flex;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    padding: 0 18px;
    border-radius: 999px;
    color: #fff;
    background: var(--ink);
    font-size: 13px;
    font-weight: 700;
}
.asset-layout {
    grid-template-columns: minmax(270px, 0.68fr) minmax(0, 1.32fr);
    align-items: start;
}
.asset-summary {
    position: sticky;
    top: 16px;
    padding: 25px;
    border-radius: 22px;
    color: #fff;
    background: linear-gradient(145deg, #171a21, #292d36);
}
.asset-summary > small {
    color: rgba(255, 255, 255, 0.68);
    font-size: 12px;
}
.asset-summary > strong {
    display: block;
    margin-top: 11px;
    font-size: 29px;
    letter-spacing: -0.03em;
}
.asset-summary > span {
    display: block;
    margin-top: 5px;
    color: rgba(255, 255, 255, 0.68);
    font-size: 12px;
}
.asset-summary-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
    margin-top: 24px;
}
.asset-summary-grid > div {
    display: grid;
    gap: 5px;
    padding: 12px;
    border-radius: 13px;
    background: rgba(255, 255, 255, 0.08);
}
.asset-summary-grid small {
    color: rgba(255, 255, 255, 0.58);
    font-size: 11px;
}
.asset-summary-grid b {
    font-size: 13px;
}
.asset-layout .allocation {
    display: flex;
    height: 8px;
    margin-top: 24px;
    overflow: hidden;
    border-radius: 99px;
    background: rgba(255, 255, 255, 0.12);
}
.asset-layout .allocation i:first-child {
    flex: none;
    background: linear-gradient(90deg, #f36447, #f05537);
}
.asset-layout .allocation i:last-child {
    flex: 1;
    background: rgba(255, 255, 255, 0.12);
}
.asset-summary footer {
    display: flex;
    justify-content: space-between;
    margin-top: 10px;
    color: rgba(255, 255, 255, 0.68);
    font-size: 11px;
}
.asset-list {
    min-width: 0;
    margin: 0;
}
.asset-list .panel-head {
    align-items: center;
}
.asset-search {
    display: flex;
    width: 230px;
    height: 38px;
    align-items: center;
    gap: 7px;
    padding: 0 11px;
    border: 1px solid var(--line);
    border-radius: 10px;
    color: var(--muted);
}
.asset-search input {
    min-width: 0;
    flex: 1;
    border: 0;
    outline: 0;
    background: transparent;
    color: var(--ink);
    font-size: 12px;
}
.asset-filters {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 14px 0 8px;
}
.asset-filters button,
.asset-filters select {
    height: 32px;
    padding: 0 11px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: #fff;
    color: var(--muted);
    font-size: 11.5px;
}
.asset-filters button.active {
    border-color: var(--ink);
    color: #fff;
    background: var(--ink);
}
.asset-filters select {
    margin-left: auto;
    outline: 0;
}
.asset-items {
    display: grid;
    gap: 7px;
}
.asset-row {
    display: grid;
    width: 100%;
    grid-template-columns: 56px minmax(0, 1fr) auto 17px;
    align-items: center;
    gap: 12px;
    padding: 11px;
    border: 1px solid transparent;
    border-radius: 15px;
    background: var(--panel);
    text-align: left;
    transition: 0.16s;
}
.asset-row:hover {
    border-color: #e3e6eb;
    background: #fff;
    box-shadow: 0 7px 18px rgba(18, 20, 26, 0.06);
    transform: translateY(-1px);
}
.asset-thumb {
    display: grid;
    width: 56px;
    height: 56px;
    place-items: center;
    border: 1px solid #e8eaf0;
    border-radius: 14px;
    color: #111827;
    background: linear-gradient(145deg, #fff, #eef1f6);
    box-shadow: inset 0 1px 0 #fff;
}
.asset-thumb.category-2 {
    color: #1d4ed8;
    background: linear-gradient(145deg, #eff6ff, #dbeafe);
}
.asset-thumb.category-3 {
    color: #394150;
    background: linear-gradient(145deg, #fff, #e5e7eb);
}
.asset-thumb.category-4 {
    color: #6d28d9;
    background: linear-gradient(145deg, #f5f3ff, #ede9fe);
}
.asset-thumb.category-5 {
    color: #b45309;
    background: linear-gradient(145deg, #fffbeb, #fef3c7);
}
.asset-thumb.category-6 {
    color: #047857;
    background: linear-gradient(145deg, #ecfdf5, #d1fae5);
}
.asset-thumb.category-7 {
    color: #b42318;
    background: linear-gradient(145deg, #fff1f2, #ffe4e6);
}
.asset-copy,
.asset-value {
    display: grid;
    gap: 4px;
    min-width: 0;
}
.asset-copy b {
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 13.5px;
    white-space: nowrap;
}
.asset-copy small,
.asset-value small,
.asset-value em {
    color: var(--muted);
    font-size: 11.5px;
    font-style: normal;
}
.asset-copy em {
    justify-self: start;
    padding: 4px 8px;
    border-radius: 999px;
    color: var(--muted);
    background: #fff;
    font-size: 10.5px;
    font-style: normal;
}
.asset-copy em.success {
    color: #087a49;
    background: #eaf8f1;
}
.asset-value {
    text-align: right;
}
.asset-value strong {
    font-size: 14px;
}
.asset-empty {
    display: grid;
    min-height: 210px;
    place-items: center;
    align-content: center;
    gap: 6px;
    color: var(--muted);
    text-align: center;
}
.asset-empty b {
    color: var(--ink);
    font-size: 14px;
}
.asset-empty span {
    font-size: 12px;
}
.asset-empty button {
    margin-top: 8px;
    padding: 9px 14px;
    border-radius: 999px;
    color: #fff;
    background: var(--ink);
    font-size: 12px;
    font-weight: 700;
}
.asset-drawer {
    width: 480px;
    overflow-y: auto;
}
.asset-drawer > small {
    display: block;
    margin-top: 7px;
    color: var(--orange) !important;
    font-weight: 750;
}
.asset-detail-head {
    display: grid;
    grid-template-columns: 68px 1fr;
    align-items: center;
    gap: 14px;
    margin-top: 18px;
}
.asset-detail-head .asset-thumb.large {
    width: 68px;
    height: 68px;
}
.asset-detail-head h2 {
    margin: 0 0 4px;
}
.asset-detail-head p {
    font-size: 12px;
}
.asset-detail-head em {
    display: inline-flex;
    margin-top: 8px;
    padding: 4px 8px;
    border-radius: 999px;
    color: var(--muted);
    background: var(--panel);
    font-size: 11px;
    font-style: normal;
}
.asset-detail-head em.success {
    color: #087a49;
    background: #eaf8f1;
}
.asset-hero-values {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 9px;
    margin-top: 22px;
}
.asset-hero-values > div {
    display: grid;
    gap: 4px;
    padding: 15px;
    border: 1px solid var(--line);
    border-radius: 15px;
}
.asset-hero-values small,
.asset-hero-values span {
    color: var(--muted);
    font-size: 11.5px;
}
.asset-hero-values strong {
    font-size: 22px;
}
.asset-detail-section {
    margin-top: 22px;
}
.asset-detail-section h3 {
    margin: 0 0 10px;
    font-size: 15px;
}
.asset-value-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
}
.asset-value-grid > div {
    display: grid;
    gap: 6px;
    padding: 13px;
    border-radius: 13px;
    background: var(--panel);
}
.asset-value-grid small {
    color: var(--muted);
    font-size: 11px;
}
.asset-value-grid b {
    font-size: 15px;
}
.asset-detail-section dl {
    padding: 4px 14px;
    border-radius: 15px;
    background: var(--panel);
}
.asset-detail-section dl > div {
    font-size: 12px;
}
.asset-detail-section dd {
    max-width: 245px;
    text-align: right;
    overflow-wrap: anywhere;
}
.asset-drawer > .primary {
    margin-top: 18px;
}
.top-actions {
    position: relative;
}
.profile {
    background: #fff;
}
.profile:hover {
    background: var(--panel);
}
.profile > b {
    text-align: left;
}
.account-menu {
    position: absolute;
    z-index: 80;
    top: 51px;
    right: 0;
    width: 285px;
    padding: 9px;
    border: 1px solid var(--line);
    border-radius: 18px;
    background: #fff;
    box-shadow: 0 18px 45px rgba(18, 20, 26, 0.16);
}
.account-current {
    display: grid;
    grid-template-columns: 40px 1fr auto;
    align-items: center;
    gap: 10px;
    padding: 10px;
    margin-bottom: 6px;
    border-radius: 13px;
    background: var(--panel);
}
.account-current > span {
    display: grid;
    width: 40px;
    height: 40px;
    place-items: center;
    border-radius: 50%;
    color: #fff;
    background: #714522;
    font-size: 12px;
    font-weight: 800;
}
.account-current > div {
    display: grid;
    gap: 2px;
    min-width: 0;
}
.account-current small {
    color: var(--faint);
    font-size: 9.5px;
}
.account-current strong {
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 12px;
    white-space: nowrap;
}
.account-current > i {
    padding: 4px 7px;
    border-radius: 999px;
    color: var(--green);
    background: #eaf9f1;
    font-size: 9px;
    font-style: normal;
    font-weight: 700;
}
.account-menu > a,
.account-menu > button {
    display: grid !important;
    width: 100%;
    grid-template-columns: 34px 1fr;
    align-items: center;
    gap: 9px;
    padding: 9px;
    border-radius: 11px;
    background: #fff;
    text-align: left;
}
.account-menu > a:hover,
.account-menu > button:hover {
    background: var(--panel);
}
.account-menu > a > .v-icon,
.account-menu > button > .v-icon {
    display: grid;
    width: 34px;
    height: 34px;
    place-items: center;
    border-radius: 10px;
    background: #f0f1f4;
}
.account-menu > a > span,
.account-menu > button > span {
    display: grid;
    gap: 2px;
}
.account-menu b {
    font-size: 11px;
}
.account-menu a small,
.account-menu button small {
    color: var(--faint);
    font-size: 9.5px;
}

/* Readability pass: keep the established layout, strengthen the entire type hierarchy. */
.workspace {
    --muted: #4f5b6b;
    --faint: #667085;
    --orange: #c83f28;
    --green: #087a49;
    --red: #b42318;
}
.page-head p {
    font-size: 12px;
}
.workspace .page-head h1 {
    font-size: 40px;
    line-height: 1.15;
}
.workspace h1,
.workspace h2,
.workspace h3 {
    color: #12141a !important;
    opacity: 1 !important;
    -webkit-text-fill-color: #12141a !important;
    text-shadow: none !important;
}
.page-head > div > span {
    font-size: 14.5px;
    line-height: 1.6;
    color: var(--muted);
}
.topnav a {
    font-size: 13.5px;
}
.profile > b {
    font-size: 13px;
}
.profile small {
    font-size: 12px;
    color: var(--muted);
}
.metrics article > span {
    font-size: 13px;
}
.metrics strong {
    font-size: 25px;
}
.metrics small {
    font-size: 12.5px;
    line-height: 1.45;
}
.metrics .neutral {
    color: var(--muted);
}
.panel-head small,
.modal small,
.drawer > small {
    font-size: 11.5px;
    color: var(--muted);
}
.panel-head h2,
.modal h2 {
    font-size: 18px;
}
.primary,
.secondary {
    font-size: 13px;
}
.head-actions button,
.text-btn,
.month-buttons button {
    font-size: 12.5px;
}
.filters label,
.modal label,
.settings-layout form > label,
.form-grid label {
    font-size: 12.5px;
}
.filters > button {
    font-size: 12.5px;
}
th,
td {
    font-size: 13px;
}
th {
    color: var(--muted);
}
.item-name b {
    font-size: 13px;
}
.item-name small {
    font-size: 12px;
    color: var(--muted);
    line-height: 1.4;
}
.status-pill,
.sync {
    font-size: 12px;
}
.table-foot {
    font-size: 12px;
    color: var(--muted);
}
.account-cards b {
    font-size: 14px;
}
.account-cards small,
.account-cards em {
    font-size: 12px;
}
.account-detail > p {
    font-size: 13px;
}
.balance small {
    font-size: 12px;
}
dl > div {
    font-size: 12.5px;
}
.split-buttons a,
.split-buttons button {
    font-size: 12.5px;
}
.schedule-row time small,
.schedule-row span small {
    font-size: 12px;
    color: var(--muted);
}
.schedule-row span b,
.schedule-row > strong {
    font-size: 13px;
}
.week {
    font-size: 11.5px;
    color: var(--muted);
}
.days button {
    font-size: 12px;
}
.calendar-note {
    font-size: 12px;
}
.period {
    font-size: 12px;
}
.legend {
    font-size: 12px;
}
.bar span {
    font-size: 11.5px;
    color: var(--muted);
}
.donut small {
    font-size: 11.5px;
}
.breakdown li {
    font-size: 12px;
}
.settings-layout nav b {
    font-size: 13px;
}
.settings-layout nav small {
    font-size: 12px;
    color: var(--muted);
}
.form-note {
    font-size: 12.5px;
}
.setting-row b {
    font-size: 13px;
}
.setting-row small {
    font-size: 12px;
    color: var(--muted);
}
.small-search input {
    font-size: 12.5px;
}
.catalog-grid b,
.catalog-grid strong {
    font-size: 13px;
}
.catalog-grid small {
    font-size: 12px;
    color: var(--muted);
}
.template-grid article > small {
    font-size: 11.5px;
    color: var(--muted);
}
.template-grid h2 {
    font-size: 15px;
}
.template-grid p {
    font-size: 12px;
    line-height: 1.55;
}
.template-grid dl > div {
    font-size: 12px;
}
.template-grid button,
.template-grid .template-edit {
    font-size: 12px;
}
.base-rate {
    font-size: 12px;
}
.asset-summary > span,
.asset-summary footer {
    font-size: 12px;
}
.asset-list b,
.asset-list strong {
    font-size: 13px;
}
.asset-list small {
    font-size: 12px;
    color: var(--muted);
}
.about-hero p {
    font-size: 11.5px;
}
.about-hero > span {
    font-size: 13px;
}
.about-hero > b {
    font-size: 11.5px;
}
.status footer button {
    font-size: 11.5px;
}
.drawer p {
    font-size: 13px;
}
.toast {
    font-size: 13px;
}
.account-current small {
    font-size: 11.5px;
    color: var(--muted);
}
.account-current strong {
    font-size: 13px;
}
.account-current > i {
    font-size: 11px;
}
.account-menu b {
    font-size: 13px;
}
.account-menu a small,
.account-menu button small {
    font-size: 12px;
    color: var(--muted);
    line-height: 1.45;
}
/* Asset card readability: explicit hierarchy and contrast on every row. */
.asset-row {
    padding: 14px;
    border-color: #e3e7ed;
    background: #fff;
    box-shadow: 0 2px 8px rgba(18, 20, 26, 0.025);
}
.asset-row:hover {
    border-color: #cfd5df;
    box-shadow: 0 9px 22px rgba(18, 20, 26, 0.08);
}
.asset-row > .v-icon {
    color: #344054;
}
.asset-copy {
    gap: 5px;
}
.asset-copy b {
    color: #12141a;
    font-size: 15px;
    font-weight: 800;
    line-height: 1.35;
    letter-spacing: -0.01em;
}
.asset-copy small {
    color: #475467;
    font-size: 12.5px;
    font-weight: 550;
    line-height: 1.45;
}
.asset-copy em {
    color: #344054;
    background: #f2f4f7;
    font-size: 11.5px;
    font-weight: 700;
    line-height: 1.3;
}
.asset-copy em.success {
    color: #067647;
    background: #e8f7ef;
}
.asset-value {
    gap: 5px;
}
.asset-value small,
.asset-value em {
    color: #475467;
    font-size: 12px;
    font-weight: 550;
}
.asset-value strong {
    color: #12141a;
    font-size: 16px;
    font-weight: 850;
    line-height: 1.35;
}
.asset-thumb {
    border-color: #d9dee7;
}
.asset-list .panel-head h2 {
    font-size: 19px;
}
.asset-list .panel-head small {
    color: #475467;
    font-weight: 750;
}
.asset-filters button,
.asset-filters select {
    color: #344054;
    font-size: 12px;
    font-weight: 650;
}
/* Asset editor: a focused sub-window based on the supplied mobile reference. */
.asset-editor-layer {
    position: fixed;
    z-index: 4500;
    inset: 0;
    display: flex;
    justify-content: flex-end;
    font-family: Inter, "Microsoft YaHei UI", "PingFang SC", sans-serif;
    color: #12141a;
}
.asset-editor-layer * {
    box-sizing: border-box;
}
.asset-editor-mask {
    position: absolute;
    inset: 0;
    border: 0;
    background: rgba(18, 20, 26, 0.24);
    backdrop-filter: blur(3px);
    cursor: pointer;
}
.asset-editor {
    position: relative;
    display: grid;
    width: min(620px, calc(100vw - 28px));
    height: calc(100vh - 28px);
    grid-template-rows: auto minmax(0, 1fr) auto;
    margin: 14px;
    border-radius: 26px;
    background: #f5f7fa;
    box-shadow: 0 28px 80px rgba(18, 20, 26, 0.28);
    overflow: hidden;
}
.asset-editor button,
.asset-editor input,
.asset-editor select,
.asset-editor textarea {
    font: inherit;
}
.asset-editor button {
    border: 0;
    color: inherit;
    cursor: pointer;
}
.asset-editor-head {
    display: grid;
    grid-template-columns: 44px 1fr 44px;
    align-items: center;
    padding: 16px 20px;
    border-bottom: 1px solid #e6e9ee;
    background: rgba(255, 255, 255, 0.94);
    text-align: center;
}
.asset-editor-head > button {
    display: grid;
    width: 40px;
    height: 40px;
    place-items: center;
    border-radius: 50%;
    background: #f3f4f6;
}
.asset-editor-head > button:hover {
    background: #e9ebef;
}
.asset-editor-head small {
    display: block;
    color: #c83f28;
    font-size: 10px;
    font-weight: 800;
    letter-spacing: 0.13em;
}
.asset-editor-head h2 {
    margin: 3px 0 0;
    font-size: 20px;
    letter-spacing: -0.02em;
}
.asset-editor-scroll {
    display: grid;
    align-content: start;
    gap: 12px;
    padding: 16px 18px 28px;
    overflow-y: auto;
}
.asset-name-card {
    display: grid;
    grid-template-columns: 64px 1fr;
    align-items: center;
    gap: 14px;
    padding: 15px;
    border: 1px solid #e7e9ee;
    border-radius: 18px;
    background: #fff;
}
.asset-name-card .asset-thumb {
    width: 64px;
    height: 64px;
}
.asset-name-card label {
    display: grid;
    gap: 5px;
}
.asset-name-card label > span,
.asset-form-grid label > span {
    color: #4f5b6b;
    font-size: 12px;
}
.asset-name-card label i,
.asset-form-grid label i {
    color: #c83f28;
    font-style: normal;
}
.asset-name-card input {
    height: 31px;
    padding: 0;
    border: 0;
    outline: 0;
    color: #12141a;
    background: transparent;
    font-size: 18px;
    font-weight: 750;
}
.asset-name-card input::placeholder {
    color: #9aa3b2;
    font-weight: 500;
}
.asset-name-card label small {
    color: #667085;
    font-size: 11px;
}
.asset-form-card {
    padding: 17px;
    border: 1px solid #e7e9ee;
    border-radius: 19px;
    background: #fff;
}
.asset-form-card > header {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 15px;
}
.asset-form-card > header > span {
    display: grid;
    width: 32px;
    height: 28px;
    place-items: center;
    border-radius: 9px;
    color: #c83f28;
    background: #fff0ec;
    font-size: 11px;
    font-weight: 850;
}
.asset-form-card > header > div {
    display: grid;
    gap: 2px;
}
.asset-form-card h3 {
    margin: 0;
    font-size: 16px;
}
.asset-form-card header small {
    color: #667085;
    font-size: 11px;
}
.asset-form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 14px 12px;
}
.asset-form-grid label {
    display: grid;
    gap: 7px;
}
.asset-form-grid label.wide {
    grid-column: 1/-1;
}
.asset-form-grid label > span {
    display: flex;
    align-items: baseline;
    gap: 6px;
}
.asset-form-grid label > span small {
    color: #8b95a5;
    font-size: 10.5px;
}
.asset-form-grid input,
.asset-form-grid select,
.asset-form-grid textarea {
    width: 100%;
    border: 1px solid #e1e5ea;
    border-radius: 11px;
    outline: 0;
    color: #12141a;
    background: #f8f9fb;
    font-size: 13px;
    transition: 0.15s;
}
.asset-form-grid input,
.asset-form-grid select {
    height: 43px;
    padding: 0 12px;
}
.asset-form-grid textarea {
    min-height: 94px;
    padding: 11px 12px;
    resize: vertical;
    line-height: 1.55;
}
.asset-form-grid input:focus,
.asset-form-grid select:focus,
.asset-form-grid textarea:focus {
    border-color: #f05537;
    background: #fff;
    box-shadow: 0 0 0 3px rgba(240, 85, 55, 0.1);
}
.amount-input,
.duration-input {
    display: flex;
    align-items: center;
    border: 1px solid #e1e5ea;
    border-radius: 11px;
    background: #f8f9fb;
    overflow: hidden;
}
.amount-input:focus-within,
.duration-input:focus-within {
    border-color: #f05537;
    background: #fff;
    box-shadow: 0 0 0 3px rgba(240, 85, 55, 0.1);
}
.amount-input b {
    padding-left: 12px;
    color: #667085;
    font-size: 14px;
}
.amount-input input,
.duration-input input,
.duration-input select {
    border: 0 !important;
    border-radius: 0 !important;
    background: transparent !important;
    box-shadow: none !important;
}
.duration-input input {
    flex: 1;
}
.duration-input select {
    width: 86px;
    border-left: 1px solid #e1e5ea !important;
}
.advanced-toggle {
    display: grid;
    width: 100%;
    grid-template-columns: 42px 1fr 20px;
    align-items: center;
    gap: 10px;
    padding: 12px 14px;
    border: 1px solid #f5c9be !important;
    border-radius: 16px;
    color: #12141a;
    background: #fff7f4;
    text-align: left;
}
.advanced-toggle > span {
    display: grid;
    width: 40px;
    height: 32px;
    place-items: center;
    border-radius: 10px;
    color: #c83f28;
    background: #fff;
    font-size: 11px;
    font-weight: 800;
}
.advanced-toggle > div {
    display: grid;
    gap: 2px;
}
.advanced-toggle b {
    font-size: 13px;
}
.advanced-toggle small {
    color: #667085;
    font-size: 11px;
}
.asset-advanced {
    background: #fbfcfd;
}
.asset-editor-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
    padding: 14px 20px;
    border-top: 1px solid #e3e6eb;
    background: rgba(255, 255, 255, 0.97);
}
.asset-editor-footer > div {
    display: grid;
    gap: 2px;
    min-width: 0;
}
.asset-editor-footer small {
    color: #667085;
    font-size: 10.5px;
}
.asset-editor-footer strong {
    max-width: 290px;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 13px;
    white-space: nowrap;
}
.asset-save {
    display: flex;
    min-width: 150px;
    height: 46px;
    align-items: center;
    justify-content: center;
    gap: 7px;
    padding: 0 21px;
    border-radius: 999px !important;
    color: #fff !important;
    background: #12141a;
    font-size: 13px !important;
    font-weight: 800;
}
.asset-save:hover {
    background: #f05537;
}
.asset-save:disabled {
    color: #9ca3af !important;
    background: #e5e7eb;
    cursor: not-allowed;
}
.form-error {
    color: #b42318 !important;
    font-size: 11px !important;
}
@media (max-width: 720px) {
    .asset-editor {
        width: 100vw;
        height: 100vh;
        margin: 0;
        border-radius: 0;
    }
    .asset-form-grid {
        grid-template-columns: 1fr;
    }
    .asset-form-grid label.wide {
        grid-column: auto;
    }
    .asset-editor-footer > div {
        display: none;
    }
    .asset-save {
        width: 100%;
    }
}
/* Workspace readability v2: all secondary pages share one clear card hierarchy. */
.workspace {
    --muted: #475467;
    --faint: #5f6b7a;
}
.metrics article:not(.featured) {
    border-color: #e3e7ed;
    background: #fff;
}
.metrics article > span {
    color: #344054;
    font-size: 14px;
    font-weight: 750;
}
.metrics strong {
    font-size: 28px;
}
.metrics small {
    color: #475467;
    font-size: 13px;
    font-weight: 600;
}
.panel-head small {
    color: #475467;
    font-size: 12.5px;
    font-weight: 800;
    letter-spacing: 0.08em;
}
.panel-head h2 {
    font-size: 19px;
}
.head-actions button,
.text-btn,
.period,
.asset-filters button,
.asset-filters select {
    font-size: 13px;
    font-weight: 650;
}
.filters label,
.filters input,
.filters select,
.filters > button {
    color: #344054;
    font-size: 13px;
}
.account-cards b {
    font-size: 15px;
}
.account-cards small,
.account-cards em {
    color: #475467;
    font-size: 13px;
}
.account-detail > p {
    color: #475467;
    font-size: 13.5px;
}
.balance small {
    color: #475467;
    font-size: 13px;
}
.account-detail dl > div {
    font-size: 13.5px;
}
.schedule-row span b,
.schedule-row > strong {
    font-size: 14px;
}
.schedule-row time small,
.schedule-row span small {
    color: #475467;
    font-size: 12.5px;
}
.week {
    color: #475467;
    font-size: 12px;
}
.days button,
.calendar-note {
    font-size: 13px;
}
.legend,
.bar span,
.breakdown li {
    color: #475467;
    font-size: 12.5px;
}
.settings-layout nav b {
    font-size: 14px;
}
.settings-layout nav small {
    color: #475467;
    font-size: 12.5px;
    line-height: 1.4;
}
.form-note,
.setting-row small {
    color: #475467;
    font-size: 13px;
}
.setting-row b,
.settings-layout label {
    font-size: 13.5px;
}
.settings-layout input:not([type="checkbox"]),
.settings-layout select {
    font-size: 13px;
}
.catalog-grid b,
.catalog-grid strong {
    font-size: 14px;
}
.catalog-grid small {
    color: #475467;
    font-size: 12.5px;
}
.template-grid article > small {
    color: #475467;
    font-size: 12px;
}
.template-grid h2 {
    font-size: 16px;
}
.template-grid p,
.template-grid dl > div,
.template-grid button,
.template-grid .template-edit {
    color: #475467;
    font-size: 12.5px;
}
.base-rate,
.rates th,
.rates td {
    font-size: 13px;
}
.asset-summary > small,
.asset-summary > span,
.asset-summary footer {
    font-size: 12.5px;
}
.asset-summary-grid small {
    font-size: 12px;
}
.asset-summary-grid b {
    font-size: 14px;
}
.drawer p,
.drawer dl > div {
    font-size: 13.5px;
}
.drawer > small {
    font-size: 12px;
}
.asset-detail-head p,
.asset-hero-values small,
.asset-hero-values span,
.asset-value-grid small,
.asset-detail-section dl > div {
    font-size: 12.5px;
}
.modal label,
.modal input,
.modal textarea {
    font-size: 13.5px;
}
.asset-name-card label > span,
.asset-form-grid label > span {
    color: #475467;
    font-size: 13px;
}
.asset-name-card label small,
.asset-form-card header small,
.asset-form-grid label > span small,
.advanced-toggle small,
.asset-editor-footer small {
    color: #5f6b7a;
    font-size: 12px;
}
.asset-form-grid input,
.asset-form-grid select,
.asset-form-grid textarea,
.advanced-toggle b {
    font-size: 13.5px;
}
.asset-copy em,
.asset-value small,
.asset-value em {
    font-size: 12.5px;
}
/* Readability v3: Windows-first functional pages use a 13px secondary-copy floor. */
.workspace {
    --muted: #344054;
    --faint: #475467;
}
.panel {
    border-color: #dfe3e9;
}
.panel-head small {
    color: #344054;
    font-size: 13px;
}
.panel-head h2 {
    color: #101828;
    font-size: 20px;
}
.page-head > div > span {
    color: #344054;
    font-size: 14px;
    line-height: 1.55;
}
.workspace table th {
    color: #344054;
    font-size: 13.5px;
    font-weight: 750;
}
.workspace table td {
    color: #344054;
    font-size: 14px;
}
.workspace .item-name b {
    color: #101828;
    font-size: 14px;
}
.workspace .item-name small {
    color: #475467;
    font-size: 13px;
}
.workspace .status-pill {
    color: #344054;
    font-size: 13px;
    font-weight: 650;
}
.workspace .table-foot {
    color: #344054;
    font-size: 13px;
}
.account-detail h2 {
    color: #101828;
    font-size: 24px;
}
.account-detail > p,
.account-detail .balance small {
    color: #344054;
    font-size: 14px;
}
.account-detail .balance strong {
    font-size: 30px;
}
.account-detail dl > div {
    color: #344054;
    font-size: 14px;
}
.account-detail dt {
    font-weight: 650;
}
.account-detail dd {
    color: #101828;
    font-weight: 750;
}
.split-buttons a,
.split-buttons button {
    font-size: 13.5px;
    font-weight: 750;
}
.schedule-row {
    min-height: 74px;
}
.schedule-row time b {
    font-size: 18px;
}
.schedule-row time small,
.schedule-row span small {
    color: #475467;
    font-size: 13px;
}
.schedule-row span b,
.schedule-row > strong {
    color: #101828;
    font-size: 14.5px;
}
.week {
    color: #344054;
    font-size: 13px;
    font-weight: 750;
}
.days button {
    color: #344054;
    font-size: 13.5px;
    font-weight: 650;
}
.days button.selected {
    color: #fff;
    background: #12141a;
    box-shadow: 0 0 0 3px rgba(18, 20, 26, 0.12);
}
.days button.selected.marked:after {
    background: #fff;
}
.calendar-note {
    color: #344054;
    font-size: 13.5px;
}
.calendar-selection {
    margin-top: 14px;
    padding: 14px;
    border: 1px solid #e1e5ea;
    border-radius: 14px;
    background: #f8f9fb;
}
.calendar-selection header {
    display: flex;
    align-items: center;
    justify-content: space-between;
}
.calendar-selection header > div {
    display: grid;
    gap: 3px;
}
.calendar-selection header small {
    color: #475467;
    font-size: 12px;
}
.calendar-selection header b {
    color: #101828;
    font-size: 16px;
}
.calendar-selection header > span {
    padding: 5px 9px;
    border-radius: 999px;
    color: #344054;
    background: #fff;
    font-size: 12.5px;
    font-weight: 700;
}
.calendar-selection article {
    display: grid;
    grid-template-columns: 54px 1fr auto;
    align-items: center;
    gap: 10px;
    margin-top: 10px;
    padding: 10px;
    border-radius: 11px;
    background: #fff;
}
.calendar-selection article time {
    color: #b42318;
    font-size: 13px;
    font-weight: 800;
}
.calendar-selection article span {
    display: grid;
    gap: 2px;
}
.calendar-selection article b,
.calendar-selection article strong {
    color: #101828;
    font-size: 13.5px;
}
.calendar-selection article small,
.calendar-selection p {
    color: #475467;
    font-size: 13px;
}
.calendar-selection p {
    margin: 14px 0 2px;
    line-height: 1.55;
}
.period {
    min-height: 38px;
    padding: 0 34px 0 12px;
    border: 1px solid #d7dce3;
    color: #101828;
    background: #fff;
    font-size: 13.5px;
    font-weight: 700;
}
.legend {
    color: #344054;
    font-size: 13.5px;
    font-weight: 650;
}
.bar span {
    color: #344054;
    font-size: 12.5px;
    font-weight: 650;
}
.breakdown .donut b {
    color: #101828;
    font-size: 18px;
}
.breakdown .donut small {
    color: #475467;
    font-size: 12.5px;
}
.breakdown li {
    color: #344054;
    font-size: 14px;
}
.breakdown li span {
    font-weight: 650;
}
.breakdown li b {
    color: #101828;
    font-weight: 800;
}
.settings-layout nav button {
    border: 1px solid transparent;
}
.settings-layout nav button.active {
    border-color: #d9dee6;
    background: #f3f4f6;
}
.settings-layout nav b {
    color: #101828;
    font-size: 14.5px;
}
.settings-layout nav small {
    color: #475467;
    font-size: 13px;
}
.settings-layout form {
    border: 1px solid #dfe3e9;
    background: #fff;
}
.settings-layout form .form-note {
    color: #344054;
    font-size: 14px;
    line-height: 1.6;
}
.settings-layout form > label,
.settings-layout .form-grid label {
    color: #344054;
    font-size: 13.5px;
    font-weight: 700;
}
.settings-layout input:not([type="checkbox"]),
.settings-layout select {
    color: #101828;
    background: #fff;
    font-size: 14px;
}
.setting-row {
    border-color: #e1e5ea;
}
.setting-row b {
    color: #101828;
    font-size: 14px;
}
.setting-row small {
    color: #475467;
    font-size: 13px;
    line-height: 1.5;
}
.drawer .review-source {
    margin-top: 18px;
    padding: 14px;
    border-radius: 13px;
    background: #fff7ed;
}
.drawer .review-source > small {
    color: #9a3412;
    font-size: 12px;
    font-weight: 800;
}
.drawer .review-source p {
    margin-top: 7px;
    color: #101828;
    font-size: 13.5px;
    line-height: 1.55;
}
.drawer .review-source em {
    display: block;
    margin-top: 8px;
    color: #b42318;
    font-size: 12.5px;
    font-style: normal;
}
.review-actions {
    display: grid;
    grid-template-columns: 1fr 1.5fr;
    gap: 8px;
}
.review-actions .secondary,
.review-actions .primary {
    width: 100%;
    font-size: 13px;
}

/* Readability v4: high-frequency finance pages keep functional text at a
   Windows-safe size and use selected state, not low-contrast decoration, to
   communicate interaction changes. */
.workspace .panel-head h2 {
    color: #101828;
    font-size: 21px;
    font-weight: 800;
    line-height: 1.3;
}
.workspace .panel-head small {
    color: #344054;
    font-size: 13px;
}
.workspace .head-actions button,
.workspace .text-btn,
.workspace .month-buttons button {
    min-height: 38px;
    color: #344054;
    font-size: 14px;
    font-weight: 700;
}
.workspace .pending-filter {
    padding: 0 12px;
    border: 1px solid #d7dce3;
    border-radius: 999px;
    background: #fff;
}
.workspace .pending-filter.active {
    color: #fff;
    border-color: #b54708;
    background: #b54708;
}
.workspace .pending-filter:disabled {
    color: #667085;
    background: #f3f4f6;
    cursor: default;
    opacity: 1;
}
.workspace .account-detail {
    color: #101828;
}
.workspace .account-detail > p,
.workspace .account-detail dt,
.workspace .account-detail .balance small {
    color: #344054;
    font-size: 14px;
    line-height: 1.55;
}
.workspace .account-detail dd {
    color: #101828;
    font-size: 14px;
}
.workspace .schedule-row {
    color: #101828;
}
.workspace .schedule-row time small,
.workspace .schedule-row span small {
    color: #344054;
    font-size: 13.5px;
    line-height: 1.5;
}
.workspace .schedule-row span b,
.workspace .schedule-row > strong {
    color: #101828;
    font-size: 15px;
}
.workspace .week,
.workspace .days button,
.workspace .calendar-note {
    color: #344054;
    font-size: 14px;
}
.workspace .days button {
    min-width: 36px;
    min-height: 36px;
}
.workspace .calendar-selection {
    border-color: #d7dce3;
    background: #f7f8fa;
    animation: calendar-selection-in 180ms ease-out;
}
.workspace .calendar-selection header small,
.workspace .calendar-selection article small,
.workspace .calendar-selection p {
    color: #344054;
    font-size: 13.5px;
}
.workspace .calendar-selection header b,
.workspace .calendar-selection article b,
.workspace .calendar-selection article strong {
    color: #101828;
}
.workspace .calendar-add {
    min-height: 40px;
    margin-top: 10px;
    padding: 0 14px;
    border: 1px solid #d7dce3;
    border-radius: 999px;
    color: #101828;
    background: #fff;
    font-size: 13.5px;
    font-weight: 750;
}
.workspace .period {
    min-height: 42px;
    color: #101828;
    background-color: #fff;
    font-size: 14px;
    font-weight: 700;
}
.workspace .legend,
.workspace .bar span {
    color: #344054;
    font-size: 13.5px;
}
.workspace .bar span {
    white-space: nowrap;
}
.workspace .breakdown .donut b {
    color: #101828;
    font-size: 20px;
}
.workspace .breakdown .donut small,
.workspace .breakdown li {
    color: #344054;
    font-size: 14px;
}
.workspace .breakdown .breakdown-empty {
    display: block;
    padding: 14px;
    border: 1px dashed #cfd5df;
    border-radius: 12px;
    background: #f8f9fb;
    text-align: center;
}
.workspace .settings-layout nav b {
    color: #101828;
    font-size: 15px;
}
.workspace .settings-layout nav small,
.workspace .settings-layout .form-note,
.workspace .setting-row small {
    color: #344054;
    font-size: 13.5px;
    line-height: 1.55;
}
.workspace .settings-layout form > label,
.workspace .settings-layout .form-grid label,
.workspace .setting-row b,
.workspace .settings-layout input:not([type="checkbox"]),
.workspace .settings-layout select {
    color: #101828;
    font-size: 14px;
}
@keyframes calendar-selection-in {
    from { opacity: 0.5; transform: translateY(4px); }
    to { opacity: 1; transform: translateY(0); }
}
@media (prefers-reduced-motion: reduce) {
    .workspace .calendar-selection { animation: none; }
}

/* Windows browser readability baseline. Keep text off synthetic fonts and
   fractional glyph sizes so the live 1x/125% view matches captured images. */
.workspace {
    font-family: "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", system-ui, sans-serif;
    font-size: 14px;
    line-height: 1.5;
    font-synthesis: none;
    text-rendering: optimizeLegibility;
}
.shell {
    border: 1px solid #dfe3e9;
    box-shadow: 0 18px 55px rgba(18, 20, 26, 0.1);
}
.workspace :is(button, input, select, textarea) {
    font-family: inherit;
}
.workspace .panel-head .panel-title {
    color: #101828 !important;
    -webkit-text-fill-color: #101828 !important;
    font-family: "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", system-ui, sans-serif;
    font-size: 21px;
    font-weight: 700;
    font-synthesis: none;
    line-height: 1.35;
    opacity: 1 !important;
    filter: none;
    mix-blend-mode: normal;
    text-shadow: none !important;
}
.catalog-help {
    margin: 12px 0 0;
    color: #344054;
    font-size: 14px;
    line-height: 1.65;
}
.catalog-grid button {
    min-height: 76px;
    border-color: #dfe3e9;
}
.catalog-grid b,
.catalog-grid strong {
    color: #101828;
    font-size: 14px;
    font-weight: 700;
}
.catalog-grid small {
    color: #475467;
    font-size: 13px;
}
.catalog-empty,
.template-empty {
    display: grid;
    min-height: 220px;
    place-items: center;
    align-content: center;
    gap: 8px;
    grid-column: 1 / -1;
    color: #475467;
    border: 1px dashed #cfd5df;
    border-radius: 16px;
    background: #f8f9fb;
    text-align: center;
}
.catalog-empty b,
.template-empty b {
    color: #101828;
    font-size: 16px;
}
.catalog-empty span,
.template-empty span {
    font-size: 13px;
}
.catalog-empty button,
.template-empty button {
    width: auto;
    height: 42px;
    margin-top: 4px;
    padding: 0 16px;
    border-radius: 999px;
    color: #fff;
    background: #12141a;
    font-size: 13px;
    font-weight: 700;
}
.recommended-template-list {
    display: grid;
    width: min(680px, 100%);
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
    margin-top: 8px;
}
.template-empty .recommended-template-list button {
    display: grid;
    width: 100%;
    height: auto;
    min-height: 68px;
    margin: 0;
    padding: 12px 14px;
    gap: 4px;
    border: 1px solid #e0e4eb;
    border-radius: 12px;
    color: #12141a;
    background: #fff;
    text-align: left;
}
.template-empty .recommended-template-list button small {
    color: #687181;
    font-weight: 500;
}
.category-children {
    margin-top: 18px;
    padding: 14px;
    border: 1px solid #dfe3e9;
    border-radius: 14px;
    background: #f8f9fb;
}
.category-children > small {
    color: #344054;
    font-size: 12px;
    font-weight: 800;
}
.category-children > div {
    display: flex;
    flex-wrap: wrap;
    gap: 7px;
    margin-top: 10px;
}
.category-children span {
    padding: 6px 9px;
    border-radius: 999px;
    color: #101828;
    background: #fff;
    font-size: 13px;
    font-weight: 600;
}
.template-grid .template-edit {
    min-width: 58px;
    height: 36px;
    color: #344054;
    font-size: 13px;
    font-weight: 700;
}
.rates :is(th, td),
.base-rate {
    color: #344054;
    font-size: 14px;
}
.rates td strong,
.base-rate b {
    color: #101828;
}
.rate-help {
    color: #344054;
    font-size: 13px;
    line-height: 1.65;
}
.status dl > div,
.service-status > div {
    color: #344054;
    font-size: 14px;
}
.status dt,
.service-status dt {
    color: #344054;
    font-weight: 600;
}
.status dd,
.service-status dd {
    color: #101828;
    font-weight: 700;
}
.ai-config-card {
    display: grid;
    grid-template-columns: 52px 1fr auto;
    align-items: center;
    gap: 14px;
    padding: 16px;
    border: 1px solid #dfe3e9;
    border-radius: 16px;
    background: #f8f9fb;
}
.ai-config-card.ready {
    border-color: #b7e4cf;
    background: #f0fbf5;
}
.ai-config-icon {
    display: grid;
    width: 48px;
    height: 48px;
    place-items: center;
    border-radius: 14px;
    color: #fff;
    background: #7c3aed;
}
.ai-config-card small {
    color: #7c3aed;
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.08em;
}
.ai-config-card h3 {
    margin: 3px 0;
    font-size: 17px;
}
.ai-config-card p {
    margin: 0;
    color: #475467;
    font-size: 13px;
}
.ai-config-card > span {
    padding: 6px 9px;
    border-radius: 999px;
    color: #344054;
    background: #fff;
    font-size: 12px;
    font-weight: 700;
}
.ai-status {
    margin-top: 4px;
}
.asset-name-card label > span,
.asset-form-grid label > span,
.asset-name-card label small,
.asset-form-card header small,
.asset-form-grid label > span small,
.advanced-toggle small,
.asset-editor-footer small {
    font-size: 13px;
}
.about-hero > span,
.about-hero > b,
.status footer button {
    font-size: 13px;
}

/* Readability v5: establish one typography contract for cards and every
   nested detail surface. Component-specific colors may still communicate
   state, while ordinary copy never falls back to low-contrast decoration. */
.workspace {
    --card-ink: #101828;
    --card-copy: #344054;
    --card-meta: #475467;
}
.workspace :is(
    .panel,
    .metrics article:not(.featured),
    .account-detail,
    .settings-layout form,
    .template-grid article,
    .catalog-grid button,
    .asset-row,
    .calendar-selection,
    .category-children,
    .ai-config-card,
    .modal,
    .drawer,
    .account-menu,
    .asset-name-card,
    .asset-form-card
) {
    font-family: "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", system-ui, sans-serif;
    font-synthesis: none;
    text-rendering: auto;
}
.workspace :is(
    .panel-head h2,
    .modal h2,
    .drawer h2,
    .account-detail h2,
    .template-grid h2,
    .ai-config-card h3,
    .asset-form-card header b,
    .asset-name-card label > span
) {
    color: var(--card-ink) !important;
    -webkit-text-fill-color: var(--card-ink) !important;
    font-weight: 700;
    opacity: 1 !important;
    filter: none;
    mix-blend-mode: normal;
    text-shadow: none !important;
}
.workspace :is(
    .panel-head small,
    .item-name small,
    .account-detail > p,
    .account-detail dt,
    .schedule-row small,
    .settings-layout nav small,
    .settings-layout .form-note,
    .setting-row small,
    .catalog-grid small,
    .template-grid p,
    .template-grid dl,
    .asset-copy small,
    .asset-value small,
    .calendar-selection small,
    .ai-config-card p,
    .modal p,
    .drawer p,
    .asset-form-card small,
    .asset-name-card small
) {
    color: var(--card-copy);
    -webkit-text-fill-color: currentColor;
    font-size: 13.5px;
    font-weight: 500;
    line-height: 1.55;
    opacity: 1;
}
.workspace :is(
    .item-name b,
    .account-detail dd,
    .schedule-row b,
    .settings-layout nav b,
    .setting-row b,
    .catalog-grid b,
    .catalog-grid strong,
    .asset-copy b,
    .asset-value strong,
    .calendar-selection b,
    .calendar-selection strong,
    .category-children span
) {
    color: var(--card-ink);
    -webkit-text-fill-color: currentColor;
    font-weight: 700;
    opacity: 1;
}
.workspace :is(
    .filters label,
    .settings-layout label,
    .modal label,
    .form-grid label,
    .asset-form-grid label
) {
    color: var(--card-copy);
    font-size: 13.5px;
    font-weight: 650;
    opacity: 1;
}
.workspace :is(
    input,
    select,
    textarea,
    table th,
    table td,
    button
) {
    opacity: 1;
    text-shadow: none;
}
.workspace :is(input, select, textarea, table td) {
    color: var(--card-ink);
    -webkit-text-fill-color: currentColor;
    font-size: 14px;
}
.workspace table th {
    color: var(--card-copy);
    -webkit-text-fill-color: currentColor;
    font-size: 13.5px;
    font-weight: 700;
}

/* Teleported surfaces live under <body>, outside .workspace. Keep these rules
   unprefixed so detail drawers and secondary/tertiary editors retain the same
   readable hierarchy after Vue moves them. */
.drawer,
.modal,
.asset-editor-layer {
    font-family: "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", system-ui, sans-serif;
    font-synthesis: none;
    text-rendering: auto;
}
.drawer :is(h2, h3),
.modal :is(h2, h3, h4),
.asset-editor-layer :is(h1, h2, h3, h4) {
    color: #101828 !important;
    -webkit-text-fill-color: #101828 !important;
    font-weight: 700 !important;
    opacity: 1 !important;
    filter: none !important;
    mix-blend-mode: normal !important;
    text-shadow: none !important;
}
.drawer h2,
.modal h2,
.asset-editor-head h2 {
    font-size: 22px !important;
    line-height: 1.35;
}
.drawer :is(p, dt, small),
.modal :is(p, label, small),
.asset-editor-layer :is(p, label, small) {
    color: #344054;
    -webkit-text-fill-color: currentColor;
    font-size: 13.5px;
    font-weight: 500;
    line-height: 1.55;
    opacity: 1;
}
.drawer :is(dd, b, strong),
.modal :is(b, strong),
.asset-editor-layer :is(b, strong) {
    color: #101828;
    -webkit-text-fill-color: currentColor;
    font-weight: 700;
    opacity: 1;
}
.drawer > small,
.modal header small,
.asset-editor-head small {
    color: #344054 !important;
    -webkit-text-fill-color: currentColor !important;
    font-size: 13px;
    font-weight: 800;
    opacity: 1 !important;
}
.drawer :is(button, input, select, textarea),
.modal :is(button, input, select, textarea),
.asset-editor-layer :is(button, input, select, textarea) {
    font-family: inherit;
    opacity: 1;
    text-shadow: none;
}
.drawer :is(input, select, textarea),
.modal :is(input, select, textarea),
.asset-editor-layer :is(input, select, textarea) {
    color: #101828;
    -webkit-text-fill-color: currentColor;
    font-size: 14px;
}
</style>
