//go:build cgo

package services

import (
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/require"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
)

const (
	transactionWriteTestLedger      = int64(900)
	transactionWriteOtherLedger     = int64(910)
	transactionWriteExistingTag     = int64(9501)
	transactionWriteSecondTag       = int64(9502)
	transactionWriteUnusedTag       = int64(9503)
	transactionWriteExistingPicture = int64(9801)
	transactionWriteSecondPicture   = int64(9802)
	transactionWriteUnusedPicture   = int64(9803)
)

type transactionWriteFixture struct {
	seed                 *occurrenceSeed
	row                  *models.Transaction
	alternateSource      *models.Account
	alternateDestination *models.Account
}

// Snapshot only the fresh fixture database, including deleted associations and
// receipts, so a rejected write cannot conceal a partial financial update.
type transactionWriteState struct {
	transactions []models.Transaction
	accounts     []models.Account
	tags         []models.TransactionTagIndex
	pictures     []models.TransactionPictureInfo
	receipts     []models.TransactionSubmission
}

func transactionWriteContext(actor int64) *core.WebContext {
	g, _ := gin.CreateTestContext(httptest.NewRecorder())
	g.Request = httptest.NewRequest("POST", "/synthetic-write", nil)
	c := core.WrapWebContext(g, nil)
	c.SetTokenClaims(&core.UserTokenClaims{Uid: actor, Type: core.USER_TOKEN_TYPE_NORMAL})
	return c
}

func setupTransactionWriteFixture(t *testing.T, recorder int64, transfer bool) *transactionWriteFixture {
	t.Helper()
	seed, rowForAmount := submissionSetup(t)
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(models.Ledger), new(models.LedgerMember)))
	sess := occurrenceDB(t).NewSession(nil)
	defer sess.Close()
	_, err := sess.Insert(
		&models.Ledger{LedgerId: transactionWriteTestLedger, OwnerUid: 1, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic shared ledger"},
		&models.Ledger{LedgerId: transactionWriteOtherLedger, OwnerUid: 1, Type: models.LEDGER_TYPE_PERSONAL, Name: "Synthetic other ledger"},
	)
	require.NoError(t, err)
	// User 4 records while writable, then becomes a viewer before the tested
	// operation. The historical row is therefore valid without bypassing posting.
	members := []*models.LedgerMember{
		{LedgerMemberId: 9001, LedgerId: transactionWriteTestLedger, Uid: 1, Status: models.FAMILY_MEMBER_STATUS_ACTIVE, Role: models.FAMILY_MEMBER_ROLE_OWNER},
		{LedgerMemberId: 9002, LedgerId: transactionWriteTestLedger, Uid: 2, Status: models.FAMILY_MEMBER_STATUS_ACTIVE, Role: models.FAMILY_MEMBER_ROLE_MEMBER},
		{LedgerMemberId: 9003, LedgerId: transactionWriteTestLedger, Uid: 3, Status: models.FAMILY_MEMBER_STATUS_ACTIVE, Role: models.FAMILY_MEMBER_ROLE_ADMIN},
		{LedgerMemberId: 9004, LedgerId: transactionWriteTestLedger, Uid: 4, Status: models.FAMILY_MEMBER_STATUS_ACTIVE, Role: models.FAMILY_MEMBER_ROLE_MEMBER},
	}
	_, err = sess.Insert(members)
	require.NoError(t, err)
	_, err = sess.In("account_id", []int64{seed.account.AccountId, seed.other.AccountId}).Cols("ledger_id").Update(&models.Account{LedgerId: transactionWriteTestLedger})
	require.NoError(t, err)
	seed.account.LedgerId, seed.other.LedgerId = transactionWriteTestLedger, transactionWriteTestLedger
	fixture := &transactionWriteFixture{
		seed: seed,
		alternateSource: &models.Account{AccountId: 100003, Uid: 1, LedgerId: transactionWriteTestLedger,
			Category: models.ACCOUNT_CATEGORY_CHECKING_ACCOUNT, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT,
			Name: "Synthetic alternate source", Currency: "USD", Balance: 2000},
		alternateDestination: &models.Account{AccountId: 100004, Uid: 1, LedgerId: transactionWriteTestLedger,
			Category: models.ACCOUNT_CATEGORY_SAVINGS_ACCOUNT, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT,
			Name: "Synthetic alternate destination", Currency: "EUR", Balance: 3000},
	}
	_, err = sess.Insert(fixture.alternateSource, fixture.alternateDestination)
	require.NoError(t, err)
	// Release the single SQLite connection before calling the production service.
	require.NoError(t, sess.Close())
	row := rowForAmount(100)
	row.LedgerId, row.RecorderUid, row.PayerUid = transactionWriteTestLedger, recorder, recorder
	if transfer {
		row.Type = models.TRANSACTION_DB_TYPE_TRANSFER_OUT
		row.CategoryId = seed.categories[models.CATEGORY_TYPE_TRANSFER].CategoryId
		row.RelatedAccountId, row.RelatedAccountAmount = seed.other.AccountId, 80
	}
	require.NoError(t, Transactions.CreateTransactionIdempotent(core.NewNullContext(), row, nil, nil, "synthetic-write-scope"))
	sess = occurrenceDB(t).NewSession(nil)
	_, err = sess.ID(int64(9004)).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	require.NoError(t, sess.Close())
	fixture.row = transactionWriteReadRow(t, row.TransactionId)
	return fixture
}

func transactionWriteReadRow(t *testing.T, id int64) *models.Transaction {
	t.Helper()
	sess := occurrenceDB(t).NewSession(nil)
	defer sess.Close()
	row := &models.Transaction{}
	found, err := sess.ID(id).Where("uid=?", 1).Get(row)
	require.NoError(t, err)
	require.True(t, found)
	return row
}

func transactionWriteSnapshot(t *testing.T) transactionWriteState {
	t.Helper()
	sess := occurrenceDB(t).NewSession(nil)
	defer sess.Close()
	var state transactionWriteState
	require.NoError(t, sess.OrderBy("transaction_id asc").Find(&state.transactions))
	require.NoError(t, sess.OrderBy("account_id asc").Find(&state.accounts))
	require.NoError(t, sess.OrderBy("tag_index_id asc").Find(&state.tags))
	require.NoError(t, sess.OrderBy("picture_id asc").Find(&state.pictures))
	require.NoError(t, sess.OrderBy("uid asc, recorder_uid asc, ledger_id asc, client_session_id asc").Find(&state.receipts))
	return state
}

func (f *transactionWriteFixture) edit() *models.Transaction {
	draft := *f.row
	draft.Amount, draft.Comment = 150, "Synthetic scoped edit"
	if draft.Type == models.TRANSACTION_DB_TYPE_TRANSFER_OUT {
		draft.RelatedAccountAmount = 120
	}
	return &draft
}

func transactionWriteMoveAccount(t *testing.T, id, ledger int64) {
	t.Helper()
	sess := occurrenceDB(t).NewSession(nil)
	defer sess.Close()
	changed, err := sess.ID(id).Cols("ledger_id").Update(&models.Account{LedgerId: ledger})
	require.NoError(t, err)
	require.Equal(t, int64(1), changed)
}

func transactionWriteSeedMetadata(t *testing.T, f *transactionWriteFixture) {
	t.Helper()
	sess := occurrenceDB(t).NewSession(nil)
	defer sess.Close()
	_, err := sess.Insert(
		&models.TransactionTag{TagId: transactionWriteExistingTag, Uid: 1, Name: "Synthetic existing tag"},
		&models.TransactionTag{TagId: transactionWriteSecondTag, Uid: 1, Name: "Synthetic second tag"},
		&models.TransactionTag{TagId: transactionWriteUnusedTag, Uid: 1, Name: "Synthetic unused tag"},
		&models.TransactionTagIndex{TagIndexId: 9701, Uid: 1, TransactionId: f.row.TransactionId, TransactionTime: f.row.TransactionTime, TagId: transactionWriteExistingTag},
		&models.TransactionTagIndex{TagIndexId: 9702, Uid: 1, TransactionId: f.row.TransactionId, TransactionTime: f.row.TransactionTime, TagId: transactionWriteSecondTag},
		&models.TransactionPictureInfo{PictureId: transactionWriteExistingPicture, Uid: 1, TransactionId: f.row.TransactionId, PictureExtension: "png"},
		&models.TransactionPictureInfo{PictureId: transactionWriteSecondPicture, Uid: 1, TransactionId: f.row.TransactionId, PictureExtension: "png"},
		&models.TransactionPictureInfo{PictureId: transactionWriteUnusedPicture, Uid: 1, TransactionId: models.TransactionPictureNewPictureTransactionId, PictureExtension: "png"},
	)
	require.NoError(t, err)
}

func transactionWriteMetadataSnapshot() TransactionMetadataSnapshot {
	return TransactionMetadataSnapshot{
		TagIds:     []int64{transactionWriteExistingTag, transactionWriteSecondTag},
		PictureIds: []int64{transactionWriteExistingPicture, transactionWriteSecondPicture},
	}
}

func TestTransactionWriteScopeFourRolesModifyAndDelete(t *testing.T) {
	cases := []struct {
		name     string
		actor    int64
		recorder int64
		allowed  bool
	}{
		{"owner_edits_member", 1, 2, true},
		{"administrator_own", 3, 3, true},
		{"administrator_other", 3, 2, false},
		{"member_own", 2, 2, true},
		{"member_other", 2, 3, false},
		{"viewer_own", 4, 4, false},
	}
	for _, operation := range []string{"modify", "delete"} {
		for _, test := range cases {
			t.Run(operation+"/"+test.name, func(t *testing.T) {
				f := setupTransactionWriteFixture(t, test.recorder, false)
				before := transactionWriteSnapshot(t)
				c := transactionWriteContext(test.actor)
				var err error
				if operation == "modify" {
					err = Transactions.ModifyTransactionInLedger(c, test.actor, transactionWriteTestLedger, f.edit(), false, 0, nil, nil, nil, nil)
				} else {
					err = Transactions.DeleteTransactionInLedger(c, test.actor, 1, transactionWriteTestLedger, f.row.TransactionId)
				}
				if !test.allowed {
					require.Error(t, err)
					require.Equal(t, before, transactionWriteSnapshot(t), "denied write changed persisted fixture data")
					return
				}
				require.NoError(t, err)
				stored := transactionWriteReadRow(t, f.row.TransactionId)
				require.Equal(t, test.recorder, stored.RecorderUid)
				require.Equal(t, test.recorder, stored.PayerUid)
				require.Equal(t, transactionWriteTestLedger, stored.LedgerId)
				if operation == "modify" {
					require.False(t, stored.Deleted)
					require.Equal(t, int64(150), stored.Amount)
					require.Equal(t, int64(9850), occurrenceAccountBalance(t, 1, f.seed.account.AccountId))
				} else {
					require.True(t, stored.Deleted)
					require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, f.seed.account.AccountId))
				}
				after := transactionWriteSnapshot(t)
				require.Equal(t, before.receipts, after.receipts, "editing must preserve the original submission receipt")
				require.Equal(t, int64(0), occurrenceAccountBalance(t, 1, f.seed.other.AccountId))
			})
		}
	}
}

func TestTransactionWriteScopeRechecksLiveMembershipAndRecorder(t *testing.T) {
	for _, operation := range []string{"modify", "delete"} {
		for _, revoked := range []string{"downgraded", "removed", "recorder_changed"} {
			t.Run(operation+"/"+revoked, func(t *testing.T) {
				f := setupTransactionWriteFixture(t, 2, false)
				c := transactionWriteContext(2)
				scope, err := resolveTransactionWriteScope(c, 2, 1, transactionWriteTestLedger)
				require.NoError(t, err)
				sess := occurrenceDB(t).NewSession(nil)
				switch revoked {
				case "downgraded":
					_, err = sess.ID(int64(9002)).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
				case "removed":
					_, err = sess.ID(int64(9002)).Cols("status").Update(&models.LedgerMember{Status: models.FAMILY_MEMBER_STATUS_REMOVED})
				case "recorder_changed":
					_, err = sess.ID(f.row.TransactionId).Cols("recorder_uid").Update(&models.Transaction{RecorderUid: 3})
				}
				require.NoError(t, err)
				require.NoError(t, sess.Close())
				before := transactionWriteSnapshot(t)
				if operation == "modify" {
					err = Transactions.modifyTransaction(c, f.edit(), false, 0, nil, nil, nil, nil, scope)
				} else {
					err = Transactions.deleteTransaction(c, 1, f.row.TransactionId, scope)
				}
				require.Error(t, err, "a preflight scope must not retain revoked write access")
				require.Equal(t, before, transactionWriteSnapshot(t), "transaction-time authorization failed to roll back")
			})
		}
	}
}

func TestTransactionWriteScopeRejectsLedgerAndAccountChanges(t *testing.T) {
	for _, operation := range []string{"modify", "delete"} {
		t.Run(operation+"/actual_ledger", func(t *testing.T) {
			f := setupTransactionWriteFixture(t, 2, true)
			// Both accounts match the supplied scope, but the persisted row does
			// not. Account validation alone must not authorize the wrong row.
			transactionWriteMoveAccount(t, f.seed.account.AccountId, transactionWriteOtherLedger)
			transactionWriteMoveAccount(t, f.seed.other.AccountId, transactionWriteOtherLedger)
			before := transactionWriteSnapshot(t)
			c := transactionWriteContext(1)
			var err error
			if operation == "modify" {
				draft := f.edit()
				draft.LedgerId = transactionWriteOtherLedger
				err = Transactions.ModifyTransactionInLedger(c, 1, transactionWriteOtherLedger, draft, false, 0, nil, nil, nil, nil)
			} else {
				err = Transactions.DeleteTransactionInLedger(c, 1, 1, transactionWriteOtherLedger, f.row.TransactionId)
			}
			require.Error(t, err)
			require.Equal(t, before, transactionWriteSnapshot(t))
		})
		for _, account := range []string{"old_source", "old_destination"} {
			t.Run(operation+"/"+account, func(t *testing.T) {
				f := setupTransactionWriteFixture(t, 2, true)
				draft := f.edit()
				if account == "old_source" {
					transactionWriteMoveAccount(t, f.seed.account.AccountId, transactionWriteOtherLedger)
					draft.AccountId = f.alternateSource.AccountId
				} else {
					transactionWriteMoveAccount(t, f.seed.other.AccountId, transactionWriteOtherLedger)
					draft.RelatedAccountId = f.alternateDestination.AccountId
				}
				before := transactionWriteSnapshot(t)
				c := transactionWriteContext(2)
				var err error
				if operation == "modify" {
					err = Transactions.ModifyTransactionInLedger(c, 2, transactionWriteTestLedger, draft, false, 0, nil, nil, nil, nil)
				} else {
					err = Transactions.DeleteTransactionInLedger(c, 2, 1, transactionWriteTestLedger, f.row.TransactionId)
				}
				require.Error(t, err, "a moved old account must not receive balance reversal")
				require.Equal(t, before, transactionWriteSnapshot(t))
			})
		}
	}
	for _, account := range []string{"new_source", "new_destination"} {
		t.Run("modify/"+account, func(t *testing.T) {
			f := setupTransactionWriteFixture(t, 2, true)
			draft := f.edit()
			if account == "new_source" {
				transactionWriteMoveAccount(t, f.alternateSource.AccountId, transactionWriteOtherLedger)
				draft.AccountId = f.alternateSource.AccountId
			} else {
				transactionWriteMoveAccount(t, f.alternateDestination.AccountId, transactionWriteOtherLedger)
				draft.RelatedAccountId = f.alternateDestination.AccountId
			}
			before := transactionWriteSnapshot(t)
			err := Transactions.ModifyTransactionInLedger(transactionWriteContext(2), 2, transactionWriteTestLedger, draft, false, 0, nil, nil, nil, nil)
			require.Error(t, err, "a replacement account must belong to the same ledger")
			require.Equal(t, before, transactionWriteSnapshot(t))
		})
	}
}

func TestTransactionWriteScopeRejectsForeignTransferRelatedRow(t *testing.T) {
	for _, operation := range []string{"modify", "delete"} {
		t.Run(operation, func(t *testing.T) {
			f := setupTransactionWriteFixture(t, 2, true)
			require.Positive(t, f.row.RelatedId)
			sess := occurrenceDB(t).NewSession(nil)
			changed, err := sess.ID(f.row.RelatedId).Cols("ledger_id").Update(&models.Transaction{LedgerId: transactionWriteOtherLedger})
			require.NoError(t, err)
			require.Equal(t, int64(1), changed)
			require.NoError(t, sess.Close())
			before := transactionWriteSnapshot(t)
			c := transactionWriteContext(2)
			if operation == "modify" {
				err = Transactions.ModifyTransactionInLedger(c, 2, transactionWriteTestLedger, f.edit(), false, 0, nil, nil, nil, nil)
			} else {
				err = Transactions.DeleteTransactionInLedger(c, 2, 1, transactionWriteTestLedger, f.row.TransactionId)
			}
			require.Error(t, err, "a transfer must not mutate its related row in another ledger")
			require.Equal(t, before, transactionWriteSnapshot(t))
		})
	}
}

func TestTransactionWriteScopeRejectsActorClaimMismatch(t *testing.T) {
	for _, operation := range []string{"modify", "delete"} {
		t.Run(operation, func(t *testing.T) {
			f := setupTransactionWriteFixture(t, 2, false)
			before := transactionWriteSnapshot(t)
			// The owner argument must not replace the authenticated member's
			// identity, even when that owner would otherwise be allowed to write.
			c := transactionWriteContext(2)
			var err error
			if operation == "modify" {
				err = Transactions.ModifyTransactionInLedger(c, 1, transactionWriteTestLedger, f.edit(), false, 0, nil, nil, nil, nil)
			} else {
				err = Transactions.DeleteTransactionInLedger(c, 1, 1, transactionWriteTestLedger, f.row.TransactionId)
			}
			require.Error(t, err)
			require.Equal(t, before, transactionWriteSnapshot(t))
		})
	}
}

func TestTransactionWriteScopePrivateMetadataDeltasAndPreservation(t *testing.T) {
	for _, actor := range []int64{2, 3} {
		for _, action := range []string{"add_tag", "remove_tag", "add_picture", "remove_picture", "preserve"} {
			name := "member"
			if actor == 3 {
				name = "administrator"
			}
			t.Run(name+"/"+action, func(t *testing.T) {
				f := setupTransactionWriteFixture(t, actor, false)
				// These fixture associations model a historical row already carrying
				// owner metadata. Ordinary edits must preserve it without permitting
				// a member to acquire or remove private objects.
				transactionWriteSeedMetadata(t, f)
				before := transactionWriteSnapshot(t)
				var addTags, removeTags, addPictures, removePictures []int64
				switch action {
				case "add_tag":
					addTags = []int64{transactionWriteUnusedTag}
				case "remove_tag":
					removeTags = []int64{transactionWriteExistingTag}
				case "add_picture":
					addPictures = []int64{transactionWriteUnusedPicture}
				case "remove_picture":
					removePictures = []int64{transactionWriteExistingPicture}
				}
				err := Transactions.ModifyTransactionInLedger(transactionWriteContext(actor), actor, transactionWriteTestLedger, f.edit(), false, 2, addTags, removeTags, addPictures, removePictures)
				if action != "preserve" {
					require.Error(t, err)
					require.Equal(t, before, transactionWriteSnapshot(t))
					return
				}
				require.NoError(t, err)
				after := transactionWriteSnapshot(t)
				require.Equal(t, before.tags, after.tags)
				require.Equal(t, before.pictures, after.pictures)
				require.Equal(t, before.receipts, after.receipts)
				require.Equal(t, int64(150), transactionWriteReadRow(t, f.row.TransactionId).Amount)
				require.Equal(t, int64(9850), occurrenceAccountBalance(t, 1, f.seed.account.AccountId))
			})
		}
	}
	t.Run("owner_may_change_private_metadata", func(t *testing.T) {
		f := setupTransactionWriteFixture(t, 2, false)
		transactionWriteSeedMetadata(t, f)
		err := Transactions.ModifyTransactionInLedger(transactionWriteContext(1), 1, transactionWriteTestLedger, f.edit(), false, 2,
			[]int64{transactionWriteUnusedTag}, []int64{transactionWriteExistingTag},
			[]int64{transactionWriteUnusedPicture}, []int64{transactionWriteExistingPicture})
		require.NoError(t, err)
		state := transactionWriteSnapshot(t)
		activeTags := make([]int64, 0, 2)
		for _, tag := range state.tags {
			if !tag.Deleted {
				activeTags = append(activeTags, tag.TagId)
			}
		}
		require.ElementsMatch(t, []int64{transactionWriteSecondTag, transactionWriteUnusedTag}, activeTags)
		for _, picture := range state.pictures {
			if picture.PictureId == transactionWriteExistingPicture {
				require.True(t, picture.Deleted)
			} else {
				require.False(t, picture.Deleted)
				require.Equal(t, f.row.TransactionId, picture.TransactionId)
			}
		}
		require.Equal(t, int64(150), transactionWriteReadRow(t, f.row.TransactionId).Amount)
		require.Equal(t, int64(9850), occurrenceAccountBalance(t, 1, f.seed.account.AccountId))
	})
}

func TestTransactionWriteScopeCompleteMetadataSnapshots(t *testing.T) {
	for _, actor := range []int64{2, 3} {
		for _, action := range []string{"same", "reordered", "omitted_tag", "changed_tag", "omitted_picture", "changed_picture"} {
			name := "member"
			if actor == 3 {
				name = "administrator"
			}
			t.Run(name+"/"+action, func(t *testing.T) {
				f := setupTransactionWriteFixture(t, actor, false)
				transactionWriteSeedMetadata(t, f)
				before := transactionWriteSnapshot(t)
				metadata := transactionWriteMetadataSnapshot()
				switch action {
				case "reordered":
					metadata.TagIds = []int64{transactionWriteSecondTag, transactionWriteExistingTag}
					metadata.PictureIds = []int64{transactionWriteSecondPicture, transactionWriteExistingPicture}
				case "omitted_tag":
					metadata.TagIds = nil
				case "changed_tag":
					metadata.TagIds = []int64{transactionWriteExistingTag, transactionWriteUnusedTag}
				case "omitted_picture":
					metadata.PictureIds = nil
				case "changed_picture":
					metadata.PictureIds = []int64{transactionWriteExistingPicture, transactionWriteUnusedPicture}
				}
				err := Transactions.ModifyTransactionInLedger(transactionWriteContext(actor), actor, transactionWriteTestLedger, f.edit(), false, 2, nil, nil, nil, nil, metadata)
				if action != "same" && action != "reordered" {
					require.Error(t, err, "an incomplete or changed private metadata set must not be accepted as unchanged")
					require.Equal(t, before, transactionWriteSnapshot(t))
					return
				}
				require.NoError(t, err)
				after := transactionWriteSnapshot(t)
				require.Equal(t, before.tags, after.tags)
				require.Equal(t, before.pictures, after.pictures)
				require.Equal(t, before.receipts, after.receipts)
				require.Equal(t, int64(150), transactionWriteReadRow(t, f.row.TransactionId).Amount)
				require.Equal(t, int64(9850), occurrenceAccountBalance(t, 1, f.seed.account.AccountId))
			})
		}
	}
}

func TestTransactionWriteScopeRechecksMetadataAfterPreflight(t *testing.T) {
	for _, changed := range []string{"tag", "picture"} {
		t.Run(changed, func(t *testing.T) {
			f := setupTransactionWriteFixture(t, 2, false)
			transactionWriteSeedMetadata(t, f)
			c := transactionWriteContext(2)
			scope, err := resolveTransactionWriteScope(c, 2, 1, transactionWriteTestLedger)
			require.NoError(t, err)
			metadata := transactionWriteMetadataSnapshot()
			scope.metadata = &metadata
			sess := occurrenceDB(t).NewSession(nil)
			if changed == "tag" {
				_, err = sess.Insert(&models.TransactionTagIndex{TagIndexId: 9703, Uid: 1, TransactionId: f.row.TransactionId, TransactionTime: f.row.TransactionTime, TagId: transactionWriteUnusedTag})
			} else {
				_, err = sess.ID(transactionWriteUnusedPicture).Cols("transaction_id").Update(&models.TransactionPictureInfo{TransactionId: f.row.TransactionId})
			}
			require.NoError(t, err)
			require.NoError(t, sess.Close())
			// This baseline includes the owner's intervening association. The
			// rejected member edit must preserve it and leave balances untouched.
			before := transactionWriteSnapshot(t)
			err = Transactions.modifyTransaction(c, f.edit(), false, 2, nil, nil, nil, nil, scope)
			require.Error(t, err)
			require.Equal(t, before, transactionWriteSnapshot(t))
		})
	}
}

func TestTransactionWriteScopeDefaultLegacyCompatibility(t *testing.T) {
	seed, rowForAmount := submissionSetup(t)
	row := rowForAmount(100)
	require.NoError(t, Transactions.CreateTransactionIdempotent(core.NewNullContext(), row, nil, nil, "synthetic-default-legacy"))
	draft := *row
	draft.Amount, draft.Comment = 150, "Synthetic default edit"
	require.NoError(t, Transactions.ModifyTransaction(core.NewNullContext(), &draft, false, 0, nil, nil, nil, nil))
	stored := transactionWriteReadRow(t, row.TransactionId)
	require.Equal(t, models.DefaultLedgerId, stored.LedgerId)
	require.Equal(t, int64(150), stored.Amount)
	require.Equal(t, int64(9850), occurrenceAccountBalance(t, 1, seed.account.AccountId))
	require.NoError(t, Transactions.DeleteTransaction(core.NewNullContext(), 1, row.TransactionId))
	require.True(t, transactionWriteReadRow(t, row.TransactionId).Deleted)
	require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))

	t.Run("explicit_ledger_requires_scoped_delete", func(t *testing.T) {
		f := setupTransactionWriteFixture(t, 2, false)
		before := transactionWriteSnapshot(t)
		err := Transactions.DeleteTransaction(transactionWriteContext(1), 1, f.row.TransactionId)
		require.Error(t, err, "legacy default-ledger deletion must not bypass the explicit ledger contract")
		require.Equal(t, before, transactionWriteSnapshot(t))
	})
}
