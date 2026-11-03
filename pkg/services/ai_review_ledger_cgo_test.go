//go:build cgo

package services

import (
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
)

func setupAIReview(t *testing.T) *occurrenceSeed {
	return setupAIReviewWithConnections(t, 8)
}
func setupAIReviewWithConnections(t *testing.T, connections int) *occurrenceSeed {
	t.Helper()
	seed := setupPeriodicScopeWithConnections(t, connections)
	require.NoError(t, datastore.Container.UserStore.SyncStructs(new(models.User)))
	for uid := int64(1); uid <= 4; uid++ {
		sess := datastore.Container.UserStore.Query(nil, uid)
		_, err := sess.Insert(&models.User{Uid: uid, Username: fmt.Sprintf("synthetic_ai_%d", uid), Email: fmt.Sprintf("synthetic_ai_%d@example.invalid", uid), TransactionEditScope: models.TRANSACTION_EDIT_SCOPE_ALL})
		sess.Close()
		require.NoError(t, err)
	}
	return seed
}

func TestAIReviewConfirmationSingleConnection(t *testing.T) {
	seed := setupAIReviewWithConnections(t, 1)
	item := aiReviewCreate(t, 3, 11, "single connection")
	id, err := AIReviewItems.ConfirmInLedger(nil, 3, aiReviewExpense(seed, item), "", time.UTC)
	require.NoError(t, err)
	require.Positive(t, id)
	require.Equal(t, int64(9500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
	require.Equal(t, int64(1), occurrenceTransactionCount(t, 1))
}
func aiReviewCreate(t *testing.T, actor, ledger int64, key string) *models.AIReviewItem {
	t.Helper()
	row, err := AIReviewItems.CreateInLedger(nil, actor, models.AIReviewItemCreateRequest{LedgerId: ledger, ClientSessionId: key, SourceType: models.AI_REVIEW_SOURCE_TEXT, SourceText: "Synthetic receipt requiring explicit review"})
	require.NoError(t, err)
	return row
}
func aiReviewExpense(seed *occurrenceSeed, item *models.AIReviewItem) models.AIReviewItemConfirmRequest {
	return models.AIReviewItemConfirmRequest{Id: item.AIReviewItemId, LedgerId: item.LedgerId, Transaction: models.TransactionCreateRequest{LedgerId: item.LedgerId, Type: models.TRANSACTION_TYPE_EXPENSE, Time: 1791043200, UtcOffset: 480, CategoryId: seed.categories[models.CATEGORY_TYPE_EXPENSE].CategoryId, SourceAccountId: seed.account.AccountId, SourceAmount: 500, Comment: "Synthetic confirmed expense", ClientSessionId: fmt.Sprintf("confirm-%d", item.AIReviewItemId)}}
}
func aiReviewAssertPending(t *testing.T, item *models.AIReviewItem) {
	t.Helper()
	stored, err := AIReviewItems.GetInLedger(nil, item.Uid, item.LedgerId, item.AIReviewItemId)
	require.NoError(t, err)
	require.Equal(t, models.AI_REVIEW_STATUS_PENDING, stored.Status)
	require.Zero(t, stored.TransactionId)
	require.Empty(t, stored.ConfirmationHash)
	require.Zero(t, stored.ConfirmedUid)
}

func TestAIReviewLedgerRolesIsolationAndState(t *testing.T) {
	setupAIReview(t)
	owner := aiReviewCreate(t, 1, 11, "owner")
	member := aiReviewCreate(t, 3, 11, "member")
	require.Equal(t, int64(1), member.Uid)
	require.Equal(t, int64(3), member.CreatorUid)
	for _, actor := range []int64{1, 2, 3, 4} {
		rows, err := AIReviewItems.ListInLedger(nil, actor, 11, models.AI_REVIEW_STATUS_PENDING, 0, 100)
		require.NoError(t, err)
		require.Len(t, rows, 2)
		for _, row := range rows {
			require.Equal(t, actor <= 2 || (actor == 3 && row.CreatorUid == 3), row.Editable)
		}
	}
	for _, ledger := range []int64{0, 12} {
		rows, err := AIReviewItems.ListInLedger(nil, 1, ledger, models.AI_REVIEW_STATUS_PENDING, 0, 100)
		require.NoError(t, err)
		require.Empty(t, rows)
		_, err = AIReviewItems.GetInLedger(nil, 1, ledger, member.AIReviewItemId)
		require.ErrorIs(t, err, errs.ErrAIReviewItemNotFound)
	}
	for _, actor := range []int64{4, 9} {
		_, err := AIReviewItems.CreateInLedger(nil, actor, models.AIReviewItemCreateRequest{LedgerId: 11, ClientSessionId: "denied", SourceType: 1, SourceText: "Synthetic"})
		require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
		require.ErrorIs(t, AIReviewItems.DeleteInLedger(nil, actor, 11, member.AIReviewItemId), errs.ErrLedgerAccessDenied)
	}
	_, err := AIReviewItems.ListInLedger(nil, 9, 11, 1, 0, 100)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	require.ErrorIs(t, AIReviewItems.UpdateStatusInLedger(nil, 3, 11, owner.AIReviewItemId, 3), errs.ErrLedgerAccessDenied)
	require.ErrorIs(t, AIReviewItems.UpdateStatusInLedger(nil, 3, 11, member.AIReviewItemId, 2), errs.ErrAIReviewConfirmationRequired)
	require.NoError(t, AIReviewItems.UpdateStatusInLedger(nil, 3, 11, member.AIReviewItemId, 3))
	require.NoError(t, AIReviewItems.UpdateStatusInLedger(nil, 3, 11, member.AIReviewItemId, 3))
	require.NoError(t, AIReviewItems.UpdateStatusInLedger(nil, 2, 11, member.AIReviewItemId, 1))
	rows, err := AIReviewItems.ListInLedger(nil, 1, 11, 1, 0, 1)
	require.NoError(t, err)
	require.Len(t, rows, 1)
	previous, err := AIReviewItems.ListInLedger(nil, 1, 11, 1, rows[0].AIReviewItemId, 1)
	require.NoError(t, err)
	require.Len(t, previous, 1)
	require.Less(t, previous[0].AIReviewItemId, rows[0].AIReviewItemId)
	require.NoError(t, AIReviewItems.DeleteInLedger(nil, 3, 11, member.AIReviewItemId))
	require.NoError(t, AIReviewItems.DeleteInLedger(nil, 3, 11, member.AIReviewItemId))
	stored, err := AIReviewItems.GetInLedger(nil, 4, 11, member.AIReviewItemId)
	require.NoError(t, err)
	require.True(t, stored.Deleted)
	require.False(t, stored.Editable)
	require.ErrorIs(t, AIReviewItems.UpdateStatusInLedger(nil, 3, 11, member.AIReviewItemId, 1), errs.ErrAIReviewItemNotFound)
	_, err = occurrenceDB(t).NewSession(nil).Where("ledger_id=? AND uid=?", 11, 3).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	require.ErrorIs(t, AIReviewItems.DeleteInLedger(nil, 3, 11, member.AIReviewItemId), errs.ErrLedgerAccessDenied)
}

func TestAIReviewCreationReceiptsLegacyAndConcurrency(t *testing.T) {
	setupAIReview(t)
	request := models.AIReviewItemCreateRequest{LedgerId: 11, ClientSessionId: "same-source", SourceType: 1, SourceText: "Synthetic unchanged input"}
	const n = 12
	var wg sync.WaitGroup
	ids, failures := make([]int64, n), make([]error, n)
	for i := range ids {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			row, err := AIReviewItems.CreateInLedger(nil, 3, request)
			failures[i] = err
			if row != nil {
				ids[i] = row.AIReviewItemId
			}
		}(i)
	}
	wg.Wait()
	for i := range ids {
		require.NoError(t, failures[i])
		require.Equal(t, ids[0], ids[i])
	}
	request.SourceText = "Synthetic changed input"
	_, err := AIReviewItems.CreateInLedger(nil, 3, request)
	require.ErrorIs(t, err, errs.ErrAIReviewItemConflict)
	request.SourceText = "Synthetic unchanged input"
	require.NoError(t, AIReviewItems.DeleteInLedger(nil, 3, 11, ids[0]))
	replayed, err := AIReviewItems.CreateInLedger(nil, 3, request)
	require.NoError(t, err)
	require.True(t, replayed.Deleted)
	require.Equal(t, ids[0], replayed.AIReviewItemId)
	other, err := AIReviewItems.CreateInLedger(nil, 2, request)
	require.NoError(t, err)
	require.NotEqual(t, ids[0], other.AIReviewItemId)
	request.ClientSessionId = ""
	_, err = AIReviewItems.CreateInLedger(nil, 3, request)
	require.ErrorIs(t, err, errs.ErrParameterInvalid)
	legacy1 := aiReviewCreate(t, 1, 0, "")
	legacy2 := aiReviewCreate(t, 1, 0, "")
	require.NotEqual(t, legacy1.AIReviewItemId, legacy2.AIReviewItemId)
	require.NoError(t, AIReviewItems.UpdateStatusInLedger(nil, 1, 0, legacy1.AIReviewItemId, models.AI_REVIEW_STATUS_RESOLVED))
	require.ErrorIs(t, AIReviewItems.UpdateStatusInLedger(nil, 1, 0, legacy1.AIReviewItemId, 1), errs.ErrAIReviewItemConflict)
	durable := aiReviewCreate(t, 1, 0, "durable-default")
	require.ErrorIs(t, AIReviewItems.UpdateStatusInLedger(nil, 1, 0, durable.AIReviewItemId, 2), errs.ErrAIReviewConfirmationRequired)
}

func TestAIReviewAtomicConfirmationReplayAndDeletion(t *testing.T) {
	seed := setupAIReview(t)
	item := aiReviewCreate(t, 3, 11, "atomic")
	request := aiReviewExpense(seed, item)
	const n = 12
	ids, failures := make([]int64, n), make([]error, n)
	var wg sync.WaitGroup
	for i := range ids {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			ids[i], failures[i] = AIReviewItems.ConfirmInLedger(nil, 3, request, "", time.UTC)
		}(i)
	}
	wg.Wait()
	for i := range ids {
		require.NoError(t, failures[i])
		require.Equal(t, ids[0], ids[i])
		require.Positive(t, ids[i])
	}
	require.Equal(t, int64(9500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
	require.Equal(t, int64(1), occurrenceTransactionCount(t, 1))
	posted := transactionWriteReadRow(t, ids[0])
	require.Equal(t, int64(11), posted.LedgerId)
	require.Equal(t, int64(1), posted.Uid)
	require.Equal(t, int64(3), posted.RecorderUid)
	require.Equal(t, int64(3), posted.PayerUid)
	stored, err := AIReviewItems.GetInLedger(nil, 1, 11, item.AIReviewItemId)
	require.NoError(t, err)
	require.Equal(t, ids[0], stored.TransactionId)
	require.Equal(t, int64(3), stored.ConfirmedUid)
	changed := request
	changed.Transaction.SourceAmount++
	_, err = AIReviewItems.ConfirmInLedger(nil, 3, changed, "", time.UTC)
	require.ErrorIs(t, err, errs.ErrAIReviewItemConflict)
	changed = request
	changed.Transaction.ClientSessionId += "changed"
	_, err = AIReviewItems.ConfirmInLedger(nil, 3, changed, "", time.UTC)
	require.ErrorIs(t, err, errs.ErrAIReviewItemConflict)
	require.NoError(t, AIReviewItems.DeleteInLedger(nil, 1, 11, item.AIReviewItemId))
	_, err = occurrenceDB(t).NewSession(nil).ID(ids[0]).Cols("deleted").Update(&models.Transaction{Deleted: true})
	require.NoError(t, err)
	id, err := AIReviewItems.ConfirmInLedger(nil, 2, request, "", time.UTC)
	require.NoError(t, err)
	require.Equal(t, ids[0], id)
	require.Equal(t, int64(1), occurrenceTransactionCount(t, 1))
	_, err = occurrenceDB(t).NewSession(nil).Where("ledger_id=? AND uid=?", 11, 3).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	_, err = AIReviewItems.ConfirmInLedger(nil, 3, request, "", time.UTC)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
}

func TestAIReviewConfirmationMoneyContracts(t *testing.T) {
	cases := []struct {
		name                         string
		kind                         models.TransactionType
		amount, destAmount           int64
		externalIn, externalOut      bool
		balance, otherBalance, count int64
	}{
		{"expense", models.TRANSACTION_TYPE_EXPENSE, 500, 0, false, false, 9500, 5000, 1},
		{"refund", models.TRANSACTION_TYPE_EXPENSE, -500, 0, false, false, 10500, 5000, 1},
		{"income", models.TRANSACTION_TYPE_INCOME, 500, 0, false, false, 10500, 5000, 1},
		{"cross currency transfer", models.TRANSACTION_TYPE_TRANSFER, 500, 450, false, false, 9500, 5450, 2},
		{"external out", models.TRANSACTION_TYPE_TRANSFER, 500, 0, false, true, 9500, 5000, 1},
		{"external in", models.TRANSACTION_TYPE_TRANSFER, 0, 450, true, false, 10000, 5450, 1},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			seed := setupAIReview(t)
			item := aiReviewCreate(t, 3, 11, tc.name)
			request := aiReviewExpense(seed, item)
			request.Transaction.Type = tc.kind
			request.Transaction.CategoryId = seed.categories[categoryFor(tc.kind)].CategoryId
			request.Transaction.SourceAmount, request.Transaction.DestinationAmount = tc.amount, tc.destAmount
			if tc.kind == models.TRANSACTION_TYPE_TRANSFER && !tc.externalOut {
				request.Transaction.DestinationAccountId = seed.other.AccountId
			}
			if tc.externalIn {
				request.Transaction.SourceAccountId = 0
			}
			id, err := AIReviewItems.ConfirmInLedger(nil, 2, request, "", time.UTC)
			require.NoError(t, err)
			require.Positive(t, id)
			require.Equal(t, tc.balance, occurrenceAccountBalance(t, 1, seed.account.AccountId))
			require.Equal(t, tc.otherBalance, occurrenceAccountBalance(t, 1, seed.other.AccountId))
			require.Equal(t, tc.count, occurrenceTransactionCount(t, 1))
		})
	}
}

func TestAIReviewConfirmationInvalidInputRollsBack(t *testing.T) {
	cases := map[string]func(*models.AIReviewItemConfirmRequest){
		"other ledger":                func(r *models.AIReviewItemConfirmRequest) { r.Transaction.LedgerId = 12 },
		"other ledger account":        func(r *models.AIReviewItemConfirmRequest) { r.Transaction.SourceAccountId = 100004 },
		"default account":             func(r *models.AIReviewItemConfirmRequest) { r.Transaction.SourceAccountId = 100003 },
		"foreign account":             func(r *models.AIReviewItemConfirmRequest) { r.Transaction.SourceAccountId = 200001 },
		"wrong category type":         func(r *models.AIReviewItemConfirmRequest) { r.Transaction.CategoryId = 100012 },
		"root category":               func(r *models.AIReviewItemConfirmRequest) { r.Transaction.CategoryId = 100090 },
		"missing category":            func(r *models.AIReviewItemConfirmRequest) { r.Transaction.CategoryId = 999999 },
		"foreign tag":                 func(r *models.AIReviewItemConfirmRequest) { r.Transaction.TagIds = []string{"200901"} },
		"hidden tag":                  func(r *models.AIReviewItemConfirmRequest) { r.Transaction.TagIds = []string{"100902"} },
		"missing picture after claim": func(r *models.AIReviewItemConfirmRequest) { r.Transaction.PictureIds = []string{"999999"} },
		"invalid key":                 func(r *models.AIReviewItemConfirmRequest) { r.Transaction.ClientSessionId = " " },
		"overflow time":               func(r *models.AIReviewItemConfirmRequest) { r.Transaction.Time = 9223372036854775807 },
		"zero account":                func(r *models.AIReviewItemConfirmRequest) { r.Transaction.SourceAccountId = 0 },
		"adjustment": func(r *models.AIReviewItemConfirmRequest) {
			r.Transaction.Type = models.TRANSACTION_TYPE_MODIFY_BALANCE
		},
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			seed := setupAIReview(t)
			item := aiReviewCreate(t, 1, 11, name)
			request := aiReviewExpense(seed, item)
			change(&request)
			_, err := AIReviewItems.ConfirmInLedger(nil, 1, request, "", time.UTC)
			require.Error(t, err)
			aiReviewAssertPending(t, item)
			require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
			require.Zero(t, occurrenceTransactionCount(t, 1))
		})
	}
}

func TestAIReviewConfirmationLiveResourcesAndReviewPermissions(t *testing.T) {
	seed := setupAIReview(t)
	item := aiReviewCreate(t, 1, 11, "permissions")
	request := aiReviewExpense(seed, item)
	for _, actor := range []int64{3, 4, 9} {
		_, err := AIReviewItems.ConfirmInLedger(nil, actor, request, "", time.UTC)
		require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
		aiReviewAssertPending(t, item)
	}
	request.Transaction.TagIds = []string{"100901"}
	_, err := AIReviewItems.ConfirmInLedger(nil, 2, request, "", time.UTC)
	require.ErrorIs(t, err, errs.ErrTransactionTagNotFound)
	aiReviewAssertPending(t, item)
	request.Transaction.TagIds = nil
	request.Transaction.PictureIds = []string{"999999"}
	_, err = AIReviewItems.ConfirmInLedger(nil, 2, request, "", time.UTC)
	require.ErrorIs(t, err, errs.ErrTransactionPictureNotFound)
	aiReviewAssertPending(t, item)
	request.Transaction.PictureIds = nil
	_, err = occurrenceDB(t).NewSession(nil).ID(seed.categories[models.CATEGORY_TYPE_EXPENSE].ParentCategoryId).Cols("hidden").Update(&models.TransactionCategory{Hidden: true})
	require.NoError(t, err)
	_, err = AIReviewItems.ConfirmInLedger(nil, 1, request, "", time.UTC)
	require.ErrorIs(t, err, errs.ErrCannotUseHiddenTransactionCategory)
	aiReviewAssertPending(t, item)
}

func TestAIReviewConfirmationCompetingPayloadsAndDismissal(t *testing.T) {
	for _, dismiss := range []bool{false, true} {
		t.Run(fmt.Sprint(dismiss), func(t *testing.T) {
			seed := setupAIReview(t)
			item := aiReviewCreate(t, 3, 11, "race")
			a := aiReviewExpense(seed, item)
			b := a
			b.Transaction.SourceAmount = 700
			var wg sync.WaitGroup
			var ida, idb int64
			var ea, eb error
			wg.Add(2)
			go func() { defer wg.Done(); ida, ea = AIReviewItems.ConfirmInLedger(nil, 3, a, "", time.UTC) }()
			go func() {
				defer wg.Done()
				if dismiss {
					eb = AIReviewItems.UpdateStatusInLedger(nil, 3, 11, item.AIReviewItemId, 3)
				} else {
					idb, eb = AIReviewItems.ConfirmInLedger(nil, 2, b, "", time.UTC)
				}
			}()
			wg.Wait()
			if ea == nil {
				require.ErrorIs(t, eb, errs.ErrAIReviewItemConflict)
				require.Positive(t, ida)
				require.Equal(t, int64(9500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
			} else {
				require.ErrorIs(t, ea, errs.ErrAIReviewItemConflict)
				require.NoError(t, eb)
				if dismiss {
					require.Zero(t, idb)
					require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
				} else {
					require.Positive(t, idb)
					require.Equal(t, int64(9300), occurrenceAccountBalance(t, 1, seed.account.AccountId))
				}
			}
			count := int64(1)
			if dismiss && eb == nil {
				count = 0
			}
			require.Equal(t, count, occurrenceTransactionCount(t, 1))
		})
	}
}

func TestAIReviewLegacyMigrationPreservesDataAndNullableKeys(t *testing.T) {
	seed := setupAIReview(t)
	db := occurrenceDB(t)
	sess := db.NewSession(nil)
	defer sess.Close()
	_, err := sess.Exec("DROP TABLE a_i_review_item")
	require.NoError(t, err)
	_, err = sess.Exec(`CREATE TABLE a_i_review_item (a_i_review_item_id INTEGER PRIMARY KEY, uid INTEGER NOT NULL, deleted INTEGER NOT NULL, source_type INTEGER, status INTEGER NOT NULL, source_text TEXT NOT NULL, recognized_data TEXT, failure_reason VARCHAR(500), created_unix_time INTEGER, updated_unix_time INTEGER, deleted_unix_time INTEGER)`)
	require.NoError(t, err)
	_, err = sess.Exec(`INSERT INTO a_i_review_item VALUES (101,1,0,1,1,'Synthetic old source','{"sourceAmount":500}','Synthetic reason',1,2,0),(102,1,0,2,2,'Synthetic old resolved','', '',1,2,0),(103,1,1,3,3,'Synthetic old deleted','', '',1,2,3)`)
	require.NoError(t, err)
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(models.AIReviewItem)))
	for i := 0; i < 2; i++ {
		require.NoError(t, AIReviewItems.MigrateLegacyReviewItems(nil))
		require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(models.AIReviewItem)))
	}
	var rows []*models.AIReviewItem
	require.NoError(t, sess.Asc("a_i_review_item_id").Find(&rows))
	require.Len(t, rows, 3)
	for _, row := range rows {
		require.Equal(t, int64(1), row.CreatorUid)
		require.Zero(t, row.LedgerId)
		require.Nil(t, row.ClientSessionId)
		require.Zero(t, row.TransactionId)
		require.Empty(t, row.ConfirmationHash)
	}
	require.Equal(t, "Synthetic old source", rows[0].SourceText)
	require.Equal(t, `{"sourceAmount":500}`, rows[0].RecognizedData)
	require.Equal(t, models.AI_REVIEW_STATUS_RESOLVED, rows[1].Status)
	require.True(t, rows[2].Deleted)
	request := aiReviewExpense(seed, rows[1])
	request.Transaction.SourceAccountId = 100003
	_, err = AIReviewItems.ConfirmInLedger(nil, 1, request, "", time.UTC)
	require.ErrorIs(t, err, errs.ErrAIReviewConfirmationRequired)
	aiReviewCreate(t, 1, 0, "")
	aiReviewCreate(t, 1, 0, "")
	require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
	require.Zero(t, occurrenceTransactionCount(t, 1))
}

func TestAIReviewQueuePreventsLedgerDeletion(t *testing.T) {
	setupAIReview(t)
	db := occurrenceDB(t)
	_, err := db.NewSession(nil).Insert(&models.Ledger{LedgerId: 13, OwnerUid: 1, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic queue only ledger"}, &models.LedgerMember{LedgerMemberId: 130, LedgerId: 13, Uid: 1, Role: models.FAMILY_MEMBER_ROLE_OWNER, Status: models.FAMILY_MEMBER_STATUS_ACTIVE})
	require.NoError(t, err)
	item := aiReviewCreate(t, 1, 13, "only-reference")
	preview, err := Ledgers.GetDeletePreview(nil, 1, 13)
	require.NoError(t, err)
	require.False(t, preview.CanDelete)
	require.Equal(t, int64(1), preview.AIReviewItemCount)
	require.Contains(t, preview.BlockingReasons, "aiReviewItems")
	require.ErrorIs(t, Ledgers.DeleteLedger(nil, 1, 13), errs.ErrLedgerNotEmpty)
	require.NoError(t, AIReviewItems.DeleteInLedger(nil, 1, 13, item.AIReviewItemId))
	preview, err = Ledgers.GetDeletePreview(nil, 1, 13)
	require.NoError(t, err)
	require.True(t, preview.CanDelete)
	require.NoError(t, Ledgers.DeleteLedger(nil, 1, 13))
}

func TestAIReviewConfirmationAccountParentsSameCurrencyAndAttachments(t *testing.T) {
	for _, name := range []string{"hidden account", "hidden parent", "foreign parent ledger", "same currency mismatch", "same currency success", "attachment claims"} {
		t.Run(name, func(t *testing.T) {
			seed := setupAIReview(t)
			item := aiReviewCreate(t, 1, 11, name)
			request := aiReviewExpense(seed, item)
			db := occurrenceDB(t)
			switch name {
			case "hidden account":
				_, err := db.NewSession(nil).ID(seed.account.AccountId).Cols("hidden").Update(&models.Account{Hidden: true})
				require.NoError(t, err)
			case "hidden parent", "foreign parent ledger":
				parent := &models.Account{AccountId: 100010, Uid: 1, LedgerId: 11, Type: models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS, Hidden: name == "hidden parent"}
				if name == "foreign parent ledger" {
					parent.LedgerId = 12
				}
				_, err := db.NewSession(nil).Insert(parent)
				require.NoError(t, err)
				_, err = db.NewSession(nil).ID(seed.account.AccountId).Cols("parent_account_id").Update(&models.Account{ParentAccountId: parent.AccountId})
				require.NoError(t, err)
			case "same currency mismatch", "same currency success":
				_, err := db.NewSession(nil).ID(seed.other.AccountId).Cols("currency").Update(&models.Account{Currency: "USD"})
				require.NoError(t, err)
				request.Transaction.Type, request.Transaction.CategoryId = models.TRANSACTION_TYPE_TRANSFER, seed.categories[models.CATEGORY_TYPE_TRANSFER].CategoryId
				request.Transaction.DestinationAccountId, request.Transaction.DestinationAmount = seed.other.AccountId, 450
				if name == "same currency success" {
					request.Transaction.DestinationAmount = request.Transaction.SourceAmount
				}
			case "attachment claims":
				_, err := db.NewSession(nil).Insert(&models.TransactionPictureInfo{Uid: 1, PictureId: 900001})
				require.NoError(t, err)
				request.Transaction.PictureIds, request.Transaction.TagIds = []string{"900001"}, []string{"100901"}
			}
			id, err := AIReviewItems.ConfirmInLedger(nil, 1, request, "", time.UTC)
			if name == "same currency success" || name == "attachment claims" {
				require.NoError(t, err)
				require.Positive(t, id)
				require.Equal(t, int64(9500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
				if name == "same currency success" {
					require.Equal(t, int64(5500), occurrenceAccountBalance(t, 1, seed.other.AccountId))
				}
				if name == "attachment claims" {
					picture := &models.TransactionPictureInfo{}
					found, err := db.NewSession(nil).ID(900001).Get(picture)
					require.NoError(t, err)
					require.True(t, found)
					require.Equal(t, id, picture.TransactionId)
					count, err := db.NewSession(nil).Where("transaction_id=?", id).Count(new(models.TransactionTagIndex))
					require.NoError(t, err)
					require.Equal(t, int64(1), count)
					second := aiReviewCreate(t, 1, 11, "second attachment claim")
					secondRequest := aiReviewExpense(seed, second)
					secondRequest.Transaction.PictureIds = []string{"900001"}
					_, err = AIReviewItems.ConfirmInLedger(nil, 1, secondRequest, "", time.UTC)
					require.Error(t, err)
					aiReviewAssertPending(t, second)
					require.Equal(t, int64(9500), occurrenceAccountBalance(t, 1, seed.account.AccountId))
					require.Equal(t, int64(1), occurrenceTransactionCount(t, 1))
				}
			} else {
				require.Error(t, err)
				aiReviewAssertPending(t, item)
				require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
				require.Zero(t, occurrenceTransactionCount(t, 1))
			}
		})
	}
}
