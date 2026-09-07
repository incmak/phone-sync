import Foundation

public enum PairingError: Error { case expired, fingerprintRequired, invalidSignature, httpStatus(Int), invalidResponse }

public struct PairingQR: Codable, Sendable {
    public let relayURL: String
    public let deviceID: String
    public let encryptionKey: Data
    public let signingKey: Data
    public let token: String

    public init(json: Data, allowDebugLoopback: Bool = false) throws {
        guard json.count <= 8_192 else { throw ProtocolError.invalidPacket }
        let object = try JSONValue.parse(json)
        guard let fields = object.object,
              Set(fields.keys) == Set(["relay_url", "device_id", "enc_pubkey", "sign_pubkey", "pair_token"]),
              let relay = object["relay_url"]?.string, let id = object["device_id"]?.string, (1...128).contains(id.utf8.count), !id.contains("\0"),
              let enc = object["enc_pubkey"]?.string.flatMap({ Data(base64Encoded: $0) }), enc.count == 32,
              let sign = object["sign_pubkey"]?.string.flatMap({ Data(base64Encoded: $0) }), sign.count == 32,
              let token = object["pair_token"]?.string, (16...128).contains(token.utf8.count),
              token.utf8.allSatisfy({ (45...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 95 }) else {
            throw ProtocolError.invalidPacket
        }
        _ = try RelayEndpoint(relay, allowDebugLoopback: allowDebugLoopback)
        relayURL = relay; deviceID = id; encryptionKey = enc; signingKey = sign; self.token = token
    }
    public var wireJSON: Data { get throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(JSONValue.object(["relay_url": .string(relayURL), "device_id": .string(deviceID),
            "enc_pubkey": .string(encryptionKey.base64EncodedString()), "sign_pubkey": .string(signingKey.base64EncodedString()),
            "pair_token": .string(token)]))
    } }
    public var fingerprint: String { get throws { try WireCrypto.fingerprint(encryptionKey: encryptionKey, signingKey: signingKey) } }
}

public struct PendingPairing: Codable, Sendable {
    public let id: String
    public let qr: PairingQR
    public let expiresAt: Int64
    public var fingerprintConfirmed: Bool
    public var initiatorSignature: Data?
    public var pairID: String?
    // Optional fields preserve pending responder sessions written by earlier builds.
    public var initiator: Bool? = nil
    public var phone: PairingQR? = nil
    public var isInitiator: Bool { initiator == true }
    public var peer: PairingQR? { isInitiator ? phone : qr }
}

/// Does not follow redirects carrying signed proofs to another endpoint.
final class RelayHTTPDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

public actor PairingClient {
    private let store: DurableStore
    private let session: URLSession
    private let debugLoopback: Bool
    private var socket: URLSessionWebSocketTask?
    private var operation: UUID?
    private var cancelledOperation: UUID?
    private var cancellationWaiters: [CheckedContinuation<Void, Never>] = []
    private var cancelling = 0
    public init(store: DurableStore, allowDebugLoopback: Bool = false) {
        self.store = store; debugLoopback = allowDebugLoopback
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 300
        session = URLSession(configuration: configuration, delegate: RelayHTTPDelegate(), delegateQueue: nil)
    }
    public func pending() async throws -> PendingPairing? {
        guard let bytes = try await store.pendingPair() else { return nil }
        return try JSONDecoder().decode(PendingPairing.self, from: bytes)
    }
    public func begin(qr: PairingQR, now: Int64) async throws -> PendingPairing {
        let token = try startOperation()
        defer { finishOperation(token) }
        guard qr.deviceID != store.identity.deviceID, now >= 0, now <= Int64.max - 300_000 else { throw ProtocolError.invalidPacket }
        if let current = try await pending() { return current }
        guard try await store.peers().count < 2 else { throw StorageError.capacityExceeded }
        let pending = PendingPairing(id: UUID().uuidString.lowercased(), qr: qr, expiresAt: now + 300_000,
                                     fingerprintConfirmed: false, initiatorSignature: nil, pairID: nil)
        try checkOperation(token)
        try await store.savePendingPair(id: pending.id, plaintext: JSONEncoder().encode(pending), expiresAt: pending.expiresAt)
        return pending
    }
    public func initiate(relayURL: String, now: Int64) async throws -> PendingPairing {
        let token = try startOperation()
        defer { finishOperation(token) }
        guard now >= 0, now <= Int64.max - 300_000 else { throw ProtocolError.invalidPacket }
        if let current = try await pending() { return current }
        guard try await store.peers().count < 2 else { throw StorageError.capacityExceeded }
        let endpoint = try RelayEndpoint(relayURL, allowDebugLoopback: debugLoopback)
        let qr = try PairingQR(json: JSONValue.object([
            "relay_url": .string(endpoint.http.absoluteString), "device_id": .string(store.identity.deviceID),
            "enc_pubkey": .string(store.identity.encryptionKey.base64EncodedString()),
            "sign_pubkey": .string(store.identity.signingKey.base64EncodedString()),
            "pair_token": .string(UUID().uuidString.lowercased() + UUID().uuidString.lowercased())]).encoded(), allowDebugLoopback: debugLoopback)
        let pending = PendingPairing(id: UUID().uuidString.lowercased(), qr: qr, expiresAt: now + 300_000,
            fingerprintConfirmed: false, initiator: true)
        try checkOperation(token)
        try await store.savePendingPair(id: pending.id, plaintext: JSONEncoder().encode(pending), expiresAt: pending.expiresAt)
        _ = try await post(endpoint, path: "pair/init", fields: hello(pending))
        try checkOperation(token)
        return pending
    }

    public func waitForPhone(now: @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) async throws -> PendingPairing {
        let token = try startOperation()
        defer { finishOperation(token) }
        guard var pending = try await pending(), pending.isInitiator, now() < pending.expiresAt else { throw PairingError.expired }
        if pending.phone != nil { return pending }
        let endpoint = try RelayEndpoint(pending.qr.relayURL, allowDebugLoopback: debugLoopback)
        // Retrying init is idempotent and lets a saved request survive an interrupted HTTP response.
        _ = try await post(endpoint, path: "pair/init", fields: hello(pending))
        let frame = try await waitForFrame(pending, endpoint: endpoint, role: "A", type: "peer.hello", now: now)
        guard let deviceID = frame["device_id"], let enc = frame["enc_pubkey"], let sign = frame["sign_pubkey"] else {
            throw PairingError.invalidResponse
        }
        let phone = try PairingQR(json: JSONValue.object(["relay_url": .string(pending.qr.relayURL),
            "pair_token": .string(pending.qr.token), "device_id": deviceID, "enc_pubkey": enc, "sign_pubkey": sign]).encoded(), allowDebugLoopback: debugLoopback)
        guard phone.deviceID != store.identity.deviceID else { throw PairingError.invalidResponse }
        try checkOperation(token)
        pending.phone = phone
        try await save(pending)
        return pending
    }

    public func confirmFingerprint(id: String, now: Int64) async throws {
        let token = try startOperation()
        defer { finishOperation(token) }
        guard var pending = try await pending(), pending.id == id, now < pending.expiresAt else { throw PairingError.expired }
        guard pending.peer != nil else { throw PairingError.fingerprintRequired }
        pending.fingerprintConfirmed = true
        try checkOperation(token)
        try await save(pending)
    }
    public func cancel() async throws {
        cancelling += 1
        defer { cancelling -= 1 }
        cancelledOperation = operation
        socket?.cancel(with: .goingAway, reason: nil); socket = nil
        if operation != nil {
            await withCheckedContinuation { cancellationWaiters.append($0) }
        }
        if let pending = try await pending() { try await store.clearPendingPair(id: pending.id) }
    }
    public func complete(now: @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) async throws -> PeerLink {
        let token = try startOperation()
        defer { finishOperation(token) }
        guard var pending = try await pending() else { throw PairingError.invalidResponse }
        guard pending.pairID != nil || now() < pending.expiresAt else { throw PairingError.expired }
        try checkOperation(token)
        guard pending.fingerprintConfirmed else { throw PairingError.fingerprintRequired }
        let endpoint = try RelayEndpoint(pending.qr.relayURL, allowDebugLoopback: debugLoopback)
        if pending.isInitiator {
            guard let phone = pending.phone else { throw PairingError.fingerprintRequired }
            if pending.pairID == nil {
                let transcript = try PairingTranscript.initiator(token: pending.qr.token,
                    aEncryptionKey: store.identity.encryptionKey, aSigningKey: store.identity.signingKey,
                    bEncryptionKey: phone.encryptionKey, bSigningKey: phone.signingKey)
                let signature = try await store.sign(transcript)
                try checkOperation(token)
                _ = try await post(endpoint, path: "pair/send_sig", fields: ["pair_token": .string(pending.qr.token),
                    "confirmation_sig": .string(signature.base64EncodedString())])
                let frame = try await waitForFrame(pending, endpoint: endpoint, role: "A", type: "pair.complete", now: now)
                guard let pairID = frame["pair_id"]?.string, UUID(uuidString: pairID) != nil else { throw PairingError.invalidResponse }
                try checkOperation(token)
                pending.pairID = pairID
                try await save(pending)
            }
        } else if pending.pairID == nil {
            if pending.initiatorSignature == nil {
                _ = try await post(endpoint, path: "pair/hello", fields: hello(pending))
                let signature = try await waitForSignature(pending, endpoint: endpoint, now: now)
                let transcript = try PairingTranscript.initiator(token: pending.qr.token,
                    aEncryptionKey: pending.qr.encryptionKey, aSigningKey: pending.qr.signingKey,
                    bEncryptionKey: store.identity.encryptionKey, bSigningKey: store.identity.signingKey)
                guard WireCrypto.verify(signature, message: transcript, publicKey: pending.qr.signingKey) else {
                    throw PairingError.invalidSignature
                }
                try checkOperation(token)
                pending.initiatorSignature = signature
                try await save(pending)
            }
            guard now() < pending.expiresAt, let signature = pending.initiatorSignature else { throw PairingError.expired }
            let transcript = try PairingTranscript.initiator(token: pending.qr.token,
                aEncryptionKey: pending.qr.encryptionKey, aSigningKey: pending.qr.signingKey,
                bEncryptionKey: store.identity.encryptionKey, bSigningKey: store.identity.signingKey)
            let responder = try PairingTranscript.responder(initiatorTranscript: transcript, initiatorSignature: signature)
            var fields = hello(pending)
            fields["confirmation_sig"] = .string(signature.base64EncodedString())
            fields["responder_confirmation_sig"] = .string(try await store.sign(responder).base64EncodedString())
            let result = try await post(endpoint, path: "pair/complete", fields: fields)
            guard let pairID = result["pair_id"]?.string, UUID(uuidString: pairID) != nil else { throw PairingError.invalidResponse }
            try checkOperation(token)
            pending.pairID = pairID
            try await save(pending)
        }
        guard let peer = pending.peer else { throw PairingError.invalidResponse }
        let link = PeerLink(id: pending.id, deviceID: peer.deviceID, pairID: pending.pairID!, relayURL: peer.relayURL,
                            encryptionKey: peer.encryptionKey, signingKey: peer.signingKey)
        try checkOperation(token)
        try await store.finishPairing(link, pendingID: pending.id)
        return link
    }
    private func hello(_ pending: PendingPairing) -> [String: JSONValue] {
        ["pair_token": .string(pending.qr.token), "device_id": .string(store.identity.deviceID),
         "enc_pubkey": .string(store.identity.encryptionKey.base64EncodedString()),
         "sign_pubkey": .string(store.identity.signingKey.base64EncodedString()), "display_name": .string("Mac")]
    }
    private func save(_ pending: PendingPairing) async throws {
        try await store.updatePendingPair(id: pending.id, plaintext: JSONEncoder().encode(pending), expiresAt: pending.expiresAt)
    }
    private func startOperation() throws -> UUID {
        guard operation == nil, cancelling == 0 else { throw DeliveryStoreError.invalidTransition }
        let token = UUID(); operation = token; return token
    }
    private func finishOperation(_ token: UUID) {
        guard operation == token else { return }
        operation = nil; cancelledOperation = nil
        let waiters = cancellationWaiters; cancellationWaiters = []
        for waiter in waiters { waiter.resume() }
    }
    private func checkOperation(_ token: UUID) throws {
        try Task.checkCancellation()
        guard operation == token, cancelledOperation != token else { throw CancellationError() }
    }
    private func post(_ endpoint: RelayEndpoint, path: String, fields: [String: JSONValue]) async throws -> JSONValue {
        var request = URLRequest(url: try endpoint.url(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONValue.object(fields).encoded()
        let (stream, response) = try await session.bytes(for: request)
        guard let response = response as? HTTPURLResponse else { throw PairingError.invalidResponse }
        guard (200..<300).contains(response.statusCode) else { throw PairingError.httpStatus(response.statusCode) }
        var data = Data()
        for try await byte in stream {
            guard data.count < 16_384 else { throw PairingError.invalidResponse }
            data.append(byte)
        }
        if data.isEmpty { return .object([:]) }
        return try JSONValue.parse(data)
    }
    private func waitForSignature(_ pending: PendingPairing, endpoint: RelayEndpoint,
                                  now: @Sendable () -> Int64) async throws -> Data {
        let frame = try await waitForFrame(pending, endpoint: endpoint, role: "B", type: "pair.sig", now: now)
        guard let signature = frame["confirmation_sig"]?.string.flatMap({ Data(base64Encoded: $0) }), signature.count == 64 else {
            throw PairingError.invalidResponse
        }
        return signature
    }
    private func waitForFrame(_ pending: PendingPairing, endpoint: RelayEndpoint, role: String, type: String,
                              now: @Sendable () -> Int64) async throws -> JSONValue {
        try Task.checkCancellation()
        guard cancelling == 0, cancelledOperation == nil else { throw CancellationError() }
        guard now() < pending.expiresAt else { throw PairingError.expired }
        var request = URLRequest(url: try endpoint.url("pair/notify", websocket: true, query: [
            URLQueryItem(name: "token", value: pending.qr.token), URLQueryItem(name: "role", value: role)]))
        let proof = PairingTranscript.notify(token: pending.qr.token, role: role, deviceID: store.identity.deviceID)
        request.setValue(store.identity.deviceID, forHTTPHeaderField: "X-Twinotify-Device-ID")
        request.setValue(try await store.sign(proof).base64EncodedString(), forHTTPHeaderField: "X-Twinotify-Pair-Signature")
        request.timeoutInterval = max(1, Double(pending.expiresAt - now()) / 1000)
        try Task.checkCancellation()
        guard cancelling == 0, cancelledOperation == nil else { throw CancellationError() }
        let connection = session.webSocketTask(with: request)
        connection.maximumMessageSize = 16_384
        socket = connection; connection.resume()
        let remaining = max(1, pending.expiresAt - now())
        let expiry = Task {
            try? await Task.sleep(for: .milliseconds(remaining))
            if !Task.isCancelled { connection.cancel(with: .goingAway, reason: nil) }
        }
        defer { expiry.cancel(); connection.cancel(with: .normalClosure, reason: nil); socket = nil }
        return try await withTaskCancellationHandler {
            while now() < pending.expiresAt {
                let message = try await connection.receive()
                let data: Data
                switch message { case .string(let text): data = Data(text.utf8); case .data(let bytes): data = bytes; @unknown default: throw PairingError.invalidResponse }
                let frame = try JSONValue.parse(data)
                if frame["pair_token"]?.string == pending.qr.token, frame["type"]?.string == type {
                    guard now() < pending.expiresAt else { throw PairingError.expired }
                    return frame
                }
            }
            throw PairingError.expired
        } onCancel: { connection.cancel(with: .goingAway, reason: nil) }
    }
}
