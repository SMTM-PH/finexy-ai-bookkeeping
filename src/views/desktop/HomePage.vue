<template>
    <div class="finance-home">
        <div class="app">
            <header class="topbar">
                <div class="brand"><span class="logo">F</span>Finexy</div>
                <nav class="tabs" aria-label="主导航">
                    <router-link class="active" to="/">总览</router-link>
                    <router-link to="/transaction/list?pageType=1&dateType=7">流水</router-link>
                    <router-link to="/account/list">管理</router-link>
                    <router-link to="/schedule/list">计划</router-link>
                    <router-link to="/user/settings">账户</router-link>
                    <router-link to="/statistics/transaction">报表</router-link>
                </nav>
                <div class="top-actions">
                    <GlobalLedgerSwitcher
                        :ledgers="ledgersStore.allLedgers"
                        :selected-id="selectedLedgerId"
                        :disabled="loadingOverview || ledgersStore.featureDisabled"
                        @select="onGlobalLedgerSelect"
                    />
                    <button class="ic" :class="{active:utilityPanel==='search'}" type="button" aria-label="搜索" :aria-expanded="utilityPanel==='search'" @click="toggleUtilityPanel('search')"><v-icon :icon="mdiMagnify" size="17"/></button>
                    <button class="ic" :class="{active:utilityPanel==='notifications'}" type="button" aria-label="通知" :aria-expanded="utilityPanel==='notifications'" @click="toggleUtilityPanel('notifications')"><v-icon :icon="mdiBellOutline" size="17"/><span v-if="pendingSummary && (pendingSummary.knownCount > 0 || pendingSummary.exactTotal === null)" class="dot"></span></button>
                    <button class="ic" :class="{active:utilityPanel==='help'}" type="button" aria-label="帮助与信息" :aria-expanded="utilityPanel==='help'" @click="toggleUtilityPanel('help')"><v-icon :icon="mdiInformationOutline" size="17"/></button>
                    <button class="user" type="button" :aria-expanded="accountMenuOpen" @click="toggleAccountMenu">
                        <span class="avatar">{{ currentUserInitial }}</span>
                        <span class="uinfo"><b>{{ currentUserName }}</b><small>{{ selectedLedgerName }}</small></span>
                        <v-icon :icon="mdiChevronDown" size="14"/>
                    </button>
                    <transition name="account-drop">
                        <section v-if="utilityPanel==='search'" class="utility-panel search-panel">
                            <header><div><b>全局搜索</b><small>查找交易或前往常用功能</small></div><button type="button" aria-label="关闭" @click="utilityPanel=null">×</button></header>
                            <label class="utility-search"><v-icon :icon="mdiMagnify" size="17"/><input v-model.trim="globalSearchQuery" autofocus placeholder="输入订单号、活动或状态" @keydown.enter="submitGlobalSearch"></label>
                            <div class="utility-results">
                                <small>{{ globalSearchQuery ? '匹配的近期活动' : '近期活动' }}</small>
                                <button v-for="row in globalSearchResults" :key="row.id" type="button" @click="openSearchResult(row)"><i :style="{background:row.color}">{{ row.icon }}</i><span><b>{{ row.name }}</b><small>{{ row.id }} · {{ row.status }}</small></span><strong>{{ row.price }}</strong></button>
                                <p v-if="!globalSearchResults.length">没有找到匹配内容</p>
                            </div>
                            <button class="utility-primary" type="button" @click="submitGlobalSearch">在全部流水中搜索</button>
                        </section>
                    </transition>
                    <transition name="account-drop">
                        <section v-if="utilityPanel==='help'" class="utility-panel help-panel">
                            <header><div><b>帮助与信息</b><small>了解功能与应用状态</small></div><button type="button" aria-label="关闭" @click="utilityPanel=null">×</button></header>
                            <router-link to="/about"><v-icon :icon="mdiInformationOutline" size="18"/><span><b>关于 Finexy</b><small>版本、隐私与运行状态</small></span><v-icon :icon="mdiChevronRight" size="16"/></router-link>
                            <router-link to="/app/settings"><v-icon :icon="mdiCogOutline" size="18"/><span><b>应用设置</b><small>语言、显示与数据管理</small></span><v-icon :icon="mdiChevronRight" size="16"/></router-link>
                            <router-link to="/schedule/list"><v-icon :icon="mdiHelpCircleOutline" size="18"/><span><b>计划与提醒</b><small>管理周期交易和待办事项</small></span><v-icon :icon="mdiChevronRight" size="16"/></router-link>
                        </section>
                    </transition>
                    <transition name="account-drop">
                        <div v-if="accountMenuOpen" class="account-menu">
                            <div class="account-current">
                                <span class="account-avatar">{{ currentUserInitial }}</span>
                                <div><small>当前账号</small><strong>{{ currentUserName }}</strong></div>
                                <i>使用中</i>
                            </div>
                            <router-link to="/user/settings"><v-icon :icon="mdiCogOutline" size="17"/><span><b>账号设置</b><small>资料、安全与显示偏好</small></span></router-link>
                            <button type="button" @click="switchAccount"><v-icon :icon="mdiSwapHorizontal" size="17"/><span><b>切换账号</b><small>登录另一个已有账号</small></span></button>
                            <button type="button" :disabled="accountActionBusy" @click="logoutAccount"><v-icon :icon="mdiLogout" size="17"/><span><b>{{ accountActionBusy ? '正在退出' : '退出登录' }}</b><small>结束当前账号会话</small></span></button>
                        </div>
                    </transition>
                </div>
            </header>

            <div class="body">
                <aside class="rail">
                    <div class="stack">
                        <router-link class="ric" to="/category/list" aria-label="交易分类" title="交易分类"><v-icon :icon="mdiLayersOutline" size="18"/></router-link>
                        <router-link class="ric" to="/tag/list" aria-label="交易标签" title="交易标签"><v-icon :icon="mdiTagOutline" size="18"/></router-link>
                        <router-link class="ric" to="/template/list" aria-label="交易模板" title="交易模板"><v-icon :icon="mdiFileDocumentOutline" size="18"/></router-link>
                        <router-link class="ric" to="/exchange_rates" aria-label="汇率数据" title="汇率数据"><v-icon :icon="mdiWeb" size="18"/></router-link>
                        <router-link class="ric" to="/product/assets" aria-label="产品资产" title="产品资产"><v-icon :icon="mdiPackageVariantClosed" size="18"/></router-link>
                        <router-link class="ric" to="/savings/goals" aria-label="存钱计划" title="存钱计划"><v-icon :icon="mdiPiggyBankOutline" size="18"/></router-link>
                        <router-link class="ric" to="/ledger/manage" aria-label="账本" title="账本"><v-icon :icon="mdiBookOpenOutline" size="18"/></router-link>
                    </div>
                    <div class="stack bottom">
                        <router-link class="ric" to="/app/settings" aria-label="应用设置" title="应用设置"><v-icon :icon="mdiCogOutline" size="18"/></router-link>
                        <router-link class="ric" to="/about" aria-label="帮助与关于" title="帮助与关于"><v-icon :icon="mdiHelpCircleOutline" size="18"/></router-link>
                    </div>
                </aside>

                <main>
                    <h1>{{ homeGreeting }}，{{ currentUserName }}</h1>
                    <ReleaseUpdateNotice />

                    <section class="ledger-context" aria-label="当前账本信息">
                        <div class="ledger-context-mark"><v-icon :icon="mdiBookOpenVariant" size="18"/></div>
                        <div class="ledger-context-copy">
                            <small>当前账本</small>
                            <strong>{{ selectedLedgerName }}</strong>
                            <span>{{ selectedLedgerSummary }}</span>
                        </div>
                        <router-link to="/ledger/manage">管理账本<v-icon :icon="mdiChevronRight" size="15"/></router-link>
                    </section>

                    <p v-if="overviewError" class="home-data-error" role="alert">{{ overviewError }} <button type="button" @click="reload(true, false)">重试读取</button></p>
                    <label class="summary-currency">收支与资产小计币种<select v-model="summaryCurrency" aria-label="收支与资产小计币种"><option v-for="code in summaryCurrencies" :key="code" :value="code">{{ code }}</option></select><span>只统计同币种，不折算其他币种</span></label>
                    <p v-if="monthlySummary.unresolvedCount" class="home-data-error" role="status">{{ monthlySummary.unresolvedCount }} 笔收支缺少有效账户币种或金额，当前小计未完整计算。</p>
                    <p v-if="walletIncompleteCount" class="home-data-error" role="status">{{ walletIncompleteCount }} 个账户缺少有效币种或整数余额，余额小计未完整计算。</p>

                    <div class="grid">
                        <div class="home-left-column">
                            <section class="card balance-card">
                                <div class="bhead">
                                    <span>同币种净余额</span>
                                    <div class="currency-switch">
                                        <button class="cur" :class="{active:balanceCurrencyMenuOpen}" type="button" :aria-expanded="balanceCurrencyMenuOpen" @click="balanceCurrencyMenuOpen=!balanceCurrencyMenuOpen"><span class="flag" :class="currencyFlag(balanceCurrency)"></span>{{ balanceCurrency }} <v-icon :icon="mdiChevronDown" size="12"/></button>
                                        <transition name="account-drop"><div v-if="balanceCurrencyMenuOpen" class="balance-menu">
                                            <small>余额显示币种</small>
                                            <button v-for="currency in balanceCurrencies" :key="currency" type="button" :class="{active:balanceCurrency===currency}" @click="selectBalanceCurrency(currency)"><span class="flag" :class="currencyFlag(currency)"></span><span><b>{{ currency }}</b><small>同币种账户小计 · 不折算</small></span><v-icon v-if="balanceCurrency===currency" :icon="mdiCheck" size="16"/></button>
                                            <div class="balance-menu-actions"><button type="button" @click="toggleBalanceVisibility"><v-icon :icon="showAmountInHomePage?mdiEyeOffOutline:mdiEyeOutline" size="16"/>{{ showAmountInHomePage?'隐藏金额':'显示金额' }}</button><button type="button" @click="refreshBalance"><v-icon :icon="mdiRefresh" size="16"/>刷新余额</button></div>
                                        </div></transition>
                                    </div>
                                </div>
                                <div class="amount">{{ loadingOverview ? '—' : balanceDisplayAmount }}</div>
                                <div class="trend balance-meta"><span>{{ balanceAccountSummary }}</span></div>
                                <div class="bactions">
                                    <button class="btn-dark" @click="openQuickTransaction(TransactionType.Expense)"><v-icon :icon="mdiMinusCircleOutline" size="16"/>记录支出</button>
                                    <button class="btn-light" @click="openQuickTransaction(TransactionType.Income)"><v-icon :icon="mdiPlusCircleOutline" size="16"/>记录收入</button>
                                </div>
                                <div class="wallets">
                                    <div class="whead"><b>钱包</b><span>|&nbsp;&nbsp;共 {{ displayAccountCount }} 个账户</span><button class="wallet-add" type="button" @click="openAccountEditor()">+ 添加账户</button></div>
                                    <section v-if="activeWallet" class="wallet-carousel" aria-label="钱包账户卡片">
                                        <WalletHoloCard :account="activeWallet.raw" :ledger-id="selectedLedgerId" :category-name="activeWallet.note" :display-amount="showAmountInHomePage?activeWallet.amount:'******'" :refresh-key="walletAppearanceRefresh" @navigate="direction => navigateWallet(direction < 0 ? -1 : 1)" @details="openWalletDetails(activeWallet.raw)" />
                                        <div class="wallet-pager">
                                            <button type="button" aria-label="上一张钱包卡片" :disabled="walletPosition <= 0" @click="navigateWallet(-1)"><v-icon :icon="mdiChevronLeft" size="20"/></button>
                                            <span role="status" aria-live="polite" aria-atomic="true">{{ walletPosition + 1 }} / {{ displayWallets.length }} <small>个可见账户</small></span>
                                            <button type="button" aria-label="下一张钱包卡片" :disabled="walletPosition >= displayWallets.length - 1" @click="navigateWallet(1)"><v-icon :icon="mdiChevronRight" size="20"/></button>
                                        </div>
                                    </section>
                                    <div v-else class="wallet-empty" role="status"><span>{{ loadingOverview ? '正在读取账户…' : overviewError ? '账户暂不可用，请重试读取。' : '还没有可用账户' }}</span><button v-if="!loadingOverview && !overviewError" type="button" @click="openAccountEditor()">添加第一个账户</button></div>
                                    <router-link class="wallet-view-all" to="/account/list">查看全部 {{ displayAccountCount }} 个账户</router-link>
                                </div>
                            </section>
                            <div class="col-left">
                                <section v-if="featuredSavingsGoal" class="card savings-goal-card" role="button" tabindex="0" :aria-label="`查看存钱目标：${featuredSavingsGoal.name}`" @click="router.push('/savings/goals')" @keyup.enter="router.push('/savings/goals')">
                                    <div class="savings-goal-head">
                                        <div><small>SAVINGS GOAL</small><h3>{{ featuredSavingsGoal.name }}</h3></div>
                                        <span>{{ savingsGoalsStore.goals.length > 1 ? `${savingsGoalsStore.goals.length} 个目标` : featuredSavingsGoal.achieved ? '已达成' : '进行中' }}</span>
                                    </div>
                                    <div class="savings-goal-amount"><strong>{{ formatBudgetMoney(featuredSavingsGoal.savedAmount) }}</strong><span>/ {{ formatBudgetMoney(featuredSavingsGoal.targetAmount) }}</span></div>
                                    <div class="savings-goal-progress" role="img" :aria-label="`目标进度 ${featuredSavingsGoalProgress.toFixed(1)}%`"><i :style="{width:`${featuredSavingsGoalProgress}%`}"></i></div>
                                    <footer><span>已完成 {{ featuredSavingsGoalProgress.toFixed(0) }}%</span><button type="button" @click.stop="router.push('/savings/goals')">查看计划<v-icon :icon="mdiChevronRight" size="15"/></button></footer>
                                </section>
                                <section v-if="!budgetError && !loadingOverview && !overviewError" class="card limit" role="button" tabindex="0" aria-label="查看当前账本月度预算" @click="router.push('/schedule/list?panel=budget')" @keydown.enter="router.push('/schedule/list?panel=budget')" @keydown.space.prevent="router.push('/schedule/list?panel=budget')">
                                    <h3>本月预算 · {{ budgetCurrency }}</h3>
                                    <div v-if="monthlyBudget && showAmountInHomePage && budgetExpense !== null" class="limitbar" aria-hidden="true"><div class="used" :style="{width: `${Math.min(Math.max(budgetPercentage,0),100)}%`}"></div><div class="rest"></div></div>
                                    <div class="limitmeta"><span><b>{{ budgetExpense === null ? '未完整计算' : formatBudgetLimitMoney(budgetExpense) }}</b> 同币种支出</span><b>{{ monthlyBudget ? formatBudgetLimitMoney(monthlyBudget.amount) : '未设置预算' }}</b></div>
                                    <p v-if="monthlyBudget && budgetExpense !== null && showAmountInHomePage" class="scope-copy">已用 {{ budgetPercentage.toFixed(1) }}% · {{ budgetExpense > monthlyBudget.amount ? '超支' : '剩余' }} {{ formatBudgetLimitMoney(Math.abs(monthlyBudget.amount - budgetExpense)) }}</p>
                                    <p v-if="monthlySummary.totals.some(item => item.currency !== budgetCurrency) || monthlySummary.unresolvedCount" class="scope-copy">只比较 {{ budgetCurrency }} 支出，其他币种不折算；缺失数据时不计算使用率。</p>
                                </section>
                                <p v-if="budgetError" class="home-data-error" role="alert">{{ budgetError }} <button type="button" @click="reload(true, false)">重试预算读取</button></p>
                                <LedgerPendingPanel compact @open="openPendingTarget" />
                            </div>
                        </div>
                        <div class="home-right-column">
                            <section class="panel stats">
                                <div class="stat orange">
                                    <div class="shead"><span>本月结余</span><i class="sic"><v-icon :icon="mdiWalletOutline" size="15"/></i></div>
                                    <strong>{{ thisMonthNet }}</strong>
                                </div>
                                <div class="stat">
                                    <div class="shead"><span>本月支出</span><i class="sic"><v-icon :icon="mdiCreditCardOutline" size="15"/></i></div>
                                    <strong>{{ thisMonthExpense }}</strong>
                                </div>
                                <div class="stat">
                                    <div class="shead"><span>本月收入</span><i class="sic"><v-icon :icon="mdiCashMultiple" size="15"/></i></div>
                                    <strong>{{ thisMonthIncome }}</strong>
                                </div>
                                <div class="stat">
                                    <div class="shead"><span>资产余额</span><i class="sic"><v-icon :icon="mdiSackPercent" size="15"/></i></div>
                                    <strong>{{ homeTotalAssets }}</strong>
                                </div>
                            </section>

                            <section class="card chart-card">
                                <h3>近八个月同币种收支 <small>{{ summaryCurrency }}</small></h3>
                                <p v-if="chartIncompleteCount" class="scope-copy" role="status">{{ chartIncompleteCount }} 项趋势数据缺少有效账户币种、分类或金额，趋势未完整计算。</p>
                                <p v-if="loadingOverview" role="status">正在读取收支趋势…</p>
                                <p v-else-if="overviewError" role="status">趋势暂不可用，请重试读取。</p>
                                <p v-else-if="!chartMaximum" role="status">当前币种与时间范围暂无收支数据。</p>
                                <p v-if="!loadingOverview && !overviewError && chartAmounts.some(item => item.incomeAmount < 0 || item.expenseAmount < 0)" class="scope-copy">柱高显示金额绝对值，退款后的负净额见下方金额表。</p>
                                <div v-if="!loadingOverview && !overviewError && chartMaximum" class="plot-wrap">
                                    <div class="plot-head"><b>收支趋势</b><span class="legend"><i class="b"></i>收入<i class="k"></i>支出</span></div>
                                    <div class="plot">
                                        <div v-for="top in [0,20,40,60,80]" :key="top" class="gl" :style="{top: `calc((100% - 24px) * ${top / 100})`}"></div><div class="gl gl-bottom"></div>
                                        <span v-for="(label,index) in chartYAxisLabels" :key="`${label}-${index}`" class="ylab" :style="{top: `calc((100% - 24px) * ${index / 5})`}">{{ label }}</span><span class="ylab ylab-bottom">0</span>
                                        <div class="bars"><div v-for="bar in chartBars" :key="bar.month" class="grp"><i class="p" :style="{height: `${bar.profit}%`}"></i><i class="l" :style="{height: `${bar.loss}%`}"></i><span>{{ bar.month }}</span></div></div>
                                    </div>
                                </div>
                                <details v-if="!loadingOverview && !overviewError" class="trend-values"><summary>查看趋势金额</summary><table><thead><tr><th>月份</th><th>收入 · {{ summaryCurrency }}</th><th>支出 · {{ summaryCurrency }}</th></tr></thead><tbody><tr v-for="row in chartAmounts" :key="row.yearMonth"><td>{{ row.yearMonth }}</td><td>{{ formatSummaryMoney(row.incomeAmount) }}</td><td>{{ formatSummaryMoney(row.expenseAmount) }}</td></tr></tbody></table></details>
                            </section>
                            <section class="card activities">
                                <div class="ahead"><h3>近期活动</h3><span v-if="selectedActivityCount" class="selected-summary">已选择 {{ selectedActivityCount }} 笔<button type="button" @click="clearActivitySelection">取消选择</button></span><label class="searchbox"><v-icon :icon="mdiMagnify" size="14"/><input v-model.trim="activityQuery" placeholder="搜索流水" aria-label="搜索近期活动"></label><button class="filter" :class="{active:activityFilterOpen}" :aria-expanded="activityFilterOpen" @click="activityFilterOpen=!activityFilterOpen"><v-icon :icon="mdiFilterOutline" size="14"/>筛选</button></div>
                                <transition name="account-drop"><div v-if="activityFilterOpen" class="activity-filters"><span>交易类型</span><button v-for="item in activityTypes" :key="item.value" type="button" :class="{active:activityType===item.value}" @click="activityType=item.value">{{ item.label }}</button><button class="reset-filter" type="button" @click="resetActivityFilters">清除</button></div></transition>
                                <div class="activity-table-wrap"><table><thead><tr><th class="check-col"><input v-model="allActivitiesSelected" :indeterminate="someActivitiesSelected" type="checkbox" class="cb" aria-label="全选近期活动"></th><th>流水号</th><th>活动</th><th>类型</th><th>金额</th><th>状态</th><th>日期</th><th class="more-col"></th></tr></thead>
                                    <tbody><tr v-for="row in filteredActivities" :key="`${row.id}-${row.name}`" tabindex="0" @click="openActivity(row)" @keyup.enter="openActivity(row)"><td @click.stop><input v-model="row.selected" type="checkbox" class="cb" :aria-label="`选择 ${row.id}`"></td><td class="id">{{ row.id }}</td><td><span class="act"><span class="aic" :style="{background:row.color}">{{ row.icon }}</span>{{ row.name }}</span></td><td><span class="type-pill">{{ row.typeLabel }}</span></td><td class="price" :class="{income:row.type==='income'}">{{ row.price }}</td><td><span class="status"><i :class="row.statusClass"></i>{{ row.status }}</span></td><td class="date">{{ row.date }}</td><td class="row-actions"><button class="more" :aria-label="`${row.id} 更多操作`" :aria-expanded="activityMenuId===row.id" @click.stop="activityMenuId=activityMenuId===row.id?null:row.id"><v-icon :icon="mdiDotsHorizontal" size="16"/></button><div v-if="activityMenuId===row.id" class="activity-menu"><button type="button" @click.stop="openActivity(row)">查看详情</button><button type="button" @click.stop="viewActivityInList(row)">在流水中查看</button></div></td></tr><tr v-if="!filteredActivities.length"><td class="activity-empty" colspan="8" role="status"><b>{{ loadingOverview ? '正在读取近期活动…' : overviewError ? '近期活动暂不可用' : activityRows.length ? '没有匹配的近期活动' : '本月还没有流水' }}</b><span>{{ loadingOverview ? '请稍候' : overviewError ? '请重试读取，失败不能视为空账本' : activityRows.length ? '调整搜索或筛选条件后重试' : '记录一笔支出或收入后会显示在这里' }}</span><button v-if="!loadingOverview && !overviewError && !activityRows.length" type="button" @click="router.push('/transaction/list?action=expense')">记录第一笔流水</button></td></tr></tbody>
                                </table></div>
                                <footer class="activity-footer"><span>显示 {{ filteredActivities.length }} 笔{{ activityRows.length?`，本月共 ${activityRows.length} 笔`:'' }}</span><button type="button" @click="router.push('/transaction/list')">查看全部流水<v-icon :icon="mdiChevronRight" size="15"/></button></footer>
                            </section>
                        </div>
                    </div>
                </main>
            </div>
        </div>

        <Teleport to="body"><transition name="drawer"><div v-if="selectedWallet" class="balance-overlay" @click.self="closeWalletDetails">
            <aside class="balance-drawer" role="dialog" aria-modal="true" aria-labelledby="wallet-detail-title">
                <button class="drawer-close" type="button" aria-label="关闭账户详情" @click="closeWalletDetails">×</button>
                <span class="drawer-mark" :class="currencyFlag(selectedWallet.currency)">{{ selectedWallet.name.slice(0,1) }}</span>
                <small>账户详情</small><h2 id="wallet-detail-title">{{ selectedWallet.name }}</h2><p>{{ walletCategoryLabel(selectedWallet.category) }} · {{ selectedWallet.currency }}</p>
                <strong class="drawer-balance">{{ showAmountInHomePage?formatWalletBalance(selectedWallet.balance,selectedWallet.currency):'******' }}</strong>
                <dl><div><dt>账户状态</dt><dd><i :class="selectedWallet.hidden?'off':'ok'"></i>{{ selectedWallet.hidden?'已停用':'正常' }}</dd></div><div><dt>币种</dt><dd>{{ selectedWallet.currency }}</dd></div><div><dt>账户说明</dt><dd>{{ selectedWallet.comment||'暂无说明' }}</dd></div></dl>
                <div class="drawer-actions"><button class="btn-dark" type="button" @click="openQuickTransaction(TransactionType.Expense,selectedWallet)">记录支出</button><button class="btn-light" type="button" @click="openQuickTransaction(TransactionType.Income,selectedWallet)">记录收入</button></div>
                <button class="drawer-edit" type="button" @click="openAccountEditor(selectedWallet)"><v-icon :icon="mdiPencilOutline" size="16"/>编辑账户</button>
            </aside>
        </div></transition></Teleport>

    <LedgerPendingDialog v-if="utilityPanel === 'notifications'" @close="utilityPanel = null" @open="openPendingTarget" />
    <snack-bar ref="snackbar" />
    <TransactionEditDialog ref="transactionEditDialog" :type="TransactionEditPageType.Transaction"/>
    <AccountEditDialog ref="accountEditDialog"/>
    </div>
</template>

<script setup lang="ts">
import SnackBar from '@/components/desktop/SnackBar.vue';
import ReleaseUpdateNotice from '@/components/ReleaseUpdateNotice.vue';
import GlobalLedgerSwitcher from '@/components/desktop/GlobalLedgerSwitcher.vue';
import WalletHoloCard from '@/components/desktop/WalletHoloCard.vue';
import TransactionEditDialog from '@/views/desktop/transactions/list/dialogs/EditDialog.vue';
import AccountEditDialog from '@/views/desktop/accounts/list/dialogs/EditDialog.vue';
import LedgerPendingPanel from '@/components/common/LedgerPendingPanel.vue';
import LedgerPendingDialog from '@/components/common/LedgerPendingDialog.vue';
import { useLedgerPendingStore } from '@/stores/ledgerPending.ts';
import { desktopLedgerPendingLocation } from '@/lib/ledger_pending_navigation.ts';
import type { LedgerPendingTarget } from '@/models/ledger_pending.ts';
import { TransactionEditPageType } from '@/views/base/transactions/TransactionEditPageBase.ts';

import { ref, computed, watch, useTemplateRef, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';

import { useI18n } from '@/locales/helpers.ts';
import { useHomePageBase } from '@/views/base/HomePageBase.ts';

import { useAccountsStore } from '@/stores/account.ts';
import { useTransactionCategoriesStore } from '@/stores/transactionCategory.ts';
import { useMonthlyBudgetStore } from '@/stores/monthlyBudget.ts';
import { useRootStore } from '@/stores/index.ts';
import { useUserStore } from '@/stores/user.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useSavingsGoalsStore } from '@/stores/savingsGoal.ts';
import { DefaultLedgerId } from '@/models/ledger.ts';
import { AccountCategory } from '@/core/account.ts';
import type { Account } from '@/models/account.ts';
import { isUserLogined, isUserUnlocked } from '@/lib/userstate.ts';
import type { MonthlyBudgetInfoResponse } from '@/models/monthly_budget.ts';
import { Transaction, type TransactionStatisticTrendsResponseItem } from '@/models/transaction.ts';
import { TransactionType } from '@/core/transaction.ts';
import services from '@/lib/services.ts';
import { accountCurrency, exactMinorUnitSum, summarizeCurrencyAmounts, summarizeStatisticCurrencyAmounts } from '@/lib/currency_totals.ts';

import {
    mdiWalletOutline,
    mdiMagnify,
    mdiBellOutline,
    mdiInformationOutline,
    mdiChevronDown,
    mdiChevronRight,
    mdiChevronLeft,
    mdiFileDocumentOutline,
    mdiLayersOutline,
    mdiTagOutline,
    mdiWeb,
    mdiPackageVariantClosed,
    mdiPiggyBankOutline,
    mdiBookOpenOutline,
    mdiCogOutline,
    mdiHelpCircleOutline,
    mdiSwapHorizontal,
    mdiCreditCardOutline,
    mdiCashMultiple,
    mdiSackPercent,
    mdiFilterOutline,
    mdiDotsHorizontal,
    mdiLogout,
    mdiCheck,
    mdiEyeOutline,
    mdiEyeOffOutline,
    mdiRefresh,
    mdiMinusCircleOutline,
    mdiPlusCircleOutline,
    mdiPencilOutline,
    mdiBookOpenVariant
} from '@mdi/js';

type SnackBarType = InstanceType<typeof SnackBar>;
type TransactionDialogType = InstanceType<typeof TransactionEditDialog>;
type AccountDialogType = InstanceType<typeof AccountEditDialog>;
type ActivityType = 'all' | 'expense' | 'income' | 'transfer' | 'balance';
interface ActivityRow {
    id: string;
    transactionId: string;
    icon: string;
    color: string;
    name: string;
    price: string;
    status: string;
    statusClass: string;
    date: string;
    selected: boolean;
    type: Exclude<ActivityType, 'all'>;
    typeLabel: string;
    raw: Transaction;
}

const router = useRouter();
const { formatNumberToLocalizedNumerals, formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
const {
    showAmountInHomePage,
} = useHomePageBase();

const accountsStore = useAccountsStore();
const transactionCategoriesStore = useTransactionCategoriesStore();
const monthlyBudgetStore = useMonthlyBudgetStore();
const ledgerPendingStore = useLedgerPendingStore();
const rootStore = useRootStore();
const userStore = useUserStore();
const ledgersStore = useLedgersStore();
const savingsGoalsStore = useSavingsGoalsStore();

const snackbar = useTemplateRef<SnackBarType>('snackbar');
const transactionEditDialog = useTemplateRef<TransactionDialogType>('transactionEditDialog');
const accountEditDialog = useTemplateRef<AccountDialogType>('accountEditDialog');

const loadingOverview = ref<boolean>(true);
const overviewError = ref('');
const budgetError = ref('');
const balanceCurrencyMenuOpen = ref<boolean>(false);
const balanceCurrency = ref<string>(userStore.currentUserDefaultCurrency || 'CNY');
const selectedWallet = ref<Account | null>(null);
const accountMenuOpen = ref<boolean>(false);
const accountActionBusy = ref<boolean>(false);
const utilityPanel = ref<'search' | 'notifications' | 'help' | null>(null);
const globalSearchQuery = ref<string>('');
const currentUserName = computed<string>(() => userStore.currentUserNickname || '当前用户');
const currentUserInitial = computed<string>(() => currentUserName.value.trim().slice(0, 1).toUpperCase() || '用');
const homeGreeting = computed<string>(() => {
    const hour = new Date().getHours();
    if (hour < 6) return '夜深了';
    if (hour < 12) return '早上好';
    if (hour < 18) return '下午好';
    return '晚上好';
});
const selectedLedgerId = computed<string>(() => ledgersStore.selectedLedgerId);
const selectedLedgerName = computed<string>(() => ledgersStore.selectedLedger.name);
const familyLedgerSelected = computed<boolean>(() => selectedLedgerId.value !== DefaultLedgerId);
const currentLedgerMember = computed(() => ledgersStore.members.find(member => member.isCurrentUser && member.status === 1));
const ledgerRoleLabel = computed<string>(() => {
    const labels: Record<number, string> = { 1: '所有者', 2: '管理员', 3: '可记账成员', 4: '只读成员' };
    return labels[currentLedgerMember.value?.role ?? 0] ?? '成员';
});
const selectedLedgerSummary = computed<string>(() => {
    if (!familyLedgerSelected.value) return '仅自己可见 · 个人空间';
    const activeMembers = ledgersStore.members.filter(member => member.status === 1).length;
    return activeMembers > 0 ? `${ledgerRoleLabel.value} · ${activeMembers} 位成员` : '共享账本';
});
const walletAccounts = computed<Account[]>(() => accountsStore.accountListLedgerId === selectedLedgerId.value ? accountsStore.allVisiblePlainAccounts : []);
const walletIncompleteCount = computed(() => walletAccounts.value.filter(account => !/^[A-Z]{3}$/.test(account.currency) || !Number.isSafeInteger(account.balance)).length);
const pendingSummary = computed(() => ledgerPendingStore.summaryFor(selectedLedgerId.value));
const featuredSavingsGoal = computed(() => {
    if (loadingOverview.value || overviewError.value) return null;
    const goals = savingsGoalsStore.goals.filter(goal => goal.ledgerId === selectedLedgerId.value);
    return goals.find(goal => !goal.achieved) ?? goals[0] ?? null;
});
const featuredSavingsGoalProgress = computed(() => Math.min(100, Math.max(0, featuredSavingsGoal.value?.progressPercent ?? 0)));

const defaultCurrency = computed<string>(() => userStore.currentUserDefaultCurrency || 'CNY');
const displayAccountCount = computed<string>(() => formatNumberToLocalizedNumerals(accountsStore.accountListLedgerId === selectedLedgerId.value ? accountsStore.allPlainAccounts.length : 0));
const balanceCurrencies = computed<string[]>(() => Array.from(new Set([defaultCurrency.value, ...walletAccounts.value.map(account => account.currency).filter(code => /^[A-Z]{3}$/.test(code))])));
const balanceDisplayAmount = computed<string>(() => {
    if (!showAmountInHomePage.value) {
        return '******';
    }
    if (overviewError.value) return '未能读取';
    if (loadingOverview.value || accountsStore.accountListLedgerId !== selectedLedgerId.value) return '—';
    const currencyBalance = exactMinorUnitSum(walletAccounts.value.filter(account => account.currency === balanceCurrency.value).map(account => account.balance));
    return currencyBalance !== null ? formatAmountToLocalizedNumeralsWithCurrency(currencyBalance, balanceCurrency.value) : '未完整计算';
});
const balanceAccountSummary = computed<string>(() => {
    const count = walletAccounts.value.filter(account => account.currency === balanceCurrency.value).length;
    return `${formatNumberToLocalizedNumerals(count)} 个同币种可见账户 · ${balanceCurrency.value} · 不折算`;
});
const currentYearMonth = computed<number>(() => {
    const now = new Date();
    return now.getFullYear() * 100 + now.getMonth() + 1;
});
const monthlyBudget = computed<MonthlyBudgetInfoResponse | null>(() => monthlyBudgetStore.budgetFor(currentYearMonth.value, selectedLedgerId.value) ?? null);
const budgetCurrency = computed(() => monthlyBudget.value?.currency || defaultCurrency.value);
const monthlyTransactions = ref<Transaction[]>([]);
const summaryCurrency = ref(userStore.currentUserDefaultCurrency || 'CNY');
const summaryAccounts = computed(() => accountsStore.accountListLedgerId === selectedLedgerId.value ? accountsStore.allAccountsMap : {});
const summaryCurrencies = computed(() => [...new Set([defaultCurrency.value, ...Object.values(summaryAccounts.value).map(account => account.currency).filter(code => /^[A-Z]{3}$/.test(code))])].sort());
const monthlySummary = computed(() => summarizeCurrencyAmounts(monthlyTransactions.value.map(item => ({ type: item.type, accountId: item.sourceAccountId, amount: item.sourceAmount })), summaryAccounts.value));
const currentMonthTotals = computed(() => monthlySummary.value.totals.find(item => item.currency === summaryCurrency.value));
const currentMonthIncome = computed<number>(() => currentMonthTotals.value?.income ?? 0);
const currentMonthExpense = computed<number>(() => currentMonthTotals.value?.expense ?? 0);
const currentMonthNet = computed<number>(() => currentMonthIncome.value - currentMonthExpense.value);
const budgetExpense = computed<number | null>(() => monthlySummary.value.unresolvedCount ? null : monthlySummary.value.totals.find(item => item.currency === budgetCurrency.value)?.expense ?? 0);
const budgetPercentage = computed<number>(() => monthlyBudget.value && budgetExpense.value !== null ? budgetExpense.value * 100 / monthlyBudget.value.amount : 0);
function formatBudgetLimitMoney(value: number): string { return showAmountInHomePage.value ? formatAmountToLocalizedNumeralsWithCurrency(value, budgetCurrency.value) : '******'; }
function formatSummaryMoney(amount: number | null): string { return !showAmountInHomePage.value ? '******' : loadingOverview.value ? '—' : overviewError.value ? '未能读取' : amount === null || !Number.isSafeInteger(amount) ? '未完整计算' : formatAmountToLocalizedNumeralsWithCurrency(amount, summaryCurrency.value); }
const thisMonthNet = computed<string>(() => formatSummaryMoney(currentMonthNet.value));
const thisMonthIncome = computed<string>(() => formatSummaryMoney(currentMonthIncome.value));
const thisMonthExpense = computed<string>(() => formatSummaryMoney(currentMonthExpense.value));
const homeTotalAssets = computed<string>(() => formatSummaryMoney(exactMinorUnitSum(walletAccounts.value.filter(account => account.currency === summaryCurrency.value && account.isAsset).map(account => account.balance))));
const homeTrendRows = ref<TransactionStatisticTrendsResponseItem[]>([]);

const overviewMonthKeys = [
    'monthBeforeLast6Months',
    'monthBeforeLast5Months',
    'monthBeforeLast4Months',
    'monthBeforeLast3Months',
    'monthBeforeLast2Months',
    'monthBeforeLastMonth',
    'lastMonth',
    'thisMonth'
] as const;
const chartAmounts = computed(() => overviewMonthKeys.map((_, index) => {
    const now = new Date();
    const date = new Date(now.getFullYear(), now.getMonth() - (overviewMonthKeys.length - 1 - index), 1);
    const row = homeTrendRows.value.find(item => item.year === date.getFullYear() && item.month === date.getMonth() + 1);
    const summary = summarizeStatisticCurrencyAmounts(row?.items ?? [], summaryAccounts.value, transactionCategoriesStore.allTransactionCategoriesMap);
    const item = summary.totals.find(item => item.currency === summaryCurrency.value);
    return {
        month: `${date.getMonth() + 1}月`,
        yearMonth: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`,
        incomeAmount: item?.income ?? 0,
        expenseAmount: item?.expense ?? 0,
        unresolvedCount: summary.unresolvedCount,
    };
}));
const chartIncompleteCount = computed(() => chartAmounts.value.reduce((sum, item) => sum + item.unresolvedCount, 0));
const chartMaximum = computed(() => Math.max(0, ...chartAmounts.value.flatMap(item => [Math.abs(item.incomeAmount), Math.abs(item.expenseAmount)])));
const chartBars = computed(() => chartAmounts.value.map(item => ({
    month: item.month,
    profit: chartMaximum.value && item.incomeAmount ? Math.max(2, Math.abs(item.incomeAmount) * 100 / chartMaximum.value) : 0,
    loss: chartMaximum.value && item.expenseAmount ? Math.max(2, Math.abs(item.expenseAmount) * 100 / chartMaximum.value) : 0
})));
const chartYAxisLabels = computed(() => Array.from({ length: 5 }, (_, index) => {
    const value = chartMaximum.value * (5 - index) / 500;
    if (value >= 1000) return `${Math.round(value / 100) / 10}k`;
    return String(Math.round(value));
}));

const activityQuery = ref<string>('');
const activityFilterOpen = ref<boolean>(false);
const activityType = ref<ActivityType>('all');
const activityMenuId = ref<string | null>(null);
const activityTypes: Array<{label:string;value:ActivityType}> = [{label:'全部',value:'all'},{label:'支出',value:'expense'},{label:'收入',value:'income'},{label:'转账',value:'transfer'},{label:'余额调整',value:'balance'}];
const activityRows = ref<ActivityRow[]>([]);
const filteredActivities = computed(() => {
    const query = activityQuery.value.trim().toLowerCase();
    return activityRows.value.filter(row => (activityType.value === 'all' || row.type === activityType.value) && (!query || `${row.id}${row.name}${row.status}${row.typeLabel}`.toLowerCase().includes(query)));
});
const selectedActivityCount = computed(() => activityRows.value.filter(row => row.selected).length);
const allActivitiesSelected = computed({get:()=>filteredActivities.value.length>0&&filteredActivities.value.every(row=>row.selected),set:(selected:boolean)=>filteredActivities.value.forEach(row=>row.selected=selected)});
const someActivitiesSelected = computed(()=>selectedActivityCount.value>0&&!allActivitiesSelected.value);
const globalSearchResults = computed(() => {
    const query = globalSearchQuery.value.trim().toLowerCase();
    if (!query) return activityRows.value.slice(0, 3);
    return activityRows.value.filter(row => `${row.id}${row.name}${row.status}`.toLowerCase().includes(query)).slice(0, 5);
});

const displayWallets = computed(() => {
    return walletAccounts.value.map(account => ({
        id: account.id,
        flag: currencyFlag(account.currency),
        code: account.currency || '币种未读取',
        amount: formatWalletBalance(account.balance, account.currency),
        name: account.name,
        note: walletCategoryLabel(account.category),
        active: !account.hidden,
        raw: account
    }));
});
const walletAccountId = ref('');
const walletAppearanceRefresh = ref(0);
const walletPosition = computed(() => Math.max(0, displayWallets.value.findIndex(account => account.id === walletAccountId.value)));
const activeWallet = computed(() => displayWallets.value[walletPosition.value]);
watch(displayWallets, accounts => {
    if (!accounts.some(account => account.id === walletAccountId.value)) walletAccountId.value = accounts[0]?.id || '';
}, { immediate: true });
watch(selectedLedgerId, () => {
    walletAccountId.value = '';
    selectedWallet.value = null;
}, { flush: 'sync' });
function navigateWallet(direction: -1 | 1): void {
    const account = displayWallets.value[walletPosition.value + direction];
    if (account) walletAccountId.value = account.id;
}

function formatBudgetMoney(value: number): string {
    if (!showAmountInHomePage.value) return '******';
    return formatAmountToLocalizedNumeralsWithCurrency(value, defaultCurrency.value);
}

function currencyFlag(currency: string): string {
    if (currency === 'CNY') return 'cn';
    if (currency === 'USD') return 'blue';
    if (currency === 'EUR') return 'gold';
    return 'currency-alt';
}

function selectBalanceCurrency(currency: string): void {
    balanceCurrency.value = currency;
    balanceCurrencyMenuOpen.value = false;
}

function toggleBalanceVisibility(): void {
    showAmountInHomePage.value = !showAmountInHomePage.value;
    balanceCurrencyMenuOpen.value = false;
}

function refreshBalance(): void {
    balanceCurrencyMenuOpen.value = false;
    walletAppearanceRefresh.value++;
    reload(true);
}

function openQuickTransaction(type: number, account?: Account | null): void {
    if (familyLedgerSelected.value) {
        snackbar.value?.showMessage('共享账本记账能力正在接入，请先在账本页查看数据与成员');
        return;
    }
    balanceCurrencyMenuOpen.value = false;
    selectedWallet.value = null;
    transactionEditDialog.value?.open({ type, accountId: account?.id, noTransactionDraft: true }).then(() => reload(true, false)).catch(error => {
        if (error && !error.canceled && !error.processed) snackbar.value?.showError(error);
    });
}

function openWalletDetails(account: Account): void {
    balanceCurrencyMenuOpen.value = false;
    selectedWallet.value = account;
}

function closeWalletDetails(): void {
    selectedWallet.value = null;
}

function openAccountEditor(account?: Account | null): void {
    if (familyLedgerSelected.value) {
        snackbar.value?.showMessage('共享账本账户编辑能力正在接入，请先在账本页管理成员');
        return;
    }
    balanceCurrencyMenuOpen.value = false;
    selectedWallet.value = null;
    accountEditDialog.value?.open(account ? { id: account.id, currentAccount: account } : undefined).then(() => reload(false)).catch(error => {
        if (error && !error.processed) snackbar.value?.showError(error);
    });
}

function transactionToActivity(transaction: Transaction): ActivityRow {
    const isModifyBalance = transaction.type === TransactionType.ModifyBalance;
    const isIncome = transaction.type === TransactionType.Income;
    const isTransfer = transaction.type === TransactionType.Transfer;
    const type: ActivityRow['type'] = isModifyBalance ? 'balance' : isIncome ? 'income' : isTransfer ? 'transfer' : 'expense';
    const typeLabel = isModifyBalance ? '余额调整' : isIncome ? '收入' : isTransfer ? '转账' : '支出';
    const currency = accountCurrency(summaryAccounts.value, transaction.sourceAccountId);
    const amount = currency ? formatAmountToLocalizedNumeralsWithCurrency(isModifyBalance ? Math.abs(transaction.sourceAmount) : transaction.sourceAmount, currency) : '币种未读取';
    const prefix = isModifyBalance ? (transaction.sourceAmount >= 0 ? '+' : '-') : isIncome ? '+' : isTransfer ? '↔ ' : '-';
    const date = new Date(transaction.time * 1000);
    const title = transaction.comment || transaction.category?.name || typeLabel;

    return {
        id: `TX_${transaction.id.slice(-6).toUpperCase()}`,
        transactionId: transaction.id,
        icon: (transaction.category?.name || typeLabel).slice(0, 1),
        color: isIncome ? '#149C63' : isTransfer ? '#4F46E5' : '#F05537',
        name: title,
        price: `${prefix}${amount}`,
        status: transaction.editable ? '已完成' : '只读',
        statusClass: transaction.editable ? 'c' : 'g',
        date: date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }),
        selected: false,
        type,
        typeLabel,
        raw: transaction
    };
}

function clearActivitySelection(): void {
    activityRows.value.forEach(row => row.selected = false);
}

function resetActivityFilters(): void {
    activityQuery.value = '';
    activityType.value = 'all';
}

function openActivity(row: ActivityRow): void {
    if (familyLedgerSelected.value) {
        viewActivityInList(row);
        return;
    }
    activityMenuId.value = null;
    transactionEditDialog.value?.open({ id: row.transactionId, currentTransaction: row.raw }).then(() => reload(true, false)).catch(error => {
        if (error && !error.processed) snackbar.value?.showError(error);
    });
}

function viewActivityInList(row: ActivityRow): void {
    activityMenuId.value = null;
    router.push({ path: '/transaction/list', query: { search: row.transactionId } });
}

function formatWalletBalance(balance: number, currency: string): string {
    return /^[A-Z]{3}$/.test(currency) && Number.isSafeInteger(balance) ? formatAmountToLocalizedNumeralsWithCurrency(balance, currency) : '未完整计算';
}

function walletCategoryLabel(category: number): string {
    const labels: Record<number, string> = {
        [AccountCategory.Cash.type]: '现金',
        [AccountCategory.CheckingAccount.type]: '储蓄账户',
        [AccountCategory.SavingsAccount.type]: '储蓄账户',
        [AccountCategory.CreditCard.type]: '信用账户',
        [AccountCategory.VirtualAccount.type]: '数字余额',
        [AccountCategory.DebtAccount.type]: '负债账户',
        [AccountCategory.Receivables.type]: '应收款',
        [AccountCategory.CertificateOfDeposit.type]: '定期存款',
        [AccountCategory.InvestmentAccount.type]: '投资账户'
    };
    return labels[category] || '资金账户';
}

function toggleUtilityPanel(panel: 'search' | 'notifications' | 'help'): void {
    utilityPanel.value = utilityPanel.value === panel ? null : panel;
    accountMenuOpen.value = false;
}

function toggleAccountMenu(): void {
    accountMenuOpen.value = !accountMenuOpen.value;
    utilityPanel.value = null;
}

function submitGlobalSearch(): void {
    const search = globalSearchQuery.value.trim();
    utilityPanel.value = null;
    router.push({ path: '/transaction/list', query: search ? { search } : { search: '1' } });
}

function openSearchResult(row: { id: string; name: string }): void {
    utilityPanel.value = null;
    router.push({ path: '/transaction/list', query: { search: row.id || row.name } });
}

function openPendingTarget(target: LedgerPendingTarget): void {
    utilityPanel.value = null;
    void router.push(desktopLedgerPendingLocation(target));
}

function switchAccount(): void {
    accountMenuOpen.value = false;
    router.push({ path: '/login', query: { switch: '1' } });
}

function logoutAccount(): void {
    if (accountActionBusy.value) {
        return;
    }

    accountActionBusy.value = true;
    rootStore.logout().then(() => {
        router.replace('/login').then(() => window.location.reload());
    }).catch(error => {
        accountActionBusy.value = false;
        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

let overviewLoadSerial = 0;
onUnmounted(() => { overviewLoadSerial++; });
async function loadHomeAccounts(force: boolean, ledgerId: string) {
    try { await accountsStore.loadAllAccounts({ force, ledgerId }); }
    catch (error) {
        if ((error as { isUpToDate?: boolean } | null)?.isUpToDate === true && selectedLedgerId.value === ledgerId && accountsStore.accountListLedgerId === ledgerId && !accountsStore.accountListStateInvalid) return;
        throw error;
    }
}
async function loadHomeCategories(ledgerId: string) {
    try { await transactionCategoriesStore.loadAllCategories({ force: false, ledgerId }); }
    catch (error) {
        if ((error as { isUpToDate?: boolean } | null)?.isUpToDate === true && selectedLedgerId.value === ledgerId && transactionCategoriesStore.loadedLedgerId === ledgerId && !transactionCategoriesStore.transactionCategoryListStateInvalid) return;
        throw error;
    }
}
async function reload(force: boolean, showRefreshMessage = force): Promise<void> {
    const serial = ++overviewLoadSerial;
    loadingOverview.value = true;
    overviewError.value = ''; budgetError.value = '';
    const now = new Date();
    try {
        await ledgersStore.load();
        const ledgerId = selectedLedgerId.value;
        const explicitLedgerId = ledgerId === DefaultLedgerId ? undefined : ledgerId;
        const isCurrent = () => serial === overviewLoadSerial && ledgerId === selectedLedgerId.value;
        await Promise.all([
            ledgersStore.loadAccess(ledgerId),
            loadHomeAccounts(force, ledgerId),
            loadHomeCategories(ledgerId),
        ]);
        if (!isCurrent()) return;
        const start = new Date(now.getFullYear(), now.getMonth() - 7, 1);
        const yearMonth = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
        const promises: Array<Promise<unknown>> = [
            savingsGoalsStore.load(ledgerId),
            services.getTransactionStatisticsTrends({ ledgerId: explicitLedgerId, startYearMonth: yearMonth(start), endYearMonth: yearMonth(now), tagFilter: '', keyword: '', matchMode: 0, useTransactionTimezone: false }).then(response => {
                if (!response.data?.success || !Array.isArray(response.data.result) || !response.data.result.every(row => Array.isArray(row.items))) throw new Error('无法读取收支趋势');
                if (isCurrent()) homeTrendRows.value = response.data.result;
            }),
            services.getAllTransactionsByMonth({ ledgerId: explicitLedgerId, year: now.getFullYear(), month: now.getMonth() + 1, type: 0, categoryIds: '', accountIds: '', tagFilter: '', amountFilter: '', keyword: '', matchMode: 0 }).then(response => {
                if (!response.data?.success || !Array.isArray(response.data.result?.items)) throw new Error('无法读取本月流水');
                if (!isCurrent()) return;
                const items = Transaction.ofMulti(response.data.result.items);
                items.forEach(transaction => {
                    transaction.setSourceAccount(accountsStore.allAccountsMap[transaction.sourceAccountId]);
                    transaction.setCategory(transactionCategoriesStore.allTransactionCategoriesMap[transaction.categoryId]);
                });
                monthlyTransactions.value = items;
                activityRows.value = items.slice(0, 5).map(transactionToActivity);
                activityMenuId.value = null;
            })
        ];
        promises.push(monthlyBudgetStore.load(currentYearMonth.value, force, ledgerId).catch(() => {
            if (isCurrent()) budgetError.value = '未能读取当前账本预算。';
        }));
        await Promise.all(promises);
        if (!isCurrent()) return;
        loadingOverview.value = false;
        if (showRefreshMessage) {
            snackbar.value?.showMessage('余额数据已更新');
        }
    } catch (error) {
        if (serial !== overviewLoadSerial) return;
        overviewError.value = '未能完整读取当前账本，请检查连接后重试。';
        loadingOverview.value = false;
        if (!(error as { processed?: boolean }).processed) {
            snackbar.value?.showError(error as Error);
        }
    }
}

function onGlobalLedgerSelect(ledgerId: string): void {
    try {
        ledgersStore.select(ledgerId);
        monthlyTransactions.value = [];
        homeTrendRows.value = [];
        activityRows.value = [];
        selectedWallet.value = null;
        balanceCurrency.value = defaultCurrency.value;
        summaryCurrency.value = defaultCurrency.value;
        void reload(true, false).then(() => { if (!overviewError.value) snackbar.value?.showMessage(`已切换到「${selectedLedgerName.value}」`); });
    } catch (error) {
        snackbar.value?.showError(error as Error);
    }
}

if (isUserLogined() && isUserUnlocked()) {
    reload(false);
}
</script>

<style scoped>
.summary-currency { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 14px 0; font-size: 14px; color: var(--muted); }
.summary-currency select, .home-data-error button { min-height: 44px; padding: 8px 12px; color: var(--ink); border: 1px solid var(--line); border-radius: 12px; background: var(--panel); font: inherit; }
.home-data-error { padding: 12px; border: 1px solid var(--red); border-radius: 12px; color: var(--red); font-size: 14px; }
.scope-copy { font-size: 14px; color: var(--muted); line-height: 1.5; }
.trend-values summary { min-height: 44px; padding: 12px 0; cursor: pointer; }
.trend-values table { width: 100%; font-size: 14px; border-collapse: collapse; }
.trend-values :is(th, td) { text-align: left; padding: 6px; border-bottom: 1px solid var(--line); }
/* finexy-replica.html exact layout tokens and geometry */
.finance-home {
    --page:#F1F2F6; --card:#FFFFFF; --panel:#F7F8FA; --line:#EFF0F4;
    --ink:#12141A; --muted:#6E7684; --faint:#9AA1AD;
    --orange:#F05537; --orange-2:#EE4E31;
    --green:#1DBF73; --red:#F04444; --olive:#A8A832; --blue:#4F46E5;
    --r-lg:22px; --r-md:16px;
    position:fixed; inset:0; z-index:1000; overflow:auto;
    min-width:1160px; padding:34px 92px; background:var(--page); color:var(--ink);
    font-family:"Inter","Microsoft YaHei UI","PingFang SC","Segoe UI",system-ui,sans-serif;
    -webkit-font-smoothing:antialiased;
}
.finance-home,.finance-home *{box-sizing:border-box}
.finance-home button{font:inherit;background:none;border:0;cursor:pointer;color:inherit}
.finance-home a{color:inherit;text-decoration:none}
.app{min-height:1018px;background:var(--card);border-radius:30px;padding:16px 22px 22px;box-shadow:0 1px 2px rgba(16,24,40,.03)}
.topbar{position:relative;display:flex;align-items:center;height:56px}
.brand{display:flex;align-items:center;gap:10px;background:var(--panel);border-radius:14px;padding:7px 14px 7px 8px;font-weight:800;font-size:15.5px;letter-spacing:-.02em}
.logo{display:grid;place-items:center;width:34px;height:34px;border-radius:11px;color:#fff;font-weight:900;font-size:19px;font-style:italic;background:linear-gradient(135deg,#F36447,var(--orange-2));box-shadow:inset 0 1px 0 rgba(255,255,255,.3)}
.tabs{display:flex;align-items:center;gap:34px;margin-left:64px}
.tabs a{font-size:13.5px;font-weight:500;color:var(--muted);cursor:pointer;padding:9px 4px;border-radius:999px;white-space:nowrap}
.tabs a.active{background:var(--ink);color:#fff;padding:9px 22px;font-weight:600}
.top-actions{position:relative;margin-left:auto;display:flex;align-items:center;gap:10px}
.ic{position:relative;display:grid!important;place-items:center;width:38px;height:38px;border-radius:50%;background:#fff!important;border:1px solid var(--line)!important}
.ic:hover{background:var(--panel)!important}.ic .dot{position:absolute;top:9px;right:10px;width:6px;height:6px;border-radius:50%;background:var(--orange)}
.user{display:flex;align-items:center;gap:9px;border:1px solid var(--line)!important;border-radius:999px;padding:4px 12px 4px 5px!important}
.user:hover{background:var(--panel)}
.avatar{display:grid;place-items:center;width:32px;height:32px;border-radius:50%;color:#fff;font-size:11px;font-weight:800;background:linear-gradient(135deg,#8D5524,#5C3A1E)}
.uinfo{text-align:left;line-height:1.25}.uinfo b{display:block;font-size:12px;font-weight:700}.uinfo small{display:block;font-size:10.5px;color:var(--faint);max-width:118px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.account-menu{position:absolute;z-index:80;top:51px;right:0;width:285px;padding:9px;border:1px solid var(--line);border-radius:18px;background:#fff;box-shadow:0 18px 45px rgba(18,20,26,.16)}
.account-current{display:grid;grid-template-columns:40px 1fr auto;align-items:center;gap:10px;padding:10px;margin-bottom:6px;border-radius:13px;background:var(--panel)}
.account-avatar{display:grid;width:40px;height:40px;place-items:center;border-radius:50%;color:#fff;background:linear-gradient(135deg,#8D5524,#5C3A1E);font-size:12px;font-weight:800}
.account-current div{display:grid;gap:2px;min-width:0}.account-current small{color:var(--faint);font-size:9.5px}.account-current strong{overflow:hidden;text-overflow:ellipsis;font-size:12px;white-space:nowrap}.account-current i{padding:4px 7px;border-radius:999px;color:var(--green);background:#eaf9f1;font-size:9px;font-style:normal;font-weight:700}
.account-menu>a,.account-menu>button{display:grid!important;width:100%;grid-template-columns:34px 1fr;align-items:center;gap:9px;padding:9px!important;border-radius:11px!important;text-align:left}
.account-menu>a:hover,.account-menu>button:hover{background:var(--panel)!important}.account-menu>a>.v-icon,.account-menu>button>.v-icon{display:grid;width:34px;height:34px;place-items:center;border-radius:10px;background:#f0f1f4}
.account-menu>a>span,.account-menu>button>span{display:grid;gap:2px}.account-menu b{font-size:11px}.account-menu a small,.account-menu button small{color:var(--faint);font-size:9.5px}
.account-drop-enter-active,.account-drop-leave-active{transition:.18s}.account-drop-enter-from,.account-drop-leave-to{opacity:0;transform:translateY(-6px)}
.ic.active{color:#fff;background:var(--ink)!important;border-color:var(--ink)!important}
.utility-panel{position:absolute;z-index:79;top:51px;right:0;width:360px;padding:12px;border:1px solid var(--line);border-radius:20px;background:#fff;box-shadow:0 20px 50px rgba(18,20,26,.16)}
.utility-panel header{display:flex;align-items:flex-start;justify-content:space-between;padding:4px 4px 12px}.utility-panel header>div{display:grid;gap:3px}.utility-panel header b{font-size:15px}.utility-panel header small{color:var(--muted);font-size:12px}.utility-panel header>button{display:grid;width:30px;height:30px;place-items:center;border-radius:50%;background:var(--panel);font-size:18px}
.utility-search{display:flex;height:44px;align-items:center;gap:8px;padding:0 12px;border:1px solid var(--line);border-radius:12px;background:var(--panel);color:var(--muted)}.utility-search input{min-width:0;flex:1;border:0;outline:0;background:transparent;color:var(--ink);font-size:13px}.utility-search input::placeholder{color:var(--muted)}
.utility-results{display:grid;gap:3px;margin-top:12px}.utility-results>small{padding:0 5px 5px;color:var(--muted);font-size:11.5px}.utility-results>button{display:grid;width:100%;grid-template-columns:34px 1fr auto;align-items:center;gap:9px;padding:8px;border-radius:11px;text-align:left}.utility-results>button:hover{background:var(--panel)}.utility-results>button>i{display:grid;width:34px;height:34px;place-items:center;border-radius:9px;color:#fff;font-size:11px;font-style:normal;font-weight:800}.utility-results>button>span{display:grid;gap:2px}.utility-results b{font-size:12.5px}.utility-results small{color:var(--muted);font-size:11.5px}.utility-results strong{font-size:12px}.utility-results>p{margin:8px 0 12px;color:var(--muted);font-size:12.5px;text-align:center}
.utility-primary{display:flex!important;width:100%;height:42px;align-items:center;justify-content:center;margin-top:10px;border-radius:999px!important;color:#fff!important;background:var(--ink)!important;font-size:12.5px;font-weight:700}
.notice-list{display:grid;gap:3px}.notice-list>button{display:grid;width:100%;grid-template-columns:8px 1fr 18px;align-items:center;gap:10px;padding:11px 9px;border-radius:11px;text-align:left}.notice-list>button:hover{background:var(--panel)}.notice-list>button>i{width:8px;height:8px;border-radius:50%;background:var(--olive)}.notice-list>button>i.p{background:var(--red)}.notice-list>button>span{display:grid;gap:3px}.notice-list b{font-size:13px}.notice-list small{color:var(--muted);font-size:11.5px}
.utility-empty{display:grid;justify-items:center;gap:5px;padding:25px 10px;color:var(--muted)}.utility-empty b{color:var(--ink);font-size:13px}.utility-empty small{font-size:12px}
.help-panel>a{display:grid;grid-template-columns:36px 1fr 18px;align-items:center;gap:10px;padding:11px 9px;border-radius:12px}.help-panel>a:hover{background:var(--panel)}.help-panel>a>.v-icon:first-child{display:grid;width:36px;height:36px;place-items:center;border-radius:10px;background:var(--panel)}.help-panel>a>span{display:grid;gap:3px}.help-panel b{font-size:13px}.help-panel small{color:var(--muted);font-size:11.5px}
.body{display:flex;gap:26px;margin-top:14px}.rail{display:flex;flex-direction:column;gap:12px;width:56px;flex:none}
.stack{display:flex;flex-direction:column;align-items:center;gap:4px;background:var(--panel);border-radius:18px;padding:8px 0}.stack.bottom{margin-top:auto}
.ric{display:grid!important;place-items:center;width:40px;height:40px;border-radius:50%;color:#4A4F5A}.ric:hover{background:#EDEFF3}.ric.active{background:var(--ink);color:#fff}
main{flex:1;min-width:0}h1{font-size:36px;font-weight:800;letter-spacing:-.03em;line-height:1.2;margin:0}.sub{margin-top:6px;font-size:13.5px;color:var(--muted)}
.ledger-context{display:flex;align-items:center;gap:11px;min-height:58px;margin-top:18px;padding:9px 10px 9px 12px;border:1px solid var(--line);border-radius:18px;background:linear-gradient(135deg,#fff 0%,#F8F9FB 100%)}.ledger-context-mark{display:grid;width:36px;height:36px;flex:none;place-items:center;border-radius:12px;background:#17191E;color:#fff}.ledger-context-copy{display:grid;min-width:0;grid-template-columns:auto 1fr;align-items:baseline;column-gap:9px}.ledger-context-copy small{grid-column:1/-1;color:var(--faint);font-size:9px;font-weight:800;letter-spacing:.08em}.ledger-context-copy strong{overflow:hidden;font-size:13px;text-overflow:ellipsis;white-space:nowrap}.ledger-context-copy span{color:var(--muted);font-size:11px}.ledger-context>a{display:flex;min-height:40px;margin-left:auto;align-items:center;gap:3px;padding:0 11px;border-radius:12px;background:#fff;color:var(--ink);font-size:11px;font-weight:750;box-shadow:inset 0 0 0 1px var(--line)}.ledger-context>a:hover{background:#F2F3F6}
.grid{display:grid;grid-template-columns:1.04fr .94fr 1.02fr;gap:18px;margin-top:18px}.card{background:var(--card);border:1px solid var(--line);border-radius:var(--r-lg);padding:22px}.panel{background:var(--panel);border-radius:var(--r-lg)}.col-left{display:grid;gap:18px;align-content:start}.activities{grid-column:2/4}
.balance-card{position:relative}.bhead{display:flex;align-items:center;justify-content:space-between}.bhead>span{font-size:13px;color:var(--muted);font-weight:500}.currency-switch{position:relative}.cur{display:flex;align-items:center;gap:6px;border:1px solid var(--line)!important;border-radius:9px;padding:5px 10px!important;font-size:12px;font-weight:600}.cur:hover,.cur.active{background:var(--panel)!important}.cur .flag{width:15px;height:15px}
.balance-menu{position:absolute;z-index:45;top:38px;right:0;width:250px;padding:9px;border:1px solid var(--line);border-radius:15px;background:#fff;box-shadow:0 16px 38px rgba(18,20,26,.15)}.balance-menu>small{display:block;padding:4px 7px 7px;color:var(--muted);font-size:11.5px;font-weight:650}.balance-menu>button{display:grid;width:100%;grid-template-columns:22px 1fr 18px;align-items:center;gap:8px;padding:8px!important;border-radius:10px!important;text-align:left}.balance-menu>button:hover,.balance-menu>button.active{background:var(--panel)!important}.balance-menu>button>span:nth-child(2){display:grid;gap:2px}.balance-menu b{font-size:12.5px}.balance-menu button small{color:var(--muted);font-size:11px}.balance-menu-actions{display:grid;grid-template-columns:1fr 1fr;gap:5px;margin-top:6px;padding-top:7px;border-top:1px solid var(--line)}.balance-menu-actions button{display:flex;align-items:center;justify-content:center;gap:5px;padding:8px 5px!important;border-radius:9px!important;background:var(--panel)!important;font-size:11px;font-weight:650}.balance-menu-actions button:hover{color:var(--orange)}
.amount{margin-top:10px;font-size:31px;font-weight:800;letter-spacing:-.03em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.trend{display:flex;align-items:center;gap:7px;margin-top:6px;font-size:12px}.trend b{color:var(--green);font-weight:700}.trend span{color:var(--faint)}
.balance-meta b{display:flex;align-items:center;gap:5px}.balance-meta b i{width:6px;height:6px;border-radius:50%;background:var(--green);box-shadow:0 0 0 3px rgba(8,122,73,.1)}
.bactions{display:flex;gap:12px;margin-top:18px}.btn-dark,.btn-light{flex:1;display:flex;align-items:center;justify-content:center;gap:9px;height:45px;border-radius:999px;font-size:13.5px;font-weight:600}.btn-dark{background:var(--ink)!important;color:#fff!important}.btn-dark:hover{background:#262932!important}.btn-light{background:#F3F4F6!important}.btn-light:hover{background:#E9EBEE!important}
.wallets{margin-top:20px;background:var(--panel);border-radius:var(--r-md);padding:16px}.whead{display:flex;align-items:baseline;gap:8px;font-size:12.5px}.whead b{font-weight:700}.whead span{color:var(--faint);font-size:11.5px}.wallet-add{margin-left:auto;color:var(--ink);font-size:11.5px;font-weight:700}.wallet-add:hover{color:var(--orange)}.wrow{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:12px}.w{display:block;width:100%;background:#fff!important;border-radius:13px;padding:12px!important;text-align:left}.w:hover{transform:translateY(-1px);box-shadow:0 7px 18px rgba(18,20,26,.06)}.wt{display:flex;align-items:center;gap:6px}
.flag{position:relative;display:inline-block;width:17px;height:17px;border-radius:50%;overflow:hidden;flex:none;box-shadow:inset 0 0 0 1px rgba(0,0,0,.08)}.flag.cn{background:#DE2910}.flag.cn:after{content:"★";position:absolute;left:3px;top:-1px;color:#FFDE00;font-size:8px}.flag.blue{background:linear-gradient(180deg,#2563EB 50%,#fff 50%)}.flag.gold{background:linear-gradient(180deg,#151515 33.3%,#DD0000 33.3% 66.6%,#FFCE00 66.6%)}.flag.currency-alt{background:linear-gradient(135deg,#4f46e5,#2e9bff)}
.wt b{font-size:12px;font-weight:700}.wt i{margin-left:auto;font-style:normal;color:var(--faint);font-size:13px}.w strong{display:block;margin-top:9px;font-size:14px;font-weight:800;letter-spacing:-.01em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.w small{display:block;margin-top:3px;min-height:21px;font-size:9.5px;color:var(--faint)}.w em{display:block;margin-top:7px;font-size:10.5px;font-weight:700;font-style:normal}.w em.ok{color:var(--green)}.w em.off{color:var(--red)}
.wallet-empty{display:flex;min-height:92px;align-items:center;justify-content:center;flex-direction:column;gap:9px;margin-top:12px;border:1px dashed #dfe2e8;border-radius:13px;color:var(--muted);font-size:12px}.wallet-empty button{padding:7px 12px!important;border-radius:999px!important;color:#fff!important;background:var(--ink)!important;font-size:11.5px;font-weight:700}
.balance-overlay{--panel:#f7f8fa;--line:#eff0f4;--ink:#12141a;--muted:#4f5b6b;--orange:#f05537;--green:#087a49;--red:#c92b35;position:fixed;z-index:2500;inset:0;background:rgba(18,20,26,.2);backdrop-filter:blur(2px);color:var(--ink);font-family:"Inter","Microsoft YaHei UI","PingFang SC","Segoe UI",system-ui,sans-serif}.balance-overlay button{font:inherit;color:inherit;border:0;cursor:pointer}.balance-drawer{position:absolute;top:18px;right:18px;bottom:18px;width:390px;padding:30px;border-radius:24px;background:#fff;box-shadow:0 26px 80px rgba(18,20,26,.24)}.drawer-close{position:absolute;top:18px;right:18px;display:grid!important;width:38px;height:38px;place-items:center;border-radius:50%;background:var(--panel)!important;font-size:20px}.drawer-mark{display:grid;width:50px;height:50px;place-items:center;margin-top:20px;border-radius:15px;color:#fff;background:var(--ink);font-size:17px;font-weight:850}.drawer-mark.cn{background:#de2910}.drawer-mark.blue{background:#2563eb}.drawer-mark.gold{background:linear-gradient(135deg,#151515,#dd0000 60%,#ffce00)}.drawer-mark.currency-alt{background:linear-gradient(135deg,#4f46e5,#2e9bff)}.balance-drawer>small{display:block;margin-top:17px;color:var(--orange);font-size:11.5px;font-weight:750}.balance-drawer h2{margin:4px 0 3px;font-size:24px}.balance-drawer>p{margin:0;color:var(--muted);font-size:12.5px}.drawer-balance{display:block;margin-top:25px;font-size:30px;letter-spacing:-.03em}.balance-drawer dl{margin:25px 0}.balance-drawer dl>div{display:flex;align-items:center;justify-content:space-between;padding:13px 0;border-bottom:1px solid var(--line);font-size:12.5px}.balance-drawer dt{color:var(--muted)}.balance-drawer dd{display:flex;align-items:center;gap:7px;max-width:220px;margin:0;text-align:right}.balance-drawer dd i{width:7px;height:7px;border-radius:50%}.balance-drawer dd i.ok{background:var(--green)}.balance-drawer dd i.off{background:var(--red)}.drawer-actions{display:flex;gap:9px}.drawer-actions .btn-dark,.drawer-actions .btn-light{height:44px}.drawer-edit{display:flex!important;width:100%;height:42px;align-items:center;justify-content:center;gap:7px;margin-top:9px;border:1px solid var(--line)!important;border-radius:999px!important;font-size:12.5px;font-weight:700;background:#fff}.drawer-edit:hover{background:var(--panel)!important}.drawer-enter-active,.drawer-leave-active{transition:.2s}.drawer-enter-from,.drawer-leave-to{opacity:0}.drawer-enter-from .balance-drawer,.drawer-leave-to .balance-drawer{transform:translateX(28px)}.balance-drawer{transition:transform .2s}.balance-overlay .btn-dark,.balance-overlay .btn-light{flex:1;display:flex;align-items:center;justify-content:center;border-radius:999px;font-size:13.5px;font-weight:600}.balance-overlay .btn-dark{color:#fff;background:var(--ink)}.balance-overlay .btn-light{background:var(--panel)}
.stats{display:grid;grid-template-columns:1fr 1fr;grid-auto-rows:1fr;gap:10px;padding:10px}.stat{background:#fff;border-radius:var(--r-md);padding:16px;min-width:0}.shead{display:flex;align-items:center;justify-content:space-between}.shead span{font-size:13px;font-weight:600;color:#3A3F4A}.sic{display:grid;place-items:center;width:30px;height:30px;border-radius:50%;background:#F3F4F6;color:#3A3F4A;font-size:13px;font-style:normal}.stat strong{display:block;margin-top:12px;font-size:26px;font-weight:800;letter-spacing:-.02em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.strend{display:flex;align-items:center;gap:6px;margin-top:8px;font-size:11.5px}.strend b{font-weight:700}.strend span{color:var(--faint)}.strend.up b{color:var(--green)}.strend.down b{color:var(--red)}.stat.orange{background:linear-gradient(135deg,#F36447,var(--orange-2));color:#fff}.stat.orange .shead span{color:rgba(255,255,255,.92)}.stat.orange .sic{background:rgba(255,255,255,.16);color:#fff}.stat.orange .strend b{color:#fff}.stat.orange .strend span{color:rgba(255,255,255,.72)}
.chart-card h3{font-size:16px;font-weight:800;letter-spacing:-.01em;margin:0}.csub{margin-top:4px;font-size:11.5px;color:var(--faint)}.plot-wrap{margin-top:14px;background:var(--panel);border-radius:var(--r-md);padding:16px 16px 8px}.plot-head{display:flex;align-items:center;justify-content:space-between;font-size:12.5px;font-weight:700}.legend{display:flex;align-items:center;gap:6px;font-size:11px;font-weight:500;color:var(--muted)}.legend i{display:inline-block;width:9px;height:9px;border-radius:2.5px;margin:0 2px 0 12px}.legend .b{background:var(--blue)}.legend .k{background:var(--ink)}
.plot{position:relative;height:196px;margin-top:12px;padding-left:30px}.gl{position:absolute;left:30px;right:0;border-top:1.5px dashed #E6E8ED}.gl-bottom{top:calc(100% - 24px)}.ylab{position:absolute;left:0;transform:translateY(-50%);font-size:10px;color:var(--faint);font-weight:500}.ylab-bottom{top:calc(100% - 24px)}.bars{position:absolute;inset:0 0 24px 30px;display:flex;align-items:flex-end;justify-content:space-around}.grp{position:relative;display:flex;align-items:flex-end;gap:4px;height:100%}.grp i{width:15px;border-radius:5px 5px 0 0}.grp .p{background:repeating-linear-gradient(-45deg,rgba(255,255,255,.42) 0 3px,transparent 3px 9px),linear-gradient(180deg,#F36447,var(--orange-2))}.grp .l{background:var(--ink)}.grp span{position:absolute;left:50%;transform:translateX(-50%);bottom:-20px;font-size:10.5px;color:var(--faint);white-space:nowrap}
.savings-goal-card{cursor:pointer}.savings-goal-card:focus-visible{outline:3px solid rgba(240,85,55,.28);outline-offset:2px}.savings-goal-head,.savings-goal-card footer{display:flex;align-items:center;justify-content:space-between;gap:12px}.savings-goal-head small{color:var(--orange);font-size:9px;font-weight:800;letter-spacing:.08em}.savings-goal-head h3{margin-top:4px;font-size:16px}.savings-goal-head>span{padding:5px 9px;border-radius:999px;background:#FFF0EC;color:#B93820;font-size:10.5px;font-weight:700}.savings-goal-amount{display:flex;align-items:baseline;gap:6px;margin-top:16px}.savings-goal-amount strong{font-size:22px}.savings-goal-amount span{color:var(--muted);font-size:12px}.savings-goal-progress{height:9px;margin-top:12px;overflow:hidden;border-radius:999px;background:#ECEEF2}.savings-goal-progress i{display:block;height:100%;border-radius:999px;background:linear-gradient(90deg,#F36447,var(--orange-2))}.savings-goal-card footer{margin-top:10px;color:var(--muted);font-size:11.5px}.savings-goal-card footer button{display:flex;min-height:44px;align-items:center;gap:4px;color:var(--ink);font-weight:700}
.limit{cursor:pointer}.limit h3,.mycards h3{font-size:15px;font-weight:800;display:flex;align-items:center;gap:8px;margin:0}.limitbar{display:flex;height:10px;border-radius:999px;overflow:hidden;margin-top:18px}.limitbar .used{background:linear-gradient(90deg,#F36447,var(--orange-2));width:0;border-radius:999px 0 0 999px}.limitbar .rest{flex:1;background:repeating-linear-gradient(-45deg,#E9EAEF 0 4px,#F4F5F8 4px 9px)}.limitmeta{display:flex;justify-content:space-between;margin-top:10px;font-size:12px}.limitmeta span{color:var(--faint)}.limitmeta b{font-weight:700;color:var(--ink)}
.mycards .add{margin-left:auto;background:#F3F4F6!important;border-radius:999px;padding:8px 14px!important;font-size:11.5px;font-weight:700}.mycards .add:hover{background:#E9EBEE!important}.mhead{display:flex;align-items:center}.cards{display:flex;gap:12px;margin-top:16px}.cc{position:relative;flex:1;height:158px;border-radius:18px;padding:14px;color:#fff;overflow:hidden}.cc.black{background:linear-gradient(140deg,#262932,#101216)}.cc.black:after{content:"";position:absolute;right:-46px;top:-46px;width:150px;height:150px;border:26px solid rgba(255,255,255,.045);border-radius:50%}.cc.orange{background:linear-gradient(140deg,#F4674B,var(--orange-2))}.cc .spark{position:absolute;color:rgba(255,255,255,.75);font-size:15px}.cc .s1{right:18px;top:38px}.cc .s2{right:36px;top:66px;font-size:10px}.cc .s3{right:16px;top:84px;font-size:9px}.cc .nfc{position:absolute;left:14px;top:14px;opacity:.85}.cc .active-pill{position:absolute;left:44px;top:13px;display:flex;align-items:center;gap:5px;background:#fff;color:var(--ink);border-radius:999px;padding:4px 10px;font-size:9.5px;font-weight:800}.cc .active-pill:before{content:"";width:5px;height:5px;border-radius:50%;background:var(--green)}.mc{position:absolute;right:14px;top:14px;display:flex}.mc i{width:22px;height:22px;border-radius:50%}.mc i:first-child{background:#EB001B;margin-right:-9px}.mc i:last-child{background:#F79E1B;opacity:.92}.cc .cnum{position:absolute;left:14px;bottom:14px;right:10px;display:flex;gap:10px;align-items:flex-end}.cc .cnum>div{flex:none;min-width:0}.cc label{display:block;font-size:7.5px;color:rgba(255,255,255,.55);text-transform:uppercase;letter-spacing:.03em;white-space:nowrap}.cc .cnum b{display:block;margin-top:3px;font-size:10.5px;font-weight:700;letter-spacing:.03em;white-space:nowrap}
.activities{display:flex;flex-direction:column}.ahead{display:flex;align-items:center;gap:12px}.ahead h3{font-size:16px;font-weight:800;letter-spacing:-.01em;margin:0}.searchbox{margin-left:auto;display:flex;align-items:center;gap:8px;border:1px solid var(--line);border-radius:11px;padding:0 12px;height:38px;width:212px;color:var(--faint)}.searchbox input{border:0;outline:0;background:none;font-size:12.5px;width:100%;color:var(--ink)}.searchbox input::placeholder{color:var(--faint)}.filter{display:flex;align-items:center;gap:8px;border:1px solid var(--line)!important;border-radius:11px;height:38px;padding:0 15px!important;font-size:12.5px;font-weight:600}.filter:hover{background:var(--panel)}
table{width:100%;border-collapse:collapse;margin-top:8px}th{font-size:11.5px;font-weight:600;color:var(--faint);text-align:left;padding:12px 8px;border-bottom:1px solid var(--line)}td{font-size:12.5px;padding:13px 8px;border-bottom:1px solid #F4F5F8;color:#3A3F4A;vertical-align:middle;white-space:nowrap}tr:last-child td{border-bottom:0}td.id{font-weight:600;color:var(--ink)}td.price{font-weight:700;color:var(--ink)}td.date{color:var(--muted)}.check-col{width:30px}.more-col{width:34px}.act{display:flex;align-items:center;gap:11px;font-weight:600;color:var(--ink)}.aic{display:grid;place-items:center;width:28px;height:28px;border-radius:8px;color:#fff;font-size:12px;font-weight:800;flex:none}.status{display:flex;align-items:center;gap:8px;font-size:12.5px}.status i{width:7px;height:7px;border-radius:50%;flex:none}.status .c{background:var(--green)}.status .p{background:var(--red)}.status .g{background:var(--olive)}.more{color:var(--faint);padding:0 4px!important}.cb{appearance:none;width:16px;height:16px;border:1.5px solid #D8DBE1;border-radius:5px;cursor:pointer;vertical-align:middle;position:relative}.cb:checked{background:var(--ink);border-color:var(--ink)}.cb:checked:after{content:"✓";position:absolute;inset:0;display:grid;place-items:center;color:#fff;font-size:10px;font-weight:900}tbody tr{transition:background .15s}tbody tr:hover{background:#FAFBFC}
.selected-summary{display:flex;align-items:center;gap:8px;padding:6px 9px;border-radius:999px;color:var(--ink);background:var(--panel);font-size:12px;font-weight:650}.selected-summary button{color:var(--orange);font-size:11.5px;font-weight:700}.filter.active{color:#fff;background:var(--ink)!important;border-color:var(--ink)!important}.activity-filters{display:flex;align-items:center;gap:7px;margin-top:12px;padding:9px 11px;border-radius:12px;background:var(--panel)}.activity-filters>span{margin-right:3px;color:var(--muted);font-size:12px;font-weight:650}.activity-filters button{padding:6px 11px!important;border-radius:999px!important;color:var(--muted);background:#fff!important;font-size:12px;font-weight:650}.activity-filters button.active{color:#fff;background:var(--ink)!important}.activity-filters .reset-filter{margin-left:auto;color:var(--orange);background:transparent!important}.activity-table-wrap{overflow-x:auto}.activity-table-wrap table{min-width:790px}.type-pill{display:inline-flex;padding:4px 8px;border-radius:999px;color:var(--muted);background:var(--panel);font-size:11.5px;font-weight:650}.price.income{color:var(--green)}.activities tbody tr:not(:has(.activity-empty)){cursor:pointer}.activities tbody tr:focus-visible{outline:2px solid rgba(240,85,55,.45);outline-offset:-2px}.row-actions{position:relative}.activity-menu{position:absolute;z-index:20;top:36px;right:4px;display:grid;width:126px;padding:5px;border:1px solid var(--line);border-radius:11px;background:#fff;box-shadow:0 12px 28px rgba(18,20,26,.14)}.activity-menu button{padding:8px 9px!important;border-radius:8px!important;text-align:left;font-size:11.5px;font-weight:650}.activity-menu button:hover{background:var(--panel)!important}.activity-empty{height:145px!important;text-align:center!important;white-space:normal}.activity-empty b,.activity-empty span{display:block}.activity-empty b{color:var(--ink);font-size:14px}.activity-empty span{margin-top:5px;color:var(--muted);font-size:12.5px}.activity-empty button{margin-top:12px;padding:8px 14px!important;border-radius:999px!important;color:#fff!important;background:var(--ink)!important;font-size:12px;font-weight:700}.activity-footer{display:flex;align-items:center;justify-content:space-between;margin-top:7px;padding-top:12px;border-top:1px solid var(--line);color:var(--muted);font-size:12px}.activity-footer button{display:flex;align-items:center;gap:4px;color:var(--ink);font-weight:700}.activity-footer button:hover{color:var(--orange)}
/* Readability pass: retain the Finexy geometry while making functional copy legible. */
.finance-home{--muted:#4F5B6B;--faint:#667085;--green:#087A49;--red:#C92B35}
.finance-home main h1{font-size:40px;line-height:1.15}.finance-home main h1,.finance-home main h2,.finance-home main h3{color:#12141A!important;opacity:1!important;-webkit-text-fill-color:#12141A!important;text-shadow:none!important}.sub{font-size:14.5px;line-height:1.6}
.uinfo b{font-size:13px}.uinfo small{font-size:12px;color:var(--muted)}
.account-current small{font-size:11.5px;color:var(--muted)}.account-current strong{font-size:13px}.account-current i{font-size:11px;color:#06683f}
.account-menu b{font-size:13px}.account-menu a small,.account-menu button small{font-size:12px;color:var(--muted);line-height:1.45}
.bhead>span{font-size:14px;color:var(--muted)}.cur{font-size:13px}.trend{font-size:13px}.trend span{color:var(--muted)}
.whead{font-size:13.5px}.whead span{font-size:12.5px;color:var(--muted)}.wt b{font-size:13px}.wt i{color:var(--muted)}
.w strong{font-size:15px}.w small{font-size:12px;color:var(--muted);line-height:1.4}.w em{font-size:12px}
.shead span{font-size:14px}.strend{font-size:12.5px}.strend span{color:var(--muted)}
.chart-card h3,.ahead h3{font-size:18px}.csub{font-size:13px;color:var(--muted);line-height:1.5}.plot-head{font-size:13.5px}.legend{font-size:12px;color:var(--muted)}
.ylab,.grp span{font-size:11.5px;color:var(--muted)}
.limit h3,.mycards h3{font-size:16px}.limitmeta{font-size:13px}.limitmeta span{color:var(--muted)}.mycards .add{font-size:12.5px}
.cc .active-pill{font-size:11px}.cc label{font-size:10.5px;color:rgba(255,255,255,.78)}.cc .cnum b{font-size:12px}
.searchbox input,.filter{font-size:13px}.searchbox,.searchbox input::placeholder{color:var(--muted)}
table th{font-size:12.5px;color:var(--muted)}table td{font-size:13px}.status{font-size:13px}
/* Readability v2: dense cards use a consistent functional type floor. */
.finance-home{--muted:#475467;--faint:#5f6b7a}.finance-home .card,.finance-home .panel{color:#12141a}.utility-panel header small,.utility-results>small,.utility-results small,.notice-list small,.help-panel small{color:#475467;font-size:12.5px;line-height:1.45}.utility-results b,.notice-list b,.help-panel b{font-size:13.5px}.balance-menu>small{color:#475467;font-size:12.5px}.balance-menu b{font-size:13px}.balance-menu button small,.balance-menu-actions button{color:#475467;font-size:12px}.whead{font-size:14px}.whead span,.wallet-add{font-size:13px}.w{border:1px solid #e5e8ed}.wt b{font-size:13.5px}.w strong{font-size:16px}.w small{min-height:0;font-size:12.5px;color:#475467}.w em{font-size:12.5px}.shead span{color:#344054;font-size:14.5px;font-weight:700}.stat strong{font-size:28px}.strend{font-size:13px}.strend span{color:#475467}.chart-card h3,.ahead h3{font-size:19px}.csub{font-size:13.5px;color:#475467}.plot-head{font-size:14px}.legend{font-size:12.5px;color:#475467}.ylab,.grp span{font-size:12px;color:#475467}.limit h3,.mycards h3{font-size:17px}.limitmeta{font-size:13.5px}.limitmeta span{color:#475467}.mycards .add{font-size:13px}.ahead{gap:14px}.searchbox input,.filter{font-size:13.5px}.activities th{color:#475467;font-size:13px;font-weight:700}.activities td{color:#344054;font-size:13.5px}.activities .act,.activities td.id,.activities td.price{font-weight:700}.type-pill,.activity-menu button{font-size:12.5px}.status{font-size:13.5px}.activity-footer{color:#475467;font-size:12.5px}.balance-drawer>small{font-size:12.5px}.balance-drawer>p,.balance-drawer dl>div{font-size:13.5px}.drawer-edit{font-size:13px}
@media(max-width:1500px){
    .activity-table-wrap{overflow-x:visible}
    .activity-table-wrap table{min-width:0;table-layout:auto}
    .activities th:nth-child(2),.activities td:nth-child(2),.activities th:nth-child(4),.activities td:nth-child(4){display:none}
    .activities th,.activities td{padding-left:6px;padding-right:6px}
    .activities .act{gap:8px}
    .activities .date{font-size:12px}
}
@media(max-width:1250px){.finance-home{padding:28px 40px}.tabs{gap:22px;margin-left:38px}}
@media(prefers-reduced-motion:reduce){.finance-home *{transition:none!important}}
.finance-tasks .mhead>div{display:grid;gap:3px}.finance-tasks h3{display:flex;align-items:center;gap:8px;margin:0;color:#12141a;font-size:17px;font-weight:800}.finance-tasks .mhead p{margin:0;color:#475467;font-size:12.5px;line-height:1.45}.finance-tasks .add{margin-left:auto;padding:8px 14px!important;border-radius:999px;background:#f3f4f6!important;font-size:13px;font-weight:700}.finance-tasks .add:hover{background:#e9ebee!important}.task-list{display:grid;gap:8px;margin-top:14px}.task-list>button{display:grid;width:100%;grid-template-columns:36px minmax(0,1fr) auto 18px;align-items:center;gap:10px;padding:11px!important;border:1px solid #e7eaf0!important;border-radius:13px!important;background:#fff!important;text-align:left}.task-list>button:hover{border-color:#cfd5df!important;box-shadow:0 6px 16px rgba(18,20,26,.06);transform:translateY(-1px)}.task-list>button.done{background:#f8faf9!important}.task-list>button.done>strong{color:#087a49}.task-icon{display:grid;width:36px;height:36px;place-items:center;border-radius:10px;font-size:11px;font-style:normal;font-weight:850}.task-icon.pending{color:#b54708;background:#fff4e5}.task-list>button.done .task-icon.pending{color:#087a49;background:#eaf8f1}.task-icon.plan{color:#3f3ac4;background:#eeedff}.task-list span{display:grid;gap:3px;min-width:0}.task-list span b{color:#12141a;font-size:13.5px}.task-list span small{overflow:hidden;color:#475467;font-size:12.5px;text-overflow:ellipsis;white-space:nowrap}.task-list>button>strong{color:#12141a;font-size:13px}.task-list>button>.v-icon{color:#667085}

.wallet-view-all { display: flex; align-items: center; min-height: 44px; margin-top: 8px; color: var(--ink); font-size: 13px; font-weight: 600; text-decoration: underline; }
.wallet-view-all:focus-visible { outline: 2px solid var(--orange); outline-offset: 3px; }
.wallet-carousel { margin-top: 12px; }
.grid { grid-template-columns: repeat(3, minmax(0, 1fr)); align-items: start; }
.home-left-column { display: grid; gap: 18px; align-content: start; min-width: 0; }
.home-right-column { grid-column: 2 / 4; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; align-items: start; min-width: 0; }
.home-right-column .activities { grid-column: 1 / -1; }
.balance-card { padding: 18px; }
.balance-card .wallets { margin-top: 16px; padding: 12px; }
.balance-card .amount { font-size: 28px; }
.balance-card .bactions { margin-top: 14px; gap: 8px; }
.home-right-column .stats, .home-right-column .chart-card { grid-column: 1 / -1; }
.stats { grid-template-columns: repeat(4, minmax(0, 1fr)); grid-auto-rows: minmax(116px, auto); padding: 8px; gap: 8px; }
.stat { padding: 12px; }
.stat strong { font-size: clamp(18px, 1.4vw, 22px); white-space: normal; overflow: visible; text-overflow: clip; overflow-wrap: anywhere; font-weight: 650; }
.stats .shead span { font-size: 13px; font-weight: 600; }
.stats .sic { width: 24px; height: 24px; flex: none; }
.chart-card { padding: 18px; }
.chart-card h3 { font-size: 16px; font-weight: 650; display: flex; align-items: center; justify-content: space-between; }
.chart-card h3 small { font-size: 12px; color: var(--muted); font-weight: 500; }
.chart-card .plot-wrap { margin-top: 12px; padding: 12px 14px 8px; }
.chart-card .plot { height: 160px; }
.finance-home main h1 { font-size: 30px; font-weight: 650; margin-bottom: 16px; }
.ledger-context { margin-bottom: 16px; }
.ledger-context-copy > small { display: none; }
.balance-card .wallets { background: transparent; padding: 0; border-top: 1px solid var(--line); padding-top: 12px; }
.balance-card .amount { font-weight: 650; }
.wallet-carousel { margin-top: 4px; }
.balance-card .wallet-view-all { justify-content: center; margin-top: 0; }
.wallet-pager { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 8px; }
.wallet-pager > button { display: grid; place-items: center; width: 44px; height: 44px; border: 1px solid var(--line); border-radius: 50%; background: #fff !important; }
.wallet-pager > button:disabled { opacity: .4; cursor: not-allowed; }
.wallet-pager > span { color: var(--ink); font-size: 13px; font-variant-numeric: tabular-nums; text-align: center; }
.wallet-pager small { color: var(--muted); font-size: 12px; }
.wallet-pager > button:focus-visible { outline: 2px solid var(--orange); outline-offset: 3px; }
.wallet-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@media (max-width: 1160px) {
    .finance-home { min-width: 0; }
    .finance-home .app { min-width: 1080px; }
}
</style>
