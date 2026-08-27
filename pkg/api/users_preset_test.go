package api

import (
	"reflect"
	"testing"
)

func TestGetPresetTransactionTagNames(t *testing.T) {
	tests := []struct {
		name     string
		language string
		expected []string
	}{
		{
			name:     "simplified chinese",
			language: "zh-Hans",
			expected: []string{"必要支出", "可选消费", "待报销", "工作", "家庭", "旅行"},
		},
		{
			name:     "traditional chinese locale variant",
			language: "zh-Hant-TW",
			expected: []string{"必要支出", "可選消費", "待報銷", "工作", "家庭", "旅行"},
		},
		{
			name:     "english fallback",
			language: "de",
			expected: []string{"Essential", "Optional", "Reimbursable", "Work", "Family", "Travel"},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if actual := getPresetTransactionTagNames(test.language); !reflect.DeepEqual(actual, test.expected) {
				t.Fatalf("unexpected preset tags: %#v", actual)
			}
		})
	}
}
