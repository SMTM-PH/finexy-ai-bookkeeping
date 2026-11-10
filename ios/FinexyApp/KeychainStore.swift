import CryptoKit
import FinexyCore
import Foundation
import Security

struct SavedSession: Codable {
    let server: String
    let token: String
    let username: String
    var ledgerID: String
}

/// Session and submitted creation snapshots are kept in separate device-only items.
struct KeychainStore {
    private let sessionItem = Item(service: "com.finexy.ios.session", account: "current-session")

    func load() throws -> SavedSession? { try sessionItem.load() }
    func save(_ session: SavedSession) throws { try sessionItem.save(session) }
    func remove() throws { try sessionItem.remove() }

    func pending(in session: SavedSession, ledgerID: String) throws -> TransactionCreateRequest? {
        try pendingItem(in: session, ledgerID: ledgerID).load()
    }

    func savePending(_ pending: TransactionCreateRequest, in session: SavedSession, ledgerID: String) throws {
        let item = try pendingItem(in: session, ledgerID: ledgerID)
        let existing: TransactionCreateRequest? = try item.load()
        guard existing == nil else { throw StoreError.pendingExists }
        try item.save(pending)
    }

    func removePending(_ pending: TransactionCreateRequest, in session: SavedSession, ledgerID: String) throws {
        let item = try pendingItem(in: session, ledgerID: ledgerID)
        let existing: TransactionCreateRequest? = try item.load()
        if existing?.clientSessionID == pending.clientSessionID { try item.remove() }
    }

    private func pendingItem(in session: SavedSession, ledgerID: String) throws -> Item {
        let scope = try JSONEncoder().encode([session.server, session.username, ledgerID])
        let digest = SHA256.hash(data: scope).map { String(format: "%02x", $0) }.joined()
        return Item(service: "com.finexy.ios.transaction-submission", account: digest)
    }

    enum StoreError: LocalizedError {
        case unavailable, pendingExists

        var errorDescription: String? {
            switch self {
            case .unavailable: return "无法访问此设备的安全存储。请解锁设备后重试。"
            case .pendingExists: return "此账本有一笔待核对的新增请求。请先处理原请求。"
            }
        }
    }

    private struct Item {
        let service: String
        let account: String

        private var query: [String: Any] {
            [kSecClass as String: kSecClassGenericPassword,
             kSecAttrService as String: service,
             kSecAttrAccount as String: account]
        }

        func load<Value: Decodable>() throws -> Value? {
            var request = query
            request[kSecReturnData as String] = true
            request[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: CFTypeRef?
            let status = SecItemCopyMatching(request as CFDictionary, &result)
            if status == errSecItemNotFound { return nil }
            guard status == errSecSuccess, let data = result as? Data,
                  let value = try? JSONDecoder().decode(Value.self, from: data) else { throw StoreError.unavailable }
            return value
        }

        func save<Value: Encodable>(_ value: Value) throws {
            let values: [String: Any] = [
                kSecValueData as String: try JSONEncoder().encode(value),
                kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            ]
            let status = SecItemUpdate(query as CFDictionary, values as CFDictionary)
            if status == errSecItemNotFound {
                var request = query
                values.forEach { request[$0.key] = $0.value }
                guard SecItemAdd(request as CFDictionary, nil) == errSecSuccess else { throw StoreError.unavailable }
            } else if status != errSecSuccess { throw StoreError.unavailable }
        }

        func remove() throws {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw StoreError.unavailable }
        }
    }
}
