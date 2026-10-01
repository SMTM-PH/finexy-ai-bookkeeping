package middlewares

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"testing"
)

func TestAgentScopesCannotEscalateOrBypassPreview(t *testing.T) {
	for _, tc := range []struct {
		scope, method, path string
		allowed             bool
	}{
		{core.AgentScopeRead, "GET", "/api/v1/accounts/list.json", true},
		{core.AgentScopeRead, "GET", "/api/v1/tokens/list.json", false},
		{core.AgentScopeRead, "POST", "/api/v1/transactions/add.json", false},
		{core.AgentScopeWrite, "POST", "/api/v1/transactions/add.json", true},
		{core.AgentScopeWrite, "POST", "/api/v1/accounts/add.json", false},
		{core.AgentScopeManage, "POST", "/api/v1/accounts/add.json", true},
		{core.AgentScopeManage, "POST", "/api/v1/transaction/categories/add.json", true},
		{core.AgentScopeManage, "POST", "/api/v1/transactions/add.json", false},
		{core.AgentScopeImport, "POST", "/api/v1/agent/import/confirm.json", true},
		{core.AgentScopeImport, "POST", "/api/v1/transactions/import.json", false},
		{core.AgentScopeImport, "POST", "/api/v1/tokens/generate/api.json", false},
		{core.AgentScopeImport, "POST", "/api/v1/data/clear/all.json", false},
		{core.AgentScopeManage, "POST", "/api/v1/unknown/future.json", false},
	} {
		claims := &core.UserTokenClaims{Type: core.USER_TOKEN_TYPE_API, AgentScopes: []string{core.AgentScopeRead, tc.scope}}
		if got := AgentAPIRequestAllowed(claims, tc.method, tc.path); got != tc.allowed {
			t.Errorf("%s %s %s: %v", tc.scope, tc.method, tc.path, got)
		}
	}
}
