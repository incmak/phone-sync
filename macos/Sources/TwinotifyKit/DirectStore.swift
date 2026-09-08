import Foundation

public struct LanBinding: Codable, Sendable, Equatable {
    public let peerPin: Data
    public let contextDigest: Data
}

extension DurableStore {
    static func createDirectSchema(_ db: SQLiteConnection) throws {
        try db.execute("ALTER TABLE outbox ADD COLUMN requires_receipt INTEGER NOT NULL DEFAULT 0")
        try db.execute("ALTER TABLE outbox ADD COLUMN event_type TEXT NOT NULL DEFAULT 'peer.receipt'")
        try db.execute("ALTER TABLE outbox ADD COLUMN custody_at INTEGER")
        try db.execute("""
            CREATE TABLE lan_binding(link_id TEXT PRIMARY KEY REFERENCES peer_link(id) ON DELETE CASCADE,
              content BLOB NOT NULL)
            """)
        try db.execute("""
            CREATE TABLE action_invocation(link_id TEXT NOT NULL REFERENCES peer_link(id) ON DELETE CASCADE,
              invocation_id TEXT NOT NULL, msg_id TEXT NOT NULL, canon_id TEXT NOT NULL,
              action_id TEXT NOT NULL, sequence INTEGER NOT NULL, expires_at INTEGER NOT NULL,
              status TEXT NOT NULL DEFAULT 'pending', PRIMARY KEY(link_id,invocation_id))
            """)
        try db.execute("CREATE UNIQUE INDEX action_target ON action_invocation(link_id,canon_id,action_id,sequence)")
    }

    public func lanBinding(linkID: String) throws -> LanBinding? {
        try requireActive(linkID)
        guard let row = try database.execute("SELECT content FROM lan_binding WHERE link_id=?", [.text(linkID)]).first else { return nil }
        let binding = try JSONDecoder().decode(LanBinding.self, from: openContent(row.blob("content"), context: Data("lan:\(linkID)".utf8)))
        guard binding.peerPin.count == 32, binding.contextDigest.count == 32 else { throw StorageError.repairRequired }
        return binding
    }

    public func commitLanBinding(peer: PeerLink, pin: Data, contextDigest: Data) throws {
        guard pin.count == 32, try lanMaterial(peer: peer).contextDigest == contextDigest else { throw LanError.authentication }
        try database.transaction {
            let binding = LanBinding(peerPin: pin, contextDigest: contextDigest)
            if let existing = try lanBinding(linkID: peer.id) {
                guard existing == binding else { throw LanError.bindingConflict }
                return
            }
            let sealed = try sealContent(JSONEncoder().encode(binding), context: Data("lan:\(peer.id)".utf8))
            try database.execute("INSERT INTO lan_binding VALUES(?,?)", [.text(peer.id), .blob(sealed)])
        }
    }

    public func enqueueControl(linkID: String, envelope: StoredEnvelope, type: String, requiresReceipt: Bool) throws {
        try database.transaction {
            try requireActive(linkID)
            try insertOutbound(linkID: linkID, envelope: envelope, limits: .init())
            try database.execute("UPDATE outbox SET requires_receipt=?,event_type=? WHERE link_id=? AND msg_id=?",
                [.integer(requiresReceipt ? 1 : 0), .text(type), .text(linkID), .text(envelope.messageID)])
        }
    }
    public func hasPendingControl(linkID: String, type: String, now: Int64) throws -> Bool {
        try requireActive(linkID)
        return try database.execute("SELECT msg_id FROM outbox WHERE link_id=? AND event_type=? AND expires_at>? LIMIT 1",
            [.text(linkID), .text(type), .integer(now)]).first != nil
    }
    public func makeDueAfterRouteChange(linkID: String) throws {
        try requireActive(linkID)
        try database.execute("UPDATE outbox SET retry_at=0 WHERE link_id=?", [.text(linkID)])
    }
    /// LAN acknowledgements have stronger digest requirements than relay custody.
    public func directAccepted(linkID: String, messageID: String, digest: String, now: Int64) throws {
        try database.transaction {
            try requireActive(linkID)
            guard let row = try database.execute("SELECT digest FROM outbox WHERE link_id=? AND msg_id=?",
                [.text(linkID), .text(messageID)]).first else { return }
            guard try row.text("digest") == digest else { throw DeliveryStoreError.digestConflict }
            try acceptOutboundCustody(linkID: linkID, messageID: messageID, now: now)
        }
    }
}
