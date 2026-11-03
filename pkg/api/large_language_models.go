package api

import (
	"bytes"
	"encoding/json"
	"io"
	"strconv"
	"strings"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm/data"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/log"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/templates"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
)

// LargeLanguageModelsApi represents large language models api
type LargeLanguageModelsApi struct {
	ApiUsingConfig
	transactionCategories *services.TransactionCategoryService
	transactionTags       *services.TransactionTagService
	accounts              *services.AccountService
	users                 *services.UserService
}

// Initialize a large language models api singleton instance
var (
	LargeLanguageModels = &LargeLanguageModelsApi{
		ApiUsingConfig: ApiUsingConfig{
			container: settings.Container,
		},
		transactionCategories: services.TransactionCategories,
		transactionTags:       services.TransactionTags,
		accounts:              services.Accounts,
		users:                 services.Users,
	}
)

// RecognizeTransactionTextHandler returns the recognized transaction text result
func (a *LargeLanguageModelsApi) RecognizeTransactionTextHandler(c *core.WebContext) (any, *errs.Error) {
	if a.CurrentConfig().TextRecognitionLLMConfig == nil || a.CurrentConfig().TextRecognitionLLMConfig.LLMProvider == "" || !a.CurrentConfig().TransactionFromAITextRecognition {
		return nil, errs.ErrLargeLanguageModelProviderNotEnabled
	}

	clientTimezone, err := c.GetClientTimezone()

	if err != nil {
		log.Warnf(c, "[large_language_models.RecognizeTransactionTextHandler] cannot get client timezone, because %s", err.Error())
		return nil, errs.ErrClientTimezoneOffsetInvalid
	}

	uid := c.GetCurrentUid()
	user, err := a.users.GetUserById(c, uid)

	if err != nil {
		if !errs.IsCustomError(err) {
			log.Warnf(c, "[large_language_models.RecognizeTransactionTextHandler] failed to get user for user \"uid:%d\", because %s", uid, err.Error())
		}

		return false, errs.ErrUserNotFound
	}

	if user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_CREATE_TRANSACTION_FROM_AI_TEXT_RECOGNITION) {
		return false, errs.ErrNotPermittedToPerformThisAction
	}

	var textRecognitionReq models.TransactionTextRecognitionRequest
	err = c.ShouldBindJSON(&textRecognitionReq)

	if err != nil {
		log.Warnf(c, "[large_language_models.RecognizeTransactionTextHandler] parse request failed, because %s", err.Error())
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	if len(textRecognitionReq.Text) == 0 {
		log.Warnf(c, "[large_language_models.RecognizeTransactionTextHandler] there is no text in request for user \"uid:%d\"", uid)
		return nil, errs.ErrNoAIRecognitionText
	}

	text := strings.TrimSpace(textRecognitionReq.Text)

	if len(text) == 0 {
		log.Warnf(c, "[large_language_models.RecognizeTransactionTextHandler] the text in request is empty for user \"uid:%d\"", uid)
		return nil, errs.ErrAIRecognitionTextIsEmpty
	}

	scope, err := services.AIRecognition.GetLedgerEssentialData(c, uid, textRecognitionReq.LedgerId)

	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	systemPrompt, err := templates.GetTemplate(templates.SYSTEM_PROMPT_TRANSACTION_TEXT_RECOGNITION)

	if err != nil {
		log.Errorf(c, "[large_language_models.RecognizeTransactionTextHandler] failed to get system prompt template for user \"uid:%d\", because %s", uid, err.Error())
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	systemPromptParams := map[string]any{
		"CurrentDateTime":          utils.FormatUnixTimeToLongDateTime(time.Now().Unix(), clientTimezone),
		"AllExpenseCategoryNames":  strings.Join(scope.ExpenseCategoryNames, "\n"),
		"AllIncomeCategoryNames":   strings.Join(scope.IncomeCategoryNames, "\n"),
		"AllTransferCategoryNames": strings.Join(scope.TransferCategoryNames, "\n"),
		"AllAccountNames":          strings.Join(scope.AccountNames, "\n"),
		"AllTagNames":              strings.Join(scope.TagNames, "\n"),
	}

	var bodyBuffer bytes.Buffer
	err = systemPrompt.Execute(&bodyBuffer, systemPromptParams)

	if err != nil {
		log.Errorf(c, "[large_language_models.RecognizeTransactionTextHandler] failed to get final system prompt from template for user \"uid:%d\", because %s", uid, err.Error())
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	llmRequest := &data.LargeLanguageModelRequest{
		Stream:         false,
		SystemPrompt:   strings.ReplaceAll(bodyBuffer.String(), "\r\n", "\n"),
		UserPrompt:     []byte(text),
		UserPromptType: data.LARGE_LANGUAGE_MODEL_REQUEST_PROMPT_TYPE_TEXT,
	}

	llmResponse, err := llm.Container.GetJsonResponseByTextRecognitionModel(c, c.GetCurrentUid(), a.CurrentConfig(), llmRequest)

	if err != nil {
		log.Errorf(c, "[large_language_models.RecognizeTransactionTextHandler] model request failed for uid:%d", uid)
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	if llmResponse == nil || len(llmResponse.Content) == 0 || strings.HasPrefix(llmResponse.Content, "{}") {
		return nil, errs.ErrNoTransactionInformation
	}

	var result *models.RecognizedTransactionResult

	if err := json.Unmarshal([]byte(llmResponse.Content), &result); err != nil {
		log.Errorf(c, "[large_language_models.RecognizeTransactionTextHandler] invalid model JSON for uid:%d", uid)
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	if err := scope.Recheck(c); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	response, parseErr := scope.Parse(result, clientTimezone)
	if parseErr != nil {
		return nil, errs.Or(parseErr, errs.ErrOperationFailed)
	}
	return response, nil
}

// RecognizeReceiptImageHandler returns the recognized receipt image result
func (a *LargeLanguageModelsApi) RecognizeReceiptImageHandler(c *core.WebContext) (any, *errs.Error) {
	clientTimezone, err := c.GetClientTimezone()

	if err != nil {
		log.Warnf(c, "[large_language_models.RecognizeReceiptImageHandler] cannot get client timezone, because %s", err.Error())
		return nil, errs.ErrClientTimezoneOffsetInvalid
	}

	uid := c.GetCurrentUid()
	user, err := a.users.GetUserById(c, uid)

	if err != nil {
		if !errs.IsCustomError(err) {
			log.Warnf(c, "[large_language_models.RecognizeReceiptImageHandler] failed to get user for user \"uid:%d\", because %s", uid, err.Error())
		}

		return false, errs.ErrUserNotFound
	}

	form, err := c.MultipartForm()

	if err != nil {
		log.Errorf(c, "[large_language_models.RecognizeReceiptImageHandler] failed to get multi-part form data for user \"uid:%d\", because %s", uid, err.Error())
		return nil, errs.ErrParameterInvalid
	}

	ledgerID := int64(0)
	values := form.Value["ledgerId"]
	if len(values) > 1 {
		return nil, errs.ErrParameterInvalid
	}
	if len(values) == 1 {
		ledgerID, err = strconv.ParseInt(values[0], 10, 64)
		if err != nil || ledgerID < 0 || strconv.FormatInt(ledgerID, 10) != values[0] {
			return nil, errs.ErrParameterInvalid
		}
	}
	if query, present := c.GetQuery("ledgerId"); present && query != strconv.FormatInt(ledgerID, 10) {
		return nil, errs.ErrParameterInvalid
	}
	imageFiles := form.File["image"]

	if len(imageFiles) < 1 {
		log.Warnf(c, "[large_language_models.RecognizeReceiptImageHandler] there is no image in request for user \"uid:%d\"", uid)
		return nil, errs.ErrNoAIRecognitionImage
	}

	if imageFiles[0].Size < 1 {
		log.Warnf(c, "[large_language_models.RecognizeReceiptImageHandler] the size of image in request is zero for user \"uid:%d\"", uid)
		return nil, errs.ErrAIRecognitionImageIsEmpty
	}

	if imageFiles[0].Size > int64(a.CurrentConfig().MaxAIRecognitionPictureFileSize) {
		log.Warnf(c, "[large_language_models.RecognizeReceiptImageHandler] the upload file size \"%d\" exceeds the maximum size \"%d\" of image for user \"uid:%d\"", imageFiles[0].Size, a.CurrentConfig().MaxAIRecognitionPictureFileSize, uid)
		return nil, errs.ErrExceedMaxAIRecognitionImageFileSize
	}

	fileExtension := utils.GetFileNameExtension(imageFiles[0].Filename)
	contentType := utils.GetImageContentType(fileExtension)

	if contentType == "" {
		log.Warnf(c, "[large_language_models.RecognizeReceiptImageHandler] the file extension \"%s\" of image in request is not supported for user \"uid:%d\"", fileExtension, uid)
		return nil, errs.ErrImageTypeNotSupported
	}

	imageFile, err := imageFiles[0].Open()

	if err != nil {
		log.Errorf(c, "[large_language_models.RecognizeReceiptImageHandler] failed to get image file from request for user \"uid:%d\", because %s", uid, err.Error())
		return nil, errs.ErrOperationFailed
	}

	defer imageFile.Close()

	imageData, err := io.ReadAll(imageFile)

	if err != nil {
		log.Errorf(c, "[large_language_models.RecognizeReceiptImageHandler] failed to read image file from request for user \"uid:%d\", because %s", uid, err.Error())
		return nil, errs.ErrOperationFailed
	}

	response, _, recognizeErr := services.AIRecognition.RecognizeReceiptImageInLedger(c, user, a.CurrentConfig(), ledgerID, imageData, contentType, clientTimezone)
	if recognizeErr != nil {
		return nil, errs.Or(recognizeErr, errs.ErrOperationFailed)
	}

	return response, nil
}
