//go:build cgo

package usage

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
)

func TestUsageSummaryUserIsolationAndLocalDayBoundaries(t *testing.T) {
	if err := datastore.InitializeDataStore(&settings.Config{DatabaseConfig: &settings.DatabaseConfig{DatabaseType: settings.Sqlite3DbType, DatabasePath: filepath.Join(t.TempDir(), "usage.db"), MaxOpenConnection: 1, MaxIdleConnection: 1}}); err != nil {
		t.Fatal(err)
	}
	if err := datastore.Container.UserStore.SyncStructs(new(models.AIUsageRecord)); err != nil {
		t.Fatal(err)
	}
	c := core.NewNullContext()
	zone := time.FixedZone("UTC+8", 8*3600)
	now := time.Now().In(zone)
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, zone)
	rows := []*models.AIUsageRecord{
		{Uid: 1, CreatedUnixTime: today.Add(-time.Second).Unix(), UsageKnown: true, CacheUsageKnown: true, Success: true, InputTokens: 100, OutputTokens: 50, TotalTokens: 150, CachedTokens: 70, DurationMs: 1000},
		{Uid: 1, CreatedUnixTime: today.Unix(), UsageKnown: true, Success: true, InputTokens: 20, OutputTokens: 10, TotalTokens: 30, DurationMs: 2000},
		{Uid: 1, CreatedUnixTime: now.Unix(), Success: false, DurationMs: 500},
		{Uid: 2, CreatedUnixTime: now.Unix(), UsageKnown: true, Success: true, TotalTokens: 9999},
	}
	for _, row := range rows {
		if err := Save(c, row); err != nil {
			t.Fatal(err)
		}
	}
	r, err := Read(c, 1, zone)
	if err != nil {
		t.Fatal(err)
	}
	if r.All.Requests != 3 || r.All.TotalTokens != 180 || r.All.FailedRequests != 1 || r.All.UnmeteredRequests != 1 || r.Today.TotalTokens != 30 || r.Today.Requests != 2 || len(r.Recent) != 3 {
		t.Fatalf("incorrect isolated aggregation: %+v", r)
	}
	if r.All.CachedTokens != 70 || r.All.TotalTokens != r.All.InputTokens+r.All.OutputTokens {
		t.Fatal("cache was counted twice")
	}
	if r.All.CacheMeteredRequests != 1 || r.All.ReasoningMeteredRequests != 0 || r.Today.CacheMeteredRequests != 0 {
		t.Fatal("missing optional metering fields must remain unknown")
	}
	other, err := Read(c, 2, zone)
	if err != nil || other.All.Requests != 1 || other.All.TotalTokens != 9999 {
		t.Fatal("other-user isolation failed")
	}
}
