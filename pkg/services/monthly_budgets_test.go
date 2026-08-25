package services

import "testing"

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
