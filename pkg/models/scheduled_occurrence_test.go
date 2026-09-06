package models

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestScheduledOccurrenceSnapshotPreservesTransfer(t *testing.T) {
	original := ScheduledOccurrenceSnapshot{Type: TRANSACTION_TYPE_TRANSFER, SourceAccountId: 9007199254740993,
		DestinationAccountId: 9007199254740995, SourceAmount: 12345, DestinationAmount: 67890,
		UtcOffset: -240, HideAmount: true, TagIds: []string{"9007199254740997"}, Comment: "计划转账"}
	raw, err := json.Marshal(original)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), `"sourceAccountId":"9007199254740993"`) {
		t.Fatal("ID must be encoded as string")
	}
	var restored ScheduledOccurrenceSnapshot
	if err := json.Unmarshal(raw, &restored); err != nil {
		t.Fatal(err)
	}
	if restored.SourceAccountId != original.SourceAccountId || restored.DestinationAccountId != original.DestinationAccountId ||
		restored.SourceAmount != original.SourceAmount || restored.DestinationAmount != original.DestinationAmount ||
		restored.UtcOffset != original.UtcOffset || !restored.HideAmount || restored.TagIds[0] != original.TagIds[0] {
		t.Fatal("snapshot lost transaction fields")
	}
}

func TestScheduledSnapshotRejectsIncompleteLedgerFields(t *testing.T) {
	valid := ScheduledOccurrenceSnapshot{Type: TRANSACTION_TYPE_EXPENSE, SourceAccountId: 1, CategoryId: 2, SourceAmount: 100, UtcOffset: 480}
	if err := valid.Validate(); err != nil {
		t.Fatal(err)
	}
	missing := valid
	missing.CategoryId = 0
	overflow := valid
	overflow.SourceAmount = 10000000000000
	zone := valid
	zone.UtcOffset = 841
	transfer := valid
	transfer.Type = TRANSACTION_TYPE_TRANSFER
	transfer.DestinationAccountId = 1
	for _, snapshot := range []ScheduledOccurrenceSnapshot{{}, missing, overflow, zone, transfer} {
		if snapshot.Validate() == nil {
			t.Fatal("invalid snapshot accepted")
		}
	}
}
