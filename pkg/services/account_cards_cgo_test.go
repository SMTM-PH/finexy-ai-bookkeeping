//go:build cgo

package services

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"testing"
)

func TestAccountCardPersistenceIsolationAndConflict(t *testing.T) {
	setupOccurrenceLedger(t)
	if err := datastore.Container.UserDataStore.SyncStructs(new(models.AccountCardAppearance)); err != nil {
		t.Fatal(err)
	}
	seed := insertOccurrenceSeed(t, 1, 10000, 0)
	c := agentTestContext(1, "card")
	c.SetTokenClaims(&core.UserTokenClaims{Uid: 1, Type: core.USER_TOKEN_TYPE_NORMAL})
	style, err := AccountCards.Get(c, 1, 0, seed.account.AccountId)
	if err != nil || style.Revision != 0 {
		t.Fatal("default", err)
	}
	style.Background = cardTestImage()
	style.Subject = style.Background
	style.Theme = "mint"
	saved, err := AccountCards.Save(c, 1, 0, seed.account.AccountId, *style)
	if err != nil || saved.Revision != 1 {
		t.Fatal("save", err)
	}
	loaded, err := AccountCards.Get(c, 1, 0, seed.account.AccountId)
	if err != nil || *loaded != *saved {
		t.Fatal("roundtrip", err)
	}
	if _, err = AccountCards.Save(c, 1, 0, seed.account.AccountId, *style); err != errs.ErrAccountCardConflict {
		t.Fatal("stale initial insert accepted", err)
	}
	reset := models.DefaultAccountCardStyle()
	reset.Revision = saved.Revision
	if _, err = AccountCards.Save(c, 1, 0, seed.account.AccountId, reset); err != nil {
		t.Fatal(err)
	}
	loaded, err = AccountCards.Get(c, 1, 0, seed.account.AccountId)
	if err != nil || loaded.Theme != "classic" || loaded.Background != "" || loaded.Subject != "" || loaded.Revision != 2 {
		t.Fatal("update did not replace persisted appearance", err)
	}
	if _, err = AccountCards.Save(c, 1, 0, seed.account.AccountId, *saved); err != errs.ErrAccountCardConflict {
		t.Fatal("stale update accepted", err)
	}
	if _, err = AccountCards.Get(nil, 2, 0, seed.account.AccountId); err == nil {
		t.Fatal("cross-user read")
	}
	if _, err = AccountCards.Save(nil, 2, 0, seed.account.AccountId, reset); err == nil {
		t.Fatal("cross-user write")
	}
	if _, err = AccountCards.Get(c, 1, 999, seed.account.AccountId); err == nil {
		t.Fatal("wrong ledger")
	}
	if occurrenceAccountBalance(t, 1, seed.account.AccountId) != 10000 || occurrenceTransactionCount(t, 1) != 0 {
		t.Fatal("appearance changed financial data")
	}
}
