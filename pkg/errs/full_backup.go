package errs

import "net/http"

var (
	ErrFullBackupNotSupported = NewNormalError(NormalSubcategoryFullBackup, 1, http.StatusBadRequest, "full backup is only supported with SQLite and local filesystem storage")
	ErrFullBackupTooLarge     = NewNormalError(NormalSubcategoryFullBackup, 2, http.StatusBadRequest, "full backup file is too large")
	ErrFullBackupInvalid      = NewNormalError(NormalSubcategoryFullBackup, 3, http.StatusBadRequest, "full backup file is invalid")
	ErrFullBackupInProgress   = NewNormalError(NormalSubcategoryFullBackup, 4, http.StatusConflict, "a full backup restore is already pending")
)
