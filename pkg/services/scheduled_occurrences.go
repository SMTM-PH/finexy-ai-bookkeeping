package services

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
	"xorm.io/xorm"
)

type ScheduledOccurrenceService struct{ ServiceUsingDB }

var ScheduledOccurrences = &ScheduledOccurrenceService{ServiceUsingDB: ServiceUsingDB{container: datastore.Container}}
var scheduledOccurrenceLocks [256]sync.Mutex

// Template IDs are globally unique. Lock before resolving ledger membership:
// another confirmer's SQLite write transaction also locks ledger_member reads.
func scheduledOccurrenceLock(templateID, scheduledTime int64) *sync.Mutex {
	key := uint64(templateID)*17 + uint64(scheduledTime)
	return &scheduledOccurrenceLocks[key%uint64(len(scheduledOccurrenceLocks))]
}

func (s *ScheduledOccurrenceService) List(c core.Context, uid int64, status models.ScheduledOccurrenceStatus, offset, limit int) ([]*models.ScheduledOccurrence, error) {
	return s.ListInLedger(c, uid, 0, status, offset, limit)
}

// ListInLedger uses the durable row's ledger rather than the current template,
// so edits/deletion cannot silently move existing captured occurrences.
func (s *ScheduledOccurrenceService) ListInLedger(c core.Context, actor, ledgerID int64, status models.ScheduledOccurrenceStatus, offset, limit int) ([]*models.ScheduledOccurrence, error) {
	if actor <= 0 || status < models.ScheduledOccurrencePending || status > models.ScheduledOccurrenceDismissed || offset < 0 || limit < 1 || limit > 100 {
		return nil, errs.ErrParameterInvalid
	}
	db, ledger, err := resolveTemplateLedger(c, actor, ledgerID, anyFamilyRole)
	if err != nil {
		return nil, err
	}
	rows := make([]*models.ScheduledOccurrence, 0)
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, anyFamilyRole, false); err != nil {
			return err
		}
		return sess.Where("uid=? AND ledger_id=? AND status=?", ledger.OwnerUid, ledgerID, status).Asc("scheduled_unix_time", "template_id").Limit(limit, offset).Find(&rows)
	})
	return rows, err
}
func (s *ScheduledOccurrenceService) SetDismissed(c core.Context, uid, templateID, scheduledTime int64, dismissed bool) error {
	return s.SetDismissedInLedger(c, uid, 0, templateID, scheduledTime, dismissed)
}
func (s *ScheduledOccurrenceService) SetDismissedInLedger(c core.Context, actor, ledgerID, templateID, scheduledTime int64, dismissed bool) error {
	if actor <= 0 || templateID <= 0 || scheduledTime <= 0 {
		return errs.ErrParameterInvalid
	}
	lock := scheduledOccurrenceLock(templateID, scheduledTime)
	lock.Lock()
	defer lock.Unlock()
	db, ledger, err := resolveTemplateLedger(c, actor, ledgerID, models.FamilyMemberRole.CanWrite)
	if err != nil {
		return err
	}
	from, to := models.ScheduledOccurrencePending, models.ScheduledOccurrenceDismissed
	if !dismissed {
		from, to = to, from
	}
	return db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanWrite, true); err != nil {
			return err
		}
		item := &models.ScheduledOccurrence{}
		found, err := sess.Where("uid=? AND ledger_id=? AND template_id=? AND scheduled_unix_time=?", ledger.OwnerUid, ledgerID, templateID, scheduledTime).Get(item)
		if err != nil {
			return err
		}
		if !found {
			return errs.ErrTransactionTemplateNotFound
		}
		if item.Status == to {
			return nil
		}
		if item.Status != from {
			return fmt.Errorf("occurrence state changed; refresh before retrying")
		}
		rows, err := sess.Where("uid=? AND ledger_id=? AND template_id=? AND scheduled_unix_time=? AND status=?", ledger.OwnerUid, ledgerID, templateID, scheduledTime, from).
			Cols("status", "updated_unix_time").Update(&models.ScheduledOccurrence{Status: to, UpdatedUnixTime: time.Now().Unix()})
		if err != nil {
			return err
		}
		if rows != 1 {
			return fmt.Errorf("occurrence state changed; refresh before retrying")
		}
		return nil
	})
}
func (s *ScheduledOccurrenceService) Confirm(c core.Context, uid, templateID, scheduledTime int64) (int64, error) {
	return s.ConfirmInLedger(c, uid, 0, templateID, scheduledTime)
}

// ConfirmInLedger claims and acknowledges a captured occurrence in the same
// transaction as posting. The authenticated confirmer is the recorder/payer;
// the ledger owner remains the financial row's storage UID.
func (s *ScheduledOccurrenceService) ConfirmInLedger(c core.Context, actor, ledgerID, templateID, scheduledTime int64) (int64, error) {
	if actor <= 0 || templateID <= 0 || scheduledTime <= 0 {
		return 0, errs.ErrParameterInvalid
	}
	lock := scheduledOccurrenceLock(templateID, scheduledTime)
	lock.Lock()
	defer lock.Unlock()
	db, ledger, err := resolveTemplateLedger(c, actor, ledgerID, models.FamilyMemberRole.CanWrite)
	if err != nil {
		return 0, err
	}
	read := func() (*models.ScheduledOccurrence, error) {
		item := &models.ScheduledOccurrence{}
		err := db.DoTransaction(c, func(sess *xorm.Session) error {
			if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanWrite, false); err != nil {
				return err
			}
			found, err := sess.Where("uid=? AND ledger_id=? AND template_id=? AND scheduled_unix_time=?", ledger.OwnerUid, ledgerID, templateID, scheduledTime).Get(item)
			if err != nil {
				return err
			}
			if !found {
				return errs.ErrTransactionTemplateNotFound
			}
			return nil
		})
		return item, err
	}
	item, err := read()
	if err != nil {
		return 0, err
	}
	if item.Status == models.ScheduledOccurrenceConfirmed {
		if item.TransactionId <= 0 {
			return 0, errs.ErrOperationFailed
		}
		return item.TransactionId, nil
	}
	if item.Status != models.ScheduledOccurrencePending {
		return 0, fmt.Errorf("scheduled occurrence is not pending")
	}
	var snapshot models.ScheduledOccurrenceSnapshot
	if err := json.Unmarshal([]byte(item.SnapshotJSON), &snapshot); err != nil {
		return 0, errs.ErrOperationFailed
	}
	if err := snapshot.Validate(); err != nil {
		return 0, errs.ErrParameterInvalid
	}
	if snapshot.LedgerId != item.LedgerId {
		return 0, errs.ErrLedgerAccessDenied
	}
	dbType, err := snapshot.Type.ToTransactionDbType()
	if err != nil {
		return 0, err
	}
	tags := make([]int64, 0, len(snapshot.TagIds))
	for _, raw := range snapshot.TagIds {
		id, _ := strconv.ParseInt(raw, 10, 64)
		tags = append(tags, id)
	}
	transaction := &models.Transaction{Uid: ledger.OwnerUid, LedgerId: ledgerID, RecorderUid: actor, PayerUid: actor,
		Type: dbType, CategoryId: snapshot.CategoryId, AccountId: snapshot.SourceAccountId, RelatedAccountId: snapshot.DestinationAccountId,
		Amount: snapshot.SourceAmount, RelatedAccountAmount: snapshot.DestinationAmount, TimezoneUtcOffset: snapshot.UtcOffset,
		TransactionTime: utils.GetMinTransactionTimeFromUnixTime(scheduledTime), HideAmount: snapshot.HideAmount, Comment: snapshot.Comment, ScheduledCreated: true}
	claimedElsewhere := errors.New("occurrence already handled")
	err = Transactions.createTransactionWithHooks(c, transaction, tags, nil, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanWrite, true); err != nil {
			return err
		}
		if _, err := sess.Where("uid=? AND ledger_id=? AND template_id=? AND scheduled_unix_time=?", ledger.OwnerUid, ledgerID, templateID, scheduledTime).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.ScheduledOccurrence)); err != nil {
			return err
		}
		live := &models.ScheduledOccurrence{}
		found, err := sess.Where("uid=? AND ledger_id=? AND template_id=? AND scheduled_unix_time=?", ledger.OwnerUid, ledgerID, templateID, scheduledTime).Get(live)
		if err != nil {
			return err
		}
		if !found {
			return errs.ErrTransactionTemplateNotFound
		}
		if live.Status != models.ScheduledOccurrencePending {
			return claimedElsewhere
		}
		if live.SnapshotJSON != item.SnapshotJSON {
			return errs.ErrParameterInvalid
		}
		if err := validateScheduledSnapshotResources(sess, ledger.OwnerUid, snapshot); err != nil {
			return err
		}
		rows, err := sess.Where("uid=? AND ledger_id=? AND template_id=? AND scheduled_unix_time=? AND status=?", ledger.OwnerUid, ledgerID, templateID, scheduledTime, models.ScheduledOccurrencePending).
			Cols("status").Update(&models.ScheduledOccurrence{Status: models.ScheduledOccurrenceConfirmed})
		if err != nil {
			return err
		}
		if rows != 1 {
			return claimedElsewhere
		}
		return nil
	}, func(sess *xorm.Session) error {
		rows, err := sess.Where("uid=? AND ledger_id=? AND template_id=? AND scheduled_unix_time=? AND status=?", ledger.OwnerUid, ledgerID, templateID, scheduledTime, models.ScheduledOccurrenceConfirmed).
			Cols("transaction_id", "updated_unix_time").Update(&models.ScheduledOccurrence{TransactionId: transaction.TransactionId, UpdatedUnixTime: time.Now().Unix()})
		if err != nil {
			return err
		}
		if rows != 1 {
			return fmt.Errorf("occurrence acknowledgement failed")
		}
		return nil
	})
	if errors.Is(err, claimedElsewhere) {
		current, readErr := read()
		if readErr == nil && current.Status == models.ScheduledOccurrenceConfirmed && current.TransactionId > 0 {
			return current.TransactionId, nil
		}
	}
	if err != nil {
		return 0, err
	}
	return transaction.TransactionId, nil
}
func validOccurrenceDispatch(template *models.TransactionTemplate, scheduledTime int64) bool {
	return template != nil && template.Uid > 0 && template.LedgerId >= 0 && template.TemplateId > 0 && scheduledTime > 0 &&
		template.TemplateType == models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE && !template.Deleted &&
		template.ScheduledFrequencyType != models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DISABLED &&
		template.Type >= models.TRANSACTION_TYPE_INCOME && template.Type <= models.TRANSACTION_TYPE_TRANSFER &&
		(template.ScheduledStartTime == nil || scheduledTime >= *template.ScheduledStartTime) &&
		(template.ScheduledEndTime == nil || scheduledTime <= *template.ScheduledEndTime)
}

// Enqueue rechecks the actual persisted template in the ledger transaction.
// Dispatch only creates a durable review snapshot; it never changes balances.
func (s *ScheduledOccurrenceService) Enqueue(c core.Context, template *models.TransactionTemplate, scheduledTime int64) (*models.ScheduledOccurrence, error) {
	if !validOccurrenceDispatch(template, scheduledTime) {
		return nil, fmt.Errorf("invalid scheduled occurrence")
	}
	lock := scheduledOccurrenceLock(template.TemplateId, scheduledTime)
	lock.Lock()
	defer lock.Unlock()
	db, ledger, err := resolveTemplateLedger(c, template.Uid, template.LedgerId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}
	if ledger.OwnerUid != template.Uid {
		return nil, errs.ErrLedgerAccessDenied
	}
	var item *models.ScheduledOccurrence
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, template.Uid, ledger, models.FamilyMemberRole.CanManage, true); err != nil {
			return err
		}
		live := &models.TransactionTemplate{}
		if _, err := sess.Where("template_id=? AND uid=? AND ledger_id=?", template.TemplateId, template.Uid, template.LedgerId).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.TransactionTemplate)); err != nil {
			return err
		}
		found, err := sess.Where("template_id=? AND uid=? AND ledger_id=? AND deleted=?", template.TemplateId, template.Uid, template.LedgerId, false).Get(live)
		if err != nil {
			return err
		}
		if !found {
			return errs.ErrTransactionTemplateNotFound
		}
		if !validOccurrenceDispatch(live, scheduledTime) {
			return fmt.Errorf("scheduled template is no longer active")
		}
		next := live.NextScheduledUnixTime(time.Unix(scheduledTime-1, 0))
		if next == nil || *next != scheduledTime {
			return fmt.Errorf("scheduled time no longer matches the template rule")
		}

		existing := &models.ScheduledOccurrence{}
		found, err = sess.Where("uid=? AND ledger_id=? AND template_id=? AND scheduled_unix_time=?", template.Uid, template.LedgerId, template.TemplateId, scheduledTime).Get(existing)
		if err != nil {
			return err
		}
		if found {
			item = existing
			return nil
		}
		snapshot := templateSnapshot(live)
		if err := snapshot.Validate(); err != nil {
			return errs.ErrParameterInvalid
		}
		if err := validateScheduledSnapshotResources(sess, live.Uid, snapshot); err != nil {
			return err
		}
		data, err := json.Marshal(snapshot)
		if err != nil {
			return err
		}
		now := time.Now().Unix()
		item = &models.ScheduledOccurrence{Uid: live.Uid, LedgerId: live.LedgerId, TemplateId: live.TemplateId, ScheduledUnixTime: scheduledTime,
			Status: models.ScheduledOccurrencePending, SnapshotJSON: string(data), CreatedUnixTime: now, UpdatedUnixTime: now}
		_, err = sess.Insert(item)
		return err
	})
	if err == nil {
		return item, nil
	}
	// Independent dispatchers are also protected by the composite primary key.
	// A fresh transaction is mandatory after a PostgreSQL duplicate-key rollback.
	var existing *models.ScheduledOccurrence
	readErr := db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, template.Uid, ledger, models.FamilyMemberRole.CanManage, false); err != nil {
			return err
		}
		row := &models.ScheduledOccurrence{}
		found, e := sess.Where("uid=? AND ledger_id=? AND template_id=? AND scheduled_unix_time=?", template.Uid, template.LedgerId, template.TemplateId, scheduledTime).Get(row)
		if e != nil {
			return e
		}
		if found {
			existing = row
		}
		return nil
	})
	if readErr == nil && existing != nil {
		return existing, nil
	}
	return nil, err
}

// pendingScheduledSnapshots fails closed on an incomplete durable capture.
// A corrupt queue is not proof that a private resource has no references.
func pendingScheduledSnapshots(sess *xorm.Session, owner int64) ([]models.ScheduledOccurrenceSnapshot, error) {
	rows := make([]*models.ScheduledOccurrence, 0)
	if err := sess.Where("uid=?", owner).In("status", []models.ScheduledOccurrenceStatus{models.ScheduledOccurrencePending, models.ScheduledOccurrenceDismissed}).Find(&rows); err != nil {
		return nil, err
	}
	snapshots := make([]models.ScheduledOccurrenceSnapshot, 0, len(rows))
	for _, row := range rows {
		var snapshot models.ScheduledOccurrenceSnapshot
		if err := json.Unmarshal([]byte(row.SnapshotJSON), &snapshot); err != nil || snapshot.Validate() != nil || snapshot.LedgerId != row.LedgerId {
			return nil, errs.ErrOperationFailed
		}
		snapshots = append(snapshots, snapshot)
	}
	return snapshots, nil
}
