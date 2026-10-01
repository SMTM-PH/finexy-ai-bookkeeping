package api

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"net/http"
)

type AgentImportsApi struct{ ApiUsingConfig }

var AgentImports = &AgentImportsApi{ApiUsingConfig: ApiUsingConfig{container: settings.Container}}

func (a *AgentImportsApi) PreviewHandler(c *core.WebContext) (any, *errs.Error) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 28*1024*1024)
	var req models.AgentImportPreviewRequest
	if c.ShouldBindJSON(&req) != nil {
		return nil, errs.ErrParameterInvalid
	}
	user, err := services.Users.GetUserById(c, c.GetCurrentUid())
	if err != nil {
		return nil, errs.ErrUserNotFound
	}
	result, err := services.AgentImports.Preview(c, user, a.CurrentConfig(), req)
	if err != nil {
		return nil, errs.Or(err, errs.ErrParameterInvalid)
	}
	return result, nil
}

func (a *AgentImportsApi) MapHandler(c *core.WebContext) (any, *errs.Error) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 2*1024*1024)
	var req models.AgentImportMapRequest
	if c.ShouldBindJSON(&req) != nil {
		return nil, errs.ErrParameterInvalid
	}
	if !a.CurrentConfig().EnableDataImport {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}
	user, err := services.Users.GetUserById(c, c.GetCurrentUid())
	if err != nil {
		return nil, errs.ErrUserNotFound
	}
	if user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_IMPORT_TRANSACTION) {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}
	result, err := services.AgentImports.Map(c, c.GetCurrentUid(), req)
	if err != nil {
		return nil, errs.Or(err, errs.ErrParameterInvalid)
	}
	return result, nil
}

func (a *AgentImportsApi) ConfirmHandler(c *core.WebContext) (any, *errs.Error) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 16*1024)
	var req models.AgentImportConfirmRequest
	if c.ShouldBindJSON(&req) != nil {
		return nil, errs.ErrParameterInvalid
	}
	user, err := services.Users.GetUserById(c, c.GetCurrentUid())
	if err != nil {
		return nil, errs.ErrUserNotFound
	}
	count, err := services.AgentImports.Confirm(c, user, a.CurrentConfig(), req)
	if err != nil {
		return nil, errs.Or(err, errs.ErrParameterInvalid)
	}
	return map[string]int{"importedCount": count}, nil
}
