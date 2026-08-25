package api

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
)

// MonthlyBudgetsApi represents monthly budget API handlers.
type MonthlyBudgetsApi struct {
	monthlyBudgets *services.MonthlyBudgetService
}

// MonthlyBudgets is the monthly budget API singleton.
var MonthlyBudgets = &MonthlyBudgetsApi{monthlyBudgets: services.MonthlyBudgets}

// MonthlyBudgetGetHandler returns one month budget, or null when not configured.
func (a *MonthlyBudgetsApi) MonthlyBudgetGetHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.MonthlyBudgetGetRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	budget, err := a.monthlyBudgets.GetMonthlyBudget(c, c.GetCurrentUid(), request.YearMonth)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	if budget == nil {
		return nil, nil
	}
	return budget.ToMonthlyBudgetInfoResponse(), nil
}

// MonthlyBudgetSetHandler creates or replaces one month budget.
func (a *MonthlyBudgetsApi) MonthlyBudgetSetHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.MonthlyBudgetSetRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	budget, err := a.monthlyBudgets.SetMonthlyBudget(c, c.GetCurrentUid(), request.YearMonth, request.Amount)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return budget.ToMonthlyBudgetInfoResponse(), nil
}

// MonthlyBudgetDeleteHandler removes one month budget.
func (a *MonthlyBudgetsApi) MonthlyBudgetDeleteHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.MonthlyBudgetDeleteRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	if err := a.monthlyBudgets.DeleteMonthlyBudget(c, c.GetCurrentUid(), request.YearMonth); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return true, nil
}
