package services

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strings"
	"sync"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
	"xorm.io/xorm"
)

var templateSubmissionLocks [64]sync.Mutex

func templateSubmissionHash(row *models.TransactionTemplate) (string, error) {
	// Exclude generated IDs, timestamps, visibility and display order. Keep all
	// submitted template fields, including the complete normalized schedule.
	snapshot := struct {
		Kind                   models.TransactionTemplateType
		Type                   models.TransactionType
		Name, Tags, Comment    string
		Account, Destination   int64
		Category, Amount       int64
		DestinationAmount      int64
		HideAmount             bool
		FrequencyType          models.TransactionScheduleFrequencyType
		Frequency              string
		Start, End             *int64
		ScheduledAt, UtcOffset int16
	}{row.TemplateType, row.Type, row.Name, row.TagIds, row.Comment,
		row.AccountId, row.RelatedAccountId, row.CategoryId, row.Amount,
		row.RelatedAccountAmount, row.HideAmount, row.ScheduledFrequencyType,
		row.ScheduledFrequency, row.ScheduledStartTime, row.ScheduledEndTime,
		row.ScheduledAt, row.ScheduledTimezoneUtcOffset}
	data, err := json.Marshal(snapshot)
	if err != nil {
		return "", errs.ErrParameterInvalid
	}
	hash := sha256.Sum256(data)
	return hex.EncodeToString(hash[:]), nil
}

func replayTemplateSubmission(sess *xorm.Session, owner, actor, ledgerID int64, key, hash string, row *models.TransactionTemplate) (bool, error) {
	receipt := &models.TemplateSubmission{}
	found, err := sess.Where("uid=? AND creator_uid=? AND ledger_id=? AND client_session_id=?", owner, actor, ledgerID, key).Get(receipt)
	if err != nil || !found {
		return false, err
	}
	if receipt.RequestHash != hash {
		return false, errs.ErrParameterInvalid
	}
	stored := &models.TransactionTemplate{}
	// Include tombstones. A deleted result acknowledges the original operation
	// and must never become a second creation or an indefinitely unknown request.
	found, err = sess.Where("uid=? AND ledger_id=? AND template_id=?", owner, ledgerID, receipt.TemplateId).Get(stored)
	if err != nil {
		return false, err
	}
	if !found {
		return false, errs.ErrTransactionTemplateNotFound
	}
	*row = *stored
	return true, nil
}

// CreateTemplateIdempotent records the immutable request and its result in the
// same transaction as the template. An empty key preserves legacy creation.
func (s *TransactionTemplateService) CreateTemplateIdempotent(c core.Context, actor int64, row *models.TransactionTemplate, key string) error {
	if row == nil {
		return errs.ErrTransactionTemplateNotFound
	}
	if len(key) > 128 || (key != "" && strings.TrimSpace(key) == "") {
		return errs.ErrParameterInvalid
	}
	// Serialize before the access preflight too: SQLite readers of the member
	// table must not race our own writer while resolving the ledger's owner.
	lockScope := row.LedgerId
	if lockScope == models.DefaultLedgerId {
		lockScope = actor
	}
	lock := &templateSubmissionLocks[uint64(lockScope)%uint64(len(templateSubmissionLocks))]
	lock.Lock()
	defer lock.Unlock()
	db, ledger, err := resolveTemplateLedger(c, actor, row.LedgerId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return err
	}
	row.Uid = ledger.OwnerUid
	hash, err := templateSubmissionHash(row)
	if err != nil {
		return err
	}
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanManage, true); err != nil {
			return err
		}
		if key != "" {
			if replayed, err := replayTemplateSubmission(sess, ledger.OwnerUid, actor, ledger.LedgerId, key, hash, row); err != nil || replayed {
				return err
			}
		}
		if err := s.isTemplateValid(sess, row); err != nil {
			return err
		}
		if row.DisplayOrder < 0 {
			last := &models.TransactionTemplate{}
			found, err := sess.Where("uid=? AND ledger_id=? AND template_type=? AND deleted=?", ledger.OwnerUid, ledger.LedgerId, row.TemplateType, false).
				OrderBy("display_order DESC").Get(last)
			if err != nil {
				return err
			}
			if found && last.DisplayOrder == 2147483647 {
				return errs.ErrParameterInvalid
			}
			row.DisplayOrder = 1
			if found && last.DisplayOrder >= 0 {
				row.DisplayOrder = last.DisplayOrder + 1
			}
		}
		row.TemplateId = s.GenerateUuid(uuid.UUID_TYPE_TEMPLATE)
		if row.TemplateId < 1 {
			return errs.ErrSystemIsBusy
		}
		row.Deleted, row.DeletedUnixTime = false, 0
		row.CreatedUnixTime, row.UpdatedUnixTime = time.Now().Unix(), time.Now().Unix()
		if key != "" {
			_, err := sess.Insert(&models.TemplateSubmission{Uid: ledger.OwnerUid, CreatorUid: actor, LedgerId: ledger.LedgerId,
				ClientSessionId: key, RequestHash: hash, TemplateId: row.TemplateId, CreatedUnixTime: row.CreatedUnixTime})
			if err != nil {
				return err
			}
		}
		_, err := sess.Insert(row)
		return err
	})
	if err == nil || key == "" {
		return err
	}
	// The composite primary key protects independent service instances too.
	// After a rollback, acknowledge a winning commit without creating again;
	// any unresolved database error remains retryable with the same request.
	originalErr := err
	replayed := false
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanManage, true); err != nil {
			return err
		}
		var err error
		replayed, err = replayTemplateSubmission(sess, ledger.OwnerUid, actor, ledger.LedgerId, key, hash, row)
		return err
	})
	if err != nil {
		return err
	}
	if replayed {
		return nil
	}
	return originalErr
}
