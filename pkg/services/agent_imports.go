package services

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/converters"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/converters/converter"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
	"xorm.io/xorm"
)

type AgentImportService struct {
	ServiceUsingDB
	// Bound memory while serializing same-user commits on SQLite. Database keys
	// and conditional updates remain authoritative across server processes.
	commitLocks [64]sync.Mutex
}

var AgentImports = &AgentImportService{ServiceUsingDB: ServiceUsingDB{container: datastore.Container}}

func agentTokenIdentity(c *core.WebContext) string {
	claims := c.GetTokenClaims()
	return fmt.Sprintf("%d:%s:%d", claims.Type, claims.UserTokenId, claims.IssuedAt)
}

func importHash(value any) string {
	b, _ := json.Marshal(value)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func (s *AgentImportService) Preview(c *core.WebContext, user *models.User, config *settings.Config, req models.AgentImportPreviewRequest) (*models.AgentImportPreview, error) {
	if !config.EnableDataImport || !c.GetTokenClaims().HasAgentScope(core.AgentScopeImport) || user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_IMPORT_TRANSACTION) {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}
	if req.UtcOffset < -720 || req.UtcOffset > 840 {
		return nil, errs.ErrClientTimezoneOffsetInvalid
	}
	provider := ""
	switch req.FileType {
	case "alipay_app_csv", "alipay_web_csv":
		provider = "alipay"
	case "wechat_pay_app_csv", "wechat_pay_app_xlsx":
		provider = "wechat"
	default:
		return nil, errs.ErrImportFileTypeNotSupported
	}
	limit := int(config.MaxImportFileSize)
	if limit > 20*1024*1024 {
		limit = 20 * 1024 * 1024
	}
	if limit <= 0 || len(req.FileBase64) > ((limit+2)/3)*4 {
		return nil, errs.ErrExceedMaxUploadFileSize
	}
	data, err := base64.StdEncoding.DecodeString(req.FileBase64)
	if err != nil || len(data) == 0 {
		return nil, errs.ErrParameterInvalid
	}
	if len(data) > limit {
		return nil, errs.ErrExceedMaxUploadFileSize
	}
	importer, err := converters.GetTransactionDataImporter(req.FileType)
	if err != nil {
		return nil, err
	}
	// Empty maps deliberately prevent guessing account/category IDs from names.
	rows, _, _, _, _, _, err := importer.ParseImportedData(c, user, data, time.FixedZone("statement", int(req.UtcOffset)*60), converter.ParseImporterOptions(config, ""), nil, nil, nil, nil, nil)
	if err != nil {
		return nil, err
	}
	return s.createPreview(c, user.Uid, provider, rows.ToImportTransactionResponseList(), req.LedgerId)
}

func (s *AgentImportService) createPreview(c *core.WebContext, uid int64, provider string, transactions []*models.ImportTransactionResponse, ledgerIDs ...int64) (*models.AgentImportPreview, error) {
	ledgerID := int64(0)
	if len(ledgerIDs) > 0 {
		ledgerID = ledgerIDs[0]
	}
	ledger, err := ImportLedger(c, uid, ledgerID)
	if err != nil {
		return nil, err
	}
	if len(transactions) == 0 {
		return nil, errs.ErrNoDataToImport
	}
	if len(transactions) > 5000 {
		return nil, errs.ErrImportTooManyTransaction
	}
	db := s.UserDataDB(ledger.OwnerUid)
	if _, err := db.NewSession(c).Where("uid=? AND status=? AND expires_at<=?", uid, 0, time.Now().Unix()).Delete(new(models.AgentImportBatch)); err != nil {
		return nil, err
	}
	active, err := db.NewSession(c).Where("uid=? AND status=?", uid, 0).Count(new(models.AgentImportBatch))
	if err != nil {
		return nil, err
	}
	if active >= 10 {
		return nil, errs.ErrRepeatedRequest
	}
	id := make([]byte, 24)
	if _, err := rand.Read(id); err != nil {
		return nil, err
	}
	preview := &models.AgentImportPreview{BatchId: hex.EncodeToString(id), LedgerId: strconv.FormatInt(ledgerID, 10), LedgerName: ledger.Name, ExpiresAt: time.Now().Add(time.Hour).Unix()}
	if ledgerID == models.DefaultLedgerId {
		preview.LedgerName = "默认个人账本"
	}
	for i, t := range transactions {
		t.SourceAccountId, t.DestinationAccountId, t.CategoryId = 0, 0, 0
		t.TagIds = nil
		preview.Rows = append(preview.Rows, &models.AgentImportRow{Row: i, Transaction: t, Fingerprint: importHash([]any{ledgerID, provider, t}), Issue: "请选择账户和末级分类"})
	}
	if err := s.validatePreview(c, uid, preview); err != nil {
		return nil, err
	}
	preview.PreviewHash = importHash(preview.Rows)
	payload, _ := json.Marshal(preview)
	batch := &models.AgentImportBatch{Uid: uid, LedgerId: ledgerID, BatchId: preview.BatchId, TokenIdentity: agentTokenIdentity(c), Payload: string(payload), PreviewHash: preview.PreviewHash, ExpiresAt: preview.ExpiresAt}
	_, err = db.NewSession(c).Insert(batch)
	return preview, err
}

func (s *AgentImportService) load(c *core.WebContext, uid int64, id string, ledgerIDs ...int64) (*models.AgentImportBatch, *models.AgentImportPreview, error) {
	ledgerID := int64(0)
	if len(ledgerIDs) > 0 {
		ledgerID = ledgerIDs[0]
	}
	ledger, err := ImportLedger(c, uid, ledgerID)
	if err != nil {
		return nil, nil, err
	}
	if len(id) != 48 {
		return nil, nil, errs.ErrParameterInvalid
	}
	batch := &models.AgentImportBatch{}
	found, err := s.UserDataDB(ledger.OwnerUid).NewSession(c).Where("uid=? AND batch_id=? AND token_identity=? AND ledger_id=?", uid, id, agentTokenIdentity(c), ledgerID).Get(batch)
	if err != nil {
		return nil, nil, err
	}
	if !found || (batch.Status != 1 && batch.ExpiresAt <= time.Now().Unix()) {
		return nil, nil, errs.ErrParameterInvalid
	}
	var preview models.AgentImportPreview
	if err := json.Unmarshal([]byte(batch.Payload), &preview); err != nil {
		return nil, nil, err
	}
	return batch, &preview, nil
}

func (s *AgentImportService) Map(c *core.WebContext, uid int64, req models.AgentImportMapRequest) (*models.AgentImportPreview, error) {
	if !c.GetTokenClaims().HasAgentScope(core.AgentScopeImport) {
		return nil, errs.ErrNotPermittedToPerformThisAction
	}
	batch, preview, err := s.load(c, uid, req.BatchId, req.LedgerId)
	if err != nil {
		return nil, err
	}
	if batch.Status != 0 || req.PreviewHash != batch.PreviewHash || len(req.Mappings) == 0 || len(req.Mappings) > len(preview.Rows) {
		return nil, errs.ErrParameterInvalid
	}
	seen := map[int]bool{}
	for _, m := range req.Mappings {
		if m.Row < 0 || m.Row >= len(preview.Rows) || seen[m.Row] || m.SourceAccountId < 0 || m.CategoryId < 0 || m.DestinationAccountId < 0 {
			return nil, errs.ErrParameterInvalid
		}
		seen[m.Row] = true
		row := preview.Rows[m.Row]
		row.Selected = m.Selected
		row.Transaction.SourceAccountId = m.SourceAccountId
		row.Transaction.DestinationAccountId = m.DestinationAccountId
		row.Transaction.CategoryId = m.CategoryId
	}
	if err := s.validatePreview(c, uid, preview); err != nil {
		return nil, err
	}
	preview.PreviewHash = importHash(preview.Rows)
	payload, _ := json.Marshal(preview)
	updated := &models.AgentImportBatch{Payload: string(payload), PreviewHash: preview.PreviewHash}
	ledger, err := ImportLedger(c, uid, req.LedgerId)
	if err != nil {
		return nil, err
	}
	n, err := s.UserDataDB(ledger.OwnerUid).NewSession(c).Where("uid=? AND batch_id=? AND preview_hash=? AND status=?", uid, batch.BatchId, batch.PreviewHash, 0).Cols("payload", "preview_hash").Update(updated)
	if err != nil {
		return nil, err
	}
	if n != 1 {
		return nil, errs.ErrRepeatedRequest
	}
	return preview, nil
}

func (s *AgentImportService) validatePreview(c core.Context, uid int64, preview *models.AgentImportPreview) error {
	ledgerID, err := strconv.ParseInt(preview.LedgerId, 10, 64)
	if err != nil {
		return errs.ErrParameterInvalid
	}
	ledger, err := ImportLedger(c, uid, ledgerID)
	if err != nil {
		return err
	}
	owner := ledger.OwnerUid
	accounts, err := Accounts.GetAccountsInLedger(c, uid, ledgerID)
	if err != nil {
		return err
	}
	categories, err := TransactionCategories.GetAllCategoriesByUid(c, owner, 0, -1)
	if err != nil {
		return err
	}
	am := Accounts.GetAccountMapByList(accounts)
	cm := map[int64]*models.TransactionCategory{}
	for _, cat := range categories {
		cm[cat.CategoryId] = cat
	}
	var openings []*models.Transaction
	if err := s.UserDataDB(owner).NewSession(c).Where("uid=? AND ledger_id=? AND deleted=? AND type=?", owner, ledgerID, false, models.TRANSACTION_DB_TYPE_MODIFY_BALANCE).Find(&openings); err != nil {
		return err
	}
	openingTime := map[int64]int64{}
	for _, t := range openings {
		sec := utils.GetUnixTimeFromTransactionTime(t.TransactionTime)
		if sec > openingTime[t.AccountId] {
			openingTime[t.AccountId] = sec
		}
	}
	preview.ReadyCount, preview.DuplicateCount, preview.SelectedCount, preview.IncomeAmount, preview.ExpenseAmount = 0, 0, 0, 0, 0
	seen := map[string]bool{}
	for _, row := range preview.Rows {
		row.Issue = ""
		t := row.Transaction
		if t == nil {
			return errs.ErrParameterInvalid
		}
		sess := s.UserDataDB(owner).NewSession(c)
		exists, err := sess.Where("uid=? AND fingerprint=?", owner, row.Fingerprint).Exist(new(models.AgentImportFingerprint))
		sess.Close()
		if err != nil {
			return err
		}
		row.Duplicate = exists || seen[row.Fingerprint]
		seen[row.Fingerprint] = true
		if !row.Duplicate && t.SourceAccountId > 0 {
			sess = s.UserDataDB(owner).NewSession(c)
			row.Duplicate, err = agentExistingTransaction(sess, owner, t, ledgerID)
			sess.Close()
			if err != nil {
				return err
			}
		}
		if row.Duplicate {
			row.Issue = "重复或疑似重复流水，不能再次导入"
			preview.DuplicateCount++
		} else {
			row.Issue = agentRowIssue(t, am, cm, ledgerID)
			if row.Issue == "" && (t.Time <= openingTime[t.SourceAccountId] || (t.DestinationAccountId > 0 && t.Time <= openingTime[t.DestinationAccountId])) {
				row.Issue = "流水早于账户期初余额日期，请先核对期初记录"
			}
		}
		if row.Selected {
			preview.SelectedCount++
			if row.Issue == "" {
				preview.ReadyCount++
				if t.Type == models.TRANSACTION_TYPE_INCOME {
					preview.IncomeAmount += t.SourceAmount
				}
				if t.Type == models.TRANSACTION_TYPE_EXPENSE {
					preview.ExpenseAmount += t.SourceAmount
				}
			}
		}
	}
	return nil
}

func agentRowIssue(t *models.ImportTransactionResponse, am map[int64]*models.Account, cm map[int64]*models.TransactionCategory, ledgerIDs ...int64) string {
	ledgerID := int64(0)
	if len(ledgerIDs) > 0 {
		ledgerID = ledgerIDs[0]
	}
	if t.Type < models.TRANSACTION_TYPE_INCOME || t.Type > models.TRANSACTION_TYPE_TRANSFER || t.Time <= 0 || t.SourceAmount < 0 || t.SourceAmount > 9999999999999 || t.UtcOffset < -720 || t.UtcOffset > 840 || len([]rune(t.Comment)) > 255 {
		return "流水类型、时间或金额无效"
	}
	validAccount := func(id int64, currency string) bool {
		a := am[id]
		if a == nil || a.Hidden || a.Deleted || a.Type == models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS || a.LedgerId != ledgerID {
			return false
		}
		if parent := am[a.ParentAccountId]; parent != nil && (parent.Hidden || parent.Deleted) {
			return false
		}
		return currency == "" || currency == a.Currency
	}
	if !validAccount(t.SourceAccountId, t.OriginalSourceAccountCurrency) {
		return "请选择当前账本内可用且币种一致的账户"
	}
	cat := cm[t.CategoryId]
	catType := models.CATEGORY_TYPE_EXPENSE
	if t.Type == models.TRANSACTION_TYPE_INCOME {
		catType = models.CATEGORY_TYPE_INCOME
	}
	if t.Type == models.TRANSACTION_TYPE_TRANSFER {
		catType = models.CATEGORY_TYPE_TRANSFER
	}
	if cat == nil || cat.Deleted || cat.Hidden || cat.ParentCategoryId == 0 || cat.Type != catType {
		return "请选择对应收支类型的可用末级分类"
	}
	parent := cm[cat.ParentCategoryId]
	if parent == nil || parent.Hidden || parent.Deleted {
		return "分类已停用"
	}
	if t.Type == models.TRANSACTION_TYPE_TRANSFER {
		if t.SourceAccountId == t.DestinationAccountId || !validAccount(t.DestinationAccountId, t.OriginalDestinationAccountCurrency) || t.DestinationAmount < 0 || t.DestinationAmount > 9999999999999 {
			return "请核对转入账户和金额"
		}
		if am[t.SourceAccountId].Currency == am[t.DestinationAccountId].Currency && t.SourceAmount != t.DestinationAmount {
			return "同币种转账的两侧金额必须一致"
		}
	} else if t.DestinationAccountId != 0 || t.DestinationAmount != 0 {
		return "非转账流水不能指定转入账户"
	}
	return ""
}

func agentExistingTransaction(sess *xorm.Session, uid int64, t *models.ImportTransactionResponse, ledgerIDs ...int64) (bool, error) {
	ledgerID := int64(0)
	if len(ledgerIDs) > 0 {
		ledgerID = ledgerIDs[0]
	}
	dbType, err := t.Type.ToTransactionDbType()
	if err != nil {
		return false, err
	}
	return sess.Where("uid=? AND ledger_id=? AND deleted=? AND type=? AND account_id=? AND amount=? AND comment=? AND transaction_time>=? AND transaction_time<=?", uid, ledgerID, false, dbType, t.SourceAccountId, t.SourceAmount, t.Comment, utils.GetMinTransactionTimeFromUnixTime(t.Time), utils.GetMaxTransactionTimeFromUnixTime(t.Time)).Exist(new(models.Transaction))
}

func (s *AgentImportService) Confirm(c *core.WebContext, user *models.User, config *settings.Config, req models.AgentImportConfirmRequest) (int, error) {
	ledger, err := ImportLedger(c, user.Uid, req.LedgerId)
	if err != nil {
		return 0, err
	}
	owner := ledger.OwnerUid
	lock := &s.commitLocks[uint64(owner)%uint64(len(s.commitLocks))]
	lock.Lock()
	defer lock.Unlock()
	if !config.EnableDataImport || !c.GetTokenClaims().HasAgentScope(core.AgentScopeImport) || user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_IMPORT_TRANSACTION) {
		return 0, errs.ErrNotPermittedToPerformThisAction
	}
	if !req.Confirmed {
		return 0, errs.ErrParameterInvalid
	}
	batch, preview, err := s.load(c, user.Uid, req.BatchId, req.LedgerId)
	if err != nil {
		return 0, err
	}
	if req.PreviewHash == "" || req.PreviewHash != batch.PreviewHash {
		return 0, errs.ErrParameterInvalid
	}
	if batch.Status == 1 {
		return batch.ImportedCount, nil
	}
	if err = s.validatePreview(c, user.Uid, preview); err != nil {
		return 0, err
	}
	if preview.ReadyCount == 0 || preview.ReadyCount != preview.SelectedCount {
		again, _, readErr := s.load(c, user.Uid, batch.BatchId, req.LedgerId)
		if readErr == nil && again.Status == 1 && again.PreviewHash == req.PreviewHash {
			return again.ImportedCount, nil
		}
		return 0, errs.ErrParameterInvalid
	}
	accounts, err := Accounts.GetAccountsInLedger(c, user.Uid, req.LedgerId)
	if err != nil {
		return 0, err
	}
	am := Accounts.GetAccountMapByList(accounts)
	transactions := []*models.Transaction{}
	selected := []*models.AgentImportRow{}
	for _, row := range preview.Rows {
		if row.Selected {
			t := row.Transaction
			dbType, _ := t.Type.ToTransactionDbType()
			transaction := &models.Transaction{Uid: owner, LedgerId: req.LedgerId, RecorderUid: user.Uid, PayerUid: user.Uid, Type: dbType, AccountId: t.SourceAccountId, RelatedAccountId: t.DestinationAccountId, CategoryId: t.CategoryId, Amount: t.SourceAmount, RelatedAccountAmount: t.DestinationAmount, TransactionTime: utils.GetMinTransactionTimeFromUnixTime(t.Time), TimezoneUtcOffset: t.UtcOffset, Comment: t.Comment}
			if t.GeoLocation != nil {
				transaction.GeoLongitude = t.GeoLocation.Longitude
				transaction.GeoLatitude = t.GeoLocation.Latitude
			}
			if !user.CanEditTransactionByTransactionTime(transaction.TransactionTime, time.FixedZone("statement", int(t.UtcOffset)*60), am[transaction.AccountId], am[transaction.RelatedAccountId]) {
				return 0, errs.ErrCannotCreateTransactionWithThisTransactionTime
			}
			transactions = append(transactions, transaction)
			selected = append(selected, row)
		}
	}
	count := len(transactions)
	err = Transactions.batchCreateTransactionsWithHooks(c, owner, transactions, nil, nil, func(sess *xorm.Session) error {
		if err := authorizeImportLedger(sess, user.Uid, ledger); err != nil {
			return err
		}
		n, err := sess.Where("uid=? AND batch_id=? AND status=? AND preview_hash=? AND expires_at>?", user.Uid, batch.BatchId, 0, batch.PreviewHash, time.Now().Unix()).Cols("status", "imported_count").Update(&models.AgentImportBatch{Status: 1, ImportedCount: count})
		if err != nil {
			return err
		}
		if n != 1 {
			return errs.ErrRepeatedRequest
		}
		var liveAccounts []*models.Account
		var liveCategories []*models.TransactionCategory
		if err := sess.Where("uid=? AND deleted=?", owner, false).Find(&liveAccounts); err != nil {
			return err
		}
		if err := sess.Where("uid=? AND deleted=?", owner, false).Find(&liveCategories); err != nil {
			return err
		}
		liveAM := Accounts.GetAccountMapByList(liveAccounts)
		liveCM := map[int64]*models.TransactionCategory{}
		for _, cat := range liveCategories {
			liveCM[cat.CategoryId] = cat
		}
		for _, row := range selected {
			if agentRowIssue(row.Transaction, liveAM, liveCM, req.LedgerId) != "" {
				return errs.ErrParameterInvalid
			}
			exists, err := agentExistingTransaction(sess, owner, row.Transaction, req.LedgerId)
			if err != nil {
				return err
			}
			if exists {
				return errs.ErrRepeatedRequest
			}
			if _, err := sess.Insert(&models.AgentImportFingerprint{Uid: owner, Fingerprint: row.Fingerprint, BatchId: batch.BatchId}); err != nil {
				return err
			}
		}
		return nil
	}, func(sess *xorm.Session) error {
		// Financial snapshots are no longer needed after commit; keep only the receipt/hash.
		_, err := sess.Where("uid=? AND batch_id=?", user.Uid, batch.BatchId).Cols("payload").Update(&models.AgentImportBatch{Payload: "{}"})
		return err
	})
	if err != nil {
		// A concurrent/replayed confirmation may have committed before this request.
		again, _, readErr := s.load(c, user.Uid, batch.BatchId, req.LedgerId)
		if readErr == nil && again.Status == 1 && again.PreviewHash == req.PreviewHash {
			return again.ImportedCount, nil
		}
		return 0, err
	}
	return count, nil
}
