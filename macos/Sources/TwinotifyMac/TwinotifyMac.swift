import AppKit
import SwiftUI
import TwinotifyKit

@MainActor final class AppDelegate: NSObject, NSApplicationDelegate {
    let model = AppModel()
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        Task { await model.stop(); sender.reply(toApplicationShouldTerminate: true) }
        return .terminateLater
    }
}

@main struct TwinotifyMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    var body: some Scene {
        MenuBarExtra {
            NotificationInboxView(model: delegate.model)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "bell.and.waves.left.and.right")
                Text("\(delegate.model.inbox.count)").monospacedDigit()
            }
            .accessibilityLabel("Twinotify, \(delegate.model.inboxCountDescription)")
        }.menuBarExtraStyle(.window)
        Settings { TwinotifySettings(model: delegate.model) }
            .windowResizability(.contentMinSize)
            .defaultSize(width: 480, height: 680)
    }
}

private struct TwinotifySettings: View {
    @Bindable var model: AppModel
    @Environment(\.colorScheme) private var colorScheme
    @State private var removing: PeerLink?
    @State private var importingPhoneCode = false
    var body: some View {
        Form {
            if let problem = model.storageProblem {
                Section("Delivery paused") { Text(problem).foregroundStyle(.red).textSelection(.enabled) }
            }
            if let pending = model.pending {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let expired = Int64(context.date.timeIntervalSince1970 * 1000) >= pending.expiresAt
                    Section(pending.peer == nil ? "Scan with your phone" : "Confirm fingerprints") {
                        if expired {
                            Text("Pairing code expired").font(.headline)
                            Text("Create a new code, then scan it with your phone.").foregroundStyle(.secondary)
                            Button("Create new code") { Task { model.relayURL = pending.qr.relayURL; await model.cancelPairing(); model.createPairingCode() } }
                        } else if let peer = pending.peer {
                            Text("Compare these with the fingerprints shown on your phone.").font(.callout)
                            Text("Phone").font(.headline)
                            Text((try? peer.fingerprint) ?? "Unavailable").font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                            Text("This Mac").font(.headline)
                            Text(model.ownFingerprint).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                            Button(model.busy ? "Waiting for phone…" : "Fingerprints match — pair") { model.confirmPairing() }
                                .disabled(model.busy)
                        } else {
                            Text("In Twinotify on your phone, choose Scan QR code and point the camera here.")
                                .font(.callout)
                            if let image = model.pairingImage {
                                HStack {
                                    Spacer()
                                    Image(nsImage: image).interpolation(.none).resizable().scaledToFit()
                                        .frame(width: 240, height: 240).padding(16).background(.white)
                                        .accessibilityLabel("Pairing QR code. Scan with Twinotify on your phone.")
                                    Spacer()
                                }
                            }
                            Text("Expires in \(max(0, (pending.expiresAt - Int64(context.date.timeIntervalSince1970 * 1000)) / 1000)) seconds")
                                .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                            if model.busy { Label("Waiting for phone…", systemImage: "iphone") }
                            else { Button("Resume waiting for phone") { model.resumeWaitingForPhone() } }
                        }
                        if let problem = model.pairingProblem { Text(problem).font(.callout).foregroundStyle(.red) }
                        Button("Cancel pairing") { Task { await model.cancelPairing() } }
                    }
                }
            } else if model.peers.count < 2 && model.storageProblem == nil {
                Section("Pair a phone") {
                    Text("Show a QR code here, then scan it with Twinotify on your phone.").font(.callout).foregroundStyle(.secondary)
                    TextField("Relay address", text: $model.relayURL, prompt: Text("https://relay.example.com"))
                        .textContentType(.URL).disabled(model.busy)
                    Text("Use the same relay as your phone.").font(.caption).foregroundStyle(.secondary)
                    Button(model.busy ? "Creating code…" : "Show pairing QR code") { model.createPairingCode() }
                        .disabled(model.busy || model.relayURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if let problem = model.pairingProblem { Text(problem).font(.callout).foregroundStyle(.red) }
                    Button {
                        importingPhoneCode.toggle()
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: importingPhoneCode ? "chevron.down" : "chevron.right")
                                .font(.caption).foregroundStyle(.secondary)
                            Text("Scan a code from your phone instead")
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityValue(importingPhoneCode ? "Expanded" : "Collapsed")
                    .disabled(model.busy)
                    if importingPhoneCode {
                        Button("Import QR image…") { Task { await model.importQR() } }
                            .disabled(model.busy)
                        TextEditor(text: $model.qrText).font(.system(.caption, design: .monospaced)).frame(height: 70)
                            .accessibilityLabel("Pairing QR JSON")
                            .disabled(model.busy)
                        Button("Read pairing code") { Task { await model.parseQR() } }.disabled(model.busy || model.qrText.isEmpty)
                    }
                }
            }
            Section("Notifications") {
                Picker("Show notifications in", selection: Binding(get: { model.destination }, set: { model.setDestination($0) })) {
                    ForEach(NotificationDestination.allCases) { destination in
                        Text(destination.title).tag(destination)
                    }
                }
                Text("The menu bar count shows current mirrored notifications. Updates replace their existing entry; phone dismissals remove it.")
                    .font(.callout).foregroundStyle(.secondary)
                if model.destination == .menuBar {
                    Text("System alerts are off. Notifications stay in the encrypted app inbox, without requiring Notification Center permission.")
                        .font(.callout).foregroundStyle(.secondary)
                } else {
                    LabeledContent("Permission", value: model.permission)
                }
                if model.destination == .notificationCenter && model.permission != "Allowed" {
                    Button("Allow notifications") { Task { await model.requestPermission() } }
                    Button("Open notification settings") {
                        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") { NSWorkspace.shared.open(url) }
                    }
                    Text("Pending notifications wait for permission. Expired notifications are not shown when permission returns.")
                        .font(.callout).foregroundStyle(.secondary)
                }
                Text("Changing this choice won’t replay notifications already received.")
                    .font(.caption).foregroundStyle(.secondary)
                if model.destination == .notificationCenter {
                    Button("Send test notification") { Task { await model.postTest() } }
                    Button("Remove test notification") { Task { await model.removeTest() } }
                }
            }
            Section("Phones") {
                if model.peers.isEmpty { Text("Pair a phone to receive its notifications here.").foregroundStyle(.secondary) }
                ForEach(model.peers, id: \.id) { peer in
                    VStack(alignment: .leading, spacing: 8) {
                        LabeledContent("Phone \(peer.deviceID.prefix(8))", value: model.routeDescription(peer.id))
                        if let count = model.counts[peer.id] {
                            Text("\(count.pending) pending · \(count.outbound) awaiting relay").font(.callout).foregroundStyle(.secondary)
                        }
                        if peer.lifecycle == .active {
                            Button("Remove connection", role: .destructive) { removing = peer }
                                .accessibilityLabel("Remove phone \(peer.deviceID.prefix(8))")
                        } else { Text("Disabled. Relay cleanup will retry when reachable.").font(.callout).foregroundStyle(.secondary) }
                    }
                }
                Text("Dismissing a notification on this Mac only removes the local copy.").font(.callout).foregroundStyle(.secondary)
            }
            Section("General") {
                Toggle("Launch at login", isOn: Binding(get: { model.launchAtLogin }, set: { model.setLaunchAtLogin($0) }))
                if !model.activity.isEmpty { Text(model.activity).font(.callout).foregroundStyle(.secondary).textSelection(.enabled) }
            }
            if !model.history.isEmpty {
                Section("Recent activity") {
                    ForEach(model.history) { event in
                        LabeledContent(event.outcome.replacingOccurrences(of: "_", with: " ").capitalized) {
                            Text(Date(timeIntervalSince1970: Double(event.occurredAt) / 1000), style: .time).foregroundStyle(.secondary)
                        }
                    }
                    Text("Only delivery metadata is kept here.").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .formStyle(.grouped)
        // Twinotify's existing Seam primary roles; layout and typography stay native.
        .tint(colorScheme == .dark ? Color(red: 155 / 255, green: 190 / 255, blue: 174 / 255)
                                  : Color(red: 31 / 255, green: 104 / 255, blue: 90 / 255))
        .frame(minWidth: 380, idealWidth: 480, minHeight: 460, idealHeight: 680)
        .confirmationDialog("Remove this phone’s connection?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            if let peer = removing { Button("Remove connection", role: .destructive) { Task { await model.remove(peer) }; removing = nil } }
        } message: { Text("Its mirrored notifications and pending deliveries will be removed from this Mac.") }
    }
}
