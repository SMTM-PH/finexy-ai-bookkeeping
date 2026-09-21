package models

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestLedgerValidation(t *testing.T) {
	personal := &Ledger{OwnerUid: 100, Type: LEDGER_TYPE_PERSONAL, FamilyId: DefaultLedgerId, Name: "个人账本"}
	assert.NoError(t, personal.Validate())
	assert.False(t, personal.IsFamilyLedger())

	family := &Ledger{OwnerUid: 100, Type: LEDGER_TYPE_FAMILY, FamilyId: 3, Name: "家庭账本"}
	assert.NoError(t, family.Validate())
	assert.True(t, family.IsFamilyLedger())

	// A personal ledger must not reference a family.
	personalWithFamily := &Ledger{OwnerUid: 100, Type: LEDGER_TYPE_PERSONAL, FamilyId: 3, Name: "个人账本"}
	assert.Error(t, personalWithFamily.Validate())

	// A family ledger requires its family reference.
	familyWithoutId := &Ledger{OwnerUid: 100, Type: LEDGER_TYPE_FAMILY, FamilyId: DefaultLedgerId, Name: "家庭账本"}
	assert.Error(t, familyWithoutId.Validate())

	badType := &Ledger{OwnerUid: 100, Type: LedgerType(9), FamilyId: DefaultLedgerId, Name: "个人账本"}
	assert.Error(t, badType.Validate())

	noOwner := &Ledger{OwnerUid: 0, Type: LEDGER_TYPE_PERSONAL, FamilyId: DefaultLedgerId, Name: "个人账本"}
	assert.Error(t, noOwner.Validate())

	noName := &Ledger{OwnerUid: 100, Type: LEDGER_TYPE_PERSONAL, FamilyId: DefaultLedgerId, Name: ""}
	assert.Error(t, noName.Validate())

	tooLongName := &Ledger{OwnerUid: 100, Type: LEDGER_TYPE_PERSONAL, FamilyId: DefaultLedgerId, Name: string(make([]byte, MaximumNameLength+1))}
	assert.Error(t, tooLongName.Validate())
}

// TestDefaultLedgerIdContract pins the backward-compatibility rule: rows
// without an explicit ledger id keep belonging to the user's default personal
// ledger, so pre-existing data never needs a value migration.
func TestDefaultLedgerIdContract(t *testing.T) {
	assert.Equal(t, int64(0), DefaultLedgerId)
	assert.Equal(t, LEDGER_TYPE_PERSONAL, LedgerType(1))
	assert.Equal(t, LEDGER_TYPE_FAMILY, LedgerType(2))
	assert.False(t, LedgerType(0).IsValid())
	assert.False(t, LedgerType(3).IsValid())
}

func TestLedgerInfoResponseConversion(t *testing.T) {
	ledger := &Ledger{
		LedgerId:        9,
		OwnerUid:        100,
		Type:            LEDGER_TYPE_FAMILY,
		FamilyId:        3,
		Name:            "家庭账本",
		Comment:         "共享账本",
		CreatedUnixTime: 1234,
	}

	response := ledger.ToLedgerInfoResponse()
	assert.Equal(t, int64(9), response.Id)
	assert.Equal(t, int64(100), response.OwnerUid)
	assert.Equal(t, LEDGER_TYPE_FAMILY, response.Type)
	assert.Equal(t, int64(3), response.FamilyId)
	assert.Equal(t, "家庭账本", response.Name)
	assert.Equal(t, int64(1234), response.CreatedTime)
}

func TestLedgerMemberValidationAndResponse(t *testing.T) {
	member := &LedgerMember{LedgerMemberId: 7, LedgerId: 9, Uid: 100, Role: FAMILY_MEMBER_ROLE_OWNER, Status: FAMILY_MEMBER_STATUS_ACTIVE, CreatedUnixTime: 1234}
	assert.NoError(t, member.Validate())
	assert.True(t, member.CanAct(FamilyMemberRole.CanManage))
	response := member.ToLedgerMemberInfoResponse("账本主人")
	assert.Equal(t, int64(9), response.LedgerId)
	assert.Equal(t, "账本主人", response.Nickname)

	member.LedgerId = 0
	assert.Error(t, member.Validate())
}
