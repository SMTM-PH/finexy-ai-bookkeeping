package services

import (
	"sort"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
	"xorm.io/xorm"
)

// transactionWriteScope retains the requested ledger and the authenticated
// actor, independently of the row's historical recorder and data owner.
type transactionWriteScope struct {
	actor    int64
	ledger   *models.Ledger
	metadata *TransactionMetadataSnapshot
}

// TransactionMetadataSnapshot is the complete requested association set, used
// to reject stale or changed private metadata without silently clearing it.
type TransactionMetadataSnapshot struct {
	TagIds            []int64
	PictureIds        []int64
	ExpectedEditToken string
}

// TransactionObjectIdsEqual compares association sets; their order is not part
// of the transaction contract.
func TransactionObjectIdsEqual(left, right []int64) bool {
	normalize := func(ids []int64) []int64 {
		result := utils.ToUniqueInt64Slice(append([]int64{}, ids...))
		sort.Slice(result, func(i, j int) bool { return result[i] < result[j] })
		return result
	}
	return utils.Int64SliceEquals(normalize(left), normalize(right))
}

func transactionWriteActor(c core.Context, fallback int64) int64 {
	if web, ok := c.(*core.WebContext); ok {
		return web.GetCurrentUid()
	}
	return fallback
}

func resolveTransactionWriteScope(c core.Context, actor, ownerUid, ledgerId int64) (*transactionWriteScope, error) {
	if actor <= 0 {
		return nil, errs.ErrUserIdInvalid
	}
	if web, ok := c.(*core.WebContext); ok && web.GetCurrentUid() != actor {
		return nil, errs.ErrLedgerAccessDenied
	}
	ledger, err := ImportLedger(c, actor, ledgerId)
	if err != nil {
		return nil, err
	}
	if ledger.OwnerUid != ownerUid {
		return nil, errs.ErrLedgerAccessDenied
	}
	return &transactionWriteScope{actor: actor, ledger: ledger}, nil
}

// Lock in ledger-first order before reading a row for conditional writes.
// A no-op update acquires a write lock on all supported providers.
func (scope *transactionWriteScope) lockTransactionForConditionalWrite(sess *xorm.Session, id int64) error {
	if err := authorizeImportLedger(sess, scope.actor, scope.ledger); err != nil {
		return err
	}
	_, err := sess.ID(id).Where("uid=? AND ledger_id=? AND deleted=?", scope.ledger.OwnerUid, scope.ledger.LedgerId, false).
		SetExpr("updated_unix_time", "updated_unix_time").Update(&models.Transaction{})
	return err
}

// authorize uses the existing financial transaction, not another session.
// The ledger/member lock and live account checks also cover stale API preflight.
func (scope *transactionWriteScope) authorize(sess *xorm.Session, row *models.Transaction) error {
	if row.Uid != scope.ledger.OwnerUid || row.LedgerId != scope.ledger.LedgerId {
		return errs.ErrTransactionNotFound
	}
	if err := authorizeImportLedger(sess, scope.actor, scope.ledger); err != nil {
		return err
	}
	if scope.actor != scope.ledger.OwnerUid && row.RecorderUid != scope.actor {
		return errs.ErrTransactionNotFound
	}
	if row.RelatedId > 0 && (row.Type == models.TRANSACTION_DB_TYPE_TRANSFER_OUT || row.Type == models.TRANSACTION_DB_TYPE_TRANSFER_IN) {
		related := &models.Transaction{}
		found, err := sess.ID(row.RelatedId).Where("uid=? AND ledger_id=? AND deleted=?", row.Uid, row.LedgerId, false).Get(related)
		if err != nil {
			return err
		}
		if !found || (scope.actor != scope.ledger.OwnerUid && related.RecorderUid != scope.actor) {
			return errs.ErrTransactionNotFound
		}
	}
	return authorizeTransactionAccounts(sess, row)
}

func (scope *transactionWriteScope) authorizeMetadataChanges(sess *xorm.Session, row *models.Transaction, addTags, removeTags, addPictures, removePictures []int64) error {
	if scope.metadata != nil && scope.metadata.ExpectedEditToken != "" {
		if len(addTags)+len(removeTags)+len(addPictures)+len(removePictures) > 0 {
			return errs.ErrParameterInvalid
		}
		return nil
	}
	if scope.actor == scope.ledger.OwnerUid {
		return nil
	}
	// Shared members can preserve existing private associations, but cannot add
	// or remove them by editing their own shared transaction.
	if len(addTags) > 0 || len(removeTags) > 0 {
		return errs.ErrTransactionTagNotFound
	}
	if len(addPictures) > 0 || len(removePictures) > 0 {
		return errs.ErrTransactionPictureNotFound
	}
	if scope.metadata != nil {
		var tagIndexes []*models.TransactionTagIndex
		if err := sess.Where("uid=? AND transaction_id=? AND deleted=?", row.Uid, row.TransactionId, false).Find(&tagIndexes); err != nil {
			return err
		}
		ids := make([]int64, 0, len(tagIndexes))
		for _, index := range tagIndexes {
			ids = append(ids, index.TagId)
		}
		if !TransactionObjectIdsEqual(ids, scope.metadata.TagIds) {
			return errs.ErrTransactionTagNotFound
		}
		var pictures []*models.TransactionPictureInfo
		if err := sess.Where("uid=? AND transaction_id=? AND deleted=?", row.Uid, row.TransactionId, false).Find(&pictures); err != nil {
			return err
		}
		ids = make([]int64, 0, len(pictures))
		for _, picture := range pictures {
			ids = append(ids, picture.PictureId)
		}
		if !TransactionObjectIdsEqual(ids, scope.metadata.PictureIds) {
			return errs.ErrTransactionPictureNotFound
		}
	}
	return nil
}

// TransactionEditableInLedger combines row ownership, live write capability,
// account scope and the user's existing time policy for list/detail responses.
func TransactionEditableInLedger(row *models.Transaction, user *models.User, clientTimezone *time.Location,
	account, relatedAccount *models.Account, actor, ownerUid, ledgerId int64, canWrite bool) bool {
	if row == nil || actor <= 0 || !canWrite || row.Uid != ownerUid || row.LedgerId != ledgerId ||
		(actor != ownerUid && row.RecorderUid != actor) || row.SavingsGoalFundId > 0 {
		return false
	}
	if account == nil || account.AccountId != row.AccountId || account.Uid != ownerUid || account.LedgerId != ledgerId || account.Type == models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
		return false
	}
	if row.RelatedAccountId > 0 && (relatedAccount == nil || relatedAccount.AccountId != row.RelatedAccountId || relatedAccount.Uid != ownerUid ||
		relatedAccount.LedgerId != ledgerId || relatedAccount.Type == models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS) {
		return false
	}
	return row.IsEditable(user, clientTimezone, account, relatedAccount)
}
