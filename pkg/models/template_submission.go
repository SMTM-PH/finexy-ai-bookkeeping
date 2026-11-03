package models

// TemplateSubmission is an immutable creation receipt in the template owner's
// shard. It survives restarts and template deletion; keys must never be reused.
type TemplateSubmission struct {
	Uid             int64  `xorm:"PK NOT NULL"`
	CreatorUid      int64  `xorm:"PK NOT NULL"`
	LedgerId        int64  `xorm:"PK NOT NULL"`
	ClientSessionId string `xorm:"PK VARCHAR(128) NOT NULL"`
	RequestHash     string `xorm:"VARCHAR(64) NOT NULL"`
	TemplateId      int64  `xorm:"NOT NULL"`
	CreatedUnixTime int64  `xorm:"NOT NULL"`
}
