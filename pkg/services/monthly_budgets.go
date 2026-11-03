package services

import (
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"xorm.io/xorm"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/validators"
)

// MonthlyBudgetService manages one non-carrying, fixed-currency budget per
// ledger and calendar month. Shared budgets live in the ledger owner's shard.
type MonthlyBudgetService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

var MonthlyBudgets = &MonthlyBudgetService{
	ServiceUsingDB:   ServiceUsingDB{container: datastore.Container},
	ServiceUsingUuid: ServiceUsingUuid{container: uuid.Container},
}

var monthlyBudgetSaveLocks [256]sync.Mutex

// GetMonthlyBudget preserves the implicit personal-ledger service contract.
func (s *MonthlyBudgetService) GetMonthlyBudget(c core.Context, uid int64, yearMonth int32) (*models.MonthlyBudget, error) {
	return s.GetMonthlyBudgetInLedger(c, uid, models.DefaultLedgerId, yearMonth)
}

// GetMonthlyBudgetInLedger allows every active member to read the same budget.
func (s *MonthlyBudgetService) GetMonthlyBudgetInLedger(c core.Context, actor, ledgerID int64, yearMonth int32) (*models.MonthlyBudget, error) {
	if !isValidYearMonth(yearMonth) {
		return nil, errs.ErrMonthlyBudgetYearMonthInvalid
	}
	db, ledger, err := s.resolveBudgetLedger(c, actor, ledgerID, func(role models.FamilyMemberRole) bool { return role.IsValid() })
	if err != nil {
		return nil, err
	}
	var budget *models.MonthlyBudget
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, func(role models.FamilyMemberRole) bool { return role.IsValid() }, false); err != nil {
			return err
		}
		row := &models.MonthlyBudget{}
		has, err := sess.Where("uid=? AND ledger_id=? AND deleted=? AND "+sess.Engine().Quote("year_month")+"=?", ledger.OwnerUid, ledgerID, false, yearMonth).Get(row)
		if err != nil || !has {
			return err
		}
		if err := validateStoredMonthlyBudget(row); err != nil {
			return err
		}
		budget = row
		return nil
	})
	return budget, err
}

// SetMonthlyBudget preserves callers that omit a ledger and currency.
func (s *MonthlyBudgetService) SetMonthlyBudget(c core.Context, uid int64, yearMonth int32, amount int64, categoryAmounts map[string]int64) (*models.MonthlyBudget, error) {
	return s.SetMonthlyBudgetInLedger(c, uid, models.DefaultLedgerId, yearMonth, amount, categoryAmounts, "")
}

// SetMonthlyBudgetInLedger replaces one month's budget. An omitted currency
// retains the existing currency; only a new budget uses its owner's default.
func (s *MonthlyBudgetService) SetMonthlyBudgetInLedger(c core.Context, actor, ledgerID int64, yearMonth int32, amount int64, categoryAmounts map[string]int64, currency string, revisions ...*int64) (*models.MonthlyBudget, error) {
	if !isValidYearMonth(yearMonth) {
		return nil, errs.ErrMonthlyBudgetYearMonthInvalid
	}
	if amount <= 0 || amount > models.MaximumMonthlyBudgetAmount {
		return nil, errs.ErrMonthlyBudgetAmountInvalid
	}
	if currency != "" && !validMonthlyBudgetCurrency(currency) {
		return nil, errs.ErrMonthlyBudgetCurrencyInvalid
	}
	if categoryAmounts == nil {
		categoryAmounts = map[string]int64{}
	}
	for categoryID, categoryAmount := range categoryAmounts {
		parsedID, err := strconv.ParseInt(categoryID, 10, 64)
		if err != nil || parsedID <= 0 || strconv.FormatInt(parsedID, 10) != categoryID {
			return nil, errs.ErrMonthlyBudgetCategoryInvalid
		}
		if categoryAmount <= 0 || categoryAmount > models.MaximumMonthlyBudgetAmount {
			return nil, errs.ErrMonthlyBudgetAmountInvalid
		}
	}
	categoryJSON, err := json.Marshal(categoryAmounts)
	if err != nil {
		return nil, err
	}
	db, ledger, err := s.resolveBudgetLedger(c, actor, ledgerID, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}
	lock := monthlyBudgetSaveLock(ledger.OwnerUid, ledgerID, yearMonth)
	lock.Lock()
	defer lock.Unlock()
	// UserStore may be a separate shard; resolve outside the financial
	// transaction. Its error matters only when a new budget needs a default.
	var ownerCurrency string
	var ownerCurrencyErr error
	if currency == "" {
		ownerCurrency, ownerCurrencyErr = s.ownerBudgetCurrency(c, ledger.OwnerUid)
	}
	var budget *models.MonthlyBudget
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanManage, true); err != nil {
			return err
		}
		expectedRevision, err := expectedMonthlyBudgetRevision(ledgerID, revisions)
		if err != nil {
			return err
		}
		row := &models.MonthlyBudget{}
		has, err := sess.Where("uid=? AND ledger_id=? AND deleted=? AND "+sess.Engine().Quote("year_month")+"=?", ledger.OwnerUid, ledgerID, false, yearMonth).Get(row)
		if err != nil {
			return err
		}
		if expectedRevision != nil && ((!has && *expectedRevision != 0) || (has && *expectedRevision != row.Revision)) {
			return errs.ErrMonthlyBudgetConflict
		}
		if err := validateMonthlyBudgetCategories(sess, ledger.OwnerUid, categoryAmounts); err != nil {
			return err
		}
		resolvedCurrency := currency
		if resolvedCurrency == "" && has {
			resolvedCurrency = row.Currency
		}
		if resolvedCurrency == "" {
			if ownerCurrencyErr != nil {
				return ownerCurrencyErr
			}
			resolvedCurrency = ownerCurrency
		}
		if !validMonthlyBudgetCurrency(resolvedCurrency) {
			return errs.ErrMonthlyBudgetCurrencyInvalid
		}
		now := time.Now().Unix()
		scope := monthlyBudgetActiveScope(ledger.OwnerUid, ledgerID, yearMonth)
		active := has
		if !has {
			// Restore the latest historical configuration instead of resetting
			// its revision. Existing deleted rows are retained by migration.
			has, err = sess.Where("uid=? AND ledger_id=? AND "+sess.Engine().Quote("year_month")+"=?", ledger.OwnerUid, ledgerID, yearMonth).
				OrderBy("revision DESC, monthly_budget_id DESC").Get(row)
			if err != nil {
				return err
			}
		}
		if has {
			previousRevision := row.Revision
			if previousRevision < 1 || previousRevision >= models.MaximumMonthlyBudgetRevision {
				return errs.ErrMonthlyBudgetRevisionInvalid
			}
			row.Amount, row.Currency, row.CategoryAmountsJson = amount, resolvedCurrency, string(categoryJSON)
			row.ActiveBudgetScope, row.UpdatedUnixTime, row.Revision = &scope, now, previousRevision+1
			row.Deleted, row.DeletedUnixTime = false, 0
			rows, err := sess.ID(row.MonthlyBudgetId).Cols("amount", "currency", "category_amounts_json", "active_budget_scope", "updated_unix_time", "revision", "deleted", "deleted_unix_time").
				Where("uid=? AND ledger_id=? AND deleted=? AND revision=?", ledger.OwnerUid, ledgerID, !active, previousRevision).Update(row)
			if err != nil {
				return err
			}
			if rows != 1 {
				return errs.ErrMonthlyBudgetConflict
			}
		} else {
			row = &models.MonthlyBudget{MonthlyBudgetId: s.GenerateUuid(uuid.UUID_TYPE_MONTHLY_BUDGET),
				Uid: ledger.OwnerUid, LedgerId: ledgerID, YearMonth: yearMonth, Currency: resolvedCurrency, Amount: amount, Revision: 1,
				CategoryAmountsJson: string(categoryJSON), ActiveBudgetScope: &scope, CreatedUnixTime: now, UpdatedUnixTime: now}
			if row.MonthlyBudgetId < 1 {
				return errs.ErrSystemIsBusy
			}
			if _, err := sess.Insert(row); err != nil {
				if monthlyBudgetUniqueScopeConflict(err) {
					return errs.ErrMonthlyBudgetConflict
				}
				return err
			}
		}
		budget = row
		return nil
	})
	return budget, err
}

func (s *MonthlyBudgetService) DeleteMonthlyBudget(c core.Context, uid int64, yearMonth int32) error {
	return s.DeleteMonthlyBudgetInLedger(c, uid, models.DefaultLedgerId, yearMonth)
}

func (s *MonthlyBudgetService) DeleteMonthlyBudgetInLedger(c core.Context, actor, ledgerID int64, yearMonth int32, revisions ...*int64) error {
	if !isValidYearMonth(yearMonth) {
		return errs.ErrMonthlyBudgetYearMonthInvalid
	}
	db, ledger, err := s.resolveBudgetLedger(c, actor, ledgerID, models.FamilyMemberRole.CanManage)
	if err != nil {
		return err
	}
	lock := monthlyBudgetSaveLock(ledger.OwnerUid, ledgerID, yearMonth)
	lock.Lock()
	defer lock.Unlock()
	return db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, models.FamilyMemberRole.CanManage, true); err != nil {
			return err
		}
		expectedRevision, err := expectedMonthlyBudgetRevision(ledgerID, revisions)
		if err != nil {
			return err
		}
		row := &models.MonthlyBudget{}
		has, err := sess.Where("uid=? AND ledger_id=? AND deleted=? AND "+sess.Engine().Quote("year_month")+"=?", ledger.OwnerUid, ledgerID, false, yearMonth).Get(row)
		if err != nil {
			return err
		}
		if expectedRevision != nil && ((!has && *expectedRevision != 0) || (has && *expectedRevision != row.Revision)) {
			return errs.ErrMonthlyBudgetConflict
		}
		if !has {
			return errs.ErrMonthlyBudgetNotFound
		}
		if row.Revision < 1 || row.Revision >= models.MaximumMonthlyBudgetRevision {
			return errs.ErrMonthlyBudgetRevisionInvalid
		}
		now := time.Now().Unix()
		update := &models.MonthlyBudget{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now, Revision: row.Revision + 1}
		rows, err := sess.ID(row.MonthlyBudgetId).Cols("deleted", "deleted_unix_time", "updated_unix_time", "active_budget_scope", "revision").
			Where("uid=? AND ledger_id=? AND deleted=? AND revision=?", ledger.OwnerUid, ledgerID, false, row.Revision).Update(update)
		if err != nil {
			return err
		}
		if rows != 1 {
			return errs.ErrMonthlyBudgetConflict
		}
		return nil
	})
}

// DeleteAllMonthlyBudgets is the existing account data-erasure operation. It
// removes owned budgets, never budgets owned by ledgers the user only joins.
func (s *MonthlyBudgetService) DeleteAllMonthlyBudgets(c core.Context, uid int64) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}
	now := time.Now().Unix()
	update := &models.MonthlyBudget{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now}
	_, err := s.UserDataDB(uid).NewSession(c).Cols("deleted", "deleted_unix_time", "updated_unix_time", "active_budget_scope").SetExpr("revision", "revision+1").Where("uid=? AND deleted=?", uid, false).Update(update)
	return err
}

func (s *MonthlyBudgetService) resolveBudgetLedger(c core.Context, actor, ledgerID int64, capability func(models.FamilyMemberRole) bool) (*datastore.Database, *models.Ledger, error) {
	if ledgerID < 0 {
		return nil, nil, errs.ErrLedgerNotFound
	}
	db, ledger, err := Ledgers.GetLedgerWithAccess(c, actor, ledgerID, capability)
	if err != nil {
		return nil, nil, err
	}
	if db == nil {
		db = s.UserDataDB(ledger.OwnerUid)
	}
	return db, ledger, nil
}

// authorizeMonthlyBudgetLedger takes the same ledger write lock used by
// transaction submissions before rechecking live membership. Ledger deletion
// takes this lock too, so a budget cannot be created into a deleted ledger.
func authorizeMonthlyBudgetLedger(sess *xorm.Session, actor int64, ledger *models.Ledger, capability func(models.FamilyMemberRole) bool, write bool) error {
	if ledger.LedgerId == models.DefaultLedgerId {
		if ledger.OwnerUid != actor {
			return errs.ErrLedgerAccessDenied
		}
		return nil
	}
	if write {
		if _, err := sess.Where("ledger_id=? AND owner_uid=? AND deleted=?", ledger.LedgerId, ledger.OwnerUid, false).
			SetExpr("updated_unix_time", "updated_unix_time").Update(&models.Ledger{}); err != nil {
			return err
		}
		if _, err := sess.Where("ledger_id=? AND uid=?", ledger.LedgerId, actor).SetExpr("role", "role").Update(&models.LedgerMember{}); err != nil {
			return err
		}
	}
	live := &models.Ledger{}
	found, err := sess.Where("ledger_id=? AND owner_uid=? AND deleted=?", ledger.LedgerId, ledger.OwnerUid, false).Get(live)
	if err != nil {
		return err
	}
	if !found {
		return errs.ErrLedgerAccessDenied
	}
	member := &models.LedgerMember{}
	found, err = sess.Where("ledger_id=? AND uid=?", ledger.LedgerId, actor).Get(member)
	if err != nil {
		return err
	}
	if found {
		if member.Role.IsValid() && member.CanAct(capability) {
			return nil
		}
		return errs.ErrLedgerAccessDenied
	}
	if live.Type == models.LEDGER_TYPE_PERSONAL && live.OwnerUid == actor {
		return nil
	}
	return errs.ErrLedgerAccessDenied
}

// Categories are owner-level taxonomy, not ledger rows. Limits can reference
// only visible expense leaves with a live, visible expense root of that owner.
func validateMonthlyBudgetCategories(sess *xorm.Session, owner int64, amounts map[string]int64) error {
	ids := make([]string, 0, len(amounts))
	for categoryID := range amounts {
		ids = append(ids, categoryID)
	}
	sort.Strings(ids)
	for _, categoryID := range ids {
		id, _ := strconv.ParseInt(categoryID, 10, 64)
		if _, err := sess.Where("uid=? AND category_id=?", owner, id).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.TransactionCategory)); err != nil {
			return err
		}
		category := &models.TransactionCategory{}
		found, err := sess.Where("uid=? AND category_id=? AND deleted=?", owner, id, false).Get(category)
		if err != nil {
			return err
		}
		if !found || category.Hidden || category.Type != models.CATEGORY_TYPE_EXPENSE || category.ParentCategoryId <= 0 {
			return errs.ErrMonthlyBudgetCategoryInvalid
		}
		parent := &models.TransactionCategory{}
		if _, err := sess.Where("uid=? AND category_id=?", owner, category.ParentCategoryId).SetExpr("updated_unix_time", "updated_unix_time").Update(new(models.TransactionCategory)); err != nil {
			return err
		}
		found, err = sess.Where("uid=? AND category_id=? AND deleted=?", owner, category.ParentCategoryId, false).Get(parent)
		if err != nil {
			return err
		}
		if !found || parent.Hidden || parent.Type != models.CATEGORY_TYPE_EXPENSE || parent.ParentCategoryId != models.LevelOneTransactionCategoryParentId {
			return errs.ErrMonthlyBudgetCategoryInvalid
		}
		children, err := sess.Where("uid=? AND parent_category_id=? AND deleted=?", owner, id, false).Exist(new(models.TransactionCategory))
		if err != nil {
			return err
		}
		if children {
			return errs.ErrMonthlyBudgetCategoryInvalid
		}
	}
	return nil
}

func (s *MonthlyBudgetService) ownerBudgetCurrency(c core.Context, owner int64) (string, error) {
	user := &models.User{}
	found, err := s.UserDB().NewSession(c).ID(owner).Where("deleted=?", false).Get(user)
	if err != nil {
		return "", err
	}
	if !found {
		return "", errs.ErrUserNotFound
	}
	if !validMonthlyBudgetCurrency(user.DefaultCurrency) {
		return "", errs.ErrMonthlyBudgetCurrencyInvalid
	}
	return user.DefaultCurrency, nil
}

// MigrateLegacyMonthlyBudgets runs after Sync2 adds ledger_id (DEFAULT 0),
// currency and the nullable unique active scope. It freezes the compatibility
// currency once; historical currency cannot be inferred from old amounts.
func (s *MonthlyBudgetService) MigrateLegacyMonthlyBudgets(c core.Context) error {
	for i := 0; i < s.UserDataDBCount(); i++ {
		db := s.UserDataDBByIndex(i)
		rows := make([]*models.MonthlyBudget, 0)
		// Deleted historical rows may belong to erased users. They retain a
		// NULL active scope and are never exposed or reused as live budgets.
		if err := db.NewSession(c).Where("deleted=? AND (currency=? OR active_budget_scope IS NULL OR revision=?)", false, "", 0).Find(&rows); err != nil {
			return err
		}
		currencies := make(map[int64]string)
		for _, row := range rows {
			currency := row.Currency
			if currency == "" {
				currency = currencies[row.Uid]
				if currency == "" {
					var err error
					currency, err = s.ownerBudgetCurrency(c, row.Uid)
					if err != nil {
						return err
					}
					currencies[row.Uid] = currency
				}
			}
			if !validMonthlyBudgetCurrency(currency) {
				return errs.ErrMonthlyBudgetCurrencyInvalid
			}
			if err := db.DoTransaction(c, func(sess *xorm.Session) error {
				live := &models.MonthlyBudget{}
				found, err := sess.ID(row.MonthlyBudgetId).Get(live)
				if err != nil || !found {
					return err
				}
				if live.Currency == "" {
					live.Currency = currency
				}
				if live.Revision == 0 {
					live.Revision = 1
				}
				if !live.Deleted {
					scope := monthlyBudgetActiveScope(live.Uid, live.LedgerId, live.YearMonth)
					live.ActiveBudgetScope = &scope
				}
				_, err = sess.ID(live.MonthlyBudgetId).Cols("currency", "active_budget_scope", "revision").Update(live)
				return err
			}); err != nil {
				return err
			}
		}
	}
	return nil
}

func monthlyBudgetActiveScope(owner, ledgerID int64, yearMonth int32) string {
	return fmt.Sprintf("%d/%d/%d", owner, ledgerID, yearMonth)
}

func validMonthlyBudgetCurrency(currency string) bool {
	return validators.AllCurrencyNames[currency]
}

func validateStoredMonthlyBudget(budget *models.MonthlyBudget) error {
	if budget.Revision < 1 || budget.Revision > models.MaximumMonthlyBudgetRevision {
		return errs.ErrMonthlyBudgetRevisionInvalid
	}
	if budget.Amount <= 0 || budget.Amount > models.MaximumMonthlyBudgetAmount {
		return errs.ErrMonthlyBudgetAmountInvalid
	}
	if !validMonthlyBudgetCurrency(budget.Currency) {
		return errs.ErrMonthlyBudgetCurrencyInvalid
	}
	amounts := make(map[string]int64)
	if err := json.Unmarshal([]byte(budget.CategoryAmountsJson), &amounts); err != nil || amounts == nil {
		return errs.ErrMonthlyBudgetCategoryInvalid
	}
	for categoryID, amount := range amounts {
		id, err := strconv.ParseInt(categoryID, 10, 64)
		if err != nil || id <= 0 || strconv.FormatInt(id, 10) != categoryID {
			return errs.ErrMonthlyBudgetCategoryInvalid
		}
		if amount <= 0 || amount > models.MaximumMonthlyBudgetAmount {
			return errs.ErrMonthlyBudgetAmountInvalid
		}
	}
	return nil
}

func expectedMonthlyBudgetRevision(ledgerID int64, revisions []*int64) (*int64, error) {
	if len(revisions) > 1 {
		return nil, errs.ErrMonthlyBudgetRevisionInvalid
	}
	var revision *int64
	if len(revisions) == 1 {
		revision = revisions[0]
	}
	if revision == nil && ledgerID != models.DefaultLedgerId {
		return nil, errs.ErrMonthlyBudgetRevisionRequired
	}
	if revision != nil && (*revision < 0 || *revision > models.MaximumMonthlyBudgetRevision) {
		return nil, errs.ErrMonthlyBudgetRevisionInvalid
	}
	return revision, nil
}

func monthlyBudgetSaveLock(owner, ledgerID int64, yearMonth int32) *sync.Mutex {
	key := uint64(owner)*31 + uint64(ledgerID)*17 + uint64(yearMonth)
	return &monthlyBudgetSaveLocks[key%uint64(len(monthlyBudgetSaveLocks))]
}

func monthlyBudgetUniqueScopeConflict(err error) bool {
	message := strings.ToLower(err.Error())
	return (strings.Contains(message, "unique") || strings.Contains(message, "duplicate")) &&
		(strings.Contains(message, "active_budget_scope") || strings.Contains(message, "monthly_budget_active_scope"))
}

func isValidYearMonth(yearMonth int32) bool {
	month, year := yearMonth%100, yearMonth/100
	return year >= 2000 && year <= 9999 && month >= 1 && month <= 12
}
