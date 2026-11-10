import Combine
import FinexyCore
import Foundation

@MainActor
final class AppSession: ObservableObject {
    enum Phase { case starting, signedOut, restoring, restoreFailed, signedIn }

    @Published private(set) var phase: Phase = .starting
    @Published private(set) var user: UserProfile?
    @Published private(set) var serverText = ""
    @Published private(set) var hasChallenge = false
    @Published private(set) var authBusy = false
    @Published private(set) var authError: String?
    @Published private(set) var ledgers: [Ledger] = []
    @Published private(set) var currentLedgerID = "0"
    @Published private(set) var transactions: [TransactionRecord] = []
    @Published private(set) var accounts: [String: Account] = [:]
    @Published private(set) var categories: [String: TransactionCategory] = [:]
    @Published private(set) var dataBusy = false
    @Published private(set) var dataError: String?
    @Published private(set) var hasLoaded = false
    @Published private(set) var nextCursor: String?
    @Published private(set) var lastReadAt: Date?
    @Published var showAmounts = false

    private let store = KeychainStore()
    private var saved: SavedSession?
    private var challenge: (server: ServerAddress, token: String)?
    private var authTask: Task<Void, Never>?
    private var dataTask: Task<Void, Never>?
    private var authGeneration = UUID()
    private var dataGeneration = UUID()
    private var started = false
    private var needsRevalidation = false
    private var isInBackground = false

    var currentLedgerName: String { ledgers.first { $0.id == currentLedgerID }?.name ?? "账本待核验" }
    var displayName: String { user?.nickname.isEmpty == false ? user!.nickname : (user?.username ?? "") }

    func start() {
        guard !started else { return }
        started = true
        guard !isInBackground else { needsRevalidation = true; return }
        restore()
    }

    func restore() {
        cancelAuthentication()
        clearFinancialData()
        user = nil
        ledgers = []
        authError = nil
        do {
            guard let session = try store.load() else { phase = .signedOut; return }
            saved = session
            serverText = session.server
            let server = try ServerAddress(rawValue: session.server)
            let version = authGeneration
            phase = .restoring
            authTask = Task { [weak self] in
                guard let self else { return }
                do {
                    let client = APIClient(server: server)
                    async let profile = client.profile(token: session.token)
                    async let books = client.ledgers(token: session.token)
                    let (verifiedUser, verifiedBooks) = try await (profile, books)
                    try Task.checkCancellation()
                    guard self.authGeneration == version else { return }
                    guard verifiedUser.username == session.username else { throw SessionError.identityChanged }
                    self.enterSession(session, user: verifiedUser, ledgers: verifiedBooks)
                } catch {
                    guard self.authGeneration == version, !Task.isCancelled else { return }
                    if (error as? APIError)?.isUnauthorized == true || error is SessionError {
                        self.signOut(message: "登录已失效，请重新登录。")
                    } else {
                        self.phase = .restoreFailed
                        self.authError = self.message(for: error)
                    }
                }
            }
        } catch {
            phase = .restoreFailed
            authError = message(for: error)
        }
    }

    func login(server raw: String, loginName: String, password: String, allowHTTP: Bool) {
        guard !authBusy else { return }
        authError = nil
        do {
            let server = try ServerAddress(rawValue: raw)
            guard server.isSecure || allowHTTP else { throw SessionError.insecureConsent }
            guard !loginName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !password.isEmpty else {
                throw SessionError.missingCredentials
            }
            cancelAuthentication()
            authBusy = true
            let version = authGeneration
            authTask = Task { [weak self] in
                guard let self else { return }
                do {
                    let response = try await APIClient(server: server).authorize(
                        loginName: loginName.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
                    try Task.checkCancellation()
                    guard self.authGeneration == version else { return }
                    if response.need2FA {
                        self.challenge = (server, response.token)
                        self.hasChallenge = true
                        self.serverText = server.rawValue
                    } else {
                        try await self.finishAuthentication(response, server: server, version: version)
                    }
                } catch {
                    guard self.authGeneration == version, !Task.isCancelled else { return }
                    self.authError = self.message(for: error)
                }
                if self.authGeneration == version { self.authBusy = false }
            }
        } catch { authError = message(for: error) }
    }

    func completeTwoFactor(code: String, recovery: Bool) {
        guard !authBusy, let challenge else { return }
        guard !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        authError = nil
        authBusy = true
        let version = authGeneration
        authTask = Task { [weak self] in
            guard let self else { return }
            do {
                let response = try await APIClient(server: challenge.server).authorizeTwoFactor(
                    token: challenge.token, code: code.trimmingCharacters(in: .whitespacesAndNewlines), recovery: recovery)
                try Task.checkCancellation()
                guard self.authGeneration == version else { return }
                guard !response.need2FA else { throw SessionError.invalidAuthentication }
                try await self.finishAuthentication(response, server: challenge.server, version: version)
            } catch {
                guard self.authGeneration == version, !Task.isCancelled else { return }
                self.authError = self.message(for: error)
            }
            if self.authGeneration == version { self.authBusy = false }
        }
    }

    func cancelChallenge() {
        cancelAuthentication()
        authError = nil
    }

    private func finishAuthentication(_ response: AuthResponse, server: ServerAddress, version: UUID) async throws {
        let client = APIClient(server: server)
        async let profile = client.profile(token: response.token)
        async let books = client.ledgers(token: response.token)
        let (verifiedUser, verifiedBooks) = try await (profile, books)
        try Task.checkCancellation()
        guard authGeneration == version else { return }
        if let authenticatedUser = response.user, authenticatedUser.username != verifiedUser.username {
            throw SessionError.identityChanged
        }
        let session = SavedSession(server: server.rawValue, token: response.token,
                                   username: verifiedUser.username, ledgerID: "0")
        try store.save(session)
        challenge = nil
        hasChallenge = false
        enterSession(session, user: verifiedUser, ledgers: verifiedBooks)
    }

    private func enterSession(_ session: SavedSession, user: UserProfile, ledgers: [Ledger]) {
        clearFinancialData()
        saved = session
        serverText = session.server
        self.user = user
        self.ledgers = ledgers
        currentLedgerID = ledgers.contains { $0.id == session.ledgerID } ? session.ledgerID : "0"
        phase = .signedIn
        authError = nil
        needsRevalidation = false
        refresh()
    }

    func chooseLedger(_ ledgerID: String) {
        guard phase == .signedIn, ledgers.contains(where: { $0.id == ledgerID }) else { return }
        clearFinancialData()
        currentLedgerID = ledgerID
        showAmounts = false
        if var session = saved {
            session.ledgerID = ledgerID
            saved = session
            do { try store.save(session) }
            catch { authError = "账本已切换，但未能保存此设备的选择。" }
        }
        refresh()
    }

    @discardableResult
    func refresh() -> Task<Void, Never>? {
        guard phase == .signedIn, let saved else { return nil }
        clearFinancialData()
        dataBusy = true
        let version = dataGeneration
        let ledgerID = currentLedgerID
        dataTask = Task { [weak self] in
            guard let self else { return }
            do {
                let client = APIClient(server: try ServerAddress(rawValue: saved.server))
                async let books = client.ledgers(token: saved.token)
                async let accountList = client.accounts(token: saved.token, ledgerID: ledgerID)
                async let categoryList = client.categories(token: saved.token, ledgerID: ledgerID)
                async let page = client.transactions(token: saved.token, ledgerID: ledgerID)
                let (verifiedBooks, accountRows, categoryRows, transactionPage) = try await (books, accountList, categoryList, page)
                try Task.checkCancellation()
                guard self.dataGeneration == version else { return }
                guard verifiedBooks.contains(where: { $0.id == ledgerID }) else { throw SessionError.ledgerUnavailable }
                self.ledgers = verifiedBooks
                self.accounts = self.flattenAccounts(accountRows)
                self.categories = self.flattenCategories(categoryRows)
                self.transactions = self.unique(transactionPage.items)
                self.nextCursor = transactionPage.nextTimeSequenceId.flatMap { $0 == "0" ? nil : $0 }
                self.hasLoaded = true
                self.lastReadAt = Date()
            } catch {
                guard self.dataGeneration == version, !Task.isCancelled else { return }
                self.handleDataFailure(error)
            }
            if self.dataGeneration == version { self.dataBusy = false }
        }
        return dataTask
    }

    func loadMore() {
        guard !dataBusy, phase == .signedIn, let saved, let cursor = nextCursor else { return }
        dataBusy = true
        dataError = nil
        let version = dataGeneration
        let ledgerID = currentLedgerID
        dataTask = Task { [weak self] in
            guard let self else { return }
            do {
                let client = APIClient(server: try ServerAddress(rawValue: saved.server))
                let page = try await client.transactions(token: saved.token, ledgerID: ledgerID, cursor: cursor)
                try Task.checkCancellation()
                guard self.dataGeneration == version else { return }
                self.transactions = self.unique(self.transactions + page.items)
                self.nextCursor = page.nextTimeSequenceId.flatMap { $0 == "0" ? nil : $0 }
                self.lastReadAt = Date()
            } catch {
                guard self.dataGeneration == version, !Task.isCancelled else { return }
                self.handleDataFailure(error)
            }
            if self.dataGeneration == version { self.dataBusy = false }
        }
    }

    private func handleDataFailure(_ error: Error) {
        if (error as? APIError)?.isUnauthorized == true {
            signOut(message: "登录已失效，请重新登录。")
        } else {
            // Failed access is never represented as an empty ledger or stale cached finance.
            transactions = []
            accounts = [:]
            categories = [:]
            nextCursor = nil
            hasLoaded = false
            lastReadAt = nil
            dataError = message(for: error)
        }
    }

    func signOut(message: String? = nil) {
        cancelAuthentication()
        clearFinancialData()
        saved = nil
        user = nil
        ledgers = []
        currentLedgerID = "0"
        showAmounts = false
        phase = .signedOut
        authError = message
        do { try store.remove() }
        catch {
            phase = .restoreFailed
            authError = "当前界面已退出，但未能移除此设备的登录凭据。请解锁设备后重试退出。"
        }
    }

    func protectInBackground() {
        isInBackground = true
        // Invalidate login/2FA/restore too: a late background response must not
        // establish or persist a fresh session while the interface is hidden.
        cancelAuthentication()
        clearFinancialData()
        showAmounts = false
        user = nil
        ledgers = []
        needsRevalidation = saved != nil || !started
        phase = needsRevalidation ? .restoring : .signedOut
    }

    func resumeIfNeeded() {
        isInBackground = false
        if needsRevalidation { restore() }
    }

    private func cancelAuthentication() {
        authGeneration = UUID()
        authTask?.cancel()
        authTask = nil
        challenge = nil
        hasChallenge = false
        authBusy = false
    }

    private func clearFinancialData() {
        dataGeneration = UUID()
        dataTask?.cancel()
        dataTask = nil
        transactions = []
        accounts = [:]
        categories = [:]
        hasLoaded = false
        dataBusy = false
        dataError = nil
        nextCursor = nil
        lastReadAt = nil
    }

    private func unique(_ items: [TransactionRecord]) -> [TransactionRecord] {
        var seen = Set<String>()
        return items.filter { seen.insert($0.id).inserted }
    }

    private func flattenAccounts(_ items: [Account]) -> [String: Account] {
        var result: [String: Account] = [:]
        func visit(_ rows: [Account]) {
            for row in rows { result[row.id] = row; visit(row.subAccounts ?? []) }
        }
        visit(items)
        return result
    }

    private func flattenCategories(_ items: [TransactionCategory]) -> [String: TransactionCategory] {
        var result: [String: TransactionCategory] = [:]
        func visit(_ rows: [TransactionCategory]) {
            for row in rows { result[row.id] = row; visit(row.subCategories ?? []) }
        }
        visit(items)
        return result
    }

    private func message(for error: Error) -> String {
        if let error = error as? APIError { return error.localizedDescription }
        if let error = error as? SessionError { return error.localizedDescription }
        if error is KeychainStore.StoreError { return error.localizedDescription }
        if let error = error as? URLError {
            switch error.code {
            case .notConnectedToInternet, .networkConnectionLost: return "网络已断开。请检查连接后重试。"
            case .timedOut: return "服务器响应超时。请稍后重试。"
            case .serverCertificateUntrusted, .secureConnectionFailed: return "无法建立安全连接。请检查服务器证书与地址。"
            default: return "无法连接服务器。请检查地址与网络后重试。"
            }
        }
        return "未能读取服务器数据，请重试。"
    }

    private enum SessionError: LocalizedError {
        case insecureConsent, missingCredentials, identityChanged, invalidAuthentication, ledgerUnavailable
        var errorDescription: String? {
            switch self {
            case .insecureConsent: return "此地址使用 HTTP。请先确认明文传输提示，或改用 HTTPS。"
            case .missingCredentials: return "请输入用户名和密码。"
            case .identityChanged: return "登录身份未通过核验，请重新登录。"
            case .invalidAuthentication: return "二步验证未完成，请重新登录。"
            case .ledgerUnavailable: return "此账本已不可访问。请切换账本或重试。"
            }
        }
    }
}
