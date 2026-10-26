package smartdns

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"github.com/xtls/xray-core/core"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type authority struct {
	mu             sync.Mutex
	reserved, used int
	grants         map[string]int
	deny           atomic.Bool
}

func (a *authority) handler(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("x-pg-node-key") != "test-node-key" {
		w.WriteHeader(401)
		return
	}
	var body map[string]any
	json.NewDecoder(r.Body).Decode(&body)
	a.mu.Lock()
	defer a.mu.Unlock()
	if strings.HasSuffix(r.URL.Path, "/reserve") {
		if a.deny.Load() {
			w.WriteHeader(403)
			return
		}
		id := body["request_id"].(string)
		amount := int(body["amount"].(float64))
		if amount+a.used+a.reserved > 4*1024*1024 {
			w.WriteHeader(403)
			return
		}
		a.grants[id] = amount
		a.reserved += amount
		json.NewEncoder(w).Encode(map[string]any{"grant_id": id, "amount": amount, "lease_ms": 2000})
		return
	}
	id := body["grant_id"].(string)
	used := int(body["used"].(float64))
	amount, ok := a.grants[id]
	if !ok || used > amount {
		w.WriteHeader(409)
		return
	}
	delete(a.grants, id)
	a.reserved -= amount
	a.used += used
	json.NewEncoder(w).Encode(map[string]bool{"settled": true})
}
func fixture(t *testing.T) (*Manager, *httptest.Server, *authority) {
	t.Helper()
	a := &authority{grants: map[string]int{}}
	panel := httptest.NewTLSServer(http.HandlerFunc(a.handler))
	m, e := New(filepath.Join(t.TempDir(), "state"), "test-node-key")
	if e != nil {
		t.Fatal(e)
	}
	m.resolve = func(context.Context, string) (net.IP, error) { return net.ParseIP("127.0.0.2"), nil }
	t.Cleanup(func() { m.Close(); panel.Close() })
	return m, panel, a
}
func configuration(panel *httptest.Server, port int, mode, domain string) Config {
	return Config{Revision: 1, NodeID: 1, PanelURL: panel.URL, PanelCA: string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: panel.Certificate().Raw})), Routes: []Route{{ID: 1, NodeID: 1, PolicyID: 1, Domain: domain, PublicAddress: "192.0.2.10", BindAddress: "127.0.0.1", TCPPorts: []int{port}, UDPPorts: []int{port}, Mode: mode, Enabled: true, Revision: 1}}}
}
func TestRealTLSHostRoutingAndAuthenticatedBudget(t *testing.T) {
	m, panel, authority := fixture(t)
	backend := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("native-smartdns-tls")) }))
	l, e := net.Listen("tcp4", "127.0.0.2:0")
	if e != nil {
		t.Fatal(e)
	}
	backend.Listener = l
	backend.StartTLS()
	defer backend.Close()
	port := l.Addr().(*net.TCPAddr).Port
	domain := backend.Certificate().DNSNames[0]
	cfg := configuration(panel, port, "sniff", domain)
	cfg.Routes[0].UDPPorts = nil
	if e = m.Apply(cfg); e != nil {
		t.Fatal(e)
	}
	roots := x509.NewCertPool()
	roots.AddCert(backend.Certificate())
	client := &http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: roots}, DisableKeepAlives: true, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		return net.DialTimeout("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), time.Second)
	}}}
	result, e := client.Get("https://" + domain + ":" + strconv.Itoa(port))
	if e != nil {
		t.Fatal(e)
	}
	wire, _ := io.ReadAll(result.Body)
	result.Body.Close()
	if string(wire) != "native-smartdns-tls" {
		t.Fatal("TLS stream was modified")
	}
	time.Sleep(100 * time.Millisecond)
	authority.mu.Lock()
	defer authority.mu.Unlock()
	if authority.used == 0 || authority.used > 4*1024*1024 {
		t.Fatal("Traffic was not settled within its cap")
	}
}
func TestRealOpaqueUDPAndRawTCP(t *testing.T)                    { exerciseOpaque(t, false) }
func TestRealOpaqueUDPAndRawTCPOverEncryptedEgress(t *testing.T) { exerciseOpaque(t, true) }
func exerciseOpaque(t *testing.T, encrypted bool) {
	m, panel, _ := fixture(t)
	tcp, e := net.Listen("tcp4", "127.0.0.2:0")
	if e != nil {
		t.Fatal(e)
	}
	defer tcp.Close()
	port := tcp.Addr().(*net.TCPAddr).Port
	udp, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.2"), Port: port})
	if e != nil {
		t.Fatal(e)
	}
	defer udp.Close()
	go func() {
		for {
			c, e := tcp.Accept()
			if e != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	go func() {
		b := make([]byte, 4096)
		for {
			n, a, e := udp.ReadFromUDP(b)
			if e != nil {
				return
			}
			udp.WriteToUDP(b[:n], a)
		}
	}()
	config := configuration(panel, port, "fixed", "game.example.com")
	if encrypted {
		listener, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		proxyPort := listener.Addr().(*net.TCPAddr).Port
		listener.Close()
		secret := base64.StdEncoding.EncodeToString(make([]byte, 16))
		value := map[string]any{"log": map[string]any{"loglevel": "none"}, "inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": proxyPort, "protocol": "shadowsocks", "settings": map[string]any{"method": "2022-blake3-aes-128-gcm", "password": secret, "network": "tcp,udp"}}}, "outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.0/8"}}}}}}}
		data, _ := json.Marshal(value)
		exit, err := core.StartInstance("json", data)
		if err != nil {
			t.Fatal(err)
		}
		defer exit.Close()
		config.Routes[0].Egress = &Egress{Address: "127.0.0.1", Port: proxyPort, Password: secret}
	}
	if e = m.Apply(config); e != nil {
		t.Fatal(e)
	}
	c, e := net.DialTimeout("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), time.Second)
	if e != nil {
		t.Fatal(e)
	}
	c.SetDeadline(time.Now().Add(3 * time.Second))
	payload := []byte("native-game-tcp")
	c.Write(payload)
	back := make([]byte, len(payload))
	if _, e = io.ReadFull(c, back); e != nil || string(back) != string(payload) {
		t.Fatal("TCP forwarding failed", e)
	}
	c.Close()
	u, e := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: port})
	if e != nil {
		t.Fatal(e)
	}
	defer u.Close()
	u.SetDeadline(time.Now().Add(3 * time.Second))
	u.Write([]byte("native-game-udp"))
	b := make([]byte, 64)
	n, e := u.Read(b)
	if e != nil || string(b[:n]) != "native-game-udp" {
		t.Fatal("UDP forwarding failed", e)
	}
}
func TestLeaseExpiryRevocationAndConfigurationRollback(t *testing.T) {
	m, panel, a := fixture(t)
	l, e := net.Listen("tcp4", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	cfg := configuration(panel, port, "fixed", "game.example.com")
	cfg.Routes[0].UDPPorts = nil
	if e = m.Apply(cfg); e != nil {
		t.Fatal(e)
	}
	p := m.budget(cfg.Routes[0], "127.0.0.1", "game.example.com", port, "tcp")
	if !p.ensure() || p.take(100, true) != 100 {
		t.Fatal("Initial byte grant failed")
	}
	a.deny.Store(true)
	time.Sleep(2100 * time.Millisecond)
	if p.take(1, true) != 0 {
		t.Fatal("Revoked grant survived its TTL")
	}
	p.release()
	blocked, e := net.Listen("tcp4", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer blocked.Close()
	bad := cfg
	bad.Revision = 2
	bad.Routes = append([]Route(nil), cfg.Routes...)
	bad.Routes[0].TCPPorts = []int{blocked.Addr().(*net.TCPAddr).Port}
	if m.Apply(bad) == nil || m.current.Load().Revision != 1 {
		t.Fatal("Failed bind replaced the acknowledged generation")
	}
}
func TestPrivateAndRebindingDestinationsRejected(t *testing.T) {
	for _, value := range []string{"127.0.0.1", "10.0.0.1", "100.64.0.1", "169.254.169.254", "::1", "fc00::1", "64:ff9b::a00:1", "2002:0a00:0001::1", "198.18.0.1"} {
		if publicIP(net.ParseIP(value)) {
			t.Fatalf("private or translated target allowed: %s", value)
		}
	}
	if !publicIP(net.ParseIP("1.1.1.1")) {
		t.Fatal("public destination rejected")
	}
}
