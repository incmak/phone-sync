import CryptoKit
import Foundation
import Sodium

public struct PublicIdentity: Codable, Sendable, Equatable {
    public let deviceID: String
    public let encryptionKey: Data
    public let signingKey: Data
}

private struct PrivateIdentity: Codable, Sendable {
    let version: Int
    let identity: PublicIdentity
    let encryptionSecret: Data
    let signingSecret: Data
    let contentKey: Data
    let noncePrefix: Data

    static func generate() throws -> Self {
        let sodium = Sodium()
        guard let box = sodium.box.keyPair(), let sign = sodium.sign.keyPair(),
              let prefix = sodium.randomBytes.buf(length: 16), let key = sodium.randomBytes.buf(length: 32) else {
            throw CryptoError.encryptionFailed
        }
        return Self(version: 1, identity: PublicIdentity(deviceID: UUID().uuidString.lowercased(),
                                                       encryptionKey: Data(box.publicKey), signingKey: Data(sign.publicKey)),
                    encryptionSecret: Data(box.secretKey), signingSecret: Data(sign.secretKey),
                    contentKey: Data(key), noncePrefix: Data(prefix))
    }

    func validate() throws {
        guard version == 1, UUID(uuidString: identity.deviceID) != nil,
              encryptionSecret.count == 32, signingSecret.count == 64,
              contentKey.count == 32, noncePrefix.count == 16 else { throw StorageError.repairRequired }
        let box = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: encryptionSecret)
        let sign = try Curve25519.Signing.PrivateKey(rawRepresentation: signingSecret.prefix(32))
        guard box.publicKey.rawRepresentation == identity.encryptionKey,
              sign.publicKey.rawRepresentation == identity.signingKey,
              signingSecret.suffix(32) == identity.signingKey else { throw StorageError.repairRequired }
    }
}

/// Serializes storage and identity-wide nonce allocation. SQLite transactions
/// also serialize distinct process/connection instances sharing the same file.
public actor DurableStore {
    let database: SQLiteConnection
    private let privateIdentity: PrivateIdentity
    public nonisolated let identity: PublicIdentity

    public init(path: String, vault: any KeyVault) throws {
        let database = try SQLiteConnection(path: path)
        let fresh = try Self.migrate(database)
        let storedIdentity = try database.execute("SELECT value FROM metadata WHERE key='identity'").first?["value"]
        let privateData = try vault.load()
        let record: PrivateIdentity
        if let privateData {
            guard !fresh, case .blob(let publicData) = storedIdentity else { throw StorageError.repairRequired }
            do {
                record = try JSONDecoder().decode(PrivateIdentity.self, from: privateData)
                try record.validate()
                guard try JSONDecoder().decode(PublicIdentity.self, from: publicData) == record.identity else {
                    throw StorageError.repairRequired
                }
            } catch { throw StorageError.repairRequired }
        } else {
            guard fresh, storedIdentity == nil else { throw StorageError.repairRequired }
            record = try PrivateIdentity.generate()
            // A crash between Keychain insertion and SQL commit fails closed on restart.
            // It cannot silently reuse a nonce under a surviving private identity.
            try vault.insert(JSONEncoder().encode(record))
            try database.transaction {
                try database.execute("INSERT INTO metadata(key,value) VALUES('identity',?)", [.blob(try JSONEncoder().encode(record.identity))])
                try database.execute("INSERT INTO metadata(key,value) VALUES('nonce_prefix',?)", [.blob(record.noncePrefix)])
                try database.execute("INSERT INTO metadata(key,value) VALUES('nonce_counter',?)", [.blob(Data(repeating: 0, count: 8))])
            }
        }
        guard try database.execute("SELECT value FROM metadata WHERE key='nonce_prefix'").first?["value"] == .blob(record.noncePrefix),
              case .blob(let counter) = try database.execute("SELECT value FROM metadata WHERE key='nonce_counter'").first?["value"],
              counter.count == 8 else { throw StorageError.repairRequired }
        self.database = database
        self.privateIdentity = record
        self.identity = record.identity
    }

    private static func migrate(_ database: SQLiteConnection) throws -> Bool {
        try database.transaction {
            guard case .integer(let version) = try database.execute("PRAGMA user_version").first?["user_version"] else {
                throw StorageError.repairRequired
            }
            guard version <= 2 else { throw StorageError.unsupportedVersion(version) }
            if version == 2 { return false }
            if version == 0 {
                guard try database.execute("SELECT name FROM sqlite_master WHERE type='table'").isEmpty else {
                    throw StorageError.repairRequired
                }
                try database.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL)")
            }
            try createDeliverySchema(database)
            try database.execute("PRAGMA user_version=2")
            return version == 0
        }
    }

    public func nextNonce() throws -> Data {
        try database.transaction {
            guard try database.execute("SELECT value FROM metadata WHERE key='nonce_prefix'").first?["value"] == .blob(privateIdentity.noncePrefix) else {
                throw StorageError.repairRequired
            }
            guard case .blob(let bytes) = try database.execute("SELECT value FROM metadata WHERE key='nonce_counter'").first?["value"],
                  bytes.count == 8 else { throw StorageError.repairRequired }
            let current = bytes.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
            guard current < UInt64.max else { throw StorageError.nonceExhausted }
            let nonce = try WireCrypto.nonce(prefix: privateIdentity.noncePrefix, counter: current + 1)
            try database.execute("UPDATE metadata SET value=? WHERE key='nonce_counter'", [.blob(Data(nonce.suffix(8)))])
            return nonce
        }
    }

    public func sealContent(_ plaintext: Data, context: Data) throws -> Data {
        guard let sealed = try AES.GCM.seal(plaintext, using: SymmetricKey(data: privateIdentity.contentKey),
                                           authenticating: context).combined else { throw CryptoError.encryptionFailed }
        return sealed
    }

    public func openContent(_ ciphertext: Data, context: Data) throws -> Data {
        try AES.GCM.open(AES.GCM.SealedBox(combined: ciphertext), using: SymmetricKey(data: privateIdentity.contentKey),
                         authenticating: context)
    }

    public func sign(_ data: Data) throws -> Data {
        try WireCrypto.sign(data, secretKey: privateIdentity.signingSecret)
    }

    /// Caller persists the returned envelope before making it eligible for transport.
    /// Each attempt burns its nonce, including an interrupted/failed SQL commit later.
    public func encrypt(_ plaintext: Data, peerPublicKey: Data) throws -> (nonce: Data, ciphertext: Data) {
        let nonce = try nextNonce()
        return (nonce, try WireCrypto.seal(plaintext, nonce: nonce, peerPublicKey: peerPublicKey,
                                          secretKey: privateIdentity.encryptionSecret))
    }

    public func decrypt(_ ciphertext: Data, nonce: Data, peerPublicKey: Data) throws -> Data {
        try WireCrypto.open(ciphertext, nonce: nonce, peerPublicKey: peerPublicKey, secretKey: privateIdentity.encryptionSecret)
    }
}
