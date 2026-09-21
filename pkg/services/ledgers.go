package services

import (
	"errors"
	"sort"
	"time"
	"xorm.io/xorm"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
)

// LedgerService manages personal and family ledgers. Ledger id zero is the
// implicit default personal ledger of a user and never needs a row.
//
// Personal ledgers live in the owner's shard; family ledgers live in the
// family owner's shard next to the other family-domain rows.
type LedgerService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

// Ledgers is the ledger service singleton.
var Ledgers = &LedgerService{
	ServiceUsingDB:   ServiceUsingDB{container: datastore.Container},
	ServiceUsingUuid: ServiceUsingUuid{container: uuid.Container},
}

// locateLedger returns the shard and row holding one ledger.
func (s *LedgerService) locateLedger(c core.Context, ledgerId int64) (*datastore.Database, *models.Ledger, error) {
	if ledgerId <= 0 {
		return nil, nil, errs.ErrParameterInvalid
	}

	for i := 0; i < s.UserDataDBCount(); i++ {
		db := s.UserDataDBByIndex(i)
		ledger := &models.Ledger{}
		has, err := db.NewSession(c).ID(ledgerId).Where("deleted=?", false).Get(ledger)
		if err != nil {
			return nil, nil, err
		}
		if has {
			return db, ledger, nil
		}
	}
	return nil, nil, errs.ErrLedgerNotFound
}

// GetLedgerWithAccess returns one ledger and its shard when the membership of
// the user grants the capability. The implicit default personal ledger is
// granted to its owner without a database row.
func (s *LedgerService) GetLedgerWithAccess(c core.Context, uid, ledgerId int64, capability func(models.FamilyMemberRole) bool) (*datastore.Database, *models.Ledger, error) {
	if uid <= 0 {
		return nil, nil, errs.ErrUserIdInvalid
	}
	if ledgerId == models.DefaultLedgerId {
		return nil, &models.Ledger{LedgerId: models.DefaultLedgerId, OwnerUid: uid, Type: models.LEDGER_TYPE_PERSONAL}, nil
	}

	db, ledger, err := s.locateLedger(c, ledgerId)
	if err != nil {
		return nil, nil, err
	}

	member := &models.LedgerMember{}
	hasMember, memberErr := db.NewSession(c).Where("ledger_id=? AND uid=?", ledgerId, uid).Get(member)
	if memberErr != nil {
		return nil, nil, memberErr
	}
	if hasMember {
		if member.CanAct(capability) {
			return db, ledger, nil
		}
		return nil, nil, errs.ErrLedgerAccessDenied
	}

	if ledger.Type == models.LEDGER_TYPE_PERSONAL {
		// Compatibility for personal ledgers created before LedgerMember.
		if ledger.OwnerUid == uid {
			return db, ledger, nil
		}
		return nil, nil, errs.ErrLedgerAccessDenied
	}

	if _, _, err := Families.locateFamilyDB(c, uid, ledger.FamilyId, capability); err != nil {
		return nil, nil, errs.ErrLedgerAccessDenied
	}
	return db, ledger, nil
}

// LedgerDataOwnerUid is the transaction row owner in a ledger's shard. A
// family ledger may have been created by an administrator, while its rows
// still live in the family owner's shard.
func (s *LedgerService) LedgerDataOwnerUid(c core.Context, uid int64, ledger *models.Ledger) (int64, error) {
	if ledger.Type != models.LEDGER_TYPE_FAMILY {
		return ledger.OwnerUid, nil
	}
	family, err := Families.GetFamilyGroup(c, uid, ledger.FamilyId)
	if err != nil {
		return 0, err
	}
	return family.OwnerUid, nil
}

// CreateLedger creates a personal or family ledger. Family ledgers require a
// managing role in the referenced family and are stored in that family's
// shard.
func (s *LedgerService) CreateLedger(c core.Context, uid int64, request *models.LedgerCreateRequest) (*models.Ledger, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	ledger := &models.Ledger{
		OwnerUid: uid,
		Type:     request.Type,
		FamilyId: request.FamilyId,
		Name:     request.Name,
		Comment:  request.Comment,
	}

	switch {
	case !ledger.Type.IsValid():
		return nil, errs.ErrLedgerTypeInvalid
	case ledger.Type == models.LEDGER_TYPE_FAMILY && ledger.FamilyId <= 0:
		return nil, errs.ErrLedgerFamilyIdRequired
	case len(ledger.Name) == 0 || len(ledger.Name) > models.MaximumNameLength:
		return nil, errs.ErrLedgerNameInvalid
	}
	if err := ledger.Validate(); err != nil {
		return nil, errs.ErrLedgerNameInvalid
	}

	var db *datastore.Database
	if ledger.Type == models.LEDGER_TYPE_FAMILY {
		var err error
		db, _, err = Families.locateFamilyDB(c, uid, ledger.FamilyId, models.FamilyMemberRole.CanManage)
		if err != nil {
			return nil, err
		}
	} else {
		db = s.UserDataDB(uid)
	}

	now := time.Now().Unix()
	ledger.LedgerId = s.GenerateUuid(uuid.UUID_TYPE_FAMILY)
	ledger.CreatedUnixTime = now
	ledger.UpdatedUnixTime = now
	if ledger.LedgerId < 1 {
		return nil, errs.ErrSystemIsBusy
	}

	err := db.DoTransaction(c, func(sess *xorm.Session) error {
		if _, err := sess.Insert(ledger); err != nil {
			return err
		}
		members := make([]*models.LedgerMember, 0, 1)
		if ledger.Type == models.LEDGER_TYPE_FAMILY {
			legacyMembers := make([]*models.FamilyMember, 0)
			if err := sess.Where("family_id=? AND status=?", ledger.FamilyId, models.FAMILY_MEMBER_STATUS_ACTIVE).Find(&legacyMembers); err != nil {
				return err
			}
			for _, legacy := range legacyMembers {
				members = append(members, &models.LedgerMember{LedgerMemberId: s.GenerateUuid(uuid.UUID_TYPE_FAMILY), LedgerId: ledger.LedgerId, Uid: legacy.Uid, Status: legacy.Status, Role: legacy.Role, CreatedUnixTime: now, UpdatedUnixTime: now})
			}
		} else {
			members = append(members, &models.LedgerMember{LedgerMemberId: s.GenerateUuid(uuid.UUID_TYPE_FAMILY), LedgerId: ledger.LedgerId, Uid: uid, Status: models.FAMILY_MEMBER_STATUS_ACTIVE, Role: models.FAMILY_MEMBER_ROLE_OWNER, CreatedUnixTime: now, UpdatedUnixTime: now})
		}
		for i := range members {
			if members[i].LedgerMemberId < 1 || members[i].Validate() != nil {
				return errs.ErrSystemIsBusy
			}
			if _, err := sess.Insert(members[i]); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return ledger, nil
}

// ListLedgers returns the personal ledgers of the user and the family ledgers
// of every family the user actively belongs to, optionally filtered to one
// family.
func (s *LedgerService) ListLedgers(c core.Context, uid int64, familyId int64) ([]*models.LedgerInfoResponse, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	ledgers := make([]*models.Ledger, 0)

	if familyId > 0 {
		db, _, err := Families.locateFamilyDB(c, uid, familyId, func(models.FamilyMemberRole) bool { return true })
		if err != nil {
			return nil, err
		}

		err = db.NewSession(c).
			Where("deleted=? AND type=? AND family_id=?", false, models.LEDGER_TYPE_FAMILY, familyId).
			OrderBy("created_unix_time").
			Find(&ledgers)
		if err != nil {
			return nil, err
		}
	} else {
		err := s.UserDataDB(uid).NewSession(c).
			Where("deleted=? AND type=? AND owner_uid=?", false, models.LEDGER_TYPE_PERSONAL, uid).
			OrderBy("created_unix_time").
			Find(&ledgers)
		if err != nil {
			return nil, err
		}

		// LedgerMember is the canonical source for newly shared ledgers.
		for i := 0; i < s.UserDataDBCount(); i++ {
			db := s.UserDataDBByIndex(i)
			members := make([]*models.LedgerMember, 0)
			if err := db.NewSession(c).Where("uid=? AND status=?", uid, models.FAMILY_MEMBER_STATUS_ACTIVE).Find(&members); err != nil {
				return nil, err
			}
			for _, member := range members {
				ledger := &models.Ledger{}
				has, err := db.NewSession(c).ID(member.LedgerId).Where("deleted=?", false).Get(ledger)
				if err != nil {
					return nil, err
				}
				if has {
					ledgers = append(ledgers, ledger)
				}
			}
		}

		// Family ledgers are co-located with the membership rows, so every
		// shard that holds a membership of this user also holds the family
		// ledgers of the families the user belongs to.
		for i := 0; i < s.UserDataDBCount(); i++ {
			db := s.UserDataDBByIndex(i)

			members := make([]*models.FamilyMember, 0)
			err := db.NewSession(c).
				Where("uid=? AND status=?", uid, models.FAMILY_MEMBER_STATUS_ACTIVE).
				Find(&members)
			if err != nil {
				return nil, err
			}

			for _, member := range members {
				familyLedgers := make([]*models.Ledger, 0)
				err := db.NewSession(c).
					Where("deleted=? AND type=? AND family_id=?", false, models.LEDGER_TYPE_FAMILY, member.FamilyId).
					OrderBy("created_unix_time").
					Find(&familyLedgers)
				if err != nil {
					return nil, err
				}
				ledgers = append(ledgers, familyLedgers...)
			}
		}
	}

	responses := make([]*models.LedgerInfoResponse, 0, len(ledgers))
	seen := make(map[int64]bool, len(ledgers))
	for _, ledger := range ledgers {
		if seen[ledger.LedgerId] {
			continue
		}
		seen[ledger.LedgerId] = true
		// Legacy family discovery must not re-expose explicitly removed members.
		if _, _, err := s.GetLedgerWithAccess(c, uid, ledger.LedgerId, func(models.FamilyMemberRole) bool { return true }); err != nil {
			if errors.Is(err, errs.ErrLedgerAccessDenied) {
				continue
			}
			return nil, err
		}
		responses = append(responses, ledger.ToLedgerInfoResponse())
	}
	return responses, nil
}

// ListMembers returns the canonical members of a ledger. For an old family
// ledger without migrated rows it materializes a read-compatible response
// from the legacy family memberships without changing stored data.
func (s *LedgerService) ListMembers(c core.Context, uid, ledgerId int64) ([]*models.LedgerMemberInfoResponse, error) {
	db, ledger, err := s.GetLedgerWithAccess(c, uid, ledgerId, func(models.FamilyMemberRole) bool { return true })
	if err != nil {
		return nil, err
	}
	members := make([]*models.LedgerMember, 0)
	if err := db.NewSession(c).Where("ledger_id=?", ledgerId).OrderBy("created_unix_time").Find(&members); err != nil {
		return nil, err
	}
	if len(members) == 0 && ledger.Type == models.LEDGER_TYPE_FAMILY {
		legacy := make([]*models.FamilyMember, 0)
		if err := db.NewSession(c).Where("family_id=?", ledger.FamilyId).OrderBy("created_unix_time").Find(&legacy); err != nil {
			return nil, err
		}
		for _, item := range legacy {
			members = append(members, &models.LedgerMember{LedgerMemberId: item.FamilyMemberId, LedgerId: ledgerId, Uid: item.Uid, Status: item.Status, Role: item.Role, CreatedUnixTime: item.CreatedUnixTime, UpdatedUnixTime: item.UpdatedUnixTime})
		}
	} else if len(members) == 0 && ledger.Type == models.LEDGER_TYPE_PERSONAL {
		// Explicit personal ledgers created before this table existed expose
		// their owner as the initial member until the migration is persisted.
		members = append(members, &models.LedgerMember{LedgerId: ledgerId, Uid: ledger.OwnerUid, Status: models.FAMILY_MEMBER_STATUS_ACTIVE, Role: models.FAMILY_MEMBER_ROLE_OWNER, CreatedUnixTime: ledger.CreatedUnixTime, UpdatedUnixTime: ledger.UpdatedUnixTime})
	}
	legacyForNames := make([]*models.FamilyMember, 0, len(members))
	for _, member := range members {
		legacyForNames = append(legacyForNames, &models.FamilyMember{Uid: member.Uid})
	}
	nicknames, err := Families.getNicknames(c, legacyForNames)
	if err != nil {
		return nil, err
	}
	responses := make([]*models.LedgerMemberInfoResponse, 0, len(members))
	for _, member := range members {
		response := member.ToLedgerMemberInfoResponse(nicknames[member.Uid])
		response.IsCurrentUser = member.Uid == uid
		responses = append(responses, response)
	}
	return responses, nil
}

func (s *LedgerService) ensureMembers(c core.Context, db *datastore.Database, ledger *models.Ledger) error {
	count, err := db.NewSession(c).Where("ledger_id=?", ledger.LedgerId).Count(new(models.LedgerMember))
	if err != nil || count > 0 {
		return err
	}
	now := time.Now().Unix()
	members := make([]*models.LedgerMember, 0)
	if ledger.Type == models.LEDGER_TYPE_FAMILY {
		legacy := make([]*models.FamilyMember, 0)
		if err := db.NewSession(c).Where("family_id=?", ledger.FamilyId).Find(&legacy); err != nil {
			return err
		}
		for _, item := range legacy {
			members = append(members, &models.LedgerMember{LedgerMemberId: s.GenerateUuid(uuid.UUID_TYPE_FAMILY), LedgerId: ledger.LedgerId, Uid: item.Uid, Status: item.Status, Role: item.Role, CreatedUnixTime: item.CreatedUnixTime, UpdatedUnixTime: now})
		}
	} else {
		members = append(members, &models.LedgerMember{LedgerMemberId: s.GenerateUuid(uuid.UUID_TYPE_FAMILY), LedgerId: ledger.LedgerId, Uid: ledger.OwnerUid, Status: models.FAMILY_MEMBER_STATUS_ACTIVE, Role: models.FAMILY_MEMBER_ROLE_OWNER, CreatedUnixTime: ledger.CreatedUnixTime, UpdatedUnixTime: now})
	}
	if len(members) > 0 {
		_, err = db.NewSession(c).Insert(members)
	}
	return err
}

func (s *LedgerService) ChangeMemberRole(c core.Context, uid int64, request *models.LedgerMemberRoleChangeRequest) (*models.LedgerMember, error) {
	db, ledger, err := s.GetLedgerWithAccess(c, uid, request.LedgerId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}
	if err := s.ensureMembers(c, db, ledger); err != nil {
		return nil, err
	}
	member := &models.LedgerMember{}
	has, err := db.NewSession(c).ID(request.MemberId).Where("ledger_id=?", request.LedgerId).Get(member)
	if err != nil {
		return nil, err
	}
	if !has {
		return nil, errs.ErrLedgerMemberNotFound
	}
	if member.Role == models.FAMILY_MEMBER_ROLE_OWNER {
		return nil, errs.ErrLedgerOwnerRoleImmutable
	}
	if !request.Role.IsValid() || request.Role == models.FAMILY_MEMBER_ROLE_OWNER {
		return nil, errs.ErrParameterInvalid
	}
	if member.Status != models.FAMILY_MEMBER_STATUS_ACTIVE {
		return nil, errs.ErrLedgerMemberNotFound
	}
	if member.Role == models.FAMILY_MEMBER_ROLE_ADMIN || request.Role == models.FAMILY_MEMBER_ROLE_ADMIN {
		if _, _, err := s.GetLedgerWithAccess(c, uid, request.LedgerId, func(role models.FamilyMemberRole) bool {
			return role == models.FAMILY_MEMBER_ROLE_OWNER
		}); err != nil {
			return nil, err
		}
	}
	member.Role = request.Role
	member.UpdatedUnixTime = time.Now().Unix()
	_, err = db.NewSession(c).ID(member.LedgerMemberId).Cols("role", "updated_unix_time").Update(member)
	return member, err
}

func (s *LedgerService) RemoveMember(c core.Context, uid, ledgerId, memberId int64) error {
	db, ledger, err := s.GetLedgerWithAccess(c, uid, ledgerId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return err
	}
	if err = s.ensureMembers(c, db, ledger); err != nil {
		return err
	}
	member := &models.LedgerMember{}
	has, err := db.NewSession(c).ID(memberId).Where("ledger_id=?", ledgerId).Get(member)
	if err != nil {
		return err
	}
	if !has {
		return errs.ErrLedgerMemberNotFound
	}
	if member.Role == models.FAMILY_MEMBER_ROLE_OWNER {
		return errs.ErrLedgerOwnerRoleImmutable
	}
	if member.Role == models.FAMILY_MEMBER_ROLE_ADMIN {
		if _, _, err := s.GetLedgerWithAccess(c, uid, ledgerId, func(role models.FamilyMemberRole) bool {
			return role == models.FAMILY_MEMBER_ROLE_OWNER
		}); err != nil {
			return err
		}
	}
	_, err = db.NewSession(c).ID(memberId).Cols("status", "updated_unix_time").Update(&models.LedgerMember{Status: models.FAMILY_MEMBER_STATUS_REMOVED, UpdatedUnixTime: time.Now().Unix()})
	return err
}

func (s *LedgerService) LeaveLedger(c core.Context, uid, ledgerId int64) error {
	db, ledger, err := s.GetLedgerWithAccess(c, uid, ledgerId, func(models.FamilyMemberRole) bool { return true })
	if err != nil {
		return err
	}
	if err = s.ensureMembers(c, db, ledger); err != nil {
		return err
	}
	member := &models.LedgerMember{}
	has, err := db.NewSession(c).Where("ledger_id=? AND uid=?", ledgerId, uid).Get(member)
	if err != nil {
		return err
	}
	if !has {
		return errs.ErrLedgerMemberNotFound
	}
	if member.Role == models.FAMILY_MEMBER_ROLE_OWNER {
		return errs.ErrLedgerOwnerCannotLeave
	}
	_, err = db.NewSession(c).ID(member.LedgerMemberId).Cols("status", "updated_unix_time").Update(&models.LedgerMember{Status: models.FAMILY_MEMBER_STATUS_LEFT, UpdatedUnixTime: time.Now().Unix()})
	return err
}

func (s *LedgerService) CreateInvitation(c core.Context, uid int64, request *models.LedgerInvitationCreateRequest) (*models.LedgerInvitation, error) {
	db, _, err := s.GetLedgerWithAccess(c, uid, request.LedgerId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}
	expires := request.ExpiresInSeconds
	if expires <= 0 {
		expires = DefaultInvitationExpiresInSeconds
	}
	now := time.Now().Unix()
	for attempt := 0; attempt < 3; attempt++ {
		token, e := utils.GetRandomNumberOrLowercaseLetter(FamilyInvitationTokenLength)
		if e != nil {
			return nil, e
		}
		item := &models.LedgerInvitation{InvitationId: s.GenerateUuid(uuid.UUID_TYPE_FAMILY), LedgerId: request.LedgerId, InviterUid: uid, InviteeName: request.InviteeName, Role: request.Role, Token: token, Status: models.FAMILY_INVITATION_STATUS_PENDING, CreatedUnixTime: now, ExpiredUnixTime: now + expires}
		if item.Validate() != nil {
			return nil, errs.ErrLedgerInvitationInvalid
		}
		if _, e = db.NewSession(c).Insert(item); e == nil {
			return item, nil
		}
	}
	return nil, errs.ErrSystemIsBusy
}

func (s *LedgerService) ListInvitations(c core.Context, uid, ledgerId int64) ([]*models.LedgerInvitationInfoResponse, error) {
	db, _, err := s.GetLedgerWithAccess(c, uid, ledgerId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}
	items := make([]*models.LedgerInvitation, 0)
	if err = db.NewSession(c).Where("ledger_id=?", ledgerId).OrderBy("created_unix_time DESC").Find(&items); err != nil {
		return nil, err
	}
	out := make([]*models.LedgerInvitationInfoResponse, 0, len(items))
	for _, item := range items {
		out = append(out, item.ToLedgerInvitationInfoResponse())
	}
	return out, nil
}

func (s *LedgerService) RevokeInvitation(c core.Context, uid, ledgerId, invitationId int64) error {
	db, _, err := s.GetLedgerWithAccess(c, uid, ledgerId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return err
	}
	item := &models.LedgerInvitation{}
	has, err := db.NewSession(c).ID(invitationId).Where("ledger_id=?", ledgerId).Get(item)
	if err != nil {
		return err
	}
	if !has {
		return errs.ErrLedgerInvitationNotFound
	}
	if item.Status != models.FAMILY_INVITATION_STATUS_PENDING {
		return errs.ErrLedgerInvitationInvalid
	}
	_, err = db.NewSession(c).ID(invitationId).Cols("status").Update(&models.LedgerInvitation{Status: models.FAMILY_INVITATION_STATUS_REVOKED})
	return err
}

func (s *LedgerService) findInvitation(c core.Context, token string) (*datastore.Database, *models.LedgerInvitation, error) {
	for i := 0; i < s.UserDataDBCount(); i++ {
		candidate := s.UserDataDBByIndex(i)
		item := &models.LedgerInvitation{}
		has, err := candidate.NewSession(c).Where("token=?", token).Get(item)
		if err != nil {
			return nil, nil, err
		}
		if has {
			return candidate, item, nil
		}
	}
	return nil, nil, errs.ErrLedgerInvitationNotFound
}

// PreviewInvitation validates a token without consuming it and returns the
// ledger, granted role and expiry shown by clients before the final join.
func (s *LedgerService) PreviewInvitation(c core.Context, uid int64, token string) (*models.LedgerInvitationPreviewResponse, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}
	db, item, err := s.findInvitation(c, token)
	if err != nil {
		return nil, err
	}
	if !item.CanBeAccepted(time.Now().Unix()) {
		return nil, errs.ErrLedgerInvitationInvalid
	}
	ledger := &models.Ledger{}
	has, err := db.NewSession(c).ID(item.LedgerId).Where("deleted=?", false).Get(ledger)
	if err != nil {
		return nil, err
	}
	if !has {
		return nil, errs.ErrLedgerNotFound
	}
	nickname, err := Families.GetUserNickname(c, item.InviterUid)
	if err != nil {
		return nil, err
	}
	return &models.LedgerInvitationPreviewResponse{
		Ledger: ledger.ToLedgerInfoResponse(), Role: item.Role, InviteeName: item.InviteeName,
		InviterNickname: nickname, ExpiredTime: item.ExpiredUnixTime,
	}, nil
}

func (s *LedgerService) AcceptInvitation(c core.Context, uid int64, token string) (*models.Ledger, error) {
	db, item, err := s.findInvitation(c, token)
	if err != nil {
		return nil, err
	}
	now := time.Now().Unix()
	if !item.CanBeAccepted(now) {
		return nil, errs.ErrLedgerInvitationInvalid
	}
	ledger := &models.Ledger{}
	has, err := db.NewSession(c).ID(item.LedgerId).Where("deleted=?", false).Get(ledger)
	if err != nil || !has {
		if err != nil {
			return nil, err
		}
		return nil, errs.ErrLedgerNotFound
	}
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		existing := &models.LedgerMember{}
		has, e := sess.Where("ledger_id=? AND uid=?", item.LedgerId, uid).Get(existing)
		if e != nil {
			return e
		}
		if has && existing.Status == models.FAMILY_MEMBER_STATUS_ACTIVE {
			return errs.ErrLedgerAlreadyMember
		}
		if has {
			existing.Status = models.FAMILY_MEMBER_STATUS_ACTIVE
			existing.Role = item.Role
			existing.UpdatedUnixTime = now
			_, e = sess.ID(existing.LedgerMemberId).Cols("status", "role", "updated_unix_time").Update(existing)
			if e != nil {
				return e
			}
		} else {
			member := &models.LedgerMember{LedgerMemberId: s.GenerateUuid(uuid.UUID_TYPE_FAMILY), LedgerId: item.LedgerId, Uid: uid, Status: models.FAMILY_MEMBER_STATUS_ACTIVE, Role: item.Role, CreatedUnixTime: now, UpdatedUnixTime: now}
			if _, e = sess.Insert(member); e != nil {
				return e
			}
		}
		item.Status = models.FAMILY_INVITATION_STATUS_ACCEPTED
		item.UsedUnixTime = now
		item.UsedByUid = uid
		_, e = sess.ID(item.InvitationId).Cols("status", "used_unix_time", "used_by_uid").Update(item)
		return e
	})
	if err != nil {
		return nil, err
	}
	return ledger, nil
}

// ModifyLedger renames one ledger. Family ledgers require a managing role.
func (s *LedgerService) ModifyLedger(c core.Context, uid int64, request *models.LedgerModifyRequest) (*models.Ledger, error) {
	db, ledger, err := s.GetLedgerWithAccess(c, uid, request.Id, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}

	name := &models.Ledger{Name: request.Name, Comment: request.Comment, OwnerUid: ledger.OwnerUid, Type: ledger.Type, FamilyId: ledger.FamilyId}
	if err := name.Validate(); err != nil {
		return nil, errs.ErrLedgerNameInvalid
	}

	now := time.Now().Unix()
	rows, err := db.NewSession(c).ID(request.Id).
		Cols("name", "comment", "updated_unix_time").
		Where("deleted=?", false).
		Update(&models.Ledger{Name: request.Name, Comment: request.Comment, UpdatedUnixTime: now})
	if err != nil {
		return nil, err
	}
	if rows < 1 {
		return nil, errs.ErrLedgerNotFound
	}

	_, updated, err := s.GetLedgerWithAccess(c, uid, request.Id, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}
	return updated, nil
}

func (s *LedgerService) GetDeletePreview(c core.Context, uid, ledgerId int64) (*models.LedgerDeletePreviewResponse, error) {
	if ledgerId == models.DefaultLedgerId {
		return nil, errs.ErrLedgerAccessDenied
	}
	db, ledger, err := s.GetLedgerWithAccess(c, uid, ledgerId, func(role models.FamilyMemberRole) bool {
		return role == models.FAMILY_MEMBER_ROLE_OWNER
	})
	if err != nil {
		return nil, err
	}
	if err := s.ensureMembers(c, db, ledger); err != nil {
		return nil, err
	}

	preview := &models.LedgerDeletePreviewResponse{LedgerId: ledgerId, LedgerName: ledger.Name, BlockingReasons: make([]string, 0)}
	sess := db.NewSession(c)
	defer sess.Close()
	if preview.ActiveMemberCount, err = sess.Where("ledger_id=? AND status=?", ledgerId, models.FAMILY_MEMBER_STATUS_ACTIVE).Count(new(models.LedgerMember)); err != nil {
		return nil, err
	}
	if preview.PendingInvitationCount, err = sess.Where("ledger_id=? AND status=?", ledgerId, models.FAMILY_INVITATION_STATUS_PENDING).Count(new(models.LedgerInvitation)); err != nil {
		return nil, err
	}
	counts := []struct {
		model  any
		value  *int64
		reason string
	}{
		{new(models.Account), &preview.AccountCount, "accounts"},
		{new(models.Transaction), &preview.TransactionCount, "transactions"},
		{new(models.SavingsGoal), &preview.SavingsGoalCount, "savingsGoals"},
	}
	for _, item := range counts {
		count, countErr := sess.Where("ledger_id=?", ledgerId).Count(item.model)
		if countErr != nil {
			return nil, countErr
		}
		*item.value = count
		if count > 0 {
			preview.BlockingReasons = append(preview.BlockingReasons, item.reason)
		}
	}
	preview.SavingsGoalFundCount, err = sess.Table(new(models.SavingsGoalFund)).
		Join("INNER", "savings_goal", "savings_goal.savings_goal_id = savings_goal_fund.goal_id").
		Where("savings_goal.ledger_id=?", ledgerId).Count(new(models.SavingsGoalFund))
	if err != nil {
		return nil, err
	}
	preview.CanDelete = len(preview.BlockingReasons) == 0
	return preview, nil
}

// GetOverview returns a compact, read-only snapshot for a ledger detail page.
// It does not change the caller's globally selected ledger.
func (s *LedgerService) GetOverview(c core.Context, uid, ledgerId int64) (*models.LedgerOverviewResponse, error) {
	db, ledger, err := s.GetLedgerWithAccess(c, uid, ledgerId, func(models.FamilyMemberRole) bool { return true })
	if err != nil {
		return nil, err
	}
	if db == nil {
		db = s.UserDataDB(uid)
	}
	ownerUid, err := s.LedgerDataOwnerUid(c, uid, ledger)
	if err != nil {
		return nil, err
	}
	name := ledger.Name
	if ledgerId == models.DefaultLedgerId {
		name = "默认个人账本"
	}
	overview := &models.LedgerOverviewResponse{LedgerId: ledgerId, LedgerName: name, Balances: make([]*models.LedgerBalanceInfoResponse, 0)}
	if ledgerId == models.DefaultLedgerId {
		overview.ActiveMemberCount = 1
	} else {
		members, memberErr := s.ListMembers(c, uid, ledgerId)
		if memberErr != nil {
			return nil, memberErr
		}
		for _, member := range members {
			if member.Status == models.FAMILY_MEMBER_STATUS_ACTIVE {
				overview.ActiveMemberCount++
			}
		}
	}
	accounts := make([]*models.Account, 0)
	accountQuery := db.NewSession(c).Where("ledger_id=? AND deleted=? AND hidden=? AND type=?", ledgerId, false, false, models.ACCOUNT_TYPE_SINGLE_ACCOUNT)
	if ledgerId == models.DefaultLedgerId {
		accountQuery = accountQuery.And("uid=?", uid)
	}
	if err = accountQuery.Find(&accounts); err != nil {
		return nil, err
	}
	overview.AccountCount = int64(len(accounts))
	balances := make(map[string]int64)
	for _, account := range accounts {
		balances[account.Currency] += account.Balance
	}
	currencies := make([]string, 0, len(balances))
	for currency := range balances {
		currencies = append(currencies, currency)
	}
	sort.Strings(currencies)
	for _, currency := range currencies {
		overview.Balances = append(overview.Balances, &models.LedgerBalanceInfoResponse{Currency: currency, Balance: balances[currency]})
	}
	transactionCondition := "uid=? AND ledger_id=? AND deleted=? AND (type=? OR type=? OR type=? OR type=? OR (type=? AND related_account_id=0))"
	overview.TransactionCount, err = db.NewSession(c).Where(transactionCondition, ownerUid, ledgerId, false,
		models.TRANSACTION_DB_TYPE_MODIFY_BALANCE, models.TRANSACTION_DB_TYPE_INCOME, models.TRANSACTION_DB_TYPE_EXPENSE,
		models.TRANSACTION_DB_TYPE_TRANSFER_OUT, models.TRANSACTION_DB_TYPE_TRANSFER_IN).Count(new(models.Transaction))
	if err != nil {
		return nil, err
	}
	overview.SavingsGoalCount, err = db.NewSession(c).Where("ledger_id=? AND deleted=?", ledgerId, false).Count(new(models.SavingsGoal))
	if err != nil {
		return nil, err
	}
	return overview, nil
}

// DeleteLedger soft deletes an empty explicit ledger. Only the ledger owner
// may delete it; accounts, transactions and savings goals must be moved or
// removed through their own guarded workflows first.
func (s *LedgerService) DeleteLedger(c core.Context, uid, ledgerId int64) error {
	if ledgerId == models.DefaultLedgerId {
		return errs.ErrLedgerAccessDenied
	}
	db, ledger, err := s.GetLedgerWithAccess(c, uid, ledgerId, func(role models.FamilyMemberRole) bool {
		return role == models.FAMILY_MEMBER_ROLE_OWNER
	})
	if err != nil {
		return err
	}
	if err := s.ensureMembers(c, db, ledger); err != nil {
		return err
	}

	return db.DoTransaction(c, func(sess *xorm.Session) error {
		for _, model := range []any{&models.Account{}, &models.Transaction{}, &models.SavingsGoal{}} {
			has, err := sess.Where("ledger_id=?", ledgerId).Exist(model)
			if err != nil {
				return err
			}
			if has {
				return errs.ErrLedgerNotEmpty
			}
		}

		now := time.Now().Unix()
		rows, err := sess.ID(ledgerId).Cols("deleted", "deleted_unix_time", "updated_unix_time").Where("deleted=?", false).
			Update(&models.Ledger{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now})
		if err != nil {
			return err
		}
		if rows < 1 {
			return errs.ErrLedgerNotFound
		}
		_, err = sess.Where("ledger_id=? AND status=?", ledgerId, models.FAMILY_INVITATION_STATUS_PENDING).
			Cols("status").Update(&models.LedgerInvitation{Status: models.FAMILY_INVITATION_STATUS_REVOKED})
		return err
	})
}
