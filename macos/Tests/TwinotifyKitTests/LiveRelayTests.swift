import Foundation
import Testing
@testable import TwinotifyKit

private actor LiveProgress {
    var ticks = 0
    var digest: String?
    func tick() { ticks += 1 }
    func received(_ value: String) { digest = value }
}

@Test(.enabled(if: ProcessInfo.processInfo.environment["TWINOTIFY_TEST_RELAY"] != nil), .timeLimit(.minutes(3)))
func livePairingExactBytesAndTwoHeartbeats() async throws {
    let base = ProcessInfo.processInfo.environment["TWINOTIFY_TEST_RELAY"]!
    let endpoint = try RelayEndpoint(base, allowDebugLoopback: true)
    let a = try DurableStore(path: ":memory:", vault: MemoryVault())
    let b = try DurableStore(path: ":memory:", vault: MemoryVault())
    let token = "pt-" + UUID().uuidString.lowercased()
    let http = URLSession(configuration: .ephemeral)
    func post(_ path: String, _ fields: [String: JSONValue]) async throws {
        var request = URLRequest(url: try endpoint.url(path)); request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONValue.object(fields).encoded()
        let (_, response) = try await http.data(for: request)
        #expect((response as? HTTPURLResponse)?.statusCode == 200)
    }
    try await post("pair/init", ["pair_token": .string(token), "device_id": .string(a.identity.deviceID),
        "enc_pubkey": .string(a.identity.encryptionKey.base64EncodedString()),
        "sign_pubkey": .string(a.identity.signingKey.base64EncodedString())])
    let qr = try PairingQR(json: JSONValue.object(["relay_url": .string(base), "pair_token": .string(token),
        "device_id": .string(a.identity.deviceID), "enc_pubkey": .string(a.identity.encryptionKey.base64EncodedString()),
        "sign_pubkey": .string(a.identity.signingKey.base64EncodedString())]).encoded(), allowDebugLoopback: true)
    let pairing = PairingClient(store: b, allowDebugLoopback: true)
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    let pending = try await pairing.begin(qr: qr, now: now)
    await #expect(throws: PairingError.self) { try await pairing.complete() }
    try await pairing.confirmFingerprint(id: pending.id, now: now)
    var notifyRequest = URLRequest(url: try endpoint.url("pair/notify", websocket: true,
        query: [URLQueryItem(name: "token", value: token), URLQueryItem(name: "role", value: "A")]))
    notifyRequest.setValue(a.identity.deviceID, forHTTPHeaderField: "X-Twinotify-Device-ID")
    notifyRequest.setValue(try await a.sign(PairingTranscript.notify(token: token, role: "A", deviceID: a.identity.deviceID)).base64EncodedString(),
                           forHTTPHeaderField: "X-Twinotify-Pair-Signature")
    let notify = http.webSocketTask(with: notifyRequest); notify.resume()
    defer { notify.cancel(with: .goingAway, reason: nil) }
    let initiator = Task {
        let frame = try await notify.receive()
        guard case .string(let text) = frame else { throw PairingError.invalidResponse }
        let hello = try JSONValue.parse(Data(text.utf8))
        #expect(hello["device_id"]?.string == b.identity.deviceID)
        let transcript = try PairingTranscript.initiator(token: token, aEncryptionKey: a.identity.encryptionKey,
            aSigningKey: a.identity.signingKey, bEncryptionKey: b.identity.encryptionKey, bSigningKey: b.identity.signingKey)
        try await post("pair/send_sig", ["pair_token": .string(token),
            "confirmation_sig": .string(try await a.sign(transcript).base64EncodedString())])
    }
    let bLink = try await pairing.complete()
    try await initiator.value
    #expect(try await pairing.pending() == nil)
    let aLink = PeerLink(deviceID: b.identity.deviceID, pairID: bLink.pairID, relayURL: base,
                         encryptionKey: b.identity.encryptionKey, signingKey: b.identity.signingKey)
    try await a.addPeer(aLink)
    var aRequest = URLRequest(url: try endpoint.url("ws", websocket: true))
    aRequest.setValue("Bearer " + (try await a.mintJWT(nowSeconds: Int64(Date().timeIntervalSince1970))), forHTTPHeaderField: "Authorization")
    let aSocket = http.webSocketTask(with: aRequest); aSocket.resume()
    defer { aSocket.cancel(with: .goingAway, reason: nil) }
    try await aSocket.send(.string(#"{"v":2,"type":"relay.hello","protocols":[2],"app_version":"swift-test"}"#))
    _ = try await aSocket.receive()
    let drain = Task { while !Task.isCancelled { _ = try await aSocket.receive() } }
    defer { drain.cancel() }
    let progress = LiveProgress(), codec = try ProtocolCodec()
    let transport = try RelaySession(peer: bLink, store: b)
    let running = Task {
        try await transport.run(allowDebugLoopback: true, received: { bytes in
            let event = try await codec.authenticate(bytes, peer: bLink, store: b)
            await progress.received(event.digest)
        }, tick: { await progress.tick() })
    }
    defer { running.cancel() }
    let sealed = try await codec.seal(type: "peer.receipt", payload: .object([
        "acked_msg_id": .string(UUID().uuidString.lowercased()), "envelope_sha256": .string(String(repeating: "a", count: 64)),
        "status": .string("applied")]), peer: aLink, store: a, now: now)
    // Whitespace and escaped JSON field spelling must survive real relay custody.
    let original = String(decoding: sealed.bytes, as: UTF8.self)
    let altered = Data(original.replacingOccurrences(of: "\"origin_device\"", with: "\"origin_\\u0064evice\"")
        .replacingOccurrences(of: "{", with: "{ \n ").utf8)
    try await Task.sleep(for: .seconds(2))
    try await aSocket.send(.string(String(decoding: RawEnvelope.put(envelope: altered), as: UTF8.self)))
    try await Task.sleep(for: .seconds(113))
    #expect(await progress.ticks >= 110)
    #expect(await progress.digest == RawEnvelope.digest(altered))
    await transport.cancel()
    running.cancel()
    _ = await running.result
    aSocket.cancel(with: .goingAway, reason: nil)
    _ = await drain.result
}
