package models

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata" // Reports also need IANA rules in minimal offline images.
)

const (
	LedgerReportQueryVersion = 1
	MaximumLedgerReportDays  = 3660
	MaximumLedgerReportIDs   = 1000
	MaximumLedgerReportTags  = 20
)

// LedgerReportQuery is a read-only report's input, not an authorization grant.
// An explicit string ledger ID is required, including "0" for the default ledger.
// Resource ownership and membership must be checked in the reader's transaction.
type LedgerReportQuery struct {
	LedgerID   *string                 `json:"ledgerId"`
	StartDate  string                  `json:"startDate"`
	EndDate    string                  `json:"endDate"`
	Timezone   string                  `json:"timezone"`
	Types      []int32                 `json:"types"`
	Accounts   *LedgerReportIDFilter   `json:"accounts"`
	Categories *LedgerReportIDFilter   `json:"categories"`
	Tags       []LedgerReportTagFilter `json:"tags"`
	Currencies []string                `json:"currencies"`
	Hidden     string                  `json:"hidden"`
}

// LedgerReportIDFilter uses IDs, never names. Descendant expansion is deferred
// until the reader has a complete, authorized metadata snapshot.
// Categories alone accept "0" as the exact uncategorized-row selector; it is
// not a category resource or a parent of every root category.
type LedgerReportIDFilter struct {
	IDs                []string `json:"ids"`
	Mode               string   `json:"mode"` // include or exclude
	IncludeDescendants bool     `json:"includeDescendants"`
}

// LedgerReportTagFilter clauses are ANDed. Modes retain the existing tag query
// semantics: any, all, notAny, notAll, or none (the only clause, without IDs).
type LedgerReportTagFilter struct {
	IDs  []string `json:"ids"`
	Mode string   `json:"mode"`
}

// LedgerReportTimeRange is half-open and uses Unix seconds, not database
// transaction sequence IDs or Room milliseconds. Strings preserve API precision.
type LedgerReportTimeRange struct {
	Start        int64 `json:"start,string"`
	EndExclusive int64 `json:"endExclusive,string"`
}

// NormalizedLedgerReportQuery has deterministic ordering for cache/snapshot keys.
// UTC ranges can be empty for a skipped civil date, or disjoint after a date
// rollback. Neither case can be represented by blindly adding 24 hours.
type NormalizedLedgerReportQuery struct {
	Version    int                     `json:"version"`
	LedgerID   string                  `json:"ledgerId"`
	StartDate  string                  `json:"startDate"`
	EndDate    string                  `json:"endDate"`
	Timezone   string                  `json:"timezone"`
	Types      []int32                 `json:"types"`
	Accounts   *LedgerReportIDFilter   `json:"accounts"`
	Categories *LedgerReportIDFilter   `json:"categories"`
	Tags       []LedgerReportTagFilter `json:"tags"`
	Currencies []string                `json:"currencies"`
	Hidden     string                  `json:"hidden"`
	UTCRanges  []LedgerReportTimeRange `json:"utcRanges"`
}

// LedgerReportQueryError identifies invalid input without echoing financial data.
type LedgerReportQueryError struct {
	Field  string
	Reason string
}

func (e *LedgerReportQueryError) Error() string {
	return fmt.Sprintf("invalid report query: %s: %s", e.Field, e.Reason)
}

func invalidReportQuery(field, reason string) error {
	return &LedgerReportQueryError{Field: field, Reason: reason}
}

// NormalizeLedgerReportQuery validates syntax and bounds only. In particular,
// accepting a tag ID does not grant access to the owner's private tag namespace.
func NormalizeLedgerReportQuery(q *LedgerReportQuery) (*NormalizedLedgerReportQuery, error) {
	if q == nil || q.LedgerID == nil {
		return nil, invalidReportQuery("ledgerId", "explicit string ID required")
	}
	if _, err := parseLedgerReportID(*q.LedgerID, true); err != nil {
		return nil, invalidReportQuery("ledgerId", "canonical nonnegative int64 required")
	}
	start, err := reportCalendarDate(q.StartDate)
	if err != nil {
		return nil, invalidReportQuery("startDate", "YYYY-MM-DD in years 1970 through 9998 required")
	}
	end, err := reportCalendarDate(q.EndDate)
	if err != nil || end.Before(start) {
		return nil, invalidReportQuery("endDate", "valid date on or after startDate required")
	}
	wallEnd := end.AddDate(0, 0, 1)
	if wallEnd.After(start.AddDate(0, 0, MaximumLedgerReportDays)) {
		return nil, invalidReportQuery("endDate", "date range exceeds report limit")
	}
	// LoadLocation accepts Local and an empty name; reports must never depend on
	// the service machine's timezone. UTC is an explicit supported IANA name.
	if q.Timezone == "" || q.Timezone == "Local" || strings.TrimSpace(q.Timezone) != q.Timezone {
		return nil, invalidReportQuery("timezone", "explicit IANA timezone required")
	}
	loc, err := time.LoadLocation(q.Timezone)
	if err != nil {
		return nil, invalidReportQuery("timezone", "unknown IANA timezone")
	}
	n := &NormalizedLedgerReportQuery{
		Version: LedgerReportQueryVersion, LedgerID: *q.LedgerID,
		StartDate: q.StartDate, EndDate: q.EndDate, Timezone: q.Timezone,
		Hidden: q.Hidden,
	}
	if n.Hidden == "" {
		n.Hidden = "exclude"
	}
	if n.Hidden != "include" && n.Hidden != "exclude" {
		return nil, invalidReportQuery("hidden", "include or exclude required")
	}
	if q.Types == nil {
		n.Types = []int32{1, 2, 3, 4}
	} else {
		if len(q.Types) == 0 || len(q.Types) > 4 {
			return nil, invalidReportQuery("types", "one to four transaction types required")
		}
		seen := make(map[int32]bool)
		for _, kind := range q.Types {
			if kind < int32(TRANSACTION_TYPE_MODIFY_BALANCE) || kind > int32(TRANSACTION_TYPE_TRANSFER) {
				return nil, invalidReportQuery("types", "unsupported transaction type")
			}
			if !seen[kind] {
				n.Types = append(n.Types, kind)
				seen[kind] = true
			}
		}
		sort.Slice(n.Types, func(i, j int) bool { return n.Types[i] < n.Types[j] })
	}
	if n.Accounts, err = normalizeReportIDFilter(q.Accounts, "accounts", false); err != nil {
		return nil, err
	}
	if n.Categories, err = normalizeReportIDFilter(q.Categories, "categories", true); err != nil {
		return nil, err
	}
	if q.Tags != nil {
		if len(q.Tags) == 0 || len(q.Tags) > MaximumLedgerReportTags {
			return nil, invalidReportQuery("tags", "nonempty bounded clause list required")
		}
		seen := make(map[string]bool)
		for _, clause := range q.Tags {
			normalized := LedgerReportTagFilter{Mode: clause.Mode}
			switch clause.Mode {
			case "none":
				if len(q.Tags) != 1 || len(clause.IDs) != 0 {
					return nil, invalidReportQuery("tags", "none must be the only clause and have no IDs")
				}
			case "any", "all", "notAny", "notAll":
				normalized.IDs, err = normalizeReportIDs(clause.IDs, "tags", false)
				if err != nil {
					return nil, err
				}
			default:
				return nil, invalidReportQuery("tags", "unsupported match mode")
			}
			key := normalized.Mode + ":" + strings.Join(normalized.IDs, ",")
			if !seen[key] {
				n.Tags = append(n.Tags, normalized)
				seen[key] = true
			}
		}
		sort.Slice(n.Tags, func(i, j int) bool {
			a, b := n.Tags[i], n.Tags[j]
			return a.Mode+":"+strings.Join(a.IDs, ",") < b.Mode+":"+strings.Join(b.IDs, ",")
		})
	}
	if q.Currencies != nil {
		if len(q.Currencies) == 0 || len(q.Currencies) > 256 {
			return nil, invalidReportQuery("currencies", "nonempty bounded currency list required")
		}
		seen := make(map[string]bool)
		for _, currency := range q.Currencies {
			if len(currency) != 3 || currency[0] < 'A' || currency[0] > 'Z' || currency[1] < 'A' || currency[1] > 'Z' || currency[2] < 'A' || currency[2] > 'Z' {
				return nil, invalidReportQuery("currencies", "three uppercase ASCII letters required")
			}
			if !seen[currency] {
				n.Currencies = append(n.Currencies, currency)
				seen[currency] = true
			}
		}
		sort.Strings(n.Currencies)
	}
	n.UTCRanges, err = reportCivilTimeRanges(start, wallEnd, loc)
	if err != nil {
		return nil, err
	}
	return n, nil
}

func reportCalendarDate(value string) (time.Time, error) {
	date, err := time.Parse("2006-01-02", value)
	if err != nil || len(value) != 10 || date.Format("2006-01-02") != value || date.Year() < 1970 || date.Year() > 9998 {
		return time.Time{}, invalidReportQuery("date", "invalid calendar date")
	}
	return date, nil
}

func parseLedgerReportID(value string, allowZero bool) (int64, error) {
	id, err := strconv.ParseInt(value, 10, 64)
	if err != nil || id < 0 || (!allowZero && id == 0) || strconv.FormatInt(id, 10) != value {
		return 0, invalidReportQuery("id", "canonical positive int64 required")
	}
	return id, nil
}

func normalizeReportIDs(ids []string, field string, allowZero bool) ([]string, error) {
	if len(ids) == 0 || len(ids) > MaximumLedgerReportIDs {
		return nil, invalidReportQuery(field, "nonempty bounded ID list required")
	}
	values := make([]int64, 0, len(ids))
	seen := make(map[int64]bool)
	for _, value := range ids {
		id, err := parseLedgerReportID(value, allowZero)
		if err != nil {
			if allowZero {
				return nil, invalidReportQuery(field, "canonical nonnegative int64 IDs required")
			}
			return nil, invalidReportQuery(field, "canonical positive int64 IDs required")
		}
		if !seen[id] {
			values = append(values, id)
			seen[id] = true
		}
	}
	sort.Slice(values, func(i, j int) bool { return values[i] < values[j] })
	result := make([]string, len(values))
	for i, value := range values {
		result[i] = strconv.FormatInt(value, 10)
	}
	return result, nil
}

func normalizeReportIDFilter(filter *LedgerReportIDFilter, field string, allowZero bool) (*LedgerReportIDFilter, error) {
	if filter == nil {
		return nil, nil
	}
	if filter.Mode != "include" && filter.Mode != "exclude" {
		return nil, invalidReportQuery(field, "include or exclude required")
	}
	ids, err := normalizeReportIDs(filter.IDs, field, allowZero)
	if err != nil {
		return nil, err
	}
	return &LedgerReportIDFilter{IDs: ids, Mode: filter.Mode, IncludeDescendants: filter.IncludeDescendants}, nil
}

// reportCivilTimeRanges intersects the wall-date interval with every constant
// UTC-offset segment. This handles missing midnights, skipped days, and rollbacks
// across midnight, for which ParseInLocation(midnight) can select a wrong bound.
func reportCivilTimeRanges(wallStart, wallEnd time.Time, loc *time.Location) ([]LedgerReportTimeRange, error) {
	// IANA offsets fit within 24 hours. A 48-hour envelope also covers date-line
	// changes; it is not used as a civil-day duration.
	cursor := wallStart.Add(-48 * time.Hour)
	limit := wallEnd.Add(48 * time.Hour)
	ranges := make([]LedgerReportTimeRange, 0)
	for cursor.Before(limit) {
		local := cursor.In(loc)
		_, offset := local.Zone()
		segmentEnd, err := reportZoneSegmentEnd(cursor, limit, loc)
		if err != nil {
			return nil, err
		}
		if segmentEnd.IsZero() || segmentEnd.After(limit) {
			segmentEnd = limit
		}
		start := wallStart.Add(-time.Duration(offset) * time.Second)
		end := wallEnd.Add(-time.Duration(offset) * time.Second)
		if start.Before(cursor) {
			start = cursor
		}
		if end.After(segmentEnd) {
			end = segmentEnd
		}
		if start.Before(end) {
			ranges = append(ranges, LedgerReportTimeRange{Start: start.Unix(), EndExclusive: end.Unix()})
		}
		cursor = segmentEnd
	}
	// Segments were visited in UTC order, so only adjacent merging is needed.
	merged := make([]LedgerReportTimeRange, 0, len(ranges))
	for _, next := range ranges {
		if len(merged) > 0 && next.Start <= merged[len(merged)-1].EndExclusive {
			last := &merged[len(merged)-1]
			if next.EndExclusive > last.EndExclusive {
				last.EndExclusive = next.EndExclusive
			}
		} else {
			merged = append(merged, next)
		}
	}
	return merged, nil
}

func reportZoneSegmentEnd(cursor, limit time.Time, loc *time.Location) (time.Time, error) {
	_, end := cursor.In(loc).ZoneBounds()
	if end.IsZero() || end.After(cursor) {
		return end, nil
	}
	// Go's POSIX-rule extrapolation can report the end of a 365-day year on
	// December 31 of a future leap year (time/zoneinfo.go: tzset). Iterating that
	// nonadvancing bound hangs. Inspect every second until the offset changes
	// or bounds advance again; do not skip a possible transition. Recovery is
	// bounded to one day and fails closed if timezone bounds remain unusable.
	_, offset := cursor.In(loc).Zone()
	for seconds := 1; seconds <= 86400; seconds++ {
		probe := cursor.Add(time.Duration(seconds) * time.Second)
		if !probe.Before(limit) {
			return limit, nil
		}
		local := probe.In(loc)
		_, nextOffset := local.Zone()
		_, nextEnd := local.ZoneBounds()
		if nextOffset != offset || nextEnd.IsZero() || nextEnd.After(probe) {
			return probe, nil
		}
	}
	return time.Time{}, invalidReportQuery("timezone", "nonadvancing timezone bounds")
}

// ContainsUnixTime is shared by snapshot validation and tests. SQL readers must
// OR these same half-open ranges, without replacing them with their envelope.
func (q *NormalizedLedgerReportQuery) ContainsUnixTime(unixTime int64) bool {
	i := sort.Search(len(q.UTCRanges), func(i int) bool { return q.UTCRanges[i].EndExclusive > unixTime })
	return i < len(q.UTCRanges) && q.UTCRanges[i].Start <= unixTime
}
