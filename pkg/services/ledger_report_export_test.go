package services

import (
	"encoding/csv"
	"encoding/json"
	"math"
	"strconv"
	"strings"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
)

func reportCSVFixture() *models.LedgerReportSnapshot {
	target := int64(1000)
	leg := func(currency string, amount int64) *models.LedgerReportLeg {
		return &models.LedgerReportLeg{AccountID: "9007199254740993", AccountName: " \t=SUM(1,2)", Currency: currency, Amount: amount}
	}
	return &models.LedgerReportSnapshot{SnapshotHash: strings.Repeat("ab", 32), DataComplete: true, MatchedCount: 4, Query: &models.NormalizedLedgerReportQuery{Version: 1, LedgerID: "0", Timezone: "UTC", StartDate: "2026-10-01", EndDate: "2026-10-31", Hidden: "exclude"}, Details: []*models.LedgerReportDetail{
		{ID: "9007199254740993", PhysicalIDs: []string{"9007199254740993"}, Type: 2, Time: 1791633600, Sequence: 1791633600000, Date: "2026-10-10", Source: leg("CNY", 10000), CategoryID: "101", CategoryName: "Income", Comment: "'literal, quoted\nsecond line", RecorderUID: "1", PayerUID: "2"},
		{ID: "2", PhysicalIDs: []string{"2"}, Type: 3, Source: leg("CNY", -500), CategoryID: "102", CategoryName: "\r\n=SUM(1,2)", Comment: "=HYPERLINK(\"https://example.invalid\",\"x\")", Tags: []models.LedgerReportResource{{ID: "501", Name: "=SUM(1,2)"}, {ID: "502", Name: "中文, \"引号\"\n换行"}}},
		{ID: "3", PhysicalIDs: []string{"3", "4"}, Type: 4, Source: leg("CNY", 700), Destination: leg("USD", 100), CategoryID: "103"},
		{ID: "5", PhysicalIDs: []string{"5"}, Type: 1, Source: leg("CNY", 1000), CategoryID: "0", Adjustment: -400, BalanceTarget: &target},
	}}
}

func TestLedgerReportCSVReparsePreservesIDsMoneyAndText(t *testing.T) {
	snapshot := reportCSVFixture()
	data, err := EncodeLedgerReportCSV(snapshot)
	require.NoError(t, err)
	records, err := csv.NewReader(strings.NewReader(string(data))).ReadAll()
	require.NoError(t, err)
	require.Len(t, records, 5)
	header := make(map[string]int)
	for i, name := range records[0] {
		header[name] = i
	}
	require.Len(t, header, 37)
	money := make(map[string]*models.LedgerReportMoney)
	ids := make([]string, 0, 4)
	for _, record := range records[1:] {
		require.Len(t, record, 37)
		field := func(name string) string { return record[header[name]] }
		ids = append(ids, field("id"))
		require.Equal(t, "minor", field("amount_unit"))
		require.Equal(t, snapshot.SnapshotHash, field("snapshot_hash"))
		require.Equal(t, "0", field("ledger_id"))
		kind, err := strconv.Atoi(field("type"))
		require.NoError(t, err)
		for _, side := range []string{"source", "destination"} {
			currency := field(side + "_currency")
			if currency == "" {
				continue
			}
			amount, err := strconv.ParseInt(field(side+"_amount_minor"), 10, 64)
			require.NoError(t, err)
			if money[currency] == nil {
				money[currency] = &models.LedgerReportMoney{Currency: currency}
			}
			m := money[currency]
			switch kind {
			case 1:
				delta, err := strconv.ParseInt(field("adjustment_minor"), 10, 64)
				require.NoError(t, err)
				m.Adjustment += delta
			case 2:
				m.Income += amount
			case 3:
				m.Expense += amount
			case 4:
				if side == "source" {
					m.TransferOut += amount
				} else {
					m.TransferIn += amount
				}
			}
		}
	}
	for _, m := range money {
		m.Net = m.Income - m.Expense
	}
	require.Equal(t, []string{"9007199254740993", "2", "3", "5"}, ids)
	require.Equal(t, &models.LedgerReportMoney{Currency: "CNY", Income: 10000, Expense: -500, Net: 10500, Adjustment: -400, TransferOut: 700}, money["CNY"])
	require.Equal(t, &models.LedgerReportMoney{Currency: "USD", TransferIn: 100}, money["USD"])
	require.Equal(t, "''literal, quoted\nsecond line", records[1][header["comment"]])
	require.Equal(t, "' \t=SUM(1,2)", records[1][header["source_account_name"]])
	// encoding/csv normalizes CRLF inside quoted fields when reading; the
	// emitted file still retains the original CRLF bytes and formula prefix.
	require.Equal(t, "'\n=SUM(1,2)", records[2][header["category_name"]])
	require.Contains(t, string(data), "\"'\r\n=SUM(1,2)\"")
	require.Equal(t, "1000", records[4][header["balance_target_minor"]])
	require.Equal(t, "-400", records[4][header["adjustment_minor"]])
	require.Equal(t, "-5.00", records[2][header["source_amount_major"]])
	require.Equal(t, "7.00", records[3][header["source_amount_major"]])
	require.Equal(t, "1.00", records[3][header["destination_amount_major"]])
	require.Equal(t, "-4.00", records[4][header["adjustment_major"]])
	require.Equal(t, "10.00", records[4][header["balance_target_major"]])
	var physical, tagNames []string
	require.NoError(t, json.Unmarshal([]byte(records[3][header["physical_ids_json"]]), &physical))
	require.Equal(t, []string{"3", "4"}, physical)
	require.NoError(t, json.Unmarshal([]byte(records[2][header["tag_names_json"]]), &tagNames))
	require.Equal(t, []string{"=SUM(1,2)", "中文, \"引号\"\n换行"}, tagNames)
}

func TestLedgerReportCSVMajorUnitIntegerBoundaries(t *testing.T) {
	for _, tt := range []struct {
		minor int64
		want  string
	}{
		{0, "0.00"}, {1, "0.01"}, {-1, "-0.01"}, {50, "0.50"}, {-500, "-5.00"}, {12345, "123.45"},
		{math.MaxInt64, "92233720368547758.07"}, {math.MinInt64, "-92233720368547758.08"},
	} {
		require.Equal(t, tt.want, reportCSVMajorAmount(tt.minor))
	}
}

func TestLedgerReportCSVFormulaPrefixesAndReversibleEscaping(t *testing.T) {
	for _, value := range []string{"=1+2", "+1", "-SUM(1,2)", "@SUM(1,2)", " \t=1", "\u200b=1", "\u200e+1", "\x00=1", "\tplain", "\rplain", "\nplain", "'literal"} {
		t.Run(strconv.Quote(value), func(t *testing.T) {
			protected := ReportCSVText(value)
			require.True(t, strings.HasPrefix(protected, "'"))
			require.Equal(t, value, strings.TrimPrefix(protected, "'"))
		})
	}
	for _, value := range []string{"", "中文", "sale - refund", "line one\nline two"} {
		require.Equal(t, value, ReportCSVText(value))
	}
}

func TestLedgerReportPagingGuardsSnapshotAndKeepsFullTotals(t *testing.T) {
	snapshot := reportCSVFixture()
	count := 2
	first, err := PageLedgerReport(snapshot, 0, &count, "")
	require.NoError(t, err)
	require.Len(t, first.Details, 2)
	require.Equal(t, 2, *first.NextOffset)
	require.False(t, first.DetailsComplete)
	require.True(t, first.DataComplete)
	last, err := PageLedgerReport(snapshot, 2, &count, first.SnapshotHash)
	require.NoError(t, err)
	require.Len(t, last.Details, 2)
	require.Nil(t, last.NextOffset)
	require.Equal(t, 4, last.MatchedCount)
	require.Len(t, snapshot.Details, 4)
	for _, tt := range []struct {
		offset, count int
		hash          string
		want          error
	}{{-1, 2, "", errs.ErrLedgerReportQueryInvalid}, {5, 2, snapshot.SnapshotHash, errs.ErrLedgerReportQueryInvalid}, {0, 0, "", errs.ErrLedgerReportQueryInvalid}, {0, 201, "", errs.ErrLedgerReportQueryInvalid}, {2, 2, "", errs.ErrLedgerReportSnapshotChanged}, {0, 2, strings.Repeat("00", 32), errs.ErrLedgerReportSnapshotChanged}, {0, 2, "invalid", errs.ErrLedgerReportQueryInvalid}} {
		_, err := PageLedgerReport(snapshot, tt.offset, &tt.count, tt.hash)
		require.ErrorIs(t, err, tt.want)
	}
	copy := *snapshot
	copy.MatchedCount = 0
	copy.Details = []*models.LedgerReportDetail{}
	empty, err := PageLedgerReport(&copy, 0, nil, "")
	require.NoError(t, err)
	require.Empty(t, empty.Details)
	require.True(t, empty.DetailsComplete)
	data, err := EncodeLedgerReportCSV(&copy)
	require.NoError(t, err)
	rows, err := csv.NewReader(strings.NewReader(string(data))).ReadAll()
	require.NoError(t, err)
	require.Len(t, rows, 1)
	copy.DataComplete = false
	_, err = EncodeLedgerReportCSV(&copy)
	require.ErrorIs(t, err, errs.ErrLedgerReportIncomplete)
}
