import Foundation
import Network
import OSLog

public enum PeerRoute: String, Sendable { case relay, wifi }

/// One active drainer for a link. Discovery authenticates a candidate while relay
/// runs; joining the relay worker is the lease handoff boundary.
public actor PeerSession {
    public let peer: PeerLink
    private let store: DurableStore
    private let identity: LanIdentity?
    private var worker: Task<Void, any Error>?
    private var nextAnnouncementAt: Int64 = 0

    public init(peer: PeerLink, store: DurableStore, lanIdentity: LanIdentity?) {
        self.peer = peer; self.store = store; identity = lanIdentity
    }
    public func cancel() { worker?.cancel() }
    public func run(allowDebugLoopback: Bool = false,
                    received: @escaping @Sendable (Data) async throws -> Void,
                    tick: @escaping @Sendable () async throws -> Void,
                    status: @escaping @Sendable (PeerRoute?) async -> Void) async throws {
        guard worker == nil else { throw RelaySessionError.alreadyRunning }
        let worker = Task { [self] in
            if let identity {
                do { try await announce(pin: identity.pin) }
                catch { if Task.isCancelled { throw CancellationError() } }
                // A full outbox or unavailable LAN key must not block relay
                // from draining existing reliable work.
            }
            while !Task.isCancelled {
                let candidate = try await relayUntilDirect(allowDebugLoopback: allowDebugLoopback, received: received, tick: tick, status: status)
                guard let candidate else { return }
                do {
                    try Task.checkCancellation()
                    try await store.makeDueAfterRouteChange(linkID: peer.id)
                    await status(.wifi)
                    try await LanSession(connection: candidate, peer: peer, store: store).run(received: received, tick: tick)
                } catch {
                    candidate.cancel()
                    if Task.isCancelled { throw CancellationError() }
                    await status(nil)
                }
            }
            throw CancellationError()
        }
        self.worker = worker
        defer { self.worker = nil }
        try await withTaskCancellationHandler { try await worker.value } onCancel: { worker.cancel() }
    }
    private func announce(pin: Data) async throws {
        let now = Self.now
        nextAnnouncementAt = now + 60_000
        guard try await !store.hasPendingControl(linkID: peer.id, type: "lan.bootstrap", now: now) else { return }
        let material = try await store.lanMaterial(peer: peer)
        let envelope = try await ProtocolCodec().seal(type: "lan.bootstrap", payload: .object([
            "protocol_version": .integer(1), "tls_spki_sha256": .string(pin.hex),
            "binding_context_sha256": .string(material.contextDigest.hex)
        ]), peer: peer, store: store, now: now, lifetime: 600_000)
        try await store.enqueueControl(linkID: peer.id, envelope: envelope, type: "lan.bootstrap", requiresReceipt: true)
    }
    private func retryAnnouncement() async throws {
        guard let identity, Self.now >= nextAnnouncementAt else { return }
        do {
            if try await store.lanBinding(linkID: peer.id) == nil { try await announce(pin: identity.pin) }
            else { nextAnnouncementAt = Self.now + 60_000 }
        } catch {
            nextAnnouncementAt = Self.now + 60_000
            if Task.isCancelled { throw CancellationError() }
        }
    }
    private func relayUntilDirect(allowDebugLoopback: Bool, received: @escaping @Sendable (Data) async throws -> Void,
                                  tick: @escaping @Sendable () async throws -> Void,
                                  status: @escaping @Sendable (PeerRoute?) async -> Void) async throws -> LanConnection? {
        var workers: [@Sendable () async throws -> LanConnection?] = []
        workers.append { [self] in
            while !Task.isCancelled {
                do {
                    try await store.makeDueAfterRouteChange(linkID: peer.id)
                    let relay = try RelaySession(peer: peer, store: store, features: identity == nil ? ["peer-probe-v1"] : ["lan-bootstrap-v1", "peer-probe-v1"])
                    try await relay.run(allowDebugLoopback: allowDebugLoopback, received: received,
                        tick: { [self] in try await tick(); try await retryAnnouncement() },
                        connected: { await status(.relay) })
                } catch {
                    if Task.isCancelled { throw CancellationError() }
                    guard try await store.peers().contains(peer) else { throw DeliveryStoreError.missingLink }
                    Logger(subsystem: "co.twinotify.mac", category: "transport").error("Relay session failed: \(routeFailureCode(error), privacy: .public)")
                    await status(nil)
                }
                try await Task.sleep(for: .seconds(3))
            }
            throw CancellationError()
        }
        if let identity {
            workers.append { [self] in
                while !Task.isCancelled {
                    var connection: LanConnection?
                    do {
                        if let binding = try await store.lanBinding(linkID: peer.id) {
                            let material = try await store.lanMaterial(peer: peer)
                            let candidate = try await lanTimeout(20) { [peer] in
                                try await LanDiscovery.candidate(material: material, peerDeviceID: peer.deviceID, now: Self.now)
                            }
                            let socket = try LanConnection(endpoint: candidate.endpoint, interface: candidate.interface,
                                identity: identity, peerPin: binding.peerPin)
                            connection = socket
                            try await withTaskCancellationHandler {
                                try await socket.authenticate(peer: peer, store: store)
                                try Task.checkCancellation()
                            } onCancel: { socket.cancel() }
                            return socket
                        }
                    } catch {
                        connection?.cancel()
                        if Task.isCancelled { throw CancellationError() }
                        guard try await store.peers().contains(peer) else { throw DeliveryStoreError.missingLink }
                    }
                    try await Task.sleep(for: .seconds(3))
                }
                throw CancellationError()
            }
        }
        return try await routeHandoff(workers: workers, close: { $0?.cancel() })
    }
    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

public actor LanSession {
    private let connection: LanConnection
    private let peer: PeerLink
    private let store: DurableStore
    private var awaitingPong: (token: Int64, sentAt: Int64)?
    private var nextPingAt: Int64 = 0
    private var running = false
    public init(connection: LanConnection, peer: PeerLink, store: DurableStore) {
        self.connection = connection; self.peer = peer; self.store = store
    }
    public func run(received: @escaping @Sendable (Data) async throws -> Void,
                    tick: @escaping @Sendable () async throws -> Void) async throws {
        guard !running else { throw RelaySessionError.alreadyRunning }
        running = true
        defer { connection.cancel(); running = false }
        try await withTaskCancellationHandler {
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask { [self] in
                    do {
                        while !Task.isCancelled { try await consume(connection.receive(), received: received) }
                    } catch { connection.cancel(); throw error }
                }
                group.addTask { [self] in
                    do {
                        while !Task.isCancelled {
                            try await tick()
                            for row in try await store.sendable(linkID: peer.id, now: Self.now) {
                                try await connection.send(.put(row.bytes))
                                try await store.markSent(linkID: peer.id, messageID: row.messageID, retryAt: Self.now + 5_000)
                            }
                            try await heartbeat()
                            try await Task.sleep(for: .seconds(1))
                        }
                    } catch { connection.cancel(); throw error }
                }
                defer { group.cancelAll(); connection.cancel() }
                _ = try await group.next()
            }
        } onCancel: { [connection] in connection.cancel() }
    }
    private func consume(_ frame: LanFrame, received: @Sendable (Data) async throws -> Void) async throws {
        switch frame {
        case .put(let bytes):
            try await received(bytes)
            guard let id = try JSONValue.parse(bytes)["msg_id"]?.string,
                  let record = try await store.received(linkID: peer.id, messageID: id),
                  record.digest == RawEnvelope.digest(bytes) else { throw LanError.authentication }
            try await connection.send(.accepted(id, record.digest))
        case .accepted(let id, let digest): try await store.directAccepted(linkID: peer.id, messageID: id, digest: digest, now: Self.now)
        case .ping(let token): try await connection.send(.pong(token))
        case .pong(let token): if awaitingPong?.token == token { awaitingPong = nil }
        case .close: throw LanError.closed
        case .hello, .helloAck: throw LanError.authentication
        }
    }
    private func heartbeat() async throws {
        let now = Self.now
        if let pending = awaitingPong {
            if now - pending.sentAt >= 9_000 { throw LanError.timeout }
        } else if now >= nextPingAt {
            awaitingPong = (now, now); nextPingAt = now + 3_000
            try await connection.send(.ping(now))
        }
    }
    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

extension Data { var hex: String { map { String(format: "%02x", $0) }.joined() } }


private func routeFailureCode(_ error: any Error) -> String {
    switch error {
    case let value as ProtocolError: "protocol_" + String(describing: value)
    case let value as FrameError: "frame_" + String(describing: value)
    case let value as StorageError: "storage_" + String(describing: value)
    case let value as DeliveryStoreError: "delivery_" + String(describing: value)
    case let value as RelaySessionError: "relay_" + String(describing: value)
    case let value as URLError: "url_" + String(value.code.rawValue)
    default: "connection_failed"
    }
}
