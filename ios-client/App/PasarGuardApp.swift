import NetworkExtension
import SwiftUI

@main
struct PasarGuardApp: App {
    var body: some Scene {
        WindowGroup {
            MainView()
                .environment(\.layoutDirection, .rightToLeft)
                .environment(\.locale, Locale(identifier: "fa_IR"))
        }
    }
}

private struct MainView: View {
    @StateObject private var subscription = SubscriptionModel()
    @StateObject private var vpn = VPNController()
    @State private var tab = 0
    @State private var importing = false
    @State private var confirmingRemoval = false
    @State private var operationMessage: String?

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { connection }
                .tabItem { Label("اتصال", systemImage: "shield.lefthalf.filled") }.tag(0)
            NavigationStack { servers }
                .tabItem { Label("سرورها", systemImage: "point.3.connected.trianglepath.dotted") }.tag(1)
            NavigationStack { account }
                .tabItem { Label("اشتراک", systemImage: "person.crop.circle") }.tag(2)
        }
        .tint(ocean.accent)
        .sheet(isPresented: $importing) { ImportSubscriptionView(subscription: subscription) }
        .task {
            await vpn.restore()
            await subscription.restore()
        }
        .onReceive(NotificationCenter.default.publisher(for: .NEVPNStatusDidChange)) { _ in vpn.updateStatus() }
        .confirmationDialog("اشتراک از این آیفون حذف شود؟", isPresented: $confirmingRemoval, titleVisibility: .visible) {
            Button("حذف اشتراک", role: .destructive) { Task { await removeSubscription() } }
        }
    }

    @Environment(\.colorScheme) private var colorScheme

    private var ocean: Ocean {
        Ocean(dark: colorScheme == .dark)
    }

    private var connection: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("پاسارگارد").font(.largeTitle.bold())
                    Text("اتصال VPN").font(.subheadline).foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                connectionCard
                if let message = vpn.message ?? subscription.message {
                    Label(message, systemImage: "exclamationmark.circle").font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityLiveStatus(message)
                }
                if subscription.hasSubscription {
                    Button { tab = 1 } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "server.rack").foregroundStyle(ocean.accent)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(vpn.connected ? "سرور فعال" : "سرور انتخابی").font(.caption).foregroundStyle(.secondary)
                                Text(vpn.activeServerName ?? subscription.selectedServer?.name ?? "سروری انتخاب نشده").font(.headline)
                            }
                            Spacer()
                            Image(systemName: "chevron.forward").foregroundStyle(.secondary)
                        }
                        .frame(minHeight: 44)
                        .padding(16)
                        .background(ocean.surface, in: RoundedRectangle(cornerRadius: 20))
                    }.buttonStyle(.plain)
                }
            }
            .padding(20)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .background(ocean.background)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var connectionCard: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle().strokeBorder(.white.opacity(0.15), lineWidth: 1).frame(width: 96, height: 96)
                Circle().fill(.white.opacity(0.12)).frame(width: 76, height: 76)
                Image(systemName: vpn.connected ? "checkmark.shield.fill" : "shield.lefthalf.filled")
                    .font(.system(size: 34, weight: .medium))
            }.accessibilityHidden(true)
            Text(connectionTitle).font(.title2.bold()).multilineTextAlignment(.center)
            if !subscription.hasSubscription && !subscription.loading && !vpn.connected {
                Text("لینک اشتراک پاسارگارد را وارد کنید.").font(.subheadline)
                    .multilineTextAlignment(.center)
            }
            Button(action: primaryAction) {
                Group {
                    if vpn.transitioning || subscription.loading {
                        ProgressView().tint(Ocean.ink)
                    } else {
                        Text(primaryTitle).font(.headline)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 52)
                .foregroundStyle(Ocean.ink)
                .background(Ocean.mint, in: RoundedRectangle(cornerRadius: 15))
            }
            .buttonStyle(.plain)
            .disabled(vpn.transitioning || subscription.loading || (subscription.hasSubscription &&
                      subscription.selectedServer == nil && !vpn.connected))
            .accessibilityLabel(primaryTitle)
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .foregroundStyle(.white)
        .background(
            LinearGradient(colors: [Ocean.deep, Ocean.teal], startPoint: .topTrailing, endPoint: .bottomLeading),
            in: RoundedRectangle(cornerRadius: 26)
        )
    }

    private var servers: some View {
        List {
            if subscription.servers.isEmpty {
                if subscription.loading {
                    ProgressView("دریافت سرورها")
                } else {
                    ContentUnavailableView("سروری در دسترس نیست", systemImage: "server.rack",
                                           description: Text("لینک اشتراک را در بخش اشتراک وارد یا تازه‌سازی کنید."))
                }
            } else {
                ForEach(subscription.servers) { server in
                    Button {
                        subscription.selection = server.id
                        tab = 0
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(server.name).foregroundStyle(.primary)
                                Text(server.type).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if subscription.selection == server.id {
                                Image(systemName: "checkmark.circle.fill").accessibilityHidden(true)
                            }
                        }.frame(minHeight: 44)
                    }
                    .accessibilityAddTraits(subscription.selection == server.id ? .isSelected : [])
                    .disabled(!vpn.canChangeServer)
                }
            }
        }
        .navigationTitle("سرورها")
        .refreshable { if vpn.canChangeServer { await subscription.refresh() } }
    }

    private var account: some View {
        List {
            Section("حساب و خرید") {
                NavigationLink { PlanCatalogView() } label: { Label("پلن‌های اشتراک", systemImage: "list.bullet") }
                if let billing = BillingOrigin.url {
                    Link(destination: billing.appendingPathComponent("account")) {
                        Label("حساب کاربری و کیف پول", systemImage: "wallet.pass")
                    }
                }
            }
            Section("اطلاعات اشتراک") {
                if let info = subscription.info {
                    if let username = info.username { LabeledContent("نام اشتراک", value: username) }
                    if let status = info.status { LabeledContent("وضعیت", value: localizedStatus(status)) }
                    if let used = info.usedTraffic { LabeledContent("مصرف", value: byteCount(used)) }
                    if let limit = info.dataLimit { LabeledContent("سقف مصرف", value: limit == 0 ? "نامحدود" : byteCount(limit)) }
                } else if subscription.hasSubscription {
                    Label("اطلاعات مصرف در دسترس نیست", systemImage: "info.circle")
                } else {
                    Label("هنوز اشتراکی اضافه نشده است", systemImage: "link")
                }
            }
            Section {
                Button { importing = true } label: {
                    Label(subscription.hasSubscription ? "تغییر لینک اشتراک" : "افزودن اشتراک", systemImage: "link")
                }.disabled(!vpn.canChangeServer || subscription.loading)
                if subscription.hasSubscription {
                    Button { Task { await subscription.refresh() } } label: {
                        Label("تازه‌سازی", systemImage: "arrow.clockwise")
                    }.disabled(!vpn.canChangeServer || subscription.loading)
                    Button(role: .destructive) { confirmingRemoval = true } label: {
                        Label("حذف اشتراک از دستگاه", systemImage: "trash")
                    }.disabled(!vpn.canChangeServer || subscription.loading)
                }
                if let message = operationMessage ?? subscription.message {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("اشتراک")
    }

    private var connectionTitle: String {
        if vpn.connected { return "متصل" }
        if vpn.status == .disconnecting { return "در حال قطع اتصال" }
        if vpn.transitioning { return "در حال اتصال" }
        if subscription.loading { return "در حال دریافت اشتراک" }
        return subscription.hasSubscription ? "اتصال قطع است" : "افزودن اشتراک"
    }

    private var primaryTitle: String {
        if vpn.connected { return "قطع اتصال" }
        return subscription.hasSubscription ? "اتصال به VPN" : "وارد کردن لینک"
    }

    private func primaryAction() {
        if vpn.connected {
            vpn.disconnect()
        } else if let server = subscription.selectedServer {
            Task { await vpn.connect(to: server) }
        } else if !subscription.hasSubscription {
            importing = true
        }
    }

    private func removeSubscription() async {
        do {
            try await vpn.forgetConfiguration()
            try subscription.forget()
            operationMessage = nil
        } catch {
            operationMessage = "پاک کردن تنظیمات اشتراک ممکن نشد. دوباره تلاش کنید."
        }
    }

    private func localizedStatus(_ status: String) -> String {
        switch status.lowercased() {
        case "active": "فعال"
        case "disabled": "غیرفعال"
        case "limited": "اتمام ترافیک"
        case "expired": "منقضی"
        case "on_hold": "در انتظار فعال‌سازی"
        default: "نامشخص"
        }
    }

    private func byteCount(_ count: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: count, countStyle: .file)
    }
}

private struct ImportSubscriptionView: View {
    @ObservedObject var subscription: SubscriptionModel
    @Environment(\.dismiss) private var dismiss
    @State private var link = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("لینک HTTPS اشتراک", text: $link)
                        .textContentType(.URL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .environment(\.layoutDirection, .leftToRight)
                        .multilineTextAlignment(.leading)
                        .privacySensitive()
                } footer: {
                    Text("لینک و شناسهٔ تصادفی برنامه برای دریافت سرورها به دامنهٔ اشتراک فرستاده می‌شوند و در Keychain این دستگاه ذخیره می‌شوند.")
                }
                Section {
                    Button {
                        Task {
                            await subscription.load(link, save: true)
                            if subscription.message == nil && subscription.hasSubscription { dismiss() }
                        }
                    } label: {
                        if subscription.loading { ProgressView("در حال دریافت") }
                        else { Text("دریافت سرورها") }
                    }
                    .disabled(subscription.loading || link.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if let message = subscription.message {
                        Text(message).font(.footnote)
                    }
                }
            }
            .navigationTitle("لینک اشتراک")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("بستن") { dismiss() }.disabled(subscription.loading)
                }
            }
            .interactiveDismissDisabled(subscription.loading)
        }
    }
}

private struct Ocean {
    let dark: Bool
    static let deep = Color(red: 0.08, green: 0.18, blue: 0.31)
    static let teal = Color(red: 0.04, green: 0.31, blue: 0.37)
    static let mint = Color(red: 0.68, green: 0.91, blue: 0.90)
    static let ink = Color(red: 0.05, green: 0.18, blue: 0.22)
    var accent: Color { dark ? Self.mint : Self.teal }
    var surface: Color { Color(uiColor: .secondarySystemGroupedBackground) }
    var background: Color { Color(uiColor: .systemGroupedBackground) }
}

private extension View {
    func accessibilityLiveStatus(_ message: String) -> some View {
        onAppear { AccessibilityNotification.Announcement(message).post() }
    }
}
