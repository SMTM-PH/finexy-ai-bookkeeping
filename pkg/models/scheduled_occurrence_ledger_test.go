package models

import (
	"encoding/json"
	"reflect"
	"strconv"
	"testing"
)

func TestScheduledOccurrenceSnapshotLegacyPersonalLedger(t *testing.T) {
	const legacy = `{"name":"Synthetic refund","type":3,"categoryId":"9007199254740993","sourceAccountId":"9007199254740995","destinationAccountId":"0","sourceAmount":-12345,"destinationAmount":0,"utcOffset":480,"hideAmount":true,"tagIds":["9007199254740997"],"comment":"Legacy fixture"}`
	var snapshot ScheduledOccurrenceSnapshot
	if err := json.Unmarshal([]byte(legacy), &snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.LedgerId != 0 || snapshot.SourceAmount != -12345 || !snapshot.HideAmount {
		t.Fatal("legacy snapshot lost personal ledger or refund semantics")
	}
	if err := snapshot.Validate(); err != nil {
		t.Fatalf("legacy complete snapshot rejected: %v", err)
	}
	encoded, err := json.Marshal(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &fields); err != nil {
		t.Fatal(err)
	}
	if got := string(fields["ledgerId"]); got != `"0"` {
		t.Fatalf("legacy snapshot ledgerId = %s, want explicit personal ledger string", got)
	}
}

func TestScheduledOccurrenceSnapshotSharedLedgerRoundTrip(t *testing.T) {
	original := ScheduledOccurrenceSnapshot{
		LedgerId: 9007199254740993, Name: "Synthetic transfer", Type: TRANSACTION_TYPE_TRANSFER,
		CategoryId: 9007199254740995, SourceAccountId: 9007199254740997, DestinationAccountId: 9007199254740999,
		SourceAmount: 12345, DestinationAmount: 67890, UtcOffset: -240, HideAmount: true,
		TagIds: []string{"9007199254741001", "9223372036854775807"}, Comment: "Synthetic fixture",
	}
	if err := original.Validate(); err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(original)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &fields); err != nil {
		t.Fatal(err)
	}
	for field, want := range map[string]int64{
		"ledgerId": original.LedgerId, "categoryId": original.CategoryId,
		"sourceAccountId": original.SourceAccountId, "destinationAccountId": original.DestinationAccountId,
	} {
		if got := string(fields[field]); got != strconv.Quote(strconv.FormatInt(want, 10)) {
			t.Fatalf("%s = %s, must preserve the ID as a string", field, got)
		}
	}
	var restored ScheduledOccurrenceSnapshot
	if err := json.Unmarshal(encoded, &restored); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(original, restored) {
		t.Fatal("snapshot did not preserve its complete ledger-scoped transfer")
	}
	if err := restored.Validate(); err != nil {
		t.Fatal(err)
	}
}

func TestScheduledOccurrenceSnapshotRejectsNumericIDs(t *testing.T) {
	for _, field := range []string{"ledgerId", "categoryId", "sourceAccountId", "destinationAccountId"} {
		t.Run(field, func(t *testing.T) {
			var snapshot ScheduledOccurrenceSnapshot
			if err := json.Unmarshal([]byte(`{"`+field+`":9007199254740993}`), &snapshot); err == nil {
				t.Fatal("numeric ID accepted instead of the string contract")
			}
		})
	}
}

func TestScheduledOccurrenceSnapshotValidLedgerFinancialBoundaries(t *testing.T) {
	const maximum = int64(9999999999999)
	base := ScheduledOccurrenceSnapshot{LedgerId: 9007199254740993, Type: TRANSACTION_TYPE_EXPENSE,
		CategoryId: 2, SourceAccountId: 3, SourceAmount: 12345, UtcOffset: 480}
	cases := []struct {
		name   string
		change func(*ScheduledOccurrenceSnapshot)
	}{
		{"personal ledger", func(s *ScheduledOccurrenceSnapshot) { s.LedgerId = 0 }},
		{"maximum ledger ID", func(s *ScheduledOccurrenceSnapshot) { s.LedgerId = 9223372036854775807 }},
		{"expense maximum", func(s *ScheduledOccurrenceSnapshot) { s.SourceAmount = maximum }},
		{"refund maximum", func(s *ScheduledOccurrenceSnapshot) { s.SourceAmount = -maximum }},
		{"zero expense", func(s *ScheduledOccurrenceSnapshot) { s.SourceAmount = 0 }},
		{"income", func(s *ScheduledOccurrenceSnapshot) { s.Type = TRANSACTION_TYPE_INCOME }},
		{"minimum timezone", func(s *ScheduledOccurrenceSnapshot) { s.UtcOffset = -720 }},
		{"maximum timezone", func(s *ScheduledOccurrenceSnapshot) { s.UtcOffset = 840 }},
		{"maximum tags", func(s *ScheduledOccurrenceSnapshot) {
			for id := 1; id <= MaximumTagsCountOfTransaction; id++ {
				s.TagIds = append(s.TagIds, strconv.Itoa(id))
			}
		}},
		{"distinct transfer amounts", func(s *ScheduledOccurrenceSnapshot) {
			s.Type, s.DestinationAccountId, s.DestinationAmount = TRANSACTION_TYPE_TRANSFER, 4, 67890
		}},
		{"maximum transfer amounts", func(s *ScheduledOccurrenceSnapshot) {
			s.Type, s.DestinationAccountId = TRANSACTION_TYPE_TRANSFER, 4
			s.SourceAmount, s.DestinationAmount = maximum, maximum
		}},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			snapshot := base
			test.change(&snapshot)
			if err := snapshot.Validate(); err != nil {
				t.Fatalf("valid ledger snapshot rejected: %v", err)
			}
		})
	}
}

func TestScheduledOccurrenceSnapshotRejectsInvalidLedgerFinancialFields(t *testing.T) {
	const overflow = int64(10000000000000)
	base := ScheduledOccurrenceSnapshot{LedgerId: 9007199254740993, Type: TRANSACTION_TYPE_EXPENSE,
		CategoryId: 2, SourceAccountId: 3, SourceAmount: 12345, UtcOffset: 480}
	cases := []struct {
		name   string
		change func(*ScheduledOccurrenceSnapshot)
	}{
		{"negative ledger", func(s *ScheduledOccurrenceSnapshot) { s.LedgerId = -1 }},
		{"missing transaction type", func(s *ScheduledOccurrenceSnapshot) { s.Type = 0 }},
		{"balance adjustment", func(s *ScheduledOccurrenceSnapshot) { s.Type = TRANSACTION_TYPE_MODIFY_BALANCE }},
		{"unknown transaction type", func(s *ScheduledOccurrenceSnapshot) { s.Type = 5 }},
		{"missing category", func(s *ScheduledOccurrenceSnapshot) { s.CategoryId = 0 }},
		{"negative category", func(s *ScheduledOccurrenceSnapshot) { s.CategoryId = -1 }},
		{"missing source account", func(s *ScheduledOccurrenceSnapshot) { s.SourceAccountId = 0 }},
		{"negative source account", func(s *ScheduledOccurrenceSnapshot) { s.SourceAccountId = -1 }},
		{"source amount overflow", func(s *ScheduledOccurrenceSnapshot) { s.SourceAmount = overflow }},
		{"refund amount overflow", func(s *ScheduledOccurrenceSnapshot) { s.SourceAmount = -overflow }},
		{"timezone below range", func(s *ScheduledOccurrenceSnapshot) { s.UtcOffset = -721 }},
		{"timezone above range", func(s *ScheduledOccurrenceSnapshot) { s.UtcOffset = 841 }},
		{"expense destination account", func(s *ScheduledOccurrenceSnapshot) { s.DestinationAccountId = 4 }},
		{"expense destination amount", func(s *ScheduledOccurrenceSnapshot) { s.DestinationAmount = 100 }},
		{"expense negative destination amount", func(s *ScheduledOccurrenceSnapshot) { s.DestinationAmount = -100 }},
		{"income destination account", func(s *ScheduledOccurrenceSnapshot) {
			s.Type, s.DestinationAccountId = TRANSACTION_TYPE_INCOME, 4
		}},
		{"too many tags", func(s *ScheduledOccurrenceSnapshot) {
			for id := 1; id <= MaximumTagsCountOfTransaction+1; id++ {
				s.TagIds = append(s.TagIds, strconv.Itoa(id))
			}
		}},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			snapshot := base
			test.change(&snapshot)
			if err := snapshot.Validate(); err == nil {
				t.Fatal("invalid ledger snapshot was accepted")
			}
		})
	}
}

func TestScheduledOccurrenceSnapshotRejectsInvalidLedgerTransfers(t *testing.T) {
	base := ScheduledOccurrenceSnapshot{LedgerId: 7, Type: TRANSACTION_TYPE_TRANSFER,
		CategoryId: 2, SourceAccountId: 3, DestinationAccountId: 4, SourceAmount: 12345, DestinationAmount: 67890}
	cases := []struct {
		name   string
		change func(*ScheduledOccurrenceSnapshot)
	}{
		{"missing destination", func(s *ScheduledOccurrenceSnapshot) { s.DestinationAccountId = 0 }},
		{"negative destination", func(s *ScheduledOccurrenceSnapshot) { s.DestinationAccountId = -1 }},
		{"same account", func(s *ScheduledOccurrenceSnapshot) { s.DestinationAccountId = s.SourceAccountId }},
		{"negative source amount", func(s *ScheduledOccurrenceSnapshot) { s.SourceAmount = -1 }},
		{"negative destination amount", func(s *ScheduledOccurrenceSnapshot) { s.DestinationAmount = -1 }},
		{"destination amount overflow", func(s *ScheduledOccurrenceSnapshot) { s.DestinationAmount = 10000000000000 }},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			snapshot := base
			test.change(&snapshot)
			if err := snapshot.Validate(); err == nil {
				t.Fatal("invalid ledger transfer was accepted")
			}
		})
	}
}

func TestScheduledOccurrenceSnapshotRejectsNonCanonicalTagIDs(t *testing.T) {
	base := ScheduledOccurrenceSnapshot{LedgerId: 7, Type: TRANSACTION_TYPE_EXPENSE, CategoryId: 2, SourceAccountId: 3, SourceAmount: -100}
	for _, raw := range []string{"", "0", "-1", "01", "+1", " 1", "1 ", "1.0", "1e2", "9223372036854775808"} {
		t.Run(strconv.Quote(raw), func(t *testing.T) {
			snapshot := base
			snapshot.TagIds = []string{raw}
			if err := snapshot.Validate(); err == nil {
				t.Fatal("non-canonical tag ID was accepted")
			}
		})
	}
	duplicate := base
	duplicate.TagIds = []string{"9007199254740993", "9007199254740993"}
	if err := duplicate.Validate(); err == nil {
		t.Fatal("duplicate tag IDs were accepted")
	}
}
