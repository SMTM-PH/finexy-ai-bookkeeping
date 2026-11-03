package mcp

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/settings"
	"testing"
)

func TestManagementSchemasAndScopes(t *testing.T) {
	if err := InitializeMCPHandlers(&settings.Config{}); err != nil {
		t.Fatal(err)
	}
	count := 0
	for _, tool := range Container.GetMCPTools() {
		if AgentToolScope(tool.Name) != core.AgentScopeManage {
			continue
		}
		count++
		if tool.Annotations["readOnlyHint"] {
			t.Fatalf("write tool marked readonly: %s", tool.Name)
		}
		for _, key := range []string{"ledgerId", "confirmed"} {
			if _, exists := tool.InputSchema.Properties.Get(key); !exists {
				t.Fatalf("%s missing %s", tool.Name, key)
			}
		}
		if id, exists := tool.InputSchema.Properties.Get("id"); exists && id.Type != "string" {
			t.Fatalf("%s ID schema loses precision", tool.Name)
		}
	}
	if count != 8 {
		t.Fatalf("expected 8 tools, got %d", count)
	}
}
