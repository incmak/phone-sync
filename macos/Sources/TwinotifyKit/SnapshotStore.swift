import Foundation

public enum SnapshotError: Error { case invalid, missingBegin, conflict, incomplete, digestMismatch }
private struct SnapshotHeader: Codable {
    let count: Int
    let baseline: [String: Int64]
}
private let snapshotHeaderKey = "\u{0}begin"

public struct OriginSummary: Sendable {
    public let count: Int
    public let digest: String
}

extension DurableStore {
    public func originSummary(linkID: String) throws -> OriginSummary {
        let active = try allDesired(linkID: linkID).filter { $0.active && !$0.canonicalID.hasPrefix("call:") }
        return OriginSummary(count: active.count, digest: Self.stateDigest(active))
    }
    static func stateDigest(_ records: [DesiredRecord]) -> String {
        let ordered = records.sorted { $0.canonicalID.utf16.lexicographicallyPrecedes($1.canonicalID.utf16) }
        return RawEnvelope.digest(Data(ordered.map { "\($0.canonicalID)\u{0}\($0.sequence)\u{0}ACTIVE" }.joined(separator: "\n").utf8))
    }
    public func allDesired(linkID: String) throws -> [DesiredRecord] {
        try database.execute("SELECT canon_id FROM desired WHERE link_id=?", [.text(linkID)])
            .compactMap { try desired(linkID: linkID, canonicalID: $0.text("canon_id")) }
    }
    public func enqueueControl(linkID: String, envelope: StoredEnvelope) throws {
        try database.transaction {
            try requireActive(linkID)
            try insertOutbound(linkID: linkID, envelope: envelope, limits: .init())
        }
    }
    public func commitDirect(linkID: String, event: InnerEvent, digest: String, now: Int64, outcome: DeliveryOutcome = .applied) throws {
        try database.transaction {
            try requireActive(linkID)
            try recordDirect(linkID: linkID, event: event, digest: digest, now: now, outcome: outcome)
        }
    }
    func recordDirect(linkID: String, event: InnerEvent, digest: String, now: Int64, outcome: DeliveryOutcome = .applied) throws {
        if let old = try received(linkID: linkID, messageID: event.messageID) {
            guard old.digest == digest else { throw DeliveryStoreError.digestConflict }
            try database.execute("UPDATE inbound SET ack_state='READY' WHERE link_id=? AND msg_id=?", [.text(linkID), .text(event.messageID)])
            return
        }
        guard try database.execute("SELECT count(*) AS n FROM inbound").first!.integer("n") < DeliveryLimits().journalRows else {
            throw StorageError.capacityExceeded
        }
        try database.execute("""
            INSERT INTO inbound(link_id,msg_id,digest,expires_at,outcome,committed_at,event_type,ack_state)
            VALUES(?,?,?,?,?,?,?,'READY')
            """, [.text(linkID), .text(event.messageID), .text(digest), .integer(event.expiresAt), .text(outcome.rawValue), .integer(now), .text(event.type)])
    }

    /// Staging is invisible until end count and digest validate. The begin baseline
    /// prevents a missing snapshot item from cancelling a newer live event.
    public func processSnapshot(linkID: String, event: InnerEvent, digest: String, now: Int64) throws {
        guard let id = event.payload["snapshot_id"]?.string, !id.isEmpty, id.utf8.count <= 128,
              event.payload["origin_device"] == nil || event.payload["origin_device"]?.string == event.originDevice else {
            throw SnapshotError.invalid
        }
        try database.transaction {
            try requireActive(linkID)
            if let old = try received(linkID: linkID, messageID: event.messageID) {
                guard old.digest == digest else { throw DeliveryStoreError.digestConflict }
                try recordDirect(linkID: linkID, event: event, digest: digest, now: now)
                return
            }
            try database.execute("DELETE FROM snapshot_stage WHERE received_at<?", [.integer(max(0, now - 600_000))])
            if now >= event.expiresAt {
                try recordDirect(linkID: linkID, event: event, digest: digest, now: now, outcome: .expired)
                return
            }
            let rows = try database.execute("SELECT * FROM snapshot_stage WHERE link_id=? AND snapshot_id=?",
                                            [.text(linkID), .text(id)])
            switch event.type {
            case "state.snapshot.begin":
                guard rows.isEmpty, let count = (event.payload["item_count"] ?? event.payload["count"])?.integer,
                      (0...4_096).contains(count) else { throw SnapshotError.invalid }
                guard try database.execute("SELECT count(*) AS n FROM snapshot_stage WHERE canon_id=?", [.text(snapshotHeaderKey)]).first!.integer("n") < 4 else {
                    throw StorageError.capacityExceeded
                }
                let baseline = Dictionary(uniqueKeysWithValues: try allDesired(linkID: linkID)
                    .filter { !$0.canonicalID.hasPrefix("call:") }.map { ($0.canonicalID, $0.sequence) })
                try insertSnapshot(linkID: linkID, snapshotID: id, canonicalID: snapshotHeaderKey,
                                   data: JSONEncoder().encode(SnapshotHeader(count: Int(count), baseline: baseline)), now: now)
            case "state.snapshot.item":
                guard let headerRow = try rows.first(where: { try $0.text("canon_id") == snapshotHeaderKey }) else { throw SnapshotError.missingBegin }
                let header = try JSONDecoder().decode(SnapshotHeader.self, from: snapshotContent(headerRow, linkID: linkID, snapshotID: id))
                guard let canonicalID = event.canonicalID, !canonicalID.hasPrefix("call:"), !canonicalID.contains("\u{0}"),
                      let sequence = event.sequence, let payload = event.payload["notification_payload"] ?? event.payload["payload"] else {
                    throw SnapshotError.invalid
                }
                try SchemaValidator().validate(payload, schema: "notif-post")
                guard payload["canon_id"]?.string == canonicalID, try payload.encoded().count <= 512 * 1024 else { throw SnapshotError.invalid }
                let synthetic = InnerEvent(messageID: event.messageID, originDevice: event.originDevice, type: "notif.post",
                    canonicalID: canonicalID, sequence: sequence, createdAt: event.createdAt, expiresAt: event.expiresAt, payload: payload)
                let state = try ReliableReceiver.presentation(synthetic)!
                if let old = try rows.first(where: { try $0.text("canon_id") == canonicalID }) {
                    let previous = try JSONDecoder().decode(DesiredRecord.self, from: snapshotContent(old, linkID: linkID, snapshotID: id))
                    guard previous == state else { throw SnapshotError.conflict }
                } else {
                    guard rows.count - 1 < header.count else { throw StorageError.capacityExceeded }
                    try insertSnapshot(linkID: linkID, snapshotID: id, canonicalID: canonicalID, data: JSONEncoder().encode(state), now: now)
                }
            case "state.snapshot.end":
                guard let headerRow = try rows.first(where: { try $0.text("canon_id") == snapshotHeaderKey }) else { throw SnapshotError.missingBegin }
                let header = try JSONDecoder().decode(SnapshotHeader.self, from: snapshotContent(headerRow, linkID: linkID, snapshotID: id))
                let items = try rows.filter { try $0.text("canon_id") != snapshotHeaderKey }.map {
                    try JSONDecoder().decode(DesiredRecord.self, from: snapshotContent($0, linkID: linkID, snapshotID: id))
                }
                guard items.count == header.count else { throw SnapshotError.incomplete }
                guard event.payload["digest"]?.string == Self.stateDigest(items) else { throw SnapshotError.digestMismatch }
                for item in items {
                    let current = try desired(linkID: linkID, canonicalID: item.canonicalID)
                    if let current, current.sequence > item.sequence { continue }
                    // Same sequence and terminal local dismissal does not re-alert.
                    let materialized = try materializedSequence(linkID: linkID, canonicalID: item.canonicalID)
                    let reuse = current.map { $0.hasSamePresentation(as: item) && materialized >= $0.sequence } ?? false
                    try putSnapshotDesired(linkID: linkID, state: item, dirty: !reuse)
                    if reuse { try markDesiredApplied(linkID: linkID, state: item) }
                }
                let present = Set(items.map(\.canonicalID))
                for (canonicalID, sequence) in header.baseline where !present.contains(canonicalID) {
                    guard let current = try desired(linkID: linkID, canonicalID: canonicalID), current.sequence == sequence else { continue }
                    try putSnapshotDesired(linkID: linkID, state: DesiredRecord(canonicalID: canonicalID, sequence: sequence,
                        expiresAt: event.expiresAt, remove: true), dirty: true)
                }
                try database.execute("DELETE FROM snapshot_stage WHERE link_id=? AND snapshot_id=?", [.text(linkID), .text(id)])
            default: throw SnapshotError.invalid
            }
            try recordDirect(linkID: linkID, event: event, digest: digest, now: now)
        }
    }
    private func insertSnapshot(linkID: String, snapshotID: String, canonicalID: String, data: Data, now: Int64) throws {
        let encrypted = try sealContent(data, context: snapshotContext(linkID, snapshotID, canonicalID))
        let bytes = try database.execute("SELECT COALESCE(sum(length(content)),0) AS n FROM snapshot_stage").first!.integer("n")
        guard bytes + Int64(encrypted.count) <= DeliveryLimits().contentBytes else { throw StorageError.capacityExceeded }
        try database.execute("INSERT INTO snapshot_stage VALUES(?,?,?,?,?)",
            [.text(linkID), .text(snapshotID), .text(canonicalID), .blob(encrypted), .integer(now)])
    }
    private func snapshotContent(_ row: [String: SQLValue], linkID: String, snapshotID: String) throws -> Data {
        try openContent(row.blob("content"), context: snapshotContext(linkID, snapshotID, row.text("canon_id")))
    }
    private func snapshotContext(_ link: String, _ snapshot: String, _ canonical: String) -> Data {
        Data("snapshot:\(link.utf8.count):\(link)\(snapshot.utf8.count):\(snapshot)\(canonical)".utf8)
    }
    private func putSnapshotDesired(linkID: String, state: DesiredRecord, dirty: Bool) throws {
        let state = try state.retainingLocalDismissal(from: desired(linkID: linkID, canonicalID: state.canonicalID))
        let encrypted = try sealContent(JSONEncoder().encode(state), context: contentContext(linkID, state.canonicalID))
        let current = try database.execute("SELECT length(content) AS n FROM desired WHERE link_id=? AND canon_id=?", [.text(linkID), .text(state.canonicalID)]).first
        let sizes = try database.execute("SELECT count(*) AS rows,COALESCE(sum(length(content)),0) AS bytes FROM desired").first!
        guard try (current != nil || sizes.integer("rows") < DeliveryLimits().desiredRows),
              try sizes.integer("bytes") - (current?.integer("n") ?? 0) + Int64(encrypted.count) <= DeliveryLimits().contentBytes else {
            throw StorageError.capacityExceeded
        }
        try database.execute("""
            INSERT INTO desired(link_id,canon_id,sequence,content,dirty) VALUES(?,?,?,?,?)
            ON CONFLICT(link_id,canon_id) DO UPDATE SET sequence=excluded.sequence,content=excluded.content,
              dirty=excluded.dirty,blocked=0,retry_at=NULL
            """, [.text(linkID), .text(state.canonicalID), .integer(state.sequence), .blob(encrypted), .integer(dirty ? 1 : 0)])
    }
    public func desiredWork(linkID: String, now: Int64, permissionRecovered: Bool) throws -> [DesiredRecord] {
        try database.execute("""
            SELECT canon_id FROM desired d WHERE link_id=? AND dirty=1
              AND (?=1 OR (blocked=0 AND (retry_at IS NULL OR retry_at<=?)))
              AND NOT EXISTS(SELECT 1 FROM inbound i WHERE i.link_id=d.link_id AND i.canon_id=d.canon_id
                AND i.sequence=d.sequence AND i.outcome='pending') LIMIT 32
            """, [.text(linkID), .integer(permissionRecovered ? 1 : 0), .integer(now)])
            .compactMap { try desired(linkID: linkID, canonicalID: $0.text("canon_id")) }
    }
    public func markDesiredApplied(linkID: String, state: DesiredRecord) throws {
        try database.execute("UPDATE desired SET dirty=0,materialized=?,blocked=0,retry_at=NULL WHERE link_id=? AND canon_id=? AND sequence=?",
                             [.integer(state.sequence), .text(linkID), .text(state.canonicalID), .integer(state.sequence)])
    }
    public func markDesiredExpired(linkID: String, state: DesiredRecord) throws {
        try database.execute("UPDATE desired SET dirty=0,blocked=0,retry_at=NULL WHERE link_id=? AND canon_id=? AND sequence=?",
                             [.text(linkID), .text(state.canonicalID), .integer(state.sequence)])
    }
}

extension DurableStore {
    public func callPresentations(linkID: String) throws -> [DesiredRecord] {
        try database.execute("SELECT canon_id FROM desired WHERE link_id=? AND canon_id LIKE 'call:%'", [.text(linkID)])
            .compactMap { try desired(linkID: linkID, canonicalID: $0.text("canon_id")) }
    }
    public func retireCall(linkID: String, state: DesiredRecord) throws {
        guard state.canonicalID.hasPrefix("call:") else { throw DeliveryStoreError.invalidRecord }
        let retired = DesiredRecord(canonicalID: state.canonicalID, sequence: state.sequence,
                                    expiresAt: state.expiresAt, remove: true)
        let content = try sealContent(JSONEncoder().encode(retired), context: contentContext(linkID, state.canonicalID))
        try database.execute("UPDATE desired SET content=?,dirty=0,blocked=0,retry_at=NULL WHERE link_id=? AND canon_id=? AND sequence=?",
                             [.blob(content), .text(linkID), .text(state.canonicalID), .integer(state.sequence)])
    }
}
