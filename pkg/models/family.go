package models

import "fmt"

// MaximumNameLength limits the stored length of family and ledger names.
const MaximumNameLength = 64

// FamilyMemberRole represents the permission level of a family member.
type FamilyMemberRole byte

// Family member roles. The creator always holds the owner role; invitations
// may only grant the member or viewer roles.
const (
	FAMILY_MEMBER_ROLE_OWNER  FamilyMemberRole = 1
	FAMILY_MEMBER_ROLE_ADMIN  FamilyMemberRole = 2
	FAMILY_MEMBER_ROLE_MEMBER FamilyMemberRole = 3
	FAMILY_MEMBER_ROLE_VIEWER FamilyMemberRole = 4
)

// IsValid reports whether the role is a defined value.
func (r FamilyMemberRole) IsValid() bool {
	return r >= FAMILY_MEMBER_ROLE_OWNER && r <= FAMILY_MEMBER_ROLE_VIEWER
}

// CanManage reports whether the role may manage members, invitations, ledgers
// and savings goals of the family.
func (r FamilyMemberRole) CanManage() bool {
	return r == FAMILY_MEMBER_ROLE_OWNER || r == FAMILY_MEMBER_ROLE_ADMIN
}

// CanWrite reports whether the role may record and edit shared bookkeeping
// entries. Viewers are read-only.
func (r FamilyMemberRole) CanWrite() bool {
	return r != FAMILY_MEMBER_ROLE_VIEWER
}

// FamilyMemberStatus represents the membership lifecycle state.
type FamilyMemberStatus byte

// Family member statuses. LEFT and REMOVED keep history but end participation.
const (
	FAMILY_MEMBER_STATUS_ACTIVE  FamilyMemberStatus = 1
	FAMILY_MEMBER_STATUS_LEFT    FamilyMemberStatus = 2
	FAMILY_MEMBER_STATUS_REMOVED FamilyMemberStatus = 3
)

// IsValid reports whether the status is a defined value.
func (s FamilyMemberStatus) IsValid() bool {
	return s >= FAMILY_MEMBER_STATUS_ACTIVE && s <= FAMILY_MEMBER_STATUS_REMOVED
}

// FamilyInvitationStatus represents the invitation lifecycle state. Every
// state other than pending is terminal.
type FamilyInvitationStatus byte

// Family invitation statuses.
const (
	FAMILY_INVITATION_STATUS_PENDING  FamilyInvitationStatus = 1
	FAMILY_INVITATION_STATUS_ACCEPTED FamilyInvitationStatus = 2
	FAMILY_INVITATION_STATUS_REVOKED  FamilyInvitationStatus = 3
	FAMILY_INVITATION_STATUS_EXPIRED  FamilyInvitationStatus = 4
	FAMILY_INVITATION_STATUS_REJECTED FamilyInvitationStatus = 5
)

// IsValid reports whether the status is a defined value.
func (s FamilyInvitationStatus) IsValid() bool {
	return s >= FAMILY_INVITATION_STATUS_PENDING && s <= FAMILY_INVITATION_STATUS_REJECTED
}

// IsTerminal reports whether the invitation has left the pending state.
func (s FamilyInvitationStatus) IsTerminal() bool {
	return s != FAMILY_INVITATION_STATUS_PENDING
}

// FamilyGroup represents a family that shares ledgers and savings goals.
type FamilyGroup struct {
	FamilyGroupId   int64  `xorm:"PK"`
	OwnerUid        int64  `xorm:"INDEX(IDX_family_group_owner_uid_deleted) NOT NULL"`
	Deleted         bool   `xorm:"INDEX(IDX_family_group_owner_uid_deleted) NOT NULL"`
	Name            string `xorm:"VARCHAR(64) NOT NULL"`
	Comment         string `xorm:"VARCHAR(255) NOT NULL"`
	CreatedUnixTime int64
	UpdatedUnixTime int64
	DeletedUnixTime int64
}

// Validate rejects family groups with a missing owner or an unusable name.
func (g *FamilyGroup) Validate() error {
	if g.OwnerUid <= 0 {
		return fmt.Errorf("invalid family group owner")
	}

	if len(g.Name) == 0 || len(g.Name) > MaximumNameLength {
		return fmt.Errorf("invalid family group name")
	}

	return nil
}

// FamilyMember represents one user's participation in one family. The
// composite unique key allows exactly one membership per user per family.
type FamilyMember struct {
	FamilyMemberId  int64              `xorm:"PK"`
	FamilyId        int64              `xorm:"UNIQUE(UQE_family_member_family_uid) NOT NULL"`
	Uid             int64              `xorm:"UNIQUE(UQE_family_member_family_uid) INDEX(IDX_family_member_uid_status) NOT NULL"`
	Status          FamilyMemberStatus `xorm:"INDEX(IDX_family_member_uid_status) NOT NULL"`
	Role            FamilyMemberRole   `xorm:"NOT NULL"`
	CreatedUnixTime int64
	UpdatedUnixTime int64
}

// Validate rejects memberships that do not reference a family user with a
// defined role and status.
func (m *FamilyMember) Validate() error {
	if m.FamilyId <= 0 || m.Uid <= 0 {
		return fmt.Errorf("invalid family member reference")
	}

	if !m.Role.IsValid() || !m.Status.IsValid() {
		return fmt.Errorf("invalid family member role or status")
	}

	return nil
}

// CanAct reports whether an active membership grants the supplied capability.
func (m *FamilyMember) CanAct(capability func(FamilyMemberRole) bool) bool {
	return m.Status == FAMILY_MEMBER_STATUS_ACTIVE && capability(m.Role)
}

// FamilyInvitation represents a one-shot invitation of a new family member.
// The token is the only secret that grants joining; revocation invalidates it.
type FamilyInvitation struct {
	InvitationId    int64                  `xorm:"PK"`
	FamilyId        int64                  `xorm:"INDEX(IDX_family_invitation_family_status) NOT NULL"`
	InviterUid      int64                  `xorm:"NOT NULL"`
	InviteeName     string                 `xorm:"VARCHAR(64) NOT NULL"`
	Role            FamilyMemberRole       `xorm:"NOT NULL"`
	Token           string                 `xorm:"VARCHAR(64) UNIQUE NOT NULL"`
	Status          FamilyInvitationStatus `xorm:"INDEX(IDX_family_invitation_family_status) NOT NULL"`
	CreatedUnixTime int64
	ExpiredUnixTime int64
	UsedUnixTime    int64
	UsedByUid       int64
}

// Validate rejects invitations with unusable tokens or invitee names. Only the
// member and viewer roles may be granted by invitation; owner and admin roles
// are assigned by the owner after joining.
func (i *FamilyInvitation) Validate() error {
	if i.FamilyId <= 0 || i.InviterUid <= 0 {
		return fmt.Errorf("invalid family invitation reference")
	}

	if len(i.InviteeName) == 0 || len(i.InviteeName) > MaximumNameLength {
		return fmt.Errorf("invalid family invitation invitee name")
	}

	if i.Role != FAMILY_MEMBER_ROLE_MEMBER && i.Role != FAMILY_MEMBER_ROLE_VIEWER {
		return fmt.Errorf("family invitation may only grant member or viewer role")
	}

	if len(i.Token) == 0 {
		return fmt.Errorf("invalid family invitation token")
	}

	if i.ExpiredUnixTime <= i.CreatedUnixTime {
		return fmt.Errorf("invalid family invitation expiry")
	}

	return nil
}

// CanBeAccepted reports whether the invitation is pending and unexpired at the
// supplied time. Only then may the token be used to join.
func (i *FamilyInvitation) CanBeAccepted(nowUnixTime int64) bool {
	return i.Status == FAMILY_INVITATION_STATUS_PENDING && nowUnixTime < i.ExpiredUnixTime
}

// CanBeRevoked reports whether a manager may still revoke the invitation.
func (i *FamilyInvitation) CanBeRevoked() bool {
	return i.Status == FAMILY_INVITATION_STATUS_PENDING
}

// FamilyGroupCreateRequest represents the parameters for creating a family.
type FamilyGroupCreateRequest struct {
	Name    string `json:"name" binding:"required,notBlank,max=64"`
	Comment string `json:"comment" binding:"max=255"`
}

// FamilyGroupModifyRequest represents the parameters for renaming a family.
type FamilyGroupModifyRequest struct {
	Id      int64  `json:"id,string" binding:"required,min=1"`
	Name    string `json:"name" binding:"required,notBlank,max=64"`
	Comment string `json:"comment" binding:"max=255"`
}

// FamilyGroupDeleteRequest represents the parameters for disbanding a family.
type FamilyGroupDeleteRequest struct {
	Id int64 `json:"id,string" binding:"required,min=1"`
}

// FamilyGroupListRequest represents the parameters for listing own families.
type FamilyGroupListRequest struct{}

// FamilyMemberListRequest represents the parameters for listing members.
type FamilyMemberListRequest struct {
	FamilyId int64 `form:"familyId,string" binding:"required,min=1"`
}

// FamilyMemberRoleChangeRequest represents the parameters for changing the
// role of one member. The owner role itself can never be changed here.
type FamilyMemberRoleChangeRequest struct {
	FamilyId int64            `json:"familyId,string" binding:"required,min=1"`
	MemberId int64            `json:"memberId,string" binding:"required,min=1"`
	Role     FamilyMemberRole `json:"role" binding:"required,min=2,max=4"`
}

// FamilyMemberRemoveRequest represents the parameters for removing a member.
type FamilyMemberRemoveRequest struct {
	FamilyId int64 `json:"familyId,string" binding:"required,min=1"`
	MemberId int64 `json:"memberId,string" binding:"required,min=1"`
}

// FamilyMemberLeaveRequest represents the parameters for leaving a family.
type FamilyMemberLeaveRequest struct {
	FamilyId int64 `json:"familyId,string" binding:"required,min=1"`
}

// FamilyInvitationCreateRequest represents the parameters for inviting one
// family member. When ExpiresInSeconds is omitted the service applies a
// default of 24 hours.
type FamilyInvitationCreateRequest struct {
	FamilyId         int64            `json:"familyId,string" binding:"required,min=1"`
	InviteeName      string           `json:"inviteeName" binding:"required,notBlank,max=64"`
	Role             FamilyMemberRole `json:"role" binding:"required,min=3,max=4"`
	ExpiresInSeconds int64            `json:"expiresInSeconds" binding:"omitempty,min=300,max=2592000"`
}

// FamilyInvitationRevokeRequest represents the parameters for revoking one
// invitation.
type FamilyInvitationRevokeRequest struct {
	FamilyId     int64 `json:"familyId,string" binding:"required,min=1"`
	InvitationId int64 `json:"invitationId,string" binding:"required,min=1"`
}

// FamilyInvitationListRequest represents the parameters for listing the
// pending and recently handled invitations of one family.
type FamilyInvitationListRequest struct {
	FamilyId int64 `form:"familyId,string" binding:"required,min=1"`
}

// FamilyInvitationAcceptRequest represents the parameters for joining a family
// with an invitation token.
type FamilyInvitationAcceptRequest struct {
	Token string `json:"token" binding:"required,notBlank,max=64"`
}

// FamilyGroupInfoResponse is the API representation of one family.
type FamilyGroupInfoResponse struct {
	Id          int64  `json:"id,string"`
	OwnerUid    int64  `json:"ownerUid,string"`
	Name        string `json:"name"`
	Comment     string `json:"comment"`
	MemberCount int    `json:"memberCount"`
	CreatedTime int64  `json:"createdTime"`
}

// FamilyMemberInfoResponse is the API representation of one membership.
type FamilyMemberInfoResponse struct {
	Id         int64              `json:"id,string"`
	FamilyId   int64              `json:"familyId,string"`
	Uid        int64              `json:"uid,string"`
	Role       FamilyMemberRole   `json:"role"`
	Status     FamilyMemberStatus `json:"status"`
	Nickname   string             `json:"nickname,omitempty"`
	JoinedTime int64              `json:"joinedTime"`
}

// FamilyInvitationInfoResponse is the API representation of one invitation.
// The token is only returned to managers of the family.
type FamilyInvitationInfoResponse struct {
	Id          int64                  `json:"id,string"`
	FamilyId    int64                  `json:"familyId,string"`
	InviteeName string                 `json:"inviteeName"`
	Role        FamilyMemberRole       `json:"role"`
	Status      FamilyInvitationStatus `json:"status"`
	Token       string                 `json:"token"`
	CreatedTime int64                  `json:"createdTime"`
	ExpiredTime int64                  `json:"expiredTime"`
	UsedTime    int64                  `json:"usedTime,omitempty"`
	UsedByUid   int64                  `json:"usedByUid,string,omitempty"`
}

// ToFamilyGroupInfoResponse converts a stored family to its API representation.
func (g *FamilyGroup) ToFamilyGroupInfoResponse(memberCount int) *FamilyGroupInfoResponse {
	return &FamilyGroupInfoResponse{
		Id:          g.FamilyGroupId,
		OwnerUid:    g.OwnerUid,
		Name:        g.Name,
		Comment:     g.Comment,
		MemberCount: memberCount,
		CreatedTime: g.CreatedUnixTime,
	}
}

// ToFamilyMemberInfoResponse converts a stored membership to its API representation.
func (m *FamilyMember) ToFamilyMemberInfoResponse(nickname string) *FamilyMemberInfoResponse {
	return &FamilyMemberInfoResponse{
		Id:         m.FamilyMemberId,
		FamilyId:   m.FamilyId,
		Uid:        m.Uid,
		Role:       m.Role,
		Status:     m.Status,
		Nickname:   nickname,
		JoinedTime: m.CreatedUnixTime,
	}
}

// ToFamilyInvitationInfoResponse converts a stored invitation to its API
// representation.
func (i *FamilyInvitation) ToFamilyInvitationInfoResponse() *FamilyInvitationInfoResponse {
	return &FamilyInvitationInfoResponse{
		Id:          i.InvitationId,
		FamilyId:    i.FamilyId,
		InviteeName: i.InviteeName,
		Role:        i.Role,
		Status:      i.Status,
		Token:       i.Token,
		CreatedTime: i.CreatedUnixTime,
		ExpiredTime: i.ExpiredUnixTime,
		UsedTime:    i.UsedUnixTime,
		UsedByUid:   i.UsedByUid,
	}
}
