<template>
    <svg class="account-card-artwork" viewBox="0 0 320 180" fill="none" aria-hidden="true" :data-pattern="artwork.name">
        <defs><linearGradient :id="gradientId" x1="24" y1="154" x2="288" y2="20" gradientUnits="userSpaceOnUse"><stop stop-color="var(--holo-ice)" /><stop offset=".55" stop-color="var(--holo-mint)" /><stop offset="1" stop-color="var(--holo-gold)" /></linearGradient></defs>
        <g :stroke="`url(#${gradientId})`" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
            <path v-for="(path, index) in artwork.paths" :key="index" :d="path" />
        </g>
        <g stroke="var(--holo-paper)" opacity=".5"><path d="M18 18H30M24 12V24M290 156H302M296 150V162" /><circle cx="296" cy="20" r="2" /></g>
    </svg>
</template>

<script setup lang="ts">
import { computed, useId } from 'vue';
import { AccountCategory } from '@/core/account.ts';
const props = defineProps<{ category: number }>();
const gradientId = `card-art-${useId()}`;
// Decorative geometry follows the actual account category, never its balance or name.
const patterns: Record<number, { name: string; paths: string[] }> = {
    [AccountCategory.CheckingAccount.type]: { name: 'vault', paths: [
        ...Array.from({ length: 6 }, (_, n) => `M${36+n*12} 162V${70-n*6}Q160 ${-28+n*8} ${284-n*12} ${70-n*6}V162`),
        'M20 162H300M52 140H268M104 76H216M112 76V128M136 76V128M160 76V128M184 76V128M208 76V128M104 128H216'
    ] },
    [AccountCategory.SavingsAccount.type]: { name: 'reserve', paths: [
        ...Array.from({ length: 7 }, (_, n) => `M20 ${48+n*14}Q88 ${-15+n*14} 160 ${48+n*14}T300 ${48+n*14}`),
        'M196 68A36 36 0 1 0 124 68A36 36 0 1 0 196 68M188 68A28 28 0 1 0 132 68A28 28 0 1 0 188 68M148 68H172M160 56V80'
    ] },
    [AccountCategory.CreditCard.type]: { name: 'credit', paths: [
        ...Array.from({ length: 5 }, (_, n) => `M${38+n*14} ${55+n*12}L${182+n*14} ${20+n*12}Q${198+n*14} ${16+n*12} ${202+n*14} ${32+n*12}L${216+n*14} ${96+n*12}L${72+n*14} ${131+n*12}Q${56+n*14} ${135+n*12} ${52+n*14} ${119+n*12}Z`),
        'M68 94L110 85M118 82L141 77M246 30A22 22 0 1 0 246 74A22 22 0 1 0 246 30M270 30A22 22 0 1 0 270 74A22 22 0 1 0 270 30'
    ] },
    [AccountCategory.VirtualAccount.type]: { name: 'digital', paths: [
        'M38 42L108 24L174 60L246 30L282 108L212 154L126 128L42 150ZM38 42L126 128L174 60L212 154M108 24L126 128L282 108M42 150L174 60L246 30',
        ...[[38,42],[108,24],[174,60],[246,30],[282,108],[212,154],[126,128],[42,150]].map(([x,y]) => `M${x!+5} ${y}A5 5 0 1 0 ${x!-5} ${y}A5 5 0 1 0 ${x!+5} ${y}`),
        'M196 60A22 22 0 1 0 152 60A22 22 0 1 0 196 60'
    ] },
    [AccountCategory.InvestmentAccount.type]: { name: 'investment', paths: [
        ...Array.from({ length: 6 }, (_, n) => `M24 ${160-n*7}L76 ${133-n*7}L126 ${144-n*7}L186 ${78-n*7}L240 ${96-n*7}L292 ${36-n*4}`),
        'M24 20V164H300M76 156V166M126 156V166M186 156V166M240 156V166'
    ] },
    [AccountCategory.CertificateOfDeposit.type]: { name: 'deposit', paths: [
        ...Array.from({ length: 5 }, (_, n) => `M${112-n*10} ${134-n*19}A${48+n*10} 22 0 1 0 ${208+n*10} ${134-n*19}A${48+n*10} 22 0 1 0 ${112-n*10} ${134-n*19}`),
        'M62 39V115M258 39V115M112 18H208M160 6V52M148 40L160 52L172 40'
    ] },
    [AccountCategory.DebtAccount.type]: { name: 'debt', paths: [
        ...Array.from({ length: 6 }, (_, n) => `M${30+n*16} 154V${124-n*14}H${106+n*16}V${72-n*8}H${182+n*16}V${34+n*3}H290`),
        'M24 166H294M36 30H80M66 20L80 30L66 40'
    ] },
    [AccountCategory.Receivables.type]: { name: 'receivable', paths: [
        ...Array.from({ length: 5 }, (_, n) => `M${26+n*11} ${30+n*8}H${264-n*11}Q${290-n*11} ${30+n*8} ${290-n*11} ${60+n*8}V${140-n*8}H${54+n*11}Q${26+n*11} ${140-n*8} ${26+n*11} ${110-n*8}Z`),
        'M110 85H206M186 65L206 85L186 105'
    ] },
    [AccountCategory.Cash.type]: { name: 'cash', paths: [
        ...Array.from({ length: 6 }, (_, n) => `M${76+n*24} ${66-n*8}A${24+n*8} ${24+n*8} 0 1 0 ${76+n*24} ${114+n*8}A${24+n*8} ${24+n*8} 0 1 0 ${76+n*24} ${66-n*8}`),
        'M22 90H298M160 18V162'
    ] }
};
const artwork = computed(() => patterns[props.category] || patterns[AccountCategory.Cash.type]!);
</script>

<style scoped>
.account-card-artwork { position: absolute; inset: 0; width: 100%; height: 100%; opacity: .85; pointer-events: none; }
</style>
