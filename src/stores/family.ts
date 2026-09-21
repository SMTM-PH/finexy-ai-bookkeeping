import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { FamilyGroup, FamilyInvitation, FamilyMember } from '@/models/family.ts';
import services from '@/lib/services.ts';

/**
 * Client view of the family domain. Family rows live in the family owner's
 * shard on the server; the client only ever talks to the HTTP endpoints. A
 * 400/403/404 response means the family feature is unavailable on this server
 * and is presented as an empty state instead of an error.
 */
export const useFamilyStore = defineStore('family', () => {
    const groups = ref<FamilyGroup[]>([]);
    const members = ref<FamilyMember[]>([]);
    const invitations = ref<FamilyInvitation[]>([]);
    const myMember = ref<FamilyMember | null>(null);
    const featureDisabled = ref(false);
    const loading = ref(false);
    const loaded = ref(false);

    const firstGroup = computed<FamilyGroup | null>(() => groups.value[0] || null);
    const activeMembers = computed<FamilyMember[]>(() => members.value.filter(member => member.isActive));
    const pendingInvitations = computed<FamilyInvitation[]>(() => invitations.value.filter(item => item.isPending));
    const handledInvitations = computed<FamilyInvitation[]>(() => invitations.value.filter(item => !item.isPending));

    const canManage = computed<boolean>(() => !!myMember.value && myMember.value.canManage);
    const isOwner = computed<boolean>(() => !!myMember.value && myMember.value.isOwner);

    function assertSuccess(response: { data?: { success?: boolean, errorCode?: number, errorMessage?: string } }, fallback: string): void {
        if (!response.data?.success) {
            throw { message: response.data?.errorMessage || fallback };
        }
    }

    async function loadGroups(): Promise<void> {
        const response = await services.listFamilyGroups();
        if (!response.data?.success) throw { message: '无法读取家庭列表' };
        groups.value = (response.data.result ?? []).map(info => FamilyGroup.of(info));
    }

    async function loadMembers(familyId: string): Promise<void> {
        const [membersResponse, meResponse] = await Promise.all([
            services.listFamilyMembers({ familyId: familyId }),
            services.getMyFamilyMember({ familyId: familyId })
        ]);
        assertSuccess(membersResponse, '无法读取家庭成员');
        members.value = (membersResponse.data?.result ?? []).map(info => FamilyMember.of(info));

        if (meResponse.data?.success && meResponse.data.result) {
            myMember.value = FamilyMember.of(meResponse.data.result);
        } else {
            myMember.value = null;
        }
    }

    async function loadInvitations(familyId: string): Promise<void> {
        if (!canManage.value) {
            invitations.value = [];
            return;
        }
        const response = await services.listFamilyInvitations({ familyId: familyId });
        assertSuccess(response, '无法读取邀请记录');
        invitations.value = (response.data?.result ?? []).map(info => FamilyInvitation.of(info));
    }

    async function load(): Promise<void> {
        loading.value = true;
        try {
            try {
                await loadGroups();
                featureDisabled.value = false;
            } catch (error) {
                const status = (error as { response?: { status?: number } })?.response?.status;
                if (status === 400 || status === 403 || status === 404) {
                    featureDisabled.value = true;
                    groups.value = [];
                    return;
                }
                throw error;
            }

            const group = firstGroup.value;
            if (group) {
                await loadMembers(group.id);
                await loadInvitations(group.id);
            } else {
                members.value = [];
                invitations.value = [];
                myMember.value = null;
            }
            loaded.value = true;
        } finally {
            loading.value = false;
        }
    }

    async function createGroup(name: string, comment: string): Promise<void> {
        const response = await services.createFamilyGroup({ name: name, comment: comment });
        assertSuccess(response, '无法创建家庭');
        await load();
    }

    async function modifyGroup(id: string, name: string, comment: string): Promise<void> {
        const response = await services.modifyFamilyGroup({ id: id, name: name, comment: comment });
        assertSuccess(response, '无法保存家庭信息');
        await load();
    }

    async function deleteGroup(id: string): Promise<void> {
        const response = await services.deleteFamilyGroup({ id: id });
        assertSuccess(response, '无法解散家庭');
        await load();
    }

    async function changeMemberRole(familyId: string, memberId: string, role: number): Promise<void> {
        const response = await services.changeFamilyMemberRole({ familyId: familyId, memberId: memberId, role: role });
        assertSuccess(response, '无法调整成员权限');
        await loadMembers(familyId);
    }

    async function removeMember(familyId: string, memberId: string): Promise<void> {
        const response = await services.removeFamilyMember({ familyId: familyId, memberId: memberId });
        assertSuccess(response, '无法移除成员');
        await loadMembers(familyId);
    }

    async function leaveFamily(familyId: string): Promise<void> {
        const response = await services.leaveFamily({ familyId: familyId });
        assertSuccess(response, '无法退出家庭');
        await load();
    }

    async function createInvitation(familyId: string, inviteeName: string, role: number): Promise<FamilyInvitation> {
        const response = await services.createFamilyInvitation({ familyId: familyId, inviteeName: inviteeName, role: role });
        assertSuccess(response, '无法生成邀请');
        const invitation = FamilyInvitation.of(response.data!.result!);
        await loadInvitations(familyId);
        return invitation;
    }

    async function revokeInvitation(familyId: string, invitationId: string): Promise<void> {
        const response = await services.revokeFamilyInvitation({ familyId: familyId, invitationId: invitationId });
        assertSuccess(response, '无法撤销邀请');
        await loadInvitations(familyId);
    }

    async function acceptInvitation(token: string): Promise<void> {
        const response = await services.acceptFamilyInvitation({ token: token });
        assertSuccess(response, '邀请无效或已被使用');
        await load();
    }

    function reset(): void {
        groups.value = [];
        members.value = [];
        invitations.value = [];
        myMember.value = null;
        featureDisabled.value = false;
    }

    return {
        groups,
        members,
        activeMembers,
        invitations,
        pendingInvitations,
        handledInvitations,
        myMember,
        firstGroup,
        featureDisabled,
        loading,
        loaded,
        canManage,
        isOwner,
        load,
        loadMembers,
        loadInvitations,
        createGroup,
        modifyGroup,
        deleteGroup,
        changeMemberRole,
        removeMember,
        leaveFamily,
        createInvitation,
        revokeInvitation,
        acceptInvitation,
        reset
    };
});
