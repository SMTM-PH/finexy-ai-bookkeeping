//go:build cgo

package services

import (
	"sync"
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
)

func submissionSetup(t *testing.T) (*occurrenceSeed, func(int64) *models.Transaction) {
	setupOccurrenceLedger(t)
	if err := datastore.Container.UserDataStore.SyncStructs(new(models.TransactionSubmission)); err != nil {
		t.Fatal(err)
	}
	seed := insertOccurrenceSeed(t, 1, 10000, 0)
	when := utils.GetMinTransactionTimeFromUnixTime(time.Now().Unix())
	return seed, func(amount int64) *models.Transaction {
		return &models.Transaction{Uid: 1, RecorderUid: 1, PayerUid: 1, Type: models.TRANSACTION_DB_TYPE_EXPENSE,
			AccountId: seed.account.AccountId, CategoryId: seed.categories[models.CATEGORY_TYPE_EXPENSE].CategoryId,
			Amount: amount, TransactionTime: when, Comment: "synthetic retry"}
	}
}

func TestTransactionSubmissionPersistentReplayAndPayloadBinding(t *testing.T) {
	seed, row := submissionSetup(t)
	first := row(123)
	if err := Transactions.CreateTransactionIdempotent(core.NewNullContext(), first, nil, nil, "stable-key"); err != nil {
		t.Fatal(err)
	}
	// A new service instance has no duplicate-submission memory.
	fresh := &TransactionService{ServiceUsingDB: Transactions.ServiceUsingDB, ServiceUsingUuid: Transactions.ServiceUsingUuid}
	retry := row(123)
	if err := fresh.CreateTransactionIdempotent(core.NewNullContext(), retry, nil, nil, "stable-key"); err != nil {
		t.Fatal(err)
	}
	if first.TransactionId != retry.TransactionId || occurrenceTransactionCount(t, 1) != 1 || occurrenceAccountBalance(t, 1, seed.account.AccountId) != 9877 {
		t.Fatal("replay posted more than once")
	}
	acknowledged := row(123)
	if found, err := fresh.ReplayTransactionSubmission(core.NewNullContext(), acknowledged, nil, nil, "stable-key"); err != nil || !found || acknowledged.TransactionId != first.TransactionId {
		t.Fatal("early replay did not acknowledge the persisted transaction", err)
	}
	if found, err := fresh.ReplayTransactionSubmission(core.NewNullContext(), row(123), []int64{9}, nil, "stable-key"); err != errs.ErrParameterInvalid || found {
		t.Fatal("replay accepted changed attachment or tag fields", err)
	}
	if err := fresh.CreateTransactionIdempotent(core.NewNullContext(), row(124), nil, nil, "stable-key"); err != errs.ErrParameterInvalid {
		t.Fatal("key accepted different financial values", err)
	}
	// A replay after deletion must not silently recreate the deleted entry.
	sess := occurrenceDB(t).NewSession(nil)
	_, err := sess.Where("transaction_id=?", first.TransactionId).Cols("deleted").Update(&models.Transaction{Deleted: true})
	_ = sess.Close()
	if err != nil {
		t.Fatal(err)
	}
	if err = fresh.CreateTransactionIdempotent(core.NewNullContext(), row(123), nil, nil, "stable-key"); err == nil {
		t.Fatal("deleted operation was recreated")
	}
}

func TestTransactionSubmissionConcurrencyAndRollback(t *testing.T) {
	seed, row := submissionSetup(t)
	var wg sync.WaitGroup
	errors := make(chan error, 8)
	ids := make(chan int64, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			draft := row(123)
			errors <- Transactions.CreateTransactionIdempotent(core.NewNullContext(), draft, nil, nil, "concurrent-key")
			ids <- draft.TransactionId
		}()
	}
	wg.Wait()
	close(errors)
	close(ids)
	for err := range errors {
		if err != nil {
			t.Fatal(err)
		}
	}
	var expected int64
	for id := range ids {
		if expected == 0 {
			expected = id
		}
		if id != expected {
			t.Fatal("concurrent results differ")
		}
	}
	if occurrenceTransactionCount(t, 1) != 1 || occurrenceAccountBalance(t, 1, seed.account.AccountId) != 9877 {
		t.Fatal("concurrent posting changed balance twice")
	}
	bad := row(10)
	bad.CategoryId = 999999
	if err := Transactions.CreateTransactionIdempotent(core.NewNullContext(), bad, nil, nil, "rollback-key"); err == nil {
		t.Fatal("invalid category accepted")
	}
	var receipt models.TransactionSubmission
	sess := occurrenceDB(t).NewSession(nil)
	found, err := sess.Where("client_session_id=?", "rollback-key").Get(&receipt)
	_ = sess.Close()
	if err != nil || found {
		t.Fatal("failed financial write retained a receipt", err)
	}
	if err = Transactions.CreateTransactionIdempotent(core.NewNullContext(), row(10), nil, nil, "rollback-key"); err != nil {
		t.Fatal("rolled-back key could not be retried", err)
	}
}

func TestTransactionSubmissionLedgerActorAndLiveAccess(t *testing.T) {
	seed, row := submissionSetup(t)
	if err := datastore.Container.UserDataStore.SyncStructs(new(models.Ledger), new(models.LedgerMember)); err != nil {
		t.Fatal(err)
	}
	sess := occurrenceDB(t).NewSession(nil)
	defer sess.Close()
	ledger := &models.Ledger{LedgerId: 900, OwnerUid: 1, Type: models.LEDGER_TYPE_PERSONAL, Name: "Isolated ledger"}
	member := &models.LedgerMember{LedgerMemberId: 901, LedgerId: 900, Uid: 2, Status: models.FAMILY_MEMBER_STATUS_ACTIVE, Role: models.FAMILY_MEMBER_ROLE_MEMBER}
	if _, err := sess.Insert(ledger, member); err != nil {
		t.Fatal(err)
	}
	if _, err := sess.ID(seed.account.AccountId).Cols("ledger_id").Update(&models.Account{LedgerId: 900}); err != nil {
		t.Fatal(err)
	}
	draft := row(123)
	draft.LedgerId, draft.RecorderUid, draft.PayerUid = 900, 2, 2
	if err := Transactions.CreateTransactionIdempotent(core.NewNullContext(), draft, nil, nil, "shared-key"); err != nil {
		t.Fatal(err)
	}
	owner := row(123)
	owner.LedgerId = 900
	if err := Transactions.CreateTransactionIdempotent(core.NewNullContext(), owner, nil, nil, "shared-key"); err != nil || owner.TransactionId == draft.TransactionId {
		t.Fatal("keys were not separated by actor", err)
	}
	if _, err := sess.ID(member.LedgerMemberId).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER}); err != nil {
		t.Fatal(err)
	}
	retry := row(123)
	retry.LedgerId, retry.RecorderUid, retry.PayerUid = 900, 2, 2
	if err := Transactions.CreateTransactionIdempotent(core.NewNullContext(), retry, nil, nil, "shared-key"); err != errs.ErrLedgerAccessDenied {
		t.Fatal("revoked write access exposed a replay", err)
	}
	if err := Transactions.CreateTransactionIdempotent(core.NewNullContext(), row(10), nil, nil, "foreign-account"); err != errs.ErrAccountIdInvalid {
		t.Fatal("default ledger wrote an explicit-ledger account", err)
	}
	if occurrenceAccountBalance(t, 1, seed.account.AccountId) != 9754 {
		t.Fatal("denied writes changed the balance")
	}
}
