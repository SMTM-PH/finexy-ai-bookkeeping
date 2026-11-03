package api

import (
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
)

func TestLedgerListTransferEditabilityMatchesOutgoingDetail(t *testing.T) {
	a := &TransactionsApi{transactions: &services.TransactionService{}}
	user := &models.User{TransactionEditScope: models.TRANSACTION_EDIT_SCOPE_ALL}
	accounts := map[int64]*models.Account{
		10: {AccountId: 10, Uid: 1, LedgerId: 900, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT},
		11: {AccountId: 11, Uid: 1, LedgerId: 900, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT},
	}
	outgoing := &models.Transaction{TransactionId: 50, RelatedId: 51, Uid: 1, LedgerId: 900, RecorderUid: 2,
		Type: models.TRANSACTION_DB_TYPE_TRANSFER_OUT, AccountId: 10, RelatedAccountId: 11,
		TransactionTime: utils.GetMinTransactionTimeFromUnixTime(time.Now().Unix())}
	incoming := a.transactions.GetRelatedTransferTransaction(outgoing)
	for _, row := range []*models.Transaction{outgoing, incoming} {
		if !a.transactionEditableInLedger(row, user, time.UTC, accounts, 2, 1, 900, true) {
			t.Fatal("both halves of a visible internal transfer should open the member's outgoing edit")
		}
		if a.transactionEditableInLedger(row, user, time.UTC, accounts, 2, 1, 900, false) {
			t.Fatal("a downgraded recorder cannot edit either half")
		}
	}
	accounts[10].Hidden = true
	for _, row := range []*models.Transaction{outgoing, incoming} {
		if a.transactionEditableInLedger(row, user, time.UTC, accounts, 2, 1, 900, true) {
			t.Fatal("hidden outgoing source must disable both list and detail edit actions")
		}
	}
}
