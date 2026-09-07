import AppKit
import CoreImage
import ImageIO
import Network
import ServiceManagement
import SwiftUI
import TwinotifyKit
import UserNotifications

@MainActor @Observable final class AppModel {
    let platform = SystemNotifications()
    var permission = "Checking…"
    var activity = ""
    var storageProblem: String?
    var peers: [PeerLink] = []
    var statuses: [String: String] = [:]
    var counts: [String: PeerCounts] = [:]
    var history: [ActivityRecord] = []
    var pending: PendingPairing? {
        didSet {
            if pending?.id != oldValue?.id { pairingImage = makePairingImage() }
        }
    }
    private(set) var pairingImage: NSImage?
    var qrText = ""
    var relayURL = UserDefaults.standard.string(forKey: "pairingRelayURL") ?? "https://relay.twinotify.nuvaynlabs.com"
    var busy = false
    var pairingProblem: String?
    var launchAtLogin = SMAppService.mainApp.status == .enabled
    private var store: DurableStore?
    private var pairing: PairingClient?
    private var sessions: [String: RelaySession] = [:]
    private var receivers: [String: ReliableReceiver] = [:]
    private var tasks: [String: Task<Void, Never>] = [:]
    private var pairingTask: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []
    private var workspaceObserver: NSObjectProtocol?
    private var maintenance: Task<Void, Never>?
    private let network = NWPathMonitor()
    private var testSequence: Int64 = 0
    private var stopping = false
    #if TWINOTIFY_E2E
    private var e2eControl: E2EControl?
    private var e2eRelayPaused = false
    private var e2eStorageStatus: Int?
    #endif

    init() {
        do {
            let folder: URL
            let vault: KeychainVault
            #if TWINOTIFY_E2E
            if let run = try E2EControl.directoryFromEnvironment() {
                folder = run
                vault = KeychainVault(service: "co.twinotify.mac.e2e." + run.lastPathComponent)
            } else {
                throw CocoaError(.fileReadNoPermission)
            }
            #else
            folder = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true).appendingPathComponent("co.twinotify.mac", isDirectory: true)
            vault = KeychainVault()
            #endif
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
            let store = try DurableStore(path: folder.appendingPathComponent("state.sqlite").path, vault: vault)
            self.store = store
            pairing = PairingClient(store: store, allowDebugLoopback: Self.debugLoopback)
        } catch {
            storageProblem = "Identity storage needs attention. Delivery is paused to protect your paired identity. \(error.localizedDescription)"
            #if TWINOTIFY_E2E
            if let failure = error as? VaultError, case .status(let status) = failure { e2eStorageStatus = Int(status) }
            #endif
        }
        observers.append(NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.refreshPermission() }
        })
        workspaceObserver = NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.refreshPermission(); await self?.reconnect() }
        }
        network.pathUpdateHandler = { [weak self] _ in Task { @MainActor in await self?.reconnect() } }
        network.start(queue: DispatchQueue(label: "co.twinotify.mac.network"))
        maintenance = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(for: .seconds(10))
            }
        }
        Task { await refreshPermission(); await refresh() }
        #if TWINOTIFY_E2E
        if let run = try? E2EControl.directoryFromEnvironment() { e2eControl = E2EControl(directory: run, model: self) }
        #endif
    }
    static var debugLoopback: Bool {
        #if DEBUG || TWINOTIFY_E2E
        true
        #else
        false
        #endif
    }
    static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    var ownFingerprint: String {
        guard let store else { return "Unavailable" }
        return (try? WireCrypto.fingerprint(encryptionKey: store.identity.encryptionKey, signingKey: store.identity.signingKey)) ?? "Unavailable"
    }
    func refreshPermission() async {
        let authorization = await platform.authorization()
        let before = permission
        switch authorization {
        case .authorized, .provisional: permission = "Allowed"
        case .denied: permission = "Blocked in System Settings"
        case .notDetermined: permission = "Permission needed"
        default: permission = "Unavailable"
        }
        if permission == "Allowed", before != permission {
            for receiver in receivers.values { try? await receiver.resume(now: Self.now, permissionRecovered: true) }
        }
    }
    func refresh() async {
        guard let store, !stopping else { return }
        do {
            peers = try await store.peers()
            pending = try await pairing?.pending()
            history = try await store.recentActivity()
            try await store.sweepCompleted(now: Self.now)
            for peer in peers {
                counts[peer.id] = try await store.counts(linkID: peer.id)
                if tasks[peer.id] == nil { start(peer) }
            }
        } catch { storageProblem = "Delivery storage is unavailable. \(error.localizedDescription)" }
    }
    private func start(_ peer: PeerLink) {
        guard let store, !stopping else { return }
        tasks[peer.id] = Task { [weak self] in
            guard let self else { return }
            defer { tasks[peer.id] = nil; sessions[peer.id] = nil; receivers[peer.id] = nil }
            while !Task.isCancelled && !stopping {
                #if TWINOTIFY_E2E
                if e2eRelayPaused {
                    try? await Task.sleep(for: .milliseconds(200))
                    continue
                }
                #endif
                do {
                    guard let current = try await store.peers().first(where: { $0.id == peer.id }) else { return }
                    if current.lifecycle == .removing {
                        statuses[peer.id] = "Removing · waiting for relay"
                        for state in try await store.allDesired(linkID: peer.id) {
                            await platform.remove(identifier: NotificationPresentation(linkGeneration: peer.id, canonicalID: state.canonicalID,
                                sequence: state.sequence, title: "", body: "").identifier)
                        }
                        try await store.clearRemovingWork(linkID: peer.id)
                        try await PeerRemoval.revoke(peer: current, store: store, allowDebugLoopback: Self.debugLoopback)
                        statuses[peer.id] = nil
                        peers = try await store.peers()
                        return
                    }
                    let receiver = try ReliableReceiver(store: store, peer: current, platform: platform)
                    let session = try RelaySession(peer: current, store: store)
                    receivers[peer.id] = receiver; sessions[peer.id] = session
                    statuses[peer.id] = "Connecting…"
                    try await session.run(allowDebugLoopback: Self.debugLoopback, received: { [weak self] data in
                        try await receiver.receive(data, now: Int64(Date().timeIntervalSince1970 * 1000))
                        await self?.setConnected(peer.id)
                    }, tick: { [weak self] in
                        try await receiver.resume(now: Int64(Date().timeIntervalSince1970 * 1000))
                        await self?.updateCounts(peer.id)
                    }, connected: { [weak self] in await self?.setConnected(peer.id) })
                } catch {
                    if Task.isCancelled || stopping { return }
                    statuses[peer.id] = "Offline · retrying"
                }
                try? await Task.sleep(for: .seconds(peer.lifecycle == .removing ? 60 : 5))
            }
        }
    }
    private func setConnected(_ id: String) { statuses[id] = "Connected" }
    private func updateCounts(_ id: String) async {
        guard let store else { return }
        do { counts[id] = try await store.counts(linkID: id) }
        catch { storageProblem = "Delivery storage is unavailable." }
    }
    func reconnect() async {
        guard !stopping else { return }
        for session in sessions.values { await session.cancel() }
        // Each existing task owns reconnect and joins its old session before retry.
        await refresh()
    }
    func remove(_ peer: PeerLink) async {
        guard let store else { return }
        do {
            try await store.beginRemoval(peer.id)
            await sessions[peer.id]?.cancel()
            tasks[peer.id]?.cancel()
            if let task = tasks[peer.id] { await task.value }
            await refresh()
        } catch { activity = "Could not remove the connection. \(error.localizedDescription)" }
    }
    private func makePairingImage() -> NSImage? {
        guard let pending, pending.isInitiator,
              let bytes = try? pending.qr.wireJSON,
              let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(bytes, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage,
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
    }
    func createPairingCode() {
        guard let pairing, pairingTask == nil else { return }
        busy = true
        pairingProblem = nil
        pairingTask = Task {
            defer { busy = false; pairingTask = nil }
            do {
                pending = try await pairing.initiate(relayURL: relayURL.trimmingCharacters(in: .whitespacesAndNewlines), now: Self.now)
                UserDefaults.standard.set(pending?.qr.relayURL, forKey: "pairingRelayURL")
                activity = "Scan this code with Twinotify on your phone."
                pending = try await pairing.waitForPhone()
                activity = "Compare both fingerprints, then confirm on each device."
            } catch is CancellationError { }
            catch { pending = try? await pairing.pending(); pairingProblem = pairingMessage(error); activity = pairingProblem! }
        }
    }
    func resumeWaitingForPhone() {
        guard let pairing, pairingTask == nil else { return }
        busy = true
        pairingProblem = nil
        pairingTask = Task {
            defer { busy = false; pairingTask = nil }
            do {
                pending = try await pairing.waitForPhone()
                activity = "Compare both fingerprints, then confirm on each device."
            } catch is CancellationError { }
            catch { pairingProblem = pairingMessage(error); activity = pairingProblem! }
        }
    }
    private func pairingMessage(_ error: Error) -> String {
        if let pending, Self.now >= pending.expiresAt { return "Pairing code expired. Create a new code to try again." }
        if let failure = error as? PairingError, case .httpStatus(let status) = failure {
            return "The relay could not start or finish pairing (HTTP \(status)). Check its address and try again."
        }
        return "Could not connect for pairing. Check the relay address and your connection, then try again."
    }
    func parseQR() async {
        guard let pairing else { return }
        do {
            let qr = try PairingQR(json: Data(qrText.utf8), allowDebugLoopback: Self.debugLoopback)
            pending = try await pairing.begin(qr: qr, now: Self.now)
            qrText = ""
            activity = "Compare fingerprints with the phone before pairing."
        } catch { activity = "Could not read this pairing code. \(error.localizedDescription)" }
    }
    func importQR() async {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]; panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let values = try url.resourceValues(forKeys: [.fileSizeKey])
            guard (values.fileSize ?? Int.max) <= 8 * 1024 * 1024,
                  let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceThumbnailMaxPixelSize: 2048, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary),
                  let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]) else {
                throw PairingError.invalidResponse
            }
            let codes = detector.features(in: CIImage(cgImage: image)).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
            guard codes.count == 1 else { throw PairingError.invalidResponse }
            qrText = codes[0]; await parseQR()
        } catch { activity = "Choose an image containing one clear Twinotify pairing code." }
    }
    func confirmPairing() {
        guard let pairing, let pending, pairingTask == nil else { return }
        busy = true
        pairingProblem = nil
        pairingTask = Task {
            defer { busy = false; pairingTask = nil }
            do {
                try await pairing.confirmFingerprint(id: pending.id, now: Self.now)
                activity = "Waiting for confirmation on the phone…"
                _ = try await pairing.complete()
                self.pending = nil
                activity = "Phone connected."
                await refresh()
            } catch { pairingProblem = pairingMessage(error); activity = pairingProblem!; self.pending = try? await pairing.pending() }
        }
    }
    func cancelPairing() async {
        pairingTask?.cancel()
        try? await pairing?.cancel()
        if let task = pairingTask { await task.value }
        pending = nil
        pairingProblem = nil
        busy = false
    }
    func requestPermission() async {
        do { _ = try await platform.requestPermission(); await refreshPermission() }
        catch { activity = "Could not request permission. \(error.localizedDescription)" }
    }
    func postTest() async {
        testSequence += 1
        do {
            let result = try await platform.post(testPresentation)
            activity = result == .applied ? "Test notification submitted." : "Allow notifications in System Settings to run the test."
            await refreshPermission()
        } catch { activity = "Could not post a test notification. \(error.localizedDescription)" }
    }
    private var testPresentation: NotificationPresentation {
        NotificationPresentation(linkGeneration: "local-diagnostic", canonicalID: "notification-test", sequence: testSequence,
                                 title: "Twinotify", body: "Notifications are ready on this Mac.")
    }
    func removeTest() async { await platform.remove(identifier: testPresentation.identifier); activity = "Test notification removed." }
    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            launchAtLogin = SMAppService.mainApp.status == .enabled
        } catch { activity = "Could not update login settings. \(error.localizedDescription)" }
    }
    func stop() async {
        stopping = true; network.cancel(); maintenance?.cancel(); pairingTask?.cancel()
        #if TWINOTIFY_E2E
        e2eControl?.stop()
        #endif
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        if let workspaceObserver { NSWorkspace.shared.notificationCenter.removeObserver(workspaceObserver) }
        for session in sessions.values { await session.cancel() }
        let running = Array(tasks.values)
        for task in running { task.cancel() }
        for task in running { await task.value }
        if let pairingTask { await pairingTask.value }
    }
    #if TWINOTIFY_E2E
    func executeE2E(_ request: E2EControl.Request) async throws -> [String: Any] {
        if request.operation == "state", store == nil {
            return ["storage_ok": false, "storage_status": e2eStorageStatus ?? 0,
                    "peer_links": [], "canonical": [], "permission": permission,
                    "process_id": ProcessInfo.processInfo.processIdentifier]
        }
        guard let store else { throw CocoaError(.fileReadUnknown) }
        switch request.operation {
        case "state":
            await refreshPermission()
            await refresh()
            let center = UNUserNotificationCenter.current()
            let delivered = await center.deliveredNotifications().map(\.request)
            let pendingRequests = await center.pendingNotificationRequests()
            var canonical: [[String: Any]] = []
            var links: [[String: Any]] = []
            for peer in try await store.peers() {
                let count = try await store.counts(linkID: peer.id)
                links.append(["peer_link_id": peer.id, "device_id_hash": E2EControl.hash(peer.deviceID),
                    "lifecycle": peer.lifecycle.rawValue, "status": statuses[peer.id] ?? "unknown",
                    "pending": count.pending, "outbound": count.outbound])
                for state in try await store.allDesired(linkID: peer.id) {
                    let identifier = NotificationPresentation(linkGeneration: peer.id, canonicalID: state.canonicalID,
                        sequence: state.sequence, title: "", body: "").identifier
                    let matches: (UNNotificationRequest) -> Bool = {
                        $0.identifier == identifier && ($0.content.userInfo["sequence"] as? NSNumber)?.int64Value == state.sequence
                    }
                    canonical.append(["peer_link_id": peer.id, "canon_id_hash": E2EControl.hash(state.canonicalID),
                        "sequence": state.sequence, "state": state.active ? "ACTIVE" : "CANCELLED",
                        "materialized_sequence": try await store.materializedSequence(linkID: peer.id, canonicalID: state.canonicalID),
                        "delivered": delivered.contains(where: matches), "platform_pending": pendingRequests.contains(where: matches),
                        "body_hash": E2EControl.hash(state.body)])
                }
            }
            return ["device_id_hash": E2EControl.hash(store.identity.deviceID), "peer_links": links,
                "canonical": canonical, "permission": permission, "pairing_pending": pending != nil,
                "storage_ok": storageProblem == nil, "relay_paused": e2eRelayPaused,
                "snapshot_commits": try await store.e2eSnapshotCommits(), "process_id": ProcessInfo.processInfo.processIdentifier]
        case "create_pairing_code":
            guard let value = request.value else { throw PairingError.invalidResponse }
            relayURL = value
            createPairingCode()
            return ["started": true]
        case "pairing_code":
            guard let pending, pending.isInitiator else { throw PairingError.invalidResponse }
            return ["qr": String(decoding: try pending.qr.wireJSON, as: UTF8.self)]
        case "pair":
            guard let raw = request.value, raw.utf8.count <= 16_384, let pairing else { throw PairingError.invalidResponse }
            let qr = try PairingQR(json: Data(raw.utf8), allowDebugLoopback: true)
            pending = try await pairing.begin(qr: qr, now: Self.now)
            return ["phone_fingerprint": try qr.fingerprint, "mac_fingerprint": ownFingerprint]
        case "confirm_pairing":
            guard pending != nil else { throw PairingError.invalidResponse }
            confirmPairing()
            return ["started": true]
        case "cancel_pairing":
            await cancelPairing()
            return [:]
        case "pause_relay":
            guard request.value == "true" || request.value == "false" else { throw PairingError.invalidResponse }
            e2eRelayPaused = request.value == "true"
            for session in sessions.values { await session.cancel() }
            return ["paused": e2eRelayPaused]
        case "remove_peer":
            guard let peer = try await store.peers().first(where: { $0.id == request.value }) else { throw PairingError.invalidResponse }
            await remove(peer)
            return [:]
        case "dismiss_local":
            for peer in try await store.peers() {
                for state in try await store.allDesired(linkID: peer.id) where E2EControl.hash(state.canonicalID) == request.value {
                    await platform.remove(identifier: NotificationPresentation(linkGeneration: peer.id,
                        canonicalID: state.canonicalID, sequence: state.sequence, title: "", body: "").identifier)
                    return [:]
                }
            }
            throw PairingError.invalidResponse
        case "request_permission":
            await requestPermission()
            return ["permission": permission]
        case "appearance":
            switch request.value {
            case "light": NSApp.appearance = NSAppearance(named: .aqua)
            case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
            case "system": NSApp.appearance = nil
            default: throw PairingError.invalidResponse
            }
            return [:]
        default: throw PairingError.invalidResponse
        }
    }
    #endif

}
