package usage

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"testing"
)

func TestReportedMeteringRejectsMalformedAndDoesNotDoubleCountSubsets(t *testing.T) {
	for _, test := range []struct {
		name, body string
		known      bool
	}{
		{"reported", `{"usage":{"prompt_tokens":120,"completion_tokens":80,"total_tokens":200,"prompt_cache_hit_tokens":100,"completion_tokens_details":{"reasoning_tokens":40}}}`, true},
		{"openai cache", `{"usage":{"prompt_tokens":120,"completion_tokens":80,"total_tokens":200,"prompt_tokens_details":{"cached_tokens":100}}}`, true},
		{"zero", `{"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}`, true},
		{"missing", `{}`, false},
		{"null", `null`, false},
		{"partial", `{"usage":{"total_tokens":200}}`, false},
		{"negative", `{"usage":{"prompt_tokens":-1,"completion_tokens":80,"total_tokens":200}}`, false},
		{"fraction", `{"usage":{"prompt_tokens":1.5,"completion_tokens":80,"total_tokens":200}}`, false},
		{"bad cache", `{"usage":{"prompt_tokens":120,"completion_tokens":80,"total_tokens":200,"prompt_cache_hit_tokens":121}}`, false},
		{"bad total", `{"usage":{"prompt_tokens":120,"completion_tokens":80,"total_tokens":199}}`, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			r := &models.AIUsageRecord{}
			ApplyReportedUsage(r, []byte(test.body))
			if r.UsageKnown != test.known {
				t.Fatal("metering validity mismatch")
			}
			if test.name == "reported" && (r.TotalTokens != 200 || r.CachedTokens != 100 || r.ReasoningTokens != 40 || !r.CacheUsageKnown || !r.ReasoningUsageKnown) {
				t.Fatal("cache or reasoning was incorrectly added to total")
			}
			if test.name == "zero" && (r.CacheUsageKnown || r.ReasoningUsageKnown) {
				t.Fatal("missing optional fields were treated as measured zero")
			}
		})
	}
}

func TestDeepSeekBalanceOriginNeverGuessesCompatibleEndpoints(t *testing.T) {
	for _, test := range []struct {
		url   string
		valid bool
	}{
		{"https://api.deepseek.com", true}, {"https://api.deepseek.com/v1/", true}, {"https://api.deepseek.com/beta", true},
		{"http://api.deepseek.com", false}, {"https://api.deepseek.com.evil.invalid", false}, {"https://evil.invalid", false},
		{"https://key@api.deepseek.com", false}, {"https://api.deepseek.com/?key=secret", false}, {"https://api.deepseek.com/other", false},
	} {
		if IsOfficialDeepSeek(&settings.LLMConfig{LLMProvider: settings.OpenAICompatibleLLMProvider, OpenAICompatibleBaseURL: test.url}) != test.valid {
			t.Fatal("incorrect official-origin decision")
		}
	}
}
