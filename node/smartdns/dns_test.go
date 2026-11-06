package smartdns

import (
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	dns "github.com/miekg/dns"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"sync/atomic"
	"testing"
	"time"
)

func TestRealNodeDNSUDPAndTCPWithCentralAuthorization(t *testing.T) {
	var denied atomic.Bool
	var transports atomic.Int32
	panel := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("x-pg-node-key") != "node-key" || r.URL.Path != "/api/smartdns/nodes/1/resolve" || denied.Load() {
			w.WriteHeader(403)
			return
		}
		var body struct {
			Revision int    `json:"revision"`
			IP       string `json:"source_ip"`
			UDP      bool   `json:"udp"`
			Wire     string `json:"wire"`
		}
		if json.NewDecoder(r.Body).Decode(&body) != nil || body.IP != "127.0.0.1" || body.Revision != 1 {
			w.WriteHeader(403)
			return
		}
		if body.UDP {
			transports.Or(1)
		} else {
			transports.Or(2)
		}
		wire, _ := base64.StdEncoding.DecodeString(body.Wire)
		query := new(dns.Msg)
		if query.Unpack(wire) != nil {
			w.WriteHeader(400)
			return
		}
		response := new(dns.Msg)
		response.SetReply(query)
		answer, _ := dns.NewRR(query.Question[0].Name + " 30 IN A 192.0.2.20")
		response.Answer = []dns.RR{answer}
		result, _ := response.Pack()
		json.NewEncoder(w).Encode(map[string]string{"wire": base64.StdEncoding.EncodeToString(result)})
	}))
	defer panel.Close()
	m, e := New(filepath.Join(t.TempDir(), "state"), "node-key")
	if e != nil {
		t.Fatal(e)
	}
	defer m.Close()
	reserved, e := net.Listen("tcp4", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	port := reserved.Addr().(*net.TCPAddr).Port
	reserved.Close()
	cfg := Config{Revision: 1, NodeID: 1, PanelURL: panel.URL, PanelCA: string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: panel.Certificate().Raw})), Routes: []Route{}, DNS: &DNSListener{Enabled: true, BindAddress: "127.0.0.1", Port: port}}
	if e = m.Apply(cfg); e != nil {
		t.Fatal(e)
	}
	query := new(dns.Msg)
	query.SetQuestion("game.example.com.", dns.TypeA)
	for _, network := range []string{"udp", "tcp"} {
		client := &dns.Client{Net: network, Timeout: 3 * time.Second}
		reply, _, e := client.Exchange(query, net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
		if e != nil || reply.Rcode != dns.RcodeSuccess || len(reply.Answer) != 1 {
			t.Fatalf("actual node DNS %s failed: %v", network, e)
		}
	}
	if transports.Load() != 3 {
		t.Fatal("DNS transports were not separately authorized")
	}
	denied.Store(true)
	reply, _, e := (&dns.Client{Net: "udp", Timeout: 3 * time.Second}).Exchange(query, net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if e != nil || reply.Rcode != dns.RcodeServerFailure || len(reply.Answer) != 0 {
		t.Fatal("Revoked client could resolve through node DNS")
	}
	cfg.Revision = 2
	cfg.DNS = &DNSListener{Enabled: false, BindAddress: "127.0.0.1", Port: port}
	if e = m.Apply(cfg); e != nil {
		t.Fatal(e)
	}
	if _, _, e = (&dns.Client{Net: "tcp", Timeout: 300 * time.Millisecond}).Exchange(query, net.JoinHostPort("127.0.0.1", strconv.Itoa(port))); e == nil {
		t.Fatal("Disabled DNS listener remained active")
	}
}
