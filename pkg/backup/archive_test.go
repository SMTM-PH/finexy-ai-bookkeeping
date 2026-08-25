package backup

import (
	"archive/zip"
	"bytes"
	"database/sql"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

func TestArchiveRoundTrip(t *testing.T) {
	root := t.TempDir()
	databasePath := filepath.Join(root, "source.sqlite3")
	storagePath := filepath.Join(root, "objects")
	if err := os.WriteFile(databasePath, append([]byte("SQLite format 3\x00"), []byte("test database")...), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(storagePath, "nested"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(storagePath, "nested", "receipt.jpg"), []byte("image"), 0600); err != nil {
		t.Fatal(err)
	}
	var content bytes.Buffer
	if err := WriteArchive(&content, databasePath, storagePath, time.Unix(1700000000, 0)); err != nil {
		t.Fatal(err)
	}
	archivePath := filepath.Join(root, "backup.zip")
	if err := os.WriteFile(archivePath, content.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	if err := ValidateArchive(archivePath, 1024*1024); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(root, "restored")
	if err := ExtractArchive(archivePath, destination, 1024*1024); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(destination, StorageDirectory, "nested", "receipt.jpg"))
	if err != nil || string(data) != "image" {
		t.Fatalf("unexpected restored file: %q, %v", data, err)
	}
}

func TestSQLiteVacuumIntoAcceptsBoundPath(t *testing.T) {
	root := t.TempDir()
	sourcePath := filepath.Join(root, "source.sqlite3")
	snapshotPath := filepath.Join(root, "snapshot.sqlite3")
	database, err := sql.Open("sqlite3", sourcePath)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	if _, err = database.Exec("CREATE TABLE records (value TEXT); INSERT INTO records VALUES ('saved')"); err != nil {
		if strings.Contains(err.Error(), "requires cgo") {
			t.Skip("go-sqlite3 is a stub in the CGO-disabled test environment")
		}
		t.Fatal(err)
	}
	if _, err = database.Exec("VACUUM INTO ?", snapshotPath); err != nil {
		t.Fatal(err)
	}
	if info, statErr := os.Stat(snapshotPath); statErr != nil || info.Size() <= SQLiteHeaderLength {
		t.Fatalf("snapshot was not created: %v", statErr)
	}
}

func TestValidateArchiveRejectsTraversal(t *testing.T) {
	path := filepath.Join(t.TempDir(), "unsafe.zip")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(file)
	entry, _ := writer.Create("../escape")
	_, _ = entry.Write([]byte("bad"))
	_ = writer.Close()
	_ = file.Close()
	if err := ValidateArchive(path, 1024); err == nil {
		t.Fatal("expected unsafe archive to be rejected")
	}
}
