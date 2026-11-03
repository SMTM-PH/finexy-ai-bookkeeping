// A test host for production stores and storage. This does not mount a product
// page and must not be counted as production UI acceptance.
import { createPinia, setActivePinia } from 'pinia';
import { useAIReviewLedgerStore } from '@/stores/aiReviewLedger.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { updateCurrentToken, updateCurrentUserInfo } from '@/lib/userstate.ts';
import type { UserBasicInfo } from '@/models/user.ts';
import type { AIReviewConfirmRequest, LedgerAIReviewCreateRequest } from '@/models/ai_review_ledger.ts';

export function boot(auth: { token: string; user: UserBasicInfo }, ledgerId: string) {
    updateCurrentToken(auth.token); updateCurrentUserInfo(auth.user);
    setActivePinia(createPinia());
    const ledgers = useLedgersStore(); ledgers.selectedLedgerId = ledgerId;
    const store = useAIReviewLedgerStore();
    return { store, select: (id: string) => { ledgers.selectedLedgerId = id; } };
}
export type { AIReviewConfirmRequest, LedgerAIReviewCreateRequest };
