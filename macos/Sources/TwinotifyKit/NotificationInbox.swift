import Foundation

public enum NotificationDestination: String, CaseIterable, Identifiable, Sendable {
    case notificationCenter, menuBar
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .notificationCenter: "Notification Center + inbox"
        case .menuBar: "Menu bar inbox only"
        }
    }
}

/// The encrypted desired state is the inbox. Inbox-only delivery therefore
/// completes through the same durable receipt path without requiring OS alerts.
@MainActor public final class NotificationRouter: NotificationPlatform {
    public var destination: NotificationDestination
    private let system: any NotificationPlatform

    public init(system: any NotificationPlatform, destination: NotificationDestination) {
        self.system = system
        self.destination = destination
    }
    public func post(_ presentation: NotificationPresentation) async throws -> PlatformOutcome {
        if destination == .menuBar { return .applied }
        return try await system.post(presentation)
    }
    public func remove(identifier: String) async { await system.remove(identifier: identifier) }
}

public struct InboxItem: Identifiable, Sendable {
    public var id: String { presentation.identifier }
    public let canonicalID: String
    public let linkID: String
    public let receivedAt: Int64?
    public let presentation: NotificationPresentation
}

public struct InboxPage: Sendable {
    public let items: [InboxItem]
    public let index: Int
    public let pageCount: Int
    public let total: Int
    public let first: Int
    public let last: Int

    public init(items: [InboxItem], index: Int, size: Int = 8) {
        let size = max(1, size)
        total = items.count
        pageCount = max(1, (total + size - 1) / size)
        self.index = min(max(0, index), pageCount - 1)
        let offset = self.index * size
        self.items = Array(items.dropFirst(offset).prefix(size))
        first = total == 0 ? 0 : offset + 1
        last = offset + self.items.count
    }
}

extension DurableStore {
    /// One entry per link/canonical notification, including after a restart.
    /// Cancelled, filtered, unmaterialized and removed-peer content stays hidden.
    public func notificationInbox(now: Int64) throws -> [InboxItem] {
        try database.execute("""
            SELECT d.link_id,d.canon_id,d.content,i.received_at FROM desired d
            JOIN peer_link p ON p.id=d.link_id AND p.lifecycle='active'
            LEFT JOIN (
              SELECT link_id,canon_id,MAX(committed_at) AS received_at FROM inbound
              WHERE canon_id IS NOT NULL GROUP BY link_id,canon_id
            ) i ON i.link_id=d.link_id AND i.canon_id=d.canon_id
            WHERE d.materialized>=d.sequence
            ORDER BY COALESCE(i.received_at,0) DESC,d.link_id,d.canon_id
            """).compactMap { row in
                let link = try row.text("link_id"), canonicalID = try row.text("canon_id")
                let state = try JSONDecoder().decode(DesiredRecord.self, from: openContent(row.blob("content"),
                    context: contentContext(link, canonicalID)))
                guard state.active, !state.remove, state.locallyDismissed != true,
                      !canonicalID.hasPrefix("call:") || now < state.expiresAt else { return nil }
                let receivedAt: Int64?
                if case .integer(let value) = row["received_at"] { receivedAt = value } else { receivedAt = nil }
                return InboxItem(canonicalID: canonicalID, linkID: link, receivedAt: receivedAt,
                    presentation: NotificationPresentation(linkGeneration: link, canonicalID: canonicalID,
                        sequence: state.sequence, title: state.title, subtitle: state.subtitle, body: state.body,
                        imagePNG: state.imagePNG, sourceApp: state.sourceApp, actions: state.actions ?? []))
            }
    }
}


extension DurableStore {
    /// Changes only local presentation state, never the phone or delivery receipts.
    /// Stale UI actions cannot clear a newer presentation. Undo may restore an
    /// unchanged higher sequence, but cannot revive cancelled or changed content.
    public func setInboxDismissed(_ items: [InboxItem], dismissed: Bool) throws -> [InboxItem] {
        try database.transaction {
            var changed: [InboxItem] = []
            var bytes = try database.execute("SELECT COALESCE(sum(length(content)),0) AS bytes FROM desired").first!.integer("bytes")
            for item in items {
                guard let row = try database.execute("""
                    SELECT d.content,d.materialized FROM desired d
                    JOIN peer_link p ON p.id=d.link_id AND p.lifecycle='active'
                    WHERE d.link_id=? AND d.canon_id=?
                    """, [.text(item.linkID), .text(item.canonicalID)]).first else { continue }
                let old = try row.blob("content")
                let context = contentContext(item.linkID, item.canonicalID)
                var state = try JSONDecoder().decode(DesiredRecord.self, from: openContent(old, context: context))
                let presentation = item.presentation
                guard state.active, !state.remove, (state.locallyDismissed == true) != dismissed,
                      try row.integer("materialized") >= state.sequence,
                      (!dismissed || state.sequence == presentation.sequence),
                      state.title == presentation.title, state.subtitle == presentation.subtitle,
                      state.body == presentation.body, state.imagePNG == presentation.imagePNG else { continue }
                state.locallyDismissed = dismissed
                let content = try sealContent(JSONEncoder().encode(state), context: context)
                bytes += Int64(content.count - old.count)
                guard bytes <= DeliveryLimits().contentBytes else { throw StorageError.capacityExceeded }
                try database.execute("UPDATE desired SET content=? WHERE link_id=? AND canon_id=?",
                    [.blob(content), .text(item.linkID), .text(item.canonicalID)])
                changed.append(item)
            }
            return changed
        }
    }
}
