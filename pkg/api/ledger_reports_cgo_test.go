//go:build cgo

package api

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/stretchr/testify/require"
)

func setupReportAPI(t *testing.T) (*LedgerReportsApi, *models.LedgerReportQuery) {
	t.Helper()
	require.NoError(t, datastore.InitializeDataStore(&settings.Config{DatabaseConfig: &settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType, DatabasePath: filepath.Join(t.TempDir(), "report-api.db"), MaxOpenConnection: 1, MaxIdleConnection: 1}}))
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(models.Ledger), new(models.LedgerMember), new(models.Account), new(models.TransactionCategory), new(models.Transaction), new(models.TransactionTag), new(models.TransactionTagIndex)))
	require.NoError(t, datastore.Container.UserStore.SyncStructs(new(models.User)))
	db := services.Transactions.UserDataDB(11)
	sess := db.NewSession(nil)
	defer sess.Close()
	_, err := services.Users.UserDB().NewSession(nil).Insert(&models.User{Uid: 11, Username: "synthetic-api-owner", Email: "report-api-owner@example.invalid"}, &models.User{Uid: 22, Username: "synthetic-api-member", Email: "report-api-member@example.invalid"})
	require.NoError(t, err)
	_, err = sess.Insert(&models.Ledger{LedgerId: 100, OwnerUid: 11, Type: models.LEDGER_TYPE_PERSONAL}, &models.LedgerMember{LedgerMemberId: 1, LedgerId: 100, Uid: 22, Role: models.FAMILY_MEMBER_ROLE_MEMBER, Status: models.FAMILY_MEMBER_STATUS_ACTIVE}, &models.Account{AccountId: 5, Uid: 11, LedgerId: 100, Type: 1, Currency: "CNY", Name: "Synthetic"}, &models.TransactionCategory{CategoryId: 10, Uid: 11, Type: 2, Name: "Root"}, &models.TransactionCategory{CategoryId: 12, Uid: 11, ParentCategoryId: 10, Type: 2, Name: "Leaf"}, &models.Transaction{TransactionId: 50, Uid: 11, LedgerId: 100, Type: 3, CategoryId: 12, AccountId: 5, Amount: 500, TransactionTime: 1791633600000})
	require.NoError(t, err)
	previous := settings.Container.GetCurrentConfig()
	settings.SetCurrentConfig(&settings.Config{EnableDataExport: true, SecretKey: "synthetic-report-config"})
	t.Cleanup(func() { settings.SetCurrentConfig(previous) })
	id := "100"
	query := &models.LedgerReportQuery{LedgerID: &id, StartDate: "2026-10-01", EndDate: "2026-10-31", Timezone: "UTC"}
	return &LedgerReportsApi{ApiUsingConfig: ApiUsingConfig{container: settings.Container}, transactions: services.Transactions, users: services.Users}, query
}

func reportAPIContext(value any) *core.WebContext {
	data, _ := json.Marshal(value)
	c := reportTestContext(string(data))
	c.SetTokenClaims(&core.UserTokenClaims{Type: core.USER_TOKEN_TYPE_NORMAL, Uid: 22, UserTokenId: "synthetic-api-session"})
	return c
}

func TestLedgerReportAPIFinalChecksRejectChangesDuringRendering(t *testing.T) {
	for _, scenario := range []string{"demotion", "revocation", "export-setting", "user-restriction"} {
		t.Run(scenario, func(t *testing.T) {
			a, query := setupReportAPI(t)
			value, apiErr := a.QueryHandler(reportAPIContext(&models.LedgerReportQueryRequest{Query: query}))
			require.Nil(t, apiErr)
			page := value.(*models.LedgerReportPage)
			value, apiErr = a.ExportPreviewHandler(reportAPIContext(&models.LedgerReportExportPreviewRequest{Query: query, ExpectedSnapshotHash: page.SnapshotHash}))
			require.Nil(t, apiErr)
			preview := value.(*models.LedgerReportExportPreview)
			a.encodeCSV = func(snapshot *models.LedgerReportSnapshot) ([]byte, error) {
				switch scenario {
				case "demotion":
					_, err := services.Transactions.UserDataDB(11).NewSession(nil).Where("ledger_id=? AND uid=?", 100, 22).Cols("role").Update(&models.LedgerMember{Role: models.FAMILY_MEMBER_ROLE_VIEWER})
					require.NoError(t, err)
				case "revocation":
					_, err := services.Transactions.UserDataDB(11).NewSession(nil).Where("ledger_id=? AND uid=?", 100, 22).Cols("status").Update(&models.LedgerMember{Status: models.FAMILY_MEMBER_STATUS_REMOVED})
					require.NoError(t, err)
				case "export-setting":
					settings.SetCurrentConfig(&settings.Config{EnableDataExport: false, SecretKey: "synthetic-report-config"})
				case "user-restriction":
					_, err := services.Users.UserDB().NewSession(nil).ID(22).Cols("feature_restriction").Update(&models.User{FeatureRestriction: core.UserFeatureRestrictions(0).Add(core.USER_FEATURE_RESTRICTION_TYPE_EXPORT_TRANSACTION)})
					require.NoError(t, err)
				}
				return services.EncodeLedgerReportCSV(snapshot)
			}
			ctx := reportAPIContext(&models.LedgerReportExportRequest{Query: query, ExpectedSnapshotHash: page.SnapshotHash, PreviewToken: preview.PreviewToken, Confirmed: true})
			data, name, apiErr := a.ExportCSVHandler(ctx)
			require.NotNil(t, apiErr)
			require.Nil(t, data)
			require.Empty(t, name)
			require.Empty(t, ctx.Writer.Header().Get("X-Finexy-Report-Snapshot"))
			switch scenario {
			case "demotion":
				require.Equal(t, errs.ErrLedgerReportSnapshotChanged, apiErr)
			case "revocation":
				require.Equal(t, errs.ErrLedgerAccessDenied, apiErr)
			case "export-setting":
				require.Equal(t, errs.ErrDataExportNotAllowed, apiErr)
			case "user-restriction":
				require.Equal(t, errs.ErrNotPermittedToPerformThisAction, apiErr)
			}
		})
	}
}
