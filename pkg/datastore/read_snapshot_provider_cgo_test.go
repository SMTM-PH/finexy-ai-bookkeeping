//go:build cgo

package datastore

import (
	"os"
	"regexp"
	"strings"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/stretchr/testify/require"
	"xorm.io/xorm"
)

type ledgerReportProviderProbe struct {
	ID    int64 `xorm:"'id' PK"`
	Value int64
}

// External-provider tests are intentionally opt-in and refuse an arbitrary
// hostname/schema. The runner creates these databases in no-volume containers
// on a dedicated network and never publishes their ports.
func externalReadSnapshotDB(t *testing.T) *Database {
	t.Helper()
	provider := os.Getenv("FINEXY_REPORT_PROVIDER_TYPE")
	if provider == "" {
		t.Skip("external provider not configured; this is not provider acceptance")
	}
	require.Equal(t, "1", os.Getenv("FINEXY_REPORT_PROVIDER_ISOLATED"))
	require.Contains(t, []string{settings.PostgresDbType, settings.MySqlDbType}, provider)
	host, name := os.Getenv("FINEXY_REPORT_PROVIDER_HOST"), os.Getenv("FINEXY_REPORT_PROVIDER_NAME")
	require.True(t, strings.HasPrefix(host, "finexy-report-provider-20261022-"), "only dedicated provider containers are allowed")
	require.Regexp(t, regexp.MustCompile(`^finexy_report_provider_[a-z0-9]+$`), name)
	db, err := initializeDatabase(&settings.DatabaseConfig{DatabaseType: provider,
		DatabaseHost: host, DatabaseName: name, DatabaseUser: os.Getenv("FINEXY_REPORT_PROVIDER_USER"),
		DatabasePassword: os.Getenv("FINEXY_REPORT_PROVIDER_PASSWORD"), DatabaseSSLMode: "disable",
		MaxOpenConnection: 4, MaxIdleConnection: 4})
	require.NoError(t, err)
	t.Cleanup(func() { require.NoError(t, db.engineGroup.Close()) })
	_, err = db.NewSession(nil).Exec("DROP TABLE IF EXISTS ledger_report_provider_probe")
	require.NoError(t, err)
	require.NoError(t, db.engineGroup.Sync2(new(ledgerReportProviderProbe)))
	_, err = db.NewSession(nil).Insert(&ledgerReportProviderProbe{ID: 1, Value: 100})
	require.NoError(t, err)
	return db
}

func TestReadSnapshotExternalProviderConcurrentCommit(t *testing.T) {
	db := externalReadSnapshotDB(t)
	// The callback obtains its baseline before a second physical connection
	// commits. Both observations must retain the old value; a later snapshot
	// must see the committed value. This detects READ COMMITTED regressions.
	err := db.DoReadSnapshot(nil, func(sess *xorm.Session) error {
		first := &ledgerReportProviderProbe{ID: 1}
		_, err := sess.Get(first)
		require.NoError(t, err)
		require.Equal(t, int64(100), first.Value)
		writer := db.NewSession(nil)
		defer writer.Close()
		_, err = writer.Where("id=?", 1).Cols("value").Update(&ledgerReportProviderProbe{Value: 200})
		require.NoError(t, err)
		second := &ledgerReportProviderProbe{ID: 1}
		_, err = sess.Get(second)
		require.NoError(t, err)
		require.Equal(t, int64(100), second.Value, "one snapshot must not mix a concurrent commit")
		return nil
	}, new(ledgerReportProviderProbe))
	require.NoError(t, err)
	require.NoError(t, db.DoReadSnapshot(nil, func(sess *xorm.Session) error {
		current := &ledgerReportProviderProbe{ID: 1}
		_, err := sess.Get(current)
		require.NoError(t, err)
		require.Equal(t, int64(200), current.Value)
		return nil
	}, new(ledgerReportProviderProbe)))
}

func TestReadSnapshotExternalProviderWeakIsolation(t *testing.T) {
	db := externalReadSnapshotDB(t)
	db.engineGroup.SetMaxOpenConns(1)
	db.engineGroup.SetMaxIdleConns(1)
	sess := db.NewSession(nil)
	defer sess.Close()
	var err error
	if db.databaseType == settings.MySqlDbType {
		_, err = sess.Exec("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED")
	} else {
		_, err = sess.Exec("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL READ COMMITTED")
	}
	require.NoError(t, err)
	entered := false
	err = db.DoReadSnapshot(nil, func(tx *xorm.Session) error {
		entered = true
		rows, err := tx.QueryString("SHOW transaction_isolation")
		require.NoError(t, err)
		require.Equal(t, "repeatable read", rows[0]["transaction_isolation"])
		return nil
	}, new(ledgerReportProviderProbe))
	if db.databaseType == settings.MySqlDbType {
		require.ErrorIs(t, err, errs.ErrDatabaseSnapshotUnavailable)
		require.False(t, entered)
		_, err = sess.Exec("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
		require.NoError(t, err)
	} else {
		require.NoError(t, err)
		require.True(t, entered)
		rows, err := sess.QueryString("SHOW transaction_isolation")
		require.NoError(t, err)
		require.Equal(t, "read committed", rows[0]["transaction_isolation"], "pool defaults must remain unchanged")
	}
}

func TestReadSnapshotExternalProviderNonTransactionalTable(t *testing.T) {
	db := externalReadSnapshotDB(t)
	if db.databaseType != settings.MySqlDbType {
		t.Skip("MyISAM exists only in MySQL; PostgreSQL table storage is transactional")
	}
	_, err := db.NewSession(nil).Exec("ALTER TABLE ledger_report_provider_probe ENGINE=MyISAM")
	require.NoError(t, err)
	entered := false
	err = db.DoReadSnapshot(nil, func(sess *xorm.Session) error {
		entered = true
		return nil
	}, new(ledgerReportProviderProbe))
	require.ErrorIs(t, err, errs.ErrDatabaseSnapshotUnavailable, "repeatable-read configuration alone cannot snapshot MyISAM")
	require.False(t, entered, "a report must reject nontransactional storage before reading financial data")
}

func TestReadSnapshotExternalProviderRequiresTableWhitelist(t *testing.T) {
	db := externalReadSnapshotDB(t)
	if db.databaseType != settings.MySqlDbType {
		t.Skip("the MySQL storage engine requires an explicit snapshot table set")
	}
	entered := false
	err := db.DoReadSnapshot(nil, func(*xorm.Session) error { entered = true; return nil })
	require.ErrorIs(t, err, errs.ErrDatabaseSnapshotUnavailable)
	require.False(t, entered)
}

func TestReadSnapshotExternalProviderPinsStorageEngine(t *testing.T) {
	db := externalReadSnapshotDB(t)
	if db.databaseType != settings.MySqlDbType {
		t.Skip("the MySQL engine alteration race is provider specific")
	}
	require.NoError(t, db.DoReadSnapshot(nil, func(sess *xorm.Session) error {
		writer := db.NewSession(nil)
		defer writer.Close()
		_, err := writer.Exec("SET SESSION lock_wait_timeout=1")
		require.NoError(t, err)
		_, err = writer.Exec("ALTER TABLE ledger_report_provider_probe ENGINE=MyISAM")
		require.ErrorContains(t, err, "1205", "DDL must wait for the snapshot's metadata lock")
		row := &ledgerReportProviderProbe{ID: 1}
		_, err = sess.Get(row)
		require.NoError(t, err)
		require.Equal(t, int64(100), row.Value)
		return nil
	}, new(ledgerReportProviderProbe)))
	_, err := db.NewSession(nil).Exec("ALTER TABLE ledger_report_provider_probe ENGINE=MyISAM")
	require.NoError(t, err, "snapshot commit must release the metadata lock")
	err = db.DoReadSnapshot(nil, func(*xorm.Session) error { t.Fatal("nontransactional callback must not run"); return nil }, new(ledgerReportProviderProbe))
	require.ErrorIs(t, err, errs.ErrDatabaseSnapshotUnavailable)
}
