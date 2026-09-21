package api

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
)

// FamiliesApi represents family API handlers.
type FamiliesApi struct {
	families *services.FamilyService
}

// Families is the family API singleton.
var Families = &FamiliesApi{families: services.Families}

// FamilyCreateHandler creates a family with the current user as its owner.
func (a *FamiliesApi) FamilyCreateHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyGroupCreateRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	group, err := a.families.CreateFamilyGroup(c, c.GetCurrentUid(), &request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return group.ToFamilyGroupInfoResponse(1), nil
}

// FamilyListHandler returns every family of the current user.
func (a *FamiliesApi) FamilyListHandler(c *core.WebContext) (any, *errs.Error) {
	responses, err := a.families.ListFamilyGroups(c, c.GetCurrentUid())
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return responses, nil
}

// FamilyModifyHandler renames a family. Managers only.
func (a *FamiliesApi) FamilyModifyHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyGroupModifyRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	group, err := a.families.ModifyFamilyGroup(c, c.GetCurrentUid(), &request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return group.ToFamilyGroupInfoResponse(0), nil
}

// FamilyDeleteHandler disbands a family. Only the owner may delete it.
func (a *FamiliesApi) FamilyDeleteHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyGroupDeleteRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	if err := a.families.DeleteFamilyGroup(c, c.GetCurrentUid(), request.Id); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return nil, nil
}

// FamilyMemberListHandler returns the members of one family.
func (a *FamiliesApi) FamilyMemberListHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyMemberListRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	responses, err := a.families.ListMembers(c, c.GetCurrentUid(), request.FamilyId)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return responses, nil
}

// FamilyMemberMeHandler returns the calling user's own membership of one
// family, or null when the user has no active membership there.
func (a *FamiliesApi) FamilyMemberMeHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyMemberListRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	member, err := a.families.GetActiveMember(c, c.GetCurrentUid(), request.FamilyId)
	if err == errs.ErrFamilyAccessDenied || err == errs.ErrFamilyNotFound {
		return nil, nil
	}
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	nickname, err := a.families.GetUserNickname(c, member.Uid)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return member.ToFamilyMemberInfoResponse(nickname), nil
}

// FamilyMemberRoleChangeHandler changes the role of one member. Owner only.
func (a *FamiliesApi) FamilyMemberRoleChangeHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyMemberRoleChangeRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	member, err := a.families.ChangeMemberRole(c, c.GetCurrentUid(), &request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	nickname, err := a.families.GetUserNickname(c, member.Uid)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return member.ToFamilyMemberInfoResponse(nickname), nil
}

// FamilyMemberRemoveHandler removes one member. Managers only.
func (a *FamiliesApi) FamilyMemberRemoveHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyMemberRemoveRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	if err := a.families.RemoveMember(c, c.GetCurrentUid(), request.FamilyId, request.MemberId); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return nil, nil
}

// FamilyMemberLeaveHandler lets the current user leave a family.
func (a *FamiliesApi) FamilyMemberLeaveHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyMemberLeaveRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	if err := a.families.LeaveFamily(c, c.GetCurrentUid(), request.FamilyId); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return nil, nil
}

// FamilyInvitationCreateHandler creates a one-shot invitation. Managers only.
func (a *FamiliesApi) FamilyInvitationCreateHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyInvitationCreateRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	invitation, err := a.families.CreateInvitation(c, c.GetCurrentUid(), &request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return invitation.ToFamilyInvitationInfoResponse(), nil
}

// FamilyInvitationListHandler returns the invitations of one family.
func (a *FamiliesApi) FamilyInvitationListHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyInvitationListRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	responses, err := a.families.ListInvitations(c, c.GetCurrentUid(), request.FamilyId)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return responses, nil
}

// FamilyInvitationRevokeHandler revokes one pending invitation.
func (a *FamiliesApi) FamilyInvitationRevokeHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyInvitationRevokeRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	if err := a.families.RevokeInvitation(c, c.GetCurrentUid(), request.FamilyId, request.InvitationId); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return nil, nil
}

// FamilyInvitationAcceptHandler joins a family with an invitation token.
func (a *FamiliesApi) FamilyInvitationAcceptHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.FamilyInvitationAcceptRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	group, err := a.families.AcceptInvitation(c, c.GetCurrentUid(), request.Token)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return group.ToFamilyGroupInfoResponse(0), nil
}
