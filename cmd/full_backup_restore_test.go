package cmd

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/backup"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

func TestApplyPendingFullBackupRestore(t *testing.T) {
	root := t.TempDir()
	databasePath := filepath.Join(root, "data", "bookkeeping.db")
	storagePath := filepath.Join(root, "storage")
	if err := os.MkdirAll(filepath.Dir(databasePath), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(storagePath, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(databasePath, sqliteTestData("old"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(storagePath, "old.txt"), []byte("old"), 0600); err != nil {
		t.Fatal(err)
	}
	backupDatabase := filepath.Join(root, "backup.db")
	backupStorage := filepath.Join(root, "backup-storage")
	if err := os.WriteFile(backupDatabase, sqliteTestData("new"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(backupStorage, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(backupStorage, "new.txt"), []byte("new"), 0600); err != nil {
		t.Fatal(err)
	}
	pending, err := os.Create(databasePath + backup.PendingFileSuffix)
	if err != nil {
		t.Fatal(err)
	}
	if err = backup.WriteArchive(pending, backupDatabase, backupStorage, time.Now()); err != nil {
		t.Fatal(err)
	}
	if err = pending.Close(); err != nil {
		t.Fatal(err)
	}
	config := &settings.Config{
		DatabaseConfig: &settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType, DatabasePath: databasePath},
		StorageType:    settings.LocalFileSystemObjectStorageType, LocalFileSystemPath: storagePath,
		MaxFullBackupFileSize: 1024 * 1024,
	}
	if err = applyPendingFullBackupRestore(config); err != nil {
		t.Fatal(err)
	}
	if data, readErr := os.ReadFile(databasePath); readErr != nil || string(data) != string(sqliteTestData("new")) {
		t.Fatalf("unexpected restored database: %q, %v", data, readErr)
	}
	if data, readErr := os.ReadFile(filepath.Join(storagePath, "new.txt")); readErr != nil || string(data) != "new" {
		t.Fatalf("unexpected restored storage: %q, %v", data, readErr)
	}
	if matches, _ := filepath.Glob(databasePath + ".pre-restore-*"); len(matches) != 1 {
		t.Fatalf("expected one recoverable database copy, got %v", matches)
	}
	if _, statErr := os.Stat(databasePath + backup.PendingFileSuffix); !os.IsNotExist(statErr) {
		t.Fatalf("pending restore was not consumed: %v", statErr)
	}
}

func sqliteTestData(marker string) []byte {
	return append([]byte("SQLite format 3\x00"), []byte(marker)...)
}
