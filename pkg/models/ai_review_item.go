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
	AIReviewItemId   int64   `xorm:"PK"`
	Uid              int64   `xorm:"INDEX(IDX_ai_review_item_uid_deleted_status) INDEX(IDX_ai_review_ledger_status) UNIQUE(UQE_ai_review_create) NOT NULL"`
	LedgerId         int64   `xorm:"NOT NULL DEFAULT 0 INDEX(IDX_ai_review_ledger_status) UNIQUE(UQE_ai_review_create)"`
	CreatorUid       int64   `xorm:"NOT NULL DEFAULT 0 UNIQUE(UQE_ai_review_create)"`
	ClientSessionId  *string `xorm:"VARCHAR(128) UNIQUE(UQE_ai_review_create)"`
	CreationHash     string  `xorm:"VARCHAR(64)"`
	ConfirmationHash string  `xorm:"VARCHAR(64)"`
	TransactionId    int64   `xorm:"NOT NULL DEFAULT 0"`
	ConfirmedUid     int64   `xorm:"NOT NULL DEFAULT 0"`
	Editable         bool    `xorm:"-"`
	Deleted          bool    `xorm:"INDEX(IDX_ai_review_item_uid_deleted_status) INDEX(IDX_ai_review_ledger_status) NOT NULL"`
	SourceType       AIReviewSourceType
	Status           AIReviewStatus `xorm:"INDEX(IDX_ai_review_item_uid_deleted_status) INDEX(IDX_ai_review_ledger_status) NOT NULL"`
	SourceText       string         `xorm:"TEXT NOT NULL"`
	RecognizedData   string         `xorm:"TEXT"`
	FailureReason    string         `xorm:"VARCHAR(500)"`
	CreatedUnixTime  int64
	UpdatedUnixTime  int64
	DeletedUnixTime  int64
}

type AIReviewItemCreateRequest struct {
	LedgerId        int64                          `json:"ledgerId,string" binding:"min=0"`
	ClientSessionId string                         `json:"clientSessionId" binding:"max=128"`
	SourceType      AIReviewSourceType             `json:"sourceType" binding:"required,min=1,max=3"`
	SourceText      string                         `json:"sourceText" binding:"required,max=8000"`
	RecognizedData  *RecognizedTransactionResponse `json:"recognizedData"`
	FailureReason   string                         `json:"failureReason" binding:"max=500"`
}

type AIReviewItemStatusRequest struct {
	LedgerId int64 `json:"ledgerId,string" binding:"min=0"`
	Id       int64 `json:"id,string" binding:"required"`
}

type AIReviewItemConfirmRequest struct {
	LedgerId    int64                    `json:"ledgerId,string" binding:"min=0"`
	Id          int64                    `json:"id,string" binding:"required,min=1"`
	Transaction TransactionCreateRequest `json:"transaction" binding:"required"`
}

type AIReviewItemInfoResponse struct {
	Id              int64                          `json:"id,string"`
	LedgerId        int64                          `json:"ledgerId,string"`
	CreatorUid      int64                          `json:"creatorUid,string"`
	TransactionId   int64                          `json:"transactionId,string"`
	ConfirmedUid    int64                          `json:"confirmedUid,string"`
	Editable        bool                           `json:"editable"`
	Deleted         bool                           `json:"deleted"`
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
		LedgerId: item.LedgerId, CreatorUid: item.CreatorUid, TransactionId: item.TransactionId,
		ConfirmedUid: item.ConfirmedUid, Editable: item.Editable && !item.Deleted, Deleted: item.Deleted,
		SourceText: item.SourceText, RecognizedData: recognizedData,
		FailureReason: item.FailureReason, CreatedUnixTime: item.CreatedUnixTime,
	}
}
