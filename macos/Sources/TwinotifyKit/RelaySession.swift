import Foundation
import Synchronization

public enum RelaySessionError: Error { case alreadyRunning, closed, unsupportedProtocol }

/// One session per peer link. The owner starts a replacement only after run()
/// finishes; cancel() closes the socket to unblock receive and joins via run().
public actor RelaySession {
    public let peer: PeerLink
    private let store: DurableStore
    private let session: URLSession
    private var connection: URLSessionWebSocketTask?
    private let validator: SchemaValidator
    private let features: [String]

    public init(peer: PeerLink, store: DurableStore, features: [String] = []) throws {
        guard Set(features).isSubset(of: ["lan-bootstrap-v1", "peer-probe-v1"]), Set(features).count == features.count else { throw ProtocolError.invalidPacket }
        self.features = features
        self.peer = peer; self.store = store; validator = try SchemaValidator()
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 0
        session = URLSession(configuration: config, delegate: RelayHTTPDelegate(), delegateQueue: nil)
    }
    public func cancel() {
        connection?.cancel(with: .goingAway, reason: nil)
    }
    public func run(allowDebugLoopback: Bool = false,
                    received: @escaping @Sendable (Data) async throws -> Void,
                    tick: @escaping @Sendable () async throws -> Void = {},
                    connected: @escaping @Sendable () async -> Void = {}) async throws {
        guard connection == nil else { throw RelaySessionError.alreadyRunning }
        guard try await store.peers().contains(peer), peer.lifecycle == .active else { throw DeliveryStoreError.missingLink }
        let endpoint = try RelayEndpoint(peer.relayURL, allowDebugLoopback: allowDebugLoopback)
        var request = URLRequest(url: try endpoint.url("ws", websocket: true, query: [URLQueryItem(name: "pair_id", value: peer.pairID)]))
        request.setValue("Bearer " + (try await store.mintJWT(pairID: peer.pairID, nowSeconds: Int64(Date().timeIntervalSince1970))),
                         forHTTPHeaderField: "Authorization")
        let socket = session.webSocketTask(with: request)
        socket.maximumMessageSize = RawEnvelope.maximumFrameBytes
        connection = socket
        socket.resume()
        defer { socket.cancel(with: .goingAway, reason: nil); connection = nil }
        try await withTaskCancellationHandler {
            var hello: [String: JSONValue] = ["v": .integer(2), "type": .string("relay.hello"), "protocols": .array([.integer(2)]), "app_version": .string("macos-0.2.0")]
            if !features.isEmpty { hello["features"] = .array(features.map(JSONValue.string)) }
            try await socket.send(.string(String(decoding: try JSONValue.object(hello).encoded(), as: UTF8.self)))
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask { [self] in
                    do {
                        while !Task.isCancelled {
                            let message = try await socket.receive()
                            let bytes: Data
                            switch message {
                            case .string(let text): bytes = Data(text.utf8)
                            case .data(let data): bytes = data
                            @unknown default: throw ProtocolError.invalidPacket
                            }
                            try await consume(bytes, received: received, connected: connected)
                        }
                    } catch { socket.cancel(with: .goingAway, reason: nil); throw error }
                }
                group.addTask { [self] in
                    do {
                        var ticks = 0
                        while !Task.isCancelled {
                            try await tick()
                            try await flush(socket)
                            if ticks % 25 == 0 {
                                try await waitForWebSocketPing { completion in
                                    socket.sendPing(pongReceiveHandler: completion)
                                }
                            }
                            ticks = (ticks + 1) % 25
                            try await Task.sleep(for: .seconds(1))
                        }
                    } catch { socket.cancel(with: .goingAway, reason: nil); throw error }
                }
                do { _ = try await group.next(); group.cancelAll(); socket.cancel(with: .goingAway, reason: nil) }
                catch { group.cancelAll(); socket.cancel(with: .goingAway, reason: nil); throw error }
            }
        } onCancel: { socket.cancel(with: .goingAway, reason: nil) }
    }
    private func consume(_ bytes: Data, received: @Sendable (Data) async throws -> Void, connected: @Sendable () async -> Void) async throws {
        guard bytes.count <= RawEnvelope.maximumFrameBytes else { throw FrameError.oversized }
        try RawEnvelope.validateObject(bytes, maximumBytes: RawEnvelope.maximumFrameBytes)
        let frame = try JSONDecoder().decode(JSONValue.self, from: bytes)
        try validator.validate(frame, schema: "relay-control")
        switch frame["type"]?.string {
        case "relay.deliver": try await received(RawEnvelope.extract(from: bytes))
        case "relay.accepted":
            guard let id = frame["msg_id"]?.string else { throw ProtocolError.invalidPacket }
            try await store.receiptAccepted(linkID: peer.id, messageID: id, now: Self.now())
        case "relay.capabilities":
            guard frame["floor"] == .integer(2), frame["peer"]?.array?.contains(.integer(2)) == true else {
                throw RelaySessionError.unsupportedProtocol
            }
            await connected()
        case "relay.rejected":
            guard let id = frame["msg_id"]?.string else { throw ProtocolError.invalidPacket }
            // Retain reliable bytes and back off; never turn rejection into custody.
            try await store.markSent(linkID: peer.id, messageID: id, retryAt: Self.now() + 60_000)
        case "relay.expired": break // Journal retains receipt reconstruction state.
        default: throw ProtocolError.invalidPacket
        }
    }
    private func flush(_ socket: URLSessionWebSocketTask) async throws {
        let now = Self.now()
        for envelope in try await store.sendable(linkID: peer.id, now: now) {
            let bytes = try RawEnvelope.put(envelope: envelope.bytes)
            guard let text = String(data: bytes, encoding: .utf8) else { throw ProtocolError.invalidPacket }
            try await socket.send(.string(text))
            try await store.markSent(linkID: peer.id, messageID: envelope.messageID, retryAt: now + 5_000)
        }
        for record in try await store.readyAcks(linkID: peer.id) {
            let bytes = try JSONValue.object(["v": .integer(2), "type": .string("relay.ack"),
                "msg_id": .string(record.messageID), "envelope_sha256": .string(record.digest)]).encoded()
            try await socket.send(.string(String(decoding: bytes, as: UTF8.self)))
            try await store.markAckSent(linkID: peer.id, messageID: record.messageID, digest: record.digest)
        }
    }
    private static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}


/// URLSession can deliver a late ping error while a socket is closing. A pong,
/// a close error, and task cancellation must settle this wait only once.
private final class PingCompletion: Sendable {
    private struct State {
        var continuation: CheckedContinuation<Void, any Error>?
        var result: Result<Void, any Error>?
    }
    private let state = Mutex(State())

    func install(_ continuation: CheckedContinuation<Void, any Error>) {
        let result: Result<Void, any Error>? = state.withLock { state -> Result<Void, any Error>? in
            if let result = state.result { return result }
            state.continuation = continuation
            return nil as Result<Void, any Error>?
        }
        if let result { continuation.resume(with: result) }
    }
    func finish(_ result: Result<Void, any Error>) {
        let continuation = state.withLock { state in
            guard state.result == nil else { return nil as CheckedContinuation<Void, any Error>? }
            state.result = result
            let continuation = state.continuation
            state.continuation = nil
            return continuation
        }
        continuation?.resume(with: result)
    }
}

func waitForWebSocketPing(_ send: (@escaping @Sendable (Error?) -> Void) -> Void) async throws {
    let completion = PingCompletion()
    try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { continuation in
            completion.install(continuation)
            guard !Task.isCancelled else { return }
            send { error in
                if let error { completion.finish(.failure(error)) }
                else { completion.finish(.success(())) }
            }
        }
    } onCancel: { completion.finish(.failure(CancellationError())) }
}
