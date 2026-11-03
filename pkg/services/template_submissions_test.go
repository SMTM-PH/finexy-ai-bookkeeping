package services

import (
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
)

func TestTemplateSubmissionHashBindsEverySubmittedField(t *testing.T) {
	start, end := int64(100), int64(200)
	base := models.TransactionTemplate{TemplateType: models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE, Type: models.TRANSACTION_TYPE_TRANSFER,
		Name: "Synthetic", AccountId: 1, RelatedAccountId: 2, CategoryId: 3, Amount: 100,
		RelatedAccountAmount: 200, TagIds: "4,5", Comment: "original", HideAmount: true,
		ScheduledFrequencyType: models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY, ScheduledFrequency: "0",
		ScheduledStartTime: &start, ScheduledEndTime: &end, ScheduledAt: 960, ScheduledTimezoneUtcOffset: 480}
	original, err := templateSubmissionHash(&base)
	require.NoError(t, err)
	changes := map[string]func(*models.TransactionTemplate){
		"kind":               func(r *models.TransactionTemplate) { r.TemplateType = models.TRANSACTION_TEMPLATE_TYPE_NORMAL },
		"type":               func(r *models.TransactionTemplate) { r.Type = models.TRANSACTION_TYPE_EXPENSE },
		"name":               func(r *models.TransactionTemplate) { r.Name += " edited" },
		"source":             func(r *models.TransactionTemplate) { r.AccountId++ },
		"destination":        func(r *models.TransactionTemplate) { r.RelatedAccountId++ },
		"category":           func(r *models.TransactionTemplate) { r.CategoryId++ },
		"amount":             func(r *models.TransactionTemplate) { r.Amount++ },
		"destination amount": func(r *models.TransactionTemplate) { r.RelatedAccountAmount++ },
		"tags":               func(r *models.TransactionTemplate) { r.TagIds = "4,6" },
		"comment":            func(r *models.TransactionTemplate) { r.Comment += " edited" },
		"hide amount":        func(r *models.TransactionTemplate) { r.HideAmount = false },
		"frequency type":     func(r *models.TransactionTemplate) { r.ScheduledFrequencyType++ },
		"frequency":          func(r *models.TransactionTemplate) { r.ScheduledFrequency = "1" },
		"start":              func(r *models.TransactionTemplate) { v := start + 1; r.ScheduledStartTime = &v },
		"end":                func(r *models.TransactionTemplate) { r.ScheduledEndTime = nil },
		"scheduled time":     func(r *models.TransactionTemplate) { r.ScheduledAt++ },
		"timezone":           func(r *models.TransactionTemplate) { r.ScheduledTimezoneUtcOffset++ },
	}
	for name, change := range changes {
		t.Run(name, func(t *testing.T) {
			row := base
			change(&row)
			hash, err := templateSubmissionHash(&row)
			require.NoError(t, err)
			require.NotEqual(t, original, hash)
		})
	}
	generated := base
	generated.TemplateId, generated.Uid, generated.LedgerId = 1000, 2000, 3000
	generated.DisplayOrder, generated.Hidden, generated.Deleted = 7, true, true
	generated.CreatedUnixTime, generated.UpdatedUnixTime, generated.DeletedUnixTime = 8, 9, 10
	hash, err := templateSubmissionHash(&generated)
	require.NoError(t, err)
	require.Equal(t, original, hash, "Generated fields and scope columns must not change the request identity")
}
