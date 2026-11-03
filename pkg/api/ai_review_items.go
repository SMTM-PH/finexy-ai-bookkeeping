package api

import (
	"strconv"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
)

type AIReviewItemsApi struct{ items *services.AIReviewItemService }

var AIReviewItems = &AIReviewItemsApi{items: services.AIReviewItems}

// This contract describes the selected ledger and never grants permissions.
func (a *AIReviewItemsApi) CapabilitiesHandler(c *core.WebContext) (any, *errs.Error) {
	c.Header("Cache-Control", "no-store")
	var request struct {
		LedgerId int64 `form:"ledgerId,string" binding:"min=0"`
	}
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	// Recheck current membership in the owner shard without exposing queue data.
	if _, err := a.items.ListInLedger(c, c.GetCurrentUid(), request.LedgerId, models.AI_REVIEW_STATUS_PENDING, 0, 1); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return core.O{"ledgerId": strconv.FormatInt(request.LedgerId, 10), "contractVersion": 1, "recognitionLedgerContext": true,
		"durableCreation": true, "atomicConfirmation": true, "payloadBinding": true, "deletedReceipts": true,
		"maxClientSessionIdBytes": 128, "maxPageSize": 100, "pagination": "beforeId",
		"reviewerScope": "manager-or-creator", "confirmationPath": "v1/ai/review/confirm.json"}, nil
}

func (a *AIReviewItemsApi) ListHandler(c *core.WebContext) (any, *errs.Error) {
	request := struct {
		LedgerId int64                 `form:"ledgerId,string" binding:"min=0"`
		Status   models.AIReviewStatus `form:"status" binding:"min=1,max=3"`
		BeforeId int64                 `form:"beforeId,string" binding:"min=0"`
		Limit    int                   `form:"limit" binding:"min=1,max=100"`
	}{Status: models.AI_REVIEW_STATUS_PENDING, Limit: 100}
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	items, err := a.items.ListInLedger(c, c.GetCurrentUid(), request.LedgerId, request.Status, request.BeforeId, request.Limit)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	result := make([]*models.AIReviewItemInfoResponse, 0, len(items))
	for _, item := range items {
		result = append(result, item.ToInfoResponse())
	}
	return result, nil
}

func (a *AIReviewItemsApi) CreateHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.AIReviewItemCreateRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	item, err := a.items.CreateInLedger(c, c.GetCurrentUid(), request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return item.ToInfoResponse(), nil
}

func (a *AIReviewItemsApi) ResolveHandler(c *core.WebContext) (any, *errs.Error) {
	return a.updateStatus(c, models.AI_REVIEW_STATUS_RESOLVED)
}

func (a *AIReviewItemsApi) DismissHandler(c *core.WebContext) (any, *errs.Error) {
	return a.updateStatus(c, models.AI_REVIEW_STATUS_DISMISSED)
}

func (a *AIReviewItemsApi) RestoreHandler(c *core.WebContext) (any, *errs.Error) {
	return a.updateStatus(c, models.AI_REVIEW_STATUS_PENDING)
}

func (a *AIReviewItemsApi) GetHandler(c *core.WebContext) (any, *errs.Error) {
	var request struct {
		LedgerId int64 `form:"ledgerId,string" binding:"min=0"`
		Id       int64 `form:"id,string" binding:"required,min=1"`
	}
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	item, err := a.items.GetInLedger(c, c.GetCurrentUid(), request.LedgerId, request.Id)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return item.ToInfoResponse(), nil
}

func (a *AIReviewItemsApi) ConfirmHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.AIReviewItemConfirmRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	timezone, err := c.GetClientTimezone()
	if err != nil {
		return nil, errs.ErrClientTimezoneOffsetInvalid
	}
	id, err := a.items.ConfirmInLedger(c, c.GetCurrentUid(), request, c.ClientIP(), timezone)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return core.O{"id": strconv.FormatInt(request.Id, 10), "ledgerId": strconv.FormatInt(request.LedgerId, 10), "transactionId": strconv.FormatInt(id, 10)}, nil
}

func (a *AIReviewItemsApi) DeleteHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.AIReviewItemStatusRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	if err := a.items.DeleteInLedger(c, c.GetCurrentUid(), request.LedgerId, request.Id); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return true, nil
}

func (a *AIReviewItemsApi) updateStatus(c *core.WebContext, status models.AIReviewStatus) (any, *errs.Error) {
	var request models.AIReviewItemStatusRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	if err := a.items.UpdateStatusInLedger(c, c.GetCurrentUid(), request.LedgerId, request.Id, status); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return true, nil
}
