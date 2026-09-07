import Foundation
import Testing
@testable import TwinotifyKit

private func fixture() throws -> (URL, String, MemoryVault, DurableStore) {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tw-delivery-" + UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent("state.sqlite").path
    let vault = MemoryVault()
    return (folder, path, vault, try DurableStore(path: path, vault: vault))
}
private func peer() -> PeerLink {
    PeerLink(deviceID: UUID().uuidString, pairID: UUID().uuidString, relayURL: "https://relay.example.test",
             encryptionKey: Data(repeating: 1, count: 32), signingKey: Data(repeating: 2, count: 32))
}
private func envelope() -> StoredEnvelope {
    let bytes = Data("{ \"ciphertext\": \"encrypted\" }".utf8)
    return StoredEnvelope(messageID: UUID().uuidString, bytes: bytes, digest: RawEnvelope.digest(bytes), expiresAt: 99_000)
}

@Test func custodyAndAckRecoverAtEveryDatabaseBoundary() async throws {
    let (folder, path, vault, first) = try fixture()
    defer { try? FileManager.default.removeItem(at: folder) }
    let link = peer(), messageID = UUID().uuidString, digest = String(repeating: "a", count: 64)
    try await first.addPeer(link)
    let desired = DesiredRecord(canonicalID: "private-canonical", sequence: 1, expiresAt: 9_000,
                                remove: false, title: "Secret title", body: "Secret body")
    _ = try await first.stage(linkID: link.id, messageID: messageID, digest: digest,
                              expiresAt: 9_000, desired: desired, now: 1)
    let afterStage = try DurableStore(path: path, vault: vault)
    #expect(try await afterStage.pending(linkID: link.id, now: 2).count == 1)
    #expect(try await afterStage.readyAcks(linkID: link.id).isEmpty)
    #expect(try await afterStage.desired(linkID: link.id, canonicalID: desired.canonicalID)?.body == "Secret body")
    let sql = try SQLiteConnection(path: path)
    let encrypted = try sql.execute("SELECT content FROM desired").first!.blob("content")
    #expect(encrypted.range(of: Data("Secret".utf8)) == nil)
    let receipt = envelope()
    try await afterStage.complete(linkID: link.id, messageID: messageID, digest: digest,
                                  outcome: .applied, receipt: receipt, now: 3)
    let afterComplete = try DurableStore(path: path, vault: vault)
    #expect(try await afterComplete.pending(linkID: link.id, now: 4).isEmpty)
    #expect(try await afterComplete.readyAcks(linkID: link.id).isEmpty)
    #expect(try await afterComplete.sendable(linkID: link.id, now: 4).first?.bytes == receipt.bytes)
    try await afterComplete.receiptAccepted(linkID: link.id, messageID: receipt.messageID)
    let afterCustody = try DurableStore(path: path, vault: vault)
    #expect(try await afterCustody.sendable(linkID: link.id, now: 5).isEmpty)
    #expect(try await afterCustody.readyAcks(linkID: link.id).first?.digest == digest)
    try await afterCustody.markAckSent(linkID: link.id, messageID: messageID, digest: digest)
    let afterAck = try DurableStore(path: path, vault: vault)
    #expect(try await afterAck.readyAcks(linkID: link.id).isEmpty)
    try await afterAck.replayReceipt(linkID: link.id, messageID: messageID, digest: digest)
    #expect(try await afterAck.sendable(linkID: link.id, now: 6).first?.bytes == receipt.bytes)
    #expect(try await afterAck.received(linkID: link.id, messageID: messageID)?.outcome == .applied)
}

@Test func duplicateDigestAndCapacityFailuresDoNotAlterDesiredState() async throws {
    let (folder, _, _, store) = try fixture()
    defer { try? FileManager.default.removeItem(at: folder) }
    let link = peer(), messageID = UUID().uuidString, digest = String(repeating: "b", count: 64)
    try await store.addPeer(link)
    let state = DesiredRecord(canonicalID: "a", sequence: 1, expiresAt: 100, remove: false, body: "first")
    _ = try await store.stage(linkID: link.id, messageID: messageID, digest: digest, expiresAt: 100, desired: state, now: 1)
    await #expect(throws: DeliveryStoreError.digestConflict) {
        try await store.stage(linkID: link.id, messageID: messageID, digest: String(repeating: "c", count: 64),
                              expiresAt: 100, desired: state, now: 2)
    }
    var limits = DeliveryLimits(); limits.contentBytes = 1
    let nextID = UUID().uuidString
    await #expect(throws: StorageError.capacityExceeded) {
        try await store.stage(linkID: link.id, messageID: nextID, digest: digest, expiresAt: 100,
            desired: DesiredRecord(canonicalID: "a", sequence: 2, expiresAt: 100, remove: false, body: "second"), now: 3, limits: limits)
    }
    #expect(try await store.received(linkID: link.id, messageID: nextID) == nil)
    #expect(try await store.desired(linkID: link.id, canonicalID: "a")?.sequence == 1)
    limits = DeliveryLimits(); limits.outboxRows = 0
    await #expect(throws: StorageError.capacityExceeded) {
        try await store.complete(linkID: link.id, messageID: messageID, digest: digest, outcome: .applied,
                                 receipt: envelope(), now: 4, limits: limits)
    }
    #expect(try await store.received(linkID: link.id, messageID: messageID)?.outcome == .pending)
}

@Test func removalAndReceiptCustodyAreScopedAndNonceContinues() async throws {
    let (folder, _, _, store) = try fixture()
    defer { try? FileManager.default.removeItem(at: folder) }
    let a = peer(), b = peer(), id = UUID().uuidString, digest = String(repeating: "d", count: 64)
    try await store.addPeer(a); try await store.addPeer(b)
    await #expect(throws: StorageError.capacityExceeded) { try await store.addPeer(peer()) }
    for link in [a, b] {
        _ = try await store.stage(linkID: link.id, messageID: id, digest: digest, expiresAt: 100, desired: nil, now: 1)
    }
    let receipt = envelope()
    try await store.complete(linkID: a.id, messageID: id, digest: digest, outcome: .rejected, receipt: receipt, now: 2)
    try await store.receiptAccepted(linkID: b.id, messageID: receipt.messageID)
    #expect(try await store.readyAcks(linkID: a.id).isEmpty)
    let before = try await store.nextNonce()
    try await store.beginRemoval(a.id)
    await #expect(throws: DeliveryStoreError.removingLink) {
        try await store.stage(linkID: a.id, messageID: UUID().uuidString, digest: digest, expiresAt: 100, desired: nil, now: 3)
    }
    #expect(try await store.peers().first(where: { $0.id == a.id })?.lifecycle == .removing)
    try await store.finishRemoval(a.id)
    #expect(try await store.received(linkID: a.id, messageID: id) == nil)
    #expect(try await store.received(linkID: b.id, messageID: id)?.outcome == .pending)
    let after = try await store.nextNonce()
    #expect(before.prefix(16) == after.prefix(16))
    #expect(after.last == before.last! + 1)
}

@Test func permissionBlockedWorkWakesOnRecoveryExpiryAndSupersession() async throws {
    let (folder, _, _, store) = try fixture()
    defer { try? FileManager.default.removeItem(at: folder) }
    let link = peer(), id = UUID().uuidString, digest = String(repeating: "e", count: 64)
    try await store.addPeer(link)
    _ = try await store.stage(linkID: link.id, messageID: id, digest: digest, expiresAt: 100,
        desired: DesiredRecord(canonicalID: "a", sequence: 1, expiresAt: 100, remove: false), now: 1)
    try await store.deferMaterialization(linkID: link.id, canonicalID: "a", sequence: 1, permissionBlocked: true, retryAt: nil)
    #expect(try await store.pending(linkID: link.id, now: 2).isEmpty)
    #expect(try await store.pending(linkID: link.id, now: 2, permissionRecovered: true).count == 1)
    #expect(try await store.pending(linkID: link.id, now: 100).count == 1)
}

@Test func cancelledPairingCannotBeResurrectedAndCompletionIsAtomic() async throws {
    let (directory, _, _, store) = try fixture()
    defer { try? FileManager.default.removeItem(at: directory) }
    let link = peer()
    try await store.savePendingPair(id: link.id, plaintext: Data("pending".utf8), expiresAt: 100)
    try await store.clearPendingPair(id: link.id)
    await #expect(throws: DeliveryStoreError.invalidTransition) {
        try await store.updatePendingPair(id: link.id, plaintext: Data("late response".utf8), expiresAt: 100)
    }
    await #expect(throws: DeliveryStoreError.invalidTransition) {
        try await store.finishPairing(link, pendingID: link.id)
    }
    #expect(try await store.peers().isEmpty)
    try await store.savePendingPair(id: link.id, plaintext: Data("confirmed".utf8), expiresAt: 100)
    try await store.finishPairing(link, pendingID: link.id)
    #expect(try await store.peers() == [link])
    #expect(try await store.pendingPair() == nil)
}
