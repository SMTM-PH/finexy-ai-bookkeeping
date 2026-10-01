package middlewares

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"strings"
)

// AgentAPIRequestAllowed is a fail-closed allow list. New routes need an explicit grant.
func AgentAPIRequestAllowed(claims *core.UserTokenClaims, method, path string) bool {
	p := strings.TrimPrefix(path, "/api/v1")
	if method == "GET" && claims.HasAgentScope(core.AgentScopeRead) {
		switch p {
		case "/ledger/list.json", "/ledger/overview.json", "/accounts/list.json", "/accounts/get.json", "/transaction/categories/list.json", "/transaction/categories/get.json", "/transaction/tags/list.json",
			"/transactions/count.json", "/transactions/list.json", "/transactions/list/by_month.json",
			"/transactions/get.json", "/transactions/statistics.json", "/transactions/statistics/trends.json",
			"/exchange_rates/latest.json":
			return true
		}
	}
	if method != "POST" {
		return false
	}
	switch p {
	case "/transactions/add.json", "/transactions/modify.json", "/transactions/delete.json":
		return claims.HasAgentScope(core.AgentScopeWrite)
	case "/accounts/add.json", "/accounts/modify.json", "/accounts/hide.json", "/accounts/delete.json", "/accounts/sub_account/delete.json",
		"/transaction/categories/add.json", "/transaction/categories/modify.json", "/transaction/categories/hide.json", "/transaction/categories/delete.json":
		return claims.HasAgentScope(core.AgentScopeManage)
	case "/agent/import/preview.json", "/agent/import/map.json", "/agent/import/confirm.json":
		return claims.HasAgentScope(core.AgentScopeImport)
	}
	return false
}
