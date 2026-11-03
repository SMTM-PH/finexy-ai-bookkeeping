package models

// LedgerReportMoney keeps original-currency minor units. Transfers and balance
// adjustments are separate from income/expense; net is income minus expense.
type LedgerReportMoney struct {
	Currency    string `json:"currency"`
	Income      int64  `json:"income,string"`
	Expense     int64  `json:"expense,string"`
	Net         int64  `json:"net,string"`
	Adjustment  int64  `json:"adjustment,string"`
	TransferIn  int64  `json:"transferIn,string"`
	TransferOut int64  `json:"transferOut,string"`
}

// LedgerReportLeg retains both sides of a logical transfer, even when only one
// side matches an account or currency filter. A missing external leg is null.
type LedgerReportLeg struct {
	AccountID   string `json:"accountId"`
	AccountName string `json:"accountName"`
	Currency    string `json:"currency"`
	Amount      int64  `json:"amount,string"`
}

type LedgerReportResource struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	ParentID string `json:"parentId,omitempty"`
	Hidden   bool   `json:"hidden"`
	Type     int32  `json:"type,omitempty"`
	Currency string `json:"currency,omitempty"`
}

type LedgerReportDetail struct {
	ID            string                 `json:"id"`
	PhysicalIDs   []string               `json:"physicalIds"`
	Type          int32                  `json:"type"`
	Time          int64                  `json:"time,string"`
	Sequence      int64                  `json:"sequence,string"`
	Date          string                 `json:"date"`
	CategoryID    string                 `json:"categoryId"`
	CategoryName  string                 `json:"categoryName"`
	Source        *LedgerReportLeg       `json:"source"`
	Destination   *LedgerReportLeg       `json:"destination"`
	Adjustment    int64                  `json:"adjustment,string"`
	BalanceTarget *int64                 `json:"balanceTarget,string,omitempty"`
	Tags          []LedgerReportResource `json:"tags"`
	Hidden        bool                   `json:"hidden"`
	Comment       string                 `json:"comment"`
	RecorderUID   string                 `json:"recorderUid"`
	PayerUID      string                 `json:"payerUid"`
}

type LedgerReportGroup struct {
	Key   string              `json:"key"` // local date, category ID, or tag ID; never a name
	Name  string              `json:"name,omitempty"`
	Count int                 `json:"count"`
	Money []LedgerReportMoney `json:"money"`
}

// LedgerReportSnapshot is a complete bounded result. Transport pagination must
// be applied only after its hash and aggregates have been computed.
type LedgerReportSnapshot struct {
	MemberRole        int32                        `json:"memberRole"`
	Query             *NormalizedLedgerReportQuery `json:"query"`
	SnapshotHash      string                       `json:"snapshotHash"`
	DataComplete      bool                         `json:"dataComplete"`
	MatchedCount      int                          `json:"matchedCount"`
	Details           []*LedgerReportDetail        `json:"details"`
	Totals            []LedgerReportMoney          `json:"totals"`
	Trend             []LedgerReportGroup          `json:"trend"`
	Categories        []LedgerReportGroup          `json:"categories"`
	TagGroups         []LedgerReportGroup          `json:"tagGroups"`
	TagGroupsAdditive bool                         `json:"tagGroupsAdditive"`
	ReadableTags      []LedgerReportResource       `json:"readableTags"`
	Accounts          []LedgerReportResource       `json:"accounts"`
	CategoryMetadata  []LedgerReportResource       `json:"categoryMetadata"`
}

type LedgerReportQueryRequest struct {
	Query                *LedgerReportQuery `json:"query"`
	Offset               int                `json:"offset"`
	Count                *int               `json:"count"`
	ExpectedSnapshotHash string             `json:"expectedSnapshotHash"`
}

// DataComplete describes the server's full aggregate source. DetailsComplete
// describes whether this transport page contains every matched logical row.
type LedgerReportPage struct {
	*LedgerReportSnapshot
	Offset          int  `json:"offset"`
	ReturnedCount   int  `json:"returnedCount"`
	NextOffset      *int `json:"nextOffset"`
	DetailsComplete bool `json:"detailsComplete"`
	ExportAllowed   bool `json:"exportAllowed"`
}

type LedgerReportExportPreviewRequest struct {
	Query                *LedgerReportQuery `json:"query"`
	ExpectedSnapshotHash string             `json:"expectedSnapshotHash"`
}

type LedgerReportExportRequest struct {
	Query                *LedgerReportQuery `json:"query"`
	ExpectedSnapshotHash string             `json:"expectedSnapshotHash"`
	PreviewToken         string             `json:"previewToken"`
	Confirmed            bool               `json:"confirmed"`
	IncludeHiddenAmounts bool               `json:"includeHiddenAmounts"`
}

type LedgerReportExportPreview struct {
	Query                 *NormalizedLedgerReportQuery `json:"query"`
	SnapshotHash          string                       `json:"snapshotHash"`
	MatchedCount          int                          `json:"matchedCount"`
	Totals                []LedgerReportMoney          `json:"totals"`
	AmountUnit            string                       `json:"amountUnit"`
	IncludesHiddenAmounts bool                         `json:"includesHiddenAmounts"`
	ExpiresAt             int64                        `json:"expiresAt,string"`
	PreviewToken          string                       `json:"previewToken"`
}
