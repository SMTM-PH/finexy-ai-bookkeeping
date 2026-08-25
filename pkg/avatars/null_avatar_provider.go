package avatars

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
)

// NullAvatarProvider represents the null avatar provider
type NullAvatarProvider struct {
}

// NewNullAvatarProvider returns a new null avatar provider
func NewNullAvatarProvider() *NullAvatarProvider {
	return &NullAvatarProvider{}
}

// GetAvatarUrl returns an empty url
func (p *NullAvatarProvider) GetAvatarUrl(user *models.User) string {
	return ""
}
