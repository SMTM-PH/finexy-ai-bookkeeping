package api

import (
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

type balanceTransport func(*http.Request) (*http.Response, error)

func (f balanceTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestDeepSeekBalanceValidationAndRedirectGuard(t *testing.T) {
	config := &settings.LLMConfig{LLMProvider: settings.OpenAICompatibleLLMProvider, OpenAICompatibleBaseURL: "https://api.deepseek.com/v1", OpenAICompatibleAPIKey: "synthetic-only-key"}
	for _, tc := range []struct {
		name   string
		status int
		body   string
		valid  bool
	}{
		{"balance", 200, `{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.34","granted_balance":"0.34","topped_up_balance":"12.00"}]}`, true},
		{"zero", 200, `{"is_available":false,"balance_infos":[{"currency":"USD","total_balance":"0.00","granted_balance":"0","topped_up_balance":"0"}]}`, true},
		{"missing", 200, `{}`, false},
		{"invalid", 200, `{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"secret-key","granted_balance":"0","topped_up_balance":"0"}]}`, false},
		{"upstream error", 401, `{"message":"synthetic-only-key"}`, false},
		{"redirect", 302, ``, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			client := &http.Client{Transport: balanceTransport(func(req *http.Request) (*http.Response, error) {
				calls++
				if req.URL.String() != "https://api.deepseek.com/user/balance" || req.Header.Get("Authorization") != "Bearer synthetic-only-key" {
					t.Fatal("unexpected balance request")
				}
				return &http.Response{StatusCode: tc.status, Body: io.NopCloser(strings.NewReader(tc.body)), Header: http.Header{"Location": []string{"https://evil.invalid"}}, Request: req}, nil
			})}
			r := readDeepSeekBalance(config, client)
			if calls != 1 || (r.Status == "available") != tc.valid {
				t.Fatal("balance status or redirect guard failed")
			}
			if !tc.valid && len(r.Balances) != 0 {
				t.Fatal("invalid upstream data was exposed")
			}
		})
	}
}
