#if TWINOTIFY_E2E
import AppKit
import CryptoKit
import Foundation
import TwinotifyKit
import UserNotifications

/// Compiled only into an explicitly requested E2E bundle. The host owns a private,
/// synthetic run directory; ordinary bundles contain no control endpoint.
@MainActor final class E2EControl {
    struct Request: Decodable {
        let id: String
        let operation: String
        let value: String?
    }
    let directory: URL
    private var task: Task<Void, Never>?

    static func directoryFromEnvironment() throws -> URL? {
        guard let raw = ProcessInfo.processInfo.environment["TWINOTIFY_E2E_DIRECTORY"] else { return nil }
        let url = URL(fileURLWithPath: raw, isDirectory: true).standardizedFileURL
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard UUID(uuidString: url.lastPathComponent) != nil,
              url.resolvingSymlinksInPath() == url,
              attributes[.type] as? FileAttributeType == .typeDirectory,
              (attributes[.ownerAccountID] as? NSNumber)?.uint32Value == getuid(),
              (attributes[.posixPermissions] as? NSNumber)?.intValue == 0o700 else {
            throw CocoaError(.fileReadNoPermission)
        }
        return url
    }

    init(directory: URL, model: AppModel) {
        self.directory = directory
        task = Task { [weak model] in
            while !Task.isCancelled {
                guard let model else { return }
                let files = (try? FileManager.default.contentsOfDirectory(at: directory,
                    includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey])) ?? []
                for file in files.filter({ $0.lastPathComponent.hasSuffix(".request.json") }).sorted(by: { $0.path < $1.path }).prefix(16) {
                    do {
                        let values = try file.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .isSymbolicLinkKey])
                        guard values.isRegularFile == true, values.isSymbolicLink != true,
                              (values.fileSize ?? Int.max) <= 65_536 else { continue }
                        let request = try JSONDecoder().decode(Request.self, from: Data(contentsOf: file))
                        guard UUID(uuidString: request.id) != nil,
                              file.lastPathComponent == request.id + ".request.json" else { continue }
                        // Claim before acting. A crash leaves an unknown result for the host;
                        // it must query state instead of replaying a destructive command.
                        let claimed = directory.appendingPathComponent(request.id + ".claimed.json")
                        try FileManager.default.moveItem(at: file, to: claimed)
                        let response: [String: Any]
                        do {
                            response = ["id": request.id, "code": "ok", "payload": try await model.executeE2E(request)]
                        } catch {
                            let detail: String
                            if let failure = error as? ProtocolError { detail = String(describing: failure) }
                            else if let failure = error as? PairingError { detail = String(describing: failure) }
                            else if error is StorageError { detail = "storage_failed" }
                            else { detail = "operation_failed" }
                            response = ["id": request.id, "code": "error", "detail": detail]
                        }
                        let data = try JSONSerialization.data(withJSONObject: response, options: [.sortedKeys])
                        try data.write(to: directory.appendingPathComponent(request.id + ".response.json"), options: .atomic)
                        try FileManager.default.removeItem(at: claimed)
                    } catch { /* A malformed request cannot operate on app state. */ }
                }
                try? await Task.sleep(for: .milliseconds(200))
            }
        }
    }
    func stop() { task?.cancel() }
    static func hash(_ value: String) -> String { SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined() }
}
#endif
