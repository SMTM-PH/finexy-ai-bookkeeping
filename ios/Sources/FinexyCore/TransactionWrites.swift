import Foundation

public enum LedgerRole: Int, Codable, Sendable {
    case owner = 1, admin, member, viewer

    public var canWrite: Bool { self != .viewer }
}

public enum TransactionKind: Int, Codable, CaseIterable, Sendable {
    case balanceAdjustment = 1, income, expense, transfer

    var categoryType: Int { rawValue - 1 }
}

public enum TransactionDraftError: LocalizedError, Equatable, Sendable {
    case invalidTime, invalidAmount, invalidAccount, invalidCategory, invalidTransfer, commentTooLong
    case readOnly, invalidSubmissionID, balanceAdjustmentUnavailable, externalTransferReadOnly, editUpgradeRequired

    public var errorDescription: String? {
        switch self {
        case .invalidTime: return "请选择有效的记账时间与时区。"
        case .invalidAmount: return "请输入有效金额，最多保留两位小数。"
        case .invalidAccount: return "请选择当前账本的可用账户。"
        case .invalidCategory: return "请选择与流水类型匹配的可用末级分类。"
        case .invalidTransfer: return "请核对转出与转入账户及金额。同币种转账的两侧金额必须相同。"
        case .commentTooLong: return "备注最多可输入 255 个字符。"
        case .readOnly: return "当前账本权限或流水状态不允许此操作。"
        case .invalidSubmissionID: return "待确认请求标识无效，请重新打开记账页。"
        case .balanceAdjustmentUnavailable: return "此账本或流水不支持修改期初余额。"
        case .externalTransferReadOnly: return "当前服务端仅支持新增外部转账。"
        case .editUpgradeRequired: return "此服务端缺少安全编辑接口，请更新后重试。"
        }
    }
}

public struct TransactionDraft: Codable, Equatable, Sendable {
    public static let maximumAmount: Int64 = 9_999_999_999_999

    public let type: TransactionKind
    public let categoryID: String
    public let time: Int64
    public let utcOffset: Int
    public let sourceAccountID: String
    public let destinationAccountID: String
    public let sourceAmount: Int64
    public let destinationAmount: Int64
    public let hideAmount: Bool
    public let comment: String

    public init(type: TransactionKind, categoryID: String, time: Int64, utcOffset: Int,
                sourceAccountID: String, destinationAccountID: String = "0", sourceAmount: Int64,
                destinationAmount: Int64 = 0, hideAmount: Bool = false, comment: String = "") {
        self.type = type
        self.categoryID = categoryID
        self.time = time
        self.utcOffset = utcOffset
        self.sourceAccountID = sourceAccountID
        self.destinationAccountID = destinationAccountID
        self.sourceAmount = sourceAmount
        self.destinationAmount = destinationAmount
        self.hideAmount = hideAmount
        self.comment = comment
    }

    public init?(record: TransactionRecord) {
        guard let kind = TransactionKind(rawValue: record.type) else { return nil }
        self.init(type: kind, categoryID: record.categoryId, time: record.time, utcOffset: record.utcOffset,
                  sourceAccountID: record.sourceAccountId, destinationAccountID: record.destinationAccountId ?? "0",
                  sourceAmount: record.sourceAmount, destinationAmount: record.destinationAmount ?? 0,
                  hideAmount: record.hideAmount, comment: record.comment)
    }

    public static func isAccountSelectable(_ id: String, ledgerID: String, accounts: [String: Account]) -> Bool {
        guard let account = accounts[id], account.ledgerId == ledgerID, !account.hidden,
              account.type == 1, account.subAccounts?.isEmpty != false,
              account.currency.utf8.count == 3,
              account.currency.utf8.allSatisfy({ (65...90).contains($0) }) else { return false }
        if account.parentId == "0" { return true }
        guard let parent = accounts[account.parentId] else { return false }
        return parent.ledgerId == ledgerID && !parent.hidden && parent.type == 2
            && parent.subAccounts?.contains(where: { $0.id == id && $0.parentId == parent.id }) == true
    }

    public static func isCategorySelectable(_ id: String, kind: TransactionKind, categories: [String: TransactionCategory]) -> Bool {
        guard kind != .balanceAdjustment, let category = categories[id], !category.hidden,
              category.type == kind.categoryType, category.subCategories?.isEmpty != false,
              let parent = categories[category.parentId], parent.parentId == "0", !parent.hidden,
              parent.type == category.type else { return false }
        return parent.subCategories?.contains { $0.id == id && $0.parentId == parent.id } == true
    }

    public func validate(accounts: [String: Account], categories: [String: TransactionCategory], ledgerID: String) throws {
        try validateWire(ledgerID: ledgerID)
        let selected = [sourceAccountID, destinationAccountID].filter { $0 != "0" }
        guard selected.allSatisfy({ Self.isAccountSelectable($0, ledgerID: ledgerID, accounts: accounts) }) else {
            throw TransactionDraftError.invalidAccount
        }
        if type != .balanceAdjustment,
           !Self.isCategorySelectable(categoryID, kind: type, categories: categories) {
            throw TransactionDraftError.invalidCategory
        }
        if type == .transfer, selected.count == 2,
           accounts[sourceAccountID]?.currency == accounts[destinationAccountID]?.currency,
           sourceAmount != destinationAmount {
            throw TransactionDraftError.invalidTransfer
        }
    }

    func validateWire(ledgerID: String) throws {
        guard time > 0, (-720...840).contains(utcOffset) else { throw TransactionDraftError.invalidTime }
        let allowedAmount = -Self.maximumAmount...Self.maximumAmount
        guard allowedAmount.contains(sourceAmount), allowedAmount.contains(destinationAmount) else {
            throw TransactionDraftError.invalidAmount
        }
        guard comment.unicodeScalars.count <= 255 else { throw TransactionDraftError.commentTooLong }
        guard Int64(sourceAccountID).map({ $0 >= 0 && String($0) == sourceAccountID }) == true,
              Int64(destinationAccountID).map({ $0 >= 0 && String($0) == destinationAccountID }) == true else {
            throw TransactionDraftError.invalidAccount
        }

        if type == .balanceAdjustment {
            guard ledgerID == "0" else { throw TransactionDraftError.balanceAdjustmentUnavailable }
            guard categoryID == "0" else { throw TransactionDraftError.invalidCategory }
        } else {
            guard Self.isPositiveID(categoryID) else { throw TransactionDraftError.invalidCategory }
        }

        if type == .transfer {
            guard sourceAccountID != destinationAccountID,
                  (sourceAccountID == "0" ? sourceAmount == 0 : sourceAmount > 0),
                  (destinationAccountID == "0" ? destinationAmount == 0 : destinationAmount > 0) else {
                throw TransactionDraftError.invalidTransfer
            }
        } else {
            guard sourceAccountID != "0", destinationAccountID == "0", destinationAmount == 0 else {
                throw TransactionDraftError.invalidAccount
            }
            if type != .balanceAdjustment, sourceAmount == 0 { throw TransactionDraftError.invalidAmount }
        }
    }

    static func isPositiveID(_ id: String) -> Bool {
        Int64(id).map { $0 > 0 && String($0) == id } == true
    }
}

public enum TransactionCreateProtocol: String, Codable, Sendable {
    case dedicated = "durable-receipt-v1"
    case releaseCompatible = "released-add-v1"
}

/// An immutable, ledger-bound submission to persist before the first POST.
public struct TransactionCreateRequest: Codable, Equatable, Sendable {
    public let ledgerID: String
    public let draft: TransactionDraft
    public let clientSessionID: String
    public let writeProtocol: TransactionCreateProtocol

    public init(ledgerID: String, draft: TransactionDraft, clientSessionID: String, writeProtocol: TransactionCreateProtocol) {
        self.ledgerID = ledgerID
        self.draft = draft
        self.clientSessionID = clientSessionID
        self.writeProtocol = writeProtocol
    }
}

public struct TransactionDetail: Decodable, Sendable {
    public let record: TransactionRecord
    public let editable: Bool
    let editToken: String?
    private let savingsGoalFundID: String?

    public var canDelete: Bool {
        guard editable, let kind = TransactionKind(rawValue: record.type), savingsGoalFundID == nil else { return false }
        return kind != .transfer || (record.sourceAccountId != "0" && (record.destinationAccountId ?? "0") != "0")
    }
    public var canEdit: Bool { canDelete && editToken != nil }

    private enum CodingKeys: String, CodingKey { case editable, editToken, savingsGoalFundId }

    public init(from decoder: Decoder) throws {
        record = try TransactionRecord(from: decoder)
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        editable = try fields.decode(Bool.self, forKey: .editable)
        savingsGoalFundID = try fields.decodeIfPresent(String.self, forKey: .savingsGoalFundId)
        let editToken = try fields.decodeIfPresent(String.self, forKey: .editToken)
        if let editToken {
            guard editToken.utf8.count == 64,
                  editToken.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
                throw APIError.invalidResponse
            }
        }
        self.editToken = editToken
    }

    func matchesSnapshot(_ previous: Self) -> Bool {
        record.id == previous.record.id && record.ledgerId == previous.record.ledgerId
            && record.timeSequenceId == previous.record.timeSequenceId
            && TransactionDraft(record: record) == TransactionDraft(record: previous.record)
            && editToken == previous.editToken
    }
}

extension MoneyFormatter {
    /// Parse ungrouped decimal input exactly, including pasted ASCII-dot amounts.
    public static func parseMinorUnits(_ input: String, locale: Locale = .current) -> Int64? {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let negative = value.hasPrefix("-") || value.hasPrefix("−")
        let number = negative ? String(value.dropFirst()) : value
        let separator = locale.decimalSeparator ?? "."
        let parts = number.components(separatedBy: number.contains(separator) ? separator : ".")
        guard parts.count <= 2, !number.isEmpty else { return nil }
        let whole = parts[0]
        let fraction = parts.count == 2 ? parts[1] : ""
        guard !whole.isEmpty || !fraction.isEmpty, fraction.count <= 2,
              let units = parseDigits(whole), let fractional = parseDigits(fraction),
              units <= TransactionDraft.maximumAmount / 100 else { return nil }
        let minor = units * 100 + fractional * (fraction.count == 1 ? 10 : 1)
        guard minor <= TransactionDraft.maximumAmount else { return nil }
        return negative ? -minor : minor
    }

    private static func parseDigits(_ digits: String) -> Int64? {
        let values = digits.compactMap { digit -> Int? in
            guard digit.unicodeScalars.count == 1,
                  digit.unicodeScalars.first?.properties.generalCategory == .decimalNumber,
                  let value = digit.wholeNumberValue else { return nil }
            return value
        }
        guard values.count == digits.count else { return nil }
        return digits.isEmpty ? 0 : Int64(values.map(String.init).joined())
    }
}

extension APIError {
    /// Use only for the first submission. A later rejection cannot establish that
    /// a previous request with a lost response never committed.
    public var isDefinitiveTransactionRejection: Bool {
        switch self {
        case .unsupportedTransactionWrites:
            return true
        case let .server(code?, status):
            return (200_000...299_999).contains(code)
                && (400...499).contains(status)
                && ![408, 409, 425, 429].contains(status)
                && ![200_012, 200_019, 205_001].contains(code)
        default:
            return false
        }
    }

    public var isAmbiguousTransactionWrite: Bool {
        switch self {
        case .invalidResponse, .ledgerMismatch, .httpStatus:
            return true
        case .server(_, 408), .server(_, 425), .server(_, 429), .server(_, 500...599), .server(200_019, _):
            return true
        default:
            return false
        }
    }
}

extension APIClient {
    public func ledgerRole(token: String, ledgerID: String) async throws -> LedgerRole {
        try validateLedgerID(ledgerID)
        if ledgerID == "0" { return .owner }
        let members: [LedgerRoleResponse] = try await request("api/v1/ledger/member/list.json", token: token,
                                                               query: ["ledgerId": ledgerID])
        guard members.allSatisfy({ $0.ledgerId == ledgerID }) else { throw APIError.ledgerMismatch }
        let current = members.filter { $0.isCurrentUser == true && $0.status == 1 }
        guard current.count == 1, let role = LedgerRole(rawValue: current[0].role) else {
            throw APIError.invalidResponse
        }
        return role
    }

    @discardableResult
    public func requireDurableTransactionWrites(token: String) async throws -> TransactionCreateProtocol {
        let version: TransactionWriteServerVersion = try await request("api/v1/systems/version.json", token: token)
        guard let writeProtocol = version.writeProtocol else { throw APIError.unsupportedTransactionWrites }
        return writeProtocol
    }

    public func transaction(token: String, ledgerID: String, id: String) async throws -> TransactionDetail {
        try validateLedgerID(ledgerID)
        guard TransactionDraft.isPositiveID(id) else { throw APIError.invalidResponse }
        let detail: TransactionDetail = try await request("api/v1/transactions/get.json", token: token, query: [
            "ledgerId": ledgerID, "id": id, "with_pictures": "false",
            "trim_account": "true", "trim_category": "true", "trim_tag": "true"
        ])
        guard detail.record.ledgerId == ledgerID else { throw APIError.ledgerMismatch }
        guard detail.record.id == id else { throw APIError.invalidResponse }
        return detail
    }

    public func addTransaction(token: String, request submission: TransactionCreateRequest) async throws -> TransactionRecord {
        try validateLedgerID(submission.ledgerID)
        try submission.draft.validateWire(ledgerID: submission.ledgerID)
        guard !submission.clientSessionID.isEmpty, submission.clientSessionID.utf8.count <= 128 else {
            throw TransactionDraftError.invalidSubmissionID
        }
        let endpoint: String
        switch submission.writeProtocol {
        case .dedicated:
            endpoint = "api/v1/transactions/add_durable.json"
        case .releaseCompatible:
            try await requireDurableTransactionWrites(token: token)
            endpoint = "api/v1/transactions/add.json"
        }
        let result: TransactionRecord = try await request(endpoint, method: "POST", token: token,
            body: TransactionWritePayload(ledgerId: submission.ledgerID, draft: submission.draft,
                                          clientSessionId: submission.clientSessionID))
        guard result.ledgerId == submission.ledgerID else { throw APIError.ledgerMismatch }
        guard TransactionDraft.isPositiveID(result.id) else { throw APIError.invalidResponse }
        return result
    }

    public func modifyTransaction(token: String, ledgerID: String, detail: TransactionDetail,
                                  draft: TransactionDraft) async throws -> TransactionRecord {
        guard detail.record.ledgerId == ledgerID else { throw APIError.ledgerMismatch }
        guard detail.canDelete else { throw TransactionDraftError.readOnly }
        guard let editToken = detail.editToken else { throw TransactionDraftError.editUpgradeRequired }
        guard let original = TransactionDraft(record: detail.record) else { throw APIError.invalidResponse }
        try draft.validateWire(ledgerID: ledgerID)
        if draft.type == .transfer && (draft.sourceAccountID == "0" || draft.destinationAccountID == "0") {
            throw TransactionDraftError.externalTransferReadOnly
        }
        if draft.type == .balanceAdjustment || original.type == .balanceAdjustment {
            guard original.type == draft.type, original.sourceAccountID == draft.sourceAccountID,
                  original.time == draft.time else { throw TransactionDraftError.balanceAdjustmentUnavailable }
        }
        let result: TransactionRecord = try await request("api/v1/transactions/modify_preserving_metadata.json", method: "POST", token: token,
            body: TransactionWritePayload(ledgerId: ledgerID, draft: draft, id: detail.record.id,
                                          expectedEditToken: editToken))
        guard result.ledgerId == ledgerID else { throw APIError.ledgerMismatch }
        guard result.id == detail.record.id else { throw APIError.invalidResponse }
        return result
    }

    public func deleteTransaction(token: String, ledgerID: String, detail: TransactionDetail) async throws {
        guard detail.record.ledgerId == ledgerID else { throw APIError.ledgerMismatch }
        guard detail.canDelete else { throw TransactionDraftError.readOnly }
        let endpoint: String
        var body = ["ledgerId": ledgerID, "id": detail.record.id]
        if let editToken = detail.editToken {
            endpoint = "api/v1/transactions/delete_if_unchanged.json"
            body["expectedEditToken"] = editToken
        } else {
            let current = try await transaction(token: token, ledgerID: ledgerID, id: detail.record.id)
            guard current.canDelete else { throw TransactionDraftError.readOnly }
            guard current.matchesSnapshot(detail) else { throw APIError.transactionChanged }
            endpoint = "api/v1/transactions/delete.json"
        }
        let result: Bool = try await request(endpoint, method: "POST", token: token, body: body)
        guard result else { throw APIError.invalidResponse }
    }
}

private struct LedgerRoleResponse: Decodable {
    let ledgerId: String
    let role: Int
    let status: Int
    let isCurrentUser: Bool?
}

private struct TransactionWriteServerVersion: Decodable {
    let version: String
    let transactionCreateProtocol: String?

    var writeProtocol: TransactionCreateProtocol? {
        if let transactionCreateProtocol {
            return transactionCreateProtocol == TransactionCreateProtocol.dedicated.rawValue ? .dedicated : nil
        }
        let baseVersion = version.prefix { $0 != "+" }
        let parts = baseVersion.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3, parts.allSatisfy({ !$0.isEmpty && $0.utf8.allSatisfy { (48...57).contains($0) } })
        else { return nil }
        let numbers = parts.compactMap { Int($0) }
        return numbers.count == 3 && !numbers.lexicographicallyPrecedes([1, 9, 4]) ? .releaseCompatible : nil
    }
}

private struct TransactionWritePayload: Encodable {
    let ledgerId: String
    let draft: TransactionDraft
    var id: String?
    var clientSessionId: String?
    var expectedEditToken: String?

    private enum CodingKeys: String, CodingKey {
        case ledgerId, id, clientSessionId, expectedEditToken, type, categoryId, time, utcOffset
        case sourceAccountId, destinationAccountId, sourceAmount, destinationAmount, hideAmount, comment
    }

    func encode(to encoder: Encoder) throws {
        var fields = encoder.container(keyedBy: CodingKeys.self)
        try fields.encode(ledgerId, forKey: .ledgerId)
        try fields.encodeIfPresent(id, forKey: .id)
        try fields.encodeIfPresent(clientSessionId, forKey: .clientSessionId)
        try fields.encodeIfPresent(expectedEditToken, forKey: .expectedEditToken)
        try fields.encode(draft.type, forKey: .type)
        try fields.encode(draft.categoryID, forKey: .categoryId)
        try fields.encode(draft.time, forKey: .time)
        try fields.encode(draft.utcOffset, forKey: .utcOffset)
        try fields.encode(draft.sourceAccountID, forKey: .sourceAccountId)
        try fields.encode(draft.destinationAccountID, forKey: .destinationAccountId)
        try fields.encode(draft.sourceAmount, forKey: .sourceAmount)
        try fields.encode(draft.destinationAmount, forKey: .destinationAmount)
        try fields.encode(draft.hideAmount, forKey: .hideAmount)
        try fields.encode(draft.comment, forKey: .comment)
    }
}
