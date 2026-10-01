<template>
    <f7-popup :opened="opened" @popup:closed="close">
        <f7-page>
            <f7-navbar title="导入支付账单">
                <f7-nav-right><f7-link popup-close>关闭</f7-link></f7-nav-right>
            </f7-navbar>
            <f7-block strong inset>
                <p>账单将导入「{{ importLedgerName }}」。先核对账户和分类，再确认入账；重复导入同一账单会产生重复流水。</p>
                <div class="display-flex gap-8 margin-bottom">
                    <f7-button :fill="provider === 'alipay'" outline @click="selectProvider('alipay')">支付宝</f7-button>
                    <f7-button :fill="provider === 'wechat'" outline @click="selectProvider('wechat')">微信支付</f7-button>
                </div>
                <label class="display-block margin-bottom">
                    <span>选择{{ provider === 'alipay' ? 'ZIP / CSV' : 'XLSX / CSV' }} 文件</span>
                    <input :key="provider" type="file" :accept="provider === 'alipay' ? '.zip,.csv' : '.xlsx,.csv'" :disabled="busy" @change="selectFile" />
                </label>
                <label class="display-block margin-bottom" v-if="file?.name.toLowerCase().endsWith('.zip')">
                    <span>ZIP 解压密码（仅在浏览器内使用）</span>
                    <input type="password" autocomplete="off" v-model="password" />
                </label>
                <f7-button fill :disabled="!file || busy" @click="parse">{{ busy ? '处理中…' : '解析并预览' }}</f7-button>
                <p role="status" aria-live="polite" v-if="message">{{ message }}</p>
            </f7-block>

            <template v-if="rows.length">
                <f7-block strong inset>
                    <p>共 {{ rows.length }} 笔，已选 {{ chosen.length }} 笔，待补全 {{ invalidCount }} 笔。</p>
                    <p>未匹配的账户和末级分类须手动选择；可逐笔取消勾选。</p>
                    <StatementMappingPanel :rows="rows" :accounts="accountsStore.allAccountsMap" :categories="categoriesStore.allTransactionCategoriesMap" :disabled="busy" />
                    <f7-button fill :disabled="busy || !chosen.length || invalidCount > 0" @click="submit">确认导入 {{ chosen.length }} 笔</f7-button>
                </f7-block>

                <f7-block-title>逐笔核对</f7-block-title>
                <f7-block strong inset :key="row.index" v-for="row in visibleRows">
                    <label class="display-flex align-items-center gap-8">
                        <input type="checkbox" v-model="row.selected" />
                        <strong>{{ typeLabel(row.type) }} · {{ (row.sourceAmount / 100).toFixed(2) }} · {{ new Date(row.time * 1000).toLocaleString() }}</strong>
                    </label>
                    <p>{{ row.comment || '无备注' }}</p>
                    <label class="display-block margin-bottom">账户：{{ row.originalSourceAccountName || '未识别' }}
                        <select v-model="row.sourceAccountId" aria-label="付款账户">
                            <option value="0">请选择</option>
                            <option v-for="account in availableAccounts" :key="account.id" :value="account.id">{{ account.name }}</option>
                        </select>
                    </label>
                    <label class="display-block margin-bottom" v-if="row.type === TransactionType.Transfer">转入账户：{{ row.originalDestinationAccountName || '未识别' }}
                        <select v-model="row.destinationAccountId" aria-label="收款账户">
                            <option value="0">请选择</option>
                            <option v-for="account in availableAccounts" :key="account.id" :value="account.id">{{ account.name }}</option>
                        </select>
                    </label>
                    <label class="display-block">分类：{{ row.originalCategoryName || '未识别' }}
                        <select v-model="row.categoryId" aria-label="末级分类">
                            <option value="0">请选择</option>
                            <option v-for="category in categoriesFor(row.type)" :key="category.id" :value="category.id">{{ category.name }}</option>
                        </select>
                    </label>
                    <p class="text-color-red" v-if="row.selected && !ready(row)">请补全或修正账户、分类</p>
                </f7-block>
                <f7-block v-if="visibleCount < rows.length">
                    <f7-button outline @click="visibleCount += 25">继续查看</f7-button>
                </f7-block>
            </template>
        </f7-page>
    </f7-popup>
</template>

<script setup lang="ts">
import StatementMappingPanel from '@/components/StatementMappingPanel.vue';
import { useLedgersStore } from '@/stores/ledger.ts';
import { ref, computed, watch } from 'vue';
import { useAccountsStore } from '@/stores/account.ts';
import { useTransactionCategoriesStore } from '@/stores/transactionCategory.ts';
import { useTransactionsStore } from '@/stores/transaction.ts';
import { useOverviewStore } from '@/stores/overview.ts';
import { useStatisticsStore } from '@/stores/statistics.ts';
import { useI18nUIComponents } from '@/lib/ui/mobile.ts';
import { TransactionType } from '@/core/transaction.ts';
import { ImportTransaction } from '@/models/imported_transaction.ts';
import { extractAlipayCsv } from '@/lib/alipay_archive.ts';
import { generateRandomUUID } from '@/lib/misc.ts';

const opened = defineModel<boolean>('opened', { required: true });
const emit = defineEmits<{ imported: [] }>();
const ledgersStore = useLedgersStore();
const importLedgerId = ref('0');
const importLedgerName = ref('默认个人账本');
watch(opened, value => {
    if (value) { importLedgerId.value = ledgersStore.selectedLedgerId; importLedgerName.value = ledgersStore.selectedLedger.name; rows.value = []; }
});
const accountsStore = useAccountsStore();
const categoriesStore = useTransactionCategoriesStore();
const transactionsStore = useTransactionsStore();
const overviewStore = useOverviewStore();
const statisticsStore = useStatisticsStore();
const { showConfirm } = useI18nUIComponents();
const provider = ref<'alipay' | 'wechat'>('alipay');
const file = ref<File | null>(null);
const password = ref('');
const busy = ref(false);
const message = ref('');
const rows = ref<ImportTransaction[]>([]);
const visibleCount = ref(25);
const sessionId = ref(generateRandomUUID());

const availableAccounts = computed(() => accountsStore.allVisiblePlainAccounts);
const availableCategories = computed(() => Object.values(categoriesStore.allTransactionCategoriesMap).filter(category =>
    category.visible && category.parentId !== '0' && categoriesStore.allTransactionCategoriesMap[category.parentId]?.visible));
const accountIds = computed(() => new Set(availableAccounts.value.map(account => account.id)));
const chosen = computed(() => rows.value.filter(row => row.selected));
const invalidCount = computed(() => chosen.value.filter(row => !ready(row)).length);
const visibleRows = computed(() => rows.value.slice(0, visibleCount.value));
function typeLabel(type: number): string {
    return type === TransactionType.Income ? '收入' : type === TransactionType.Expense ? '支出' : '转账';
}

function categoriesFor(type: number) {
    return availableCategories.value.filter(category => category.type === (type === TransactionType.Income ? 1 : type === TransactionType.Expense ? 2 : 3));
}

function ready(row: ImportTransaction): boolean {
    return row.type >= TransactionType.Income && row.type <= TransactionType.Transfer && row.time > 0 &&
        accountIds.value.has(row.sourceAccountId) && categoriesFor(row.type).some(category => category.id === row.categoryId) &&
        (row.type !== TransactionType.Transfer || (accountIds.value.has(row.destinationAccountId) && row.destinationAccountId !== row.sourceAccountId)) &&
        row.comment.length <= 255;
}

function selectProvider(value: 'alipay' | 'wechat'): void {
    if (busy.value) return;
    provider.value = value; file.value = null; password.value = ''; rows.value = []; message.value = '';
}

function close(): void {
    opened.value = false;
    password.value = '';
}

function selectFile(event: Event): void {
    file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
    rows.value = []; message.value = ''; sessionId.value = generateRandomUUID();
}

async function parse(): Promise<void> {
    if (!file.value || busy.value) return;
    busy.value = true; message.value = '';
    try {
        const [accounts] = await Promise.all([accountsStore.loadAllAccounts({ force: true, ledgerId: importLedgerId.value }).catch(error => {
            if (!error?.isUpToDate) throw error;
            return accountsStore.allAccounts;
        }), categoriesStore.loadAllCategories({ force: true, ledgerId: importLedgerId.value }).catch(error => {
            if (!error?.isUpToDate) throw error;
        })]);
        if (!accounts.length) throw new Error('请先创建当前账本账户');
        const selectedFile = provider.value === 'alipay' && file.value.name.toLowerCase().endsWith('.zip')
            ? await extractAlipayCsv(file.value, password.value) : file.value;
        const type = provider.value === 'alipay' ? 'alipay_app_csv' : selectedFile.name.toLowerCase().endsWith('.xlsx') ? 'wechat_pay_app_xlsx' : 'wechat_pay_app_csv';
        if (provider.value === 'alipay' && !selectedFile.name.toLowerCase().endsWith('.csv')) throw new Error('请选择支付宝 ZIP 或 CSV');
        if (provider.value === 'wechat' && !/\.(xlsx|csv)$/i.test(selectedFile.name)) throw new Error('请选择微信支付 XLSX 或 CSV');
        const response = await transactionsStore.parseImportTransaction({ ledgerId: importLedgerId.value, fileType: type, importFile: selectedFile });
        rows.value = response.items.map((item, index) => {
            const row = ImportTransaction.of(item, index);
            row.selected = true;
            return row;
        });
        visibleCount.value = 25;
        message.value = rows.value.length ? `已解析 ${rows.value.length} 笔，请核对后导入` : '账单中没有可导入记录';
    } catch (error) {
        message.value = `解析失败：${error instanceof Error ? error.message : '请检查文件格式与密码'}`;
    } finally { busy.value = false; }
}

function submit(): void {
    if (busy.value || !chosen.value.length || invalidCount.value) return;
    showConfirm(`确认将 ${chosen.value.length} 笔流水导入「${importLedgerName.value}」？重复导入会产生重复流水。`, () => void submitConfirmed());
}

async function submitConfirmed(): Promise<void> {
    if (ledgersStore.selectedLedgerId !== importLedgerId.value) { message.value = '账本已切换，请关闭后重新预览账单'; return; }
    if (busy.value || !chosen.value.length || invalidCount.value) return;
    busy.value = true; message.value = '';
    try {
        const count = await transactionsStore.importTransactions({ ledgerId: importLedgerId.value, transactions: chosen.value, clientSessionId: sessionId.value });
        accountsStore.updateAccountListInvalidState(true);
        transactionsStore.updateTransactionListInvalidState(true);
        overviewStore.updateTransactionOverviewInvalidState(true);
        statisticsStore.updateTransactionStatisticsInvalidState(true);
        message.value = `已导入 ${count} 笔流水`;
        rows.value = []; file.value = null; password.value = ''; sessionId.value = generateRandomUUID();
        emit('imported');
    } catch (error) {
        message.value = `导入失败：${error instanceof Error ? error.message : '请稍后重试'}`;
    } finally { busy.value = false; }
}
</script>

<style scoped>
input[type="file"], input[type="password"], select { display: block; width: 100%; min-height: 44px; margin-top: 6px; box-sizing: border-box; }
</style>
