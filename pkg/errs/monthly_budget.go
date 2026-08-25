package errs

import "net/http"

// Error codes related to monthly budgets.
var (
	ErrMonthlyBudgetYearMonthInvalid = NewNormalError(NormalSubcategoryMonthlyBudget, 0, http.StatusBadRequest, "monthly budget year and month is invalid")
	ErrMonthlyBudgetAmountInvalid    = NewNormalError(NormalSubcategoryMonthlyBudget, 1, http.StatusBadRequest, "monthly budget amount is invalid")
	ErrMonthlyBudgetNotFound         = NewNormalError(NormalSubcategoryMonthlyBudget, 2, http.StatusBadRequest, "monthly budget not found")
)
