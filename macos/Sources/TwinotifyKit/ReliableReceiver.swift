import Foundation

/// One instance belongs to one peer session. Platform work is serialized across
/// receives and recovery ticks, including actor reentrancy while awaiting the OS.
@MainActor public final class ReliableReceiver {
    private let store: DurableStore
    private let peer: PeerLink
    private let platform: any NotificationPlatform
    private let codec: ProtocolCodec
    private var materializing = false
    public init(store: DurableStore, peer: PeerLink, platform: any NotificationPlatform) throws {
        self.store = store; self.peer = peer; self.platform = platform; codec = try ProtocolCodec()
    }
    public func receive(_ bytes: Data, now: Int64) async throws {
        let authenticated = try await codec.authenticate(bytes, peer: peer, store: store)
        let event = authenticated.inner
        if event.type == "unpair" {
            if now < event.expiresAt { try await store.beginRemoval(peer.id) }
            else { try await store.commitDirect(linkID: peer.id, event: event, digest: authenticated.digest, now: now, outcome: .expired) }
            return
        }
        if event.type == "peer.receipt" {
            try await store.commitPeerReceipt(linkID: peer.id, event: event, digest: authenticated.digest, now: now)
            return
        }
        if event.type.hasPrefix("state.snapshot.") {
            do { try await store.processSnapshot(linkID: peer.id, event: event, digest: authenticated.digest, now: now) }
            catch is SnapshotError {
                try await store.commitDirect(linkID: peer.id, event: event, digest: authenticated.digest, now: now, outcome: .rejected)
            }
            try await resume(now: now)
            return
        }
        if event.type == "state.digest" {
            guard event.payload["origin_device"] == nil || event.payload["origin_device"]?.string == peer.deviceID,
                  let count = (event.payload["count"] ?? event.payload["item_count"])?.integer, (0...4_096).contains(count),
                  let digest = event.payload["digest"]?.string, digest.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else {
                throw ProtocolError.invalidPacket
            }
            try await store.commitDirect(linkID: peer.id, event: event, digest: authenticated.digest, now: now,
                                         outcome: now >= event.expiresAt ? .expired : .applied)
            return
        }
        if let previous = try await store.received(linkID: peer.id, messageID: event.messageID) {
            guard previous.digest == authenticated.digest else { throw DeliveryStoreError.digestConflict }
            if previous.outcome != .pending {
                try await store.replayReceipt(linkID: peer.id, messageID: event.messageID, digest: authenticated.digest)
                return
            }
        } else {
            let desired = try Self.presentation(event)
            _ = try await store.stage(linkID: peer.id, messageID: event.messageID, digest: authenticated.digest,
                                      expiresAt: event.expiresAt, desired: desired, now: now, eventType: event.type)
        }
        try await resume(now: now)
    }
    public func resume(now: Int64, permissionRecovered: Bool = false) async throws {
        guard !materializing else { return }
        materializing = true
        defer { materializing = false }
        for record in try await store.receiptsNeedingRenewal(linkID: peer.id, now: now) {
            let receipt = try await makeReceipt(record, outcome: record.outcome, reason: "unsupported_on_mac", now: now)
            try await store.renewReceipt(linkID: peer.id, original: record, receipt: receipt, now: now)
        }
        for record in try await store.pending(linkID: peer.id, now: now, permissionRecovered: permissionRecovered) {
            guard let canonicalID = record.canonicalID, let sequence = record.sequence else {
                try await finish(record, outcome: now >= record.expiresAt ? .expired : .rejected,
                                 reason: "unsupported_on_mac", now: now)
                continue
            }
            guard let desired = try await store.desired(linkID: peer.id, canonicalID: canonicalID) else {
                throw StorageError.repairRequired
            }
            if now >= record.expiresAt {
                // Expired ringing is never resurrected after sleep or denied permission.
                if canonicalID.hasPrefix("call:"), desired.sequence == sequence {
                    await platform.remove(identifier: presentation(desired).identifier)
                }
                try await finish(record, outcome: .expired, now: now)
                continue
            }
            if desired.sequence > sequence {
                try await finish(record, outcome: .applied, now: now)
                continue
            }
            if try await store.materializedSequence(linkID: peer.id, canonicalID: canonicalID) >= sequence {
                try await finish(record, outcome: .applied, now: now)
                continue
            }
            do {
                if desired.remove { await platform.remove(identifier: presentation(desired).identifier) }
                else if try await platform.post(presentation(desired)) == .permissionBlocked {
                    try await store.deferMaterialization(linkID: peer.id, canonicalID: canonicalID, sequence: sequence,
                                                         permissionBlocked: true, retryAt: nil)
                    continue
                }
            } catch {
                try await store.deferMaterialization(linkID: peer.id, canonicalID: canonicalID, sequence: sequence,
                                                     permissionBlocked: false, retryAt: now + 5_000)
                continue
            }
            try await finish(record, outcome: .applied, now: now)
        }
        for state in try await store.desiredWork(linkID: peer.id, now: now, permissionRecovered: permissionRecovered) {
            if now >= state.expiresAt {
                if state.canonicalID.hasPrefix("call:") { await platform.remove(identifier: presentation(state).identifier) }
                try await store.markDesiredExpired(linkID: peer.id, state: state)
                continue
            }
            do {
                if state.remove { await platform.remove(identifier: presentation(state).identifier) }
                else if try await platform.post(presentation(state)) == .permissionBlocked {
                    try await store.deferMaterialization(linkID: peer.id, canonicalID: state.canonicalID, sequence: state.sequence,
                                                         permissionBlocked: true, retryAt: nil)
                    continue
                }
                try await store.markDesiredApplied(linkID: peer.id, state: state)
            } catch {
                try await store.deferMaterialization(linkID: peer.id, canonicalID: state.canonicalID, sequence: state.sequence,
                                                     permissionBlocked: false, retryAt: now + 5_000)
            }
        }
        // Calls are short-lived presentation, including calls already materialized before sleep.
        for state in try await store.callPresentations(linkID: peer.id) where !state.remove && now >= state.expiresAt {
            await platform.remove(identifier: presentation(state).identifier)
            try await store.retireCall(linkID: peer.id, state: state)
        }
    }
    private func finish(_ record: ReceivedRecord, outcome: DeliveryOutcome, reason: String? = nil, now: Int64) async throws {
        let receipt = try await makeReceipt(record, outcome: outcome, reason: reason, now: now)
        try await store.complete(linkID: peer.id, messageID: record.messageID, digest: record.digest,
                                 outcome: outcome, receipt: receipt, now: now)
    }
    private func makeReceipt(_ record: ReceivedRecord, outcome: DeliveryOutcome, reason: String?, now: Int64) async throws -> StoredEnvelope {
        var payload: [String: JSONValue] = ["acked_msg_id": .string(record.messageID),
            "envelope_sha256": .string(record.digest), "status": .string(outcome.rawValue)]
        if outcome == .rejected || outcome == .decryptFailed { payload["reason"] = .string(reason ?? "unsupported_on_mac") }
        return try await codec.seal(type: "peer.receipt", payload: .object(payload), peer: peer, store: store, now: now)
    }
    private func presentation(_ desired: DesiredRecord) -> NotificationPresentation {
        NotificationPresentation(linkGeneration: peer.id, canonicalID: desired.canonicalID, sequence: desired.sequence,
                                 title: desired.title, subtitle: desired.subtitle, body: desired.body, imagePNG: desired.imagePNG)
    }
    nonisolated static func presentation(_ event: InnerEvent) throws -> DesiredRecord? {
        guard ["notif.post", "notif.update", "notif.cancel", "call.state"].contains(event.type) else { return nil }
        guard let id = event.canonicalID, let sequence = event.sequence else { throw ProtocolError.invalidPacket }
        let payload = event.payload
        if event.type == "call.state" {
            return DesiredRecord(canonicalID: id, sequence: sequence, expiresAt: event.expiresAt,
                remove: payload["state"]?.string != "ringing" || payload["direction"]?.string != "incoming",
                title: "Incoming call", body: "Your phone is ringing.")
        }
        let remove = event.type == "notif.cancel" || payload["visibility"]?.string == "secret" || payload["is_group_summary"]?.boolean == true
        let title = payload["title"]?.string ?? payload["app_name"]?.string ?? payload["package_name"]?.string ?? "Notification"
        let conversation = payload["conversation"]
        let messages = conversation?["messages"]?.array ?? []
        let conversationText = messages.compactMap { message -> String? in
            guard let text = message["text"]?.string else { return nil }
            if let sender = message["sender_name"]?.string { return sender + ": " + text }
            return text
        }.joined(separator: "\n")
        let body = !conversationText.isEmpty ? conversationText
            : payload["big_text"]?.string.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
                ?? payload["text"]?.string ?? payload["title"]?.string ?? ""
        let image = payload["large_icon_png_b64"]?.string.flatMap { Data(base64Encoded: $0) }
        return DesiredRecord(canonicalID: id, sequence: sequence, expiresAt: event.expiresAt, remove: remove,
                             title: conversation?["title"]?.string ?? title, subtitle: payload["sub_text"]?.string ?? "", body: body,
                             active: event.type != "notif.cancel", imagePNG: image.flatMap { $0.count <= 512 * 1024 ? $0 : nil })
    }
}
