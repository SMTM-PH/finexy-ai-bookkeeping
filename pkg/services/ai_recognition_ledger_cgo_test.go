//go:build cgo

package services

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/llm"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/stretchr/testify/require"
)

func TestAIRecognitionLedgerScopeRolesDefaultAndAmbiguousNames(t *testing.T) {
	seed := setupAIReview(t)
	for _, actor := range []int64{1, 2, 3, 4} {
		data, err := AIRecognition.GetLedgerEssentialData(nil, actor, 11)
		require.NoError(t, err)
		require.Len(t, data.Accounts, 2)
		require.Equal(t, seed.account.AccountId, data.Accounts["Main"].AccountId)
		require.Equal(t, seed.categories[models.CATEGORY_TYPE_EXPENSE].CategoryId, data.ExpenseCategories["Leaf"].CategoryId)
		if actor == 1 {
			require.Contains(t, data.Tags, "Synthetic owner tag")
		} else {
			require.Empty(t, data.Tags)
		}
	}
	for _, ledger := range []int64{0, 12} {
		data, err := AIRecognition.GetLedgerEssentialData(nil, 1, ledger)
		require.NoError(t, err)
		require.Len(t, data.Accounts, 1)
		require.Equal(t, ledger, data.Accounts["Main"].LedgerId)
	}
	_, err := AIRecognition.GetLedgerEssentialData(nil, 9, 11)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	_, err = AIRecognition.GetLedgerEssentialData(nil, 1, -1)
	require.Error(t, err)
	db := occurrenceDB(t)
	_, err = db.NewSession(nil).Insert(&models.Account{AccountId: 100020, Uid: 1, LedgerId: 11, Type: models.ACCOUNT_TYPE_SINGLE_ACCOUNT, Name: "Main", Currency: "USD"}, &models.TransactionCategory{CategoryId: 100030, Uid: 1, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: seed.categories[models.CATEGORY_TYPE_EXPENSE].ParentCategoryId, Name: "Leaf"})
	require.NoError(t, err)
	data, err := AIRecognition.GetLedgerEssentialData(nil, 3, 11)
	require.NoError(t, err)
	require.NotContains(t, data.Accounts, "Main")
	require.NotContains(t, data.ExpenseCategories, "Leaf")
	response, err := data.Parse(&models.RecognizedTransactionResult{Type: "expense", AccountName: "Main", CategoryName: "Leaf", Amount: "5.00", TagNames: []string{"Synthetic owner tag"}}, time.UTC)
	require.NoError(t, err)
	require.Zero(t, response.SourceAccountId)
	require.Zero(t, response.CategoryId)
	require.Empty(t, response.TagIds)
	require.Equal(t, int64(11), response.LedgerId)
}

func TestAIRecognitionRejectsHiddenMalformedParentsAndRevocation(t *testing.T) {
	seed := setupAIReview(t)
	db := occurrenceDB(t)
	_, err := db.NewSession(nil).Insert(&models.Account{AccountId: 100020, Uid: 1, LedgerId: 12, Type: models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS, Name: "Other ledger parent"})
	require.NoError(t, err)
	_, err = db.NewSession(nil).ID(seed.account.AccountId).Cols("parent_account_id").Update(&models.Account{ParentAccountId: 100020})
	require.NoError(t, err)
	_, err = db.NewSession(nil).ID(seed.categories[models.CATEGORY_TYPE_EXPENSE].ParentCategoryId).Cols("hidden").Update(&models.TransactionCategory{Hidden: true})
	require.NoError(t, err)
	data, err := AIRecognition.GetLedgerEssentialData(nil, 3, 11)
	require.NoError(t, err)
	require.NotContains(t, data.Accounts, "Main")
	require.Empty(t, data.ExpenseCategories)
	_, err = db.NewSession(nil).Where("ledger_id=? AND uid=?", 11, 3).Cols("status").Update(&models.LedgerMember{Status: models.FAMILY_MEMBER_STATUS_REMOVED})
	require.NoError(t, err)
	require.ErrorIs(t, data.Recheck(nil), errs.ErrLedgerAccessDenied)
}

func TestAIRecognitionReceiptMockScopeUsageAndRevocation(t *testing.T) {
	setupAIReview(t)
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(models.AIUsageRecord)))
	// Production prompt copied before changing to a temporary working directory.
	prompt, err := os.ReadFile("../../templates/prompt/receipt_image_recognition.tmpl")
	require.NoError(t, err)
	dir := t.TempDir()
	require.NoError(t, os.MkdirAll(filepath.Join(dir, "templates/prompt"), 0700))
	require.NoError(t, os.WriteFile(filepath.Join(dir, "templates/prompt/receipt_image_recognition.tmpl"), prompt, 0600))
	t.Chdir(dir)
	var mu sync.Mutex
	calls := 0
	revoke := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, readErr := io.ReadAll(r.Body)
		if readErr != nil {
			t.Error("mock read failed")
			w.WriteHeader(500)
			return
		}
		var request struct {
			Messages []struct {
				Role    string          `json:"role"`
				Content json.RawMessage `json:"content"`
			} `json:"messages"`
		}
		if json.Unmarshal(body, &request) != nil {
			t.Error("invalid mock request")
			w.WriteHeader(400)
			return
		}
		var context string
		for _, message := range request.Messages {
			if message.Role == "system" {
				_ = json.Unmarshal(message.Content, &context)
			}
		}
		if !strings.Contains(context, "Main") || strings.Contains(context, "Synthetic owner tag") {
			t.Error("mock saw incorrect ledger context")
		}
		mu.Lock()
		calls++
		shouldRevoke := revoke
		mu.Unlock()
		if shouldRevoke {
			_, e := occurrenceDB(t).NewSession(nil).Where("ledger_id=? AND uid=?", 11, 3).Cols("status").Update(&models.LedgerMember{Status: models.FAMILY_MEMBER_STATUS_REMOVED})
			if e != nil {
				t.Error("synthetic revoke failed")
			}
		}
		w.Header().Set("Content-Type", "application/json")
		content, _ := json.Marshal(&models.RecognizedTransactionResult{Type: "expense", AccountName: "Main", CategoryName: "Leaf", Amount: "-5.00", TagNames: []string{"Synthetic owner tag"}})
		_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]any{"content": string(content)}}}, "usage": map[string]int{"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}})
	}))
	defer server.Close()
	config := &settings.Config{TransactionFromAIImageRecognition: true, MaxAIRecognitionPictureFileSize: 1024, ReceiptImageRecognitionLLMConfig: &settings.LLMConfig{LLMProvider: settings.OpenAICompatibleLLMProvider, OpenAICompatibleBaseURL: server.URL, OpenAICompatibleModelID: "synthetic", OpenAICompatibleAPIKey: "synthetic", LargeLanguageModelAPIRequestTimeout: 2000, LargeLanguageModelAPIProxy: "none"}}
	require.NoError(t, llm.InitializeLargeLanguageModelProvider(config))
	t.Cleanup(func() { _ = llm.InitializeLargeLanguageModelProvider(&settings.Config{}) })
	user, err := Users.GetUserById(nil, 3)
	require.NoError(t, err)
	const n = 8
	var wg sync.WaitGroup
	failures := make([]error, n)
	responses := make([]*models.RecognizedTransactionResponse, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			responses[i], _, failures[i] = AIRecognition.RecognizeReceiptImageInLedger(nil, user, config, 11, []byte("synthetic image"), "image/png", time.UTC)
		}(i)
	}
	wg.Wait()
	for i := range responses {
		require.NoError(t, failures[i])
		require.Equal(t, int64(11), responses[i].LedgerId)
		require.Equal(t, int64(100001), responses[i].SourceAccountId)
		require.Equal(t, int64(-500), responses[i].SourceAmount)
		require.Empty(t, responses[i].TagIds)
	}
	var meters []*models.AIUsageRecord
	require.NoError(t, occurrenceDB(t).NewSession(nil).Find(&meters))
	require.Len(t, meters, n)
	for _, meter := range meters {
		require.Equal(t, int64(3), meter.Uid)
	}
	_, _, err = AIRecognition.RecognizeReceiptImageInLedger(nil, user, config, 12, []byte("synthetic"), "image/png", time.UTC)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	mu.Lock()
	require.Equal(t, n, calls)
	revoke = true
	mu.Unlock()
	_, _, err = AIRecognition.RecognizeReceiptImageInLedger(nil, user, config, 11, []byte("synthetic"), "image/png", time.UTC)
	require.ErrorIs(t, err, errs.ErrLedgerAccessDenied)
	require.Equal(t, int64(10000), occurrenceAccountBalance(t, 1, 100001))
	require.Zero(t, occurrenceTransactionCount(t, 1))
}

func TestAIRecognitionCreationHashLegacyAndLedgerMismatch(t *testing.T) {
	setupAIReview(t)
	request := models.AIReviewItemCreateRequest{LedgerId: 11, ClientSessionId: "legacy-hash", SourceType: 1, SourceText: "Synthetic", RecognizedData: &models.RecognizedTransactionResponse{Type: 3, SourceAmount: 500}}
	// An omitted zero ledger field retains pre-extension JSON and receipt hashes.
	encoded, err := json.Marshal(request.RecognizedData)
	require.NoError(t, err)
	require.NotContains(t, string(encoded), "ledgerId")
	first, err := AIReviewItems.CreateInLedger(nil, 3, request)
	require.NoError(t, err)
	second, err := AIReviewItems.CreateInLedger(nil, 3, request)
	require.NoError(t, err)
	require.Equal(t, first.AIReviewItemId, second.AIReviewItemId)
	request.RecognizedData.LedgerId = 12
	_, err = AIReviewItems.CreateInLedger(nil, 3, request)
	require.ErrorIs(t, err, errs.ErrParameterInvalid)
}
