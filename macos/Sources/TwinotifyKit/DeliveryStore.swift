import Foundation

public enum LinkLifecycle: String, Codable, Sendable { case active, removing }

public struct PeerLink: Codable, Sendable, Equatable {
    public let id: String
    public let deviceID: String
    public let pairID: String
    public let relayURL: String
    public let encryptionKey: Data
    public let signingKey: Data
    public var lifecycle: LinkLifecycle

    public init(id: String = UUID().uuidString.lowercased(), deviceID: String, pairID: String,
                relayURL: String, encryptionKey: Data, signingKey: Data, lifecycle: LinkLifecycle = .active) {
        self.id = id; self.deviceID = deviceID; self.pairID = pairID; self.relayURL = relayURL
        self.encryptionKey = encryptionKey; self.signingKey = signingKey; self.lifecycle = lifecycle
    }
}

public enum DeliveryStoreError: Error, Equatable {
    case invalidLink, missingLink, removingLink, digestConflict, invalidTransition, invalidRecord
}

public struct DeliveryLimits: Sendable {
    public var journalRows = 50_000
    public var desiredRows = 4_096
    public var contentBytes = 32 * 1024 * 1024
    public var outboxRows = 2_000
    public var outboxBytes = 128 * 1024 * 1024
    public init() {}
}

public enum DeliveryOutcome: String, Codable, Sendable {
    case pending, applied, expired, rejected, decryptFailed = "decrypt_failed"
}

public struct ReceivedRecord: Sendable {
    public let linkID: String
    public let messageID: String
    public let digest: String
    public let canonicalID: String?
    public let sequence: Int64?
    public let expiresAt: Int64
    public let outcome: DeliveryOutcome
    public let receiptID: String?
    public let ackReady: Bool
    public let eventType: String
}

public struct DesiredRecord: Codable, Sendable, Equatable {
    public let canonicalID: String
    public let sequence: Int64
    public let expiresAt: Int64
    public let remove: Bool
    public let active: Bool
    public let title: String
    public let subtitle: String
    public let body: String
    public let imagePNG: Data?
    public let sourceApp: String?
    public var locallyDismissed: Bool? = false

    public init(canonicalID: String, sequence: Int64, expiresAt: Int64, remove: Bool,
                title: String = "", subtitle: String = "", body: String = "", active: Bool? = nil, imagePNG: Data? = nil,
                sourceApp: String? = nil) {
        self.canonicalID = canonicalID; self.sequence = sequence; self.expiresAt = expiresAt
        self.remove = remove; self.title = title; self.subtitle = subtitle; self.body = body
        self.active = active ?? !remove
        self.imagePNG = imagePNG
        self.sourceApp = sourceApp
    }

    func retainingLocalDismissal(from previous: DesiredRecord?) -> DesiredRecord {
        var result = self
        result.locallyDismissed = previous?.locallyDismissed == true && previous.map { hasSamePresentation(as: $0) } == true
        return result
    }

    /// Android may advance a notification's sequence without changing what the
    /// user sees. Transport ordering alone must not create another local alert.
    func hasSamePresentation(as other: DesiredRecord) -> Bool {
        canonicalID == other.canonicalID && remove == other.remove && active == other.active &&
        title == other.title && subtitle == other.subtitle && body == other.body && imagePNG == other.imagePNG
    }
}

public struct StoredEnvelope: Sendable {
    public let messageID: String
    public let bytes: Data
    public let digest: String
    public let expiresAt: Int64
}

extension DurableStore {
    static func createDeliverySchema(_ db: SQLiteConnection) throws {
        try db.execute("""
            CREATE TABLE peer_link(id TEXT PRIMARY KEY, device_id TEXT NOT NULL, pair_id TEXT NOT NULL UNIQUE,
              record BLOB NOT NULL, lifecycle TEXT NOT NULL CHECK(lifecycle IN ('active','removing')))
            """)
        try db.execute("""
            CREATE TABLE inbound(link_id TEXT NOT NULL REFERENCES peer_link(id) ON DELETE CASCADE,
              msg_id TEXT NOT NULL, digest TEXT NOT NULL, canon_id TEXT, sequence INTEGER, expires_at INTEGER NOT NULL,
              outcome TEXT NOT NULL, receipt_id TEXT, receipt BLOB, receipt_expires INTEGER,
              ack_state TEXT NOT NULL DEFAULT 'WAITING', committed_at INTEGER NOT NULL, event_type TEXT NOT NULL,
              PRIMARY KEY(link_id,msg_id))
            """)
        try db.execute("""
            CREATE TABLE desired(link_id TEXT NOT NULL REFERENCES peer_link(id) ON DELETE CASCADE,
              canon_id TEXT NOT NULL, sequence INTEGER NOT NULL, content BLOB NOT NULL,
              materialized INTEGER NOT NULL DEFAULT 0, retry_at INTEGER, blocked INTEGER NOT NULL DEFAULT 0,
              dirty INTEGER NOT NULL DEFAULT 1,
              PRIMARY KEY(link_id,canon_id))
            """)
        try db.execute("""
            CREATE TABLE outbox(link_id TEXT NOT NULL REFERENCES peer_link(id) ON DELETE CASCADE,
              msg_id TEXT NOT NULL, envelope BLOB NOT NULL, digest TEXT NOT NULL, expires_at INTEGER NOT NULL,
              retry_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(link_id,msg_id))
            """)
        try db.execute("CREATE INDEX inbound_pending ON inbound(link_id,outcome)")
        try db.execute("CREATE INDEX outbox_due ON outbox(link_id,retry_at)")
        try db.execute("CREATE TABLE pending_pair(id TEXT PRIMARY KEY, content BLOB NOT NULL, expires_at INTEGER NOT NULL)")
        try db.execute("""
            CREATE TABLE snapshot_stage(link_id TEXT NOT NULL REFERENCES peer_link(id) ON DELETE CASCADE,
              snapshot_id TEXT NOT NULL, canon_id TEXT NOT NULL, content BLOB NOT NULL, received_at INTEGER NOT NULL,
              PRIMARY KEY(link_id,snapshot_id,canon_id))
            """)
        try db.execute("""
            CREATE TABLE activity(id INTEGER PRIMARY KEY, link_id TEXT NOT NULL, msg_id TEXT NOT NULL,
              outcome TEXT NOT NULL, occurred_at INTEGER NOT NULL)
            """)
    }

    public func peers() throws -> [PeerLink] {
        try database.execute("SELECT record,lifecycle FROM peer_link ORDER BY id").map { row in
            var link = try JSONDecoder().decode(PeerLink.self, from: row.blob("record"))
            guard let lifecycle = LinkLifecycle(rawValue: try row.text("lifecycle")) else { throw StorageError.repairRequired }
            link.lifecycle = lifecycle
            return link
        }
    }

    public func addPeer(_ link: PeerLink) throws {
        try database.transaction { try insertPeer(link) }
    }

    private func insertPeer(_ link: PeerLink) throws {
        guard UUID(uuidString: link.id) != nil, (1...128).contains(link.deviceID.utf8.count), !link.deviceID.contains("\0"),
              UUID(uuidString: link.pairID) != nil, link.deviceID != identity.deviceID,
              link.encryptionKey.count == 32, link.signingKey.count == 32, link.lifecycle == .active else {
            throw DeliveryStoreError.invalidLink
        }
        let existing = try peers()
        if let same = existing.first(where: { $0.id == link.id }) {
            guard same == link else { throw DeliveryStoreError.invalidLink }
            return
        }
        guard !existing.contains(where: { $0.deviceID == link.deviceID || $0.pairID == link.pairID }) else {
            throw DeliveryStoreError.invalidLink
        }
        guard existing.count < 2 else { throw StorageError.capacityExceeded }
        try database.execute("INSERT INTO peer_link VALUES(?,?,?,?,?)", [
            .text(link.id), .text(link.deviceID), .text(link.pairID),
            .blob(try JSONEncoder().encode(link)), .text(link.lifecycle.rawValue)
        ])
    }

    public func finishPairing(_ link: PeerLink, pendingID: String) throws {
        try database.transaction {
            guard link.id == pendingID,
                  !(try database.execute("SELECT id FROM pending_pair WHERE id=?", [.text(pendingID)])).isEmpty else {
                throw DeliveryStoreError.invalidTransition
            }
            try insertPeer(link)
            try clearPendingPair(id: pendingID)
        }
    }

    func requireActive(_ linkID: String) throws {
        guard let row = try database.execute("SELECT lifecycle FROM peer_link WHERE id=?", [.text(linkID)]).first else {
            throw DeliveryStoreError.missingLink
        }
        guard try row.text("lifecycle") == "active" else { throw DeliveryStoreError.removingLink }
    }

    /// Disables new work first. Credentials remain available for remote revoke retries.
    public func beginRemoval(_ linkID: String) throws {
        try database.execute("UPDATE peer_link SET lifecycle='removing' WHERE id=?", [.text(linkID)])
    }

    /// Call only after sessions have joined, platform entries are removed and relay revoke succeeded.
    public func finishRemoval(_ linkID: String) throws {
        try database.transaction {
            guard let row = try database.execute("SELECT lifecycle FROM peer_link WHERE id=?", [.text(linkID)]).first else { return }
            guard try row.text("lifecycle") == "removing" else { throw DeliveryStoreError.invalidTransition }
            try database.execute("DELETE FROM peer_link WHERE id=?", [.text(linkID)])
            try database.execute("DELETE FROM activity WHERE link_id=?", [.text(linkID)])
        }
    }

    public func received(linkID: String, messageID: String) throws -> ReceivedRecord? {
        guard let row = try database.execute("SELECT * FROM inbound WHERE link_id=? AND msg_id=?",
                                             [.text(linkID), .text(messageID)]).first else { return nil }
        guard let outcome = DeliveryOutcome(rawValue: try row.text("outcome")) else { throw StorageError.repairRequired }
        return ReceivedRecord(linkID: linkID, messageID: messageID, digest: try row.text("digest"),
                              canonicalID: row.optionalText("canon_id"), sequence: row.optionalInteger("sequence"),
                              expiresAt: try row.integer("expires_at"), outcome: outcome,
                              receiptID: row.optionalText("receipt_id"), ackReady: try row.text("ack_state") == "READY",
                              eventType: try row.text("event_type"))
    }

    /// Authenticated callers only. Duplicate IDs must retain their exact envelope digest.
    /// Journal and desired content commit together; no platform success is implied here.
    public func stage(linkID: String, messageID: String, digest: String, expiresAt: Int64,
                      desired: DesiredRecord?, now: Int64, eventType: String = "unsupported", limits: DeliveryLimits = .init()) throws -> ReceivedRecord {
        guard UUID(uuidString: messageID) != nil, digest.count == 64,
              digest.allSatisfy({ $0.isASCII && ("0123456789abcdef".contains($0)) }),
              desired == nil || (desired!.sequence > 0 && !desired!.canonicalID.isEmpty) else {
            throw DeliveryStoreError.invalidRecord
        }
        return try database.transaction {
            try requireActive(linkID)
            if let existing = try received(linkID: linkID, messageID: messageID) {
                guard existing.digest == digest else { throw DeliveryStoreError.digestConflict }
                return existing
            }
            guard try count("inbound") < limits.journalRows else { throw StorageError.capacityExceeded }
            if let desired {
                let old = try database.execute("SELECT sequence FROM desired WHERE link_id=? AND canon_id=?",
                                               [.text(linkID), .text(desired.canonicalID)]).first
                if try old == nil || old!.integer("sequence") < desired.sequence {
                    let previous = try self.desired(linkID: linkID, canonicalID: desired.canonicalID)
                    let reuse = try previous.map {
                        try $0.hasSamePresentation(as: desired) &&
                        (try materializedSequence(linkID: linkID, canonicalID: desired.canonicalID)) >= $0.sequence
                    } ?? false
                    guard try old != nil || count("desired") < limits.desiredRows else { throw StorageError.capacityExceeded }
                    let context = contentContext(linkID, desired.canonicalID)
                    let content = try sealContent(JSONEncoder().encode(desired.retainingLocalDismissal(from: previous)), context: context)
                    let oldSize = try database.execute("SELECT length(content) AS bytes FROM desired WHERE link_id=? AND canon_id=?",
                                                      [.text(linkID), .text(desired.canonicalID)]).first?.integer("bytes") ?? 0
                    guard try size("desired", "content") - oldSize + Int64(content.count) <= limits.contentBytes else {
                        throw StorageError.capacityExceeded
                    }
                    try database.execute("""
                        INSERT INTO desired(link_id,canon_id,sequence,content) VALUES(?,?,?,?)
                        ON CONFLICT(link_id,canon_id) DO UPDATE SET sequence=excluded.sequence,content=excluded.content,
                          blocked=0,retry_at=NULL,dirty=?,
                          materialized=CASE WHEN ?=1 THEN excluded.sequence ELSE desired.materialized END
                        """, [.text(linkID), .text(desired.canonicalID), .integer(desired.sequence), .blob(content),
                                .integer(reuse ? 0 : 1), .integer(reuse ? 1 : 0)])
                }
            }
            try database.execute("""
                INSERT INTO inbound(link_id,msg_id,digest,canon_id,sequence,expires_at,outcome,committed_at,event_type)
                VALUES(?,?,?,?,?,?,'pending',?,?)
                """, [.text(linkID), .text(messageID), .text(digest), desired.map { .text($0.canonicalID) } ?? .null,
                        desired.map { .integer($0.sequence) } ?? .null, .integer(expiresAt), .integer(now), .text(eventType)])
            return try received(linkID: linkID, messageID: messageID)!
        }
    }

    public func desired(linkID: String, canonicalID: String) throws -> DesiredRecord? {
        guard let row = try database.execute("SELECT content FROM desired WHERE link_id=? AND canon_id=?",
                                            [.text(linkID), .text(canonicalID)]).first else { return nil }
        return try JSONDecoder().decode(DesiredRecord.self, from: openContent(row.blob("content"),
                                        context: contentContext(linkID, canonicalID)))
    }

    public func pending(linkID: String, now: Int64, permissionRecovered: Bool = false) throws -> [ReceivedRecord] {
        let rows = try database.execute("""
            SELECT i.msg_id FROM inbound i LEFT JOIN desired d ON d.link_id=i.link_id AND d.canon_id=i.canon_id
            WHERE i.link_id=? AND i.outcome='pending' AND
              (i.expires_at<=? OR d.sequence>i.sequence OR ?=1 OR
               (COALESCE(d.blocked,0)=0 AND (d.retry_at IS NULL OR d.retry_at<=?)))
            ORDER BY i.committed_at LIMIT 32
            """, [.text(linkID), .integer(now), .integer(permissionRecovered ? 1 : 0), .integer(now)])
        return try rows.compactMap { try received(linkID: linkID, messageID: $0.text("msg_id")) }
    }

    public func deferMaterialization(linkID: String, canonicalID: String, sequence: Int64,
                                     permissionBlocked: Bool, retryAt: Int64?) throws {
        try database.execute("UPDATE desired SET blocked=?,retry_at=? WHERE link_id=? AND canon_id=? AND sequence=?",
                             [.integer(permissionBlocked ? 1 : 0), retryAt.map(SQLValue.integer) ?? .null,
                              .text(linkID), .text(canonicalID), .integer(sequence)])
    }

    /// The immutable receipt is retained in the journal even after its outbox row is removed.
    public func complete(linkID: String, messageID: String, digest: String, outcome: DeliveryOutcome,
                         receipt: StoredEnvelope, now: Int64, limits: DeliveryLimits = .init()) throws {
        guard outcome != .pending, UUID(uuidString: receipt.messageID) != nil,
              receipt.digest == RawEnvelope.digest(receipt.bytes) else { throw DeliveryStoreError.invalidRecord }
        try database.transaction {
            try requireActive(linkID)
            guard let row = try received(linkID: linkID, messageID: messageID), row.digest == digest else {
                throw DeliveryStoreError.digestConflict
            }
            if row.outcome != .pending { return }
            guard try size("inbound", "receipt") + Int64(receipt.bytes.count) <= limits.outboxBytes else {
                throw StorageError.capacityExceeded
            }
            try insertOutbound(linkID: linkID, envelope: receipt, limits: limits)
            try database.execute("""
                UPDATE inbound SET outcome=?,receipt_id=?,receipt=?,receipt_expires=? WHERE link_id=? AND msg_id=?
                """, [.text(outcome.rawValue), .text(receipt.messageID), .blob(receipt.bytes), .integer(receipt.expiresAt),
                        .text(linkID), .text(messageID)])
            if outcome == .applied, let canonicalID = row.canonicalID, let sequence = row.sequence {
                try database.execute("""
                    UPDATE desired SET materialized=MAX(materialized,?),blocked=0,retry_at=NULL,dirty=0
                    WHERE link_id=? AND canon_id=? AND sequence=?
                    """, [.integer(sequence), .text(linkID), .text(canonicalID), .integer(sequence)])
            }
            try database.execute("INSERT INTO activity(link_id,msg_id,outcome,occurred_at) VALUES(?,?,?,?)",
                                 [.text(linkID), .text(messageID), .text(outcome.rawValue), .integer(now)])
            try database.execute("DELETE FROM activity WHERE id NOT IN (SELECT id FROM activity ORDER BY id DESC LIMIT 500)")
        }
    }

    func insertOutbound(linkID: String, envelope: StoredEnvelope, limits: DeliveryLimits) throws {
        if let old = try database.execute("SELECT digest FROM outbox WHERE link_id=? AND msg_id=?",
                                          [.text(linkID), .text(envelope.messageID)]).first {
            guard try old.text("digest") == envelope.digest else { throw DeliveryStoreError.digestConflict }
            return
        }
        guard try count("outbox") < limits.outboxRows,
              try size("outbox", "envelope") + Int64(envelope.bytes.count) <= limits.outboxBytes else {
            throw StorageError.capacityExceeded
        }
        try database.execute("INSERT INTO outbox(link_id,msg_id,envelope,digest,expires_at) VALUES(?,?,?,?,?)",
                             [.text(linkID), .text(envelope.messageID), .blob(envelope.bytes),
                              .text(envelope.digest), .integer(envelope.expiresAt)])
    }

    public func sendable(linkID: String, now: Int64) throws -> [StoredEnvelope] {
        try requireActive(linkID)
        return try database.execute("SELECT * FROM outbox WHERE link_id=? AND retry_at<=? AND expires_at>? LIMIT 32",
                                    [.text(linkID), .integer(now), .integer(now)]).map {
            StoredEnvelope(messageID: try $0.text("msg_id"), bytes: try $0.blob("envelope"),
                           digest: try $0.text("digest"), expiresAt: try $0.integer("expires_at"))
        }
    }

    public func markSent(linkID: String, messageID: String, retryAt: Int64) throws {
        try database.execute("UPDATE outbox SET retry_at=? WHERE link_id=? AND msg_id=?",
                             [.integer(retryAt), .text(linkID), .text(messageID)])
    }

    public func receiptAccepted(linkID: String, messageID: String) throws {
        try database.transaction {
            try requireActive(linkID)
            // A custody frame can only advance a receipt actually submitted by this link.
            guard try database.execute("SELECT msg_id FROM outbox WHERE link_id=? AND msg_id=?",
                                       [.text(linkID), .text(messageID)]).first != nil else { return }
            try database.execute("UPDATE inbound SET ack_state='READY' WHERE link_id=? AND receipt_id=? AND outcome!='pending'",
                                 [.text(linkID), .text(messageID)])
            try database.execute("DELETE FROM outbox WHERE link_id=? AND msg_id=?", [.text(linkID), .text(messageID)])
        }
    }

    public func readyAcks(linkID: String) throws -> [ReceivedRecord] {
        try database.execute("SELECT msg_id FROM inbound WHERE link_id=? AND ack_state='READY' LIMIT 32", [.text(linkID)])
            .compactMap { try received(linkID: linkID, messageID: $0.text("msg_id")) }
    }

    public func markAckSent(linkID: String, messageID: String, digest: String) throws {
        try database.execute("UPDATE inbound SET ack_state='SENT' WHERE link_id=? AND msg_id=? AND digest=? AND ack_state='READY'",
                             [.text(linkID), .text(messageID), .text(digest)])
    }

    public func replayReceipt(linkID: String, messageID: String, digest: String, limits: DeliveryLimits = .init()) throws {
        try database.transaction {
            try requireActive(linkID)
            guard let row = try database.execute("SELECT * FROM inbound WHERE link_id=? AND msg_id=?",
                                                [.text(linkID), .text(messageID)]).first else { return }
            guard try row.text("digest") == digest else { throw DeliveryStoreError.digestConflict }
            guard let receiptID = row.optionalText("receipt_id") else { return }
            let bytes = try row.blob("receipt")
            try insertOutbound(linkID: linkID, envelope: StoredEnvelope(messageID: receiptID, bytes: bytes,
                                digest: RawEnvelope.digest(bytes), expiresAt: row.integer("receipt_expires")), limits: limits)
            try database.execute("UPDATE inbound SET ack_state='WAITING' WHERE link_id=? AND msg_id=?", [.text(linkID), .text(messageID)])
        }
    }

    func contentContext(_ linkID: String, _ canonicalID: String) -> Data {
        Data("desired:\(linkID.utf8.count):\(linkID)\(canonicalID)".utf8)
    }

    // Table and column names are private constants at every call site.
    private func count(_ table: String) throws -> Int64 {
        try database.execute("SELECT count(*) AS value FROM \(table)").first!.integer("value")
    }
    private func size(_ table: String, _ column: String) throws -> Int64 {
        try database.execute("SELECT COALESCE(sum(length(\(column))),0) AS value FROM \(table)").first!.integer("value")
    }
}

extension Dictionary where Key == String, Value == SQLValue {
    func text(_ key: String) throws -> String {
        guard case .text(let value) = self[key] else { throw StorageError.repairRequired }; return value
    }
    func blob(_ key: String) throws -> Data {
        guard case .blob(let value) = self[key] else { throw StorageError.repairRequired }; return value
    }
    func integer(_ key: String) throws -> Int64 {
        guard case .integer(let value) = self[key] else { throw StorageError.repairRequired }; return value
    }
    func optionalText(_ key: String) -> String? { if case .text(let value) = self[key] { return value }; return nil }
    func optionalInteger(_ key: String) -> Int64? { if case .integer(let value) = self[key] { return value }; return nil }
}

extension DurableStore {
    public func savePendingPair(id: String, plaintext: Data, expiresAt: Int64) throws {
        guard plaintext.count <= 16_384 else { throw StorageError.capacityExceeded }
        let encrypted = try sealContent(plaintext, context: Data(("pair:" + id).utf8))
        try database.transaction {
            guard try database.execute("SELECT id FROM pending_pair WHERE id!=?", [.text(id)]).isEmpty else {
                throw StorageError.capacityExceeded
            }
            try database.execute("INSERT INTO pending_pair VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET content=excluded.content,expires_at=excluded.expires_at",
                                 [.text(id), .blob(encrypted), .integer(expiresAt)])
        }
    }
    public func updatePendingPair(id: String, plaintext: Data, expiresAt: Int64) throws {
        guard plaintext.count <= 16_384 else { throw StorageError.capacityExceeded }
        let encrypted = try sealContent(plaintext, context: Data(("pair:" + id).utf8))
        try database.transaction {
            guard !(try database.execute("SELECT id FROM pending_pair WHERE id=?", [.text(id)])).isEmpty else {
                throw DeliveryStoreError.invalidTransition
            }
            try database.execute("UPDATE pending_pair SET content=?,expires_at=? WHERE id=?", [.blob(encrypted), .integer(expiresAt), .text(id)])
        }
    }
    public func pendingPair() throws -> Data? {
        guard let row = try database.execute("SELECT * FROM pending_pair LIMIT 1").first else { return nil }
        return try openContent(row.blob("content"), context: Data(("pair:" + row.text("id")).utf8))
    }
    public func clearPendingPair(id: String) throws {
        try database.execute("DELETE FROM pending_pair WHERE id=?", [.text(id)])
    }
}

extension DurableStore {
    /// No receipt-of-receipt. A mismatched digest cannot retire another outbound envelope.
    public func commitPeerReceipt(linkID: String, event: InnerEvent, digest: String, now: Int64) throws {
        guard event.type == "peer.receipt", let ackedID = event.payload["acked_msg_id"]?.string,
              let ackedDigest = event.payload["envelope_sha256"]?.string else { throw ProtocolError.invalidPacket }
        try database.transaction {
            try requireActive(linkID)
            if let existing = try received(linkID: linkID, messageID: event.messageID) {
                guard existing.digest == digest else { throw DeliveryStoreError.digestConflict }
                try database.execute("UPDATE inbound SET ack_state='READY' WHERE link_id=? AND msg_id=?", [.text(linkID), .text(event.messageID)])
                return
            }
            if let row = try database.execute("SELECT digest FROM outbox WHERE link_id=? AND msg_id=?",
                                              [.text(linkID), .text(ackedID)]).first {
                guard try row.text("digest") == ackedDigest else { throw DeliveryStoreError.digestConflict }
                try database.execute("DELETE FROM outbox WHERE link_id=? AND msg_id=?", [.text(linkID), .text(ackedID)])
            }
            guard try database.execute("SELECT count(*) AS n FROM inbound").first!.integer("n") < DeliveryLimits().journalRows else {
                throw StorageError.capacityExceeded
            }
            try database.execute("""
                INSERT INTO inbound(link_id,msg_id,digest,expires_at,outcome,committed_at,event_type,ack_state)
                VALUES(?,?,?,?,'applied',?,'peer.receipt','READY')
                """, [.text(linkID), .text(event.messageID), .text(digest), .integer(event.expiresAt), .integer(now)])
        }
    }

    public func materializedSequence(linkID: String, canonicalID: String) throws -> Int64 {
        try database.execute("SELECT materialized FROM desired WHERE link_id=? AND canon_id=?",
                             [.text(linkID), .text(canonicalID)]).first?.integer("materialized") ?? 0
    }
}

extension DurableStore {
    public func receiptsNeedingRenewal(linkID: String, now: Int64) throws -> [ReceivedRecord] {
        try database.execute("""
            SELECT msg_id FROM inbound WHERE link_id=? AND receipt_id IS NOT NULL
              AND outcome!='pending' AND ack_state='WAITING' AND receipt_expires<=? LIMIT 32
            """, [.text(linkID), .integer(now)]).compactMap { try received(linkID: linkID, messageID: $0.text("msg_id")) }
    }
    public func renewReceipt(linkID: String, original: ReceivedRecord, receipt: StoredEnvelope, now: Int64) throws {
        try database.transaction {
            try requireActive(linkID)
            guard let current = try received(linkID: linkID, messageID: original.messageID),
                  current.digest == original.digest, current.outcome == original.outcome,
                  current.outcome != .pending, let oldID = current.receiptID,
                  receipt.digest == RawEnvelope.digest(receipt.bytes), receipt.expiresAt > now else { throw DeliveryStoreError.invalidTransition }
            let row = try database.execute("SELECT receipt_expires FROM inbound WHERE link_id=? AND msg_id=?",
                                           [.text(linkID), .text(original.messageID)]).first!
            guard try row.integer("receipt_expires") <= now else { return }
            try database.execute("DELETE FROM outbox WHERE link_id=? AND msg_id=?", [.text(linkID), .text(oldID)])
            try insertOutbound(linkID: linkID, envelope: receipt, limits: .init())
            try database.execute("UPDATE inbound SET receipt_id=?,receipt=?,receipt_expires=?,ack_state='WAITING' WHERE link_id=? AND msg_id=?",
                [.text(receipt.messageID), .blob(receipt.bytes), .integer(receipt.expiresAt), .text(linkID), .text(original.messageID)])
        }
    }
    public func sweepCompleted(now: Int64) throws {
        guard now >= 172_800_000 else { return }
        let cutoff = now - 172_800_000
        try database.transaction {
            try database.execute("DELETE FROM inbound WHERE outcome!='pending' AND ack_state='SENT' AND expires_at<? AND committed_at<?",
                                 [.integer(cutoff), .integer(cutoff)])
            try database.execute("DELETE FROM snapshot_stage WHERE received_at<?", [.integer(max(0, now - 600_000))])
        }
    }
}

public struct PeerCounts: Sendable { public let pending: Int; public let outbound: Int }
public struct ActivityRecord: Sendable, Identifiable {
    public let id: Int64
    public let linkID: String
    public let messageID: String
    public let outcome: String
    public let occurredAt: Int64
}
extension DurableStore {
    public func counts(linkID: String) throws -> PeerCounts {
        let pending = try database.execute("SELECT count(*) AS n FROM inbound WHERE link_id=? AND outcome='pending'", [.text(linkID)]).first!.integer("n")
        let outbound = try database.execute("SELECT count(*) AS n FROM outbox WHERE link_id=?", [.text(linkID)]).first!.integer("n")
        return PeerCounts(pending: Int(pending), outbound: Int(outbound))
    }
    public func recentActivity() throws -> [ActivityRecord] {
        try database.execute("SELECT * FROM activity ORDER BY id DESC LIMIT 20").map {
            ActivityRecord(id: try $0.integer("id"), linkID: try $0.text("link_id"), messageID: try $0.text("msg_id"),
                           outcome: try $0.text("outcome"), occurredAt: try $0.integer("occurred_at"))
        }
    }
    public func clearRemovingWork(linkID: String) throws {
        try database.transaction {
            guard let peer = try peers().first(where: { $0.id == linkID }), peer.lifecycle == .removing else { throw DeliveryStoreError.invalidTransition }
            for table in ["inbound", "desired", "outbox", "snapshot_stage", "activity"] {
                try database.execute("DELETE FROM \(table) WHERE link_id=?", [.text(linkID)])
            }
        }
    }
}

#if TWINOTIFY_E2E
extension DurableStore {
    public func e2eSnapshotCommits() throws -> Int64 {
        try database.execute("SELECT count(*) AS n FROM inbound WHERE event_type='state.snapshot.end' AND outcome='applied'").first!.integer("n")
    }
}
#endif
