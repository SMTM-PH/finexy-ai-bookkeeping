package mcp

import (
	"encoding/base64"
	"encoding/json"
	"reflect"
	"strings"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
)

// MCPRecognizeReceiptImageRequest contains a base64-encoded receipt image.
type MCPRecognizeReceiptImageRequest struct {
	ImageBase64           string `json:"image_base64" jsonschema_description:"Base64-encoded receipt image data without a data URL prefix"`
	MimeType              string `json:"mime_type" jsonschema:"enum=image/jpeg,enum=image/png,enum=image/gif,enum=image/webp" jsonschema_description:"Image MIME type"`
	TimezoneOffsetMinutes int16  `json:"timezone_offset_minutes,omitempty" jsonschema:"minimum=-720,maximum=840" jsonschema_description:"Timezone offset in minutes used to interpret recognized dates; defaults to UTC"`
}

// MCPRecognizeReceiptImageResponse is a reviewable transaction draft. It is never saved automatically.
type MCPRecognizeReceiptImageResponse struct {
	Type                      string   `json:"type"`
	Time                      string   `json:"time,omitempty"`
	Amount                    string   `json:"amount,omitempty"`
	AccountName               string   `json:"account_name,omitempty"`
	AccountId                 string   `json:"account_id,omitempty"`
	CategoryName              string   `json:"category_name,omitempty"`
	CategoryId                string   `json:"category_id,omitempty"`
	DestinationAmount         string   `json:"destination_amount,omitempty"`
	DestinationAccountName    string   `json:"destination_account_name,omitempty"`
	DestinationAccountId      string   `json:"destination_account_id,omitempty"`
	Tags                      []string `json:"tags,omitempty"`
	TagIds                    []string `json:"tag_ids,omitempty"`
	Comment                   string   `json:"comment,omitempty"`
	RequiresReview            bool     `json:"requires_review"`
	AccountMatched            bool     `json:"account_matched"`
	CategoryMatched           bool     `json:"category_matched"`
	DestinationAccountMatched bool     `json:"destination_account_matched,omitempty"`
}

type mcpRecognizeReceiptImageToolHandler struct{}

var MCPRecognizeReceiptImageToolHandler = &mcpRecognizeReceiptImageToolHandler{}

func (h *mcpRecognizeReceiptImageToolHandler) Name() string {
	return "recognize_receipt_image"
}

func (h *mcpRecognizeReceiptImageToolHandler) Description() string {
	return "Recognize a receipt image with Finexy's configured vision model and return a reviewable transaction draft. This tool never saves a transaction."
}

func (h *mcpRecognizeReceiptImageToolHandler) InputType() reflect.Type {
	return reflect.TypeOf(&MCPRecognizeReceiptImageRequest{})
}

func (h *mcpRecognizeReceiptImageToolHandler) OutputType() reflect.Type {
	return reflect.TypeOf(&MCPRecognizeReceiptImageResponse{})
}

func (h *mcpRecognizeReceiptImageToolHandler) Handle(c *core.WebContext, callToolReq *MCPCallToolRequest, user *models.User, currentConfig *settings.Config, availableServices MCPAvailableServices) (any, []*MCPTextContent, error) {
	var request MCPRecognizeReceiptImageRequest
	if callToolReq.Arguments == nil {
		return nil, nil, errs.ErrIncompleteOrIncorrectSubmission
	}
	if err := json.Unmarshal(callToolReq.Arguments, &request); err != nil {
		return nil, nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	request.ImageBase64 = strings.TrimSpace(request.ImageBase64)
	request.MimeType = strings.ToLower(strings.TrimSpace(request.MimeType))
	if request.ImageBase64 == "" {
		return nil, nil, errs.ErrNoAIRecognitionImage
	}

	if base64.StdEncoding.DecodedLen(len(request.ImageBase64)) > int(currentConfig.MaxAIRecognitionPictureFileSize) {
		return nil, nil, errs.ErrExceedMaxAIRecognitionImageFileSize
	}

	imageData, err := base64.StdEncoding.DecodeString(request.ImageBase64)
	if err != nil {
		return nil, nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	timezone := time.FixedZone("MCP Client Timezone", int(request.TimezoneOffsetMinutes)*60)
	recognized, raw, err := availableServices.GetAIRecognitionService().RecognizeReceiptImage(c, user, currentConfig, imageData, request.MimeType, timezone)
	if err != nil {
		return nil, nil, err
	}

	response := &MCPRecognizeReceiptImageResponse{
		Type:                      raw.Type,
		AccountName:               raw.AccountName,
		CategoryName:              raw.CategoryName,
		DestinationAccountName:    raw.DestinationAccountName,
		Tags:                      raw.TagNames,
		TagIds:                    recognized.TagIds,
		Comment:                   raw.Description,
		RequiresReview:            true,
		AccountMatched:            recognized.SourceAccountId > 0,
		CategoryMatched:           recognized.CategoryId > 0,
		DestinationAccountMatched: recognized.DestinationAccountId > 0,
	}

	if recognized.Time > 0 {
		response.Time = time.Unix(recognized.Time, 0).In(timezone).Format(time.RFC3339)
	}
	if recognized.SourceAmount > 0 {
		response.Amount = utils.FormatAmount(recognized.SourceAmount)
	}
	if recognized.DestinationAmount > 0 {
		response.DestinationAmount = utils.FormatAmount(recognized.DestinationAmount)
	}
	if recognized.SourceAccountId > 0 {
		response.AccountId = utils.Int64ToString(recognized.SourceAccountId)
	}
	if recognized.CategoryId > 0 {
		response.CategoryId = utils.Int64ToString(recognized.CategoryId)
	}
	if recognized.DestinationAccountId > 0 {
		response.DestinationAccountId = utils.Int64ToString(recognized.DestinationAccountId)
	}

	content, err := json.Marshal(response)
	if err != nil {
		return nil, nil, err
	}
	return response, []*MCPTextContent{NewMCPTextContent(string(content))}, nil
}
