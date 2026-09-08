import Foundation
import Testing
@testable import TwinotifyKit

private struct ActionFixture {
    let source: DurableStore, mac: DurableStore
    let phone: PeerLink, receiver: PeerLink
    let action = NotificationAction(id: UUID().uuidString.lowercased(), title: "Reply", semantic: 1, reply: true, replyLabel: "Your reply")
    init() async throws {
        source = try DurableStore(path: ":memory:", vault: MemoryVault())
        mac = try DurableStore(path: ":memory:", vault: MemoryVault())
        phone = PeerLink(deviceID: source.identity.deviceID, pairID: UUID().uuidString,
            relayURL: "https://relay.example.test", encryptionKey: source.identity.encryptionKey, signingKey: source.identity.signingKey)
        receiver = PeerLink(deviceID: mac.identity.deviceID, pairID: UUID().uuidString,
            relayURL: "https://relay.example.test", encryptionKey: mac.identity.encryptionKey, signingKey: mac.identity.signingKey)
        try await source.addPeer(receiver); try await mac.addPeer(phone)
    }
    func notification(sequence: Int64 = 1) async throws -> InboxItem {
        let desired = DesiredRecord(canonicalID: "test-action", sequence: sequence, expiresAt: 900_000, remove: false,
            title: "Synthetic message", body: "Test body", actions: [action])
        _ = try await mac.stage(linkID: phone.id, messageID: UUID().uuidString.lowercased(), digest: String(repeating: "a", count: 64),
            expiresAt: 900_000, desired: desired, now: sequence)
        try await mac.markDesiredApplied(linkID: phone.id, state: desired)
        return try #require(try await mac.notificationInbox(now: 2).first)
    }
}

@Test func repliesAreEncryptedAndConcurrentClicksReuseOneInvocation() async throws {
    let f = try await ActionFixture(), item = try await f.notification()
    let results = try await withThrowingTaskGroup(of: ActionAttempt.self) { group in
        for _ in 0..<8 {
            group.addTask { try await f.mac.invokeNotificationAction(item: item, actionID: f.action.id, reply: "Private synthetic reply 😀", now: 3) }
        }
        var results: [ActionAttempt] = []
        for try await result in group { results.append(result) }
        return results
    }
    #expect(Set(results.map(\.id)).count == 1)
    let rows = try await f.mac.sendable(linkID: f.phone.id, now: 4)
    #expect(rows.count == 1)
    let row = try #require(rows.first)
    #expect(row.bytes.range(of: Data("Private synthetic reply".utf8)) == nil)
    let decoded = try await ProtocolCodec().authenticate(row.bytes, peer: f.receiver, store: f.source).inner
    #expect(decoded.type == "notif.action.invoke")
    #expect(decoded.payload["notification_sequence"] == .integer(1))
    #expect(decoded.payload["action_id"] == .string(f.action.id))
    #expect(decoded.payload["reply_text"] == .string("Private synthetic reply 😀"))
    #expect(decoded.expiresAt - decoded.createdAt == 120_000)
    try await f.mac.receiptAccepted(linkID: f.phone.id, messageID: row.messageID, now: 4)
    #expect(try await f.mac.counts(linkID: f.phone.id).outbound == 1)
    let result = InnerEvent(messageID: UUID().uuidString.lowercased(), originDevice: f.phone.deviceID, type: "notif.action.result",
        canonicalID: nil, sequence: nil, createdAt: 5, expiresAt: 600_005,
        payload: .object(["invocation_id": .string(results[0].id), "canon_id": .string(item.canonicalID), "status": .string("dispatched")]))
    try await f.mac.commitActionResult(linkID: f.phone.id, event: result, digest: String(repeating: "b", count: 64), now: 6)
    #expect(try await f.mac.counts(linkID: f.phone.id).outbound == 0)
    #expect(try await f.mac.actionAttempts(now: 6).first?.status == "dispatched")
    let repeated = try await f.mac.invokeNotificationAction(item: item, actionID: f.action.id, reply: "Do not resend", now: 7)
    #expect(repeated.id == results[0].id)
    #expect(try await f.mac.counts(linkID: f.phone.id).outbound == 0)
}

@Test func staleClearedAndOversizedReplyTargetsCannotQueueCommands() async throws {
    let f = try await ActionFixture(), stale = try await f.notification()
    await #expect(throws: ActionError.invalidReply) {
        try await f.mac.invokeNotificationAction(item: stale, actionID: f.action.id, reply: String(repeating: "😀", count: 1025), now: 3)
    }
    await #expect(throws: ActionError.invalidReply) { try await f.mac.invokeNotificationAction(item: stale, actionID: f.action.id, reply: " \n ", now: 3) }
    let current = try await f.notification(sequence: 2)
    await #expect(throws: ActionError.unavailable) { try await f.mac.invokeNotificationAction(item: stale, actionID: f.action.id, reply: "Test", now: 3) }
    _ = try await f.mac.setInboxDismissed([current], dismissed: true)
    await #expect(throws: ActionError.unavailable) { try await f.mac.invokeNotificationAction(item: current, actionID: f.action.id, reply: "Test", now: 3) }
    #expect(try await f.mac.counts(linkID: f.phone.id).outbound == 0)
}

@Test func actionTimeoutNeverCreatesAnotherInvocationAndLateResultCanResolveIt() async throws {
    let f = try await ActionFixture(), item = try await f.notification()
    let attempt = try await f.mac.invokeNotificationAction(item: item, actionID: f.action.id, reply: "Test", now: 3)
    #expect(try await f.mac.actionAttempts(now: 120_004).first?.status == "timed_out")
    #expect(try await f.mac.counts(linkID: f.phone.id).outbound == 0)
    #expect(try await f.mac.invokeNotificationAction(item: item, actionID: f.action.id, reply: "Test", now: 120_005).id == attempt.id)
    let result = InnerEvent(messageID: UUID().uuidString.lowercased(), originDevice: f.phone.deviceID, type: "notif.action.result",
        canonicalID: nil, sequence: nil, createdAt: 100, expiresAt: 600_100,
        payload: .object(["invocation_id": .string(attempt.id), "canon_id": .string(item.canonicalID), "status": .string("dispatched")]))
    try await f.mac.commitActionResult(linkID: f.phone.id, event: result, digest: String(repeating: "b", count: 64), now: 130_000)
    #expect(try await f.mac.actionAttempts(now: 130_001).first?.status == "dispatched")
}
