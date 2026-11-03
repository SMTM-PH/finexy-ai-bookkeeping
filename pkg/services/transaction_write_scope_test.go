package services

import (
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
)

func TestLedgerTransactionEditabilityRespectsWriteScopeAndTime(t *testing.T) {
	row := models.Transaction{Uid: 1, LedgerId: 900, RecorderUid: 2, Type: models.TRANSACTION_DB_TYPE_EXPENSE,
		AccountId: 10, TransactionTime: utils.GetMinTransactionTimeFromUnixTime(time.Now().Unix())}
	account := models.Account{Uid: 1, LedgerId: 900, AccountId: 10, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT}
	user := models.User{TransactionEditScope: models.TRANSACTION_EDIT_SCOPE_ALL}
	for _, test := range []struct {
		name     string
		actor    int64
		canWrite bool
		change   func(*models.Transaction, *models.Account, *models.User)
		want     bool
	}{
		{name: "owner edits member entry", actor: 1, canWrite: true, want: true},
		{name: "member edits own entry", actor: 2, canWrite: true, want: true},
		{name: "another writer cannot edit", actor: 3, canWrite: true},
		{name: "downgraded recorder cannot edit", actor: 2},
		{name: "owner with time editing disabled", actor: 1, canWrite: true, change: func(_ *models.Transaction, _ *models.Account, user *models.User) {
			user.TransactionEditScope = models.TRANSACTION_EDIT_SCOPE_NONE
		}},
		{name: "old entry outside time window", actor: 2, canWrite: true, change: func(row *models.Transaction, _ *models.Account, user *models.User) {
			row.TransactionTime = utils.GetMinTransactionTimeFromUnixTime(time.Now().Add(-48 * time.Hour).Unix())
			user.TransactionEditScope = models.TRANSACTION_EDIT_SCOPE_LAST_24H_OR_LATER
		}},
		{name: "hidden account", actor: 2, canWrite: true, change: func(_ *models.Transaction, account *models.Account, _ *models.User) { account.Hidden = true }},
		{name: "account moved to another ledger", actor: 2, canWrite: true, change: func(_ *models.Transaction, account *models.Account, _ *models.User) { account.LedgerId = 901 }},
		{name: "foreign row", actor: 2, canWrite: true, change: func(row *models.Transaction, _ *models.Account, _ *models.User) { row.LedgerId = 901 }},
		{name: "fund entry uses its dedicated flow", actor: 1, canWrite: true, change: func(row *models.Transaction, _ *models.Account, _ *models.User) { row.SavingsGoalFundId = 42 }},
	} {
		t.Run(test.name, func(t *testing.T) {
			copyRow, copyAccount, copyUser := row, account, user
			if test.change != nil {
				test.change(&copyRow, &copyAccount, &copyUser)
			}
			if got := TransactionEditableInLedger(&copyRow, &copyUser, time.UTC, &copyAccount, nil, test.actor, 1, 900, test.canWrite); got != test.want {
				t.Fatalf("editable = %v; want %v", got, test.want)
			}
		})
	}
}
