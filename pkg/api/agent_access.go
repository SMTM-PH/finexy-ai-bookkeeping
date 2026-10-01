package api

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
)

// Agent access preferences can only be managed by an interactive login session.
func (a *TokensApi) AgentAccessGetHandler(c *core.WebContext) (any, *errs.Error) {
	if c.GetTokenClaims().Type != core.USER_TOKEN_TYPE_NORMAL {
		return nil, errs.ErrAgentPermissionDenied
	}
	enabled, err := a.users.IsMCPAccessEnabled(c, c.GetCurrentUid())
	if err != nil {
		return nil, errs.ErrOperationFailed
	}
	user, err := a.users.GetUserById(c, c.GetCurrentUid())
	if err != nil {
		return nil, errs.ErrUserNotFound
	}
	available := a.CurrentConfig().EnableMCPServer && !user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_MCP_ACCESS)
	return &models.AgentAccessResponse{MCPEnabled: enabled, MCPAvailable: available}, nil
}

func (a *TokensApi) AgentAccessUpdateHandler(c *core.WebContext) (any, *errs.Error) {
	if c.GetTokenClaims().Type != core.USER_TOKEN_TYPE_NORMAL {
		return nil, errs.ErrAgentPermissionDenied
	}
	var req models.AgentAccessUpdateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	if err := a.users.SetMCPAccessEnabled(c, c.GetCurrentUid(), *req.MCPEnabled); err != nil {
		return nil, errs.ErrOperationFailed
	}
	return a.AgentAccessGetHandler(c)
}
