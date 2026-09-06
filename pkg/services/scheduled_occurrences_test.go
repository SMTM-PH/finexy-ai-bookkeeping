package services

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"testing"
)

func TestOccurrenceQueriesRejectMissingIdentityAndUnboundedPages(t *testing.T) {
	s := &ScheduledOccurrenceService{}
	for _, input := range []struct {
		uid           int64
		offset, limit int
	}{{0, 0, 10}, {1, -1, 10}, {1, 0, 0}, {1, 0, 101}} {
		if _, err := s.List(nil, input.uid, models.ScheduledOccurrencePending, input.offset, input.limit); err == nil {
			t.Fatal("expected invalid query to fail")
		}
	}
	if err := s.SetDismissed(nil, 0, 1, 1, true); err == nil {
		t.Fatal("missing identity must fail")
	}
	if _, err := s.Confirm(nil, 0, 1, 1); err == nil {
		t.Fatal("missing identity must fail")
	}
}

func TestScheduledEnqueueRejectsInactiveTemplatesBeforeDatabaseAccess(t *testing.T) {
	active := models.TransactionTemplate{Uid: 1, TemplateId: 2, TemplateType: models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE,
		Type: models.TRANSACTION_TYPE_EXPENSE, ScheduledFrequencyType: models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY}
	paused := active
	paused.ScheduledFrequencyType = models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DISABLED
	deleted := active
	deleted.Deleted = true
	ended := active
	end := int64(10)
	ended.ScheduledEndTime = &end
	future := active
	start := int64(30)
	future.ScheduledStartTime = &start
	s := &ScheduledOccurrenceService{}
	for _, template := range []*models.TransactionTemplate{nil, &paused, &deleted, &ended, &future} {
		if item, err := s.Enqueue(nil, template, 20); err == nil || item != nil {
			t.Fatal("inactive template must not enter review queue")
		}
	}
}
