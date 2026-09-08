import Foundation
import Testing
@testable import TwinotifyKit

@MainActor private final class FakeNotifications: NotificationPlatform {
    var allowed = true
    var failAfterSubmission = false
    var visible: [String: Int64] = [:]
    var alerts = 0
    func post(_ presentation: NotificationPresentation) async throws -> PlatformOutcome {
        guard allowed else { return .permissionBlocked }
        if visible[presentation.identifier] == presentation.sequence { return .applied }
        visible[presentation.identifier] = presentation.sequence; alerts += 1
        if failAfterSubmission { failAfterSubmission = false; throw CocoaError(.fileWriteUnknown) }
        return .applied
    }
    func remove(identifier: String) async { visible.removeValue(forKey: identifier) }
}

private struct ReceiverFixture {
    let source: DurableStore
    let target: DurableStore
    let link: PeerLink
    init() async throws {
        source = try DurableStore(path: ":memory:", vault: MemoryVault())
        target = try DurableStore(path: ":memory:", vault: MemoryVault())
        link = PeerLink(deviceID: source.identity.deviceID, pairID: UUID().uuidString, relayURL: "https://relay.example.test",
                        encryptionKey: source.identity.encryptionKey, signingKey: source.identity.signingKey)
        try await target.addPeer(link)
    }
    func notification(sequence: Int64 = 1, type: String = "notif.post", expires: Int64 = 100_000,
                      visibility: String = "private", text: String = "Long text") async throws -> Data {
        var payload: [String: JSONValue] = ["v": .integer(1), "type": .string(type), "canon_id": .string("canon-a"),
            "app_name": .string("Example"), "package_name": .string("test.example"), "id": .integer(1), "tag": .null,
            "title": .string("Title"), "text": .string("Text"), "sub_text": .null, "big_text": .string(text),
            "visibility": .string(visibility), "is_group_summary": .bool(false), "is_ongoing": .bool(false),
            "is_clearable": .bool(true), "small_icon_png_b64": .null, "large_icon_png_b64": .null, "ts": .integer(1)]
        if type == "notif.cancel" { payload = ["reason": .integer(2), "removed_at": .integer(1)] }
        let id = UUID().uuidString.lowercased()
        let inner = try JSONValue.object(["v": .integer(2), "msg_id": .string(id), "origin_device": .string(source.identity.deviceID),
            "type": .string(type), "canon_id": .string("canon-a"), "sequence": .integer(sequence), "created_at": .integer(1),
            "expires_at": .integer(expires), "payload": .object(payload)]).encoded()
        let encrypted = try await source.encrypt(inner, peerPublicKey: target.identity.encryptionKey)
        return try JSONValue.object(["v": .integer(2), "type": .string("enc"), "msg_id": .string(id),
            "origin_device": .string(source.identity.deviceID), "created_at": .integer(1),
            "nonce": .string(encrypted.nonce.base64EncodedString()), "ciphertext": .string(encrypted.ciphertext.base64EncodedString())]).encoded()
    }
}

@Test @MainActor func permissionRecoveryDefersReceiptAndTerminalDuplicateStaysLocallyDismissed() async throws {
    let f = try await ReceiverFixture(), platform = FakeNotifications()
    platform.allowed = false
    let receiver = try ReliableReceiver(store: f.target, peer: f.link, platform: platform)
    let bytes = try await f.notification()
    try await receiver.receive(bytes, now: 2)
    #expect(platform.visible.isEmpty)
    #expect(try await f.target.sendable(linkID: f.link.id, now: 3).isEmpty)
    #expect(try await f.target.readyAcks(linkID: f.link.id).isEmpty)
    platform.allowed = true
    try await receiver.resume(now: 4, permissionRecovered: true)
    #expect(platform.alerts == 1)
    let receipt = try #require(try await f.target.sendable(linkID: f.link.id, now: 4).first)
    #expect(try await f.target.readyAcks(linkID: f.link.id).isEmpty)
    try await f.target.receiptAccepted(linkID: f.link.id, messageID: receipt.messageID)
    #expect(try await f.target.readyAcks(linkID: f.link.id).count == 1)
    platform.visible.removeAll() // User dismisses locally; no source cancellation is emitted.
    try await receiver.receive(bytes, now: 5)
    #expect(platform.alerts == 1)
    #expect(platform.visible.isEmpty)
    #expect(try await f.target.sendable(linkID: f.link.id, now: 5).first?.bytes == receipt.bytes)
}

@Test @MainActor func uncertainPlatformSubmissionRecoversWithoutDuplicateEntry() async throws {
    let f = try await ReceiverFixture(), platform = FakeNotifications()
    platform.failAfterSubmission = true
    let receiver = try ReliableReceiver(store: f.target, peer: f.link, platform: platform)
    let bytes = try await f.notification()
    try await receiver.receive(bytes, now: 2)
    #expect(platform.alerts == 1)
    #expect(try await f.target.sendable(linkID: f.link.id, now: 3).isEmpty)
    let restarted = try ReliableReceiver(store: f.target, peer: f.link, platform: platform)
    try await restarted.resume(now: 5_002)
    #expect(platform.alerts == 1)
    #expect(platform.visible.count == 1)
    #expect(try await f.target.sendable(linkID: f.link.id, now: 5_003).count == 1)
}

@Test @MainActor func updatesFilteringCancellationAndExpiredPermissionBacklogConverge() async throws {
    let f = try await ReceiverFixture(), platform = FakeNotifications()
    let receiver = try ReliableReceiver(store: f.target, peer: f.link, platform: platform)
    try await receiver.receive(f.notification(visibility: "secret"), now: 2)
    #expect(platform.visible.isEmpty)
    try await receiver.receive(f.notification(sequence: 2, type: "notif.update"), now: 3)
    #expect(platform.alerts == 1)
    #expect(platform.visible.values.first == 2)
    try await receiver.receive(f.notification(sequence: 3, type: "notif.cancel"), now: 4)
    #expect(platform.visible.isEmpty)
    platform.allowed = false
    let expired = try await f.notification(sequence: 4, expires: 10)
    try await receiver.receive(expired, now: 5)
    platform.allowed = true
    try await receiver.resume(now: 11, permissionRecovered: true)
    #expect(platform.visible.isEmpty)
    #expect(platform.alerts == 1)
    let id = try JSONValue.parse(expired)["msg_id"]!.string!
    #expect(try await f.target.received(linkID: f.link.id, messageID: id)?.outcome == .expired)
    var tampered = try JSONValue.parse(try await f.notification(sequence: 5)).object!
    tampered["origin_device"] = .string(UUID().uuidString)
    await #expect(throws: ProtocolError.identityMismatch) { try await receiver.receive(JSONValue.object(tampered).encoded(), now: 12) }
    #expect(try await f.target.desired(linkID: f.link.id, canonicalID: "canon-a")?.sequence == 4)
}

@Test @MainActor func unchangedHigherSequenceDoesNotAlertAgainAcrossReceiverRestart() async throws {
    let f = try await ReceiverFixture(), platform = FakeNotifications()
    let receiver = try ReliableReceiver(store: f.target, peer: f.link, platform: platform)
    try await receiver.receive(f.notification(), now: 2)
    #expect(platform.alerts == 1)
    platform.visible.removeAll() // A local dismissal must survive an unchanged Android update.
    let restarted = try ReliableReceiver(store: f.target, peer: f.link, platform: platform)
    try await restarted.receive(f.notification(sequence: 2, type: "notif.update"), now: 3)
    #expect(platform.alerts == 1)
    #expect(platform.visible.isEmpty)
    #expect(try await f.target.materializedSequence(linkID: f.link.id, canonicalID: "canon-a") == 2)
    try await restarted.receive(f.notification(sequence: 3, type: "notif.update", text: "A new message"), now: 4)
    #expect(platform.alerts == 2)
    #expect(platform.visible.values.first == 3)
    try await restarted.receive(f.notification(sequence: 4, type: "notif.cancel"), now: 5)
    try await restarted.receive(f.notification(sequence: 5, text: "A new message"), now: 6)
    #expect(platform.alerts == 3) // A genuinely new lifecycle still alerts.
}

@Test @MainActor func inboxOnlyDeliveryCommitsReceiptsAndPermissionBacklogWithoutOSAlerts() async throws {
    let f = try await ReceiverFixture(), system = FakeNotifications()
    system.allowed = false
    let router = NotificationRouter(system: system, destination: .menuBar)
    let receiver = try ReliableReceiver(store: f.target, peer: f.link, platform: router)
    try await receiver.receive(f.notification(), now: 2)
    #expect(system.alerts == 0)
    #expect(try await f.target.counts(linkID: f.link.id).pending == 0)
    #expect(try await f.target.sendable(linkID: f.link.id, now: 2).count == 1)
    #expect(try await f.target.notificationInbox(now: 2).count == 1)
    #expect(try await f.target.notificationInbox(now: 2).first?.presentation.sourceApp == "Example")
    router.destination = .notificationCenter
    try await receiver.receive(f.notification(sequence: 2, text: "Changed"), now: 3)
    #expect(try await f.target.counts(linkID: f.link.id).pending == 1)
    router.destination = .menuBar
    try await receiver.resume(now: 4, permissionRecovered: true)
    #expect(try await f.target.counts(linkID: f.link.id).pending == 0)
    #expect(try await f.target.notificationInbox(now: 4).first?.presentation.body == "Changed")
    system.allowed = true
    router.destination = .notificationCenter
    try await receiver.resume(now: 5, permissionRecovered: true)
    #expect(system.alerts == 0) // Enabling system alerts doesn't replay the inbox.
}
