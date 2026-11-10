import FinexyCore
import SwiftUI

struct OverviewView: View {
    @EnvironmentObject private var session: AppSession
    @State private var choosingLedger = false
    @State private var month = LedgerReportMonth()

    var body: some View {
        NavigationStack {
            List {
                Section { LedgerContextButton { choosingLedger = true } }
                if session.hasLoaded {
                    Section("账户当前余额") {
                        AccountBalances(accounts: browsableAccounts(session.accounts).filter { !isHidden($0, in: session.accounts) })
                    }
                }
                Section("月份") { ReportMonthControl(month: $month) }
                MonthlyReportSections(month: month)
            }
            .navigationTitle("总览")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { AmountVisibilityButton() } }
            .sheet(isPresented: $choosingLedger) { LedgerPickerView() }
            .refreshable { await session.refresh()?.value }
        }
    }
}

struct AccountsView: View {
    @EnvironmentObject private var session: AppSession
    @State private var choosingLedger = false
    @State private var search = ""
    @State private var path: [String] = []

    private var accounts: [Account] { browsableAccounts(session.accounts) }

    private var filteredAccounts: [Account] {
        guard !search.isEmpty else { return accounts }
        return accounts.filter { account in
            account.name.localizedCaseInsensitiveContains(search) ||
            account.currency.localizedCaseInsensitiveContains(search) ||
            session.accounts[account.parentId]?.name.localizedCaseInsensitiveContains(search) == true
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section { LedgerContextButton { choosingLedger = true } }
                if let error = session.dataError {
                    Section {
                        InlineError(message: error)
                        Button("重新读取当前账本") { session.refresh() }.frame(minHeight: 44)
                    }
                } else if !session.hasLoaded {
                    Section { ProgressView("正在读取当前账本…").frame(minHeight: 80) }
                } else if accounts.isEmpty {
                    Section {
                        ContentUnavailableView("暂无账户", systemImage: "wallet.bifold", description: Text("在 Web 或 Android 添加账户后下拉刷新。"))
                    }
                } else if filteredAccounts.isEmpty {
                    Section { ContentUnavailableView.search(text: search) }
                } else {
                    accountSection("可用账户", accounts: filteredAccounts.filter { !isHidden($0, in: session.accounts) })
                    accountSection("已隐藏账户", accounts: filteredAccounts.filter { isHidden($0, in: session.accounts) })
                }
            }
            .navigationTitle("账户")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { AmountVisibilityButton() } }
            .searchable(text: $search, prompt: "搜索账户名称或币种")
            .sheet(isPresented: $choosingLedger) { LedgerPickerView() }
            .refreshable { await session.refresh()?.value }
            .navigationDestination(for: String.self) { accountID in
                AccountDetailView(accountID: accountID)
            }
            .onChange(of: session.currentLedgerID) { _, _ in
                search = ""
                path = []
            }
        }
    }

    @ViewBuilder
    private func accountSection(_ title: String, accounts: [Account]) -> some View {
        if !accounts.isEmpty {
            Section(title) {
                ForEach(accounts) { account in
                    NavigationLink(value: account.id) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(account.name).font(.headline)
                            if let parent = session.accounts[account.parentId] {
                                Text(parent.name).font(.caption).foregroundStyle(.secondary)
                            }
                            Text(accountBalance(account, visible: session.showAmounts))
                                .monospacedDigit().foregroundStyle(.secondary)
                        }
                        .frame(minHeight: 44)
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
    }
}

private struct AccountDetailView: View {
    @EnvironmentObject private var session: AppSession
    @State private var month = LedgerReportMonth()
    let accountID: String

    var body: some View {
        List {
            if let account = session.accounts[accountID], session.hasLoaded {
                Section("账户资料") {
                    LabeledContent("账本", value: session.currentLedgerName)
                    LabeledContent("账户", value: account.name)
                    LabeledContent("状态", value: isHidden(account, in: session.accounts) ? "已隐藏" : "可用")
                    LabeledContent("币种", value: account.currency.isEmpty ? "待核对" : account.currency)
                    LabeledContent("当前余额", value: accountBalance(account, visible: session.showAmounts))
                        .monospacedDigit()
                }
                Section("流水月份") { ReportMonthControl(month: $month) }
                MonthlyReportSections(month: month, accountID: accountID)
            } else if let error = session.dataError {
                InlineError(message: error)
                Button("重新读取当前账本") { session.refresh() }.frame(minHeight: 44)
            } else if session.dataBusy {
                ProgressView("正在读取当前账本…").frame(minHeight: 80)
            } else {
                ContentUnavailableView("账户已不可用", systemImage: "wallet.bifold", description: Text("请返回账户列表。"))
            }
        }
        .navigationTitle("账户详情")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { AmountVisibilityButton() } }
        .refreshable { await session.refresh()?.value }
    }
}

private struct LedgerContextButton: View {
    @EnvironmentObject private var session: AppSession
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Image(systemName: "books.vertical").accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("当前账本").font(.caption).foregroundStyle(.secondary)
                    Text(session.currentLedgerName).font(.headline).foregroundStyle(.primary)
                }
                Spacer()
                Image(systemName: "chevron.down").accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("切换账本，当前为" + session.currentLedgerName)
    }
}

private struct AmountVisibilityButton: View {
    @EnvironmentObject private var session: AppSession

    var body: some View {
        Button { session.showAmounts.toggle() } label: {
            Image(systemName: session.showAmounts ? "eye" : "eye.slash")
                .frame(minWidth: 44, minHeight: 44)
        }
        .accessibilityLabel(session.showAmounts ? "隐藏金额与备注" : "显示金额与备注")
    }
}

private struct ReportMonthControl: View {
    @Binding var month: LedgerReportMonth

    var body: some View {
        HStack {
            monthButton(-1, label: "上一个月", icon: "chevron.left")
            Spacer(minLength: 4)
            Text("\(month.year.formatted(.number.grouping(.never))) 年 \(month.month) 月")
                .font(.headline).multilineTextAlignment(.center)
            Spacer(minLength: 4)
            monthButton(1, label: "下一个月", icon: "chevron.right")
        }
        .buttonStyle(.borderless)
    }

    private func monthButton(_ delta: Int, label: String, icon: String) -> some View {
        let next = month.moved(by: delta)
        return Button {
            if let next { month = next }
        } label: {
            Image(systemName: icon).frame(minWidth: 44, minHeight: 44)
        }
        .disabled(next == nil)
        .accessibilityLabel(label)
    }
}

private struct MonthlyReportSections: View {
    @EnvironmentObject private var session: AppSession
    @State private var state: ReportReadState?

    let month: LedgerReportMonth
    var accountID: String?
    var categoryID: String?

    private struct Scope: Equatable {
        let ledgerID: String
        let generation: UUID
        let month: LedgerReportMonth
        let accountID: String?
        let categoryID: String?
        let isReady: Bool
    }

    private struct ReportReadState {
        let scope: Scope
        let requestID = UUID()
        var summary: LedgerReportSummary?
        var rows: [LedgerReportEntry] = []
        var nextOffset: Int?
        var error: String?
        var busy = true
    }

    private var scope: Scope {
        Scope(ledgerID: session.currentLedgerID, generation: session.financialReadGeneration,
              month: month, accountID: accountID, categoryID: categoryID, isReady: session.hasLoaded)
    }

    private var current: ReportReadState? { state?.scope == scope ? state : nil }

    var body: some View {
        Group {
            Section {
                if let error = session.dataError, !scope.isReady {
                    InlineError(message: error)
                    Button("重新读取当前账本") { session.refresh() }.frame(minHeight: 44)
                } else if let summary = current?.summary {
                    if summary.matchedCount == 0 {
                        ContentUnavailableView("本月暂无可见流水", systemImage: "chart.bar")
                    } else if !session.showAmounts {
                        Text("金额已隐藏").foregroundStyle(.secondary)
                    } else {
                        ForEach(summary.totals) { money in ReportMoneyValues(money: money) }
                    }
                } else if let error = current?.error {
                    InlineError(message: error)
                    Button("重新读取月报") { Task { await read() } }.frame(minHeight: 44)
                } else {
                    ProgressView("正在读取月报…").frame(minHeight: 64)
                }
            } header: {
                Text("月度收支")
            } footer: {
                Text("\(month.timeZone.identifier) · 按原币种汇总 · 排除隐藏流水。退款冲减支出，转账和余额调整不计入收支。")
            }
            .task(id: scope) {
                state = nil
                if scope.isReady { await read() }
            }

            if let current, let summary = current.summary, summary.matchedCount > 0 {
                if accountID == nil, categoryID == nil, !summary.categories.isEmpty {
                    Section("按分类") {
                        ForEach(summary.categories) { category in
                            NavigationLink {
                                List {
                                    Section { LabeledContent("当前账本", value: session.currentLedgerName) }
                                    MonthlyReportSections(month: month, categoryID: category.id)
                                }
                                .navigationTitle(categoryTitle(category))
                                .navigationBarTitleDisplayMode(.inline)
                                .toolbar { ToolbarItem(placement: .topBarTrailing) { AmountVisibilityButton() } }
                            } label: {
                                LabeledContent(categoryTitle(category), value: "\(category.count) 笔")
                                    .frame(minHeight: 44)
                            }
                        }
                    }
                }
                Section("可见流水 · \(summary.matchedCount) 笔") {
                    ForEach(current.rows) { entry in ReportEntryRow(entry: entry) }
                    if current.busy {
                        ProgressView("正在读取更多流水…").frame(minHeight: 44)
                    } else if let next = current.nextOffset {
                        Button("加载更多流水") { Task { await read(offset: next) } }.frame(minHeight: 44)
                    }
                }
            }
        }
    }

    private func read(offset: Int = 0) async {
        let captured = scope
        guard captured.isReady else { return }
        let previous = current
        var pending = ReportReadState(scope: captured)
        if offset > 0 {
            guard let previous, !previous.busy, previous.nextOffset == offset else { return }
            pending.summary = previous.summary
            pending.rows = previous.rows
        }
        state = pending
        do {
            let result = try await session.monthlyReport(month: captured.month, accountID: captured.accountID, categoryID: captured.categoryID,
                                                         offset: offset, snapshotHash: pending.summary?.snapshotHash)
            guard !Task.isCancelled, scope == captured, state?.requestID == pending.requestID else { return }
            let existing = Set(pending.rows.map(\.id))
            guard !result.details.contains(where: { existing.contains($0.id) }) else { throw APIError.invalidResponse }
            pending.summary = result
            pending.rows += result.details
            pending.nextOffset = result.nextOffset
        } catch {
            guard !Task.isCancelled, scope == captured, state?.requestID == pending.requestID else { return }
            pending.summary = nil
            pending.rows = []
            pending.error = reportError(error)
        }
        pending.busy = false
        state = pending
    }

    private func reportError(_ error: Error) -> String {
        switch error as? APIError {
        case .httpStatus(404), .server(_, 404): return "服务器不支持月报接口，请核对服务器版本。"
        case .httpStatus(409), .server(_, 409): return "报告数据已变化，请重新读取月报。"
        case let apiError?: return apiError.localizedDescription
        case nil: return "未能读取月报，请检查网络后重试。"
        }
    }

    private func categoryTitle(_ category: LedgerReportCategory) -> String {
        if category.id == "0" { return "无分类" }
        let name = category.name ?? "分类待核对"
        guard let row = session.categories[category.id], let parent = session.categories[row.parentId] else { return name }
        return "\(parent.name) / \(name)"
    }
}

private struct ReportMoneyValues: View {
    let money: LedgerReportMoney

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(money.currency).font(.headline)
            value("收入", money.incomeMinor)
            value("净支出", money.expenseMinor)
            value("结余", money.netMinor)
        }
        .padding(.vertical, 4)
    }

    private func value(_ label: String, _ units: Int64) -> some View {
        ViewThatFits(in: .horizontal) {
            LabeledContent(label, value: MoneyFormatter.format(minorUnits: units, currency: money.currency))
                .fixedSize(horizontal: true, vertical: false)
            VStack(alignment: .leading) {
                Text(label).foregroundStyle(.secondary)
                Text(MoneyFormatter.format(minorUnits: units, currency: money.currency))
            }
            .accessibilityElement(children: .combine)
        }
        .monospacedDigit()
    }
}

private struct ReportEntryRow: View {
    @EnvironmentObject private var session: AppSession
    let entry: LedgerReportEntry

    private var typeName: String {
        switch entry.type {
        case 1: return "余额调整"
        case 2: return (entry.source?.amountMinor ?? 0) < 0 ? "收入冲回" : "收入"
        case 3: return (entry.source?.amountMinor ?? 0) < 0 ? "支出退款" : "支出"
        default:
            if entry.source == nil { return "外部转入" }
            if entry.destination == nil { return "外部转出" }
            return "账户间转账"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(typeName).font(.headline)
            Text(entry.categoryName.isEmpty ? entry.date : "\(entry.date) · \(entry.categoryName)")
                .font(.subheadline).foregroundStyle(.secondary)
            if entry.type == 4 {
                if let source = entry.source { leg(source, label: "转出") }
                if let destination = entry.destination { leg(destination, label: "转入") }
            } else if let source = entry.source {
                Text(source.accountName).foregroundStyle(.secondary)
                if session.showAmounts, !entry.hidden {
                    if entry.type == 1 {
                        Text("实际差额 " + MoneyFormatter.format(minorUnits: entry.adjustmentMinor, currency: source.currency))
                            .monospacedDigit()
                        if let target = entry.balanceTargetMinor {
                            Text("调整后余额 " + MoneyFormatter.format(minorUnits: target, currency: source.currency))
                                .monospacedDigit()
                        }
                    } else {
                        Text(MoneyFormatter.format(minorUnits: source.amountMinor, currency: source.currency)).monospacedDigit()
                    }
                }
            }
            if session.showAmounts, !entry.hidden, !entry.comment.isEmpty { Text(entry.comment) }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private func leg(_ leg: LedgerReportLeg, label: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(label) · \(leg.accountName)").foregroundStyle(.secondary)
            if session.showAmounts, !entry.hidden {
                Text(MoneyFormatter.format(minorUnits: leg.amountMinor, currency: leg.currency)).monospacedDigit()
            }
        }
    }
}

private struct AccountBalances: View {
    @EnvironmentObject private var session: AppSession
    let accounts: [Account]

    var body: some View {
        if accounts.isEmpty {
            Text("暂无可用账户").foregroundStyle(.secondary)
        } else if !session.showAmounts {
            Text("余额已隐藏").foregroundStyle(.secondary)
        } else {
            let currencies = Dictionary(grouping: accounts, by: \.currency)
            ForEach(currencies.keys.sorted(), id: \.self) { currency in
                let units = currencies[currency, default: []].reduce(Decimal.zero) { $0 + Decimal($1.balance) }
                if currency.isEmpty {
                    Text("部分账户币种待核对").foregroundStyle(.secondary)
                } else if units >= Decimal(Int64.min), units <= Decimal(Int64.max) {
                    Text(MoneyFormatter.format(minorUnits: NSDecimalNumber(decimal: units).int64Value, currency: currency))
                        .monospacedDigit()
                } else {
                    Text("\(currency) 合计超出范围").foregroundStyle(.secondary)
                }
            }
            Text("可见末级账户的当前余额，按币种分别汇总。")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }
}

private func browsableAccounts(_ accounts: [String: Account]) -> [Account] {
    accounts.values.filter { $0.type != 2 && ($0.subAccounts?.isEmpty ?? true) }.sorted {
        let comparison = $0.name.localizedStandardCompare($1.name)
        return comparison == .orderedSame ? $0.id < $1.id : comparison == .orderedAscending
    }
}

private func isHidden(_ account: Account, in accounts: [String: Account]) -> Bool {
    account.hidden || accounts[account.parentId]?.hidden == true
}

private func accountBalance(_ account: Account, visible: Bool) -> String {
    guard visible else { return "余额已隐藏" }
    guard !account.currency.isEmpty else { return "币种待核对" }
    return MoneyFormatter.format(minorUnits: account.balance, currency: account.currency)
}
