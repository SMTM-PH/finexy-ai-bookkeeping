package api

import (
	"encoding/json"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/log"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
	"strconv"
)

type ScheduledOccurrencesApi struct{}

var ScheduledOccurrences = &ScheduledOccurrencesApi{}

type occurrenceRequest struct {
	LedgerId          int64 `json:"ledgerId,string" binding:"min=0"`
	TemplateId        int64 `json:"templateId,string" binding:"required,min=1"`
	ScheduledUnixTime int64 `json:"scheduledUnixTime" binding:"required,min=1"`
}

func (a *ScheduledOccurrencesApi) ListHandler(c *core.WebContext) (any, *errs.Error) {
	request := struct {
		LedgerId int64                            `form:"ledgerId,string" binding:"min=0"`
		Status   models.ScheduledOccurrenceStatus `form:"status" binding:"min=1,max=3"`
		Offset   int                              `form:"offset" binding:"min=0"`
		Limit    int                              `form:"limit" binding:"min=1,max=100"`
	}{Status: models.ScheduledOccurrencePending, Limit: 50}
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	items, err := services.ScheduledOccurrences.ListInLedger(c, c.GetCurrentUid(), request.LedgerId, request.Status, request.Offset, request.Limit)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	result := make([]core.O, 0, len(items))
	for _, item := range items {
		var snapshot models.ScheduledOccurrenceSnapshot
		if err := json.Unmarshal([]byte(item.SnapshotJSON), &snapshot); err != nil || snapshot.Validate() != nil || snapshot.LedgerId != item.LedgerId {
			return nil, errs.ErrOperationFailed
		}
		result = append(result, core.O{"ledgerId": strconv.FormatInt(item.LedgerId, 10), "templateId": strconv.FormatInt(item.TemplateId, 10), "scheduledUnixTime": item.ScheduledUnixTime,
			"status": item.Status, "transactionId": strconv.FormatInt(item.TransactionId, 10), "snapshot": snapshot})
	}
	return result, nil
}

func (a *ScheduledOccurrencesApi) ConfirmHandler(c *core.WebContext) (any, *errs.Error) {
	var request occurrenceRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	id, err := services.ScheduledOccurrences.ConfirmInLedger(c, c.GetCurrentUid(), request.LedgerId, request.TemplateId, request.ScheduledUnixTime)
	if err != nil {
		log.Errorf(c, "[scheduled_occurrences.ConfirmHandler] confirmation failed for ledger %d and template %d: %s", request.LedgerId, request.TemplateId, err.Error())
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return core.O{"ledgerId": strconv.FormatInt(request.LedgerId, 10), "transactionId": strconv.FormatInt(id, 10)}, nil
}

func (a *ScheduledOccurrencesApi) DismissHandler(c *core.WebContext) (any, *errs.Error) {
	return a.setDismissed(c, true)
}
func (a *ScheduledOccurrencesApi) RestoreHandler(c *core.WebContext) (any, *errs.Error) {
	return a.setDismissed(c, false)
}
func (a *ScheduledOccurrencesApi) setDismissed(c *core.WebContext, dismissed bool) (any, *errs.Error) {
	var request occurrenceRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	if err := services.ScheduledOccurrences.SetDismissedInLedger(c, c.GetCurrentUid(), request.LedgerId, request.TemplateId, request.ScheduledUnixTime, dismissed); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return true, nil
}
