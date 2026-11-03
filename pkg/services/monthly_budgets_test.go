package services

import (
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
)

func TestIsValidYearMonth(t *testing.T) {
	tests := []struct {
		value int32
		valid bool
	}{
		{202601, true}, {202612, true}, {202600, false}, {202613, false}, {199912, false},
	}

	for _, test := range tests {
		if actual := isValidYearMonth(test.value); actual != test.valid {
			t.Fatalf("isValidYearMonth(%d) = %t, want %t", test.value, actual, test.valid)
		}
	}
}

func TestMonthlyBudgetStoredValueRejectsCorruptionAndUnsafeIntegers(t *testing.T) {
	base := models.MonthlyBudget{Currency: "CNY", Amount: 123, Revision: 1, CategoryAmountsJson: "{}"}
	require.NoError(t, validateStoredMonthlyBudget(&base))
	for _, amount := range []int64{-1, 0, models.MaximumMonthlyBudgetAmount + 1} {
		row := base
		row.Amount = amount
		require.ErrorIs(t, validateStoredMonthlyBudget(&row), errs.ErrMonthlyBudgetAmountInvalid)
	}
	for _, currency := range []string{"", "cny", "ZZZ", "***"} {
		row := base
		row.Currency = currency
		require.ErrorIs(t, validateStoredMonthlyBudget(&row), errs.ErrMonthlyBudgetCurrencyInvalid)
	}
	for _, amounts := range []string{"", "null", "[]", `{"1002":1.23}`, `{"01002":123}`, `{"wrong":123}`} {
		row := base
		row.CategoryAmountsJson = amounts
		require.ErrorIs(t, validateStoredMonthlyBudget(&row), errs.ErrMonthlyBudgetCategoryInvalid)
	}
	for _, amounts := range []string{`{"1002":0}`, `{"1002":-1}`, `{"1002":10000000000000}`} {
		row := base
		row.CategoryAmountsJson = amounts
		require.ErrorIs(t, validateStoredMonthlyBudget(&row), errs.ErrMonthlyBudgetAmountInvalid)
	}
}
