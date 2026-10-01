//go:build cgo

package services

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/gin-gonic/gin"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
	"xorm.io/xorm"
)

func agentTestContext(uid int64, token string) *core.WebContext {
	g, _ := gin.CreateTestContext(httptest.NewRecorder())
	c := &core.WebContext{Context: g}
	c.SetTokenClaims(&core.UserTokenClaims{Uid: uid, Type: core.USER_TOKEN_TYPE_MCP, UserTokenId: token, IssuedAt: 1, AgentScopes: []string{core.AgentScopeRead, core.AgentScopeImport}})
	return c
}

func agentTestSetup(t *testing.T) *occurrenceSeed {
	setupOccurrenceLedger(t)
	if err := datastore.Container.UserDataStore.SyncStructs(new(models.AgentImportBatch), new(models.AgentImportFingerprint)); err != nil {
		t.Fatal(err)
	}
	return insertOccurrenceSeed(t, 1, 10000, 0)
}

func agentTestRows() []*models.ImportTransactionResponse {
	return []*models.ImportTransactionResponse{{Type: models.TRANSACTION_TYPE_EXPENSE, Time: time.Now().UTC().Truncate(24 * time.Hour).Unix(), UtcOffset: 480, SourceAmount: 123, Comment: "synthetic agent import"}}
}

func agentTestMapped(t *testing.T, seed *occurrenceSeed) *models.AgentImportPreview {
	t.Helper()
	c := agentTestContext(seed.uid, "token-a")
	p, err := AgentImports.createPreview(c, seed.uid, "test", agentTestRows())
	if err != nil {
		t.Fatal(err)
	}
	if occurrenceAccountBalance(t, 1, seed.account.AccountId) != 10000 || occurrenceTransactionCount(t, 1) != 0 {
		t.Fatal("preview changed ledger")
	}
	p, err = AgentImports.Map(c, 1, models.AgentImportMapRequest{BatchId: p.BatchId, PreviewHash: p.PreviewHash, Mappings: []models.AgentImportMapping{{Row: 0, Selected: true, SourceAccountId: seed.account.AccountId, CategoryId: seed.categories[models.CATEGORY_TYPE_EXPENSE].CategoryId}}})
	if err != nil {
		t.Fatal(err)
	}
	if p.ReadyCount != 1 || p.ExpenseAmount != 123 {
		t.Fatalf("mapping not ready: %v", p.Rows[0].Issue)
	}
	return p
}

func TestAgentImportConfirmationIsBoundToPreviewAndToken(t *testing.T) {
	seed := agentTestSetup(t)
	p := agentTestMapped(t, seed)
	user := &models.User{Uid: 1, TransactionEditScope: models.TRANSACTION_EDIT_SCOPE_ALL}
	config := &settings.Config{EnableDataImport: true}
	req := models.AgentImportConfirmRequest{BatchId: p.BatchId, PreviewHash: p.PreviewHash, Confirmed: true}
	if _, err := AgentImports.Confirm(agentTestContext(2, "token-a"), &models.User{Uid: 2}, config, req); err == nil {
		t.Fatal("cross-user confirm")
	}
	if _, err := AgentImports.Confirm(agentTestContext(1, "token-b"), user, config, req); err == nil {
		t.Fatal("cross-token confirm")
	}
	bad := req
	bad.Confirmed = false
	if _, err := AgentImports.Confirm(agentTestContext(1, "token-a"), user, config, bad); err == nil {
		t.Fatal("no explicit confirmation")
	}
	bad = req
	bad.PreviewHash = "stale"
	if _, err := AgentImports.Confirm(agentTestContext(1, "token-a"), user, config, bad); err == nil {
		t.Fatal("stale preview accepted")
	}
	expired, err := AgentImports.createPreview(agentTestContext(1, "token-a"), 1, "test", agentTestRows())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := occurrenceDB(t).NewSession(nil).Where("uid=? AND batch_id=?", 1, expired.BatchId).Cols("expires_at").Update(&models.AgentImportBatch{ExpiresAt: time.Now().Unix() - 1}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := AgentImports.load(agentTestContext(1, "token-a"), 1, expired.BatchId); err == nil {
		t.Fatal("expired preview accepted")
	}
	var wg sync.WaitGroup
	errors := make(chan error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			count, err := AgentImports.Confirm(agentTestContext(1, "token-a"), user, config, req)
			if err == nil && count != 1 {
				t.Error("wrong count")
			}
			errors <- err
		}()
	}
	wg.Wait()
	close(errors)
	for err := range errors {
		if err != nil {
			t.Fatal(err)
		}
	}
	if occurrenceTransactionCount(t, 1) != 1 || occurrenceAccountBalance(t, 1, seed.account.AccountId) != 9877 {
		t.Fatal("concurrent confirmation duplicated balance impact")
	}
	p2, err := AgentImports.createPreview(agentTestContext(1, "token-a"), 1, "test", agentTestRows())
	if err != nil {
		t.Fatal(err)
	}
	if p2.DuplicateCount != 1 {
		t.Fatal("reimport not detected")
	}
}

func TestAgentImportFailureRollsBackClaimAndFingerprints(t *testing.T) {
	seed := agentTestSetup(t)
	p := agentTestMapped(t, seed)
	// Invalid source/category state at confirmation must leave preview pending and balances intact.
	_, err := occurrenceDB(t).NewSession(nil).Where("uid=? AND account_id=?", 1, seed.account.AccountId).Cols("hidden").Update(&models.Account{Hidden: true})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := AgentImports.Confirm(agentTestContext(1, "token-a"), &models.User{Uid: 1, TransactionEditScope: models.TRANSACTION_EDIT_SCOPE_ALL}, &settings.Config{EnableDataImport: true}, models.AgentImportConfirmRequest{BatchId: p.BatchId, PreviewHash: p.PreviewHash, Confirmed: true}); err == nil {
		t.Fatal("hidden account accepted")
	}
	batch, _, err := AgentImports.load(agentTestContext(1, "token-a"), 1, p.BatchId)
	if err != nil || batch.Status != 0 || occurrenceAccountBalance(t, 1, seed.account.AccountId) != 10000 {
		t.Fatal("failed confirmation changed data")
	}
	count, err := occurrenceDB(t).NewSession(nil).Count(new(models.AgentImportFingerprint))
	if err != nil || count != 0 {
		t.Fatal("failed confirmation saved dedupe marker")
	}
}

func TestAgentImportDatabaseFailureRollsBackWholeBatch(t *testing.T) {
	seed := agentTestSetup(t)
	p := agentTestMapped(t, seed)
	// Abort the actual transaction insert after the batch claim and fingerprint insert.
	table := occurrenceDB(t).NewSession(nil).Engine().TableName(new(models.Transaction))
	if _, err := occurrenceDB(t).NewSession(nil).Exec("CREATE TRIGGER agent_import_abort BEFORE INSERT ON `" + table + "` BEGIN SELECT RAISE(ABORT, 'synthetic rollback'); END"); err != nil {
		t.Fatal(err)
	}
	req := models.AgentImportConfirmRequest{BatchId: p.BatchId, PreviewHash: p.PreviewHash, Confirmed: true}
	if _, err := AgentImports.Confirm(agentTestContext(1, "token-a"), &models.User{Uid: 1, TransactionEditScope: models.TRANSACTION_EDIT_SCOPE_ALL}, &settings.Config{EnableDataImport: true}, req); err == nil {
		t.Fatal("database failure accepted")
	}
	batch, _, err := AgentImports.load(agentTestContext(1, "token-a"), 1, p.BatchId)
	if err != nil || batch.Status != 0 || occurrenceTransactionCount(t, 1) != 0 || occurrenceAccountBalance(t, 1, seed.account.AccountId) != 10000 {
		t.Fatal("database failure partially committed")
	}
	count, err := occurrenceDB(t).NewSession(nil).Count(new(models.AgentImportFingerprint))
	if err != nil || count != 0 {
		t.Fatal("database failure saved dedupe marker")
	}
}

func TestAgentImportSelectedLedgerAndSharedRecorder(t *testing.T) {
	seed := agentTestSetup(t)
	if err := datastore.Container.UserDataStore.SyncStructs(new(models.Ledger), new(models.LedgerMember)); err != nil {
		t.Fatal(err)
	}
	db := occurrenceDB(t)
	ledger := &models.Ledger{LedgerId: 900, OwnerUid: 1, Type: models.LEDGER_TYPE_PERSONAL, Name: "synthetic shared ledger"}
	if _, err := db.NewSession(nil).Insert(ledger); err != nil {
		t.Fatal(err)
	}
	member := &models.LedgerMember{LedgerMemberId: 901, LedgerId: 900, Uid: 2, Role: models.FAMILY_MEMBER_ROLE_MEMBER, Status: models.FAMILY_MEMBER_STATUS_ACTIVE}
	if _, err := db.NewSession(nil).Insert(member); err != nil {
		t.Fatal(err)
	}
	if _, err := db.NewSession(nil).ID(seed.account.AccountId).Cols("ledger_id").Update(&models.Account{LedgerId: 900}); err != nil {
		t.Fatal(err)
	}
	c := agentTestContext(2, "member-token")
	c.GetTokenClaims().AgentLedgerId = 900
	preview, err := AgentImports.createPreview(c, 2, "test", agentTestRows(), 900)
	if err != nil {
		t.Fatal(err)
	}
	preview, err = AgentImports.Map(c, 2, models.AgentImportMapRequest{LedgerId: 900, BatchId: preview.BatchId, PreviewHash: preview.PreviewHash, Mappings: []models.AgentImportMapping{{Row: 0, Selected: true, SourceAccountId: seed.account.AccountId, CategoryId: seed.categories[models.CATEGORY_TYPE_EXPENSE].CategoryId}}})
	if err != nil {
		t.Fatal(err)
	}
	req := models.AgentImportConfirmRequest{LedgerId: 900, BatchId: preview.BatchId, PreviewHash: preview.PreviewHash, Confirmed: true}
	user := &models.User{Uid: 2, TransactionEditScope: models.TRANSACTION_EDIT_SCOPE_ALL}
	config := &settings.Config{EnableDataImport: true}
	if _, err := AgentImports.Confirm(c, user, config, req); err != nil {
		t.Fatal(err)
	}
	var rows []*models.Transaction
	if err := db.NewSession(nil).Where("type=?", models.TRANSACTION_DB_TYPE_EXPENSE).Find(&rows); err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].Uid != 1 || rows[0].LedgerId != 900 || rows[0].RecorderUid != 2 || rows[0].PayerUid != 2 {
		t.Fatal("ledger owner/actor attribution was lost")
	}
	// Revocation is also enforced within the transaction, not only before it.
	if _, err := db.NewSession(nil).ID(member.LedgerMemberId).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER}); err != nil {
		t.Fatal(err)
	}
	if err := db.DoTransaction(c, func(sess *xorm.Session) error { return authorizeImportLedger(sess, 2, ledger) }); err == nil {
		t.Fatal("revoked member passed commit authorization")
	}
}
