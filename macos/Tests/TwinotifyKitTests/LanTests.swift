import CryptoKit
import Foundation
import Security
import Testing
@testable import TwinotifyKit

@MainActor private final class LanTestNotifications: NotificationPlatform {
    var posts = 0
    func post(_ presentation: NotificationPresentation) async throws -> PlatformOutcome { posts += 1; return .applied }
    func remove(identifier: String) async {}
}

@Test func lanFramesPreserveExactEnvelopeBytesAndRejectAmbiguity() throws {
    let bytes = Data(" { \"v\" : 2, \"text\":\"Unicode 😀 \\\" \\\\ \" }\n".utf8)
    for frame: LanFrame in [.put(bytes), .hello(Data(repeating: 1, count: 32)), .helloAck(Data()),
        .accepted("a1111111-1111-4111-a111-111111111111", String(repeating: "a", count: 64)), .ping(0), .pong(Int64.max), .close("done")] {
        #expect(try LanFrame.decode(frame.encode()) == frame)
    }
    func framed(_ body: String) -> Data { Data.be32(UInt32(body.utf8.count)) + Data(body.utf8) }
    for body in [#"{"v":1,"v":1,"type":"lan.ping","token":0}"#,
                 #"{"v":1,"type":"lan.ping","token":-1}"#,
                 #"{"v":1,"type":"lan.ping","token":0,"extra":1}"#,
                 #"{"v":1,"type":"lan.hello","data":"YQ"}"#,
                 #"{"v":2,"type":"lan.ping","token":0}"#] {
        #expect(throws: (any Error).self) { try LanFrame.decode(framed(body)) }
    }
    #expect(throws: LanError.self) { try LanFrame.decode(Data.be32(UInt32.max)) }
    #expect(throws: LanError.self) { try LanFrame.decode(LanFrame.ping(1).encode() + Data([0])) }
}

@Test func lanBootstrapIsSymmetricAndTrustSurvivesReopen() async throws {
    let a = try DurableStore(path: ":memory:", vault: MemoryVault())
    let b = try DurableStore(path: ":memory:", vault: MemoryVault())
    func link(_ store: DurableStore) -> PeerLink { PeerLink(deviceID: store.identity.deviceID, pairID: UUID().uuidString,
        relayURL: "https://relay.example.test", encryptionKey: store.identity.encryptionKey, signingKey: store.identity.signingKey) }
    let ab = link(b), ba = link(a)
    try await a.addPeer(ab); try await b.addPeer(ba)
    let first = try await a.lanMaterial(peer: ab), second = try await b.lanMaterial(peer: ba)
    #expect(first.secret == second.secret)
    #expect(first.contextDigest == second.contextDigest)
    let pin = Data(repeating: 5, count: 32)
    try await a.commitLanBinding(peer: ab, pin: pin, contextDigest: first.contextDigest)
    try await a.commitLanBinding(peer: ab, pin: pin, contextDigest: first.contextDigest)
    await #expect(throws: LanError.bindingConflict) { try await a.commitLanBinding(peer: ab, pin: Data(repeating: 6, count: 32), contextDigest: first.contextDigest) }
    await #expect(throws: LanError.authentication) { try await a.commitLanBinding(peer: ab, pin: pin, contextDigest: Data(repeating: 0, count: 32)) }
    #expect(try await a.lanBinding(linkID: ab.id)?.peerPin == pin)
    let ad = try LanCrypto.advertisement(secret: first.secret, deviceID: b.identity.deviceID, day: 20_000)
    #expect(ad.count == 22)
    #expect(try LanCrypto.advertisement(secret: first.secret, deviceID: b.identity.deviceID, day: 20_001) != ad)
    #expect(try LanCrypto.advertisement(secret: first.secret, deviceID: a.identity.deviceID, day: 20_000) != ad)
    try await a.beginRemoval(ab.id)
    await #expect(throws: (any Error).self) { try await a.lanBinding(linkID: ab.id) }
}

@Test func signedLanChallengeBindsPeerRoleAndExporter() async throws {
    let a = try DurableStore(path: ":memory:", vault: MemoryVault()), b = try DurableStore(path: ":memory:", vault: MemoryVault())
    let context = LanHandshakeContext(initiator: a.identity.deviceID, acceptor: b.identity.deviceID,
        initiatorNonce: Data(repeating: 1, count: 32), acceptorNonce: Data(repeating: 2, count: 32), tlsContext: Data(repeating: 3, count: 32))
    let signature = try await b.sign(context.signedMessage(role: 2))
    let hello = try context.hello(role: 2, signature: signature)
    try context.verify(hello, role: 2, publicKey: b.identity.signingKey)
    #expect(throws: LanError.self) { try context.verify(hello, role: 1, publicKey: b.identity.signingKey) }
    #expect(throws: LanError.self) { try context.verify(hello, role: 2, publicKey: a.identity.signingKey) }
    let changed = LanHandshakeContext(initiator: a.identity.deviceID, acceptor: b.identity.deviceID,
        initiatorNonce: context.initiatorNonce, acceptorNonce: context.acceptorNonce, tlsContext: Data(repeating: 4, count: 32))
    #expect(throws: LanError.self) { try changed.verify(hello, role: 2, publicKey: b.identity.signingKey) }
    #expect(throws: LanError.self) { try context.verify(hello + Data([0]), role: 2, publicKey: b.identity.signingKey) }
}

@Test func localTLSCertificateHasTheJavaCompatibleSPKIPin() throws {
    var error: Unmanaged<CFError>?
    let key = try #require(SecKeyCreateRandomKey([kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
        kSecAttrKeySizeInBits as String: 256] as CFDictionary, &error))
    let certificate = try LanIdentity.certificate(key: key)
    let point = try #require(SecKeyCopyExternalRepresentation(SecKeyCopyPublicKey(key)!, &error)) as Data
    let prefix = Data([0x30,0x59,0x30,0x13,0x06,0x07,0x2A,0x86,0x48,0xCE,0x3D,0x02,0x01,0x06,0x08,0x2A,0x86,0x48,0xCE,0x3D,0x03,0x01,0x07,0x03,0x42,0x00])
    #expect(try LanIdentity.spkiPin(certificate) == Data(SHA256.hash(data: prefix + point)))
    var trust: SecTrust?
    #expect(SecTrustCreateWithCertificates(certificate, SecPolicyCreateBasicX509(), &trust) == errSecSuccess)
    SecTrustSetAnchorCertificates(trust!, [certificate] as CFArray)
    #expect(SecTrustEvaluateWithError(trust!, nil))
}

@Test func receiptBackedCommandSurvivesCustodyAndWrongDigest() async throws {
    let store = try DurableStore(path: ":memory:", vault: MemoryVault())
    let peer = PeerLink(deviceID: UUID().uuidString, pairID: UUID().uuidString, relayURL: "https://relay.example.test",
        encryptionKey: Data(repeating: 1, count: 32), signingKey: Data(repeating: 2, count: 32))
    try await store.addPeer(peer)
    let bytes = Data("immutable ciphertext".utf8), id = UUID().uuidString.lowercased()
    let row = StoredEnvelope(messageID: id, bytes: bytes, digest: RawEnvelope.digest(bytes), expiresAt: 100_000)
    try await store.enqueueControl(linkID: peer.id, envelope: row, type: "lan.bootstrap", requiresReceipt: true)
    await #expect(throws: DeliveryStoreError.digestConflict) { try await store.directAccepted(linkID: peer.id, messageID: id, digest: String(repeating: "a", count: 64), now: 1) }
    try await store.directAccepted(linkID: peer.id, messageID: id, digest: row.digest, now: 1)
    #expect(try await store.counts(linkID: peer.id).outbound == 1)
    #expect(try await store.sendable(linkID: peer.id, now: 2).isEmpty)
    #expect(try await store.sendable(linkID: peer.id, now: 30_002).first?.bytes == bytes)
    let receipt = InnerEvent(messageID: UUID().uuidString.lowercased(), originDevice: peer.deviceID, type: "peer.receipt",
        canonicalID: nil, sequence: nil, createdAt: 2, expiresAt: 100_000,
        payload: .object(["acked_msg_id": .string(id), "envelope_sha256": .string(row.digest), "status": .string("applied")]))
    try await store.commitPeerReceipt(linkID: peer.id, event: receipt, digest: String(repeating: "b", count: 64), now: 2)
    #expect(try await store.counts(linkID: peer.id).outbound == 0)
}

@Test(.enabled(if: ProcessInfo.processInfo.environment["TWINOTIFY_LAN_INTEROP_DIR"] != nil))
func androidMacPinnedTLSExporterAndSignedHandshake() async throws {
    let directory = URL(fileURLWithPath: ProcessInfo.processInfo.environment["TWINOTIFY_LAN_INTEROP_DIR"]!)
    let namespace = "co.twinotify.mac.tests.lan." + directory.lastPathComponent
    let identity = try LanIdentity(namespace: namespace)
    defer {
        let label = namespace + ".lan.tls.v1"
        SecItemDelete([kSecClass as String: kSecClassCertificate, kSecAttrLabel as String: label,
            kSecUseDataProtectionKeychain as String: false] as CFDictionary)
        SecItemDelete([kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: Data(label.utf8),
            kSecUseDataProtectionKeychain as String: false] as CFDictionary)
    }
    let store = try DurableStore(path: ":memory:", vault: MemoryVault())
    let publicData = try JSONSerialization.data(withJSONObject: ["device_id": store.identity.deviceID,
        "signing_key": store.identity.signingKey.base64EncodedString(), "tls_pin": identity.pin.hex,
        "encryption_key": store.identity.encryptionKey.base64EncodedString()])
    try publicData.write(to: directory.appendingPathComponent("mac.json"), options: .atomic)
    let androidURL = directory.appendingPathComponent("android.json")
    let started = ContinuousClock.now
    while !FileManager.default.fileExists(atPath: androidURL.path) {
        guard ContinuousClock.now - started < .seconds(70) else { throw LanError.timeout }
        try await Task.sleep(for: .milliseconds(200))
    }
    let fields = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: androidURL)) as? [String: Any])
    let peer = PeerLink(deviceID: fields["device_id"] as! String, pairID: UUID().uuidString,
        relayURL: "https://relay.example.test", encryptionKey: Data(base64Encoded: fields["encryption_key"] as! String)!,
        signingKey: Data(base64Encoded: fields["signing_key"] as! String)!)
    try await store.addPeer(peer)
    #expect(try await store.lanMaterial(peer: peer).contextDigest.base64EncodedString() == fields["binding_context"] as? String)
    let pinHex = fields["tls_pin"] as! String
    let pin = Data(stride(from: 0, to: 64, by: 2).map { offset -> UInt8 in
        let start = pinHex.index(pinHex.startIndex, offsetBy: offset)
        return UInt8(pinHex[start..<pinHex.index(start, offsetBy: 2)], radix: 16)!
    })
    let connection = try LanConnection(endpoint: .hostPort(host: "127.0.0.1", port: .init(rawValue: UInt16(fields["host_port"] as! Int))!), identity: identity, peerPin: pin)
    defer { connection.cancel() }
    try await connection.authenticate(peer: peer, store: store)
    try await connection.send(.ping(42))
    #expect(try await connection.receive() == .pong(42))
    let bytes = Data(" {\"unicode\":\"😀\", \"escaped\":\"a\\nb\"} \n".utf8)
    try await connection.send(.put(bytes))
    #expect(try await connection.receive() == .put(bytes))
    let platform = await LanTestNotifications()
    let receiver = try await ReliableReceiver(store: store, peer: peer, platform: platform)
    do {
        try await LanSession(connection: connection, peer: peer, store: store).run(received: { bytes in
            try await receiver.receive(bytes, now: Int64(Date().timeIntervalSince1970 * 1000))
        }, tick: {})
        Issue.record("LAN fixture should close the session after its delivery assertions")
    } catch LanError.closed {}
    #expect(await platform.posts == 1)
    #expect(try await store.notificationInbox(now: Int64(Date().timeIntervalSince1970 * 1000)).count == 1)
    #expect(try await store.counts(linkID: peer.id).outbound == 0)
}


@Test func versionTwoMigrationPreservesIdentityNonceAndPendingDelivery() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("tw-v2-migration-" + UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let path = directory.appendingPathComponent("state.sqlite").path, vault = MemoryVault()
    let old = try DurableStore(path: path, vault: vault)
    let peer = PeerLink(deviceID: UUID().uuidString, pairID: UUID().uuidString, relayURL: "https://relay.example.test",
        encryptionKey: Data(repeating: 1, count: 32), signingKey: Data(repeating: 2, count: 32))
    try await old.addPeer(peer)
    let nonce = try await old.nextNonce()
    let state = DesiredRecord(canonicalID: "kept", sequence: 2, expiresAt: 100_000, remove: false, body: "Encrypted saved body")
    _ = try await old.stage(linkID: peer.id, messageID: UUID().uuidString.lowercased(), digest: String(repeating: "a", count: 64),
        expiresAt: 100_000, desired: state, now: 1)
    let sql = try SQLiteConnection(path: path)
    // Strip only the additive v3 schema to build a populated pre-upgrade file.
    try sql.execute("DROP TABLE lan_binding")
    try sql.execute("DROP TABLE action_invocation")
    for column in ["requires_receipt", "event_type", "custody_at"] { try sql.execute("ALTER TABLE outbox DROP COLUMN \(column)") }
    try sql.execute("PRAGMA user_version=2")
    let migrated = try DurableStore(path: path, vault: vault)
    #expect(migrated.identity == old.identity)
    #expect(try await migrated.peers() == [peer])
    #expect(try await migrated.desired(linkID: peer.id, canonicalID: "kept") == state)
    #expect(try await migrated.counts(linkID: peer.id).pending == 1)
    let next = try await migrated.nextNonce()
    #expect(next.prefix(16) == nonce.prefix(16))
    #expect(next.suffix(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) } == 2)
    #expect(try sql.execute("PRAGMA user_version").first?["user_version"] == .integer(3))
}
