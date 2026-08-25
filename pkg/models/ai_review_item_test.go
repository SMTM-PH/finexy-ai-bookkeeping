package models

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestAIReviewItemToInfoResponseRestoresRecognizedData(t *testing.T) {
	item := &AIReviewItem{
		AIReviewItemId: 123,
		SourceType:     AI_REVIEW_SOURCE_TEXT,
		SourceText:     "今天中午盒饭花费16元",
		RecognizedData: `{"type":3,"sourceAmount":1600,"comment":"盒饭"}`,
	}

	response := item.ToInfoResponse()

	assert.NotNil(t, response.RecognizedData)
	assert.Equal(t, TRANSACTION_TYPE_EXPENSE, response.RecognizedData.Type)
	assert.Equal(t, int64(1600), response.RecognizedData.SourceAmount)
	assert.Equal(t, "盒饭", response.RecognizedData.Comment)
}

func TestAIReviewItemToInfoResponseIgnoresInvalidRecognizedData(t *testing.T) {
	item := &AIReviewItem{RecognizedData: "not-json"}

	assert.Nil(t, item.ToInfoResponse().RecognizedData)
}
