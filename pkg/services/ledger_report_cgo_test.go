//go:build cgo

package services

import (
	"encoding/json"
	"math"
	"strconv"
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
	"xorm.io/xorm"
)

func setupLedgerReport(t *testing.T) (*models.LedgerReportQuery, *xorm.Session) {
	t.Helper()
	initFamilyTestEnvironment(t)
	require.NoError(t, Transactions.ServiceUsingDB.container.UserDataStore.SyncStructs(new(models.TransactionTag)))
	db := Transactions.UserDataDB(100)
	sess := db.NewSession(nil)
	t.Cleanup(func() { _ = sess.Close() })
	_, err := Transactions.UserDB().NewSession(nil).Insert(
		&models.User{Uid: 100, Username: "synthetic-report-owner", Email: "report-owner@example.invalid", DefaultCurrency: "CNY"},
		&models.User{Uid: 200, Username: "synthetic-report-admin", Email: "report-admin@example.invalid", DefaultCurrency: "USD"},
		&models.User{Uid: 300, Username: "synthetic-report-member", Email: "report-member@example.invalid", DefaultCurrency: "EUR"},
		&models.User{Uid: 400, Username: "synthetic-report-viewer", Email: "report-viewer@example.invalid", DefaultCurrency: "JPY"})
	require.NoError(t, err)
	_, err = sess.Insert(&models.Ledger{LedgerId: 11, OwnerUid: 100, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic A"}, &models.Ledger{LedgerId: 12, OwnerUid: 100, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic B"})
	require.NoError(t, err)
	for i, role := range []models.FamilyMemberRole{1, 2, 3, 4} {
		_, err = sess.Insert(&models.LedgerMember{LedgerMemberId: int64(i + 1), LedgerId: 11, Uid: int64((i + 1) * 100), Role: role, Status: models.FAMILY_MEMBER_STATUS_ACTIVE})
		require.NoError(t, err)
	}
	fixture := loadTestLedgerReportFixture(t)
	seedTestLedgerReportFixture(t, sess, fixture)
	return &fixture.Query, sess
}

func reportDetailIDs(snapshot *models.LedgerReportSnapshot) []string {
	ids := make([]string, 0, len(snapshot.Details))
	for _, row := range snapshot.Details {
		ids = append(ids, row.ID)
	}
	return ids
}

func TestLedgerReportReadSnapshotMoneyAndNoWrites(t *testing.T) {
	query, sess := setupLedgerReport(t)
	var accountsBefore []*models.Account
	var rowsBefore []*models.Transaction
	var indicesBefore []*models.TransactionTagIndex
	require.NoError(t, sess.Asc("account_id").Find(&accountsBefore))
	require.NoError(t, sess.Asc("transaction_id").Find(&rowsBefore))
	require.NoError(t, sess.Asc("tag_index_id").Find(&indicesBefore))
	snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.True(t, snapshot.DataComplete)
	fixture := loadTestLedgerReportFixture(t)
	require.Equal(t, len(fixture.Expected.DetailIDs), snapshot.MatchedCount)
	require.Len(t, snapshot.SnapshotHash, 64)
	require.Equal(t, fixture.Expected.DetailIDs, reportDetailIDs(snapshot))
	require.Equal(t, fixture.Expected.Totals, snapshot.Totals)
	require.Len(t, snapshot.Trend, 1)
	require.Equal(t, len(fixture.Expected.DetailIDs), snapshot.Trend[0].Count)
	require.Equal(t, snapshot.Totals, snapshot.Trend[0].Money)
	for _, row := range snapshot.Details {
		if row.ID == fixture.Expected.Transfer.ID {
			require.Equal(t, fixture.Expected.Transfer.PhysicalIDs, row.PhysicalIDs)
			require.Equal(t, reportFixtureInt(t, fixture.Expected.Transfer.SourceAmount), row.Source.Amount)
			require.Equal(t, reportFixtureInt(t, fixture.Expected.Transfer.DestinationAmount), row.Destination.Amount)
			var tagIDs []string
			for _, tag := range row.Tags {
				tagIDs = append(tagIDs, tag.ID)
			}
			require.Equal(t, fixture.Expected.Transfer.TagIDs, tagIDs)
		}
		if row.ID == fixture.Expected.Adjustment.ID {
			require.Equal(t, reportFixtureInt(t, fixture.Expected.Adjustment.BalanceTarget), *row.BalanceTarget)
			require.Equal(t, reportFixtureInt(t, fixture.Expected.Adjustment.Delta), row.Adjustment)
		}
	}
	var readableTagIDs []string
	for _, tag := range snapshot.ReadableTags {
		readableTagIDs = append(readableTagIDs, tag.ID)
	}
	require.Equal(t, fixture.Expected.ReadableTagIDs, readableTagIDs)
	require.False(t, snapshot.TagGroupsAdditive)
	tagCounts := make(map[string]int)
	for _, group := range snapshot.TagGroups {
		tagCounts[group.Key] = group.Count
	}
	require.Equal(t, fixture.Expected.TagCounts, tagCounts)
	categoryCounts := make(map[string]int)
	for _, g := range snapshot.Categories {
		categoryCounts[g.Key] = g.Count
	}
	require.Equal(t, fixture.Expected.CategoryCounts, categoryCounts)
	var accountsAfter []*models.Account
	var rowsAfter []*models.Transaction
	var indicesAfter []*models.TransactionTagIndex
	require.NoError(t, sess.Asc("account_id").Find(&accountsAfter))
	require.NoError(t, sess.Asc("transaction_id").Find(&rowsAfter))
	require.NoError(t, sess.Asc("tag_index_id").Find(&indicesAfter))
	require.Equal(t, accountsBefore, accountsAfter)
	require.Equal(t, rowsBefore, rowsAfter)
	require.Equal(t, indicesBefore, indicesAfter)
	raw, err := json.Marshal(snapshot)
	require.NoError(t, err)
	require.Contains(t, string(raw), `"income":"`+strconv.FormatInt(fixture.Expected.Totals[0].Income, 10)+`"`)
	require.Contains(t, string(raw), `"balanceTarget":"`+fixture.Expected.Adjustment.BalanceTarget+`"`)
}

func TestLedgerReportHistoricalAdjustmentTargetIsNotDelta(t *testing.T) {
	query, sess := setupLedgerReport(t)
	variant := loadTestLedgerReportFixture(t).Variants.HistoricalAdjustment
	_, err := sess.ID(reportFixtureInt(t, variant.ID)).Cols("amount", "related_account_amount").Update(&models.Transaction{
		Amount: reportFixtureInt(t, variant.BalanceTarget), RelatedAccountAmount: reportFixtureInt(t, variant.Delta),
	})
	require.NoError(t, err)
	snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.Equal(t, variant.Totals, snapshot.Totals)
	found := false
	for _, row := range snapshot.Details {
		if row.ID == variant.ID {
			found = true
			require.NotNil(t, row.BalanceTarget)
			require.Equal(t, reportFixtureInt(t, variant.BalanceTarget), *row.BalanceTarget)
			require.Equal(t, reportFixtureInt(t, variant.Delta), row.Adjustment)
			require.NotEqual(t, *row.BalanceTarget, row.Adjustment)
		}
	}
	require.True(t, found)
}

func TestLedgerReportFiveDimensionsShareOneSet(t *testing.T) {
	query, _ := setupLedgerReport(t)
	fixture := loadTestLedgerReportFixture(t)
	tests := fixture.Cases
	for _, tt := range tests {
		t.Run(tt.Name, func(t *testing.T) {
			copy := *query
			require.NoError(t, json.Unmarshal(tt.Query, &copy))
			snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 100, &copy)
			require.NoError(t, err)
			require.Equal(t, tt.DetailIDs, reportDetailIDs(snapshot))
			require.Equal(t, len(tt.DetailIDs), snapshot.MatchedCount)
			trendCount := 0
			for _, g := range snapshot.Trend {
				trendCount += g.Count
			}
			require.Equal(t, snapshot.MatchedCount, trendCount)
			categoryCount := 0
			for _, g := range snapshot.Categories {
				categoryCount += g.Count
			}
			require.Equal(t, snapshot.MatchedCount, categoryCount)
			if tt.Totals != nil {
				require.Equal(t, tt.Totals, snapshot.Totals)
			}
		})
	}
}

func TestLedgerReportRolesIsolationAndScopedTags(t *testing.T) {
	query, sess := setupLedgerReport(t)
	hashes := make(map[string]bool)
	fixture := loadTestLedgerReportFixture(t)
	for _, actor := range []int64{100, 200, 300, 400} {
		snapshot, err := Transactions.GetLedgerReportSnapshot(nil, actor, query)
		require.NoError(t, err)
		require.Equal(t, len(fixture.Expected.DetailIDs), snapshot.MatchedCount)
		hashes[snapshot.SnapshotHash] = true
		require.Len(t, snapshot.ReadableTags, 2)
	}
	require.Len(t, hashes, 4, "preview hashes bind the actual actor")
	_, err := Transactions.GetLedgerReportSnapshot(nil, 500, query)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	defaultQuery := *query
	zero := "0"
	defaultQuery.LedgerID = &zero
	ownerDefault, err := Transactions.GetLedgerReportSnapshot(nil, 100, &defaultQuery)
	require.NoError(t, err)
	require.Equal(t, []string{"12"}, reportDetailIDs(ownerDefault))
	memberDefault, err := Transactions.GetLedgerReportSnapshot(nil, 200, &defaultQuery)
	require.NoError(t, err)
	require.Empty(t, memberDefault.Details)
	otherQuery := *query
	otherLedger := "12"
	otherQuery.LedgerID = &otherLedger
	other, err := Transactions.GetLedgerReportSnapshot(nil, 100, &otherQuery)
	require.NoError(t, err)
	require.Equal(t, []string{"11"}, reportDetailIDs(other))
	require.Equal(t, []models.LedgerReportResource{{ID: "502", Name: "Synthetic other ledger private"}}, other.ReadableTags)
	_, err = Transactions.GetLedgerReportSnapshot(nil, 200, &otherQuery)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	for _, id := range []string{"502", "503", "504"} {
		copy := *query
		copy.Tags = []models.LedgerReportTagFilter{{Mode: "any", IDs: []string{id}}}
		_, err := Transactions.GetLedgerReportSnapshot(nil, 400, &copy)
		require.ErrorIs(t, err, errs.ErrLedgerReportResourceInvalid)
	}
	for _, id := range []string{"200", "400"} {
		copy := *query
		copy.Accounts = &models.LedgerReportIDFilter{Mode: "include", IDs: []string{id}}
		_, err := Transactions.GetLedgerReportSnapshot(nil, 100, &copy)
		require.ErrorIs(t, err, errs.ErrLedgerReportResourceInvalid)
	}
	for _, id := range []string{"1003", "2002"} {
		copy := *query
		copy.Categories = &models.LedgerReportIDFilter{Mode: "include", IDs: []string{id}, IncludeDescendants: true}
		_, err := Transactions.GetLedgerReportSnapshot(nil, 100, &copy)
		require.ErrorIs(t, err, errs.ErrLedgerReportResourceInvalid)
	}
	before, err := Transactions.GetLedgerReportSnapshot(nil, 300, query)
	require.NoError(t, err)
	_, err = sess.Where("ledger_id=? AND uid=?", 11, 300).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	after, err := Transactions.GetLedgerReportSnapshot(nil, 300, query)
	require.NoError(t, err)
	require.NotEqual(t, before.SnapshotHash, after.SnapshotHash)
	_, err = sess.Where("ledger_id=? AND uid=?", 11, 300).Cols("status").Update(&models.LedgerMember{Status: models.FAMILY_MEMBER_STATUS_REMOVED})
	require.NoError(t, err)
	_, err = Transactions.GetLedgerReportSnapshot(nil, 300, query)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
}

func TestLedgerReportSnapshotHashTracksQueryDataAndLabels(t *testing.T) {
	query, sess := setupLedgerReport(t)
	before, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	repeated, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.Equal(t, before.SnapshotHash, repeated.SnapshotHash)
	_, err = sess.ID(2).Cols("comment").Update(&models.Transaction{Comment: "Synthetic changed comment"})
	require.NoError(t, err)
	changed, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.NotEqual(t, before.SnapshotHash, changed.SnapshotHash)
	_, err = sess.ID(100).Cols("name").Update(&models.Account{Name: "Synthetic renamed cash"})
	require.NoError(t, err)
	renamed, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.NotEqual(t, changed.SnapshotHash, renamed.SnapshotHash)
	_, err = sess.ID(1004).Cols("name").Update(&models.TransactionCategory{Name: "Synthetic renamed parent"})
	require.NoError(t, err)
	parentRenamed, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.NotEqual(t, renamed.SnapshotHash, parentRenamed.SnapshotHash)
	query.Types = []int32{3}
	filtered, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.NotEqual(t, renamed.SnapshotHash, filtered.SnapshotHash)
}

func TestLedgerReportCrossDayTransferAndHiddenPartner(t *testing.T) {
	query, sess := setupLedgerReport(t)
	end := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC).Unix() * 1000
	_, err := sess.Insert(&models.Transaction{TransactionId: 20, Uid: 100, LedgerId: 11, Type: 4, CategoryId: 1021, AccountId: 100, Amount: 70, RelatedId: 21, RelatedAccountId: 101, RelatedAccountAmount: 10, TransactionTime: end - 1}, &models.Transaction{TransactionId: 21, Uid: 100, LedgerId: 11, Type: 5, CategoryId: 1021, AccountId: 101, Amount: 10, RelatedId: 20, RelatedAccountId: 100, RelatedAccountAmount: 70, TransactionTime: end})
	require.NoError(t, err)
	query.StartDate = "2026-10-31"
	october, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.Equal(t, []string{"20"}, reportDetailIDs(october))
	query.StartDate = "2026-11-01"
	query.EndDate = "2026-11-01"
	november, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.Equal(t, []string{"14"}, reportDetailIDs(november))
	query.StartDate = "2026-10-01"
	query.EndDate = "2026-10-31"
	_, err = sess.ID(6).Cols("hide_amount").Update(&models.Transaction{HideAmount: true})
	require.NoError(t, err)
	hidden, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.NotContains(t, reportDetailIDs(hidden), "5")
}

func TestLedgerReportRejectsIncompleteDataAndOverflow(t *testing.T) {
	for _, scenario := range []struct {
		name   string
		change func(*xorm.Session) error
		want   error
	}{
		{"missing-account", func(s *xorm.Session) error {
			_, e := s.ID(2).Cols("account_id").Update(&models.Transaction{AccountId: 999999})
			return e
		}, errs.ErrLedgerReportIncomplete},
		{"missing-category", func(s *xorm.Session) error {
			_, e := s.ID(2).Cols("category_id").Update(&models.Transaction{CategoryId: 999999})
			return e
		}, errs.ErrLedgerReportIncomplete},
		{"missing-tag", func(s *xorm.Session) error {
			_, e := s.ID(500).Cols("deleted").Update(&models.TransactionTag{Deleted: true})
			return e
		}, errs.ErrLedgerReportIncomplete},
		{"foreign-tag-association", func(s *xorm.Session) error {
			_, e := s.Insert(&models.TransactionTagIndex{TagIndexId: 99, Uid: 100, TransactionId: 2, TagId: 504})
			return e
		}, errs.ErrLedgerReportIncomplete},
		{"missing-partner", func(s *xorm.Session) error {
			_, e := s.ID(6).Cols("deleted").Update(&models.Transaction{Deleted: true})
			return e
		}, errs.ErrLedgerReportIncomplete},
		{"cross-ledger-partner", func(s *xorm.Session) error {
			_, e := s.ID(6).Cols("ledger_id").Update(&models.Transaction{LedgerId: 12})
			return e
		}, errs.ErrLedgerReportIncomplete},
		{"wrong-pair-amount", func(s *xorm.Session) error {
			_, e := s.ID(6).Cols("amount").Update(&models.Transaction{Amount: 99})
			return e
		}, errs.ErrLedgerReportIncomplete},
		{"invalid-parent", func(s *xorm.Session) error {
			_, e := s.ID(111).Cols("parent_account_id").Update(&models.Account{ParentAccountId: 111})
			return e
		}, errs.ErrLedgerReportIncomplete},
		{"net-overflow", func(s *xorm.Session) error {
			_, e := s.ID(3).Cols("amount").Update(&models.Transaction{Amount: math.MinInt64})
			return e
		}, errs.ErrLedgerReportAmountOverflow},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			query, sess := setupLedgerReport(t)
			require.NoError(t, scenario.change(sess))
			snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
			require.ErrorIs(t, err, scenario.want)
			require.Nil(t, snapshot)
		})
	}
}

func TestLedgerReportCompleteCandidateLimit(t *testing.T) {
	query, sess := setupLedgerReport(t)
	base := time.Date(2026, 10, 12, 0, 0, 0, 0, time.UTC).Unix() * 1000
	_, err := sess.Exec("WITH RECURSIVE counter(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM counter WHERE n<?) INSERT INTO `transaction` (transaction_id,uid,ledger_id,deleted,type,category_id,account_id,transaction_time,timezone_utc_offset,amount,related_id,related_account_id,related_account_amount,hide_amount,comment,geo_longitude,geo_latitude,created_ip) SELECT 100000+n,100,11,0,3,1002,100,?+n,0,1,0,0,0,0,'synthetic',0,0,'' FROM counter", MaximumLedgerReportRows+1, base)
	require.NoError(t, err)
	snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.ErrorIs(t, err, errs.ErrLedgerReportTooLarge)
	require.Nil(t, snapshot)
}

func TestLedgerReportStringIDsAboveJavaScriptPrecision(t *testing.T) {
	query, sess := setupLedgerReport(t)
	id := int64(9007199254740993)
	_, err := sess.Insert(&models.Transaction{TransactionId: id, Uid: 100, LedgerId: 11, Type: 3, CategoryId: 1002, AccountId: 100, Amount: 1, TransactionTime: time.Date(2026, 10, 12, 0, 0, 0, 0, time.UTC).Unix() * 1000})
	require.NoError(t, err)
	snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 100, query)
	require.NoError(t, err)
	require.Contains(t, reportDetailIDs(snapshot), strconv.FormatInt(id, 10))
	raw, err := json.Marshal(snapshot)
	require.NoError(t, err)
	require.Contains(t, string(raw), `"id":"9007199254740993"`)
}
