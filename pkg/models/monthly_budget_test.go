package models

import (
	"encoding/json"
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

func TestMonthlyBudgetCategoryAmountsResponse(t *testing.T) {
	budget := &MonthlyBudget{
		MonthlyBudgetId:     8,
		LedgerId:            9007199254740993,
		Currency:            "USD",
		Revision:            7,
		YearMonth:           202609,
		Amount:              500000,
		CategoryAmountsJson: `{"101":120000,"102":80000}`,
	}

	response := budget.ToMonthlyBudgetInfoResponse()
	assert.Equal(t, int64(9007199254740993), response.LedgerId)
	assert.Equal(t, "USD", response.Currency)
	assert.Equal(t, int64(7), response.Revision)
	raw, err := json.Marshal(response)
	assert.NoError(t, err)
	assert.Contains(t, string(raw), `"ledgerId":"9007199254740993"`)
	assert.Equal(t, int64(120000), response.CategoryAmounts["101"])
	assert.Equal(t, int64(80000), response.CategoryAmounts["102"])
}

func TestMonthlyBudgetRequestKeepsStringLedgerAndIntegerMinorUnits(t *testing.T) {
	var request MonthlyBudgetSetRequest
	assert.NoError(t, json.Unmarshal([]byte(`{"ledgerId":"9007199254740993","yearMonth":202610,"currency":"JPY","amount":9999999999999,"categoryAmounts":{"9007199254740995":123}}`), &request))
	assert.Equal(t, int64(9007199254740993), request.LedgerId)
	assert.Equal(t, MaximumMonthlyBudgetAmount, request.Amount)
	assert.Equal(t, int64(123), request.CategoryAmounts["9007199254740995"])
	assert.Error(t, json.Unmarshal([]byte(`{"ledgerId":"0","amount":1.23}`), &request))
	assert.Error(t, json.Unmarshal([]byte(`{"ledgerId":9007199254740993,"amount":123}`), &request))
}
