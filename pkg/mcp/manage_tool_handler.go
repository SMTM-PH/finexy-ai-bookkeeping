package mcp

import (
	"encoding/json"
	"reflect"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

// The API adapter reuses the production validators and ownership checks.
type managementExecutor interface {
	ExecuteAgentManagement(*core.WebContext, string, json.RawMessage) (any, error)
}

type ManagementGrant struct {
	LedgerId  *int64 `json:"ledgerId,string" jsonschema_description:"Required target ledger ID as a string; must equal the token's authorized ledger."`
	Confirmed bool   `json:"confirmed" jsonschema_description:"True only after the user has authorized this specific change."`
	UtcOffset int16  `json:"utcOffset" jsonschema_description:"Timezone offset in minutes for balance dates; default UTC."`
}
type CreateAccountArguments struct {
	ManagementGrant
	AccountFields
	Type            int    `json:"type" jsonschema:"enum=1" jsonschema_description:"Single account. Manage child groups through the regular account editor."`
	Balance         int64  `json:"balance"`
	BalanceTime     int64  `json:"balanceTime"`
	ClientSessionId string `json:"clientSessionId,omitempty"`
}
type UpdateAccountArguments struct {
	ManagementGrant
	AccountFields
	Id                 int64  `json:"id,string"`
	LastReconciledTime *int64 `json:"lastReconciledTime,omitempty"`
	Hidden             bool   `json:"hidden"`
}
type AccountFields struct {
	Name                    string `json:"name"`
	Category                int    `json:"category"`
	Icon                    int64  `json:"icon,string"`
	Color                   string `json:"color"`
	Currency                string `json:"currency"`
	Comment                 string `json:"comment"`
	CreditCardStatementDate int    `json:"creditCardStatementDate"`
}
type HideAccountArguments struct {
	ManagementGrant
	models.AccountHideRequest
}
type DeleteAccountArguments struct {
	ManagementGrant
	models.AccountDeleteRequest
}
type CreateCategoryArguments struct {
	ManagementGrant
	models.TransactionCategoryCreateRequest
}
type UpdateCategoryArguments struct {
	ManagementGrant
	models.TransactionCategoryModifyRequest
}
type HideCategoryArguments struct {
	ManagementGrant
	models.TransactionCategoryHideRequest
}
type DeleteCategoryArguments struct {
	ManagementGrant
	models.TransactionCategoryDeleteRequest
}

type managementTool struct {
	name      string
	arguments reflect.Type
}

func (h *managementTool) Name() string             { return h.name }
func (h *managementTool) InputType() reflect.Type  { return h.arguments }
func (h *managementTool) OutputType() reflect.Type { return nil }
func (h *managementTool) Description() string {
	return "Manage an account or transaction category in the explicitly authorized ledger. Query current objects first, preserve existing fields when updating, use string IDs and integer minor-unit amounts. Requires accounts:manage, ledgerId and confirmed=true after user authorization. Hidden=true disables; false restores. Deletion retains production protections for referenced objects. Categories belong to the ledger owner and cannot be managed by another member."
}
func (h *managementTool) Handle(c *core.WebContext, req *MCPCallToolRequest, user *models.User, config *settings.Config, available MCPAvailableServices) (any, []*MCPTextContent, error) {
	var grant ManagementGrant
	if json.Unmarshal(req.Arguments, &grant) != nil || grant.LedgerId == nil || !grant.Confirmed || grant.UtcOffset < -720 || grant.UtcOffset > 840 {
		return nil, nil, errs.ErrParameterInvalid
	}
	if !c.GetTokenClaims().HasAgentScope(core.AgentScopeManage) || !c.GetTokenClaims().HasAgentLedger(*grant.LedgerId) {
		return nil, nil, errs.ErrAgentPermissionDenied
	}
	if h.name == "create_account" {
		var args CreateAccountArguments
		if json.Unmarshal(req.Arguments, &args) != nil || args.Type != 1 {
			return nil, nil, errs.ErrParameterInvalid
		}
	}
	executor, ok := available.(managementExecutor)
	if !ok {
		return nil, nil, errs.ErrOperationFailed
	}
	result, err := executor.ExecuteAgentManagement(c, h.name, req.Arguments)
	if err != nil {
		return nil, nil, err
	}
	text, err := json.Marshal(result)
	return result, []*MCPTextContent{{Type: "text", Text: string(text)}}, err
}

func registerManagementTools(container *MCPContainer) {
	for _, tool := range []*managementTool{
		{"create_account", reflect.TypeOf(&CreateAccountArguments{})},
		{"update_account", reflect.TypeOf(&UpdateAccountArguments{})},
		{"set_account_hidden", reflect.TypeOf(&HideAccountArguments{})},
		{"delete_account", reflect.TypeOf(&DeleteAccountArguments{})},
		{"create_category", reflect.TypeOf(&CreateCategoryArguments{})},
		{"update_category", reflect.TypeOf(&UpdateCategoryArguments{})},
		{"set_category_hidden", reflect.TypeOf(&HideCategoryArguments{})},
		{"delete_category", reflect.TypeOf(&DeleteCategoryArguments{})},
	} {
		registerMCPTextContentToolHandler(container, tool)
	}
}
