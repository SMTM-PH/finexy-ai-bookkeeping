package api

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
)

// LedgersApi represents ledger API handlers.
type LedgersApi struct {
	ledgers *services.LedgerService
}

// Ledgers is the ledger API singleton.
var Ledgers = &LedgersApi{ledgers: services.Ledgers}

// LedgerListHandler returns the visible ledgers of the current user.
func (a *LedgersApi) LedgerListHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.LedgerListRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	responses, err := a.ledgers.ListLedgers(c, c.GetCurrentUid(), request.FamilyId)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return responses, nil
}

func (a *LedgersApi) LedgerOverviewHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.LedgerOverviewRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	overview, err := a.ledgers.GetOverview(c, c.GetCurrentUid(), request.LedgerId)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return overview, nil
}

// LedgerMemberListHandler returns members attached directly to one ledger.
func (a *LedgersApi) LedgerMemberListHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.LedgerMemberListRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	responses, err := a.ledgers.ListMembers(c, c.GetCurrentUid(), request.LedgerId)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return responses, nil
}

func (a *LedgersApi) LedgerMemberRoleChangeHandler(c *core.WebContext) (any, *errs.Error) {
	var r models.LedgerMemberRoleChangeRequest
	if e := c.ShouldBindJSON(&r); e != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(e)
	}
	m, e := a.ledgers.ChangeMemberRole(c, c.GetCurrentUid(), &r)
	if e != nil {
		return nil, errs.Or(e, errs.ErrOperationFailed)
	}
	return m.ToLedgerMemberInfoResponse(""), nil
}
func (a *LedgersApi) LedgerMemberRemoveHandler(c *core.WebContext) (any, *errs.Error) {
	var r models.LedgerMemberRemoveRequest
	if e := c.ShouldBindJSON(&r); e != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(e)
	}
	if e := a.ledgers.RemoveMember(c, c.GetCurrentUid(), r.LedgerId, r.MemberId); e != nil {
		return nil, errs.Or(e, errs.ErrOperationFailed)
	}
	return nil, nil
}
func (a *LedgersApi) LedgerMemberLeaveHandler(c *core.WebContext) (any, *errs.Error) {
	var r models.LedgerMemberLeaveRequest
	if e := c.ShouldBindJSON(&r); e != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(e)
	}
	if e := a.ledgers.LeaveLedger(c, c.GetCurrentUid(), r.LedgerId); e != nil {
		return nil, errs.Or(e, errs.ErrOperationFailed)
	}
	return nil, nil
}
func (a *LedgersApi) LedgerInvitationCreateHandler(c *core.WebContext) (any, *errs.Error) {
	var r models.LedgerInvitationCreateRequest
	if e := c.ShouldBindJSON(&r); e != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(e)
	}
	item, e := a.ledgers.CreateInvitation(c, c.GetCurrentUid(), &r)
	if e != nil {
		return nil, errs.Or(e, errs.ErrOperationFailed)
	}
	return item.ToLedgerInvitationInfoResponse(), nil
}
func (a *LedgersApi) LedgerInvitationListHandler(c *core.WebContext) (any, *errs.Error) {
	var r models.LedgerInvitationListRequest
	if e := c.ShouldBindQuery(&r); e != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(e)
	}
	items, e := a.ledgers.ListInvitations(c, c.GetCurrentUid(), r.LedgerId)
	if e != nil {
		return nil, errs.Or(e, errs.ErrOperationFailed)
	}
	return items, nil
}
func (a *LedgersApi) LedgerInvitationRevokeHandler(c *core.WebContext) (any, *errs.Error) {
	var r models.LedgerInvitationRevokeRequest
	if e := c.ShouldBindJSON(&r); e != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(e)
	}
	if e := a.ledgers.RevokeInvitation(c, c.GetCurrentUid(), r.LedgerId, r.InvitationId); e != nil {
		return nil, errs.Or(e, errs.ErrOperationFailed)
	}
	return nil, nil
}
func (a *LedgersApi) LedgerInvitationPreviewHandler(c *core.WebContext) (any, *errs.Error) {
	var r models.LedgerInvitationAcceptRequest
	if e := c.ShouldBindJSON(&r); e != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(e)
	}
	preview, e := a.ledgers.PreviewInvitation(c, c.GetCurrentUid(), r.Token)
	if e != nil {
		return nil, errs.Or(e, errs.ErrOperationFailed)
	}
	return preview, nil
}
func (a *LedgersApi) LedgerInvitationAcceptHandler(c *core.WebContext) (any, *errs.Error) {
	var r models.LedgerInvitationAcceptRequest
	if e := c.ShouldBindJSON(&r); e != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(e)
	}
	ledger, e := a.ledgers.AcceptInvitation(c, c.GetCurrentUid(), r.Token)
	if e != nil {
		return nil, errs.Or(e, errs.ErrOperationFailed)
	}
	return ledger.ToLedgerInfoResponse(), nil
}

// LedgerCreateHandler creates a personal or family ledger.
func (a *LedgersApi) LedgerCreateHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.LedgerCreateRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	ledger, err := a.ledgers.CreateLedger(c, c.GetCurrentUid(), &request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return ledger.ToLedgerInfoResponse(), nil
}

// LedgerModifyHandler renames one ledger.
func (a *LedgersApi) LedgerModifyHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.LedgerModifyRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	ledger, err := a.ledgers.ModifyLedger(c, c.GetCurrentUid(), &request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return ledger.ToLedgerInfoResponse(), nil
}

func (a *LedgersApi) LedgerDeletePreviewHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.LedgerDeletePreviewRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	preview, err := a.ledgers.GetDeletePreview(c, c.GetCurrentUid(), request.Id)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return preview, nil
}

// LedgerDeleteHandler soft deletes one ledger.
func (a *LedgersApi) LedgerDeleteHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.LedgerDeleteRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	if err := a.ledgers.DeleteLedger(c, c.GetCurrentUid(), request.Id); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return nil, nil
}
