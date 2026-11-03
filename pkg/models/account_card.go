package models

// AccountCardAppearance is a personal presentation, never account/ledger financial data.
type AccountCardAppearance struct {
	Uid            int64  `xorm:"PK"`
	LedgerId       int64  `xorm:"PK"`
	AccountId      int64  `xorm:"PK"`
	Revision       int64  `xorm:"NOT NULL"`
	AppearanceJSON string `xorm:"'appearance_json' BLOB NOT NULL"`
}

type AccountCardStyle struct {
	Revision    int64  `json:"revision"`
	Theme       string `json:"theme"`
	Background  string `json:"background"`
	Subject     string `json:"subject"`
	ImageX      int    `json:"imageX"`
	ImageY      int    `json:"imageY"`
	ImageZoom   int    `json:"imageZoom"`
	SubjectX    int    `json:"subjectX"`
	SubjectY    int    `json:"subjectY"`
	SubjectZoom int    `json:"subjectZoom"`
	Foil        int    `json:"foil"`
	Depth       int    `json:"depth"`
}

func DefaultAccountCardStyle() AccountCardStyle {
	return AccountCardStyle{Theme: "classic", ImageX: 50, ImageY: 50, ImageZoom: 100, SubjectX: 50, SubjectY: 50, SubjectZoom: 100, Foil: 22, Depth: 50}
}

type AccountCardTarget struct {
	LedgerId  *int64 `json:"ledgerId,string" form:"ledgerId" binding:"required,min=0"`
	AccountId int64  `json:"accountId,string" form:"accountId" binding:"required,min=1"`
}

type AccountCardUpdateRequest struct {
	AccountCardTarget
	Appearance *AccountCardStyle `json:"appearance" binding:"required"`
}
