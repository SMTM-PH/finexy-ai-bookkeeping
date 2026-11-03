//go:build cgo

package services

import (
	"bytes"
	"encoding/csv"
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
)

func TestLedgerReportUncategorizedReaderSavingsGoalsAndNoWrites(t *testing.T) {
	query, sess := setupLedgerReport(t)
	goal, err := SavingsGoals.CreateSavingsGoal(nil, 100, &models.SavingsGoalCreateRequest{LedgerId: 11, Name: "Synthetic report goal", TargetAmount: 1000})
	require.NoError(t, err)
	deposit, err := SavingsGoals.DepositToGoal(nil, 100, &models.SavingsGoalFundRequest{Id: goal.SavingsGoalId, AccountId: 100, Amount: 50})
	require.NoError(t, err)
	withdrawal, err := SavingsGoals.WithdrawFromGoal(nil, 100, &models.SavingsGoalFundRequest{Id: goal.SavingsGoalId, AccountId: 100, Amount: 20})
	require.NoError(t, err)
	// Production goal posting chooses the current time. Pin only these synthetic
	// timestamps into the common calendar fixture so this test stays repeatable.
	when := time.Date(2026, 10, 10, 12, 1, 0, 0, time.UTC).Unix() * 1000
	for i, fund := range []*models.SavingsGoalFund{deposit, withdrawal} {
		_, err := sess.ID(fund.TransactionId).Cols("transaction_time").Update(&models.Transaction{TransactionTime: when + 20 + int64(i)})
		require.NoError(t, err)
	}
	var accountsBefore []*models.Account
	var transactionsBefore []*models.Transaction
	require.NoError(t, sess.Asc("account_id").Find(&accountsBefore))
	require.NoError(t, sess.Asc("transaction_id").Find(&transactionsBefore))
	query.Categories = &models.LedgerReportIDFilter{Mode: "include", IDs: []string{"0"}, IncludeDescendants: true}
	snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 400, query)
	require.NoError(t, err)
	require.Equal(t, []string{reportID(withdrawal.TransactionId), reportID(deposit.TransactionId), "9"}, reportDetailIDs(snapshot))
	require.Empty(t, snapshot.CategoryMetadata)
	require.Equal(t, []models.LedgerReportMoney{{Currency: "CNY", Adjustment: 1000, TransferOut: 50, TransferIn: 20}}, snapshot.Totals)
	encoded, err := EncodeLedgerReportCSV(snapshot)
	require.NoError(t, err)
	rows, err := csv.NewReader(bytes.NewReader(encoded)).ReadAll()
	require.NoError(t, err)
	require.Len(t, rows, 4)
	for _, row := range rows[1:] {
		require.Equal(t, "0", row[14])
	}
	query.Categories.Mode = "exclude"
	excluded, err := Transactions.GetLedgerReportSnapshot(nil, 400, query)
	require.NoError(t, err)
	require.NotContains(t, reportDetailIDs(excluded), "9")
	require.Contains(t, reportDetailIDs(excluded), "7", "categorized external transfers remain visible")
	require.NotContains(t, reportDetailIDs(excluded), reportID(deposit.TransactionId))
	require.NotContains(t, reportDetailIDs(excluded), reportID(withdrawal.TransactionId))
	query.Categories.Mode = "include"
	_, err = Transactions.GetLedgerReportSnapshot(nil, 999, query)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	query.Accounts = &models.LedgerReportIDFilter{Mode: "include", IDs: []string{"0"}}
	_, err = Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.ErrorIs(t, err, errs.ErrLedgerReportQueryInvalid)
	var accountsAfter []*models.Account
	var transactionsAfter []*models.Transaction
	require.NoError(t, sess.Asc("account_id").Find(&accountsAfter))
	require.NoError(t, sess.Asc("transaction_id").Find(&transactionsAfter))
	require.Equal(t, accountsBefore, accountsAfter)
	require.Equal(t, transactionsBefore, transactionsAfter)
}

func TestLedgerReportUncategorizedReaderRejectsInvalidZeroRows(t *testing.T) {
	for _, rowID := range []int64{1, 2, 7} {
		t.Run(reportID(rowID), func(t *testing.T) {
			query, sess := setupLedgerReport(t)
			_, err := sess.ID(rowID).Cols("category_id").Update(&models.Transaction{CategoryId: 0})
			require.NoError(t, err)
			query.Categories = &models.LedgerReportIDFilter{Mode: "include", IDs: []string{"0"}}
			_, err = Transactions.GetLedgerReportSnapshot(nil, 100, query)
			require.ErrorIs(t, err, errs.ErrLedgerReportIncomplete)
		})
	}
}
