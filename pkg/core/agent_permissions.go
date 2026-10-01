package core

import (
	"encoding/json"
	"fmt"
	"strings"
)

const (
	AgentScopeRead   = "read"
	AgentScopeWrite  = "transactions:write"
	AgentScopeManage = "accounts:manage"
	AgentScopeImport = "bills:import"
)

// AgentAuthorization is persisted in the token record, never trusted from a client.
type AgentAuthorization struct {
	LedgerId int64    `json:"ledgerId,string"`
	Name     string   `json:"name"`
	Scopes   []string `json:"scopes"`
}

func ParseAgentAuthorization(context string) AgentAuthorization {
	a := AgentAuthorization{Name: "旧版令牌", Scopes: []string{AgentScopeRead}}
	if context != "" {
		if json.Unmarshal([]byte(context), &a) != nil || a.LedgerId < 0 {
			return AgentAuthorization{}
		}
	}
	return a
}

func NewAgentAuthorization(name string, scopes []string, ledgerIds ...int64) (string, error) {
	ledgerId := int64(0)
	if len(ledgerIds) > 0 {
		ledgerId = ledgerIds[0]
	}
	if ledgerId < 0 {
		return "", fmt.Errorf("invalid ledger")
	}
	name = strings.TrimSpace(name)
	if name == "" {
		name = "Agent"
	}
	if len([]rune(name)) > 64 {
		return "", fmt.Errorf("token name is too long")
	}
	if len(scopes) == 0 {
		scopes = []string{AgentScopeRead}
	}
	seen := map[string]bool{}
	clean := []string{AgentScopeRead}
	seen[AgentScopeRead] = true
	for _, scope := range scopes {
		switch scope {
		case AgentScopeRead, AgentScopeWrite, AgentScopeManage, AgentScopeImport:
		default:
			return "", fmt.Errorf("unknown agent permission")
		}
		if !seen[scope] {
			clean = append(clean, scope)
			seen[scope] = true
		}
	}
	data, err := json.Marshal(AgentAuthorization{Name: name, Scopes: clean, LedgerId: ledgerId})
	return string(data), err
}

func (c *UserTokenClaims) HasAgentLedger(ledgerId int64) bool {
	return c != nil && (c.Type == USER_TOKEN_TYPE_NORMAL || (ledgerId >= 0 && c.AgentLedgerId == ledgerId))
}

func (c *UserTokenClaims) HasAgentScope(scope string) bool {
	if c == nil {
		return false
	}
	if c.Type == USER_TOKEN_TYPE_NORMAL {
		return true
	}
	for _, s := range c.AgentScopes {
		if s == scope {
			return true
		}
	}
	return false
}
