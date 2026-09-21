package llm

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/data"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider/anthropic"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider/googleai"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider/lmstudio"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider/ollama"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/provider/openai"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"sync"
)

// LargeLanguageModelProviderContainer contains the current large language model provider
type LargeLanguageModelProviderContainer struct {
	mutex                                  sync.RWMutex
	textRecognitionCurrentProvider         provider.LargeLanguageModelProvider
	receiptImageRecognitionCurrentProvider provider.LargeLanguageModelProvider
}

// Initialize a large language model provider container singleton instance
var (
	Container = &LargeLanguageModelProviderContainer{}
)

// InitializeLargeLanguageModelProvider initializes the current large language model provider according to the config
func InitializeLargeLanguageModelProvider(config *settings.Config) error {
	var err error
	var textProvider provider.LargeLanguageModelProvider
	var imageProvider provider.LargeLanguageModelProvider

	if config.TextRecognitionLLMConfig != nil {
		textProvider, err = initializeLargeLanguageModelProvider(config.TextRecognitionLLMConfig, config.EnableDebugLog)

		if err != nil {
			return err
		}
	}

	if config.ReceiptImageRecognitionLLMConfig != nil {
		imageProvider, err = initializeLargeLanguageModelProvider(config.ReceiptImageRecognitionLLMConfig, config.EnableDebugLog)

		if err != nil {
			return err
		}
	}
	Container.mutex.Lock()
	Container.textRecognitionCurrentProvider = textProvider
	Container.receiptImageRecognitionCurrentProvider = imageProvider
	Container.mutex.Unlock()

	return nil
}

func initializeLargeLanguageModelProvider(llmConfig *settings.LLMConfig, enableResponseLog bool) (provider.LargeLanguageModelProvider, error) {
	if llmConfig.LLMProvider == settings.OpenAILLMProvider {
		return openai.NewOpenAILargeLanguageModelProvider(llmConfig, enableResponseLog), nil
	} else if llmConfig.LLMProvider == settings.OpenAICompatibleLLMProvider {
		return openai.NewOpenAICompatibleLargeLanguageModelProvider(llmConfig, enableResponseLog), nil
	} else if llmConfig.LLMProvider == settings.AnthropicLLMProvider {
		return anthropic.NewAnthropicLargeLanguageModelProvider(llmConfig, enableResponseLog), nil
	} else if llmConfig.LLMProvider == settings.AnthropicCompatibleLLMProvider {
		return anthropic.NewAnthropicCompatibleLargeLanguageModelProvider(llmConfig, enableResponseLog), nil
	} else if llmConfig.LLMProvider == settings.OpenRouterLLMProvider {
		return openai.NewOpenRouterLargeLanguageModelProvider(llmConfig, enableResponseLog), nil
	} else if llmConfig.LLMProvider == settings.OllamaLLMProvider {
		return ollama.NewOllamaLargeLanguageModelProvider(llmConfig, enableResponseLog), nil
	} else if llmConfig.LLMProvider == settings.LMStudioLLMProvider {
		return lmstudio.NewLMStudioLargeLanguageModelProvider(llmConfig, enableResponseLog), nil
	} else if llmConfig.LLMProvider == settings.GoogleAILLMProvider {
		return googleai.NewGoogleAILargeLanguageModelProvider(llmConfig, enableResponseLog), nil
	} else if llmConfig.LLMProvider == "" {
		return nil, nil
	}

	return nil, errs.ErrInvalidLLMProvider
}

// GetJsonResponseByTextRecognitionModel returns the json response from the current large language model provider by transaction text recognition model
func (l *LargeLanguageModelProviderContainer) GetJsonResponseByTextRecognitionModel(c core.Context, uid int64, currentConfig *settings.Config, request *data.LargeLanguageModelRequest) (*data.LargeLanguageModelTextualResponse, error) {
	l.mutex.RLock()
	currentProvider := l.textRecognitionCurrentProvider
	l.mutex.RUnlock()
	if currentConfig.TextRecognitionLLMConfig == nil || currentProvider == nil {
		return nil, errs.ErrInvalidLLMProvider
	}

	return currentProvider.GetJsonResponse(c, uid, currentConfig.TextRecognitionLLMConfig, request)
}

// GetJsonResponseByReceiptImageRecognitionModel returns the json response from the current large language model provider by receipt image recognition model
func (l *LargeLanguageModelProviderContainer) GetJsonResponseByReceiptImageRecognitionModel(c core.Context, uid int64, currentConfig *settings.Config, request *data.LargeLanguageModelRequest) (*data.LargeLanguageModelTextualResponse, error) {
	l.mutex.RLock()
	currentProvider := l.receiptImageRecognitionCurrentProvider
	l.mutex.RUnlock()
	if currentConfig.ReceiptImageRecognitionLLMConfig == nil || currentProvider == nil {
		return nil, errs.ErrInvalidLLMProvider
	}

	return currentProvider.GetJsonResponse(c, uid, currentConfig.ReceiptImageRecognitionLLMConfig, request)
}
