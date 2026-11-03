export interface LedgerReportSession {
    readonly identity: string | null;
    readonly username: string | null;
    readonly hasToken: boolean;
}

/** Compare identity metadata only; never retain a session credential. */
export function ledgerReportSessionChanged(initial: LedgerReportSession, current: LedgerReportSession, piniaUsername: string | null): boolean {
    return !initial.identity || !initial.username || !initial.hasToken || !current.hasToken ||
        current.identity !== initial.identity || current.username !== initial.username || piniaUsername !== initial.username;
}

/** Storage events can be queued after a later login, so also inspect the event's
 * identity change rather than trusting only the latest localStorage snapshot. */
export function ledgerReportStorageInvalidatesSession(
    key: string | null, newValue: string | null, initial: LedgerReportSession, current: LedgerReportSession, piniaUsername: string | null
): boolean {
    if (key !== null && key !== 'ebk_user_info' && key !== 'ebk_user_token') return false;
    if (key === null || newValue === null) return true;
    if (key === 'ebk_user_info') {
        try {
            const value: unknown = JSON.parse(newValue);
            if (!value || typeof value !== 'object' || !('username' in value) || value.username !== initial.username) return true;
        } catch { return true; }
    }
    return ledgerReportSessionChanged(initial, current, piniaUsername);
}
