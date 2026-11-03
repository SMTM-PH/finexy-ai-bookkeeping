import { computed, ref, watch } from 'vue';
import { LedgerMemberRole } from '@/models/ledger.ts';
import { isPeriodicStringId } from '@/models/transaction_template.ts';
import { getCurrentUserInfo, getTransactionStateIdentity } from '@/lib/userstate.ts';
import { mobileLedgerRole } from '@/lib/mobile_ledger.ts';
import services from '@/lib/services.ts';
import logger from '@/lib/logger.ts';
import { useLedgersStore } from './ledger.ts';
import { useUserStore } from './user.ts';

/** A scope change invalidates requests as well as the rows currently shown. */
export function usePeriodicScope(clear: (ledgerId: string) => void) {
    const ledgers = useLedgersStore();
    const user = useUserStore();
    const loadedLedgerId = ref(ledgers.selectedLedgerId);
    const role = ref<number | null>(loadedLedgerId.value === '0' ? LedgerMemberRole.Owner : null);
    let identity = getTransactionStateIdentity();
    let generation = 0;

    function reset(ledgerId = ledgers.selectedLedgerId): void {
        generation++;
        identity = getTransactionStateIdentity();
        loadedLedgerId.value = ledgerId;
        role.value = ledgerId === '0' ? LedgerMemberRole.Owner : null;
        clear(ledgerId);
    }

    watch(() => [ledgers.selectedLedgerId, user.currentUserBasicInfo?.username], () => reset(), { flush: 'sync' });

    function selectLedger(ledgerId: string): void {
        if (!isPeriodicStringId(ledgerId) || ledgerId !== ledgers.selectedLedgerId) {
            throw new Error('账本已切换，请重新打开当前账本的周期计划');
        }
        if (loadedLedgerId.value !== ledgerId || identity !== getTransactionStateIdentity()) reset(ledgerId);
    }

    function capture(ledgerId: string) {
        selectLedger(ledgerId);
        const username = getCurrentUserInfo()?.username;
        if (!identity || !username) throw new Error('请登录后读取当前账本的周期计划');
        return Object.freeze({ ledgerId, identity, username, generation });
    }

    function assertCurrent(context: ReturnType<typeof capture>): void {
        if (context.generation !== generation || context.identity !== getTransactionStateIdentity()
            || context.username !== getCurrentUserInfo()?.username || context.ledgerId !== loadedLedgerId.value
            || context.ledgerId !== ledgers.selectedLedgerId) {
            throw Object.assign(new Error('账号或账本已变化，请重新读取'), { stale: true });
        }
    }

    async function authorize(context: ReturnType<typeof capture>, action: 'read' | 'manage' | 'review'): Promise<void> {
        assertCurrent(context);
        let nextRole: number = LedgerMemberRole.Owner;
        if (context.ledgerId !== '0') {
            const response = await services.listLedgerMembers({ ledgerId: context.ledgerId, expectedUsername: context.username, ignoreError: true });
            assertCurrent(context);
            if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('无法读取当前账本成员权限，请重试');
            nextRole = mobileLedgerRole(context.ledgerId, response.data.result);
        }
        role.value = nextRole;
        if ((action === 'manage' && nextRole !== LedgerMemberRole.Owner && nextRole !== LedgerMemberRole.Admin)
            || (action === 'review' && nextRole === LedgerMemberRole.Viewer)) {
            throw new Error(action === 'manage' ? '当前成员仅可查看周期模板，无法管理' : '当前成员仅可查看待确认记录，无法入账或忽略');
        }
    }

    return {
        loadedLedgerId, role, reset, selectLedger, capture, assertCurrent, authorize,
        canManage: computed(() => role.value === LedgerMemberRole.Owner || role.value === LedgerMemberRole.Admin),
        canReview: computed(() => role.value === LedgerMemberRole.Owner || role.value === LedgerMemberRole.Admin || role.value === LedgerMemberRole.Member)
    };
}

export function periodicError(error: unknown, fallback: string): never {
    // Axios errors may include credentials or financial request contents.
    logger.warn(fallback);
    const api = (error && typeof error === 'object' ? error : {}) as { response?: { status?: number; data?: { errorMessage?: string } }; processed?: boolean };
    if (api.response?.data?.errorMessage) throw { error: { errorMessage: api.response.data.errorMessage }, status: api.response.status };
    if (error instanceof Error || api.processed) throw error;
    throw { message: fallback, status: api.response?.status };
}
