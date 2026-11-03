//go:build cgo

package cmd

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
	"github.com/stretchr/testify/require"
	"github.com/urfave/cli/v3"
)

// The original pre-ledger table had no ledger/currency/revision/scope columns.
// Keep JSON as unbounded TEXT without relying on unsupported MySQL defaults.
type legacyProviderMonthlyBudget struct {
	MonthlyBudgetId     int64  `xorm:"PK"`
	Uid                 int64  `xorm:"NOT NULL"`
	Deleted             bool   `xorm:"NOT NULL"`
	YearMonth           int32  `xorm:"NOT NULL"`
	Amount              int64  `xorm:"NOT NULL"`
	CategoryAmountsJson string `xorm:"TEXT NOT NULL"`
	CreatedUnixTime     int64
	UpdatedUnixTime     int64
	DeletedUnixTime     int64
}

func (*legacyProviderMonthlyBudget) TableName() string { return "monthly_budget" }

func TestApplicationSchemaSQLiteBudgetStorage(t *testing.T) {
	verifyApplicationSchemaAndBudgetStorage(t, &settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType,
		DatabasePath: filepath.Join(t.TempDir(), "complete-schema.db"), MaxOpenConnection: 4, MaxIdleConnection: 4})
}

func TestApplicationSchemaExternalProviderBudgetStorage(t *testing.T) {
	provider := os.Getenv("FINEXY_REPORT_PROVIDER_TYPE")
	if provider == "" {
		t.Skip("real external provider not configured; this is not provider acceptance")
	}
	require.Equal(t, "1", os.Getenv("FINEXY_REPORT_PROVIDER_ISOLATED"))
	require.Contains(t, []string{settings.MySqlDbType, settings.PostgresDbType}, provider)
	host, name := os.Getenv("FINEXY_REPORT_PROVIDER_HOST"), os.Getenv("FINEXY_REPORT_PROVIDER_NAME")
	require.True(t, strings.HasPrefix(host, "finexy-report-provider-20261022-"))
	require.Regexp(t, regexp.MustCompile(`^finexy_report_provider_[a-z0-9]+$`), name)
	verifyApplicationSchemaAndBudgetStorage(t, &settings.DatabaseConfig{DatabaseType: provider, DatabaseHost: host,
		DatabaseName: name, DatabaseUser: os.Getenv("FINEXY_REPORT_PROVIDER_USER"), DatabasePassword: os.Getenv("FINEXY_REPORT_PROVIDER_PASSWORD"),
		DatabaseSSLMode: "disable", MaxOpenConnection: 4, MaxIdleConnection: 4})
}

func verifyApplicationSchemaAndBudgetStorage(t *testing.T, config *settings.DatabaseConfig) {
	t.Helper()
	require.NoError(t, datastore.InitializeDataStore(&settings.Config{DatabaseConfig: config}))
	require.NoError(t, uuid.InitializeUuidGenerator(&settings.Config{UuidGeneratorType: settings.InternalUuidGeneratorType, UuidServerId: 207}))
	ctx := core.WrapCilContext(context.Background(), &cli.Command{})
	// Execute the actual full production initializer, not a selected report-table
	// Sync2 list. This is the MySQL startup path that originally failed at 1101.
	require.NoError(t, updateAllDatabaseTablesStructure(ctx))
	db := services.MonthlyBudgets.UserDataDB(70001)
	sess := db.NewSession(nil)
	defer sess.Close()
	_, err := sess.Insert(&models.User{Uid: 70001, Username: "synthetic-provider-budget", Email: "provider-budget@example.invalid", DefaultCurrency: "CNY"})
	require.NoError(t, err)
	_, err = sess.Exec("DROP TABLE monthly_budget")
	require.NoError(t, err)
	require.NoError(t, datastore.Container.UserDataStore.SyncStructs(new(legacyProviderMonthlyBudget)))
	amounts := make(map[string]int64)
	_, err = sess.Insert(&models.TransactionCategory{CategoryId: 890000, Uid: 70001, Type: models.CATEGORY_TYPE_EXPENSE, Name: "Synthetic budget root"})
	require.NoError(t, err)
	for i := int64(0); i < 80; i++ {
		id := 890001 + i
		amounts[strconv.FormatInt(id, 10)] = 1234567890
		_, err = sess.Insert(&models.TransactionCategory{CategoryId: id, Uid: 70001, Type: models.CATEGORY_TYPE_EXPENSE, ParentCategoryId: 890000, Name: "Synthetic budget leaf"})
		require.NoError(t, err)
	}
	raw, err := json.Marshal(amounts)
	require.NoError(t, err)
	require.Greater(t, len(raw), 1024, "regression must retain a real TEXT payload rather than a short VARCHAR sample")
	_, err = sess.Insert(&legacyProviderMonthlyBudget{MonthlyBudgetId: 700001, Uid: 70001, YearMonth: 202601, Amount: 200000000000, CategoryAmountsJson: "{}"},
		&legacyProviderMonthlyBudget{MonthlyBudgetId: 700002, Uid: 70001, YearMonth: 202602, Amount: 200000000000, CategoryAmountsJson: string(raw)})
	require.NoError(t, err)
	// Sync2 must add the real scope/currency columns and the production migration
	// must preserve both old JSON values while freezing the owner's currency.
	require.NoError(t, updateAllDatabaseTablesStructure(ctx))
	for month, expected := range map[int32]string{202601: "{}", 202602: string(raw)} {
		budget, err := services.MonthlyBudgets.GetMonthlyBudget(nil, 70001, month)
		require.NoError(t, err)
		require.NotNil(t, budget)
		require.Equal(t, expected, budget.CategoryAmountsJson)
		require.Equal(t, "CNY", budget.Currency)
		require.Equal(t, int64(1), budget.Revision)
	}
	fresh, err := services.MonthlyBudgets.SetMonthlyBudget(nil, 70001, 202603, 200000000000, nil)
	require.NoError(t, err)
	require.Equal(t, "{}", fresh.CategoryAmountsJson, "application serialization owns the empty map default")
	populated, err := services.MonthlyBudgets.SetMonthlyBudget(nil, 70001, 202604, 200000000000, amounts)
	require.NoError(t, err)
	read, err := services.MonthlyBudgets.GetMonthlyBudget(nil, 70001, 202604)
	require.NoError(t, err)
	require.Equal(t, populated.CategoryAmountsJson, read.CategoryAmountsJson)
	require.Equal(t, amounts, read.ToMonthlyBudgetInfoResponse().CategoryAmounts)
	_, err = sess.ID(70001).Cols("default_currency").Update(&models.User{DefaultCurrency: "USD"})
	require.NoError(t, err)
	require.NoError(t, updateAllDatabaseTablesStructure(ctx))
	legacy, err := services.MonthlyBudgets.GetMonthlyBudget(nil, 70001, 202602)
	require.NoError(t, err)
	require.Equal(t, string(raw), legacy.CategoryAmountsJson)
	require.Equal(t, "CNY", legacy.Currency, "repeated startup must not reinterpret stored amounts in a new default currency")
	cleared, err := services.MonthlyBudgets.SetMonthlyBudget(nil, 70001, 202602, 5000, nil)
	require.NoError(t, err)
	require.Equal(t, "{}", cleared.CategoryAmountsJson)
	require.Equal(t, "CNY", cleared.Currency)
	require.Equal(t, int64(2), cleared.Revision)
	require.NoError(t, services.MonthlyBudgets.DeleteMonthlyBudget(nil, 70001, 202601))
	deleted, err := services.MonthlyBudgets.GetMonthlyBudget(nil, 70001, 202601)
	require.NoError(t, err)
	require.Nil(t, deleted)
	restored, err := services.MonthlyBudgets.SetMonthlyBudgetInLedger(nil, 70001, 0, 202601, 6000, nil, "CNY")
	require.NoError(t, err)
	require.Equal(t, int64(700001), restored.MonthlyBudgetId)
	require.Equal(t, int64(3), restored.Revision, "delete and restore retain the historical version sequence")
	require.Equal(t, "CNY", restored.Currency)
	require.Equal(t, "{}", restored.CategoryAmountsJson)
	// A corrupt stored empty string remains an explicit data error. Removing a
	// DDL default must never silently interpret unknown JSON as an empty budget.
	scope := "70001/0/202605"
	_, err = sess.Insert(&models.MonthlyBudget{MonthlyBudgetId: 700005, Uid: 70001, YearMonth: 202605, Amount: 1,
		Currency: "CNY", Revision: 1, ActiveBudgetScope: &scope, CategoryAmountsJson: ""})
	require.NoError(t, err)
	_, err = services.MonthlyBudgets.GetMonthlyBudget(nil, 70001, 202605)
	require.ErrorIs(t, err, errs.ErrMonthlyBudgetCategoryInvalid)
}
