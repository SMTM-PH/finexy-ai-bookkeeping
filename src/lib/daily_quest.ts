export const DAILY_QUEST_UPDATED_EVENT = 'ezbookkeeping:daily-quest-updated';

function getLocalDateKey(date: Date = new Date()): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}

function getNoTransactionsStorageKey(date: Date = new Date()): string {
    return `no-transactions-${getLocalDateKey(date)}`;
}

export function isNoTransactionsDay(date: Date = new Date()): boolean {
    return window.localStorage.getItem(getNoTransactionsStorageKey(date)) === 'true';
}

export function markNoTransactionsDay(date: Date = new Date()): void {
    window.localStorage.setItem(getNoTransactionsStorageKey(date), 'true');
    window.dispatchEvent(new Event(DAILY_QUEST_UPDATED_EVENT));
}
