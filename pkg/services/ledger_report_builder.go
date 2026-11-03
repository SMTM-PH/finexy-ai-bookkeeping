package services

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"math/big"
	"sort"
	"strconv"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
)

func reportID(id int64) string { return strconv.FormatInt(id, 10) }

func reportCurrencyValid(currency string) bool {
	if len(currency) != 3 {
		return false
	}
	for _, c := range currency {
		if c < 'A' || c > 'Z' {
			return false
		}
	}
	return true
}

func (d *ledgerReportData) account(id int64) (*models.Account, error) {
	a := d.accounts[id]
	if a == nil || a.AccountId <= 0 || a.Uid != d.owner || a.LedgerId != d.ledger || a.Deleted {
		return nil, errs.ErrLedgerReportIncomplete
	}
	if a.Type != models.ACCOUNT_TYPE_SINGLE_ACCOUNT && a.Type != models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
		return nil, errs.ErrLedgerReportIncomplete
	}
	if a.Type == models.ACCOUNT_TYPE_SINGLE_ACCOUNT && !reportCurrencyValid(a.Currency) {
		return nil, errs.ErrLedgerReportIncomplete
	}
	if a.ParentAccountId != 0 {
		p := d.accounts[a.ParentAccountId]
		if a.Type != models.ACCOUNT_TYPE_SINGLE_ACCOUNT || p == nil || p.Deleted || p.Uid != d.owner || p.LedgerId != d.ledger || p.ParentAccountId != 0 || p.Type != models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
			return nil, errs.ErrLedgerReportIncomplete
		}
	}
	return a, nil
}

func (d *ledgerReportData) category(id int64) (*models.TransactionCategory, error) {
	c := d.categories[id]
	if c == nil || c.CategoryId <= 0 || c.Uid != d.owner || c.Deleted || c.Type < models.CATEGORY_TYPE_INCOME || c.Type > models.CATEGORY_TYPE_TRANSFER {
		return nil, errs.ErrLedgerReportIncomplete
	}
	if c.ParentCategoryId != 0 {
		p := d.categories[c.ParentCategoryId]
		if p == nil || p.Deleted || p.Uid != d.owner || p.ParentCategoryId != 0 || p.Type != c.Type {
			return nil, errs.ErrLedgerReportIncomplete
		}
	}
	return c, nil
}

func (d *ledgerReportData) resourceFilter(f *models.LedgerReportIDFilter, accounts bool) (map[int64]bool, error) {
	if f == nil {
		return nil, nil
	}
	selected := make(map[int64]bool)
	for _, value := range f.IDs {
		id, err := strconv.ParseInt(value, 10, 64)
		if err != nil || id < 0 || reportID(id) != value {
			return nil, errs.ErrLedgerReportResourceInvalid
		}
		if accounts {
			if _, err := d.account(id); err != nil {
				return nil, errs.ErrLedgerReportResourceInvalid
			}
		} else if id != 0 {
			if _, err := d.category(id); err != nil {
				return nil, errs.ErrLedgerReportResourceInvalid
			}
		}
		selected[id] = true
	}
	if f.IncludeDescendants {
		if accounts {
			for id, a := range d.accounts {
				if a.ParentAccountId != 0 && selected[a.ParentAccountId] {
					if _, err := d.account(id); err != nil {
						return nil, errs.ErrLedgerReportIncomplete
					}
					selected[id] = true
				}
			}
		} else {
			for id, c := range d.categories {
				if c.ParentCategoryId != 0 && selected[c.ParentCategoryId] {
					if _, err := d.category(id); err != nil {
						return nil, errs.ErrLedgerReportIncomplete
					}
					selected[id] = true
				}
			}
		}
	}
	return selected, nil
}

func reportIDMatches(f *models.LedgerReportIDFilter, selected map[int64]bool, ids ...int64) bool {
	if f == nil {
		return true
	}
	found := false
	for _, id := range ids {
		if id >= 0 && selected[id] {
			found = true
		}
	}
	if f.Mode == "exclude" {
		return !found
	}
	return found
}

func reportTagMatches(filters []models.LedgerReportTagFilter, tags map[int64]bool) bool {
	for _, f := range filters {
		if f.Mode == "none" {
			if len(tags) != 0 {
				return false
			}
			continue
		}
		matches := 0
		for _, value := range f.IDs {
			id, _ := strconv.ParseInt(value, 10, 64)
			if tags[id] {
				matches++
			}
		}
		switch f.Mode {
		case "any":
			if matches == 0 {
				return false
			}
		case "all":
			if matches != len(f.IDs) {
				return false
			}
		case "notAny":
			if matches > 0 {
				return false
			}
		case "notAll":
			if matches == len(f.IDs) {
				return false
			}
		}
	}
	return true
}

func (d *ledgerReportData) logicalDetail(row *models.Transaction, loc *time.Location) (*models.LedgerReportDetail, map[int64]bool, error) {
	if row.TransactionId <= 0 || row.Uid != d.owner || row.LedgerId != d.ledger || row.Deleted || row.TransactionTime < 0 || row.CategoryId < 0 {
		return nil, nil, errs.ErrLedgerReportIncomplete
	}
	kind, err := row.Type.ToTransactionType()
	if err != nil {
		return nil, nil, errs.ErrLedgerReportIncomplete
	}
	var partner *models.Transaction
	if kind == models.TRANSACTION_TYPE_TRANSFER && row.RelatedAccountId > 0 {
		partner = d.rows[row.RelatedId]
		if partner == nil || partner.TransactionId == row.TransactionId || partner.TransactionId <= 0 || partner.Deleted || partner.Uid != row.Uid || partner.LedgerId != row.LedgerId || partner.RelatedId != row.TransactionId || partner.AccountId != row.RelatedAccountId || partner.RelatedAccountId != row.AccountId || partner.Amount != row.RelatedAccountAmount || partner.RelatedAccountAmount != row.Amount || partner.CategoryId != row.CategoryId || partner.SavingsGoalFundId != row.SavingsGoalFundId {
			return nil, nil, errs.ErrLedgerReportIncomplete
		}
		if (row.Type == models.TRANSACTION_DB_TYPE_TRANSFER_OUT && partner.Type != models.TRANSACTION_DB_TYPE_TRANSFER_IN) || (row.Type == models.TRANSACTION_DB_TYPE_TRANSFER_IN && partner.Type != models.TRANSACTION_DB_TYPE_TRANSFER_OUT) {
			return nil, nil, errs.ErrLedgerReportIncomplete
		}
		if row.Type == models.TRANSACTION_DB_TYPE_TRANSFER_IN {
			return nil, nil, nil
		}
	} else if kind == models.TRANSACTION_TYPE_TRANSFER && (row.RelatedId != 0 || row.RelatedAccountId != 0) {
		return nil, nil, errs.ErrLedgerReportIncomplete
	}
	if !d.query.ContainsUnixTime(row.TransactionTime / 1000) {
		return nil, nil, nil
	}
	a, err := d.account(row.AccountId)
	if err != nil || a.Type != models.ACCOUNT_TYPE_SINGLE_ACCOUNT {
		return nil, nil, errs.ErrLedgerReportIncomplete
	}
	detail := &models.LedgerReportDetail{ID: reportID(row.TransactionId), PhysicalIDs: []string{reportID(row.TransactionId)}, Type: int32(kind), Time: row.TransactionTime / 1000, Sequence: row.TransactionTime, Date: time.Unix(row.TransactionTime/1000, 0).In(loc).Format("2006-01-02"), CategoryID: reportID(row.CategoryId), Tags: make([]models.LedgerReportResource, 0), Hidden: row.HideAmount, Comment: row.Comment}
	if row.CategoryId > 0 {
		category, err := d.category(row.CategoryId)
		if err != nil || category.ParentCategoryId == 0 {
			return nil, nil, errs.ErrLedgerReportIncomplete
		}
		expected := models.CATEGORY_TYPE_TRANSFER
		if kind == models.TRANSACTION_TYPE_INCOME {
			expected = models.CATEGORY_TYPE_INCOME
		}
		if kind == models.TRANSACTION_TYPE_EXPENSE {
			expected = models.CATEGORY_TYPE_EXPENSE
		}
		if kind == models.TRANSACTION_TYPE_MODIFY_BALANCE || category.Type != expected {
			return nil, nil, errs.ErrLedgerReportIncomplete
		}
		detail.CategoryName = category.Name
	} else if kind != models.TRANSACTION_TYPE_MODIFY_BALANCE && !(kind == models.TRANSACTION_TYPE_TRANSFER && row.SavingsGoalFundId > 0) {
		return nil, nil, errs.ErrLedgerReportIncomplete
	}
	leg := &models.LedgerReportLeg{AccountID: reportID(a.AccountId), AccountName: a.Name, Currency: a.Currency, Amount: row.Amount}
	if row.Type == models.TRANSACTION_DB_TYPE_TRANSFER_IN {
		detail.Destination = leg
	} else {
		detail.Source = leg
	}
	if partner != nil {
		other, err := d.account(partner.AccountId)
		if err != nil || other.Type != models.ACCOUNT_TYPE_SINGLE_ACCOUNT || other.AccountId == a.AccountId {
			return nil, nil, errs.ErrLedgerReportIncomplete
		}
		detail.Destination = &models.LedgerReportLeg{AccountID: reportID(other.AccountId), AccountName: other.Name, Currency: other.Currency, Amount: partner.Amount}
		detail.PhysicalIDs = append(detail.PhysicalIDs, reportID(partner.TransactionId))
		// Legacy paired rows may not mirror HideAmount. Either side hides the
		// whole logical transfer, so its second leg cannot disclose an amount.
		detail.Hidden = detail.Hidden || partner.HideAmount
	}
	if kind == models.TRANSACTION_TYPE_MODIFY_BALANCE {
		if row.RelatedAccountId != row.AccountId || row.RelatedId != 0 {
			return nil, nil, errs.ErrLedgerReportIncomplete
		}
		target := row.Amount
		detail.BalanceTarget = &target
		detail.Adjustment = row.RelatedAccountAmount
	}
	recorder := row.RecorderUid
	if recorder == 0 {
		recorder = row.Uid
	}
	payer := row.PayerUid
	if payer == 0 {
		payer = recorder
	}
	detail.RecorderUID, detail.PayerUID = reportID(recorder), reportID(payer)
	tags := make(map[int64]bool)
	for id := range d.rowTags[row.TransactionId] {
		tags[id] = true
	}
	if partner != nil {
		for id := range d.rowTags[partner.TransactionId] {
			tags[id] = true
		}
	}
	for _, id := range reportSortedIDs(tags) {
		tag := d.tags[id]
		if tag == nil || tag.Uid != d.owner || tag.Deleted {
			return nil, nil, errs.ErrLedgerReportIncomplete
		}
		detail.Tags = append(detail.Tags, models.LedgerReportResource{ID: reportID(id), Name: tag.Name, Hidden: tag.Hidden})
	}
	return detail, tags, nil
}

func reportSortedIDs[T any](m map[int64]T) []int64 {
	ids := make([]int64, 0, len(m))
	for id := range m {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
	return ids
}

type reportMoneyAccumulator struct{ income, expense, adjustment, transferIn, transferOut big.Int }
type reportMoneyBuckets map[string]*reportMoneyAccumulator

func (b reportMoneyBuckets) add(detail *models.LedgerReportDetail) {
	add := func(leg *models.LedgerReportLeg, destination bool) {
		if leg == nil {
			return
		}
		m := b[leg.Currency]
		if m == nil {
			m = new(reportMoneyAccumulator)
			b[leg.Currency] = m
		}
		amount := big.NewInt(leg.Amount)
		switch models.TransactionType(detail.Type) {
		case models.TRANSACTION_TYPE_INCOME:
			m.income.Add(&m.income, amount)
		case models.TRANSACTION_TYPE_EXPENSE:
			m.expense.Add(&m.expense, amount)
		case models.TRANSACTION_TYPE_MODIFY_BALANCE:
			m.adjustment.Add(&m.adjustment, big.NewInt(detail.Adjustment))
		case models.TRANSACTION_TYPE_TRANSFER:
			if destination {
				m.transferIn.Add(&m.transferIn, amount)
			} else {
				m.transferOut.Add(&m.transferOut, amount)
			}
		}
	}
	add(detail.Source, false)
	add(detail.Destination, true)
}

func (b reportMoneyBuckets) result() ([]models.LedgerReportMoney, error) {
	currencies := make([]string, 0, len(b))
	for currency := range b {
		currencies = append(currencies, currency)
	}
	sort.Strings(currencies)
	result := make([]models.LedgerReportMoney, 0, len(b))
	for _, currency := range currencies {
		m := b[currency]
		net := new(big.Int).Sub(&m.income, &m.expense)
		for _, amount := range []*big.Int{&m.income, &m.expense, &m.adjustment, &m.transferIn, &m.transferOut, net} {
			if !amount.IsInt64() {
				return nil, errs.ErrLedgerReportAmountOverflow
			}
		}
		result = append(result, models.LedgerReportMoney{Currency: currency, Income: m.income.Int64(), Expense: m.expense.Int64(), Net: net.Int64(), Adjustment: m.adjustment.Int64(), TransferIn: m.transferIn.Int64(), TransferOut: m.transferOut.Int64()})
	}
	return result, nil
}

type reportGroupAccumulator struct {
	name  string
	count int
	money reportMoneyBuckets
}
type reportGroupBuckets map[string]*reportGroupAccumulator

func (b reportGroupBuckets) add(key, name string, detail *models.LedgerReportDetail) {
	g := b[key]
	if g == nil {
		g = &reportGroupAccumulator{name: name, money: make(reportMoneyBuckets)}
		b[key] = g
	}
	g.count++
	g.money.add(detail)
}

func (b reportGroupBuckets) result() ([]models.LedgerReportGroup, error) {
	keys := make([]string, 0, len(b))
	for key := range b {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]models.LedgerReportGroup, 0, len(b))
	for _, key := range keys {
		g := b[key]
		money, err := g.money.result()
		if err != nil {
			return nil, err
		}
		result = append(result, models.LedgerReportGroup{Key: key, Name: g.name, Count: g.count, Money: money})
	}
	return result, nil
}

func (d *ledgerReportData) build() (*models.LedgerReportSnapshot, error) {
	accountFilter, err := d.resourceFilter(d.query.Accounts, true)
	if err != nil {
		return nil, err
	}
	categoryFilter, err := d.resourceFilter(d.query.Categories, false)
	if err != nil {
		return nil, err
	}
	for _, filter := range d.query.Tags {
		for _, value := range filter.IDs {
			id, _ := strconv.ParseInt(value, 10, 64)
			if d.tags[id] == nil {
				return nil, errs.ErrLedgerReportResourceInvalid
			}
		}
	}
	loc, err := time.LoadLocation(d.query.Timezone)
	if err != nil {
		return nil, errs.ErrLedgerReportQueryInvalid
	}
	result := &models.LedgerReportSnapshot{Query: d.query, DataComplete: true, Details: make([]*models.LedgerReportDetail, 0), ReadableTags: make([]models.LedgerReportResource, 0), TagGroupsAdditive: false}
	result.MemberRole = int32(d.role)
	for _, id := range reportSortedIDs(d.tags) {
		tag := d.tags[id]
		if tag.Uid != d.owner || tag.Deleted {
			return nil, errs.ErrLedgerReportIncomplete
		}
		result.ReadableTags = append(result.ReadableTags, models.LedgerReportResource{ID: reportID(id), Name: tag.Name, Hidden: tag.Hidden})
	}
	types := make(map[int32]bool)
	for _, kind := range d.query.Types {
		types[kind] = true
	}
	currencies := make(map[string]bool)
	for _, currency := range d.query.Currencies {
		currencies[currency] = true
	}
	totals := make(reportMoneyBuckets)
	trend, categories, tags := make(reportGroupBuckets), make(reportGroupBuckets), make(reportGroupBuckets)
	for _, id := range reportSortedIDs(d.rows) {
		detail, tagSet, err := d.logicalDetail(d.rows[id], loc)
		if err != nil {
			return nil, err
		}
		if detail == nil {
			continue
		}
		if !types[detail.Type] || (detail.Hidden && d.query.Hidden == "exclude") {
			continue
		}
		source, destination := int64(0), int64(0)
		currencyMatch := len(currencies) == 0
		if detail.Source != nil {
			source, _ = strconv.ParseInt(detail.Source.AccountID, 10, 64)
			currencyMatch = currencyMatch || currencies[detail.Source.Currency]
		}
		if detail.Destination != nil {
			destination, _ = strconv.ParseInt(detail.Destination.AccountID, 10, 64)
			currencyMatch = currencyMatch || currencies[detail.Destination.Currency]
		}
		if !currencyMatch || !reportIDMatches(d.query.Accounts, accountFilter, source, destination) || !reportIDMatches(d.query.Categories, categoryFilter, d.rows[id].CategoryId) || !reportTagMatches(d.query.Tags, tagSet) {
			continue
		}
		result.Details = append(result.Details, detail)
		totals.add(detail)
		trend.add(detail.Date, "", detail)
		categories.add(detail.CategoryID, detail.CategoryName, detail)
		if len(detail.Tags) == 0 {
			tags.add("none", "", detail)
		} else {
			for _, tag := range detail.Tags {
				tags.add(tag.ID, tag.Name, detail)
			}
		}
	}
	sort.Slice(result.Details, func(i, j int) bool {
		a, b := result.Details[i], result.Details[j]
		if a.Sequence != b.Sequence {
			return a.Sequence > b.Sequence
		}
		ai, _ := strconv.ParseInt(a.ID, 10, 64)
		bi, _ := strconv.ParseInt(b.ID, 10, 64)
		return ai > bi
	})
	result.MatchedCount = len(result.Details)
	if result.Totals, err = totals.result(); err != nil {
		return nil, err
	}
	if result.Trend, err = trend.result(); err != nil {
		return nil, err
	}
	if result.Categories, err = categories.result(); err != nil {
		return nil, err
	}
	if result.TagGroups, err = tags.result(); err != nil {
		return nil, err
	}
	if err := d.attachResources(result, accountFilter, categoryFilter); err != nil {
		return nil, err
	}
	// Hashes are equality guards, not credentials. Actor and live role prevent
	// reusing another member's or a pre-demotion preview for transport/export.
	payload := struct {
		Actor, Owner int64
		Role         models.FamilyMemberRole
		Report       *models.LedgerReportSnapshot
	}{d.actor, d.owner, d.role, result}
	raw, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	hash := sha256.Sum256(raw)
	result.SnapshotHash = hex.EncodeToString(hash[:])
	return result, nil
}

// Include labels/parents used by the result or captured filter in the preview
// hash. Account balances and unused owner-level category names stay out of the
// response. Parent renames therefore invalidate a displayed filter's preview.
func (d *ledgerReportData) attachResources(result *models.LedgerReportSnapshot, accountFilter, categoryFilter map[int64]bool) error {
	accounts, categories := make(map[int64]bool), make(map[int64]bool)
	for id := range accountFilter {
		accounts[id] = true
	}
	for id := range categoryFilter {
		// Zero is an exact row selector, not a synthetic metadata resource.
		if id > 0 {
			categories[id] = true
		}
	}
	for _, detail := range result.Details {
		if detail.Source != nil {
			id, _ := strconv.ParseInt(detail.Source.AccountID, 10, 64)
			accounts[id] = true
		}
		if detail.Destination != nil {
			id, _ := strconv.ParseInt(detail.Destination.AccountID, 10, 64)
			accounts[id] = true
		}
		id, _ := strconv.ParseInt(detail.CategoryID, 10, 64)
		if id > 0 {
			categories[id] = true
		}
	}
	for _, id := range reportSortedIDs(accounts) {
		a, err := d.account(id)
		if err != nil {
			return err
		}
		if a.ParentAccountId > 0 {
			accounts[a.ParentAccountId] = true
		}
	}
	for _, id := range reportSortedIDs(categories) {
		c, err := d.category(id)
		if err != nil {
			return err
		}
		if c.ParentCategoryId > 0 {
			categories[c.ParentCategoryId] = true
		}
	}
	result.Accounts = make([]models.LedgerReportResource, 0, len(accounts))
	result.CategoryMetadata = make([]models.LedgerReportResource, 0, len(categories))
	for _, id := range reportSortedIDs(accounts) {
		a, err := d.account(id)
		if err != nil {
			return err
		}
		result.Accounts = append(result.Accounts, models.LedgerReportResource{ID: reportID(id), Name: a.Name, ParentID: reportID(a.ParentAccountId), Hidden: a.Hidden, Type: int32(a.Type), Currency: a.Currency})
	}
	for _, id := range reportSortedIDs(categories) {
		c, err := d.category(id)
		if err != nil {
			return err
		}
		result.CategoryMetadata = append(result.CategoryMetadata, models.LedgerReportResource{ID: reportID(id), Name: c.Name, ParentID: reportID(c.ParentCategoryId), Hidden: c.Hidden, Type: int32(c.Type)})
	}
	return nil
}
