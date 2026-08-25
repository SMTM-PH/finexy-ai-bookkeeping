package services

import (
	"time"

	"xorm.io/xorm"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
)

// MonthlyBudgetService manages one non-carrying total budget per calendar month.
type MonthlyBudgetService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

// MonthlyBudgets is the monthly budget service singleton.
var MonthlyBudgets = &MonthlyBudgetService{
	ServiceUsingDB:   ServiceUsingDB{container: datastore.Container},
	ServiceUsingUuid: ServiceUsingUuid{container: uuid.Container},
}

// GetMonthlyBudget returns the budget for one month, or nil when it is not configured.
func (s *MonthlyBudgetService) GetMonthlyBudget(c core.Context, uid int64, yearMonth int32) (*models.MonthlyBudget, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}
	if !isValidYearMonth(yearMonth) {
		return nil, errs.ErrMonthlyBudgetYearMonthInvalid
	}

	budget := &models.MonthlyBudget{}
	has, err := s.UserDataDB(uid).NewSession(c).Where("uid=? AND deleted=? AND year_month=?", uid, false, yearMonth).Get(budget)
	if err != nil {
		return nil, err
	}
	if !has {
		return nil, nil
	}
	return budget, nil
}

// SetMonthlyBudget creates or replaces the budget for one month.
func (s *MonthlyBudgetService) SetMonthlyBudget(c core.Context, uid int64, yearMonth int32, amount int64) (*models.MonthlyBudget, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}
	if !isValidYearMonth(yearMonth) {
		return nil, errs.ErrMonthlyBudgetYearMonthInvalid
	}
	if amount <= 0 {
		return nil, errs.ErrMonthlyBudgetAmountInvalid
	}

	now := time.Now().Unix()
	budget := &models.MonthlyBudget{}
	err := s.UserDataDB(uid).DoTransaction(c, func(sess *xorm.Session) error {
		has, err := sess.Where("uid=? AND deleted=? AND year_month=?", uid, false, yearMonth).Get(budget)
		if err != nil {
			return err
		}

		if has {
			budget.Amount = amount
			budget.UpdatedUnixTime = now
			_, err = sess.ID(budget.MonthlyBudgetId).Cols("amount", "updated_unix_time").Where("uid=? AND deleted=?", uid, false).Update(budget)
			return err
		}

		budget = &models.MonthlyBudget{
			MonthlyBudgetId: s.GenerateUuid(uuid.UUID_TYPE_MONTHLY_BUDGET),
			Uid:             uid, YearMonth: yearMonth, Amount: amount,
			CreatedUnixTime: now, UpdatedUnixTime: now,
		}
		if budget.MonthlyBudgetId < 1 {
			return errs.ErrSystemIsBusy
		}
		_, err = sess.Insert(budget)
		return err
	})

	return budget, err
}

// DeleteMonthlyBudget removes the configured budget for one month.
func (s *MonthlyBudgetService) DeleteMonthlyBudget(c core.Context, uid int64, yearMonth int32) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}
	if !isValidYearMonth(yearMonth) {
		return errs.ErrMonthlyBudgetYearMonthInvalid
	}

	now := time.Now().Unix()
	update := &models.MonthlyBudget{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now}
	rows, err := s.UserDataDB(uid).NewSession(c).Cols("deleted", "deleted_unix_time", "updated_unix_time").Where("uid=? AND deleted=? AND year_month=?", uid, false, yearMonth).Update(update)
	if err != nil {
		return err
	}
	if rows < 1 {
		return errs.ErrMonthlyBudgetNotFound
	}
	return nil
}

// DeleteAllMonthlyBudgets removes every budget owned by a user.
func (s *MonthlyBudgetService) DeleteAllMonthlyBudgets(c core.Context, uid int64) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}
	now := time.Now().Unix()
	update := &models.MonthlyBudget{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now}
	_, err := s.UserDataDB(uid).NewSession(c).Cols("deleted", "deleted_unix_time", "updated_unix_time").Where("uid=? AND deleted=?", uid, false).Update(update)
	return err
}

func isValidYearMonth(yearMonth int32) bool {
	month := yearMonth % 100
	year := yearMonth / 100
	return year >= 2000 && year <= 9999 && month >= 1 && month <= 12
}
