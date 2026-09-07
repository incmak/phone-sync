import Foundation
import Testing
@testable import TwinotifyKit

private func snapshotPeer() -> PeerLink {
    PeerLink(deviceID: UUID().uuidString, pairID: UUID().uuidString, relayURL: "https://relay.example.test",
             encryptionKey: Data(repeating: 1, count: 32), signingKey: Data(repeating: 2, count: 32))
}
private func control(_ type: String, peer: PeerLink, payload: [String: JSONValue], canonicalID: String? = nil, sequence: Int64? = nil) -> InnerEvent {
    InnerEvent(messageID: UUID().uuidString, originDevice: peer.deviceID, type: type, canonicalID: canonicalID,
               sequence: sequence, createdAt: 1, expiresAt: 100_000, payload: .object(payload))
}

@Test func emptySnapshotPreservesNewerLiveStateAndOtherOrigins() async throws {
    let store = try DurableStore(path: ":memory:", vault: MemoryVault()), a = snapshotPeer(), b = snapshotPeer()
    try await store.addPeer(a); try await store.addPeer(b)
    let digest = String(repeating: "a", count: 64)
    for (peer, canonicalID, sequence) in [(a, "newer", Int64(1)), (a, "missing", Int64(1)), (b, "missing", Int64(9))] {
        _ = try await store.stage(linkID: peer.id, messageID: UUID().uuidString, digest: digest, expiresAt: 100_000,
            desired: DesiredRecord(canonicalID: canonicalID, sequence: sequence, expiresAt: 100_000, remove: false), now: 1)
    }
    let begin = control("state.snapshot.begin", peer: a, payload: ["snapshot_id": .string("snapshot-a"), "item_count": .integer(0)])
    try await store.processSnapshot(linkID: a.id, event: begin, digest: digest, now: 2)
    _ = try await store.stage(linkID: a.id, messageID: UUID().uuidString, digest: digest, expiresAt: 100_000,
        desired: DesiredRecord(canonicalID: "newer", sequence: 2, expiresAt: 100_000, remove: false), now: 3)
    let end = control("state.snapshot.end", peer: a, payload: ["snapshot_id": .string("snapshot-a"), "digest": .string(RawEnvelope.digest(Data()))])
    try await store.processSnapshot(linkID: a.id, event: end, digest: digest, now: 4)
    #expect(try await store.desired(linkID: a.id, canonicalID: "newer")?.sequence == 2)
    #expect(try await store.desired(linkID: a.id, canonicalID: "newer")?.remove == false)
    #expect(try await store.desired(linkID: a.id, canonicalID: "missing")?.remove == true)
    #expect(try await store.desired(linkID: b.id, canonicalID: "missing")?.remove == false)
    #expect(try await store.readyAcks(linkID: a.id).count == 2)
}

@Test func snapshotItemsStayInvisibleUntilValidEndAndSurviveReopen() async throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tw-snapshot-" + UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: folder) }
    let path = folder.appendingPathComponent("state.sqlite").path, vault = MemoryVault()
    let store = try DurableStore(path: path, vault: vault), peer = snapshotPeer()
    try await store.addPeer(peer)
    let digest = String(repeating: "b", count: 64)
    let begin = control("state.snapshot.begin", peer: peer, payload: ["snapshot_id": .string("snapshot"), "item_count": .integer(1)])
    try await store.processSnapshot(linkID: peer.id, event: begin, digest: digest, now: 1)
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    var payload = try JSONValue.parse(Data(contentsOf: root.appendingPathComponent("proto/fixtures/v2-valid/notif-post-legacy-valid.json"))).object!
    payload["canon_id"] = .string("a")
    let item = control("state.snapshot.item", peer: peer,
        payload: ["snapshot_id": .string("snapshot"), "notification_payload": .object(payload)], canonicalID: "a", sequence: 1)
    try await store.processSnapshot(linkID: peer.id, event: item, digest: digest, now: 2)
    #expect(try await store.desired(linkID: peer.id, canonicalID: "a") == nil)
    let restarted = try DurableStore(path: path, vault: vault)
    let wrong = control("state.snapshot.end", peer: peer, payload: ["snapshot_id": .string("snapshot"), "digest": .string(digest)])
    await #expect(throws: SnapshotError.self) { try await restarted.processSnapshot(linkID: peer.id, event: wrong, digest: digest, now: 3) }
    #expect(try await restarted.desired(linkID: peer.id, canonicalID: "a") == nil)
    let correct = RawEnvelope.digest(Data("a\u{0}1\u{0}ACTIVE".utf8))
    let end = control("state.snapshot.end", peer: peer, payload: ["snapshot_id": .string("snapshot"), "digest": .string(correct)])
    try await restarted.processSnapshot(linkID: peer.id, event: end, digest: digest, now: 4)
    #expect(try await restarted.originSummary(linkID: peer.id).digest == correct)
    #expect(try await restarted.desiredWork(linkID: peer.id, now: 4, permissionRecovered: false).count == 1)
    #expect(try await restarted.sendable(linkID: peer.id, now: 4).isEmpty) // Direct ack controls have no receipt recursion.
}
