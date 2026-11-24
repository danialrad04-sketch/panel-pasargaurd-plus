import Foundation

struct TunnelSelection: Codable, Sendable {
    enum Kind: String, Codable, Sendable {
        case xrayConfig
        case shareLink
    }

    let kind: Kind
    let value: String

    static let maximumBytes = 2 * 1024 * 1024
    static let mtu = 1500
    static let ipv4Address = "198.18.0.1"
    static let ipv6Address = "fd21:76e1:5061::1"

    private static func storageURL() throws -> URL {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "AppGroupIdentifier") as? String,
              let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else {
            throw TunnelSetupError.sharedDirectoryUnavailable
        }
        return directory.appendingPathComponent("vpn-selection.json")
    }

    func save() throws {
        let data = try JSONEncoder().encode(self)
        guard data.count <= Self.maximumBytes else { throw TunnelSetupError.invalidConfig }
        try data.write(to: Self.storageURL(), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func load() throws -> Self {
        let url = try storageURL()
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard (1...maximumBytes).contains(size) else { throw TunnelSetupError.invalidConfig }
        return try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
    }

    static func remove() throws {
        let url = try storageURL()
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }
}

enum TunnelSetupError: Int, Error, CustomNSError {
    case sharedDirectoryUnavailable = 1
    case invalidConfig
    case unavailable
    case coreRejected
    case missingExtension

    static let errorDomain = "com.pasarguard.vpn.tunnel"
    var errorCode: Int { rawValue }
}
