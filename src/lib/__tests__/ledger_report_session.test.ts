import { describe, expect, it } from 'vitest';
import { ledgerReportSessionChanged, ledgerReportStorageInvalidatesSession, type LedgerReportSession } from '../ledger_report_session.ts';

const original: LedgerReportSession = { identity: 'server-A/user-A', username: 'user-A', hasToken: true };
const second: LedgerReportSession = { identity: 'server-A/user-B', username: 'user-B', hasToken: true };

describe('report session privacy boundary', () => {
    it('detects another account before the old Pinia session changes', () => {
        expect(ledgerReportSessionChanged(original, second, 'user-A')).toBe(true);
        expect(ledgerReportStorageInvalidatesSession('ebk_user_info', JSON.stringify({ username: 'user-B' }), original, second, 'user-A')).toBe(true);
    });

    it.each([
        { ...original, hasToken: false }, { ...original, identity: null, username: null },
        { ...original, identity: 'server-B/user-A' }
    ])('detects a missing login or different server on focus/visibility: %j', current => {
        expect(ledgerReportSessionChanged(original, current, 'user-A')).toBe(true);
    });

    it('does not accept a stale or not-yet-initialized Pinia username', () => {
        expect(ledgerReportSessionChanged(original, original, 'user-B')).toBe(true);
        expect(ledgerReportSessionChanged(original, original, null)).toBe(true);
        expect(ledgerReportSessionChanged({ identity: null, username: null, hasToken: false }, original, 'user-A')).toBe(true);
    });

    it('does not reset for a same-user token refresh or profile edit', () => {
        expect(ledgerReportStorageInvalidatesSession('ebk_user_token', 'replacement-is-not-retained', original, original, 'user-A')).toBe(false);
        expect(ledgerReportStorageInvalidatesSession('ebk_user_info', JSON.stringify({ username: 'user-A', nickname: 'Updated' }), original, original, 'user-A')).toBe(false);
        expect(ledgerReportSessionChanged(original, original, 'user-A')).toBe(false);
    });

    it('ignores unrelated preference and scoped-draft events', () => {
        for (const key of ['ebk_app_settings', 'ebk_transaction_state_v1:example:draft:1']) {
            expect(ledgerReportStorageInvalidatesSession(key, null, original, second, 'user-A')).toBe(false);
        }
    });

    it.each([null, 'ebk_user_info', 'ebk_user_token'])('clears for queued removal even if the same user already logged back in: %s', key => {
        expect(ledgerReportStorageInvalidatesSession(key, null, original, original, 'user-A')).toBe(true);
    });

    it('clears for a queued different-user event even when the latest snapshot is restored', () => {
        expect(ledgerReportStorageInvalidatesSession('ebk_user_info', JSON.stringify({ username: 'user-B' }), original, original, 'user-A')).toBe(true);
    });

    it.each(['{broken', '{}', 'null', '[]'])('fails closed on malformed identity metadata: %s', value => {
        expect(ledgerReportStorageInvalidatesSession('ebk_user_info', value, original, original, 'user-A')).toBe(true);
    });
});
