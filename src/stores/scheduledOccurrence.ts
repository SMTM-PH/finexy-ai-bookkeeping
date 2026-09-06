import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import {
    ScheduledOccurrence,
    ScheduledOccurrenceStatus
} from '@/models/scheduled_occurrence.ts';
import services from '@/lib/services.ts';

/**
 * Due schedules wait in this queue on the server; nothing posts to the ledger
 * until the user confirms an occurrence here. A malformed or partial server
 * response must not clear locally loaded rows.
 */
export const useScheduledOccurrencesStore = defineStore('scheduledOccurrences', () => {
    const occurrences = ref<ScheduledOccurrence[]>([]);
    const featureDisabled = ref(false);

    const pendingOccurrences = computed<ScheduledOccurrence[]>(() =>
        occurrences.value.filter(item => item.isPending));
    const dismissedOccurrences = computed<ScheduledOccurrence[]>(() =>
        occurrences.value.filter(item => item.isDismissed));

    async function loadStatus(status: number): Promise<ScheduledOccurrence[]> {
        const response = await services.listScheduledOccurrences({ status: status, offset: 0, limit: 100 });
        if (!response.data?.success) throw { message: '无法读取待确认队列' };
        const result = response.data.result ?? [];
        return result.map(info => ScheduledOccurrence.of(info));
    }

    async function load(): Promise<void> {
        try {
            const pending = await loadStatus(ScheduledOccurrenceStatus.Pending);
            const dismissed = await loadStatus(ScheduledOccurrenceStatus.Dismissed);
            occurrences.value = [...pending, ...dismissed];
            featureDisabled.value = false;
        } catch (error) {
            const status = (error as { response?: { status?: number } })?.response?.status;
            if (status === 400 || status === 403 || status === 404) {
                // Schedule feature disabled or review endpoints unavailable:
                // treat as an empty queue instead of an error state.
                featureDisabled.value = true;
                occurrences.value = [];
                return;
            }
            throw error;
        }
    }

    async function confirm(occurrence: ScheduledOccurrence): Promise<string> {
        const response = await services.confirmScheduledOccurrence(occurrence.actionRequest);
        if (!response.data?.success || !response.data.result?.transactionId) throw { message: '无法确认入账' };
        occurrences.value = occurrences.value.filter(item =>
            item.templateId !== occurrence.templateId || item.scheduledUnixTime !== occurrence.scheduledUnixTime);
        return response.data.result.transactionId;
    }

    async function dismiss(occurrence: ScheduledOccurrence): Promise<void> {
        const response = await services.dismissScheduledOccurrence(occurrence.actionRequest);
        if (!response.data?.success) throw { message: '无法忽略该记录' };
        await load();
    }

    async function restore(occurrence: ScheduledOccurrence): Promise<void> {
        const response = await services.restoreScheduledOccurrence(occurrence.actionRequest);
        if (!response.data?.success) throw { message: '无法恢复该记录' };
        await load();
    }

    function reset(): void {
        occurrences.value = [];
        featureDisabled.value = false;
    }

    return {
        occurrences,
        pendingOccurrences,
        dismissedOccurrences,
        featureDisabled,
        load,
        confirm,
        dismiss,
        restore,
        reset
    };
});
