import Foundation
import Security

public protocol KeyVault: Sendable {
    func load() throws -> Data?
    /// Insert once. Replacing identity is reserved for a separate explicit full reset.
    func insert(_ data: Data) throws
}

public struct KeychainVault: KeyVault {
    private let service: String
    public init(service: String = "co.twinotify.mac.identity") { self.service = service }
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: "identity-v1",
         // The local-only signed bundle has no provisioning profile. Use the
         // local login Keychain and its application ACL, not iCloud Keychain.
         // This is a fixed backend, never a fallback after identity lookup fails.
         kSecUseDataProtectionKeychain as String: false]
    }

    public func load() throws -> Data? {
        var query = query
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw VaultError.status(status) }
        return data
    }

    public func insert(_ data: Data) throws {
        var query = query
        query[kSecValueData as String] = data
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw VaultError.status(status) }
    }
}

public enum VaultError: Error { case status(OSStatus) }
