package middlewares

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
)

// Check the declared ledger as well as persisted entity ownership. A caller
// cannot bypass the grant by omitting ledgerId or substituting an account ID.
func checkAgentAPILedger(c *core.WebContext, claims *core.UserTokenClaims) *errs.Error {
	p := strings.TrimPrefix(c.Request.URL.Path, "/api/v1")
	if p == "/exchange_rates/latest.json" || p == "/ledger/list.json" {
		return nil
	}
	ledgerText := "0"
	if c.Request.Method == "GET" {
		values := c.Request.URL.Query()["ledgerId"]
		if len(values) > 1 {
			return errs.ErrParameterInvalid
		}
		if len(values) == 1 {
			ledgerText = values[0]
		}
	} else {
		body, err := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, 28*1024*1024))
		if err != nil {
			return errs.ErrParameterInvalid
		}
		c.Request.Body = io.NopCloser(bytes.NewReader(body))
		var args map[string]json.RawMessage
		if json.Unmarshal(body, &args) != nil {
			return errs.ErrParameterInvalid
		}
		if value, found := args["ledgerId"]; found {
			if json.Unmarshal(value, &ledgerText) != nil {
				return errs.ErrParameterInvalid
			}
		}
	}
	ledgerId, err := strconv.ParseInt(ledgerText, 10, 64)
	if err != nil || ledgerId < 0 || strconv.FormatInt(ledgerId, 10) != ledgerText {
		return errs.ErrParameterInvalid
	}
	if !claims.HasAgentLedger(ledgerId) {
		return errs.ErrAgentPermissionDenied
	}
	if _, _, err := services.Ledgers.GetLedgerWithAccess(c, claims.Uid, ledgerId, func(models.FamilyMemberRole) bool { return true }); err != nil {
		return errs.Or(err, errs.ErrAgentPermissionDenied)
	}
	return nil
}
