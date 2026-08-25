package cmd

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/backup"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

// applyPendingFullBackupRestore runs before the database and storage are opened.
func applyPendingFullBackupRestore(config *settings.Config) error {
	if config.DatabaseConfig == nil || config.DatabaseConfig.DatabaseType != settings.Sqlite3DbType ||
		config.StorageType != settings.LocalFileSystemObjectStorageType {
		return nil
	}
	databasePath, err := filepath.Abs(config.DatabaseConfig.DatabasePath)
	if err != nil {
		return err
	}
	storagePath, err := filepath.Abs(config.LocalFileSystemPath)
	if err != nil {
		return err
	}
	pendingPath := databasePath + backup.PendingFileSuffix
	if _, err = os.Stat(pendingPath); os.IsNotExist(err) {
		return nil
	} else if err != nil {
		return err
	}
	if err = validateRestoreTargets(databasePath, storagePath); err != nil {
		return err
	}
	if err = backup.ValidateArchive(pendingPath, int64(config.MaxFullBackupFileSize)); err != nil {
		return fmt.Errorf("invalid pending full backup: %w", err)
	}
	stagingDirectory, err := os.MkdirTemp(filepath.Dir(databasePath), ".restore-staging-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stagingDirectory)
	if err = backup.ExtractArchive(pendingPath, stagingDirectory, int64(config.MaxFullBackupFileSize)); err != nil {
		return err
	}
	stagedDatabase := filepath.Join(stagingDirectory, backup.DatabaseName)
	stagedStorage := filepath.Join(stagingDirectory, backup.StorageDirectory)
	stamp := time.Now().Format("20060102-150405.000000000")
	applyingPath := pendingPath + ".applying-" + stamp
	databaseBackup := databasePath + ".pre-restore-" + stamp
	storageBackup := storagePath + ".pre-restore-" + stamp

	databaseFilesMoved := make([][2]string, 0, 3)
	for _, suffix := range []string{"", "-wal", "-shm"} {
		oldPath := databasePath + suffix
		backupPath := databaseBackup + suffix
		moved, moveErr := renameIfExists(oldPath, backupPath)
		if moveErr != nil {
			rollbackRenames(databaseFilesMoved)
			return moveErr
		}
		if moved {
			databaseFilesMoved = append(databaseFilesMoved, [2]string{backupPath, oldPath})
		}
	}
	storageMoved, err := renameIfExists(storagePath, storageBackup)
	if err != nil {
		rollbackRenames(databaseFilesMoved)
		return err
	}
	newDatabaseInstalled := false
	newStorageInstalled := false
	rollback := func() {
		if newStorageInstalled {
			_ = os.RemoveAll(storagePath)
		}
		if newDatabaseInstalled {
			_ = os.Remove(databasePath)
		}
		if storageMoved {
			_ = os.Rename(storageBackup, storagePath)
		}
		rollbackRenames(databaseFilesMoved)
	}
	if err = os.Rename(stagedDatabase, databasePath); err != nil {
		rollback()
		return err
	}
	newDatabaseInstalled = true
	if err = os.MkdirAll(filepath.Dir(storagePath), 0700); err != nil {
		rollback()
		return err
	}
	if err = os.Rename(stagedStorage, storagePath); err != nil {
		rollback()
		return err
	}
	newStorageInstalled = true
	if err = os.Rename(pendingPath, applyingPath); err != nil {
		rollback()
		return err
	}
	_ = os.Remove(applyingPath)
	return nil
}

func validateRestoreTargets(databasePath string, storagePath string) error {
	volumeRoot := filepath.Clean(filepath.VolumeName(storagePath) + string(os.PathSeparator))
	if storagePath == volumeRoot || filepath.Base(databasePath) == "." || filepath.Base(databasePath) == string(os.PathSeparator) {
		return errors.New("unsafe full backup restore target")
	}
	relativeDatabase, err := filepath.Rel(storagePath, databasePath)
	if err != nil {
		return err
	}
	if relativeDatabase == "." || (relativeDatabase != ".." && !strings.HasPrefix(relativeDatabase, ".."+string(os.PathSeparator))) {
		return errors.New("SQLite database cannot be inside the object storage directory")
	}
	return nil
}

func renameIfExists(source string, destination string) (bool, error) {
	if _, err := os.Stat(source); os.IsNotExist(err) {
		return false, nil
	} else if err != nil {
		return false, err
	}
	if err := os.Rename(source, destination); err != nil {
		return false, err
	}
	return true, nil
}

func rollbackRenames(paths [][2]string) {
	for i := len(paths) - 1; i >= 0; i-- {
		_ = os.Rename(paths[i][0], paths[i][1])
	}
}
