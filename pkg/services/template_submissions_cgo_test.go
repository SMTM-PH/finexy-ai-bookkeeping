//go:build cgo

package services

import (
	"strings"
	"sync"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
)

func setupTemplateSubmissions(t *testing.T, connections int) *occurrenceSeed {
	t.Helper()
	seed := setupPeriodicScopeWithConnections(t, connections)
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(models.TemplateSubmission)))
	return seed
}
func submissionTemplate(seed *occurrenceSeed, actor, ledger int64, kind models.TransactionTemplateType) *models.TransactionTemplate {
	row := periodicTemplate(seed, actor, ledger)
	row.TemplateType = kind
	if kind == models.TRANSACTION_TEMPLATE_TYPE_NORMAL {
		row.ScheduledFrequencyType, row.ScheduledFrequency, row.ScheduledAt, row.ScheduledTimezoneUtcOffset = 0, "", 0, 0
	}
	return row
}
func templateSubmissionCount(t *testing.T) int64 {
	t.Helper()
	sess := occurrenceDB(t).NewSession(nil)
	defer sess.Close()
	count, err := sess.Count(new(models.TemplateSubmission))
	require.NoError(t, err)
	return count
}

func TestTemplateSubmissionPersistentReplayModificationAndDeletion(t *testing.T) {
	for _, kind := range []models.TransactionTemplateType{models.TRANSACTION_TEMPLATE_TYPE_NORMAL, models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE} {
		t.Run(map[models.TransactionTemplateType]string{1: "normal", 2: "scheduled"}[kind], func(t *testing.T) {
			seed := setupTemplateSubmissions(t, 1)
			first := submissionTemplate(seed, 2, 11, kind)
			require.NoError(t, TransactionTemplates.CreateTemplateIdempotent(nil, 2, first, "persistent-key"))
			require.Equal(t, int64(1), first.Uid)
			fresh := &TransactionTemplateService{ServiceUsingDB: TransactionTemplates.ServiceUsingDB, ServiceUsingUuid: TransactionTemplates.ServiceUsingUuid}
			retry := submissionTemplate(seed, 2, 11, kind)
			retry.DisplayOrder = 999
			require.NoError(t, fresh.CreateTemplateIdempotent(nil, 2, retry, "persistent-key"))
			require.Equal(t, first.TemplateId, retry.TemplateId)
			require.Equal(t, first.CreatedUnixTime, retry.CreatedUnixTime)
			edit := *first
			edit.Name, edit.Amount = "Modified after creation", 900
			require.NoError(t, fresh.ModifyTemplateInLedger(nil, 2, &edit))
			retry = submissionTemplate(seed, 2, 11, kind)
			require.NoError(t, fresh.CreateTemplateIdempotent(nil, 2, retry, "persistent-key"))
			require.Equal(t, first.TemplateId, retry.TemplateId)
			require.Equal(t, edit.Name, retry.Name)
			require.Equal(t, int64(900), retry.Amount)
			require.NoError(t, fresh.DeleteTemplateInLedger(nil, 2, 11, first.TemplateId))
			retry = submissionTemplate(seed, 2, 11, kind)
			require.NoError(t, fresh.CreateTemplateIdempotent(nil, 2, retry, "persistent-key"))
			require.Equal(t, first.TemplateId, retry.TemplateId)
			require.True(t, retry.Deleted)
			response := retry.ToTransactionTemplateInfoResponse(0)
			require.True(t, response.Deleted)
			require.Nil(t, response.NextScheduledTime)
			rows, err := fresh.GetAllTemplatesInLedger(nil, 1, 11, kind)
			require.NoError(t, err)
			require.Empty(t, rows)
			require.Equal(t, int64(1), templateSubmissionCount(t))
			require.Zero(t, occurrenceTransactionCount(t, 1))
			require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, seed.account.AccountId))
		})
	}
}

func TestTemplateSubmissionScopesAndLivePermissions(t *testing.T) {
	seed := setupTemplateSubmissions(t, 1)
	create := func(actor, ledger, account int64) *models.TransactionTemplate {
		row := submissionTemplate(seed, actor, ledger, models.TRANSACTION_TEMPLATE_TYPE_NORMAL)
		row.AccountId = account
		require.NoError(t, TransactionTemplates.CreateTemplateIdempotent(nil, actor, row, "same-key"))
		return row
	}
	first := create(1, 11, seed.account.AccountId)
	admin := create(2, 11, seed.account.AccountId)
	other := create(1, 12, 100004)
	personal := create(1, 0, 100003)
	require.NotEqual(t, first.TemplateId, admin.TemplateId)
	require.NotEqual(t, first.TemplateId, other.TemplateId)
	require.NotEqual(t, first.TemplateId, personal.TemplateId)
	require.Equal(t, int64(4), templateSubmissionCount(t))
	for _, actor := range []int64{3, 4} {
		require.ErrorIs(t, TransactionTemplates.CreateTemplateIdempotent(nil, actor, submissionTemplate(seed, actor, 11, 1), "same-key"), errs.ErrLedgerAccessDenied)
	}
	sess := occurrenceDB(t).NewSession(nil)
	defer sess.Close()
	_, err := sess.Where("ledger_id=? AND uid=?", 11, 2).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
	require.NoError(t, err)
	require.ErrorIs(t, TransactionTemplates.CreateTemplateIdempotent(nil, 2, submissionTemplate(seed, 2, 11, 1), "same-key"), errs.ErrLedgerAccessDenied)
	_, err = sess.Where("ledger_id=?", 11).Cols("deleted").Update(&models.Ledger{Deleted: true})
	require.NoError(t, err)
	require.ErrorIs(t, TransactionTemplates.CreateTemplateIdempotent(nil, 1, submissionTemplate(seed, 1, 11, 1), "same-key"), errs.ErrLedgerNotFound)
	require.Equal(t, int64(4), templateSubmissionCount(t))
}

func TestTemplateSubmissionConcurrentConnectionsAndPayloadConflict(t *testing.T) {
	seed := setupTemplateSubmissions(t, 8)
	var group sync.WaitGroup
	results := make(chan *models.TransactionTemplate, 8)
	errors := make(chan error, 8)
	for i := 0; i < 8; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			service := &TransactionTemplateService{ServiceUsingDB: TransactionTemplates.ServiceUsingDB, ServiceUsingUuid: TransactionTemplates.ServiceUsingUuid}
			row := submissionTemplate(seed, 2, 11, 2)
			errors <- service.CreateTemplateIdempotent(nil, 2, row, "concurrent-key")
			results <- row
		}()
	}
	group.Wait()
	close(results)
	close(errors)
	for err := range errors {
		require.NoError(t, err)
	}
	var expected int64
	for row := range results {
		if expected == 0 {
			expected = row.TemplateId
		}
		require.Equal(t, expected, row.TemplateId)
	}
	for _, change := range []func(*models.TransactionTemplate){
		func(r *models.TransactionTemplate) { r.Amount++ },
		func(r *models.TransactionTemplate) { r.Comment = "changed" },
		func(r *models.TransactionTemplate) { r.HideAmount = !r.HideAmount },
		func(r *models.TransactionTemplate) { r.ScheduledFrequency = "0" },
		func(r *models.TransactionTemplate) { r.TemplateType = 1 },
	} {
		row := submissionTemplate(seed, 2, 11, 2)
		change(row)
		require.ErrorIs(t, TransactionTemplates.CreateTemplateIdempotent(nil, 2, row, "concurrent-key"), errs.ErrParameterInvalid)
	}
	require.Equal(t, int64(1), templateSubmissionCount(t))
	rows, err := TransactionTemplates.GetAllTemplatesInLedger(nil, 1, 11, 2)
	require.NoError(t, err)
	require.Len(t, rows, 1)
}

func TestTemplateSubmissionRollbackAndLegacyCompatibility(t *testing.T) {
	seed := setupTemplateSubmissions(t, 1)
	bad := submissionTemplate(seed, 1, 11, 1)
	bad.CategoryId = 999999
	require.Error(t, TransactionTemplates.CreateTemplateIdempotent(nil, 1, bad, "rollback-key"))
	require.Zero(t, templateSubmissionCount(t))
	sess := occurrenceDB(t).NewSession(nil)
	defer sess.Close()
	_, err := sess.Exec("CREATE TRIGGER reject_template_insert BEFORE INSERT ON transaction_template BEGIN SELECT RAISE(ABORT, 'synthetic insert failure'); END")
	require.NoError(t, err)
	require.Error(t, TransactionTemplates.CreateTemplateIdempotent(nil, 1, submissionTemplate(seed, 1, 11, 1), "rollback-key"))
	require.Zero(t, templateSubmissionCount(t), "Template insert failure must also roll back the already inserted receipt")
	_, err = sess.Exec("DROP TRIGGER reject_template_insert")
	require.NoError(t, err)
	require.NoError(t, TransactionTemplates.CreateTemplateIdempotent(nil, 1, submissionTemplate(seed, 1, 11, 1), "rollback-key"))
	for i := 0; i < 2; i++ {
		require.NoError(t, TransactionTemplates.CreateTemplateInLedger(nil, 1, submissionTemplate(seed, 1, 11, 1)))
	}
	rows, err := TransactionTemplates.GetAllTemplatesInLedger(nil, 1, 11, 1)
	require.NoError(t, err)
	require.Len(t, rows, 3)
	require.Equal(t, int64(1), templateSubmissionCount(t))
	for _, key := range []string{strings.Repeat("x", 129), " "} {
		require.ErrorIs(t, TransactionTemplates.CreateTemplateIdempotent(nil, 1, submissionTemplate(seed, 1, 11, 1), key), errs.ErrParameterInvalid)
	}
	var receipt models.TemplateSubmission
	found, err := sess.Where("client_session_id=?", "rollback-key").Get(&receipt)
	require.NoError(t, err)
	require.True(t, found)
	_, err = sess.Insert(&receipt)
	require.Error(t, err, "The durable composite primary key must reject duplicate receipts")
}
