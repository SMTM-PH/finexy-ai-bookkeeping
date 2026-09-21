<template>
    <v-menu
        v-model="menuOpen"
        location="bottom end"
        :offset="8"
        :close-on-content-click="false"
        :disabled="disabled"
    >
        <template #activator="{ props: activatorProps }">
            <button
                v-bind="activatorProps"
                class="ledger-switch-trigger"
                :class="{ open: menuOpen }"
                type="button"
                :disabled="disabled"
                aria-label="切换当前账本"
            >
                <span class="ledger-switch-icon" aria-hidden="true">
                    <v-icon :icon="mdiBookOpenOutline" size="16" />
                </span>
                <span class="ledger-switch-copy">
                    <small>当前账本</small>
                    <strong>{{ selectedLedger?.name || "默认个人账本" }}</strong>
                </span>
                <v-icon
                    class="ledger-switch-chevron"
                    :class="{ open: menuOpen }"
                    :icon="mdiChevronDown"
                    size="17"
                    aria-hidden="true"
                />
            </button>
        </template>

        <section class="ledger-switch-menu" aria-label="选择账本">
            <header>
                <span>切换账本</span>
                <small>{{ ledgers.length }} 个可用账本</small>
            </header>
            <div class="ledger-switch-options" role="listbox" aria-label="可用账本">
                <button
                    v-for="ledger in ledgers"
                    :key="ledger.id"
                    class="ledger-switch-option"
                    :class="{ selected: ledger.id === selectedId }"
                    type="button"
                    role="option"
                    :aria-selected="ledger.id === selectedId"
                    @click="selectLedger(ledger.id)"
                >
                    <span class="ledger-option-icon" aria-hidden="true">
                        <v-icon :icon="mdiBookOpenOutline" size="17" />
                    </span>
                    <span class="ledger-option-copy">
                        <strong>{{ ledger.name }}</strong>
                        <small>{{ ledgerDescription(ledger) }}</small>
                    </span>
                    <span v-if="ledger.id === selectedId" class="ledger-option-check" aria-hidden="true">
                        <v-icon :icon="mdiCheck" size="16" />
                    </span>
                </button>
            </div>
        </section>
    </v-menu>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { mdiBookOpenOutline, mdiCheck, mdiChevronDown } from "@mdi/js";
import type { Ledger } from "@/models/ledger.ts";

const props = defineProps<{
    ledgers: Ledger[];
    selectedId: string;
    disabled?: boolean;
}>();

const emit = defineEmits<{
    select: [ledgerId: string];
}>();

const menuOpen = ref(false);
const selectedLedger = computed(() =>
    props.ledgers.find((ledger) => ledger.id === props.selectedId),
);

function ledgerDescription(ledger: Ledger): string {
    if (ledger.id === "0") return "仅自己可见";
    if (ledger.isFamilyLedger) return "家庭共享账本";
    return ledger.comment || "独立账本";
}

function selectLedger(ledgerId: string): void {
    menuOpen.value = false;
    if (ledgerId !== props.selectedId) emit("select", ledgerId);
}
</script>

<style scoped>
.ledger-switch-trigger {
    display: grid;
    grid-template-columns: 30px minmax(0, 1fr) 17px;
    min-width: 190px;
    height: 46px;
    align-items: center;
    gap: 9px;
    padding: 5px 10px 5px 7px;
    border: 1px solid #e9ebf0;
    border-radius: 14px;
    color: #12141a;
    background: #fff;
    font: inherit;
    text-align: left;
    cursor: pointer;
    transition: border-color 0.18s, box-shadow 0.18s, background 0.18s;
}
.ledger-switch-trigger:hover,
.ledger-switch-trigger.open {
    border-color: #d8dbe3;
    background: #fafbfc;
    box-shadow: 0 8px 22px rgba(18, 20, 26, 0.08);
}
.ledger-switch-trigger:focus-visible {
    outline: 3px solid rgba(240, 85, 55, 0.22);
    outline-offset: 2px;
}
.ledger-switch-trigger:disabled {
    opacity: 0.58;
    cursor: wait;
    box-shadow: none;
}
.ledger-switch-icon {
    display: grid;
    width: 30px;
    height: 30px;
    place-items: center;
    border-radius: 10px;
    color: #fff;
    background: #17191e;
}
.ledger-switch-copy {
    display: grid;
    min-width: 0;
    gap: 2px;
}
.ledger-switch-copy small {
    color: #9aa1ad;
    font-size: 9px;
    font-weight: 700;
    line-height: 1;
}
.ledger-switch-copy strong {
    overflow: hidden;
    font-size: 11.5px;
    font-weight: 750;
    line-height: 1.2;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.ledger-switch-chevron {
    color: #8d95a2;
    transition: transform 0.18s;
}
.ledger-switch-chevron.open {
    transform: rotate(180deg);
}
.ledger-switch-menu {
    width: 292px;
    padding: 9px;
    border: 1px solid #e9ebf0;
    border-radius: 18px;
    color: #12141a;
    background: #fff;
    box-shadow: 0 20px 52px rgba(18, 20, 26, 0.16);
    font-family: "Inter", "Microsoft YaHei UI", "PingFang SC", "Segoe UI", system-ui, sans-serif;
}
.ledger-switch-menu > header {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    padding: 7px 8px 9px;
}
.ledger-switch-menu > header span {
    font-size: 12px;
    font-weight: 800;
}
.ledger-switch-menu > header small {
    color: #9aa1ad;
    font-size: 10px;
}
.ledger-switch-options {
    display: grid;
    gap: 3px;
}
.ledger-switch-option {
    display: grid;
    width: 100%;
    min-height: 54px;
    grid-template-columns: 34px minmax(0, 1fr) 24px;
    align-items: center;
    gap: 10px;
    padding: 7px 9px;
    border: 0;
    border-radius: 12px;
    color: inherit;
    background: transparent;
    font: inherit;
    text-align: left;
    cursor: pointer;
}
.ledger-switch-option:hover,
.ledger-switch-option:focus-visible {
    background: #f4f5f7;
    outline: 0;
}
.ledger-switch-option.selected {
    background: #f1f2f5;
}
.ledger-option-icon {
    display: grid;
    width: 34px;
    height: 34px;
    place-items: center;
    border-radius: 11px;
    color: #596170;
    background: #fff;
    box-shadow: inset 0 0 0 1px #e7e9ee;
}
.ledger-switch-option.selected .ledger-option-icon {
    color: #fff;
    background: #17191e;
    box-shadow: none;
}
.ledger-option-copy {
    display: grid;
    min-width: 0;
    gap: 3px;
}
.ledger-option-copy strong,
.ledger-option-copy small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.ledger-option-copy strong {
    font-size: 12.5px;
    font-weight: 750;
}
.ledger-option-copy small {
    color: #858d9a;
    font-size: 10.5px;
}
.ledger-option-check {
    display: grid;
    width: 24px;
    height: 24px;
    place-items: center;
    border-radius: 50%;
    color: #fff;
    background: #f05537;
}
</style>
