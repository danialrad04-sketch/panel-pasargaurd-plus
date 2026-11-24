import Foundation

enum PanelConfig {
    static let proxyProtocols: Set<String> = ["vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "wireguard"]
    static let linkSchemes: Set<String> = ["vless", "vmess", "trojan", "ss", "hysteria2", "hy2"]

    static func objects(from data: Data) -> [[String: Any]] {
        guard let json = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let candidates = (json as? [[String: Any]]) ?? (json as? [String: Any]).map { [$0] } ?? []
        return Array(candidates.lazy.filter { defaultProxy(in: $0) != nil }.prefix(100))
    }

    static func defaultProxy(in config: [String: Any]) -> [String: Any]? {
        (config["outbounds"] as? [[String: Any]])?.first {
            ($0["protocol"] as? String).map(proxyProtocols.contains) == true
        }
    }

    static func tunnelJSON(from source: [String: Any], descriptor: Int32, assetDirectory: String) throws -> String {
        guard var outbounds = source["outbounds"] as? [[String: Any]],
              let first = outbounds.firstIndex(where: { outbound in
                  (outbound["protocol"] as? String).map(proxyProtocols.contains) == true
              }) else { throw TunnelSetupError.invalidConfig }
        if first != 0 {
            let proxy = outbounds.remove(at: first)
            outbounds.insert(proxy, at: 0)
        }

        var routing = source["routing"] as? [String: Any] ?? [:]
        if let rules = routing["rules"] as? [[String: Any]] {
            routing["rules"] = rules.filter { $0["inboundTag"] == nil }
        }

        var config: [String: Any] = [
            "log": ["loglevel": "none"],
            "env": ["xray.tun.fd": String(descriptor), "xray.location.asset": assetDirectory],
            "outbounds": outbounds,
            "inbounds": [[
                "tag": "tun-in",
                "protocol": "tun",
                "settings": ["name": "pasarguard", "MTU": TunnelSelection.mtu],
                "sniffing": ["enabled": true, "destOverride": ["http", "tls", "quic"]]
            ]],
            "routing": routing
        ]
        for field in ["dns", "policy", "observatory", "burstObservatory"] {
            config[field] = source[field]
        }
        let data = try JSONSerialization.data(withJSONObject: config)
        return String(decoding: data, as: UTF8.self)
    }
}
