import Foundation
import Testing
@testable import TwinotifyKit

final class MemoryVault: KeyVault, @unchecked Sendable {
    private let lock = NSLock()
    private var data: Data?
    func load() -> Data? { lock.withLock { data } }
    func insert(_ value: Data) throws {
        try lock.withLock {
            guard data == nil else { throw StorageError.repairRequired }
            data = value
        }
    }
    func remove() { lock.withLock { data = nil } }
}

private func directory() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("tw-store-" + UUID().uuidString)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

@Test func identityAndNoncesSurviveReopenAndConcurrentConnections() async throws {
    let folder = try directory()
    defer { try? FileManager.default.removeItem(at: folder) }
    let path = folder.appendingPathComponent("state.sqlite").path
    let vault = MemoryVault()
    let first = try DurableStore(path: path, vault: vault)
    let second = try DurableStore(path: path, vault: vault)
    #expect(first.identity == second.identity)
    let nonces = try await withThrowingTaskGroup(of: Data.self) { group in
        for index in 0..<40 {
            let store = index % 2 == 0 ? first : second
            group.addTask { try await store.nextNonce() }
        }
        var results: [Data] = []
        for try await nonce in group { results.append(nonce) }
        return results
    }
    #expect(Set(nonces).count == 40)
    #expect(Set(nonces.map { Data($0.prefix(16)) }).count == 1)
    let counters = nonces.map { $0.suffix(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) } }.sorted()
    #expect(counters == Array(UInt64(1)...UInt64(40)))
    let reopened = try DurableStore(path: path, vault: vault)
    let next = try await reopened.nextNonce()
    #expect(next.suffix(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) } == 41)
}

@Test func contentEncryptionSurvivesRestartAndBindsContext() async throws {
    let folder = try directory()
    defer { try? FileManager.default.removeItem(at: folder) }
    let path = folder.appendingPathComponent("state.sqlite").path
    let vault = MemoryVault()
    let first = try DurableStore(path: path, vault: vault)
    let plaintext = Data("Private notification text".utf8)
    let context = Data("link-a:canonical-id:sequence-1".utf8)
    let encrypted = try await first.sealContent(plaintext, context: context)
    #expect(encrypted.range(of: plaintext) == nil)
    let reopened = try DurableStore(path: path, vault: vault)
    #expect(try await reopened.openContent(encrypted, context: context) == plaintext)
    await #expect(throws: (any Error).self) {
        try await reopened.openContent(encrypted, context: Data("link-b".utf8))
    }
    var tampered = encrypted
    tampered[0] ^= 1
    await #expect(throws: (any Error).self) { try await reopened.openContent(tampered, context: context) }
}

@Test func identityLossAndNonceLossRequireRepair() async throws {
    let folder = try directory()
    defer { try? FileManager.default.removeItem(at: folder) }
    let path = folder.appendingPathComponent("state.sqlite").path
    let vault = MemoryVault()
    let store = try DurableStore(path: path, vault: vault)
    _ = try await store.nextNonce()
    #expect(throws: StorageError.repairRequired) {
        try DurableStore(path: folder.appendingPathComponent("missing.sqlite").path, vault: vault)
    }
    let sql = try SQLiteConnection(path: path)
    try sql.execute("DELETE FROM metadata WHERE key='nonce_counter'")
    await #expect(throws: StorageError.repairRequired) { try await store.nextNonce() }
    #expect(throws: StorageError.repairRequired) { try DurableStore(path: path, vault: vault) }
    vault.remove()
    #expect(throws: StorageError.repairRequired) { try DurableStore(path: path, vault: vault) }
}

@Test func nonceOverflowFailsWithoutWrappingAndNewerSchemaIsRefused() async throws {
    let folder = try directory()
    defer { try? FileManager.default.removeItem(at: folder) }
    let path = folder.appendingPathComponent("state.sqlite").path
    let vault = MemoryVault()
    let store = try DurableStore(path: path, vault: vault)
    let sql = try SQLiteConnection(path: path)
    let max = Data(repeating: 255, count: 8)
    try sql.execute("UPDATE metadata SET value=? WHERE key='nonce_counter'", [.blob(max)])
    await #expect(throws: StorageError.nonceExhausted) { try await store.nextNonce() }
    #expect(try sql.execute("SELECT value FROM metadata WHERE key='nonce_counter'").first?["value"] == .blob(max))
    try sql.execute("PRAGMA user_version=3")
    #expect(throws: StorageError.unsupportedVersion(3)) { try DurableStore(path: path, vault: vault) }
}

@Test func failedTransactionRollsBackAllWrites() throws {
    let folder = try directory()
    defer { try? FileManager.default.removeItem(at: folder) }
    let sql = try SQLiteConnection(path: folder.appendingPathComponent("state.sqlite").path)
    try sql.execute("CREATE TABLE work(id TEXT PRIMARY KEY, data BLOB)")
    #expect(throws: StorageError.self) {
        try sql.transaction {
            try sql.execute("INSERT INTO work VALUES('a',?)", [.blob(Data([1, 2, 3]))])
            try sql.execute("INSERT INTO work VALUES('a',?)", [.blob(Data([4, 5, 6]))])
        }
    }
    #expect(try sql.execute("SELECT * FROM work").isEmpty)
}
