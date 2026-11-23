import Foundation
import Security

enum SubscriptionKeychain {
    private static var identity: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Bundle.main.bundleIdentifier ?? "com.pasarguard.vpn.ios",
            kSecAttrAccount as String: "subscription-link"
        ]
    }

    static func load() throws -> String? {
        var item: CFTypeRef?
        var query = identity
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
        let result = SecItemUpdate(identity as CFDictionary, [kSecValueData as String: content] as CFDictionary)
        if result == errSecSuccess { return }
        guard result == errSecItemNotFound else { throw SubscriptionError.credentialsUnavailable }
        var attributes = identity
        attributes[kSecValueData as String] = content
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess else {
            throw SubscriptionError.credentialsUnavailable
        }
    }

    static func remove() throws {
        let result = SecItemDelete(identity as CFDictionary)
        guard result == errSecSuccess || result == errSecItemNotFound else {
            throw SubscriptionError.credentialsUnavailable
        }
    }
}
