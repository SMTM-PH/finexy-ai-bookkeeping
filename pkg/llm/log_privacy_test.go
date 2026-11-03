package llm_test

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/data"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider/anthropic"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider/googleai"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider/lmstudio"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider/ollama"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider/openai"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/log"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

func TestModelDebugLogsDoNotContainFinancialContent(t *testing.T) {
	const prompt = "synthetic-private-prompt-93d43"
	const answer = "synthetic-private-response-a743d"
	const key = "synthetic-private-api-key-148df"
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	original := os.Stdout
	os.Stdout = w
	configure := func(level settings.Level) error {
		return log.SetLoggerConfiguration(&settings.Config{LogLevel: level, EnableConsoleLog: true, EnableRequestLog: true, EnableQueryLog: true}, true)
	}
	if err = configure(settings.LOGLEVEL_DEBUG); err != nil {
		t.Fatal(err)
	}
	var captured bytes.Buffer
	done := make(chan struct{})
	go func() { _, _ = io.Copy(&captured, r); close(done) }()
	defer func() {
		os.Stdout = original
		_ = configure(settings.LOGLEVEL_INFO)
		_ = w.Close()
		<-done
		_ = r.Close()
		for _, marker := range []string{prompt, answer, key} {
			if strings.Contains(captured.String(), marker) {
				t.Error("model logs included a private synthetic marker")
			}
		}
		if !strings.Contains(captured.String(), "request prepared") {
			t.Error("debug logging was not exercised")
		}
	}()
	mode := "success"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if !bytes.Contains(body, []byte(prompt)) {
			t.Error("synthetic prompt did not reach the model")
		}
		w.Header().Set("Content-Type", "application/json")
		if mode == "http-error" {
			w.WriteHeader(503)
			_, _ = io.WriteString(w, answer)
			return
		}
		if mode == "malformed" {
			_, _ = io.WriteString(w, answer)
			return
		}
		_, _ = io.WriteString(w, `{"choices":[{"message":{"content":"`+answer+`"}}],"message":{"content":"`+answer+`"},"content":[{"type":"text","text":"`+answer+`"}],"output":[{"content":"`+answer+`"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}`)
	}))
	defer server.Close()
	config := &settings.LLMConfig{LargeLanguageModelAPIRequestTimeout: 1000, LargeLanguageModelAPIProxy: "none",
		OpenAICompatibleBaseURL: server.URL, OpenAICompatibleModelID: "synthetic", OpenAICompatibleAPIKey: key,
		AnthropicCompatibleBaseURL: server.URL, AnthropicCompatibleModelID: "synthetic", AnthropicCompatibleAPIKey: key, AnthropicCompatibleMaxTokens: 100,
		OllamaServerURL: server.URL, OllamaModelID: "synthetic", LMStudioServerURL: server.URL, LMStudioModelID: "synthetic", LMStudioToken: key}
	providers := []provider.LargeLanguageModelProvider{openai.NewOpenAICompatibleLargeLanguageModelProvider(config, true),
		anthropic.NewAnthropicCompatibleLargeLanguageModelProvider(config, true), ollama.NewOllamaLargeLanguageModelProvider(config, true), lmstudio.NewLMStudioLargeLanguageModelProvider(config, true)}
	request := &data.LargeLanguageModelRequest{SystemPrompt: prompt, UserPrompt: []byte(prompt)}
	for _, p := range providers {
		mode = "success"
		result, callErr := p.GetJsonResponse(core.NewNullContext(), 0, config, request)
		if callErr != nil || result == nil || result.Content != answer {
			t.Errorf("synthetic successful model call failed for %T: %T", p, callErr)
		}
		for _, failure := range []string{"http-error", "malformed"} {
			mode = failure
			if _, callErr = p.GetJsonResponse(core.NewNullContext(), 0, config, request); callErr == nil {
				t.Error("invalid model response was accepted")
			}
		}
	}
	// Exercise the Google request adapter without contacting its fixed endpoint.
	google := &googleai.GoogleAILargeLanguageModelAdapter{GoogleAIAPIKey: key, GoogleAIModelID: "synthetic"}
	if _, err = google.BuildTextualRequest(core.NewNullContext(), 0, request, data.LARGE_LANGUAGE_MODEL_RESPONSE_FORMAT_JSON); err != nil {
		t.Errorf("Google request failed: %T", err)
	}
	server.Close()
	// The URL in a transport error must not expose a configured query secret.
	config.OpenAICompatibleBaseURL = server.URL + "?private=" + key
	p := openai.NewOpenAICompatibleLargeLanguageModelProvider(config, true)
	if _, err = p.GetJsonResponse(core.NewNullContext(), 0, config, request); err == nil {
		t.Error("closed model server unexpectedly responded")
	}
}
