import SwiftUI

@MainActor
final class SubscriptionModel: ObservableObject {
    @Published private(set) var loading = false
    @Published private(set) var servers: [VPNServer] = []
    @Published private(set) var info: SubscriptionInfo?
    @Published private(set) var hasSubscription = false
    @Published var selection: Int?
    @Published var message: String?

    private let service = SubscriptionService()
    private var ready = false

    var selectedServer: VPNServer? { servers.first { $0.id == selection } }

    func restore() async {
        guard !ready else { return }
        ready = true
        do {
            guard let link = try SubscriptionKeychain.load() else { return }
            hasSubscription = true
            await load(link)
        } catch {
            message = error.localizedDescription
        }
    }

    func refresh() async {
        do {
            guard let link = try SubscriptionKeychain.load() else { return }
            await load(link)
        } catch {
            message = error.localizedDescription
        }
    }

    func load(_ link: String, save: Bool = false) async {
        guard !loading else { return }
        loading = true
        message = nil
        defer { loading = false }
        do {
            let address = try SubscriptionAddress(link)
            let snapshot = try await service.load(address)
            try Task.checkCancellation()
            if save { try SubscriptionKeychain.save(address.url) }
            let previous = save ? nil : selectedServer?.selection.value
            info = snapshot.info
            servers = snapshot.servers
            selection = servers.first { $0.selection.value == previous }?.id ?? servers.first?.id
            hasSubscription = true
        } catch is CancellationError {
            return
        } catch {
            if !Task.isCancelled {
                message = (error as? SubscriptionError)?.errorDescription ?? SubscriptionError.panelUnavailable.errorDescription
            }
        }
    }

    func forget() throws {
        try SubscriptionKeychain.remove()
        hasSubscription = false
        servers = []
        selection = nil
        info = nil
        message = nil
    }
}
