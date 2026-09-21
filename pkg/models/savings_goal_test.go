package models

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestSavingsGoalValidation(t *testing.T) {
	goal := &SavingsGoal{Uid: 100, LedgerId: DefaultLedgerId, Name: "全家旅行基金", TargetAmount: 2000000, CreatedUnixTime: 1000, DeadlineUnixTime: 2000}
	assert.NoError(t, goal.Validate())

	// Zero deadline means no deadline.
	goal.DeadlineUnixTime = 0
	assert.NoError(t, goal.Validate())

	goal.Name = ""
	assert.Error(t, goal.Validate())

	goal = &SavingsGoal{Uid: 100, Name: "全家旅行基金", TargetAmount: 0}
	assert.Error(t, goal.Validate())

	goal.TargetAmount = MaximumGoalAmount + 1
	assert.Error(t, goal.Validate())

	goal.TargetAmount = 2000000
	goal.DeadlineUnixTime = -1
	assert.Error(t, goal.Validate())

	goal.DeadlineUnixTime = 999
	goal.CreatedUnixTime = 1000
	assert.Error(t, goal.Validate())

	goal.Uid = 0
	goal.DeadlineUnixTime = 2000
	assert.Error(t, goal.Validate())
}

func TestSavingsGoalFundValidation(t *testing.T) {
	fund := &SavingsGoalFund{GoalId: 1, Uid: 100, Direction: SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT, Amount: 50000, AccountId: 2}
	assert.NoError(t, fund.Validate())

	fund.Direction = SAVINGS_GOAL_FUND_DIRECTION_WITHDRAW
	assert.NoError(t, fund.Validate())

	fund.Direction = SavingsGoalFundDirection(0)
	assert.Error(t, fund.Validate())

	fund.Direction = SavingsGoalFundDirection(3)
	assert.Error(t, fund.Validate())

	fund.Direction = SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT
	fund.Amount = 0
	assert.Error(t, fund.Validate())

	fund.Amount = MaximumGoalAmount + 1
	assert.Error(t, fund.Validate())

	fund.Amount = -50000
	assert.Error(t, fund.Validate())

	fund.Amount = 50000
	fund.AccountId = 0
	assert.Error(t, fund.Validate())

	fund.AccountId = 2
	fund.GoalId = 0
	assert.Error(t, fund.Validate())

	fund.GoalId = 1
	fund.Uid = 0
	assert.Error(t, fund.Validate())
}

func TestSavingsGoalNetSaved(t *testing.T) {
	funds := []SavingsGoalFund{
		{Direction: SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT, Amount: 1000000},
		{Direction: SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT, Amount: 50000},
		{Direction: SAVINGS_GOAL_FUND_DIRECTION_WITHDRAW, Amount: 20000},
	}

	assert.Equal(t, int64(1030000), SavingsGoalNetSaved(funds))
	assert.Equal(t, int64(0), SavingsGoalNetSaved(nil))
	assert.Equal(t, int64(0), SavingsGoalNetSaved([]SavingsGoalFund{}))
}

func TestSavingsGoalProgressAndAchievement(t *testing.T) {
	goal := &SavingsGoal{TargetAmount: 2000000}

	assert.False(t, goal.IsAchieved(1999999))
	// Achieved exactly at the target, matching the confirmed prototype rule.
	assert.True(t, goal.IsAchieved(2000000))
	assert.True(t, goal.IsAchieved(2000001))

	assert.InDelta(t, 52.5, goal.ProgressPercentage(1050000), 0.001)
	// Overfunded goals are not capped so statistics stay visible.
	assert.InDelta(t, 120.0, goal.ProgressPercentage(2400000), 0.001)
	assert.InDelta(t, 0.0, (&SavingsGoal{TargetAmount: 0}).ProgressPercentage(100), 0.001)
}

func TestSavingsGoalInfoResponseConversion(t *testing.T) {
	goal := &SavingsGoal{
		SavingsGoalId:    5,
		Uid:              100,
		LedgerId:         9,
		Name:             "全家旅行基金",
		TargetAmount:     2000000,
		CreatedUnixTime:  1000,
		DeadlineUnixTime: 2000,
		Comment:          "明年春节",
	}
	funds := []SavingsGoalFund{
		{Direction: SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT, Amount: 1050000},
		{Direction: SAVINGS_GOAL_FUND_DIRECTION_WITHDRAW, Amount: 20000},
	}

	response := goal.ToSavingsGoalInfoResponse(funds)
	assert.Equal(t, int64(5), response.Id)
	assert.Equal(t, int64(9), response.LedgerId)
	assert.Equal(t, int64(100), response.Uid)
	assert.Equal(t, int64(1030000), response.SavedAmount)
	assert.False(t, response.Achieved)
	assert.Equal(t, int64(2000), response.DeadlineTime)
	assert.Equal(t, "明年春节", response.Comment)

	achieved := goal.ToSavingsGoalInfoResponse([]SavingsGoalFund{
		{Direction: SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT, Amount: 2000000},
	})
	assert.True(t, achieved.Achieved)

	// Deadline omitted from the API when the goal has none.
	goal.DeadlineUnixTime = 0
	noDeadline := goal.ToSavingsGoalInfoResponse(nil)
	assert.Equal(t, int64(0), noDeadline.DeadlineTime)
}

func TestSavingsGoalFundInfoResponseConversion(t *testing.T) {
	fund := &SavingsGoalFund{
		SavingsGoalFundId: 11,
		GoalId:            5,
		Uid:               100,
		Direction:         SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT,
		Amount:            50000,
		AccountId:         2,
		Comment:           "九月存入",
		CreatedUnixTime:   1234,
	}

	response := fund.ToSavingsGoalFundInfoResponse()
	assert.Equal(t, int64(11), response.Id)
	assert.Equal(t, int64(5), response.GoalId)
	assert.Equal(t, SAVINGS_GOAL_FUND_DIRECTION_DEPOSIT, response.Direction)
	assert.Equal(t, int64(50000), response.Amount)
	assert.Equal(t, int64(2), response.AccountId)
	assert.Equal(t, int64(0), response.TransactionId)
	assert.Equal(t, int64(1234), response.CreatedTime)
}

// TestMaximumGoalAmountBinding pins the amount ceiling shared by the model
// validation and the gin binding tags of the API contract.
func TestMaximumGoalAmountBinding(t *testing.T) {
	assert.Equal(t, int64(9999999999999), MaximumGoalAmount)
}
