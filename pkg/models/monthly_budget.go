package models

import "encoding/json"

// MonthlyBudgetWarningLevel represents the current state of total monthly budget usage.
type MonthlyBudgetWarningLevel byte

const (
	MONTHLY_BUDGET_WARNING_LEVEL_NONE     MonthlyBudgetWarningLevel = 0
	MONTHLY_BUDGET_WARNING_LEVEL_EIGHTY   MonthlyBudgetWarningLevel = 1
	MONTHLY_BUDGET_WARNING_LEVEL_EXCEEDED MonthlyBudgetWarningLevel = 2
)

// MaximumMonthlyBudgetAmount is the integer minor-unit ceiling used by budget
// amounts. It stays below JavaScript's maximum safe integer.
const MaximumMonthlyBudgetAmount = int64(9999999999999)

const MaximumMonthlyBudgetRevision = int64(9007199254740991)

// MonthlyBudget represents one total budget for one calendar month.
// Unused value is intentionally not carried into another month.
type MonthlyBudget struct {
	MonthlyBudgetId int64  `xorm:"PK"`
	Uid             int64  `xorm:"INDEX(IDX_monthly_budget_uid_deleted_year_month) INDEX(IDX_monthly_budget_uid_ledger_deleted_month) NOT NULL"`
	LedgerId        int64  `xorm:"INDEX(IDX_monthly_budget_uid_ledger_deleted_month) NOT NULL DEFAULT 0"`
	Deleted         bool   `xorm:"INDEX(IDX_monthly_budget_uid_deleted_year_month) INDEX(IDX_monthly_budget_uid_ledger_deleted_month) NOT NULL"`
	YearMonth       int32  `xorm:"INDEX(IDX_monthly_budget_uid_deleted_year_month) INDEX(IDX_monthly_budget_uid_ledger_deleted_month) NOT NULL"`
	Currency        string `xorm:"VARCHAR(3) NOT NULL DEFAULT ''"`
	Revision        int64  `xorm:"NOT NULL DEFAULT 1"`
	// Deleted rows have a NULL scope, allowing retained history while the unique
	// index prevents two active budgets for the same owner, ledger and month.
	ActiveBudgetScope *string `xorm:"VARCHAR(80) UNIQUE(UQE_monthly_budget_active_scope)"`
	Amount            int64   `xorm:"NOT NULL"`
	// The service serializes even an empty category map as "{}". A TEXT column
	// must not require a database-specific default expression (MySQL Error 1101).
	CategoryAmountsJson string `xorm:"TEXT NOT NULL"`
	CreatedUnixTime     int64
	UpdatedUnixTime     int64
	DeletedUnixTime     int64
}

// MonthlyBudgetGetRequest represents a calendar month in YYYYMM format.
type MonthlyBudgetGetRequest struct {
	LedgerId  int64 `form:"ledgerId,string" binding:"omitempty,min=0"`
	YearMonth int32 `form:"yearMonth" binding:"required,min=200001,max=999912"`
}

// MonthlyBudgetSetRequest creates or replaces the total budget for a calendar month.
type MonthlyBudgetSetRequest struct {
	ExpectedRevision *int64           `json:"expectedRevision" binding:"omitempty,min=0,max=9007199254740991"`
	LedgerId         int64            `json:"ledgerId,string" binding:"omitempty,min=0"`
	Currency         string           `json:"currency" binding:"omitempty,len=3,validCurrency"`
	YearMonth        int32            `json:"yearMonth" binding:"required,min=200001,max=999912"`
	Amount           int64            `json:"amount" binding:"required,min=1,max=9999999999999"`
	CategoryAmounts  map[string]int64 `json:"categoryAmounts"`
}

// MonthlyBudgetDeleteRequest removes the budget for one calendar month.
type MonthlyBudgetDeleteRequest struct {
	ExpectedRevision *int64 `json:"expectedRevision" binding:"omitempty,min=0,max=9007199254740991"`
	LedgerId         int64  `json:"ledgerId,string" binding:"omitempty,min=0"`
	YearMonth        int32  `json:"yearMonth" binding:"required,min=200001,max=999912"`
}

// MonthlyBudgetInfoResponse is the API representation of a monthly budget.
type MonthlyBudgetInfoResponse struct {
	Id              int64            `json:"id,string"`
	LedgerId        int64            `json:"ledgerId,string"`
	Currency        string           `json:"currency"`
	Revision        int64            `json:"revision"`
	YearMonth       int32            `json:"yearMonth"`
	Amount          int64            `json:"amount"`
	CategoryAmounts map[string]int64 `json:"categoryAmounts"`
}

// ToMonthlyBudgetInfoResponse converts a stored budget to its API representation.
func (b *MonthlyBudget) ToMonthlyBudgetInfoResponse() *MonthlyBudgetInfoResponse {
	categoryAmounts := make(map[string]int64)
	if b.CategoryAmountsJson != "" {
		_ = json.Unmarshal([]byte(b.CategoryAmountsJson), &categoryAmounts)
	}
	return &MonthlyBudgetInfoResponse{Id: b.MonthlyBudgetId, LedgerId: b.LedgerId, Currency: b.Currency, Revision: b.Revision, YearMonth: b.YearMonth, Amount: b.Amount, CategoryAmounts: categoryAmounts}
}

// UsagePercentage returns budget usage as a percentage.
func (b *MonthlyBudget) UsagePercentage(expenseAmount int64) float64 {
	if b.Amount <= 0 {
		return 0
	}

	return float64(expenseAmount) * 100 / float64(b.Amount)
}

// WarningLevel returns the in-app warning threshold reached by current expenses.
func (b *MonthlyBudget) WarningLevel(expenseAmount int64) MonthlyBudgetWarningLevel {
	percentage := b.UsagePercentage(expenseAmount)

	if percentage >= 100 {
		return MONTHLY_BUDGET_WARNING_LEVEL_EXCEEDED
	} else if percentage >= 80 {
		return MONTHLY_BUDGET_WARNING_LEVEL_EIGHTY
	}

	return MONTHLY_BUDGET_WARNING_LEVEL_NONE
}
