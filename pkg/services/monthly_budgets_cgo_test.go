//go:build cgo

package services

import (
	"strconv"
	"sync"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
	"xorm.io/xorm"
)

func initMonthlyBudgetTestEnvironment(t *testing.T) {
	t.Helper()
	initFamilyTestEnvironment(t)
	_, err := MonthlyBudgets.UserDB().NewSession(nil).Insert(
		&models.User{Uid: 100, Username: "synthetic-budget-owner", Email: "budget-owner@example.invalid", DefaultCurrency: "CNY"},
		&models.User{Uid: 200, Username: "synthetic-budget-admin", Email: "budget-admin@example.invalid", DefaultCurrency: "USD"},
		&models.User{Uid: 300, Username: "synthetic-budget-member", Email: "budget-member@example.invalid", DefaultCurrency: "EUR"},
		&models.User{Uid: 400, Username: "synthetic-budget-viewer", Email: "budget-viewer@example.invalid", DefaultCurrency: "JPY"},
	)
	require.NoError(t, err)
	db := MonthlyBudgets.UserDataDB(100)
	_, err = db.NewSession(nil).Insert(
		&models.Ledger{LedgerId: 11, OwnerUid: 100, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic A"},
		&models.Ledger{LedgerId: 12, OwnerUid: 100, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic B"},
	)
	require.NoError(t, err)
	for index, role := range []models.FamilyMemberRole{models.FAMILY_MEMBER_ROLE_OWNER, models.FAMILY_MEMBER_ROLE_ADMIN, models.FAMILY_MEMBER_ROLE_MEMBER, models.FAMILY_MEMBER_ROLE_VIEWER} {
		_, err = db.NewSession(nil).Insert(&models.LedgerMember{LedgerMemberId: int64(index + 1), LedgerId: 11,
			Uid: int64((index + 1) * 100), Role: role, Status: models.FAMILY_MEMBER_STATUS_ACTIVE})
		require.NoError(t, err)
	}
	_, err = db.NewSession(nil).Insert(
		&models.LedgerMember{LedgerMemberId: 5, LedgerId: 12, Uid: 100, Role: models.FAMILY_MEMBER_ROLE_OWNER, Status: models.FAMILY_MEMBER_STATUS_ACTIVE},
		&models.TransactionCategory{CategoryId: 1001, Uid: 100, Type: models.CATEGORY_TYPE_EXPENSE, Name: "Synthetic expense root"},
		&models.TransactionCategory{CategoryId: 1002, Uid: 100, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: 1001, Name: "Synthetic expense leaf"},
		&models.TransactionCategory{CategoryId: 1003, Uid: 100, Type: models.CATEGORY_TYPE_INCOME, ParentCategoryId: 1001, Name: "Synthetic wrong type"},
		&models.TransactionCategory{CategoryId: 2001, Uid: 200, Type: models.CATEGORY_TYPE_EXPENSE, Name: "Synthetic foreign root"},
		&models.TransactionCategory{CategoryId: 2002, Uid: 200, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: 2001, Name: "Synthetic foreign leaf"},
	)
	require.NoError(t, err)
}

func setMonthlyBudgetForTest(t *testing.T, actor, ledgerID int64, yearMonth int32, amount int64, amounts map[string]int64, currency string) (*models.MonthlyBudget, error) {
	t.Helper()
	current, err := MonthlyBudgets.GetMonthlyBudgetInLedger(nil, actor, ledgerID, yearMonth)
	if err != nil {
		return nil, err
	}
	revision := int64(0)
	if current != nil {
		revision = current.Revision
	}
	return MonthlyBudgets.SetMonthlyBudgetInLedger(nil, actor, ledgerID, yearMonth, amount, amounts, currency, &revision)
}

func deleteMonthlyBudgetForTest(t *testing.T, actor, ledgerID int64, yearMonth int32) error {
	t.Helper()
	current, err := MonthlyBudgets.GetMonthlyBudgetInLedger(nil, actor, ledgerID, yearMonth)
	if err != nil {
		return err
	}
	revision := int64(0)
	if current != nil {
		revision = current.Revision
	}
	return MonthlyBudgets.DeleteMonthlyBudgetInLedger(nil, actor, ledgerID, yearMonth, &revision)
}

func TestMonthlyBudgetLedgerScopesRolesAndFixedCurrency(t *testing.T) {
	initMonthlyBudgetTestEnvironment(t)
	budget, err := setMonthlyBudgetForTest(t, 200, 11, 202610, 12345, map[string]int64{"1002": 345}, "")
	require.NoError(t, err)
	require.Equal(t, int64(100), budget.Uid, "administrator writes to the ledger owner's data")
	require.Equal(t, int64(11), budget.LedgerId)
	require.Equal(t, "CNY", budget.Currency, "member defaults do not select a shared budget's currency")
	for _, actor := range []int64{100, 200, 300, 400} {
		read, err := MonthlyBudgets.GetMonthlyBudgetInLedger(nil, actor, 11, 202610)
		require.NoError(t, err)
		require.Equal(t, budget.MonthlyBudgetId, read.MonthlyBudgetId)
		require.Equal(t, "CNY", read.Currency)
	}
	for _, actor := range []int64{300, 400} {
		_, err := setMonthlyBudgetForTest(t, actor, 11, 202610, 200, nil, "CNY")
		require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
		require.ErrorIs(t, deleteMonthlyBudgetForTest(t, actor, 11, 202610), errs.ErrLedgerAccessDenied)
	}
	_, err = MonthlyBudgets.GetMonthlyBudgetInLedger(nil, 500, 11, 202610)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	other, err := MonthlyBudgets.GetMonthlyBudgetInLedger(nil, 100, 12, 202610)
	require.NoError(t, err)
	require.Nil(t, other)
	_, err = setMonthlyBudgetForTest(t, 200, 12, 202610, 200, nil, "USD")
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	other, err = setMonthlyBudgetForTest(t, 100, 12, 202610, 200, nil, "USD")
	require.NoError(t, err)
	require.NotEqual(t, budget.MonthlyBudgetId, other.MonthlyBudgetId)
	personal, err := MonthlyBudgets.SetMonthlyBudget(nil, 200, 202610, 300, nil)
	require.NoError(t, err)
	require.Equal(t, models.DefaultLedgerId, personal.LedgerId)
	require.Equal(t, int64(200), personal.Uid)
	require.Equal(t, "USD", personal.Currency)
	_, err = MonthlyBudgets.UserDB().NewSession(nil).ID(100).Cols("default_currency").Update(&models.User{DefaultCurrency: "EUR"})
	require.NoError(t, err)
	updated, err := setMonthlyBudgetForTest(t, 200, 11, 202610, 400, nil, "")
	require.NoError(t, err)
	require.Equal(t, budget.MonthlyBudgetId, updated.MonthlyBudgetId)
	require.Equal(t, "CNY", updated.Currency, "editing without currency retains the original denomination")
	updated, err = setMonthlyBudgetForTest(t, 100, 11, 202610, 500, nil, "JPY")
	require.NoError(t, err)
	require.Equal(t, "JPY", updated.Currency)
	require.NoError(t, deleteMonthlyBudgetForTest(t, 200, 11, 202610))
	deleted, err := MonthlyBudgets.GetMonthlyBudgetInLedger(nil, 400, 11, 202610)
	require.NoError(t, err)
	require.Nil(t, deleted)
	other, err = MonthlyBudgets.GetMonthlyBudgetInLedger(nil, 100, 12, 202610)
	require.NoError(t, err)
	require.Equal(t, int64(200), other.Amount, "deletion does not affect the other ledger")
	personal, err = MonthlyBudgets.GetMonthlyBudget(nil, 200, 202610)
	require.NoError(t, err)
	require.Equal(t, int64(300), personal.Amount)
}

func TestMonthlyBudgetLedgerLiveDowngradeAndTransactionRecheck(t *testing.T) {
	initMonthlyBudgetTestEnvironment(t)
	_, cached, err := Ledgers.GetLedgerWithAccess(nil, 200, 11, models.FamilyMemberRole.CanManage)
	require.NoError(t, err)
	db := MonthlyBudgets.UserDataDB(100)
	_, err = db.NewSession(nil).ID(2).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	_, err = setMonthlyBudgetForTest(t, 200, 11, 202610, 500, nil, "USD")
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	require.ErrorIs(t, deleteMonthlyBudgetForTest(t, 200, 11, 202610), errs.ErrLedgerAccessDenied)
	// This is the same check invoked inside both writes, using a previously
	// authorized ledger snapshot after the actor has been downgraded.
	err = db.DoTransaction(nil, func(sess *xorm.Session) error {
		return authorizeMonthlyBudgetLedger(sess, 200, cached, models.FamilyMemberRole.CanManage, true)
	})
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	_, err = MonthlyBudgets.GetMonthlyBudgetInLedger(nil, 200, 11, 202610)
	require.NoError(t, err, "a downgraded viewer retains reading")
	_, err = db.NewSession(nil).ID(2).Cols("status").Update(&models.LedgerMember{Status: models.FAMILY_MEMBER_STATUS_REMOVED})
	require.NoError(t, err)
	_, err = MonthlyBudgets.GetMonthlyBudgetInLedger(nil, 200, 11, 202610)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	_, err = db.NewSession(nil).ID(11).Cols("deleted").Update(&models.Ledger{Deleted: true})
	require.NoError(t, err)
	err = db.DoTransaction(nil, func(sess *xorm.Session) error {
		return authorizeMonthlyBudgetLedger(sess, 100, cached, models.FamilyMemberRole.CanManage, true)
	})
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
}

func TestMonthlyBudgetAmountsCurrencyAndOwnerExpenseTaxonomy(t *testing.T) {
	initMonthlyBudgetTestEnvironment(t)
	for _, amount := range []int64{0, -1, models.MaximumMonthlyBudgetAmount + 1, 9007199254740993} {
		_, err := setMonthlyBudgetForTest(t, 100, 11, 202610, amount, nil, "CNY")
		require.ErrorIs(t, err, errs.ErrMonthlyBudgetAmountInvalid)
	}
	for _, currency := range []string{"cny", "ZZZ", "***", "CNYX"} {
		_, err := setMonthlyBudgetForTest(t, 100, 11, 202610, 123, nil, currency)
		require.ErrorIs(t, err, errs.ErrMonthlyBudgetCurrencyInvalid)
	}
	for _, id := range []string{"1001", "1003", "2002", "9999", "01002", "1002.0", "-1002"} {
		_, err := setMonthlyBudgetForTest(t, 200, 11, 202610, 123, map[string]int64{id: 100}, "CNY")
		require.ErrorIs(t, err, errs.ErrMonthlyBudgetCategoryInvalid, id)
	}
	_, err := setMonthlyBudgetForTest(t, 200, 11, 202610, models.MaximumMonthlyBudgetAmount,
		map[string]int64{"1002": models.MaximumMonthlyBudgetAmount}, "USD")
	require.NoError(t, err)
	_, err = setMonthlyBudgetForTest(t, 100, 11, 202610, 123, map[string]int64{"1002": models.MaximumMonthlyBudgetAmount + 1}, "CNY")
	require.ErrorIs(t, err, errs.ErrMonthlyBudgetAmountInvalid)
	db := MonthlyBudgets.UserDataDB(100)
	for _, id := range []int64{1001, 1002} {
		_, err = db.NewSession(nil).ID(id).Cols("hidden").Update(&models.TransactionCategory{Hidden: true})
		require.NoError(t, err)
		_, err = setMonthlyBudgetForTest(t, 100, 11, 202610, 123, map[string]int64{"1002": 100}, "CNY")
		require.ErrorIs(t, err, errs.ErrMonthlyBudgetCategoryInvalid)
		_, err = db.NewSession(nil).ID(id).Cols("hidden").Update(&models.TransactionCategory{Hidden: false})
		require.NoError(t, err)
	}
	_, err = db.NewSession(nil).Insert(&models.TransactionCategory{CategoryId: 1004, Uid: 100, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: 1002})
	require.NoError(t, err)
	_, err = setMonthlyBudgetForTest(t, 100, 11, 202610, 123, map[string]int64{"1002": 100}, "CNY")
	require.ErrorIs(t, err, errs.ErrMonthlyBudgetCategoryInvalid, "a leaf cannot have an undeleted child")
}

// This pins the actual pre-migration schema, not a new model with empty fields.
type legacyMonthlyBudget struct {
	MonthlyBudgetId     int64  `xorm:"PK"`
	Uid                 int64  `xorm:"INDEX(IDX_monthly_budget_uid_deleted_year_month) NOT NULL"`
	Deleted             bool   `xorm:"INDEX(IDX_monthly_budget_uid_deleted_year_month) NOT NULL"`
	YearMonth           int32  `xorm:"INDEX(IDX_monthly_budget_uid_deleted_year_month) NOT NULL"`
	Amount              int64  `xorm:"NOT NULL"`
	CategoryAmountsJson string `xorm:"TEXT NOT NULL DEFAULT '{}'"`
	CreatedUnixTime     int64
	UpdatedUnixTime     int64
	DeletedUnixTime     int64
}

func (*legacyMonthlyBudget) TableName() string { return "monthly_budget" }

func TestMonthlyBudgetLegacySchemaMigrationFreezesOwnerCurrency(t *testing.T) {
	initMonthlyBudgetTestEnvironment(t)
	db := MonthlyBudgets.UserDataDB(100)
	_, err := db.NewSession(nil).Exec("DROP TABLE monthly_budget")
	require.NoError(t, err)
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(legacyMonthlyBudget)))
	_, err = db.NewSession(nil).Insert(
		&legacyMonthlyBudget{MonthlyBudgetId: 9001, Uid: 100, YearMonth: 202610, Amount: 12345, CategoryAmountsJson: `{"1002":345}`, CreatedUnixTime: 11, UpdatedUnixTime: 22},
		&legacyMonthlyBudget{MonthlyBudgetId: 9002, Uid: 100, YearMonth: 202610, Amount: 50, CategoryAmountsJson: "{}", Deleted: true, DeletedUnixTime: 30},
		&legacyMonthlyBudget{MonthlyBudgetId: 9003, Uid: 999, YearMonth: 202610, Amount: 70, CategoryAmountsJson: "{}", Deleted: true, DeletedUnixTime: 31},
		&legacyMonthlyBudget{MonthlyBudgetId: 9004, Uid: 200, YearMonth: 202610, Amount: 80, CategoryAmountsJson: "{}"},
	)
	require.NoError(t, err)
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(models.MonthlyBudget)))
	require.NoError(t, MonthlyBudgets.MigrateLegacyMonthlyBudgets(nil))
	owner, err := MonthlyBudgets.GetMonthlyBudget(nil, 100, 202610)
	require.NoError(t, err)
	require.Equal(t, models.DefaultLedgerId, owner.LedgerId)
	require.Equal(t, "CNY", owner.Currency)
	require.Equal(t, int64(9001), owner.MonthlyBudgetId)
	require.Equal(t, int64(12345), owner.Amount)
	require.Equal(t, `{"1002":345}`, owner.CategoryAmountsJson)
	require.Equal(t, int64(11), owner.CreatedUnixTime)
	require.Equal(t, int64(22), owner.UpdatedUnixTime)
	member, err := MonthlyBudgets.GetMonthlyBudget(nil, 200, 202610)
	require.NoError(t, err)
	require.Equal(t, "USD", member.Currency)
	_, err = MonthlyBudgets.UserDB().NewSession(nil).ID(100).Cols("default_currency").Update(&models.User{DefaultCurrency: "EUR"})
	require.NoError(t, err)
	require.NoError(t, MonthlyBudgets.MigrateLegacyMonthlyBudgets(nil))
	owner, err = MonthlyBudgets.GetMonthlyBudget(nil, 100, 202610)
	require.NoError(t, err)
	require.Equal(t, "CNY", owner.Currency)
	count, err := db.NewSession(nil).Count(new(models.MonthlyBudget))
	require.NoError(t, err)
	require.Equal(t, int64(4), count, "migration retains every historical row, including erased users' deleted budgets")
	shared, err := MonthlyBudgets.GetMonthlyBudgetInLedger(nil, 100, 11, 202610)
	require.NoError(t, err)
	require.Nil(t, shared, "legacy budgets remain exclusively in ledger 0")
	require.NoError(t, MonthlyBudgets.DeleteMonthlyBudget(nil, 100, 202610))
	owner, err = MonthlyBudgets.SetMonthlyBudget(nil, 100, 202610, 567, nil)
	require.NoError(t, err)
	require.Equal(t, "EUR", owner.Currency)
	require.Equal(t, int64(9001), owner.MonthlyBudgetId, "restoration retains the persistent scope and avoids revision ABA")
	require.Equal(t, int64(3), owner.Revision)
}

func TestMonthlyBudgetLegacyMigrationRejectsMissingOwnerAndInvalidCurrency(t *testing.T) {
	for _, owner := range []int64{999, 100} {
		t.Run(strconv.FormatInt(owner, 10), func(t *testing.T) {
			initMonthlyBudgetTestEnvironment(t)
			if owner == 100 {
				_, err := MonthlyBudgets.UserDB().NewSession(nil).ID(owner).Cols("default_currency").Update(&models.User{DefaultCurrency: "BAD"})
				require.NoError(t, err)
			}
			_, err := MonthlyBudgets.UserDataDB(100).NewSession(nil).Insert(&models.MonthlyBudget{MonthlyBudgetId: 9101, Uid: owner, YearMonth: 202610, Amount: 123, CategoryAmountsJson: "{}"})
			require.NoError(t, err)
			err = MonthlyBudgets.MigrateLegacyMonthlyBudgets(nil)
			if owner == 999 {
				require.ErrorIs(t, err, errs.ErrUserNotFound)
			} else {
				require.ErrorIs(t, err, errs.ErrMonthlyBudgetCurrencyInvalid)
			}
		})
	}
}

func TestMonthlyBudgetUniqueActiveScopeConcurrentSetAndDeleteRecreate(t *testing.T) {
	initMonthlyBudgetTestEnvironment(t)
	for _, ledgerID := range []int64{0, 11} {
		var wg sync.WaitGroup
		failures := make(chan error, 8)
		for index := 0; index < 8; index++ {
			wg.Add(1)
			go func(amount int64) {
				defer wg.Done()
				_, err := setMonthlyBudgetForTest(t, 100, ledgerID, 202610, amount, nil, "CNY")
				failures <- err
			}(int64(index + 100))
		}
		wg.Wait()
		close(failures)
		succeeded := 0
		for err := range failures {
			if err != nil {
				require.ErrorIs(t, err, errs.ErrMonthlyBudgetConflict)
			} else {
				succeeded++
			}
		}
		require.Greater(t, succeeded, 0)
		db := MonthlyBudgets.UserDataDB(100)
		count, err := db.NewSession(nil).Where("uid=? AND ledger_id=? AND year_month=? AND deleted=?", 100, ledgerID, 202610, false).Count(new(models.MonthlyBudget))
		require.NoError(t, err)
		require.Equal(t, int64(1), count)
		scope := monthlyBudgetActiveScope(100, ledgerID, 202610)
		_, err = db.NewSession(nil).Insert(&models.MonthlyBudget{MonthlyBudgetId: 9900 + ledgerID, Uid: 100, LedgerId: ledgerID, YearMonth: 202610, Amount: 123, Currency: "CNY", CategoryAmountsJson: "{}", ActiveBudgetScope: &scope})
		require.Error(t, err, "database constraint also rejects a duplicate from another process")
		require.NoError(t, deleteMonthlyBudgetForTest(t, 100, ledgerID, 202610))
		_, err = setMonthlyBudgetForTest(t, 100, ledgerID, 202610, 123, nil, "USD")
		require.NoError(t, err, "deleted history releases the unique active slot")
	}
}

func TestMonthlyBudgetExpectedRevisionRequiredConflictAndDeletionABA(t *testing.T) {
	initMonthlyBudgetTestEnvironment(t)
	zero := int64(0)
	_, err := MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 100, 11, 202610, 100, nil, "CNY")
	require.ErrorIs(t, err, errs.ErrMonthlyBudgetRevisionRequired)
	require.ErrorIs(t, MonthlyBudgets.DeleteMonthlyBudgetInLedger(nil, 100, 11, 202610), errs.ErrMonthlyBudgetRevisionRequired)
	budget, err := MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 100, 11, 202610, 100, nil, "CNY", &zero)
	require.NoError(t, err)
	require.Equal(t, int64(1), budget.Revision)
	first := budget.Revision
	_, err = MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 200, 11, 202610, 200, nil, "USD", &zero)
	require.ErrorIs(t, err, errs.ErrMonthlyBudgetConflict, "expected no budget cannot replace an active budget")
	_, err = MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 200, 11, 202610, 200, nil, "USD", &first)
	require.NoError(t, err)
	_, err = MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 100, 11, 202610, 300, nil, "EUR", &first)
	require.ErrorIs(t, err, errs.ErrMonthlyBudgetConflict)
	require.ErrorIs(t, MonthlyBudgets.DeleteMonthlyBudgetInLedger(nil, 100, 11, 202610, &first), errs.ErrMonthlyBudgetConflict)
	second := int64(2)
	require.NoError(t, MonthlyBudgets.DeleteMonthlyBudgetInLedger(nil, 200, 11, 202610, &second))
	_, err = MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 100, 11, 202610, 300, nil, "EUR", &second)
	require.ErrorIs(t, err, errs.ErrMonthlyBudgetConflict, "deleted scope is absent for CAS matching")
	restored, err := MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 100, 11, 202610, 300, nil, "EUR", &zero)
	require.NoError(t, err)
	require.Equal(t, budget.MonthlyBudgetId, restored.MonthlyBudgetId)
	require.Equal(t, int64(4), restored.Revision)
	for _, stale := range []int64{first, second, 3} {
		_, err = MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 200, 11, 202610, 400, nil, "USD", &stale)
		require.ErrorIs(t, err, errs.ErrMonthlyBudgetConflict)
		require.ErrorIs(t, MonthlyBudgets.DeleteMonthlyBudgetInLedger(nil, 200, 11, 202610, &stale), errs.ErrMonthlyBudgetConflict)
	}
	_, err = MonthlyBudgets.UserDataDB(100).NewSession(nil).ID(2).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	_, err = MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 200, 11, 202610, 400, nil, "USD", &first)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied, "live downgrade has priority over stale-version conflict")
	require.ErrorIs(t, MonthlyBudgets.DeleteMonthlyBudgetInLedger(nil, 200, 11, 202610, &first), errs.ErrLedgerAccessDenied)
	_, err = MonthlyBudgets.SetMonthlyBudget(nil, 100, 202610, 123, nil)
	require.NoError(t, err, "default-ledger legacy callers may omit the revision")
	_, err = MonthlyBudgets.SetMonthlyBudget(nil, 100, 202610, 456, nil)
	require.NoError(t, err)
	require.NoError(t, MonthlyBudgets.DeleteMonthlyBudget(nil, 100, 202610))
	require.Equal(t, 409, errs.ErrMonthlyBudgetConflict.HttpStatusCode)
}

func TestMonthlyBudgetSameRevisionTwoManagersOnlyOneWins(t *testing.T) {
	initMonthlyBudgetTestEnvironment(t)
	zero := int64(0)
	_, err := MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 100, 11, 202610, 100, nil, "CNY", &zero)
	require.NoError(t, err)
	revision := int64(1)
	ready := make(chan struct{})
	results := make(chan error, 2)
	var wg sync.WaitGroup
	for _, actor := range []int64{100, 200} {
		wg.Add(1)
		go func(actor int64) {
			defer wg.Done()
			<-ready
			_, err := MonthlyBudgets.SetMonthlyBudgetInLedger(nil, actor, 11, 202610, actor, nil, "USD", &revision)
			results <- err
		}(actor)
	}
	close(ready)
	wg.Wait()
	close(results)
	successes, conflicts := 0, 0
	for err := range results {
		if err == nil {
			successes++
		} else {
			require.ErrorIs(t, err, errs.ErrMonthlyBudgetConflict)
			conflicts++
		}
	}
	require.Equal(t, 1, successes)
	require.Equal(t, 1, conflicts)
	budget, err := MonthlyBudgets.GetMonthlyBudgetInLedger(nil, 400, 11, 202610)
	require.NoError(t, err)
	require.Equal(t, int64(2), budget.Revision)
	require.Contains(t, []int64{100, 200}, budget.Amount)
}

func TestMonthlyBudgetBlocksLedgerDeletionUntilBudgetIsRemoved(t *testing.T) {
	initMonthlyBudgetTestEnvironment(t)
	_, err := setMonthlyBudgetForTest(t, 100, 11, 202610, 123, nil, "CNY")
	require.NoError(t, err)
	preview, err := Ledgers.GetDeletePreview(nil, 100, 11)
	require.NoError(t, err)
	require.Equal(t, int64(1), preview.MonthlyBudgetCount)
	require.False(t, preview.CanDelete)
	require.Equal(t, []string{"monthlyBudgets"}, preview.BlockingReasons)
	require.ErrorIs(t, Ledgers.DeleteLedger(nil, 100, 11), errs.ErrLedgerNotEmpty)
	require.NoError(t, deleteMonthlyBudgetForTest(t, 200, 11, 202610))
	preview, err = Ledgers.GetDeletePreview(nil, 100, 11)
	require.NoError(t, err)
	require.Zero(t, preview.MonthlyBudgetCount)
	require.True(t, preview.CanDelete)
	require.NoError(t, Ledgers.DeleteLedger(nil, 100, 11))
	_, err = setMonthlyBudgetForTest(t, 100, 11, 202610, 123, nil, "CNY")
	require.ErrorIs(t, err, errs.ErrLedgerNotFound)
}
