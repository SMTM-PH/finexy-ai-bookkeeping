package services

import (
	"sort"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"xorm.io/xorm"
)

// AIRecognitionLedgerData contains only eligible names from one authorized
// ledger snapshot. It is transient prompt context, never usage-log content.
type AIRecognitionLedgerData struct {
	LedgerId, ActorUid                                                                       int64
	AccountNames, IncomeCategoryNames, ExpenseCategoryNames, TransferCategoryNames, TagNames []string
	Accounts                                                                                 map[string]*models.Account
	IncomeCategories, ExpenseCategories, TransferCategories                                  map[string]*models.TransactionCategory
	Tags                                                                                     map[string]*models.TransactionTag
}

func (s *AIRecognitionService) GetLedgerEssentialData(c core.Context, actor, ledgerID int64) (*AIRecognitionLedgerData, error) {
	db, ledger, err := resolveAIReviewLedger(c, actor, ledgerID, anyFamilyRole)
	if err != nil {
		return nil, err
	}
	var accounts []*models.Account
	var categories []*models.TransactionCategory
	var tags []*models.TransactionTag
	err = db.DoTransaction(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, anyFamilyRole, false); err != nil {
			return err
		}
		if err := sess.Where("uid=? AND ledger_id=? AND deleted=?", ledger.OwnerUid, ledgerID, false).Find(&accounts); err != nil {
			return err
		}
		if err := sess.Where("uid=? AND deleted=?", ledger.OwnerUid, false).Find(&categories); err != nil {
			return err
		}
		// Tags retain a private user namespace. Shared readers never receive the
		// owner's private names in prompts or their IDs in recognized drafts.
		if actor == ledger.OwnerUid {
			return sess.Where("uid=? AND deleted=?", actor, false).Find(&tags)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	data := &AIRecognitionLedgerData{LedgerId: ledgerID, ActorUid: actor, Accounts: make(map[string]*models.Account), IncomeCategories: make(map[string]*models.TransactionCategory), ExpenseCategories: make(map[string]*models.TransactionCategory), TransferCategories: make(map[string]*models.TransactionCategory), Tags: make(map[string]*models.TransactionTag)}
	accountIDs := make(map[int64]*models.Account)
	for _, a := range accounts {
		accountIDs[a.AccountId] = a
	}
	accountCounts := make(map[string]int)
	for _, a := range accounts {
		if a.Hidden || a.Type != models.ACCOUNT_TYPE_SINGLE_ACCOUNT {
			continue
		}
		if a.ParentAccountId > 0 {
			p := accountIDs[a.ParentAccountId]
			if p == nil || p.Hidden || p.Type != models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS || p.ParentAccountId != 0 {
				continue
			}
		}
		accountCounts[a.Name]++
		data.Accounts[a.Name] = a
	}
	for name, count := range accountCounts {
		if count != 1 {
			delete(data.Accounts, name)
		} else {
			data.AccountNames = append(data.AccountNames, name)
		}
	}
	categoryIDs := make(map[int64]*models.TransactionCategory)
	children := make(map[int64]bool)
	for _, cat := range categories {
		categoryIDs[cat.CategoryId] = cat
		children[cat.ParentCategoryId] = true
	}
	counts := make(map[models.TransactionCategoryType]map[string]int)
	for _, kind := range []models.TransactionCategoryType{models.CATEGORY_TYPE_INCOME, models.CATEGORY_TYPE_EXPENSE, models.CATEGORY_TYPE_TRANSFER} {
		counts[kind] = make(map[string]int)
	}
	for _, cat := range categories {
		p := categoryIDs[cat.ParentCategoryId]
		if cat.Hidden || children[cat.CategoryId] || p == nil || p.Hidden || p.ParentCategoryId != 0 || p.Type != cat.Type || counts[cat.Type] == nil {
			continue
		}
		counts[cat.Type][cat.Name]++
		switch cat.Type {
		case models.CATEGORY_TYPE_INCOME:
			data.IncomeCategories[cat.Name] = cat
		case models.CATEGORY_TYPE_EXPENSE:
			data.ExpenseCategories[cat.Name] = cat
		case models.CATEGORY_TYPE_TRANSFER:
			data.TransferCategories[cat.Name] = cat
		}
	}
	for kind, names := range counts {
		for name, count := range names {
			switch kind {
			case models.CATEGORY_TYPE_INCOME:
				if count != 1 {
					delete(data.IncomeCategories, name)
				} else {
					data.IncomeCategoryNames = append(data.IncomeCategoryNames, name)
				}
			case models.CATEGORY_TYPE_EXPENSE:
				if count != 1 {
					delete(data.ExpenseCategories, name)
				} else {
					data.ExpenseCategoryNames = append(data.ExpenseCategoryNames, name)
				}
			case models.CATEGORY_TYPE_TRANSFER:
				if count != 1 {
					delete(data.TransferCategories, name)
				} else {
					data.TransferCategoryNames = append(data.TransferCategoryNames, name)
				}
			}
		}
	}
	tagCounts := make(map[string]int)
	for _, tag := range tags {
		if !tag.Hidden {
			tagCounts[tag.Name]++
			data.Tags[tag.Name] = tag
		}
	}
	for name, count := range tagCounts {
		if count != 1 {
			delete(data.Tags, name)
		} else {
			data.TagNames = append(data.TagNames, name)
		}
	}
	for _, names := range [][]string{data.AccountNames, data.IncomeCategoryNames, data.ExpenseCategoryNames, data.TransferCategoryNames, data.TagNames} {
		sort.Strings(names)
	}
	return data, nil
}

func (data *AIRecognitionLedgerData) Recheck(c core.Context) error {
	db, ledger, err := resolveAIReviewLedger(c, data.ActorUid, data.LedgerId, anyFamilyRole)
	if err != nil {
		return err
	}
	return db.DoTransaction(c, func(sess *xorm.Session) error {
		return authorizeMonthlyBudgetLedger(sess, data.ActorUid, ledger, anyFamilyRole, false)
	})
}

func (data *AIRecognitionLedgerData) Parse(result *models.RecognizedTransactionResult, timezone *time.Location) (*models.RecognizedTransactionResponse, error) {
	if timezone == nil {
		timezone = time.UTC
	}
	response, err := parseRecognizedTransactionResult(result, timezone, data.Accounts, data.ExpenseCategories, data.IncomeCategories, data.TransferCategories, data.Tags)
	if err != nil {
		return nil, err
	}
	response.LedgerId = data.LedgerId
	if len(response.TagIds) > models.MaximumTagsCountOfTransaction {
		return nil, errs.ErrTransactionHasTooManyTags
	}
	return response, nil
}
