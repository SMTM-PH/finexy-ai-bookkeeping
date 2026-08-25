package avatars

import (
	"fmt"
	"strings"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
)

// Reference: https://en.gravatar.com/site/implement/hash/
const gravatarUrlFormat = "https://www.gravatar.com/avatar/%s"

// GravatarAvatarProvider represents the gravatar avatar provider
type GravatarAvatarProvider struct {
}

// NewGravatarAvatarProvider returns a new gravatar avatar provider
func NewGravatarAvatarProvider() *GravatarAvatarProvider {
	return &GravatarAvatarProvider{}
}

// GetAvatarUrl returns the gravatar url
func (p *GravatarAvatarProvider) GetAvatarUrl(user *models.User) string {
	email := user.Email
	email = strings.TrimSpace(email)
	email = strings.ToLower(email)
	emailMd5 := utils.MD5EncodeToString([]byte(email))

	return fmt.Sprintf(gravatarUrlFormat, emailMd5)
}
