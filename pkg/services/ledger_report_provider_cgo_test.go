//go:build cgo

package services

import (
	"bytes"
	"encoding/csv"
	"encoding/json"
	"os"
	"regexp"
	"strconv"
	"strings"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/stretchr/testify/require"
	"xorm.io/xorm"
)

func TestLedgerReportExternalProvider(t *testing.T) {
	provider := os.Getenv("FINEXY_REPORT_PROVIDER_TYPE")
	if provider == "" {
		t.Skip("external provider not configured; this is not provider acceptance")
	}
	require.Equal(t, "1", os.Getenv("FINEXY_REPORT_PROVIDER_ISOLATED"))
	require.Contains(t, []string{settings.PostgresDbType, settings.MySqlDbType}, provider)
	host, name := os.Getenv("FINEXY_REPORT_PROVIDER_HOST"), os.Getenv("FINEXY_REPORT_PROVIDER_NAME")
	require.True(t, strings.HasPrefix(host, "finexy-report-provider-20261022-"))
	require.Regexp(t, regexp.MustCompile(`^finexy_report_provider_[a-z0-9]+$`), name)
	require.NoError(t, datastore.InitializeDataStore(&settings.Config{DatabaseConfig: &settings.DatabaseConfig{
		DatabaseType: provider, DatabaseHost: host, DatabaseName: name,
		DatabaseUser: os.Getenv("FINEXY_REPORT_PROVIDER_USER"), DatabasePassword: os.Getenv("FINEXY_REPORT_PROVIDER_PASSWORD"),
		DatabaseSSLMode: "disable", MaxOpenConnection: 4, MaxIdleConnection: 4,
	}}))
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(models.User), new(models.Ledger), new(models.LedgerMember),
		new(models.Account), new(models.Transaction), new(models.TransactionCategory), new(models.TransactionTag), new(models.TransactionTagIndex)))
	db := Transactions.UserDataDB(100)
	sess := db.NewSession(nil)
	defer sess.Close()
	for i, role := range []models.FamilyMemberRole{1, 2, 3, 4} {
		uid := int64((i + 1) * 100)
		_, err := sess.Insert(&models.User{Uid: uid, Username: "synthetic-provider-" + strconv.FormatInt(uid, 10), Email: strconv.FormatInt(uid, 10) + "@example.invalid", DefaultCurrency: "EUR"})
		require.NoError(t, err)
		_, err = sess.Insert(&models.LedgerMember{LedgerMemberId: int64(i + 1), LedgerId: 11, Uid: uid, Role: role, Status: models.FAMILY_MEMBER_STATUS_ACTIVE})
		require.NoError(t, err)
	}
	_, err := sess.Insert(&models.Ledger{LedgerId: 11, OwnerUid: 100, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic provider A"},
		&models.Ledger{LedgerId: 12, OwnerUid: 100, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic provider B"})
	require.NoError(t, err)
	fixture := loadTestLedgerReportFixture(t)
	seedTestLedgerReportFixture(t, sess, fixture)
	query := fixture.Query

	t.Run("original-currencies-and-csv", func(t *testing.T) {
		snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 100, &query)
		require.NoError(t, err)
		require.Equal(t, fixture.Expected.DetailIDs, reportDetailIDs(snapshot))
		require.Equal(t, fixture.Expected.Totals, snapshot.Totals)
		require.Equal(t, len(fixture.Expected.DetailIDs), snapshot.MatchedCount)
		require.True(t, snapshot.DataComplete)
		encoded, err := EncodeLedgerReportCSV(snapshot)
		require.NoError(t, err)
		rows, err := csv.NewReader(bytes.NewReader(encoded)).ReadAll()
		require.NoError(t, err)
		require.Len(t, rows, snapshot.MatchedCount+1)
		columns := make(map[string]int)
		for index, name := range rows[0] {
			columns[name] = index
		}
		ids := make([]string, 0, snapshot.MatchedCount)
		for _, row := range rows[1:] {
			ids = append(ids, row[columns["id"]])
			require.Equal(t, snapshot.SnapshotHash, row[columns["snapshot_hash"]])
			if row[columns["id"]] == "2" {
				require.Equal(t, "'=SUM(1,2)\n\"quoted\",中文", row[columns["comment"]])
			}
		}
		require.Equal(t, fixture.Expected.DetailIDs, ids)
	})

	for _, testcase := range fixture.Cases {
		t.Run("fixture/"+testcase.Name, func(t *testing.T) {
			candidate := fixture.Query
			require.NoError(t, json.Unmarshal(testcase.Query, &candidate))
			snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 100, &candidate)
			require.NoError(t, err)
			require.Equal(t, testcase.DetailIDs, reportDetailIDs(snapshot))
			if testcase.Totals != nil {
				require.Equal(t, testcase.Totals, snapshot.Totals)
			}
		})
	}
	t.Run("roles-owner-shard-and-ledger-scope", func(t *testing.T) {
		for _, uid := range []int64{100, 200, 300, 400} {
			snapshot, err := Transactions.GetLedgerReportSnapshot(nil, uid, &query)
			require.NoError(t, err)
			require.Equal(t, fixture.Expected.Totals, snapshot.Totals)
			require.Equal(t, fixture.Expected.DetailIDs, reportDetailIDs(snapshot))
		}
		_, err := Transactions.GetLedgerReportSnapshot(nil, 999, &query)
		require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
		other, explicit := query, "12"
		other.LedgerID = &explicit
		_, err = Transactions.GetLedgerReportSnapshot(nil, 400, &other)
		require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
		zero := "0"
		personal := query
		personal.LedgerID = &zero
		snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 100, &personal)
		require.NoError(t, err)
		require.Equal(t, []string{"12"}, reportDetailIDs(snapshot))
	})
	t.Run("concurrent-metadata-financial-commit", func(t *testing.T) {
		normalized, err := models.NormalizeLedgerReportQuery(&query)
		require.NoError(t, err)
		data := &ledgerReportData{actor: 100, owner: 100, ledger: 11, role: models.FAMILY_MEMBER_ROLE_OWNER, query: normalized,
			accounts: make(map[int64]*models.Account), categories: make(map[int64]*models.TransactionCategory), tags: make(map[int64]*models.TransactionTag),
			rows: make(map[int64]*models.Transaction), rowTags: make(map[int64]map[int64]bool)}
		require.NoError(t, db.DoReadSnapshot(nil, func(tx *xorm.Session) error {
			require.NoError(t, data.readMetadata(tx))
			require.NoError(t, db.DoTransaction(nil, func(writer *xorm.Session) error {
				_, err := writer.Where("account_id=?", 100).Cols("name").Update(&models.Account{Name: "Synthetic newer name"})
				require.NoError(t, err)
				_, err = writer.Where("transaction_id=?", 1).Cols("amount").Update(&models.Transaction{Amount: 10100})
				return err
			}))
			return data.readTransactions(tx)
		}, new(models.Ledger), new(models.LedgerMember), new(models.Account), new(models.Transaction), new(models.TransactionCategory), new(models.TransactionTag), new(models.TransactionTagIndex)))
		snapshot, err := data.build()
		require.NoError(t, err)
		require.Equal(t, fixture.Expected.Totals, snapshot.Totals)
		current, err := Transactions.GetLedgerReportSnapshot(nil, 100, &query)
		require.NoError(t, err)
		require.Equal(t, int64(10100), current.Totals[0].Income)
		require.NotEqual(t, snapshot.SnapshotHash, current.SnapshotHash)
		_, err = PageLedgerReport(current, 1, nil, snapshot.SnapshotHash)
		require.ErrorIs(t, err, errs.ErrLedgerReportSnapshotChanged)
	})
	t.Run("live-revocation-and-role-change", func(t *testing.T) {
		snapshot, err := Transactions.GetLedgerReportSnapshot(nil, 200, &query)
		require.NoError(t, err)
		_, err = sess.Where("ledger_id=? AND uid=?", 11, 200).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
		require.NoError(t, err)
		require.ErrorIs(t, Transactions.RecheckLedgerReportAccess(nil, 200, snapshot), errs.ErrLedgerReportSnapshotChanged)
		_, err = sess.Where("ledger_id=? AND uid=?", 11, 400).Delete(new(models.LedgerMember))
		require.NoError(t, err)
		_, err = Transactions.GetLedgerReportSnapshot(nil, 400, &query)
		require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	})
	if provider == settings.MySqlDbType {
		t.Run("every-report-table-requires-transactional-storage", func(t *testing.T) {
			for _, bean := range []any{new(models.Ledger), new(models.LedgerMember), new(models.Account), new(models.Transaction), new(models.TransactionCategory), new(models.TransactionTag), new(models.TransactionTagIndex)} {
				name := sess.Engine().TableName(bean)
				t.Run(name, func(t *testing.T) {
					quoted := sess.Engine().Quote(name)
					_, err := sess.Exec("ALTER TABLE " + quoted + " ENGINE=MyISAM")
					require.NoError(t, err)
					t.Cleanup(func() { _, err := sess.Exec("ALTER TABLE " + quoted + " ENGINE=InnoDB"); require.NoError(t, err) })
					_, err = Transactions.GetLedgerReportSnapshot(nil, 100, &query)
					require.ErrorIs(t, err, errs.ErrDatabaseSnapshotUnavailable)
				})
			}
		})
		t.Run("unrelated-nontransactional-table-does-not-broaden-scope", func(t *testing.T) {
			_, err := sess.Exec("CREATE TABLE report_unrelated_probe (id BIGINT PRIMARY KEY) ENGINE=MyISAM")
			require.NoError(t, err)
			t.Cleanup(func() { _, err := sess.Exec("DROP TABLE report_unrelated_probe"); require.NoError(t, err) })
			_, err = Transactions.GetLedgerReportSnapshot(nil, 100, &query)
			require.NoError(t, err)
		})
	}
}
