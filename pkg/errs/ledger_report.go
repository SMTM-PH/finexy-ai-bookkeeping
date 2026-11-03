package errs

import "net/http"

var (
	ErrLedgerReportQueryInvalid         = NewNormalError(NormalSubcategoryLedgerReport, 0, http.StatusBadRequest, "Report query is invalid")
	ErrLedgerReportTooLarge             = NewNormalError(NormalSubcategoryLedgerReport, 1, http.StatusUnprocessableEntity, "Report exceeds the complete snapshot limit")
	ErrLedgerReportIncomplete           = NewNormalError(NormalSubcategoryLedgerReport, 2, http.StatusUnprocessableEntity, "Report metadata or transfer pair is incomplete")
	ErrLedgerReportResourceInvalid      = NewNormalError(NormalSubcategoryLedgerReport, 3, http.StatusBadRequest, "Report resource is outside the readable scope")
	ErrLedgerReportAmountOverflow       = NewNormalError(NormalSubcategoryLedgerReport, 4, http.StatusUnprocessableEntity, "Report amount exceeds the integer limit")
	ErrLedgerReportSnapshotChanged      = NewNormalError(NormalSubcategoryLedgerReport, 5, http.StatusConflict, "Report changed; review the preview again")
	ErrLedgerReportConfirmationRequired = NewNormalError(NormalSubcategoryLedgerReport, 6, http.StatusBadRequest, "Report export requires explicit preview confirmation")
	ErrLedgerReportPreviewInvalid       = NewNormalError(NormalSubcategoryLedgerReport, 7, http.StatusConflict, "Report export preview expired or is invalid; review it again")
)
