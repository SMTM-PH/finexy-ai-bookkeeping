package api

import (
	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/services"
)

type AIReviewItemsApi struct{ items *services.AIReviewItemService }

var AIReviewItems = &AIReviewItemsApi{items: services.AIReviewItems}

func (a *AIReviewItemsApi) ListHandler(c *core.WebContext) (any, *errs.Error) {
	items, err := a.items.ListPending(c, c.GetCurrentUid())
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
	item, err := a.items.Create(c, c.GetCurrentUid(), request.SourceType, request.SourceText, request.RecognizedData, request.FailureReason)
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

func (a *AIReviewItemsApi) DeleteHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.AIReviewItemStatusRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	if err := a.items.Delete(c, c.GetCurrentUid(), request.Id); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return true, nil
}

func (a *AIReviewItemsApi) updateStatus(c *core.WebContext, status models.AIReviewStatus) (any, *errs.Error) {
	var request models.AIReviewItemStatusRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	if err := a.items.UpdateStatus(c, c.GetCurrentUid(), request.Id, status); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return true, nil
}
