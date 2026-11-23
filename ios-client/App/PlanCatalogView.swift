import SwiftUI

enum BillingOrigin {
    static var url: URL? {
        guard let configured = Bundle.main.object(forInfoDictionaryKey: "BillingOrigin") as? String,
              let url = URL(string: configured), url.scheme == "https", url.host != nil,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else { return nil }
        return url
    }
}

struct PlanCatalogView: View {
    @State private var plans: [BillingPlan] = []
    @State private var loading = true
    @State private var failed = false

    var body: some View {
        Group {
            if loading && plans.isEmpty {
                ProgressView("دریافت پلن‌ها")
            } else if failed {
                ContentUnavailableView {
                    Label("دریافت پلن‌ها ممکن نشد", systemImage: "network.slash")
                } actions: {
                    Button("تلاش دوباره") { Task { await reload() } }
                }
            } else if plans.isEmpty {
                ContentUnavailableView("پلنی برای نمایش وجود ندارد", systemImage: "list.bullet")
            } else {
                List(plans) { plan in
                    VStack(alignment: .leading, spacing: 7) {
                        Text(plan.titleFa).font(.headline)
                        Text("\(plan.priceToman.formatted(.number.locale(Locale(identifier: "fa_IR")))) تومان")
                            .font(.subheadline.weight(.semibold))
                        Text(plan.dataLimitGb.map { "\($0) گیگابایت" } ?? "حجم نامحدود")
                            .font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 5)
                }.refreshable { await reload() }
            }
        }
        .navigationTitle("پلن‌های اشتراک")
        .task { await reload() }
    }

    private func reload() async {
        guard let url = BillingOrigin.url else {
            loading = false
            failed = true
            return
        }
        loading = true
        failed = false
        defer { loading = false }
        do {
            var request = URLRequest(url: url.appendingPathComponent("v1/billing/plans"), timeoutInterval: 15)
            let token = (UUID().uuidString + UUID().uuidString).lowercased().replacingOccurrences(of: "-", with: "")
            request.setValue(token, forHTTPHeaderField: "X-Purchase-Token")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let status = (response as? HTTPURLResponse)?.statusCode, status == 200, data.count <= 1_048_576 else {
                failed = true
                return
            }
            let decoder = JSONDecoder()
            decoder.keyDecodingStrategy = .convertFromSnakeCase
            plans = try decoder.decode(BillingPlans.self, from: data).plans
        } catch is CancellationError {
            return
        } catch {
            failed = !Task.isCancelled
        }
    }
}

private struct BillingPlans: Decodable {
    let plans: [BillingPlan]
}

private struct BillingPlan: Decodable, Identifiable {
    let id: String
    let titleFa: String
    let dataLimitGb: Int?
    let priceToman: Int
}
