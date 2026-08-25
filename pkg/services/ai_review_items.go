package services

import (
	"encoding/json"
	"strings"
	"time"

	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/uuid"
)

type AIReviewItemService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

var AIReviewItems = &AIReviewItemService{
	ServiceUsingDB:   ServiceUsingDB{container: datastore.Container},
	ServiceUsingUuid: ServiceUsingUuid{container: uuid.Container},
}

func (s *AIReviewItemService) Create(c core.Context, uid int64, sourceType models.AIReviewSourceType, sourceText string, recognizedData *models.RecognizedTransactionResponse, reason string) (*models.AIReviewItem, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}
	if sourceType < models.AI_REVIEW_SOURCE_TEXT || sourceType > models.AI_REVIEW_SOURCE_IMPORT {
		return nil, errs.ErrAIReviewItemSourceTypeInvalid
	}
	text := strings.TrimSpace(sourceText)
	if text == "" || len(text) > 8000 {
		return nil, errs.ErrAIReviewItemTextInvalid
	}
	recognizedDataJSON := ""
	if recognizedData != nil {
		encoded, err := json.Marshal(recognizedData)
		if err != nil {
			return nil, err
		}
		recognizedDataJSON = string(encoded)
	}
	now := time.Now().Unix()
	item := &models.AIReviewItem{
		AIReviewItemId: s.GenerateUuid(uuid.UUID_TYPE_AI_REVIEW_ITEM), Uid: uid,
		SourceType: sourceType, Status: models.AI_REVIEW_STATUS_PENDING,
		SourceText: text, RecognizedData: recognizedDataJSON,
		FailureReason: strings.TrimSpace(reason), CreatedUnixTime: now, UpdatedUnixTime: now,
	}
	if item.AIReviewItemId < 1 {
		return nil, errs.ErrSystemIsBusy
	}
	_, err := s.UserDataDB(uid).NewSession(c).Insert(item)
	return item, err
}

func (s *AIReviewItemService) ListPending(c core.Context, uid int64) ([]*models.AIReviewItem, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}
	items := make([]*models.AIReviewItem, 0)
	err := s.UserDataDB(uid).NewSession(c).Where("uid=? AND deleted=? AND status=?", uid, false, models.AI_REVIEW_STATUS_PENDING).
		Desc("created_unix_time").Limit(100).Find(&items)
	return items, err
}

func (s *AIReviewItemService) UpdateStatus(c core.Context, uid, id int64, status models.AIReviewStatus) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}
	if id <= 0 {
		return errs.ErrAIReviewItemNotFound
	}
	update := &models.AIReviewItem{Status: status, UpdatedUnixTime: time.Now().Unix()}
	rows, err := s.UserDataDB(uid).NewSession(c).Cols("status", "updated_unix_time").
		Where("a_i_review_item_id=? AND uid=? AND deleted=? AND status=?", id, uid, false, models.AI_REVIEW_STATUS_PENDING).Update(update)
	if err != nil {
		return err
	}
	if rows < 1 {
		return errs.ErrAIReviewItemNotFound
	}
	return nil
}

func (s *AIReviewItemService) Delete(c core.Context, uid, id int64) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}
	if id <= 0 {
		return errs.ErrAIReviewItemNotFound
	}
	now := time.Now().Unix()
	update := &models.AIReviewItem{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now}
	rows, err := s.UserDataDB(uid).NewSession(c).Cols("deleted", "deleted_unix_time", "updated_unix_time").
		Where("a_i_review_item_id=? AND uid=? AND deleted=?", id, uid, false).Update(update)
	if err != nil {
		return err
	}
	if rows < 1 {
		return errs.ErrAIReviewItemNotFound
	}
	return nil
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
