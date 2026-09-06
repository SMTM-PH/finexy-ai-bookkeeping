//go:build cgo

package datastore

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"path/filepath"
	"testing"
)

func TestScheduledOccurrenceCompositeKeyAndRollback(t *testing.T) {
	db, err := initializeDatabase(&settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType,
		DatabasePath: filepath.Join(t.TempDir(), "occurrences.db"), MaxOpenConnection: 1, MaxIdleConnection: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer db.engineGroup.Close()
	if err := db.engineGroup.Sync2(new(models.ScheduledOccurrence)); err != nil {
		t.Fatal(err)
	}
	first := models.ScheduledOccurrence{Uid: 1, TemplateId: 2, ScheduledUnixTime: 100, Status: models.ScheduledOccurrencePending, SnapshotJSON: "original"}
	if _, err := db.engineGroup.Insert(&first); err != nil {
		t.Fatal(err)
	}
	duplicate := first
	duplicate.SnapshotJSON = "replacement"
	if _, err := db.engineGroup.Insert(&duplicate); err == nil {
		t.Fatal("duplicate occurrence accepted")
	}
	otherUser := first
	otherUser.Uid = 2
	if _, err := db.engineGroup.Insert(&otherUser); err != nil {
		t.Fatal(err)
	}
	session := db.engineGroup.NewSession()
	defer session.Close()
	if err := session.Begin(); err != nil {
		t.Fatal(err)
	}
	if _, err := session.Where("uid=? AND template_id=? AND scheduled_unix_time=?", 1, 2, 100).
		Cols("status", "transaction_id").Update(&models.ScheduledOccurrence{Status: models.ScheduledOccurrenceConfirmed, TransactionId: 42}); err != nil {
		t.Fatal(err)
	}
	if err := session.Rollback(); err != nil {
		t.Fatal(err)
	}
	var restored models.ScheduledOccurrence
	found, err := db.engineGroup.Where("uid=? AND template_id=? AND scheduled_unix_time=?", 1, 2, 100).Get(&restored)
	if err != nil || !found {
		t.Fatalf("read failed: %v", err)
	}
	if restored.Status != models.ScheduledOccurrencePending || restored.TransactionId != 0 || restored.SnapshotJSON != "original" {
		t.Fatal("rollback or snapshot preservation failed")
	}
}
