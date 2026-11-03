package services

import (
	"math"
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
)

func TestLedgerReportMoneyIntegerLimitsAndCancellation(t *testing.T) {
	for _, amounts := range [][]int64{{math.MaxInt64, 1, -1}, {1, math.MaxInt64, -1}, {-1, 1, math.MaxInt64}} {
		buckets := make(reportMoneyBuckets)
		for _, amount := range amounts {
			buckets.add(&models.LedgerReportDetail{Type: 2, Source: &models.LedgerReportLeg{Currency: "CNY", Amount: amount}})
		}
		money, err := buckets.result()
		require.NoError(t, err)
		require.Equal(t, int64(math.MaxInt64), money[0].Income)
	}
	for _, scenario := range []struct {
		name    string
		kind    int32
		amounts []int64
	}{
		{"income", 2, []int64{math.MaxInt64, 1}}, {"expense", 3, []int64{math.MaxInt64, 1}}, {"net-negation", 3, []int64{math.MinInt64}},
		{"adjustment", 1, []int64{math.MaxInt64, 1}}, {"transfer", 4, []int64{math.MaxInt64, 1}},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			buckets := make(reportMoneyBuckets)
			for _, amount := range scenario.amounts {
				buckets.add(&models.LedgerReportDetail{Type: scenario.kind, Adjustment: amount, Source: &models.LedgerReportLeg{Currency: "CNY", Amount: amount}})
			}
			_, err := buckets.result()
			require.ErrorIs(t, err, errs.ErrLedgerReportAmountOverflow)
		})
	}
}

func uncategorizedReportData(t *testing.T, filter *models.LedgerReportIDFilter) *ledgerReportData {
	t.Helper()
	ledger := "11"
	query, err := models.NormalizeLedgerReportQuery(&models.LedgerReportQuery{LedgerID: &ledger, StartDate: "2026-10-01", EndDate: "2026-10-31", Timezone: "UTC", Categories: filter})
	require.NoError(t, err)
	data := &ledgerReportData{actor: 100, owner: 100, ledger: 11, role: models.FAMILY_MEMBER_ROLE_OWNER, query: query,
		accounts: map[int64]*models.Account{1: {AccountId: 1, Uid: 100, LedgerId: 11, Type: 1, Currency: "CNY"}, 2: {AccountId: 2, Uid: 100, LedgerId: 11, Type: 1, Currency: "USD"}},
		categories: map[int64]*models.TransactionCategory{
			1001: {CategoryId: 1001, Uid: 100, Type: 2}, 1002: {CategoryId: 1002, Uid: 100, Type: 2, ParentCategoryId: 1001},
			2001: {CategoryId: 2001, Uid: 100, Type: 1}, 2002: {CategoryId: 2002, Uid: 100, Type: 1, ParentCategoryId: 2001},
			3001: {CategoryId: 3001, Uid: 100, Type: 3}, 3002: {CategoryId: 3002, Uid: 100, Type: 3, ParentCategoryId: 3001}},
		tags: map[int64]*models.TransactionTag{}, rows: map[int64]*models.Transaction{}, rowTags: map[int64]map[int64]bool{}}
	for _, row := range []*models.Transaction{
		{TransactionId: 1, Type: 3, CategoryId: 1002, AccountId: 1, Amount: 150},
		{TransactionId: 2, Type: 2, CategoryId: 2002, AccountId: 1, Amount: 1000},
		{TransactionId: 3, Type: 4, CategoryId: 3002, AccountId: 1, Amount: 70},
		{TransactionId: 4, Type: 1, AccountId: 1, Amount: 1000, RelatedAccountId: 1, RelatedAccountAmount: 40},
		{TransactionId: 5, Type: 4, AccountId: 1, Amount: 50, SavingsGoalFundId: 7},
		{TransactionId: 6, Type: 5, AccountId: 2, Amount: 20, SavingsGoalFundId: 8},
	} {
		row.Uid, row.LedgerId = 100, 11
		row.TransactionTime = time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC).Unix()*1000 + row.TransactionId
		data.rows[row.TransactionId] = row
	}
	return data
}

func uncategorizedDetailIDs(snapshot *models.LedgerReportSnapshot) []string {
	ids := make([]string, 0, len(snapshot.Details))
	for _, row := range snapshot.Details {
		ids = append(ids, row.ID)
	}
	return ids
}

func TestLedgerReportUncategorizedFilterMatchesRowsNotTypesOrRootParents(t *testing.T) {
	for _, scenario := range []struct {
		name, mode  string
		ids         []string
		descendants bool
		want        []string
	}{
		{"include", "include", []string{"0"}, false, []string{"6", "5", "4"}},
		{"exclude", "exclude", []string{"0"}, false, []string{"3", "2", "1"}},
		{"zero-not-a-root-parent", "include", []string{"0"}, true, []string{"6", "5", "4"}},
		{"exclude-zero-not-all-roots", "exclude", []string{"0"}, true, []string{"3", "2", "1"}},
		{"zero-and-one-root", "include", []string{"0", "1001"}, true, []string{"6", "5", "4", "1"}},
		{"exclude-zero-and-one-root", "exclude", []string{"0", "1001"}, true, []string{"3", "2"}},
		{"zero-and-unexpanded-root", "include", []string{"0", "1001"}, false, []string{"6", "5", "4"}},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			data := uncategorizedReportData(t, &models.LedgerReportIDFilter{Mode: scenario.mode, IDs: scenario.ids, IncludeDescendants: scenario.descendants})
			snapshot, err := data.build()
			require.NoError(t, err)
			require.Equal(t, scenario.want, uncategorizedDetailIDs(snapshot))
			for _, resource := range snapshot.CategoryMetadata {
				require.NotEqual(t, "0", resource.ID)
			}
			if scenario.mode == "include" && len(scenario.ids) == 1 {
				require.Empty(t, snapshot.CategoryMetadata, "uncategorized selection does not fabricate a category resource")
			}
		})
	}
}

func TestLedgerReportUncategorizedHashBindsZeroEvenForIdenticalRows(t *testing.T) {
	// With only adjustments selected by type, adding category zero leaves the
	// rows unchanged. The normalized query still must invalidate old receipts.
	plain := uncategorizedReportData(t, nil)
	plain.query.Types = []int32{1}
	baseline, err := plain.build()
	require.NoError(t, err)
	zero := uncategorizedReportData(t, &models.LedgerReportIDFilter{Mode: "include", IDs: []string{"0"}})
	zero.query.Types = []int32{1}
	selected, err := zero.build()
	require.NoError(t, err)
	require.Equal(t, uncategorizedDetailIDs(baseline), uncategorizedDetailIDs(selected))
	require.Equal(t, baseline.Totals, selected.Totals)
	require.NotEqual(t, baseline.SnapshotHash, selected.SnapshotHash)
	_, err = PageLedgerReport(selected, 0, nil, baseline.SnapshotHash)
	require.ErrorIs(t, err, errs.ErrLedgerReportSnapshotChanged)
	permuted := uncategorizedReportData(t, &models.LedgerReportIDFilter{Mode: "include", IDs: []string{"0", "0"}})
	permuted.query.Types = []int32{1}
	canonical, err := permuted.build()
	require.NoError(t, err)
	require.Equal(t, selected.SnapshotHash, canonical.SnapshotHash)
}

func TestLedgerReportUncategorizedRejectsCorruptRowsAndInvalidResources(t *testing.T) {
	for _, scenario := range []struct {
		name   string
		mutate func(*ledgerReportData)
	}{
		{"expense-zero", func(d *ledgerReportData) { d.rows[1].CategoryId = 0 }},
		{"income-zero", func(d *ledgerReportData) { d.rows[2].CategoryId = 0 }},
		{"ordinary-transfer-zero", func(d *ledgerReportData) { d.rows[3].CategoryId = 0 }},
		{"negative-adjustment-category", func(d *ledgerReportData) { d.rows[4].CategoryId = -1 }},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			data := uncategorizedReportData(t, &models.LedgerReportIDFilter{Mode: "include", IDs: []string{"0"}})
			scenario.mutate(data)
			_, err := data.build()
			require.ErrorIs(t, err, errs.ErrLedgerReportIncomplete)
		})
	}
	data := uncategorizedReportData(t, &models.LedgerReportIDFilter{Mode: "include", IDs: []string{"0"}})
	data.query.Categories.IDs = []string{"0", "999999"}
	_, err := data.build()
	require.ErrorIs(t, err, errs.ErrLedgerReportResourceInvalid, "zero must not bypass other selected categories' ownership validation")
	_, err = data.resourceFilter(&models.LedgerReportIDFilter{Mode: "include", IDs: []string{"0"}}, true)
	require.ErrorIs(t, err, errs.ErrLedgerReportResourceInvalid)
}
