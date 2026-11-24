import Foundation
import LibXray
import NetworkExtension

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let lifecycle = DispatchQueue(label: "com.pasarguard.vpn.core")
    private var running = false
    private var generation = 0

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        lifecycle.async {
            self.generation += 1
            let current = self.generation
            do {
                let source = try self.readConfiguration()
                self.setTunnelNetworkSettings(self.networkSettings()) { error in
                    self.lifecycle.async {
                        guard self.generation == current else {
                            completionHandler(CancellationError())
                            return
                        }
                        if let error {
                            completionHandler(error)
                            return
                        }
                        do {
                            let fd = TunnelSelection.ipv4Address.withCString(PasarGuardTunnelDescriptor)
                            guard fd >= 0, let assets = Bundle.main.resourceURL else { throw TunnelSetupError.unavailable }
                            let config = try PanelConfig.tunnelJSON(from: source, descriptor: fd, assetDirectory: assets.path)
                            _ = try Self.invoke("runXray", payload: ["xrayJson": config])
                            self.running = true
                            completionHandler(nil)
                        } catch {
                            completionHandler(error)
                        }
                    }
                }
            } catch {
                completionHandler(error)
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        lifecycle.async {
            self.generation += 1
            if self.running {
                _ = try? Self.invoke("stopXray")
                self.running = false
            }
            completionHandler()
        }
    }

    private func readConfiguration() throws -> [String: Any] {
        let selected = try TunnelSelection.load()
        switch selected.kind {
        case .xrayConfig:
            let data = Data(selected.value.utf8)
            guard let config = PanelConfig.objects(from: data).first else { throw TunnelSetupError.invalidConfig }
            return config
        case .shareLink:
            return try Self.invoke("convertShareLinksToXrayJson", payload: ["text": selected.value])
        }
    }

    private func networkSettings() -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "198.18.0.2")
        let ipv4 = NEIPv4Settings(addresses: [TunnelSelection.ipv4Address], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [.default()]
        settings.ipv4Settings = ipv4

        let ipv6 = NEIPv6Settings(addresses: [TunnelSelection.ipv6Address], networkPrefixLengths: [64])
        ipv6.includedRoutes = [.default()]
        settings.ipv6Settings = ipv6
        settings.dnsSettings = NEDNSSettings(servers: ["1.1.1.1", "1.0.0.1"])
        settings.dnsSettings?.matchDomains = [""]
        settings.mtu = NSNumber(value: TunnelSelection.mtu)
        return settings
    }

    private static func invoke(_ method: String, payload: [String: Any] = [:]) throws -> [String: Any] {
        let request = try JSONSerialization.data(withJSONObject: ["apiVersion": 3, "method": method, "payload": payload])
        var input = String(decoding: request, as: UTF8.self).utf8CString
        guard let response = input.withUnsafeMutableBufferPointer({ CGoInvoke($0.baseAddress) }) else {
            throw TunnelSetupError.coreRejected
        }
        defer { CGoFree(response) }
        let data = Data(String(cString: response).utf8)
        guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              result["success"] as? Bool == true else { throw TunnelSetupError.coreRejected }
        return result["data"] as? [String: Any] ?? [:]
    }
}
