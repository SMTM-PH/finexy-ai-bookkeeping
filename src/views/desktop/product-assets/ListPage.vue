<template>
    <v-row class="product-assets-page">
        <v-col cols="12">
            <div class="d-flex flex-wrap align-center ga-3 mb-4">
                <div>
                    <h2 class="text-h5">已购产品</h2>
                    <div class="text-body-2 text-medium-emphasis mt-1">记录仍有转售价值的大额商品，按天查看真实持有成本</div>
                </div>
                <v-spacer/>
                <v-btn color="primary" :prepend-icon="mdiPlus" :disabled="loading" @click="openCreateDialog">
                    添加产品
                </v-btn>
                <v-btn color="default" variant="text" :icon="true" :loading="loading" aria-label="刷新产品列表" @click="reload">
                    <v-icon :icon="mdiRefresh"/>
                    <v-tooltip activator="parent">{{ tt('Refresh') }}</v-tooltip>
                </v-btn>
            </div>
        </v-col>

        <v-col cols="12" md="3" v-for="summary in summaries" :key="summary.label">
            <v-card variant="outlined" class="summary-card fill-height">
                <v-card-text>
                    <div class="text-body-2 text-medium-emphasis">{{ summary.label }}</div>
                    <div class="summary-value mt-2">{{ summary.value }}</div>
                    <div class="text-caption text-medium-emphasis mt-1">{{ summary.hint }}</div>
                </v-card-text>
            </v-card>
        </v-col>

        <v-col cols="12">
            <v-card>
                <v-card-item>
                    <template #title>产品清单</template>
                    <template #append>
                        <v-btn-toggle v-model="statusFilter" mandatory density="compact" color="primary" variant="outlined">
                            <v-btn :value="0">全部</v-btn>
                            <v-btn :value="ProductAssetStatus.Active">持有中</v-btn>
                            <v-btn :value="ProductAssetStatus.Sold">已售出</v-btn>
                        </v-btn-toggle>
                    </template>
                </v-card-item>

                <v-card-text v-if="loading && !assets.length">
                    <v-skeleton-loader type="list-item-three-line@3"/>
                </v-card-text>

                <v-card-text class="py-10 text-center" v-else-if="!filteredAssets.length">
                    <v-icon :icon="mdiDevices" size="44" color="secondary"/>
                    <div class="text-h6 mt-3">还没有符合条件的产品</div>
                    <div class="text-body-2 text-medium-emphasis mt-1">添加手机、电脑或其他可转售商品后，这里会持续计算折旧和每日成本。</div>
                    <v-btn class="mt-4" color="primary" variant="tonal" @click="openCreateDialog">添加第一个产品</v-btn>
                </v-card-text>

                <v-table v-else class="product-table" hover>
                    <thead>
                    <tr>
                        <th>产品</th>
                        <th>购买 / 当前价值</th>
                        <th class="holding-column">持有时间尺</th>
                        <th>每日成本</th>
                        <th class="text-end">操作</th>
                    </tr>
                    </thead>
                    <tbody>
                    <tr v-for="asset in filteredAssets" :key="asset.id">
                        <td>
                            <div class="d-flex align-center ga-3 py-2">
                                <v-avatar color="secondary" variant="tonal" rounded="lg">
                                    <v-icon :icon="categoryIcon(asset.category)"/>
                                </v-avatar>
                                <div>
                                    <div class="font-weight-medium">{{ asset.name }}</div>
                                    <div class="text-caption text-medium-emphasis">
                                        {{ categoryName(asset.category) }}<template v-if="asset.brand || asset.model"> · {{ [asset.brand, asset.model].filter(Boolean).join(' ') }}</template>
                                    </div>
                                    <v-chip class="mt-1" size="x-small" :color="asset.status === ProductAssetStatus.Active ? 'success' : 'default'" variant="tonal">
                                        {{ asset.status === ProductAssetStatus.Active ? '持有中' : '已售出' }}
                                    </v-chip>
                                </div>
                            </div>
                        </td>
                        <td class="amount-cell">
                            <div>{{ money(asset.purchaseAmount) }}</div>
                            <div class="text-caption text-medium-emphasis mt-1">账面 {{ money(asset.valuation.bookValue) }}</div>
                            <div class="text-caption" v-if="asset.manualMarketValue !== undefined">二手估值 {{ money(asset.manualMarketValue) }}</div>
                        </td>
                        <td class="holding-column">
                            <div class="d-flex align-center mb-2">
                                <strong class="time-number">{{ asset.valuation.heldDays }}</strong>
                                <span class="text-caption text-medium-emphasis ms-1">天 / {{ asset.usefulLifeDays }} 天</span>
                                <v-spacer/>
                                <span class="text-caption">{{ purchaseDate(asset.purchaseTime) }}</span>
                            </div>
                            <v-progress-linear :model-value="lifeProgress(asset)" color="primary" bg-color="secondary" height="7" rounded/>
                            <div class="d-flex text-caption text-medium-emphasis mt-1">
                                <span>累计折旧 {{ money(asset.valuation.accumulatedDepreciation) }}</span>
                                <v-spacer/>
                                <span>每日折旧 {{ money(Math.round(asset.valuation.dailyDepreciation)) }}</span>
                            </div>
                        </td>
                        <td class="amount-cell">
                            <strong>{{ money(Math.round(asset.valuation.averageDailyCost)) }}</strong>
                            <div class="text-caption text-medium-emphasis mt-1">{{ asset.status === ProductAssetStatus.Sold ? '已扣除卖出收入' : '购买价 ÷ 持有天数' }}</div>
                        </td>
                        <td class="text-end text-no-wrap">
                            <v-btn variant="text" color="default" :icon="true" aria-label="编辑产品" @click="openEditDialog(asset)">
                                <v-icon :icon="mdiPencilOutline"/>
                                <v-tooltip activator="parent">{{ tt('Edit') }}</v-tooltip>
                            </v-btn>
                            <v-btn v-if="asset.status === ProductAssetStatus.Active" variant="text" color="default" :icon="true" aria-label="登记售出" @click="openSellDialog(asset)">
                                <v-icon :icon="mdiCashCheck"/>
                                <v-tooltip activator="parent">登记售出</v-tooltip>
                            </v-btn>
                            <v-btn variant="text" color="error" :icon="true" aria-label="删除产品" @click="remove(asset)">
                                <v-icon :icon="mdiDeleteOutline"/>
                                <v-tooltip activator="parent">{{ tt('Delete') }}</v-tooltip>
                            </v-btn>
                        </td>
                    </tr>
                    </tbody>
                </v-table>
            </v-card>
        </v-col>
    </v-row>

    <v-dialog v-model="editDialogVisible" persistent max-width="720">
        <v-card>
            <v-toolbar color="primary">
                <v-toolbar-title>{{ editingAsset ? '编辑产品' : '添加产品' }}</v-toolbar-title>
                <v-btn :icon="mdiClose" aria-label="关闭" @click="editDialogVisible = false"/>
            </v-toolbar>
            <v-form ref="editForm" @submit.prevent="save">
                <v-card-text class="pa-5">
                    <v-row>
                        <v-col cols="12" md="8">
                            <v-text-field v-model.trim="form.name" label="产品名称 *" maxlength="128" :rules="requiredRules" autofocus/>
                        </v-col>
                        <v-col cols="12" md="4">
                            <v-select v-model="form.category" label="类别 *" :items="categoryOptions" @update:model-value="applyCategoryDefaults"/>
                        </v-col>
                        <v-col cols="12" md="6"><v-text-field v-model.trim="form.brand" label="品牌" maxlength="64"/></v-col>
                        <v-col cols="12" md="6"><v-text-field v-model.trim="form.model" label="型号" maxlength="64"/></v-col>
                        <v-col cols="12" md="6"><v-text-field v-model.number="form.purchaseYuan" label="购买价格 *" type="number" min="0.01" step="0.01" suffix="元" :rules="positiveRules"/></v-col>
                        <v-col cols="12" md="6"><v-text-field v-model="form.purchaseDate" label="购买日期 *" type="date" :rules="requiredRules"/></v-col>
                        <v-col cols="12" md="6"><v-text-field v-model.number="form.usefulLifeDays" label="预计使用寿命 *" type="number" min="1" max="36500" suffix="天" :rules="positiveRules" hint="系统按类别给出默认值，可手动修改" persistent-hint/></v-col>
                        <v-col cols="12" md="6"><v-text-field v-model.number="form.residualYuan" label="期末残值" type="number" min="0" step="0.01" suffix="元"/></v-col>
                        <v-col cols="12" md="6"><v-text-field v-model.number="form.marketYuan" label="当前二手估值" type="number" min="0" step="0.01" suffix="元" clearable hint="留空时仅显示系统账面价值" persistent-hint/></v-col>
                        <v-col cols="12"><v-textarea v-model.trim="form.comment" label="备注" maxlength="255" rows="2" counter/></v-col>
                    </v-row>
                </v-card-text>
                <v-card-actions class="px-5 pb-5">
                    <v-spacer/>
                    <v-btn color="default" :disabled="saving" @click="editDialogVisible = false">{{ tt('Cancel') }}</v-btn>
                    <v-btn color="primary" type="submit" :loading="saving">保存</v-btn>
                </v-card-actions>
            </v-form>
        </v-card>
    </v-dialog>

    <v-dialog v-model="sellDialogVisible" persistent max-width="480">
        <v-card>
            <v-toolbar color="primary"><v-toolbar-title>登记售出</v-toolbar-title></v-toolbar>
            <v-form ref="sellForm" @submit.prevent="sell">
                <v-card-text class="pa-5">
                    <div class="text-body-2 mb-4">{{ sellingAsset?.name }}</div>
                    <v-text-field v-model.number="sellFormData.amountYuan" label="卖出价格 *" type="number" min="0" step="0.01" suffix="元" :rules="nonNegativeRules"/>
                    <v-text-field v-model="sellFormData.date" label="卖出日期 *" type="date" :rules="requiredRules"/>
                </v-card-text>
                <v-card-actions class="px-5 pb-5">
                    <v-spacer/>
                    <v-btn color="default" :disabled="saving" @click="sellDialogVisible = false">{{ tt('Cancel') }}</v-btn>
                    <v-btn color="primary" type="submit" :loading="saving">确认售出</v-btn>
                </v-card-actions>
            </v-form>
        </v-card>
    </v-dialog>

    <confirm-dialog ref="confirmDialog"/>
    <snack-bar ref="snackbar"/>
</template>

<script setup lang="ts">
import { computed, reactive, ref, useTemplateRef } from 'vue';
import type { VForm } from 'vuetify/components';

import ConfirmDialog from '@/components/desktop/ConfirmDialog.vue';
import SnackBar from '@/components/desktop/SnackBar.vue';
import { useI18n } from '@/locales/helpers.ts';
import { useProductAssetsStore } from '@/stores/productAsset.ts';
import { AMOUNT_FACTOR } from '@/consts/numeral.ts';
import {
    ProductAssetCategory,
    ProductAssetStatus,
    type ProductAssetInfoResponse
} from '@/models/product_asset.ts';
import {
    mdiCameraOutline,
    mdiCashCheck,
    mdiCellphone,
    mdiClose,
    mdiControllerClassicOutline,
    mdiDeleteOutline,
    mdiDevices,
    mdiLaptop,
    mdiPencilOutline,
    mdiPlus,
    mdiRefresh,
    mdiTablet,
    mdiWashingMachine
} from '@mdi/js';

type ConfirmDialogType = InstanceType<typeof ConfirmDialog>;
type SnackBarType = InstanceType<typeof SnackBar>;

const { tt, formatAmountToLocalizedNumeralsWithCurrency } = useI18n();
const store = useProductAssetsStore();
const confirmDialog = useTemplateRef<ConfirmDialogType>('confirmDialog');
const snackbar = useTemplateRef<SnackBarType>('snackbar');
const editForm = useTemplateRef<VForm>('editForm');
const sellForm = useTemplateRef<VForm>('sellForm');

const loading = ref(true);
const saving = ref(false);
const statusFilter = ref(0);
const editDialogVisible = ref(false);
const sellDialogVisible = ref(false);
const editingAsset = ref<ProductAssetInfoResponse | null>(null);
const sellingAsset = ref<ProductAssetInfoResponse | null>(null);

const categoryDefaults: Record<number, number> = {
    [ProductAssetCategory.Other]: 5 * 365,
    [ProductAssetCategory.Phone]: 4 * 365,
    [ProductAssetCategory.Computer]: 5 * 365,
    [ProductAssetCategory.Tablet]: 4 * 365,
    [ProductAssetCategory.Camera]: 6 * 365,
    [ProductAssetCategory.GameConsole]: 5 * 365,
    [ProductAssetCategory.Appliance]: 8 * 365
};

const categoryOptions = [
    { title: '手机', value: ProductAssetCategory.Phone },
    { title: '电脑', value: ProductAssetCategory.Computer },
    { title: '平板', value: ProductAssetCategory.Tablet },
    { title: '相机', value: ProductAssetCategory.Camera },
    { title: '游戏机', value: ProductAssetCategory.GameConsole },
    { title: '家电', value: ProductAssetCategory.Appliance },
    { title: '其他', value: ProductAssetCategory.Other }
];

const form = reactive({
    name: '', category: ProductAssetCategory.Phone as number, brand: '', model: '', purchaseYuan: null as number | null,
    purchaseDate: today(), usefulLifeDays: categoryDefaults[ProductAssetCategory.Phone]!, residualYuan: 0 as number | null,
    marketYuan: null as number | null, comment: ''
});
const sellFormData = reactive({ amountYuan: null as number | null, date: today() });

const requiredRules = [(value: unknown) => !!value || '此项不能为空'];
const positiveRules = [(value: unknown) => Number(value) > 0 || '请输入大于 0 的数值'];
const nonNegativeRules = [(value: unknown) => Number(value) >= 0 || '请输入不小于 0 的数值'];

const assets = computed(() => store.assets);
const filteredAssets = computed(() => statusFilter.value === 0 ? assets.value : assets.value.filter(asset => asset.status === statusFilter.value));
const activeAssets = computed(() => assets.value.filter(asset => asset.status === ProductAssetStatus.Active));
const summaries = computed(() => [
    { label: '持有产品', value: `${activeAssets.value.length} 件`, hint: `全部记录 ${assets.value.length} 件` },
    { label: '累计购买', value: money(activeAssets.value.reduce((sum, asset) => sum + asset.purchaseAmount, 0)), hint: '当前持有产品购买价' },
    { label: '剩余账面价值', value: money(activeAssets.value.reduce((sum, asset) => sum + asset.valuation.bookValue, 0)), hint: '按直线法每日折旧' },
    { label: '参考二手价值', value: money(activeAssets.value.reduce((sum, asset) => sum + (asset.manualMarketValue ?? asset.valuation.bookValue), 0)), hint: '优先采用手动二手估值' }
]);

function money(value: number): string {
    return formatAmountToLocalizedNumeralsWithCurrency(value, 'CNY');
}

function today(): string {
    const now = new Date();
    const offsetDate = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
    return offsetDate.toISOString().slice(0, 10);
}

function unixDate(value: string): number {
    return Math.floor(new Date(`${value}T12:00:00`).getTime() / 1000);
}

function inputDate(unixTime: number): string {
    const date = new Date(unixTime * 1000);
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

function purchaseDate(unixTime: number): string {
    return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(unixTime * 1000));
}

function categoryName(category: number): string {
    return categoryOptions.find(item => item.value === category)?.title ?? '其他';
}

function categoryIcon(category: number): string {
    return ({
        [ProductAssetCategory.Phone]: mdiCellphone,
        [ProductAssetCategory.Computer]: mdiLaptop,
        [ProductAssetCategory.Tablet]: mdiTablet,
        [ProductAssetCategory.Camera]: mdiCameraOutline,
        [ProductAssetCategory.GameConsole]: mdiControllerClassicOutline,
        [ProductAssetCategory.Appliance]: mdiWashingMachine
    } as Record<number, string>)[category] ?? mdiDevices;
}

function lifeProgress(asset: ProductAssetInfoResponse): number {
    return Math.min(100, Math.max(0, asset.valuation.heldDays / asset.usefulLifeDays * 100));
}

function applyCategoryDefaults(): void {
    if (!editingAsset.value) {
        form.usefulLifeDays = categoryDefaults[form.category] ?? categoryDefaults[ProductAssetCategory.Other]!;
        form.residualYuan = 0;
    }
}

function resetForm(): void {
    editingAsset.value = null;
    Object.assign(form, {
        name: '', category: ProductAssetCategory.Phone, brand: '', model: '', purchaseYuan: null,
        purchaseDate: today(), usefulLifeDays: categoryDefaults[ProductAssetCategory.Phone], residualYuan: 0,
        marketYuan: null, comment: ''
    });
}

function openCreateDialog(): void {
    resetForm();
    editDialogVisible.value = true;
}

function openEditDialog(asset: ProductAssetInfoResponse): void {
    editingAsset.value = asset;
    Object.assign(form, {
        name: asset.name, category: asset.category, brand: asset.brand, model: asset.model,
        purchaseYuan: asset.purchaseAmount / AMOUNT_FACTOR, purchaseDate: inputDate(asset.purchaseTime),
        usefulLifeDays: asset.usefulLifeDays, residualYuan: asset.residualAmount / AMOUNT_FACTOR,
        marketYuan: asset.manualMarketValue === undefined ? null : asset.manualMarketValue / AMOUNT_FACTOR,
        comment: asset.comment
    });
    editDialogVisible.value = true;
}

async function save(): Promise<void> {
    const validation = await editForm.value?.validate();
    if (!validation?.valid || form.purchaseYuan === null) return;

    saving.value = true;
    const common = {
        sourceTransactionId: editingAsset.value?.sourceTransactionId ?? '0', category: form.category, name: form.name,
        brand: form.brand, model: form.model, purchaseAmount: Math.round(form.purchaseYuan * AMOUNT_FACTOR),
        purchaseTime: unixDate(form.purchaseDate), utcOffset: -new Date().getTimezoneOffset(),
        usefulLifeDays: Number(form.usefulLifeDays), residualAmount: Math.round(Number(form.residualYuan ?? 0) * AMOUNT_FACTOR),
        manualMarketValue: form.marketYuan === null ? undefined : Math.round(Number(form.marketYuan) * AMOUNT_FACTOR), comment: form.comment
    };

    try {
        if (editingAsset.value) {
            await store.modify({ ...common, id: editingAsset.value.id, clearManualMarketValue: form.marketYuan === null });
        } else {
            await store.create(common);
        }
        editDialogVisible.value = false;
        snackbar.value?.showMessage(editingAsset.value ? '产品已更新' : '产品已添加');
    } catch (error) {
        snackbar.value?.showError(error as { message: string });
    } finally {
        saving.value = false;
    }
}

function openSellDialog(asset: ProductAssetInfoResponse): void {
    sellingAsset.value = asset;
    sellFormData.amountYuan = null;
    sellFormData.date = today();
    sellDialogVisible.value = true;
}

async function sell(): Promise<void> {
    const validation = await sellForm.value?.validate();
    if (!validation?.valid || !sellingAsset.value || sellFormData.amountYuan === null) return;

    saving.value = true;
    try {
        await store.sell({
            id: sellingAsset.value.id, saleTransactionId: '0',
            soldAmount: Math.round(sellFormData.amountYuan * AMOUNT_FACTOR), soldTime: unixDate(sellFormData.date)
        });
        sellDialogVisible.value = false;
        snackbar.value?.showMessage('已登记售出，每日成本已扣除卖出收入');
    } catch (error) {
        snackbar.value?.showError(error as { message: string });
    } finally {
        saving.value = false;
    }
}

function remove(asset: ProductAssetInfoResponse): void {
    confirmDialog.value?.open('删除产品记录后无法恢复，确认删除吗？', { color: 'error' }).then(async () => {
        try {
            await store.remove(asset.id);
            snackbar.value?.showMessage('产品已删除');
        } catch (error) {
            snackbar.value?.showError(error as { message: string });
        }
    }).catch(() => undefined);
}

async function reload(): Promise<void> {
    loading.value = true;
    try {
        await store.loadAll(true);
    } catch (error) {
        snackbar.value?.showError(error as { message: string });
    } finally {
        loading.value = false;
    }
}

reload();
</script>

<style scoped>
.summary-card { border-color: rgba(var(--v-border-color), var(--v-border-opacity)); }
.summary-value { font-size: 1.45rem; line-height: 1.3; font-weight: 600; font-variant-numeric: tabular-nums; }
.amount-cell, .time-number { font-variant-numeric: tabular-nums; }
.holding-column { min-width: 300px; }
.time-number { color: rgb(var(--v-theme-primary)); }
.product-table :deep(th) { white-space: nowrap; }
@media (max-width: 959px) {
    .holding-column { min-width: 250px; }
}
</style>
