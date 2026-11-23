import SwiftUI

@MainActor
final class AppUpdates: ObservableObject {
    struct Release: Decodable {
        let latestVersion: String
        let latestVersionCode: Int
        let downloadUrl: URL
        let changelog: String
    }

    @Published private(set) var release: Release?
    @Published private(set) var checking = false
    @Published var showResult = false
    @Published private(set) var message = ""

    private let defaults = UserDefaults.standard
    private let interval: TimeInterval = 6 * 60 * 60
    private let remindAfter: TimeInterval = 24 * 60 * 60
    private var manual = false

    func check(manual requested: Bool) async {
        if requested { manual = true }
        let elapsed = Date().timeIntervalSince1970 - defaults.double(forKey: "releaseCheckedAt")
        guard !checking, requested || !showResult && (elapsed < 0 || elapsed >= interval) else { return }
        checking = true
        defer { checking = false; manual = false }
        do {
            let result = try await availableRelease()
            defaults.set(Date().timeIntervalSince1970, forKey: "releaseCheckedAt")
            if let result, manual || shouldOffer(result) {
                release = result
                message = result.changelog
                showResult = true
            } else if manual {
                release = nil
                message = "آخرین نسخهٔ موجود نصب است."
                showResult = true
            }
        } catch {
            if manual && !Task.isCancelled {
                release = nil
                message = "بررسی نسخهٔ جدید ممکن نشد."
                showResult = true
            }
        }
    }

    func dismiss() {
        if let release {
            defaults.set(release.latestVersionCode, forKey: "releaseDismissedBuild")
            defaults.set(Date().timeIntervalSince1970, forKey: "releaseDismissedAt")
        }
        showResult = false
    }

    private func shouldOffer(_ release: Release) -> Bool {
        let elapsed = Date().timeIntervalSince1970 - defaults.double(forKey: "releaseDismissedAt")
        return defaults.integer(forKey: "releaseDismissedBuild") != release.latestVersionCode ||
            elapsed < 0 || elapsed >= remindAfter
    }

    private func availableRelease() async throws -> Release? {
        guard let base = BillingOrigin.url,
              let bundle = Bundle.main.bundleIdentifier,
              let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String,
              let build = Int(version) else { throw UpdateError.invalid }
        var components = URLComponents(url: base.appendingPathComponent("v1/app/config"), resolvingAgainstBaseURL: false)
        components?.queryItems = [
            URLQueryItem(name: "platform", value: "ios"),
            URLQueryItem(name: "package_name", value: bundle),
            URLQueryItem(name: "version_code", value: String(build))
        ]
        guard let url = components?.url else { throw UpdateError.invalid }
        let (data, response) = try await URLSession.shared.data(for: URLRequest(url: url, timeoutInterval: 12))
        guard let http = response as? HTTPURLResponse,
              http.url?.scheme == "https", http.url?.host == base.host, http.url?.port == base.port else {
            throw UpdateError.invalid
        }
        if http.statusCode == 204 { return nil }
        guard http.statusCode == 200, data.count <= 65_536 else { throw UpdateError.invalid }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let result = try decoder.decode(Release.self, from: data)
        guard result.downloadUrl.scheme == "https", result.downloadUrl.user == nil, result.downloadUrl.password == nil,
              result.downloadUrl.port == nil, let storeHost = result.downloadUrl.host, ["apps.apple.com", "testflight.apple.com"].contains(storeHost) else {
            throw UpdateError.invalid
        }
        return result.latestVersionCode > build ? result : nil
    }

    private enum UpdateError: Error { case invalid }
}
