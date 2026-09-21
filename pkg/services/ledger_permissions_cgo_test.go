//go:build cgo

package services

import (
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
)

func TestLedgerInvitationPreviewDoesNotConsumeToken(t *testing.T) {
	initFamilyTestEnvironment(t)
	ledger, err := Ledgers.CreateLedger(nil, 100, &models.LedgerCreateRequest{Type: models.LEDGER_TYPE_PERSONAL, Name: "Preview", Comment: "Shared expenses"})
	require.NoError(t, err)
	invitation, err := Ledgers.CreateInvitation(nil, 100, &models.LedgerInvitationCreateRequest{LedgerId: ledger.LedgerId, InviteeName: "Alex", Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)

	preview, err := Ledgers.PreviewInvitation(nil, 200, invitation.Token)
	require.NoError(t, err)
	require.Equal(t, ledger.LedgerId, preview.Ledger.Id)
	require.Equal(t, "Preview", preview.Ledger.Name)
	require.Equal(t, models.FAMILY_MEMBER_ROLE_VIEWER, preview.Role)
	require.Equal(t, "Alex", preview.InviteeName)
	require.Equal(t, invitation.ExpiredUnixTime, preview.ExpiredTime)

	stored := &models.LedgerInvitation{}
	has, err := Ledgers.UserDataDB(100).NewSession(nil).ID(invitation.InvitationId).Get(stored)
	require.NoError(t, err)
	require.True(t, has)
	require.Equal(t, models.FAMILY_INVITATION_STATUS_PENDING, stored.Status)
	_, err = Ledgers.AcceptInvitation(nil, 200, invitation.Token)
	require.NoError(t, err)
	_, err = Ledgers.PreviewInvitation(nil, 300, invitation.Token)
	require.ErrorIs(t, err, errs.ErrLedgerInvitationInvalid)

	expired, err := Ledgers.CreateInvitation(nil, 100, &models.LedgerInvitationCreateRequest{LedgerId: ledger.LedgerId, InviteeName: "Expired", Role: models.FAMILY_MEMBER_ROLE_MEMBER})
	require.NoError(t, err)
	_, err = Ledgers.UserDataDB(100).NewSession(nil).ID(expired.InvitationId).Cols("expired_unix_time").Update(&models.LedgerInvitation{ExpiredUnixTime: time.Now().Unix() - 1})
	require.NoError(t, err)
	_, err = Ledgers.PreviewInvitation(nil, 300, expired.Token)
	require.ErrorIs(t, err, errs.ErrLedgerInvitationInvalid)
}

func TestLedgerOverviewAggregatesVisibleDataAndEnforcesMembership(t *testing.T) {
	initFamilyTestEnvironment(t)
	ledger, err := Ledgers.CreateLedger(nil, 100, &models.LedgerCreateRequest{Type: models.LEDGER_TYPE_PERSONAL, Name: "Overview"})
	require.NoError(t, err)
	invitation, err := Ledgers.CreateInvitation(nil, 100, &models.LedgerInvitationCreateRequest{LedgerId: ledger.LedgerId, InviteeName: "Viewer", Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	_, err = Ledgers.AcceptInvitation(nil, 200, invitation.Token)
	require.NoError(t, err)
	db := Ledgers.UserDataDB(100)
	_, err = db.NewSession(nil).Insert(
		&models.Account{AccountId: 8101, Uid: 100, LedgerId: ledger.LedgerId, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Currency: "CNY", Balance: 100},
		&models.Account{AccountId: 8102, Uid: 100, LedgerId: ledger.LedgerId, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Currency: "CNY", Balance: -20},
		&models.Account{AccountId: 8103, Uid: 100, LedgerId: ledger.LedgerId, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Currency: "USD", Balance: 500},
		&models.Account{AccountId: 8104, Uid: 100, LedgerId: ledger.LedgerId, Type: models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS, Currency: "CNY", Balance: 999},
		&models.Account{AccountId: 8105, Uid: 100, LedgerId: ledger.LedgerId, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Currency: "CNY", Balance: 999, Hidden: true},
	)
	require.NoError(t, err)
	_, err = db.NewSession(nil).Insert(
		&models.Transaction{TransactionId: 8201, Uid: 100, LedgerId: ledger.LedgerId, Type: models.TRANSACTION_DB_TYPE_EXPENSE, AccountId: 8101, TransactionTime: 1},
		&models.Transaction{TransactionId: 8202, Uid: 100, LedgerId: ledger.LedgerId, Type: models.TRANSACTION_DB_TYPE_TRANSFER_OUT, AccountId: 8101, RelatedAccountId: 8102, TransactionTime: 2},
		&models.Transaction{TransactionId: 8203, Uid: 100, LedgerId: ledger.LedgerId, Type: models.TRANSACTION_DB_TYPE_TRANSFER_IN, AccountId: 8102, RelatedAccountId: 8101, TransactionTime: 3},
	)
	require.NoError(t, err)
	_, err = db.NewSession(nil).Insert(&models.SavingsGoal{SavingsGoalId: 8301, Uid: 100, LedgerId: ledger.LedgerId, Name: "Goal", TargetAmount: 100})
	require.NoError(t, err)

	overview, err := Ledgers.GetOverview(nil, 200, ledger.LedgerId)
	require.NoError(t, err)
	require.Equal(t, int64(2), overview.ActiveMemberCount)
	require.Equal(t, int64(3), overview.AccountCount)
	require.Equal(t, int64(2), overview.TransactionCount)
	require.Equal(t, int64(1), overview.SavingsGoalCount)
	require.Equal(t, []*models.LedgerBalanceInfoResponse{{Currency: "CNY", Balance: 80}, {Currency: "USD", Balance: 500}}, overview.Balances)
	_, err = Ledgers.GetOverview(nil, 300, ledger.LedgerId)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
}

func TestLedgerMemberCanPostIntoOwnerShardAndViewerCannotWrite(t *testing.T) {
	initFamilyTestEnvironment(t)
	ledger, err := Ledgers.CreateLedger(nil, 100, &models.LedgerCreateRequest{Type: models.LEDGER_TYPE_PERSONAL, Name: "Shared posting"})
	require.NoError(t, err)
	memberInvite, err := Ledgers.CreateInvitation(nil, 100, &models.LedgerInvitationCreateRequest{LedgerId: ledger.LedgerId, InviteeName: "Member", Role: models.FAMILY_MEMBER_ROLE_MEMBER})
	require.NoError(t, err)
	_, err = Ledgers.AcceptInvitation(nil, 200, memberInvite.Token)
	require.NoError(t, err)
	viewerInvite, err := Ledgers.CreateInvitation(nil, 100, &models.LedgerInvitationCreateRequest{LedgerId: ledger.LedgerId, InviteeName: "Viewer", Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	_, err = Ledgers.AcceptInvitation(nil, 300, viewerInvite.Token)
	require.NoError(t, err)

	db := Ledgers.UserDataDB(100)
	account := &models.Account{AccountId: 8401, Uid: 100, LedgerId: ledger.LedgerId, Category: models.ACCOUNT_CATEGORY_CASH, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "Shared cash", Currency: "CNY", Balance: 1000}
	category := &models.TransactionCategory{CategoryId: 8402, Uid: 100, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: 8403, Name: "Groceries", DisplayOrder: 1, Icon: 1, Color: "112233"}
	parent := &models.TransactionCategory{CategoryId: 8403, Uid: 100, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: models.LevelOneTransactionCategoryParentId, Name: "Food", DisplayOrder: 1, Icon: 1, Color: "112233"}
	_, err = db.NewSession(nil).Insert(account, parent, category)
	require.NoError(t, err)

	_, memberLedger, err := Ledgers.GetLedgerWithAccess(nil, 200, ledger.LedgerId, models.FamilyMemberRole.CanWrite)
	require.NoError(t, err)
	postingUid, err := Ledgers.LedgerDataOwnerUid(nil, 200, memberLedger)
	require.NoError(t, err)
	require.Equal(t, int64(100), postingUid)
	visibleCategories, err := TransactionCategories.GetAllCategoriesInLedger(nil, 200, ledger.LedgerId, models.CATEGORY_TYPE_EXPENSE, -1)
	require.NoError(t, err)
	require.Len(t, visibleCategories, 2)

	transaction := &models.Transaction{Uid: postingUid, LedgerId: ledger.LedgerId, RecorderUid: 200, PayerUid: 200, Type: models.TRANSACTION_DB_TYPE_EXPENSE, CategoryId: category.CategoryId, AccountId: account.AccountId, Amount: 250, TransactionTime: time.Now().UnixMilli()}
	require.NoError(t, Transactions.CreateTransaction(nil, transaction, nil, nil))
	stored := &models.Transaction{}
	has, err := db.NewSession(nil).ID(transaction.TransactionId).Get(stored)
	require.NoError(t, err)
	require.True(t, has)
	require.Equal(t, ledger.LedgerId, stored.LedgerId)
	require.Equal(t, int64(200), stored.RecorderUid)
	require.Equal(t, int64(200), stored.PayerUid)
	updatedAccount := &models.Account{}
	has, err = db.NewSession(nil).ID(account.AccountId).Get(updatedAccount)
	require.NoError(t, err)
	require.True(t, has)
	require.Equal(t, int64(750), updatedAccount.Balance)

	_, _, err = Ledgers.GetLedgerWithAccess(nil, 300, ledger.LedgerId, models.FamilyMemberRole.CanWrite)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	_, err = TransactionCategories.GetAllCategoriesInLedger(nil, 400, ledger.LedgerId, 0, -1)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
}

func TestLedgerDirectMembershipOverridesLegacyFamily(t *testing.T) {
	initFamilyTestEnvironment(t)
	group, err := Families.CreateFamilyGroup(nil, 100, &models.FamilyGroupCreateRequest{Name: "Legacy"})
	require.NoError(t, err)
	invite, err := Families.CreateInvitation(nil, 100, &models.FamilyInvitationCreateRequest{FamilyId: group.FamilyGroupId, InviteeName: "Member", Role: models.FAMILY_MEMBER_ROLE_MEMBER})
	require.NoError(t, err)
	_, err = Families.AcceptInvitation(nil, 200, invite.Token)
	require.NoError(t, err)
	ledger, err := Ledgers.CreateLedger(nil, 100, &models.LedgerCreateRequest{Type: models.LEDGER_TYPE_FAMILY, FamilyId: group.FamilyGroupId, Name: "Shared"})
	require.NoError(t, err)
	members, err := Ledgers.ListMembers(nil, 100, ledger.LedgerId)
	require.NoError(t, err)
	var memberID int64
	for _, member := range members {
		if member.Uid == 200 {
			memberID = member.Id
		}
	}
	require.NotZero(t, memberID)
	_, err = Ledgers.ChangeMemberRole(nil, 100, &models.LedgerMemberRoleChangeRequest{LedgerId: ledger.LedgerId, MemberId: memberID, Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	_, _, err = Ledgers.GetLedgerWithAccess(nil, 200, ledger.LedgerId, models.FamilyMemberRole.CanWrite)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	_, _, err = Ledgers.GetLedgerWithAccess(nil, 200, ledger.LedgerId, func(models.FamilyMemberRole) bool { return true })
	require.NoError(t, err)
	require.NoError(t, Ledgers.RemoveMember(nil, 100, ledger.LedgerId, memberID))
	_, _, err = Ledgers.GetLedgerWithAccess(nil, 200, ledger.LedgerId, func(models.FamilyMemberRole) bool { return true })
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	// Family membership is deliberately still active; it must not restore access.
	visible, err := Ledgers.ListLedgers(nil, 200, 0)
	require.NoError(t, err)
	require.Empty(t, visible)
	visible, err = Ledgers.ListLedgers(nil, 200, group.FamilyGroupId)
	require.NoError(t, err)
	require.Empty(t, visible)
	_, err = Families.GetFamilyGroup(nil, 200, group.FamilyGroupId)
	require.NoError(t, err)
}

func TestLedgerOnlyOwnerCanManageAdministrators(t *testing.T) {
	initFamilyTestEnvironment(t)
	ledger, err := Ledgers.CreateLedger(nil, 100, &models.LedgerCreateRequest{Type: models.LEDGER_TYPE_PERSONAL, Name: "Team"})
	require.NoError(t, err)
	ids := map[int64]int64{}
	for _, uid := range []int64{200, 300} {
		invite, err := Ledgers.CreateInvitation(nil, 100, &models.LedgerInvitationCreateRequest{LedgerId: ledger.LedgerId, InviteeName: "Member", Role: models.FAMILY_MEMBER_ROLE_MEMBER})
		require.NoError(t, err)
		_, err = Ledgers.AcceptInvitation(nil, uid, invite.Token)
		require.NoError(t, err)
	}
	members, err := Ledgers.ListMembers(nil, 100, ledger.LedgerId)
	require.NoError(t, err)
	for _, member := range members {
		ids[member.Uid] = member.Id
	}
	change := func(actor, target int64, role models.FamilyMemberRole) error {
		_, err := Ledgers.ChangeMemberRole(nil, actor, &models.LedgerMemberRoleChangeRequest{LedgerId: ledger.LedgerId, MemberId: ids[target], Role: role})
		return err
	}
	require.NoError(t, change(100, 200, models.FAMILY_MEMBER_ROLE_ADMIN))
	require.ErrorIs(t, change(200, 300, models.FAMILY_MEMBER_ROLE_ADMIN), errs.ErrLedgerAccessDenied)
	require.NoError(t, change(200, 300, models.FAMILY_MEMBER_ROLE_VIEWER))
	require.NoError(t, change(100, 300, models.FAMILY_MEMBER_ROLE_ADMIN))
	require.ErrorIs(t, change(200, 300, models.FAMILY_MEMBER_ROLE_MEMBER), errs.ErrLedgerAccessDenied)
	require.ErrorIs(t, Ledgers.RemoveMember(nil, 200, ledger.LedgerId, ids[300]), errs.ErrLedgerAccessDenied)
	require.ErrorIs(t, change(200, 200, models.FAMILY_MEMBER_ROLE_MEMBER), errs.ErrLedgerAccessDenied)
	require.ErrorIs(t, change(100, 300, models.FAMILY_MEMBER_ROLE_OWNER), errs.ErrParameterInvalid)
	require.NoError(t, Ledgers.RemoveMember(nil, 100, ledger.LedgerId, ids[300]))
	require.ErrorIs(t, change(100, 300, models.FAMILY_MEMBER_ROLE_MEMBER), errs.ErrLedgerMemberNotFound)
}

func TestLedgerDeleteRequiresOwnerAndEmptyLedger(t *testing.T) {
	initFamilyTestEnvironment(t)
	ledger, err := Ledgers.CreateLedger(nil, 100, &models.LedgerCreateRequest{Type: models.LEDGER_TYPE_PERSONAL, Name: "Temporary"})
	require.NoError(t, err)
	invite, err := Ledgers.CreateInvitation(nil, 100, &models.LedgerInvitationCreateRequest{LedgerId: ledger.LedgerId, InviteeName: "Admin", Role: models.FAMILY_MEMBER_ROLE_MEMBER})
	require.NoError(t, err)
	_, err = Ledgers.AcceptInvitation(nil, 200, invite.Token)
	require.NoError(t, err)
	members, err := Ledgers.ListMembers(nil, 100, ledger.LedgerId)
	require.NoError(t, err)
	var adminID int64
	for _, member := range members {
		if member.Uid == 200 {
			adminID = member.Id
		}
	}
	_, err = Ledgers.ChangeMemberRole(nil, 100, &models.LedgerMemberRoleChangeRequest{LedgerId: ledger.LedgerId, MemberId: adminID, Role: models.FAMILY_MEMBER_ROLE_ADMIN})
	require.NoError(t, err)
	require.ErrorIs(t, Ledgers.DeleteLedger(nil, 200, ledger.LedgerId), errs.ErrLedgerAccessDenied)
	_, err = Ledgers.GetDeletePreview(nil, 200, ledger.LedgerId)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	_, err = Ledgers.GetDeletePreview(nil, 100, models.DefaultLedgerId)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)

	db := Ledgers.UserDataDB(100)
	_, err = db.NewSession(nil).Insert(&models.Account{AccountId: 9100, Uid: 100, LedgerId: ledger.LedgerId, Category: models.ACCOUNT_CATEGORY_CASH, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "Cash", Currency: "CNY"})
	require.NoError(t, err)
	_, err = db.NewSession(nil).Insert(&models.Transaction{TransactionId: 9200, Uid: 100, LedgerId: ledger.LedgerId, AccountId: 9100})
	require.NoError(t, err)
	_, err = db.NewSession(nil).Insert(&models.SavingsGoal{SavingsGoalId: 9300, Uid: 100, LedgerId: ledger.LedgerId, Name: "Goal", TargetAmount: 100})
	require.NoError(t, err)
	_, err = db.NewSession(nil).Insert(&models.SavingsGoalFund{SavingsGoalFundId: 9400, GoalId: 9300, Uid: 100, Direction: models.SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT, Amount: 10, AccountId: 9100})
	require.NoError(t, err)
	pending, err := Ledgers.CreateInvitation(nil, 100, &models.LedgerInvitationCreateRequest{LedgerId: ledger.LedgerId, InviteeName: "Pending", Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	preview, err := Ledgers.GetDeletePreview(nil, 100, ledger.LedgerId)
	require.NoError(t, err)
	require.False(t, preview.CanDelete)
	require.Equal(t, int64(2), preview.ActiveMemberCount)
	require.Equal(t, int64(1), preview.PendingInvitationCount)
	require.Equal(t, int64(1), preview.AccountCount)
	require.Equal(t, int64(1), preview.TransactionCount)
	require.Equal(t, int64(1), preview.SavingsGoalCount)
	require.Equal(t, int64(1), preview.SavingsGoalFundCount)
	require.ElementsMatch(t, []string{"accounts", "transactions", "savingsGoals"}, preview.BlockingReasons)
	require.ErrorIs(t, Ledgers.DeleteLedger(nil, 100, ledger.LedgerId), errs.ErrLedgerNotEmpty)
	_, err = db.NewSession(nil).ID(9400).Delete(&models.SavingsGoalFund{})
	require.NoError(t, err)
	_, err = db.NewSession(nil).ID(9300).Delete(&models.SavingsGoal{})
	require.NoError(t, err)
	_, err = db.NewSession(nil).ID(9200).Delete(&models.Transaction{})
	require.NoError(t, err)
	_, err = db.NewSession(nil).ID(9100).Delete(&models.Account{})
	require.NoError(t, err)
	preview, err = Ledgers.GetDeletePreview(nil, 100, ledger.LedgerId)
	require.NoError(t, err)
	require.True(t, preview.CanDelete)
	require.Empty(t, preview.BlockingReasons)
	require.NoError(t, Ledgers.DeleteLedger(nil, 100, ledger.LedgerId))
	stored := &models.LedgerInvitation{}
	has, err := db.NewSession(nil).ID(pending.InvitationId).Get(stored)
	require.NoError(t, err)
	require.True(t, has)
	require.Equal(t, models.FAMILY_INVITATION_STATUS_REVOKED, stored.Status)
	_, _, err = Ledgers.GetLedgerWithAccess(nil, 100, ledger.LedgerId, func(models.FamilyMemberRole) bool { return true })
	require.ErrorIs(t, err, errs.ErrLedgerNotFound)
	require.ErrorIs(t, Ledgers.DeleteLedger(nil, 100, models.DefaultLedgerId), errs.ErrLedgerAccessDenied)
}
