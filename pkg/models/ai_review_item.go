package models

import "encoding/json"

type AIReviewSourceType byte
type AIReviewStatus byte

const (
	AI_REVIEW_SOURCE_TEXT   AIReviewSourceType = 1
	AI_REVIEW_SOURCE_IMAGE  AIReviewSourceType = 2
	AI_REVIEW_SOURCE_IMPORT AIReviewSourceType = 3

	AI_REVIEW_STATUS_PENDING   AIReviewStatus = 1
	AI_REVIEW_STATUS_RESOLVED  AIReviewStatus = 2
	AI_REVIEW_STATUS_DISMISSED AIReviewStatus = 3
)

// AIReviewItem stores text that could not be posted automatically. Source images are never stored.
type AIReviewItem struct {
	AIReviewItemId  int64 `xorm:"PK"`
	Uid             int64 `xorm:"INDEX(IDX_ai_review_item_uid_deleted_status) NOT NULL"`
	Deleted         bool  `xorm:"INDEX(IDX_ai_review_item_uid_deleted_status) NOT NULL"`
	SourceType      AIReviewSourceType
	Status          AIReviewStatus `xorm:"INDEX(IDX_ai_review_item_uid_deleted_status) NOT NULL"`
	SourceText      string         `xorm:"TEXT NOT NULL"`
	RecognizedData  string         `xorm:"TEXT"`
	FailureReason   string         `xorm:"VARCHAR(500)"`
	CreatedUnixTime int64
	UpdatedUnixTime int64
	DeletedUnixTime int64
}

type AIReviewItemCreateRequest struct {
	SourceType     AIReviewSourceType             `json:"sourceType" binding:"required,min=1,max=3"`
	SourceText     string                         `json:"sourceText" binding:"required,max=8000"`
	RecognizedData *RecognizedTransactionResponse `json:"recognizedData"`
	FailureReason  string                         `json:"failureReason" binding:"max=500"`
}

type AIReviewItemStatusRequest struct {
	Id int64 `json:"id,string" binding:"required"`
}

type AIReviewItemInfoResponse struct {
	Id              int64                          `json:"id,string"`
	SourceType      AIReviewSourceType             `json:"sourceType"`
	Status          AIReviewStatus                 `json:"status"`
	SourceText      string                         `json:"sourceText"`
	RecognizedData  *RecognizedTransactionResponse `json:"recognizedData,omitempty"`
	FailureReason   string                         `json:"failureReason"`
	CreatedUnixTime int64                          `json:"createdUnixTime"`
}

func (item *AIReviewItem) ToInfoResponse() *AIReviewItemInfoResponse {
	var recognizedData *RecognizedTransactionResponse
	if item.RecognizedData != "" {
		recognizedData = &RecognizedTransactionResponse{}
		if err := json.Unmarshal([]byte(item.RecognizedData), recognizedData); err != nil {
			recognizedData = nil
		}
	}

	return &AIReviewItemInfoResponse{
		Id: item.AIReviewItemId, SourceType: item.SourceType, Status: item.Status,
		SourceText: item.SourceText, RecognizedData: recognizedData,
		FailureReason: item.FailureReason, CreatedUnixTime: item.CreatedUnixTime,
	}
}
