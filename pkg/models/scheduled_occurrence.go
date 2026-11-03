package models

import (
	"fmt"
	"strconv"
)

// ScheduledOccurrence is a durable review item, not a posted transaction.
// The composite primary key makes dispatch of a given occurrence idempotent.
type ScheduledOccurrence struct {
	Uid               int64                     `xorm:"PK NOT NULL"`
	LedgerId          int64                     `xorm:"INDEX NOT NULL DEFAULT 0"`
	TemplateId        int64                     `xorm:"PK NOT NULL"`
	ScheduledUnixTime int64                     `xorm:"PK NOT NULL"`
	Status            ScheduledOccurrenceStatus `xorm:"NOT NULL"`
	SnapshotJSON      string                    `xorm:"TEXT NOT NULL"`
	TransactionId     int64
	CreatedUnixTime   int64
	UpdatedUnixTime   int64
}

type ScheduledOccurrenceStatus byte

const (
	ScheduledOccurrencePending   ScheduledOccurrenceStatus = 1
	ScheduledOccurrenceConfirmed ScheduledOccurrenceStatus = 2
	ScheduledOccurrenceDismissed ScheduledOccurrenceStatus = 3
)

// ScheduledOccurrenceSnapshot preserves monetary values and IDs without float conversion.
type ScheduledOccurrenceSnapshot struct {
	LedgerId             int64           `json:"ledgerId,string"`
	Name                 string          `json:"name"`
	Type                 TransactionType `json:"type"`
	CategoryId           int64           `json:"categoryId,string"`
	SourceAccountId      int64           `json:"sourceAccountId,string"`
	DestinationAccountId int64           `json:"destinationAccountId,string"`
	SourceAmount         int64           `json:"sourceAmount"`
	DestinationAmount    int64           `json:"destinationAmount"`
	UtcOffset            int16           `json:"utcOffset"`
	HideAmount           bool            `json:"hideAmount"`
	TagIds               []string        `json:"tagIds"`
	Comment              string          `json:"comment"`
}

// Validate rejects incomplete persisted snapshots before they reach the ledger.
func (s ScheduledOccurrenceSnapshot) Validate() error {
	const maximumAmount = int64(9999999999999)
	if s.LedgerId < 0 || s.Type < TRANSACTION_TYPE_INCOME || s.Type > TRANSACTION_TYPE_TRANSFER || s.SourceAccountId <= 0 || s.CategoryId <= 0 ||
		s.SourceAmount < -maximumAmount || s.SourceAmount > maximumAmount ||
		s.DestinationAmount < -maximumAmount || s.DestinationAmount > maximumAmount || s.UtcOffset < -720 || s.UtcOffset > 840 || len(s.TagIds) > MaximumTagsCountOfTransaction {
		return fmt.Errorf("invalid scheduled transaction snapshot")
	}
	if s.Type == TRANSACTION_TYPE_TRANSFER {
		if s.DestinationAccountId <= 0 || s.DestinationAccountId == s.SourceAccountId || s.SourceAmount < 0 || s.DestinationAmount < 0 {
			return fmt.Errorf("invalid scheduled transfer")
		}
	} else if s.DestinationAccountId != 0 || s.DestinationAmount != 0 {
		return fmt.Errorf("non-transfer snapshot has destination fields")
	}
	seenTags := make(map[int64]bool, len(s.TagIds))
	for _, raw := range s.TagIds {
		id, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || id <= 0 || strconv.FormatInt(id, 10) != raw || seenTags[id] {
			return fmt.Errorf("invalid scheduled tag ID")
		}
		seenTags[id] = true
	}
	return nil
}
