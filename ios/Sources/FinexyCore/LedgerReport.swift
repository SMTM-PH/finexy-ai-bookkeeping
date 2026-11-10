import Foundation

public struct LedgerReportMonth: Hashable, Sendable {
    public let year: Int
    public let month: Int
    public let timeZone: TimeZone

    public init(date: Date = .now, timeZone: TimeZone = .current) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        year = calendar.component(.year, from: date)
        month = calendar.component(.month, from: date)
        self.timeZone = timeZone
    }

    private init(year: Int, month: Int, timeZone: TimeZone) {
        self.year = year
        self.month = month
        self.timeZone = timeZone
    }

    public func moved(by months: Int) -> Self? {
        let (index, overflow) = (year * 12 + month - 1).addingReportingOverflow(months)
        guard !overflow, (1970 * 12...9998 * 12 + 11).contains(index) else { return nil }
        return Self(year: index / 12, month: index % 12 + 1, timeZone: timeZone)
    }

    public var startDate: String { String(format: "%04d-%02d-01", year, month) }

    public var endDate: String {
        // Determine the civil calendar day count in UTC. The server maps the
        // requested dates to its IANA rules, including skipped local dates.
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        let first = calendar.date(from: DateComponents(year: year, month: month, day: 1))
        let days = first.flatMap { calendar.range(of: .day, in: .month, for: $0)?.count } ?? 0
        return String(format: "%04d-%02d-%02d", year, month, days)
    }
}

private struct ReportIDFilter: Codable, Equatable, Sendable {
    let ids: [String]
    let mode: String
    let includeDescendants: Bool

    init(id: String) {
        ids = [id]
        mode = "include"
        includeDescendants = false
    }
}

private struct ReportQuery: Codable, Equatable, Sendable {
    private struct TagFilter: Codable, Equatable, Sendable {
        let ids: [String]?
        let mode: String
    }

    let ledgerId: String
    let startDate: String
    let endDate: String
    let timezone: String
    let types: [Int]
    let accounts: ReportIDFilter?
    let categories: ReportIDFilter?
    let hidden: String
    private let tags: [TagFilter]?
    private let currencies: [String]?

    init(ledgerID: String, month: LedgerReportMonth, accountID: String?, categoryID: String?, types: [Int]) {
        ledgerId = ledgerID
        startDate = month.startDate
        endDate = month.endDate
        timezone = month.timeZone.identifier
        self.types = types
        accounts = accountID.map(ReportIDFilter.init)
        categories = categoryID.map(ReportIDFilter.init)
        hidden = "exclude"
        tags = nil
        currencies = nil
    }
}

private struct ReportRequest: Encodable {
    let query: ReportQuery
    let offset: Int
    let count: Int
    let expectedSnapshotHash: String?
}

// Report amounts use decimal strings on the wire, unlike the transaction list.
private struct ReportAmount: Decodable, Sendable {
    let units: Int64

    init(from decoder: Decoder) throws {
        let text = try decoder.singleValueContainer().decode(String.self)
        guard let units = Int64(text), String(units) == text else { throw APIError.invalidResponse }
        self.units = units
    }
}

public struct LedgerReportMoney: Decodable, Identifiable, Sendable {
    public let currency: String
    private let income: ReportAmount
    private let expense: ReportAmount
    private let net: ReportAmount

    public var id: String { currency }
    public var incomeMinor: Int64 { income.units }
    public var expenseMinor: Int64 { expense.units }
    public var netMinor: Int64 { net.units }

    fileprivate var isValid: Bool {
        let (expected, overflow) = incomeMinor.subtractingReportingOverflow(expenseMinor)
        return !overflow && expected == netMinor && isReportCurrency(currency)
    }
}

public struct LedgerReportCategory: Decodable, Identifiable, Sendable {
    public let key: String
    public let name: String?
    public let count: Int
    public let money: [LedgerReportMoney]
    public var id: String { key }
}

public struct LedgerReportLeg: Decodable, Sendable {
    public let accountId: String
    public let accountName: String
    public let currency: String
    private let amount: ReportAmount
    public var amountMinor: Int64 { amount.units }
}

public struct LedgerReportEntry: Decodable, Identifiable, Sendable {
    public let id: String
    public let type: Int
    public let date: String
    public let categoryId: String
    public let categoryName: String
    public let source: LedgerReportLeg?
    public let destination: LedgerReportLeg?
    public let hidden: Bool
    public let comment: String
    private let adjustment: ReportAmount
    private let balanceTarget: ReportAmount?

    public var adjustmentMinor: Int64 { adjustment.units }
    public var balanceTargetMinor: Int64? { balanceTarget?.units }
}

public struct LedgerReportSummary: Decodable, Sendable {
    fileprivate let query: ReportQuery
    fileprivate let dataComplete: Bool
    fileprivate let offset: Int
    fileprivate let returnedCount: Int
    fileprivate let detailsComplete: Bool
    public let snapshotHash: String
    public let matchedCount: Int
    public let details: [LedgerReportEntry]
    public let totals: [LedgerReportMoney]
    public let categories: [LedgerReportCategory]
    public let nextOffset: Int?
}

public extension APIClient {
    func monthlyReport(token: String, ledgerID: String, month: LedgerReportMonth,
                       accountID: String? = nil, categoryID: String? = nil, types: [Int]? = nil,
                       offset: Int = 0, snapshotHash: String? = nil) async throws -> LedgerReportSummary {
        try validateLedgerID(ledgerID)
        let selectedTypes = types.map { Set($0).sorted() } ?? [1, 2, 3, 4]
        guard isReportID(ledgerID, allowZero: true), (1970...9998).contains(month.year),
              accountID.map({ isReportID($0) }) ?? true,
              categoryID.map({ isReportID($0, allowZero: true) }) ?? true,
              !selectedTypes.isEmpty, selectedTypes.allSatisfy({ (1...4).contains($0) }),
              offset >= 0, offset == 0 || snapshotHash != nil,
              snapshotHash.map(isReportHash) ?? true else { throw APIError.invalidResponse }

        let query = ReportQuery(ledgerID: ledgerID, month: month, accountID: accountID, categoryID: categoryID, types: selectedTypes)
        let request = ReportRequest(query: query, offset: offset, count: 50, expectedSnapshotHash: snapshotHash)
        let report: LedgerReportSummary = try await self.request("api/v1/reports/query.json", method: "POST", token: token, body: request)
        guard report.query.ledgerId == ledgerID else { throw APIError.ledgerMismatch }
        guard report.dataComplete, report.query == query, isReportHash(report.snapshotHash),
              snapshotHash.map({ $0 == report.snapshotHash }) ?? true,
              report.offset == offset, report.matchedCount >= offset,
              report.returnedCount == report.details.count,
              report.returnedCount == min(request.count, report.matchedCount - offset) else { throw APIError.invalidResponse }
        let end = offset + report.returnedCount
        guard report.nextOffset == (end < report.matchedCount ? end : nil),
              report.detailsComplete == (offset == 0 && end == report.matchedCount),
              report.totals.isEmpty == (report.matchedCount == 0),
              validReportMoney(report.totals),
              Set(report.categories.map(\.id)).count == report.categories.count,
              Set(report.details.map(\.id)).count == report.details.count else { throw APIError.invalidResponse }
        var unclassifiedCount = report.matchedCount
        for group in report.categories {
            guard isReportID(group.id, allowZero: true), group.count > 0, group.count <= unclassifiedCount,
                  !group.money.isEmpty, validReportMoney(group.money),
                  categoryID.map({ $0 == group.id }) ?? true else { throw APIError.invalidResponse }
            unclassifiedCount -= group.count
        }
        guard unclassifiedCount == 0 else { throw APIError.invalidResponse }
        for row in report.details {
            let legs = [row.source, row.destination].compactMap { $0 }
            guard !row.hidden, selectedTypes.contains(row.type), isReportID(row.id),
                  row.date.utf8.count == 10, row.date.hasPrefix(query.startDate.dropLast(2)),
                  row.date.utf8.suffix(2).allSatisfy({ (48...57).contains($0) }),
                  row.date >= query.startDate, row.date <= query.endDate,
                  isReportID(row.categoryId, allowZero: true),
                  categoryID.map({ $0 == row.categoryId }) ?? true,
                  accountID.map({ id in legs.contains { $0.accountId == id } }) ?? true,
                  !legs.isEmpty, legs.allSatisfy({ isReportID($0.accountId) && isReportCurrency($0.currency) }),
                  row.type == 4 || (row.source != nil && row.destination == nil),
                  (row.type == 1) == (row.balanceTargetMinor != nil)
            else { throw APIError.invalidResponse }
        }
        return report
    }
}

private func validReportMoney(_ money: [LedgerReportMoney]) -> Bool {
    Set(money.map(\.currency)).count == money.count && money.allSatisfy(\.isValid)
}

private func isReportID(_ value: String, allowZero: Bool = false) -> Bool {
    guard let id = Int64(value) else { return false }
    return id >= (allowZero ? 0 : 1) && String(id) == value
}

private func isReportCurrency(_ value: String) -> Bool {
    value.utf8.count == 3 && value.utf8.allSatisfy { (65...90).contains($0) }
}

private func isReportHash(_ value: String) -> Bool {
    value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
}
