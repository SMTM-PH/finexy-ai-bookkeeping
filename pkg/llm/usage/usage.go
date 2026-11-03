// Package usage collects API-reported metering without storing model content.
package usage

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/url"
	"strings"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

func IsOfficialDeepSeek(config *settings.LLMConfig) bool {
	if config == nil || config.LLMProvider != settings.OpenAICompatibleLLMProvider {
		return false
	}
	u, err := url.Parse(config.OpenAICompatibleBaseURL)
	return err == nil && u.Scheme == "https" && strings.EqualFold(u.Host, "api.deepseek.com") && u.User == nil && u.RawQuery == "" && u.Fragment == "" && (strings.TrimRight(u.Path, "/") == "" || strings.TrimRight(u.Path, "/") == "/v1" || strings.TrimRight(u.Path, "/") == "/beta")
}

func NewRecord(uid int64, config *settings.LLMConfig) *models.AIUsageRecord {
	r := &models.AIUsageRecord{Uid: uid, CreatedUnixTime: time.Now().Unix()}
	if config != nil {
		r.Provider = config.LLMProvider
		switch config.LLMProvider {
		case settings.OpenAICompatibleLLMProvider:
			r.ModelId = config.OpenAICompatibleModelID
		case settings.OpenAILLMProvider:
			r.ModelId = config.OpenAIModelID
		case settings.OpenRouterLLMProvider:
			r.ModelId = config.OpenRouterModelID
		case settings.GoogleAILLMProvider:
			r.ModelId = config.GoogleAIModelID
		}
		if IsOfficialDeepSeek(config) {
			r.Provider = "deepseek"
		}
	}
	return r
}

// Missing or malformed usage stays unknown; it must not look like measured zero.
func ApplyReportedUsage(r *models.AIUsageRecord, body []byte) {
	var response struct {
		Usage *struct {
			Input        *int64 `json:"prompt_tokens"`
			Output       *int64 `json:"completion_tokens"`
			Total        *int64 `json:"total_tokens"`
			Cached       *int64 `json:"prompt_cache_hit_tokens"`
			InputDetails struct {
				Cached *int64 `json:"cached_tokens"`
			} `json:"prompt_tokens_details"`
			OutputDetails struct {
				Reasoning *int64 `json:"reasoning_tokens"`
			} `json:"completion_tokens_details"`
		} `json:"usage"`
	}
	if json.Unmarshal(body, &response) != nil || response.Usage == nil {
		return
	}
	u := response.Usage
	valid := func(n *int64) bool { return n != nil && *n >= 0 && *n <= 2147483647 }
	if !valid(u.Input) || !valid(u.Output) || !valid(u.Total) || *u.Total < *u.Input+*u.Output {
		return
	}
	cache := u.Cached
	if cache == nil {
		cache = u.InputDetails.Cached
	}
	if cache != nil && (!valid(cache) || *cache > *u.Input) {
		return
	}
	if u.OutputDetails.Reasoning != nil && (!valid(u.OutputDetails.Reasoning) || *u.OutputDetails.Reasoning > *u.Output) {
		return
	}
	r.UsageKnown = true
	r.InputTokens, r.OutputTokens, r.TotalTokens = *u.Input, *u.Output, *u.Total
	if cache != nil {
		r.CacheUsageKnown = true
		r.CachedTokens = *cache
	}
	if u.OutputDetails.Reasoning != nil {
		r.ReasoningUsageKnown = true
		r.ReasoningTokens = *u.OutputDetails.Reasoning
	}
}

func Save(c core.Context, record *models.AIUsageRecord) error {
	if datastore.Container.UserStore == nil || record.Uid <= 0 {
		return nil
	}
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return err
	}
	record.Id = hex.EncodeToString(id[:])
	_, err := datastore.Container.UserStore.Query(c, record.Uid).Insert(record)
	return err
}

func Read(c core.Context, uid int64, location *time.Location) (*models.AIUsageResponse, error) {
	now := time.Now().In(location)
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, location)
	month := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, location)
	summary := func(from int64) (*models.AIUsageSummary, error) {
		result := &models.AIUsageSummary{}
		_, err := datastore.Container.UserStore.Query(c, uid).Table(new(models.AIUsageRecord)).Where("uid=? AND created_unix_time>=?", uid, from).Select("COUNT(*) AS requests, COALESCE(SUM(CASE WHEN cache_usage_known THEN 1 ELSE 0 END),0) AS cache_metered_requests, COALESCE(SUM(CASE WHEN reasoning_usage_known THEN 1 ELSE 0 END),0) AS reasoning_metered_requests, COALESCE(SUM(CASE WHEN success THEN 0 ELSE 1 END),0) AS failed_requests, COALESCE(SUM(CASE WHEN usage_known THEN 0 ELSE 1 END),0) AS unmetered_requests, COALESCE(SUM(input_tokens),0) AS input_tokens, COALESCE(SUM(output_tokens),0) AS output_tokens, COALESCE(SUM(total_tokens),0) AS total_tokens, COALESCE(SUM(cached_tokens),0) AS cached_tokens, COALESCE(SUM(reasoning_tokens),0) AS reasoning_tokens, COALESCE(SUM(duration_ms),0) AS duration_ms, COALESCE(MIN(created_unix_time),0) AS first_request_time").Get(result)
		return result, err
	}
	result := &models.AIUsageResponse{UpdatedAt: now.Unix(), Timezone: location.String(), Recent: make([]*models.AIUsageRecord, 0)}
	var err error
	if result.Today, err = summary(today.Unix()); err != nil {
		return nil, err
	}
	if result.Month, err = summary(month.Unix()); err != nil {
		return nil, err
	}
	if result.All, err = summary(0); err != nil {
		return nil, err
	}
	err = datastore.Container.UserStore.Query(c, uid).Where("uid=?", uid).Desc("created_unix_time", "id").Limit(20).Find(&result.Recent)
	return result, err
}
