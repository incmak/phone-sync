import Foundation
import ImageIO
import UserNotifications
import TwinotifyKit

@MainActor final class SystemNotifications: NSObject, NotificationPlatform, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()

    override init() {
        super.init()
        center.delegate = self
    }

    func authorization() async -> UNAuthorizationStatus {
        await center.notificationSettings().authorizationStatus
    }

    func requestPermission() async throws -> Bool {
        try await center.requestAuthorization(options: [.alert, .sound, .badge])
    }

    func post(_ presentation: NotificationPresentation) async throws -> PlatformOutcome {
        let status = await authorization()
        guard status == .authorized || status == .provisional else { return .permissionBlocked }
        let delivered = await center.deliveredNotifications().map(\.request)
        let pending = await center.pendingNotificationRequests()
        if (delivered + pending).contains(where: {
            $0.identifier == presentation.identifier &&
            ($0.content.userInfo["sequence"] as? NSNumber)?.int64Value == presentation.sequence
        }) { return .applied }
        let content = UNMutableNotificationContent()
        content.title = presentation.title
        content.subtitle = presentation.subtitle
        content.body = presentation.body
        content.sound = .default
        content.userInfo = ["sequence": NSNumber(value: presentation.sequence)]
        let attachmentDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("tw-notification-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: attachmentDirectory) }
        if let png = presentation.imagePNG, png.count <= 512 * 1024,
           let source = CGImageSourceCreateWithData(png as CFData, nil), CGImageSourceGetType(source) as String? == "public.png",
           let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
           let width = properties[kCGImagePropertyPixelWidth] as? Int, let height = properties[kCGImagePropertyPixelHeight] as? Int,
           (1...2048).contains(width), (1...2048).contains(height) {
            do {
                try FileManager.default.createDirectory(at: attachmentDirectory, withIntermediateDirectories: false,
                                                        attributes: [.posixPermissions: 0o700])
                let url = attachmentDirectory.appendingPathComponent("image.png")
                try png.write(to: url, options: .withoutOverwriting)
                content.attachments = [try UNNotificationAttachment(identifier: "image", url: url)]
            } catch { /* Attachment failure preserves text delivery. */ }
        }
        try await center.add(UNNotificationRequest(identifier: presentation.identifier, content: content, trigger: nil))
        return .applied
    }

    func remove(identifier: String) async {
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                           willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
