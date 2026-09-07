import Foundation
import Testing
@testable import TwinotifyKit

@Test func sharedProtocolFixtureManifest() throws {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("proto/fixtures")
    let manifest = try JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: root.appendingPathComponent("manifest.json")))
    let validator = try SchemaValidator(), codec = try ProtocolCodec()
    let innerTypes: Set<String> = ["peer_receipt_inner", "call_state", "call_control_invoke", "call_control_result",
        "notif_action_invoke", "notif_action_result", "lan_bootstrap_inner", "relay_attach_inner", "peer_probe_inner", "notif_cancel_inner"]
    for fixture in manifest["fixtures"]!.array! {
        let name = fixture["file"]!.string!, kind = fixture["type"]!.string!, scope = fixture["scope"]!.string!
        let expected = fixture["valid"]?.boolean ?? false
        let data = try Data(contentsOf: root.appendingPathComponent(name))
        var passed = false
        do {
            if scope == "server", kind == "relay_control" {
                try validator.validate(JSONValue.parse(data), schema: "relay-control")
            } else if scope == "cross_layer", kind == "notif_post_payload" {
                try validator.validate(JSONValue.parse(data), schema: "notif-post")
            } else if scope == "cross_layer", innerTypes.contains(kind) {
                _ = try codec.decodeInner(data)
            } else if scope == "cross_layer", kind == "outer_inner_pair" {
                let pair = try JSONValue.parse(data), outer = pair["outer"]!, inner = pair["inner"]!
                try validator.validate(outer, schema: "envelope-encrypted")
                let event = try codec.decodeInner(inner.encoded())
                guard outer["msg_id"]?.string == event.messageID,
                      outer["origin_device"]?.string == event.originDevice,
                      outer["created_at"]?.integer == event.createdAt else { throw ProtocolError.identityMismatch }
            } else { Issue.record("Unrecognized fixture dispatch: \(scope)/\(kind)"); continue }
            passed = true
        } catch {}
        #expect(passed == expected, "\(name)")
    }
}

@Test func endpointPolicyRejectsCredentialsFragmentsAndRemoteCleartext() throws {
    for invalid in ["http://example.com", "ws://192.168.1.2", "https://u:p@example.com", "https://example.com?token=x",
                    "https://example.com#fragment", "ftp://example.com"] {
        #expect(throws: ProtocolError.invalidPacket) { try RelayEndpoint(invalid, allowDebugLoopback: true) }
    }
    #expect(try RelayEndpoint("wss://relay.example.com/base").url("ws", websocket: true).absoluteString == "wss://relay.example.com/base/ws")
    #if DEBUG || TWINOTIFY_E2E
    #expect(try RelayEndpoint("http://127.0.0.1:8080", allowDebugLoopback: true).http.scheme == "http")
    #else
    #expect(throws: ProtocolError.invalidPacket) { try RelayEndpoint("http://127.0.0.1:8080", allowDebugLoopback: true) }
    #endif
    #expect(throws: ProtocolError.invalidPacket) { try RelayEndpoint("http://127.0.0.1:8080") }
}

@Test func jwtUsesFreshIdentifiersAndBindsPairSelector() async throws {
    let store = try DurableStore(path: ":memory:", vault: MemoryVault())
    let pair = UUID().uuidString.lowercased()
    let a = try await store.mintJWT(pairID: pair, nowSeconds: 100)
    let b = try await store.mintJWT(pairID: pair, nowSeconds: 100)
    #expect(a != b)
    let parts = a.split(separator: ".").map(String.init)
    func decode(_ string: String) -> Data {
        let base = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        return Data(base64Encoded: base + String(repeating: "=", count: (4 - base.count % 4) % 4))!
    }
    let payload = try JSONValue.parse(decode(parts[1]))
    #expect(payload["pair_id"]?.string == pair)
    #expect(payload["exp"]?.integer == 160)
    #expect(WireCrypto.verify(decode(parts[2]), message: Data((parts[0] + "." + parts[1]).utf8), publicKey: store.identity.signingKey))
}

@Test func androidDeviceIDsAreAcceptedWithoutChangingTheirSpelling() async throws {
    let device = "dev-" + UUID().uuidString.lowercased()
    let key = Data(repeating: 1, count: 32)
    let json = try JSONValue.object(["relay_url": .string("https://relay.example.com"),
        "device_id": .string(device), "enc_pubkey": .string(key.base64EncodedString()),
        "sign_pubkey": .string(key.base64EncodedString()), "pair_token": .string("pt-" + UUID().uuidString)]).encoded()
    let qr = try PairingQR(json: json)
    #expect(qr.deviceID == device)
    let store = try DurableStore(path: ":memory:", vault: MemoryVault())
    let peer = PeerLink(id: UUID().uuidString, deviceID: qr.deviceID, pairID: UUID().uuidString,
        relayURL: qr.relayURL, encryptionKey: qr.encryptionKey, signingKey: qr.signingKey)
    try await store.addPeer(peer)
    #expect(try await store.peers().first?.deviceID == device)
}
