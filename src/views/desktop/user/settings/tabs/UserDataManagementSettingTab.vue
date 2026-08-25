<template>
    <v-row>
        <v-col cols="12">
            <v-card :class="{ 'disabled': loadingDataStatistics }">
                <template #title>
                    <div class="d-flex align-center">
                        <span>{{ tt('Data Management') }}</span>
                        <v-btn density="compact" color="default" variant="text" size="24"
                               class="ms-2" :icon="true" :loading="loadingDataStatistics" @click="reloadUserDataStatistics(true)">
                            <template #loader>
                                <v-progress-circular indeterminate size="20"/>
                            </template>
                            <v-icon :icon="mdiRefresh" size="24" />
                            <v-tooltip activator="parent">{{ tt('Refresh') }}</v-tooltip>
                        </v-btn>
                    </div>
                </template>

                <v-card-text>
                    <v-row>
                        <v-col cols="6" sm="3" :key="idx" v-for="(item, idx) in [
                            {
                                title: 'Transactions',
                                count: displayDataStatistics ? displayDataStatistics.totalTransactionCount : '-',
                                icon: mdiListBoxOutline,
                                color: 'info-darken-1'
                            },
                            {
                                title: 'Transaction Pictures',
                                count: displayDataStatistics ? displayDataStatistics.totalTransactionPictureCount : '-',
                                icon: mdiImage,
                                color: 'error-darken-1'
                            },
                            {
                                title: 'Accounts',
                                count: displayDataStatistics ? displayDataStatistics.totalAccountCount : '-',
                                icon: mdiCreditCardOutline,
                                color: 'primary'
                            },
                            {
                                title: 'Explorations',
                                count: displayDataStatistics ? displayDataStatistics.totalExplorationCount : '-',
                                icon: mdiCompassOutline,
                                color: 'warning'
                            },
                            {
                                title: 'Transaction Categories',
                                count: displayDataStatistics ? displayDataStatistics.totalTransactionCategoryCount : '-',
                                icon: mdiViewDashboardOutline,
                                color: 'teal'
                            },
                            {
                                title: 'Transaction Tags',
                                count: displayDataStatistics ? displayDataStatistics.totalTransactionTagCount : '-',
                                icon: mdiTagOutline,
                                color: 'grey'
                            },
                            {
                                title: 'Transaction Templates',
                                count: displayDataStatistics ? displayDataStatistics.totalTransactionTemplateCount : '-',
                                icon: mdiClipboardTextOutline,
                                color: 'secondary-darken-1'
                            },
                            {
                                title: 'Scheduled Transactions',
                                count: displayDataStatistics ? displayDataStatistics.totalScheduledTransactionCount : '-',
                                icon: mdiClipboardTextClockOutline,
                                color: 'success-darken-1'
                            }
                        ]">
                            <div class="d-flex align-center">
                                <div class="me-3">
                                    <v-avatar rounded :color="item.color" size="42" class="elevation-1">
                                        <v-icon size="24" :icon="item.icon"/>
                                    </v-avatar>
                                </div>

                                <div class="d-flex flex-column">
                                    <span class="text-caption">{{ tt(item.title) }}</span>
                                    <v-skeleton-loader class="skeleton-no-margin pt-2 pb-2" type="text" style="width: 60px" :loading="true" v-if="loadingDataStatistics"></v-skeleton-loader>
                                    <span class="text-xl" v-if="!loadingDataStatistics">{{ item.count }}</span>
                                </div>
                            </div>
                        </v-col>
                    </v-row>
                </v-card-text>
            </v-card>
        </v-col>

        <v-col cols="12" v-if="isDataExportingEnabled()">
            <v-card :class="{ 'disabled': exportingData }" :title="tt('Export Data')">
                <v-card-text>
                    <span class="text-body-1">{{ tt('Export all transaction data to file.') }}&nbsp;{{ tt('It may take a long time, please wait for a few minutes.') }}</span>
                </v-card-text>

                <v-card-text class="d-flex flex-wrap gap-4">
                    <v-btn-group variant="elevated" density="comfortable" color="primary">
                        <v-btn :disabled="loadingDataStatistics || exportingData || !dataStatistics || !dataStatistics.totalTransactionCount || dataStatistics.totalTransactionCount === '0'">
                            {{ tt('Export Data') }}
                            <v-progress-circular indeterminate size="22" class="ms-2" v-if="exportingData"></v-progress-circular>
                            <v-menu activator="parent">
                                <v-list :disabled="loadingDataStatistics || exportingData || !dataStatistics || !dataStatistics.totalTransactionCount || dataStatistics.totalTransactionCount === '0'">
                                    <v-list-item @click="exportData('csv')">
                                        <v-list-item-title>{{ tt('CSV (Comma-separated values) File') }}</v-list-item-title>
                                    </v-list-item>
                                    <v-list-item @click="exportData('tsv')">
                                        <v-list-item-title>{{ tt('TSV (Tab-separated values) File') }}</v-list-item-title>
                                    </v-list-item>
                                </v-list>
                            </v-menu>
                        </v-btn>
                    </v-btn-group>
                </v-card-text>
            </v-card>
        </v-col>

        <v-col cols="12" v-if="isDataExportingEnabled() || isDataImportingEnabled()">
            <v-card :class="{ 'disabled': processingFullBackup }">
                <template #title>完整备份与恢复</template>
                <v-card-text>
                    <span class="text-body-1">备份包含账户、流水、预算、商品资产、AI 分析报告及本地附件。恢复会校验备份并重启服务，恢复前的数据会保留为可回退副本。</span>
                </v-card-text>
                <v-card-text class="d-flex flex-wrap gap-4">
                    <v-btn color="primary" variant="elevated" :prepend-icon="mdiDownload"
                           :loading="downloadingFullBackup" :disabled="processingFullBackup || !isDataExportingEnabled()"
                           @click="downloadFullBackup">
                        下载完整备份
                    </v-btn>
                    <v-btn color="warning" variant="tonal" :prepend-icon="mdiBackupRestore"
                           :loading="restoringFullBackup" :disabled="processingFullBackup || !isDataImportingEnabled()"
                           @click="selectFullBackup">
                        从备份恢复
                    </v-btn>
                    <input ref="fullBackupInput" class="d-none" type="file" accept=".zip,application/zip" @change="restoreFullBackup" />
                </v-card-text>
            </v-card>
        </v-col>

        <v-col cols="12">
            <v-card :class="{ 'disabled': clearingData }">
                <template #title>
                    <span class="text-error">{{ tt('Danger Zone') }}</span>
                </template>

                <v-card-text class="py-0">
                    <span class="text-body-1 text-error">
                        <v-icon class="mt-n1" :icon="mdiAlert"/>
                        {{ tt('You CANNOT undo this action. "Clear All Transactions" will clear all your transactions data, and "Clear All Data" will clear your accounts, categories, tags and transactions data. Please enter your current password to confirm.') }}
                    </span>
                </v-card-text>

                <v-card-text class="pb-0">
                    <v-row class="mb-3">
                        <v-col cols="12" md="6">
                            <v-text-field
                                autocomplete="current-password"
                                ref="currentPasswordInput"
                                type="password"
                                variant="underlined"
                                color="error"
                                :disabled="loadingDataStatistics || clearingData"
                                :placeholder="tt('Current Password')"
                                v-model="currentPasswordForClearData"
                            />
                        </v-col>
                    </v-row>
                </v-card-text>

                <v-card-text class="d-flex flex-wrap gap-4">
                    <v-btn color="error" :disabled="loadingDataStatistics || !currentPasswordForClearData || clearingData">
                        {{ tt('Clear User Data') }}
                        <v-progress-circular indeterminate size="22" class="ms-2" v-if="clearingData"></v-progress-circular>
                        <v-menu activator="parent">
                            <v-list :disabled="loadingDataStatistics || !currentPasswordForClearData || clearingData">
                                <v-list-item @click="clearAllTransactions">
                                    <v-list-item-title>{{ tt('Clear All Transactions') }}</v-list-item-title>
                                </v-list-item>
                                <v-list-item @click="clearAllData">
                                    <v-list-item-title>{{ tt('Clear All Data') }}</v-list-item-title>
                                </v-list-item>
                            </v-list>
                        </v-menu>
                    </v-btn>
                </v-card-text>
            </v-card>
        </v-col>
    </v-row>

    <confirm-dialog ref="confirmDialog"/>
    <snack-bar ref="snackbar" />
</template>

<script setup lang="ts">
import ConfirmDialog from '@/components/desktop/ConfirmDialog.vue';
import SnackBar from '@/components/desktop/SnackBar.vue';

import { computed, ref, useTemplateRef } from 'vue';

import { useI18n } from '@/locales/helpers.ts';
import { useDataManagementPageBase } from '@/views/base/users/DataManagementPageBase.ts';

import { useRootStore } from '@/stores/index.ts';
import { useUserStore } from '@/stores/user.ts';

import { isEquals } from '@/lib/common.ts';
import services from '@/lib/services.ts';
import { isDataExportingEnabled, isDataImportingEnabled } from '@/lib/server_settings.ts';
import { startDownloadFile } from '@/lib/ui/common.ts';

import {
    mdiRefresh,
    mdiListBoxOutline,
    mdiCreditCardOutline,
    mdiImage,
    mdiCompassOutline,
    mdiViewDashboardOutline,
    mdiTagOutline,
    mdiClipboardTextOutline,
    mdiClipboardTextClockOutline,
    mdiAlert,
    mdiDownload,
    mdiBackupRestore
} from '@mdi/js';

type ConfirmDialogType = InstanceType<typeof ConfirmDialog>;
type SnackBarType = InstanceType<typeof SnackBar>;

const { tt } = useI18n();
const { dataStatistics, displayDataStatistics, getExportFileName } = useDataManagementPageBase();

const rootStore = useRootStore();
const userStore = useUserStore();

const confirmDialog = useTemplateRef<ConfirmDialogType>('confirmDialog');
const snackbar = useTemplateRef<SnackBarType>('snackbar');
const fullBackupInput = useTemplateRef<HTMLInputElement>('fullBackupInput');

const loadingDataStatistics = ref<boolean>(true);
const exportingData = ref<boolean>(false);
const currentPasswordForClearData = ref<string>('');
const clearingData = ref<boolean>(false);
const downloadingFullBackup = ref<boolean>(false);
const restoringFullBackup = ref<boolean>(false);
const processingFullBackup = computed<boolean>(() => downloadingFullBackup.value || restoringFullBackup.value);

function fullBackupFileName(): string {
    const date = new Date();
    const part = (value: number): string => value.toString().padStart(2, '0');
    return `ai-bookkeeping-full-backup_${date.getFullYear()}${part(date.getMonth() + 1)}${part(date.getDate())}_${part(date.getHours())}${part(date.getMinutes())}${part(date.getSeconds())}.zip`;
}

function downloadFullBackup(): void {
    if (processingFullBackup.value) {
        return;
    }
    downloadingFullBackup.value = true;
    services.downloadFullBackup().then(response => {
        startDownloadFile(fullBackupFileName(), response.data);
        snackbar.value?.showMessage('完整备份已下载');
    }).catch(error => {
        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    }).finally(() => {
        downloadingFullBackup.value = false;
    });
}

function selectFullBackup(): void {
    if (!processingFullBackup.value) {
        fullBackupInput.value?.click();
    }
}

function restoreFullBackup(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || processingFullBackup.value) {
        return;
    }
    confirmDialog.value?.open('确认使用此备份替换当前全部数据？服务将自动重启，当前数据会保留为恢复前副本。', { color: 'warning' }).then(() => {
        restoringFullBackup.value = true;
        services.restoreFullBackup(file).then(() => {
            snackbar.value?.showMessage('备份已校验，服务正在重启…');
            waitForRestart();
        }).catch(error => {
            restoringFullBackup.value = false;
            if (!error.processed) {
                snackbar.value?.showError(error);
            }
        });
    });
}

function waitForRestart(): void {
    let attempts = 0;
    const timer = window.setInterval(() => {
        attempts++;
        fetch(window.location.href, { cache: 'no-store' }).then(response => {
            if (response.ok && attempts > 1) {
                window.clearInterval(timer);
                window.location.reload();
            }
        }).catch(() => undefined);
        if (attempts >= 30) {
            window.clearInterval(timer);
            restoringFullBackup.value = false;
            snackbar.value?.showMessage('服务重启时间较长，请稍后手动刷新页面');
        }
    }, 2000);
}

function reloadUserDataStatistics(force: boolean): void {
    loadingDataStatistics.value = true;

    userStore.getUserDataStatistics().then(dataStatisticsResponse => {
        if (force) {
            if (isEquals(dataStatistics.value, dataStatisticsResponse)) {
                snackbar.value?.showMessage('Data is up to date');
            } else {
                snackbar.value?.showMessage('Data has been updated');
            }
        }

        dataStatistics.value = dataStatisticsResponse;
        loadingDataStatistics.value = false;
    }).catch(error => {
        loadingDataStatistics.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function exportData(fileType: string): void {
    if (exportingData.value) {
        return;
    }

    exportingData.value = true;

    userStore.getExportedUserData(fileType).then(data => {
        startDownloadFile(getExportFileName(fileType), data);
        exportingData.value = false;
    }).catch(error => {
        exportingData.value = false;

        if (!error.processed) {
            snackbar.value?.showError(error);
        }
    });
}

function clearAllTransactions(): void {
    if (!currentPasswordForClearData.value) {
        snackbar.value?.showMessage('Current password cannot be blank');
        return;
    }

    if (clearingData.value) {
        return;
    }

    confirmDialog.value?.open('Are you sure you want to clear all transactions?', { color: 'error' }).then(() => {
        clearingData.value = true;

        rootStore.clearAllUserTransactions({
            password: currentPasswordForClearData.value
        }).then(() => {
            clearingData.value = false;
            currentPasswordForClearData.value = '';

            snackbar.value?.showMessage('All transactions has been cleared');
            reloadUserDataStatistics(false);
        }).catch(error => {
            clearingData.value = false;

            if (!error.processed) {
                snackbar.value?.showError(error);
            }
        });
    });
}

function clearAllData(): void {
    if (!currentPasswordForClearData.value) {
        snackbar.value?.showMessage('Current password cannot be blank');
        return;
    }

    if (clearingData.value) {
        return;
    }

    confirmDialog.value?.open('Are you sure you want to clear all data?', { color: 'error' }).then(() => {
        clearingData.value = true;

        rootStore.clearAllUserData({
            password: currentPasswordForClearData.value
        }).then(() => {
            clearingData.value = false;
            currentPasswordForClearData.value = '';

            snackbar.value?.showMessage('All user data has been cleared');
            reloadUserDataStatistics(false);
        }).catch(error => {
            clearingData.value = false;

            if (!error.processed) {
                snackbar.value?.showError(error);
            }
        });
    });
}

reloadUserDataStatistics(false);
</script>
