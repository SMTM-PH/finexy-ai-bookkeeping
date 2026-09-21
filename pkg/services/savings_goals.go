package services

import (
	"fmt"
	"time"

	"xorm.io/xorm"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
)

// SavingsGoalService manages the savings goals of one ledger and their fund
// movements. Each new movement atomically posts an external transfer and
// stores its transaction ID. Older movements with a zero transaction ID keep
// their historical balance semantics.
//
// Goals live in the shard of their ledger: default personal ledger goals in
// the acting user's shard, explicit personal ledger goals in the owner's
// shard, and family ledger goals in the family owner's shard next to the
// ledger row.
type SavingsGoalService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

// SavingsGoals is the savings goal service singleton.
var SavingsGoals = &SavingsGoalService{
	ServiceUsingDB:   ServiceUsingDB{container: datastore.Container},
	ServiceUsingUuid: ServiceUsingUuid{container: uuid.Container},
}

// locateGoal returns the shard and row holding one goal together with the
// resolved ledger access of the user.
func (s *SavingsGoalService) locateGoal(c core.Context, uid, goalId int64, capability func(models.FamilyMemberRole) bool) (*datastore.Database, *models.SavingsGoal, error) {
	if uid <= 0 || goalId <= 0 {
		return nil, nil, errs.ErrParameterInvalid
	}

	for i := 0; i < s.UserDataDBCount(); i++ {
		db := s.UserDataDBByIndex(i)
		goal := &models.SavingsGoal{}
		has, err := db.NewSession(c).ID(goalId).Where("deleted=?", false).Get(goal)
		if err != nil {
			return nil, nil, err
		}
		if !has {
			continue
		}

		if goal.LedgerId == models.DefaultLedgerId {
			// The implicit default personal ledger is private to its owner.
			if goal.Uid != uid {
				return nil, nil, errs.ErrSavingsGoalAccessDenied
			}
			return db, goal, nil
		}

		_, _, err = Ledgers.GetLedgerWithAccess(c, uid, goal.LedgerId, capability)
		if err != nil {
			return nil, nil, errs.ErrSavingsGoalAccessDenied
		}
		return db, goal, nil
	}
	return nil, nil, errs.ErrSavingsGoalNotFound
}

// CreateSavingsGoal creates one goal in the requested ledger. Family ledger
// goals require a managing role, matching the confirmed prototype rule.
func (s *SavingsGoalService) CreateSavingsGoal(c core.Context, uid int64, request *models.SavingsGoalCreateRequest) (*models.SavingsGoal, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	db, ledger, err := Ledgers.GetLedgerWithAccess(c, uid, request.LedgerId, models.FamilyMemberRole.CanManage)
	if err == errs.ErrLedgerAccessDenied {
		return nil, errs.ErrSavingsGoalAccessDenied
	}
	if err != nil {
		return nil, err
	}

	now := time.Now().Unix()
	goal := &models.SavingsGoal{
		Uid:              uid,
		LedgerId:         ledger.LedgerId,
		Name:             request.Name,
		TargetAmount:     request.TargetAmount,
		DeadlineUnixTime: request.DeadlineTime,
		Comment:          request.Comment,
		CreatedUnixTime:  now,
		UpdatedUnixTime:  now,
	}
	if err := goal.Validate(); err != nil {
		return nil, errs.ErrSavingsGoalNameInvalid
	}

	goal.SavingsGoalId = s.GenerateUuid(uuid.UUID_TYPE_FAMILY)
	if goal.SavingsGoalId < 1 {
		return nil, errs.ErrSystemIsBusy
	}

	// The implicit default personal ledger has no shard of its own; its goals
	// always live in the acting user's shard.
	if db == nil {
		db = s.UserDataDB(uid)
	}

	if _, err := db.NewSession(c).Insert(goal); err != nil {
		return nil, err
	}
	return goal, nil
}

// ListSavingsGoals returns the goals of one ledger with their derived saved
// amounts. Any active family member may view the goals of a family ledger.
func (s *SavingsGoalService) ListSavingsGoals(c core.Context, uid, ledgerId int64) ([]*models.SavingsGoalInfoResponse, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	var db *datastore.Database
	var query string
	var args []any
	if ledgerId == models.DefaultLedgerId {
		db = s.UserDataDB(uid)
		query = "ledger_id=? AND uid=? AND deleted=?"
		args = []any{models.DefaultLedgerId, uid, false}
	} else {
		var err error
		db, _, err = Ledgers.GetLedgerWithAccess(c, uid, ledgerId, anyFamilyRole)
		if err == errs.ErrLedgerAccessDenied {
			return nil, errs.ErrSavingsGoalAccessDenied
		}
		if err != nil {
			return nil, err
		}
		if db == nil {
			db = s.UserDataDB(uid)
		}
		query = "ledger_id=? AND deleted=?"
		args = []any{ledgerId, false}
	}

	goals := make([]*models.SavingsGoal, 0)
	err := db.NewSession(c).Where(query, args...).OrderBy("created_unix_time").Find(&goals)
	if err != nil {
		return nil, err
	}

	responses := make([]*models.SavingsGoalInfoResponse, 0, len(goals))
	for _, goal := range goals {
		funds, err := s.goalFunds(db, goal.SavingsGoalId)
		if err != nil {
			return nil, err
		}
		responses = append(responses, goal.ToSavingsGoalInfoResponse(funds))
	}
	return responses, nil
}

// GetSavingsGoal returns one goal with its derived saved amount.
func (s *SavingsGoalService) GetSavingsGoal(c core.Context, uid, goalId int64) (*models.SavingsGoalInfoResponse, error) {
	db, goal, err := s.locateGoal(c, uid, goalId, anyFamilyRole)
	if err != nil {
		return nil, err
	}

	funds, err := s.goalFunds(db, goal.SavingsGoalId)
	if err != nil {
		return nil, err
	}
	return goal.ToSavingsGoalInfoResponse(funds), nil
}

// ModifySavingsGoal renames or retargets one goal. Fund movements are
// immutable, so the saved amount always follows the movements.
func (s *SavingsGoalService) ModifySavingsGoal(c core.Context, uid int64, request *models.SavingsGoalModifyRequest) (*models.SavingsGoalInfoResponse, error) {
	db, goal, err := s.locateGoal(c, uid, request.Id, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}

	goal.Name = request.Name
	goal.TargetAmount = request.TargetAmount
	goal.DeadlineUnixTime = request.DeadlineTime
	goal.Comment = request.Comment
	if err := goal.Validate(); err != nil {
		return nil, errs.ErrSavingsGoalNameInvalid
	}

	goal.UpdatedUnixTime = time.Now().Unix()
	rows, err := db.NewSession(c).ID(request.Id).
		Cols("name", "target_amount", "deadline_unix_time", "comment", "updated_unix_time").
		Update(goal)
	if err != nil {
		return nil, err
	}
	if rows < 1 {
		return nil, errs.ErrSavingsGoalNotFound
	}

	return s.GetSavingsGoal(c, uid, request.Id)
}

// DeleteSavingsGoal soft deletes a goal that has no fund movements. Goals with
// movements cannot be deleted so that fund records always keep their owner.
func (s *SavingsGoalService) DeleteSavingsGoal(c core.Context, uid, goalId int64) error {
	db, goal, err := s.locateGoal(c, uid, goalId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return err
	}

	fundCount, err := db.NewSession(c).Where("goal_id=?", goal.SavingsGoalId).Count(new(models.SavingsGoalFund))
	if err != nil {
		return err
	}
	if fundCount > 0 {
		return errs.ErrSavingsGoalHasFunds
	}

	now := time.Now().Unix()
	rows, err := db.NewSession(c).ID(goal.SavingsGoalId).
		Cols("deleted", "deleted_unix_time", "updated_unix_time").
		Where("deleted=?", false).
		Update(&models.SavingsGoal{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now})
	if err != nil {
		return err
	}
	if rows < 1 {
		return errs.ErrSavingsGoalNotFound
	}
	return nil
}

// DepositToGoal records a fund movement from a ledger account into the goal.
// The account must belong to the same ledger as the goal.
func (s *SavingsGoalService) DepositToGoal(c core.Context, uid int64, request *models.SavingsGoalFundRequest) (*models.SavingsGoalFund, error) {
	return s.moveFunds(c, uid, request, models.SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT)
}

// WithdrawFromGoal records a fund movement from the goal back to a ledger
// account. The withdrawal can never exceed the net saved amount.
func (s *SavingsGoalService) WithdrawFromGoal(c core.Context, uid int64, request *models.SavingsGoalFundRequest) (*models.SavingsGoalFund, error) {
	return s.moveFunds(c, uid, request, models.SAVINGS_GOAL_FUND_DIRECTION_WITHDRAW)
}

// ListFunds returns the movements of one goal, newest first.
func (s *SavingsGoalService) ListFunds(c core.Context, uid, goalId int64) ([]*models.SavingsGoalFundInfoResponse, error) {
	db, goal, err := s.locateGoal(c, uid, goalId, anyFamilyRole)
	if err != nil {
		return nil, err
	}

	funds, err := s.goalFunds(db, goal.SavingsGoalId)
	if err != nil {
		return nil, err
	}

	responses := make([]*models.SavingsGoalFundInfoResponse, 0, len(funds))
	for _, fund := range funds {
		responses = append(responses, fund.ToSavingsGoalFundInfoResponse())
	}
	return responses, nil
}

func (s *SavingsGoalService) moveFunds(c core.Context, uid int64, request *models.SavingsGoalFundRequest, direction models.SavingsGoalFundDirection) (*models.SavingsGoalFund, error) {
	if request.Amount <= 0 || request.Amount > models.MaximumGoalAmount {
		return nil, errs.ErrSavingsGoalFundAmountInvalid
	}

	db, goal, err := s.locateGoal(c, uid, request.Id, models.FamilyMemberRole.CanWrite)
	if err != nil {
		return nil, err
	}

	// The account must belong to the same ledger as the goal. Default personal
	// ledger goals additionally require the user's own account, because the
	// implicit ledger is user-private. Family ledger accounts are shared with
	// every member that holds write access to the goal.
	account := &models.Account{}
	has, err := db.NewSession(c).ID(request.AccountId).Where("deleted=?", false).Get(account)
	if err != nil {
		return nil, err
	}
	if !has || account.LedgerId != goal.LedgerId || (goal.LedgerId == models.DefaultLedgerId && account.Uid != uid) {
		return nil, errs.ErrSavingsGoalAccountMismatch
	}
	if account.Hidden || account.Type != models.ACCOUNT_TYPE_SINGLE_ACCOUNT {
		return nil, errs.ErrSavingsGoalAccountMismatch
	}
	_, ledger, err := Ledgers.GetLedgerWithAccess(c, uid, goal.LedgerId, models.FamilyMemberRole.CanWrite)
	if err != nil {
		return nil, err
	}
	postingUid, err := Ledgers.LedgerDataOwnerUid(c, uid, ledger)
	if err != nil {
		return nil, err
	}

	now := time.Now().Unix()
	fund := &models.SavingsGoalFund{
		GoalId:          goal.SavingsGoalId,
		Uid:             uid,
		Direction:       direction,
		Amount:          request.Amount,
		AccountId:       request.AccountId,
		Comment:         request.Comment,
		CreatedUnixTime: now,
	}
	if err := fund.Validate(); err != nil {
		return nil, errs.ErrSavingsGoalFundAmountInvalid
	}
	fund.SavingsGoalFundId = s.GenerateUuid(uuid.UUID_TYPE_FAMILY)
	if fund.SavingsGoalFundId < 1 {
		return nil, errs.ErrSystemIsBusy
	}
	transferType := models.TRANSACTION_DB_TYPE_TRANSFER_OUT
	transferLabel := "存入"
	if direction == models.SAVINGS_GOAL_FUND_DIRECTION_WITHDRAW {
		transferType = models.TRANSACTION_DB_TYPE_TRANSFER_IN
		transferLabel = "取出"
	}
	transaction := &models.Transaction{
		Uid: postingUid, LedgerId: goal.LedgerId,
		RecorderUid: uid, PayerUid: uid, SavingsGoalFundId: fund.SavingsGoalFundId,
		Type: transferType, AccountId: account.AccountId, CategoryId: 0,
		TransactionTime: utils.GetMinTransactionTimeFromUnixTime(now),
		Amount:          request.Amount, Comment: fmt.Sprintf("存钱计划%s「%s」", transferLabel, goal.Name),
	}
	err = Transactions.createTransactionWithHooks(c, transaction, nil, nil, func(sess *xorm.Session) error {
		// Lock the goal row before checking the net saved amount. Concurrent
		// withdrawals then serialize on the same row in SQL backends.
		rows, err := sess.ID(goal.SavingsGoalId).Cols("updated_unix_time").Where("deleted=?", false).
			Update(&models.SavingsGoal{UpdatedUnixTime: now})
		if err != nil {
			return err
		}
		if rows != 1 {
			return errs.ErrSavingsGoalNotFound
		}
		if direction == models.SAVINGS_GOAL_FUND_DIRECTION_WITHDRAW {
			funds := make([]models.SavingsGoalFund, 0)
			if err := sess.Where("goal_id=?", goal.SavingsGoalId).Find(&funds); err != nil {
				return err
			}
			if models.SavingsGoalNetSaved(funds) < request.Amount {
				return errs.ErrSavingsGoalInsufficientSaved
			}
		}

		_, err = sess.Insert(fund)
		return err
	}, func(sess *xorm.Session) error {
		fund.TransactionId = transaction.TransactionId
		rows, err := sess.ID(fund.SavingsGoalFundId).Cols("transaction_id").Update(fund)
		if err != nil {
			return err
		}
		if rows != 1 {
			return errs.ErrDatabaseOperationFailed
		}
		return nil
	})

	if err != nil {
		return nil, err
	}
	return fund, nil
}

// ListLedgerAccounts returns the accounts that may fund a goal of the given
// ledger. For the default personal ledger these are the user's own unassigned
// accounts; for an explicit ledger they are every account assigned to it, so
// that family ledger accounts created by one member can be used by the others
// with write access.
func (s *SavingsGoalService) ListLedgerAccounts(c core.Context, uid, ledgerId int64) ([]*models.Account, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	db, ledger, err := Ledgers.GetLedgerWithAccess(c, uid, ledgerId, models.FamilyMemberRole.CanWrite)
	if err != nil {
		return nil, err
	}

	accounts := make([]*models.Account, 0)
	if ledger.LedgerId == models.DefaultLedgerId {
		err = s.UserDataDB(uid).NewSession(c).
			Where("uid=? AND ledger_id=? AND deleted=? AND hidden=? AND type=?", uid, models.DefaultLedgerId, false, false, models.ACCOUNT_TYPE_SINGLE_ACCOUNT).
			OrderBy("display_order").
			Find(&accounts)
	} else {
		err = db.NewSession(c).
			Where("ledger_id=? AND deleted=? AND hidden=? AND type=?", ledger.LedgerId, false, false, models.ACCOUNT_TYPE_SINGLE_ACCOUNT).
			OrderBy("display_order").
			Find(&accounts)
	}
	if err != nil {
		return nil, err
	}
	return accounts, nil
}

// goalFunds returns the movements of one goal from the given shard, newest
// first.
func (s *SavingsGoalService) goalFunds(db *datastore.Database, goalId int64) ([]models.SavingsGoalFund, error) {
	funds := make([]models.SavingsGoalFund, 0)
	err := db.NewSession(nil).
		Where("goal_id=?", goalId).
		OrderBy("created_unix_time DESC").
		Find(&funds)
	return funds, err
}
