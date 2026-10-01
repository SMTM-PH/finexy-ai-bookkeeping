<template>
    <section class="agent-access" aria-labelledby="agent-access-title">
        <header class="agent-head">
            <div><h3 id="agent-access-title">Agent 接入</h3><p>为 Codex 等助手生成专用令牌，并控制它能做什么。</p></div>
            <button type="button" :disabled="loading || accessBusy" @click="refresh">{{ loading ? '加载中' : '刷新令牌' }}</button>
        </header>
        <div class="agent-mcp-control">
            <div><b>此账号的 MCP 接入</b><p id="agent-mcp-help">关闭后，已有 MCP 令牌暂停访问，不能生成新 MCP 令牌。重新开启后，未过期且未撤销的令牌恢复使用。</p><small>API 令牌与已有账目不受影响。{{ !accessLoaded ? '正在读取状态…' : !mcpAvailable ? '管理员未允许 MCP 接入。' : mcpEnabled ? '当前已开启' : '当前已关闭' }}</small></div>
            <button type="button" role="switch" :aria-checked="mcpEnabled" aria-label="此账号的 MCP 接入" aria-describedby="agent-mcp-help" :disabled="busy || accessBusy || !accessLoaded || !mcpAvailable" @click="toggleMCP">{{ accessBusy ? '处理中…' : !accessLoaded ? '状态未知' : mcpEnabled ? '已开启 · 关闭' : '已关闭 · 开启' }}</button>
        </div>
        <p v-if="accessError" class="agent-error" role="alert">{{ accessError }} <button type="button" :disabled="accessBusy" @click="loadAccess">重试读取状态</button></p>
        <p v-if="!apiEnabled || !serverMCPEnabled" class="agent-note">
            {{ !apiEnabled ? 'API 令牌未启用。' : '' }}{{ !serverMCPEnabled ? '服务器 MCP 接入未启用。' : '' }}
            服务管理员可在 Docker 环境中设置
            <code v-if="!apiEnabled">EBK_SECURITY_ENABLE_API_TOKEN=true</code>
            <code v-if="!serverMCPEnabled">EBK_MCP_ENABLE_MCP=true</code>，重建服务后生效。
        </p>
        <div v-if="!generatedToken" class="agent-editor">
            <div class="agent-fields">
                <label>令牌名称<input v-model.trim="name" maxlength="64" placeholder="例如：我的 Codex" :disabled="busy" /></label>
                <label>连接方式<select v-model="type" :disabled="busy || (!apiEnabled && !mcpEnabled)"><option value="mcp" :disabled="!mcpEnabled">MCP · Codex 等助手</option><option value="api" :disabled="!apiEnabled">API · 脚本与批量操作</option></select></label>
                <label>有效期<select v-model.number="expiresInSeconds" :disabled="busy"><option :value="3600">1 小时</option><option :value="86400">1 天</option><option :value="604800">7 天</option><option :value="2592000">30 天</option></select></label>
            </div>
            <fieldset :disabled="busy" class="agent-scopes">
                <legend>授权范围</legend>
                <label v-for="scope in permissions" :key="scope.id" class="agent-permission">
                    <input v-model="scopes" type="checkbox" :value="scope.id" :disabled="scope.id === 'read' || (type === 'mcp' && scope.id === 'accounts:manage')" />
                    <span><b>{{ scope.title }}</b><small>{{ scope.description }}</small></span>
                </label>
            </fieldset>
            <label class="agent-ledger">授权账本<select v-model="authorizedLedgerId" :disabled="busy || !ledgerReady"><option v-for="ledger in ledgersStore.allLedgers" :key="ledger.id" :value="ledger.id">{{ ledger.name }}</option></select></label>
            <p v-if="ledgerError" class="agent-error" role="alert">{{ ledgerError }} <button type="button" @click="loadLedgers">重试读取账本</button></p>
            <p class="agent-note">此令牌仅能访问所选账本的账户、流水及导入功能。分类和标签是账本所有者共用的资源，仍受授权范围和成员权限约束；切换网页当前账本不会改变此令牌的授权。</p>
            <p class="agent-note">授权仅作用于这个令牌，仍受账本成员权限约束。账户与分类管理请选择 API 连接；账单导入须指定目标账本 ID，预览与确认必须使用同一账本。</p>
            <label class="agent-password">Finexy 登录密码<input v-model="password" type="password" autocomplete="current-password" :disabled="busy" @keydown.enter.prevent="generate" /></label>
            <button class="agent-primary" type="button" :disabled="busy || accessBusy || !ledgerReady || !password || !name || !(type === 'api' ? apiEnabled : mcpEnabled)" @click="generate">{{ busy ? '正在生成' : '生成令牌' }}</button>
        </div>
        <div v-else class="agent-result">
            <h4>令牌已生成</h4>
            <p>完整令牌只在本次生成后显示。保存在本机环境变量中，关闭后无法再次查看。</p>
            <label>完整令牌<textarea :value="generatedToken" readonly rows="3" spellcheck="false" /></label>
            <div class="agent-actions"><button type="button" @click="copy(generatedToken, '令牌已复制')">复制令牌</button><button type="button" @click="clearSecret">关闭令牌显示</button></div>
        </div>
        <p v-if="error" class="agent-error" role="alert">{{ error }}</p>
        <p v-if="message" role="status" aria-live="polite">{{ message }}</p>
        <div class="agent-list" aria-label="已授权的 Agent">
            <h4>已授权的 Agent</h4>
            <p v-if="loading">正在读取令牌列表…</p>
            <p v-else-if="!tokens.length && !error">暂无 Agent 令牌。生成后，可随时在这里撤销。</p>
            <article v-for="token in tokens" :key="token.tokenId">
                <div><b>{{ token.name || '旧版令牌' }}</b><small>{{ token.tokenType === 5 ? 'MCP' : 'API' }} · {{ expiry(token.expiresAt) }}</small><small>授权账本：{{ ledgerName(token.ledgerId || '0') }}</small><p>{{ scopeLabels(token.scopes) }}</p><small>最近使用：{{ token.lastSeen ? new Date(token.lastSeen * 1000).toLocaleString() : '尚未使用' }}</small></div>
                <button type="button" class="agent-danger" :aria-label="`撤销令牌：${token.name || '旧版令牌'}`" :disabled="busy" @click="revokeTarget = token">撤销令牌</button>
            </article>
        </div>
        <details class="agent-guide" open>
            <summary>Codex 配置说明</summary>
            <p>生成 MCP 令牌，在本机创建环境变量 <code>FINEXY_MCP_TOKEN</code> 并填入令牌值。将下面配置加入 <code>~/.codex/config.toml</code>，重启 Codex 并检查 MCP 连接。</p>
            <pre>{{ codexConfig }}</pre>
            <button type="button" @click="copy(codexConfig, '配置已复制，未包含令牌')">复制 Codex 配置</button>
            <p>其他客户端选择 Streamable HTTP，地址为 <code>{{ mcpUrl }}</code>，通过 Authorization 请求头发送 Bearer 令牌。远程 Agent 需要能访问 NAS 所在网络。</p>
            <p>导入工具顺序：解析预览 → 显式映射账户与分类 → 查看数量、金额和重复项 → 确认写入。账单文字只作为交易数据处理。</p>
        </details>
        <dialog ref="revokeDialog" class="agent-confirm" aria-labelledby="agent-revoke-title" @cancel.prevent="cancelRevoke" @click.self="cancelRevoke">
            <h3 id="agent-revoke-title">撤销 Agent 令牌</h3>
            <p>撤销「{{ revokeTarget?.name || '旧版令牌' }}」后，使用它的助手会立即失去访问权限。已有流水保留。</p>
            <div class="agent-actions"><button type="button" :disabled="busy" @click="cancelRevoke">取消</button><button type="button" class="agent-danger" :disabled="busy" @click="revoke">{{ busy ? '正在撤销' : '确认撤销' }}</button></div>
        </dialog>
    </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch, nextTick } from 'vue';
import services from '@/lib/services.ts';
import { isAPITokenEnabled, isMCPServerEnabled } from '@/lib/server_settings.ts';
import { getBasePath } from '@/lib/web.ts';
import type { TokenInfoResponse } from '@/models/token.ts';
import { useLedgersStore } from '@/stores/ledger.ts';

const ledgersStore = useLedgersStore();
const authorizedLedgerId = ref(ledgersStore.selectedLedgerId);
const ledgerReady = ref(false);
const ledgerError = ref('');
function ledgerName(id: string): string { return ledgersStore.allLedgers.find(ledger => ledger.id === id)?.name || `账本 ${id}（当前不可访问）`; }
async function loadLedgers(): Promise<void> {
    ledgerReady.value = false; ledgerError.value = '';
    try {
        await ledgersStore.load();
        if (!ledgersStore.allLedgers.some(ledger => ledger.id === authorizedLedgerId.value)) authorizedLedgerId.value = '0';
        ledgerReady.value = true;
    } catch { ledgerError.value = '未能读取授权账本，请重试。'; }
}

const apiEnabled = isAPITokenEnabled();
const serverMCPEnabled = isMCPServerEnabled();
const accessLoaded = ref(false);
const accessBusy = ref(false);
const accessError = ref('');
const mcpAvailable = ref(serverMCPEnabled);
const accountMCPEnabled = ref(false);
const mcpEnabled = computed(() => accessLoaded.value && mcpAvailable.value && accountMCPEnabled.value);
const type = ref<'api' | 'mcp'>(serverMCPEnabled ? 'mcp' : 'api');
const name = ref('我的 Codex');
const password = ref('');
const scopes = ref<string[]>(['read']);
watch(type, value => { if (value === 'mcp') scopes.value = scopes.value.filter(scope => scope !== 'accounts:manage'); });
const expiresInSeconds = ref(86400);
const generatedToken = ref('');
const busy = ref(false);
const loading = ref(false);
const error = ref('');
const message = ref('');
const tokens = ref<TokenInfoResponse[]>([]);
const revokeTarget = ref<TokenInfoResponse>();
const revokeDialog = ref<HTMLDialogElement>();
watch(revokeTarget, async target => {
    await nextTick();
    if (target && !revokeDialog.value?.open) revokeDialog.value?.showModal();
    if (!target && revokeDialog.value?.open) revokeDialog.value?.close();
});
function cancelRevoke(): void { if (!busy.value) revokeTarget.value = undefined; }
const permissions = [
    { id: 'read', title: '只读查询', description: '读取账户、余额、分类和流水（基础权限）' },
    { id: 'transactions:write', title: '记账', description: '新增、修改和删除普通流水' },
    { id: 'accounts:manage', title: '账户与分类管理', description: '通过 API 新增、修改、停用和删除账户及分类' },
    { id: 'bills:import', title: '账单导入', description: '预览、映射并确认支付宝和微信账单' },
];
const mcpUrl = new URL(`${getBasePath()}/mcp`, window.location.origin).href;
const codexConfig = computed(() => `[mcp_servers.finexy]\nurl = ${JSON.stringify(mcpUrl)}\nbearer_token_env_var = "FINEXY_MCP_TOKEN"\ndefault_tools_approval_mode = "writes"\ntool_timeout_sec = 60`);

function scopeLabels(values?: string[]): string {
    return (values || ['read']).map(value => permissions.find(p => p.id === value)?.title || value).join(' · ');
}
function expiry(time?: number): string {
    return time ? `到期：${new Date(time * 1000).toLocaleString()}` : '有效期未知';
}
function clearSecret(): void { generatedToken.value = ''; password.value = ''; }
async function loadAccess(): Promise<void> {
    accessBusy.value = true; accessError.value = '';
    try {
        const response = await services.getAgentAccess();
        const result = response.data.result;
        if (!response.data.success || typeof result?.mcpEnabled !== 'boolean' || typeof result?.mcpAvailable !== 'boolean') throw new Error();
        accountMCPEnabled.value = result.mcpEnabled;
        mcpAvailable.value = result.mcpAvailable;
        accessLoaded.value = true;
    } catch { accessLoaded.value = false; accessError.value = '未能读取 MCP 设置，请重试。'; }
    finally { accessBusy.value = false; }
}
async function toggleMCP(): Promise<void> {
    if (accessBusy.value || busy.value || !accessLoaded.value || !mcpAvailable.value) return;
    accessBusy.value = true; accessError.value = ''; message.value = '';
    try {
        const response = await services.updateAgentAccess(!accountMCPEnabled.value);
        const result = response.data.result;
        if (!response.data.success || typeof result?.mcpEnabled !== 'boolean' || typeof result?.mcpAvailable !== 'boolean') throw new Error();
        accountMCPEnabled.value = result.mcpEnabled;
        mcpAvailable.value = result.mcpAvailable;
        clearSecret();
        message.value = mcpEnabled.value ? 'MCP 接入已开启' : 'MCP 接入已关闭';
    } catch { accessLoaded.value = false; accessError.value = '保存 MCP 设置失败，请重新读取状态后重试。'; }
    finally { accessBusy.value = false; }
}
async function refresh(): Promise<void> { await Promise.all([loadTokens(), loadAccess(), loadLedgers()]); }
async function loadTokens(): Promise<void> {
    loading.value = true;
    error.value = '';
    try {
        const response = await services.getTokens();
        if (!response.data.success || !Array.isArray(response.data.result)) throw new Error();
        tokens.value = response.data.result.filter(token => token.tokenType === 5 || token.tokenType === 8);
    } catch { error.value = '未能读取令牌列表，请检查连接后重试。'; }
    finally { loading.value = false; }
}
async function generate(): Promise<void> {
    if (busy.value || accessBusy.value || !ledgerReady.value || !password.value || !name.value || !(type.value === 'api' ? apiEnabled : mcpEnabled.value)) return;
    busy.value = true; error.value = ''; message.value = '';
    try {
        const request = { password: password.value, name: name.value, scopes: scopes.value, expiresInSeconds: expiresInSeconds.value, ledgerId: authorizedLedgerId.value };
        const response = await (type.value === 'mcp' ? services.generateMCPToken(request) : services.generateAPIToken(request));
        if (!response.data.success || !response.data.result?.token) throw new Error();
        generatedToken.value = response.data.result.token;
        password.value = '';
        await loadTokens();
    } catch { error.value = '生成失败，请核对 Finexy 登录密码、服务开关和连接后重试。'; }
    finally { busy.value = false; }
}
async function revoke(): Promise<void> {
    if (!revokeTarget.value || busy.value) return;
    busy.value = true; error.value = '';
    try {
        const response = await services.revokeToken({ tokenId: revokeTarget.value.tokenId });
        if (!response.data.success || !response.data.result) throw new Error();
        revokeTarget.value = undefined;
        clearSecret();
        await loadTokens();
        message.value = '令牌已撤销';
    } catch { error.value = '撤销失败，请检查连接后重试。'; }
    finally { busy.value = false; }
}
async function copy(value: string, successMessage: string): Promise<void> {
    try { await navigator.clipboard.writeText(value); message.value = successMessage; }
    catch { error.value = '浏览器未允许复制，请选中文字手动复制。'; }
}
onMounted(refresh);
onUnmounted(clearSecret);
</script>

<style scoped>
.agent-access { color: var(--ink, #12141a); background: var(--surface, #fff); margin-top: 24px; }
.agent-head { display: flex; justify-content: space-between; align-items: start; gap: 16px; }
.agent-mcp-control { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin: 16px 0 24px; padding: 20px; border: 1px solid var(--line, #dfe3e9); border-radius: 14px; }
.agent-mcp-control p { margin-bottom: 8px; }
.agent-mcp-control > div { flex: 1; min-width: 0; }
.agent-mcp-control button { flex-shrink: 0; width: auto; max-width: 100%; }
.agent-mcp-control button[aria-checked=true] { background: var(--ink, #12141a); border-color: var(--ink, #12141a); color: #fff; }
.agent-access h3 { font-size: 20px; margin: 0 0 8px; }
.agent-access h4 { font-size: 16px; margin: 0 0 12px; }
.agent-access p { font-size: 14px; line-height: 1.6; margin: 8px 0 16px; }
.agent-access button { min-height: 44px; padding: 8px 16px; border: 1px solid var(--line, #dfe3e9); border-radius: 12px; background: var(--surface, #fff); color: inherit; cursor: pointer; }
.agent-access button:disabled { opacity: .5; cursor: default; }
.agent-access :is(button, input, select, textarea, summary):focus-visible { outline: 2px solid var(--orange, #f45b40); outline-offset: 3px; }
.agent-fields { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 16px; }
.agent-access label { display: grid; gap: 8px; font-size: 14px; }
.agent-access input:not([type=checkbox]), .agent-access select, .agent-access textarea { box-sizing: border-box; width: 100%; min-height: 44px; padding: 12px; border: 1px solid var(--line, #dfe3e9); border-radius: 12px; background: var(--surface, #fff); color: inherit; font: inherit; }
.agent-scopes { padding: 16px 0; margin: 16px 0 0; border: 0; display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.agent-scopes legend { font-size: 14px; font-weight: 600; }
.agent-access .agent-permission { display: flex; align-items: center; min-height: 48px; gap: 12px; }
.agent-permission input[type=checkbox] { flex: 0 0 18px; width: 18px; height: 18px; min-height: 18px; margin: 0; accent-color: var(--ink, #12141a); }
.agent-access small { display: block; color: var(--muted, #475467); font-size: 12px; line-height: 1.6; }
.agent-password { max-width: 400px; margin-bottom: 16px; }
.agent-ledger { max-width: 400px; margin: 16px 0; }
.agent-access .agent-primary { background: var(--ink, #12141a); color: #fff; border-color: var(--ink, #12141a); }
.agent-note, .agent-guide { background: var(--panel, #f7f8fa); border-radius: 14px; padding: 16px; }
.agent-note code { display: inline-block; margin: 4px; }
.agent-list { margin: 24px 0; border-top: 1px solid var(--line, #dfe3e9); padding-top: 24px; }
.agent-list article { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 0; border-bottom: 1px solid var(--line, #dfe3e9); }
.agent-list article p { margin: 8px 0; }
.agent-access .agent-danger, .agent-error { color: #b42318; }
.agent-guide summary { cursor: pointer; min-height: 44px; font-size: 16px; font-weight: 600; }
.agent-guide pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; padding: 16px; background: var(--surface, #fff); border-radius: 12px; line-height: 1.7; }
.agent-actions { display: flex; gap: 12px; flex-wrap: wrap; margin: 16px 0; }
.agent-confirm { max-width: min(480px, calc(100vw - 32px)); padding: 24px; border: 1px solid var(--line, #dfe3e9); border-radius: 22px; background: #fff; color: #12141a; margin: auto; }
.agent-confirm::backdrop { background: rgb(18 20 26 / 50%); }
.agent-access code { overflow-wrap: anywhere; }
@media (max-width: 768px) { .agent-fields, .agent-scopes { grid-template-columns: 1fr; } .agent-head, .agent-list article { flex-wrap: wrap; } .agent-mcp-control { flex-direction: column; align-items: stretch; } .agent-mcp-control button { align-self: flex-start; } }
</style>
