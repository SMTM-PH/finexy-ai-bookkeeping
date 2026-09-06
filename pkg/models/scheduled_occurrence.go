package models

import "fmt"

// ScheduledOccurrence is a durable review item, not a posted transaction.
// The composite primary key makes dispatch of a given occurrence idempotent.
type ScheduledOccurrence struct {
	Uid               int64                     `xorm:"PK NOT NULL"`
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
	if s.Type < TRANSACTION_TYPE_INCOME || s.Type > TRANSACTION_TYPE_TRANSFER || s.SourceAccountId <= 0 || s.CategoryId <= 0 ||
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
	return nil
}
