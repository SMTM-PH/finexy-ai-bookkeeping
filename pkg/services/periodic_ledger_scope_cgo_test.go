//go:build cgo

package services

import (
	"encoding/json"
	"errors"
	"sync"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
	"xorm.io/xorm"
)

func setupPeriodicScope(t *testing.T) *occurrenceSeed {
	return setupPeriodicScopeWithConnections(t, 1)
}

func setupPeriodicScopeWithConnections(t *testing.T, connections int) *occurrenceSeed {
	t.Helper()
	setupOccurrenceLedgerWithConnections(t, connections)
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(models.Ledger), new(models.LedgerMember), new(models.LedgerInvitation), new(models.MonthlyBudget), new(models.SavingsGoal), new(models.SavingsGoalFund), new(models.AIReviewItem)))
	seed := insertOccurrenceSeed(t, 1, 10000, 5000)
	db := occurrenceDB(t)
	_, err := db.NewSession(nil).Insert(&models.Ledger{LedgerId: 11, OwnerUid: 1, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic periodic A"}, &models.Ledger{LedgerId: 12, OwnerUid: 1, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic periodic B"})
	require.NoError(t, err)
	for i, role := range []models.FamilyMemberRole{models.FAMILY_MEMBER_ROLE_OWNER, models.FAMILY_MEMBER_ROLE_ADMIN, models.FAMILY_MEMBER_ROLE_MEMBER, models.FAMILY_MEMBER_ROLE_VIEWER} {
		_, err := db.NewSession(nil).Insert(&models.LedgerMember{LedgerMemberId: int64(110 + i), LedgerId: 11, Uid: int64(i + 1), Role: role, Status: models.FAMILY_MEMBER_STATUS_ACTIVE})
		require.NoError(t, err)
	}
	_, err = db.NewSession(nil).Insert(&models.LedgerMember{LedgerMemberId: 120, LedgerId: 12, Uid: 1, Role: models.FAMILY_MEMBER_ROLE_OWNER, Status: models.FAMILY_MEMBER_STATUS_ACTIVE})
	require.NoError(t, err)
	_, err = db.NewSession(nil).In("account_id", []int64{seed.account.AccountId, seed.other.AccountId}).Cols("ledger_id").Update(&models.Account{LedgerId: 11})
	require.NoError(t, err)
	seed.account.LedgerId, seed.other.LedgerId = 11, 11
	defaultAccount := *seed.account
	defaultAccount.AccountId, defaultAccount.LedgerId = 100003, 0
	otherAccount := *seed.account
	otherAccount.AccountId, otherAccount.LedgerId = 100004, 12
	_, err = db.NewSession(nil).Insert(&defaultAccount, &otherAccount, &models.TransactionTag{TagId: 100901, Uid: 1, Name: "Synthetic owner tag"}, &models.TransactionTag{TagId: 100902, Uid: 1, Name: "Synthetic hidden owner tag", Hidden: true}, &models.TransactionTag{TagId: 200901, Uid: 2, Name: "Synthetic foreign tag"})
	require.NoError(t, err)
	return seed
}
func periodicTemplate(seed *occurrenceSeed, actor, ledger int64) *models.TransactionTemplate {
	return &models.TransactionTemplate{Uid: actor, LedgerId: ledger, Name: "Synthetic recurring expense", TemplateType: models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE,
		Type: models.TRANSACTION_TYPE_EXPENSE, CategoryId: seed.categories[models.CATEGORY_TYPE_EXPENSE].CategoryId, AccountId: seed.account.AccountId, Amount: 500,
		ScheduledFrequencyType: models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY, ScheduledFrequency: "1", ScheduledTimezoneUtcOffset: 480, ScheduledAt: 960, TagIds: "100901"}
}
func createPeriodic(t *testing.T, seed *occurrenceSeed, actor, ledger int64) *models.TransactionTemplate {
	t.Helper()
	row := periodicTemplate(seed, actor, ledger)
	require.NoError(t, TransactionTemplates.CreateTemplateInLedger(nil, actor, row))
	return row
}
func TestPeriodicLedgerWebDailyFrequencyCreateModifyAndDispatch(t *testing.T) {
	seed := setupPeriodicScope(t)
	row := periodicTemplate(seed, 2, 11)
	row.ScheduledFrequency = "0"
	require.NoError(t, TransactionTemplates.CreateTemplateInLedger(nil, 2, row))
	stored, err := TransactionTemplates.GetTemplateInLedger(nil, 1, 11, row.TemplateId)
	require.NoError(t, err)
	require.Equal(t, "0", stored.ScheduledFrequency)
	stored.Amount = 321
	require.NoError(t, TransactionTemplates.ModifyTemplateInLedger(nil, 2, stored))
	const due = int64(1791043200)
	_, err = ScheduledOccurrences.Enqueue(nil, stored, due)
	require.NoError(t, err)
	require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
	postedID, err := ScheduledOccurrences.ConfirmInLedger(nil, 3, 11, stored.TemplateId, due)
	require.NoError(t, err)
	posted := transactionWriteReadRow(t, postedID)
	require.Equal(t, int64(11), posted.LedgerId)
	require.Equal(t, int64(321), posted.Amount)
	require.Equal(t, int64(3), posted.RecorderUid)
	require.Equal(t, int64(9679), occurrenceAccountBalance(t, 1, seed.account.AccountId))
}
func TestPeriodicLedgerTemplatesOwnerShardRolesAndIsolation(t *testing.T) {
	seed := setupPeriodicScope(t)
	row := createPeriodic(t, seed, 2, 11)
	require.Equal(t, int64(1), row.Uid)
	for _, actor := range []int64{1, 2, 3, 4} {
		rows, err := TransactionTemplates.GetAllTemplatesInLedger(nil, actor, 11, models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE)
		require.NoError(t, err)
		require.Len(t, rows, 1)
		require.Equal(t, row.TemplateId, rows[0].TemplateId)
		require.Equal(t, int64(11), rows[0].LedgerId)
		got, err := TransactionTemplates.GetTemplateInLedger(nil, actor, 11, row.TemplateId)
		require.NoError(t, err)
		require.Equal(t, row.TemplateId, got.TemplateId)
	}
	rows, err := TransactionTemplates.GetAllTemplatesByUid(nil, 1, models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE)
	require.NoError(t, err)
	require.Empty(t, rows)
	rows, err = TransactionTemplates.GetAllTemplatesInLedger(nil, 1, 12, models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE)
	require.NoError(t, err)
	require.Empty(t, rows)
	_, err = TransactionTemplates.GetTemplateInLedger(nil, 1, 12, row.TemplateId)
	require.ErrorIs(t, err, errs.ErrTransactionTemplateNotFound)
	_, err = TransactionTemplates.GetTemplateInLedger(nil, 9, 11, row.TemplateId)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	for _, actor := range []int64{3, 4} {
		candidate := periodicTemplate(seed, actor, 11)
		require.ErrorIs(t, TransactionTemplates.CreateTemplateInLedger(nil, actor, candidate), errs.ErrLedgerAccessDenied)
		edit := *row
		edit.Amount = 900
		require.ErrorIs(t, TransactionTemplates.ModifyTemplateInLedger(nil, actor, &edit), errs.ErrLedgerAccessDenied)
		require.ErrorIs(t, TransactionTemplates.HideTemplateInLedger(nil, actor, 11, []int64{row.TemplateId}, true), errs.ErrLedgerAccessDenied)
		require.ErrorIs(t, TransactionTemplates.ModifyTemplateDisplayOrdersInLedger(nil, actor, 11, []*models.TransactionTemplate{{TemplateId: row.TemplateId, DisplayOrder: 9}}), errs.ErrLedgerAccessDenied)
		require.ErrorIs(t, TransactionTemplates.DeleteTemplateInLedger(nil, actor, 11, row.TemplateId), errs.ErrLedgerAccessDenied)
	}
	edit := *row
	edit.Amount = 600
	require.NoError(t, TransactionTemplates.ModifyTemplateInLedger(nil, 2, &edit))
	require.Equal(t, int64(1), edit.Uid)
	require.NoError(t, TransactionTemplates.HideTemplateInLedger(nil, 2, 11, []int64{row.TemplateId}, true))
	require.NoError(t, TransactionTemplates.ModifyTemplateDisplayOrdersInLedger(nil, 2, 11, []*models.TransactionTemplate{{TemplateId: row.TemplateId, DisplayOrder: 9}}))
	require.NoError(t, TransactionTemplates.DeleteTemplateInLedger(nil, 2, 11, row.TemplateId))
}
func TestPeriodicLedgerRejectsForeignAndCrossLedgerResources(t *testing.T) {
	seed := setupPeriodicScope(t)
	foreign := insertOccurrenceSeed(t, 2, 100, 100)
	cases := map[string]func(*models.TransactionTemplate){
		"default account":      func(r *models.TransactionTemplate) { r.AccountId = 100003 },
		"other ledger account": func(r *models.TransactionTemplate) { r.AccountId = 100004 },
		"foreign account":      func(r *models.TransactionTemplate) { r.AccountId = foreign.account.AccountId },
		"foreign category": func(r *models.TransactionTemplate) {
			r.CategoryId = foreign.categories[models.CATEGORY_TYPE_EXPENSE].CategoryId
		},
		"foreign tag":  func(r *models.TransactionTemplate) { r.TagIds = "200901" },
		"hidden tag":   func(r *models.TransactionTemplate) { r.TagIds = "100902" },
		"bad tag":      func(r *models.TransactionTemplate) { r.TagIds = "0100901" },
		"repeated tag": func(r *models.TransactionTemplate) { r.TagIds = "100901,100901" },
		"other destination": func(r *models.TransactionTemplate) {
			r.Type = models.TRANSACTION_TYPE_TRANSFER
			r.CategoryId = seed.categories[models.CATEGORY_TYPE_TRANSFER].CategoryId
			r.RelatedAccountId = 100004
			r.RelatedAccountAmount = 500
		},
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			row := periodicTemplate(seed, 2, 11)
			change(row)
			require.Error(t, TransactionTemplates.CreateTemplateInLedger(nil, 2, row))
		})
	}
	count, err := occurrenceDB(t).NewSession(nil).Count(new(models.TransactionTemplate))
	require.NoError(t, err)
	require.Zero(t, count)
	require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
}
func TestPeriodicLedgerMemberConfirmationAttributionAndReplay(t *testing.T) {
	seed := setupPeriodicScope(t)
	row := createPeriodic(t, seed, 2, 11)
	const due = int64(1791043200)
	item, err := ScheduledOccurrences.Enqueue(nil, row, due)
	require.NoError(t, err)
	require.Equal(t, int64(11), item.LedgerId)
	require.Equal(t, int64(11), occurrenceSnapshot(t, item).LedgerId)
	require.Zero(t, occurrenceTransactionCount(t, 1))
	require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
	for _, actor := range []int64{1, 2, 3, 4} {
		rows, err := ScheduledOccurrences.ListInLedger(nil, actor, 11, models.ScheduledOccurrencePending, 0, 50)
		require.NoError(t, err)
		require.Len(t, rows, 1)
	}
	rows, err := ScheduledOccurrences.List(nil, 1, models.ScheduledOccurrencePending, 0, 50)
	require.NoError(t, err)
	require.Empty(t, rows)
	rows, err = ScheduledOccurrences.ListInLedger(nil, 1, 12, models.ScheduledOccurrencePending, 0, 50)
	require.NoError(t, err)
	require.Empty(t, rows)
	_, err = ScheduledOccurrences.ConfirmInLedger(nil, 4, 11, row.TemplateId, due)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	_, err = ScheduledOccurrences.ConfirmInLedger(nil, 1, 12, row.TemplateId, due)
	require.ErrorIs(t, err, errs.ErrTransactionTemplateNotFound)
	id, err := ScheduledOccurrences.ConfirmInLedger(nil, 3, 11, row.TemplateId, due)
	require.NoError(t, err)
	posted := transactionWriteReadRow(t, id)
	require.Equal(t, int64(1), posted.Uid)
	require.Equal(t, int64(11), posted.LedgerId)
	require.Equal(t, int64(3), posted.RecorderUid)
	require.Equal(t, int64(3), posted.PayerUid)
	require.Equal(t, int64(9500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
	for _, actor := range []int64{1, 2, 3} {
		replay, err := ScheduledOccurrences.ConfirmInLedger(nil, actor, 11, row.TemplateId, due)
		require.NoError(t, err)
		require.Equal(t, id, replay)
	}
	_, err = ScheduledOccurrences.ConfirmInLedger(nil, 4, 11, row.TemplateId, due)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	require.Equal(t, int64(1), occurrenceTransactionCount(t, 1))
}
func TestPeriodicLedgerConcurrentManagersAndMembersPostOnlyOnce(t *testing.T) {
	seed := setupPeriodicScope(t)
	row := createPeriodic(t, seed, 1, 11)
	const due = int64(1791043200)
	_, err := ScheduledOccurrences.Enqueue(nil, row, due)
	require.NoError(t, err)
	const n = 12
	ids := make([]int64, n)
	errsBySlot := make([]error, n)
	var ready, done sync.WaitGroup
	ready.Add(n)
	done.Add(n)
	for i := 0; i < n; i++ {
		go func(i int) {
			defer done.Done()
			ready.Done()
			ready.Wait()
			ids[i], errsBySlot[i] = ScheduledOccurrences.ConfirmInLedger(nil, int64(i%3+1), 11, row.TemplateId, due)
		}(i)
	}
	done.Wait()
	for i := range ids {
		require.NoError(t, errsBySlot[i])
		require.Equal(t, ids[0], ids[i])
	}
	require.Equal(t, int64(1), occurrenceTransactionCount(t, 1))
	require.Equal(t, int64(9500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
}

func TestPeriodicLedgerConcurrentConfirmWithMultipleSQLiteConnections(t *testing.T) {
	seed := setupPeriodicScopeWithConnections(t, 10)
	row := createPeriodic(t, seed, 1, 11)
	const firstDue = int64(1791043200)
	for round := 0; round < 12; round++ {
		due := firstDue + int64(round)*86400
		_, err := ScheduledOccurrences.Enqueue(nil, row, due)
		require.NoError(t, err)
		const requests = 12
		ids := make([]int64, requests)
		errorsBySlot := make([]error, requests)
		var ready, done sync.WaitGroup
		ready.Add(requests)
		done.Add(requests)
		for i := range requests {
			go func(i int) {
				defer done.Done()
				ready.Done()
				ready.Wait()
				ids[i], errorsBySlot[i] = ScheduledOccurrences.ConfirmInLedger(nil, int64(i%3+1), 11, row.TemplateId, due)
			}(i)
		}
		done.Wait()
		for i := range ids {
			require.NoError(t, errorsBySlot[i], "round %d request %d", round, i)
			require.Positive(t, ids[i])
			require.Equal(t, ids[0], ids[i])
		}
		require.Equal(t, int64(round+1), occurrenceTransactionCount(t, 1))
		require.Equal(t, int64(10000-(round+1)*500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
	}
}
func TestPeriodicLedgerRevokedPermissionsAndSnapshotRollBack(t *testing.T) {
	seed := setupPeriodicScope(t)
	row := createPeriodic(t, seed, 2, 11)
	const due = int64(1791043200)
	_, err := ScheduledOccurrences.Enqueue(nil, row, due)
	require.NoError(t, err)
	_, err = occurrenceDB(t).NewSession(nil).Where("ledger_id=? AND uid=?", 11, 3).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	_, err = ScheduledOccurrences.ConfirmInLedger(nil, 3, 11, row.TemplateId, due)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	require.ErrorIs(t, ScheduledOccurrences.SetDismissedInLedger(nil, 3, 11, row.TemplateId, due, true), errs.ErrLedgerAccessDenied)
	require.NoError(t, ScheduledOccurrences.SetDismissedInLedger(nil, 2, 11, row.TemplateId, due, true))
	require.NoError(t, ScheduledOccurrences.SetDismissedInLedger(nil, 2, 11, row.TemplateId, due, true))
	_, err = ScheduledOccurrences.ConfirmInLedger(nil, 2, 11, row.TemplateId, due)
	require.Error(t, err)
	require.NoError(t, ScheduledOccurrences.SetDismissedInLedger(nil, 2, 11, row.TemplateId, due, false))
	item := occurrenceRow(t, 1, row.TemplateId, due)
	snapshot := occurrenceSnapshot(t, item)
	for _, mutate := range []func(*models.ScheduledOccurrenceSnapshot){func(s *models.ScheduledOccurrenceSnapshot) { s.LedgerId = 12 }, func(s *models.ScheduledOccurrenceSnapshot) { s.SourceAccountId = 100004 }, func(s *models.ScheduledOccurrenceSnapshot) { s.TagIds = []string{"200901"} }, func(s *models.ScheduledOccurrenceSnapshot) { s.SourceAmount = 10000000000000 }} {
		invalid := snapshot
		mutate(&invalid)
		data, err := json.Marshal(invalid)
		require.NoError(t, err)
		_, err = occurrenceDB(t).NewSession(nil).Where("uid=? AND template_id=? AND scheduled_unix_time=?", 1, row.TemplateId, due).Cols("snapshot_j_s_o_n").Update(&models.ScheduledOccurrence{SnapshotJSON: string(data)})
		require.NoError(t, err)
		_, err = ScheduledOccurrences.ConfirmInLedger(nil, 2, 11, row.TemplateId, due)
		require.Error(t, err)
		require.Zero(t, occurrenceTransactionCount(t, 1))
		require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
		require.Equal(t, models.ScheduledOccurrencePending, occurrenceRow(t, 1, row.TemplateId, due).Status)
	}
}
func TestPeriodicLedgerHiddenIsNotPausedAndDeletedDispatchFails(t *testing.T) {
	seed := setupPeriodicScope(t)
	row := createPeriodic(t, seed, 1, 11)
	const due = int64(1791043200)
	require.NoError(t, TransactionTemplates.HideTemplateInLedger(nil, 1, 11, []int64{row.TemplateId}, true))
	_, err := ScheduledOccurrences.Enqueue(nil, row, due)
	require.NoError(t, err)
	paused := *row
	paused.ScheduledFrequencyType = models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DISABLED
	paused.ScheduledFrequency = ""
	require.NoError(t, TransactionTemplates.ModifyTemplateInLedger(nil, 1, &paused))
	_, err = ScheduledOccurrences.Enqueue(nil, row, due+1)
	require.Error(t, err)
	require.NoError(t, TransactionTemplates.DeleteTemplateInLedger(nil, 1, 11, row.TemplateId))
	_, err = ScheduledOccurrences.Enqueue(nil, row, due+2)
	require.Error(t, err)
	// Existing captures survive template deletion and can be confirmed unchanged.
	id, err := ScheduledOccurrences.ConfirmInLedger(nil, 3, 11, row.TemplateId, due)
	require.NoError(t, err)
	require.Positive(t, id)
}
func TestPeriodicLedgerTransferAndRefundKeepIntegerAmounts(t *testing.T) {
	for _, transfer := range []bool{false, true} {
		t.Run(map[bool]string{false: "refund", true: "dual currency transfer"}[transfer], func(t *testing.T) {
			seed := setupPeriodicScope(t)
			row := periodicTemplate(seed, 1, 11)
			if transfer {
				row.Type = models.TRANSACTION_TYPE_TRANSFER
				row.CategoryId = seed.categories[models.CATEGORY_TYPE_TRANSFER].CategoryId
				row.RelatedAccountId = seed.other.AccountId
				row.RelatedAccountAmount = 425
			} else {
				row.Amount = -500
			}
			require.NoError(t, TransactionTemplates.CreateTemplateInLedger(nil, 1, row))
			const due = int64(1791043200)
			_, err := ScheduledOccurrences.Enqueue(nil, row, due)
			require.NoError(t, err)
			id, err := ScheduledOccurrences.ConfirmInLedger(nil, 2, 11, row.TemplateId, due)
			require.NoError(t, err)
			posted := transactionWriteReadRow(t, id)
			require.Equal(t, int64(2), posted.RecorderUid)
			require.Equal(t, int64(2), posted.PayerUid)
			if transfer {
				require.Equal(t, int64(9500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
				require.Equal(t, int64(5425), occurrenceAccountBalance(t, 1, seed.other.AccountId))
				other := transactionWriteReadRow(t, posted.RelatedId)
				require.Equal(t, int64(11), other.LedgerId)
				require.Equal(t, int64(425), other.Amount)
				require.Equal(t, int64(2), other.RecorderUid)
				require.Equal(t, int64(2), other.PayerUid)
			} else {
				require.Equal(t, int64(10500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
				require.Equal(t, int64(-500), posted.Amount)
			}
		})
	}
}
func TestPeriodicLedgerTemplateAndDismissedOccurrenceDeleteReferences(t *testing.T) {
	seed := setupPeriodicScope(t)
	row := createPeriodic(t, seed, 1, 11)
	const due = int64(1791043200)
	_, err := ScheduledOccurrences.Enqueue(nil, row, due)
	require.NoError(t, err)
	require.NoError(t, ScheduledOccurrences.SetDismissedInLedger(nil, 1, 11, row.TemplateId, due, true))
	// Physically remove only this synthetic fixture's accounts to isolate these
	// two references from the existing account/transaction deletion blockers.
	_, err = occurrenceDB(t).NewSession(nil).Where("ledger_id=?", 11).Delete(new(models.Account))
	require.NoError(t, err)
	preview, err := Ledgers.GetDeletePreview(nil, 1, 11)
	require.NoError(t, err)
	require.Equal(t, int64(1), preview.TransactionTemplateCount)
	require.Equal(t, int64(1), preview.ScheduledOccurrenceCount)
	require.Contains(t, preview.BlockingReasons, "transactionTemplates")
	require.Contains(t, preview.BlockingReasons, "scheduledOccurrences")
	require.False(t, preview.CanDelete)
	require.ErrorIs(t, Ledgers.DeleteLedger(nil, 1, 11), errs.ErrLedgerNotEmpty)
	require.NoError(t, TransactionTemplates.DeleteTemplateInLedger(nil, 1, 11, row.TemplateId))
	preview, err = Ledgers.GetDeletePreview(nil, 1, 11)
	require.NoError(t, err)
	require.Zero(t, preview.TransactionTemplateCount)
	require.Equal(t, int64(1), preview.ScheduledOccurrenceCount)
	require.ErrorIs(t, Ledgers.DeleteLedger(nil, 1, 11), errs.ErrLedgerNotEmpty)
	_, err = occurrenceDB(t).NewSession(nil).Where("ledger_id=?", 11).Delete(new(models.ScheduledOccurrence))
	require.NoError(t, err)
	require.NoError(t, Ledgers.DeleteLedger(nil, 1, 11))
	_, err = ScheduledOccurrences.Enqueue(nil, row, due+1)
	require.Error(t, err)
}
func TestPeriodicLedgerTemplateBatchWrongScopeRollsBackAll(t *testing.T) {
	seed := setupPeriodicScope(t)
	first := createPeriodic(t, seed, 1, 11)
	second := periodicTemplate(seed, 1, 12)
	second.AccountId = 100004
	require.NoError(t, TransactionTemplates.CreateTemplateInLedger(nil, 1, second))
	require.ErrorIs(t, TransactionTemplates.HideTemplateInLedger(nil, 2, 11, []int64{first.TemplateId, second.TemplateId}, true), errs.ErrTransactionTemplateNotFound)
	require.ErrorIs(t, TransactionTemplates.ModifyTemplateDisplayOrdersInLedger(nil, 2, 11, []*models.TransactionTemplate{{TemplateId: first.TemplateId, DisplayOrder: 9}, {TemplateId: second.TemplateId, DisplayOrder: 8}}), errs.ErrTransactionTemplateNotFound)
	actual, err := TransactionTemplates.GetTemplateInLedger(nil, 1, 11, first.TemplateId)
	require.NoError(t, err)
	require.False(t, actual.Hidden)
	require.Zero(t, actual.DisplayOrder)
}
func TestPeriodicLedgerTransactionRecheckDeniesStaleManager(t *testing.T) {
	seed := setupPeriodicScope(t)
	_, ledger, err := resolveTemplateLedger(nil, 2, 11, models.FamilyMemberRole.CanManage)
	require.NoError(t, err)
	_, err = occurrenceDB(t).NewSession(nil).Where("ledger_id=? AND uid=?", 11, 2).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	err = occurrenceDB(t).DoTransaction(nil, func(sess *xorm.Session) error {
		return authorizeMonthlyBudgetLedger(sess, 2, ledger, models.FamilyMemberRole.CanManage, true)
	})
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	require.ErrorIs(t, TransactionTemplates.CreateTemplateInLedger(nil, 2, periodicTemplate(seed, 2, 11)), errs.ErrLedgerAccessDenied)
}
func TestPeriodicLedgerConfirmationAndDismissRaceLeavesOneState(t *testing.T) {
	seed := setupPeriodicScope(t)
	row := createPeriodic(t, seed, 1, 11)
	const due = int64(1791043200)
	_, err := ScheduledOccurrences.Enqueue(nil, row, due)
	require.NoError(t, err)
	var wg sync.WaitGroup
	wg.Add(2)
	var id int64
	var confirmErr, dismissErr error
	go func() {
		defer wg.Done()
		id, confirmErr = ScheduledOccurrences.ConfirmInLedger(nil, 3, 11, row.TemplateId, due)
	}()
	go func() {
		defer wg.Done()
		dismissErr = ScheduledOccurrences.SetDismissedInLedger(nil, 2, 11, row.TemplateId, due, true)
	}()
	wg.Wait()
	actual := occurrenceRow(t, 1, row.TemplateId, due)
	if actual.Status == models.ScheduledOccurrenceConfirmed {
		require.NoError(t, confirmErr)
		require.Positive(t, id)
		require.Error(t, dismissErr)
		require.Equal(t, int64(1), occurrenceTransactionCount(t, 1))
		require.Equal(t, int64(9500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
	} else {
		require.Equal(t, models.ScheduledOccurrenceDismissed, actual.Status)
		require.NoError(t, dismissErr)
		require.Error(t, confirmErr)
		require.Zero(t, occurrenceTransactionCount(t, 1))
		require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
	}
}
func TestPeriodicLedgerLegacySnapshotMissingLedgerRemainsDefault(t *testing.T) {
	setupOccurrenceLedger(t)
	seed := insertOccurrenceSeed(t, 1, 10000, 5000)
	template := occurrenceTemplate(1, 17001, seed, models.TRANSACTION_TYPE_EXPENSE, 100)
	const due = int64(1790985600)
	_, err := ScheduledOccurrences.Enqueue(nil, template, due)
	require.NoError(t, err)
	var body map[string]any
	require.NoError(t, json.Unmarshal([]byte(occurrenceRow(t, 1, template.TemplateId, due).SnapshotJSON), &body))
	delete(body, "ledgerId")
	data, err := json.Marshal(body)
	require.NoError(t, err)
	_, err = occurrenceDB(t).NewSession(nil).Where("uid=? AND template_id=? AND scheduled_unix_time=?", 1, template.TemplateId, due).Cols("snapshot_j_s_o_n").Update(&models.ScheduledOccurrence{SnapshotJSON: string(data)})
	require.NoError(t, err)
	id, err := ScheduledOccurrences.Confirm(nil, 1, template.TemplateId, due)
	require.NoError(t, err)
	row := transactionWriteReadRow(t, id)
	require.Zero(t, row.LedgerId)
	require.Equal(t, int64(1), row.RecorderUid)
}
func TestPeriodicLedgerDeletedLedgerRecheckBlocksConcurrentCreate(t *testing.T) {
	setupPeriodicScope(t)
	// Capture a resolved ledger, then perform the deletion on a truly empty B.
	_, ledger, err := resolveTemplateLedger(nil, 1, 12, models.FamilyMemberRole.CanManage)
	require.NoError(t, err)
	_, err = occurrenceDB(t).NewSession(nil).Where("ledger_id=?", 12).Delete(new(models.Account))
	require.NoError(t, err)
	require.NoError(t, Ledgers.DeleteLedger(nil, 1, 12))
	err = occurrenceDB(t).DoTransaction(nil, func(sess *xorm.Session) error {
		return authorizeMonthlyBudgetLedger(sess, 1, ledger, models.FamilyMemberRole.CanManage, true)
	})
	require.True(t, errors.Is(err, errs.ErrLedgerAccessDenied))
}
func TestPeriodicLedgerFrequencyAndTimezoneChangesRejectStaleDispatch(t *testing.T) {
	for _, change := range []string{"frequency", "timezone"} {
		t.Run(change, func(t *testing.T) {
			seed := setupPeriodicScope(t)
			row := createPeriodic(t, seed, 1, 11)
			const due = int64(1791043200)
			edit := *row
			if change == "frequency" {
				edit.ScheduledFrequencyType = models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY
				edit.ScheduledFrequency = "101"
			} else {
				edit.ScheduledTimezoneUtcOffset = 0
				edit.ScheduledAt = 0
			}
			require.NoError(t, TransactionTemplates.ModifyTemplateInLedger(nil, 1, &edit))
			_, err := ScheduledOccurrences.Enqueue(nil, row, due)
			require.Error(t, err)
			pending, err := ScheduledOccurrences.ListInLedger(nil, 1, 11, models.ScheduledOccurrencePending, 0, 50)
			require.NoError(t, err)
			require.Empty(t, pending)
			require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
		})
	}
}
func TestPeriodicLedgerPendingAndDismissedProtectCategoryAndTagAfterTemplateDeletion(t *testing.T) {
	for _, dismissed := range []bool{false, true} {
		t.Run(map[bool]string{false: "pending", true: "dismissed"}[dismissed], func(t *testing.T) {
			seed := setupPeriodicScope(t)
			row := createPeriodic(t, seed, 1, 11)
			const due = int64(1791043200)
			_, err := ScheduledOccurrences.Enqueue(nil, row, due)
			require.NoError(t, err)
			if dismissed {
				require.NoError(t, ScheduledOccurrences.SetDismissedInLedger(nil, 2, 11, row.TemplateId, due, true))
			}
			require.NoError(t, TransactionTemplates.DeleteTemplateInLedger(nil, 2, 11, row.TemplateId))
			category := seed.categories[models.CATEGORY_TYPE_EXPENSE]
			require.ErrorIs(t, TransactionCategories.DeleteCategory(nil, 1, category.CategoryId), errs.ErrTransactionCategoryInUseCannotBeDeleted)
			require.ErrorIs(t, TransactionCategories.DeleteCategory(nil, 1, category.ParentCategoryId), errs.ErrTransactionCategoryInUseCannotBeDeleted)
			require.ErrorIs(t, TransactionCategories.DeleteAllCategories(nil, 1), errs.ErrTransactionCategoryInUseCannotBeDeleted)
			require.ErrorIs(t, TransactionTags.DeleteTag(nil, 1, 100901), errs.ErrTransactionTagInUseCannotBeDeleted)
			require.ErrorIs(t, TransactionTags.DeleteAllTags(nil, 1), errs.ErrTransactionTagInUseCannotBeDeleted)
			// An unrelated owner's matching name or private ID is not a reference.
			require.NoError(t, TransactionTags.DeleteTag(nil, 2, 200901))
			if dismissed {
				require.NoError(t, ScheduledOccurrences.SetDismissedInLedger(nil, 2, 11, row.TemplateId, due, false))
			}
			_, err = ScheduledOccurrences.ConfirmInLedger(nil, 3, 11, row.TemplateId, due)
			require.NoError(t, err)
			// Once confirmed, ordinary posted transaction/index references take over.
			require.ErrorIs(t, TransactionCategories.DeleteCategory(nil, 1, category.CategoryId), errs.ErrTransactionCategoryInUseCannotBeDeleted)
			require.ErrorIs(t, TransactionTags.DeleteTag(nil, 1, 100901), errs.ErrTransactionTagInUseCannotBeDeleted)
		})
	}
}
func TestPeriodicLedgerCorruptPendingReferenceFailsClosed(t *testing.T) {
	seed := setupPeriodicScope(t)
	row := createPeriodic(t, seed, 1, 11)
	const due = int64(1791043200)
	_, err := ScheduledOccurrences.Enqueue(nil, row, due)
	require.NoError(t, err)
	require.NoError(t, TransactionTemplates.DeleteTemplateInLedger(nil, 1, 11, row.TemplateId))
	_, err = occurrenceDB(t).NewSession(nil).Where("uid=? AND template_id=?", 1, row.TemplateId).Cols("snapshot_j_s_o_n").Update(&models.ScheduledOccurrence{SnapshotJSON: "{}"})
	require.NoError(t, err)
	require.ErrorIs(t, TransactionCategories.DeleteCategory(nil, 1, seed.categories[models.CATEGORY_TYPE_EXPENSE].CategoryId), errs.ErrOperationFailed)
	require.ErrorIs(t, TransactionTags.DeleteTag(nil, 1, 100901), errs.ErrOperationFailed)
}
func TestPeriodicLedgerSchemaAddsDefaultZeroWithoutMovingHistoricalRows(t *testing.T) {
	setupOccurrenceLedger(t)
	seed := insertOccurrenceSeed(t, 1, 10000, 5000)
	row := occurrenceTemplate(1, 18001, seed, models.TRANSACTION_TYPE_EXPENSE, 100)
	const due = int64(1790985600)
	_, err := ScheduledOccurrences.Enqueue(nil, row, due)
	require.NoError(t, err)
	sess := occurrenceDB(t).NewSession(nil)
	_, err = sess.Exec("DROP INDEX IDX_transaction_template_ledger_id")
	require.NoError(t, err)
	_, err = sess.Exec("ALTER TABLE transaction_template DROP COLUMN ledger_id")
	require.NoError(t, err)
	_, err = sess.Exec("DROP INDEX IDX_scheduled_occurrence_ledger_id")
	require.NoError(t, err)
	_, err = sess.Exec("ALTER TABLE scheduled_occurrence DROP COLUMN ledger_id")
	require.NoError(t, err)
	require.NoError(t, sess.Close())
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(models.TransactionTemplate), new(models.ScheduledOccurrence)))
	got, err := TransactionTemplates.GetTemplateByTemplateId(nil, 1, row.TemplateId)
	require.NoError(t, err)
	require.Zero(t, got.LedgerId)
	occurrence := occurrenceRow(t, 1, row.TemplateId, due)
	require.Zero(t, occurrence.LedgerId)
	require.Equal(t, int64(100), occurrenceSnapshot(t, occurrence).SourceAmount)
	require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
}
func TestPeriodicLedgerPendingAndDismissedProtectAccountsAfterTemplateDeletion(t *testing.T) {
	for _, dismissed := range []bool{false, true} {
		t.Run(map[bool]string{false: "pending", true: "dismissed"}[dismissed], func(t *testing.T) {
			seed := setupPeriodicScope(t)
			row := createPeriodic(t, seed, 1, 11)
			const due = int64(1791043200)
			_, err := ScheduledOccurrences.Enqueue(nil, row, due)
			require.NoError(t, err)
			if dismissed {
				require.NoError(t, ScheduledOccurrences.SetDismissedInLedger(nil, 1, 11, row.TemplateId, due, true))
			}
			require.NoError(t, TransactionTemplates.DeleteTemplateInLedger(nil, 1, 11, row.TemplateId))
			require.ErrorIs(t, Accounts.DeleteAccount(nil, 2, seed.account.AccountId), errs.ErrAccountInUseCannotBeDeleted)
			_, err = Accounts.MoveAccountToLedger(nil, 1, seed.account.AccountId, 12)
			require.ErrorIs(t, err, errs.ErrAccountLedgerMigrationBlocked)
			require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
			if dismissed {
				require.NoError(t, ScheduledOccurrences.SetDismissedInLedger(nil, 1, 11, row.TemplateId, due, false))
			}
			_, err = ScheduledOccurrences.ConfirmInLedger(nil, 3, 11, row.TemplateId, due)
			require.NoError(t, err)
		})
	}
}
