package services

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
	"xorm.io/xorm"
)

func resolveAIReviewLedger(c core.Context, actor, ledgerID int64, capability func(models.FamilyMemberRole) bool) (*datastore.Database, *models.Ledger, error) {
	if ledgerID < 0 {
		return nil, nil, errs.ErrLedgerNotFound
	}
	db, ledger, err := Ledgers.GetLedgerWithAccess(c, actor, ledgerID, capability)
	if err != nil {
		return nil, nil, err
	}
	if db == nil {
		db = AIReviewItems.UserDataDB(ledger.OwnerUid)
	}
	return db, ledger, nil
}

func aiReviewHash(value any) (string, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return "", errs.ErrParameterInvalid
	}
	hash := sha256.Sum256(data)
	return hex.EncodeToString(hash[:]), nil
}

// Startup migration is idempotent. Existing sources and statuses stay on ledger
// zero; no historical financial transaction is inferred or reconstructed.
func (s *AIReviewItemService) MigrateLegacyReviewItems(c core.Context) error {
	for i := 0; i < s.UserDataDBCount(); i++ {
		err := s.UserDataDBByIndex(i).DoTransaction(c, func(sess *xorm.Session) error {
			_, err := sess.Where("creator_uid=0").SetExpr("creator_uid", "uid").Update(new(models.AIReviewItem))
			return err
		})
		if err != nil {
			return err
		}
	}
	return nil
}

func reviewCreator(item *models.AIReviewItem) int64 {
	if item.CreatorUid > 0 {
		return item.CreatorUid
	}
	return item.Uid
}
func reviewEditable(sess *xorm.Session, actor int64, ledger *models.Ledger, item *models.AIReviewItem) (bool, error) {
	if item.Deleted {
		return false, nil
	}
	if ledger.LedgerId == 0 {
		return actor == ledger.OwnerUid, nil
	}
	member := &models.LedgerMember{}
	found, err := sess.Where("ledger_id=? AND uid=?", ledger.LedgerId, actor).Get(member)
	if err != nil {
		return false, err
	}
	if !found {
		return actor == ledger.OwnerUid && ledger.Type == models.LEDGER_TYPE_PERSONAL, nil
	}
	return member.CanAct(func(role models.FamilyMemberRole) bool {
		return role.CanManage() || (role.CanWrite() && actor == reviewCreator(item))
	}), nil
}
func authorizeReviewWrite(sess *xorm.Session, actor int64, ledger *models.Ledger, item *models.AIReviewItem) error {
	if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanWrite, true); err != nil {
		return err
	}
	// A deleted confirmation receipt is still readable by its original eligible
	// reviewer. It must never permit another financial write.
	copy := *item
	copy.Deleted = false
	editable, err := reviewEditable(sess, actor, ledger, &copy)
	if err != nil {
		return err
	}
	if !editable {
		return errs.ErrLedgerAccessDenied
	}
	return nil
}

func (s *AIReviewItemService) CreateInLedger(c core.Context, actor int64, request models.AIReviewItemCreateRequest) (*models.AIReviewItem, error) {
	db, ledger, err := resolveAIReviewLedger(c, actor, request.LedgerId, models.FamilyMemberRole.CanWrite)
	if err != nil {
		return nil, err
	}
	if request.SourceType < models.AI_REVIEW_SOURCE_TEXT || request.SourceType > models.AI_REVIEW_SOURCE_IMPORT {
		return nil, errs.ErrAIReviewItemSourceTypeInvalid
	}
	if request.RecognizedData != nil && (request.RecognizedData.LedgerId < 0 || (request.RecognizedData.LedgerId != 0 && request.RecognizedData.LedgerId != request.LedgerId)) {
		return nil, errs.ErrParameterInvalid
	}
	request.SourceText = strings.TrimSpace(request.SourceText)
	request.FailureReason = strings.TrimSpace(request.FailureReason)
	if request.SourceText == "" || len(request.SourceText) > 8000 {
		return nil, errs.ErrAIReviewItemTextInvalid
	}
	if utf8.RuneCountInString(request.FailureReason) > 500 || len(request.ClientSessionId) > 128 || (request.ClientSessionId != "" && strings.TrimSpace(request.ClientSessionId) == "") || (request.LedgerId > 0 && request.ClientSessionId == "") {
		return nil, errs.ErrParameterInvalid
	}
	hash, err := aiReviewHash(request)
	if err != nil {
		return nil, err
	}
	recognized := ""
	if request.RecognizedData != nil {
		data, err := json.Marshal(request.RecognizedData)
		if err != nil {
			return nil, errs.ErrParameterInvalid
		}
		recognized = string(data)
	}
	now := time.Now().Unix()
	item := &models.AIReviewItem{AIReviewItemId: s.GenerateUuid(uuid.UUID_TYPE_AI_REVIEW_ITEM), Uid: ledger.OwnerUid,
		LedgerId: ledger.LedgerId, CreatorUid: actor, CreationHash: hash, SourceType: request.SourceType,
		Status: models.AI_REVIEW_STATUS_PENDING, SourceText: request.SourceText, RecognizedData: recognized,
		FailureReason: request.FailureReason, CreatedUnixTime: now, UpdatedUnixTime: now, Editable: true}
	if item.AIReviewItemId <= 0 {
		return nil, errs.ErrSystemIsBusy
	}
	if request.ClientSessionId != "" {
		key := request.ClientSessionId
		item.ClientSessionId = &key
	}
	replay := func(sess *xorm.Session) (bool, error) {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanWrite, true); err != nil {
			return false, err
		}
		if item.ClientSessionId == nil {
			return false, nil
		}
		stored := &models.AIReviewItem{}
		found, err := sess.Where("uid=? AND ledger_id=? AND creator_uid=? AND client_session_id=?", ledger.OwnerUid, ledger.LedgerId, actor, request.ClientSessionId).Get(stored)
		if err != nil || !found {
			return false, err
		}
		if stored.CreationHash != hash {
			return false, errs.ErrAIReviewItemConflict
		}
		stored.Editable, err = reviewEditable(sess, actor, ledger, stored)
		if err == nil {
			item = stored
		}
		return true, err
	}
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if found, err := replay(sess); err != nil || found {
			return err
		}
		_, err := sess.Insert(item)
		return err
	})
	if err != nil && request.ClientSessionId != "" {
		// Only recover an existing receipt after rollback; never retry a write.
		found := false
		readErr := db.DoTransaction(c, func(sess *xorm.Session) error { var e error; found, e = replay(sess); return e })
		if readErr != nil {
			return nil, readErr
		}
		if found {
			return item, nil
		}
	}
	return item, err
}

func (s *AIReviewItemService) ListInLedger(c core.Context, actor, ledgerID int64, status models.AIReviewStatus, beforeID int64, limit int) ([]*models.AIReviewItem, error) {
	if status < models.AI_REVIEW_STATUS_PENDING || status > models.AI_REVIEW_STATUS_DISMISSED || beforeID < 0 || limit < 1 || limit > 100 {
		return nil, errs.ErrParameterInvalid
	}
	db, ledger, err := resolveAIReviewLedger(c, actor, ledgerID, anyFamilyRole)
	if err != nil {
		return nil, err
	}
	items := make([]*models.AIReviewItem, 0)
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, anyFamilyRole, false); err != nil {
			return err
		}
		query := sess.Where("uid=? AND ledger_id=? AND deleted=? AND status=?", ledger.OwnerUid, ledgerID, false, status)
		if beforeID > 0 {
			query = query.And("a_i_review_item_id<?", beforeID)
		}
		if err := query.Desc("a_i_review_item_id").Limit(limit).Find(&items); err != nil {
			return err
		}
		for _, item := range items {
			var err error
			item.Editable, err = reviewEditable(sess, actor, ledger, item)
			if err != nil {
				return err
			}
		}
		return nil
	})
	return items, err
}

func readReview(sess *xorm.Session, owner, ledgerID, id int64) (*models.AIReviewItem, error) {
	item := &models.AIReviewItem{}
	found, err := sess.Where("uid=? AND ledger_id=? AND a_i_review_item_id=?", owner, ledgerID, id).Get(item)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, errs.ErrAIReviewItemNotFound
	}
	return item, nil
}
func (s *AIReviewItemService) GetInLedger(c core.Context, actor, ledgerID, id int64) (*models.AIReviewItem, error) {
	db, ledger, err := resolveAIReviewLedger(c, actor, ledgerID, anyFamilyRole)
	if err != nil {
		return nil, err
	}
	var item *models.AIReviewItem
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, anyFamilyRole, false); err != nil {
			return err
		}
		var err error
		item, err = readReview(sess, ledger.OwnerUid, ledgerID, id)
		if err != nil {
			return err
		}
		item.Editable, err = reviewEditable(sess, actor, ledger, item)
		return err
	})
	return item, err
}

func (s *AIReviewItemService) changeInLedger(c core.Context, actor, ledgerID, id int64, action string, status models.AIReviewStatus) error {
	db, ledger, err := resolveAIReviewLedger(c, actor, ledgerID, models.FamilyMemberRole.CanWrite)
	if err != nil {
		return err
	}
	return db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanWrite, true); err != nil {
			return err
		}
		if _, err := sess.Where("uid=? AND ledger_id=? AND a_i_review_item_id=?", ledger.OwnerUid, ledgerID, id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.AIReviewItem)); err != nil {
			return err
		}
		item, err := readReview(sess, ledger.OwnerUid, ledgerID, id)
		if err != nil {
			return err
		}
		if err := authorizeReviewWrite(sess, actor, ledger, item); err != nil {
			return err
		}
		if item.Deleted {
			if action == "delete" {
				return nil
			}
			return errs.ErrAIReviewItemNotFound
		}
		if action == "delete" {
			_, err = sess.ID(id).Cols("deleted", "updated_unix_time", "deleted_unix_time").Update(&models.AIReviewItem{Deleted: true, UpdatedUnixTime: time.Now().Unix(), DeletedUnixTime: time.Now().Unix()})
			return err
		}
		if status == models.AI_REVIEW_STATUS_RESOLVED && (ledgerID != 0 || item.ClientSessionId != nil) {
			return errs.ErrAIReviewConfirmationRequired
		}
		if item.Status == status {
			return nil
		}
		if (status == models.AI_REVIEW_STATUS_PENDING && item.Status != models.AI_REVIEW_STATUS_DISMISSED) || (status != models.AI_REVIEW_STATUS_PENDING && item.Status != models.AI_REVIEW_STATUS_PENDING) {
			return errs.ErrAIReviewItemConflict
		}
		_, err = sess.ID(id).Cols("status", "updated_unix_time").Update(&models.AIReviewItem{Status: status, UpdatedUnixTime: time.Now().Unix()})
		return err
	})
}
func (s *AIReviewItemService) UpdateStatusInLedger(c core.Context, actor, ledgerID, id int64, status models.AIReviewStatus) error {
	if status < 1 || status > 3 {
		return errs.ErrParameterInvalid
	}
	return s.changeInLedger(c, actor, ledgerID, id, "status", status)
}
func (s *AIReviewItemService) DeleteInLedger(c core.Context, actor, ledgerID, id int64) error {
	return s.changeInLedger(c, actor, ledgerID, id, "delete", 0)
}
