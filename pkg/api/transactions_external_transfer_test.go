package api

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
)

func TestCreateNewExternalTransferModels(t *testing.T) {
	api := &TransactionsApi{}

	outgoing := api.createNewTransactionModel(7, &models.TransactionCreateRequest{
		Type: models.TRANSACTION_TYPE_TRANSFER, SourceAccountId: 11, SourceAmount: 1200,
	}, "127.0.0.1")
	assert.Equal(t, models.TRANSACTION_DB_TYPE_TRANSFER_OUT, outgoing.Type)
	assert.Equal(t, int64(11), outgoing.AccountId)
	assert.Equal(t, int64(0), outgoing.RelatedAccountId)
	assert.Equal(t, int64(1200), outgoing.Amount)

	incoming := api.createNewTransactionModel(7, &models.TransactionCreateRequest{
		Type: models.TRANSACTION_TYPE_TRANSFER, DestinationAccountId: 22, DestinationAmount: 3400,
	}, "127.0.0.1")
	assert.Equal(t, models.TRANSACTION_DB_TYPE_TRANSFER_IN, incoming.Type)
	assert.Equal(t, int64(22), incoming.AccountId)
	assert.Equal(t, int64(0), incoming.RelatedAccountId)
	assert.Equal(t, int64(3400), incoming.Amount)

	response := incoming.ToTransactionInfoResponse(nil, true)
	require.NotNil(t, response)
	assert.Equal(t, int64(0), response.SourceAccountId)
	assert.Equal(t, int64(22), response.DestinationAccountId)
	assert.Equal(t, int64(3400), response.DestinationAmount)
}

func TestFilterTransactionsKeepsExternalTransfer(t *testing.T) {
	api := &TransactionsApi{}
	account := &models.Account{AccountId: 22}
	incoming := &models.Transaction{
		TransactionId: 33,
		Type:          models.TRANSACTION_DB_TYPE_TRANSFER_IN,
		AccountId:     account.AccountId,
	}

	filtered := api.filterTransactions(nil, 7, []*models.Transaction{incoming}, map[int64]*models.Account{
		account.AccountId: account,
	})

	require.Len(t, filtered, 1)
	assert.Same(t, incoming, filtered[0])
}
