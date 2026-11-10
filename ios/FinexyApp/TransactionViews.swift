import FinexyCore
import SwiftUI

struct TransactionListView: View {
    @EnvironmentObject private var session: AppSession
    @State private var showLedgers = false
    @State private var showEditor = false
    @State private var confirmRetry = false
    @State private var retryError: String?
    @State private var path: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    Button { showLedgers = true } label: {
                        HStack {
                            Image(systemName: "books.vertical")
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
                    .disabled(session.writeBusy)
                    .accessibilityLabel("切换账本，当前为" + session.currentLedgerName)
                    Text("全部日期 · 已读取 \(session.transactions.count) 笔")
                        .font(.footnote).foregroundStyle(.secondary)
                    if let lastReadAt = session.lastReadAt {
                        Text("读取于 " + lastReadAt.formatted(date: .abbreviated, time: .shortened))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }

                if let notice = session.writeNotice {
                    Section { Label(notice, systemImage: "checkmark.circle") }
                }
                if let storageError = session.pendingStorageError {
                    Section {
                        InlineError(message: storageError)
                        Button("重新读取当前账本") { session.refresh() }.frame(minHeight: 44)
                    }
                } else if session.hasPendingCreation {
                    Section {
                        Label("有一笔新增流水待核对", systemImage: "exclamationmark.circle")
                        Text("服务器可能已收到原请求。核对时会使用原账本和原请求编号。")
                            .font(.footnote).foregroundStyle(.secondary)
                        if let retryError { InlineError(message: retryError) }
                        if session.writeBusy {
                            ProgressView("正在核对原请求…").frame(minHeight: 44)
                        } else if session.canWrite {
                            Button("重试原请求") { confirmRetry = true }
                                .frame(minHeight: 44)
                                .disabled(session.dataBusy)
                        } else if session.ledgerRole != nil {
                            Text("需要恢复此账本的记账权限后才能核对。").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                if let error = session.dataError {
                    Section {
                        InlineError(message: error)
                        Button("重新读取当前账本") { session.refresh() }.frame(minHeight: 44)
                    }
                } else if !session.hasLoaded {
                    Section {
                        if session.dataBusy { ProgressView("正在读取当前账本…").frame(minHeight: 80) }
                        else { Button("读取当前账本") { session.refresh() }.frame(minHeight: 44) }
                    }
                }
                if session.hasLoaded && session.ledgerRole == nil {
                    Section {
                        InlineError(message: "暂时无法核验此账本的记账权限。")
                        Button("重新核验权限") { session.refresh() }.frame(minHeight: 44)
                    }
                }
                if session.hasLoaded && session.transactions.isEmpty {
                    Section {
                        ContentUnavailableView {
                            Label("当前账本暂无流水", systemImage: "tray")
                        } description: {
                            Text(session.canWrite ? "在此账本记录第一笔流水。" : "有记账权限的成员可以添加流水。")
                        } actions: {
                            if allowsCreation { Button("新增流水") { showEditor = true }.frame(minHeight: 44) }
                        }
                    }
                } else if session.hasLoaded {
                    Section("流水") {
                        ForEach(session.transactions) { transaction in
                            NavigationLink(value: transaction.id) {
                                TransactionRow(transaction: transaction)
                            }
                        }
                    }
                    Section {
                        if session.dataBusy { ProgressView("正在读取更多流水…").frame(minHeight: 44) }
                        else if session.nextCursor != nil {
                            Button("加载更多流水") { session.loadMore() }.frame(minHeight: 44)
                        } else {
                            Text("已读完当前账本流水")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("流水")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { session.showAmounts.toggle() } label: {
                        Image(systemName: session.showAmounts ? "eye" : "eye.slash")
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel(session.showAmounts ? "隐藏金额与备注" : "显示金额与备注")
                    if session.canWrite {
                        Button { showEditor = true } label: {
                            Image(systemName: "plus").frame(minWidth: 44, minHeight: 44)
                        }
                        .accessibilityLabel("新增流水")
                        .accessibilityIdentifier("transaction.add")
                        .disabled(!allowsCreation)
                    }
                }
            }
            .refreshable { await session.refresh()?.value }
            .navigationDestination(for: String.self) { id in
                if let transaction = session.transactions.first(where: { $0.id == id }) {
                    TransactionDetailView(transaction: transaction)
                } else {
                    ContentUnavailableView("流水待重新核验", systemImage: "arrow.clockwise", description: Text("请返回当前账本，重新读取流水。"))
                }
            }
            .sheet(isPresented: $showLedgers) { LedgerPickerView() }
            .sheet(isPresented: $showEditor) { TransactionEditorView(contextID: session.writeContextID) }
            .confirmationDialog("核对此账本的原新增请求？", isPresented: $confirmRetry, titleVisibility: .visible) {
                Button("重试原请求") {
                    let contextID = session.writeContextID
                    Task {
                        retryError = nil
                        do { try await session.retryPendingCreation(contextID: contextID) }
                        catch is CancellationError { return }
                        catch { retryError = session.message(for: error) }
                    }
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("将在「\(session.currentLedgerName)」使用已保存的原请求。")
            }
            .onChange(of: session.currentLedgerID) { _, _ in
                path = []
                showEditor = false
                confirmRetry = false
                retryError = nil
            }
            .onChange(of: session.hasLoaded) { _, loaded in if !loaded { path = [] } }
        }
    }

    private var allowsCreation: Bool {
        session.canWrite && !session.dataBusy && !session.writeBusy &&
            !session.hasPendingCreation && session.pendingStorageError == nil
    }
}

struct LedgerPickerView: View {
    @EnvironmentObject private var session: AppSession
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""

    private var filtered: [Ledger] {
        search.isEmpty ? session.ledgers : session.ledgers.filter { $0.name.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(filtered) { ledger in
                    Button {
                        session.chooseLedger(ledger.id)
                        dismiss()
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "book.closed").foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(ledger.name).foregroundStyle(.primary)
                                if !ledger.comment.isEmpty {
                                    Text(ledger.comment).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            if ledger.id == session.currentLedgerID {
                                Image(systemName: "checkmark").accessibilityHidden(true)
                            }
                        }
                        .frame(minHeight: 44)
                    }
                    .disabled(session.writeBusy)
                    .accessibilityLabel(ledger.name + (ledger.id == session.currentLedgerID ? "，正在使用" : "，切换到此账本"))
                }
                if filtered.isEmpty {
                    ContentUnavailableView.search(text: search)
                    Button("清除搜索") { search = "" }.frame(minHeight: 44)
                }
                Section {
                    Text("本版支持切换已有账本。创建账本、邀请和成员管理请使用 Web 或 Android。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("切换账本")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search, prompt: "搜索账本")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }.frame(minHeight: 44)
                }
            }
        }
        .modifier(PrivacyShield())
    }
}

private struct TransactionRow: View {
    @EnvironmentObject private var session: AppSession
    let transaction: TransactionRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(TransactionPresentation.typeName(transaction), systemImage: TransactionPresentation.symbol(transaction))
                .font(.subheadline.weight(.semibold))
            Text(TransactionPresentation.categoryName(transaction, session: session))
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
            Text(TransactionPresentation.amounts(transaction, session: session))
                .font(.body.weight(.medium)).monospacedDigit()
                .fixedSize(horizontal: false, vertical: true)
            Text(TransactionPresentation.accounts(transaction, session: session))
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Text(Date(timeIntervalSince1970: TimeInterval(transaction.time)), format: .dateTime.year().month().day().hour().minute())
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
    }
}

struct TransactionDetailView: View {
    @EnvironmentObject private var session: AppSession
    @Environment(\.dismiss) private var dismiss
    @State private var detail: TransactionDetail?
    @State private var isLoadingActions = false
    @State private var error: String?
    @State private var resultUnknown = false
    @State private var showEditor = false
    @State private var confirmDisclosure = false
    @State private var confirmDelete = false

    let transaction: TransactionRecord

    private var displayedTransaction: TransactionRecord { detail?.record ?? transaction }
    private var privateContent: Bool { displayedTransaction.hideAmount || !session.showAmounts }

    var body: some View {
        let transaction = displayedTransaction
        List {
            Section {
                Label(TransactionPresentation.typeName(transaction), systemImage: TransactionPresentation.symbol(transaction))
                    .font(.headline)
                Text(TransactionPresentation.amounts(transaction, session: session))
                    .font(.title2.weight(.semibold)).monospacedDigit()
                    .fixedSize(horizontal: false, vertical: true)
            }
            Section("流水详情") {
                LabeledContent("账本", value: session.currentLedgerName)
                LabeledContent("分类", value: TransactionPresentation.categoryName(transaction, session: session))
                Text(TransactionPresentation.accounts(transaction, session: session))
                    .textSelection(.enabled)
                LabeledContent("交易日期") {
                    Text(Date(timeIntervalSince1970: TimeInterval(transaction.time)), format: .dateTime.year().month().day().hour().minute().second())
                }
                Text("日期按设备时区 \(TimeZone.current.identifier) 显示。")
                    .font(.footnote).foregroundStyle(.secondary)
                LabeledContent("备注") {
                    Text(privateContent ? "已隐藏" : (transaction.comment.isEmpty ? "无备注" : transaction.comment))
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
                }
            }
            Section {
                if transaction.hideAmount { Text("此流水在服务器设置为隐藏金额。") }
            }
            .font(.footnote).foregroundStyle(.secondary)
            if session.canWrite && transaction.editable == true {
                Section("操作") { writeActions }
            }
        }
        .navigationTitle("流水详情")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: transaction.id) { await loadActions() }
        .sheet(isPresented: $showEditor) {
            if let detail {
                TransactionEditorView(contextID: session.writeContextID, detail: detail)
            }
        }
        .alert("编辑时显示隐藏金额和备注？", isPresented: $confirmDisclosure) {
            Button("显示并编辑") { showEditor = true }
            Button("取消", role: .cancel) {}
        } message: {
            Text("原始内容会在编辑表单中显示。")
        }
        .alert("删除这笔流水？", isPresented: $confirmDelete) {
            Button("删除流水", role: .destructive) {
                guard let detail else { return }
                let contextID = session.writeContextID
                Task { await delete(detail, contextID: contextID) }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("将从「\(session.currentLedgerName)」删除这笔流水，相关账户余额会重新计算。")
        }
    }

    @ViewBuilder
    private var writeActions: some View {
        if let error { InlineError(message: error) }
        if resultUnknown {
            Button("返回并重新读取流水") {
                dismiss()
                session.refresh()
            }
            .frame(minHeight: 44)
        } else if isLoadingActions {
            ProgressView("正在核对流水操作权限…").frame(minHeight: 44)
        } else if let detail {
            if detail.canEdit, TransactionDraft(record: detail.record) != nil {
                Button("编辑流水") {
                    if privateContent { confirmDisclosure = true }
                    else { showEditor = true }
                }
                .frame(minHeight: 44)
                .accessibilityIdentifier("transaction.edit")
                .disabled(session.writeBusy || session.dataBusy)
            } else if detail.editable {
                Text("编辑需要支持安全修改的新版 Finexy 服务端。").font(.footnote).foregroundStyle(.secondary)
            }
            if detail.canDelete {
                Button("删除流水", role: .destructive) { confirmDelete = true }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("transaction.delete")
                    .disabled(session.writeBusy || session.dataBusy)
            }
            if !detail.editable {
                Text("服务器当前未授予对此流水的写入权限。").font(.footnote).foregroundStyle(.secondary)
            }
        } else if isExternalTransfer {
            Text("当前版本可以新增和查看外部转账。").font(.footnote).foregroundStyle(.secondary)
        } else if error != nil {
            Button("重新核对操作权限") { Task { await loadActions() } }.frame(minHeight: 44)
        }
    }

    private var isExternalTransfer: Bool {
        transaction.type == 4 &&
            (transaction.sourceAccountId == "0" || transaction.destinationAccountId == nil || transaction.destinationAccountId == "0")
    }

    private func loadActions() async {
        guard session.canWrite, transaction.editable == true, !isExternalTransfer, !isLoadingActions else { return }
        let contextID = session.writeContextID
        isLoadingActions = true
        error = nil
        defer { isLoadingActions = false }
        do {
            detail = try await session.transactionDetail(id: transaction.id, contextID: contextID)
        } catch is CancellationError {
            return
        } catch {
            self.error = session.message(for: error)
        }
    }

    private func delete(_ detail: TransactionDetail, contextID: UUID) async {
        error = nil
        do {
            try await session.deleteTransaction(detail, contextID: contextID)
            dismiss()
        } catch is CancellationError {
            return
        } catch {
            if error is URLError || (error as? APIError)?.isAmbiguousTransactionWrite == true {
                resultUnknown = true
                self.error = "未能确认删除结果。请重新读取流水并核对。"
            } else {
                if (error as? APIError)?.isTransactionConflict == true { resultUnknown = true }
                self.error = session.message(for: error)
            }
        }
    }
}

extension APIError {
    var isTransactionConflict: Bool {
        switch self {
        case .transactionChanged, .server(205042, 409): return true
        default: return false
        }
    }
}

@MainActor
private enum TransactionPresentation {
    static func typeName(_ row: TransactionRecord) -> String {
        switch row.type {
        case 1: return "余额调整"
        case 2: return row.sourceAmount < 0 ? "收入冲回" : "收入"
        case 3: return row.sourceAmount < 0 ? "支出退款" : "支出"
        case 4:
            if row.sourceAccountId == "0" { return "外部转入" }
            if row.destinationAccountId == nil || row.destinationAccountId == "0" { return "外部转出" }
            return "账户间转账"
        default: return "未知流水类型"
        }
    }

    static func symbol(_ row: TransactionRecord) -> String {
        switch row.type {
        case 1: return "equal.circle"
        case 2: return "arrow.down.left"
        case 3: return "arrow.up.right"
        case 4: return "arrow.left.arrow.right"
        default: return "questionmark.circle"
        }
    }

    static func categoryName(_ row: TransactionRecord, session: AppSession) -> String {
        if row.type == 1 { return "调整后余额" }
        if row.categoryId == "0" { return "无分类" }
        return session.categories[row.categoryId]?.name ?? "分类待核对"
    }

    static func accounts(_ row: TransactionRecord, session: AppSession) -> String {
        func name(_ id: String?) -> String {
            guard let id, id != "0" else { return "系统外" }
            return session.accounts[id]?.name ?? "账户待核对（ID \(id)）"
        }
        if row.type == 4 {
            return "转出：\(name(row.sourceAccountId))\n转入：\(name(row.destinationAccountId))"
        }
        return (row.type == 2 ? "入账账户：" : "账户：") + name(row.sourceAccountId)
    }

    static func amounts(_ row: TransactionRecord, session: AppSession) -> String {
        guard !row.hideAmount, session.showAmounts else { return "金额已隐藏" }
        func money(_ value: Int64?, accountID: String?, multiplier: Decimal = 1, signed: Bool = false) -> String {
            guard let value, let accountID, let account = session.accounts[accountID], !account.currency.isEmpty else {
                return "金额币种待核对"
            }
            let amount = Decimal(value) * multiplier / Decimal(100)
            let formatter = NumberFormatter()
            formatter.numberStyle = .decimal
            formatter.minimumFractionDigits = 2
            formatter.maximumFractionDigits = 2
            let decimal = NSDecimalNumber(decimal: amount)
            guard let text = formatter.string(from: decimal) else { return "金额暂不可显示" }
            return account.currency + " " + (signed && amount > 0 ? "+" : "") + text
        }
        switch row.type {
        case 1: return "调整后余额 " + money(row.sourceAmount, accountID: row.sourceAccountId)
        case 2: return money(row.sourceAmount, accountID: row.sourceAccountId, signed: true)
        case 3: return money(row.sourceAmount, accountID: row.sourceAccountId, multiplier: -1, signed: true)
        case 4:
            var parts: [String] = []
            if row.sourceAccountId != "0" {
                parts.append("转出 " + money(row.sourceAmount, accountID: row.sourceAccountId))
            }
            if let destinationID = row.destinationAccountId, destinationID != "0" {
                parts.append("转入 " + money(row.destinationAmount, accountID: destinationID))
            }
            return parts.isEmpty ? "账户与币种待核对" : parts.joined(separator: "\n")
        default: return "流水类型待核对"
        }
    }
}
