package models

// TransactionSubmission keeps the result of a client operation in the same
// shard and transaction as its financial writes. It has no expiry: deleting a
// transaction must never make a lost-response retry create it again.
type TransactionSubmission struct {
	Uid             int64  `xorm:"PK NOT NULL"`
	RecorderUid     int64  `xorm:"PK NOT NULL"`
	LedgerId        int64  `xorm:"PK NOT NULL"`
	ClientSessionId string `xorm:"PK VARCHAR(128) NOT NULL"`
	RequestHash     string `xorm:"VARCHAR(64) NOT NULL"`
	TransactionId   int64  `xorm:"NOT NULL"`
	CreatedUnixTime int64  `xorm:"NOT NULL"`
}
