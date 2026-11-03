package errs

import "net/http"

// Error codes related to monthly budgets.
var (
	ErrMonthlyBudgetYearMonthInvalid = NewNormalError(NormalSubcategoryMonthlyBudget, 0, http.StatusBadRequest, "monthly budget year and month is invalid")
	ErrMonthlyBudgetAmountInvalid    = NewNormalError(NormalSubcategoryMonthlyBudget, 1, http.StatusBadRequest, "monthly budget amount is invalid")
	ErrMonthlyBudgetNotFound         = NewNormalError(NormalSubcategoryMonthlyBudget, 2, http.StatusBadRequest, "monthly budget not found")
	ErrMonthlyBudgetCurrencyInvalid  = NewNormalError(NormalSubcategoryMonthlyBudget, 3, http.StatusBadRequest, "monthly budget currency is invalid")
	ErrMonthlyBudgetCategoryInvalid  = NewNormalError(NormalSubcategoryMonthlyBudget, 4, http.StatusBadRequest, "monthly budget category is invalid")
	ErrMonthlyBudgetRevisionRequired = NewNormalError(NormalSubcategoryMonthlyBudget, 5, http.StatusBadRequest, "monthly budget expected revision is required")
	ErrMonthlyBudgetRevisionInvalid  = NewNormalError(NormalSubcategoryMonthlyBudget, 6, http.StatusBadRequest, "monthly budget revision is invalid")
	ErrMonthlyBudgetConflict         = NewNormalError(NormalSubcategoryMonthlyBudget, 7, http.StatusConflict, "monthly budget has changed; reload before saving")
)
