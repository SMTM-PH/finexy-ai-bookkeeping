package services

import (
	"strconv"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"xorm.io/builder"
	"xorm.io/xorm"
)

const (
	MaximumLedgerReportRows     = 50000
	maximumLedgerReportMetadata = 20000
)

type ledgerReportData struct {
	actor, owner, ledger int64
	role                 models.FamilyMemberRole
	query                *models.NormalizedLedgerReportQuery
	accounts             map[int64]*models.Account
	categories           map[int64]*models.TransactionCategory
	tags                 map[int64]*models.TransactionTag
	rows                 map[int64]*models.Transaction
	rowTags              map[int64]map[int64]bool
}

// GetLedgerReportSnapshot reads one complete bounded snapshot from the ledger
// owner's shard. No report data is persisted and no balances are changed.
// The dedicated report tag projection authorizes names only for tags attached
// to live transactions in this readable ledger. It does not grant tag management
// or expose unused tags, tags of other ledgers, or AI prompt access.
func (s *TransactionService) GetLedgerReportSnapshot(c core.Context, actor int64, request *models.LedgerReportQuery) (*models.LedgerReportSnapshot, error) {
	query, err := models.NormalizeLedgerReportQuery(request)
	if err != nil {
		return nil, errs.ErrLedgerReportQueryInvalid
	}
	ledgerID, _ := strconv.ParseInt(query.LedgerID, 10, 64)
	db, ledger, err := Ledgers.GetLedgerWithAccess(c, actor, ledgerID, func(role models.FamilyMemberRole) bool { return role.IsValid() })
	if err != nil {
		return nil, err
	}
	if db == nil {
		db = s.UserDataDB(actor)
	}
	data := &ledgerReportData{actor: actor, owner: ledger.OwnerUid, ledger: ledgerID, query: query,
		accounts: make(map[int64]*models.Account), categories: make(map[int64]*models.TransactionCategory),
		tags: make(map[int64]*models.TransactionTag), rows: make(map[int64]*models.Transaction), rowTags: make(map[int64]map[int64]bool)}
	err = db.DoReadSnapshot(c, func(sess *xorm.Session) error {
		// The earlier locator is not sufficient: membership is checked again in
		// the same transaction that reads all financial data and metadata.
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, func(role models.FamilyMemberRole) bool { return role.IsValid() }, false); err != nil {
			return err
		}
		data.role = models.FAMILY_MEMBER_ROLE_OWNER
		if ledgerID != 0 {
			member := new(models.LedgerMember)
			found, err := sess.Where("ledger_id=? AND uid=?", ledgerID, actor).Get(member)
			if err != nil {
				return err
			}
			if found {
				data.role = member.Role
			}
		}
		if err := data.readMetadata(sess); err != nil {
			return err
		}
		return data.readTransactions(sess)
	}, new(models.Ledger), new(models.LedgerMember), new(models.Account),
		new(models.Transaction), new(models.TransactionCategory), new(models.TransactionTag), new(models.TransactionTagIndex))
	if err != nil {
		return nil, err
	}
	// The captured rows/metadata are private copies. Release the database
	// transaction before grouping and hashing this immutable snapshot.
	return data.build()
}

func (d *ledgerReportData) readMetadata(sess *xorm.Session) error {
	var accounts []*models.Account
	if err := sess.Where("uid=? AND ledger_id=? AND deleted=?", d.owner, d.ledger, false).Limit(maximumLedgerReportMetadata + 1).Find(&accounts); err != nil {
		return err
	}
	if len(accounts) > maximumLedgerReportMetadata {
		return errs.ErrLedgerReportTooLarge
	}
	for _, account := range accounts {
		d.accounts[account.AccountId] = account
	}
	var categories []*models.TransactionCategory
	if err := sess.Where("uid=? AND deleted=?", d.owner, false).Limit(maximumLedgerReportMetadata + 1).Find(&categories); err != nil {
		return err
	}
	if len(categories) > maximumLedgerReportMetadata {
		return errs.ErrLedgerReportTooLarge
	}
	for _, category := range categories {
		d.categories[category.CategoryId] = category
	}
	// Scope the dictionary through actual live ledger associations, not through
	// the owner's entire private tag namespace. Both sides are owner constrained.
	ledgerRows := builder.Select("transaction_id").From(sess.Engine().Quote(sess.Engine().TableName(new(models.Transaction)))).Where(builder.Eq{"uid": d.owner, "ledger_id": d.ledger, "deleted": false})
	var indices []*models.TransactionTagIndex
	if err := sess.Where("uid=? AND deleted=?", d.owner, false).In("transaction_id", ledgerRows).Distinct("tag_id").Limit(maximumLedgerReportMetadata + 1).Find(&indices); err != nil {
		return err
	}
	if len(indices) > maximumLedgerReportMetadata {
		return errs.ErrLedgerReportTooLarge
	}
	ids := make([]int64, 0, len(indices))
	for _, index := range indices {
		ids = append(ids, index.TagId)
	}
	if len(ids) == 0 {
		return nil
	}
	// Keep IN lists small for SQLite builds with the legacy variable limit.
	for start := 0; start < len(ids); start += 500 {
		end := min(start+500, len(ids))
		var tags []*models.TransactionTag
		if err := sess.Where("uid=? AND deleted=?", d.owner, false).In("tag_id", ids[start:end]).Find(&tags); err != nil {
			return err
		}
		for _, tag := range tags {
			if tag.TagId <= 0 {
				return errs.ErrLedgerReportIncomplete
			}
			d.tags[tag.TagId] = tag
		}
	}
	for _, id := range ids {
		if d.tags[id] == nil {
			return errs.ErrLedgerReportIncomplete
		}
	}
	return nil
}

func (d *ledgerReportData) readTransactions(sess *xorm.Session) error {
	if len(d.query.UTCRanges) == 0 {
		return nil
	}
	condition := builder.NewCond()
	for _, interval := range d.query.UTCRanges {
		condition = condition.Or(builder.And(builder.Gte{"transaction_time": interval.Start * 1000}, builder.Lt{"transaction_time": interval.EndExclusive * 1000}))
	}
	var rows []*models.Transaction
	if err := sess.Where("uid=? AND ledger_id=? AND deleted=?", d.owner, d.ledger, false).And(condition).Limit(MaximumLedgerReportRows + 1).Find(&rows); err != nil {
		return err
	}
	if len(rows) > MaximumLedgerReportRows {
		return errs.ErrLedgerReportTooLarge
	}
	for _, row := range rows {
		d.rows[row.TransactionId] = row
	}
	missing := make(map[int64]bool)
	for _, row := range rows {
		if (row.Type == models.TRANSACTION_DB_TYPE_TRANSFER_IN || row.Type == models.TRANSACTION_DB_TYPE_TRANSFER_OUT) && row.RelatedAccountId > 0 && d.rows[row.RelatedId] == nil {
			missing[row.RelatedId] = true
		}
	}
	ids := make([]int64, 0, len(missing))
	for id := range missing {
		ids = append(ids, id)
	}
	for start := 0; start < len(ids); start += 500 {
		var related []*models.Transaction
		if err := sess.Where("uid=? AND ledger_id=? AND deleted=?", d.owner, d.ledger, false).In("transaction_id", ids[start:min(start+500, len(ids))]).Find(&related); err != nil {
			return err
		}
		for _, row := range related {
			d.rows[row.TransactionId] = row
		}
	}
	// Related rows can straddle a natural-day boundary because the sequence
	// generator advances. The OUT row supplies a logical transfer's date.
	ids = ids[:0]
	for id := range d.rows {
		ids = append(ids, id)
	}
	for start := 0; start < len(ids); start += 500 {
		var indices []*models.TransactionTagIndex
		if err := sess.Where("uid=? AND deleted=?", d.owner, false).In("transaction_id", ids[start:min(start+500, len(ids))]).Limit(500*models.MaximumTagsCountOfTransaction + 1).Find(&indices); err != nil {
			return err
		}
		if len(indices) > 500*models.MaximumTagsCountOfTransaction {
			return errs.ErrLedgerReportTooLarge
		}
		for _, index := range indices {
			if d.tags[index.TagId] == nil {
				return errs.ErrLedgerReportIncomplete
			}
			if d.rowTags[index.TransactionId] == nil {
				d.rowTags[index.TransactionId] = make(map[int64]bool)
			}
			d.rowTags[index.TransactionId][index.TagId] = true
		}
	}
	return nil
}
