package models

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestFamilyMemberRoleCapabilities(t *testing.T) {
	assert.True(t, FAMILY_MEMBER_ROLE_OWNER.IsValid())
	assert.True(t, FAMILY_MEMBER_ROLE_ADMIN.IsValid())
	assert.True(t, FAMILY_MEMBER_ROLE_MEMBER.IsValid())
	assert.True(t, FAMILY_MEMBER_ROLE_VIEWER.IsValid())
	assert.False(t, FamilyMemberRole(0).IsValid())
	assert.False(t, FamilyMemberRole(5).IsValid())

	assert.True(t, FAMILY_MEMBER_ROLE_OWNER.CanManage())
	assert.True(t, FAMILY_MEMBER_ROLE_ADMIN.CanManage())
	assert.False(t, FAMILY_MEMBER_ROLE_MEMBER.CanManage())
	assert.False(t, FAMILY_MEMBER_ROLE_VIEWER.CanManage())

	assert.True(t, FAMILY_MEMBER_ROLE_OWNER.CanWrite())
	assert.True(t, FAMILY_MEMBER_ROLE_ADMIN.CanWrite())
	assert.True(t, FAMILY_MEMBER_ROLE_MEMBER.CanWrite())
	assert.False(t, FAMILY_MEMBER_ROLE_VIEWER.CanWrite())
}

func TestFamilyGroupValidation(t *testing.T) {
	group := &FamilyGroup{OwnerUid: 100, Name: "温暖小家"}
	assert.NoError(t, group.Validate())

	group.OwnerUid = 0
	assert.Error(t, group.Validate())

	group = &FamilyGroup{OwnerUid: 100, Name: ""}
	assert.Error(t, group.Validate())

	group = &FamilyGroup{OwnerUid: 100, Name: string(make([]byte, MaximumNameLength+1))}
	assert.Error(t, group.Validate())
}

func TestFamilyMemberValidationAndCapabilities(t *testing.T) {
	member := &FamilyMember{FamilyId: 1, Uid: 100, Role: FAMILY_MEMBER_ROLE_MEMBER, Status: FAMILY_MEMBER_STATUS_ACTIVE}
	assert.NoError(t, member.Validate())
	assert.True(t, member.CanAct(FamilyMemberRole.CanWrite))
	assert.False(t, member.CanAct(FamilyMemberRole.CanManage))

	member.Status = FAMILY_MEMBER_STATUS_LEFT
	assert.True(t, member.CanAct(FamilyMemberRole.CanWrite) == false)

	member.FamilyId = 0
	assert.Error(t, member.Validate())

	member.FamilyId = 1
	member.Uid = 0
	assert.Error(t, member.Validate())

	member.Uid = 100
	member.Role = FamilyMemberRole(9)
	assert.Error(t, member.Validate())

	member.Role = FAMILY_MEMBER_ROLE_MEMBER
	member.Status = FamilyMemberStatus(9)
	assert.Error(t, member.Validate())
}

func TestFamilyInvitationValidation(t *testing.T) {
	invitation := &FamilyInvitation{
		FamilyId:        1,
		InviterUid:      100,
		InviteeName:     "爸爸",
		Role:            FAMILY_MEMBER_ROLE_MEMBER,
		Token:           "fam-token-1",
		CreatedUnixTime: 1000,
		ExpiredUnixTime: 2000,
	}
	assert.NoError(t, invitation.Validate())

	// Only member and viewer roles may be granted by invitation.
	invitation.Role = FAMILY_MEMBER_ROLE_ADMIN
	assert.Error(t, invitation.Validate())

	invitation.Role = FAMILY_MEMBER_ROLE_MEMBER
	invitation.Token = ""
	assert.Error(t, invitation.Validate())

	invitation.Token = "fam-token-1"
	invitation.ExpiredUnixTime = invitation.CreatedUnixTime
	assert.Error(t, invitation.Validate())

	invitation.InviteeName = ""
	assert.Error(t, invitation.Validate())
}

func TestFamilyInvitationLifecycle(t *testing.T) {
	invitation := &FamilyInvitation{
		Status:          FAMILY_INVITATION_STATUS_PENDING,
		CreatedUnixTime: 1000,
		ExpiredUnixTime: 2000,
	}

	assert.True(t, invitation.CanBeAccepted(1500))
	assert.False(t, invitation.CanBeAccepted(2000))
	assert.False(t, invitation.CanBeAccepted(2500))
	assert.True(t, invitation.CanBeRevoked())
	assert.False(t, invitation.Status.IsTerminal())

	invitation.Status = FAMILY_INVITATION_STATUS_ACCEPTED
	assert.False(t, invitation.CanBeAccepted(1500))
	assert.False(t, invitation.CanBeRevoked())
	assert.True(t, invitation.Status.IsTerminal())

	invitation.Status = FAMILY_INVITATION_STATUS_REVOKED
	assert.False(t, invitation.CanBeAccepted(1500))
	assert.True(t, invitation.Status.IsTerminal())

	invitation.Status = FAMILY_INVITATION_STATUS_EXPIRED
	assert.True(t, invitation.Status.IsTerminal())

	invitation.Status = FAMILY_INVITATION_STATUS_REJECTED
	assert.True(t, invitation.Status.IsTerminal())
}

func TestFamilyInvitationInfoResponseExposesTokenAndLifecycle(t *testing.T) {
	invitation := &FamilyInvitation{
		InvitationId:    7,
		FamilyId:        3,
		InviteeName:     "爸爸",
		Role:            FAMILY_MEMBER_ROLE_VIEWER,
		Status:          FAMILY_INVITATION_STATUS_PENDING,
		Token:           "fam-token-1",
		CreatedUnixTime: 1000,
		ExpiredUnixTime: 2000,
	}

	response := invitation.ToFamilyInvitationInfoResponse()
	assert.Equal(t, int64(7), response.Id)
	assert.Equal(t, int64(3), response.FamilyId)
	assert.Equal(t, FAMILY_MEMBER_ROLE_VIEWER, response.Role)
	assert.Equal(t, "fam-token-1", response.Token)
	assert.Equal(t, int64(2000), response.ExpiredTime)
	assert.Equal(t, int64(0), response.UsedTime)
	assert.Equal(t, int64(0), response.UsedByUid)
}
