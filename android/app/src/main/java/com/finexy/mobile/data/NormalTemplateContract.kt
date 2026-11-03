package com.finexy.mobile.data

import kotlinx.serialization.json.*
import java.util.UUID

/** A new, unposted entry. It retains integer money and the ledger selected at application time. */
data class TemplateEntrySeed(
    val localId: String, val ledgerId: Long, val type: Int, val categoryId: Long,
    val sourceAccountId: Long, val destinationAccountId: Long, val sourceAmountMinor: Long,
    val destinationAmountMinor: Long, val tagIdsJson: String, val comment: String,
    val hideAmount: Boolean, val sourceCurrency: String, val destinationCurrency: String
)

internal fun templateTagIds(raw: String): List<Long> {
    val values = Json.parseToJsonElement(raw) as? JsonArray ?: error("模板标签不完整")
    return values.map {
        val value = it as? JsonPrimitive ?: error("模板标签 ID 无效")
        value.content.toLongOrNull()?.takeIf { id -> id > 0 && id.toString() == value.content } ?: error("模板标签 ID 无效")
    }.also { require(it.distinct().size == it.size && it.size <= 10) { "模板标签重复或过多" } }
}

internal fun selectableTemplateAccount(account: AccountEntity, accounts: List<AccountEntity>): Boolean =
    account.id > 0 && account.type == 1 && !account.hidden && account.currency.matches(Regex("[A-Z]{3}")) &&
        (account.parentId == 0L || accounts.singleOrNull { it.id == account.parentId }?.let { !it.hidden && it.type == 2 } == true)

/** Both application and management use explicit IDs, including same-name resources. */
internal fun validateNormalTemplate(template: TemplateEntity, ledgerId: Long, accounts: List<AccountEntity>, categories: List<CategoryEntity>) {
    require(ledgerId >= 0 && template.ledgerId == ledgerId && template.templateType == 1 && template.name.isNotBlank()) { "模板不属于当前账本或类型无效" }
    require(template.type in 2..4 && template.sourceAmountMinor in -9_999_999_999_999L..9_999_999_999_999L &&
        (template.type == 3 || template.sourceAmountMinor >= 0)) { "模板类型或金额无效" }
    require(accounts.singleOrNull { it.id == template.sourceAccountId }?.let { selectableTemplateAccount(it, accounts) } == true) { "请选择此账本的可用末级账户" }
    if (template.type == 4) {
        require(template.destinationAccountId != template.sourceAccountId && template.destinationAmountMinor in 0..9_999_999_999_999L &&
            accounts.singleOrNull { it.id == template.destinationAccountId }?.let { selectableTemplateAccount(it, accounts) } == true) { "转账模板须保留两个可用账户与双金额" }
        val source = accounts.single { it.id == template.sourceAccountId }
        val destination = accounts.single { it.id == template.destinationAccountId }
        require(source.currency != destination.currency || template.sourceAmountMinor == template.destinationAmountMinor) { "同币种转账两侧金额须相等" }
    } else require(template.destinationAccountId == 0L && template.destinationAmountMinor == 0L) { "非转账模板包含转入字段" }
    val categoryType = when (template.type) { 2 -> 1; 4 -> 3; else -> 2 }
    val category = categories.singleOrNull { it.id == template.categoryId }
    require(category != null && category.type == categoryType && !category.hidden && category.parentId > 0 &&
        categories.singleOrNull { it.id == category.parentId }?.let { !it.hidden && it.type == categoryType && it.parentId == 0L } == true &&
        categories.none { it.parentId == category.id }) { "请选择同类型的可用末级分类" }
    templateTagIds(template.tagIdsJson)
}

internal fun applyNormalTemplate(template: TemplateEntity, ledgerId: Long, role: Int, accounts: List<AccountEntity>, categories: List<CategoryEntity>, removeOwnerTagsConfirmed: Boolean = false): TemplateEntrySeed {
    require(role in RemoteLedgerMember.ROLE_OWNER..RemoteLedgerMember.ROLE_MEMBER) { "当前成员只读，不能应用模板" }
    require(!template.hidden && template.id > 0) { "此模板已隐藏或无效，请重新读取" }
    validateNormalTemplate(template, ledgerId, accounts, categories)
    val tags = templateTagIds(template.tagIdsJson)
    val mustRemoveTags = ledgerId > 0 && role != RemoteLedgerMember.ROLE_OWNER && tags.isNotEmpty()
    require(!mustRemoveTags || removeOwnerTagsConfirmed) { "请确认去除所有者标签，或取消应用模板" }
    val source = accounts.single { it.id == template.sourceAccountId }
    val destination = accounts.singleOrNull { it.id == template.destinationAccountId }
    return TemplateEntrySeed(UUID.randomUUID().toString(), ledgerId, template.type, template.categoryId,
        template.sourceAccountId, template.destinationAccountId, template.sourceAmountMinor, template.destinationAmountMinor,
        if (mustRemoveTags) "[]" else template.tagIdsJson, template.comment, template.hideAmount, source.currency, destination?.currency.orEmpty())
}
