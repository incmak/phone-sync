import Foundation
import Testing
@testable import TwinotifyKit

@Test func pendingResponderRemainsCompatibleAndCannotConfirmBeforeHello() async throws {
    let store = try DurableStore(path: ":memory:", vault: MemoryVault())
    let qr = try PairingQR(json: JSONValue.object([
        "relay_url": .string("https://relay.example.test"), "device_id": .string("phone"),
        "enc_pubkey": .string(store.identity.encryptionKey.base64EncodedString()),
        "sign_pubkey": .string(store.identity.signingKey.base64EncodedString()),
        "pair_token": .string(UUID().uuidString)]).encoded())
    let client = PairingClient(store: store)
    let pending = try await client.begin(qr: qr, now: 100)
    var object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(pending)) as! [String: Any]
    object.removeValue(forKey: "initiator"); object.removeValue(forKey: "phone")
    let legacy = try JSONDecoder().decode(PendingPairing.self, from: JSONSerialization.data(withJSONObject: object))
    #expect(!legacy.isInitiator)
    #expect(legacy.peer?.deviceID == "phone")
    #expect(try PairingQR(json: qr.wireJSON).fingerprint == qr.fingerprint)
    let stableQR = try qr.wireJSON
    for _ in 0..<100 {
        // Re-decoding models a periodic reload of the persisted pending session.
        let reloaded = try JSONDecoder().decode(PairingQR.self, from: JSONEncoder().encode(qr))
        #expect(try reloaded.wireJSON == stableQR)
    }
    try await client.cancel()
    var waiting = pending
    waiting.initiator = true
    try await store.savePendingPair(id: waiting.id, plaintext: JSONEncoder().encode(waiting), expiresAt: waiting.expiresAt)
    await #expect(throws: PairingError.self) { try await client.confirmFingerprint(id: waiting.id, now: 101) }
    await #expect(throws: PairingError.self) { try await client.waitForPhone(now: { 300_100 }) }
    try await client.cancel()
    #expect(try await client.pending() == nil)
    #expect(try await store.peers().isEmpty)
}

@Test(.enabled(if: ProcessInfo.processInfo.environment["TWINOTIFY_TEST_RELAY"] != nil), .timeLimit(.minutes(1)))
func liveMacInitiatorResumesAndRequiresBothConfirmations() async throws {
    let base = ProcessInfo.processInfo.environment["TWINOTIFY_TEST_RELAY"]!
    let a = try DurableStore(path: ":memory:", vault: MemoryVault())
    let b = try DurableStore(path: ":memory:", vault: MemoryVault())
    let first = PairingClient(store: a, allowDebugLoopback: true)
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    let initial = try await first.initiate(relayURL: base, now: now)
    #expect(initial.isInitiator)
    #expect(initial.peer == nil)
    await #expect(throws: PairingError.self) { try await first.confirmFingerprint(id: initial.id, now: now) }
    // Recreate clients at both durable boundaries, retaining the same token and identities.
    let initiator = PairingClient(store: a, allowDebugLoopback: true)
    let responder = PairingClient(store: b, allowDebugLoopback: true)
    let scanned = try PairingQR(json: initial.qr.wireJSON, allowDebugLoopback: true)
    let phone = try await responder.begin(qr: scanned, now: now)
    try await responder.confirmFingerprint(id: phone.id, now: now)
    let responding = Task { try await responder.complete() }
    defer { responding.cancel() }
    let hello = try await initiator.waitForPhone()
    #expect(hello.phone?.deviceID == b.identity.deviceID)
    #expect(try await a.peers().isEmpty)
    #expect(try await b.peers().isEmpty)
    await #expect(throws: PairingError.self) { try await initiator.complete() }
    try await initiator.confirmFingerprint(id: initial.id, now: now)
    let resumed = PairingClient(store: a, allowDebugLoopback: true)
    let aLink = try await resumed.complete()
    let bLink = try await responding.value
    #expect(aLink.deviceID == b.identity.deviceID)
    #expect(aLink.pairID == bLink.pairID)
    #expect(try await resumed.pending() == nil)
    #expect(try await responder.pending() == nil)
    // Cancelling a waiting subscription must settle before a new attempt can start.
    _ = try await resumed.initiate(relayURL: base, now: now)
    let waiting = Task { try await resumed.waitForPhone() }
    await Task.yield()
    try await resumed.cancel()
    waiting.cancel()
    _ = await waiting.result
    #expect(try await resumed.pending() == nil)
    #expect(try await a.peers().count == 1)
}
