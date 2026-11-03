import services from '@/lib/services.ts';
import { copyLedgerReportQuery, validateLedgerReportPage } from '@/lib/ledger_report.ts';
import { MonthlyOverviewContractError, validateOverviewBudget, type MonthlyOverviewGateway } from '@/lib/monthly_overview.ts';

/** Dedicated home owner: never reuses/reset the report-page singleton store. */
export function monthlyOverviewGateway(username: () => string): MonthlyOverviewGateway {
    return {
        async report(query) {
            const requested = copyLedgerReportQuery(query), expectedUsername = username();
            if (!expectedUsername) throw new MonthlyOverviewContractError();
            const response = await services.queryLedgerReport({ query: requested, offset: 0, count: 1 }, expectedUsername);
            if (response.data?.success !== true || !response.data.result) throw new MonthlyOverviewContractError();
            // count=1 is enough: totals derive from the server's complete snapshot.
            return validateLedgerReportPage(response.data.result, requested, 0, 1);
        },
        async budget(ledgerId, yearMonth) {
            const expectedUsername = username();
            if (!expectedUsername) throw new MonthlyOverviewContractError();
            const response = await services.getMonthlyBudget({ ledgerId, yearMonth, expectedUsername });
            if (response.data?.success !== true || !Object.prototype.hasOwnProperty.call(response.data, 'result')) throw new MonthlyOverviewContractError();
            return validateOverviewBudget(response.data.result, ledgerId, yearMonth);
        }
    };
}
