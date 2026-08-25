package models

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestMonthlyBudgetWarningLevel(t *testing.T) {
	budget := &MonthlyBudget{Amount: 100000}

	assert.InDelta(t, 79.999, budget.UsagePercentage(79999), 0.001)
	assert.Equal(t, MONTHLY_BUDGET_WARNING_LEVEL_NONE, budget.WarningLevel(79999))
	assert.Equal(t, MONTHLY_BUDGET_WARNING_LEVEL_EIGHTY, budget.WarningLevel(80000))
	assert.Equal(t, MONTHLY_BUDGET_WARNING_LEVEL_EXCEEDED, budget.WarningLevel(100000))
	assert.Equal(t, MONTHLY_BUDGET_WARNING_LEVEL_EXCEEDED, budget.WarningLevel(120000))
}
