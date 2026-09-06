//go:build cgo

package services

import (
	"encoding/json"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
)

// setupOccurrenceLedger repoints the global datastore container at a fresh
// temporary SQLite database. The package-level service singletons read that
// container, so Confirm exercises the real transaction posting path.
func setupOccurrenceLedger(t *testing.T) {
	t.Helper()
	config := &settings.Config{
		DatabaseConfig: &settings.DatabaseConfig{
			DatabaseType:      settings.Sqlite3DbType,
			DatabasePath:      filepath.Join(t.TempDir(), "ledger.db"),
			MaxOpenConnection: 1,
			MaxIdleConnection: 1,
		},
		UuidGeneratorType: settings.InternalUuidGeneratorType,
		UuidServerId:      1,
	}
	if err := datastore.InitializeDataStore(config); err != nil {
		t.Fatal(err)
	}
	if err := uuid.InitializeUuidGenerator(config); err != nil {
		t.Fatal(err)
	}
	if err := datastore.Container.UserDataStore.SyncStructs(
		new(models.Account), new(models.Transaction), new(models.TransactionCategory),
		new(models.TransactionTagIndex), new(models.TransactionPictureInfo), new(models.ScheduledOccurrence),
		new(models.TransactionTemplate), new(models.TransactionTag)); err != nil {
		t.Fatal(err)
	}
}

func occurrenceDB(t *testing.T) *datastore.Database {
	t.Helper()
	return datastore.Container.UserDataStore.Get(0)
}

func occurrenceAccountBalance(t *testing.T, uid, accountId int64) int64 {
	t.Helper()
	account := &models.Account{}
	found, err := occurrenceDB(t).NewSession(nil).Where("uid=? AND account_id=?", uid, accountId).Get(account)
	if err != nil {
		t.Fatal(err)
	}
	if !found {
		t.Fatalf("account %d of user %d not found", accountId, uid)
	}
	return account.Balance
}

func occurrenceTransactionCount(t *testing.T, uid int64) int64 {
	t.Helper()
	count, err := occurrenceDB(t).NewSession(nil).Where("uid=?", uid).Count(new(models.Transaction))
	if err != nil {
		t.Fatal(err)
	}
	return count
}

func occurrenceRow(t *testing.T, uid, templateId, scheduledUnixTime int64) *models.ScheduledOccurrence {
	t.Helper()
	item := &models.ScheduledOccurrence{}
	found, err := occurrenceDB(t).NewSession(nil).Where("uid=? AND template_id=? AND scheduled_unix_time=?", uid, templateId, scheduledUnixTime).Get(item)
	if err != nil || !found {
		t.Fatalf("occurrence not found: %v", err)
	}
	return item
}

type occurrenceSeed struct {
	uid        int64
	account    *models.Account
	other      *models.Account
	categories map[models.TransactionCategoryType]*models.TransactionCategory
}

// insertOccurrenceSeed creates one spendable account, one counterpart account
// in another currency, and one leaf category per transaction type. A leaf
// category is required because primary categories cannot back a transaction.
func insertOccurrenceSeed(t *testing.T, uid int64, accountBalance int64, otherBalance int64) *occurrenceSeed {
	t.Helper()
	session := occurrenceDB(t).NewSession(nil)
	defer session.Close()
	seed := &occurrenceSeed{
		uid: uid,
		account: &models.Account{AccountId: uid*100000 + 1, Uid: uid, Category: models.ACCOUNT_CATEGORY_CHECKING_ACCOUNT,
			Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "Main", Icon: 1, Color: "000000",
			Currency: "USD", Balance: accountBalance},
		other: &models.Account{AccountId: uid*100000 + 2, Uid: uid, Category: models.ACCOUNT_CATEGORY_SAVINGS_ACCOUNT,
			Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "Other", Icon: 1, Color: "000000",
			Currency: "EUR", Balance: otherBalance},
		categories: make(map[models.TransactionCategoryType]*models.TransactionCategory),
	}
	for i, categoryType := range []models.TransactionCategoryType{models.CATEGORY_TYPE_EXPENSE, models.CATEGORY_TYPE_INCOME, models.CATEGORY_TYPE_TRANSFER} {
		// isCategoryValid requires the parent row to exist, so seed roots too.
		root := &models.TransactionCategory{CategoryId: uid*100000 + 90 + int64(i), Uid: uid,
			Type: categoryType, ParentCategoryId: 0, Name: "Root", Icon: 1, Color: "000000"}
		if _, err := session.Insert(root); err != nil {
			t.Fatal(err)
		}
		category := &models.TransactionCategory{CategoryId: uid*100000 + 11 + int64(i), Uid: uid,
			Type: categoryType, ParentCategoryId: root.CategoryId, Name: "Leaf", Icon: 1, Color: "000000"}
		if _, err := session.Insert(category); err != nil {
			t.Fatal(err)
		}
		seed.categories[categoryType] = category
	}
	for _, account := range []*models.Account{seed.account, seed.other} {
		if _, err := session.Insert(account); err != nil {
			t.Fatal(err)
		}
	}
	return seed
}

func occurrenceTemplate(uid, templateId int64, seed *occurrenceSeed, transactionType models.TransactionType, amount int64) *models.TransactionTemplate {
	template := &models.TransactionTemplate{TemplateId: templateId, Uid: uid, TemplateType: models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE,
		Type: transactionType, CategoryId: seed.categories[categoryFor(transactionType)].CategoryId, AccountId: seed.account.AccountId,
		ScheduledFrequencyType: models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY, ScheduledFrequency: "1",
		Amount: amount, Comment: "from schedule"}
	if transactionType == models.TRANSACTION_TYPE_TRANSFER {
		template.RelatedAccountId = seed.other.AccountId
		template.RelatedAccountAmount = amount - 100
	}
	return template
}

func categoryFor(transactionType models.TransactionType) models.TransactionCategoryType {
	if transactionType == models.TRANSACTION_TYPE_INCOME {
		return models.CATEGORY_TYPE_INCOME
	}
	if transactionType == models.TRANSACTION_TYPE_TRANSFER {
		return models.CATEGORY_TYPE_TRANSFER
	}
	return models.CATEGORY_TYPE_EXPENSE
}

func occurrenceSnapshot(t *testing.T, item *models.ScheduledOccurrence) models.ScheduledOccurrenceSnapshot {
	t.Helper()
	var snapshot models.ScheduledOccurrenceSnapshot
	if err := json.Unmarshal([]byte(item.SnapshotJSON), &snapshot); err != nil {
		t.Fatal(err)
	}
	return snapshot
}

func TestScheduledOccurrenceConfirmPostsOnceWithCorrectBalance(t *testing.T) {
	setupOccurrenceLedger(t)
	const uid = int64(1)
	seed := insertOccurrenceSeed(t, uid, 10000, 5000)
	template := occurrenceTemplate(uid, 7001, seed, models.TRANSACTION_TYPE_EXPENSE, 3000)
	const scheduledUnixTime = int64(1757100000)

	occurrence, err := ScheduledOccurrences.Enqueue(nil, template, scheduledUnixTime)
	if err != nil {
		t.Fatal(err)
	}
	if occurrence.Status != models.ScheduledOccurrencePending || occurrence.TransactionId != 0 {
		t.Fatalf("enqueued occurrence is not a pending review item: %+v", occurrence)
	}
	if balance := occurrenceAccountBalance(t, uid, seed.account.AccountId); balance != 10000 {
		t.Fatalf("enqueue changed the source balance to %d", balance)
	}
	if balance := occurrenceAccountBalance(t, uid, seed.other.AccountId); balance != 5000 {
		t.Fatalf("enqueue changed the other balance to %d", balance)
	}
	if count := occurrenceTransactionCount(t, uid); count != 0 {
		t.Fatalf("enqueue posted %d transactions", count)
	}
	snapshot := occurrenceSnapshot(t, occurrence)
	if snapshot.SourceAmount != 3000 || snapshot.SourceAccountId != seed.account.AccountId ||
		snapshot.CategoryId != template.CategoryId || snapshot.Comment != "from schedule" {
		t.Fatalf("snapshot does not capture the template values: %+v", snapshot)
	}

	transactionId, err := ScheduledOccurrences.Confirm(nil, uid, template.TemplateId, scheduledUnixTime)
	if err != nil {
		t.Fatal(err)
	}
	if transactionId <= 0 {
		t.Fatalf("confirm returned invalid transaction id %d", transactionId)
	}
	if count := occurrenceTransactionCount(t, uid); count != 1 {
		t.Fatalf("confirm posted %d transactions, want exactly 1", count)
	}
	posted := &models.Transaction{}
	if _, err := occurrenceDB(t).NewSession(nil).ID(transactionId).Get(posted); err != nil || posted.TransactionId != transactionId {
		t.Fatalf("posted transaction missing: %v", err)
	}
	if posted.Type != models.TRANSACTION_DB_TYPE_EXPENSE || posted.Amount != 3000 ||
		posted.AccountId != seed.account.AccountId || posted.CategoryId != template.CategoryId ||
		posted.Comment != "from schedule" || !posted.ScheduledCreated || posted.RelatedAccountId != 0 {
		t.Fatalf("posted transaction fields deviate from the snapshot: %+v", posted)
	}
	if balance := occurrenceAccountBalance(t, uid, seed.account.AccountId); balance != 7000 {
		t.Fatalf("source balance after confirm is %d, want 7000", balance)
	}
	if balance := occurrenceAccountBalance(t, uid, seed.other.AccountId); balance != 5000 {
		t.Fatalf("other balance after confirm is %d, want 5000", balance)
	}
	item := occurrenceRow(t, uid, template.TemplateId, scheduledUnixTime)
	if item.Status != models.ScheduledOccurrenceConfirmed || item.TransactionId != transactionId {
		t.Fatalf("occurrence was not acknowledged: %+v", item)
	}

	// Repeated confirms and repeated cron dispatches must not post again.
	repeatedId, err := ScheduledOccurrences.Confirm(nil, uid, template.TemplateId, scheduledUnixTime)
	if err != nil || repeatedId != transactionId {
		t.Fatalf("repeated confirm returned (%d, %v)", repeatedId, err)
	}
	redispatched := occurrenceTemplate(uid, 7001, seed, models.TRANSACTION_TYPE_EXPENSE, 9999)
	redispatched.Comment = "changed between runs"
	if _, err := ScheduledOccurrences.Enqueue(nil, redispatched, scheduledUnixTime); err != nil {
		t.Fatal(err)
	}
	item = occurrenceRow(t, uid, template.TemplateId, scheduledUnixTime)
	if occurrenceSnapshot(t, item).SourceAmount != 3000 {
		t.Fatalf("re-dispatch overwrote the first snapshot: %s", item.SnapshotJSON)
	}
	if count := occurrenceTransactionCount(t, uid); count != 1 {
		t.Fatalf("ledger has %d transactions after re-dispatch, want 1", count)
	}
	if balance := occurrenceAccountBalance(t, uid, seed.account.AccountId); balance != 7000 {
		t.Fatalf("balance after re-dispatch is %d, want 7000", balance)
	}
	if err := ScheduledOccurrences.SetDismissed(nil, uid, template.TemplateId, scheduledUnixTime, true); err == nil {
		t.Fatal("confirmed occurrence accepted a dismiss")
	}
	if err := ScheduledOccurrences.SetDismissed(nil, uid, template.TemplateId, scheduledUnixTime, false); err == nil {
		t.Fatal("confirmed occurrence accepted a restore")
	}
}

func TestScheduledOccurrenceConcurrentConfirmPostsExactlyOnce(t *testing.T) {
	setupOccurrenceLedger(t)
	const uid = int64(1)
	seed := insertOccurrenceSeed(t, uid, 8000, 5000)
	template := occurrenceTemplate(uid, 7002, seed, models.TRANSACTION_TYPE_EXPENSE, 2500)
	const scheduledUnixTime = int64(1757200000)
	if _, err := ScheduledOccurrences.Enqueue(nil, template, scheduledUnixTime); err != nil {
		t.Fatal(err)
	}

	const confirmers = 8
	transactionIds := make([]int64, confirmers)
	confirmErrors := make([]error, confirmers)
	var ready, done sync.WaitGroup
	ready.Add(confirmers)
	done.Add(confirmers)
	for i := 0; i < confirmers; i++ {
		go func(slot int) {
			defer done.Done()
			ready.Done()
			ready.Wait()
			transactionIds[slot], confirmErrors[slot] = ScheduledOccurrences.Confirm(nil, uid, template.TemplateId, scheduledUnixTime)
		}(i)
	}
	done.Wait()

	for i := 0; i < confirmers; i++ {
		if confirmErrors[i] != nil {
			t.Fatalf("confirmer %d failed: %v", i, confirmErrors[i])
		}
		if transactionIds[i] != transactionIds[0] {
			t.Fatalf("confirmer %d got transaction %d, confirmer 0 got %d", i, transactionIds[i], transactionIds[0])
		}
	}
	if count := occurrenceTransactionCount(t, uid); count != 1 {
		t.Fatalf("concurrent confirms posted %d transactions, want exactly 1", count)
	}
	if balance := occurrenceAccountBalance(t, uid, seed.account.AccountId); balance != 5500 {
		t.Fatalf("balance after concurrent confirms is %d, want 5500", balance)
	}
	item := occurrenceRow(t, uid, template.TemplateId, scheduledUnixTime)
	if item.Status != models.ScheduledOccurrenceConfirmed || item.TransactionId != transactionIds[0] {
		t.Fatalf("occurrence acknowledgement is wrong: %+v", item)
	}
}

func TestScheduledOccurrenceRejectsCrossUserAndNonPendingStates(t *testing.T) {
	setupOccurrenceLedger(t)
	seedOne := insertOccurrenceSeed(t, 1, 10000, 5000)
	seedTwo := insertOccurrenceSeed(t, 2, 20000, 3000)
	template := occurrenceTemplate(2, 8001, seedTwo, models.TRANSACTION_TYPE_EXPENSE, 1200)
	const scheduledUnixTime = int64(1757300000)
	if _, err := ScheduledOccurrences.Enqueue(nil, template, scheduledUnixTime); err != nil {
		t.Fatal(err)
	}

	pendingOfUserOne, err := ScheduledOccurrences.List(nil, 1, models.ScheduledOccurrencePending, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(pendingOfUserOne) != 0 {
		t.Fatalf("user 1 sees %d occurrences of user 2", len(pendingOfUserOne))
	}
	pendingOfUserTwo, err := ScheduledOccurrences.List(nil, 2, models.ScheduledOccurrencePending, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(pendingOfUserTwo) != 1 {
		t.Fatalf("user 2 should see exactly 1 pending occurrence, got %d", len(pendingOfUserTwo))
	}

	if _, err := ScheduledOccurrences.Confirm(nil, 1, template.TemplateId, scheduledUnixTime); err == nil {
		t.Fatal("cross-user confirm must fail")
	}
	if count := occurrenceTransactionCount(t, 2); count != 0 {
		t.Fatalf("cross-user attempt posted %d transactions", count)
	}
	if balance := occurrenceAccountBalance(t, 2, seedTwo.account.AccountId); balance != 20000 {
		t.Fatalf("cross-user attempt changed the balance to %d", balance)
	}

	if err := ScheduledOccurrences.SetDismissed(nil, 2, template.TemplateId, scheduledUnixTime, true); err != nil {
		t.Fatal(err)
	}
	if _, err := ScheduledOccurrences.Confirm(nil, 2, template.TemplateId, scheduledUnixTime); err == nil {
		t.Fatal("dismissed occurrence must not confirm")
	}
	if err := ScheduledOccurrences.SetDismissed(nil, 2, template.TemplateId, scheduledUnixTime, true); err != nil {
		t.Fatalf("repeated dismiss must replay: %v", err)
	}
	if err := ScheduledOccurrences.SetDismissed(nil, 2, template.TemplateId, scheduledUnixTime, false); err != nil {
		t.Fatal(err)
	}
	if item := occurrenceRow(t, 2, template.TemplateId, scheduledUnixTime); item.Status != models.ScheduledOccurrencePending {
		t.Fatalf("restore did not return the occurrence to pending: %+v", item)
	}
	if _, err := ScheduledOccurrences.Confirm(nil, 2, template.TemplateId, scheduledUnixTime); err != nil {
		t.Fatal(err)
	}
	if count := occurrenceTransactionCount(t, 2); count != 1 {
		t.Fatalf("restore+confirm posted %d transactions, want 1", count)
	}
	if balance := occurrenceAccountBalance(t, 2, seedTwo.account.AccountId); balance != 18800 {
		t.Fatalf("balance after confirm is %d, want 18800", balance)
	}
	if err := ScheduledOccurrences.SetDismissed(nil, 2, template.TemplateId, scheduledUnixTime, true); err == nil {
		t.Fatal("confirmed occurrence accepted a dismiss")
	}
	if _, err := ScheduledOccurrences.Confirm(nil, 1, template.TemplateId, scheduledUnixTime); err == nil {
		t.Fatal("cross-user confirm must fail after posting too")
	}
	_ = seedOne
}

// TestScheduledDispatchQueuesDueTemplateWithoutTouchingBalance runs the real
// cron job body: a due template enters the review queue while the ledger is
// untouched, repeated dispatches stay idempotent, and paused templates never
// reach the queue.
func TestScheduledDispatchQueuesDueTemplateWithoutTouchingBalance(t *testing.T) {
	setupOccurrenceLedger(t)
	const uid = int64(1)
	seed := insertOccurrenceSeed(t, uid, 12000, 5000)

	// Replicate the dispatch window calculation of CreateScheduledTransactions.
	currentTime := time.Now()
	intervalMinute := 15
	currentMinute := (currentTime.Minute() / intervalMinute) * intervalMinute
	startTime := time.Date(currentTime.Year(), currentTime.Month(), currentTime.Day(), currentTime.Hour(), currentMinute, 0, 0, time.Local)
	startTimeInUTC := startTime.In(time.UTC)
	scheduledAt := int16(startTimeInUTC.Hour()*60 + startTimeInUTC.Minute())

	dueTemplate := &models.TransactionTemplate{TemplateId: 9001, Uid: uid, TemplateType: models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE,
		Type: models.TRANSACTION_TYPE_EXPENSE, CategoryId: seed.categories[models.CATEGORY_TYPE_EXPENSE].CategoryId,
		AccountId: seed.account.AccountId, ScheduledFrequencyType: models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY,
		ScheduledFrequency: "1", ScheduledAt: scheduledAt, Amount: 6000, Comment: "due rent"}
	if err := TransactionTemplates.CreateTemplate(nil, dueTemplate); err != nil {
		t.Fatal(err)
	}
	pausedTemplate := *dueTemplate
	pausedTemplate.TemplateId = 9002
	pausedTemplate.ScheduledFrequencyType = models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DISABLED
	if err := TransactionTemplates.CreateTemplate(nil, &pausedTemplate); err != nil {
		t.Fatal(err)
	}

	if err := Transactions.CreateScheduledTransactions(nil, time.Now().Unix(), 15*time.Minute); err != nil {
		t.Fatal(err)
	}

	pending, err := ScheduledOccurrences.List(nil, uid, models.ScheduledOccurrencePending, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 1 {
		t.Fatalf("cron dispatch should queue exactly 1 occurrence, got %d", len(pending))
	}
	snapshot := occurrenceSnapshot(t, pending[0])
	if snapshot.SourceAmount != 6000 || snapshot.SourceAccountId != seed.account.AccountId {
		t.Fatalf("dispatched snapshot deviates from the template: %+v", snapshot)
	}
	if count := occurrenceTransactionCount(t, uid); count != 0 {
		t.Fatalf("dispatch posted %d transactions", count)
	}
	if balance := occurrenceAccountBalance(t, uid, seed.account.AccountId); balance != 12000 {
		t.Fatalf("balance after dispatch is %d, want 12000", balance)
	}

	// The next tick re-processes the same template without duplicating.
	if err := Transactions.CreateScheduledTransactions(nil, time.Now().Unix(), 15*time.Minute); err != nil {
		t.Fatal(err)
	}
	pendingAgain, err := ScheduledOccurrences.List(nil, uid, models.ScheduledOccurrencePending, 0, 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(pendingAgain) != 1 || pendingAgain[0].ScheduledUnixTime != pending[0].ScheduledUnixTime {
		t.Fatalf("repeated dispatch duplicated the occurrence: %d items", len(pendingAgain))
	}

	if _, err := ScheduledOccurrences.Confirm(nil, uid, dueTemplate.TemplateId, pending[0].ScheduledUnixTime); err != nil {
		t.Fatal(err)
	}
	if count := occurrenceTransactionCount(t, uid); count != 1 {
		t.Fatalf("confirm posted %d transactions, want 1", count)
	}
	if balance := occurrenceAccountBalance(t, uid, seed.account.AccountId); balance != 6000 {
		t.Fatalf("balance after confirm is %d, want 6000", balance)
	}
}

// TestScheduledDispatchFrequencyBoundaries drives the cron body with simulated
// times to pin down month-end, leap-day and timezone behaviour: monthly day 29
// posts only on real Feb 29, day 31 only in 31-day months, yearly 02-29 only in
// leap years, and the day check follows the template's own timezone.
func TestScheduledDispatchFrequencyBoundaries(t *testing.T) {
	setupOccurrenceLedger(t)
	const uid = int64(1)
	seed := insertOccurrenceSeed(t, uid, 10000, 5000)

	makeTemplate := func(id int64, freqType models.TransactionScheduleFrequencyType, freq string, tzOffset int16) *models.TransactionTemplate {
		template := &models.TransactionTemplate{TemplateId: id, Uid: uid, TemplateType: models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE,
			Type: models.TRANSACTION_TYPE_EXPENSE, CategoryId: seed.categories[models.CATEGORY_TYPE_EXPENSE].CategoryId,
			AccountId: seed.account.AccountId, ScheduledFrequencyType: freqType, ScheduledFrequency: freq,
			ScheduledAt: 180, ScheduledTimezoneUtcOffset: tzOffset, Amount: 100}
		if err := TransactionTemplates.CreateTemplate(nil, template); err != nil {
			t.Fatal(err)
		}
		return template
	}
	leapMonthly := makeTemplate(9101, models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "29", 0)
	makeTemplate(9102, models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "30", 0)
	leapYearly := makeTemplate(9103, models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "229", 0)
	makeTemplate(9104, models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "1", 480)
	makeTemplate(9105, models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "31", 0)

	runAt := func(year int, month time.Month, day int) {
		simulated := time.Date(year, month, day, 3, 0, 0, 0, time.UTC).Unix()
		if err := Transactions.CreateScheduledTransactions(nil, simulated, 15*time.Minute); err != nil {
			t.Fatal(err)
		}
	}
	expectPending := func(want int, scenario string) {
		t.Helper()
		pending, err := ScheduledOccurrences.List(nil, uid, models.ScheduledOccurrencePending, 0, 100)
		if err != nil {
			t.Fatal(err)
		}
		if len(pending) != want {
			t.Fatalf("%s: expected %d pending occurrences, got %d", scenario, want, len(pending))
		}
	}

	// 2024-02-29 03:00 UTC: leap day. Day 29 posts, day 30 does not, yearly
	// 02-29 posts, and in UTC+8 it is still Feb 29 so day 1 does not post.
	runAt(2024, time.February, 29)
	expectPending(2, "leap day")
	for _, item := range mustPending(t, uid, 2) {
		if item.TemplateId != leapMonthly.TemplateId && item.TemplateId != leapYearly.TemplateId {
			t.Fatalf("unexpected template %d dispatched on leap day", item.TemplateId)
		}
	}

	// 2024-03-01 03:00 UTC = 2024-03-01 11:00 UTC+8: day 1 posts only in the
	// UTC+8 template; day 29 and yearly 02-29 are already handled.
	runAt(2024, time.March, 1)
	expectPending(3, "march 1st")

	// 2024-03-31 03:00 UTC: day 31 posts, day 30 does not.
	runAt(2024, time.March, 31)
	expectPending(4, "month end")

	// 2025-02-28 03:00 UTC: no leap day, so day 29 and yearly 02-29 stay quiet.
	runAt(2025, time.February, 28)
	expectPending(4, "non-leap february")

	if balance := occurrenceAccountBalance(t, uid, seed.account.AccountId); balance != 10000 {
		t.Fatalf("dispatch changed the balance to %d", balance)
	}
}

func mustPending(t *testing.T, uid int64, count int) []*models.ScheduledOccurrence {
	t.Helper()
	pending, err := ScheduledOccurrences.List(nil, uid, models.ScheduledOccurrencePending, 0, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != count {
		t.Fatalf("expected %d pending occurrences, got %d", count, len(pending))
	}
	return pending
}

func TestScheduledOccurrenceTransferAndCorruptSnapshot(t *testing.T) {
	setupOccurrenceLedger(t)
	const uid = int64(1)
	seed := insertOccurrenceSeed(t, uid, 5000, 2000)
	template := occurrenceTemplate(uid, 7003, seed, models.TRANSACTION_TYPE_TRANSFER, 1000)
	const scheduledUnixTime = int64(1757400000)
	if _, err := ScheduledOccurrences.Enqueue(nil, template, scheduledUnixTime); err != nil {
		t.Fatal(err)
	}

	transactionId, err := ScheduledOccurrences.Confirm(nil, uid, template.TemplateId, scheduledUnixTime)
	if err != nil {
		t.Fatal(err)
	}
	outRow := &models.Transaction{}
	if _, err := occurrenceDB(t).NewSession(nil).ID(transactionId).Get(outRow); err != nil || outRow.TransactionId != transactionId {
		t.Fatalf("transfer out row missing: %v", err)
	}
	if outRow.Type != models.TRANSACTION_DB_TYPE_TRANSFER_OUT || outRow.Amount != 1000 ||
		outRow.AccountId != seed.account.AccountId || outRow.RelatedAccountId != seed.other.AccountId {
		t.Fatalf("transfer out row deviates from the snapshot: %+v", outRow)
	}
	rows := make([]*models.Transaction, 0)
	if err := occurrenceDB(t).NewSession(nil).Where("uid=?", uid).Find(&rows); err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 {
		t.Fatalf("transfer confirm posted %d rows, want out+in pair", len(rows))
	}
	var inRow *models.Transaction
	for _, row := range rows {
		if row.TransactionId != transactionId {
			inRow = row
		}
	}
	if inRow == nil || inRow.Type != models.TRANSACTION_DB_TYPE_TRANSFER_IN || inRow.Amount != 900 ||
		inRow.AccountId != seed.other.AccountId || inRow.RelatedAccountId != seed.account.AccountId ||
		inRow.RelatedId != outRow.TransactionId {
		t.Fatalf("transfer in row deviates: %+v", inRow)
	}
	if balance := occurrenceAccountBalance(t, uid, seed.account.AccountId); balance != 4000 {
		t.Fatalf("source balance is %d, want 4000", balance)
	}
	if balance := occurrenceAccountBalance(t, uid, seed.other.AccountId); balance != 2900 {
		t.Fatalf("destination balance is %d, want 2900", balance)
	}

	// A persisted snapshot that no longer satisfies the validation rules must
	// be rejected before any ledger write, and the occurrence stays pending.
	broken := occurrenceTemplate(uid, 7004, seed, models.TRANSACTION_TYPE_EXPENSE, 100)
	const brokenTime = int64(1757500000)
	if _, err := ScheduledOccurrences.Enqueue(nil, broken, brokenTime); err != nil {
		t.Fatal(err)
	}
	corruptSnapshots := []string{
		`{"type":9,"categoryId":"11","sourceAccountId":"1","sourceAmount":100}`,
		`{"type":3,"categoryId":"11","sourceAccountId":"1","sourceAmount":99999999999999}`,
		`{"type":3,"categoryId":"11","sourceAccountId":"1","sourceAmount":100,"destinationAccountId":"2","destinationAmount":100}`,
	}
	for _, corrupt := range corruptSnapshots {
		session := occurrenceDB(t).NewSession(nil)
		// xorm's SnakeMapper maps SnapshotJSON to the snapshot_j_s_o_n column.
		if _, err := session.Where("uid=? AND template_id=? AND scheduled_unix_time=?", uid, 7004, brokenTime).
			Cols("status", "snapshot_j_s_o_n").Update(&models.ScheduledOccurrence{Status: models.ScheduledOccurrencePending, SnapshotJSON: corrupt}); err != nil {
			t.Fatal(err)
		}
		session.Close()
		if _, err := ScheduledOccurrences.Confirm(nil, uid, 7004, brokenTime); err == nil {
			t.Fatalf("corrupt snapshot accepted: %s", corrupt)
		}
		if count := occurrenceTransactionCount(t, uid); count != 2 {
			t.Fatalf("corrupt snapshot posted a transaction: %s", corrupt)
		}
		item := occurrenceRow(t, uid, 7004, brokenTime)
		if item.Status != models.ScheduledOccurrencePending {
			t.Fatalf("rejected snapshot changed the occurrence state: %+v", item)
		}
	}
}
