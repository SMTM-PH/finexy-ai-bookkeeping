package api

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

const reportPreviewLifetime = 10 * time.Minute

// Every report object has a fixed schema (at most 13 fields). Bound the syntax
// pass too, before the strict decoder rejects unknown fields.
const maximumReportObjectFields = 64

type LedgerReportsApi struct {
	ApiUsingConfig
	transactions *services.TransactionService
	users        *services.UserService
	encodeCSV    func(*models.LedgerReportSnapshot) ([]byte, error)
}

var LedgerReports = &LedgerReportsApi{ApiUsingConfig: ApiUsingConfig{container: settings.Container}, transactions: services.Transactions, users: services.Users}

// Reports currently serve normal login sessions. No new API/MCP grant is
// implicitly added to existing read tokens or their private tag capabilities.
func reportSession(c *core.WebContext) *errs.Error {
	c.Header("Cache-Control", "no-store")
	c.Header("Pragma", "no-cache")
	c.Header("X-Content-Type-Options", "nosniff")
	claims := c.GetTokenClaims()
	if claims == nil || claims.Type != core.USER_TOKEN_TYPE_NORMAL || claims.Uid <= 0 || claims.UserTokenId == "" {
		return errs.ErrAgentPermissionDenied
	}
	return nil
}

func decodeReportRequest(c *core.WebContext, destination any) error {
	media, _, err := mime.ParseMediaType(c.GetHeader("Content-Type"))
	if err != nil || media != "application/json" {
		return errs.ErrLedgerReportQueryInvalid
	}
	body, err := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, 1024*1024))
	if err != nil {
		return errs.ErrLedgerReportQueryInvalid
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.UseNumber()
	if err := reportJSONValue(decoder, 0); err != nil {
		return errs.ErrLedgerReportQueryInvalid
	}
	if _, err := decoder.Token(); err != io.EOF {
		return errs.ErrLedgerReportQueryInvalid
	}
	decoder = json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if decoder.Decode(destination) != nil {
		return errs.ErrLedgerReportQueryInvalid
	}
	return nil
}

// Reject duplicate keys, including nested ledger/query/confirmation fields.
// Decoder.UseNumber keeps syntax validation from rounding any numeric input.
func reportJSONValue(decoder *json.Decoder, depth int) error {
	if depth > 32 {
		return errs.ErrLedgerReportQueryInvalid
	}
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	delim, ok := token.(json.Delim)
	if !ok {
		return nil
	}
	switch delim {
	case '{':
		seen := make(map[string]bool)
		for decoder.More() {
			key, err := decoder.Token()
			if err != nil {
				return err
			}
			name, ok := key.(string)
			if !ok || len(seen) >= maximumReportObjectFields {
				return errs.ErrLedgerReportQueryInvalid
			}
			folded := reportJSONFoldKey(name)
			if seen[folded] {
				return errs.ErrLedgerReportQueryInvalid
			}
			seen[folded] = true
			if err := reportJSONValue(decoder, depth+1); err != nil {
				return err
			}
		}
	case '[':
		for decoder.More() {
			if err := reportJSONValue(decoder, depth+1); err != nil {
				return err
			}
		}
	default:
		return errs.ErrLedgerReportQueryInvalid
	}
	end, err := decoder.Token()
	if err != nil {
		return err
	}
	if delim == '{' && end != json.Delim('}') || delim == '[' && end != json.Delim(']') {
		return errs.ErrLedgerReportQueryInvalid
	}
	return nil
}

// encoding/json matches struct fields using Unicode simple folding, including
// non-ASCII members of ASCII fold sets (Kelvin K and long S). Index each fold
// set by its smallest rune instead of comparing every earlier key.
func reportJSONFoldKey(name string) string {
	var key strings.Builder
	key.Grow(len(name))
	for _, r := range name {
		canonical := r
		for next := unicode.SimpleFold(r); next != r; next = unicode.SimpleFold(next) {
			if next < canonical {
				canonical = next
			}
		}
		key.WriteRune(canonical)
	}
	return key.String()
}

func (a *LedgerReportsApi) exportAllowed(c *core.WebContext) *errs.Error {
	if !a.CurrentConfig().EnableDataExport {
		return errs.ErrDataExportNotAllowed
	}
	user, err := a.users.GetUserById(c, c.GetCurrentUid())
	if err != nil {
		return errs.Or(err, errs.ErrUserNotFound)
	}
	if user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_EXPORT_TRANSACTION) {
		return errs.ErrNotPermittedToPerformThisAction
	}
	return nil
}

func (a *LedgerReportsApi) QueryHandler(c *core.WebContext) (any, *errs.Error) {
	if err := reportSession(c); err != nil {
		return nil, err
	}
	var request models.LedgerReportQueryRequest
	if decodeReportRequest(c, &request) != nil {
		return nil, errs.ErrLedgerReportQueryInvalid
	}
	// Reject invalid pagination before reading a potentially large snapshot.
	if request.Offset < 0 || request.Offset > services.MaximumLedgerReportRows || request.Count != nil && (*request.Count < 1 || *request.Count > 200) {
		return nil, errs.ErrLedgerReportQueryInvalid
	}
	user, err := a.users.GetUserById(c, c.GetCurrentUid())
	if err != nil {
		return nil, errs.Or(err, errs.ErrUserNotFound)
	}
	snapshot, err := a.transactions.GetLedgerReportSnapshot(c, c.GetCurrentUid(), request.Query)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	page, err := services.PageLedgerReport(snapshot, request.Offset, request.Count, request.ExpectedSnapshotHash)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	page.ExportAllowed = a.CurrentConfig().EnableDataExport && !user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_EXPORT_TRANSACTION)
	return page, nil
}

type reportPreviewClaims struct {
	Version   int    `json:"v"`
	Actor     string `json:"u"`
	Session   string `json:"s"`
	Origin    string `json:"o"`
	Snapshot  string `json:"h"`
	Hidden    bool   `json:"hidden"`
	IssuedAt  int64  `json:"iat"`
	ExpiresAt int64  `json:"exp"`
}

func reportPreviewIdentity(c *core.WebContext) reportPreviewClaims {
	claims := c.GetTokenClaims()
	session := sha256.Sum256([]byte(claims.UserTokenId))
	return reportPreviewClaims{Version: 1, Actor: strconv.FormatInt(claims.Uid, 10), Session: hex.EncodeToString(session[:]), Origin: strings.ToLower(c.Request.Host)}
}

func signReportPreview(secret string, claims reportPreviewClaims) (string, error) {
	if secret == "" {
		return "", errs.ErrOperationFailed
	}
	data, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	payload := base64.RawURLEncoding.EncodeToString(data)
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte("finexy/report-export/v1\x00" + payload))
	return payload + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), nil
}

func verifyReportPreview(token, secret string, identity reportPreviewClaims, now int64) (*reportPreviewClaims, error) {
	if len(token) > 4096 || secret == "" {
		return nil, errs.ErrLedgerReportPreviewInvalid
	}
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		return nil, errs.ErrLedgerReportPreviewInvalid
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, errs.ErrLedgerReportPreviewInvalid
	}
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte("finexy/report-export/v1\x00" + parts[0]))
	if !hmac.Equal(signature, mac.Sum(nil)) {
		return nil, errs.ErrLedgerReportPreviewInvalid
	}
	data, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return nil, errs.ErrLedgerReportPreviewInvalid
	}
	var claims reportPreviewClaims
	if json.Unmarshal(data, &claims) != nil || claims.Version != 1 || claims.Actor != identity.Actor || claims.Session != identity.Session || claims.Origin != identity.Origin || claims.ExpiresAt <= now || claims.IssuedAt > now+30 || claims.ExpiresAt <= claims.IssuedAt || claims.ExpiresAt-claims.IssuedAt > int64(reportPreviewLifetime/time.Second) || !services.ValidLedgerReportHash(claims.Snapshot) {
		return nil, errs.ErrLedgerReportPreviewInvalid
	}
	return &claims, nil
}

func (a *LedgerReportsApi) ExportPreviewHandler(c *core.WebContext) (any, *errs.Error) {
	if err := reportSession(c); err != nil {
		return nil, err
	}
	if err := a.exportAllowed(c); err != nil {
		return nil, err
	}
	var request models.LedgerReportExportPreviewRequest
	if decodeReportRequest(c, &request) != nil || !services.ValidLedgerReportHash(request.ExpectedSnapshotHash) {
		return nil, errs.ErrLedgerReportQueryInvalid
	}
	snapshot, err := a.transactions.GetLedgerReportSnapshot(c, c.GetCurrentUid(), request.Query)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	if snapshot.SnapshotHash != request.ExpectedSnapshotHash {
		return nil, errs.ErrLedgerReportSnapshotChanged
	}
	if err := a.transactions.RecheckLedgerReportAccess(c, c.GetCurrentUid(), snapshot); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	if err := a.exportAllowed(c); err != nil {
		return nil, err
	}
	claims := reportPreviewIdentity(c)
	claims.Snapshot = snapshot.SnapshotHash
	claims.Hidden = snapshot.Query.Hidden == "include"
	claims.IssuedAt = time.Now().Unix()
	claims.ExpiresAt = claims.IssuedAt + int64(reportPreviewLifetime/time.Second)
	token, err := signReportPreview(a.CurrentConfig().SecretKey, claims)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return &models.LedgerReportExportPreview{Query: snapshot.Query, SnapshotHash: snapshot.SnapshotHash, MatchedCount: snapshot.MatchedCount, Totals: snapshot.Totals, AmountUnit: "minor", IncludesHiddenAmounts: claims.Hidden, ExpiresAt: claims.ExpiresAt, PreviewToken: token}, nil
}

func (a *LedgerReportsApi) ExportCSVHandler(c *core.WebContext) ([]byte, string, *errs.Error) {
	if err := reportSession(c); err != nil {
		return nil, "", err
	}
	if err := a.exportAllowed(c); err != nil {
		return nil, "", err
	}
	var request models.LedgerReportExportRequest
	if decodeReportRequest(c, &request) != nil || !services.ValidLedgerReportHash(request.ExpectedSnapshotHash) {
		return nil, "", errs.ErrLedgerReportQueryInvalid
	}
	if !request.Confirmed {
		return nil, "", errs.ErrLedgerReportConfirmationRequired
	}
	claims, err := verifyReportPreview(request.PreviewToken, a.CurrentConfig().SecretKey, reportPreviewIdentity(c), time.Now().Unix())
	if err != nil {
		return nil, "", errs.Or(err, errs.ErrOperationFailed)
	}
	if claims.Snapshot != request.ExpectedSnapshotHash {
		return nil, "", errs.ErrLedgerReportSnapshotChanged
	}
	if claims.Hidden && !request.IncludeHiddenAmounts {
		return nil, "", errs.ErrLedgerReportConfirmationRequired
	}
	snapshot, err := a.transactions.GetLedgerReportSnapshot(c, c.GetCurrentUid(), request.Query)
	if err != nil {
		return nil, "", errs.Or(err, errs.ErrOperationFailed)
	}
	if snapshot.SnapshotHash != claims.Snapshot {
		return nil, "", errs.ErrLedgerReportSnapshotChanged
	}
	encoder := a.encodeCSV
	if encoder == nil {
		encoder = services.EncodeLedgerReportCSV
	}
	data, err := encoder(snapshot)
	if err != nil {
		return nil, "", errs.Or(err, errs.ErrOperationFailed)
	}
	if err := a.transactions.RecheckLedgerReportAccess(c, c.GetCurrentUid(), snapshot); err != nil {
		return nil, "", errs.Or(err, errs.ErrOperationFailed)
	}
	if err := a.exportAllowed(c); err != nil {
		return nil, "", err
	}
	// A long render must not turn an expired receipt into an accepted download.
	if _, err := verifyReportPreview(request.PreviewToken, a.CurrentConfig().SecretKey, reportPreviewIdentity(c), time.Now().Unix()); err != nil {
		return nil, "", errs.Or(err, errs.ErrOperationFailed)
	}
	c.Header("X-Finexy-Report-Snapshot", snapshot.SnapshotHash)
	c.Header("X-Finexy-Report-Count", strconv.Itoa(snapshot.MatchedCount))
	c.Header("X-Finexy-Report-Amount-Unit", "minor")
	filename := fmt.Sprintf("finexy-report-%s-%s-%s.csv", snapshot.Query.LedgerID, snapshot.Query.StartDate, snapshot.Query.EndDate)
	return data, filename, nil
}
