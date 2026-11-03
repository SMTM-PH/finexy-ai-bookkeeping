package services

import (
	"bytes"
	"encoding/csv"
	"encoding/hex"
	"encoding/json"
	"strconv"
	"strings"
	"unicode"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"xorm.io/xorm"
)

func ValidLedgerReportHash(value string) bool {
	raw, err := hex.DecodeString(value)
	return err == nil && len(raw) == 32 && value == strings.ToLower(value)
}

// PageLedgerReport slices an already complete snapshot without changing the
// aggregates, hash, or original snapshot. Offset pages require its exact hash.
func PageLedgerReport(snapshot *models.LedgerReportSnapshot, offset int, count *int, expectedHash string) (*models.LedgerReportPage, error) {
	pageSize := 50
	if count != nil {
		pageSize = *count
	}
	if snapshot == nil || !snapshot.DataComplete || snapshot.MatchedCount != len(snapshot.Details) {
		return nil, errs.ErrLedgerReportIncomplete
	}
	if offset < 0 || offset > snapshot.MatchedCount || pageSize < 1 || pageSize > 200 {
		return nil, errs.ErrLedgerReportQueryInvalid
	}
	if expectedHash != "" && !ValidLedgerReportHash(expectedHash) {
		return nil, errs.ErrLedgerReportQueryInvalid
	}
	if (offset > 0 && expectedHash == "") || (expectedHash != "" && expectedHash != snapshot.SnapshotHash) {
		return nil, errs.ErrLedgerReportSnapshotChanged
	}
	end := min(offset+pageSize, len(snapshot.Details))
	copy := *snapshot
	copy.Details = append(make([]*models.LedgerReportDetail, 0, end-offset), snapshot.Details[offset:end]...)
	page := &models.LedgerReportPage{LedgerReportSnapshot: &copy, Offset: offset, ReturnedCount: end - offset, DetailsComplete: offset == 0 && end == snapshot.MatchedCount}
	if end < len(snapshot.Details) {
		next := end
		page.NextOffset = &next
	}
	return page, nil
}

// RecheckLedgerReportAccess is called after file generation as well as during
// the original snapshot read. A demotion invalidates the old confirmation even
// if the new role can still read reports. No financial rows are re-written.
func (s *TransactionService) RecheckLedgerReportAccess(c core.Context, actor int64, snapshot *models.LedgerReportSnapshot) error {
	if snapshot == nil || snapshot.Query == nil {
		return errs.ErrLedgerReportIncomplete
	}
	ledgerID, err := strconv.ParseInt(snapshot.Query.LedgerID, 10, 64)
	if err != nil {
		return errs.ErrLedgerReportQueryInvalid
	}
	db, ledger, err := Ledgers.GetLedgerWithAccess(c, actor, ledgerID, func(role models.FamilyMemberRole) bool { return role.IsValid() })
	if err != nil {
		return err
	}
	if db == nil {
		db = s.UserDataDB(actor)
	}
	return db.DoReadSnapshot(c, func(sess *xorm.Session) error {
		if err := authorizeMonthlyBudgetLedger(sess, actor, ledger, func(role models.FamilyMemberRole) bool { return role.IsValid() }, false); err != nil {
			return err
		}
		role := models.FAMILY_MEMBER_ROLE_OWNER
		if ledgerID != 0 {
			member := new(models.LedgerMember)
			found, err := sess.Where("ledger_id=? AND uid=?", ledgerID, actor).Get(member)
			if err != nil {
				return err
			}
			if found {
				role = member.Role
			}
		}
		if int32(role) != snapshot.MemberRole {
			return errs.ErrLedgerReportSnapshotChanged
		}
		return nil
	}, new(models.Ledger), new(models.LedgerMember))
}

// ReportCSVText protects text only. Signed numeric amount columns remain exact
// integers, including negative refunds. Leading whitespace/control characters
// cannot hide spreadsheet formula prefixes. Original apostrophes are escaped
// reversibly too; consumers may remove one added apostrophe from text columns.
func ReportCSVText(value string) string {
	if strings.HasPrefix(value, "'") {
		return "'" + value
	}
	trimmed := strings.TrimLeftFunc(value, func(r rune) bool { return unicode.IsSpace(r) || unicode.IsControl(r) || unicode.Is(unicode.Cf, r) })
	if trimmed != "" && strings.ContainsRune("=+-@", rune(trimmed[0])) {
		return "'" + value
	}
	if strings.HasPrefix(value, "\t") || strings.HasPrefix(value, "\r") || strings.HasPrefix(value, "\n") {
		return "'" + value
	}
	return value
}

// Finexy's existing export contract uses two decimal major units for every
// currency. Divide first so MinInt64 never needs an overflowing absolute value.
func reportCSVMajorAmount(minor int64) string {
	whole, fraction := minor/100, minor%100
	sign := ""
	if minor < 0 {
		sign = "-"
		whole = -whole
		fraction = -fraction
	}
	decimal := strconv.FormatInt(fraction, 10)
	if fraction < 10 {
		decimal = "0" + decimal
	}
	return sign + strconv.FormatInt(whole, 10) + "." + decimal
}

// EncodeLedgerReportCSV emits exactly one record per matched logical row. It
// has no authorization side effects; the HTTP layer owns permission, preview,
// hidden-amount acknowledgement, and final access checks.
func EncodeLedgerReportCSV(snapshot *models.LedgerReportSnapshot) ([]byte, error) {
	if snapshot == nil || snapshot.Query == nil || !snapshot.DataComplete || snapshot.MatchedCount != len(snapshot.Details) {
		return nil, errs.ErrLedgerReportIncomplete
	}
	var output bytes.Buffer
	writer := csv.NewWriter(&output)
	header := []string{"report_version", "snapshot_hash", "ledger_id", "timezone", "start_date", "end_date", "amount_unit", "hidden_policy", "id", "physical_ids_json", "type", "unix_seconds", "sequence", "local_date", "category_id", "category_name", "source_account_id", "source_account_name", "source_currency", "source_amount_minor", "destination_account_id", "destination_account_name", "destination_currency", "destination_amount_minor", "adjustment_minor", "balance_target_minor", "tag_ids_json", "tag_names_json", "hidden", "comment", "recorder_uid", "payer_uid"}
	header = append(header, "major_unit_scale", "source_amount_major", "destination_amount_major", "adjustment_major", "balance_target_major")
	if err := writer.Write(header); err != nil {
		return nil, err
	}
	for _, row := range snapshot.Details {
		if row == nil {
			return nil, errs.ErrLedgerReportIncomplete
		}
		source, destination := []string{"", "", "", ""}, []string{"", "", "", ""}
		for _, side := range []struct {
			leg   *models.LedgerReportLeg
			cells []string
		}{{row.Source, source}, {row.Destination, destination}} {
			if side.leg != nil {
				side.cells[0] = side.leg.AccountID
				side.cells[1] = ReportCSVText(side.leg.AccountName)
				side.cells[2] = side.leg.Currency
				side.cells[3] = strconv.FormatInt(side.leg.Amount, 10)
			}
		}
		physical, err := json.Marshal(row.PhysicalIDs)
		if err != nil {
			return nil, err
		}
		tagIDs, tagNames := make([]string, 0, len(row.Tags)), make([]string, 0, len(row.Tags))
		for _, tag := range row.Tags {
			tagIDs = append(tagIDs, tag.ID)
			tagNames = append(tagNames, tag.Name)
		}
		ids, err := json.Marshal(tagIDs)
		if err != nil {
			return nil, err
		}
		names, err := json.Marshal(tagNames)
		if err != nil {
			return nil, err
		}
		target := ""
		if row.BalanceTarget != nil {
			target = strconv.FormatInt(*row.BalanceTarget, 10)
		}
		record := []string{"1", snapshot.SnapshotHash, snapshot.Query.LedgerID, snapshot.Query.Timezone, snapshot.Query.StartDate, snapshot.Query.EndDate, "minor", snapshot.Query.Hidden, row.ID, string(physical), strconv.FormatInt(int64(row.Type), 10), strconv.FormatInt(row.Time, 10), strconv.FormatInt(row.Sequence, 10), row.Date, row.CategoryID, ReportCSVText(row.CategoryName)}
		record = append(record, source...)
		record = append(record, destination...)
		record = append(record, strconv.FormatInt(row.Adjustment, 10), target, string(ids), string(names), strconv.FormatBool(row.Hidden), ReportCSVText(row.Comment), row.RecorderUID, row.PayerUID)
		sourceMajor, destinationMajor, targetMajor := "", "", ""
		if row.Source != nil {
			sourceMajor = reportCSVMajorAmount(row.Source.Amount)
		}
		if row.Destination != nil {
			destinationMajor = reportCSVMajorAmount(row.Destination.Amount)
		}
		if row.BalanceTarget != nil {
			targetMajor = reportCSVMajorAmount(*row.BalanceTarget)
		}
		record = append(record, "2", sourceMajor, destinationMajor, reportCSVMajorAmount(row.Adjustment), targetMajor)
		if err := writer.Write(record); err != nil {
			return nil, err
		}
	}
	writer.Flush()
	if err := writer.Error(); err != nil {
		return nil, err
	}
	return output.Bytes(), nil
}
