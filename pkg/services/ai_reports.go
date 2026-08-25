package services

import (
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
)

type AIReportService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

var AIReports = &AIReportService{
	ServiceUsingDB:   ServiceUsingDB{container: datastore.Container},
	ServiceUsingUuid: ServiceUsingUuid{container: uuid.Container},
}

func (s *AIReportService) CreatePending(c core.Context, uid int64, yearMonth, comparedYearMonth int32, provider, modelID, fingerprint string) (*models.AiReport, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}
	if !isValidYearMonth(yearMonth) || !isValidYearMonth(comparedYearMonth) {
		return nil, errs.ErrAIReportYearMonthInvalid
	}
	now := time.Now().Unix()
	report := &models.AiReport{
		AiReportId: s.GenerateUuid(uuid.UUID_TYPE_AI_REPORT), Uid: uid, YearMonth: yearMonth,
		ComparedYearMonth: comparedYearMonth, Status: models.AI_REPORT_STATUS_PENDING,
		Provider: provider, ModelId: modelID, DataFingerprint: fingerprint,
		GeneratedUnixTime: now, CreatedUnixTime: now, UpdatedUnixTime: now,
	}
	if report.AiReportId < 1 {
		return nil, errs.ErrSystemIsBusy
	}
	_, err := s.UserDataDB(uid).NewSession(c).Insert(report)
	return report, err
}

func (s *AIReportService) Complete(c core.Context, uid, id int64, content string) error {
	update := &models.AiReport{Status: models.AI_REPORT_STATUS_COMPLETED, Content: content, ErrorMessage: "", UpdatedUnixTime: time.Now().Unix()}
	rows, err := s.UserDataDB(uid).NewSession(c).Cols("status", "content", "error_message", "updated_unix_time").Where("ai_report_id=? AND uid=? AND deleted=?", id, uid, false).Update(update)
	if err != nil {
		return err
	}
	if rows < 1 {
		return errs.ErrAIReportNotFound
	}
	return nil
}

func (s *AIReportService) Fail(c core.Context, uid, id int64, message string) error {
	if len(message) > 255 {
		message = message[:255]
	}
	update := &models.AiReport{Status: models.AI_REPORT_STATUS_FAILED, ErrorMessage: message, UpdatedUnixTime: time.Now().Unix()}
	rows, err := s.UserDataDB(uid).NewSession(c).Cols("status", "error_message", "updated_unix_time").Where("ai_report_id=? AND uid=? AND deleted=?", id, uid, false).Update(update)
	if err != nil {
		return err
	}
	if rows < 1 {
		return errs.ErrAIReportNotFound
	}
	return nil
}

func (s *AIReportService) List(c core.Context, uid int64) ([]*models.AiReport, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}
	reports := make([]*models.AiReport, 0)
	err := s.UserDataDB(uid).NewSession(c).Where("uid=? AND deleted=?", uid, false).Desc("generated_unix_time").Limit(50).Find(&reports)
	return reports, err
}

func (s *AIReportService) DeleteAll(c core.Context, uid int64) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}
	now := time.Now().Unix()
	update := &models.AiReport{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now}
	_, err := s.UserDataDB(uid).NewSession(c).Cols("deleted", "deleted_unix_time", "updated_unix_time").Where("uid=? AND deleted=?", uid, false).Update(update)
	return err
}
