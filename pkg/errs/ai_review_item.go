package errs

import "net/http"

var (
	ErrAIReviewItemSourceTypeInvalid = NewNormalError(NormalSubcategoryAIReviewItem, 0, http.StatusBadRequest, "AI review item source type is invalid")
	ErrAIReviewItemTextInvalid       = NewNormalError(NormalSubcategoryAIReviewItem, 1, http.StatusBadRequest, "AI review item text is invalid")
	ErrAIReviewItemNotFound          = NewNormalError(NormalSubcategoryAIReviewItem, 2, http.StatusNotFound, "AI review item not found")
	ErrAIReviewItemConflict          = NewNormalError(NormalSubcategoryAIReviewItem, 3, http.StatusConflict, "AI review item changed or confirmation content does not match")
	ErrAIReviewConfirmationRequired  = NewNormalError(NormalSubcategoryAIReviewItem, 4, http.StatusConflict, "AI review confirmation must atomically create the transaction")
)
