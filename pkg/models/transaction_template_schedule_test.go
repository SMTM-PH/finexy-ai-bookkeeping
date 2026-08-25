package models

import (
	"testing"
	"time"
)

func TestNextScheduledUnixTimeMonthlyLastDay(t *testing.T) {
	template := &TransactionTemplate{
		TemplateType: TRANSACTION_TEMPLATE_TYPE_SCHEDULE, ScheduledFrequencyType: TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY,
		ScheduledFrequency: "-1", ScheduledAt: 16 * 60, ScheduledTimezoneUtcOffset: 8 * 60,
	}
	next := template.NextScheduledUnixTime(time.Date(2026, time.February, 10, 0, 0, 0, 0, time.UTC))
	expected := time.Date(2026, time.February, 28, 0, 0, 0, 0, time.FixedZone("UTC+8", 8*60*60)).Unix()
	if next == nil || *next != expected {
		t.Fatalf("expected %d, got %v", expected, next)
	}
}

func TestNextScheduledUnixTimeYearlyAndEndDate(t *testing.T) {
	end := time.Date(2026, time.December, 31, 23, 59, 59, 0, time.UTC).Unix()
	template := &TransactionTemplate{
		TemplateType: TRANSACTION_TEMPLATE_TYPE_SCHEDULE, ScheduledFrequencyType: TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY,
		ScheduledFrequency: "315", ScheduledAt: 0, ScheduledEndTime: &end,
	}
	next := template.NextScheduledUnixTime(time.Date(2026, time.March, 16, 0, 0, 0, 0, time.UTC))
	if next != nil {
		t.Fatalf("expected no occurrence after the configured end date, got %d", *next)
	}
}

func TestNextScheduledUnixTimeEveryNDays(t *testing.T) {
	start := time.Date(2026, time.August, 1, 0, 0, 0, 0, time.UTC).Unix()
	template := &TransactionTemplate{
		TemplateType: TRANSACTION_TEMPLATE_TYPE_SCHEDULE, ScheduledFrequencyType: TRANSACTION_SCHEDULE_FREQUENCY_TYPE_EVERY_N_DAYS,
		ScheduledFrequency: "3", ScheduledAt: 0, ScheduledStartTime: &start,
	}
	next := template.NextScheduledUnixTime(time.Date(2026, time.August, 2, 8, 0, 0, 0, time.UTC))
	expected := time.Date(2026, time.August, 4, 0, 0, 0, 0, time.UTC).Unix()
	if next == nil || *next != expected {
		t.Fatalf("expected %d, got %v", expected, next)
	}
}
