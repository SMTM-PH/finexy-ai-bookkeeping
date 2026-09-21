package com.finexy.mobile.data

import org.json.JSONObject

/**
 * Remote models and request drafts for family groups, ledgers and savings
 * goals. All identifiers travel as strings and are parsed with strict
 * validation so a malformed server response can never reach Room.
 */

data class RemoteFamilyGroup(
    val id: Long,
    val ownerUid: Long,
    val name: String,
    val comment: String,
    val memberCount: Int,
    val createdTime: Long
) {
    companion object {
        fun from(data: JSONObject): RemoteFamilyGroup {
            fun id(name: String): Long = data.getString(name).toLongOrNull()
                ?.also { require(it > 0) { "家庭响应中的 $name 无效" } } ?: error("家庭响应缺少 $name")
            val created = if (data.has("createdTime") && !data.isNull("createdTime")) data.getLong("createdTime") else 0
            return RemoteFamilyGroup(
                id = id("id"),
                ownerUid = id("ownerUid"),
                name = data.getString("name").trim().also { require(it.isNotEmpty() && it.length <= 64) { "家庭名称无效" } },
                comment = data.optString("comment"),
                memberCount = data.getInt("memberCount").also { require(it >= 0) { "家庭成员数无效" } },
                createdTime = created.also { require(it >= 0) { "家庭创建时间无效" } }
            )
        }
    }

}

data class RemoteFamilyMember(
    val id: Long,
    val familyId: Long,
    val uid: Long,
    val role: Int,
    val status: Int,
    val nickname: String,
    val joinedTime: Long
) {
    val canManage: Boolean get() = role == ROLE_OWNER || role == ROLE_ADMIN
    val canWrite: Boolean get() = role != ROLE_VIEWER
    val isActive: Boolean get() = status == STATUS_ACTIVE

    companion object {
        const val ROLE_OWNER = 1
        const val ROLE_ADMIN = 2
        const val ROLE_MEMBER = 3
        const val ROLE_VIEWER = 4
        const val STATUS_ACTIVE = 1
        const val STATUS_LEFT = 2
        const val STATUS_REMOVED = 3

        fun from(data: JSONObject): RemoteFamilyMember {
            fun id(name: String): Long = data.getString(name).toLongOrNull()
                ?.also { require(it > 0) { "家庭成员响应中的 $name 无效" } } ?: error("家庭成员响应缺少 $name")
            return RemoteFamilyMember(
                id = id("id"),
                familyId = id("familyId"),
                uid = id("uid"),
                role = data.getInt("role").also { require(it in ROLE_OWNER..ROLE_VIEWER) { "家庭成员角色无效" } },
                status = data.getInt("status").also { require(it in STATUS_ACTIVE..STATUS_REMOVED) { "家庭成员状态无效" } },
                nickname = data.optString("nickname"),
                joinedTime = data.getLong("joinedTime").also { require(it >= 0) { "加入时间无效" } }
            )
        }
    }

}

data class RemoteFamilyInvitation(
    val id: Long,
    val familyId: Long,
    val inviteeName: String,
    val role: Int,
    val status: Int,
    val token: String,
    val createdTime: Long,
    val expiredTime: Long
) {
    val isPending: Boolean get() = status == STATUS_PENDING

    companion object {
        const val STATUS_PENDING = 1
        const val STATUS_ACCEPTED = 2
        const val STATUS_REVOKED = 3
        const val STATUS_EXPIRED = 4
        const val STATUS_REJECTED = 5

        fun from(data: JSONObject): RemoteFamilyInvitation {
            fun id(name: String): Long = data.getString(name).toLongOrNull()
                ?.also { require(it > 0) { "邀请响应中的 $name 无效" } } ?: error("邀请响应缺少 $name")
            return RemoteFamilyInvitation(
                id = id("id"),
                familyId = id("familyId"),
                inviteeName = data.getString("inviteeName").trim().also { require(it.isNotEmpty() && it.length <= 64) { "邀请备注无效" } },
                role = data.getInt("role").also { require(it == RemoteFamilyMember.ROLE_MEMBER || it == RemoteFamilyMember.ROLE_VIEWER) { "邀请角色无效" } },
                status = data.getInt("status").also { require(it in STATUS_PENDING..STATUS_REJECTED) { "邀请状态无效" } },
                token = data.getString("token").trim().also { require(it.isNotEmpty() && it.length <= 64) { "邀请码无效" } },
                createdTime = if (data.has("createdTime") && !data.isNull("createdTime")) data.getLong("createdTime") else 0,
                expiredTime = data.getLong("expiredTime").also { require(it >= 0) { "邀请过期时间无效" } }
            )
        }
    }
}

data class RemoteLedger(
    val id: Long,
    val ownerUid: Long,
    val type: Int,
    val familyId: Long,
    val name: String,
    val comment: String,
    val createdTime: Long
) {
    val isFamilyLedger: Boolean get() = type == TYPE_FAMILY

    companion object {
        const val TYPE_PERSONAL = 1
        const val TYPE_FAMILY = 2
        const val DEFAULT_LEDGER_ID = 0L

        fun from(data: JSONObject): RemoteLedger {
            fun id(name: String, required: Boolean): Long {
                if (!required && (!data.has(name) || data.isNull(name))) return 0
                return data.getString(name).toLongOrNull()
                    ?.also { require(it >= if (required) 1 else 0) { "账本响应中的 $name 无效" } }
                    ?: error("账本响应缺少 $name")
            }
            val type = data.getInt("type")
            val familyId = id("familyId", required = false)
            require(type == TYPE_PERSONAL || type == TYPE_FAMILY) { "账本类型无效" }
            require((type == TYPE_PERSONAL) == (familyId == DEFAULT_LEDGER_ID)) { "账本归属范围与家庭引用不一致" }
            return RemoteLedger(
                id = id("id", required = true),
                ownerUid = id("ownerUid", required = true),
                type = type,
                familyId = familyId,
                name = data.getString("name").trim().also { require(it.isNotEmpty() && it.length <= 64) { "账本名称无效" } },
                comment = data.optString("comment"),
                createdTime = if (data.has("createdTime") && !data.isNull("createdTime")) data.getLong("createdTime") else 0
            )
        }
    }

}

data class RemoteLedgerMember(
    val id: Long, val ledgerId: Long, val uid: Long, val role: Int, val status: Int,
    val nickname: String, val joinedTime: Long, val isCurrentUser: Boolean
) {
    companion object {
        const val ROLE_OWNER = 1; const val ROLE_ADMIN = 2; const val ROLE_MEMBER = 3; const val ROLE_VIEWER = 4
        fun from(data: JSONObject) = RemoteLedgerMember(
            id = data.getString("id").toLong().also { require(it > 0) },
            ledgerId = data.getString("ledgerId").toLong().also { require(it > 0) },
            uid = data.getString("uid").toLong().also { require(it > 0) },
            role = data.getInt("role").also { require(it in ROLE_OWNER..ROLE_VIEWER) },
            status = data.getInt("status").also { require(it in 1..3) },
            nickname = data.optString("nickname"), joinedTime = data.optLong("joinedTime"),
            isCurrentUser = data.optBoolean("isCurrentUser", false)
        )
    }
}

data class RemoteLedgerInvitation(
    val id: Long, val ledgerId: Long, val inviteeName: String, val role: Int,
    val status: Int, val token: String, val expiredTime: Long
) {
    companion object {
        fun from(data: JSONObject) = RemoteLedgerInvitation(
            id = data.getString("id").toLong().also { require(it > 0) },
            ledgerId = data.getString("ledgerId").toLong().also { require(it > 0) },
            inviteeName = data.getString("inviteeName").trim().also { require(it.isNotEmpty()) },
            role = data.getInt("role").also { require(it == 3 || it == 4) },
            status = data.getInt("status").also { require(it in 1..4) },
            token = data.getString("token").trim().also { require(it.isNotEmpty()) },
            expiredTime = data.optLong("expiredTime")
        )
    }
}

data class RemoteLedgerInvitationPreview(
    val ledger: RemoteLedger, val role: Int, val inviteeName: String,
    val inviterNickname: String, val expiredTime: Long
) {
    companion object {
        fun from(data: JSONObject) = RemoteLedgerInvitationPreview(
            ledger = RemoteLedger.from(data.getJSONObject("ledger")),
            role = data.getInt("role").also { require(it == RemoteLedgerMember.ROLE_MEMBER || it == RemoteLedgerMember.ROLE_VIEWER) },
            inviteeName = data.getString("inviteeName").trim().also { require(it.isNotEmpty()) },
            inviterNickname = data.optString("inviterNickname").trim(),
            expiredTime = data.getLong("expiredTime").also { require(it > 0) }
        )
    }
}

data class RemoteLedgerDeletePreview(
    val ledgerId: Long, val ledgerName: String, val activeMemberCount: Int, val pendingInvitationCount: Int,
    val accountCount: Int, val transactionCount: Int, val savingsGoalCount: Int, val savingsGoalFundCount: Int,
    val canDelete: Boolean, val blockingReasons: List<String>
) {
    companion object {
        private val validBlockers = setOf("accounts", "transactions", "savingsGoals")
        fun from(data: JSONObject): RemoteLedgerDeletePreview {
            fun count(name: String) = data.getInt(name).also { require(it >= 0) { "$name 无效" } }
            val blockers = data.getJSONArray("blockingReasons").let { array ->
                (0 until array.length()).map { array.getString(it) }.also { require(it.all(validBlockers::contains)) }
            }
            val accountCount = count("accountCount"); val transactionCount = count("transactionCount"); val goalCount = count("savingsGoalCount")
            val blocked = accountCount > 0 || transactionCount > 0 || goalCount > 0
            val canDelete = data.getBoolean("canDelete").also { require(it != blocked) { "删除状态与关联数据不一致" } }
            return RemoteLedgerDeletePreview(
                ledgerId = data.getString("ledgerId").toLong().also { require(it > 0) },
                ledgerName = data.getString("ledgerName").trim().also { require(it.isNotEmpty()) },
                activeMemberCount = count("activeMemberCount"), pendingInvitationCount = count("pendingInvitationCount"),
                accountCount = accountCount, transactionCount = transactionCount, savingsGoalCount = goalCount,
                savingsGoalFundCount = count("savingsGoalFundCount"), canDelete = canDelete, blockingReasons = blockers)
        }
    }
}

data class RemoteLedgerBalance(val currency: String, val balanceMinor: Long)

data class RemoteLedgerOverview(
    val ledgerId: Long, val ledgerName: String, val activeMemberCount: Int,
    val accountCount: Int, val transactionCount: Int, val savingsGoalCount: Int,
    val balances: List<RemoteLedgerBalance>
) {
    companion object {
        fun from(data: JSONObject): RemoteLedgerOverview {
            fun count(name: String) = data.getInt(name).also { require(it >= 0) { "$name 无效" } }
            val ledgerId = data.getString("ledgerId").toLongOrNull()
                ?.also { require(it >= 0) { "账本 ID 无效" } } ?: error("账本 ID 无效")
            val balancesJson = data.getJSONArray("balances")
            val balances = (0 until balancesJson.length()).map { index ->
                val item = balancesJson.getJSONObject(index)
                RemoteLedgerBalance(
                    currency = item.getString("currency").also { require(it.matches(Regex("[A-Z]{3}"))) { "币种无效" } },
                    balanceMinor = item.getLong("balance")
                )
            }
            require(balances.map { it.currency }.distinct().size == balances.size) { "余额币种重复" }
            return RemoteLedgerOverview(
                ledgerId = ledgerId,
                ledgerName = data.getString("ledgerName").trim().also { require(it.isNotEmpty()) { "账本名称无效" } },
                activeMemberCount = count("activeMemberCount"), accountCount = count("accountCount"),
                transactionCount = count("transactionCount"), savingsGoalCount = count("savingsGoalCount"),
                balances = balances
            )
        }
    }
}

data class RemoteSavingsGoal(
    val id: Long,
    val uid: Long,
    val ledgerId: Long,
    val name: String,
    val targetAmountMinor: Long,
    val savedAmountMinor: Long,
    val achieved: Boolean,
    val deadlineTime: Long,
    val comment: String
) {
    val progressPercent: Double
        get() = if (targetAmountMinor <= 0) 0.0 else savedAmountMinor * 100.0 / targetAmountMinor

    fun toEntity() = SavingsGoalEntity(
        id = id, uid = uid, ledgerId = ledgerId, name = name, targetAmountMinor = targetAmountMinor,
        savedAmountMinor = savedAmountMinor, achieved = achieved, deadlineTime = deadlineTime, comment = comment
    )

    companion object {
        fun from(data: JSONObject): RemoteSavingsGoal {
            fun id(name: String): Long = data.getString(name).toLongOrNull()
                ?.also { require(it > 0) { "存钱目标响应中的 $name 无效" } } ?: error("存钱目标响应缺少 $name")
            val ledgerId = if (data.has("ledgerId") && !data.isNull("ledgerId")) {
                data.getString("ledgerId").toLongOrNull()?.also { require(it >= 0) { "存钱目标账本无效" } }
                    ?: error("存钱目标账本无效")
            } else {
                0
            }
            val deadline = if (data.has("deadlineTime") && !data.isNull("deadlineTime")) data.getLong("deadlineTime") else 0
            return RemoteSavingsGoal(
                id = id("id"),
                uid = id("uid"),
                ledgerId = ledgerId,
                name = data.getString("name").trim().also { require(it.isNotEmpty() && it.length <= 128) { "存钱目标名称无效" } },
                targetAmountMinor = data.getLong("targetAmount").also { require(it > 0) { "目标金额无效" } },
                savedAmountMinor = data.getLong("savedAmount").also { require(it >= 0) { "已存金额无效" } },
                achieved = data.getBoolean("achieved"),
                deadlineTime = deadline.also { require(it >= 0) { "目标日期无效" } },
                comment = data.optString("comment")
            )
        }
    }
}

data class SavingsGoalDraft(
    val name: String,
    val targetAmountMinor: Long,
    val deadlineTime: Long = 0,
    val comment: String = ""
) {
    fun validate() {
        require(name.trim().isNotEmpty() && name.trim().length <= 128) { "目标名称不能为空且最多 128 个字符" }
        require(targetAmountMinor in 1..9_999_999_999_999) { "目标金额必须大于 0" }
        require(deadlineTime >= 0) { "目标日期无效" }
        require(comment.trim().length <= 255) { "备注最多 255 个字符" }
    }

    fun toCreatePayload(ledgerId: Long): JSONObject {
        validate()
        require(ledgerId >= 0) { "账本无效" }
        return JSONObject()
            .put("ledgerId", if (ledgerId == 0L) "0" else ledgerId.toString())
            .put("name", name.trim())
            .put("targetAmount", targetAmountMinor)
            .put("comment", comment.trim())
            .also { payload -> if (deadlineTime > 0) payload.put("deadlineTime", deadlineTime) }
    }

    fun toModifyPayload(id: Long): JSONObject {
        require(id > 0) { "目标 ID 无效" }
        validate()
        return JSONObject()
            .put("id", id.toString())
            .put("name", name.trim())
            .put("targetAmount", targetAmountMinor)
            .put("comment", comment.trim())
            .also { payload -> if (deadlineTime > 0) payload.put("deadlineTime", deadlineTime) }
    }
}

data class RemoteSavingsGoalFund(
    val id: Long,
    val goalId: Long,
    val direction: Int,
    val amountMinor: Long,
    val accountId: Long,
    val transactionId: Long,
    val comment: String,
    val createdTime: Long
) {
    val isDeposit: Boolean get() = direction == DIRECTION_DEPOSIT

    companion object {
        const val DIRECTION_DEPOSIT = 1
        const val DIRECTION_WITHDRAW = 2

        fun from(data: JSONObject): RemoteSavingsGoalFund {
            fun id(name: String): Long = data.getString(name).toLongOrNull()
                ?.also { require(it > 0) { "资金记录响应中的 $name 无效" } } ?: error("资金记录响应缺少 $name")
            val created = if (data.has("createdTime") && !data.isNull("createdTime")) data.getLong("createdTime") else 0
            val transactionId = if (data.has("transactionId") && !data.isNull("transactionId")) {
                data.getString("transactionId").toLongOrNull()
                    ?.also { require(it > 0) { "资金记录响应中的 transactionId 无效" } }
                    ?: error("资金记录响应中的 transactionId 无效")
            } else 0
            return RemoteSavingsGoalFund(
                id = id("id"),
                goalId = id("goalId"),
                direction = data.getInt("direction").also { require(it == DIRECTION_DEPOSIT || it == DIRECTION_WITHDRAW) { "资金方向无效" } },
                amountMinor = data.getLong("amount").also { require(it > 0) { "资金金额无效" } },
                accountId = id("accountId"),
                transactionId = transactionId,
                comment = data.optString("comment"),
                createdTime = created.also { require(it >= 0) { "资金时间无效" } }
            )
        }
    }
}

data class RemoteAccountOption(
    val id: Long,
    val name: String,
    val currency: String,
    val balanceMinor: Long
) {
    companion object {
        fun from(data: JSONObject): RemoteAccountOption {
            val id = data.getString("id").toLongOrNull()?.also { require(it > 0) { "账户 ID 无效" } }
                ?: error("账户响应缺少 id")
            val balance = if (data.has("balance") && !data.isNull("balance")) data.getLong("balance") else 0
            return RemoteAccountOption(
                id = id,
                name = data.getString("name").trim().also { require(it.isNotEmpty()) { "账户名称无效" } },
                currency = data.getString("currency").also { require(it.length == 3) { "账户币种无效" } },
				balanceMinor = balance
            )
        }
    }
}


fun RemoteFamilyGroup.toEntity() = FamilyGroupEntity(
    id = id, ownerUid = ownerUid, name = name, comment = comment, memberCount = memberCount, createdTime = createdTime
)

fun RemoteFamilyMember.toEntity() = FamilyMemberEntity(
    id = id, familyId = familyId, uid = uid, role = role, status = status, nickname = nickname, joinedTime = joinedTime
)

fun RemoteLedger.toEntity() = LedgerEntity(
    id = id, ownerUid = ownerUid, type = type, familyId = familyId, name = name, comment = comment, createdTime = createdTime
)

fun SavingsGoalEntity.toRemote() = RemoteSavingsGoal(
    id = id, uid = uid, ledgerId = ledgerId, name = name, targetAmountMinor = targetAmountMinor,
    savedAmountMinor = savedAmountMinor, achieved = achieved, deadlineTime = deadlineTime, comment = comment
)
