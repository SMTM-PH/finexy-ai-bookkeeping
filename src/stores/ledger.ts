import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { DefaultLedgerId, Ledger, LedgerDeletePreview, LedgerInvitationPreview, LedgerOverview, LedgerType, type LedgerInvitationInfoResponse, type LedgerMemberInfoResponse } from '@/models/ledger.ts';
import services from '@/lib/services.ts';

/**
 * All ledgers visible to the current user: the implicit default personal
 * ledger (id zero, no server row) plus every personal and family ledger the
 * user may access. A 400/403/404 response means the ledger feature is
 * unavailable on this server and is presented as an empty state.
 */
export const useLedgersStore = defineStore('ledgers', () => {
    const ledgers = ref<Ledger[]>([]);
    const featureDisabled = ref(false);
    const selectedLedgerId = ref('0');
    const members = ref<LedgerMemberInfoResponse[]>([]);
    const invitations = ref<LedgerInvitationInfoResponse[]>([]);
    let accessGeneration = 0;

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
        try {
            const response = await services.listLedgers();
            if (!response.data?.success) throw { message: '无法读取账本列表' };
            ledgers.value = (response.data.result ?? []).map(info => Ledger.of(info));
            if (!allLedgers.value.some(ledger => ledger.id === selectedLedgerId.value)) {
                selectedLedgerId.value = '0';
            }
            featureDisabled.value = false;
        } catch (error) {
            const status = (error as { response?: { status?: number } })?.response?.status;
            if (status === 400 || status === 403 || status === 404) {
                featureDisabled.value = true;
                ledgers.value = [];
                return;
            }
            throw error;
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
        return LedgerOverview.of(response.data.result);
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
        ++accessGeneration;
        ledgers.value = [];
        featureDisabled.value = false;
        selectedLedgerId.value = '0';
        members.value = [];
        invitations.value = [];
    }

    function select(ledgerId: string): void {
        if (!allLedgers.value.some(ledger => ledger.id === ledgerId)) {
            throw new Error('账本不可用或已被移除');
        }
        selectedLedgerId.value = ledgerId;
    }

    return {
        ledgers,
        allLedgers,
        selectedLedgerId,
        selectedLedger,
        featureDisabled,
        load,
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
