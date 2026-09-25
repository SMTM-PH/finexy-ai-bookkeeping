package mcp

import (
	"encoding/json"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

func TestRecognizeReceiptImageToolIsRegistered(t *testing.T) {
	if err := InitializeMCPHandlers(&settings.Config{}); err != nil {
		t.Fatalf("InitializeMCPHandlers() error = %v", err)
	}

	for _, tool := range Container.GetMCPTools() {
		if tool.Name == "recognize_receipt_image" {
			if tool.InputSchema == nil || tool.OutputSchema == nil {
				t.Fatal("recognize_receipt_image must publish input and output schemas")
			}
			return
		}
	}

	t.Fatal("recognize_receipt_image was not registered")
}

func TestRecognizeReceiptImageRejectsInvalidBase64BeforeCallingModel(t *testing.T) {
	arguments, err := json.Marshal(MCPRecognizeReceiptImageRequest{
		ImageBase64: "not-valid-base64",
		MimeType:    "image/jpeg",
	})
	if err != nil {
		t.Fatal(err)
	}

	_, _, handleErr := MCPRecognizeReceiptImageToolHandler.Handle(nil, &MCPCallToolRequest{Arguments: arguments}, nil, &settings.Config{MaxAIRecognitionPictureFileSize: 1024}, nil)
	if handleErr == nil || !errs.IsCustomError(handleErr) {
		t.Fatalf("expected a validation error, got %v", handleErr)
	}
}

func TestRecognizeReceiptImageRejectsOversizedPayloadBeforeDecode(t *testing.T) {
	arguments, err := json.Marshal(MCPRecognizeReceiptImageRequest{
		ImageBase64: "YWJjZA==",
		MimeType:    "image/jpeg",
	})
	if err != nil {
		t.Fatal(err)
	}

	_, _, handleErr := MCPRecognizeReceiptImageToolHandler.Handle(nil, &MCPCallToolRequest{Arguments: arguments}, nil, &settings.Config{MaxAIRecognitionPictureFileSize: 3}, nil)
	if handleErr != errs.ErrExceedMaxAIRecognitionImageFileSize {
		t.Fatalf("expected ErrExceedMaxAIRecognitionImageFileSize, got %v", handleErr)
	}
}
