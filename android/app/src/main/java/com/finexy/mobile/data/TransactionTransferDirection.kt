package com.finexy.mobile.data

/** UI/write-model intent only. Room/API persist the canonical two account sides. */
enum class TransactionTransferDirection { INTERNAL, OUT, IN }

/** No existing row means a new form; an unselected source=0 must not imply incoming. */
internal fun initialTransactionTransferDirection(existingType: Int?, source: Long, destination: Long?): TransactionTransferDirection =
    if (existingType != TransactionRepository.TYPE_TRANSFER) TransactionTransferDirection.INTERNAL
    else transferDirectionFromSides(existingType, source, destination)

/** Call only with the explicit, canonical fields returned by a form or an existing row. */
internal fun transferDirectionFromSides(type: Int, source: Long, destination: Long?): TransactionTransferDirection = when {
    type != TransactionRepository.TYPE_TRANSFER -> TransactionTransferDirection.INTERNAL
    source == 0L && (destination ?: 0) > 0L -> TransactionTransferDirection.IN
    source > 0L && (destination ?: 0L) == 0L -> TransactionTransferDirection.OUT
    else -> TransactionTransferDirection.INTERNAL
}

internal data class TransactionTransferFields(val source: Long, val sourceAmount: Long, val destination: Long?, val destinationAmount: Long)

/** The visible primary account/amount have a declared direction; inactive controls never leak into a payload. */
internal fun transactionTransferFields(direction: TransactionTransferDirection, primary: Long, amount: Long,
    secondary: Long?, secondaryAmount: Long): TransactionTransferFields {
    require(amount in 1..9_999_999_999_999L)
    return when (direction) {
        TransactionTransferDirection.INTERNAL -> {
            require(primary >= TransactionEntity.LOCAL_ACCOUNT_ID && primary != 0L && secondary != null &&
                secondary >= TransactionEntity.LOCAL_ACCOUNT_ID && secondary != 0L && primary != secondary && secondaryAmount in 1..9_999_999_999_999L)
            TransactionTransferFields(primary, amount, secondary, secondaryAmount)
        }
        TransactionTransferDirection.OUT -> { require(primary > 0); TransactionTransferFields(primary, amount, null, 0) }
        TransactionTransferDirection.IN -> { require(primary > 0); TransactionTransferFields(0, 0, primary, amount) }
    }
}

internal fun requireTransactionTransferDirection(draft: TransactionDraft) {
    if (draft.type != TransactionRepository.TYPE_TRANSFER) {
        require(draft.transferDirection == TransactionTransferDirection.INTERNAL)
        return
    }
    when (draft.transferDirection) {
        TransactionTransferDirection.INTERNAL -> {
            require(draft.sourceAccountId >= TransactionEntity.LOCAL_ACCOUNT_ID && draft.sourceAccountId != 0L &&
                draft.destinationAccountId != null && draft.destinationAccountId >= TransactionEntity.LOCAL_ACCOUNT_ID && draft.destinationAccountId != 0L &&
                draft.sourceAccountId != draft.destinationAccountId && draft.sourceAmountMinor > 0 && draft.destinationAmountMinor > 0) {
                "账户间转账需要明确的两个不同账户和双侧金额"
            }
        }
        TransactionTransferDirection.OUT -> require(draft.sourceAccountId > 0 && (draft.destinationAccountId ?: 0) == 0L &&
            draft.sourceAmountMinor > 0 && draft.destinationAmountMinor == 0L) { "转出到系统外只使用明确的来源账户与金额" }
        TransactionTransferDirection.IN -> require(draft.sourceAccountId == 0L && (draft.destinationAccountId ?: 0) > 0L &&
            draft.sourceAmountMinor == 0L && draft.destinationAmountMinor > 0L) { "从系统外转入只使用明确的目标账户与金额" }
    }
}
