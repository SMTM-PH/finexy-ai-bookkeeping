//go:build cgo

package services

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
)

// initFamilyTestEnvironment wires the service singletons to one temporary
// SQLite database with every family-domain table synced.
func initFamilyTestEnvironment(t *testing.T) {
	t.Helper()

	require.NoError(t, datastore.InitializeDataStore(&settings.Config{DatabaseConfig: &settings.DatabaseConfig{
		DatabaseType:      settings.Sqlite3DbType,
		DatabasePath:      filepath.Join(t.TempDir(), "family-services.db"),
		MaxOpenConnection: 1,
		MaxIdleConnection: 1,
	}}))

	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(
		new(models.FamilyGroup), new(models.FamilyMember), new(models.FamilyInvitation),
		new(models.Ledger), new(models.LedgerMember), new(models.LedgerInvitation), new(models.SavingsGoal), new(models.SavingsGoalFund), new(models.Account), new(models.Transaction),
		new(models.TransactionCategory), new(models.TransactionTagIndex), new(models.TransactionPictureInfo),
		new(models.TransactionTemplate), new(models.ScheduledOccurrence)))
	require.NoError(t, datastore.Container.UserStore.SyncStructs(new(models.User)))

	require.NoError(t, uuid.InitializeUuidGenerator(&settings.Config{
		UuidGeneratorType: settings.InternalUuidGeneratorType,
		UuidServerId:      1,
	}))
}

func TestFamilyLedgerAndGoalServiceFlow(t *testing.T) {
	initFamilyTestEnvironment(t)

	// The owner creates a family and becomes its only member.
	group, err := Families.CreateFamilyGroup(nil, 100, &models.FamilyGroupCreateRequest{Name: "温暖小家"})
	require.NoError(t, err)
	require.NotNil(t, group)

	groups, err := Families.ListFamilyGroups(nil, 100)
	require.NoError(t, err)
	require.Len(t, groups, 1)
	assert.Equal(t, 1, groups[0].MemberCount)
	assert.Equal(t, int64(100), groups[0].OwnerUid)

	// A user without membership has no access at all.
	_, err = Families.GetFamilyGroup(nil, 200, group.FamilyGroupId)
	assert.ErrorIs(t, err, errs.ErrFamilyAccessDenied)

	// The owner invites one member and one viewer; both join by token.
	memberInvitation, err := Families.CreateInvitation(nil, 100, &models.FamilyInvitationCreateRequest{
		FamilyId: group.FamilyGroupId, InviteeName: "陈远", Role: models.FAMILY_MEMBER_ROLE_MEMBER,
	})
	require.NoError(t, err)
	_, err = Families.AcceptInvitation(nil, 200, memberInvitation.Token)
	require.NoError(t, err)

	viewerInvitation, err := Families.CreateInvitation(nil, 100, &models.FamilyInvitationCreateRequest{
		FamilyId: group.FamilyGroupId, InviteeName: "林妈妈", Role: models.FAMILY_MEMBER_ROLE_VIEWER,
	})
	require.NoError(t, err)
	_, err = Families.AcceptInvitation(nil, 300, viewerInvitation.Token)
	require.NoError(t, err)

	// A consumed invitation cannot be replayed; a fresh invitation for the
	// same user is rejected because the user has already joined.
	_, err = Families.AcceptInvitation(nil, 200, memberInvitation.Token)
	assert.ErrorIs(t, err, errs.ErrFamilyInvitationInvalid)

	replayInvitation, err := Families.CreateInvitation(nil, 100, &models.FamilyInvitationCreateRequest{
		FamilyId: group.FamilyGroupId, InviteeName: "重复", Role: models.FAMILY_MEMBER_ROLE_MEMBER,
	})
	require.NoError(t, err)
	_, err = Families.AcceptInvitation(nil, 200, replayInvitation.Token)
	assert.ErrorIs(t, err, errs.ErrFamilyAlreadyMember)

	members, err := Families.ListMembers(nil, 100, group.FamilyGroupId)
	require.NoError(t, err)
	assert.Len(t, members, 3)

	// Members cannot manage; the owner can change roles.
	_, err = Families.ChangeMemberRole(nil, 200, &models.FamilyMemberRoleChangeRequest{
		FamilyId: group.FamilyGroupId, MemberId: members[1].Id, Role: models.FAMILY_MEMBER_ROLE_ADMIN,
	})
	assert.ErrorIs(t, err, errs.ErrFamilyAccessDenied)

	changed, err := Families.ChangeMemberRole(nil, 100, &models.FamilyMemberRoleChangeRequest{
		FamilyId: group.FamilyGroupId, MemberId: members[1].Id, Role: models.FAMILY_MEMBER_ROLE_ADMIN,
	})
	require.NoError(t, err)
	assert.Equal(t, models.FAMILY_MEMBER_ROLE_ADMIN, changed.Role)

	// The owner role is immutable and the owner cannot leave.
	_, err = Families.ChangeMemberRole(nil, 100, &models.FamilyMemberRoleChangeRequest{
		FamilyId: group.FamilyGroupId, MemberId: members[0].Id, Role: models.FAMILY_MEMBER_ROLE_VIEWER,
	})
	assert.ErrorIs(t, err, errs.ErrFamilyOwnerRoleImmutable)
	assert.ErrorIs(t, Families.LeaveFamily(nil, 100, group.FamilyGroupId), errs.ErrFamilyOwnerCannotLeave)

	// A revoked invitation can no longer be accepted.
	revokedInvitation, err := Families.CreateInvitation(nil, 100, &models.FamilyInvitationCreateRequest{
		FamilyId: group.FamilyGroupId, InviteeName: "撤销", Role: models.FAMILY_MEMBER_ROLE_MEMBER,
	})
	require.NoError(t, err)
	require.NoError(t, Families.RevokeInvitation(nil, 100, group.FamilyGroupId, revokedInvitation.InvitationId))
	_, err = Families.AcceptInvitation(nil, 400, revokedInvitation.Token)
	assert.ErrorIs(t, err, errs.ErrFamilyInvitationInvalid)

	// An expired invitation is lazily expired and rejected.
	expiredInvitation, err := Families.CreateInvitation(nil, 100, &models.FamilyInvitationCreateRequest{
		FamilyId: group.FamilyGroupId, InviteeName: "过期", Role: models.FAMILY_MEMBER_ROLE_MEMBER,
	})
	require.NoError(t, err)
	_, err = Families.UserDataDB(100).NewSession(nil).ID(expiredInvitation.InvitationId).
		Cols("expired_unix_time").
		Update(&models.FamilyInvitation{ExpiredUnixTime: 1})
	require.NoError(t, err)
	_, err = Families.AcceptInvitation(nil, 400, expiredInvitation.Token)
	assert.ErrorIs(t, err, errs.ErrFamilyInvitationInvalid)

	// Ledgers: personal ledgers stay private, family ledgers are shared.
	personalLedger, err := Ledgers.CreateLedger(nil, 100, &models.LedgerCreateRequest{
		Type: models.LEDGER_TYPE_PERSONAL, Name: "个人账本",
	})
	require.NoError(t, err)
	personalMembers, err := Ledgers.ListMembers(nil, 100, personalLedger.LedgerId)
	require.NoError(t, err)
	require.Len(t, personalMembers, 1)
	assert.Equal(t, int64(100), personalMembers[0].Uid)
	assert.Equal(t, models.FAMILY_MEMBER_ROLE_OWNER, personalMembers[0].Role)
	personalInvite, err := Ledgers.CreateInvitation(nil, 100, &models.LedgerInvitationCreateRequest{LedgerId: personalLedger.LedgerId, InviteeName: "协作者", Role: models.FAMILY_MEMBER_ROLE_MEMBER})
	require.NoError(t, err)
	_, err = Ledgers.AcceptInvitation(nil, 500, personalInvite.Token)
	require.NoError(t, err)
	_, _, err = Ledgers.GetLedgerWithAccess(nil, 500, personalLedger.LedgerId, models.FamilyMemberRole.CanWrite)
	require.NoError(t, err)
	personalMembers, err = Ledgers.ListMembers(nil, 100, personalLedger.LedgerId)
	require.NoError(t, err)
	require.Len(t, personalMembers, 2)
	var collaboratorId int64
	for _, member := range personalMembers {
		if member.Uid == 500 {
			collaboratorId = member.Id
		}
	}
	require.NotZero(t, collaboratorId)
	changedLedgerMember, err := Ledgers.ChangeMemberRole(nil, 100, &models.LedgerMemberRoleChangeRequest{LedgerId: personalLedger.LedgerId, MemberId: collaboratorId, Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	assert.Equal(t, models.FAMILY_MEMBER_ROLE_VIEWER, changedLedgerMember.Role)
	_, _, err = Ledgers.GetLedgerWithAccess(nil, 500, personalLedger.LedgerId, models.FamilyMemberRole.CanWrite)
	assert.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	require.NoError(t, Ledgers.LeaveLedger(nil, 500, personalLedger.LedgerId))
	assert.ErrorIs(t, Ledgers.LeaveLedger(nil, 100, personalLedger.LedgerId), errs.ErrLedgerOwnerCannotLeave)

	familyLedger, err := Ledgers.CreateLedger(nil, 100, &models.LedgerCreateRequest{
		Type: models.LEDGER_TYPE_FAMILY, FamilyId: group.FamilyGroupId, Name: "家庭账本",
	})
	require.NoError(t, err)
	ledgerMembers, err := Ledgers.ListMembers(nil, 200, familyLedger.LedgerId)
	require.NoError(t, err)
	require.Len(t, ledgerMembers, 3)
	adminLedger, err := Ledgers.CreateLedger(nil, 200, &models.LedgerCreateRequest{
		Type: models.LEDGER_TYPE_FAMILY, FamilyId: group.FamilyGroupId, Name: "管理员建立的家庭账本",
	})
	require.NoError(t, err)
	adminGoal, err := SavingsGoals.CreateSavingsGoal(nil, 200, &models.SavingsGoalCreateRequest{
		LedgerId: adminLedger.LedgerId, Name: "共同教育金", TargetAmount: 50000,
	})
	require.NoError(t, err)
	adminAccount := &models.Account{AccountId: 9003, Uid: 200, LedgerId: adminLedger.LedgerId,
		Category: models.ACCOUNT_CATEGORY_CASH, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT,
		Name: "管理员钱包", Currency: "CNY"}
	_, err = Families.UserDataDB(100).NewSession(nil).Insert(adminAccount)
	require.NoError(t, err)
	adminFund, err := SavingsGoals.DepositToGoal(nil, 200, &models.SavingsGoalFundRequest{
		Id: adminGoal.SavingsGoalId, Amount: 10000, AccountId: adminAccount.AccountId,
	})
	require.NoError(t, err)
	adminPosted := &models.Transaction{}
	has, err := Families.UserDataDB(100).NewSession(nil).ID(adminFund.TransactionId).Get(adminPosted)
	require.NoError(t, err)
	require.True(t, has)
	assert.Equal(t, int64(100), adminPosted.Uid)
	assert.Equal(t, int64(200), adminPosted.RecorderUid)

	_, err = Ledgers.CreateLedger(nil, 300, &models.LedgerCreateRequest{
		Type: models.LEDGER_TYPE_FAMILY, FamilyId: group.FamilyGroupId, Name: "家庭账本二",
	})
	assert.ErrorIs(t, err, errs.ErrFamilyAccessDenied)

	ownerLedgers, err := Ledgers.ListLedgers(nil, 100, 0)
	require.NoError(t, err)
	assert.Len(t, ownerLedgers, 3)

	viewerLedgers, err := Ledgers.ListLedgers(nil, 300, 0)
	require.NoError(t, err)
	require.Len(t, viewerLedgers, 2)
	assert.Equal(t, familyLedger.LedgerId, viewerLedgers[0].Id)

	_, _, err = Ledgers.GetLedgerWithAccess(nil, 200, personalLedger.LedgerId, models.FamilyMemberRole.CanWrite)
	assert.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	_, _, err = Ledgers.GetLedgerWithAccess(nil, 300, familyLedger.LedgerId, models.FamilyMemberRole.CanWrite)
	assert.ErrorIs(t, err, errs.ErrLedgerAccessDenied)

	// A complete account group and its compatible history move atomically.
	// The visible Unix second is preserved while ownership changes to the
	// family owner; viewers cannot initiate the move.
	movable := &models.Account{AccountId: 9010, Uid: 200, Category: models.ACCOUNT_CATEGORY_CASH, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "待迁移钱包", Currency: "CNY", Balance: 8800}
	movableOpening := &models.Transaction{TransactionId: 9110, Uid: 200, Type: models.TRANSACTION_DB_TYPE_MODIFY_BALANCE,
		AccountId: movable.AccountId, RelatedAccountId: movable.AccountId, Amount: 8800, RelatedAccountAmount: 8800, TransactionTime: 1700000000001}
	_, err = Families.UserDataDB(200).NewSession(nil).Insert(movable, movableOpening)
	require.NoError(t, err)
	_, err = Accounts.MoveAccountToLedger(nil, 300, movable.AccountId, familyLedger.LedgerId)
	assert.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	moved, err := Accounts.MoveAccountToLedger(nil, 200, movable.AccountId, familyLedger.LedgerId)
	require.NoError(t, err)
	require.Len(t, moved, 1)
	assert.Equal(t, familyLedger.LedgerId, moved[0].LedgerId)
	storedOpening := &models.Transaction{}
	has, err = Families.UserDataDB(100).NewSession(nil).ID(movableOpening.TransactionId).Get(storedOpening)
	require.NoError(t, err)
	require.True(t, has)
	assert.Equal(t, familyLedger.LedgerId, storedOpening.LedgerId)
	assert.Equal(t, int64(100), storedOpening.Uid)
	assert.Equal(t, int64(200), storedOpening.RecorderUid)
	memberAccounts, err := Accounts.GetAccountsInLedger(nil, 300, familyLedger.LedgerId)
	require.NoError(t, err)
	assert.Contains(t, accountIds(memberAccounts), movable.AccountId)

	// A transaction linked to another account group blocks migration and the
	// original account/history remain in their source ledger.
	left := &models.Account{AccountId: 9020, Uid: 100, Category: models.ACCOUNT_CATEGORY_CASH, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "转出钱包", Currency: "CNY"}
	right := &models.Account{AccountId: 9021, Uid: 100, Category: models.ACCOUNT_CATEGORY_CASH, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "转入钱包", Currency: "CNY"}
	cross := &models.Transaction{TransactionId: 9120, Uid: 100, Type: models.TRANSACTION_DB_TYPE_TRANSFER_OUT,
		AccountId: left.AccountId, RelatedAccountId: right.AccountId, Amount: 10, RelatedAccountAmount: 10, TransactionTime: 1700000001001}
	_, err = Families.UserDataDB(100).NewSession(nil).Insert(left, right, cross)
	require.NoError(t, err)
	_, err = Accounts.MoveAccountToLedger(nil, 100, left.AccountId, familyLedger.LedgerId)
	assert.ErrorIs(t, err, errs.ErrAccountLedgerMigrationBlocked)
	leftAfter := &models.Account{}
	has, err = Families.UserDataDB(100).NewSession(nil).ID(left.AccountId).Get(leftAfter)
	require.NoError(t, err)
	require.True(t, has)
	assert.Equal(t, models.DefaultLedgerId, leftAfter.LedgerId)

	// Family deletion resolves the owning ledger shard and checks both sides
	// of transaction account references. An account with only its opening row
	// can be deleted together with that row.
	deletable := &models.Account{AccountId: 9030, Uid: 100, LedgerId: familyLedger.LedgerId, Category: models.ACCOUNT_CATEGORY_CASH, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "可删除家庭钱包", Currency: "CNY", Balance: 100}
	deletableOpening := &models.Transaction{TransactionId: 9130, Uid: 100, LedgerId: familyLedger.LedgerId, Type: models.TRANSACTION_DB_TYPE_MODIFY_BALANCE,
		AccountId: deletable.AccountId, RelatedAccountId: deletable.AccountId, Amount: 100, RelatedAccountAmount: 100, TransactionTime: 1700000002001}
	_, err = Families.UserDataDB(100).NewSession(nil).Insert(deletable, deletableOpening)
	require.NoError(t, err)
	assert.ErrorIs(t, Accounts.DeleteAccount(nil, 300, deletable.AccountId), errs.ErrLedgerAccessDenied)
	require.NoError(t, Accounts.DeleteAccount(nil, 100, deletable.AccountId))
	deletedAccount := &models.Account{}
	has, err = Families.UserDataDB(100).NewSession(nil).ID(deletable.AccountId).Get(deletedAccount)
	require.NoError(t, err)
	require.True(t, has)
	assert.True(t, deletedAccount.Deleted)
	deletedOpening := &models.Transaction{}
	has, err = Families.UserDataDB(100).NewSession(nil).ID(deletableOpening.TransactionId).Get(deletedOpening)
	require.NoError(t, err)
	require.True(t, has)
	assert.True(t, deletedOpening.Deleted)
	assert.ErrorIs(t, Accounts.DeleteAccount(nil, 100, right.AccountId), errs.ErrAccountInUseCannotBeDeleted)

	// Savings goals: managers create, viewers only read.
	goal, err := SavingsGoals.CreateSavingsGoal(nil, 100, &models.SavingsGoalCreateRequest{
		LedgerId: familyLedger.LedgerId, Name: "全家旅行基金", TargetAmount: 130000,
	})
	require.NoError(t, err)

	_, err = SavingsGoals.CreateSavingsGoal(nil, 300, &models.SavingsGoalCreateRequest{
		LedgerId: familyLedger.LedgerId, Name: " viewer goal", TargetAmount: 100,
	})
	assert.ErrorIs(t, err, errs.ErrSavingsGoalAccessDenied)

	// Family accounts carry the family ledger id; the default personal account
	// does not match the family goal.
	familyAccount := &models.Account{AccountId: 9001, Uid: 100, LedgerId: familyLedger.LedgerId, Category: models.ACCOUNT_CATEGORY_CASH, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "家庭公共钱包", Currency: "CNY"}
	_, err = Families.UserDataDB(100).NewSession(nil).Insert(familyAccount)
	require.NoError(t, err)
	personalAccount := &models.Account{AccountId: 9002, Uid: 100, Category: models.ACCOUNT_CATEGORY_CASH, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "个人钱包", Currency: "CNY"}
	_, err = Families.UserDataDB(100).NewSession(nil).Insert(personalAccount)
	require.NoError(t, err)

	_, err = SavingsGoals.DepositToGoal(nil, 100, &models.SavingsGoalFundRequest{Id: goal.SavingsGoalId, Amount: 100000, AccountId: personalAccount.AccountId})
	assert.ErrorIs(t, err, errs.ErrSavingsGoalAccountMismatch)

	deposit, err := SavingsGoals.DepositToGoal(nil, 100, &models.SavingsGoalFundRequest{Id: goal.SavingsGoalId, Amount: 100000, AccountId: familyAccount.AccountId})
	require.NoError(t, err)
	assert.NotZero(t, deposit.SavingsGoalFundId)
	assert.NotZero(t, deposit.TransactionId)
	posted := &models.Transaction{}
	has, err = Families.UserDataDB(100).NewSession(nil).ID(deposit.TransactionId).Get(posted)
	require.NoError(t, err)
	require.True(t, has)
	assert.Equal(t, models.TRANSACTION_DB_TYPE_TRANSFER_OUT, posted.Type)
	assert.Equal(t, familyLedger.LedgerId, posted.LedgerId)
	assert.Equal(t, deposit.SavingsGoalFundId, posted.SavingsGoalFundId)
	_, err = SavingsGoals.DepositToGoal(nil, 200, &models.SavingsGoalFundRequest{Id: goal.SavingsGoalId, Amount: 50000, AccountId: familyAccount.AccountId})
	require.NoError(t, err)
	_, err = SavingsGoals.DepositToGoal(nil, 300, &models.SavingsGoalFundRequest{Id: goal.SavingsGoalId, Amount: 1, AccountId: familyAccount.AccountId})
	assert.ErrorIs(t, err, errs.ErrSavingsGoalAccessDenied)

	// The saved amount is derived from movements, never stored on the goal.
	goalResponse, err := SavingsGoals.GetSavingsGoal(nil, 100, goal.SavingsGoalId)
	require.NoError(t, err)
	assert.Equal(t, int64(150000), goalResponse.SavedAmount)
	assert.True(t, goalResponse.Achieved)

	// Withdrawals reduce the saved amount and credit the selected account.
	_, err = SavingsGoals.WithdrawFromGoal(nil, 200, &models.SavingsGoalFundRequest{Id: goal.SavingsGoalId, Amount: 20000, AccountId: familyAccount.AccountId})
	require.NoError(t, err)
	_, err = SavingsGoals.WithdrawFromGoal(nil, 200, &models.SavingsGoalFundRequest{Id: goal.SavingsGoalId, Amount: 999999, AccountId: familyAccount.AccountId})
	assert.ErrorIs(t, err, errs.ErrSavingsGoalInsufficientSaved)

	goalResponse, err = SavingsGoals.GetSavingsGoal(nil, 100, goal.SavingsGoalId)
	require.NoError(t, err)
	assert.Equal(t, int64(130000), goalResponse.SavedAmount)

	funds, err := SavingsGoals.ListFunds(nil, 100, goal.SavingsGoalId)
	require.NoError(t, err)
	assert.Len(t, funds, 3)
	for _, fund := range funds {
		assert.NotZero(t, fund.TransactionId)
	}
	updatedFamilyAccount := &models.Account{}
	has, err = Families.UserDataDB(100).NewSession(nil).ID(familyAccount.AccountId).Get(updatedFamilyAccount)
	require.NoError(t, err)
	require.True(t, has)
	assert.Equal(t, int64(-130000), updatedFamilyAccount.Balance)
	listRequest := &models.TransactionListByMaxTimeRequest{Count: 50, WithCount: true}
	sharedRows, sharedCount, err := Transactions.GetLedgerTransactions(nil, 200, familyLedger.LedgerId, listRequest, nil, nil, nil, false)
	require.NoError(t, err)
	assert.Len(t, sharedRows, 4)
	assert.Equal(t, int64(4), sharedCount)
	assert.Contains(t, transactionIds(sharedRows), movableOpening.TransactionId)
	nowUtc := time.Now().UTC()
	monthlyRows, err := Transactions.GetLedgerTransactionsInMonth(nil, 200, familyLedger.LedgerId, int32(nowUtc.Year()), int32(nowUtc.Month()), 0, nil, nil, nil, false, "", "", 0, false)
	require.NoError(t, err)
	assert.Len(t, monthlyRows, 3)
	_, _, err = Transactions.GetLedgerTransactions(nil, 400, familyLedger.LedgerId, listRequest, nil, nil, nil, false)
	assert.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	defaultRows, err := Transactions.GetAllTransactions(nil, 100, 50, true)
	require.NoError(t, err)
	require.Len(t, defaultRows, 1)
	assert.Equal(t, cross.TransactionId, defaultRows[0].TransactionId)
	sharedAccounts, err := Accounts.GetAccountsInLedger(nil, 300, familyLedger.LedgerId)
	require.NoError(t, err)
	assert.Len(t, sharedAccounts, 2)
	statistics, err := Transactions.GetLedgerTotalInflowAndOutflow(nil, 200, familyLedger.LedgerId, 0, 0, nil, false, "", 0, time.UTC, false)
	require.NoError(t, err)
	require.Len(t, statistics, 2)
	statisticsByType := make(map[models.TransactionDbType]int64, len(statistics))
	for _, statistic := range statistics {
		statisticsByType[statistic.Type] = statistic.Amount
	}
	assert.Equal(t, int64(150000), statisticsByType[models.TRANSACTION_DB_TYPE_TRANSFER_OUT])
	assert.Equal(t, int64(20000), statisticsByType[models.TRANSACTION_DB_TYPE_TRANSFER_IN])
	_, err = Transactions.GetLedgerTotalInflowAndOutflow(nil, 400, familyLedger.LedgerId, 0, 0, nil, false, "", 0, time.UTC, false)
	assert.ErrorIs(t, err, errs.ErrLedgerAccessDenied)

	// Goals with movements cannot be deleted; empty goals can.
	assert.ErrorIs(t, SavingsGoals.DeleteSavingsGoal(nil, 100, goal.SavingsGoalId), errs.ErrSavingsGoalHasFunds)

	emptyGoal, err := SavingsGoals.CreateSavingsGoal(nil, 100, &models.SavingsGoalCreateRequest{
		LedgerId: familyLedger.LedgerId, Name: "新家电置换", TargetAmount: 800000,
	})
	require.NoError(t, err)
	assert.NoError(t, SavingsGoals.DeleteSavingsGoal(nil, 100, emptyGoal.SavingsGoalId))

	// The default personal ledger needs no row: implicit and private.
	defaultGoal, err := SavingsGoals.CreateSavingsGoal(nil, 100, &models.SavingsGoalCreateRequest{
		Name: "个人笔记本升级", TargetAmount: 1200000,
	})
	require.NoError(t, err)
	assert.Equal(t, models.DefaultLedgerId, defaultGoal.LedgerId)

	_, err = SavingsGoals.DepositToGoal(nil, 200, &models.SavingsGoalFundRequest{Id: defaultGoal.SavingsGoalId, Amount: 100, AccountId: personalAccount.AccountId})
	assert.ErrorIs(t, err, errs.ErrSavingsGoalAccessDenied)

	defaultDeposit, err := SavingsGoals.DepositToGoal(nil, 100, &models.SavingsGoalFundRequest{Id: defaultGoal.SavingsGoalId, Amount: 450000, AccountId: personalAccount.AccountId})
	require.NoError(t, err)
	assert.NotZero(t, defaultDeposit.SavingsGoalFundId)

	// The default personal ledger is user-private: another user lists no
	// goals there and cannot see the owner's goal.
	ownGoals, err := SavingsGoals.ListSavingsGoals(nil, 200, models.DefaultLedgerId)
	require.NoError(t, err)
	assert.Empty(t, ownGoals)
	_, err = SavingsGoals.GetSavingsGoal(nil, 200, defaultGoal.SavingsGoalId)
	assert.ErrorIs(t, err, errs.ErrSavingsGoalAccessDenied)
}

func accountIds(accounts []*models.Account) []int64 {
	ids := make([]int64, len(accounts))
	for i, account := range accounts {
		ids[i] = account.AccountId
	}
	return ids
}

func transactionIds(transactions []*models.Transaction) []int64 {
	ids := make([]int64, len(transactions))
	for i, transaction := range transactions {
		ids[i] = transaction.TransactionId
	}
	return ids
}
