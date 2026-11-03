package services

import (
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
	"xorm.io/xorm"
)

// TransactionTemplateService stores both ordinary and scheduled templates in
// the selected ledger owner's shard. Existing callers target personal ledger 0.
type TransactionTemplateService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

var TransactionTemplates = &TransactionTemplateService{
	ServiceUsingDB:   ServiceUsingDB{container: datastore.Container},
	ServiceUsingUuid: ServiceUsingUuid{container: uuid.Container},
}

func resolveTemplateLedger(c core.Context, actor, ledgerID int64, capability func(models.FamilyMemberRole) bool) (*datastore.Database, *models.Ledger, error) {
	if ledgerID < 0 {
		return nil, nil, errs.ErrLedgerNotFound
	}
	db, ledger, err := Ledgers.GetLedgerWithAccess(c, actor, ledgerID, capability)
	if err != nil {
		return nil, nil, err
	}
	if db == nil {
		db = TransactionTemplates.UserDataDB(ledger.OwnerUid)
	}
	return db, ledger, nil
}

func (s *TransactionTemplateService) GetTotalNormalTemplateCountByUid(c core.Context, uid int64) (int64, error) {
	return s.countTemplatesInLedger(c, uid, 0, models.TRANSACTION_TEMPLATE_TYPE_NORMAL)
}
func (s *TransactionTemplateService) GetTotalScheduledTemplateCountByUid(c core.Context, uid int64) (int64, error) {
	return s.countTemplatesInLedger(c, uid, 0, models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE)
}
func (s *TransactionTemplateService) countTemplatesInLedger(c core.Context, actor, ledgerID int64, kind models.TransactionTemplateType) (int64, error) {
	db, ledger, err := resolveTemplateLedger(c, actor, ledgerID, anyFamilyRole)
	if err != nil {
		return 0, err
	}
	var count int64
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, anyFamilyRole, false); err != nil {
			return err
		}
		var e error
		count, e = sess.Where("uid=? AND ledger_id=? AND deleted=? AND template_type=?", ledger.OwnerUid, ledgerID, false, kind).Count(new(models.TransactionTemplate))
		return e
	})
	return count, err
}
func (s *TransactionTemplateService) GetAllTemplatesByUid(c core.Context, uid int64, kind models.TransactionTemplateType) ([]*models.TransactionTemplate, error) {
	return s.GetAllTemplatesInLedger(c, uid, 0, kind)
}
func (s *TransactionTemplateService) GetAllTemplatesInLedger(c core.Context, actor, ledgerID int64, kind models.TransactionTemplateType) ([]*models.TransactionTemplate, error) {
	if kind < models.TRANSACTION_TEMPLATE_TYPE_NORMAL || kind > models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE {
		return nil, errs.ErrTransactionTemplateTypeInvalid
	}
	db, ledger, err := resolveTemplateLedger(c, actor, ledgerID, anyFamilyRole)
	if err != nil {
		return nil, err
	}
	rows := make([]*models.TransactionTemplate, 0)
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, anyFamilyRole, false); err != nil {
			return err
		}
		return sess.Where("uid=? AND ledger_id=? AND deleted=? AND template_type=?", ledger.OwnerUid, ledgerID, false, kind).OrderBy("display_order, template_id").Find(&rows)
	})
	return rows, err
}
func (s *TransactionTemplateService) GetTemplateByTemplateId(c core.Context, uid, templateID int64) (*models.TransactionTemplate, error) {
	return s.GetTemplateInLedger(c, uid, 0, templateID)
}
func (s *TransactionTemplateService) GetTemplateInLedger(c core.Context, actor, ledgerID, templateID int64) (*models.TransactionTemplate, error) {
	if templateID <= 0 {
		return nil, errs.ErrTransactionTemplateIdInvalid
	}
	db, ledger, err := resolveTemplateLedger(c, actor, ledgerID, anyFamilyRole)
	if err != nil {
		return nil, err
	}
	row := &models.TransactionTemplate{}
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, anyFamilyRole, false); err != nil {
			return err
		}
		has, e := sess.Where("template_id=? AND uid=? AND ledger_id=? AND deleted=?", templateID, ledger.OwnerUid, ledgerID, false).Get(row)
		if e != nil {
			return e
		}
		if !has {
			return errs.ErrTransactionTemplateNotFound
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return row, nil
}
func (s *TransactionTemplateService) GetMaxDisplayOrder(c core.Context, uid int64, kind models.TransactionTemplateType) (int32, error) {
	return s.GetMaxDisplayOrderInLedger(c, uid, 0, kind)
}
func (s *TransactionTemplateService) GetMaxDisplayOrderInLedger(c core.Context, actor, ledgerID int64, kind models.TransactionTemplateType) (int32, error) {
	rows, err := s.GetAllTemplatesInLedger(c, actor, ledgerID, kind)
	if err != nil {
		return 0, err
	}
	var max int32
	for _, row := range rows {
		if row.DisplayOrder > max {
			max = row.DisplayOrder
		}
	}
	return max, nil
}
func (s *TransactionTemplateService) CreateTemplate(c core.Context, template *models.TransactionTemplate) error {
	if template == nil {
		return errs.ErrTransactionTemplateNotFound
	}
	return s.CreateTemplateInLedger(c, template.Uid, template)
}
func (s *TransactionTemplateService) CreateTemplateInLedger(c core.Context, actor int64, template *models.TransactionTemplate) error {
	return s.CreateTemplateIdempotent(c, actor, template, "")
}
func (s *TransactionTemplateService) ModifyTemplate(c core.Context, template *models.TransactionTemplate) error {
	if template == nil {
		return errs.ErrTransactionTemplateNotFound
	}
	return s.ModifyTemplateInLedger(c, template.Uid, template)
}
func (s *TransactionTemplateService) ModifyTemplateInLedger(c core.Context, actor int64, template *models.TransactionTemplate) error {
	if template == nil || template.TemplateId <= 0 {
		return errs.ErrTransactionTemplateIdInvalid
	}
	db, ledger, err := resolveTemplateLedger(c, actor, template.LedgerId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return err
	}
	return db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanManage, true); err != nil {
			return err
		}
		if _, err := sess.Where("template_id=? AND uid=? AND ledger_id=?", template.TemplateId, ledger.OwnerUid, ledger.LedgerId).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.TransactionTemplate)); err != nil {
			return err
		}
		old := &models.TransactionTemplate{}
		has, err := sess.Where("template_id=? AND uid=? AND ledger_id=? AND deleted=?", template.TemplateId, ledger.OwnerUid, ledger.LedgerId, false).Get(old)
		if err != nil {
			return err
		}
		if !has {
			return errs.ErrTransactionTemplateNotFound
		}
		template.Uid, template.TemplateType = ledger.OwnerUid, old.TemplateType
		template.DisplayOrder, template.Hidden = old.DisplayOrder, old.Hidden
		template.UpdatedUnixTime = time.Now().Unix()
		if err := s.isTemplateValid(sess, template); err != nil {
			return err
		}
		rows, err := sess.Where("template_id=? AND uid=? AND ledger_id=? AND deleted=?", template.TemplateId, ledger.OwnerUid, ledger.LedgerId, false).
			Cols("name", "type", "category_id", "account_id", "scheduled_frequency_type", "scheduled_frequency", "scheduled_start_time", "scheduled_end_time", "scheduled_at", "scheduled_timezone_utc_offset", "tag_ids", "amount", "related_account_id", "related_account_amount", "hide_amount", "comment", "updated_unix_time").Update(template)
		if err != nil {
			return err
		}
		if rows != 1 {
			return errs.ErrTransactionTemplateNotFound
		}
		return nil
	})
}
func (s *TransactionTemplateService) HideTemplate(c core.Context, uid int64, ids []int64, hidden bool) error {
	return s.HideTemplateInLedger(c, uid, 0, ids, hidden)
}
func (s *TransactionTemplateService) HideTemplateInLedger(c core.Context, actor, ledgerID int64, ids []int64, hidden bool) error {
	return s.updateTemplateRows(c, actor, ledgerID, ids, func(sess *xorm.Session, row *models.TransactionTemplate) error {
		_, err := sess.Where("template_id=? AND uid=? AND ledger_id=? AND deleted=?", row.TemplateId, row.Uid, row.LedgerId, false).
			Cols("hidden", "updated_unix_time").Update(&models.TransactionTemplate{Hidden: hidden, UpdatedUnixTime: time.Now().Unix()})
		return err
	})
}
func (s *TransactionTemplateService) ModifyTemplateDisplayOrders(c core.Context, uid int64, rows []*models.TransactionTemplate) error {
	return s.ModifyTemplateDisplayOrdersInLedger(c, uid, 0, rows)
}
func (s *TransactionTemplateService) ModifyTemplateDisplayOrdersInLedger(c core.Context, actor, ledgerID int64, rows []*models.TransactionTemplate) error {
	ids := make([]int64, len(rows))
	orders := make(map[int64]int32, len(rows))
	for i, row := range rows {
		if row == nil {
			return errs.ErrTransactionTemplateIdInvalid
		}
		ids[i], orders[row.TemplateId] = row.TemplateId, row.DisplayOrder
	}
	var templateType models.TransactionTemplateType
	return s.updateTemplateRows(c, actor, ledgerID, ids, func(sess *xorm.Session, row *models.TransactionTemplate) error {
		if templateType != 0 && templateType != row.TemplateType {
			return errs.ErrTransactionTemplateTypeInvalid
		}
		templateType = row.TemplateType
		_, err := sess.Where("template_id=? AND uid=? AND ledger_id=? AND deleted=?", row.TemplateId, row.Uid, row.LedgerId, false).
			Cols("display_order", "updated_unix_time").Update(&models.TransactionTemplate{DisplayOrder: orders[row.TemplateId], UpdatedUnixTime: time.Now().Unix()})
		return err
	})
}
func (s *TransactionTemplateService) DeleteTemplate(c core.Context, uid, templateID int64) error {
	return s.DeleteTemplateInLedger(c, uid, 0, templateID)
}
func (s *TransactionTemplateService) DeleteTemplateInLedger(c core.Context, actor, ledgerID, templateID int64) error {
	return s.updateTemplateRows(c, actor, ledgerID, []int64{templateID}, func(sess *xorm.Session, row *models.TransactionTemplate) error {
		now := time.Now().Unix()
		_, err := sess.Where("template_id=? AND uid=? AND ledger_id=? AND deleted=?", row.TemplateId, row.Uid, row.LedgerId, false).
			Cols("deleted", "deleted_unix_time", "updated_unix_time").Update(&models.TransactionTemplate{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now})
		return err
	})
}

// updateTemplateRows validates the complete batch before any change; a wrong
// ledger, missing object or revoked permission rolls the batch back.
func (s *TransactionTemplateService) updateTemplateRows(c core.Context, actor, ledgerID int64, ids []int64, update func(*xorm.Session, *models.TransactionTemplate) error) error {
	if len(ids) == 0 {
		return errs.ErrTransactionTemplateIdInvalid
	}
	seen := make(map[int64]bool, len(ids))
	for _, id := range ids {
		if id <= 0 || seen[id] {
			return errs.ErrTransactionTemplateIdInvalid
		}
		seen[id] = true
	}
	db, ledger, err := resolveTemplateLedger(c, actor, ledgerID, models.FamilyMemberRole.CanManage)
	if err != nil {
		return err
	}
	return db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanManage, true); err != nil {
			return err
		}
		rows := make([]*models.TransactionTemplate, 0, len(ids))
		if err := sess.Where("uid=? AND ledger_id=? AND deleted=?", ledger.OwnerUid, ledgerID, false).In("template_id", ids).Find(&rows); err != nil {
			return err
		}
		if len(rows) != len(ids) {
			return errs.ErrTransactionTemplateNotFound
		}
		sort.Slice(rows, func(i, j int) bool { return rows[i].TemplateId < rows[j].TemplateId })
		for _, row := range rows {
			if err := update(sess, row); err != nil {
				return err
			}
		}
		return nil
	})
}

// DeleteAllTemplates remains an owned account-erasure operation, including the
// user's own explicit ledgers but never another owner's shared templates.
func (s *TransactionTemplateService) DeleteAllTemplates(c core.Context, uid int64) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}
	now := time.Now().Unix()
	return s.UserDataDB(uid).DoTransaction(c, func(sess *xorm.Session) error {
		_, err := sess.Where("uid=? AND deleted=?", uid, false).Cols("deleted", "deleted_unix_time", "updated_unix_time").Update(&models.TransactionTemplate{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now})
		return err
	})
}

func templateSnapshot(template *models.TransactionTemplate) models.ScheduledOccurrenceSnapshot {
	tags := make([]string, 0)
	if template.TagIds != "" {
		tags = strings.Split(template.TagIds, ",")
	}
	return models.ScheduledOccurrenceSnapshot{LedgerId: template.LedgerId, Name: template.Name, Type: template.Type, CategoryId: template.CategoryId,
		SourceAccountId: template.AccountId, DestinationAccountId: template.RelatedAccountId, SourceAmount: template.Amount,
		DestinationAmount: template.RelatedAccountAmount, UtcOffset: template.ScheduledTimezoneUtcOffset, HideAmount: template.HideAmount, TagIds: tags, Comment: template.Comment}
}
func (s *TransactionTemplateService) isTemplateValid(sess *xorm.Session, template *models.TransactionTemplate) error {
	if template.TemplateType < models.TRANSACTION_TEMPLATE_TYPE_NORMAL || template.TemplateType > models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE {
		return errs.ErrTransactionTemplateTypeInvalid
	}
	if template.TemplateType == models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE {
		if err := models.ValidateTransactionScheduleFrequency(template.ScheduledFrequencyType, template.ScheduledFrequency); err != nil {
			return errs.ErrScheduledTransactionFrequencyInvalid
		}
		if template.ScheduledFrequencyType == models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_EVERY_N_DAYS && template.ScheduledStartTime == nil {
			return errs.ErrScheduledTransactionStartDateRequired
		}
		if template.ScheduledStartTime != nil && template.ScheduledEndTime != nil && *template.ScheduledStartTime > *template.ScheduledEndTime {
			return errs.ErrScheduledTransactionTemplateStartDataLaterThanEndDate
		}
	}
	snapshot := templateSnapshot(template)
	if err := snapshot.Validate(); err != nil {
		return errs.ErrParameterInvalid
	}
	return validateScheduledSnapshotResources(sess, template.Uid, snapshot)
}

// Resources are rechecked under row write locks before storing a template or
// posting its captured occurrence. Categories/tags belong to the ledger owner;
// accounts (including parent groups) must additionally belong to that ledger.
func validateScheduledSnapshotResources(sess *xorm.Session, owner int64, snapshot models.ScheduledOccurrenceSnapshot) error {
	accounts := make(map[int64]*models.Account)
	ids := []int64{snapshot.SourceAccountId}
	if snapshot.DestinationAccountId > 0 {
		ids = append(ids, snapshot.DestinationAccountId)
	}
	// Discover the complete lock set, then lock parents and children together in
	// numeric order. Parent changes during discovery are rejected on reread.
	lockSet := make(map[int64]bool)
	for _, id := range ids {
		lockSet[id] = true
		preflight := &models.Account{}
		found, err := sess.Where("uid=? AND account_id=? AND ledger_id=? AND deleted=?", owner, id, snapshot.LedgerId, false).Get(preflight)
		if err != nil {
			return err
		}
		if !found {
			return errs.ErrAccountIdInvalid
		}
		if preflight.ParentAccountId > 0 {
			lockSet[preflight.ParentAccountId] = true
		}
	}
	lockIDs := make([]int64, 0, len(lockSet))
	for id := range lockSet {
		lockIDs = append(lockIDs, id)
	}
	sort.Slice(lockIDs, func(i, j int) bool { return lockIDs[i] < lockIDs[j] })
	for _, id := range lockIDs {
		if _, err := sess.Where("uid=? AND account_id=?", owner, id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.Account)); err != nil {
			return err
		}
	}
	for _, id := range ids {
		a := &models.Account{}
		found, err := sess.Where("uid=? AND account_id=? AND ledger_id=? AND deleted=?", owner, id, snapshot.LedgerId, false).Get(a)
		if err != nil {
			return err
		}
		if !found {
			return errs.ErrAccountIdInvalid
		}
		if a.Hidden {
			return errs.ErrCannotUseHiddenAccount
		}
		if a.Type == models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
			return errs.ErrCannotAddTransactionToParentAccount
		}
		if a.ParentAccountId > 0 {
			if !lockSet[a.ParentAccountId] {
				return errs.ErrAccountIdInvalid
			}
			parent := &models.Account{}
			found, err = sess.Where("uid=? AND account_id=? AND ledger_id=? AND deleted=?", owner, a.ParentAccountId, snapshot.LedgerId, false).Get(parent)
			if err != nil {
				return err
			}
			if !found || parent.Hidden || parent.Type != models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
				return errs.ErrAccountIdInvalid
			}
		}
		accounts[id] = a
	}
	if snapshot.Type == models.TRANSACTION_TYPE_TRANSFER && accounts[snapshot.SourceAccountId].Currency == accounts[snapshot.DestinationAccountId].Currency && snapshot.SourceAmount != snapshot.DestinationAmount {
		return errs.ErrTransactionSourceAndDestinationAmountNotEqual
	}
	category := &models.TransactionCategory{}
	found, err := sess.Where("uid=? AND category_id=? AND deleted=?", owner, snapshot.CategoryId, false).Get(category)
	if err != nil {
		return err
	}
	if !found {
		return errs.ErrTransactionCategoryNotFound
	}
	categoryIDs := []int64{category.CategoryId, category.ParentCategoryId}
	sort.Slice(categoryIDs, func(i, j int) bool { return categoryIDs[i] < categoryIDs[j] })
	for _, id := range categoryIDs {
		if _, err := sess.Where("uid=? AND category_id=?", owner, id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.TransactionCategory)); err != nil {
			return err
		}
	}
	originalParent := category.ParentCategoryId
	found, err = sess.Where("uid=? AND category_id=? AND deleted=?", owner, snapshot.CategoryId, false).Get(category)
	if err != nil {
		return err
	}
	if !found {
		return errs.ErrTransactionCategoryNotFound
	}
	if category.ParentCategoryId != originalParent {
		return errs.ErrTransactionCategoryTypeInvalid
	}
	if category.Hidden {
		return errs.ErrCannotUseHiddenTransactionCategory
	}
	if category.ParentCategoryId <= 0 {
		return errs.ErrCannotUsePrimaryCategoryForTransaction
	}
	expectedType := models.CATEGORY_TYPE_EXPENSE
	if snapshot.Type == models.TRANSACTION_TYPE_INCOME {
		expectedType = models.CATEGORY_TYPE_INCOME
	}
	if snapshot.Type == models.TRANSACTION_TYPE_TRANSFER {
		expectedType = models.CATEGORY_TYPE_TRANSFER
	}
	if category.Type != expectedType {
		return errs.ErrTransactionCategoryTypeInvalid
	}
	parent := &models.TransactionCategory{}
	found, err = sess.Where("uid=? AND category_id=? AND deleted=?", owner, category.ParentCategoryId, false).Get(parent)
	if err != nil {
		return err
	}
	if !found {
		return errs.ErrTransactionCategoryNotFound
	}
	if parent.Hidden {
		return errs.ErrCannotUseHiddenTransactionCategory
	}
	if parent.Type != expectedType || parent.ParentCategoryId != models.LevelOneTransactionCategoryParentId {
		return errs.ErrTransactionCategoryTypeInvalid
	}
	hasChildren, err := sess.Where("uid=? AND parent_category_id=? AND deleted=?", owner, snapshot.CategoryId, false).Exist(new(models.TransactionCategory))
	if err != nil {
		return err
	}
	if hasChildren {
		return errs.ErrCannotUsePrimaryCategoryForTransaction
	}
	tags := append([]string{}, snapshot.TagIds...)
	sort.Strings(tags)
	for _, raw := range tags {
		id, _ := strconv.ParseInt(raw, 10, 64)
		if _, err := sess.Where("uid=? AND tag_id=?", owner, id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.TransactionTag)); err != nil {
			return err
		}
		tag := &models.TransactionTag{}
		found, err := sess.Where("uid=? AND tag_id=? AND deleted=?", owner, id, false).Get(tag)
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
	return nil
}
