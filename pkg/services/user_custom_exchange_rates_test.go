package services

import (
	"errors"
	"testing"
)

func TestNextUnusedExchangeRateDeletedTimeHandlesRapidWrites(t *testing.T) {
	used := map[int64]bool{100: true, 101: true}
	actual, err := nextUnusedExchangeRateDeletedTime(100, func(candidate int64) (bool, error) {
		return used[candidate], nil
	})
	if err != nil || actual != 102 {
		t.Fatalf("expected 102 without error, got %d, %v", actual, err)
	}
}

func TestNextUnusedExchangeRateDeletedTimeReturnsLookupError(t *testing.T) {
	expected := errors.New("lookup failed")
	_, err := nextUnusedExchangeRateDeletedTime(100, func(int64) (bool, error) { return false, expected })
	if !errors.Is(err, expected) {
		t.Fatalf("expected lookup error, got %v", err)
	}
}
