import Foundation

public enum APIError: Error, LocalizedError, Equatable, Sendable {
    case invalidServerAddress
    case invalidResponse
    case httpStatus(Int)
    case server(code: Int?, status: Int)
    case invalidCursor
    case ledgerMismatch

    public var isUnauthorized: Bool {
        switch self {
        case .httpStatus(401): return true
        case .server(let code, let status): return status == 401 || code.map { (202001...202006).contains($0) } == true
        default: return false
        }
    }

    public var errorDescription: String? {
        switch self {
        case .invalidServerAddress: return "请输入完整的 HTTP 或 HTTPS 服务器地址，不含账号、查询参数或片段。"
        case .invalidResponse: return "服务器返回的数据不完整或格式不受支持，请核对服务器地址与版本后重试。"
        case .invalidCursor: return "服务器分页信息异常，请刷新流水后重试。"
        case .ledgerMismatch: return "服务器返回的账本归属不一致，已停止显示数据。请重新选择账本。"
        case .httpStatus(let status):
            return Self.statusDescription(status)
        case .server(let code, let status):
            if let code, (201000...201002).contains(code) { return "账号或密码不正确，请核对后重试。" }
            if let code, (203000...203005).contains(code) { return "两步验证码或恢复码无效，请重新输入。" }
            return Self.statusDescription(status)
        }
    }

    private static func statusDescription(_ status: Int) -> String {
        switch status {
        case 401: return "登录已失效，请重新登录。"
        case 403: return "当前账号没有访问权限，请核对账本成员权限。"
        case 404: return "未找到接口，请核对服务器地址、子路径与版本。"
        case 300...399: return "服务器要求跳转。请直接填写最终服务器地址后重试。"
        case 429: return "请求过于频繁，请稍后重试。"
        case 500...599: return "服务器暂时无法处理请求，请稍后重试。"
        default: return "请求未能完成，请核对输入与服务器状态后重试。"
        }
    }
}

private struct ResponseStatus: Decodable {
    let success: Bool
    let errorCode: Int?
}

private struct ResponseEnvelope<Result: Decodable>: Decodable {
    let result: Result
}

/// Reject every redirect, including on injected sessions. A reverse proxy must
/// be configured at its final address, so credentials never cross origins.
private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

public final class APIClient: @unchecked Sendable {
    public let server: ServerAddress
    private let session: URLSession
    private let redirectDelegate = NoRedirectDelegate()

    public init(server: ServerAddress, session: URLSession? = nil) {
        self.server = server
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.urlCache = nil
            configuration.httpCookieStorage = nil
            configuration.httpShouldSetCookies = false
            configuration.urlCredentialStorage = nil
            configuration.timeoutIntervalForRequest = 30
            configuration.timeoutIntervalForResource = 60
            self.session = URLSession(configuration: configuration)
        }
    }

    public func authorize(loginName: String, password: String) async throws -> AuthResponse {
        let response: AuthResponse = try await request("api/authorize.json", method: "POST", body: ["loginName": loginName, "password": password])
        guard !response.token.isEmpty else { throw APIError.invalidResponse }
        return response
    }

    public func authorizeTwoFactor(token: String, code: String, recovery: Bool = false) async throws -> AuthResponse {
        let response: AuthResponse = try await request(recovery ? "api/2fa/recovery.json" : "api/2fa/authorize.json", method: "POST", token: token,
                                                       body: [recovery ? "recoveryCode" : "passcode": code])
        guard !response.token.isEmpty, !response.need2FA else { throw APIError.invalidResponse }
        return response
    }

    public func profile(token: String) async throws -> UserProfile {
        let profile: UserProfile = try await request("api/v1/users/profile/get.json", token: token)
        guard !profile.username.isEmpty else { throw APIError.invalidResponse }
        return profile
    }

    public func ledgers(token: String) async throws -> [Ledger] {
        let ledgers: [Ledger] = try await request("api/v1/ledger/list.json", token: token)
        guard Set(ledgers.map(\.id)).count == ledgers.count,
              ledgers.allSatisfy({ Int64($0.id).map { $0 > 0 } == true })
        else { throw APIError.invalidResponse }
        return [.defaultPersonal] + ledgers
    }

    public func accounts(token: String, ledgerID: String) async throws -> [Account] {
        try validateLedgerID(ledgerID)
        let accounts: [Account] = try await request("api/v1/accounts/list.json", token: token, query: ["ledgerId": ledgerID, "visible_only": "false"])
        guard accounts.flatMap(\.flattened).allSatisfy({ $0.ledgerId == ledgerID }) else { throw APIError.ledgerMismatch }
        return accounts
    }

    public func categories(token: String, ledgerID: String) async throws -> [TransactionCategory] {
        try validateLedgerID(ledgerID)
        let groups: [String: [TransactionCategory]] = try await request("api/v1/transaction/categories/list.json", token: token, query: ["ledgerId": ledgerID])
        return groups.keys.sorted().flatMap { groups[$0] ?? [] }
    }

    public func transactions(token: String, ledgerID: String, cursor: String? = nil) async throws -> TransactionPage {
        try validateLedgerID(ledgerID)
        if let cursor, Int64(cursor).map({ $0 > 0 }) != true { throw APIError.invalidCursor }
        let page: TransactionPage = try await request("api/v1/transactions/list.json", token: token, query: [
            "ledgerId": ledgerID, "max_time": cursor ?? "0", "min_time": "0", "type": "0", "count": "50", "page": "1",
            "with_count": "true", "with_pictures": "false"
        ])
        guard page.items.allSatisfy({ $0.ledgerId == ledgerID }) else { throw APIError.ledgerMismatch }
        if let next = page.nextTimeSequenceId, next != "0" {
            guard let numericNext = Int64(next), numericNext > 0,
                  cursor.flatMap(Int64.init).map({ numericNext < $0 }) ?? true
            else { throw APIError.invalidCursor }
        }
        return page
    }

    private func validateLedgerID(_ id: String) throws {
        guard Int64(id).map({ $0 >= 0 }) == true else { throw APIError.ledgerMismatch }
    }

    private func request<Result: Decodable>(_ endpoint: String, method: String = "GET", token: String? = nil,
                                             query: [String: String] = [:], body: [String: String]? = nil) async throws -> Result {
        let endpointURL = server.url.appendingPathComponent(endpoint)
        guard var components = URLComponents(url: endpointURL, resolvingAgainstBaseURL: false) else { throw APIError.invalidServerAddress }
        if !query.isEmpty { components.queryItems = query.keys.sorted().map { URLQueryItem(name: $0, value: query[$0]) } }
        guard let url = components.url else { throw APIError.invalidServerAddress }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(String(TimeZone.current.secondsFromGMT() / 60), forHTTPHeaderField: "X-Timezone-Offset")
        request.setValue(TimeZone.current.identifier, forHTTPHeaderField: "X-Timezone-Name")
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        let (data, response) = try await session.data(for: request, delegate: redirectDelegate)
        guard let response = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        let status = response.statusCode
        let decoder = JSONDecoder()
        if let resultStatus = try? decoder.decode(ResponseStatus.self, from: data), !resultStatus.success {
            throw APIError.server(code: resultStatus.errorCode, status: status)
        }
        guard (200...299).contains(status) else { throw APIError.httpStatus(status) }
        guard let resultStatus = try? decoder.decode(ResponseStatus.self, from: data), resultStatus.success,
              let envelope = try? decoder.decode(ResponseEnvelope<Result>.self, from: data)
        else { throw APIError.invalidResponse }
        return envelope.result
    }
}
