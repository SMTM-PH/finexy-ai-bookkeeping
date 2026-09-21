package api

import (
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
)

// SavingsGoalsApi represents savings goal API handlers.
type SavingsGoalsApi struct {
	savingsGoals *services.SavingsGoalService
}

// SavingsGoals is the savings goal API singleton.
var SavingsGoals = &SavingsGoalsApi{savingsGoals: services.SavingsGoals}

// SavingsGoalListHandler returns the goals of one ledger.
func (a *SavingsGoalsApi) SavingsGoalListHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.SavingsGoalListRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	responses, err := a.savingsGoals.ListSavingsGoals(c, c.GetCurrentUid(), request.LedgerId)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return responses, nil
}

// SavingsGoalGetHandler returns one goal with its saved amount.
func (a *SavingsGoalsApi) SavingsGoalGetHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.SavingsGoalGetRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	response, err := a.savingsGoals.GetSavingsGoal(c, c.GetCurrentUid(), request.Id)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return response, nil
}

// SavingsGoalCreateHandler creates one goal.
func (a *SavingsGoalsApi) SavingsGoalCreateHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.SavingsGoalCreateRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	goal, err := a.savingsGoals.CreateSavingsGoal(c, c.GetCurrentUid(), &request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return goal.ToSavingsGoalInfoResponse(nil), nil
}

// SavingsGoalModifyHandler renames or retargets one goal.
func (a *SavingsGoalsApi) SavingsGoalModifyHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.SavingsGoalModifyRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	response, err := a.savingsGoals.ModifySavingsGoal(c, c.GetCurrentUid(), &request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return response, nil
}

// SavingsGoalDeleteHandler deletes a goal without fund movements.
func (a *SavingsGoalsApi) SavingsGoalDeleteHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.SavingsGoalDeleteRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	if err := a.savingsGoals.DeleteSavingsGoal(c, c.GetCurrentUid(), request.Id); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return nil, nil
}

// SavingsGoalDepositHandler records a deposit into one goal.
func (a *SavingsGoalsApi) SavingsGoalDepositHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.SavingsGoalFundRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	fund, err := a.savingsGoals.DepositToGoal(c, c.GetCurrentUid(), &request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return fund.ToSavingsGoalFundInfoResponse(), nil
}

// SavingsGoalWithdrawHandler records a withdrawal from one goal.
func (a *SavingsGoalsApi) SavingsGoalWithdrawHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.SavingsGoalFundRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	fund, err := a.savingsGoals.WithdrawFromGoal(c, c.GetCurrentUid(), &request)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return fund.ToSavingsGoalFundInfoResponse(), nil
}

// SavingsGoalAccountsHandler returns the accounts that may fund a goal of the
// requested ledger.
func (a *SavingsGoalsApi) SavingsGoalAccountsHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.SavingsGoalListRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	accounts, err := a.savingsGoals.ListLedgerAccounts(c, c.GetCurrentUid(), request.LedgerId)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	responses := make([]*models.AccountInfoResponse, 0, len(accounts))
	for _, account := range accounts {
		responses = append(responses, account.ToAccountInfoResponse())
	}
	return responses, nil
}

// SavingsGoalFundListHandler returns the movements of one goal.
func (a *SavingsGoalsApi) SavingsGoalFundListHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.SavingsGoalFundListRequest
	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	responses, err := a.savingsGoals.ListFunds(c, c.GetCurrentUid(), request.GoalId)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return responses, nil
}
