package errs

import "net/http"

var (
	ErrAIReportYearMonthInvalid = NewNormalError(NormalSubcategoryAIReport, 0, http.StatusBadRequest, "AI report year and month is invalid")
	ErrAIReportNotFound         = NewNormalError(NormalSubcategoryAIReport, 1, http.StatusNotFound, "AI report not found")
)
