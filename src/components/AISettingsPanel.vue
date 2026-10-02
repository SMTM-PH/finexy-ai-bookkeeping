<template>
    <section class="ai-settings" aria-label="AI 设置">
        <section class="ai-service" aria-labelledby="ai-service-title">
            <header class="ai-service-head">
                <div><h3 id="ai-service-title">模型与用量</h3><p>查看当前模型、服务余额和你在 Finexy 中的调用情况。</p></div>
                <button type="button" :disabled="loading" @click="refresh">{{ loading ? '刷新中…' : '刷新用量' }}</button>
            </header>
            <p v-if="loading && !configuration" role="status">正在读取 AI 服务…</p>
            <p v-if="configError" class="ai-error" role="alert">{{ configError }}</p>
            <dl v-if="configuration" class="ai-service-details">
                <div><dt>服务提供方</dt><dd>{{ providerName }}</dd></div>
                <div><dt>当前模型</dt><dd>{{ configuration.modelId || '尚未配置' }}</dd></div>
                <div><dt>文本记账</dt><dd>{{ configuration.enabled ? '已开启' : '已关闭' }}</dd></div>
                <div><dt>图片识别</dt><dd>{{ configuration.imageEnabled ? '已开启' : '已关闭' }}</dd></div>
                <div><dt>推理模式</dt><dd>{{ thinkingName }}</dd></div>
                <div><dt>请求超时</dt><dd>{{ configuration.requestTimeout / 1000 }} 秒</dd></div>
                <div class="ai-wide"><dt>API 地址</dt><dd>{{ configuration.baseUrl || '尚未配置' }}</dd></div>
                <div><dt>API 密钥</dt><dd>{{ configuration.apiKeyConfigured ? '已配置，仅服务端保存' : '尚未配置' }}</dd></div>
            </dl>
            <a v-if="configuration?.editable" class="ai-link" :href="configurationUrl">修改服务端模型配置</a>
            <section class="ai-balance" aria-label="服务账户余额">
                <h4>DeepSeek 账户余额</h4>
                <p v-if="configuration && !configuration.editable">余额属于服务器共用的 API 账户，仅服务器所有者可查看。你的调用用量仍可在下方查看。</p>
                <p v-else-if="balanceLoading" role="status">正在查询官方余额…</p>
                <template v-else-if="balance?.status === 'available'">
                    <p class="ai-balance-status">{{ balance.available ? '余额可用' : '余额不足，模型调用可能失败' }}</p>
                    <dl v-for="item in balance.balances" :key="item.currency" class="ai-balance-details">
                        <div><dt>可用余额 · {{ item.currency }}</dt><dd>{{ item.totalBalance }}</dd></div>
                        <div><dt>充值余额</dt><dd>{{ item.toppedUpBalance }} {{ item.currency }}</dd></div>
                        <div><dt>赠送余额</dt><dd>{{ item.grantedBalance }} {{ item.currency }}</dd></div>
                    </dl>
                    <small>官方余额查询时间：{{ date(balance.updatedAt) }}。这是 API 账户整体余额，可能被其他应用共同使用。</small>
                </template>
                <p v-else>{{ balanceMessage }}</p>
                <a href="https://platform.deepseek.com/usage" target="_blank" rel="noopener noreferrer" class="ai-link">查看 DeepSeek 官方账单与费用</a>
            </section>
            <section class="ai-metering" aria-label="我的 Finexy 调用用量">
                <div class="ai-usage-head"><h4>我的 Finexy 调用用量</h4><label>统计范围<select v-model="period"><option value="today">今天</option><option value="month">本月</option><option value="all">累计</option></select></label></div>
                <p v-if="usageError" class="ai-error" role="alert">{{ usageError }}</p>
                <dl v-if="summary" class="ai-usage-details">
                    <div><dt>请求次数</dt><dd>{{ number(summary.requests) }}</dd></div>
                    <div><dt>输入 token</dt><dd>{{ number(summary.inputTokens) }}</dd></div>
                    <div><dt>输出 token</dt><dd>{{ number(summary.outputTokens) }}</dd></div>
                    <div><dt>总 token</dt><dd>{{ number(summary.totalTokens) }}</dd></div>
                    <div><dt>输入缓存命中</dt><dd>{{ summary.cacheMeteredRequests ? number(summary.cachedTokens) : '未提供' }}</dd></div>
                    <div><dt>推理 token</dt><dd>{{ summary.reasoningMeteredRequests ? number(summary.reasoningTokens) : '未提供' }}</dd></div>
                    <div><dt>失败请求</dt><dd>{{ number(summary.failedRequests) }}</dd></div>
                    <div><dt>平均响应时间</dt><dd>{{ summary.requests ? (summary.durationMs / summary.requests / 1000).toFixed(2) + ' 秒' : '暂无调用' }}</dd></div>
                </dl>
                <p v-if="usage && !usage.all.requests" class="ai-note">尚无调用记录。使用 AI 文本记账、图片识别或报告后，用量会在这里累计。</p>
                <p v-if="summary?.unmeteredRequests" class="ai-note">{{ summary.unmeteredRequests }} 次请求未返回有效 token 用量；已计入请求次数，未估算 token。</p>
                <p class="ai-note">仅统计本次升级后通过 Finexy 发起的当前账号调用，不包含历史调用、其他账号或其他应用。输入、输出及缓存以接口返回为准；缓存属于输入 token，推理属于输出 token，不重复相加。缓存与推理仅累计提供对应字段的请求，未提供时不估算。不根据余额变化推算费用。</p>
                <small v-if="usage">用量更新时间：{{ date(usage.updatedAt) }} · 按当前设备时区统计{{ usage.all.firstRequestTime ? ' · 首次记录：' + date(usage.all.firstRequestTime) : '' }}</small>
                <details v-if="usage?.recent.length" class="ai-recent"><summary>最近 {{ usage.recent.length }} 次调用</summary>
                    <ul><li v-for="(call, index) in usage.recent" :key="`${call.time}-${index}`"><div><b>{{ call.modelId || call.provider }}</b><small>{{ date(call.time) }} · {{ call.provider }}</small></div><div><span>{{ call.success ? '成功' : '失败' }} · {{ (call.durationMs / 1000).toFixed(2) }} 秒</span><small>{{ call.usageKnown ? number(call.totalTokens) + ' token（输入 ' + number(call.inputTokens) + ' / 输出 ' + number(call.outputTokens) + '）' : '未返回 token 用量' }}</small></div></li></ul>
                </details>
            </section>
        </section>
        <AgentAccessPanel />
    </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import AgentAccessPanel from '@/components/AgentAccessPanel.vue';
import services from '@/lib/services.ts';
import { getBasePath } from '@/lib/web.ts';
import type { AIConfigurationResponse } from '@/models/ai_configuration.ts';
import type { AIUsageResponse, AIBalanceResponse } from '@/models/ai_usage.ts';

const configuration = ref<AIConfigurationResponse>();
const usage = ref<AIUsageResponse>();
const balance = ref<AIBalanceResponse>();
const loading = ref(false);
const balanceLoading = ref(false);
const configError = ref('');
const usageError = ref('');
const period = ref<'today' | 'month' | 'all'>('month');
defineExpose({ refresh });
let active = true;
const configurationUrl = `${getBasePath()}/desktop#/app/settings?tab=aiSetting`;
const summary = computed(() => usage.value?.[period.value]);
const providerName = computed(() => {
    try { if (new URL(configuration.value?.baseUrl || '').hostname === 'api.deepseek.com') return 'DeepSeek'; } catch { /* Unconfigured URL. */ }
    return configuration.value?.provider === 'openai_compatible' ? 'OpenAI 兼容服务' : configuration.value?.provider || '尚未配置';
});
const thinkingName = computed(() => ({ '': '由模型决定', off: '关闭', on: '开启', low: '低', medium: '中', high: '高', xhigh: '极高' }[configuration.value?.enableThinking || ''] || '由模型决定'));
const balanceMessage = computed(() => {
    if (balance.value?.status === 'unsupported') return '当前接口不是 DeepSeek 官方服务，暂不支持查询余额。';
    if (balance.value?.status === 'not_configured') return '尚未配置 DeepSeek API 密钥。';
    return '暂未获取余额。可刷新重试，或在官方平台查看。';
});
function number(value: number): string { return value.toLocaleString('zh-CN'); }
function date(value: number): string { return new Date(value * 1000).toLocaleString('zh-CN'); }
async function refresh(): Promise<void> {
    if (loading.value) return;
    loading.value = true; configError.value = ''; usageError.value = ''; balance.value = undefined;
    const results = await Promise.allSettled([services.getAIConfiguration(), services.getAIUsage()]);
    if (!active) return;
    const config = results[0]; const meter = results[1];
    if (config.status === 'fulfilled' && config.value.data.success && config.value.data.result) configuration.value = config.value.data.result;
    else { configuration.value = undefined; configError.value = '未能读取模型配置，请刷新重试。'; }
    if (meter.status === 'fulfilled' && meter.value.data.success && meter.value.data.result) usage.value = meter.value.data.result;
    else { usage.value = undefined; usageError.value = '未能读取用量，请刷新重试。'; }
    if (configuration.value?.editable) {
        balanceLoading.value = true;
        try { const response = await services.getAIBalance(); if (active && response.data.success) balance.value = response.data.result; }
        catch { if (active) balance.value = undefined; }
        finally { if (active) balanceLoading.value = false; }
    }
    if (active) loading.value = false;
}
onMounted(refresh);
onUnmounted(() => { active = false; });
</script>

<style scoped>
.ai-settings { --ai-ink: var(--card-ink, #12141a); --ai-copy: var(--card-copy, #475467); --ai-surface: var(--surface, #fff); --ai-line: var(--line, #dfe3e9); color: var(--ai-ink); background: var(--ai-surface); font-family: var(--ledger-font-body, "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", system-ui, sans-serif); font-synthesis: none; font-size: 14px; line-height: 1.6; }
.ai-service :is(h3,h4,dd,b,button) { color: var(--ai-ink); -webkit-text-fill-color: currentColor; opacity: 1; font-family: inherit; }
.ai-service h3 { font-size: 20px; margin: 0 0 8px; font-weight: 700; }
.ai-service h4 { font-size: 16px; margin: 0 0 16px; font-weight: 700; }
.ai-service p { margin: 8px 0 16px; color: var(--ai-copy); font-size: 14px; }
.ai-service small { display: block; font-size: 13px; color: var(--ai-copy); line-height: 1.6; }
.ai-service-head, .ai-usage-head { display: flex; justify-content: space-between; align-items: start; gap: 16px; margin-bottom: 20px; }
.ai-service button, .ai-service select, .ai-link { min-height: 44px; font: inherit; }
.ai-service button, .ai-service select { padding: 8px 16px; border: 1px solid var(--ai-line); border-radius: 12px; background: var(--ai-surface); color: var(--ai-ink); }
.ai-service button { cursor: pointer; flex-shrink: 0; width: auto; max-width: 100%; }
.ai-service button:disabled { opacity: .6; cursor: default; }
.ai-service :is(button,select,summary,a):focus-visible { outline: 2px solid var(--orange, #f05537); outline-offset: 3px; }
.ai-service-details, .ai-balance-details, .ai-usage-details { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 20px 24px; margin: 0; }
.ai-service dt { color: var(--ai-copy); font-size: 13px; margin-bottom: 4px; }
.ai-service dd { margin: 0; font-size: 15px; font-weight: 600; overflow-wrap: anywhere; }
.ai-wide { grid-column: 1 / -1; }
.ai-balance, .ai-metering { border-top: 1px solid var(--ai-line); padding-top: 24px; margin-top: 24px; }
.ai-balance-details, .ai-usage-details { background: var(--panel, #f7f8fa); padding: 20px; border-radius: 14px; margin-bottom: 16px; }
.ai-usage-details { grid-template-columns: repeat(4,minmax(0,1fr)); }
.ai-balance-details dd, .ai-usage-details dd { font-family: var(--ledger-font-data, ui-monospace, monospace); font-variant-numeric: tabular-nums; font-size: 18px; }
.ai-usage-head h4 { margin: 8px 0; }
.ai-usage-head label { display: flex; align-items: center; gap: 12px; font-size: 14px; }
.ai-service .ai-link { display: inline-flex; align-items: center; color: var(--ai-ink); text-decoration: underline; margin-top: 8px; }
.ai-service .ai-error { color: var(--red, #b42318); }
.ai-recent { margin-top: 20px; }
.ai-recent summary { min-height: 44px; cursor: pointer; font-weight: 600; }
.ai-recent ul { margin: 0; padding: 0; list-style: none; }
.ai-recent li { display: flex; justify-content: space-between; gap: 16px; border-top: 1px solid var(--ai-line); padding: 16px 0; }
.ai-recent li > div { min-width: 0; overflow-wrap: anywhere; }
@media(max-width:1024px) { .ai-usage-details { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media(max-width:600px) { .ai-service-head, .ai-usage-head { flex-direction: column; } .ai-service-details, .ai-balance-details { grid-template-columns: 1fr; } .ai-recent li { flex-direction: column; gap: 8px; } }
</style>
