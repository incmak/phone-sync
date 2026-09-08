import Foundation
@preconcurrency import Network
import Security
import Synchronization

/// A finite callback wait, including cancellation before registration and late
/// callbacks. No callback can resume a continuation twice.
final class NetworkCompletion<Value: Sendable>: Sendable {
    private struct State {
        var continuation: CheckedContinuation<Value, any Error>?
        var result: Result<Value, any Error>?
    }
    private let state = Mutex(State())
    func install(_ continuation: CheckedContinuation<Value, any Error>) {
        let result = state.withLock { state -> Result<Value, any Error>? in
            if let result = state.result { return result }
            state.continuation = continuation
            return nil
        }
        if let result { continuation.resume(with: result) }
    }
    func finish(_ result: Result<Value, any Error>) {
        let continuation = state.withLock { state -> CheckedContinuation<Value, any Error>? in
            guard state.result == nil else { return nil }
            state.result = result
            defer { state.continuation = nil }
            return state.continuation
        }
        continuation?.resume(with: result)
    }
}

func lanTimeout<T: Sendable>(_ seconds: Int = 10, operation: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask { try await Task.sleep(for: .seconds(seconds)); throw LanError.timeout }
        defer { group.cancelAll() }
        return try await group.next()!
    }
}

public actor LanConnection {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "co.twinotify.mac.lan.connection")
    private var reading = false
    private var writes = 0
    private var authenticated = false

    public init(endpoint: NWEndpoint, interface: NWInterface? = nil, identity: LanIdentity, peerPin: Data) throws {
        guard peerPin.count == 32, let localIdentity = sec_identity_create(identity.identity) else { throw LanError.authentication }
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, localIdentity)
        sec_protocol_options_add_tls_application_protocol(tls.securityProtocolOptions, LanCrypto.alpn)
        sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, true)
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate],
                  let certificate = chain.first, let pin = try? LanIdentity.spkiPin(certificate) else { complete(false); return }
            complete(pin == peerPin)
        }, queue)
        let parameters = NWParameters(tls: tls)
        parameters.requiredInterface = interface
        parameters.includePeerToPeer = false
        connection = NWConnection(to: endpoint, using: parameters)
    }
    public nonisolated func cancel() { connection.cancel() }

    public func authenticate(peer: PeerLink, store: DurableStore) async throws {
        guard !authenticated else { throw LanError.authentication }
        try await lanTimeout { [self] in try await startTLS() }
        guard let metadata = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata,
              let negotiated = sec_protocol_metadata_get_negotiated_protocol(metadata.securityProtocolMetadata),
              String(cString: negotiated) == LanCrypto.alpn else { cancel(); throw LanError.authentication }
        let secret = LanCrypto.exporterLabel.withCString {
            sec_protocol_metadata_create_secret(metadata.securityProtocolMetadata, LanCrypto.exporterLabel.utf8.count, $0, 32)
        }
        guard let secret else { cancel(); throw LanError.authentication }
        let contextBytes = Data(secret as DispatchData)
        var nonce = Data(count: 32)
        guard nonce.withUnsafeMutableBytes({ SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!) }) == errSecSuccess else {
            cancel(); throw LanError.authentication
        }
        do {
            try await write(.hello(nonce))
            guard case .helloAck(let peerNonce) = try await read(), peerNonce.count == 32 else { throw LanError.authentication }
            let context = LanHandshakeContext(initiator: store.identity.deviceID, acceptor: peer.deviceID,
                initiatorNonce: nonce, acceptorNonce: peerNonce, tlsContext: contextBytes)
            let signature = try await store.sign(context.signedMessage(role: 1))
            try await write(.hello(context.hello(role: 1, signature: signature)))
            guard case .helloAck(let response) = try await read() else { throw LanError.authentication }
            try context.verify(response, role: 2, publicKey: peer.signingKey)
            authenticated = true
        } catch { cancel(); throw error }
    }
    private func startTLS() async throws {
        let completion = NetworkCompletion<Void>()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                completion.install(continuation)
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready: completion.finish(.success(()))
                    case .failed: completion.finish(.failure(LanError.unavailable))
                    case .cancelled: completion.finish(.failure(LanError.closed))
                    default: break
                    }
                }
                if Task.isCancelled { completion.finish(.failure(CancellationError())); return }
                connection.start(queue: queue)
            }
        } onCancel: { [connection] in connection.cancel(); completion.finish(.failure(CancellationError())) }
    }
    public func send(_ frame: LanFrame) async throws {
        guard authenticated else { throw LanError.authentication }
        switch frame { case .hello, .helloAck: throw LanError.authentication; default: break }
        try await write(frame)
    }
    public func receive() async throws -> LanFrame {
        guard authenticated else { throw LanError.authentication }
        let frame = try await read()
        switch frame { case .hello, .helloAck: cancel(); throw LanError.authentication; default: return frame }
    }
    private func write(_ frame: LanFrame) async throws {
        guard writes < 4 else { cancel(); throw LanError.oversized }
        writes += 1
        defer { writes -= 1 }
        let bytes = try frame.encode(), connection = connection
        try await lanTimeout {
            let completion = NetworkCompletion<Void>()
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    completion.install(continuation)
                    guard !Task.isCancelled else { completion.finish(.failure(CancellationError())); return }
                    connection.send(content: bytes, completion: .contentProcessed { error in
                        if error != nil { completion.finish(.failure(LanError.closed)) }
                        else { completion.finish(.success(())) }
                    })
                }
            } onCancel: { connection.cancel(); completion.finish(.failure(CancellationError())) }
        }
    }
    private func read() async throws -> LanFrame {
        guard !reading else { throw LanError.invalidFrame }
        reading = true
        defer { reading = false }
        let connection = connection
        return try await lanTimeout {
            let prefix = try await Self.readExactly(4, from: connection)
            let size = prefix.reduce(0) { ($0 << 8) | Int($1) }
            guard size > 0, size <= LanFrame.maximumBodyBytes else { connection.cancel(); throw LanError.oversized }
            return try await LanFrame.decode(prefix + Self.readExactly(size, from: connection))
        }
    }
    private static func readExactly(_ size: Int, from connection: NWConnection) async throws -> Data {
        let completion = NetworkCompletion<Data>()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                completion.install(continuation)
                guard !Task.isCancelled else { completion.finish(.failure(CancellationError())); return }
                connection.receive(minimumIncompleteLength: size, maximumLength: size) { data, _, _, error in
                    guard error == nil, let data, data.count == size else { completion.finish(.failure(LanError.closed)); return }
                    completion.finish(.success(data))
                }
            }
        } onCancel: { connection.cancel(); completion.finish(.failure(CancellationError())) }
    }
}

public struct LanCandidate: Sendable {
    public let endpoint: NWEndpoint
    public let interface: NWInterface
}

public enum LanDiscovery {
    public static func candidate(material: LanMaterial, peerDeviceID: String, now: Int64) async throws -> LanCandidate {
        let day = now / 86_400_000
        let expected = try Set((-1...1).map { try LanCrypto.advertisement(secret: material.secret, deviceID: peerDeviceID, day: day + Int64($0)) })
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_twinotify._tcp", domain: "local."), using: parameters)
        let completion = NetworkCompletion<LanCandidate>()
        return try await withTaskCancellationHandler {
            defer { browser.cancel() }
            return try await withCheckedThrowingContinuation { continuation in
                completion.install(continuation)
                browser.browseResultsChangedHandler = { results, _ in
                    for result in results {
                        guard case .bonjour(let txt) = result.metadata,
                              Set(txt.dictionary.keys) == ["v", "ad", "caps"], txt.dictionary["v"] == "1", txt.dictionary["caps"] == "1",
                              let ad = txt.dictionary["ad"], expected.contains(ad),
                              let interface = result.interfaces.first(where: { $0.type == .wifi || $0.type == .wiredEthernet }) else { continue }
                        completion.finish(.success(LanCandidate(endpoint: result.endpoint, interface: interface)))
                        break
                    }
                }
                browser.stateUpdateHandler = { state in
                    if case .failed = state { completion.finish(.failure(LanError.unavailable)) }
                    if case .cancelled = state { completion.finish(.failure(LanError.closed)) }
                }
                guard !Task.isCancelled else { completion.finish(.failure(CancellationError())); return }
                browser.start(queue: DispatchQueue(label: "co.twinotify.mac.lan.discovery"))
            }
        } onCancel: { browser.cancel(); completion.finish(.failure(CancellationError())) }
    }
}
