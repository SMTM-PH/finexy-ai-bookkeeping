<template>
    <f7-page ptr @ptr:refresh="refresh">
        <f7-navbar title="账本" back-link="返回"></f7-navbar>

        <f7-block v-if="error" class="text-color-red" role="alert">{{ error }}</f7-block>
        <f7-block-title>我的账本</f7-block-title>
        <f7-list strong inset dividers>
            <f7-list-item v-for="ledger in ledgers.allLedgers" :key="ledger.id"
                          :title="ledger.name" :footer="ledger.comment || (ledger.id === '0' ? '默认个人账本' : '独立账本')"
                          :after="ledger.id === selectedId ? '当前' : ''" link="#"
                          @click="selectLedger(ledger.id)"></f7-list-item>
        </f7-list>

        <f7-block-title>创建账本</f7-block-title>
        <f7-list strong inset>
            <f7-list-input label="账本名称" type="text" maxlength="64" v-model:value="createName"></f7-list-input>
            <f7-list-input label="描述" type="text" maxlength="255" v-model:value="createComment"></f7-list-input>
            <f7-list-button :class="{ disabled: busy || !createName.trim() }" @click="createLedger">创建并管理</f7-list-button>
        </f7-list>

        <f7-block-title>用邀请码加入</f7-block-title>
        <f7-list strong inset>
            <f7-list-input label="邀请码" type="text" maxlength="64" v-model:value="token"></f7-list-input>
            <f7-list-button :class="{ disabled: busy || !token.trim() }" @click="previewToken">查看邀请</f7-list-button>
        </f7-list>
        <f7-card v-if="invitationPreview" class="ledger-card">
            <f7-card-header>{{ invitationPreview.ledger.name }}</f7-card-header>
            <f7-card-content>
                <p>{{ invitationPreview.inviterNickname || '账本所有者' }} 邀请你成为{{ roleLabel(invitationPreview.role) }}。</p>
                <p class="text-color-gray">邀请备注：{{ invitationPreview.inviteeName }}</p>
            </f7-card-content>
            <f7-card-footer><f7-button fill :disabled="busy" @click="acceptToken">确认加入</f7-button></f7-card-footer>
        </f7-card>

        <template v-if="selectedId !== '0'">
            <f7-block-title>账本概览</f7-block-title>
            <f7-card v-if="overview" class="ledger-card">
                <f7-card-content>
                    <strong>{{ overview.ledgerName }}</strong>
                    <p>账户 {{ overview.accountCount }} · 流水 {{ overview.transactionCount }} · 目标 {{ overview.savingsGoalCount }} · 成员 {{ overview.activeMemberCount }}</p>
                    <p v-if="overview.balances.length">{{ overview.balances.map(item => `${item.currency} ${(item.balance / 100).toFixed(2)}`).join(' · ') }}</p>
                </f7-card-content>
            </f7-card>

            <f7-block-title>成员</f7-block-title>
            <f7-list strong inset dividers>
                <f7-list-item v-for="member in ledgers.members.filter(item => item.status === 1)" :key="member.id"
                              :title="member.nickname || `成员 ${member.uid}`" :after="roleLabel(member.role)"></f7-list-item>
            </f7-list>

            <template v-if="canManage">
                <f7-block-title>邀请成员</f7-block-title>
                <f7-list strong inset>
                    <f7-list-input label="邀请备注" type="text" maxlength="64" v-model:value="inviteName"></f7-list-input>
                    <f7-list-item title="权限" smart-select :smart-select-params="{ openIn: 'sheet' }">
                        <select v-model.number="inviteRole"><option :value="3">普通成员</option><option :value="4">只读成员</option></select>
                    </f7-list-item>
                    <f7-list-button :class="{ disabled: busy || !inviteName.trim() }" @click="inviteMember">生成邀请码</f7-list-button>
                </f7-list>
                <f7-card v-if="createdToken" class="ledger-card"><f7-card-content><small>一次有效，请发送给对方</small><p class="token">{{ createdToken }}</p></f7-card-content></f7-card>
            </template>
        </template>
    </f7-page>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { LedgerInvitationPreview, LedgerOverview } from '@/models/ledger.ts';
import { useLedgersStore } from '@/stores/ledger.ts';
import { useI18nUIComponents } from '@/lib/ui/mobile.ts';

const ledgers = useLedgersStore();
const { showToast } = useI18nUIComponents();
const selectedId = ref('0');
const busy = ref(false); const error = ref('');
const createName = ref(''); const createComment = ref('');
const token = ref(''); const invitationPreview = ref<LedgerInvitationPreview>();
const overview = ref<LedgerOverview>();
const inviteName = ref(''); const inviteRole = ref(3); const createdToken = ref('');
const me = computed(() => ledgers.members.find(item => item.isCurrentUser && item.status === 1));
const canManage = computed(() => me.value?.role === 1 || me.value?.role === 2);

function messageOf(reason: unknown): string { return reason instanceof Error ? reason.message : String((reason as { message?: unknown })?.message || reason); }
function roleLabel(role: number): string { return role === 1 ? '所有者' : role === 2 ? '管理员' : role === 4 ? '只读成员' : '普通成员'; }
async function run(action: () => Promise<void>): Promise<void> {
    if (busy.value) return; busy.value = true; error.value = '';
    try { await action(); } catch (reason) { error.value = messageOf(reason); showToast(error.value); } finally { busy.value = false; }
}
async function loadSelected(id: string): Promise<void> {
    selectedId.value = id; createdToken.value = ''; overview.value = undefined;
    ledgers.select(id); overview.value = await ledgers.loadOverview(id); await ledgers.loadAccess(id);
}
async function selectLedger(id: string): Promise<void> { await run(async () => loadSelected(id)); }
async function refresh(done?: () => void): Promise<void> { await run(async () => { await ledgers.load(); await loadSelected(selectedId.value); }); done?.(); }
async function createLedger(): Promise<void> { await run(async () => { const ledger = await ledgers.createLedger(createName.value, createComment.value); createName.value = ''; createComment.value = ''; await loadSelected(ledger.id); }); }
async function previewToken(): Promise<void> { await run(async () => { invitationPreview.value = await ledgers.previewInvitation(token.value); }); }
async function acceptToken(): Promise<void> { await run(async () => { const ledger = await ledgers.accept(token.value); invitationPreview.value = undefined; token.value = ''; await loadSelected(ledger.id); }); }
async function inviteMember(): Promise<void> { await run(async () => { createdToken.value = (await ledgers.invite(inviteName.value, inviteRole.value, selectedId.value)).token; inviteName.value = ''; await ledgers.loadAccess(selectedId.value); }); }
onMounted(async () => { await run(async () => { await ledgers.load(); selectedId.value = ledgers.selectedLedgerId; await loadSelected(selectedId.value); }); });
</script>

<style scoped>
.ledger-card p { margin: 8px 0 0; } .token { overflow-wrap: anywhere; font-family: ui-monospace, monospace; }
</style>
