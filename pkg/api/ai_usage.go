package api

import (
	"encoding/json"
	"io"
	"net/http"
	"regexp"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/httpclient"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/usage"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

func (a *AIConfigurationsApi) UsageGetHandler(c *core.WebContext) (any, *errs.Error) {
	if c.GetTokenClaims().Type != core.USER_TOKEN_TYPE_NORMAL {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}
	location, err := c.GetClientTimezone()
	if err != nil {
		return nil, errs.ErrParameterInvalid
	}
	result, err := usage.Read(c, c.GetCurrentUid(), location)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return result, nil
}

func (a *AIConfigurationsApi) BalanceGetHandler(c *core.WebContext) (any, *errs.Error) {
	if c.GetTokenClaims().Type != core.USER_TOKEN_TYPE_NORMAL {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}
	owner, err := a.ownerUid(c)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	if owner != c.GetCurrentUid() {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}
	return readDeepSeekBalance(a.CurrentConfig().TextRecognitionLLMConfig, nil), nil
}

// Only the configured official origin can receive the saved key. Never follow
// redirects or return upstream errors, which can contain credential details.
func readDeepSeekBalance(config *settings.LLMConfig, client *http.Client) *models.AIBalanceResponse {
	r := &models.AIBalanceResponse{Status: "unsupported", Balances: make([]*models.AIBalanceInfo, 0)}
	if !usage.IsOfficialDeepSeek(config) {
		return r
	}
	if config.OpenAICompatibleAPIKey == "" {
		r.Status = "not_configured"
		return r
	}
	if client == nil {
		client = httpclient.NewHttpClient(10000, config.LargeLanguageModelAPIProxy, false, core.GetOutgoingUserAgent(), false)
	}
	copyClient := *client
	copyClient.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	req, _ := http.NewRequest(http.MethodGet, "https://api.deepseek.com/user/balance", nil)
	req.Header.Set("Authorization", "Bearer "+config.OpenAICompatibleAPIKey)
	req.Header.Set("Accept", "application/json")
	resp, err := copyClient.Do(req)
	if err != nil {
		r.Status = "unavailable"
		return r
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		r.Status = "unavailable"
		return r
	}
	var body struct {
		Available *bool `json:"is_available"`
		Balances  []struct {
			Currency string `json:"currency"`
			Total    string `json:"total_balance"`
			Granted  string `json:"granted_balance"`
			Topped   string `json:"topped_up_balance"`
		} `json:"balance_infos"`
	}
	if json.NewDecoder(io.LimitReader(resp.Body, 65536)).Decode(&body) != nil || body.Available == nil || len(body.Balances) == 0 || len(body.Balances) > 2 {
		r.Status = "unavailable"
		return r
	}
	decimal := regexp.MustCompile(`^-?\d{1,12}(\.\d{1,8})?$`)
	seen := make(map[string]bool)
	for _, b := range body.Balances {
		if (b.Currency != "CNY" && b.Currency != "USD") || seen[b.Currency] || !decimal.MatchString(b.Total) || !decimal.MatchString(b.Granted) || !decimal.MatchString(b.Topped) {
			r.Status = "unavailable"
			r.Balances = make([]*models.AIBalanceInfo, 0)
			return r
		}
		seen[b.Currency] = true
		r.Balances = append(r.Balances, &models.AIBalanceInfo{Currency: b.Currency, TotalBalance: b.Total, GrantedBalance: b.Granted, ToppedUpBalance: b.Topped})
	}
	r.Status = "available"
	r.Available = *body.Available
	r.UpdatedAt = time.Now().Unix()
	return r
}
