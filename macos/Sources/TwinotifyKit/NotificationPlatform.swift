import Foundation

public enum PlatformOutcome: Sendable, Equatable {
    case applied, permissionBlocked
}

public struct NotificationPresentation: Sendable {
    public let identifier: String
    public let sequence: Int64
    public let title: String
    public let subtitle: String
    public let body: String
    public let imagePNG: Data?
    public let sourceApp: String?

    public init(linkGeneration: String, canonicalID: String, sequence: Int64, title: String, subtitle: String = "", body: String, imagePNG: Data? = nil,
                sourceApp: String? = nil) {
        // Length-prefix the link to avoid collisions between components containing separators.
        self.identifier = "tw." + RawEnvelope.digest(Data("\(linkGeneration.utf8.count):\(linkGeneration)\(canonicalID)".utf8))
        self.sequence = sequence
        self.title = title
        self.subtitle = subtitle
        self.body = body
        self.imagePNG = imagePNG
        self.sourceApp = sourceApp
    }
}

@MainActor public protocol NotificationPlatform {
    func post(_ presentation: NotificationPresentation) async throws -> PlatformOutcome
    func remove(identifier: String) async
}
