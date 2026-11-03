import { describe, expect, it } from 'vitest';
import { supportsMobileLedgerRoute, transactionDraftMatchesLedger } from '../mobile_ledger.ts';

describe('mobile ledger capabilities', () => {
    const limited = ['/account/add', '/account/edit?id=1', '/account/reconciliation_statements', '/account/move_all_transactions', '/statistic/advanced', '/statistic/settings', '/category/preset', '/tag/group/list', '/user/data/management', '/settings/filter/account'];

    it.each(limited)('keeps default-ledger operations available: %s', path => {
        expect(supportsMobileLedgerRoute(path, '0')).toBe(true);
    });

    it.each(limited)('blocks unsupported non-default deep links: %s', path => {
        expect(supportsMobileLedgerRoute(path, '9223372036854775807')).toBe(false);
    });

    it.each(['/', '/transaction/list?accountIds=9', '/transaction/detail?id=9', '/account/list', '/ledger/manage', '/savings/goals', '/settings', '/user/profile', '/template/list', '/template/add?ledgerId=9223372036854775807', '/template/edit?id=9', '/schedule/list', '/schedule/review', '/Schedule/Review/', '/statistic/transaction', '/STATISTIC/Transaction/?type=3'])('permits scoped reads and unrelated settings: %s', path => {
        expect(supportsMobileLedgerRoute(path, '9223372036854775807')).toBe(true);
    });

    it('binds legacy drafts to the default ledger and keeps large ledger ids as strings', () => {
        expect(transactionDraftMatchesLedger(null, '0')).toBe(false);
        expect(transactionDraftMatchesLedger({ sourceAmount: 123 }, '0')).toBe(true);
        expect(transactionDraftMatchesLedger({ sourceAmount: 123 }, '1')).toBe(false);
        expect(transactionDraftMatchesLedger({ ledgerId: '9223372036854775807' }, '9223372036854775807')).toBe(true);
        expect(transactionDraftMatchesLedger({ ledgerId: '9223372036854775807' }, '9223372036854775806')).toBe(false);
    });

    it('also guards router-equivalent trailing slashes and case variants', () => {
        expect(supportsMobileLedgerRoute('/transaction/add/', '1')).toBe(true);
        expect(supportsMobileLedgerRoute('/Transaction/EDIT/?id=99', '1')).toBe(true);
        expect(supportsMobileLedgerRoute('/account/add/#form', '1')).toBe(false);
    });
});
