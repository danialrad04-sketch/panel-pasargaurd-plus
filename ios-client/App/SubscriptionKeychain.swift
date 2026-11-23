import Foundation
import Security

enum SubscriptionKeychain {
    private static func identity(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Bundle.main.bundleIdentifier ?? "com.pasarguard.vpn.ios",
            kSecAttrAccount as String: account
        ]
    }

    static func load() throws -> String? { try load(account: "subscription-link") }

    private static func load(account: String) throws -> String? {
        var item: CFTypeRef?
        var query = identity(account)
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecReturnData as String] = true
        let result = SecItemCopyMatching(query as CFDictionary, &item)
        if result == errSecItemNotFound { return nil }
        guard result == errSecSuccess, let bytes = item as? Data,
              let link = String(data: bytes, encoding: .utf8) else {
            throw SubscriptionError.credentialsUnavailable
        }
        return link
    }

    static func save(_ link: URL) throws {
        let content = Data(link.absoluteString.utf8)
        let result = SecItemUpdate(identity("subscription-link") as CFDictionary, [kSecValueData as String: content] as CFDictionary)
        if result == errSecSuccess { return }
        guard result == errSecItemNotFound else { throw SubscriptionError.credentialsUnavailable }
        guard add(content, account: "subscription-link") == errSecSuccess else {
            throw SubscriptionError.credentialsUnavailable
        }
    }

    static func deviceIdentifier() throws -> String {
        let account = "device-identifier"
        if let saved = try load(account: account) { return saved }
        let identifier = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let result = add(Data(identifier.utf8), account: account)
        if result == errSecSuccess { return identifier }
        if result == errSecDuplicateItem, let saved = try load(account: account) { return saved }
        throw SubscriptionError.credentialsUnavailable
    }

    private static func add(_ content: Data, account: String) -> OSStatus {
        var attributes = identity(account)
        attributes[kSecValueData as String] = content
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(attributes as CFDictionary, nil)
    }

    static func remove() throws {
        let result = SecItemDelete(identity("subscription-link") as CFDictionary)
        guard result == errSecSuccess || result == errSecItemNotFound else {
            throw SubscriptionError.credentialsUnavailable
        }
    }
}
