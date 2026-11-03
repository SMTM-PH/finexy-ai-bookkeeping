//go:build cgo

package services

import (
	"encoding/json"
	"math/big"
	"os"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
	"xorm.io/xorm"
)

// Fixture IDs are stable decimal strings. HTTP clients replace them with IDs
// allocated by their disposable candidate; direct database tests retain them.
type ledgerReportFixtureResource struct {
	ID, UID, LedgerID, ParentID, Name, Currency string
	Balance, BalanceTime                        string
	Type                                        int32
	Hidden, DatabaseOnly                        bool
}

type ledgerReportFixtureTransaction struct {
	ID, LedgerID, CategoryID, AccountID            string
	Amount, RelatedID, RelatedAccountID            string
	RelatedAccountAmount, TransactionTime, Comment string
	Type                                           models.TransactionDbType
	TagIDs                                         []string
	HideAmount, GeneratedByAccount                 bool
}

type ledgerReportFixtureAdjustment struct {
	ID, BalanceTarget, Delta string
	Totals                   []models.LedgerReportMoney
}

type ledgerReportFixture struct {
	SchemaVersion                  int
	Synthetic                      bool
	AmountUnit, TimeUnit, Timezone string
	Query                          models.LedgerReportQuery
	Accounts, Categories, Tags     []ledgerReportFixtureResource
	Transactions                   []ledgerReportFixtureTransaction
	Expected                       struct {
		DetailIDs                 []string
		Totals                    []models.LedgerReportMoney
		ReadableTagIDs            []string
		TagCounts, CategoryCounts map[string]int
		Transfer                  struct {
			ID, SourceAmount, DestinationAmount string
			PhysicalIDs, TagIDs                 []string
		}
		Adjustment ledgerReportFixtureAdjustment
	}
	Variants struct {
		HistoricalAdjustment ledgerReportFixtureAdjustment
	}
	Cases []struct {
		Name      string
		Query     json.RawMessage
		DetailIDs []string
		Totals    []models.LedgerReportMoney
	}
}

func loadTestLedgerReportFixture(t *testing.T) *ledgerReportFixture {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "testdata", "ledger_report", "v1.json"))
	require.NoError(t, err)
	var fixture ledgerReportFixture
	require.NoError(t, json.Unmarshal(raw, &fixture))
	require.Equal(t, 1, fixture.SchemaVersion)
	require.True(t, fixture.Synthetic)
	require.Equal(t, "minor", fixture.AmountUnit)
	require.Equal(t, "unix_seconds", fixture.TimeUnit)
	require.Equal(t, fixture.Timezone, fixture.Query.Timezone)
	require.NotEmpty(t, fixture.Cases)
	return &fixture
}

func reportFixtureInt(t *testing.T, value string) int64 {
	t.Helper()
	if value == "" {
		return 0
	}
	number, err := strconv.ParseInt(value, 10, 64)
	require.NoError(t, err)
	require.Equal(t, value, strconv.FormatInt(number, 10), "fixture decimals must be canonical strings")
	return number
}

func seedTestLedgerReportFixture(t *testing.T, sess *xorm.Session, fixture *ledgerReportFixture) {
	t.Helper()
	balances := make(map[string]*big.Int)
	for _, row := range fixture.Accounts {
		balances[row.ID] = big.NewInt(reportFixtureInt(t, row.Balance))
		_, err := sess.Insert(&models.Account{
			AccountId: reportFixtureInt(t, row.ID), Uid: reportFixtureInt(t, row.UID), LedgerId: reportFixtureInt(t, row.LedgerID),
			ParentAccountId: reportFixtureInt(t, row.ParentID), Type: models.AccountType(row.Type), Currency: row.Currency,
			Name: row.Name, Balance: reportFixtureInt(t, row.Balance), Hidden: row.Hidden,
		})
		require.NoError(t, err)
	}
	for _, row := range fixture.Categories {
		_, err := sess.Insert(&models.TransactionCategory{CategoryId: reportFixtureInt(t, row.ID), Uid: reportFixtureInt(t, row.UID),
			ParentCategoryId: reportFixtureInt(t, row.ParentID), Type: models.TransactionCategoryType(row.Type), Name: row.Name})
		require.NoError(t, err)
	}
	for _, row := range fixture.Tags {
		_, err := sess.Insert(&models.TransactionTag{TagId: reportFixtureInt(t, row.ID), Uid: reportFixtureInt(t, row.UID), Name: row.Name})
		require.NoError(t, err)
	}
	indexID := int64(0)
	for _, row := range fixture.Transactions {
		id := reportFixtureInt(t, row.ID)
		sequence := reportFixtureInt(t, row.TransactionTime)*1000 + id
		_, err := sess.Insert(&models.Transaction{
			TransactionId: id, Uid: 100, LedgerId: reportFixtureInt(t, row.LedgerID), Type: row.Type,
			CategoryId: reportFixtureInt(t, row.CategoryID), AccountId: reportFixtureInt(t, row.AccountID),
			Amount: reportFixtureInt(t, row.Amount), RelatedId: reportFixtureInt(t, row.RelatedID),
			RelatedAccountId: reportFixtureInt(t, row.RelatedAccountID), RelatedAccountAmount: reportFixtureInt(t, row.RelatedAccountAmount),
			TransactionTime: sequence, HideAmount: row.HideAmount, Comment: row.Comment,
		})
		require.NoError(t, err)
		// Account creation already contributes its opening adjustment. Other
		// physical legs apply once to their own account, matching HTTP seeding.
		if !row.GeneratedByAccount {
			amount := big.NewInt(reportFixtureInt(t, row.Amount))
			if row.Type == models.TRANSACTION_DB_TYPE_MODIFY_BALANCE {
				amount.SetInt64(reportFixtureInt(t, row.RelatedAccountAmount))
			} else if row.Type == models.TRANSACTION_DB_TYPE_EXPENSE || row.Type == models.TRANSACTION_DB_TYPE_TRANSFER_OUT {
				amount.Neg(amount)
			}
			balance, exists := balances[row.AccountID]
			require.True(t, exists)
			balance.Add(balance, amount)
			require.True(t, balance.IsInt64())
		}
		for _, tag := range row.TagIDs {
			indexID++
			_, err := sess.Insert(&models.TransactionTagIndex{TagIndexId: indexID, Uid: 100, TransactionId: id,
				TagId: reportFixtureInt(t, tag), TransactionTime: sequence})
			require.NoError(t, err)
		}
	}
	for id, balance := range balances {
		_, err := sess.ID(reportFixtureInt(t, id)).Cols("balance").Update(&models.Account{Balance: balance.Int64()})
		require.NoError(t, err)
	}
}
