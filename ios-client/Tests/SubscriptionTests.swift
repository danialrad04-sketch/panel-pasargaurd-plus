import XCTest
@testable import PasarGuard

final class SubscriptionTests: XCTestCase {
    func testSubscriptionURLUsesHTTPSAndTokenPath() throws {
        let url = try SubscriptionAddress(" https://example.com:8443/sub/demo-abc_123 ").url
        XCTAssertEqual(url.port, 8443)
        XCTAssertEqual(url.path, "/sub/demo-abc_123")
        for bad in ["http://example.com/sub/abc", "https://demo:abc@example.com/sub/abc",
                    "https://example.com/sub/abc/info", "https://example.com/sub/abc?key=1"] {
            XCTAssertThrowsError(try SubscriptionAddress(bad))
        }
    }

    func testPlainRawJSONAndBase64Links() {
        let link = "trojan://sample@example.com:443#demo"
        XCTAssertEqual(SubscriptionService.links(from: Data(link.utf8)), [link])
        XCTAssertEqual(SubscriptionService.links(from: Data(Data(link.utf8).base64EncodedString().utf8)), [link])
        XCTAssertEqual(SubscriptionService.links(from: Data(#"{"links":["https://example.com","ss://demo"]}"#.utf8)), ["ss://demo"])
    }

    func testTunnelReplacesUntrustedLocalListenersAndPrioritizesProxy() throws {
        let panel = Data(#"[{"env":{"xray.tun.fd":"0"},"inbounds":[{"listen":"0.0.0.0","port":1080}],"outbounds":[{"protocol":"freedom"},{"protocol":"vless","tag":"proxy"}],"routing":{"rules":[{"inboundTag":["api"]},{"domain":["example.com"],"outboundTag":"proxy"}]}}]"#.utf8)
        let profile = try XCTUnwrap(PanelConfig.objects(from: panel).first)
        let configured = try PanelConfig.tunnelJSON(from: profile, descriptor: 32, assetDirectory: "/bundle")
        let decoded = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(configured.utf8)) as? [String: Any])
        let inbounds = try XCTUnwrap(decoded["inbounds"] as? [[String: Any]])
        XCTAssertEqual(inbounds.count, 1)
        XCTAssertEqual(inbounds[0]["protocol"] as? String, "tun")
        XCTAssertNil(inbounds[0]["listen"])
        XCTAssertEqual((decoded["env"] as? [String: String])?["xray.tun.fd"], "32")
        XCTAssertEqual((decoded["outbounds"] as? [[String: String]])?.first?["tag"], "proxy")
        XCTAssertEqual((decoded["routing"] as? [String: [[String: Any]]])?["rules"]?.count, 1)
    }
}
