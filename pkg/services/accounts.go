package services

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"time"

	"xorm.io/xorm"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/datastore"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/log"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/utils"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/uuid"
)

// locateAccountGroupForManage resolves a root account and all of its children
// through the ledger permission boundary. Family accounts live in the family
// owner's data store and therefore cannot be found through UserDataDB(uid).
func (s *AccountService) locateAccountGroupForManage(c core.Context, uid, accountId int64) (*datastore.Database, *models.Ledger, []*models.Account, error) {
	if uid <= 0 {
		return nil, nil, nil, errs.ErrUserIdInvalid
	}
	if accountId <= 0 {
		return nil, nil, nil, errs.ErrAccountIdInvalid
	}

	for i := 0; i < s.UserDataDBCount(); i++ {
		db := s.UserDataDBByIndex(i)
		account := &models.Account{}
		has, err := db.NewSession(c).Where("deleted=? AND account_id=?", false, accountId).Get(account)
		if err != nil {
			return nil, nil, nil, err
		}
		if !has {
			continue
		}

		accessDB, ledger, err := Ledgers.GetLedgerWithAccess(c, uid, account.LedgerId, models.FamilyMemberRole.CanManage)
		if err != nil {
			return nil, nil, nil, errs.ErrLedgerAccessDenied
		}
		if accessDB == nil {
			accessDB = s.UserDataDB(uid)
		}
		if accessDB != db || (account.LedgerId == models.DefaultLedgerId && account.Uid != uid) {
			return nil, nil, nil, errs.ErrLedgerAccessDenied
		}

		rootId := account.AccountId
		if account.ParentAccountId > models.LevelOneAccountParentId {
			rootId = account.ParentAccountId
		}
		accounts := make([]*models.Account, 0)
		query := db.NewSession(c).Where("deleted=? AND ledger_id=? AND (account_id=? OR parent_account_id=?)", false, account.LedgerId, rootId, rootId)
		if account.LedgerId == models.DefaultLedgerId {
			query = query.And("uid=?", uid)
		}
		if err := query.OrderBy("parent_account_id asc, display_order asc").Find(&accounts); err != nil {
			return nil, nil, nil, err
		}
		if len(accounts) == 0 || accounts[0].AccountId != rootId || accounts[0].ParentAccountId != models.LevelOneAccountParentId {
			return nil, nil, nil, errs.ErrAccountNotFound
		}
		return db, ledger, accounts, nil
	}
	return nil, nil, nil, errs.ErrAccountNotFound
}

// AccountService represents account service
type AccountService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

// Initialize a account service singleton instance
var (
	Accounts = &AccountService{
		ServiceUsingDB: ServiceUsingDB{
			container: datastore.Container,
		},
		ServiceUsingUuid: ServiceUsingUuid{
			container: uuid.Container,
		},
	}
)

// GetTotalAccountCountByUid returns total account count of user
func (s *AccountService) GetTotalAccountCountByUid(c core.Context, uid int64) (int64, error) {
	if uid <= 0 {
		return 0, errs.ErrUserIdInvalid
	}

	count, err := s.UserDataDB(uid).NewSession(c).Where("uid=? AND deleted=?", uid, false).Count(&models.Account{})

	return count, err
}

// GetAllAccountsByUid returns all account models of user
func (s *AccountService) GetAllAccountsByUid(c core.Context, uid int64) ([]*models.Account, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	var accounts []*models.Account
	err := s.UserDataDB(uid).NewSession(c).Where("uid=? AND deleted=?", uid, false).OrderBy("parent_account_id asc, display_order asc").Find(&accounts)

	return accounts, err
}

// GetAccountsInLedger returns only accounts visible in the selected ledger.
// A family member reads the shared ledger from the owner's shard, including
// accounts that another member created there.
func (s *AccountService) GetAccountsInLedger(c core.Context, uid, ledgerId int64) ([]*models.Account, error) {
	db, _, err := Ledgers.GetLedgerWithAccess(c, uid, ledgerId, anyFamilyRole)
	if err != nil {
		return nil, err
	}
	if db == nil {
		db = s.UserDataDB(uid)
	}
	accounts := make([]*models.Account, 0)
	query := db.NewSession(c).Where("deleted=? AND ledger_id=?", false, ledgerId)
	if ledgerId == models.DefaultLedgerId {
		query = query.And("uid=?", uid)
	}
	err = query.OrderBy("parent_account_id asc, display_order asc").Find(&accounts)
	return accounts, err
}

// GetAccountByAccountId returns account model according to account id
func (s *AccountService) GetAccountByAccountId(c core.Context, uid int64, accountId int64) (*models.Account, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	if accountId <= 0 {
		return nil, errs.ErrAccountIdInvalid
	}

	account := &models.Account{}
	has, err := s.UserDataDB(uid).NewSession(c).Where("uid=? AND deleted=? AND account_id=?", uid, false, accountId).Get(account)

	if err != nil {
		return nil, err
	} else if !has {
		return nil, errs.ErrAccountNotFound
	}

	return account, err
}

// GetAccountAndSubAccountsByAccountId returns account model and sub-account models according to account id
func (s *AccountService) GetAccountAndSubAccountsByAccountId(c core.Context, uid int64, accountId int64) ([]*models.Account, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	if accountId <= 0 {
		return nil, errs.ErrAccountIdInvalid
	}

	var accounts []*models.Account
	err := s.UserDataDB(uid).NewSession(c).Where("uid=? AND deleted=? AND (account_id=? OR parent_account_id=?)", uid, false, accountId, accountId).OrderBy("parent_account_id asc, display_order asc").Find(&accounts)

	return accounts, err
}

// GetSubAccountsByAccountId returns sub-account models according to account id
func (s *AccountService) GetSubAccountsByAccountId(c core.Context, uid int64, accountId int64) ([]*models.Account, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	if accountId <= 0 {
		return nil, errs.ErrAccountIdInvalid
	}

	var accounts []*models.Account
	err := s.UserDataDB(uid).NewSession(c).Where("uid=? AND deleted=? AND parent_account_id=?", uid, false, accountId).OrderBy("display_order asc").Find(&accounts)

	return accounts, err
}

// GetSubAccountsByAccountIds returns sub-account models according to account ids
func (s *AccountService) GetSubAccountsByAccountIds(c core.Context, uid int64, accountIds []int64) ([]*models.Account, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	if len(accountIds) <= 0 {
		return nil, errs.ErrAccountIdInvalid
	}

	condition := "uid=? AND deleted=?"
	conditionParams := make([]any, 0, len(accountIds)+2)
	conditionParams = append(conditionParams, uid)
	conditionParams = append(conditionParams, false)

	var accountIdConditions strings.Builder

	for i := 0; i < len(accountIds); i++ {
		if accountIds[i] <= 0 {
			return nil, errs.ErrAccountIdInvalid
		}

		if accountIdConditions.Len() > 0 {
			accountIdConditions.WriteString(",")
		}

		accountIdConditions.WriteString("?")
		conditionParams = append(conditionParams, accountIds[i])
	}

	if accountIdConditions.Len() > 1 {
		condition = condition + " AND parent_account_id IN (" + accountIdConditions.String() + ")"
	} else {
		condition = condition + " AND parent_account_id = " + accountIdConditions.String()
	}

	var accounts []*models.Account
	err := s.UserDataDB(uid).NewSession(c).Where(condition, conditionParams...).OrderBy("display_order asc").Find(&accounts)

	return accounts, err
}

// GetAccountsByAccountIds returns account models according to account ids
func (s *AccountService) GetAccountsByAccountIds(c core.Context, uid int64, accountIds []int64) (map[int64]*models.Account, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	if accountIds == nil {
		return nil, errs.ErrAccountIdInvalid
	}

	var accounts []*models.Account
	err := s.UserDataDB(uid).NewSession(c).Where("uid=? AND deleted=?", uid, false).In("account_id", accountIds).Find(&accounts)

	if err != nil {
		return nil, err
	}

	accountMap := s.GetAccountMapByList(accounts)
	return accountMap, err
}

// GetMaxDisplayOrder returns the max display order according to account category
func (s *AccountService) GetMaxDisplayOrder(c core.Context, uid int64, category models.AccountCategory) (int32, error) {
	if uid <= 0 {
		return 0, errs.ErrUserIdInvalid
	}

	account := &models.Account{}
	has, err := s.UserDataDB(uid).NewSession(c).Cols("uid", "deleted", "parent_account_id", "display_order").Where("uid=? AND deleted=? AND parent_account_id=? AND category=?", uid, false, models.LevelOneAccountParentId, category).OrderBy("display_order desc").Limit(1).Get(account)

	if err != nil {
		return 0, err
	}

	if has {
		return account.DisplayOrder, nil
	} else {
		return 0, nil
	}
}

// GetMaxSubAccountDisplayOrder returns the max display order of sub-account according to account category and parent account id
func (s *AccountService) GetMaxSubAccountDisplayOrder(c core.Context, uid int64, category models.AccountCategory, parentAccountId int64) (int32, error) {
	if uid <= 0 {
		return 0, errs.ErrUserIdInvalid
	}

	if parentAccountId <= 0 {
		return 0, errs.ErrAccountIdInvalid
	}

	account := &models.Account{}
	has, err := s.UserDataDB(uid).NewSession(c).Cols("uid", "deleted", "parent_account_id", "display_order").Where("uid=? AND deleted=? AND parent_account_id=? AND category=?", uid, false, parentAccountId, category).OrderBy("display_order desc").Limit(1).Get(account)

	if err != nil {
		return 0, err
	}

	if has {
		return account.DisplayOrder, nil
	} else {
		return 0, nil
	}
}

// CreateAccounts saves a new account model to database
func (s *AccountService) CreateAccounts(c core.Context, mainAccount *models.Account, mainAccountBalanceTime int64, childrenAccounts []*models.Account, childrenAccountBalanceTimes []int64, clientTimezone *time.Location) error {
	if mainAccount.Uid <= 0 {
		return errs.ErrUserIdInvalid
	}

	needAccountUuidCount := uint16(len(childrenAccounts) + 1)
	accountUuids := s.GenerateUuids(uuid.UUID_TYPE_ACCOUNT, needAccountUuidCount)

	if len(accountUuids) < int(needAccountUuidCount) {
		return errs.ErrSystemIsBusy
	}

	now := time.Now().Unix()
	actorUid := mainAccount.Uid
	postingUid := actorUid
	userDataDb := s.UserDataDB(actorUid)
	if mainAccount.LedgerId > models.DefaultLedgerId {
		db, ledger, err := Ledgers.GetLedgerWithAccess(c, actorUid, mainAccount.LedgerId, models.FamilyMemberRole.CanManage)
		if err != nil {
			return err
		}
		postingUid, err = Ledgers.LedgerDataOwnerUid(c, actorUid, ledger)
		if err != nil {
			return err
		}
		userDataDb = db
	}

	allAccounts := make([]*models.Account, len(childrenAccounts)+1)
	var allInitTransactions []*models.Transaction

	mainAccount.AccountId = accountUuids[0]
	allAccounts[0] = mainAccount

	if mainAccount.Type == models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
		for i := 0; i < len(childrenAccounts); i++ {
			childAccount := childrenAccounts[i]
			childAccount.AccountId = accountUuids[i+1]
			childAccount.ParentAccountId = mainAccount.AccountId
			childAccount.Uid = mainAccount.Uid
			childAccount.Type = models.ACCOUNT_TYPE_SINGLE_ACCOUNT

			allAccounts[i+1] = childrenAccounts[i]
		}
	}

	defaultTransactionTime := utils.GetMinTransactionTimeFromUnixTime(now)

	for i := 0; i < len(allAccounts); i++ {
		allAccounts[i].Deleted = false
		allAccounts[i].CreatedUnixTime = now
		allAccounts[i].UpdatedUnixTime = now

		if allAccounts[i].Balance != 0 {
			transactionId := s.GenerateUuid(uuid.UUID_TYPE_TRANSACTION)

			if transactionId < 1 {
				return errs.ErrSystemIsBusy
			}

			transactionTime := defaultTransactionTime
			transactionUtcOffset := utils.GetTimezoneOffsetMinutes(now, clientTimezone)

			if i == 0 && mainAccountBalanceTime > 0 {
				transactionTime = utils.GetMinTransactionTimeFromUnixTime(mainAccountBalanceTime)
				transactionUtcOffset = utils.GetTimezoneOffsetMinutes(mainAccountBalanceTime, clientTimezone)
			} else if i > 0 && len(childrenAccountBalanceTimes) > i-1 && childrenAccountBalanceTimes[i-1] > 0 {
				transactionTime = utils.GetMinTransactionTimeFromUnixTime(childrenAccountBalanceTimes[i-1])
				transactionUtcOffset = utils.GetTimezoneOffsetMinutes(childrenAccountBalanceTimes[i-1], clientTimezone)
			} else {
				defaultTransactionTime++
			}

			newTransaction := &models.Transaction{
				TransactionId:        transactionId,
				Uid:                  postingUid,
				LedgerId:             allAccounts[i].LedgerId,
				RecorderUid:          actorUid,
				PayerUid:             actorUid,
				Deleted:              false,
				Type:                 models.TRANSACTION_DB_TYPE_MODIFY_BALANCE,
				TransactionTime:      transactionTime,
				TimezoneUtcOffset:    transactionUtcOffset,
				AccountId:            allAccounts[i].AccountId,
				Amount:               allAccounts[i].Balance,
				RelatedAccountId:     allAccounts[i].AccountId,
				RelatedAccountAmount: allAccounts[i].Balance,
				CreatedUnixTime:      now,
				UpdatedUnixTime:      now,
			}

			allInitTransactions = append(allInitTransactions, newTransaction)
		}
	}

	return userDataDb.DoTransaction(c, func(sess *xorm.Session) error {
		for i := 0; i < len(allAccounts); i++ {
			account := allAccounts[i]
			_, err := sess.Insert(account)

			if err != nil {
				return err
			}
		}

		for i := 0; i < len(allInitTransactions); i++ {
			transaction := allInitTransactions[i]

			insertTransactionSavePointName := "insert_transaction"
			err := userDataDb.SetSavePoint(sess, insertTransactionSavePointName)

			if err != nil {
				log.Errorf(c, "[accounts.CreateAccounts] failed to set save point \"%s\", because %s", insertTransactionSavePointName, err.Error())
				return err
			}

			createdRows, err := sess.Insert(transaction)

			if err != nil || createdRows < 1 { // maybe another transaction has same time
				if err != nil {
					log.Warnf(c, "[accounts.CreateAccounts] cannot create transaction, because %s, regenerate transaction time value", err.Error())
				} else {
					log.Warnf(c, "[accounts.CreateAccounts] cannot create transaction, regenerate transaction time value")
				}

				err = userDataDb.RollbackToSavePoint(sess, insertTransactionSavePointName)

				if err != nil {
					log.Errorf(c, "[accounts.CreateAccounts] failed to rollback to save point \"%s\", because %s", insertTransactionSavePointName, err.Error())
					return err
				}

				sameSecondLatestTransaction := &models.Transaction{}
				minTransactionTime := utils.GetMinTransactionTimeFromUnixTime(utils.GetUnixTimeFromTransactionTime(transaction.TransactionTime))
				maxTransactionTime := utils.GetMaxTransactionTimeFromUnixTime(utils.GetUnixTimeFromTransactionTime(transaction.TransactionTime))

				has, err := sess.Where("uid=? AND transaction_time>=? AND transaction_time<=?", transaction.Uid, minTransactionTime, maxTransactionTime).OrderBy("transaction_time desc").Limit(1).Get(sameSecondLatestTransaction)

				if err != nil {
					return err
				} else if !has {
					log.Errorf(c, "[accounts.CreateAccounts] it should have transactions in %d - %d, but result is empty", minTransactionTime, maxTransactionTime)
					return errs.ErrDatabaseOperationFailed
				} else if sameSecondLatestTransaction.TransactionTime == maxTransactionTime-1 {
					return errs.ErrTooMuchTransactionInOneSecond
				}

				transaction.TransactionTime = sameSecondLatestTransaction.TransactionTime + 1
				createdRows, err := sess.Insert(transaction)

				if err != nil {
					log.Errorf(c, "[accounts.CreateAccounts] failed to add transaction again, because %s", err.Error())
					return err
				} else if createdRows < 1 {
					log.Errorf(c, "[accounts.CreateAccounts] failed to add transaction again")
					return errs.ErrDatabaseOperationFailed
				}
			}
		}

		return nil
	})
}

// ModifyAccounts saves an existed account model to database
func (s *AccountService) ModifyAccounts(c core.Context, mainAccount *models.Account, updateAccounts []*models.Account, addSubAccounts []*models.Account, addSubAccountBalanceTimes []int64, removeSubAccountIds []int64, clientTimezone *time.Location) error {
	if mainAccount.Uid <= 0 {
		return errs.ErrUserIdInvalid
	}

	needAccountUuidCount := uint16(len(addSubAccounts))
	newAccountUuids := s.GenerateUuids(uuid.UUID_TYPE_ACCOUNT, needAccountUuidCount)

	if len(newAccountUuids) < int(needAccountUuidCount) {
		return errs.ErrSystemIsBusy
	}

	now := time.Now().Unix()

	var addInitTransactions []*models.Transaction

	for i := 0; i < len(updateAccounts); i++ {
		updateAccounts[i].UpdatedUnixTime = now
	}

	if mainAccount.Type == models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
		defaultTransactionTime := utils.GetMinTransactionTimeFromUnixTime(now)

		for i := 0; i < len(addSubAccounts); i++ {
			childAccount := addSubAccounts[i]
			childAccount.AccountId = newAccountUuids[i]
			childAccount.ParentAccountId = mainAccount.AccountId
			childAccount.Uid = mainAccount.Uid
			childAccount.Type = models.ACCOUNT_TYPE_SINGLE_ACCOUNT
			childAccount.Deleted = false
			childAccount.CreatedUnixTime = now
			childAccount.UpdatedUnixTime = now

			if childAccount.Balance != 0 {
				transactionId := s.GenerateUuid(uuid.UUID_TYPE_TRANSACTION)

				if transactionId < 1 {
					return errs.ErrSystemIsBusy
				}

				transactionTime := defaultTransactionTime
				transactionUtcOffset := utils.GetTimezoneOffsetMinutes(now, clientTimezone)

				if len(addSubAccountBalanceTimes) > i && addSubAccountBalanceTimes[i] > 0 {
					transactionTime = utils.GetMinTransactionTimeFromUnixTime(addSubAccountBalanceTimes[i])
					transactionUtcOffset = utils.GetTimezoneOffsetMinutes(addSubAccountBalanceTimes[i], clientTimezone)
				} else {
					defaultTransactionTime++
				}

				newTransaction := &models.Transaction{
					TransactionId:        transactionId,
					Uid:                  childAccount.Uid,
					Deleted:              false,
					Type:                 models.TRANSACTION_DB_TYPE_MODIFY_BALANCE,
					TransactionTime:      transactionTime,
					TimezoneUtcOffset:    transactionUtcOffset,
					AccountId:            childAccount.AccountId,
					Amount:               childAccount.Balance,
					RelatedAccountId:     childAccount.AccountId,
					RelatedAccountAmount: childAccount.Balance,
					CreatedUnixTime:      now,
					UpdatedUnixTime:      now,
				}

				addInitTransactions = append(addInitTransactions, newTransaction)
			}
		}
	}

	userDataDb := s.UserDataDB(mainAccount.Uid)

	return userDataDb.DoTransaction(c, func(sess *xorm.Session) error {
		// update accounts
		for i := 0; i < len(updateAccounts); i++ {
			account := updateAccounts[i]
			updatedRows, err := sess.ID(account.AccountId).Cols("name", "display_order", "category", "icon", "color", "comment", "extend", "hidden", "updated_unix_time").Where("uid=? AND deleted=?", account.Uid, false).Update(account)

			if err != nil {
				return err
			} else if updatedRows < 1 {
				return errs.ErrAccountNotFound
			}
		}

		// add new sub accounts
		for i := 0; i < len(addSubAccounts); i++ {
			account := addSubAccounts[i]
			_, err := sess.Insert(account)

			if err != nil {
				return err
			}
		}

		// add init transaction for new sub accounts
		for i := 0; i < len(addInitTransactions); i++ {
			transaction := addInitTransactions[i]

			insertTransactionSavePointName := "insert_transaction"
			err := userDataDb.SetSavePoint(sess, insertTransactionSavePointName)

			if err != nil {
				log.Errorf(c, "[accounts.ModifyAccounts] failed to set save point \"%s\", because %s", insertTransactionSavePointName, err.Error())
				return err
			}

			createdRows, err := sess.Insert(transaction)

			if err != nil || createdRows < 1 { // maybe another transaction has same time
				if err != nil {
					log.Warnf(c, "[accounts.ModifyAccounts] cannot create trasaction, because %s, regenerate transaction time value", err.Error())
				} else {
					log.Warnf(c, "[accounts.ModifyAccounts] cannot create trasaction, regenerate transaction time value")
				}

				err = userDataDb.RollbackToSavePoint(sess, insertTransactionSavePointName)

				if err != nil {
					log.Errorf(c, "[accounts.ModifyAccounts] failed to rollback to save point \"%s\", because %s", insertTransactionSavePointName, err.Error())
					return err
				}

				sameSecondLatestTransaction := &models.Transaction{}
				minTransactionTime := utils.GetMinTransactionTimeFromUnixTime(utils.GetUnixTimeFromTransactionTime(transaction.TransactionTime))
				maxTransactionTime := utils.GetMaxTransactionTimeFromUnixTime(utils.GetUnixTimeFromTransactionTime(transaction.TransactionTime))

				has, err := sess.Where("uid=? AND transaction_time>=? AND transaction_time<=?", transaction.Uid, minTransactionTime, maxTransactionTime).OrderBy("transaction_time desc").Limit(1).Get(sameSecondLatestTransaction)

				if err != nil {
					return err
				} else if !has {
					log.Errorf(c, "[accounts.ModifyAccounts] it should have transactions in %d - %d, but result is empty", minTransactionTime, maxTransactionTime)
					return errs.ErrDatabaseOperationFailed
				} else if sameSecondLatestTransaction.TransactionTime == maxTransactionTime-1 {
					return errs.ErrTooMuchTransactionInOneSecond
				}

				transaction.TransactionTime = sameSecondLatestTransaction.TransactionTime + 1
				createdRows, err := sess.Insert(transaction)

				if err != nil {
					log.Errorf(c, "[accounts.ModifyAccounts] failed to add transaction again, because %s", err.Error())
					return err
				} else if createdRows < 1 {
					log.Errorf(c, "[accounts.ModifyAccounts] failed to add transaction again")
					return errs.ErrDatabaseOperationFailed
				}
			}
		}

		// remove sub accounts
		if len(removeSubAccountIds) > 0 {
			subAccountsCount, err := sess.Where("uid=? AND deleted=? AND parent_account_id=?", mainAccount.Uid, false, mainAccount.AccountId).Count(&models.Account{})

			if subAccountsCount <= int64(len(removeSubAccountIds)) {
				return errs.ErrAccountHaveNoSubAccount
			}

			var relatedTransactionsByAccount []*models.Transaction
			err = sess.Cols("transaction_id", "uid", "deleted", "account_id", "type").Where("uid=? AND deleted=?", mainAccount.Uid, false).In("account_id", removeSubAccountIds).Limit(len(removeSubAccountIds) + 1).Find(&relatedTransactionsByAccount)

			if err != nil {
				return err
			} else if len(relatedTransactionsByAccount) > len(removeSubAccountIds) {
				return errs.ErrSubAccountInUseCannotBeDeleted
			} else if len(relatedTransactionsByAccount) > 0 {
				accountTransactionExists := make(map[int64]bool)

				for i := 0; i < len(relatedTransactionsByAccount); i++ {
					transaction := relatedTransactionsByAccount[i]

					if transaction.Type != models.TRANSACTION_DB_TYPE_MODIFY_BALANCE {
						return errs.ErrAccountInUseCannotBeDeleted
					} else if _, exists := accountTransactionExists[transaction.AccountId]; exists {
						return errs.ErrAccountInUseCannotBeDeleted
					}

					accountTransactionExists[transaction.AccountId] = true
				}
			}

			deleteAccountUpdateModel := &models.Account{
				Balance:         0,
				Deleted:         true,
				DeletedUnixTime: now,
			}

			deletedRows, err := sess.Cols("balance", "deleted", "deleted_unix_time").Where("uid=? AND deleted=?", mainAccount.Uid, false).In("account_id", removeSubAccountIds).Update(deleteAccountUpdateModel)

			if err != nil {
				return err
			} else if deletedRows < 1 {
				return errs.ErrSubAccountNotFound
			}

			if len(relatedTransactionsByAccount) > 0 {
				updateTransaction := &models.Transaction{
					Deleted:         true,
					DeletedUnixTime: now,
				}

				transactionIds := make([]int64, len(relatedTransactionsByAccount))

				for i := 0; i < len(relatedTransactionsByAccount); i++ {
					transactionIds[i] = relatedTransactionsByAccount[i].TransactionId
				}

				deletedTransactionRows, err := sess.Cols("deleted", "deleted_unix_time").Where("uid=? AND deleted=?", mainAccount.Uid, false).In("transaction_id", transactionIds).Update(updateTransaction)

				if err != nil {
					return err
				} else if deletedTransactionRows < int64(len(transactionIds)) {
					log.Errorf(c, "[accounts.ModifyAccounts] it should delete %d transactions, but have deleted %d actually", len(transactionIds), deletedTransactionRows)
					return errs.ErrDatabaseOperationFailed
				}
			}
		}

		return nil
	})
}

// UpdateAccountExtend updates extend field of given account
func (s *AccountService) UpdateAccountExtend(c core.Context, uid int64, account *models.Account) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}

	account.UpdatedUnixTime = time.Now().Unix()

	return s.UserDataDB(uid).DoTransaction(c, func(sess *xorm.Session) error {
		updatedRows, err := sess.ID(account.AccountId).Cols("extend", "updated_unix_time").Where("uid=? AND deleted=?", uid, false).Update(account)

		if err != nil {
			return err
		} else if updatedRows < 1 {
			return errs.ErrAccountNotFound
		}

		return nil
	})
}

// HideAccount updates hidden field of given accounts
func (s *AccountService) HideAccount(c core.Context, uid int64, ids []int64, hidden bool) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}

	now := time.Now().Unix()

	updateModel := &models.Account{
		Hidden:          hidden,
		UpdatedUnixTime: now,
	}

	return s.UserDataDB(uid).DoTransaction(c, func(sess *xorm.Session) error {
		updatedRows, err := sess.Cols("hidden", "updated_unix_time").Where("uid=? AND deleted=?", uid, false).In("account_id", ids).Update(updateModel)

		if err != nil {
			return err
		} else if updatedRows < 1 {
			return errs.ErrAccountNotFound
		}

		return nil
	})
}

// ModifyAccountDisplayOrders updates display order of given accounts
func (s *AccountService) ModifyAccountDisplayOrders(c core.Context, uid int64, accounts []*models.Account) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}

	for i := 0; i < len(accounts); i++ {
		accounts[i].UpdatedUnixTime = time.Now().Unix()
	}

	return s.UserDataDB(uid).DoTransaction(c, func(sess *xorm.Session) error {
		for i := 0; i < len(accounts); i++ {
			account := accounts[i]
			updatedRows, err := sess.ID(account.AccountId).Cols("display_order", "updated_unix_time").Where("uid=? AND deleted=?", uid, false).Update(account)

			if err != nil {
				return err
			} else if updatedRows < 1 {
				return errs.ErrAccountNotFound
			}
		}

		return nil
	})
}

// MoveAccountToLedger atomically moves a complete root-account group and all
// compatible history. References that belong to another account group or to a
// savings goal/template are rejected rather than creating cross-ledger links.
func (s *AccountService) MoveAccountToLedger(c core.Context, uid, accountId, targetLedgerId int64) ([]*models.Account, error) {
	sourceDB, _, accounts, err := s.locateAccountGroupForManage(c, uid, accountId)
	if err != nil {
		return nil, err
	}
	root := accounts[0]
	if root.AccountId != accountId {
		return nil, errs.ErrAccountIdInvalid
	}
	if root.LedgerId == targetLedgerId {
		return nil, errs.ErrAccountAlreadyInLedger
	}

	targetDB, targetLedger, err := Ledgers.GetLedgerWithAccess(c, uid, targetLedgerId, models.FamilyMemberRole.CanManage)
	if err != nil {
		return nil, err
	}
	if targetDB == nil {
		targetDB = s.UserDataDB(uid)
	}
	if sourceDB != targetDB {
		return nil, errs.ErrAccountLedgerMigrationAcrossDataStore
	}
	// A shared account may only return to the original creator's private
	// default ledger. This prevents an administrator from taking ownership of
	// a family account created by somebody else.
	if targetLedgerId == models.DefaultLedgerId && root.Uid != uid {
		return nil, errs.ErrLedgerAccessDenied
	}
	targetOwnerUid, err := Ledgers.LedgerDataOwnerUid(c, uid, targetLedger)
	if err != nil {
		return nil, err
	}

	accountIds := make([]int64, len(accounts))
	accountSet := make(map[int64]bool, len(accounts))
	for i, account := range accounts {
		if account.LedgerId != root.LedgerId {
			return nil, errs.ErrAccountLedgerMigrationBlocked
		}
		accountIds[i] = account.AccountId
		accountSet[account.AccountId] = true
	}
	now := time.Now().Unix()

	err = sourceDB.DoTransaction(c, func(sess *xorm.Session) error {
		// Templates and savings-goal funds have their own ledger lifecycle. They
		// must be removed or completed before the account can move.
		var templateRefs []*models.TransactionTemplate
		if err := sess.Where("deleted=?", false).In("account_id", accountIds).Find(&templateRefs); err != nil {
			return err
		}
		var relatedTemplateRefs []*models.TransactionTemplate
		if err := sess.Where("deleted=?", false).In("related_account_id", accountIds).Find(&relatedTemplateRefs); err != nil {
			return err
		}
		if len(templateRefs) > 0 || len(relatedTemplateRefs) > 0 {
			return errs.ErrAccountLedgerMigrationBlocked
		}
		var fundRefs []*models.SavingsGoalFund
		if err := sess.In("account_id", accountIds).Limit(1).Find(&fundRefs); err != nil {
			return err
		}
		if len(fundRefs) > 0 {
			return errs.ErrAccountLedgerMigrationBlocked
		}

		// A queued schedule keeps an immutable account snapshot even if its
		// template has since been removed. Keep that queue valid by blocking the
		// move until the occurrence is confirmed or otherwise gone.
		var occurrences []*models.ScheduledOccurrence
		if err := sess.In("status", []models.ScheduledOccurrenceStatus{models.ScheduledOccurrencePending, models.ScheduledOccurrenceDismissed}).Find(&occurrences); err != nil {
			return err
		}
		for _, occurrence := range occurrences {
			var snapshot models.ScheduledOccurrenceSnapshot
			if json.Unmarshal([]byte(occurrence.SnapshotJSON), &snapshot) == nil &&
				(accountSet[snapshot.SourceAccountId] || accountSet[snapshot.DestinationAccountId]) {
				return errs.ErrAccountLedgerMigrationBlocked
			}
		}

		transactionMap := make(map[int64]*models.Transaction)
		var sourceTransactions []*models.Transaction
		if err := sess.Where("deleted=? AND ledger_id=?", false, root.LedgerId).In("account_id", accountIds).Find(&sourceTransactions); err != nil {
			return err
		}
		var relatedTransactions []*models.Transaction
		if err := sess.Where("deleted=? AND ledger_id=?", false, root.LedgerId).In("related_account_id", accountIds).Find(&relatedTransactions); err != nil {
			return err
		}
		for _, transaction := range append(sourceTransactions, relatedTransactions...) {
			transactionMap[transaction.TransactionId] = transaction
		}
		transactions := make([]*models.Transaction, 0, len(transactionMap))
		for _, transaction := range transactionMap {
			if transaction.SavingsGoalFundId > 0 ||
				(transaction.AccountId > 0 && !accountSet[transaction.AccountId]) ||
				(transaction.RelatedAccountId > 0 && !accountSet[transaction.RelatedAccountId]) {
				return errs.ErrAccountLedgerMigrationBlocked
			}
			transactions = append(transactions, transaction)
		}
		sort.Slice(transactions, func(i, j int) bool { return transactions[i].TransactionTime < transactions[j].TransactionTime })

		// uid+transaction_time is unique. Preserve the displayed Unix second and
		// select the next free sequence slot when the target owner already has a
		// row at the same internal timestamp.
		reserved := make(map[int64]bool, len(transactions))
		for _, transaction := range transactions {
			candidate := transaction.TransactionTime
			maxTime := utils.GetMaxTransactionTimeFromUnixTime(utils.GetUnixTimeFromTransactionTime(candidate))
			for {
				collision := reserved[candidate]
				if !collision {
					exists, queryErr := sess.Where("uid=? AND transaction_time=? AND transaction_id<>?", targetOwnerUid, candidate, transaction.TransactionId).Exist(&models.Transaction{})
					if queryErr != nil {
						return queryErr
					}
					collision = exists
				}
				if !collision {
					break
				}
				candidate++
				if candidate >= maxTime {
					return errs.ErrTooMuchTransactionInOneSecond
				}
			}
			reserved[candidate] = true
			transaction.TransactionTime = candidate
			transaction.Uid = targetOwnerUid
			transaction.LedgerId = targetLedgerId
			if targetLedger.Type == models.LEDGER_TYPE_FAMILY {
				if transaction.RecorderUid == 0 {
					transaction.RecorderUid = root.Uid
				}
				if transaction.PayerUid == 0 {
					transaction.PayerUid = transaction.RecorderUid
				}
			}
			transaction.UpdatedUnixTime = now
			updated, updateErr := sess.ID(transaction.TransactionId).
				Cols("uid", "ledger_id", "recorder_uid", "payer_uid", "transaction_time", "updated_unix_time").
				Where("deleted=? AND ledger_id=?", false, root.LedgerId).Update(transaction)
			if updateErr != nil {
				return updateErr
			}
			if updated != 1 {
				return errs.ErrDatabaseOperationFailed
			}
		}

		updated, updateErr := sess.Cols("ledger_id", "updated_unix_time").
			Where("deleted=? AND ledger_id=?", false, root.LedgerId).In("account_id", accountIds).
			Update(&models.Account{LedgerId: targetLedgerId, UpdatedUnixTime: now})
		if updateErr != nil {
			return updateErr
		}
		if updated != int64(len(accounts)) {
			return errs.ErrDatabaseOperationFailed
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	for _, account := range accounts {
		account.LedgerId = targetLedgerId
		account.UpdatedUnixTime = now
	}
	return accounts, nil
}

// DeleteAccount deletes an existed account from database
func (s *AccountService) DeleteAccount(c core.Context, uid int64, accountId int64) error {
	db, _, locatedAccounts, err := s.locateAccountGroupForManage(c, uid, accountId)
	if err != nil {
		return err
	}
	if locatedAccounts[0].AccountId != accountId {
		return errs.ErrAccountIdInvalid
	}
	ledgerId := locatedAccounts[0].LedgerId
	now := time.Now().Unix()

	updateModel := &models.Account{
		Balance:         0,
		Deleted:         true,
		DeletedUnixTime: now,
	}

	return db.DoTransaction(c, func(sess *xorm.Session) error {
		var accountAndSubAccounts []*models.Account
		err := sess.Where("deleted=? AND ledger_id=? AND ((account_id=? AND parent_account_id=?) OR parent_account_id=?)", false, ledgerId, accountId, models.LevelOneAccountParentId, accountId).Find(&accountAndSubAccounts)

		if err != nil {
			return err
		} else if len(accountAndSubAccounts) < 1 {
			return errs.ErrAccountNotFound
		}

		var accountAndSubAccountIdsConditions strings.Builder
		accountAndSubAccountIds := make([]int64, len(accountAndSubAccounts))

		for i := 0; i < len(accountAndSubAccounts); i++ {
			if accountAndSubAccountIdsConditions.Len() > 0 {
				accountAndSubAccountIdsConditions.WriteString(",")
			}

			accountAndSubAccountIdsConditions.WriteString("?")
			accountAndSubAccountIds[i] = accountAndSubAccounts[i].AccountId
		}

		var bySource []*models.Transaction
		err = sess.Cols("transaction_id", "uid", "deleted", "ledger_id", "account_id", "related_account_id", "type").Where("deleted=? AND ledger_id=?", false, ledgerId).In("account_id", accountAndSubAccountIds).Find(&bySource)
		if err != nil {
			return err
		}
		var byDestination []*models.Transaction
		err = sess.Cols("transaction_id", "uid", "deleted", "ledger_id", "account_id", "related_account_id", "type").Where("deleted=? AND ledger_id=?", false, ledgerId).In("related_account_id", accountAndSubAccountIds).Find(&byDestination)
		relatedMap := make(map[int64]*models.Transaction)
		for _, transaction := range append(bySource, byDestination...) {
			relatedMap[transaction.TransactionId] = transaction
		}
		relatedTransactionsByAccount := make([]*models.Transaction, 0, len(relatedMap))
		for _, transaction := range relatedMap {
			relatedTransactionsByAccount = append(relatedTransactionsByAccount, transaction)
		}

		if err != nil {
			return err
		} else if len(relatedTransactionsByAccount) > len(accountAndSubAccountIds) {
			return errs.ErrAccountInUseCannotBeDeleted
		} else if len(relatedTransactionsByAccount) > 0 {
			accountTransactionExists := make(map[int64]bool)

			for i := 0; i < len(relatedTransactionsByAccount); i++ {
				transaction := relatedTransactionsByAccount[i]

				if transaction.Type != models.TRANSACTION_DB_TYPE_MODIFY_BALANCE {
					return errs.ErrAccountInUseCannotBeDeleted
				} else if _, exists := accountTransactionExists[transaction.AccountId]; exists {
					return errs.ErrAccountInUseCannotBeDeleted
				}

				accountTransactionExists[transaction.AccountId] = true
			}
		}

		transactionTemplateQueryCondition := fmt.Sprintf("uid=? AND deleted=? AND (template_type=? OR (template_type=? AND scheduled_frequency_type<>? AND (scheduled_end_time IS NULL OR scheduled_end_time>=?))) AND (account_id IN (%s) OR related_account_id IN (%s))", accountAndSubAccountIdsConditions.String(), accountAndSubAccountIdsConditions.String())
		transactionTemplateQueryConditionParams := make([]any, 0, len(accountAndSubAccountIds)*2+6)
		transactionTemplateQueryConditionParams = append(transactionTemplateQueryConditionParams, accountAndSubAccounts[0].Uid)
		transactionTemplateQueryConditionParams = append(transactionTemplateQueryConditionParams, false)
		transactionTemplateQueryConditionParams = append(transactionTemplateQueryConditionParams, models.TRANSACTION_TEMPLATE_TYPE_NORMAL)
		transactionTemplateQueryConditionParams = append(transactionTemplateQueryConditionParams, models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE)
		transactionTemplateQueryConditionParams = append(transactionTemplateQueryConditionParams, models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DISABLED)
		transactionTemplateQueryConditionParams = append(transactionTemplateQueryConditionParams, now)

		for i := 0; i < len(accountAndSubAccountIds); i++ {
			transactionTemplateQueryConditionParams = append(transactionTemplateQueryConditionParams, accountAndSubAccountIds[i])
		}

		for i := 0; i < len(accountAndSubAccountIds); i++ {
			transactionTemplateQueryConditionParams = append(transactionTemplateQueryConditionParams, accountAndSubAccountIds[i])
		}

		exists, err := sess.Cols("uid", "deleted", "account_id", "related_account_id", "template_type", "scheduled_frequency_type", "scheduled_end_time").Where(transactionTemplateQueryCondition, transactionTemplateQueryConditionParams...).Limit(1).Exist(&models.TransactionTemplate{})

		if err != nil {
			return err
		} else if exists {
			return errs.ErrAccountInUseCannotBeDeleted
		}

		deletedRows, err := sess.Cols("balance", "deleted", "deleted_unix_time").Where("deleted=? AND ledger_id=?", false, ledgerId).In("account_id", accountAndSubAccountIds).Update(updateModel)

		if err != nil {
			return err
		} else if deletedRows < 1 {
			return errs.ErrAccountNotFound
		}

		if len(relatedTransactionsByAccount) > 0 {
			updateTransaction := &models.Transaction{
				Deleted:         true,
				DeletedUnixTime: now,
			}

			transactionIds := make([]int64, len(relatedTransactionsByAccount))

			for i := 0; i < len(relatedTransactionsByAccount); i++ {
				transactionIds[i] = relatedTransactionsByAccount[i].TransactionId
			}

			deletedTransactionRows, err := sess.Cols("deleted", "deleted_unix_time").Where("deleted=? AND ledger_id=?", false, ledgerId).In("transaction_id", transactionIds).Update(updateTransaction)

			if err != nil {
				return err
			} else if deletedTransactionRows < int64(len(transactionIds)) {
				log.Errorf(c, "[accounts.DeleteAccount] it should delete %d transactions, but have deleted %d actually", len(transactionIds), deletedTransactionRows)
				return errs.ErrDatabaseOperationFailed
			}
		}

		return err
	})
}

// DeleteSubAccount deletes an existed sub-account from database
func (s *AccountService) DeleteSubAccount(c core.Context, uid int64, accountId int64) error {
	db, _, group, err := s.locateAccountGroupForManage(c, uid, accountId)
	if err != nil {
		return err
	}
	var account *models.Account
	for _, item := range group {
		if item.AccountId == accountId && item.ParentAccountId > models.LevelOneAccountParentId {
			account = item
			break
		}
	}
	if account == nil {
		return errs.ErrSubAccountNotFound
	}
	if len(group)-1 <= 1 {
		return errs.ErrAccountHaveNoSubAccount
	}
	now := time.Now().Unix()

	updateModel := &models.Account{
		Balance:         0,
		Deleted:         true,
		DeletedUnixTime: now,
	}

	return db.DoTransaction(c, func(sess *xorm.Session) error {
		subAccountsCount, err := sess.Where("deleted=? AND ledger_id=? AND parent_account_id=?", false, account.LedgerId, account.ParentAccountId).Count(&models.Account{})

		if subAccountsCount <= 1 {
			return errs.ErrAccountHaveNoSubAccount
		}

		var bySource []*models.Transaction
		err = sess.Cols("transaction_id", "deleted", "ledger_id", "account_id", "related_account_id", "type").Where("deleted=? AND ledger_id=? AND account_id=?", false, account.LedgerId, accountId).Find(&bySource)
		if err != nil {
			return err
		}
		var byDestination []*models.Transaction
		err = sess.Cols("transaction_id", "deleted", "ledger_id", "account_id", "related_account_id", "type").Where("deleted=? AND ledger_id=? AND related_account_id=?", false, account.LedgerId, accountId).Find(&byDestination)
		relatedMap := make(map[int64]*models.Transaction)
		for _, transaction := range append(bySource, byDestination...) {
			relatedMap[transaction.TransactionId] = transaction
		}
		relatedTransactionsByAccount := make([]*models.Transaction, 0, len(relatedMap))
		for _, transaction := range relatedMap {
			relatedTransactionsByAccount = append(relatedTransactionsByAccount, transaction)
		}

		if err != nil {
			return err
		} else if len(relatedTransactionsByAccount) > 1 {
			return errs.ErrSubAccountInUseCannotBeDeleted
		} else if len(relatedTransactionsByAccount) > 0 {
			for i := 0; i < len(relatedTransactionsByAccount); i++ {
				transaction := relatedTransactionsByAccount[i]

				if transaction.Type != models.TRANSACTION_DB_TYPE_MODIFY_BALANCE {
					return errs.ErrSubAccountInUseCannotBeDeleted
				}
			}
		}

		exists, err := sess.Cols("uid", "deleted", "account_id", "related_account_id", "template_type", "scheduled_frequency_type", "scheduled_end_time").Where("uid=? AND deleted=? AND (template_type=? OR (template_type=? AND scheduled_frequency_type<>? AND (scheduled_end_time IS NULL OR scheduled_end_time>=?))) AND (account_id=? OR related_account_id=?)", account.Uid, false, models.TRANSACTION_TEMPLATE_TYPE_NORMAL, models.TRANSACTION_TEMPLATE_TYPE_SCHEDULE, models.TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DISABLED, now, accountId, accountId).Limit(1).Exist(&models.TransactionTemplate{})

		if err != nil {
			return err
		} else if exists {
			return errs.ErrSubAccountInUseCannotBeDeleted
		}

		deletedRows, err := sess.Cols("balance", "deleted", "deleted_unix_time").Where("deleted=? AND ledger_id=? AND account_id=?", false, account.LedgerId, accountId).Update(updateModel)

		if err != nil {
			return err
		} else if deletedRows < 1 {
			return errs.ErrSubAccountNotFound
		}

		if len(relatedTransactionsByAccount) > 0 {
			updateTransaction := &models.Transaction{
				Deleted:         true,
				DeletedUnixTime: now,
			}

			transactionIds := make([]int64, len(relatedTransactionsByAccount))

			for i := 0; i < len(relatedTransactionsByAccount); i++ {
				transactionIds[i] = relatedTransactionsByAccount[i].TransactionId
			}

			deletedTransactionRows, err := sess.Cols("deleted", "deleted_unix_time").Where("deleted=? AND ledger_id=?", false, account.LedgerId).In("transaction_id", transactionIds).Update(updateTransaction)

			if err != nil {
				return err
			} else if deletedTransactionRows < int64(len(transactionIds)) {
				log.Errorf(c, "[accounts.DeleteSubAccount] it should delete %d transactions, but have deleted %d actually", len(transactionIds), deletedTransactionRows)
				return errs.ErrDatabaseOperationFailed
			}
		}

		return err
	})
}

// GetAccountMapByList returns an account map by a list
func (s *AccountService) GetAccountMapByList(accounts []*models.Account) map[int64]*models.Account {
	accountMap := make(map[int64]*models.Account)

	for i := 0; i < len(accounts); i++ {
		account := accounts[i]
		accountMap[account.AccountId] = account
	}
	return accountMap
}

// GetVisibleAccountNameMapByList returns visible account map by a list
func (s *AccountService) GetVisibleAccountNameMapByList(accounts []*models.Account) map[string]*models.Account {
	accountMap := make(map[string]*models.Account)

	for i := 0; i < len(accounts); i++ {
		account := accounts[i]

		if account.Hidden {
			continue
		}

		if account.Type == models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
			continue
		}

		accountMap[account.Name] = account
	}
	return accountMap
}

// GetAccountNames returns a list with account names from account models list
func (s *AccountService) GetAccountNames(accounts []*models.Account) []string {
	accountNames := make([]string, len(accounts))

	for i := 0; i < len(accounts); i++ {
		accountNames[i] = accounts[i].Name
	}

	return accountNames
}

// GetAccountOrSubAccountIds returns a list of account ids or sub-account ids according to given account ids
func (s *AccountService) GetAccountOrSubAccountIds(c core.Context, accountIds string, uid int64) ([]int64, error) {
	if accountIds == "" || accountIds == "0" {
		return nil, nil
	}

	requestAccountIds, err := utils.StringArrayToInt64Array(strings.Split(accountIds, ","))

	if err != nil {
		return nil, errs.Or(err, errs.ErrAccountIdInvalid)
	}

	var allAccountIds []int64

	if len(requestAccountIds) > 0 {
		allSubAccounts, err := s.GetSubAccountsByAccountIds(c, uid, requestAccountIds)

		if err != nil {
			return nil, err
		}

		accountIdsMap := make(map[int64]int32, len(requestAccountIds))

		for i := 0; i < len(requestAccountIds); i++ {
			accountIdsMap[requestAccountIds[i]] = 0
		}

		for i := 0; i < len(allSubAccounts); i++ {
			subAccount := allSubAccounts[i]

			if refCount, exists := accountIdsMap[subAccount.ParentAccountId]; exists {
				accountIdsMap[subAccount.ParentAccountId] = refCount + 1
			} else {
				accountIdsMap[subAccount.ParentAccountId] = 1
			}

			if _, exists := accountIdsMap[subAccount.AccountId]; exists {
				delete(accountIdsMap, subAccount.AccountId)
			}

			allAccountIds = append(allAccountIds, subAccount.AccountId)
		}

		for accountId, refCount := range accountIdsMap {
			if refCount < 1 {
				allAccountIds = append(allAccountIds, accountId)
			}
		}
	}

	return allAccountIds, nil
}

// GetAccountOrSubAccountIdsByAccountName returns a list of account ids or sub-account ids according to given account name
func (s *AccountService) GetAccountOrSubAccountIdsByAccountName(accounts []*models.Account, accountName string) []int64 {
	accountIds := make([]int64, 0)
	parentAccountIds := make([]int64, 0)
	childAccountByParentAccountId := make(map[int64][]*models.Account)

	for i := 0; i < len(accounts); i++ {
		account := accounts[i]

		if account.Name == accountName {
			if account.Type == models.ACCOUNT_TYPE_SINGLE_ACCOUNT {
				accountIds = append(accountIds, account.AccountId)
			} else if account.Type == models.ACCOUNT_TYPE_MULTI_SUB_ACCOUNTS {
				parentAccountIds = append(parentAccountIds, account.AccountId)
			}
		} else if account.ParentAccountId > 0 {
			childAccounts, exists := childAccountByParentAccountId[account.ParentAccountId]

			if !exists {
				childAccounts = make([]*models.Account, 0)
			}

			childAccounts = append(childAccounts, account)
			childAccountByParentAccountId[account.ParentAccountId] = childAccounts
		}
	}

	for i := 0; i < len(parentAccountIds); i++ {
		parentAccountId := parentAccountIds[i]

		if childAccounts, exists := childAccountByParentAccountId[parentAccountId]; exists {
			for j := 0; j < len(childAccounts); j++ {
				childAccount := childAccounts[j]
				accountIds = append(accountIds, childAccount.AccountId)
			}
		}
	}

	return accountIds
}
