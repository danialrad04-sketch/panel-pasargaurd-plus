import Foundation

struct SubscriptionAddress: Sendable {
    let url: URL

    init(_ input: String) throws {
        guard let components = URLComponents(string: input.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              let source = components.url else { throw SubscriptionError.invalidLink }
        let parts = source.pathComponents
        guard let position = parts.lastIndex(of: "sub"), parts.count == position + 2,
              parts[position + 1].range(of: #"^[A-Za-z0-9_.~-]{1,2048}$"#, options: .regularExpression) != nil,
              ![".", ".."].contains(parts[position + 1]) else { throw SubscriptionError.invalidLink }
        url = source
    }
}

enum SubscriptionError: Error, LocalizedError {
    case invalidLink, panelUnavailable, noServers, credentialsUnavailable

    var errorDescription: String? {
        switch self {
        case .invalidLink: "لینک HTTPS اشتراک پاسارگارد را وارد کنید."
        case .panelUnavailable: "دریافت اطلاعات اشتراک ممکن نشد. اعتبار لینک و اینترنت را بررسی کنید."
        case .noServers: "سرور سازگاری برای این اشتراک پیدا نشد."
        case .credentialsUnavailable: "ذخیرهٔ امن لینک اشتراک ممکن نشد."
        }
    }
}

struct SubscriptionInfo: Decodable {
    let username: String?
    let status: String?
    let usedTraffic: Int64?
    let dataLimit: Int64?
}

struct VPNServer: Identifiable {
    let id: Int
    let name: String
    let type: String
    let selection: TunnelSelection
}

struct SubscriptionSnapshot {
    let info: SubscriptionInfo?
    let servers: [VPNServer]
}

struct SubscriptionService {
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 30
        configuration.httpShouldSetCookies = false
        return URLSession(configuration: configuration, delegate: SameOriginRedirect(), delegateQueue: nil)
    }()

    func load(_ address: SubscriptionAddress) async throws -> SubscriptionSnapshot {
        let device = try SubscriptionKeychain.deviceIdentifier()
        async let info = subscriptionInfo(address.url.appendingPathComponent("info"), device: device)
        for endpoint in ["xray", "v2ray-json"] {
            try Task.checkCancellation()
            guard let data = try? await fetch(address.url.appendingPathComponent(endpoint), device: device) else { continue }
            let servers = PanelConfig.objects(from: data).enumerated().compactMap(Self.server)
            if !servers.isEmpty { return await SubscriptionSnapshot(info: info, servers: servers) }
        }
        for url in [address.url.appendingPathComponent("raw"), address.url] {
            try Task.checkCancellation()
            guard let data = try? await fetch(url, device: device) else { continue }
            let servers = Self.links(from: data).enumerated().map { index, link in
                let pieces = link.split(separator: "#", maxSplits: 1)
                let name = pieces.count == 2 ? String(pieces[1]).removingPercentEncoding : nil
                let type = link.components(separatedBy: "://")[0].uppercased()
                return VPNServer(id: index, name: Self.title(name, fallback: index), type: type,
                                 selection: TunnelSelection(kind: .shareLink, value: link))
            }
            if !servers.isEmpty { return await SubscriptionSnapshot(info: info, servers: servers) }
        }
        if await info != nil { throw SubscriptionError.noServers }
        throw SubscriptionError.panelUnavailable
    }

    private func subscriptionInfo(_ url: URL, device: String) async -> SubscriptionInfo? {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        guard let data = try? await fetch(url, device: device) else { return nil }
        return try? decoder.decode(SubscriptionInfo.self, from: data)
    }

    private func fetch(_ url: URL, device: String) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue("PasarGuard-iOS/1.0", forHTTPHeaderField: "User-Agent")
        request.setValue(device, forHTTPHeaderField: "X-HWID")
        request.setValue("iOS", forHTTPHeaderField: "X-Device-OS")
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              response.expectedContentLength <= Int64(TunnelSelection.maximumBytes) else {
            throw SubscriptionError.panelUnavailable
        }
        var data = Data()
        for try await byte in bytes {
            guard data.count < TunnelSelection.maximumBytes else { throw SubscriptionError.noServers }
            data.append(byte)
        }
        return data
    }

    private static func server(_ candidate: (offset: Int, element: [String: Any])) -> VPNServer? {
        let config = candidate.element
        guard let outbound = PanelConfig.defaultProxy(in: config),
              let data = try? JSONSerialization.data(withJSONObject: config, options: [.sortedKeys]) else { return nil }
        let name = config["remarks"] as? String ?? config["remark"] as? String ?? config["name"] as? String
        let type = (outbound["protocol"] as? String)?.uppercased() ?? "Xray"
        return VPNServer(id: candidate.offset, name: title(name, fallback: candidate.offset), type: type,
                         selection: TunnelSelection(kind: .xrayConfig, value: String(decoding: data, as: UTF8.self)))
    }

    static func links(from data: Data) -> [String] {
        let json = try? JSONSerialization.jsonObject(with: data)
        if let wrapped = json as? [String: [String]], let links = wrapped["links"] {
            return Array(links.lazy.filter(validLink).prefix(100))
        }
        if let links = json as? [String] { return Array(links.lazy.filter(validLink).prefix(100)) }
        guard let text = String(data: data, encoding: .utf8) else { return [] }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let plain = Self.supportedLines(trimmed)
        if !plain.isEmpty { return plain }
        var normalized = trimmed.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        normalized += String(repeating: "=", count: (4 - normalized.count % 4) % 4)
        let decoded = Data(base64Encoded: normalized, options: [.ignoreUnknownCharacters])
            .flatMap { String(data: $0, encoding: .utf8) }
        return decoded.map(supportedLines) ?? []
    }

    private static func supportedLines(_ text: String) -> [String] {
        let lines = text.split(whereSeparator: \.isNewline)
        return Array(lines.lazy.map { $0.trimmingCharacters(in: .whitespaces) }.filter(validLink).prefix(100))
    }

    private static func validLink(_ link: String) -> Bool {
        let pieces = link.components(separatedBy: "://")
        return link.utf8.count <= 16_384 && pieces.count >= 2 && PanelConfig.linkSchemes.contains(pieces[0].lowercased())
    }

    private static func title(_ value: String?, fallback: Int) -> String {
        let cleaned = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return cleaned.isEmpty ? "سرور \(fallback + 1)" : String(cleaned.prefix(80))
    }
}

private final class SameOriginRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let original = task.originalRequest?.url, let destination = request.url,
              destination.scheme == "https", destination.host?.lowercased() == original.host?.lowercased(),
              (destination.port ?? 443) == (original.port ?? 443),
              destination.user == nil, destination.password == nil else {
            completionHandler(nil)
            return
        }
        completionHandler(request)
    }
}
