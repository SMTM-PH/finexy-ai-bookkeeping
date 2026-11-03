package services

import (
	"errors"
	"math"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
	"xorm.io/xorm"
)

func validateAIReviewConfirmation(request models.AIReviewItemConfirmRequest) ([]int64, []int64, error) {
	tx := request.Transaction
	if request.Id <= 0 || request.LedgerId < 0 || tx.LedgerId != request.LedgerId || tx.Type < models.TRANSACTION_TYPE_INCOME || tx.Type > models.TRANSACTION_TYPE_TRANSFER || tx.Time <= 0 || tx.Time > (math.MaxInt64-999)/1000 || tx.UtcOffset < -720 || tx.UtcOffset > 840 || tx.CategoryId <= 0 || tx.SourceAccountId < 0 || tx.DestinationAccountId < 0 || tx.SourceAmount < -9999999999999 || tx.SourceAmount > 9999999999999 || tx.DestinationAmount < -9999999999999 || tx.DestinationAmount > 9999999999999 || utf8.RuneCountInString(tx.Comment) > 255 || len(tx.ClientSessionId) > 128 || strings.TrimSpace(tx.ClientSessionId) == "" {
		return nil, nil, errs.ErrParameterInvalid
	}
	if tx.Type != models.TRANSACTION_TYPE_TRANSFER && (tx.SourceAccountId <= 0 || tx.DestinationAccountId != 0 || tx.DestinationAmount != 0) {
		return nil, nil, errs.ErrAccountIdInvalid
	}
	if tx.Type == models.TRANSACTION_TYPE_TRANSFER && (tx.SourceAccountId == tx.DestinationAccountId || tx.SourceAmount < 0 || tx.DestinationAmount < 0) {
		return nil, nil, errs.ErrAccountIdInvalid
	}
	if tx.Type == models.TRANSACTION_TYPE_TRANSFER && ((tx.SourceAccountId == 0 && tx.SourceAmount != 0) || (tx.DestinationAccountId == 0 && tx.DestinationAmount != 0)) {
		return nil, nil, errs.ErrParameterInvalid
	}
	if tx.GeoLocation != nil && (math.IsNaN(tx.GeoLocation.Latitude) || math.IsInf(tx.GeoLocation.Latitude, 0) || math.IsNaN(tx.GeoLocation.Longitude) || math.IsInf(tx.GeoLocation.Longitude, 0) || math.Abs(tx.GeoLocation.Latitude) > 90 || math.Abs(tx.GeoLocation.Longitude) > 180) {
		return nil, nil, errs.ErrParameterInvalid
	}
	tags, err := utils.StringArrayToInt64Array(tx.TagIds)
	if err != nil || len(tags) > models.MaximumTagsCountOfTransaction {
		return nil, nil, errs.ErrTransactionTagIdInvalid
	}
	pictures, err := utils.StringArrayToInt64Array(tx.PictureIds)
	if err != nil || len(pictures) > models.MaximumPicturesCountOfTransaction {
		return nil, nil, errs.ErrTransactionPictureIdInvalid
	}
	for _, id := range append(append([]int64{}, tags...), pictures...) {
		if id <= 0 {
			return nil, nil, errs.ErrParameterInvalid
		}
	}
	return tags, pictures, nil
}

// Explicit edited input, the source queue and all financial changes are committed
// together. The review ID is the durable operation boundary; the frozen content
// and client key are bound to its receipt, including after financial deletion.
func (s *AIReviewItemService) ConfirmInLedger(c core.Context, actor int64, request models.AIReviewItemConfirmRequest, clientIP string, timezone *time.Location) (int64, error) {
	tags, pictures, err := validateAIReviewConfirmation(request)
	if err != nil {
		return 0, err
	}
	db, ledger, err := resolveAIReviewLedger(c, actor, request.LedgerId, models.FamilyMemberRole.CanWrite)
	if err != nil {
		return 0, err
	}
	hash, err := aiReviewHash(request.Transaction)
	if err != nil {
		return 0, err
	}
	var original *models.AIReviewItem
	read := func() error {
		return db.DoTransaction(c, func(sess *xorm.Session) error {
			if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanWrite, true); err != nil {
				return err
			}
			if _, err := sess.Where("uid=? AND ledger_id=? AND a_i_review_item_id=?", ledger.OwnerUid, ledger.LedgerId, request.Id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.AIReviewItem)); err != nil {
				return err
			}
			var err error
			original, err = readReview(sess, ledger.OwnerUid, ledger.LedgerId, request.Id)
			if err != nil {
				return err
			}
			if err := authorizeReviewWrite(sess, actor, ledger, original); err != nil {
				return err
			}
			if original.Status == models.AI_REVIEW_STATUS_RESOLVED {
				if original.TransactionId <= 0 {
					return errs.ErrAIReviewConfirmationRequired
				}
				if original.ConfirmationHash != hash {
					return errs.ErrAIReviewItemConflict
				}
				return nil
			}
			if original.Deleted {
				return errs.ErrAIReviewItemNotFound
			}
			if original.Status != models.AI_REVIEW_STATUS_PENDING {
				return errs.ErrAIReviewItemConflict
			}
			return nil
		})
	}
	if err := read(); err != nil {
		return 0, err
	}
	if original.Status == models.AI_REVIEW_STATUS_RESOLVED {
		return original.TransactionId, nil
	}
	if actor != ledger.OwnerUid && len(tags) > 0 {
		return 0, errs.ErrTransactionTagNotFound
	}
	if actor != ledger.OwnerUid && len(pictures) > 0 {
		return 0, errs.ErrTransactionPictureNotFound
	}
	// User settings use the main store. Read them before acquiring the owner
	// shard's write transaction: that store can share the same connection pool,
	// so an extra connection while all contenders wait on BEGIN can deadlock.
	user, err := Users.GetUserById(c, actor)
	if err != nil {
		return 0, err
	}
	tx := request.Transaction
	dbType, _ := tx.Type.ToTransactionDbType()
	row := &models.Transaction{Uid: ledger.OwnerUid, LedgerId: ledger.LedgerId, RecorderUid: actor, PayerUid: actor, Type: dbType,
		CategoryId: tx.CategoryId, AccountId: tx.SourceAccountId, RelatedAccountId: tx.DestinationAccountId,
		Amount: tx.SourceAmount, RelatedAccountAmount: tx.DestinationAmount, HideAmount: tx.HideAmount,
		TransactionTime: utils.GetMinTransactionTimeFromUnixTime(tx.Time), TimezoneUtcOffset: tx.UtcOffset, Comment: tx.Comment, CreatedIp: clientIP}
	if tx.Type == models.TRANSACTION_TYPE_TRANSFER && tx.SourceAccountId == 0 {
		row.Type = models.TRANSACTION_DB_TYPE_TRANSFER_IN
		row.AccountId, row.RelatedAccountId, row.Amount = tx.DestinationAccountId, 0, tx.DestinationAmount
		row.RelatedAccountAmount = row.Amount
	}
	if tx.GeoLocation != nil {
		row.GeoLongitude, row.GeoLatitude = tx.GeoLocation.Longitude, tx.GeoLocation.Latitude
	}
	claimed := errors.New("AI review already handled")
	err = Transactions.createTransactionWithHooks(c, row, tags, pictures, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanWrite, true); err != nil {
			return err
		}
		if _, err := sess.Where("uid=? AND ledger_id=? AND a_i_review_item_id=?", ledger.OwnerUid, ledger.LedgerId, request.Id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.AIReviewItem)); err != nil {
			return err
		}
		live, err := readReview(sess, ledger.OwnerUid, ledger.LedgerId, request.Id)
		if err != nil {
			return err
		}
		if err := authorizeReviewWrite(sess, actor, ledger, live); err != nil {
			return err
		}
		if live.Status == models.AI_REVIEW_STATUS_RESOLVED {
			return claimed
		}
		if live.Deleted {
			return errs.ErrAIReviewItemNotFound
		}
		if live.Status != models.AI_REVIEW_STATUS_PENDING {
			return errs.ErrAIReviewItemConflict
		}
		if err := validateAIReviewResources(sess, row, tags, pictures); err != nil {
			return err
		}
		source, destination, err := Transactions.getAccountModels(sess, row)
		if err != nil {
			return err
		}
		if timezone == nil {
			timezone = time.UTC
		}
		if !user.CanEditTransactionByTransactionTime(row.TransactionTime, timezone, source, destination) {
			return errs.ErrCannotCreateTransactionWithThisTransactionTime
		}
		count, err := sess.Where("uid=? AND ledger_id=? AND a_i_review_item_id=? AND status=? AND deleted=?", ledger.OwnerUid, ledger.LedgerId, request.Id, models.AI_REVIEW_STATUS_PENDING, false).
			Cols("status", "confirmation_hash", "confirmed_uid", "updated_unix_time").Update(&models.AIReviewItem{Status: models.AI_REVIEW_STATUS_RESOLVED, ConfirmationHash: hash, ConfirmedUid: actor, UpdatedUnixTime: time.Now().Unix()})
		if err != nil {
			return err
		}
		if count != 1 {
			return claimed
		}
		return nil
	}, func(sess *xorm.Session) error {
		count, err := sess.Where("uid=? AND ledger_id=? AND a_i_review_item_id=? AND status=? AND confirmation_hash=?", ledger.OwnerUid, ledger.LedgerId, request.Id, models.AI_REVIEW_STATUS_RESOLVED, hash).
			Cols("transaction_id").Update(&models.AIReviewItem{TransactionId: row.TransactionId})
		if err != nil {
			return err
		}
		if count != 1 {
			return errs.ErrAIReviewItemConflict
		}
		return nil
	})
	if errors.Is(err, claimed) {
		if err := read(); err != nil {
			return 0, err
		}
		return original.TransactionId, nil
	}
	if err != nil {
		return 0, err
	}
	return row.TransactionId, nil
}

func validateAIReviewResources(sess *xorm.Session, row *models.Transaction, tags, pictures []int64) error {
	// Lock the complete account/parent set in numeric order, including external
	// transfers which have only one local account. Reread after locking so a
	// concurrent parent change cannot bypass the selected ledger boundary.
	lockSet := make(map[int64]bool)
	for _, id := range []int64{row.AccountId, row.RelatedAccountId} {
		if id == 0 {
			continue
		}
		a := &models.Account{}
		found, err := sess.Where("uid=? AND account_id=? AND ledger_id=? AND deleted=?", row.Uid, id, row.LedgerId, false).Get(a)
		if err != nil {
			return err
		}
		if !found {
			return errs.ErrAccountIdInvalid
		}
		lockSet[id] = true
		if a.ParentAccountId > 0 {
			lockSet[a.ParentAccountId] = true
		}
	}
	lockIDs := make([]int64, 0, len(lockSet))
	for id := range lockSet {
		lockIDs = append(lockIDs, id)
	}
	sort.Slice(lockIDs, func(i, j int) bool { return lockIDs[i] < lockIDs[j] })
	for _, id := range lockIDs {
		if _, err := sess.Where("uid=? AND account_id=?", row.Uid, id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.Account)); err != nil {
			return err
		}
	}
	if err := authorizeTransactionAccounts(sess, row); err != nil {
		return err
	}
	for _, id := range []int64{row.AccountId, row.RelatedAccountId} {
		if id == 0 {
			continue
		}
		account := &models.Account{}
		if _, err := sess.Where("uid=? AND account_id=?", row.Uid, id).Get(account); err != nil {
			return err
		}
		if account.ParentAccountId > 0 {
			if !lockSet[account.ParentAccountId] {
				return errs.ErrAccountIdInvalid
			}
			parent := &models.Account{}
			found, err := sess.Where("uid=? AND ledger_id=? AND account_id=? AND deleted=?", row.Uid, row.LedgerId, account.ParentAccountId, false).Get(parent)
			if err != nil {
				return err
			}
			if !found || parent.Type != models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS || parent.Hidden {
				return errs.ErrAccountIdInvalid
			}
		}
	}
	category := &models.TransactionCategory{}
	found, err := sess.Where("uid=? AND category_id=? AND deleted=?", row.Uid, row.CategoryId, false).Get(category)
	if err != nil {
		return err
	}
	if !found {
		return errs.ErrTransactionCategoryNotFound
	}
	parentID := category.ParentCategoryId
	categoryIDs := []int64{row.CategoryId, parentID}
	sort.Slice(categoryIDs, func(i, j int) bool { return categoryIDs[i] < categoryIDs[j] })
	for _, id := range categoryIDs {
		if _, err := sess.Where("uid=? AND category_id=?", row.Uid, id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.TransactionCategory)); err != nil {
			return err
		}
	}
	if err := Transactions.isCategoryValid(sess, row); err != nil {
		return err
	}
	if _, err := sess.Where("uid=? AND category_id=?", row.Uid, row.CategoryId).Get(category); err != nil {
		return err
	}
	if category.ParentCategoryId != parentID {
		return errs.ErrTransactionCategoryTypeInvalid
	}
	parent := &models.TransactionCategory{}
	if _, err := sess.Where("uid=? AND category_id=?", row.Uid, category.ParentCategoryId).Get(parent); err != nil {
		return err
	}
	if parent.Type != category.Type || parent.ParentCategoryId != models.LevelOneTransactionCategoryParentId {
		return errs.ErrTransactionCategoryTypeInvalid
	}
	children, err := sess.Where("uid=? AND parent_category_id=? AND deleted=?", row.Uid, row.CategoryId, false).Exist(new(models.TransactionCategory))
	if err != nil {
		return err
	}
	if children {
		return errs.ErrCannotUsePrimaryCategoryForTransaction
	}
	sort.Slice(tags, func(i, j int) bool { return tags[i] < tags[j] })
	for _, id := range tags {
		if _, err := sess.Where("uid=? AND tag_id=?", row.Uid, id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.TransactionTag)); err != nil {
			return err
		}
		tag := &models.TransactionTag{}
		found, err := sess.Where("uid=? AND tag_id=? AND deleted=?", row.Uid, id, false).Get(tag)
		if err != nil {
			return err
		}
		if !found {
			return errs.ErrTransactionTagNotFound
		}
		if tag.Hidden {
			return errs.ErrCannotUseHiddenTransactionTag
		}
	}
	// Serialize attachment claims before the posting path validates ownership
	// and the unused state; two review items cannot bind the same attachment.
	sort.Slice(pictures, func(i, j int) bool { return pictures[i] < pictures[j] })
	for _, id := range pictures {
		if _, err := sess.Where("uid=? AND picture_id=?", row.Uid, id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.TransactionPictureInfo)); err != nil {
			return err
		}
	}
	return nil
}
