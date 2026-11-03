import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { DefaultLedgerId, Ledger, LedgerDeletePreview, LedgerInvitationPreview, LedgerOverview, LedgerType, type LedgerInvitationInfoResponse, type LedgerMemberInfoResponse } from '@/models/ledger.ts';
import services from '@/lib/services.ts';
import { getTransactionStateIdentity, getCurrentUserInfo, isUserLogined, getUserSelectedLedgerId, inspectUserSelectedLedgerId, updateUserSelectedLedgerId } from '@/lib/userstate.ts';
import { mobileLedgerRole } from '@/lib/mobile_ledger.ts';

/**
 * All ledgers visible to the current user: the implicit default personal
 * ledger (id zero, no server row) plus every personal and family ledger the
 * user may access. Invalid or denied responses preserve the last valid list;
 * they must not turn into an empty ledger or a successful fallback.
 */
export const useLedgersStore = defineStore('ledgers', () => {
    const ledgers = ref<Ledger[]>([]);
    const featureDisabled = ref(false);
    const selectedLedgerId = ref('0');
    const members = ref<LedgerMemberInfoResponse[]>([]);
    const invitations = ref<LedgerInvitationInfoResponse[]>([]);
    const loading = ref(false);
    const loadError = ref('');
    let loadGeneration = 0;
    let accessGeneration = 0;
    let selectionIdentity: string | null | undefined;
    let selectionLoading: Promise<void> | null = null;
    let strictSelectionGeneration = 0;

    const defaultLedger = computed<Ledger>(() => Ledger.of({
        id: '0',
        ownerUid: '0',
        type: 1,
        name: '默认个人账本',
        comment: '',
        createdTime: 0
    }));

    const allLedgers = computed<Ledger[]>(() => [defaultLedger.value, ...ledgers.value]);
    const selectedLedger = computed<Ledger>(() =>
        allLedgers.value.find(ledger => ledger.id === selectedLedgerId.value) ?? defaultLedger.value
    );

    async function load(): Promise<void> {
        const generation = ++loadGeneration;
        const identity = getTransactionStateIdentity();
        loading.value = true;
        loadError.value = '';
        try {
            const response = await services.listLedgers();
            if (generation !== loadGeneration || identity !== getTransactionStateIdentity()) return;
            if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('无法读取有效账本列表，请重试');
            const next = response.data.result.map(info => Ledger.of(info));
            if (next.some(item => item.id === DefaultLedgerId) || new Set(next.map(item => item.id)).size !== next.length) throw new Error('账本列表响应无效，请重试');
            ledgers.value = next;
            if (selectionIdentity !== identity) {
                selectedLedgerId.value = getUserSelectedLedgerId();
                selectionIdentity = identity;
            }
            if (!allLedgers.value.some(ledger => ledger.id === selectedLedgerId.value)) {
                selectedLedgerId.value = '0';
                updateUserSelectedLedgerId('0');
            }
            featureDisabled.value = false;
        } catch (error) {
            if (generation !== loadGeneration) return;
            const status = (error as { response?: { status?: number } })?.response?.status;
            featureDisabled.value = status === 404;
            loadError.value = status === 403 ? '无法访问账本列表，请重新登录或重试' : error instanceof Error ? error.message : '无法读取账本列表，请重试';
            throw error;
        } finally {
            if (generation === loadGeneration) loading.value = false;
        }
    }

    async function ensureSelection(): Promise<void> {
        if (selectionIdentity === getTransactionStateIdentity()) return;
        if (!selectionLoading) selectionLoading = load();
        const pending = selectionLoading;
        try {
            await pending;
            if (selectionIdentity !== getTransactionStateIdentity()) throw new Error('登录账号已变化，请重新加载账本');
        } finally {
            if (selectionLoading === pending) selectionLoading = null;
        }
    }

    /** Restore only this session's existing choice. A deep link is a locator,
     * never authorization to change ledger or persist a default fallback. */
    async function ensureStrictSelection(isActionCurrent: () => boolean = () => true): Promise<string> {
        const identity = getTransactionStateIdentity(), username = getCurrentUserInfo()?.username;
        if (!identity || !username || !isUserLogined()) throw new Error('请先登录后核验原账本');
        const restoring = selectionIdentity !== identity;
        const expectedLedgerId = restoring ? inspectUserSelectedLedgerId() : selectedLedgerId.value;
        const initialLedgerId = selectedLedgerId.value;
        const generation = ++loadGeneration, selectionGeneration = ++strictSelectionGeneration;
        const assertCurrent = () => {
            if (generation !== loadGeneration || selectionGeneration !== strictSelectionGeneration || !isActionCurrent()
                || identity !== getTransactionStateIdentity() || username !== getCurrentUserInfo()?.username || !isUserLogined()
                || selectedLedgerId.value !== initialLedgerId
                || restoring && inspectUserSelectedLedgerId() !== expectedLedgerId) throw new Error('账号、账本或原入口已变化，请重新打开');
        };
        loading.value = true; loadError.value = '';
        try {
            assertCurrent();
            const response = await services.listLedgers({ expectedUsername: username, ignoreError: true });
            assertCurrent();
            if (!response.data?.success || !Array.isArray(response.data.result)) throw new Error('无法读取完整账本列表，请重试');
            const next = response.data.result.map(info => Ledger.of(info));
            if (next.some(item => item.id === DefaultLedgerId) || new Set(next.map(item => item.id)).size !== next.length) throw new Error('账本列表响应无效，请重试');
            if (expectedLedgerId !== DefaultLedgerId && !next.some(item => item.id === expectedLedgerId)) throw new Error('原账本不可访问，请返回总览核对；不会改用默认账本');
            let nextMembers: LedgerMemberInfoResponse[] = [];
            if (expectedLedgerId === DefaultLedgerId) {
                const profile = await services.getProfile({ expectedUsername: username, ignoreError: true });
                assertCurrent();
                if (!profile.data?.success || profile.data.result?.username !== username) throw new Error('无法核验当前登录，请重试');
            } else {
                const access = await services.listLedgerMembers({ ledgerId: expectedLedgerId, expectedUsername: username, ignoreError: true });
                assertCurrent();
                if (!access.data?.success || !Array.isArray(access.data.result)) throw new Error('无法核验原账本成员权限，请重试');
                mobileLedgerRole(expectedLedgerId, access.data.result);
                nextMembers = access.data.result;
            }
            assertCurrent();
            ledgers.value = next;
            members.value = nextMembers; invitations.value = [];
            selectionIdentity = identity;
            selectedLedgerId.value = expectedLedgerId;
            featureDisabled.value = false;
            return expectedLedgerId;
        } catch (error) {
            if (generation === loadGeneration) loadError.value = error instanceof Error ? error.message : '无法核验原账本，请重试';
            throw error;
        } finally {
            if (generation === loadGeneration) loading.value = false;
        }
    }

    async function createLedger(name: string, comment: string): Promise<Ledger> {
        const response = await services.createLedger({
            type: LedgerType.Personal,
            name: name.trim(),
            comment: comment.trim()
        });
        if (!response.data?.success || !response.data.result) {
            throw { message: '无法创建账本' };
        }
        const ledger = Ledger.of(response.data.result);
        await load();
        return ledger;
    }

    async function loadOverview(ledgerId: string): Promise<LedgerOverview> {
        const response = await services.getLedgerOverview({ ledgerId });
        if (!response.data?.success || !response.data.result) throw new Error('无法读取账本概览');
        const overview = LedgerOverview.of(response.data.result);
        if (overview.ledgerId !== ledgerId) throw new Error('账本概览归属不匹配，请重试');
        return overview;
    }

    async function loadAccess(ledgerId = selectedLedgerId.value): Promise<void> {
        const generation = ++accessGeneration;
        members.value = []; invitations.value = [];
        if (ledgerId === DefaultLedgerId) return;
        const memberResponse = await services.listLedgerMembers({ ledgerId });
        if (generation !== accessGeneration) return;
        if (!memberResponse.data?.success || !Array.isArray(memberResponse.data.result)) throw new Error('无法读取账本成员');
        members.value = memberResponse.data.result;
        const me = members.value.find(member => member.isCurrentUser && member.status === 1);
        if (me?.role !== 1 && me?.role !== 2) return;
        const invitationResponse = await services.listLedgerInvitations({ ledgerId });
        if (generation !== accessGeneration) return;
        if (!invitationResponse.data?.success || !Array.isArray(invitationResponse.data.result)) throw new Error('无法读取邀请记录');
        invitations.value = invitationResponse.data.result;
    }

    async function invite(inviteeName: string, role: number, ledgerId = selectedLedgerId.value): Promise<LedgerInvitationInfoResponse> {
        const response = await services.createLedgerInvitation({ ledgerId, inviteeName: inviteeName.trim(), role, expiresInSeconds: 86400 });
        if (!response.data?.success || !response.data.result) throw { message: '无法创建邀请' };
        return response.data.result;
    }

    async function accept(token: string): Promise<Ledger> {
        const response = await services.acceptLedgerInvitation({ token: token.trim() });
        if (!response.data?.success || !response.data.result) throw { message: '无法加入账本' };
        const ledger = Ledger.of(response.data.result);
        await load();
        return ledger;
    }

    async function previewInvitation(token: string): Promise<LedgerInvitationPreview> {
        const response = await services.previewLedgerInvitation({ token: token.trim() });
        if (!response.data?.success || !response.data.result) throw { message: '无法读取邀请信息' };
        return LedgerInvitationPreview.of(response.data.result);
    }

    async function changeRole(memberId: string, role: number, ledgerId = selectedLedgerId.value): Promise<void> {
        const response = await services.changeLedgerMemberRole({ ledgerId, memberId, role });
        if (!response.data?.success) throw { message: '无法修改成员权限' };
    }

    async function removeMember(memberId: string, ledgerId = selectedLedgerId.value): Promise<void> {
        const response = await services.removeLedgerMember({ ledgerId, memberId });
        if (!response.data?.success) throw { message: '无法移除成员' };
    }

    async function revokeInvitation(invitationId: string, ledgerId = selectedLedgerId.value): Promise<void> {
        const response = await services.revokeLedgerInvitation({ ledgerId, invitationId });
        if (!response.data?.success) throw { message: '无法撤销邀请' };
    }

    async function deleteLedger(ledgerId: string): Promise<void> {
        if (ledgerId === DefaultLedgerId) throw new Error('默认账本不能删除');
        const response = await services.deleteLedger({ id: ledgerId });
        if (!response.data?.success) throw new Error('无法删除账本');
        if (selectedLedgerId.value === ledgerId) selectedLedgerId.value = DefaultLedgerId;
        ++accessGeneration;
        members.value = [];
        invitations.value = [];
        await load();
    }

    async function previewDelete(ledgerId: string): Promise<LedgerDeletePreview> {
        if (ledgerId === DefaultLedgerId) throw new Error('默认账本不能删除');
        const response = await services.previewLedgerDelete({ id: ledgerId });
        if (!response.data?.success || !response.data.result) throw new Error('无法读取删除影响');
        return LedgerDeletePreview.of(response.data.result);
    }

    function reset(): void {
        ++loadGeneration;
        ++strictSelectionGeneration;
        ++accessGeneration;
        ledgers.value = [];
        featureDisabled.value = false;
        selectedLedgerId.value = '0';
        members.value = [];
        invitations.value = [];
        loading.value = false;
        loadError.value = '';
        selectionIdentity = undefined;
        selectionLoading = null;
    }

    function select(ledgerId: string): void {
        if (!allLedgers.value.some(ledger => ledger.id === ledgerId)) {
            throw new Error('账本不可用或已被移除');
        }
        updateUserSelectedLedgerId(ledgerId);
        ++strictSelectionGeneration;
        selectedLedgerId.value = ledgerId;
    }

    return {
        ledgers,
        allLedgers,
        selectedLedgerId,
        selectedLedger,
        featureDisabled,
        loading,
        loadError,
        load,
        ensureSelection,
        ensureStrictSelection,
        members,
        invitations,
        createLedger,
        loadOverview,
        loadAccess,
        invite,
        previewInvitation,
        accept,
        changeRole,
        removeMember,
        revokeInvitation,
        deleteLedger,
        previewDelete,
        select,
        reset
    };
});
