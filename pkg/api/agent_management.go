package api

import (
	"bytes"
	"encoding/json"
	"io"
	"strconv"
	"strings"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/mcp"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
)

// ExecuteAgentManagement preserves the verified MCP identity. It does not mint
// a session or route through a privileged token; the ordinary APIs still check
// actual object ownership and member permissions inside their services.
func (a *ModelContextProtocolAPI) ExecuteAgentManagement(c *core.WebContext, name string, arguments json.RawMessage) (any, error) {
	var grant mcp.ManagementGrant
	if json.Unmarshal(arguments, &grant) != nil || grant.LedgerId == nil || !grant.Confirmed {
		return nil, errs.ErrParameterInvalid
	}
	claims := c.GetTokenClaims()
	if !claims.HasAgentScope(core.AgentScopeManage) || !claims.HasAgentLedger(*grant.LedgerId) {
		return nil, errs.ErrAgentPermissionDenied
	}
	_, ledger, err := services.Ledgers.GetLedgerWithAccess(c, c.GetCurrentUid(), *grant.LedgerId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}
	if strings.Contains(name, "category") && ledger != nil && ledger.OwnerUid != c.GetCurrentUid() {
		return nil, errs.ErrAgentPermissionDenied
	}
	var handler func(*core.WebContext) (any, *errs.Error)
	switch name {
	case "create_account":
		handler = Accounts.AccountCreateHandler
	case "update_account":
		handler = Accounts.AccountModifyHandler
	case "set_account_hidden":
		handler = Accounts.AccountHideHandler
	case "delete_account":
		handler = Accounts.AccountDeleteHandler
	case "create_category":
		handler = TransactionCategories.CategoryCreateHandler
	case "update_category":
		handler = TransactionCategories.CategoryModifyHandler
	case "set_category_hidden":
		handler = TransactionCategories.CategoryHideHandler
	case "delete_category":
		handler = TransactionCategories.CategoryDeleteHandler
	default:
		return nil, errs.ErrApiNotFound
	}
	original := c.Request
	request := original.Clone(original.Context())
	request.Method = "POST"
	request.Body = io.NopCloser(bytes.NewReader(arguments))
	request.ContentLength = int64(len(arguments))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-Timezone-Offset", strconv.Itoa(int(grant.UtcOffset)))
	c.Request = request
	defer func() { c.Request = original }()
	result, apiErr := handler(c)
	if apiErr != nil {
		return nil, apiErr
	}
	return result, nil
}
