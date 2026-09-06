package api

import (
	"encoding/json"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
	"strconv"
)

type ScheduledOccurrencesApi struct{}

var ScheduledOccurrences = &ScheduledOccurrencesApi{}

type occurrenceRequest struct {
	TemplateId        int64 `json:"templateId,string" binding:"required,min=1"`
	ScheduledUnixTime int64 `json:"scheduledUnixTime" binding:"required,min=1"`
}

func (a *ScheduledOccurrencesApi) ListHandler(c *core.WebContext) (any, *errs.Error) {
	request := struct {
		Status models.ScheduledOccurrenceStatus `form:"status" binding:"min=1,max=3"`
		Offset int                              `form:"offset" binding:"min=0"`
		Limit  int                              `form:"limit" binding:"min=1,max=100"`
	}{Status: models.ScheduledOccurrencePending, Limit: 50}
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	items, err := services.ScheduledOccurrences.List(c, c.GetCurrentUid(), request.Status, request.Offset, request.Limit)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	result := make([]core.O, 0, len(items))
	for _, item := range items {
		var snapshot models.ScheduledOccurrenceSnapshot
		if err := json.Unmarshal([]byte(item.SnapshotJSON), &snapshot); err != nil {
			return nil, errs.ErrOperationFailed
		}
		result = append(result, core.O{"templateId": strconv.FormatInt(item.TemplateId, 10), "scheduledUnixTime": item.ScheduledUnixTime,
			"status": item.Status, "transactionId": strconv.FormatInt(item.TransactionId, 10), "snapshot": snapshot})
	}
	return result, nil
}

func (a *ScheduledOccurrencesApi) ConfirmHandler(c *core.WebContext) (any, *errs.Error) {
	var request occurrenceRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	id, err := services.ScheduledOccurrences.Confirm(c, c.GetCurrentUid(), request.TemplateId, request.ScheduledUnixTime)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return core.O{"transactionId": strconv.FormatInt(id, 10)}, nil
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
	if err := services.ScheduledOccurrences.SetDismissed(c, c.GetCurrentUid(), request.TemplateId, request.ScheduledUnixTime, dismissed); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return true, nil
}
