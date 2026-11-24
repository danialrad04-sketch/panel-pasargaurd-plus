import PhotosUI
import SwiftUI
import UIKit

struct CustomerAccountView: View {
    let onImport: (String) async -> Void
    let onRenewed: () async -> Void
    let canImport: Bool

    @State private var email = ""
    @State private var password = ""
    @State private var code = ""
    @State private var verifying = false
    @State private var signedIn = false
    @State private var loading = false
    @State private var message = ""
    @State private var wallet: Wallet?
    @State private var orders: [CustomerOrder] = []
    @State private var plans: [CustomerPlan] = []
    @State private var order: CustomerOrder?
    @State private var plan: CustomerPlan?
    @State private var cancelOrder: CustomerOrder?
    @State private var reference = ""
    @State private var photo: PhotosPickerItem?
    @State private var attemptKeys: [String: UUID] = [:]

    var body: some View {
        List {
            if loading { ProgressView("در حال دریافت") }
            if !message.isEmpty { Text(message).font(.footnote) }
            if !signedIn {
                Section("ورود یا ساخت حساب") {
                    TextField("Gmail", text: $email).keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never).textContentType(.emailAddress).autocorrectionDisabled()
                    if verifying {
                        TextField("کد تأیید", text: $code).keyboardType(.numberPad).textContentType(.oneTimeCode)
                    }
                    SecureField("رمز پاسارگارد", text: $password)
                        .textContentType(verifying ? .newPassword : .password)
                    Button(verifying ? "تأیید و ورود" : "ورود") { authenticate() }
                        .disabled(loading || email.isEmpty || password.isEmpty || verifying && code.count != 6)
                    Button("ساخت حساب یا بازیابی رمز") {
                        run {
                            try await CustomerHTTP.empty("v1/customer/code", ["email": self.email])
                            verifying = true
                            message = "کد تأیید به Gmail ارسال شد."
                        }
                    }.disabled(email.isEmpty || loading)
                }
            } else {
                Section("کیف پول") {
                    LabeledContent(email, value: "\(wallet?.balanceToman ?? 0) تومان")
                    Button("به‌روزرسانی اطلاعات") { run { try await reload() } }.disabled(loading)
                    Button("خروج از حساب", role: .destructive) {
                        run {
                            defer {
                                try? SubscriptionKeychain.saveCustomerToken(nil)
                                signedIn = false
                                email = ""
                                orders = []
                                plans = []
                                wallet = nil
                                attemptKeys = [:]
                            }
                            try await CustomerHTTP.empty("v1/customer/logout", [:], authenticated: true)
                        }
                    }.disabled(loading)
                }
                Section(renewalToken == nil ? "خرید اشتراک" : "تمدید اشتراک") {
                    ForEach(plans) { item in
                        Button {
                            plan = item
                        } label: {
                            LabeledContent(item.titleFa, value: "\(item.priceToman) تومان")
                        }.disabled(loading)
                    }
                }
                Section("سفارش‌ها") {
                    ForEach(orders) { item in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(item.planTitleFa)
                            if let refund = wallet?.refunds.first(where: { $0.orderId == item.id }) {
                                Text(refund.finished == nil ? "در حال لغو" : "لغوشده")
                            } else if item.newSubscription == true && item.activationStatus == "READY" {
                                Button("افزودن به VPN") {
                                    run {
                                        let link: SubscriptionLink = try await CustomerHTTP.get(
                                            "v1/billing/orders/\(item.id)/subscription", authenticated: true)
                                        await onImport(link.subscriptionUrl)
                                    }
                                }.disabled(!canImport || loading)
                                Button("لغو اشتراک", role: .destructive) { cancelOrder = item }.disabled(loading)
                            } else if item.status == "AWAITING_RECEIPT" {
                                Button("اطلاعات کارت و ارسال فیش") {
                                    order = item
                                    photo = nil
                                    reference = ""
                                }.disabled(loading)
                            } else {
                                Text(item.caption).font(.footnote).foregroundStyle(.secondary)
                            }
                        }.buttonStyle(.borderless)
                    }
                }
            }
        }
        .navigationTitle("حساب و کیف پول")
        .task { if (try? SubscriptionKeychain.customerToken()) != nil { run { try await reload() } } }
        .confirmationDialog(plan?.titleFa ?? "پرداخت", isPresented: Binding(
            get: { plan != nil }, set: { if !$0 { plan = nil } }), titleVisibility: .visible) {
            if let item = plan {
                Button("کارت‌به‌کارت") { purchase(item, method: "CARD_TO_CARD") }
                if (wallet?.balanceToman ?? 0) >= item.priceToman {
                    Button("پرداخت با کیف پول") { purchase(item, method: "WALLET") }
                }
            }
        }
        .confirmationDialog("دسترسی قطع و اعتبار زمان باقی‌مانده به کیف پول برگردد؟",
          isPresented: Binding(get: { cancelOrder != nil }, set: { if !$0 { cancelOrder = nil } }), titleVisibility: .visible) {
            if let item = cancelOrder {
                Button("لغو اشتراک", role: .destructive) {
                    run {
                        try await CustomerHTTP.empty("v1/customer/orders/\(item.id)/cancel", [:], authenticated: true)
                        try await reload()
                    }
                }
            }
        }
        .sheet(item: $order) { item in
            NavigationStack {
                Form {
                    if !message.isEmpty { Text(message).font(.footnote) }
                    Text("مبلغ دقیق \(item.finalPriceToman) تومان")
                    LabeledContent("شمارهٔ کارت", value: item.destinationCard.cardNumber)
                        .textSelection(.enabled)
                    Text(item.destinationCard.holderNameFa)
                    TextField("شمارهٔ پیگیری", text: $reference).keyboardType(.numberPad)
                    PhotosPicker(selection: $photo, matching: .images) {
                        Text(photo == nil ? "انتخاب تصویر فیش" : "عکس انتخاب شد")
                    }
                    Button("ارسال فیش") { sendReceipt(item) }
                        .disabled(loading || reference.isEmpty && photo == nil)
                }
                .navigationTitle("اطلاعات پرداخت")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("بستن") { order = nil } }
                }
            }.interactiveDismissDisabled(loading)
        }
    }

    private var renewalToken: String? {
        guard let link = try? SubscriptionKeychain.load(), let url = URL(string: link),
              let panel = Bundle.main.object(forInfoDictionaryKey: "PanelOrigin") as? String,
              let base = URL(string: panel), url.scheme == base.scheme, url.host == base.host,
              (url.port ?? 443) == (base.port ?? 443) else { return nil }
        return url.lastPathComponent
    }

    private func authenticate() {
        run {
            var body = ["email": email, "password": password]
            if verifying { body["code"] = code.compactMap(\.wholeNumberValue).map(String.init).joined() }
            let session: CustomerSession = try await CustomerHTTP.post(
                verifying ? "v1/customer/verify" : "v1/customer/login", body: body)
            try SubscriptionKeychain.saveCustomerToken(session.accessToken)
            password = ""
            code = ""
            verifying = false
            try await reload()
        }
    }

    private func reload() async throws {
        let identity: CustomerIdentity = try await CustomerHTTP.get("v1/customer/me", authenticated: true)
        email = identity.email
        signedIn = true
        wallet = try await CustomerHTTP.get("v1/customer/wallet", authenticated: true)
        let history: CustomerOrders = try await CustomerHTTP.get("v1/billing/orders", authenticated: true)
        let catalog: CustomerPlans = try await CustomerHTTP.get("v1/billing/plans", authenticated: true,
                                                                 subscription: renewalToken)
        orders = history.orders
        plans = catalog.plans
        if orders.contains(where: { $0.newSubscription != true && $0.activationStatus == "READY" }) {
            await onRenewed()
        }
    }

    private func purchase(_ item: CustomerPlan, method: String) {
        run {
            let identity = item.id + ":" + method
            let key = attemptKeys[identity] ?? UUID()
            attemptKeys[identity] = key
            let created: CustomerOrder = try await CustomerHTTP.post("v1/billing/orders",
                body: ["plan_id": item.id, "payment_method": method], authenticated: true,
                subscription: renewalToken, key: key)
            attemptKeys.removeValue(forKey: identity)
            if created.status == "AWAITING_RECEIPT" {
                photo = nil; reference = ""; order = created
            }
            try await reload()
        }
    }

    private func sendReceipt(_ item: CustomerOrder) {
        run {
            var image: Data?
            if let photo {
                guard let original = try await photo.loadTransferable(type: Data.self),
                      let decoded = UIImage(data: original) else { throw CustomerHTTP.Failure.invalid }
                let ratio = min(1, 1280 / max(decoded.size.width, decoded.size.height))
                let size = CGSize(width: decoded.size.width * ratio, height: decoded.size.height * ratio)
                let format = UIGraphicsImageRendererFormat()
                format.scale = 1
                let jpeg = UIGraphicsImageRenderer(size: size, format: format).image { _ in
                    decoded.draw(in: CGRect(origin: .zero, size: size))
                }
                image = [0.8, 0.65, 0.5, 0.35].lazy.compactMap {
                    jpeg.jpegData(compressionQuality: $0)
                }.first { $0.count <= 512 * 1024 }
                if image == nil { throw CustomerHTTP.Failure.invalid }
            }
            try await CustomerHTTP.receipt(item.id, reference: reference, image: image)
            order = nil
            try await reload()
        }
    }

    private func run(_ action: @escaping () async throws -> Void) {
        if loading { return }
        loading = true
        message = ""
        Task { @MainActor in
            defer { loading = false }
            do { try await action() }
            catch {
                if (try? SubscriptionKeychain.customerToken()) == nil { signedIn = false }
                message = (error as? CustomerHTTP.Failure)?.text ?? "ارتباط با سرویس برقرار نشد."
            }
        }
    }
}

private struct CustomerSession: Decodable { let accessToken: String }
private struct CustomerIdentity: Decodable { let email: String }
private struct SubscriptionLink: Decodable { let subscriptionUrl: String }
private struct CustomerPlans: Decodable { let plans: [CustomerPlan] }
private struct CustomerPlan: Decodable, Identifiable { let id: String; let titleFa: String; let priceToman: Int }
private struct CustomerOrders: Decodable { let orders: [CustomerOrder] }
private struct Destination: Decodable { let cardNumber: String; let holderNameFa: String }
private struct Wallet: Decodable {
    struct Refund: Decodable { let orderId: String; let finished: Int64? }
    let balanceToman: Int
    let refunds: [Refund]
}
private struct CustomerOrder: Decodable, Identifiable {
    let id: String
    let planTitleFa: String
    let status: String
    let newSubscription: Bool?
    let activationStatus: String?
    let finalPriceToman: Int
    let destinationCard: Destination

    var caption: String {
        if activationStatus == "PENDING" { return "در صف اعمال روی اشتراک" }
        return ["APPROVED":"تأییدشده", "PENDING_REVIEW":"در انتظار بررسی",
                "REJECTED":"ردشده", "EXPIRED":"منقضی"][status] ?? "در حال پردازش"
    }
}

private enum CustomerHTTP {
    enum Failure: Error {
        case invalid
        case server(Int, String?)

        var text: String {
            switch self {
            case .server(_, "INVALID_VERIFICATION_CODE"): "کد اشتباه یا منقضی است."
            case .server(_, "CUSTOMER_EMAIL_UNAVAILABLE"): "ارسال ایمیل فعلاً در دسترس نیست."
            case .server(401, _): "اطلاعات ورود را بررسی کنید یا دوباره وارد شوید."
            case .server(429, _): "تعداد درخواست‌ها زیاد است. بعداً امتحان کنید."
            default: "انجام درخواست ممکن نشد."
            }
        }
    }

    static func get<T: Decodable>(_ path: String, authenticated: Bool, subscription: String? = nil) async throws -> T {
        try decode(await request(path, authenticated: authenticated, subscription: subscription))
    }

    static func post<T: Decodable>(_ path: String, body: [String: String], authenticated: Bool = false,
                                   subscription: String? = nil, key: UUID? = nil) async throws -> T {
        try decode(await request(path, body: JSONEncoder().encode(body), authenticated: authenticated,
                                 subscription: subscription, key: key))
    }

    static func empty(_ path: String, _ fields: [String: String], authenticated: Bool = false) async throws {
        _ = try await request(path, body: JSONEncoder().encode(fields), authenticated: authenticated)
    }

    static func receipt(_ orderId: String, reference: String, image: Data?) async throws {
        let boundary = UUID().uuidString
        var body = Data()
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"tracking_number\"\r\n\r\n\(reference)\r\n".utf8))
        if let image {
            body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"receipt_image\"; filename=\"receipt.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".utf8))
            body.append(image)
            body.append(Data("\r\n".utf8))
        }
        body.append(Data("--\(boundary)--\r\n".utf8))
        _ = try await request("v1/billing/orders/\(orderId)/receipt", body: body,
                              contentType: "multipart/form-data; boundary=\(boundary)", authenticated: true)
    }

    private static func decode<T: Decodable>(_ data: Data) throws -> T {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(T.self, from: data)
    }

    private static func request(_ path: String, body: Data? = nil, contentType: String = "application/json",
                                authenticated: Bool, subscription: String? = nil, key: UUID? = nil) async throws -> Data {
        guard let base = BillingOrigin.url else { throw Failure.invalid }
        var request = URLRequest(url: base.appendingPathComponent(path), timeoutInterval: 20)
        request.setValue("native", forHTTPHeaderField: "X-Pasarguard-Client")
        if authenticated {
            guard let token = try SubscriptionKeychain.customerToken() else { throw Failure.server(401, nil) }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let subscription { request.setValue(subscription, forHTTPHeaderField: "X-Subscription-Token") }
        if let key { request.setValue(key.uuidString, forHTTPHeaderField: "Idempotency-Key") }
        if let body {
            request.httpMethod = "POST"
            request.httpBody = body
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        let session = URLSession(configuration: configuration, delegate: CustomerRedirect(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        let (data, result) = try await session.data(for: request)
        guard let response = result as? HTTPURLResponse else { throw Failure.invalid }
        if !(200...299).contains(response.statusCode) {
            if authenticated && response.statusCode == 401 { try? SubscriptionKeychain.saveCustomerToken(nil) }
            let error = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            throw Failure.server(response.statusCode, error?["detail"] as? String)
        }
        return data
    }
}

private final class CustomerRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
