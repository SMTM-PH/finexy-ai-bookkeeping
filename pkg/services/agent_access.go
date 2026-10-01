package services

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
)

// IsMCPAccessEnabled reads the persisted preference on every request, so changes
// apply to existing tokens without relying on process-local caches.
func (s *UserService) IsMCPAccessEnabled(c core.Context, uid int64) (bool, error) {
	setting := &models.AgentAccessSetting{}
	_, err := s.UserDB().NewSession(c).ID(uid).Get(setting)
	return !setting.MCPDisabled, err
}

func (s *UserService) SetMCPAccessEnabled(c core.Context, uid int64, enabled bool) error {
	setting := &models.AgentAccessSetting{Uid: uid, MCPDisabled: !enabled}
	rows, err := s.UserDB().NewSession(c).ID(uid).Cols("mcp_disabled").Update(setting)
	if err != nil {
		return err
	}
	if rows > 0 {
		return nil
	}
	_, err = s.UserDB().NewSession(c).Insert(setting)
	if err == nil {
		return nil
	}
	rows, updateErr := s.UserDB().NewSession(c).ID(uid).Cols("mcp_disabled").Update(setting)
	if updateErr != nil {
		return updateErr
	}
	if rows == 0 {
		return err
	}
	return nil
}
