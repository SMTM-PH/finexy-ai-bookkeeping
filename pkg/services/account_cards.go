package services

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"strings"
	"sync"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"xorm.io/xorm"
)

type AccountCardService struct{ ServiceUsingDB }

var AccountCards = &AccountCardService{ServiceUsingDB: ServiceUsingDB{container: datastore.Container}}

// Serialize a bounded set of local writes to avoid SQLite read/insert lock upgrades.
// Revision predicates and the composite primary key still protect multiple instances.
var accountCardSaveLocks [64]sync.Mutex

func validateCardImage(value string) bool {
	if value == "" {
		return true
	}
	if len(value) > 700000 {
		return false
	}
	parts := strings.SplitN(value, ",", 2)
	if len(parts) != 2 {
		return false
	}
	format := ""
	switch parts[0] {
	case "data:image/jpeg;base64":
		format = "jpeg"
	case "data:image/png;base64":
		format = "png"
	default:
		return false
	}
	data, err := base64.StdEncoding.Strict().DecodeString(parts[1])
	if err != nil || len(data) > 512*1024 {
		return false
	}
	cfg, decoded, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil || decoded != format || cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 1600 || cfg.Height > 1600 {
		return false
	}
	// Decode completely so truncated/corrupt images cannot be persisted as valid artwork.
	_, _, err = image.Decode(bytes.NewReader(data))
	return err == nil
}

func ValidateAccountCardStyle(style models.AccountCardStyle) error {
	if style.Revision < 0 || style.Revision > 9007199254740990 {
		return errs.ErrParameterInvalid
	}
	switch style.Theme {
	case "classic", "midnight", "mint", "copper":
	default:
		return errs.ErrParameterInvalid
	}
	for _, v := range []int{style.ImageX, style.ImageY, style.SubjectX, style.SubjectY, style.Foil, style.Depth} {
		if v < 0 || v > 100 {
			return errs.ErrParameterInvalid
		}
	}
	if style.ImageZoom < 100 || style.ImageZoom > 200 || style.SubjectZoom < 25 || style.SubjectZoom > 200 || !validateCardImage(style.Background) || !validateCardImage(style.Subject) {
		return errs.ErrParameterInvalid
	}
	return nil
}

func (s *AccountCardService) authorize(c core.Context, uid, ledgerId, accountId int64) error {
	accounts, err := Accounts.GetAccountsInLedger(c, uid, ledgerId)
	if err != nil {
		return err
	}
	for _, account := range accounts {
		if account.AccountId == accountId && account.Type == models.ACCOUNT_TYPE_SINGLE_ACCOUNT {
			return nil
		}
	}
	return errs.ErrAccountNotFound
}

func (s *AccountCardService) Get(c core.Context, uid, ledgerId, accountId int64) (*models.AccountCardStyle, error) {
	if err := s.authorize(c, uid, ledgerId, accountId); err != nil {
		return nil, err
	}
	row := &models.AccountCardAppearance{}
	has, err := s.UserDataDB(uid).NewSession(c).Where("uid=? AND ledger_id=? AND account_id=?", uid, ledgerId, accountId).Get(row)
	if err != nil {
		return nil, err
	}
	style := models.DefaultAccountCardStyle()
	if has {
		if err = json.Unmarshal([]byte(row.AppearanceJSON), &style); err != nil {
			return nil, errs.ErrOperationFailed
		}
		style.Revision = row.Revision
	}
	return &style, nil
}

func (s *AccountCardService) Save(c core.Context, uid, ledgerId, accountId int64, style models.AccountCardStyle) (*models.AccountCardStyle, error) {
	if err := ValidateAccountCardStyle(style); err != nil {
		return nil, err
	}
	if err := s.authorize(c, uid, ledgerId, accountId); err != nil {
		return nil, err
	}
	previous := style.Revision
	style.Revision++
	data, err := json.Marshal(style)
	if err != nil {
		return nil, err
	}
	row := &models.AccountCardAppearance{Uid: uid, LedgerId: ledgerId, AccountId: accountId, Revision: style.Revision, AppearanceJSON: string(data)}
	lock := &accountCardSaveLocks[uint64(uid)%uint64(len(accountCardSaveLocks))]
	lock.Lock()
	defer lock.Unlock()
	err = s.UserDataDB(uid).DoTransaction(c, func(sess *xorm.Session) error {
		if previous == 0 {
			exists, e := sess.Where("uid=? AND ledger_id=? AND account_id=?", uid, ledgerId, accountId).Exist(&models.AccountCardAppearance{})
			if e != nil {
				return e
			}
			if exists {
				return errs.ErrAccountCardConflict
			}
			_, e = sess.Insert(row)
			return e
		}
		n, e := sess.Where("uid=? AND ledger_id=? AND account_id=? AND revision=?", uid, ledgerId, accountId, previous).Cols("revision", "appearance_json").Update(row)
		if e != nil {
			return e
		}
		if n != 1 {
			return errs.ErrAccountCardConflict
		}
		return nil
	})
	if err != nil {
		current := &models.AccountCardAppearance{}
		has, readErr := s.UserDataDB(uid).NewSession(c).Where("uid=? AND ledger_id=? AND account_id=?", uid, ledgerId, accountId).Get(current)
		if readErr == nil && has && current.Revision > previous {
			return nil, errs.ErrAccountCardConflict
		}
		return nil, err
	}
	return &style, nil
}
