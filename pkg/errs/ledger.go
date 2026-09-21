package errs

import "net/http"

// Error codes related to ledgers.
var (
	ErrLedgerNotFound           = NewNormalError(NormalSubcategoryLedger, 0, http.StatusBadRequest, "ledger not found")
	ErrLedgerNameInvalid        = NewNormalError(NormalSubcategoryLedger, 1, http.StatusBadRequest, "ledger name is invalid")
	ErrLedgerTypeInvalid        = NewNormalError(NormalSubcategoryLedger, 2, http.StatusBadRequest, "ledger type is invalid")
	ErrLedgerAccessDenied       = NewNormalError(NormalSubcategoryLedger, 3, http.StatusForbidden, "no permission to access this ledger")
	ErrLedgerFamilyIdRequired   = NewNormalError(NormalSubcategoryLedger, 4, http.StatusBadRequest, "a family ledger requires its family")
	ErrLedgerMemberNotFound     = NewNormalError(NormalSubcategoryLedger, 5, http.StatusBadRequest, "ledger member not found")
	ErrLedgerOwnerRoleImmutable = NewNormalError(NormalSubcategoryLedger, 6, http.StatusBadRequest, "ledger owner role cannot be changed")
	ErrLedgerOwnerCannotLeave   = NewNormalError(NormalSubcategoryLedger, 7, http.StatusBadRequest, "ledger owner cannot leave")
	ErrLedgerInvitationNotFound = NewNormalError(NormalSubcategoryLedger, 8, http.StatusBadRequest, "ledger invitation not found")
	ErrLedgerInvitationInvalid  = NewNormalError(NormalSubcategoryLedger, 9, http.StatusBadRequest, "ledger invitation is invalid or expired")
	ErrLedgerAlreadyMember      = NewNormalError(NormalSubcategoryLedger, 10, http.StatusBadRequest, "user is already a ledger member")
	ErrLedgerNotEmpty           = NewNormalError(NormalSubcategoryLedger, 11, http.StatusConflict, "ledger still contains financial data")
)
