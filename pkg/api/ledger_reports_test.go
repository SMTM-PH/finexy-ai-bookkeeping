package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/require"
)

func reportTestContext(body string) *core.WebContext {
	ctx, _ := gin.CreateTestContext(httptest.NewRecorder())
	ctx.Request = httptest.NewRequest("POST", "http://reports.example.invalid/api/v1/reports/query.json", bytes.NewBufferString(body))
	ctx.Request.Header.Set("Content-Type", "application/json")
	web := core.WrapWebContext(ctx, nil)
	web.SetTokenClaims(&core.UserTokenClaims{Type: core.USER_TOKEN_TYPE_NORMAL, Uid: 1, UserTokenId: "synthetic-session"})
	return web
}

func TestLedgerReportStrictRequestRejectsAmbiguousOrUnsafeInput(t *testing.T) {
	for _, body := range []string{
		`{"query":{"ledgerId":9007199254740993}}`,
		`{"query":{"ledgerId":"0","accounts":{"mode":"include","ids":[9007199254740993]}}}`,
		`{"query":{},"query":{}}`, `{"query":{},"Query":{}}`, `{"query":{},"q\u0075ery":{}}`,
		`{"query":{"ledgerId":"0","ledgerId":"1"}}`,
		`{"query":{"ledgerId":"0","LedgerID":"1"}}`,
		`{"query":{"unknown":true}}`, `{"unknown":true}`, `{"offset":0.5}`, `{"query":{}} {}`,
		strings.Repeat(" ", 1024*1024+1),
	} {
		t.Run(body[:min(50, len(body))], func(t *testing.T) {
			var request models.LedgerReportQueryRequest
			require.Error(t, decodeReportRequest(reportTestContext(body), &request))
		})
	}
	var request models.LedgerReportQueryRequest
	require.NoError(t, decodeReportRequest(reportTestContext(`{"query":{"ledgerId":"9007199254740993","startDate":"2026-10-01","endDate":"2026-10-31","timezone":"UTC","types":[2,3]},"count":50}`), &request))
	require.Equal(t, "9007199254740993", *request.Query.LedgerID)
	c := reportTestContext(`{}`)
	c.Request.Header.Del("Content-Type")
	require.Error(t, decodeReportRequest(c, &request))
}

func TestLedgerReportPreviewProofBindsSessionHostActorAndExpiry(t *testing.T) {
	identity := reportPreviewIdentity(reportTestContext(""))
	claims := identity
	claims.Snapshot = strings.Repeat("ab", 32)
	claims.IssuedAt = 1000
	claims.ExpiresAt = 1600
	token, err := signReportPreview("synthetic-signing-key", claims)
	require.NoError(t, err)
	verified, err := verifyReportPreview(token, "synthetic-signing-key", identity, 1001)
	require.NoError(t, err)
	require.Equal(t, claims.Snapshot, verified.Snapshot)
	for _, mutation := range []func(*reportPreviewClaims){func(c *reportPreviewClaims) { c.Actor = "2" }, func(c *reportPreviewClaims) { c.Session = "another-session" }, func(c *reportPreviewClaims) { c.Origin = "other.example.invalid" }} {
		changed := identity
		mutation(&changed)
		_, err := verifyReportPreview(token, "synthetic-signing-key", changed, 1001)
		require.ErrorIs(t, err, errs.ErrLedgerReportPreviewInvalid)
	}
	for _, now := range []int64{1600, 2000, 900} {
		_, err := verifyReportPreview(token, "synthetic-signing-key", identity, now)
		require.ErrorIs(t, err, errs.ErrLedgerReportPreviewInvalid)
	}
	_, err = verifyReportPreview(token, "different-key", identity, 1001)
	require.ErrorIs(t, err, errs.ErrLedgerReportPreviewInvalid)
	parts := strings.Split(token, ".")
	_, err = verifyReportPreview(parts[0]+".invalid", "synthetic-signing-key", identity, 1001)
	require.ErrorIs(t, err, errs.ErrLedgerReportPreviewInvalid)
	_, err = verifyReportPreview(strings.Repeat("x", 4097), "synthetic-signing-key", identity, 1001)
	require.ErrorIs(t, err, errs.ErrLedgerReportPreviewInvalid)
	claims.ExpiresAt = 1601
	token, err = signReportPreview("synthetic-signing-key", claims)
	require.NoError(t, err)
	_, err = verifyReportPreview(token, "synthetic-signing-key", identity, 1001)
	require.ErrorIs(t, err, errs.ErrLedgerReportPreviewInvalid)
}

func TestLedgerReportJSONRejectsExcessFieldsBeforeScanningWholeObject(t *testing.T) {
	var body strings.Builder
	body.WriteByte('{')
	for i := 0; i < 50000; i++ {
		fmt.Fprintf(&body, `"k%d":0,`, i)
	}
	body.WriteString(`"query":{}}`)
	require.Less(t, body.Len(), 1024*1024)
	decoder := json.NewDecoder(strings.NewReader(body.String()))
	decoder.UseNumber()
	require.Error(t, reportJSONValue(decoder, 0))
	// A deterministic parser-progress assertion avoids timing-sensitive tests.
	require.Less(t, decoder.InputOffset(), int64(4096))
	var request models.LedgerReportQueryRequest
	require.Error(t, decodeReportRequest(reportTestContext(body.String()), &request))
}

func TestLedgerReportJSONFoldMatchesUnicodeSimpleFolding(t *testing.T) {
	for _, pair := range [][2]string{{"k", "\u212a"}, {"S", "\u017f"}, {"Query", "qUERY"}, {"\u03a3", "\u03c2"}} {
		require.True(t, strings.EqualFold(pair[0], pair[1]))
		require.Equal(t, reportJSONFoldKey(pair[0]), reportJSONFoldKey(pair[1]))
		decoder := json.NewDecoder(strings.NewReader(fmt.Sprintf(`{%q:0,%q:1}`, pair[0], pair[1])))
		require.Error(t, reportJSONValue(decoder, 0))
	}
	require.NotEqual(t, reportJSONFoldKey("i"), reportJSONFoldKey("\u0130"))
}

func TestLedgerReportSessionDoesNotExpandAgentGrants(t *testing.T) {
	c := reportTestContext("")
	require.Nil(t, reportSession(c))
	require.Equal(t, "no-store", c.Writer.Header().Get("Cache-Control"))
	for _, kind := range []core.TokenType{core.USER_TOKEN_TYPE_API, core.USER_TOKEN_TYPE_MCP, core.USER_TOKEN_TYPE_REQUIRE_2FA} {
		c.SetTokenClaims(&core.UserTokenClaims{Type: kind, Uid: 1, UserTokenId: "synthetic"})
		require.ErrorIs(t, reportSession(c), errs.ErrAgentPermissionDenied)
	}
}
