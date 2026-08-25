package api

import (
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"strings"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/log"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
)

// LocalOCRApi forwards receipt screenshots to a LAN OCR sidecar without persisting them.
type LocalOCRApi struct {
	ApiUsingConfig
}

// LocalOCR is the local OCR API singleton.
var LocalOCR = &LocalOCRApi{ApiUsingConfig: ApiUsingConfig{container: settings.Container}}

// RecognizeHandler returns text extracted from one screenshot by the local OCR sidecar.
func (a *LocalOCRApi) RecognizeHandler(c *core.WebContext) (any, *errs.Error) {
	config := a.CurrentConfig()
	if config.LocalOCRServerURL == "" || !config.TransactionFromAITextRecognition ||
		config.TextRecognitionLLMConfig == nil || config.TextRecognitionLLMConfig.LLMProvider == "" {
		return nil, errs.ErrOperationFailed
	}

	form, err := c.MultipartForm()
	if err != nil {
		return nil, errs.ErrParameterInvalid
	}
	imageFiles := form.File["image"]
	if len(imageFiles) < 1 {
		return nil, errs.ErrNoAIRecognitionImage
	}
	imageHeader := imageFiles[0]
	if imageHeader.Size < 1 {
		return nil, errs.ErrAIRecognitionImageIsEmpty
	}
	if imageHeader.Size > int64(config.MaxAIRecognitionPictureFileSize) {
		return nil, errs.ErrExceedMaxAIRecognitionImageFileSize
	}

	extension := utils.GetFileNameExtension(imageHeader.Filename)
	contentType := utils.GetImageContentType(extension)
	if contentType == "" {
		return nil, errs.ErrImageTypeNotSupported
	}

	imageFile, err := imageHeader.Open()
	if err != nil {
		return nil, errs.ErrOperationFailed
	}
	defer imageFile.Close()
	imageData, err := io.ReadAll(imageFile)
	if err != nil {
		return nil, errs.ErrOperationFailed
	}

	var requestBody bytes.Buffer
	writer := multipart.NewWriter(&requestBody)
	part, err := writer.CreateFormFile("image", imageHeader.Filename)
	if err == nil {
		_, err = part.Write(imageData)
	}
	if err == nil {
		err = writer.Close()
	}
	if err != nil {
		return nil, errs.ErrOperationFailed
	}

	request, err := http.NewRequestWithContext(c.Request.Context(), http.MethodPost, config.LocalOCRServerURL+"/ocr", &requestBody)
	if err != nil {
		return nil, errs.ErrOperationFailed
	}
	request.Header.Set("Content-Type", writer.FormDataContentType())
	client := &http.Client{Timeout: time.Duration(config.LocalOCRRequestTimeout) * time.Millisecond}
	response, err := client.Do(request)
	if err != nil {
		log.Errorf(c, "[local_ocr.RecognizeHandler] local OCR request failed for user \"uid:%d\", because %s", c.GetCurrentUid(), err.Error())
		return nil, errs.ErrOperationFailed
	}
	defer response.Body.Close()
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		log.Errorf(c, "[local_ocr.RecognizeHandler] local OCR returned status %d for user \"uid:%d\"", response.StatusCode, c.GetCurrentUid())
		return nil, errs.ErrOperationFailed
	}

	var result models.LocalOCRResponse
	if err := json.NewDecoder(io.LimitReader(response.Body, 2*1024*1024)).Decode(&result); err != nil {
		return nil, errs.ErrOperationFailed
	}
	result.Text = strings.TrimSpace(result.Text)
	if result.Text == "" {
		return nil, errs.ErrNoTransactionInformation
	}

	return &result, nil
}
