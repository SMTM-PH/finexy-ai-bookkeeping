package core

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestAgentLedgerGrantPrecisionAndLegacy(t *testing.T) {
	const id int64 = 9007199254740993
	raw, err := NewAgentAuthorization("ledger", []string{AgentScopeWrite}, id)
	if err != nil || !strings.Contains(raw, `"ledgerId":"9007199254740993"`) {
		t.Fatal("ledger ID must remain a decimal string")
	}
	if got := ParseAgentAuthorization(raw); got.LedgerId != id {
		t.Fatal("ledger precision lost")
	}
	claims := &UserTokenClaims{Type: USER_TOKEN_TYPE_API, AgentLedgerId: id, AgentScopes: []string{AgentScopeRead}}
	if !claims.HasAgentLedger(id) || claims.HasAgentLedger(0) || claims.HasAgentLedger(id-1) {
		t.Fatal("ledger authorization escaped its grant")
	}
	data, _ := json.Marshal(claims)
	if strings.Contains(string(data), "AgentLedgerId") || strings.Contains(string(data), "ledgerId") {
		t.Fatal("ledger grant must be read from persisted token context")
	}
	legacy := ParseAgentAuthorization(`{"name":"old","scopes":["read"]}`)
	if legacy.LedgerId != 0 || len(legacy.Scopes) != 1 {
		t.Fatal("legacy token must stay in default ledger")
	}
	if len(ParseAgentAuthorization(`{"ledgerId":"-1","scopes":["read"]}`).Scopes) != 0 {
		t.Fatal("negative ledger accepted")
	}
	claims.Type = USER_TOKEN_TYPE_NORMAL
	if !claims.HasAgentLedger(0) || !claims.HasAgentLedger(id) {
		t.Fatal("normal session unexpectedly constrained")
	}
}

func TestAgentAuthorizationDefaultsAndValidation(t *testing.T) {
	if got := ParseAgentAuthorization(""); len(got.Scopes) != 1 || got.Scopes[0] != AgentScopeRead {
		t.Fatal("old tokens must default to read only")
	}
	if got := ParseAgentAuthorization("corrupt"); len(got.Scopes) != 0 {
		t.Fatal("corrupt grants must fail closed")
	}
	if _, err := NewAgentAuthorization("test", []string{"admin"}); err == nil {
		t.Fatal("unknown permission accepted")
	}
	raw, err := NewAgentAuthorization("Codex", []string{AgentScopeImport, AgentScopeImport})
	if err != nil {
		t.Fatal(err)
	}
	grant := ParseAgentAuthorization(raw)
	if grant.Name != "Codex" || len(grant.Scopes) != 2 {
		t.Fatal("name/scopes lost")
	}
}
