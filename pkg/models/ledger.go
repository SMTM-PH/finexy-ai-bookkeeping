package models

import "fmt"

// LedgerType represents the sharing scope of a ledger.
type LedgerType byte

// Ledger types. A personal ledger belongs to one user; a family ledger is
// shared with every active member of its family.
const (
	LEDGER_TYPE_PERSONAL LedgerType = 1
	LEDGER_TYPE_FAMILY   LedgerType = 2
)

// IsValid reports whether the ledger type is a defined value.
func (t LedgerType) IsValid() bool {
	return t == LEDGER_TYPE_PERSONAL || t == LEDGER_TYPE_FAMILY
}

// DefaultLedgerId represents the implicit ledger of pre-existing user data.
// Accounts, transactions and other user data rows without an explicit ledger
// id belong to the user's default personal ledger, which keeps every existing
// account readable without a data migration.
const DefaultLedgerId = int64(0)

// Ledger represents one book. A user may own several personal ledgers and
// participate in the family ledgers of the families they belong to. Statistics,
// balances and savings goals are always scoped to one ledger.
type Ledger struct {
	LedgerId        int64      `xorm:"PK"`
	OwnerUid        int64      `xorm:"INDEX(IDX_ledger_owner_uid_deleted) NOT NULL"`
	Type            LedgerType `xorm:"NOT NULL"`
	FamilyId        int64      `xorm:"INDEX(IDX_ledger_family_id_deleted) NOT NULL DEFAULT 0"`
	Deleted         bool       `xorm:"INDEX(IDX_ledger_owner_uid_deleted) INDEX(IDX_ledger_family_id_deleted) NOT NULL"`
	Name            string     `xorm:"VARCHAR(64) NOT NULL"`
	Comment         string     `xorm:"VARCHAR(255) NOT NULL"`
	CreatedUnixTime int64
	UpdatedUnixTime int64
	DeletedUnixTime int64
}

// LedgerMember is the canonical membership of one ledger. New code grants
// access through this relation; the former family membership remains a
// temporary compatibility source while existing rows are migrated.
type LedgerMember struct {
	LedgerMemberId  int64              `xorm:"PK"`
	LedgerId        int64              `xorm:"UNIQUE(UQE_ledger_member_ledger_uid) INDEX(IDX_ledger_member_ledger_status) NOT NULL"`
	Uid             int64              `xorm:"UNIQUE(UQE_ledger_member_ledger_uid) INDEX(IDX_ledger_member_uid_status) NOT NULL"`
	Status          FamilyMemberStatus `xorm:"INDEX(IDX_ledger_member_ledger_status) INDEX(IDX_ledger_member_uid_status) NOT NULL"`
	Role            FamilyMemberRole   `xorm:"NOT NULL"`
	CreatedUnixTime int64
	UpdatedUnixTime int64
}

type LedgerInvitation struct {
	InvitationId    int64                  `xorm:"PK"`
	LedgerId        int64                  `xorm:"INDEX(IDX_ledger_invitation_ledger_status) NOT NULL"`
	InviterUid      int64                  `xorm:"NOT NULL"`
	InviteeName     string                 `xorm:"VARCHAR(64) NOT NULL"`
	Role            FamilyMemberRole       `xorm:"NOT NULL"`
	Token           string                 `xorm:"VARCHAR(64) UNIQUE NOT NULL"`
	Status          FamilyInvitationStatus `xorm:"INDEX(IDX_ledger_invitation_ledger_status) NOT NULL"`
	CreatedUnixTime int64
	ExpiredUnixTime int64
	UsedUnixTime    int64
	UsedByUid       int64
}

func (i *LedgerInvitation) Validate() error {
	if i.LedgerId <= 0 || i.InviterUid <= 0 || len(i.InviteeName) == 0 || len(i.InviteeName) > MaximumNameLength || len(i.Token) == 0 {
		return fmt.Errorf("invalid ledger invitation")
	}
	if i.Role != FAMILY_MEMBER_ROLE_MEMBER && i.Role != FAMILY_MEMBER_ROLE_VIEWER {
		return fmt.Errorf("ledger invitation may only grant member or viewer role")
	}
	if i.ExpiredUnixTime <= i.CreatedUnixTime {
		return fmt.Errorf("invalid ledger invitation expiry")
	}
	return nil
}

func (i *LedgerInvitation) CanBeAccepted(now int64) bool {
	return i.Status == FAMILY_INVITATION_STATUS_PENDING && now < i.ExpiredUnixTime
}

func (m *LedgerMember) Validate() error {
	if m.LedgerId <= 0 || m.Uid <= 0 {
		return fmt.Errorf("invalid ledger member reference")
	}
	if !m.Role.IsValid() || !m.Status.IsValid() {
		return fmt.Errorf("invalid ledger member role or status")
	}
	return nil
}

func (m *LedgerMember) CanAct(capability func(FamilyMemberRole) bool) bool {
	return m.Status == FAMILY_MEMBER_STATUS_ACTIVE && capability(m.Role)
}

// Validate rejects ledgers whose sharing scope and family reference disagree.
// A personal ledger must not reference a family; a family ledger must.
func (l *Ledger) Validate() error {
	if l.OwnerUid <= 0 {
		return fmt.Errorf("invalid ledger owner")
	}

	if !l.Type.IsValid() {
		return fmt.Errorf("invalid ledger type")
	}

	if l.Type == LEDGER_TYPE_PERSONAL && l.FamilyId != DefaultLedgerId {
		return fmt.Errorf("personal ledger must not reference a family")
	}

	if l.Type == LEDGER_TYPE_FAMILY && l.FamilyId <= 0 {
		return fmt.Errorf("family ledger requires a family")
	}

	if len(l.Name) == 0 || len(l.Name) > MaximumNameLength {
		return fmt.Errorf("invalid ledger name")
	}

	return nil
}

// IsFamilyLedger reports whether the ledger is shared with a family.
func (l *Ledger) IsFamilyLedger() bool {
	return l.Type == LEDGER_TYPE_FAMILY
}

// LedgerCreateRequest represents the parameters for creating a ledger. Family
// ledgers require the id of the family they belong to; the creator must hold a
// managing role there (checked by the service).
type LedgerCreateRequest struct {
	Type     LedgerType `json:"type" binding:"required,min=1,max=2"`
	FamilyId int64      `json:"familyId,string" binding:"omitempty,min=1"`
	Name     string     `json:"name" binding:"required,notBlank,max=64"`
	Comment  string     `json:"comment" binding:"max=255"`
}

// LedgerModifyRequest represents the parameters for renaming a ledger. The
// sharing scope of an existing ledger is immutable.
type LedgerModifyRequest struct {
	Id      int64  `json:"id,string" binding:"required,min=1"`
	Name    string `json:"name" binding:"required,notBlank,max=64"`
	Comment string `json:"comment" binding:"max=255"`
}

// LedgerDeleteRequest represents the parameters for deleting a ledger.
type LedgerDeleteRequest struct {
	Id int64 `json:"id,string" binding:"required,min=1"`
}

type LedgerDeletePreviewRequest struct {
	Id int64 `form:"id,string" binding:"required,min=1"`
}

type LedgerDeletePreviewResponse struct {
	LedgerId               int64    `json:"ledgerId,string"`
	LedgerName             string   `json:"ledgerName"`
	ActiveMemberCount      int64    `json:"activeMemberCount"`
	PendingInvitationCount int64    `json:"pendingInvitationCount"`
	AccountCount           int64    `json:"accountCount"`
	TransactionCount       int64    `json:"transactionCount"`
	SavingsGoalCount       int64    `json:"savingsGoalCount"`
	SavingsGoalFundCount   int64    `json:"savingsGoalFundCount"`
	CanDelete              bool     `json:"canDelete"`
	BlockingReasons        []string `json:"blockingReasons"`
}

// LedgerListRequest represents the parameters for listing visible ledgers.
type LedgerListRequest struct {
	FamilyId int64 `form:"familyId,string" binding:"omitempty,min=1"`
}

type LedgerOverviewRequest struct {
	LedgerId int64 `form:"ledgerId,string" binding:"omitempty,min=1"`
}

type LedgerBalanceInfoResponse struct {
	Currency string `json:"currency"`
	Balance  int64  `json:"balance"`
}

type LedgerOverviewResponse struct {
	LedgerId          int64                        `json:"ledgerId,string"`
	LedgerName        string                       `json:"ledgerName"`
	ActiveMemberCount int64                        `json:"activeMemberCount"`
	AccountCount      int64                        `json:"accountCount"`
	TransactionCount  int64                        `json:"transactionCount"`
	SavingsGoalCount  int64                        `json:"savingsGoalCount"`
	Balances          []*LedgerBalanceInfoResponse `json:"balances"`
}

type LedgerMemberListRequest struct {
	LedgerId int64 `form:"ledgerId,string" binding:"required,min=1"`
}

type LedgerMemberRoleChangeRequest struct {
	LedgerId int64            `json:"ledgerId,string" binding:"required,min=1"`
	MemberId int64            `json:"memberId,string" binding:"required,min=1"`
	Role     FamilyMemberRole `json:"role" binding:"required,min=2,max=4"`
}

type LedgerMemberRemoveRequest struct {
	LedgerId int64 `json:"ledgerId,string" binding:"required,min=1"`
	MemberId int64 `json:"memberId,string" binding:"required,min=1"`
}
type LedgerMemberLeaveRequest struct {
	LedgerId int64 `json:"ledgerId,string" binding:"required,min=1"`
}
type LedgerInvitationCreateRequest struct {
	LedgerId         int64            `json:"ledgerId,string" binding:"required,min=1"`
	InviteeName      string           `json:"inviteeName" binding:"required,notBlank,max=64"`
	Role             FamilyMemberRole `json:"role" binding:"required,min=3,max=4"`
	ExpiresInSeconds int64            `json:"expiresInSeconds" binding:"omitempty,min=300,max=2592000"`
}
type LedgerInvitationListRequest struct {
	LedgerId int64 `form:"ledgerId,string" binding:"required,min=1"`
}
type LedgerInvitationRevokeRequest struct {
	LedgerId     int64 `json:"ledgerId,string" binding:"required,min=1"`
	InvitationId int64 `json:"invitationId,string" binding:"required,min=1"`
}
type LedgerInvitationAcceptRequest struct {
	Token string `json:"token" binding:"required,notBlank,max=64"`
}

// LedgerInvitationPreviewResponse exposes only the information a signed-in
// recipient needs to make an informed decision before consuming a token.
type LedgerInvitationPreviewResponse struct {
	Ledger          *LedgerInfoResponse `json:"ledger"`
	Role            FamilyMemberRole    `json:"role"`
	InviteeName     string              `json:"inviteeName"`
	InviterNickname string              `json:"inviterNickname,omitempty"`
	ExpiredTime     int64               `json:"expiredTime"`
}

type LedgerMemberInfoResponse struct {
	Id            int64              `json:"id,string"`
	LedgerId      int64              `json:"ledgerId,string"`
	Uid           int64              `json:"uid,string"`
	Role          FamilyMemberRole   `json:"role"`
	Status        FamilyMemberStatus `json:"status"`
	Nickname      string             `json:"nickname,omitempty"`
	JoinedTime    int64              `json:"joinedTime"`
	IsCurrentUser bool               `json:"isCurrentUser,omitempty"`
}

type LedgerInvitationInfoResponse struct {
	Id          int64                  `json:"id,string"`
	LedgerId    int64                  `json:"ledgerId,string"`
	InviteeName string                 `json:"inviteeName"`
	Role        FamilyMemberRole       `json:"role"`
	Status      FamilyInvitationStatus `json:"status"`
	Token       string                 `json:"token"`
	CreatedTime int64                  `json:"createdTime"`
	ExpiredTime int64                  `json:"expiredTime"`
	UsedTime    int64                  `json:"usedTime,omitempty"`
	UsedByUid   int64                  `json:"usedByUid,string,omitempty"`
}

func (i *LedgerInvitation) ToLedgerInvitationInfoResponse() *LedgerInvitationInfoResponse {
	return &LedgerInvitationInfoResponse{Id: i.InvitationId, LedgerId: i.LedgerId, InviteeName: i.InviteeName, Role: i.Role, Status: i.Status, Token: i.Token, CreatedTime: i.CreatedUnixTime, ExpiredTime: i.ExpiredUnixTime, UsedTime: i.UsedUnixTime, UsedByUid: i.UsedByUid}
}

func (m *LedgerMember) ToLedgerMemberInfoResponse(nickname string) *LedgerMemberInfoResponse {
	return &LedgerMemberInfoResponse{Id: m.LedgerMemberId, LedgerId: m.LedgerId, Uid: m.Uid, Role: m.Role, Status: m.Status, Nickname: nickname, JoinedTime: m.CreatedUnixTime}
}

// LedgerInfoResponse is the API representation of one ledger.
type LedgerInfoResponse struct {
	Id          int64      `json:"id,string"`
	OwnerUid    int64      `json:"ownerUid,string"`
	Type        LedgerType `json:"type"`
	FamilyId    int64      `json:"familyId,string,omitempty"`
	Name        string     `json:"name"`
	Comment     string     `json:"comment"`
	CreatedTime int64      `json:"createdTime"`
}

// ToLedgerInfoResponse converts a stored ledger to its API representation.
func (l *Ledger) ToLedgerInfoResponse() *LedgerInfoResponse {
	return &LedgerInfoResponse{
		Id:          l.LedgerId,
		OwnerUid:    l.OwnerUid,
		Type:        l.Type,
		FamilyId:    l.FamilyId,
		Name:        l.Name,
		Comment:     l.Comment,
		CreatedTime: l.CreatedUnixTime,
	}
}
