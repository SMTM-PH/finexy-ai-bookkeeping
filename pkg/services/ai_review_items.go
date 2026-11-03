package services

import (
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
)

type AIReviewItemService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

var AIReviewItems = &AIReviewItemService{
	ServiceUsingDB:   ServiceUsingDB{container: datastore.Container},
	ServiceUsingUuid: ServiceUsingUuid{container: uuid.Container},
}

// Legacy callers stay on the implicit personal ledger.
func (s *AIReviewItemService) Create(c core.Context, uid int64, sourceType models.AIReviewSourceType, sourceText string, recognizedData *models.RecognizedTransactionResponse, reason string) (*models.AIReviewItem, error) {
	return s.CreateInLedger(c, uid, models.AIReviewItemCreateRequest{SourceType: sourceType, SourceText: sourceText, RecognizedData: recognizedData, FailureReason: reason})
}
func (s *AIReviewItemService) ListPending(c core.Context, uid int64) ([]*models.AIReviewItem, error) {
	return s.ListInLedger(c, uid, 0, models.AI_REVIEW_STATUS_PENDING, 0, 100)
}
func (s *AIReviewItemService) UpdateStatus(c core.Context, uid, id int64, status models.AIReviewStatus) error {
	return s.UpdateStatusInLedger(c, uid, 0, id, status)
}
func (s *AIReviewItemService) Delete(c core.Context, uid, id int64) error {
	return s.DeleteInLedger(c, uid, 0, id)
}

func (s *AIReviewItemService) DeleteAll(c core.Context, uid int64) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}
	now := time.Now().Unix()
	update := &models.AIReviewItem{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now}
	_, err := s.UserDataDB(uid).NewSession(c).Cols("deleted", "deleted_unix_time", "updated_unix_time").Where("uid=? AND deleted=?", uid, false).Update(update)
	return err
}
