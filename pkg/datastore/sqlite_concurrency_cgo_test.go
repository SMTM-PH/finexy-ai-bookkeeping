//go:build cgo

package datastore

import (
	"errors"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/stretchr/testify/require"
	"xorm.io/xorm"
)

func sqliteLedgerDatabase(t *testing.T, path string) *Database {
	t.Helper()
	db, err := initializeDatabase(&settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType,
		DatabasePath: path, MaxOpenConnection: 4, MaxIdleConnection: 4})
	require.NoError(t, err)
	t.Cleanup(func() { require.NoError(t, db.engineGroup.Close()) })
	return db
}

func sqliteLedgerFixture(t *testing.T) (*Database, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "ledger.db")
	db := sqliteLedgerDatabase(t, path)
	require.NoError(t, db.engineGroup.Sync2(new(models.Ledger)))
	_, err := db.engineGroup.Insert(&models.Ledger{LedgerId: 11, OwnerUid: 1,
		Type: models.LEDGER_TYPE_PERSONAL, Name: "committed"})
	require.NoError(t, err)
	return db, path
}

func sqliteReadLedger(db *Database) (models.Ledger, error) {
	sess := db.NewSession(nil)
	defer sess.Close()
	var row models.Ledger
	found, err := sess.ID(11).Get(&row)
	if err == nil && !found {
		err = errors.New("synthetic ledger missing")
	}
	return row, err
}

// Hold the ledger writer open: a normal reader must return the committed
// value, rather than SQLITE_LOCKED or the uncommitted replacement.
func TestSQLiteConcurrentLedgerReaderSeesCommittedSnapshot(t *testing.T) {
	db, _ := sqliteLedgerFixture(t)
	writer := db.engineGroup.NewSession()
	defer writer.Close()
	require.NoError(t, writer.Begin())
	defer writer.Rollback()
	affected, err := writer.ID(11).Cols("name").Update(&models.Ledger{Name: "uncommitted"})
	require.NoError(t, err)
	require.Equal(t, int64(1), affected)
	var pending models.Ledger
	found, err := writer.ID(11).Get(&pending)
	require.NoError(t, err)
	require.True(t, found)
	require.Equal(t, "uncommitted", pending.Name)
	type result struct {
		row models.Ledger
		err error
	}
	read := make(chan result, 1)
	go func() { row, err := sqliteReadLedger(db); read <- result{row, err} }()
	select {
	case got := <-read:
		require.NoError(t, got.err)
		require.Equal(t, "committed", got.row.Name)
	case <-time.After(2 * time.Second):
		t.Fatal("ledger reader blocked behind an uncommitted writer")
	}
	require.NoError(t, writer.Rollback())
	row, err := sqliteReadLedger(db)
	require.NoError(t, err)
	require.Equal(t, "committed", row.Name)
}

// Independent connection pools represent two service instances. The second
// callback must start only after acquiring its write lock, and run once.
func TestSQLiteConcurrentWriterWaitsBeforeBusinessCallback(t *testing.T) {
	first, path := sqliteLedgerFixture(t)
	second := sqliteLedgerDatabase(t, path)
	writer := first.engineGroup.NewSession()
	defer writer.Close()
	require.NoError(t, writer.Begin())
	defer writer.Rollback()
	_, err := writer.ID(11).Cols("name").Update(&models.Ledger{Name: "first committed"})
	require.NoError(t, err)
	started := make(chan struct{})
	entered := make(chan struct{}, 1)
	done := make(chan error, 1)
	var calls atomic.Int32
	go func() {
		close(started)
		done <- second.DoTransaction(nil, func(sess *xorm.Session) error {
			calls.Add(1)
			entered <- struct{}{}
			var row models.Ledger
			found, err := sess.ID(11).Get(&row)
			if err != nil {
				return err
			}
			if !found || row.Name != "first committed" {
				return errors.New("writer observed a stale ledger")
			}
			_, err = sess.ID(11).Cols("name").Update(&models.Ledger{Name: "second committed"})
			return err
		})
	}()
	<-started
	select {
	case <-entered:
		t.Fatal("business callback entered before the previous writer released its lock")
	case <-time.After(100 * time.Millisecond):
	}
	require.NoError(t, writer.Commit())
	select {
	case err := <-done:
		require.NoError(t, err)
	case <-time.After(3 * time.Second):
		t.Fatal("waiting writer did not finish after commit")
	}
	require.Equal(t, int32(1), calls.Load())
	row, err := sqliteReadLedger(first)
	require.NoError(t, err)
	require.Equal(t, "second committed", row.Name)
}

func TestSQLiteConcurrentReadModifyWriteAcrossConnectionPools(t *testing.T) {
	first, path := sqliteLedgerFixture(t)
	second := sqliteLedgerDatabase(t, path)
	start := make(chan struct{})
	stop := make(chan struct{})
	failures := make(chan error, 64)
	var writers, readers sync.WaitGroup
	var calls atomic.Int32
	for worker := 0; worker < 4; worker++ {
		writers.Add(1)
		go func(db *Database) {
			defer writers.Done()
			<-start
			for i := 0; i < 12; i++ {
				if err := db.DoTransaction(nil, func(sess *xorm.Session) error {
					calls.Add(1)
					var row models.Ledger
					found, err := sess.ID(11).Get(&row)
					if err != nil {
						return err
					}
					if !found {
						return errors.New("synthetic ledger missing")
					}
					time.Sleep(time.Millisecond)
					_, err = sess.ID(11).Cols("updated_unix_time").Update(&models.Ledger{UpdatedUnixTime: row.UpdatedUnixTime + 1})
					return err
				}); err != nil {
					failures <- err
					return
				}
			}
		}([]*Database{first, second}[worker%2])
	}
	for _, db := range []*Database{first, second} {
		readers.Add(1)
		go func(db *Database) {
			defer readers.Done()
			<-start
			var last int64
			for {
				select {
				case <-stop:
					return
				default:
				}
				row, err := sqliteReadLedger(db)
				if err != nil {
					failures <- err
					return
				}
				if row.UpdatedUnixTime < last {
					failures <- errors.New("committed ledger version regressed")
					return
				}
				last = row.UpdatedUnixTime
				time.Sleep(time.Millisecond)
			}
		}(db)
	}
	close(start)
	writers.Wait()
	close(stop)
	readers.Wait()
	close(failures)
	for err := range failures {
		require.NoError(t, err)
	}
	row, err := sqliteReadLedger(first)
	require.NoError(t, err)
	require.Equal(t, int64(48), row.UpdatedUnixTime)
	require.Equal(t, int32(48), calls.Load())
}

func TestSQLiteBusinessFailureRollsBackWithoutReplayingCallback(t *testing.T) {
	db, _ := sqliteLedgerFixture(t)
	var calls int
	rejected := errors.New("synthetic rejection")
	err := db.DoTransaction(nil, func(sess *xorm.Session) error {
		calls++
		if _, err := sess.ID(11).Cols("name").Update(&models.Ledger{Name: "rejected"}); err != nil {
			return err
		}
		return rejected
	})
	require.ErrorIs(t, err, rejected)
	require.Equal(t, 1, calls)
	row, err := sqliteReadLedger(db)
	require.NoError(t, err)
	require.Equal(t, "committed", row.Name)
}
