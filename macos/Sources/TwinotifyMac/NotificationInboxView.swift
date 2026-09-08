import AppKit
import ImageIO
import SwiftUI
import TwinotifyKit

private enum InboxStyle {
    static let width: CGFloat = 360
    static let inset: CGFloat = 14
    static let listHeight: CGFloat = 360
    static let background = Color(nsColor: .windowBackgroundColor)
    static let secondary = Color.primary.opacity(0.60)
    static let body = Color.primary.opacity(0.82)
    static let separator = Color(nsColor: .separatorColor).opacity(0.5)
}

struct NotificationInboxView: View {
    @Bindable var model: AppModel
    @State private var listContentHeight: CGFloat = 106

    var body: some View {
        let page = model.inboxPage
        VStack(spacing: 0) {
            header
            rule
            if let problem = model.storageProblem {
                Label(problem, systemImage: "exclamationmark.triangle")
                    .font(.system(size: 11)).foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(InboxStyle.inset)
            }
            if page.items.isEmpty {
                emptyState
            } else {
                ScrollViewReader { scroll in
                    ScrollView {
                        VStack(spacing: 0) {
                            Color.clear.frame(height: 0).id("inbox-top")
                            ForEach(page.items) { item in
                                InboxRow(model: model, item: item, phone: phoneName(item.linkID), showsPhone: model.peers.count > 1, clearing: model.clearingInbox) {
                                    Task { await model.clearInbox([item]) }
                                }
                                if item.id != page.items.last?.id {
                                    rule.padding(.leading, 54).padding(.trailing, InboxStyle.inset)
                                }
                            }
                        }
                        .background(GeometryReader { geometry in
                            Color.clear.preference(key: InboxContentHeight.self, value: geometry.size.height)
                        })
                    }
                    .onPreferenceChange(InboxContentHeight.self) { if $0 > 0 { listContentHeight = $0 } }
                    .frame(height: min(InboxStyle.listHeight, listContentHeight))
                    .onChange(of: page.index) { scroll.scrollTo("inbox-top", anchor: .top) }
                }
                if page.pageCount > 1 { pagination(page) }
            }
            if model.destination == .notificationCenter && model.permission != "Allowed" {
                SettingsLink {
                    Label("Allow system notifications", systemImage: "exclamationmark.bubble")
                        .font(.system(size: 11))
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, InboxStyle.inset).padding(.bottom, 10)
            }
            rule
            footer
        }
        .frame(width: InboxStyle.width)
        .background(InboxStyle.background)
        .task { await model.refreshInbox() }
    }

    private var header: some View {
        HStack(spacing: 7) {
            Text("Notifications").font(.system(size: 14, weight: .semibold))
            if !model.inbox.isEmpty {
                Text("\(model.inbox.count)")
                    .font(.system(size: 12)).monospacedDigit().foregroundStyle(InboxStyle.secondary)
            }
            Spacer()
            if model.canUndoClear {
                Button("Undo") { Task { await model.undoClearInbox() } }
                    .buttonStyle(.plain).font(.system(size: 11)).disabled(model.clearingInbox)
                    .help("Restore the last cleared notifications to this inbox")
            }
            if !model.inbox.isEmpty {
                Button("Clear all") { Task { await model.clearInbox(model.inbox) } }
                    .buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(InboxStyle.secondary)
                    .disabled(model.clearingInbox).help("Clear all notifications on this Mac")
            }
            SettingsLink {
                Image(systemName: "gearshape").font(.system(size: 14))
                    .frame(width: 26, height: 26).contentShape(Rectangle())
            }
            .buttonStyle(.plain).foregroundStyle(InboxStyle.secondary)
            .accessibilityLabel("Open Twinotify settings").help("Open Twinotify settings (⌘,)")
            .keyboardShortcut(",")
        }
        .padding(.horizontal, InboxStyle.inset).padding(.vertical, 10)
    }

    private var emptyState: some View {
        VStack(spacing: 7) {
            Image(systemName: model.peers.isEmpty ? "iphone.and.arrow.forward" : "tray")
                .font(.system(size: 26, weight: .light)).foregroundStyle(.tertiary)
                .padding(.bottom, 3)
            Text(model.peers.isEmpty ? "Connect a phone" : "No active notifications")
                .font(.system(size: 13, weight: .medium))
            Text(model.peers.isEmpty ? "Pair your phone to bring its notifications here." : "New notifications from your phone appear here.")
                .font(.system(size: 11)).foregroundStyle(InboxStyle.secondary)
                .multilineTextAlignment(.center).frame(maxWidth: 230)
            if model.peers.isEmpty {
                SettingsLink { Text("Pair a phone…") }.font(.system(size: 11)).padding(.top, 3)
            }
        }
        .frame(maxWidth: .infinity).frame(height: model.peers.isEmpty ? 156 : 136)
    }

    private func pagination(_ page: InboxPage) -> some View {
        HStack(spacing: 8) {
            Text("\(page.first)–\(page.last) of \(page.total)")
                .font(.system(size: 10)).monospacedDigit().foregroundStyle(InboxStyle.secondary)
                .accessibilityLabel("Page \(page.index + 1) of \(page.pageCount), notifications \(page.first) through \(page.last)")
            Spacer()
            Button { model.inboxPageIndex = page.index - 1 } label: {
                Image(systemName: "chevron.left").frame(width: 24, height: 24)
            }
            .disabled(page.index == 0).accessibilityLabel("Previous page").help("Previous page")
            Button { model.inboxPageIndex = page.index + 1 } label: {
                Image(systemName: "chevron.right").frame(width: 24, height: 24)
            }
            .disabled(page.index + 1 >= page.pageCount).accessibilityLabel("Next page").help("Next page")
        }
        .font(.system(size: 11, weight: .medium)).buttonStyle(.plain)
        .padding(.horizontal, InboxStyle.inset).padding(.vertical, 4)
    }

    private var footer: some View {
        HStack(spacing: 7) {
            Circle().fill(connectedCount > 0 ? Color.green : Color.secondary).frame(width: 5, height: 5)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(connectionSummary).lineLimit(1)
                let pending = model.counts.values.reduce(0) { $0 + $1.pending }
                if pending > 0 { Text("\(pending) waiting to appear").foregroundStyle(.orange) }
            }.font(.system(size: 10)).foregroundStyle(InboxStyle.secondary)
            Spacer(minLength: 8)
            Menu {
                Picker("Show notifications in", selection: Binding(get: { model.destination }, set: { model.setDestination($0) })) {
                    ForEach(NotificationDestination.allCases) { destination in
                        Text(destination.title).tag(destination)
                    }
                }.pickerStyle(.inline)
                Divider()
                Button("Quit Twinotify") { NSApplication.shared.terminate(nil) }.keyboardShortcut("q")
            } label: {
                Text(model.destination == .menuBar ? "Inbox only" : "Banners on")
            }
            .menuStyle(.borderlessButton).menuIndicator(.visible).controlSize(.small)
            .font(.system(size: 10)).foregroundStyle(InboxStyle.secondary).fixedSize()
            .accessibilityLabel("Notification delivery options")
            .help("Choose where notifications appear")
        }
        .padding(.horizontal, InboxStyle.inset).padding(.vertical, 11)
    }

    private var connectedCount: Int { model.peers.filter { model.statuses[$0.id] == "Connected" }.count }
    private var connectionSummary: String {
        let total = model.peers.count
        if total == 0 { return "No phone paired" }
        if connectedCount == 0 { return "Reconnecting to phone\(total == 1 ? "" : "s")…" }
        if connectedCount == total {
            if total == 1, let peer = model.peers.first { return model.routeDescription(peer.id) }
            let direct = model.routes.values.filter { $0 == .wifi }.count
            return direct == total ? "\(total) phones · Direct on Wi-Fi" : "\(total) phones connected"
        }
        return "\(connectedCount) of \(total) phones connected"
    }
    private func phoneName(_ linkID: String) -> String {
        guard let index = model.peers.firstIndex(where: { $0.id == linkID }), model.peers.count > 1 else { return "Phone" }
        return "Phone \(index + 1)"
    }
    private var rule: some View { InboxStyle.separator.frame(height: 0.5) }
}

private struct InboxRow: View {
    @Bindable var model: AppModel
    let item: InboxItem
    let phone: String
    let showsPhone: Bool
    let clearing: Bool
    let onDismiss: () -> Void
    @State private var expanded = false
    @State private var hovering = false
    @State private var replyingTo: NotificationAction?
    @State private var replyText = ""

    private var source: String {
        let name = item.presentation.sourceApp?.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.flatMap { $0.isEmpty ? nil : $0 } ?? "Notification"
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 6) {
            Button { expanded.toggle() } label: {
                HStack(alignment: .top, spacing: 10) {
                    NotificationArtwork(data: item.presentation.imagePNG, source: item.presentation.sourceApp)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(showsPhone ? "\(source) · \(phone)" : source)
                                .font(.system(size: 10, weight: .medium)).lineLimit(1)
                            Spacer(minLength: 4)
                            if let receivedAt = item.receivedAt {
                                Text(timestamp(receivedAt)).font(.system(size: 10)).lineLimit(1).fixedSize()
                                    .help(Date(timeIntervalSince1970: Double(receivedAt) / 1000).formatted())
                            }
                        }.foregroundStyle(InboxStyle.secondary)
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(item.presentation.title.isEmpty ? "Notification" : item.presentation.title)
                                .font(.system(size: 13, weight: .semibold)).lineLimit(expanded ? nil : 2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: expanded ? "chevron.up" : "chevron.down")
                                .font(.system(size: 8, weight: .semibold)).foregroundStyle(.tertiary)
                        }
                        if !expanded {
                            Text(item.presentation.body).font(.system(size: 12)).lineLimit(2)
                                .foregroundStyle(InboxStyle.body)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(source): \(item.presentation.title)")
            .accessibilityValue(expanded ? "Expanded" : "Collapsed")
            .accessibilityHint(expanded ? "Collapse notification" : "Read full notification")
            Button(action: onDismiss) {
                Image(systemName: "xmark").font(.system(size: 9, weight: .medium))
                    .frame(width: 20, height: 20).contentShape(Rectangle())
            }
            .buttonStyle(.plain).foregroundStyle(InboxStyle.secondary).disabled(clearing)
            .accessibilityLabel("Clear notification: \(item.presentation.title)")
            .help("Clear this notification on this Mac")
            }
            if expanded {
                VStack(alignment: .leading, spacing: 5) {
                    if !item.presentation.subtitle.isEmpty {
                        Text(item.presentation.subtitle).font(.system(size: 11)).foregroundStyle(InboxStyle.secondary)
                    }
                    Text(item.presentation.body).font(.system(size: 12)).textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 40).padding(.top, 6)
            }
            if !item.presentation.actions.isEmpty { actionControls.padding(.leading, 40).padding(.top, 8) }
        }
        .padding(.horizontal, InboxStyle.inset).padding(.vertical, 12)
        .padding(.trailing, 10) // Keep text clear of the native overlay scrollbar.
        .background(hovering ? Color.primary.opacity(0.035) : Color.clear)
        .onHover { hovering = $0 }
        .onChange(of: item.presentation.sequence) { _, _ in replyingTo = nil; replyText = "" }
    }

    private var actionControls: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 6) {
                ForEach(item.presentation.actions) { action in
                    let attempt = model.actionAttempt(item, action)
                    Button(action.title) {
                        if action.reply { replyingTo = action }
                        else { Task { await model.invokeAction(item, action: action, reply: nil) } }
                    }
                    .buttonStyle(.bordered).controlSize(.small).lineLimit(1)
                    .disabled(attempt != nil || model.actionsInFlight.contains(model.actionKey(item, action)))
                    .help(action.reply ? "Reply through your phone" : "Run this action on your phone")
                    .accessibilityLabel(action.reply ? "Reply: \(action.title)" : "Phone action: \(action.title)")
                }
            }
            if let action = replyingTo, model.actionAttempt(item, action) == nil {
                VStack(alignment: .leading, spacing: 6) {
                    TextField(action.replyLabel ?? "Write a reply…", text: $replyText, axis: .vertical)
                        .textFieldStyle(.roundedBorder).font(.system(size: 12)).lineLimit(2...4)
                        .accessibilityLabel("Reply text")
                    HStack {
                        if replyText.utf8.count > 4096 {
                            Text("Reply is too long").font(.system(size: 10)).foregroundStyle(.red)
                        }
                        Spacer()
                        Button("Cancel") { replyingTo = nil; replyText = "" }
                        Button("Send") {
                            let text = replyText
                            Task {
                                await model.invokeAction(item, action: action, reply: text)
                                if model.actionAttempt(item, action) != nil { replyingTo = nil; replyText = "" }
                            }
                        }
                        .disabled(replyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || replyText.utf8.count > 4096 ||
                                  model.actionsInFlight.contains(model.actionKey(item, action)))
                    }.controlSize(.small)
                }
            }
            ForEach(item.presentation.actions) { action in
                if let attempt = model.actionAttempt(item, action) {
                    Text(attempt.label).font(.system(size: 10)).foregroundStyle(InboxStyle.secondary)
                } else if let problem = model.actionProblems[model.actionKey(item, action)] {
                    Text(problem).font(.system(size: 10)).foregroundStyle(.red)
                }
            }
        }
    }

    private func timestamp(_ milliseconds: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(milliseconds) / 1000)
        let elapsed = Date().timeIntervalSince(date)
        if elapsed < 60 { return "Now" }
        if elapsed < 3_600 { return "\(Int(elapsed) / 60)m" }
        if elapsed < 86_400 { return "\(Int(elapsed) / 3_600)h" }
        return date.formatted(.dateTime.month(.abbreviated).day())
    }
}

private struct NotificationArtwork: View {
    let data: Data?
    let source: String?
    @State private var thumbnail: NSImage?

    var body: some View {
        Group {
            if let thumbnail {
                Image(nsImage: thumbnail).resizable().scaledToFill()
            } else if let initial = source?.trimmingCharacters(in: .whitespacesAndNewlines).first {
                Text(String(initial).uppercased()).font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(InboxStyle.secondary)
            } else {
                Image(systemName: "bell").font(.system(size: 14)).foregroundStyle(InboxStyle.secondary)
            }
        }
        .frame(width: 30, height: 30)
        .background(Color.primary.opacity(0.055))
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .accessibilityHidden(true)
        .task(id: data) { thumbnail = Self.thumbnail(data) }
    }

    private static func thumbnail(_ data: Data?) -> NSImage? {
        guard let data, data.count <= 512 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetType(source) as String? == "public.png",
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              (1...2048).contains(width), (1...2048).contains(height),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: 60
              ] as CFDictionary) else { return nil }
        return NSImage(cgImage: image, size: NSSize(width: image.width, height: image.height))
    }
}

private struct InboxContentHeight: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
