package api

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
	"net/http"
)

type AccountCardApi struct{}

var AccountCards = &AccountCardApi{}

func (a *AccountCardApi) GetHandler(c *core.WebContext) (any, *errs.Error) {
	if c.GetTokenClaims().Type != core.USER_TOKEN_TYPE_NORMAL {
		return nil, errs.ErrAgentPermissionDenied
	}
	var req models.AccountCardTarget
	if c.ShouldBindQuery(&req) != nil {
		return nil, errs.ErrParameterInvalid
	}
	style, err := services.AccountCards.Get(c, c.GetCurrentUid(), *req.LedgerId, req.AccountId)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	c.Header("Cache-Control", "no-store")
	return style, nil
}

func (a *AccountCardApi) UpdateHandler(c *core.WebContext) (any, *errs.Error) {
	if c.GetTokenClaims().Type != core.USER_TOKEN_TYPE_NORMAL {
		return nil, errs.ErrAgentPermissionDenied
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 1500000)
	var req models.AccountCardUpdateRequest
	if c.ShouldBindJSON(&req) != nil {
		return nil, errs.ErrParameterInvalid
	}
	style, err := services.AccountCards.Save(c, c.GetCurrentUid(), *req.LedgerId, req.AccountId, *req.Appearance)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return style, nil
}
