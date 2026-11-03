<template>
    <section class="ledger-report-panel" :class="{ 'report-mobile': mobile }" data-testid="ledger-report-panel" :aria-labelledby="titleId" :aria-busy="reports.loading">
        <header class="report-header">
            <div><p class="report-eyebrow">{{ ledgerName }} · 只读报告</p><h2 :id="titleId">当前账本报告</h2><p class="report-copy">按原币种核对收支，退款冲减支出；转账与余额调整单列。</p></div>
            <button type="button" :aria-pressed="!showAmounts" @click="toggleAmounts">{{ showAmounts ? '隐藏报告金额' : '显示报告金额' }}</button>
        </header>

        <form class="report-filter-form" data-testid="report-filters" @submit.prevent="applyFilters">
            <details class="report-filter-disclosure" open>
                <summary>报告筛选 <span class="report-copy">日期 · 类型 · 账户 · 分类 · 标签</span></summary>
                <div class="report-filters">
                    <fieldset class="report-date-fields"><legend>日期范围</legend>
                        <label>报告月份<input v-model="form.month" type="month" min="1970-01" max="9998-12" @change="changeMonth" /></label>
                        <label>开始日期<input v-model="form.startDate" type="date" min="1970-01-01" max="9998-12-31" required /></label>
                        <label>结束日期<input v-model="form.endDate" type="date" min="1970-01-01" max="9998-12-31" required /></label>
                        <label class="report-timezone">IANA 时区<input v-model="form.timezone" type="text" :list="timezoneListId" autocomplete="off" required /><datalist :id="timezoneListId"><option value="Asia/Shanghai" /><option value="UTC" /><option value="America/New_York" /><option value="Europe/London" /></datalist></label>
                    </fieldset>
                    <fieldset><legend>类型</legend><div class="report-check-grid">
                        <label v-for="item in typeOptions" :key="item.value" class="report-check"><input v-model="form.types" type="checkbox" :value="item.value" /><span>{{ item.label }}</span></label>
                    </div></fieldset>
                    <fieldset><legend>账户</legend>
                        <label>匹配方式<select v-model="form.accountMode" aria-label="账户匹配方式"><option value="include">包含选中账户</option><option value="exclude">排除选中账户</option></select></label>
                        <label class="report-check"><input v-model="form.accountDescendants" type="checkbox" /><span>包含子账户</span></label>
                        <details class="report-option-picker"><summary>选择账户（{{ form.accountIds.length }}）</summary>
                            <p v-if="optionsLoading" class="report-copy" role="status">正在读取当前账本账户…</p>
                            <p v-else-if="optionsError" class="report-error">账户选项读取失败，请重试。</p>
                            <p v-else-if="!accountOptions.length" class="report-copy">此账本没有可选账户。</p>
                            <div v-else class="report-option-list"><label v-for="option in accountOptions" :key="option.id" class="report-check"><input v-model="form.accountIds" type="checkbox" :value="option.id" /><span>{{ option.label }}</span></label></div>
                        </details>
                        <button v-if="form.accountIds.length" type="button" class="report-quiet" @click="form.accountIds = []">清除账户筛选</button>
                    </fieldset>
                    <fieldset><legend>分类</legend>
                        <label>匹配方式<select v-model="form.categoryMode" aria-label="分类匹配方式"><option value="include">包含选中分类</option><option value="exclude">排除选中分类</option></select></label>
                        <label class="report-check"><input v-model="form.categoryDescendants" type="checkbox" /><span>包含子分类</span></label>
                        <details class="report-option-picker"><summary>选择分类（{{ form.categoryIds.length }}）</summary>
                            <p v-if="optionsLoading" class="report-copy" role="status">正在读取当前账本分类…</p>
                            <p v-else-if="optionsError" class="report-error">分类选项读取失败，请重试。</p>
                            <p v-else-if="!categoryOptions.length" class="report-copy">此账本没有可选分类。</p>
                            <div v-else class="report-option-list"><label v-for="option in categoryOptions" :key="option.id" class="report-check"><input v-model="form.categoryIds" type="checkbox" :value="option.id" /><span>{{ option.label }}</span></label></div>
                        </details>
                        <button v-if="form.categoryIds.length" type="button" class="report-quiet" @click="form.categoryIds = []">清除分类筛选</button>
                    </fieldset>
                    <fieldset class="report-tag-fields"><legend>标签</legend>
                        <p class="report-copy">各组同时满足；只提供此账本可读的关联标签。</p>
                        <div v-for="(clause, index) in form.tagClauses" :key="clause.key" class="report-tag-clause">
                            <label>第 {{ index + 1 }} 组匹配方式<select v-model="clause.mode" :aria-label="`第${index + 1}组标签匹配方式`" @change="changeTagMode(index)"><option value="any">包含任一标签</option><option value="all">包含全部标签</option><option value="notAny">不含任一标签</option><option value="notAll">未同时含全部标签</option><option value="none">没有标签</option></select></label>
                            <details v-if="clause.mode !== 'none'" class="report-option-picker"><summary>选择标签（{{ clause.ids.length }}）</summary>
                                <p v-if="!readableTags.length" class="report-copy">{{ report ? '此账本没有可读关联标签。' : '成功读取报告后提供本账本可读标签。' }}</p>
                                <div v-else class="report-option-list"><label v-for="tag in readableTags" :key="tag.id" class="report-check"><input v-model="clause.ids" type="checkbox" :value="tag.id" /><span>{{ tag.name }}{{ tag.hidden ? ' · 已隐藏' : '' }} · #{{ tag.id }}</span></label></div>
                            </details>
                            <button v-if="form.tagClauses.length > 1" type="button" class="report-quiet" :aria-label="`移除第${index + 1}组标签条件`" @click="form.tagClauses.splice(index, 1)">移除此组</button>
                        </div>
                        <div class="report-actions"><button type="button" :disabled="form.tagClauses.length >= 20 || form.tagClauses.some(item => item.mode === 'none')" @click="addTagClause">增加标签条件</button><button type="button" class="report-quiet" @click="clearTags">清除标签筛选</button></div>
                    </fieldset>
                </div>
                <div class="report-extra-fields">
                    <label>原币种筛选（可选）<input v-model="form.currencyText" type="text" autocapitalize="characters" autocomplete="off" placeholder="全部币种；例如 CNY, USD" /></label>
                    <label>隐藏记录<select v-model="form.hidden" aria-label="隐藏记录"><option value="exclude">排除隐藏记录</option><option value="include">包含隐藏记录的实际金额</option></select></label>
                </div>
                <p class="report-copy">账户与分类未勾选表示全部。隐藏账户的历史记录仍按范围统计；任一转账侧符合筛选时保留双侧原币金额。</p>
            </details>
            <div v-if="optionsError" class="report-notice" role="alert"><p>{{ optionsError }}</p><button type="button" :disabled="optionsLoading" @click="reloadOptions">重试读取筛选选项</button></div>
            <div class="report-filter-actions"><p class="report-copy">{{ formDirty ? '筛选已更改，更新后查看同一范围的汇总、图表和明细。' : '图表、合计、明细和下载使用同一报告范围。' }}</p><button type="submit" class="report-primary" data-testid="report-apply" :disabled="reports.loading || identitySuspended">{{ reports.loading ? '读取报告中…' : '更新报告' }}</button></div>
        </form>

        <div v-if="panelError" class="report-notice report-error" role="alert"><p>{{ panelError }}</p><button v-if="identitySuspended" type="button" @click="reloadSession">刷新页面核对账号</button><button v-else type="button" :disabled="reports.loading" @click="retryReport">重试读取报告</button></div>
        <p v-if="reports.loading" class="report-loading" role="status">正在核对当前账本的完整统计范围，请稍候…</p>
        <template v-if="report">
            <div class="report-scope-strip"><div><strong>{{ activeQuery?.startDate }} 至 {{ activeQuery?.endDate }}</strong><span>{{ activeQuery?.timezone }} · {{ report.matchedCount }} 笔 · {{ roleLabel(report.memberRole) }}</span></div><span class="report-source">服务器完整汇总</span></div>
            <p v-if="drillStack.length" class="report-drill-path"><span>{{ drillLabels.join(' / ') }}</span><button type="button" :disabled="reports.loading" @click="returnToReport">返回上一级报告</button></p>
            <p v-if="!showAmounts" class="report-notice">金额、图表长度和环比数值已隐藏。</p>
            <div v-if="report.matchedCount === 0" class="report-empty"><h3>此范围没有匹配流水</h3><p>可以调整日期或清除账户、分类和标签条件。读取已完成，这是真实空结果。</p><button type="button" :disabled="reports.loading" @click="resetFilters">清除筛选并读取本月</button></div>
            <div v-else class="report-totals" data-testid="report-totals">
                <article v-for="total in report.totals" :key="total.currency" class="report-currency-total"><header><h3>{{ total.currency }}</h3><span>原币种 · 不折算</span></header>
                    <dl><div><dt>收入</dt><dd><button type="button" :disabled="!canDrillMoney('income', total.currency) || reports.loading" @click="drillMoney('income', total.currency)">{{ money(total.income, total.currency) }}</button></dd></div><div><dt>净支出（含退款）</dt><dd><button type="button" :disabled="!canDrillMoney('expense', total.currency) || reports.loading" @click="drillMoney('expense', total.currency)">{{ money(total.expense, total.currency) }}</button></dd></div><div class="report-net"><dt>收支净额</dt><dd><button type="button" :disabled="!canDrillMoney('net', total.currency) || reports.loading" @click="drillMoney('net', total.currency)">{{ money(total.net, total.currency) }}</button></dd></div></dl>
                    <details class="report-transfer-totals"><summary>转账与余额调整</summary><dl><div><dt>转入</dt><dd>{{ money(total.transferIn, total.currency) }}</dd></div><div><dt>转出</dt><dd>{{ money(total.transferOut, total.currency) }}</dd></div><div><dt>余额调整差额</dt><dd>{{ money(total.adjustment, total.currency) }}</dd></div></dl><button type="button" :disabled="!canDrillMoney('transfer') || reports.loading" @click="drillMoney('transfer')">查看转账明细</button><button type="button" :disabled="!canDrillMoney('adjustment') || reports.loading" @click="drillMoney('adjustment')">查看余额调整</button></details>
                </article>
            </div>
            <section class="report-comparison" data-testid="report-comparison" aria-label="月度对比"><header class="report-section-header"><div><h3>月度对比</h3><p class="report-copy">沿用相同账本、时区、类型、账户、分类、标签、币种和隐藏规则。</p></div><button type="button" :disabled="comparisonLoading || reports.loading || !canCompare" @click="comparePrevious">{{ comparisonLoading ? '比较读取中…' : '比较上月' }}</button></header>
                <p v-if="!canCompare" class="report-copy">选择完整自然月后可比较上月。</p><p v-if="comparisonError" class="report-error" role="alert">{{ comparisonError }}</p>
                <div v-if="comparison" class="report-table-wrap"><table><caption>{{ comparison.query.startDate }} 至 {{ comparison.query.endDate }} 与当前月的原币种收支净额</caption><thead><tr><th scope="col">币种</th><th scope="col">上月净额</th><th scope="col">本月净额</th><th scope="col">变化</th></tr></thead><tbody><tr v-for="item in comparisonRows" :key="item.currency"><th scope="row">{{ item.currency }}</th><td>{{ money(item.previous, item.currency) }}</td><td>{{ money(item.current, item.currency) }}</td><td>{{ showAmounts ? item.percentage ?? '上月为零，不计算百分比' : '已隐藏' }}</td></tr></tbody></table></div>
            </section>
            <template v-if="report.matchedCount">
                <div class="report-chart-controls"><label>图表币种<select v-model="chartCurrency" aria-label="图表币种"><option v-for="total in report.totals" :key="total.currency" :value="total.currency">{{ total.currency }}</option></select></label><label>图表指标<select v-model="chartMetric" aria-label="图表指标"><option value="expense">净支出</option><option value="income">收入</option><option value="net">收支净额</option><option value="transferIn">转入</option><option value="transferOut">转出</option><option value="adjustment">余额调整</option></select></label></div>
                <div class="report-chart-grid"><section v-for="section in chartSections" :key="section.kind" class="report-chart" :data-testid="section.testId"><header><h3>{{ section.title }}</h3><p class="report-copy">{{ chartCurrency }} · {{ metricLabel }} · {{ activeQuery?.startDate }} 至 {{ activeQuery?.endDate }}</p></header>
                    <p v-if="section.kind === 'tag'" class="report-copy">同一流水可属于多个标签，各组合计不能直接相加。</p><p v-if="!section.rows.length" class="report-copy">当前币种在此分组中没有记录。</p>
                    <ul v-else class="report-bars"><li v-for="item in section.rows" :key="item.key"><button type="button" class="report-group-button" :data-report-group-id="item.key" :disabled="reports.loading" @click="drillGroup(section.kind, item.key, item.label)"><span class="report-group-label">{{ item.label }}<small>{{ item.count }} 笔</small></span><span class="report-group-money">{{ money(item.amount, chartCurrency) }}</span><span v-if="showAmounts" class="report-bar-track" aria-hidden="true"><span :class="{ 'report-bar-negative': item.amount.startsWith('-') }" :style="{ width: ledgerReportBarWidth(item.amount, section.maximum) }"></span></span></button></li></ul>
                </section></div>
            </template>
            <section class="report-details-section"><header class="report-section-header"><div><h3 ref="detailsTitle" tabindex="-1">匹配明细</h3><p class="report-copy">已读取 {{ loadedRows.length }} / {{ report.matchedCount }} 笔明细{{ detailsComplete ? ' · 已完整核对' : ' · 继续加载后核对完整明细' }}</p></div><label>每页明细<select v-model="pageSize" aria-label="每页明细" :disabled="reports.loading" @change="changePageSize"><option :value="20">20 笔</option><option :value="50">50 笔</option><option :value="100">100 笔</option><option :value="200">200 笔</option></select></label></header>
                <div class="report-table-wrap" tabindex="0" aria-label="匹配流水表格，可横向滚动"><table data-testid="report-details"><caption>{{ ledgerName }} · {{ activeQuery?.timezone }} · {{ activeQuery?.hidden === 'include' ? '包含隐藏记录实际金额' : '排除隐藏记录' }}</caption><thead><tr><th scope="col">日期与类型</th><th scope="col">分类 / 备注</th><th scope="col">资金账户</th><th scope="col">原币金额</th><th scope="col">详情</th></tr></thead><tbody><tr v-for="row in displayedRows" :key="row.id" :data-report-id="row.id" data-testid="report-detail-row"><td>{{ row.date }}<small>{{ typeLabel(row.type, row.source?.amount) }}{{ row.hidden ? ' · 已隐藏记录' : '' }}</small></td><td>{{ row.categoryName || '无分类' }}<small class="report-row-comment">{{ showAmounts ? row.comment || '无备注' : '备注已隐藏' }}</small></td><td><template v-if="row.source">{{ row.source.accountName }} · {{ row.source.currency }}</template><template v-if="row.destination"><small v-if="row.source">转入</small>{{ row.destination.accountName }} · {{ row.destination.currency }}</template></td><td class="report-row-amount"><template v-if="row.source">{{ money(row.type === 1 ? row.adjustment : row.source.amount, row.source.currency) }}</template><small v-if="row.destination">{{ money(row.destination.amount, row.destination.currency) }}</small></td><td><button type="button" :aria-label="`查看${row.date}${typeLabel(row.type)}流水详情`" @click="selectedDetail = row">查看</button></td></tr></tbody></table></div>
                <button v-if="!detailsComplete" type="button" class="report-load-more" :disabled="reports.loading" @click="loadMore">{{ reports.loading ? '正在加载明细…' : '加载更多明细' }}</button>
            </section>
            <article v-if="selectedDetail" class="report-record-detail" aria-label="流水完整详情"><header class="report-section-header"><h3>流水详情</h3><button type="button" @click="selectedDetail = null">关闭详情</button></header><dl><div><dt>流水 ID</dt><dd>{{ selectedDetail.id }}</dd></div><div><dt>日期 / 时区</dt><dd>{{ selectedDetail.date }} · {{ activeQuery?.timezone }}</dd></div><div><dt>类型</dt><dd>{{ typeLabel(selectedDetail.type, selectedDetail.source?.amount) }}</dd></div><div><dt>分类</dt><dd>{{ selectedDetail.categoryName || '无分类' }} · #{{ selectedDetail.categoryId }}</dd></div><div v-if="selectedDetail.source"><dt>{{ selectedDetail.type === 4 ? '转出账户' : '账户' }}</dt><dd>{{ selectedDetail.source.accountName }} · #{{ selectedDetail.source.accountId }}<small>{{ money(selectedDetail.type === 1 ? selectedDetail.adjustment : selectedDetail.source.amount, selectedDetail.source.currency) }}</small></dd></div><div v-if="selectedDetail.destination"><dt>转入账户</dt><dd>{{ selectedDetail.destination.accountName }} · #{{ selectedDetail.destination.accountId }}<small>{{ money(selectedDetail.destination.amount, selectedDetail.destination.currency) }}</small></dd></div><div v-if="selectedDetail.balanceTarget !== undefined && selectedDetail.source"><dt>调整后目标余额</dt><dd>{{ money(selectedDetail.balanceTarget, selectedDetail.source.currency) }}</dd></div><div><dt>标签</dt><dd>{{ selectedDetail.tags.map(tag => `${tag.name} #${tag.id}`).join('、') || '无标签' }}</dd></div><div><dt>记账人 / 付款人</dt><dd>#{{ selectedDetail.recorderUid }} / #{{ selectedDetail.payerUid }}</dd></div><div><dt>备注</dt><dd class="report-full-comment">{{ showAmounts ? selectedDetail.comment || '无备注' : '备注已隐藏' }}</dd></div></dl></article>
            <footer class="report-export-actions"><div><p class="report-copy">CSV 使用本页完整范围，按原币保留两位小数及精确整数金额。</p><p v-if="!report.exportAllowed" class="report-copy">当前服务器或账号不允许导出，仍可读取报告。</p><p v-else-if="!showAmounts" class="report-copy">请先显示金额，再核对导出预览。</p></div><button type="button" :disabled="reports.loading || !report.exportAllowed || !showAmounts" @click="prepareExport">预览 CSV 导出</button></footer>
        </template>
        <p v-else-if="!panelError && !reports.loading" class="report-empty">{{ formDirty ? '当前筛选尚未应用，请更新报告。' : '选择范围后读取当前账本报告。' }}</p>
        <p v-if="downloadStatus" class="report-notice" role="status">{{ downloadStatus }}</p>

        <dialog ref="exportDialog" class="report-export-dialog" data-testid="report-export-preview" :aria-labelledby="exportTitleId" @cancel.prevent="closeExport">
            <form v-if="shownPreview" @submit.prevent="downloadCSV"><h3 :id="exportTitleId" ref="exportTitle" tabindex="-1">核对 CSV 导出</h3><p>{{ ledgerName }} · {{ shownPreview.query.startDate }} 至 {{ shownPreview.query.endDate }}</p><p>{{ shownPreview.query.timezone }} · {{ shownPreview.matchedCount }} 笔 · {{ shownPreview.query.hidden === 'include' ? '包含已隐藏记录的实际金额' : '排除隐藏记录' }}</p><dl class="report-preview-scope"><div v-for="item in previewScope" :key="item.label"><dt>{{ item.label }}</dt><dd>{{ item.value }}</dd></div></dl><p class="report-copy">预览失效或数据变化后需要重新核对。</p><dl class="report-export-totals"><div v-for="total in shownPreview.totals" :key="total.currency"><dt>{{ total.currency }}</dt><dd>收入 {{ money(total.income, total.currency) }}<br />净支出 {{ money(total.expense, total.currency) }}<br />净额 {{ money(total.net, total.currency) }}<br />转入 {{ money(total.transferIn, total.currency) }} / 转出 {{ money(total.transferOut, total.currency) }}<br />调整 {{ money(total.adjustment, total.currency) }}</dd></div></dl><p v-if="!shownPreview.matchedCount">零笔报告将下载只有表头的文件。</p><label class="report-check"><input v-model="exportConfirmed" type="checkbox" :disabled="reports.loading" /><span>我已核对以上范围、笔数和金额</span></label><label v-if="shownPreview.includesHiddenAmounts" class="report-check"><input v-model="hiddenConfirmed" type="checkbox" :disabled="reports.loading" /><span>我同意导出已隐藏记录的实际金额</span></label><p v-if="exportError" class="report-error" role="alert">{{ exportError }}</p><p v-if="previewExpired" class="report-error" role="alert">预览已失效，请关闭后重新读取并预览。</p><div class="report-actions"><button type="button" :disabled="reports.loading" @click="closeExport">取消导出</button><button type="submit" class="report-primary" :disabled="reports.loading || !exportConfirmed || shownPreview.includesHiddenAmounts && !hiddenConfirmed || previewExpired || !showAmounts">{{ reports.loading ? '正在核对文件…' : '确认并下载 CSV' }}</button></div></form>
        </dialog>
    </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, useId, watch } from 'vue';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useLedgerReportStore } from '@/stores/ledgerReport.ts';
import { useSettingsStore } from '@/stores/setting.ts';
import { useUserStore } from '@/stores/user.ts';
import services from '@/lib/services.ts';
import { getCurrentUserInfo, getTransactionStateIdentity, isUserLogined } from '@/lib/userstate.ts';
import { copyLedgerReportQuery, formatLedgerReportAmount, ledgerReportQueryKey, validateLedgerReportPage } from '@/lib/ledger_report.ts';
import { ledgerReportSessionChanged, ledgerReportStorageInvalidatesSession, type LedgerReportSession } from '@/lib/ledger_report_session.ts';
import { ledgerReportBarWidth, ledgerReportDrillQuery, ledgerReportMonthRange, ledgerReportPanelOptions, ledgerReportPercentageChange, previousLedgerReportMonthQuery, type LedgerReportDrill, type LedgerReportOption } from '@/lib/ledger_report_panel.ts';
import type { LedgerReportDetail, LedgerReportExportPreview, LedgerReportMoney, LedgerReportPage, LedgerReportQuery, LedgerReportResource, LedgerReportTagFilter, LedgerReportType } from '@/models/ledger_report.ts';

withDefaults(defineProps<{ mobile?: boolean }>(), { mobile: false });
const ledgers = useLedgersStore(), reports = useLedgerReportStore(), settings = useSettingsStore(), user = useUserStore();
const suffix = useId(), titleId = `report-title-${suffix}`, exportTitleId = `report-export-title-${suffix}`, timezoneListId = `report-timezones-${suffix}`;
const typeOptions: readonly { value: LedgerReportType; label: string }[] = [{ value: 2, label: '收入' }, { value: 3, label: '支出' }, { value: 4, label: '转账' }, { value: 1, label: '余额调整' }];
type TagClause = { key: number; mode: LedgerReportTagFilter['mode']; ids: string[] };
let clauseKey = 0;
function initialForm() {
    const now = new Date(), month = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    return { month, ...ledgerReportMonthRange(month), timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', types: [1, 2, 3, 4] as LedgerReportType[], accountMode: 'include' as 'include' | 'exclude', accountIds: [] as string[], accountDescendants: true, categoryMode: 'include' as 'include' | 'exclude', categoryIds: [] as string[], categoryDescendants: true, tagClauses: [{ key: ++clauseKey, mode: 'any', ids: [] }] as TagClause[], currencyText: '', hidden: 'exclude' as 'include' | 'exclude' };
}
const form = ref(initialForm());
const activeQuery = shallowRef<LedgerReportQuery | null>(null), mainQuery = shallowRef<LedgerReportQuery | null>(null);
const accountOptions = shallowRef<readonly LedgerReportOption[]>([]), categoryOptions = shallowRef<readonly LedgerReportOption[]>([]), readableTags = shallowRef<readonly LedgerReportResource[]>([]);
const optionsLoading = ref(false), optionsError = ref(''), panelError = ref(''), downloadStatus = ref('');
const comparison = shallowRef<LedgerReportPage | null>(null), comparisonLoading = ref(false), comparisonError = ref('');
const chartCurrency = ref(''), chartMetric = ref<keyof Omit<LedgerReportMoney, 'currency'>>('expense');
const selectedDetail = shallowRef<LedgerReportDetail | null>(null), pageSize = ref(50);
const drillStack = shallowRef<readonly { query: LedgerReportQuery; label: string }[]>([]), drillLabels = ref<string[]>([]);
const exportDialog = ref<HTMLDialogElement | null>(null), exportTitle = ref<HTMLElement | null>(null), detailsTitle = ref<HTMLElement | null>(null);
const shownPreview = shallowRef<LedgerReportExportPreview | null>(null), exportConfirmed = ref(false), hiddenConfirmed = ref(false), exportError = ref('');
let lastFocus: HTMLElement | null = null, generation = 0, optionsGeneration = 0, readGeneration = 0, comparisonGeneration = 0, alive = true;
const clock = ref(Date.now());
const previewClock = window.setInterval(() => { clock.value = Date.now(); }, 1000);
const objectURLs = new Set<string>();
const identitySuspended = ref(false);
const initialSession = sessionSnapshot();
const changedSessionMessage = '登录账号已在其他页面变化，报告已清除。请刷新页面后重新读取。';
interface Context { readonly identity: string; readonly username: string; readonly ledgerId: string; readonly generation: number }
const ledgerName = computed(() => ledgers.allLedgers.find(item => item.id === ledgers.selectedLedgerId)?.name ?? `账本 #${ledgers.selectedLedgerId}`);
const showAmounts = computed(() => settings.appSettings.showAmountInHomePage);
function capture(): Context {
    if (!sessionCurrent()) { invalidateSession(); throw new Error(changedSessionMessage); }
    const identity = getTransactionStateIdentity(), username = getCurrentUserInfo()?.username;
    if (!identity || !username) throw new Error('请先登录后读取当前账本报告');
    return { identity, username, ledgerId: ledgers.selectedLedgerId, generation };
}
function current(context: Context): boolean { return alive && sessionCurrent() && context.generation === generation && context.identity === getTransactionStateIdentity() && context.username === getCurrentUserInfo()?.username && context.ledgerId === ledgers.selectedLedgerId; }
function sessionSnapshot(): LedgerReportSession {
    try { return { identity: getTransactionStateIdentity(), username: getCurrentUserInfo()?.username ?? null, hasToken: isUserLogined() }; }
    catch { return { identity: null, username: null, hasToken: false }; }
}
function sessionCurrent(): boolean { return !identitySuspended.value && !ledgerReportSessionChanged(initialSession, sessionSnapshot(), user.currentUserBasicInfo?.username ?? null); }
function invalidateSession(): void {
    if (identitySuspended.value) return;
    identitySuspended.value = true; clearScope(); panelError.value = changedSessionMessage;
    objectURLs.forEach(url => URL.revokeObjectURL(url)); objectURLs.clear();
}
function changedElsewhere(event: StorageEvent): void {
    if (event.storageArea !== window.localStorage) return;
    if (ledgerReportStorageInvalidatesSession(event.key, event.newValue, initialSession, sessionSnapshot(), user.currentUserBasicInfo?.username ?? null)) invalidateSession();
}
function checkSession(): void { if (alive && !sessionCurrent()) invalidateSession(); }
function visibilityChanged(): void { if (document.visibilityState === 'visible') checkSession(); }
function reloadSession(): void { window.location.reload(); }
function buildQuery(): LedgerReportQuery {
    const input = form.value;
    const clauses = input.tagClauses.filter(clause => clause.mode === 'none' || clause.ids.length > 0);
    if (input.tagClauses.length > 1 && clauses.length !== input.tagClauses.length) throw new Error('请为每组标签条件选择标签，或移除空组');
    const currencies = input.currencyText.trim() ? input.currencyText.trim().toUpperCase().split(/[\s,，]+/) : null;
    return copyLedgerReportQuery({ ledgerId: ledgers.selectedLedgerId, startDate: input.startDate, endDate: input.endDate, timezone: input.timezone, types: input.types,
        accounts: input.accountIds.length ? { mode: input.accountMode, ids: input.accountIds, includeDescendants: input.accountDescendants } : null,
        categories: input.categoryIds.length ? { mode: input.categoryMode, ids: input.categoryIds, includeDescendants: input.categoryDescendants } : null,
        tags: clauses.length ? clauses.map(clause => ({ mode: clause.mode, ids: clause.ids })) : null, currencies, hidden: input.hidden });
}
const formDirty = computed(() => {
    if (!mainQuery.value) return false;
    try { return ledgerReportQueryKey(buildQuery()) !== ledgerReportQueryKey(mainQuery.value); } catch { return true; }
});
const report = computed(() => {
    // Explicitly depend on reactive login state as well as the store's captured identity guard.
    if (identitySuspended.value || !user.currentUserBasicInfo?.username || formDirty.value || !activeQuery.value) return null;
    return reports.reportFor(activeQuery.value);
});
const loadedRows = computed(() => report.value && activeQuery.value ? reports.detailsFor(activeQuery.value) : []);
const displayedRows = loadedRows;
const detailsComplete = computed(() => !!report.value && !!activeQuery.value && reports.detailsCompleteFor(activeQuery.value));
const canCompare = computed(() => { try { return !!report.value && !!activeQuery.value && !!previousLedgerReportMonthQuery(activeQuery.value); } catch { return false; } });
const metricLabels = { income: '收入', expense: '净支出', net: '收支净额', adjustment: '余额调整', transferIn: '转入', transferOut: '转出' };
const metricLabel = computed(() => metricLabels[chartMetric.value]);
const chartSections = computed(() => {
    const page = report.value;
    if (!page) return [];
    return ([{ kind: 'day', title: '每日趋势', testId: 'report-trend', groups: page.trend }, { kind: 'category', title: '分类排行', testId: 'report-categories', groups: page.categories }, { kind: 'tag', title: '标签分组', testId: 'report-tags', groups: page.tagGroups }] as const).map(section => {
        const rows = section.groups.flatMap(group => {
            const money = group.money.find(item => item.currency === chartCurrency.value);
            let label = section.kind === 'day' ? group.key : group.name || (group.key === 'none' ? '无标签' : '无分类');
            if (section.kind === 'category') {
                const category = page.categoryMetadata.find(item => item.id === group.key);
                const parent = category ? page.categoryMetadata.find(item => item.id === category.parentId) : undefined;
                label = `${parent?.name ? `${parent.name} / ` : ''}${label} · #${group.key}`;
            }
            return money ? [{ key: group.key, label, count: group.count, amount: money[chartMetric.value] }] : [];
        });
        if (section.kind !== 'day') rows.sort((a, b) => BigInt(a.amount) > BigInt(b.amount) ? -1 : BigInt(a.amount) < BigInt(b.amount) ? 1 : a.key.localeCompare(b.key));
        let maximum = 0n;
        for (const row of rows) { const amount = BigInt(row.amount); if ((amount < 0n ? -amount : amount) > maximum) maximum = amount < 0n ? -amount : amount; }
        // MinInt64's magnitude does not fit signed int64; retain its signed representation for the width helper.
        return { ...section, rows, maximum: maximum > 9223372036854775807n ? (-maximum).toString() : maximum.toString() };
    });
});
const comparisonRows = computed(() => {
    if (!report.value || !comparison.value) return [];
    const codes = [...new Set([...report.value.totals, ...comparison.value.totals].map(row => row.currency))].sort();
    return codes.map(currency => {
        // Both responses are complete; an absent currency therefore has no matching financial legs.
        const previous = comparison.value!.totals.find(row => row.currency === currency)?.net ?? '0';
        const current = report.value!.totals.find(row => row.currency === currency)?.net ?? '0';
        return { currency, previous, current, percentage: ledgerReportPercentageChange(current, previous) };
    });
});
const previewExpired = computed(() => !shownPreview.value || BigInt(shownPreview.value.expiresAt) <= BigInt(Math.floor(clock.value / 1000)) || !activeQuery.value || reports.previewFor(activeQuery.value)?.previewToken !== shownPreview.value.previewToken);
const previewScope = computed(() => {
    const query = shownPreview.value?.query, page = report.value;
    if (!query || !page) return [];
    const selected = (filter: typeof query.accounts, resources: readonly LedgerReportResource[]): string => !filter ? '全部' : `${filter.mode === 'include' ? '包含' : '排除'} ${filter.ids.map(id => id === '0' ? '无分类' : `${resources.find(item => item.id === id)?.name ?? '原选项'} #${id}`).join('、')}${filter.includeDescendants ? '（含子级）' : ''}`;
    const tagModes = { any: '包含任一', all: '包含全部', notAny: '不含任一', notAll: '未同时包含全部', none: '没有标签' };
    return [{ label: '类型', value: query.types.map(type => typeLabel(type)).join('、') }, { label: '账户', value: selected(query.accounts, page.accounts) }, { label: '分类', value: selected(query.categories, page.categoryMetadata) },
        { label: '标签', value: query.tags?.map(clause => `${tagModes[clause.mode]}${clause.ids?.length ? `：${clause.ids.map(id => `${page.readableTags.find(tag => tag.id === id)?.name ?? '原标签'} #${id}`).join('、')}` : ''}`).join('；且 ') ?? '全部' }, { label: '币种', value: query.currencies?.join('、') ?? '全部原币种，独立合计' }];
});
function money(minor: string, currency: string): string { return showAmounts.value ? `${currency} ${formatLedgerReportAmount(minor)}` : '金额已隐藏'; }
function roleLabel(role: number): string { return ['所有者', '管理员', '普通成员', '只读成员'][role - 1] ?? '账本成员'; }
function typeLabel(type: number, amount?: string): string { return type === 3 && amount?.startsWith('-') ? '退款（冲减支出）' : typeOptions.find(item => item.value === type)?.label ?? '类型待核对'; }
function errorMessage(error: unknown): string {
    const status = (error as { response?: { status?: number }; status?: number } | null)?.response?.status ?? (error as { status?: number } | null)?.status;
    if (status === 404 || status === 405) return '当前服务暂不支持账本报告，请升级服务端后重试。';
    if (status === 401) return '登录已失效，请重新登录后读取报告。';
    if (status === 403) return '当前账本或导出权限已失效，请核对成员权限后重试。';
    if (status === 409) return '报告内容或权限已变化，请重新读取并核对。';
    if (typeof navigator !== 'undefined' && !navigator.onLine) return '当前离线，无法核对完整在线报告。请联网后重试。';
    return error instanceof Error && /^[\u3400-\u9fff]/.test(error.message) ? error.message : '无法读取完整报告，请检查服务连接后重试。';
}
function clearTags(): void { form.value.tagClauses = [{ key: ++clauseKey, mode: 'any', ids: [] }]; }
function addTagClause(): void { form.value.tagClauses.push({ key: ++clauseKey, mode: 'any', ids: [] }); }
function changeTagMode(index: number): void { if (form.value.tagClauses[index]?.mode === 'none') form.value.tagClauses = [{ key: ++clauseKey, mode: 'none', ids: [] }]; }
function changeMonth(): void { try { Object.assign(form.value, ledgerReportMonthRange(form.value.month)); panelError.value = ''; } catch (error) { panelError.value = errorMessage(error); } }
function toggleAmounts(): void { settings.setShowAmountInHomePage(!showAmounts.value); }
async function reloadOptions(): Promise<void> {
    const context = capture(), request = ++optionsGeneration;
    optionsLoading.value = true; optionsError.value = ''; accountOptions.value = []; categoryOptions.value = [];
    try {
        const [accounts, categories] = await Promise.all([services.getAllAccounts({ visibleOnly: false, ledgerId: context.ledgerId }), services.getAllTransactionCategories(context.ledgerId)]);
        if (!current(context) || request !== optionsGeneration) return;
        if (!accounts.data?.success || !categories.data?.success || accounts.data.result == null || categories.data.result == null) throw new Error('筛选选项响应不完整，请重试');
        const options = ledgerReportPanelOptions(accounts.data.result, categories.data.result, context.ledgerId);
        accountOptions.value = options.accounts; categoryOptions.value = options.categories;
    } catch (error) { if (current(context) && request === optionsGeneration) optionsError.value = `账户或分类选项未完整读取。${errorMessage(error)}`; }
    finally { if (current(context) && request === optionsGeneration) optionsLoading.value = false; }
}
async function read(query: LedgerReportQuery): Promise<void> {
    const context = capture(), request = ++readGeneration;
    comparisonGeneration++; comparisonLoading.value = false;
    activeQuery.value = copyLedgerReportQuery(query); panelError.value = ''; downloadStatus.value = ''; comparison.value = null; comparisonError.value = ''; selectedDetail.value = null; closeExport();
    try {
        const page = await reports.load(query, pageSize.value);
        if (!current(context) || request !== readGeneration || !activeQuery.value || ledgerReportQueryKey(activeQuery.value) !== ledgerReportQueryKey(query)) return;
        readableTags.value = page.readableTags;
        if (!page.totals.some(row => row.currency === chartCurrency.value)) chartCurrency.value = page.totals[0]?.currency ?? '';
    } catch (error) { if (current(context) && request === readGeneration) { panelError.value = errorMessage(error); readableTags.value = []; } }
}
async function applyFilters(): Promise<void> {
    try { const query = buildQuery(); mainQuery.value = query; drillStack.value = []; drillLabels.value = []; await read(query); }
    catch (error) { panelError.value = errorMessage(error); }
}
async function refresh(): Promise<void> { await applyFilters(); }
defineExpose({ refresh });
async function retryReport(): Promise<void> { if (activeQuery.value && !formDirty.value) await read(activeQuery.value); else await applyFilters(); }
async function resetFilters(): Promise<void> { form.value = initialForm(); await applyFilters(); }
async function loadMore(): Promise<void> {
    if (!activeQuery.value) return;
    const context = capture(), request = readGeneration; closeExport();
    try { await reports.loadNext(activeQuery.value, pageSize.value); }
    catch (error) { if (current(context) && request === readGeneration) panelError.value = errorMessage(error); }
}
async function changePageSize(): Promise<void> { if (activeQuery.value) await read(activeQuery.value); }
async function narrow(query: LedgerReportQuery, label: string): Promise<void> {
    if (!activeQuery.value) return;
    drillStack.value = [...drillStack.value, { query: activeQuery.value, label }]; drillLabels.value = [...drillLabels.value, label];
    await read(query); await nextTick(); detailsTitle.value?.focus();
}
function canDrillMoney(kind: LedgerReportDrill, currency?: string): boolean { try { return !!activeQuery.value && !!ledgerReportDrillQuery(activeQuery.value, kind, undefined, currency); } catch { return false; } }
async function drillMoney(kind: LedgerReportDrill, currency?: string): Promise<void> {
    if (!activeQuery.value) return;
    try { await narrow(ledgerReportDrillQuery(activeQuery.value, kind, undefined, currency), `${currency ? `${currency} · ` : ''}${kind === 'income' ? '收入' : kind === 'expense' ? '支出' : kind === 'net' ? '收支' : kind === 'transfer' ? '转账' : '余额调整'}`); }
    catch (error) { panelError.value = errorMessage(error); }
}
async function drillGroup(kind: 'day' | 'category' | 'tag', key: string, label: string): Promise<void> {
    if (!activeQuery.value) return;
    try { await narrow(ledgerReportDrillQuery(activeQuery.value, kind, key), label); }
    catch (error) { panelError.value = errorMessage(error); }
}
async function returnToReport(): Promise<void> {
    const previous = drillStack.value[drillStack.value.length - 1];
    if (!previous) return;
    drillStack.value = drillStack.value.slice(0, -1); drillLabels.value = drillLabels.value.slice(0, -1); await read(previous.query);
}
async function comparePrevious(): Promise<void> {
    if (!activeQuery.value || !report.value) return;
    const context = capture(), request = ++comparisonGeneration, baseline = report.value.snapshotHash, query = previousLedgerReportMonthQuery(activeQuery.value);
    comparisonLoading.value = true; comparisonError.value = ''; comparison.value = null;
    try {
        const response = await services.queryLedgerReport({ query, offset: 0, count: 1 }, context.username);
        if (!current(context) || request !== comparisonGeneration || report.value?.snapshotHash !== baseline) return;
        if (!response.data?.success || response.data.result == null) throw new Error('上月报告响应不完整，请重试');
        const page = validateLedgerReportPage(response.data.result, query, 0, 1);
        if (page.memberRole !== report.value.memberRole) throw new Error('账本权限已变化，请重新读取报告');
        comparison.value = page;
    } catch (error) {
        if (current(context) && request === comparisonGeneration) {
            comparisonError.value = errorMessage(error);
            const status = (error as { response?: { status?: number } } | null)?.response?.status;
            if (status === 401 || status === 403) {
                reports.reset(); readableTags.value = []; accountOptions.value = []; categoryOptions.value = []; selectedDetail.value = null;
                panelError.value = comparisonError.value; optionsError.value = '当前登录或账本读取权限已变化，请重新核对筛选选项。'; closeExport();
            }
        }
    }
    finally { if (current(context) && request === comparisonGeneration) comparisonLoading.value = false; }
}
async function prepareExport(): Promise<void> {
    if (!activeQuery.value || !showAmounts.value) return;
    const context = capture(); exportError.value = ''; downloadStatus.value = '';
    try {
        const preview = await reports.prepareExport(activeQuery.value);
        if (!current(context) || !report.value || !showAmounts.value) return;
        shownPreview.value = preview; exportConfirmed.value = false; hiddenConfirmed.value = false;
        lastFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        await nextTick(); exportDialog.value?.showModal(); exportTitle.value?.focus();
    } catch (error) { if (current(context)) panelError.value = errorMessage(error); }
}
function closeExport(): void { exportDialog.value?.close(); shownPreview.value = null; exportConfirmed.value = false; hiddenConfirmed.value = false; exportError.value = ''; lastFocus?.focus(); lastFocus = null; }
async function downloadCSV(): Promise<void> {
    if (!activeQuery.value || !shownPreview.value || !exportConfirmed.value || !showAmounts.value) return;
    const context = capture(), preview = shownPreview.value;
    if (preview.includesHiddenAmounts && !hiddenConfirmed.value) return;
    exportError.value = '';
    try {
        const file = await reports.download(activeQuery.value, { confirmed: true, snapshotHash: preview.snapshotHash, previewToken: preview.previewToken, includeHiddenAmounts: hiddenConfirmed.value });
        if (!current(context) || !report.value || !showAmounts.value || shownPreview.value?.previewToken !== preview.previewToken) return;
        const url = URL.createObjectURL(file.blob); objectURLs.add(url);
        // Framework7 must leave Blob downloads to the browser instead of routing them as pages.
        try { const anchor = document.createElement('a'); anchor.classList.add('external'); anchor.href = url; anchor.download = file.filename; document.body.appendChild(anchor); try { anchor.click(); } finally { anchor.remove(); } }
        catch (error) { URL.revokeObjectURL(url); objectURLs.delete(url); throw error; }
        window.setTimeout(() => { URL.revokeObjectURL(url); objectURLs.delete(url); }, 1000);
        closeExport(); downloadStatus.value = '下载已交给浏览器，请在下载列表确认文件。';
    } catch (error) { if (current(context)) exportError.value = errorMessage(error); }
}
function clearScope(): void {
    generation++; optionsGeneration++; readGeneration++; comparisonGeneration++; reports.reset(); closeExport(); mainQuery.value = null; activeQuery.value = null;
    form.value = initialForm(); accountOptions.value = []; categoryOptions.value = []; readableTags.value = []; panelError.value = ''; optionsError.value = ''; optionsLoading.value = false; downloadStatus.value = ''; comparison.value = null; comparisonLoading.value = false; comparisonError.value = ''; selectedDetail.value = null; drillStack.value = []; drillLabels.value = [];
}
function switchScope(): void {
    if (!sessionCurrent()) { invalidateSession(); return; }
    clearScope();
    void reloadOptions(); void applyFilters();
}
watch(() => [ledgers.selectedLedgerId, user.currentUserBasicInfo?.username], switchScope, { immediate: true, flush: 'sync' });
watch(form, () => { closeExport(); comparison.value = null; comparisonError.value = ''; selectedDetail.value = null; downloadStatus.value = ''; }, { deep: true, flush: 'sync' });
watch(showAmounts, value => { if (!value) closeExport(); });
window.addEventListener('storage', changedElsewhere);
window.addEventListener('focus', checkSession);
document.addEventListener('visibilitychange', visibilityChanged);
onBeforeUnmount(() => { alive = false; generation++; optionsGeneration++; reports.reset(); closeExport(); window.removeEventListener('storage', changedElsewhere); window.removeEventListener('focus', checkSession); document.removeEventListener('visibilitychange', visibilityChanged); window.clearInterval(previewClock); objectURLs.forEach(url => URL.revokeObjectURL(url)); objectURLs.clear(); });
</script>

<style scoped>
.ledger-report-panel { --report-surface: var(--surface, var(--f7-block-strong-bg-color, Canvas)); --report-ink: var(--ink, var(--f7-text-color, CanvasText)); --report-muted: color-mix(in srgb, var(--muted, var(--f7-block-footer-text-color, CanvasText)) 74%, var(--report-ink)); --report-line: var(--line, var(--f7-list-item-border-color, GrayText)); --report-raised: var(--panel, var(--f7-list-bg-color, Canvas)); --report-accent: var(--orange, #f05537); --report-error: var(--red, var(--f7-color-red, MarkText)); display: grid; gap: 24px; min-width: 0; color: var(--report-ink); font-family: var(--ledger-font-body, 'Microsoft YaHei UI', sans-serif); font-size: max(14px, 1em); line-height: 1.6; font-synthesis: none; }
:global(.workspace) .ledger-report-panel { --report-surface: var(--finexy-surface, #fff); }
.ledger-report-panel :is(h2, h3, h4, p, dl, dd, fieldset, ul) { margin: 0; }
.ledger-report-panel :is(h2, h3, h4) { color: inherit; font: inherit; font-weight: 650; }
.ledger-report-panel h2 { font-size: 1.85em; line-height: 1.3; letter-spacing: -.03em; }
.ledger-report-panel h3 { font-size: 1.2em; }
.ledger-report-panel :is(button, input, select, summary) { color: inherit; font: inherit; -webkit-text-fill-color: currentColor; }
.ledger-report-panel :is(button, input:not([type=checkbox]), select) { box-sizing: border-box; min-height: 48px; min-width: 0; padding: 10px 12px; border: 1px solid var(--report-line); border-radius: 11px; background: var(--report-surface); }
.ledger-report-panel button { max-width: 100%; cursor: pointer; white-space: normal; overflow-wrap: anywhere; }
.ledger-report-panel :is(button, input, select, summary):focus-visible { outline: 2px solid var(--report-accent); outline-offset: 3px; }
.ledger-report-panel :disabled { opacity: .55; cursor: default; }
.report-header, .report-section-header, .report-filter-actions, .report-export-actions { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.report-header > div { min-width: 0; flex: 1 1 260px; }
.report-eyebrow { color: var(--report-muted); font-weight: 600; margin-bottom: 8px !important; }
.report-copy, .report-source, .ledger-report-panel small, .report-currency-total header > span { color: var(--report-muted); font-size: max(13px, .93em); }
.report-header .report-copy { margin-top: 10px; max-width: 60ch; }
.report-filter-form { display: grid; gap: 16px; padding: 20px; background: var(--report-raised); border: 1px solid var(--report-line); border-radius: 18px; }
.report-filter-disclosure > summary { cursor: pointer; min-height: 48px; align-content: center; font-weight: 600; }
.report-filter-disclosure > summary > span { margin-left: 12px; font-weight: 400; }
.report-filters { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px 24px; padding-top: 12px; }
.ledger-report-panel fieldset { min-width: 0; border: 0; padding: 0; display: grid; align-content: start; gap: 10px; }
.ledger-report-panel legend { padding: 0; margin-bottom: 10px; font-weight: 650; }
.ledger-report-panel label:not(.report-check) { display: grid; gap: 6px; min-width: 0; }
.ledger-report-panel input:not([type=checkbox]), .ledger-report-panel select { width: 100%; }
.report-date-fields { grid-column: 1 / -1; grid-template-columns: repeat(4, minmax(0, 1fr)); }
.report-date-fields legend { float: none; }
.report-date-fields > label { min-width: 0; }
.report-check { display: flex; gap: 10px; min-height: 48px; align-items: center; cursor: pointer; padding-block: 6px; overflow-wrap: anywhere; }
.report-check input { flex: 0 0 18px; width: 18px; height: 18px; margin: 0; accent-color: var(--report-accent); }
.report-check-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.report-option-picker > summary { cursor: pointer; min-height: 48px; align-content: center; border-bottom: 1px solid var(--report-line); }
.report-option-list { max-height: 240px; overflow-y: auto; overscroll-behavior: contain; padding-right: 4px; }
.report-option-list .report-check { border-bottom: 1px solid var(--report-line); }
.report-option-list .report-check > span { min-width: 0; }
.report-tag-fields { grid-column: 1 / -1; }
.report-tag-clause { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(200px, 2fr) auto; gap: 12px; align-items: start; padding-block: 8px; border-bottom: 1px solid var(--report-line); }
.report-actions, .report-chart-controls, .report-extra-fields { display: flex; gap: 12px; align-items: end; flex-wrap: wrap; }
.report-extra-fields { margin-block: 16px; }
.report-extra-fields > label { flex: 1 1 240px; }
.report-filter-actions { align-items: center; }
.report-filter-actions > p { flex: 1 1 220px; }
.report-primary { background: var(--report-ink) !important; color: var(--report-surface) !important; border-color: var(--report-ink) !important; font-weight: 600 !important; }
.report-quiet { border-color: transparent !important; background: transparent !important; }
.report-notice, .report-loading { border: 1px solid var(--report-line); border-radius: 12px; padding: 14px 16px; overflow-wrap: anywhere; }
.report-error { color: var(--report-error); }
.report-notice { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.report-notice > p { flex: 1 1 220px; }
.report-scope-strip { display: flex; gap: 16px; justify-content: space-between; align-items: center; border-left: 3px solid var(--report-accent); padding-left: 14px; flex-wrap: wrap; }
.report-scope-strip > div { display: grid; }
.report-scope-strip span { font-size: 13px; color: var(--report-muted); }
.report-source { border: 1px solid var(--report-line); padding: 6px 10px; border-radius: 999px; }
.report-drill-path { display: flex; gap: 12px; align-items: center; justify-content: space-between; flex-wrap: wrap; }
.report-empty { padding: 24px; text-align: center; display: grid; gap: 12px; justify-items: center; background: var(--report-raised); border-radius: 16px; }
.report-totals { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr)); gap: 16px; }
.report-currency-total { padding: 20px; border: 1px solid var(--report-line); border-radius: 16px; background: var(--report-surface); min-width: 0; }
.report-currency-total header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 14px; }
.report-currency-total h3 { font-family: var(--ledger-font-data, ui-monospace, monospace); font-size: 21px; }
.report-currency-total dl { display: grid; gap: 4px; }
.report-currency-total dl > div { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.5fr); align-items: center; gap: 10px; }
.report-currency-total dt { color: var(--report-muted); }
.report-currency-total dd { text-align: end; min-width: 0; }
.report-currency-total dd button { border-color: transparent; padding-inline: 0; font-family: var(--ledger-font-data, ui-monospace, monospace); font-size: 17px; font-variant-numeric: tabular-nums; }
.report-currency-total dd button:disabled { opacity: 1; }
.report-net { border-top: 1px solid var(--report-line); margin-top: 8px; padding-top: 8px; }
.report-transfer-totals { margin-top: 12px; border-top: 1px solid var(--report-line); }
.report-transfer-totals summary { min-height: 48px; align-content: center; cursor: pointer; }
.report-transfer-totals dl { font-variant-numeric: tabular-nums; margin-bottom: 12px; }
.report-transfer-totals button { margin-right: 8px; margin-bottom: 8px; }
.report-comparison, .report-details-section { display: grid; gap: 14px; }
.report-chart-controls > label { min-width: min(100%, 180px); flex: 0 1 220px; }
.report-chart-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; }
.report-chart { display: grid; align-content: start; gap: 12px; min-width: 0; border: 1px solid var(--report-line); border-radius: 16px; padding: 18px; }
.report-chart header { display: grid; gap: 6px; }
.report-bars { padding: 0; list-style: none; display: grid; gap: 8px; max-height: 500px; overflow-y: auto; }
.ledger-report-panel .report-group-button { width: 100%; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 6px 12px; padding: 10px 0; border: 0; border-bottom: 1px solid var(--report-line); border-radius: 0; text-align: start; background: transparent; }
.report-group-label { min-width: 0; overflow-wrap: anywhere; }
.report-group-label small { display: block; }
.report-group-money { font-family: var(--ledger-font-data, ui-monospace, monospace); font-variant-numeric: tabular-nums; text-align: end; overflow-wrap: anywhere; max-width: 18ch; }
.report-bar-track { grid-column: 1 / -1; height: 6px; background: var(--report-raised); border-radius: 4px; }
.report-bar-track > span { display: block; height: 100%; background: var(--report-accent); border-radius: inherit; }
.report-bar-track > .report-bar-negative { background: var(--report-ink); }
.report-table-wrap { min-width: 0; overflow-x: auto; border: 1px solid var(--report-line); border-radius: 14px; }
.report-table-wrap table { border-collapse: collapse; width: 100%; min-width: 620px; text-align: start; }
.report-table-wrap caption { text-align: start; padding: 12px; color: var(--report-muted); font-size: 13px; }
.report-table-wrap th, .report-table-wrap td { padding: 12px; border-top: 1px solid var(--report-line); vertical-align: top; text-align: start; }
.report-table-wrap thead { background: var(--report-raised); }
.report-table-wrap th { font-weight: 600; }
.report-table-wrap small { display: block; }
.report-row-comment { max-width: 28ch; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.report-row-amount { font-family: var(--ledger-font-data, ui-monospace, monospace); font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.report-load-more { justify-self: center; min-width: 180px !important; }
.report-record-detail { border: 1px solid var(--report-line); border-radius: 16px; padding: 20px; display: grid; gap: 16px; }
.report-record-detail dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.report-record-detail dt { color: var(--report-muted); font-size: 13px; }
.report-record-detail dd { overflow-wrap: anywhere; user-select: text; }
.report-full-comment { white-space: pre-wrap; }
.report-export-actions { padding-top: 16px; border-top: 1px solid var(--report-line); align-items: center; }
.report-export-actions > div { flex: 1 1 280px; }
.report-export-dialog { box-sizing: border-box; width: min(600px, calc(100vw - 32px)); max-height: calc(100dvh - 32px); margin: auto; padding: 24px; border: 1px solid var(--report-line); border-radius: 20px; background: var(--report-surface); color: var(--report-ink); overflow-y: auto; overscroll-behavior: contain; }
.report-export-dialog::backdrop { background: color-mix(in srgb, CanvasText 50%, transparent); }
.report-export-dialog form { display: grid; gap: 16px; }
.report-preview-scope { display: grid; gap: 8px; }
.report-preview-scope > div { display: grid; grid-template-columns: 48px minmax(0, 1fr); gap: 12px; }
.report-preview-scope dt { color: var(--report-muted); }
.report-preview-scope dd { overflow-wrap: anywhere; }
.report-export-totals { display: grid; gap: 12px; }
.report-export-totals > div { display: grid; grid-template-columns: 60px minmax(0, 1fr); gap: 12px; padding-block: 10px; border-block-end: 1px solid var(--report-line); }
.report-export-totals dd { font-family: var(--ledger-font-data, ui-monospace, monospace); overflow-wrap: anywhere; }
.report-mobile { padding: 16px; }
@media (max-width: 1000px) { .report-filters, .report-chart-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .report-date-fields { grid-template-columns: repeat(2, minmax(0, 1fr)); } .report-chart:last-child { grid-column: 1 / -1; } }
@media (max-width: 650px) { .ledger-report-panel { gap: 20px; } .ledger-report-panel h2 { font-size: 23px; } .report-filter-form { padding: 14px; } .report-filters, .report-chart-grid, .report-date-fields, .report-tag-clause, .report-record-detail dl { grid-template-columns: minmax(0, 1fr); } .report-header > button { width: 100%; } .report-filter-disclosure > summary > span { display: block; margin-left: 0; } .report-filter-actions > button, .report-export-actions > button { width: 100%; } .report-chart { padding: 14px; } .report-group-button { grid-template-columns: minmax(0, 1fr) !important; } .report-group-money { text-align: start; max-width: none; } .report-currency-total dl > div { grid-template-columns: minmax(0, 1fr); gap: 0; padding-block: 6px; } .report-currency-total dd { text-align: start; } .report-actions > button { flex: 1 1 150px; } }
@media (prefers-reduced-motion: reduce) { .ledger-report-panel *, .ledger-report-panel *::before, .ledger-report-panel *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; } }
</style>
