package datastore

import (
	"reflect"
	"strings"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"xorm.io/xorm"
)

// DoReadSnapshot fixes the read baseline before invoking a read-only callback.
// No session/global isolation defaults are changed on pooled connections.
// SQLite retains the existing private-cache/immediate transaction policy;
// callers should release it before doing CPU-heavy aggregation or serialization.
func (db *Database) DoReadSnapshot(c core.Context, read func(*xorm.Session) error, tables ...any) error {
	return db.DoTransaction(c, func(sess *xorm.Session) error {
		switch db.databaseType {
		case settings.Sqlite3DbType:
			rows, err := sess.QueryString("PRAGMA read_uncommitted")
			if err != nil {
				return err
			}
			if len(rows) != 1 || rows[0]["read_uncommitted"] != "0" {
				return errs.ErrDatabaseSnapshotUnavailable
			}
		case settings.PostgresDbType:
			// Must precede the first query in the transaction. READ COMMITTED is
			// PostgreSQL's default and would allow mixed metadata/data versions.
			if _, err := sess.Exec("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"); err != nil {
				return err
			}
		case settings.MySqlDbType:
			// Xorm Begin uses the connection's configured isolation. MySQL cannot
			// change an already-running transaction's isolation; reject a weaker
			// configuration instead of changing shared pool defaults or mixing
			// versions. tx_isolation retains compatibility with older servers.
			rows, err := sess.QueryString("SELECT @@transaction_isolation AS isolation_level")
			if err != nil {
				rows, err = sess.QueryString("SELECT @@tx_isolation AS isolation_level")
			}
			if err != nil {
				return err
			}
			if len(rows) != 1 || !repeatableReadIsolation(rows[0]["isolation_level"]) {
				return errs.ErrDatabaseSnapshotUnavailable
			}
			if err := requireTransactionalSnapshotTables(sess, tables); err != nil {
				return err
			}
		default:
			return errs.ErrDatabaseTypeInvalid
		}
		return read(sess)
	})
}

// A transaction isolation setting cannot make MyISAM (or another non-MVCC
// engine) provide a snapshot. Require the caller's complete table set, pin each
// table's metadata until commit, then check its engine. Inspecting engines before
// opening the tables would race a concurrent ALTER TABLE ... ENGINE operation.
func requireTransactionalSnapshotTables(sess *xorm.Session, beans []any) error {
	if len(beans) == 0 || len(beans) > 64 {
		return errs.ErrDatabaseSnapshotUnavailable
	}
	names := make(map[string]bool, len(beans))
	for _, bean := range beans {
		kind := reflect.TypeOf(bean)
		if kind == nil {
			return errs.ErrDatabaseSnapshotUnavailable
		}
		if kind.Kind() == reflect.Pointer {
			kind = kind.Elem()
		}
		if kind.Kind() != reflect.Struct {
			return errs.ErrDatabaseSnapshotUnavailable
		}
		name := sess.Engine().TableName(bean)
		if name == "" || names[name] {
			continue
		}
		names[name] = true
		// Even LIMIT 0 opens the referenced table and obtains a transaction-held
		// metadata lock. No financial rows are loaded or changed by this query.
		if _, err := sess.QueryString("SELECT 1 FROM " + sess.Engine().Quote(name) + " LIMIT 0"); err != nil {
			return err
		}
		rows, err := sess.QueryString("SELECT ENGINE AS engine_name FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND TABLE_TYPE='BASE TABLE'", name)
		if err != nil {
			return err
		}
		if len(rows) != 1 || !strings.EqualFold(rows[0]["engine_name"], "InnoDB") {
			return errs.ErrDatabaseSnapshotUnavailable
		}
	}
	if len(names) == 0 {
		return errs.ErrDatabaseSnapshotUnavailable
	}
	return nil
}

func repeatableReadIsolation(value string) bool {
	value = strings.ReplaceAll(strings.ToUpper(strings.TrimSpace(value)), " ", "-")
	return value == "REPEATABLE-READ" || value == "SERIALIZABLE"
}
