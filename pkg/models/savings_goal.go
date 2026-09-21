package models

import "fmt"

// MaximumGoalAmount limits the target and movement amounts of savings goals,
// using the same minor currency unit bound as transactions.
const MaximumGoalAmount = int64(9999999999999)

// SavingsGoal represents one goal of one ledger. The goal itself never holds a
// balance: saved amounts are derived from the fund movements that reference it.
type SavingsGoal struct {
	SavingsGoalId    int64  `xorm:"PK"`
	Uid              int64  `xorm:"INDEX(IDX_savings_goal_uid_deleted) NOT NULL"`
	LedgerId         int64  `xorm:"INDEX(IDX_savings_goal_ledger_deleted) NOT NULL DEFAULT 0"`
	Deleted          bool   `xorm:"INDEX(IDX_savings_goal_uid_deleted) INDEX(IDX_savings_goal_ledger_deleted) NOT NULL"`
	Name             string `xorm:"VARCHAR(128) NOT NULL"`
	TargetAmount     int64  `xorm:"NOT NULL"`
	DeadlineUnixTime int64
	Comment          string `xorm:"VARCHAR(255) NOT NULL"`
	CreatedUnixTime  int64
	UpdatedUnixTime  int64
	DeletedUnixTime  int64
}

// Validate rejects goals with an unusable name, a target outside the accepted
// amount range, or a deadline placed before creation. A zero deadline means
// no deadline.
func (g *SavingsGoal) Validate() error {
	if g.Uid <= 0 {
		return fmt.Errorf("invalid savings goal owner")
	}

	if len(g.Name) == 0 || len(g.Name) > 128 {
		return fmt.Errorf("invalid savings goal name")
	}

	if g.TargetAmount <= 0 || g.TargetAmount > MaximumGoalAmount {
		return fmt.Errorf("invalid savings goal target amount")
	}

	if g.DeadlineUnixTime < 0 {
		return fmt.Errorf("invalid savings goal deadline")
	}

	if g.DeadlineUnixTime > 0 && g.CreatedUnixTime > 0 && g.DeadlineUnixTime < g.CreatedUnixTime {
		return fmt.Errorf("savings goal deadline precedes creation")
	}

	return nil
}

// SavingsGoalFundDirection represents the direction of one fund movement.
type SavingsGoalFundDirection byte

// Fund movement directions. A deposit moves ledger funds into the goal, a
// withdraw returns them to a ledger account. Neither is income or expense.
const (
	SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT  SavingsGoalFundDirection = 1
	SAVINGS_GOAL_FUND_DIRECTION_WITHDRAW SavingsGoalFundDirection = 2
)

// IsValid reports whether the direction is a defined value.
func (d SavingsGoalFundDirection) IsValid() bool {
	return d == SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT || d == SAVINGS_GOAL_FUND_DIRECTION_WITHDRAW
}

// SavingsGoalFund represents one immutable movement into or out of one goal.
// TransactionId links the movement to the transfer transaction that posts it.
// Zero is retained only for legacy movements created before transfer posting.
type SavingsGoalFund struct {
	SavingsGoalFundId int64                    `xorm:"PK"`
	GoalId            int64                    `xorm:"INDEX(IDX_savings_goal_fund_goal_created) NOT NULL"`
	Uid               int64                    `xorm:"NOT NULL"`
	Direction         SavingsGoalFundDirection `xorm:"NOT NULL"`
	Amount            int64                    `xorm:"NOT NULL"`
	AccountId         int64                    `xorm:"NOT NULL"`
	TransactionId     int64
	Comment           string `xorm:"VARCHAR(255) NOT NULL"`
	CreatedUnixTime   int64  `xorm:"INDEX(IDX_savings_goal_fund_goal_created)"`
}

// Validate rejects movements with an unknown direction, an amount outside the
// accepted range, or a missing source account.
func (f *SavingsGoalFund) Validate() error {
	if f.GoalId <= 0 || f.Uid <= 0 {
		return fmt.Errorf("invalid savings goal fund reference")
	}

	if !f.Direction.IsValid() {
		return fmt.Errorf("invalid savings goal fund direction")
	}

	if f.Amount <= 0 || f.Amount > MaximumGoalAmount {
		return fmt.Errorf("invalid savings goal fund amount")
	}

	if f.AccountId <= 0 {
		return fmt.Errorf("invalid savings goal fund account")
	}

	return nil
}

// SavingsGoalNetSaved returns the net saved amount of one goal derived from
// its fund movements. Deposits add and withdrawals subtract, so the result can
// never exceed the deposits actually made.
func SavingsGoalNetSaved(funds []SavingsGoalFund) int64 {
	saved := int64(0)

	for _, fund := range funds {
		if fund.Direction == SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT {
			saved += fund.Amount
		} else {
			saved -= fund.Amount
		}
	}

	return saved
}

// IsAchieved reports whether the supplied saved amount reaches the target.
func (g *SavingsGoal) IsAchieved(saved int64) bool {
	return saved >= g.TargetAmount
}

// ProgressPercentage returns goal completion as a percentage. The result is
// not capped so that overfunded goals stay visible in statistics.
func (g *SavingsGoal) ProgressPercentage(saved int64) float64 {
	if g.TargetAmount <= 0 {
		return 0
	}

	return float64(saved) * 100 / float64(g.TargetAmount)
}

// SavingsGoalCreateRequest represents the parameters for creating a goal. The
// ledger defaults to the user's default personal ledger when omitted.
type SavingsGoalCreateRequest struct {
	LedgerId     int64  `json:"ledgerId,string" binding:"omitempty,min=1"`
	Name         string `json:"name" binding:"required,notBlank,max=128"`
	TargetAmount int64  `json:"targetAmount" binding:"required,min=1,max=9999999999999"`
	DeadlineTime int64  `json:"deadlineTime" binding:"omitempty,min=1"`
	Comment      string `json:"comment" binding:"max=255"`
}

// SavingsGoalModifyRequest represents the parameters for modifying a goal.
// Fund movements are immutable, so the saved amount always follows the
// movements and the ledger of an existing goal never changes.
type SavingsGoalModifyRequest struct {
	Id           int64  `json:"id,string" binding:"required,min=1"`
	Name         string `json:"name" binding:"required,notBlank,max=128"`
	TargetAmount int64  `json:"targetAmount" binding:"required,min=1,max=9999999999999"`
	DeadlineTime int64  `json:"deadlineTime" binding:"omitempty,min=1"`
	Comment      string `json:"comment" binding:"max=255"`
}

// SavingsGoalGetRequest represents the parameters for retrieving one goal.
type SavingsGoalGetRequest struct {
	Id int64 `form:"id,string" binding:"required,min=1"`
}

// SavingsGoalListRequest represents the parameters for listing the goals of
// one ledger. An omitted ledger id selects the default personal ledger.
type SavingsGoalListRequest struct {
	LedgerId int64 `form:"ledgerId,string" binding:"omitempty,min=1"`
}

// SavingsGoalDeleteRequest represents the parameters for deleting a goal.
type SavingsGoalDeleteRequest struct {
	Id int64 `json:"id,string" binding:"required,min=1"`
}

// SavingsGoalFundRequest represents the parameters for depositing into or
// withdrawing from one goal. The service validates that the account belongs to
// the same ledger and that a withdrawal never exceeds the saved amount.
type SavingsGoalFundRequest struct {
	Id        int64  `json:"id,string" binding:"required,min=1"`
	Amount    int64  `json:"amount" binding:"required,min=1,max=9999999999999"`
	AccountId int64  `json:"accountId,string" binding:"required,min=1"`
	Comment   string `json:"comment" binding:"max=255"`
}

// SavingsGoalFundListRequest represents the parameters for listing the
// movements of one goal.
type SavingsGoalFundListRequest struct {
	GoalId int64 `form:"goalId,string" binding:"required,min=1"`
}

// SavingsGoalInfoResponse is the API representation of one goal including the
// saved amount derived from its movements.
type SavingsGoalInfoResponse struct {
	Id           int64  `json:"id,string"`
	LedgerId     int64  `json:"ledgerId,string"`
	Uid          int64  `json:"uid,string"`
	Name         string `json:"name"`
	TargetAmount int64  `json:"targetAmount"`
	SavedAmount  int64  `json:"savedAmount"`
	Achieved     bool   `json:"achieved"`
	DeadlineTime int64  `json:"deadlineTime,omitempty"`
	Comment      string `json:"comment"`
}

// SavingsGoalFundInfoResponse is the API representation of one movement.
type SavingsGoalFundInfoResponse struct {
	Id            int64                    `json:"id,string"`
	GoalId        int64                    `json:"goalId,string"`
	Uid           int64                    `json:"uid,string"`
	Direction     SavingsGoalFundDirection `json:"direction"`
	Amount        int64                    `json:"amount"`
	AccountId     int64                    `json:"accountId,string"`
	TransactionId int64                    `json:"transactionId,string,omitempty"`
	Comment       string                   `json:"comment"`
	CreatedTime   int64                    `json:"createdTime"`
}

// ToSavingsGoalInfoResponse converts a stored goal and its movements to the
// API representation. The movements must already be filtered to this goal.
func (g *SavingsGoal) ToSavingsGoalInfoResponse(funds []SavingsGoalFund) *SavingsGoalInfoResponse {
	saved := SavingsGoalNetSaved(funds)
	return &SavingsGoalInfoResponse{
		Id:           g.SavingsGoalId,
		LedgerId:     g.LedgerId,
		Uid:          g.Uid,
		Name:         g.Name,
		TargetAmount: g.TargetAmount,
		SavedAmount:  saved,
		Achieved:     g.IsAchieved(saved),
		DeadlineTime: g.DeadlineUnixTime,
		Comment:      g.Comment,
	}
}

// ToSavingsGoalFundInfoResponse converts a stored movement to its API
// representation.
func (f *SavingsGoalFund) ToSavingsGoalFundInfoResponse() *SavingsGoalFundInfoResponse {
	return &SavingsGoalFundInfoResponse{
		Id:            f.SavingsGoalFundId,
		GoalId:        f.GoalId,
		Uid:           f.Uid,
		Direction:     f.Direction,
		Amount:        f.Amount,
		AccountId:     f.AccountId,
		TransactionId: f.TransactionId,
		Comment:       f.Comment,
		CreatedTime:   f.CreatedUnixTime,
	}
}
