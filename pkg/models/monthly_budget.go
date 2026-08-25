package models

// MonthlyBudgetWarningLevel represents the current state of total monthly budget usage.
type MonthlyBudgetWarningLevel byte

const (
	MONTHLY_BUDGET_WARNING_LEVEL_NONE     MonthlyBudgetWarningLevel = 0
	MONTHLY_BUDGET_WARNING_LEVEL_EIGHTY   MonthlyBudgetWarningLevel = 1
	MONTHLY_BUDGET_WARNING_LEVEL_EXCEEDED MonthlyBudgetWarningLevel = 2
)

// MonthlyBudget represents one total budget for one calendar month.
// Unused value is intentionally not carried into another month.
type MonthlyBudget struct {
	MonthlyBudgetId int64 `xorm:"PK"`
	Uid             int64 `xorm:"INDEX(IDX_monthly_budget_uid_deleted_year_month) NOT NULL"`
	Deleted         bool  `xorm:"INDEX(IDX_monthly_budget_uid_deleted_year_month) NOT NULL"`
	YearMonth       int32 `xorm:"INDEX(IDX_monthly_budget_uid_deleted_year_month) NOT NULL"`
	Amount          int64 `xorm:"NOT NULL"`
	CreatedUnixTime int64
	UpdatedUnixTime int64
	DeletedUnixTime int64
}

// MonthlyBudgetGetRequest represents a calendar month in YYYYMM format.
type MonthlyBudgetGetRequest struct {
	YearMonth int32 `form:"yearMonth" binding:"required,min=200001,max=999912"`
}

// MonthlyBudgetSetRequest creates or replaces the total budget for a calendar month.
type MonthlyBudgetSetRequest struct {
	YearMonth int32 `json:"yearMonth" binding:"required,min=200001,max=999912"`
	Amount    int64 `json:"amount" binding:"required,min=1"`
}

// MonthlyBudgetDeleteRequest removes the budget for one calendar month.
type MonthlyBudgetDeleteRequest struct {
	YearMonth int32 `json:"yearMonth" binding:"required,min=200001,max=999912"`
}

// MonthlyBudgetInfoResponse is the API representation of a monthly budget.
type MonthlyBudgetInfoResponse struct {
	Id        int64 `json:"id,string"`
	YearMonth int32 `json:"yearMonth"`
	Amount    int64 `json:"amount"`
}

// ToMonthlyBudgetInfoResponse converts a stored budget to its API representation.
func (b *MonthlyBudget) ToMonthlyBudgetInfoResponse() *MonthlyBudgetInfoResponse {
	return &MonthlyBudgetInfoResponse{Id: b.MonthlyBudgetId, YearMonth: b.YearMonth, Amount: b.Amount}
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
