package models

// AgentImportBatch stores parsed rows, never the original uploaded file or a credential.
type AgentImportBatch struct {
	Uid           int64  `xorm:"PK"`
	LedgerId      int64  `xorm:"NOT NULL DEFAULT 0"`
	BatchId       string `xorm:"PK VARCHAR(64)"`
	TokenIdentity string `xorm:"VARCHAR(128) NOT NULL"`
	Payload       string `xorm:"TEXT NOT NULL"`
	PreviewHash   string `xorm:"VARCHAR(64) NOT NULL"`
	Status        int    `xorm:"NOT NULL"`
	ImportedCount int    `xorm:"NOT NULL"`
	ExpiresAt     int64  `xorm:"INDEX NOT NULL"`
}

// The composite key prevents reimport of the same source row across batches/restarts.
type AgentImportFingerprint struct {
	Uid         int64  `xorm:"PK"`
	Fingerprint string `xorm:"PK VARCHAR(64)"`
	BatchId     string `xorm:"VARCHAR(64) NOT NULL"`
}

type AgentImportPreviewRequest struct {
	LedgerId   int64  `json:"ledgerId,string"`
	FileType   string `json:"fileType"`
	FileBase64 string `json:"fileBase64"`
	UtcOffset  int16  `json:"utcOffset"`
}

type AgentImportMapping struct {
	Row                  int   `json:"row"`
	Selected             bool  `json:"selected"`
	SourceAccountId      int64 `json:"sourceAccountId,string"`
	DestinationAccountId int64 `json:"destinationAccountId,string"`
	CategoryId           int64 `json:"categoryId,string"`
}

type AgentImportMapRequest struct {
	LedgerId    int64                `json:"ledgerId,string"`
	BatchId     string               `json:"batchId"`
	PreviewHash string               `json:"previewHash"`
	Mappings    []AgentImportMapping `json:"mappings"`
}

type AgentImportConfirmRequest struct {
	LedgerId    int64  `json:"ledgerId,string"`
	BatchId     string `json:"batchId"`
	PreviewHash string `json:"previewHash"`
	Confirmed   bool   `json:"confirmed"`
}

type AgentImportRow struct {
	Row         int                        `json:"row"`
	Transaction *ImportTransactionResponse `json:"transaction"`
	Selected    bool                       `json:"selected"`
	Duplicate   bool                       `json:"duplicate"`
	Issue       string                     `json:"issue,omitempty"`
	Fingerprint string                     `json:"fingerprint"`
}

type AgentImportPreview struct {
	BatchId        string            `json:"batchId"`
	PreviewHash    string            `json:"previewHash"`
	ExpiresAt      int64             `json:"expiresAt"`
	LedgerId       string            `json:"ledgerId"`
	LedgerName     string            `json:"ledgerName"`
	Rows           []*AgentImportRow `json:"rows"`
	ReadyCount     int               `json:"readyCount"`
	DuplicateCount int               `json:"duplicateCount"`
	SelectedCount  int               `json:"selectedCount"`
	IncomeAmount   int64             `json:"incomeAmount"`
	ExpenseAmount  int64             `json:"expenseAmount"`
}
