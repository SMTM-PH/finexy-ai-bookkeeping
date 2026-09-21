package errs

import "net/http"

// Error codes related to savings goals and their fund movements.
var (
	ErrSavingsGoalNotFound          = NewNormalError(NormalSubcategorySavingsGoal, 0, http.StatusBadRequest, "savings goal not found")
	ErrSavingsGoalNameInvalid       = NewNormalError(NormalSubcategorySavingsGoal, 1, http.StatusBadRequest, "savings goal name is invalid")
	ErrSavingsGoalAmountInvalid     = NewNormalError(NormalSubcategorySavingsGoal, 2, http.StatusBadRequest, "savings goal amount is invalid")
	ErrSavingsGoalAccessDenied      = NewNormalError(NormalSubcategorySavingsGoal, 3, http.StatusForbidden, "no permission to access this savings goal")
	ErrSavingsGoalFundAmountInvalid = NewNormalError(NormalSubcategorySavingsGoal, 4, http.StatusBadRequest, "savings goal fund amount is invalid")
	ErrSavingsGoalInsufficientSaved = NewNormalError(NormalSubcategorySavingsGoal, 5, http.StatusBadRequest, "the withdrawal exceeds the saved amount of the goal")
	ErrSavingsGoalAccountMismatch   = NewNormalError(NormalSubcategorySavingsGoal, 6, http.StatusBadRequest, "the account does not belong to the ledger of the goal")
	ErrSavingsGoalHasFunds          = NewNormalError(NormalSubcategorySavingsGoal, 7, http.StatusBadRequest, "a goal with fund movements cannot be deleted")
)
