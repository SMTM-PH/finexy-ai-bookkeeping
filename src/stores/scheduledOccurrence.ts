import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { ScheduledOccurrence, ScheduledOccurrenceStatus } from '@/models/scheduled_occurrence.ts';
import { isPeriodicStringId } from '@/models/transaction_template.ts';
import services from '@/lib/services.ts';
import { periodicError, usePeriodicScope } from './periodicScope.ts';

export const useScheduledOccurrencesStore = defineStore('scheduledOccurrences', () => {
    const occurrences = ref<ScheduledOccurrence[]>([]);
    const featureDisabled = ref(false);
    let loadVersion = 0;
    const scope = usePeriodicScope(() => {
        loadVersion++;
        occurrences.value = [];
        featureDisabled.value = false;
    });
    const pendingOccurrences = computed(() => occurrences.value.filter(item => item.isPending));
    const dismissedOccurrences = computed(() => occurrences.value.filter(item => item.isDismissed));

    async function load(ledgerId = '0'): Promise<void> {
        const context = scope.capture(ledgerId);
        const version = ++loadVersion;
        try {
            await scope.authorize(context, 'read');
            const rows: ScheduledOccurrence[] = [];
            const keys = new Set<string>();
            for (const status of [ScheduledOccurrenceStatus.Pending, ScheduledOccurrenceStatus.Dismissed]) {
                for (let offset = 0; ; offset += 100) {
                    const response = await services.listScheduledOccurrences({ status, offset, limit: 100, ledgerId, expectedUsername: context.username });
                    scope.assertCurrent(context);
                    if (version !== loadVersion) throw Object.assign(new Error('队列读取已被后续请求替代'), { stale: true });
                    if (!response.data?.success || !Array.isArray(response.data.result) || response.data.result.length > 100) throw new Error('待确认队列响应不完整，请重试');
                    for (const info of response.data.result) {
                        const row = ScheduledOccurrence.of(info, ledgerId);
                        const key = `${row.templateId}:${row.scheduledUnixTime}`;
                        if (row.status !== status || keys.has(key)) throw new Error('待确认队列已变化或响应重复，请重新读取');
                        keys.add(key);
                        rows.push(row);
                    }
                    if (response.data.result.length < 100) break;
                }
            }
            scope.assertCurrent(context);
            if (version !== loadVersion) throw Object.assign(new Error('队列读取已被后续请求替代'), { stale: true });
            occurrences.value = rows;
            featureDisabled.value = false;
        } catch (error) { periodicError(error, '无法读取当前账本待确认队列'); }
    }

    async function confirm(occurrence: ScheduledOccurrence): Promise<string> {
        const request = Object.freeze({ ...occurrence.actionRequest });
        const context = scope.capture(occurrence.ledgerId);
        try {
            await scope.authorize(context, 'review');
            const response = await services.confirmScheduledOccurrence(request, context.username);
            scope.assertCurrent(context);
            const result = response.data?.result;
            if (!response.data?.success || !result || (result.ledgerId ?? '0') !== context.ledgerId || !isPeriodicStringId(result.transactionId, false)) throw new Error('确认回包不完整或账本不匹配，请重试原记录确认结果');
            loadVersion++;
            occurrences.value = occurrences.value.filter(item => item.templateId !== occurrence.templateId || item.scheduledUnixTime !== occurrence.scheduledUnixTime);
            return result.transactionId;
        } catch (error) { return periodicError(error, '无法确认当前账本周期记录'); }
    }

    async function changeDismissed(occurrence: ScheduledOccurrence, dismissed: boolean): Promise<void> {
        const request = Object.freeze({ ...occurrence.actionRequest });
        const context = scope.capture(occurrence.ledgerId);
        try {
            await scope.authorize(context, 'review');
            const action = dismissed ? services.dismissScheduledOccurrence : services.restoreScheduledOccurrence;
            const response = await action(request, context.username);
            scope.assertCurrent(context);
            if (!response.data?.success || response.data.result !== true) throw new Error('未能确认操作结果，请重新读取');
            await load(context.ledgerId);
        } catch (error) { periodicError(error, dismissed ? '无法忽略当前账本周期记录' : '无法恢复当前账本周期记录'); }
    }

    return {
        occurrences, pendingOccurrences, dismissedOccurrences, featureDisabled,
        loadedLedgerId: scope.loadedLedgerId, canReview: scope.canReview,
        selectLedger: scope.selectLedger, reset: () => scope.reset(), load, confirm,
        dismiss: (occurrence: ScheduledOccurrence) => changeDismissed(occurrence, true),
        restore: (occurrence: ScheduledOccurrence) => changeDismissed(occurrence, false),
    };
});
