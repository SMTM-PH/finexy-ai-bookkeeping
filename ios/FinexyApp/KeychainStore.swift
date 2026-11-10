import Foundation
import Security

struct SavedSession: Codable {
    let server: String
    let token: String
    let username: String
    var ledgerID: String
}

/// Stores only the authenticated session, never a password or a 2FA challenge.
struct KeychainStore {
    private let service = "com.finexy.ios.session"
    private let account = "current-session"

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    func load() throws -> SavedSession? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw StoreError.unavailable }
        do { return try JSONDecoder().decode(SavedSession.self, from: data) }
        catch { throw StoreError.unavailable }
    }

    func save(_ session: SavedSession) throws {
        let values: [String: Any] = [
            kSecValueData as String: try JSONEncoder().encode(session),
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

    enum StoreError: LocalizedError {
        case unavailable
        var errorDescription: String? { "无法访问此设备的安全登录存储。请解锁设备后重试。" }
    }
}
