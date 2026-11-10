import FinexyCore
import SwiftUI

struct TransactionEditorView: View {
    @EnvironmentObject private var session: AppSession
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var fields: Fields
    @State private var initial: Fields
    @State private var error: String?
    @State private var confirmClose = false
    @State private var needsVerification = false
    @FocusState private var editingText: Bool

    let contextID: UUID
    let detail: TransactionDetail?

    init(contextID: UUID, detail: TransactionDetail? = nil) {
        self.contextID = contextID
        self.detail = detail
        let values = Fields(draft: detail.flatMap { TransactionDraft(record: $0.record) })
        _initial = State(initialValue: values)
        _fields = State(initialValue: values)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("当前账本", value: session.currentLedgerName)
                    if detail == nil {
                        Picker("流水类型", selection: $fields.kind) {
                            Text("支出").tag(TransactionKind.expense)
                            Text("收入").tag(TransactionKind.income)
                            Text("转账").tag(TransactionKind.transfer)
                            if session.currentLedgerID == "0" {
                                Text("期初余额").tag(TransactionKind.balanceAdjustment)
                            }
                        }
                    } else {
                        LabeledContent("流水类型", value: fields.kind.title)
                    }
                    if fields.kind == .transfer {
                        if detail == nil {
                            Picker("转账方向", selection: $fields.direction) {
                                ForEach(Direction.allCases) { Text($0.title).tag($0) }
                            }
                        } else {
                            LabeledContent("转账方向", value: fields.direction.title)
                        }
                    }
                }
                Section {
                    if fields.usesSource {
                        if detail != nil && fields.kind == .balanceAdjustment {
                            LabeledContent("账户", value: session.accounts[fields.sourceID]?.name ?? "原账户当前不可用")
                        } else {
                            accountPicker(fields.sourceLabel, selection: $fields.sourceID)
                        }
                    }
                    if fields.usesDestination {
                        accountPicker("目标账户", selection: $fields.destinationID)
                    }
                    if fields.kind != .balanceAdjustment {
                        Picker("分类", selection: $fields.categoryID) {
                            Text("请选择").tag("")
                            if !fields.categoryID.isEmpty, !categoryOptions.contains(where: { $0.id == fields.categoryID }) {
                                Text("原分类当前不可选").tag(fields.categoryID)
                            }
                            ForEach(categoryOptions) { category in
                                Text(categoryLabel(category)).tag(category.id)
                            }
                        }
                    }
                } footer: {
                    if accountOptions.isEmpty {
                        Text("请先在 Web 端创建或启用此账本的账户。")
                    } else if fields.kind != .balanceAdjustment && categoryOptions.isEmpty {
                        Text("请先在 Web 端为此类型创建可用的末级分类。")
                    }
                }
                Section {
                    amountField(fields.amountLabel, currency: primaryCurrency, text: $fields.amount,
                                signed: fields.kind == .balanceAdjustment)
                    if usesSecondAmount {
                        amountField("转入金额", currency: destinationCurrency, text: $fields.secondAmount, signed: false)
                    }
                    if fields.kind == .expense || fields.kind == .income {
                        Toggle(fields.kind == .expense ? "这是一笔退款" : "这是一笔收入冲回", isOn: $fields.reversal)
                    }
                } footer: {
                    if fields.kind == .balanceAdjustment {
                        Text("输入账户的期初余额。服务器会检查账户是否已有流水。")
                    } else if fields.kind == .transfer, fields.direction == .between, sameCurrency {
                        Text("两个账户币种相同，转入和转出使用相同金额。")
                    }
                }
                Section {
                    if detail != nil && fields.kind == .balanceAdjustment {
                        LabeledContent("交易时间") { Text(fields.date, format: .dateTime.year().month().day().hour().minute()) }
                    } else {
                        DatePicker("交易时间", selection: $fields.date)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("备注")
                        TextField("选填", text: $fields.comment, axis: .vertical)
                            .accessibilityLabel("备注")
                            .focused($editingText)
                            .lineLimit(2...5)
                    }
                    Toggle("在流水中隐藏金额和备注", isOn: $fields.hideAmount)
                }
            }
            .disabled(session.writeBusy || blocksSave)
            .safeAreaInset(edge: .bottom) { saveBar }
            .navigationTitle(detail == nil ? "新增流水" : "编辑流水")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { close() }.disabled(session.writeBusy)
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完成输入") { editingText = false }
                }
            }
            .confirmationDialog("放弃未保存的更改？", isPresented: $confirmClose, titleVisibility: .visible) {
                Button("放弃更改", role: .destructive) { dismiss() }
                Button("继续编辑", role: .cancel) {}
            }
            .onChange(of: fields.kind) { _, _ in
                fields.categoryID = ""
                fields.reversal = false
            }
            .onChange(of: session.writeContextID) { _, _ in dismiss() }
        }
        .modifier(PrivacyShield())
        .interactiveDismissDisabled(session.writeBusy || fields != initial)
    }

    private var blocksSave: Bool { needsVerification || (detail == nil && session.hasPendingCreation) }

    private var saveBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let error { InlineError(message: error) }
            if needsVerification {
                Button("关闭并重新读取流水") {
                    dismiss()
                    session.refresh()
                }
                .buttonStyle(.borderedProminent)
            } else if detail == nil && session.hasPendingCreation && !session.writeBusy {
                Button("返回流水核对原请求") { dismiss() }
                    .buttonStyle(.borderedProminent)
            } else {
                Button {
                    editingText = false
                    Task { await save() }
                } label: {
                    Group {
                        if session.writeBusy { ProgressView("正在保存…").tint(.white) }
                        else { Text("保存流水") }
                    }
                    .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(session.writeBusy || !session.canWrite || session.dataBusy || (detail != nil && fields == initial))
                .accessibilityIdentifier("transaction.save")
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }

    private var accountOptions: [Account] {
        session.accounts.values.filter {
            TransactionDraft.isAccountSelectable($0.id, ledgerID: session.currentLedgerID, accounts: session.accounts)
        }.sorted { $0.name == $1.name ? $0.id < $1.id : $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    private var categoryOptions: [TransactionCategory] {
        session.categories.values.filter {
            TransactionDraft.isCategorySelectable($0.id, kind: fields.kind, categories: session.categories)
        }.sorted { categoryLabel($0).localizedStandardCompare(categoryLabel($1)) == .orderedAscending }
    }

    private func accountPicker(_ label: String, selection: Binding<String>) -> some View {
        Picker(label, selection: selection) {
            Text("请选择").tag("")
            let current = selection.wrappedValue
            if !current.isEmpty, !accountOptions.contains(where: { $0.id == current }) {
                Text("原账户当前不可选").tag(current)
            }
            ForEach(accountOptions) { account in
                let name = session.accounts[account.parentId].map { $0.name + " / " + account.name } ?? account.name
                Text("\(name) · \(account.currency)").tag(account.id)
            }
        }
    }

    private func categoryLabel(_ category: TransactionCategory) -> String {
        session.categories[category.parentId].map { $0.name + " / " + category.name } ?? category.name
    }

    private func amountField(_ title: String, currency: String?, text: Binding<String>, signed: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(currency.map { "\(title)（\($0)）" } ?? title)
            TextField("0.00", text: text)
                .keyboardType(signed ? .numbersAndPunctuation : .decimalPad)
                .monospacedDigit()
                .focused($editingText)
                .accessibilityLabel(title)
        }
    }

    private var sourceCurrency: String? { session.accounts[fields.sourceID]?.currency }
    private var destinationCurrency: String? { session.accounts[fields.destinationID]?.currency }
    private var primaryCurrency: String? { fields.usesSource ? sourceCurrency : destinationCurrency }
    private var sameCurrency: Bool { sourceCurrency != nil && sourceCurrency == destinationCurrency }
    private var usesSecondAmount: Bool {
        fields.kind == .transfer && fields.direction == .between && sourceCurrency != nil &&
            destinationCurrency != nil && !sameCurrency
    }

    private func close() {
        if needsVerification {
            dismiss()
            session.refresh()
        } else if fields == initial || blocksSave {
            dismiss()
        } else {
            confirmClose = true
        }
    }

    private func save() async {
        error = nil
        guard let draft = makeDraft() else { return }
        do {
            if let detail {
                try await session.updateTransaction(detail, draft: draft, contextID: contextID)
            } else {
                try await session.createTransaction(draft, contextID: contextID)
            }
            dismiss()
        } catch is CancellationError {
            return
        } catch {
            let apiError = error as? APIError
            if detail != nil && (error is URLError || apiError?.isAmbiguousTransactionWrite == true) {
                needsVerification = true
                self.error = "未能确认更新结果。请重新读取流水并核对后再编辑。"
            } else {
                if apiError?.isTransactionConflict == true { needsVerification = true }
                self.error = session.message(for: error)
            }
        }
    }

    private func makeDraft() -> TransactionDraft? {
        guard !fields.usesSource || !fields.sourceID.isEmpty, !fields.usesDestination || !fields.destinationID.isEmpty else {
            error = "请选择此流水需要的账户。"
            return nil
        }
        guard fields.kind == .balanceAdjustment || !fields.categoryID.isEmpty else {
            error = "请选择末级分类。"
            return nil
        }
        guard let amount = MoneyFormatter.parseMinorUnits(fields.amount, locale: locale),
              fields.kind == .balanceAdjustment || amount > 0 else {
            error = fields.kind == .balanceAdjustment ? "请输入有效的期初余额，最多两位小数。" : "请输入大于零的金额，最多两位小数。"
            return nil
        }
        var destinationAmount: Int64 = fields.usesDestination ? amount : 0
        if usesSecondAmount {
            guard let second = MoneyFormatter.parseMinorUnits(fields.secondAmount, locale: locale), second > 0 else {
                error = "请输入大于零的转入金额，最多两位小数。"
                return nil
            }
            destinationAmount = second
        }
        let original = detail?.record
        let time = Int64(fields.date.timeIntervalSince1970.rounded(.down))
        let offset = original.flatMap { $0.time == time ? $0.utcOffset : nil }
            ?? TimeZone.current.secondsFromGMT(for: fields.date) / 60
        let reversal = (fields.kind == .income || fields.kind == .expense) && fields.reversal
        return TransactionDraft(
            type: fields.kind, categoryID: fields.kind == .balanceAdjustment ? "0" : fields.categoryID,
            time: time, utcOffset: offset, sourceAccountID: fields.usesSource ? fields.sourceID : "0",
            destinationAccountID: fields.usesDestination ? fields.destinationID : "0",
            sourceAmount: fields.usesSource ? (reversal ? -amount : amount) : 0,
            destinationAmount: destinationAmount, hideAmount: fields.hideAmount, comment: fields.comment
        )
    }

    private enum Direction: String, CaseIterable, Identifiable {
        case between, outgoing, incoming
        var id: Self { self }
        var title: String {
            switch self {
            case .between: return "账户间转账"
            case .outgoing: return "转出到外部"
            case .incoming: return "从外部转入"
            }
        }
    }

    private struct Fields: Equatable {
        var kind: TransactionKind = .expense
        var direction: Direction = .between
        var sourceID = ""
        var destinationID = ""
        var categoryID = ""
        var amount = ""
        var secondAmount = ""
        var reversal = false
        var date = Date()
        var hideAmount = false
        var comment = ""

        var usesSource: Bool { kind != .transfer || direction != .incoming }
        var usesDestination: Bool { kind == .transfer && direction != .outgoing }
        var sourceLabel: String {
            switch kind {
            case .income: return "目标账户"
            case .balanceAdjustment: return "账户"
            case .expense, .transfer: return "来源账户"
            }
        }
        var amountLabel: String {
            if kind == .balanceAdjustment { return "期初余额" }
            if kind == .transfer { return usesSource ? "转出金额" : "转入金额" }
            return "金额"
        }

        init(draft: TransactionDraft?) {
            guard let draft else { return }
            kind = draft.type
            sourceID = draft.sourceAccountID == "0" ? "" : draft.sourceAccountID
            destinationID = draft.destinationAccountID == "0" ? "" : draft.destinationAccountID
            if kind == .transfer {
                direction = sourceID.isEmpty ? .incoming : (destinationID.isEmpty ? .outgoing : .between)
            }
            categoryID = draft.categoryID
            reversal = (kind == .income || kind == .expense) && draft.sourceAmount < 0
            let primary = usesSource ? Decimal(draft.sourceAmount) : Decimal(draft.destinationAmount)
            amount = NSDecimalNumber(decimal: (reversal ? -primary : primary) / 100).stringValue
            secondAmount = NSDecimalNumber(decimal: Decimal(draft.destinationAmount) / 100).stringValue
            date = Date(timeIntervalSince1970: TimeInterval(draft.time))
            hideAmount = draft.hideAmount
            comment = draft.comment
        }
    }
}

private extension TransactionKind {
    var title: String {
        switch self {
        case .balanceAdjustment: return "期初余额"
        case .income: return "收入"
        case .expense: return "支出"
        case .transfer: return "转账"
        }
    }
}
