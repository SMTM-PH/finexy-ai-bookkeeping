import Foundation

public struct UserProfile: Codable, Equatable, Sendable {
    public let username: String
    public let nickname: String
    public let defaultCurrency: String
    public var displayName: String { nickname.isEmpty ? username : nickname }
}

public struct AuthResponse: Codable, Sendable {
    public let token: String
    public let need2FA: Bool
    public let user: UserProfile?
}

public struct Ledger: Codable, Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let comment: String

    public init(id: String, name: String, comment: String = "") {
        self.id = id
        self.name = name
        self.comment = comment
    }

    public static let defaultPersonal = Ledger(id: "0", name: "默认个人账本")
}

public struct Account: Codable, Identifiable, Sendable {
    public let id: String
    public let ledgerId: String
    public let name: String
    public let parentId: String
    public let currency: String
    public let balance: Int64
    public let hidden: Bool
    public let subAccounts: [Account]?

    public var flattened: [Account] { [self] + (subAccounts ?? []).flatMap(\.flattened) }
}

public struct TransactionCategory: Codable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let parentId: String
    public let type: Int
    public let hidden: Bool
    public let subCategories: [TransactionCategory]?

    public var flattened: [TransactionCategory] { [self] + (subCategories ?? []).flatMap(\.flattened) }
}

/// IDs stay as strings; API money is Int64 hundredths for every currency.
public struct TransactionRecord: Codable, Identifiable, Sendable {
    public let id: String
    public let ledgerId: String
    public let timeSequenceId: String
    /// 1: balance adjustment; 2: income; 3: expense; 4: transfer.
    public let type: Int
    public let time: Int64
    public let utcOffset: Int
    public let categoryId: String
    public let category: TransactionCategory?
    public let sourceAccountId: String
    public let sourceAccount: Account?
    public let destinationAccountId: String?
    public let destinationAccount: Account?
    public let sourceAmount: Int64
    public let destinationAmount: Int64?
    public let hideAmount: Bool
    public let comment: String
}

public struct TransactionPage: Codable, Sendable {
    public let items: [TransactionRecord]
    public let nextTimeSequenceId: String?
    public let totalCount: Int64?

    private enum CodingKeys: String, CodingKey { case items, nextTimeSequenceId, totalCount }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = try container.decode([TransactionRecord].self, forKey: .items)
        let cursor = try container.decodeIfPresent(String.self, forKey: .nextTimeSequenceId)
        nextTimeSequenceId = cursor == "0" ? nil : cursor
        totalCount = try container.decodeIfPresent(Int64.self, forKey: .totalCount)
    }
}

public enum MoneyFormatter {
    /// Do not use ISO currency fraction digits: the Finexy wire contract is /100,
    /// including JPY. Decimal also preserves Int64 values larger than 2^53.
    public static func format(minorUnits: Int64, currency: String, locale: Locale = .current) -> String {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 2
        formatter.maximumFractionDigits = 2
        let amount = NSDecimalNumber(decimal: Decimal(minorUnits) / Decimal(100))
        return currency + " " + (formatter.string(from: amount) ?? amount.stringValue)
    }
}
