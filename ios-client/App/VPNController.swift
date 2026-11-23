import Foundation
import NetworkExtension
import SwiftUI

@MainActor
final class VPNController: ObservableObject {
    @Published private(set) var status: NEVPNStatus = .disconnected
    @Published private(set) var preparing = false
    @Published var message: String?

    private var manager: NETunnelProviderManager?
    private var waitingForConnection = false

    var canChangeServer: Bool { !preparing && (status == .disconnected || status == .invalid) }
    var connected: Bool { status == .connected }
    var transitioning: Bool { preparing || status == .connecting || status == .disconnecting || status == .reasserting }

    func restore() async {
        do {
            manager = try await findManager()
            updateStatus()
        } catch {
            #if !targetEnvironment(simulator)
            message = "خواندن تنظیمات VPN آیفون ممکن نشد."
            #endif
        }
    }

    func updateStatus() {
        guard let manager else { return }
        let newStatus = manager.connection.status
        if waitingForConnection && (newStatus == .connected || newStatus == .disconnected || newStatus == .invalid) {
            if newStatus != .connected { message = "اتصال VPN برقرار نشد. سرور و کانفیگ اشتراک را بررسی کنید." }
            waitingForConnection = false
        }
        status = newStatus
    }

    func connect(to server: VPNServer) async {
        guard canChangeServer else { return }
        message = nil
        #if targetEnvironment(simulator)
        message = "برای تست تونل VPN، نسخهٔ امضاشده را روی آیفون واقعی اجرا کنید."
        #else
        preparing = true
        defer { preparing = false }
        do {
            let saved = try await findManager() ?? NETunnelProviderManager()
            guard let extensionID = extensionIdentifier else { throw TunnelSetupError.missingExtension }
            let configuration = NETunnelProviderProtocol()
            configuration.providerBundleIdentifier = extensionID
            configuration.serverAddress = "PasarGuard"
            configuration.disconnectOnSleep = false
            try server.selection.save()
            saved.localizedDescription = "PasarGuard VPN"
            saved.protocolConfiguration = configuration
            saved.isEnabled = true
            try await saved.saveToPreferences()
            try await saved.loadFromPreferences()
            manager = saved
            try saved.connection.startVPNTunnel()
            waitingForConnection = true
            status = .connecting
        } catch {
            waitingForConnection = false
            updateStatus()
            message = "شروع VPN ممکن نشد. مجوز VPN و تنظیمات امضای برنامه را بررسی کنید."
        }
        #endif
    }

    func disconnect() {
        waitingForConnection = false
        manager?.connection.stopVPNTunnel()
        updateStatus()
    }

    func forgetConfiguration() async throws {
        if let saved = try await findManager() {
            try await saved.removeFromPreferences()
        }
        #if !targetEnvironment(simulator)
        try TunnelSelection.remove()
        #endif
        waitingForConnection = false
        manager = nil
        status = .disconnected
        message = nil
    }

    private var extensionIdentifier: String? {
        Bundle.main.object(forInfoDictionaryKey: "PacketTunnelIdentifier") as? String
    }

    private func findManager() async throws -> NETunnelProviderManager? {
        guard let id = extensionIdentifier else { throw TunnelSetupError.missingExtension }
        let managers = try await NETunnelProviderManager.loadAllFromPreferences()
        return managers.first { ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == id }
    }
}
