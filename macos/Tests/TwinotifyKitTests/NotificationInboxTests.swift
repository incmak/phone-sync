import Foundation
import Testing
@testable import TwinotifyKit

@MainActor private final class InboxSystem: NotificationPlatform {
    var allowed = false
    var posts = 0
    var removals = 0
    func post(_ presentation: NotificationPresentation) async throws -> PlatformOutcome {
        posts += 1
        return allowed ? .applied : .permissionBlocked
    }
    func remove(identifier: String) async { removals += 1 }
}

@Test @MainActor func inboxRoutingDoesNotNeedPermissionAndDoesNotReplayOnDestinationChange() async throws {
    let system = InboxSystem(), router = NotificationRouter(system: system, destination: .menuBar)
    let presentation = NotificationPresentation(linkGeneration: "a", canonicalID: "n", sequence: 1, title: "Title", body: "Text")
    #expect(try await router.post(presentation) == .applied)
    #expect(system.posts == 0)
    router.destination = .notificationCenter
    #expect(system.posts == 0) // Changing the setting must not replay existing entries.
    #expect(try await router.post(presentation) == .permissionBlocked)
    system.allowed = true
    #expect(try await router.post(presentation) == .applied)
    router.destination = .menuBar
    await router.remove(identifier: presentation.identifier)
    #expect(system.removals == 1) // Phone cancellation also clears an earlier center entry.
}

@Test func inboxPersistsAndPaginatesCanonicalStateWithPeerIsolation() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("tw-inbox-" + UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let path = directory.appendingPathComponent("state.sqlite").path, vault = MemoryVault()
    let store = try DurableStore(path: path, vault: vault)
    func peer() -> PeerLink {
        PeerLink(deviceID: UUID().uuidString, pairID: UUID().uuidString, relayURL: "https://relay.example.test",
            encryptionKey: Data(repeating: 1, count: 32), signingKey: Data(repeating: 2, count: 32))
    }
    let a = peer(), b = peer()
    try await store.addPeer(a); try await store.addPeer(b)
    func insert(_ link: PeerLink, _ canonical: String, _ sequence: Int64 = 1,
                remove: Bool = false, applied: Bool = true, expires: Int64 = 100_000) async throws {
        let state = DesiredRecord(canonicalID: canonical, sequence: sequence, expiresAt: expires, remove: remove,
                                  title: "Private inbox title", body: "Private inbox text")
        _ = try await store.stage(linkID: link.id, messageID: UUID().uuidString, digest: String(repeating: "a", count: 64),
                                  expiresAt: expires, desired: state, now: sequence)
        if applied { try await store.markDesiredApplied(linkID: link.id, state: state) }
    }
    for index in 0..<17 { try await insert(a, "n-\(index)") }
    try await insert(b, "n-0") // Same canonical key on another link is a distinct entry.
    try await insert(a, "cancelled", remove: true)
    try await insert(a, "blocked", applied: false)
    try await insert(a, "call:expired", expires: 2)
    let reopened = try DurableStore(path: path, vault: vault)
    let entries = try await reopened.notificationInbox(now: 3)
    #expect(entries.count == 18)
    #expect(Set(entries.map(\.id)).count == 18)
    let pages = (0..<3).map { InboxPage(items: entries, index: $0) }
    #expect(pages.map { $0.items.count } == [8, 8, 2])
    #expect(pages.flatMap(\.items).map(\.id) == entries.map(\.id))
    #expect(InboxPage(items: entries, index: -1).index == 0)
    #expect(InboxPage(items: entries, index: 999).index == 2)
    #expect(InboxPage(items: [], index: 9).first == 0)
    #expect(InboxPage(items: [], index: 9).index == 0)
    try await insert(a, "n-0", 2)
    #expect(try await store.notificationInbox(now: 3).count == 18)
    try await insert(a, "n-0", 3, remove: true)
    #expect(try await store.notificationInbox(now: 4).count == 17)
    try await store.beginRemoval(a.id)
    #expect(try await store.notificationInbox(now: 4).map(\.linkID) == [b.id])
    let sql = try SQLiteConnection(path: path)
    let blobs = try sql.execute("SELECT content FROM desired").map { try $0.blob("content") }
    #expect(blobs.allSatisfy { $0.range(of: Data("Private inbox text".utf8)) == nil })
    #expect(try sql.execute("PRAGMA user_version").first?["user_version"] == .integer(2))
}

@Test func inboxSourceAppIsOptionalForPreviouslySavedNotifications() throws {
    let legacy = Data(#"{"canonicalID":"legacy","sequence":1,"expiresAt":100000,"remove":false,"active":true,"title":"Saved title","subtitle":"","body":"Saved text"}"#.utf8)
    let decoded = try JSONDecoder().decode(DesiredRecord.self, from: legacy)
    #expect(decoded.sourceApp == nil)
    #expect(decoded.locallyDismissed == nil)
    #expect(decoded.title == "Saved title")
    #expect(decoded.body == "Saved text")
}


@Test func localInboxClearSurvivesRestartAndRetriesWithoutChangingDeliveryState() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("tw-clear-" + UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let path = directory.appendingPathComponent("state.sqlite").path, vault = MemoryVault()
    let store = try DurableStore(path: path, vault: vault)
    let peer = PeerLink(deviceID: UUID().uuidString, pairID: UUID().uuidString, relayURL: "https://relay.example.test",
        encryptionKey: Data(repeating: 1, count: 32), signingKey: Data(repeating: 2, count: 32))
    try await store.addPeer(peer)
    func insert(_ canonical: String, _ sequence: Int64, body: String = "Text", remove: Bool = false) async throws {
        let state = DesiredRecord(canonicalID: canonical, sequence: sequence, expiresAt: 100_000, remove: remove,
                                  title: "Title", body: body)
        _ = try await store.stage(linkID: peer.id, messageID: UUID().uuidString, digest: String(repeating: "a", count: 64),
                                  expiresAt: 100_000, desired: state, now: sequence)
        try await store.markDesiredApplied(linkID: peer.id, state: state)
    }
    try await insert("a", 1); try await insert("b", 1)
    let original = try await store.notificationInbox(now: 2)
    let a = try #require(original.first { $0.canonicalID == "a" })
    let countsBefore = try await store.counts(linkID: peer.id)
    #expect(try await store.setInboxDismissed([a], dismissed: true).count == 1)
    #expect(try await store.notificationInbox(now: 2).count == 1)
    let reopened = try DurableStore(path: path, vault: vault)
    #expect(try await reopened.notificationInbox(now: 2).count == 1)
    let countsAfter = try await store.counts(linkID: peer.id)
    #expect(countsBefore.pending == countsAfter.pending)
    #expect(try await store.sendable(linkID: peer.id, now: 2).isEmpty)
    try await insert("a", 2)
    #expect(try await store.notificationInbox(now: 3).count == 1)
    #expect(try await store.setInboxDismissed([a], dismissed: false).count == 1)
    #expect(try await store.notificationInbox(now: 3).count == 2)
    #expect(try await store.desiredWork(linkID: peer.id, now: 3, permissionRecovered: true).isEmpty)
    // The old row cannot clear a newer sequence, even when its text is unchanged.
    #expect(try await store.setInboxDismissed([a], dismissed: true).isEmpty)
    let all = try await store.notificationInbox(now: 3)
    #expect(try await store.setInboxDismissed(all, dismissed: true).count == 2)
    #expect(try await store.notificationInbox(now: 3).isEmpty)
    try await insert("a", 3, body: "Updated text")
    try await insert("b", 2, remove: true)
    #expect(try await store.notificationInbox(now: 4).map(\.canonicalID) == ["a"])
    #expect(try await store.setInboxDismissed(all, dismissed: false).isEmpty)
    #expect(try await store.notificationInbox(now: 4).count == 1)
}
