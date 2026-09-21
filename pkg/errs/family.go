package errs

import "net/http"

// Error codes related to families and their members and invitations.
var (
	ErrFamilyNotFound           = NewNormalError(NormalSubcategoryFamily, 0, http.StatusBadRequest, "family not found")
	ErrFamilyNameInvalid        = NewNormalError(NormalSubcategoryFamily, 1, http.StatusBadRequest, "family name is invalid")
	ErrFamilyAccessDenied       = NewNormalError(NormalSubcategoryFamily, 2, http.StatusForbidden, "no permission to access this family")
	ErrFamilyMemberNotFound     = NewNormalError(NormalSubcategoryFamily, 3, http.StatusBadRequest, "family member not found")
	ErrFamilyMemberRoleInvalid  = NewNormalError(NormalSubcategoryFamily, 4, http.StatusBadRequest, "family member role is invalid")
	ErrFamilyOwnerRoleImmutable = NewNormalError(NormalSubcategoryFamily, 5, http.StatusBadRequest, "the owner role of a family cannot be changed or removed")
	ErrFamilyOwnerCannotLeave   = NewNormalError(NormalSubcategoryFamily, 6, http.StatusBadRequest, "the owner cannot leave the family")
	ErrFamilyInvitationNotFound = NewNormalError(NormalSubcategoryFamily, 7, http.StatusBadRequest, "family invitation not found")
	ErrFamilyInvitationInvalid  = NewNormalError(NormalSubcategoryFamily, 8, http.StatusBadRequest, "family invitation is not acceptable")
	ErrFamilyAlreadyMember      = NewNormalError(NormalSubcategoryFamily, 9, http.StatusBadRequest, "the user has already joined this family")
	ErrFamilyLimitReached       = NewNormalError(NormalSubcategoryFamily, 10, http.StatusBadRequest, "the maximum number of families has been reached")
)
