package services

import (
	"time"

	"xorm.io/xorm"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
)

// MaximumFamiliesPerUser limits how many families one user may belong to.
const MaximumFamiliesPerUser = 20

// DefaultInvitationExpiresInSeconds matches the documented 24 hour invitation
// validity of the family bookkeeping design.
const DefaultInvitationExpiresInSeconds = int64(86400)

// FamilyInvitationTokenLength is the length of the one-shot invitation token.
const FamilyInvitationTokenLength = 40

// FamilyService manages family groups, memberships and invitations.
//
// Every family-domain row (groups, memberships, invitations, family ledgers
// and their savings goals) lives in the user-data shard of the family owner,
// so the whole family shares one consistent store. Members locate that shard
// through their own membership row, which is the only place their user id and
// the family id appear together.
type FamilyService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

// Families is the family service singleton.
var Families = &FamilyService{
	ServiceUsingDB:   ServiceUsingDB{container: datastore.Container},
	ServiceUsingUuid: ServiceUsingUuid{container: uuid.Container},
}

// anyFamilyRole grants read access to every active membership role.
func anyFamilyRole(models.FamilyMemberRole) bool { return true }

// locateMembership returns the shard and membership row holding the given
// user's membership in the given family.
func (s *FamilyService) locateMembership(c core.Context, uid, familyId int64) (*datastore.Database, *models.FamilyMember, error) {
	if uid <= 0 || familyId <= 0 {
		return nil, nil, errs.ErrParameterInvalid
	}

	for i := 0; i < s.UserDataDBCount(); i++ {
		db := s.UserDataDBByIndex(i)
		member := &models.FamilyMember{}
		has, err := db.NewSession(c).Where("uid=? AND family_id=?", uid, familyId).Get(member)
		if err != nil {
			return nil, nil, err
		}
		if has {
			return db, member, nil
		}
	}
	return nil, nil, errs.ErrFamilyAccessDenied
}

// locateFamilyDB returns the shard holding the family rows when the user's
// membership exists there and grants the capability.
func (s *FamilyService) locateFamilyDB(c core.Context, uid, familyId int64, capability func(models.FamilyMemberRole) bool) (*datastore.Database, *models.FamilyMember, error) {
	db, member, err := s.locateMembership(c, uid, familyId)
	if err != nil {
		return nil, nil, err
	}
	if member.Status != models.FAMILY_MEMBER_STATUS_ACTIVE {
		return nil, nil, errs.ErrFamilyAccessDenied
	}
	if !capability(member.Role) {
		return nil, nil, errs.ErrFamilyAccessDenied
	}
	return db, member, nil
}

// GetActiveMember returns the active membership of one user in one family so
// that clients can resolve their own role and capabilities.
func (s *FamilyService) GetActiveMember(c core.Context, uid, familyId int64) (*models.FamilyMember, error) {
	_, member, err := s.locateFamilyDB(c, uid, familyId, func(models.FamilyMemberRole) bool { return true })
	if err != nil {
		return nil, err
	}
	return member, nil
}

// CreateFamilyGroup creates a family with the creator as its owner member.
// The family rows live in the creator's own shard.
func (s *FamilyService) CreateFamilyGroup(c core.Context, uid int64, request *models.FamilyGroupCreateRequest) (*models.FamilyGroup, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	group := &models.FamilyGroup{OwnerUid: uid, Name: request.Name, Comment: request.Comment}
	if err := group.Validate(); err != nil {
		return nil, errs.ErrFamilyNameInvalid
	}

	memberCount := int64(0)
	for i := 0; i < s.UserDataDBCount(); i++ {
		count, err := s.UserDataDBByIndex(i).NewSession(c).
			Where("uid=? AND status=?", uid, models.FAMILY_MEMBER_STATUS_ACTIVE).Count(new(models.FamilyMember))
		if err != nil {
			return nil, err
		}
		memberCount += count
	}
	if memberCount >= MaximumFamiliesPerUser {
		return nil, errs.ErrFamilyLimitReached
	}

	now := time.Now().Unix()
	group.FamilyGroupId = s.GenerateUuid(uuid.UUID_TYPE_FAMILY)
	group.CreatedUnixTime = now
	group.UpdatedUnixTime = now
	if group.FamilyGroupId < 1 {
		return nil, errs.ErrSystemIsBusy
	}

	member := &models.FamilyMember{
		FamilyMemberId:  s.GenerateUuid(uuid.UUID_TYPE_FAMILY),
		FamilyId:        group.FamilyGroupId,
		Uid:             uid,
		Status:          models.FAMILY_MEMBER_STATUS_ACTIVE,
		Role:            models.FAMILY_MEMBER_ROLE_OWNER,
		CreatedUnixTime: now,
		UpdatedUnixTime: now,
	}
	if member.FamilyMemberId < 1 {
		return nil, errs.ErrSystemIsBusy
	}

	err := s.UserDataDB(uid).DoTransaction(c, func(sess *xorm.Session) error {
		if _, err := sess.Insert(group); err != nil {
			return err
		}
		_, err := sess.Insert(member)
		return err
	})
	if err != nil {
		return nil, err
	}
	return group, nil
}

// GetFamilyGroup returns one family when the user is an active member.
func (s *FamilyService) GetFamilyGroup(c core.Context, uid, familyId int64) (*models.FamilyGroup, error) {
	db, _, err := s.locateFamilyDB(c, uid, familyId, func(models.FamilyMemberRole) bool { return true })
	if err != nil {
		return nil, err
	}

	group := &models.FamilyGroup{}
	has, err := db.NewSession(c).ID(familyId).Where("deleted=?", false).Get(group)
	if err != nil {
		return nil, err
	}
	if !has {
		return nil, errs.ErrFamilyNotFound
	}
	return group, nil
}

// ListFamilyGroups returns every family the user actively belongs to.
func (s *FamilyService) ListFamilyGroups(c core.Context, uid int64) ([]*models.FamilyGroupInfoResponse, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	responses := make([]*models.FamilyGroupInfoResponse, 0)
	for i := 0; i < s.UserDataDBCount(); i++ {
		db := s.UserDataDBByIndex(i)

		groups := make([]*models.FamilyGroup, 0)
		err := db.NewSession(c).
			Select("family_group.*").
			Join("INNER", "family_member", "family_member.family_id=family_group.family_group_id").
			Where("family_member.uid=? AND family_member.status=? AND family_group.deleted=?", uid, models.FAMILY_MEMBER_STATUS_ACTIVE, false).
			Find(&groups)
		if err != nil {
			return nil, err
		}

		for _, group := range groups {
			memberCount, err := db.NewSession(c).
				Where("family_id=? AND status=?", group.FamilyGroupId, models.FAMILY_MEMBER_STATUS_ACTIVE).Count(new(models.FamilyMember))
			if err != nil {
				return nil, err
			}
			responses = append(responses, group.ToFamilyGroupInfoResponse(int(memberCount)))
		}
	}
	return responses, nil
}

// ModifyFamilyGroup renames a family. Managers only.
func (s *FamilyService) ModifyFamilyGroup(c core.Context, uid int64, request *models.FamilyGroupModifyRequest) (*models.FamilyGroup, error) {
	db, _, err := s.locateFamilyDB(c, uid, request.Id, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}

	group := &models.FamilyGroup{Name: request.Name, Comment: request.Comment}
	if err := group.Validate(); err != nil {
		return nil, errs.ErrFamilyNameInvalid
	}

	now := time.Now().Unix()
	rows, err := db.NewSession(c).ID(request.Id).
		Cols("name", "comment", "updated_unix_time").
		Where("deleted=?", false).
		Update(&models.FamilyGroup{Name: request.Name, Comment: request.Comment, UpdatedUnixTime: now})
	if err != nil {
		return nil, err
	}
	if rows < 1 {
		return nil, errs.ErrFamilyNotFound
	}

	return s.GetFamilyGroup(c, uid, request.Id)
}

// DeleteFamilyGroup soft deletes a family and ends every membership. Only the
// owner may disband a family.
func (s *FamilyService) DeleteFamilyGroup(c core.Context, uid, familyId int64) error {
	db, member, err := s.locateFamilyDB(c, uid, familyId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return err
	}
	if member.Role != models.FAMILY_MEMBER_ROLE_OWNER {
		return errs.ErrFamilyAccessDenied
	}

	now := time.Now().Unix()
	return db.DoTransaction(c, func(sess *xorm.Session) error {
		rows, err := sess.ID(familyId).
			Cols("deleted", "deleted_unix_time", "updated_unix_time").
			Where("deleted=? AND owner_uid=?", false, uid).
			Update(&models.FamilyGroup{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now})
		if err != nil {
			return err
		}
		if rows < 1 {
			return errs.ErrFamilyNotFound
		}

		_, err = sess.Where("family_id=? AND status=?", familyId, models.FAMILY_MEMBER_STATUS_ACTIVE).
			Cols("status", "updated_unix_time").
			Update(&models.FamilyMember{Status: models.FAMILY_MEMBER_STATUS_REMOVED, UpdatedUnixTime: now})
		return err
	})
}

// ChangeMemberRole changes the role of one active member. Only the owner may
// change roles, and the owner role itself is immutable.
func (s *FamilyService) ChangeMemberRole(c core.Context, uid int64, request *models.FamilyMemberRoleChangeRequest) (*models.FamilyMember, error) {
	db, _, err := s.locateFamilyDB(c, uid, request.FamilyId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}

	member := &models.FamilyMember{}
	has, err := db.NewSession(c).ID(request.MemberId).
		Where("family_id=? AND status=?", request.FamilyId, models.FAMILY_MEMBER_STATUS_ACTIVE).Get(member)
	if err != nil {
		return nil, err
	}
	if !has {
		return nil, errs.ErrFamilyMemberNotFound
	}
	if member.Role == models.FAMILY_MEMBER_ROLE_OWNER {
		return nil, errs.ErrFamilyOwnerRoleImmutable
	}
	if !request.Role.IsValid() || request.Role == models.FAMILY_MEMBER_ROLE_OWNER {
		return nil, errs.ErrFamilyMemberRoleInvalid
	}

	member.Role = request.Role
	member.UpdatedUnixTime = time.Now().Unix()
	rows, err := db.NewSession(c).ID(request.MemberId).
		Cols("role", "updated_unix_time").
		Update(member)
	if err != nil {
		return nil, err
	}
	if rows < 1 {
		return nil, errs.ErrFamilyMemberNotFound
	}
	return member, nil
}

// RemoveMember ends the membership of one active member. Managers only, and
// the owner can never be removed.
func (s *FamilyService) RemoveMember(c core.Context, uid, familyId, memberId int64) error {
	db, _, err := s.locateFamilyDB(c, uid, familyId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return err
	}

	member := &models.FamilyMember{}
	has, err := db.NewSession(c).ID(memberId).
		Where("family_id=? AND status=?", familyId, models.FAMILY_MEMBER_STATUS_ACTIVE).Get(member)
	if err != nil {
		return err
	}
	if !has {
		return errs.ErrFamilyMemberNotFound
	}
	if member.Role == models.FAMILY_MEMBER_ROLE_OWNER {
		return errs.ErrFamilyOwnerRoleImmutable
	}

	now := time.Now().Unix()
	member.Status = models.FAMILY_MEMBER_STATUS_REMOVED
	member.UpdatedUnixTime = now
	rows, err := db.NewSession(c).ID(memberId).
		Cols("status", "updated_unix_time").
		Update(member)
	if err != nil {
		return err
	}
	if rows < 1 {
		return errs.ErrFamilyMemberNotFound
	}
	return nil
}

// LeaveFamily ends the membership of the calling user. The owner cannot leave.
func (s *FamilyService) LeaveFamily(c core.Context, uid, familyId int64) error {
	db, member, err := s.locateFamilyDB(c, uid, familyId, func(models.FamilyMemberRole) bool { return true })
	if err != nil {
		return err
	}
	if member.Role == models.FAMILY_MEMBER_ROLE_OWNER {
		return errs.ErrFamilyOwnerCannotLeave
	}

	now := time.Now().Unix()
	member.Status = models.FAMILY_MEMBER_STATUS_LEFT
	member.UpdatedUnixTime = now
	rows, err := db.NewSession(c).ID(member.FamilyMemberId).
		Cols("status", "updated_unix_time").
		Update(member)
	if err != nil {
		return err
	}
	if rows < 1 {
		return errs.ErrFamilyMemberNotFound
	}
	return nil
}

// ListMembers returns the members of one family with their nicknames. Every
// active member may view the member list.
func (s *FamilyService) ListMembers(c core.Context, uid, familyId int64) ([]*models.FamilyMemberInfoResponse, error) {
	db, _, err := s.locateFamilyDB(c, uid, familyId, func(models.FamilyMemberRole) bool { return true })
	if err != nil {
		return nil, err
	}

	members := make([]*models.FamilyMember, 0)
	err = db.NewSession(c).
		Where("family_id=?", familyId).
		OrderBy("created_unix_time").
		Find(&members)
	if err != nil {
		return nil, err
	}

	nicknames, err := s.getNicknames(c, members)
	if err != nil {
		return nil, err
	}

	responses := make([]*models.FamilyMemberInfoResponse, 0, len(members))
	for _, member := range members {
		responses = append(responses, member.ToFamilyMemberInfoResponse(nicknames[member.Uid]))
	}
	return responses, nil
}

// GetUserNickname returns the display name of one user, or an empty string
// when the user does not exist.
func (s *FamilyService) GetUserNickname(c core.Context, uid int64) (string, error) {
	if uid <= 0 {
		return "", nil
	}

	user := &models.User{}
	has, err := s.UserDB().NewSession(c).ID(uid).Get(user)
	if err != nil {
		return "", err
	}
	if !has {
		return "", nil
	}
	return user.Nickname, nil
}

// getNicknames resolves the display names of the member users. User records
// live in the global user store, which every service can reach.
func (s *FamilyService) getNicknames(c core.Context, members []*models.FamilyMember) (map[int64]string, error) {
	nicknames := make(map[int64]string)

	if len(members) == 0 {
		return nicknames, nil
	}

	uids := make([]int64, 0, len(members))
	for _, member := range members {
		uids = append(uids, member.Uid)
	}

	users := make([]*models.User, 0)
	if err := s.UserDB().NewSession(c).In("uid", uids).Find(&users); err != nil {
		return nil, err
	}
	for _, user := range users {
		nicknames[user.Uid] = user.Nickname
	}
	return nicknames, nil
}

// CreateInvitation creates a one-shot invitation. Managers only.
func (s *FamilyService) CreateInvitation(c core.Context, uid int64, request *models.FamilyInvitationCreateRequest) (*models.FamilyInvitation, error) {
	db, _, err := s.locateFamilyDB(c, uid, request.FamilyId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}

	expiresInSeconds := request.ExpiresInSeconds
	if expiresInSeconds <= 0 {
		expiresInSeconds = DefaultInvitationExpiresInSeconds
	}

	now := time.Now().Unix()
	invitation := &models.FamilyInvitation{
		FamilyId:        request.FamilyId,
		InviterUid:      uid,
		InviteeName:     request.InviteeName,
		Role:            request.Role,
		Status:          models.FAMILY_INVITATION_STATUS_PENDING,
		CreatedUnixTime: now,
		ExpiredUnixTime: now + expiresInSeconds,
	}

	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		// The token column is unique; regenerate on the rare collision.
		for attempt := 0; attempt < 3; attempt++ {
			token, err := utils.GetRandomNumberOrLowercaseLetter(FamilyInvitationTokenLength)
			if err != nil {
				return err
			}

			invitation.InvitationId = s.GenerateUuid(uuid.UUID_TYPE_FAMILY)
			invitation.Token = token
			if invitation.InvitationId < 1 {
				return errs.ErrSystemIsBusy
			}
			if err := invitation.Validate(); err != nil {
				return errs.ErrFamilyInvitationInvalid
			}

			inserted, err := sess.Insert(invitation)
			if err != nil {
				return err
			}
			if inserted > 0 {
				return nil
			}
		}
		return errs.ErrSystemIsBusy
	})

	if err != nil {
		return nil, err
	}
	return invitation, nil
}

// ListInvitations returns the invitations of one family. Managers only.
func (s *FamilyService) ListInvitations(c core.Context, uid, familyId int64) ([]*models.FamilyInvitationInfoResponse, error) {
	db, _, err := s.locateFamilyDB(c, uid, familyId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}

	invitations := make([]*models.FamilyInvitation, 0)
	err = db.NewSession(c).
		Where("family_id=?", familyId).
		OrderBy("created_unix_time DESC").
		Find(&invitations)
	if err != nil {
		return nil, err
	}

	responses := make([]*models.FamilyInvitationInfoResponse, 0, len(invitations))
	for _, invitation := range invitations {
		responses = append(responses, invitation.ToFamilyInvitationInfoResponse())
	}
	return responses, nil
}

// RevokeInvitation revokes one pending invitation. Managers only.
func (s *FamilyService) RevokeInvitation(c core.Context, uid, familyId, invitationId int64) error {
	db, _, err := s.locateFamilyDB(c, uid, familyId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return err
	}

	invitation := &models.FamilyInvitation{}
	has, err := db.NewSession(c).ID(invitationId).Where("family_id=?", familyId).Get(invitation)
	if err != nil {
		return err
	}
	if !has {
		return errs.ErrFamilyInvitationNotFound
	}
	if !invitation.CanBeRevoked() {
		return errs.ErrFamilyInvitationInvalid
	}

	invitation.Status = models.FAMILY_INVITATION_STATUS_REVOKED
	rows, err := db.NewSession(c).ID(invitationId).
		Cols("status").
		Update(invitation)
	if err != nil {
		return err
	}
	if rows < 1 {
		return errs.ErrFamilyInvitationNotFound
	}
	return nil
}

// AcceptInvitation joins a family with a valid invitation token. The
// invitation lives in the family owner's shard, which the acceptor locates by
// searching the tokens. A user with an earlier left or removed membership
// rejoins with the invited role.
func (s *FamilyService) AcceptInvitation(c core.Context, uid int64, token string) (*models.FamilyGroup, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	var familyDb *datastore.Database
	var invitation *models.FamilyInvitation
	for i := 0; i < s.UserDataDBCount() && invitation == nil; i++ {
		db := s.UserDataDBByIndex(i)
		found := &models.FamilyInvitation{}
		has, err := db.NewSession(c).Where("token=?", token).Get(found)
		if err != nil {
			return nil, err
		}
		if has {
			familyDb, invitation = db, found
		}
	}
	if invitation == nil {
		return nil, errs.ErrFamilyInvitationNotFound
	}

	now := time.Now().Unix()
	err := familyDb.DoTransaction(c, func(sess *xorm.Session) error {
		if !invitation.CanBeAccepted(now) {
			if invitation.Status == models.FAMILY_INVITATION_STATUS_PENDING {
				// Lazily expire a pending invitation whose deadline has passed.
				_, err := sess.ID(invitation.InvitationId).
					Cols("status").
					Update(&models.FamilyInvitation{Status: models.FAMILY_INVITATION_STATUS_EXPIRED})
				if err != nil {
					return err
				}
			}
			return errs.ErrFamilyInvitationInvalid
		}

		existing := &models.FamilyMember{}
		has, err := sess.Where("family_id=? AND uid=?", invitation.FamilyId, uid).Get(existing)
		if err != nil {
			return err
		}

		if has {
			if existing.Status == models.FAMILY_MEMBER_STATUS_ACTIVE {
				return errs.ErrFamilyAlreadyMember
			}

			existing.Status = models.FAMILY_MEMBER_STATUS_ACTIVE
			existing.Role = invitation.Role
			existing.UpdatedUnixTime = now
			rows, err := sess.ID(existing.FamilyMemberId).
				Cols("status", "role", "updated_unix_time").
				Update(existing)
			if err != nil {
				return err
			}
			if rows < 1 {
				return errs.ErrFamilyMemberNotFound
			}
		} else {
			member := &models.FamilyMember{
				FamilyMemberId:  s.GenerateUuid(uuid.UUID_TYPE_FAMILY),
				FamilyId:        invitation.FamilyId,
				Uid:             uid,
				Status:          models.FAMILY_MEMBER_STATUS_ACTIVE,
				Role:            invitation.Role,
				CreatedUnixTime: now,
				UpdatedUnixTime: now,
			}
			if member.FamilyMemberId < 1 {
				return errs.ErrSystemIsBusy
			}
			if _, err := sess.Insert(member); err != nil {
				return err
			}
		}

		invitation.Status = models.FAMILY_INVITATION_STATUS_ACCEPTED
		invitation.UsedUnixTime = now
		invitation.UsedByUid = uid
		rows, err := sess.ID(invitation.InvitationId).
			Cols("status", "used_unix_time", "used_by_uid").
			Update(invitation)
		if err != nil {
			return err
		}
		if rows < 1 {
			return errs.ErrFamilyInvitationNotFound
		}
		return nil
	})

	if err != nil {
		return nil, err
	}

	group := &models.FamilyGroup{}
	has, err := familyDb.NewSession(c).ID(invitation.FamilyId).Where("deleted=?", false).Get(group)
	if err != nil {
		return nil, err
	}
	if !has {
		return nil, errs.ErrFamilyNotFound
	}
	return group, nil
}
