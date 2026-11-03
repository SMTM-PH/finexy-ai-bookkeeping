package api

import (
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
)

func (a *TransactionsApi) ledgerTransactionCanWrite(c *core.WebContext, uid, ledgerId int64) (bool, *errs.Error) {
	_, _, err := services.Ledgers.GetLedgerWithAccess(c, uid, ledgerId, models.FamilyMemberRole.CanWrite)
	if err == errs.ErrLedgerAccessDenied {
		return false, nil
	}
	if err != nil {
		return false, errs.Or(err, errs.ErrOperationFailed)
	}
	return true, nil
}

func (a *TransactionsApi) ledgerTransactionEditContext(c *core.WebContext, uid, ledgerId int64) (*models.User, *time.Location, bool, *errs.Error) {
	user, err := a.users.GetUserById(c, uid)
	if err != nil {
		return nil, nil, false, errs.ErrUserNotFound
	}
	clientTimezone, err := c.GetClientTimezone()
	if err != nil {
		return nil, nil, false, errs.ErrClientTimezoneOffsetInvalid
	}
	canWrite, editErr := a.ledgerTransactionCanWrite(c, uid, ledgerId)
	return user, clientTimezone, canWrite, editErr
}

func (a *TransactionsApi) transactionEditableInLedger(row *models.Transaction, user *models.User, clientTimezone *time.Location,
	accounts map[int64]*models.Account, actor, ownerUid, ledgerId int64, canWrite bool) bool {
	// Both halves of an internal transfer open the same outgoing transaction
	// in the detail/edit API; apply its source/destination policy to the list too.
	if row.Type == models.TRANSACTION_DB_TYPE_TRANSFER_IN && row.RelatedId > 0 {
		row = a.transactions.GetRelatedTransferTransaction(row)
	}
	return services.TransactionEditableInLedger(row, user, clientTimezone, accounts[row.AccountId], accounts[row.RelatedAccountId], actor, ownerUid, ledgerId, canWrite)
}
