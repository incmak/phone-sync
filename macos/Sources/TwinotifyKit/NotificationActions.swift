import Foundation

public struct NotificationAction: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let title: String
    public let semantic: Int
    public let reply: Bool
    public let replyLabel: String?
    enum CodingKeys: String, CodingKey { case id = "action_id", title, semantic, reply, replyLabel = "reply_label" }
}

public struct ActionAttempt: Sendable, Identifiable {
    public let id: String
    public let linkID: String
    public let canonicalID: String
    public let actionID: String
    public let sequence: Int64
    public let status: String
    public var notificationID: String {
        NotificationPresentation(linkGeneration: linkID, canonicalID: canonicalID, sequence: sequence, title: "", body: "").identifier
    }
    public var label: String {
        switch status {
        case "pending": "Sending…"
        case "dispatched": "Sent to app"
        case "outcome_unknown", "timed_out": "No confirmation — check your phone"
        case "action_gone": "Action is no longer available"
        case "notification_gone": "Notification was removed"
        case "expired": "Action expired"
        default: "Could not complete action"
        }
    }
}

public enum ActionError: Error { case unavailable, invalidReply, capacity }

extension DurableStore {
    public func invokeNotificationAction(item: InboxItem, actionID: String, reply: String?, now: Int64) async throws -> ActionAttempt {
        guard let peer = try peers().first(where: { $0.id == item.linkID }), peer.lifecycle == .active else { throw ActionError.unavailable }
        if let previous = try actionAttempt(item: item, actionID: actionID) { return previous }
        let action = try actionable(item: item, actionID: actionID)
        if action.reply {
            guard let reply, !reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, reply.utf8.count <= 4096 else { throw ActionError.invalidReply }
        } else if reply != nil { throw ActionError.invalidReply }
        let id = UUID().uuidString.lowercased()
        var payload: [String: JSONValue] = ["invocation_id": .string(id), "canon_id": .string(item.canonicalID),
            "action_id": .string(actionID), "notification_sequence": .integer(item.presentation.sequence), "invoked_at": .integer(now)]
        if let reply { payload["reply_text"] = .string(reply) }
        let envelope = try await ProtocolCodec().seal(type: "notif.action.invoke", payload: .object(payload),
            peer: peer, store: self, now: now, lifetime: 120_000)
        // Recheck after nonce allocation/encryption: actor reentrancy may have
        // admitted a newer notification, removal or another user click.
        return try database.transaction {
            try requireActive(item.linkID)
            if let existing = try actionAttempt(item: item, actionID: actionID) { return existing }
            _ = try actionable(item: item, actionID: actionID)
            guard try database.execute("SELECT count(*) AS n FROM action_invocation").first!.integer("n") < 2_000 else { throw ActionError.capacity }
            try insertOutbound(linkID: item.linkID, envelope: envelope, limits: .init())
            try database.execute("UPDATE outbox SET requires_receipt=1,event_type='notif.action.invoke' WHERE link_id=? AND msg_id=?",
                [.text(item.linkID), .text(envelope.messageID)])
            try database.execute("""
                INSERT INTO action_invocation(link_id,invocation_id,msg_id,canon_id,action_id,sequence,expires_at)
                VALUES(?,?,?,?,?,?,?)
                """, [.text(item.linkID), .text(id), .text(envelope.messageID), .text(item.canonicalID),
                    .text(actionID), .integer(item.presentation.sequence), .integer(envelope.expiresAt)])
            return try actionAttempt(item: item, actionID: actionID)!
        }
    }
    private func actionable(item: InboxItem, actionID: String) throws -> NotificationAction {
        guard let state = try desired(linkID: item.linkID, canonicalID: item.canonicalID),
              state.active, !state.remove, state.locallyDismissed != true, state.sequence == item.presentation.sequence,
              try materializedSequence(linkID: item.linkID, canonicalID: item.canonicalID) >= state.sequence,
              let action = state.actions?.first(where: { $0.id == actionID }) else { throw ActionError.unavailable }
        return action
    }
    private func actionAttempt(item: InboxItem, actionID: String) throws -> ActionAttempt? {
        try database.execute("SELECT * FROM action_invocation WHERE link_id=? AND canon_id=? AND action_id=? AND sequence=?",
            [.text(item.linkID), .text(item.canonicalID), .text(actionID), .integer(item.presentation.sequence)]).first.map(Self.attempt)
    }
    private static func attempt(_ row: [String: SQLValue]) throws -> ActionAttempt {
        try ActionAttempt(id: row.text("invocation_id"), linkID: row.text("link_id"), canonicalID: row.text("canon_id"),
            actionID: row.text("action_id"), sequence: row.integer("sequence"), status: row.text("status"))
    }
    public func actionAttempts(now: Int64) throws -> [ActionAttempt] {
        try database.transaction {
            try database.execute("UPDATE action_invocation SET status='timed_out' WHERE status='pending' AND expires_at<=?", [.integer(now)])
            // Retain immutable bytes until a result or local expiry, including
            // after relay/direct custody. No automatic invocation replacement.
            try database.execute("DELETE FROM outbox WHERE requires_receipt=1 AND expires_at<=?", [.integer(now)])
            // A tombstone remains as long as the same notification generation is
            // active, preventing an old button from creating a new side effect.
            try database.execute("""
                DELETE FROM action_invocation WHERE expires_at<? AND status!='pending' AND NOT EXISTS (
                  SELECT 1 FROM desired d WHERE d.link_id=action_invocation.link_id
                    AND d.canon_id=action_invocation.canon_id AND d.sequence=action_invocation.sequence)
                """, [.integer(max(0, now - 172_800_000))])
            return try database.execute("""
                SELECT a.* FROM action_invocation a JOIN peer_link p ON p.id=a.link_id AND p.lifecycle='active'
                ORDER BY a.expires_at DESC LIMIT 2000
                """).map(Self.attempt)
        }
    }
    public func commitActionResult(linkID: String, event: InnerEvent, digest: String, now: Int64) throws {
        guard event.type == "notif.action.result", let id = event.payload["invocation_id"]?.string,
              let canonical = event.payload["canon_id"]?.string, let status = event.payload["status"]?.string,
              ["dispatched", "outcome_unknown", "action_gone", "notification_gone", "expired", "failed"].contains(status) else {
            throw ProtocolError.invalidPacket
        }
        try database.transaction {
            try requireActive(linkID)
            if let previous = try received(linkID: linkID, messageID: event.messageID), previous.digest != digest { throw DeliveryStoreError.digestConflict }
            if now < event.expiresAt, let row = try database.execute("SELECT * FROM action_invocation WHERE link_id=? AND invocation_id=?",
                [.text(linkID), .text(id)]).first {
                guard try row.text("canon_id") == canonical else { throw ProtocolError.identityMismatch }
                let previous = try row.text("status")
                guard ["pending", "timed_out", status].contains(previous) else { throw DeliveryStoreError.digestConflict }
                try database.execute("UPDATE action_invocation SET status=? WHERE link_id=? AND invocation_id=?", [.text(status), .text(linkID), .text(id)])
                try database.execute("DELETE FROM outbox WHERE link_id=? AND msg_id=?", [.text(linkID), .text(row.text("msg_id"))])
            }
            try recordDirect(linkID: linkID, event: event, digest: digest, now: now, outcome: now >= event.expiresAt ? .expired : .applied)
        }
    }
}
