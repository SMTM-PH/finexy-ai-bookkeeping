package services

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
	"xorm.io/xorm"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
)

type ScheduledOccurrenceService struct{ ServiceUsingDB }

var ScheduledOccurrences = &ScheduledOccurrenceService{
	ServiceUsingDB: ServiceUsingDB{container: datastore.Container},
}

// List is bounded and deterministically ordered; the authenticated UID is mandatory.
func (s *ScheduledOccurrenceService) List(c core.Context, uid int64, status models.ScheduledOccurrenceStatus, offset, limit int) ([]*models.ScheduledOccurrence, error) {
	if uid <= 0 || status < models.ScheduledOccurrencePending || status > models.ScheduledOccurrenceDismissed || offset < 0 || limit < 1 || limit > 100 {
		return nil, fmt.Errorf("invalid occurrence query")
	}
	items := make([]*models.ScheduledOccurrence, 0)
	session := s.UserDataDB(uid).NewSession(c)
	defer session.Close()
	err := session.Where("uid=? AND status=?", uid, status).Asc("scheduled_unix_time", "template_id").Limit(limit, offset).Find(&items)
	return items, err
}

// SetDismissed only transitions pending <-> dismissed; confirmed records are immutable.
func (s *ScheduledOccurrenceService) SetDismissed(c core.Context, uid, templateId, scheduledUnixTime int64, dismissed bool) error {
	if uid <= 0 || templateId <= 0 || scheduledUnixTime <= 0 {
		return fmt.Errorf("invalid occurrence identity")
	}
	from, to := models.ScheduledOccurrencePending, models.ScheduledOccurrenceDismissed
	if !dismissed {
		from, to = to, from
	}
	session := s.UserDataDB(uid).NewSession(c)
	defer session.Close()
	rows, err := session.Where("uid=? AND template_id=? AND scheduled_unix_time=? AND status=?", uid, templateId, scheduledUnixTime, from).
		Cols("status", "updated_unix_time").Update(&models.ScheduledOccurrence{Status: to, UpdatedUnixTime: time.Now().Unix()})
	if err != nil {
		return err
	}
	if rows == 1 {
		return nil
	}
	lookup := s.UserDataDB(uid).NewSession(c)
	defer lookup.Close()
	item := &models.ScheduledOccurrence{}
	found, err := lookup.Where("uid=? AND template_id=? AND scheduled_unix_time=?", uid, templateId, scheduledUnixTime).Get(item)
	if err != nil {
		return err
	}
	if found && item.Status == to {
		return nil
	}
	return fmt.Errorf("occurrence not found or state changed; refresh before retrying")
}

// Confirm posts the captured values once. Caller identity must come from authentication.
func (s *ScheduledOccurrenceService) Confirm(c core.Context, uid, templateId, scheduledUnixTime int64) (int64, error) {
	if uid <= 0 || templateId <= 0 || scheduledUnixTime <= 0 {
		return 0, fmt.Errorf("invalid occurrence identity")
	}
	read := func() (*models.ScheduledOccurrence, error) {
		session := s.UserDataDB(uid).NewSession(c)
		defer session.Close()
		item := &models.ScheduledOccurrence{}
		found, err := session.Where("uid=? AND template_id=? AND scheduled_unix_time=?", uid, templateId, scheduledUnixTime).Get(item)
		if err != nil {
			return nil, err
		}
		if !found {
			return nil, fmt.Errorf("scheduled occurrence not found")
		}
		return item, nil
	}
	item, err := read()
	if err != nil {
		return 0, err
	}
	if item.Status == models.ScheduledOccurrenceConfirmed {
		return item.TransactionId, nil
	}
	if item.Status != models.ScheduledOccurrencePending {
		return 0, fmt.Errorf("scheduled occurrence is not pending")
	}
	var snapshot models.ScheduledOccurrenceSnapshot
	if err := json.Unmarshal([]byte(item.SnapshotJSON), &snapshot); err != nil {
		return 0, err
	}
	if err := snapshot.Validate(); err != nil {
		return 0, err
	}
	if snapshot.Type < models.TRANSACTION_TYPE_INCOME || snapshot.Type > models.TRANSACTION_TYPE_TRANSFER {
		return 0, fmt.Errorf("invalid scheduled transaction type")
	}
	dbType, err := snapshot.Type.ToTransactionDbType()
	if err != nil {
		return 0, err
	}
	tags := make([]int64, 0, len(snapshot.TagIds))
	for _, raw := range snapshot.TagIds {
		id, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || id <= 0 {
			return 0, fmt.Errorf("invalid scheduled tag ID")
		}
		tags = append(tags, id)
	}
	transaction := &models.Transaction{Uid: uid, Type: dbType, CategoryId: snapshot.CategoryId,
		AccountId: snapshot.SourceAccountId, RelatedAccountId: snapshot.DestinationAccountId,
		Amount: snapshot.SourceAmount, RelatedAccountAmount: snapshot.DestinationAmount,
		TimezoneUtcOffset: snapshot.UtcOffset, TransactionTime: utils.GetMinTransactionTimeFromUnixTime(scheduledUnixTime),
		HideAmount: snapshot.HideAmount, Comment: snapshot.Comment, ScheduledCreated: true}
	claimedElsewhere := errors.New("occurrence already handled")
	err = Transactions.createTransactionWithHooks(c, transaction, tags, nil, func(session *xorm.Session) error {
		rows, err := session.Where("uid=? AND template_id=? AND scheduled_unix_time=? AND status=?", uid, templateId, scheduledUnixTime, models.ScheduledOccurrencePending).
			Cols("status").Update(&models.ScheduledOccurrence{Status: models.ScheduledOccurrenceConfirmed})
		if err != nil {
			return err
		}
		if rows != 1 {
			return claimedElsewhere
		}
		return nil
	}, func(session *xorm.Session) error {
		rows, err := session.Where("uid=? AND template_id=? AND scheduled_unix_time=?", uid, templateId, scheduledUnixTime).
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

// Enqueue preserves the first snapshot even if a template changes during retry.
// It never posts a transaction or updates an account balance.
func (s *ScheduledOccurrenceService) Enqueue(c core.Context, template *models.TransactionTemplate, scheduledUnixTime int64) (*models.ScheduledOccurrence, error) {
	if template == nil || template.Uid <= 0 || template.TemplateId <= 0 || scheduledUnixTime <= 0 ||
		template.TemplateType != models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE || template.Deleted ||
		template.ScheduledFrequencyType == models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DISABLED ||
		template.Type < models.TRANSACTION_TYPE_INCOME || template.Type > models.TRANSACTION_TYPE_TRANSFER {
		return nil, fmt.Errorf("invalid scheduled occurrence")
	}
	if (template.ScheduledStartTime != nil && scheduledUnixTime < *template.ScheduledStartTime) ||
		(template.ScheduledEndTime != nil && scheduledUnixTime > *template.ScheduledEndTime) {
		return nil, fmt.Errorf("scheduled occurrence is outside template date range")
	}
	tags := []string{}
	if template.TagIds != "" {
		tags = strings.Split(template.TagIds, ",")
	}
	snapshot, err := json.Marshal(models.ScheduledOccurrenceSnapshot{
		Name: template.Name, Type: template.Type, CategoryId: template.CategoryId,
		SourceAccountId: template.AccountId, DestinationAccountId: template.RelatedAccountId,
		SourceAmount: template.Amount, DestinationAmount: template.RelatedAccountAmount,
		UtcOffset: template.ScheduledTimezoneUtcOffset, HideAmount: template.HideAmount,
		TagIds: tags, Comment: template.Comment,
	})
	if err != nil {
		return nil, err
	}
	now := time.Now().Unix()
	item := &models.ScheduledOccurrence{Uid: template.Uid, TemplateId: template.TemplateId,
		ScheduledUnixTime: scheduledUnixTime, Status: models.ScheduledOccurrencePending,
		SnapshotJSON: string(snapshot), CreatedUnixTime: now, UpdatedUnixTime: now}
	db := s.UserDataDB(template.Uid)
	session := db.NewSession(c)
	_, err = session.Insert(item)
	session.Close()
	if err == nil {
		return item, nil
	}
	// Read using a fresh session after a duplicate-key failure (including PostgreSQL).
	lookup := db.NewSession(c)
	defer lookup.Close()
	existing := &models.ScheduledOccurrence{}
	found, lookupErr := lookup.Where("uid=? AND template_id=? AND scheduled_unix_time=?", template.Uid, template.TemplateId, scheduledUnixTime).Get(existing)
	if lookupErr != nil || !found {
		return nil, err
	}
	return existing, nil
}
