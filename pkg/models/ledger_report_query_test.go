package models

import (
	"encoding/json"
	"errors"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

func reportQueryFixture() *LedgerReportQuery {
	id := "0"
	return &LedgerReportQuery{LedgerID: &id, StartDate: "2026-10-01", EndDate: "2026-10-31", Timezone: "Asia/Shanghai"}
}

func TestLedgerReportCalendarFixtures(t *testing.T) {
	var fixtures []struct {
		Name, Timezone, StartDate, EndDate string
		UTCRanges                          [][2]string
	}
	data, err := os.ReadFile("testdata/ledger_report_dates.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &fixtures); err != nil {
		t.Fatal(err)
	}
	for _, fixture := range fixtures {
		t.Run(fixture.Name, func(t *testing.T) {
			q := reportQueryFixture()
			q.StartDate, q.EndDate, q.Timezone = fixture.StartDate, fixture.EndDate, fixture.Timezone
			n, err := NormalizeLedgerReportQuery(q)
			if err != nil {
				t.Fatal(err)
			}
			want := make([]LedgerReportTimeRange, 0, len(fixture.UTCRanges))
			for _, pair := range fixture.UTCRanges {
				start, err := time.Parse(time.RFC3339, pair[0])
				if err != nil {
					t.Fatal(err)
				}
				end, err := time.Parse(time.RFC3339, pair[1])
				if err != nil {
					t.Fatal(err)
				}
				want = append(want, LedgerReportTimeRange{Start: start.Unix(), EndExclusive: end.Unix()})
			}
			if !reflect.DeepEqual(want, n.UTCRanges) {
				t.Fatalf("ranges = %+v, want %+v", n.UTCRanges, want)
			}
			loc, err := time.LoadLocation(q.Timezone)
			if err != nil {
				t.Fatal(err)
			}
			// Independent oracle: compare local calendar labels, without constructing
			// a local midnight or using the normalizer's offset-segment algorithm.
			start, _ := time.Parse("2006-01-02", q.StartDate)
			end, _ := time.Parse("2006-01-02", q.EndDate)
			check := func(instant time.Time) {
				date := instant.In(loc).Format("2006-01-02")
				want := date >= q.StartDate && date <= q.EndDate
				if got := n.ContainsUnixTime(instant.Unix()); got != want {
					t.Fatalf("membership at %s local=%s: got %v want %v", instant, instant.In(loc), got, want)
				}
			}
			for instant := start.Add(-48 * time.Hour); instant.Before(end.Add(72 * time.Hour)); instant = instant.Add(time.Minute) {
				check(instant)
			}
			for _, interval := range n.UTCRanges {
				for _, boundary := range []int64{interval.Start, interval.EndExclusive} {
					for delta := int64(-1); delta <= 1; delta++ {
						check(time.Unix(boundary+delta, 0))
					}
				}
			}
		})
	}
}

func TestLedgerReportQueryCanonicalAndDetached(t *testing.T) {
	q := reportQueryFixture()
	large := "9007199254740993"
	q.LedgerID = &large
	q.Types = []int32{4, 2, 2, 1}
	q.Accounts = &LedgerReportIDFilter{IDs: []string{"10", large, "2", "10"}, Mode: "include", IncludeDescendants: true}
	q.Categories = &LedgerReportIDFilter{IDs: []string{"4", "3"}, Mode: "exclude"}
	q.Tags = []LedgerReportTagFilter{{IDs: []string{"3", "2", "3"}, Mode: "all"}, {IDs: []string{"2"}, Mode: "notAny"}, {IDs: []string{"2", "3"}, Mode: "all"}}
	q.Currencies = []string{"USD", "CNY", "USD"}
	n, err := NormalizeLedgerReportQuery(q)
	if err != nil {
		t.Fatal(err)
	}
	if n.LedgerID != large || n.Hidden != "exclude" || n.Version != 1 || !reflect.DeepEqual(n.Types, []int32{1, 2, 4}) || !reflect.DeepEqual(n.Accounts.IDs, []string{"2", "10", large}) || !reflect.DeepEqual(n.Currencies, []string{"CNY", "USD"}) || len(n.Tags) != 2 {
		t.Fatalf("unexpected canonical query: %+v", n)
	}
	before, err := json.Marshal(n)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(before), `"ledgerId":"9007199254740993"`) || !strings.Contains(string(before), `"types":[1,2,4]`) || !strings.Contains(string(before), `"start":"`) || !strings.Contains(string(before), `"ids":["2","10","9007199254740993"]`) {
		t.Fatalf("ID/time precision lost: %s", before)
	}
	// Permutations and duplicate choices must generate identical canonical bytes.
	q.Types = []int32{2, 4, 1}
	q.Accounts.IDs = []string{large, "2", "10"}
	q.Categories.IDs = []string{"3", "4"}
	q.Tags = []LedgerReportTagFilter{{IDs: []string{"2"}, Mode: "notAny"}, {IDs: []string{"2", "3"}, Mode: "all"}}
	q.Currencies = []string{"CNY", "USD"}
	reordered, err := NormalizeLedgerReportQuery(q)
	if err != nil {
		t.Fatal(err)
	}
	after, _ := json.Marshal(reordered)
	if string(before) != string(after) {
		t.Fatalf("equivalent selections produce different canonical queries\n%s\n%s", before, after)
	}
	// Input mutation must not alter a captured query while a request is running.
	large = "7"
	q.Types[0] = 3
	q.Accounts.IDs[0] = "99"
	q.Categories.IDs[0] = "99"
	q.Tags[0].IDs[0] = "99"
	q.Currencies[0] = "EUR"
	detached, _ := json.Marshal(reordered)
	if string(after) != string(detached) {
		t.Fatal("normalized query aliases request memory")
	}
}

func TestLedgerReportQueryRejectsAmbiguousOrUnboundedInput(t *testing.T) {
	tests := []struct {
		name, field string
		mutate      func(*LedgerReportQuery)
	}{
		{"missing-ledger", "ledgerId", func(q *LedgerReportQuery) { q.LedgerID = nil }},
		{"empty-ledger", "ledgerId", func(q *LedgerReportQuery) { *q.LedgerID = "" }},
		{"negative-ledger", "ledgerId", func(q *LedgerReportQuery) { *q.LedgerID = "-1" }},
		{"float-ledger", "ledgerId", func(q *LedgerReportQuery) { *q.LedgerID = "1.0" }},
		{"padded-ledger", "ledgerId", func(q *LedgerReportQuery) { *q.LedgerID = "01" }},
		{"signed-ledger", "ledgerId", func(q *LedgerReportQuery) { *q.LedgerID = "+1" }},
		{"overflow-ledger", "ledgerId", func(q *LedgerReportQuery) { *q.LedgerID = "9223372036854775808" }},
		{"nonleap-day", "startDate", func(q *LedgerReportQuery) { q.StartDate = "2025-02-29" }},
		{"unpadded-date", "startDate", func(q *LedgerReportQuery) { q.StartDate = "2026-1-01" }},
		{"pre-epoch", "startDate", func(q *LedgerReportQuery) { q.StartDate = "1969-01-01" }},
		{"out-of-bounds-year", "endDate", func(q *LedgerReportQuery) { q.EndDate = "9999-01-01" }},
		{"reversed", "endDate", func(q *LedgerReportQuery) { q.EndDate = "2026-09-30" }},
		{"too-many-days", "endDate", func(q *LedgerReportQuery) { q.EndDate = "2037-01-01" }},
		{"no-timezone", "timezone", func(q *LedgerReportQuery) { q.Timezone = "" }},
		{"machine-timezone", "timezone", func(q *LedgerReportQuery) { q.Timezone = "Local" }},
		{"unknown-timezone", "timezone", func(q *LedgerReportQuery) { q.Timezone = "Invalid/Zone" }},
		{"timezone-whitespace", "timezone", func(q *LedgerReportQuery) { q.Timezone = " UTC" }},
		{"empty-types", "types", func(q *LedgerReportQuery) { q.Types = []int32{} }},
		{"all-as-type", "types", func(q *LedgerReportQuery) { q.Types = []int32{0} }},
		{"database-type", "types", func(q *LedgerReportQuery) { q.Types = []int32{5} }},
		{"empty-accounts", "accounts", func(q *LedgerReportQuery) { q.Accounts = &LedgerReportIDFilter{Mode: "include"} }},
		{"zero-account", "accounts", func(q *LedgerReportQuery) { q.Accounts = &LedgerReportIDFilter{Mode: "include", IDs: []string{"0"}} }},
		{"missing-mode", "accounts", func(q *LedgerReportQuery) { q.Accounts = &LedgerReportIDFilter{IDs: []string{"1"}} }},
		{"id-limit", "accounts", func(q *LedgerReportQuery) {
			q.Accounts = &LedgerReportIDFilter{Mode: "include", IDs: make([]string, MaximumLedgerReportIDs+1)}
		}},
		{"empty-categories", "categories", func(q *LedgerReportQuery) { q.Categories = &LedgerReportIDFilter{Mode: "exclude", IDs: []string{}} }},
		{"empty-tags", "tags", func(q *LedgerReportQuery) { q.Tags = []LedgerReportTagFilter{} }},
		{"empty-tag-ids", "tags", func(q *LedgerReportQuery) { q.Tags = []LedgerReportTagFilter{{Mode: "any"}} }},
		{"unknown-tag-mode", "tags", func(q *LedgerReportQuery) { q.Tags = []LedgerReportTagFilter{{Mode: "ANY", IDs: []string{"1"}}} }},
		{"none-with-ids", "tags", func(q *LedgerReportQuery) { q.Tags = []LedgerReportTagFilter{{Mode: "none", IDs: []string{"1"}}} }},
		{"none-with-another-clause", "tags", func(q *LedgerReportQuery) {
			q.Tags = []LedgerReportTagFilter{{Mode: "none"}, {Mode: "any", IDs: []string{"1"}}}
		}},
		{"tag-clause-limit", "tags", func(q *LedgerReportQuery) { q.Tags = make([]LedgerReportTagFilter, MaximumLedgerReportTags+1) }},
		{"empty-currencies", "currencies", func(q *LedgerReportQuery) { q.Currencies = []string{} }},
		{"lowercase-currency", "currencies", func(q *LedgerReportQuery) { q.Currencies = []string{"cny"} }},
		{"unicode-currency", "currencies", func(q *LedgerReportQuery) { q.Currencies = []string{"人民币"} }},
		{"currency-injection", "currencies", func(q *LedgerReportQuery) { q.Currencies = []string{"=1+"} }},
		{"currency-limit", "currencies", func(q *LedgerReportQuery) { q.Currencies = make([]string, 257) }},
		{"unknown-hidden-policy", "hidden", func(q *LedgerReportQuery) { q.Hidden = "masked" }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			q := reportQueryFixture()
			tt.mutate(q)
			_, err := NormalizeLedgerReportQuery(q)
			var invalid *LedgerReportQueryError
			if !errors.As(err, &invalid) || invalid.Field != tt.field {
				t.Fatalf("error = %v, want field %s", err, tt.field)
			}
		})
	}
	if _, err := NormalizeLedgerReportQuery(nil); err == nil {
		t.Fatal("nil request accepted")
	}
}

func TestLedgerReportQueryJSONRejectsNumericIDs(t *testing.T) {
	for _, input := range []string{
		`{"ledgerId":9007199254740993}`,
		`{"ledgerId":"0","accounts":{"mode":"include","ids":[9007199254740993]}}`,
		`{"ledgerId":"0","categories":{"mode":"include","ids":[1]}}`,
		`{"ledgerId":"0","tags":[{"mode":"any","ids":[1]}]}`,
		`{"ledgerId":"0","types":"AQIDBA=="}`,
	} {
		var q LedgerReportQuery
		if err := json.Unmarshal([]byte(input), &q); err == nil {
			t.Fatalf("precision-unsafe JSON accepted: %s", input)
		}
	}
}

func TestLedgerReportQueryDefaultsAndExplicitChoices(t *testing.T) {
	for _, mode := range []string{"any", "all", "notAny", "notAll", "none"} {
		t.Run(mode, func(t *testing.T) {
			q := reportQueryFixture()
			q.Hidden = "include"
			q.Tags = []LedgerReportTagFilter{{Mode: mode}}
			if mode != "none" {
				q.Tags[0].IDs = []string{"9223372036854775807"}
			}
			n, err := NormalizeLedgerReportQuery(q)
			if err != nil {
				t.Fatal(err)
			}
			if n.Hidden != "include" || !reflect.DeepEqual(n.Types, []int32{1, 2, 3, 4}) || n.Accounts != nil || n.Categories != nil || n.Currencies != nil || n.Tags[0].Mode != mode {
				t.Fatalf("explicit selection/defaults lost: %+v", n)
			}
		})
	}
}

func TestLedgerReportQueryUncategorizedIsExplicitCanonicalCategoryOnly(t *testing.T) {
	for _, mode := range []string{"include", "exclude"} {
		q := reportQueryFixture()
		q.Categories = &LedgerReportIDFilter{IDs: []string{"10", "0", "2", "0"}, Mode: mode, IncludeDescendants: true}
		normalized, err := NormalizeLedgerReportQuery(q)
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(normalized.Categories.IDs, []string{"0", "2", "10"}) || normalized.Categories.Mode != mode || !normalized.Categories.IncludeDescendants {
			t.Fatalf("uncategorized selector lost during normalization: %+v", normalized.Categories)
		}
		q.Categories.IDs[1] = "99"
		if normalized.Categories.IDs[0] != "0" {
			t.Fatal("captured uncategorized selection aliases mutable input")
		}
	}
	for _, value := range []string{"00", "+0", "-0", "0.0", " 0", "-1", "9223372036854775808"} {
		q := reportQueryFixture()
		q.Categories = &LedgerReportIDFilter{Mode: "include", IDs: []string{value}}
		if _, err := NormalizeLedgerReportQuery(q); err == nil {
			t.Fatalf("ambiguous category ID %q accepted", value)
		}
	}
	for _, field := range []string{"accounts", "tags"} {
		q := reportQueryFixture()
		if field == "accounts" {
			q.Accounts = &LedgerReportIDFilter{Mode: "include", IDs: []string{"0"}}
		} else {
			q.Tags = []LedgerReportTagFilter{{Mode: "any", IDs: []string{"0"}}}
		}
		_, err := NormalizeLedgerReportQuery(q)
		var invalid *LedgerReportQueryError
		if !errors.As(err, &invalid) || invalid.Field != field {
			t.Fatalf("zero became valid outside categories: %s: %v", field, err)
		}
	}
	var numeric LedgerReportQuery
	if err := json.Unmarshal([]byte(`{"categories":{"mode":"include","ids":[0]}}`), &numeric); err == nil {
		t.Fatal("numeric category zero bypassed the string-ID contract")
	}
}

func TestLedgerReportQueryRangeLimitAndFutureZoneRules(t *testing.T) {
	for _, start := range []string{"1970-01-01", "2040-01-01", "9988-01-01"} {
		t.Run(start, func(t *testing.T) {
			q := reportQueryFixture()
			q.StartDate = start
			day, _ := time.Parse("2006-01-02", start)
			q.EndDate = day.AddDate(0, 0, MaximumLedgerReportDays-1).Format("2006-01-02")
			q.Timezone = "America/New_York"
			n, err := NormalizeLedgerReportQuery(q)
			if err != nil {
				t.Fatal(err)
			}
			if len(n.UTCRanges) != 1 || !n.ContainsUnixTime(day.Add(48*time.Hour).Unix()) {
				t.Fatalf("long range not merged: %+v", n.UTCRanges)
			}
			q.EndDate = day.AddDate(0, 0, MaximumLedgerReportDays).Format("2006-01-02")
			if _, err := NormalizeLedgerReportQuery(q); err == nil {
				t.Fatal("one day above range limit accepted")
			}
		})
	}
}
