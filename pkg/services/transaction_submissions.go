package services

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sort"
	"sync"
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
	"xorm.io/xorm"
)

var transactionSubmissionLocks [64]sync.Mutex

// ReplayTransactionSubmission acknowledges an already committed operation
// before new-only validations (for example, unattached pictures or edit time).
// It still verifies current ledger access and the complete original request.
func (s *TransactionService) ReplayTransactionSubmission(c core.Context, row *models.Transaction, tags, pictures []int64, key string) (bool, error) {
	if key == "" {
		return false, nil
	}
	if len(key) > 128 {
		return false, errs.ErrParameterInvalid
	}
	lock := &transactionSubmissionLocks[uint64(row.Uid)%uint64(len(transactionSubmissionLocks))]
	lock.Lock()
	defer lock.Unlock()
	actor := row.RecorderUid
	if actor <= 0 {
		actor = row.Uid
	}
	ledger, err := ImportLedger(c, actor, row.LedgerId)
	if err != nil {
		return false, err
	}
	if row.Uid != ledger.OwnerUid {
		return false, errs.ErrLedgerAccessDenied
	}
	sess := s.UserDataDB(row.Uid).NewSession(c)
	defer sess.Close()
	receipt := &models.TransactionSubmission{}
	found, err := sess.Where("uid=? AND recorder_uid=? AND ledger_id=? AND client_session_id=?", row.Uid, actor, row.LedgerId, key).Get(receipt)
	if err != nil || !found {
		return false, err
	}
	hash, err := transactionSubmissionHash(row, tags, pictures)
	if err != nil {
		return false, err
	}
	if hash != receipt.RequestHash {
		return false, errs.ErrParameterInvalid
	}
	committed, err := s.GetTransactionByTransactionIdInLedger(c, row.Uid, row.LedgerId, receipt.TransactionId)
	if err != nil {
		return false, err
	}
	*row = *committed
	return true, nil
}

func authorizeTransactionAccounts(sess *xorm.Session, row *models.Transaction) error {
	for _, id := range []int64{row.AccountId, row.RelatedAccountId} {
		if id == 0 {
			continue
		}
		found, err := sess.Where("uid=? AND account_id=? AND ledger_id=? AND deleted=?", row.Uid, id, row.LedgerId, false).Exist(new(models.Account))
		if err != nil {
			return err
		}
		if !found {
			return errs.ErrAccountIdInvalid
		}
	}
	return nil
}

func transactionSubmissionHash(row *models.Transaction, tags, pictures []int64) (string, error) {
	ids := func(values []int64) []int64 {
		copy := utils.ToUniqueInt64Slice(append([]int64{}, values...))
		sort.Slice(copy, func(i, j int) bool { return copy[i] < copy[j] })
		return copy
	}
	snapshot := struct {
		Type                                                                       models.TransactionDbType
		Account, Destination, Category, Amount, DestinationAmount, UnixTime, Payer int64
		UtcOffset                                                                  int16
		HideAmount                                                                 bool
		Comment                                                                    string
		Longitude, Latitude                                                        float64
		Tags, Pictures                                                             []int64
	}{row.Type, row.AccountId, row.RelatedAccountId, row.CategoryId, row.Amount, row.RelatedAccountAmount,
		utils.GetUnixTimeFromTransactionTime(row.TransactionTime), row.PayerUid, row.TimezoneUtcOffset,
		row.HideAmount, row.Comment, row.GeoLongitude, row.GeoLatitude, ids(tags), ids(pictures)}
	data, err := json.Marshal(snapshot)
	if err != nil {
		return "", errs.ErrParameterInvalid
	}
	hash := sha256.Sum256(data)
	return hex.EncodeToString(hash[:]), nil
}

// CreateTransactionIdempotent binds a stable client key to exactly one posting,
// independently of the optional short-lived duplicate-submission cache.
func (s *TransactionService) CreateTransactionIdempotent(c core.Context, row *models.Transaction, tags, pictures []int64, key string) error {
	if len(key) > 128 {
		return errs.ErrParameterInvalid
	}
	if key != "" {
		lock := &transactionSubmissionLocks[uint64(row.Uid)%uint64(len(transactionSubmissionLocks))]
		lock.Lock()
		defer lock.Unlock()
	}
	actor := row.RecorderUid
	if actor <= 0 {
		actor = row.Uid
	}
	ledger, err := ImportLedger(c, actor, row.LedgerId)
	if err != nil {
		return err
	}
	if row.Uid != ledger.OwnerUid {
		return errs.ErrLedgerAccessDenied
	}
	if key == "" {
		return s.createTransactionWithHooks(c, row, tags, pictures, func(sess *xorm.Session) error {
			if err := authorizeImportLedger(sess, actor, ledger); err != nil {
				return err
			}
			return authorizeTransactionAccounts(sess, row)
		}, nil)
	}
	hash, err := transactionSubmissionHash(row, tags, pictures)
	if err != nil {
		return err
	}
	receipt := &models.TransactionSubmission{Uid: row.Uid, RecorderUid: actor, LedgerId: row.LedgerId,
		ClientSessionId: key, RequestHash: hash, CreatedUnixTime: time.Now().Unix()}
	replayed := errors.New("transaction submission already committed")
	err = s.createTransactionWithHooks(c, row, tags, pictures, func(sess *xorm.Session) error {
		if e := authorizeImportLedger(sess, actor, ledger); e != nil {
			return e
		}
		existing := &models.TransactionSubmission{}
		found, e := sess.Where("uid=? AND recorder_uid=? AND ledger_id=? AND client_session_id=?", receipt.Uid, actor, receipt.LedgerId, key).Get(existing)
		if e != nil {
			return e
		}
		if found {
			if existing.RequestHash != hash {
				return errs.ErrParameterInvalid
			}
			return replayed
		}
		if e := authorizeTransactionAccounts(sess, row); e != nil {
			return e
		}
		receipt.TransactionId = row.TransactionId
		_, e = sess.Insert(receipt)
		return e
	}, nil)
	if err == nil {
		return nil
	}
	// A composite primary key also protects independent server instances. Read
	// an acknowledged operation after rollback; never retry financial creation.
	read := s.UserDataDB(receipt.Uid).NewSession(c)
	defer read.Close()
	existing := &models.TransactionSubmission{}
	found, readErr := read.Where("uid=? AND recorder_uid=? AND ledger_id=? AND client_session_id=?", receipt.Uid, actor, receipt.LedgerId, key).Get(existing)
	if readErr != nil || !found {
		return err
	}
	if existing.RequestHash != hash {
		return errs.ErrParameterInvalid
	}
	// Resolve live access again before exposing a prior result.
	if _, accessErr := ImportLedger(c, actor, receipt.LedgerId); accessErr != nil {
		return accessErr
	}
	committed, readErr := s.GetTransactionByTransactionIdInLedger(c, receipt.Uid, receipt.LedgerId, existing.TransactionId)
	if readErr != nil {
		return readErr
	}
	*row = *committed
	return nil
}
