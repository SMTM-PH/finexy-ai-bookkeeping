package services

import (
	"bytes"
	"encoding/json"
	"strings"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/data"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/log"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/templates"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
)

// AIRecognitionService recognizes transaction drafts with the configured LLM.
type AIRecognitionService struct {
	transactionCategories *TransactionCategoryService
	transactionTags       *TransactionTagService
	accounts              *AccountService
}

// AIRecognition is the shared AI recognition service used by HTTP and MCP entry points.
var AIRecognition = &AIRecognitionService{
	transactionCategories: TransactionCategories,
	transactionTags:       TransactionTags,
	accounts:              Accounts,
}

// RecognizeReceiptImage sends a receipt image directly to the configured vision model and returns a draft.
// It never creates a transaction or stores the supplied image.
func (s *AIRecognitionService) RecognizeReceiptImage(c core.Context, user *models.User, currentConfig *settings.Config, imageData []byte, contentType string, clientTimezone *time.Location) (*models.RecognizedTransactionResponse, *models.RecognizedTransactionResult, error) {
	if currentConfig == nil || currentConfig.ReceiptImageRecognitionLLMConfig == nil || currentConfig.ReceiptImageRecognitionLLMConfig.LLMProvider == "" || !currentConfig.TransactionFromAIImageRecognition {
		return nil, nil, errs.ErrLargeLanguageModelProviderNotEnabled
	}

	if user == nil {
		return nil, nil, errs.ErrUserNotFound
	}

	if user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_CREATE_TRANSACTION_FROM_AI_IMAGE_RECOGNITION) {
		return nil, nil, errs.ErrNotPermittedToPerformThisAction
	}

	if len(imageData) < 1 {
		return nil, nil, errs.ErrAIRecognitionImageIsEmpty
	}

	if len(imageData) > int(currentConfig.MaxAIRecognitionPictureFileSize) {
		return nil, nil, errs.ErrExceedMaxAIRecognitionImageFileSize
	}

	if !isSupportedAIRecognitionImageContentType(contentType) {
		return nil, nil, errs.ErrImageTypeNotSupported
	}

	if clientTimezone == nil {
		clientTimezone = time.UTC
	}

	accountNames, accountMap, incomeCategoryNames, expenseCategoryNames, transferCategoryNames, incomeCategoryMap, expenseCategoryMap, transferCategoryMap, tagNames, tagMap, err := s.getUserEssentialData(c, user.Uid)
	if err != nil {
		return nil, nil, errs.Or(err, errs.ErrOperationFailed)
	}

	systemPrompt, err := templates.GetTemplate(templates.SYSTEM_PROMPT_RECEIPT_IMAGE_RECOGNITION)
	if err != nil {
		return nil, nil, errs.Or(err, errs.ErrOperationFailed)
	}

	systemPromptParams := map[string]any{
		"CurrentDateTime":          utils.FormatUnixTimeToLongDateTime(time.Now().Unix(), clientTimezone),
		"AllExpenseCategoryNames":  strings.Join(expenseCategoryNames, "\n"),
		"AllIncomeCategoryNames":   strings.Join(incomeCategoryNames, "\n"),
		"AllTransferCategoryNames": strings.Join(transferCategoryNames, "\n"),
		"AllAccountNames":          strings.Join(accountNames, "\n"),
		"AllTagNames":              strings.Join(tagNames, "\n"),
		"AdditionalNotes":          "",
	}

	var bodyBuffer bytes.Buffer
	if err := systemPrompt.Execute(&bodyBuffer, systemPromptParams); err != nil {
		return nil, nil, errs.Or(err, errs.ErrOperationFailed)
	}

	llmRequest := &data.LargeLanguageModelRequest{
		Stream:                false,
		SystemPrompt:          strings.ReplaceAll(bodyBuffer.String(), "\r\n", "\n"),
		UserPrompt:            imageData,
		UserPromptType:        data.LARGE_LANGUAGE_MODEL_REQUEST_PROMPT_TYPE_IMAGE_URL,
		UserPromptContentType: contentType,
	}

	llmResponse, err := llm.Container.GetJsonResponseByReceiptImageRecognitionModel(c, user.Uid, currentConfig, llmRequest)
	if err != nil {
		log.Errorf(c, "[ai_recognition.RecognizeReceiptImage] failed to get llm response for user \"uid:%d\", because %s", user.Uid, err.Error())
		return nil, nil, errs.Or(err, errs.ErrOperationFailed)
	}

	if llmResponse == nil || len(llmResponse.Content) == 0 || strings.HasPrefix(llmResponse.Content, "{}") {
		return nil, nil, errs.ErrNoTransactionInformation
	}

	var result *models.RecognizedTransactionResult
	if err := json.Unmarshal([]byte(llmResponse.Content), &result); err != nil {
		return nil, nil, errs.Or(err, errs.ErrOperationFailed)
	}

	response, err := parseRecognizedTransactionResult(result, clientTimezone, accountMap, expenseCategoryMap, incomeCategoryMap, transferCategoryMap, tagMap)
	if err != nil {
		return nil, nil, err
	}

	return response, result, nil
}

func isSupportedAIRecognitionImageContentType(contentType string) bool {
	switch strings.ToLower(strings.TrimSpace(contentType)) {
	case "image/jpeg", "image/png", "image/gif", "image/webp":
		return true
	default:
		return false
	}
}

func (s *AIRecognitionService) getUserEssentialData(c core.Context, uid int64) ([]string, map[string]*models.Account, []string, []string, []string, map[string]*models.TransactionCategory, map[string]*models.TransactionCategory, map[string]*models.TransactionCategory, []string, map[string]*models.TransactionTag, error) {
	accounts, err := s.accounts.GetAllAccountsByUid(c, uid)
	if err != nil {
		return nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, err
	}

	accountMap := s.accounts.GetVisibleAccountNameMapByList(accounts)
	accountNames := make([]string, 0, len(accounts))
	for _, account := range accounts {
		if !account.Hidden && account.Type != models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
			accountNames = append(accountNames, account.Name)
		}
	}

	categories, err := s.transactionCategories.GetAllCategoriesByUid(c, uid, 0, -1)
	if err != nil {
		return nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, err
	}

	incomeCategoryMap := make(map[string]*models.TransactionCategory)
	expenseCategoryMap := make(map[string]*models.TransactionCategory)
	transferCategoryMap := make(map[string]*models.TransactionCategory)
	incomeCategoryNames := make([]string, 0)
	expenseCategoryNames := make([]string, 0)
	transferCategoryNames := make([]string, 0)

	for _, category := range categories {
		if category.Hidden || category.ParentCategoryId == models.LevelOneTransactionCategoryParentId {
			continue
		}
		switch category.Type {
		case models.CATEGORY_TYPE_INCOME:
			incomeCategoryMap[category.Name] = category
			incomeCategoryNames = append(incomeCategoryNames, category.Name)
		case models.CATEGORY_TYPE_EXPENSE:
			expenseCategoryMap[category.Name] = category
			expenseCategoryNames = append(expenseCategoryNames, category.Name)
		case models.CATEGORY_TYPE_TRANSFER:
			transferCategoryMap[category.Name] = category
			transferCategoryNames = append(transferCategoryNames, category.Name)
		}
	}

	tags, err := s.transactionTags.GetAllTagsByUid(c, uid)
	if err != nil {
		return nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, err
	}

	tagMap := s.transactionTags.GetVisibleTagNameMapByList(tags)
	tagNames := make([]string, 0, len(tags))
	for _, tag := range tags {
		if !tag.Hidden {
			tagNames = append(tagNames, tag.Name)
		}
	}

	return accountNames, accountMap, incomeCategoryNames, expenseCategoryNames, transferCategoryNames, incomeCategoryMap, expenseCategoryMap, transferCategoryMap, tagNames, tagMap, nil
}

func parseRecognizedTransactionResult(result *models.RecognizedTransactionResult, clientTimezone *time.Location, accountMap map[string]*models.Account, expenseCategoryMap map[string]*models.TransactionCategory, incomeCategoryMap map[string]*models.TransactionCategory, transferCategoryMap map[string]*models.TransactionCategory, tagMap map[string]*models.TransactionTag) (*models.RecognizedTransactionResponse, error) {
	if result == nil {
		return nil, errs.ErrNoTransactionInformation
	}

	response := &models.RecognizedTransactionResponse{Type: models.TRANSACTION_TYPE_EXPENSE}
	var categoryMap map[string]*models.TransactionCategory
	switch result.Type {
	case "income":
		response.Type = models.TRANSACTION_TYPE_INCOME
		categoryMap = incomeCategoryMap
	case "expense":
		response.Type = models.TRANSACTION_TYPE_EXPENSE
		categoryMap = expenseCategoryMap
	case "transfer":
		response.Type = models.TRANSACTION_TYPE_TRANSFER
		categoryMap = transferCategoryMap
	case "":
		return nil, errs.ErrNoTransactionInformation
	default:
		return nil, errs.ErrOperationFailed
	}

	if category, exists := categoryMap[result.CategoryName]; exists {
		response.CategoryId = category.CategoryId
	}

	if result.Time != "" {
		longDateTime := result.Time
		if utils.IsValidLongDateTimeWithoutSecondFormat(longDateTime) {
			longDateTime += ":00"
		} else if utils.IsValidLongDateFormat(longDateTime) {
			longDateTime += " 00:00:00"
		}
		if timestamp, err := utils.ParseFromLongDateTimeInTimeZone(longDateTime, clientTimezone); err == nil {
			response.Time = timestamp.Unix()
		}
	}

	if result.Amount != "" {
		amount, err := utils.ParseAmount(result.Amount)
		if err != nil {
			return nil, errs.ErrOperationFailed
		}
		response.SourceAmount = amount
	}

	if response.Type == models.TRANSACTION_TYPE_TRANSFER && result.DestinationAmount != "" {
		amount, err := utils.ParseAmount(result.DestinationAmount)
		if err != nil {
			return nil, errs.ErrOperationFailed
		}
		response.DestinationAmount = amount
	}

	if account, exists := accountMap[result.AccountName]; exists {
		response.SourceAccountId = account.AccountId
	}
	if account, exists := accountMap[result.DestinationAccountName]; exists {
		response.DestinationAccountId = account.AccountId
	}

	if len(result.TagNames) > 0 {
		response.TagIds = make([]string, 0, len(result.TagNames))
		for _, tagName := range result.TagNames {
			if tag, exists := tagMap[tagName]; exists {
				response.TagIds = append(response.TagIds, utils.Int64ToString(tag.TagId))
			}
		}
	}

	response.Comment = result.Description
	return response, nil
}
