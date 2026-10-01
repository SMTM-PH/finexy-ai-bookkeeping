package services

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"xorm.io/xorm"
)

// ImportLedger resolves the actor's writable ledger and the data owner's shard.
func ImportLedger(c core.Context, actor, ledgerID int64) (*models.Ledger, error) {
	_, ledger, err := Ledgers.GetLedgerWithAccess(c, actor, ledgerID, models.FamilyMemberRole.CanWrite)
	return ledger, err
}

// authorizeImportLedger rechecks access inside the financial commit transaction.
func authorizeImportLedger(sess *xorm.Session, actor int64, ledger *models.Ledger) error {
	if ledger.LedgerId == models.DefaultLedgerId {
		return nil
	}
	// Acquire the ledger's write lock before checking membership and accounts.
	if _, err := sess.Where("ledger_id=? AND owner_uid=? AND deleted=?", ledger.LedgerId, ledger.OwnerUid, false).SetExpr("updated_unix_time", "updated_unix_time").Update(&models.Ledger{}); err != nil {
		return err
	}
	if _, err := sess.Where("ledger_id=? AND uid=?", ledger.LedgerId, actor).SetExpr("role", "role").Update(&models.LedgerMember{}); err != nil {
		return err
	}
	live := &models.Ledger{}
	found, err := sess.Where("ledger_id=? AND owner_uid=? AND deleted=?", ledger.LedgerId, ledger.OwnerUid, false).Get(live)
	if err != nil {
		return err
	}
	if !found {
		return errs.ErrLedgerAccessDenied
	}
	member := &models.LedgerMember{}
	found, err = sess.Where("ledger_id=? AND uid=?", ledger.LedgerId, actor).Get(member)
	if err != nil {
		return err
	}
	if found {
		if member.Role.IsValid() && member.CanAct(models.FamilyMemberRole.CanWrite) {
			return nil
		}
		return errs.ErrLedgerAccessDenied
	}
	if live.Type == models.LEDGER_TYPE_PERSONAL && live.OwnerUid == actor {
		return nil
	}
	return errs.ErrLedgerAccessDenied
}

// BatchImportTransactions binds every row to the selected ledger and validates
// live access/account ownership in the same transaction that changes balances.
func (s *TransactionService) BatchImportTransactions(c core.Context, actor, ledgerID int64, rows []*models.Transaction, tags map[int][]int64, progress func(float64)) error {
	ledger, err := ImportLedger(c, actor, ledgerID)
	if err != nil {
		return err
	}
	for i, row := range rows {
		if ledger.OwnerUid != actor && len(tags[i]) > 0 {
			return errs.ErrNotPermittedToPerformThisAction
		}
		row.Uid, row.LedgerId, row.RecorderUid, row.PayerUid = ledger.OwnerUid, ledgerID, actor, actor
	}
	return s.batchCreateTransactionsWithHooks(c, ledger.OwnerUid, rows, tags, progress, func(sess *xorm.Session) error {
		if err := authorizeImportLedger(sess, actor, ledger); err != nil {
			return err
		}
		for _, row := range rows {
			for _, id := range []int64{row.AccountId, row.RelatedAccountId} {
				if id == 0 {
					continue
				}
				a := &models.Account{}
				found, err := sess.Where("uid=? AND account_id=? AND ledger_id=? AND deleted=?", ledger.OwnerUid, id, ledgerID, false).Get(a)
				if err != nil {
					return err
				}
				if !found || a.Hidden || a.Type == models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
					return errs.ErrAccountIdInvalid
				}
				if a.ParentAccountId > 0 {
					parent := &models.Account{}
					found, err = sess.Where("uid=? AND account_id=? AND ledger_id=? AND deleted=?", ledger.OwnerUid, a.ParentAccountId, ledgerID, false).Get(parent)
					if err != nil {
						return err
					}
					if !found || parent.Hidden {
						return errs.ErrAccountIdInvalid
					}
				}
			}
		}
		return nil
	}, nil)
}
