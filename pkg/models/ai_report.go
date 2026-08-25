package models

// AiReportStatus represents the generation state of an on-demand AI report.
type AiReportStatus byte

const (
	AI_REPORT_STATUS_PENDING   AiReportStatus = 1
	AI_REPORT_STATUS_COMPLETED AiReportStatus = 2
	AI_REPORT_STATUS_FAILED    AiReportStatus = 3
)

// AiReport represents a saved comparison of one month with its previous month.
type AiReport struct {
	AiReportId        int64          `xorm:"PK"`
	Uid               int64          `xorm:"INDEX(IDX_ai_report_uid_deleted_year_month_generated_time) NOT NULL"`
	Deleted           bool           `xorm:"INDEX(IDX_ai_report_uid_deleted_year_month_generated_time) NOT NULL"`
	YearMonth         int32          `xorm:"INDEX(IDX_ai_report_uid_deleted_year_month_generated_time) NOT NULL"`
	ComparedYearMonth int32          `xorm:"NOT NULL"`
	Status            AiReportStatus `xorm:"NOT NULL"`
	Provider          string         `xorm:"VARCHAR(32) NOT NULL"`
	ModelId           string         `xorm:"VARCHAR(128) NOT NULL"`
	DataFingerprint   string         `xorm:"VARCHAR(64) NOT NULL"`
	Content           string         `xorm:"MEDIUMTEXT"`
	ErrorMessage      string         `xorm:"VARCHAR(255) NOT NULL"`
	GeneratedUnixTime int64          `xorm:"INDEX(IDX_ai_report_uid_deleted_year_month_generated_time) NOT NULL"`
	CreatedUnixTime   int64
	UpdatedUnixTime   int64
	DeletedUnixTime   int64
}

type AIReportGenerateRequest struct {
	YearMonth int32 `json:"yearMonth" binding:"required,min=200001,max=999912"`
}

type AIReportInfoResponse struct {
	Id                int64          `json:"id,string"`
	YearMonth         int32          `json:"yearMonth"`
	ComparedYearMonth int32          `json:"comparedYearMonth"`
	Status            AiReportStatus `json:"status"`
	Provider          string         `json:"provider"`
	ModelId           string         `json:"modelId"`
	Content           string         `json:"content"`
	ErrorMessage      string         `json:"errorMessage"`
	GeneratedUnixTime int64          `json:"generatedUnixTime"`
}

type AIReportLLMResult struct {
	Content string `json:"content"`
}

func (report *AiReport) ToInfoResponse() *AIReportInfoResponse {
	return &AIReportInfoResponse{
		Id: report.AiReportId, YearMonth: report.YearMonth, ComparedYearMonth: report.ComparedYearMonth,
		Status: report.Status, Provider: report.Provider, ModelId: report.ModelId, Content: report.Content,
		ErrorMessage: report.ErrorMessage, GeneratedUnixTime: report.GeneratedUnixTime,
	}
}
