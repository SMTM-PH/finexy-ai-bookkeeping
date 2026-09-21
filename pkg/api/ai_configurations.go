package api

import (
	"net/url"
	"strings"
	"sync"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

type AIConfigurationsApi struct {
	ApiUsingConfig
	users *services.UserService
	mutex sync.Mutex
}

var AIConfigurations = &AIConfigurationsApi{
	ApiUsingConfig: ApiUsingConfig{container: settings.Container},
	users:          services.Users,
}

func (a *AIConfigurationsApi) GetHandler(c *core.WebContext) (any, *errs.Error) {
	ownerUid, err := a.ownerUid(c)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return a.response(ownerUid == c.GetCurrentUid()), nil
}

func (a *AIConfigurationsApi) UpdateHandler(c *core.WebContext) (any, *errs.Error) {
	a.mutex.Lock()
	defer a.mutex.Unlock()

	ownerUid, err := a.ownerUid(c)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	if ownerUid != c.GetCurrentUid() {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}

	var request models.AIConfigurationUpdateRequest
	if err = c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	baseURL := strings.TrimRight(strings.TrimSpace(request.BaseURL), "/")
	parsedURL, parseErr := url.ParseRequestURI(baseURL)
	if parseErr != nil || parsedURL.Host == "" || (parsedURL.Scheme != "http" && parsedURL.Scheme != "https") {
		return nil, errs.ErrParameterInvalid
	}
	thinking := settings.LLMThinkingLevel(request.EnableThinking)
	if thinking != settings.LLMThinkingDefault && thinking != settings.LLMThinkingDisabled && thinking != settings.LLMThinkingEnabled &&
		thinking != settings.LLMThinkingLow && thinking != settings.LLMThinkingMedium && thinking != settings.LLMThinkingHigh && thinking != settings.LLMThinkingXHigh {
		return nil, errs.ErrParameterInvalid
	}
	if request.RequestTimeout == 0 {
		request.RequestTimeout = 60000
	}

	current := a.CurrentConfig()
	stored, loadErr := settings.LoadWebAIConfiguration(current)
	if loadErr != nil {
		return nil, errs.Or(loadErr, errs.ErrOperationFailed)
	}
	apiKey := strings.TrimSpace(request.APIKey)
	if apiKey == "" && stored != nil {
		apiKey = stored.APIKey
	}
	override := &settings.WebAIConfiguration{
		OwnerUid: ownerUid, Enabled: request.Enabled, ImageEnabled: request.ImageEnabled, BaseURL: baseURL, APIKey: apiKey,
		ModelID: strings.TrimSpace(request.ModelID), EnableThinking: thinking, RequestTimeout: request.RequestTimeout,
	}
	updated := *current
	settings.ApplyWebAIConfiguration(&updated, override)
	if updated.TextRecognitionLLMConfig == nil || updated.TextRecognitionLLMConfig.OpenAICompatibleAPIKey == "" {
		return nil, errs.ErrParameterInvalid
	}
	if err = llm.InitializeLargeLanguageModelProvider(&updated); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	if err = settings.SaveWebAIConfiguration(&updated, override); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	settings.SetCurrentConfig(&updated)
	return a.response(true), nil
}

func (a *AIConfigurationsApi) ownerUid(c core.Context) (int64, error) {
	stored, err := settings.LoadWebAIConfiguration(a.CurrentConfig())
	if err != nil {
		return 0, err
	}
	if stored != nil && stored.OwnerUid > 0 {
		owner := &models.User{}
		has, queryErr := a.users.UserDB().NewSession(c).ID(stored.OwnerUid).Where("deleted=?", false).Get(owner)
		if queryErr != nil {
			return 0, queryErr
		}
		if has {
			return stored.OwnerUid, nil
		}
	}
	user := &models.User{}
	has, err := a.users.UserDB().NewSession(c).Where("deleted=?", false).Asc("created_unix_time", "uid").Get(user)
	if err != nil {
		return 0, err
	}
	if !has {
		return 0, errs.ErrUserNotFound
	}
	return user.Uid, nil
}

func (a *AIConfigurationsApi) response(editable bool) *models.AIConfigurationResponse {
	config := a.CurrentConfig()
	llmConfig := config.TextRecognitionLLMConfig
	response := &models.AIConfigurationResponse{Provider: settings.OpenAICompatibleLLMProvider, Editable: editable}
	if llmConfig == nil {
		return response
	}
	response.Enabled = config.TransactionFromAITextRecognition
	response.ImageEnabled = config.TransactionFromAIImageRecognition
	response.BaseURL = llmConfig.OpenAICompatibleBaseURL
	response.ModelID = llmConfig.OpenAICompatibleModelID
	response.EnableThinking = string(llmConfig.EnableThinking)
	response.RequestTimeout = llmConfig.LargeLanguageModelAPIRequestTimeout
	response.APIKeyConfigured = llmConfig.OpenAICompatibleAPIKey != ""
	return response
}
