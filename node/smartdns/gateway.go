// Package smartdns is a native, policy-bound TCP/UDP gateway. It never accepts
// a client supplied arbitrary proxy destination, and never forwards without a
// short online byte grant from the panel's existing DNS account/IP lease.
package smartdns

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/xtls/xray-core/core"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type Route struct {
	EgressTunnelID *int    `json:"egress_tunnel_id"`
	Egress         *Egress `json:"egress,omitempty"`
	proxy          *core.Instance
	ID             int    `json:"id"`
	NodeID         int    `json:"node_id"`
	PolicyID       int    `json:"policy_id"`
	Domain         string `json:"domain"`
	PublicAddress  string `json:"public_address"`
	BindAddress    string `json:"bind_address"`
	TCPPorts       []int  `json:"tcp_ports"`
	UDPPorts       []int  `json:"udp_ports"`
	Mode           string `json:"mode"`
	Enabled        bool   `json:"enabled"`
	Revision       int    `json:"revision"`
}
type Config struct {
	DNS      *DNSListener `json:"dns,omitempty"`
	Revision int          `json:"revision"`
	NodeID   int          `json:"node_id"`
	PanelURL string       `json:"panel_url"`
	PanelCA  string       `json:"panel_ca"`
	Routes   []Route      `json:"routes"`
	client   *http.Client
}
type listener struct {
	tcp net.Listener
	udp *net.UDPConn
}
type Manager struct {
	mu        sync.Mutex
	current   atomic.Pointer[Config]
	listeners map[string]listener
	poolsMu   sync.Mutex
	pools     map[string]*pool
	path, key string
	done      chan struct{}
	slots     chan struct{}
	udpSlots  chan struct{}
	closed    bool
	resolve   func(context.Context, string) (net.IP, error)
}

func New(directory, key string) (*Manager, error) {
	if !filepath.IsAbs(directory) || key == "" {
		return nil, errors.New("invalid gateway identity")
	}
	if err := os.MkdirAll(directory, 0700); err != nil {
		return nil, err
	}
	if err := os.Chmod(directory, 0700); err != nil {
		return nil, err
	}
	m := &Manager{path: filepath.Join(directory, "gateway.json"), key: key, listeners: map[string]listener{}, pools: map[string]*pool{}, done: make(chan struct{}), slots: make(chan struct{}, 256), udpSlots: make(chan struct{}, 2048), resolve: publicTarget}
	m.current.Store(&Config{})
	if data, err := os.ReadFile(m.path); err == nil {
		var c Config
		if len(data) > 1048576 || json.Unmarshal(data, &c) != nil {
			return nil, errors.New("invalid gateway journal")
		}
		if err = m.Apply(c); err != nil {
			m.Close()
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	go m.maintain()
	return m, nil
}
func contains(values []int, value int) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}
func hostOK(host, domain string) bool { return host == domain || strings.HasSuffix(host, "."+domain) }
func domainOK(value string) bool {
	if len(value) > 253 || !strings.Contains(value, ".") {
		return false
	}
	for _, label := range strings.Split(value, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
				return false
			}
		}
	}
	return true
}
func (m *Manager) Apply(c Config) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return errors.New("closed")
	}
	old := m.current.Load()
	if c.Revision < old.Revision || c.Revision < 1 || c.NodeID < 1 || len(c.Routes) > 256 {
		return errors.New("invalid gateway generation")
	}
	u, e := url.Parse(c.PanelURL)
	if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Path != "" {
		return errors.New("panel must be an HTTPS origin")
	}
	roots, e := x509.SystemCertPool()
	if e != nil {
		roots = x509.NewCertPool()
	}
	if c.PanelCA != "" && !roots.AppendCertsFromPEM([]byte(c.PanelCA)) {
		return errors.New("invalid panel trust certificate")
	}
	c.client = &http.Client{Timeout: 2 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}, Proxy: http.ProxyFromEnvironment, MaxIdleConns: 64, MaxIdleConnsPerHost: 32, IdleConnTimeout: 30 * time.Second, TLSHandshakeTimeout: 2 * time.Second}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	wanted := map[string]string{}
	ids := map[int]bool{}
	for _, r := range c.Routes {
		if ids[r.ID] || r.ID < 1 || r.Revision < 1 || r.NodeID != c.NodeID || !r.Enabled || !domainOK(r.Domain) || net.ParseIP(r.BindAddress) == nil || len(r.TCPPorts)+len(r.UDPPorts) == 0 || len(r.TCPPorts) > 16 || len(r.UDPPorts) > 16 || (r.Mode != "sniff" && r.Mode != "fixed") {
			return errors.New("invalid gateway route")
		}
		ids[r.ID] = true
		for _, kind := range []string{"tcp", "udp"} {
			ports := r.TCPPorts
			if kind == "udp" {
				ports = r.UDPPorts
			}
			for _, p := range ports {
				if p < 1 || p > 65535 {
					return errors.New("invalid port")
				}
				wanted[kind+"|"+net.JoinHostPort(r.BindAddress, strconv.Itoa(p))] = kind
			}
		}
	}
	if address, err := dnsAddress(c.DNS); err != nil {
		return err
	} else if address != "" {
		if _, ok := wanted["tcp|"+address]; ok {
			return errors.New("DNS/gateway TCP port conflict")
		}
		if _, ok := wanted["udp|"+address]; ok {
			return errors.New("DNS/gateway UDP port conflict")
		}
		wanted["dns-tcp|"+address] = "dns-tcp"
		wanted["dns-udp|"+address] = "dns-udp"
	}
	for i, r := range c.Routes {
		for _, other := range c.Routes[i+1:] {
			if r.BindAddress != other.BindAddress {
				continue
			}
			for _, p := range r.UDPPorts {
				if contains(other.UDPPorts, p) && r.Domain != other.Domain {
					return errors.New("ambiguous UDP destination")
				}
			}
			for _, p := range r.TCPPorts {
				if contains(other.TCPPorts, p) && (r.Mode == "fixed" || other.Mode == "fixed") && r.Domain != other.Domain {
					return errors.New("ambiguous fixed TCP destination")
				}
			}
		}
	}
	encoded, _ := json.Marshal(c)
	previous, _ := json.Marshal(old)
	if c.Revision == old.Revision {
		if bytes.Equal(encoded, previous) {
			return nil
		}
		return errors.New("gateway revision conflict")
	}
	proxies := map[Egress]*core.Instance{}
	accepted := false
	defer func() {
		if !accepted {
			closeEgress(&c)
		}
	}()
	for i := range c.Routes {
		value := c.Routes[i].Egress
		if value == nil {
			continue
		}
		proxy := proxies[*value]
		if proxy == nil {
			proxy, e = startEgress(value)
			if e != nil {
				return errors.New("encrypted egress could not start")
			}
			proxies[*value] = proxy
		}
		c.Routes[i].proxy = proxy
	}
	added := map[string]listener{}
	cleanup := func() {
		for _, l := range added {
			if l.tcp != nil {
				l.tcp.Close()
			}
			if l.udp != nil {
				l.udp.Close()
			}
		}
	}
	for key, kind := range wanted {
		if _, ok := m.listeners[key]; ok {
			continue
		}
		address := strings.SplitN(key, "|", 2)[1]
		var l listener
		if kind == "tcp" || kind == "dns-tcp" {
			l.tcp, e = net.Listen("tcp", address)
		} else {
			var a *net.UDPAddr
			a, e = net.ResolveUDPAddr("udp", address)
			if e == nil {
				l.udp, e = net.ListenUDP("udp", a)
			}
		}
		if e != nil {
			cleanup()
			return errors.New("gateway bind failed; previous listeners retained")
		}
		added[key] = l
	}
	f, e := os.CreateTemp(filepath.Dir(m.path), ".gateway-*")
	if e != nil {
		cleanup()
		return e
	}
	name := f.Name()
	defer os.Remove(name)
	f.Chmod(0600)
	_, e = f.Write(encoded)
	if e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e == nil {
		e = closeErr
	}
	if e == nil {
		e = os.Rename(name, m.path)
	}
	if e != nil {
		cleanup()
		return e
	}
	m.current.Store(&c)
	accepted = true
	closeEgress(old)
	for key, l := range m.listeners {
		if _, keep := wanted[key]; !keep {
			if l.tcp != nil {
				l.tcp.Close()
			}
			if l.udp != nil {
				l.udp.Close()
			}
			delete(m.listeners, key)
		}
	}
	for key, l := range added {
		m.listeners[key] = l
		if strings.HasPrefix(key, "dns-tcp|") {
			go m.serveDNSTCP(l.tcp)
		} else if strings.HasPrefix(key, "dns-udp|") {
			go m.serveDNSUDP(l.udp)
		} else if l.tcp != nil {
			go m.serveTCP(key, l.tcp)
		} else {
			go m.serveUDP(key, l.udp)
		}
	}
	return nil
}
func (m *Manager) Close() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return
	}
	m.closed = true
	close(m.done)
	old := m.current.Load()
	m.current.Store(&Config{})
	closeEgress(old)
	for _, l := range m.listeners {
		if l.tcp != nil {
			l.tcp.Close()
		}
		if l.udp != nil {
			l.udp.Close()
		}
	}
}
func (m *Manager) Handler(key string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		if key == "" || subtle.ConstantTimeCompare([]byte(key), []byte(r.Header.Get("x-api-key"))) != 1 {
			w.WriteHeader(401)
			return
		}
		if r.Method == "GET" && r.URL.Path == "/v1/smartdns/capabilities" {
			json.NewEncoder(w).Encode(map[string]any{"contract": 1, "tcp": true, "udp": true, "byte_leases": true})
			return
		}
		if r.Method == "GET" && r.URL.Path == "/v1/smartdns/status" {
			cfg := m.current.Load()
			json.NewEncoder(w).Encode(map[string]any{"revision": cfg.Revision, "active_tcp": len(m.slots), "active_udp": len(m.udpSlots), "dns_enabled": cfg.DNS != nil && cfg.DNS.Enabled})
			return
		}
		if r.Method != "PUT" || r.URL.Path != "/v1/smartdns/config" {
			w.WriteHeader(405)
			return
		}
		var c Config
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1048576))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&c) != nil {
			w.WriteHeader(422)
			return
		}
		if m.Apply(c) != nil {
			http.Error(w, "Gateway configuration could not be applied; inspect listener conflicts and generation", 409)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"revision": c.Revision, "applied": true})
	})
}
func (m *Manager) candidates(key, host string) []Route {
	c := m.current.Load()
	parts := strings.SplitN(key, "|", 2)
	address, port, _ := net.SplitHostPort(parts[1])
	p, _ := strconv.Atoi(port)
	out := []Route{}
	for _, r := range c.Routes {
		ports := r.TCPPorts
		if parts[0] == "udp" {
			ports = r.UDPPorts
		}
		if r.BindAddress == address && contains(ports, p) && (host == "" || hostOK(host, r.Domain)) {
			out = append(out, r)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if len(out[i].Domain) != len(out[j].Domain) {
			return len(out[i].Domain) > len(out[j].Domain)
		}
		return out[i].ID < out[j].ID
	})
	return out
}
func (m *Manager) serveTCP(key string, l net.Listener) {
	for {
		c, e := l.Accept()
		if e != nil {
			return
		}
		select {
		case m.slots <- struct{}{}:
			go func() { defer func() { <-m.slots }(); m.tcp(key, c) }()
		default:
			c.Close()
		}
	}
}
func (m *Manager) tcp(key string, c net.Conn) {
	defer c.Close()
	rows := m.candidates(key, "")
	if len(rows) == 0 {
		return
	}
	host := rows[0].Domain
	prefix := []byte{}
	var e error
	c.SetReadDeadline(time.Now().Add(5 * time.Second))
	if rows[0].Mode == "sniff" {
		host, prefix, e = sniff(c)
		if e != nil {
			return
		}
		rows = m.candidates(key, host)
	}
	c.SetDeadline(time.Time{})
	ip, _, _ := net.SplitHostPort(c.RemoteAddr().String())
	_, port, _ := net.SplitHostPort(strings.SplitN(key, "|", 2)[1])
	number, _ := strconv.Atoi(port)
	var budget *pool
	for _, r := range rows {
		budget = m.budget(r, ip, host, number, "tcp")
		if budget.ensure() {
			break
		}
		budget = nil
	}
	if budget == nil {
		return
	}
	defer budget.release()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	target, e := m.resolve(ctx, host)
	cancel()
	if e != nil {
		return
	}
	dialContext, dialCancel := context.WithCancel(context.Background())
	defer dialCancel()
	remote, e := budget.route.dialTCP(dialContext, target, number)
	if e != nil {
		return
	}
	defer remote.Close()
	if !budget.forward(remote, prefix) {
		return
	}
	done := make(chan struct{}, 1)
	go func() { budget.copy(c, remote); c.Close(); remote.Close(); done <- struct{}{} }()
	budget.copy(remote, c)
	c.Close()
	remote.Close()
	<-done
}
func (p *pool) copy(to, from net.Conn) {
	buffer := make([]byte, 65536)
	for {
		from.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, e := from.Read(buffer)
		if n > 0 && !p.forward(to, buffer[:n]) {
			return
		}
		if e != nil {
			return
		}
	}
}
func (p *pool) forward(to net.Conn, b []byte) bool {
	for len(b) > 0 {
		allowed := p.take(len(b), false)
		if allowed == 0 {
			return false
		}
		to.SetWriteDeadline(time.Now().Add(5 * time.Second))
		if _, e := to.Write(b[:allowed]); e != nil {
			return false
		}
		b = b[allowed:]
	}
	return true
}

type udpSession struct{ packets chan []byte }

func (m *Manager) serveUDP(key string, listener *net.UDPConn) {
	var mu sync.Mutex
	peers := map[string]*udpSession{}
	buffer := make([]byte, 65535)
	for {
		n, client, e := listener.ReadFromUDP(buffer)
		if e != nil {
			return
		}
		if n > 4096 {
			continue
		}
		id := client.String()
		mu.Lock()
		session := peers[id]
		if session == nil {
			select {
			case m.udpSlots <- struct{}{}:
				session = &udpSession{packets: make(chan []byte, 16)}
				peers[id] = session
				go func(id string, client *net.UDPAddr, session *udpSession) {
					defer func() { mu.Lock(); delete(peers, id); mu.Unlock(); <-m.udpSlots }()
					m.udp(key, listener, client, session)
				}(id, client, session)
			default:
				mu.Unlock()
				continue
			}
		}
		mu.Unlock()
		packet := append([]byte(nil), buffer[:n]...)
		select {
		case session.packets <- packet:
		default:
		}
	}
}
func (m *Manager) udp(key string, listener *net.UDPConn, client *net.UDPAddr, session *udpSession) {
	rows := m.candidates(key, "")
	if len(rows) == 0 {
		return
	}
	host := rows[0].Domain
	_, port, _ := net.SplitHostPort(strings.SplitN(key, "|", 2)[1])
	number, _ := strconv.Atoi(port)
	var budget *pool
	for _, route := range rows {
		budget = m.budget(route, client.IP.String(), host, number, "udp")
		if budget.ensure() {
			break
		}
		budget = nil
	}
	if budget == nil {
		return
	}
	defer budget.release()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	target, e := m.resolve(ctx, host)
	cancel()
	if e != nil {
		return
	}
	remote, e := budget.route.dialUDP(target, number)
	if e != nil {
		return
	}
	defer remote.Close()
	var in, out atomic.Int64
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		b := make([]byte, 65535)
		for {
			remote.SetReadDeadline(time.Now().Add(15 * time.Second))
			n, e := remote.Read(b)
			if e != nil {
				return
			}
			if n > 4096 || out.Load()+int64(n) > in.Load()*3+1200 {
				continue
			}
			if budget.take(n, true) != n {
				return
			}
			if _, e = listener.WriteToUDP(b[:n], client); e != nil {
				return
			}
			out.Add(int64(n))
		}
	}()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	last := time.Now()
	for {
		select {
		case packet := <-session.packets:
			if budget.take(len(packet), true) != len(packet) {
				return
			}
			in.Add(int64(len(packet)))
			last = time.Now()
			if _, e = remote.Write(packet); e != nil {
				return
			}
		case <-ticker.C:
			if time.Since(last) > 15*time.Second {
				return
			}
		case <-finished:
			return
		case <-m.done:
			return
		}
	}
}

type pool struct {
	mu                 sync.Mutex
	m                  *Manager
	c                  *Config
	route              Route
	ip, host, network  string
	port               int
	id                 string
	amount, used, refs int
	deadline, last     time.Time
}

func (m *Manager) budget(route Route, ip, host string, port int, network string) *pool {
	c := m.current.Load()
	key := strconv.Itoa(c.Revision) + "|" + strconv.Itoa(route.ID) + "|" + ip + "|" + host + "|" + network + "|" + strconv.Itoa(port)
	m.poolsMu.Lock()
	defer m.poolsMu.Unlock()
	p := m.pools[key]
	if p == nil {
		if len(m.pools) >= 8192 {
			return nil
		}
		p = &pool{m: m, c: c, route: route, ip: ip, host: host, port: port, network: network, last: time.Now()}
		m.pools[key] = p
	}
	return p
}
func (p *pool) api(action string, body, out any) bool {
	b, _ := json.Marshal(body)
	req, e := http.NewRequest("POST", p.c.PanelURL+"/api/smartdns/nodes/"+strconv.Itoa(p.c.NodeID)+"/"+action, bytes.NewReader(b))
	if e != nil {
		return false
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("x-pg-node-key", p.m.key)
	response, e := p.c.client.Do(req)
	if e != nil {
		return false
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return false
	}
	limit := int64(4096)
	if action == "resolve" {
		limit = 100000
	}
	return json.NewDecoder(io.LimitReader(response.Body, limit)).Decode(out) == nil
}
func (p *pool) finish() bool {
	if p.id == "" {
		return true
	}
	var result struct {
		Settled bool `json:"settled"`
	}
	if !p.api("settle", map[string]any{"grant_id": p.id, "used": p.used}, &result) || !result.Settled {
		return false
	}
	p.id = ""
	p.amount = 0
	p.used = 0
	return true
}
func (p *pool) ensure() bool {
	if p == nil {
		return false
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.refs++
	if !p.ready() {
		p.refs--
		return false
	}
	return true
}
func (p *pool) release() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.refs--
	p.last = time.Now()
	if p.refs == 0 {
		p.finish()
	}
}
func (p *pool) ready() bool {
	if p.m.current.Load() != p.c {
		return false
	}
	p.last = time.Now()
	if p.id != "" && (time.Now().After(p.deadline) || p.used == p.amount) {
		if !p.finish() {
			return false
		}
	}
	if p.id == "" {
		id := make([]byte, 16)
		rand.Read(id)
		id[6] = (id[6] & 15) | 64
		id[8] = (id[8] & 63) | 128
		raw := hex.EncodeToString(id)
		uuid := raw[:8] + "-" + raw[8:12] + "-" + raw[12:16] + "-" + raw[16:20] + "-" + raw[20:]
		start := time.Now()
		var result struct {
			ID     string `json:"grant_id"`
			Amount int    `json:"amount"`
			Lease  int    `json:"lease_ms"`
		}
		if !p.api("reserve", map[string]any{"request_id": uuid, "issued_at": time.Now().Unix(), "route_id": p.route.ID, "revision": p.route.Revision, "source_ip": p.ip, "host": p.host, "port": p.port, "network": p.network, "amount": 262144}, &result) || result.ID != uuid || result.Amount < 1 || result.Amount > 262144 || result.Lease < 1 || result.Lease > 2000 {
			return false
		}
		p.id, p.amount, p.used = uuid, result.Amount, 0
		p.deadline = start.Add(time.Duration(result.Lease) * time.Millisecond)
		if !time.Now().Before(p.deadline) {
			p.finish()
			return false
		}
	}
	return true
}
func (p *pool) take(n int, whole bool) int {
	if p == nil {
		return 0
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if !p.ready() {
		return 0
	}
	available := p.amount - p.used
	if whole && available < n {
		if !p.finish() || !p.ready() {
			return 0
		}
		available = p.amount - p.used
		if available < n {
			p.finish()
			return 0
		}
	}
	if n > available {
		n = available
	}
	p.used += n
	return n
}
func (m *Manager) maintain() {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	workers := make(chan struct{}, 16)
	for {
		select {
		case <-m.done:
			return
		case <-ticker.C:
			m.poolsMu.Lock()
			snapshot := make(map[string]*pool, len(m.pools))
			for key, p := range m.pools {
				snapshot[key] = p
			}
			m.poolsMu.Unlock()
			for key, p := range snapshot {
				select {
				case workers <- struct{}{}:
					go func(key string, p *pool) {
						defer func() { <-workers }()
						if !p.mu.TryLock() {
							return
						}
						defer p.mu.Unlock()
						if p.id != "" && (time.Now().After(p.deadline) || m.current.Load() != p.c) {
							p.finish()
						}
						if p.id == "" && p.refs == 0 && time.Since(p.last) > 30*time.Second {
							m.poolsMu.Lock()
							if m.pools[key] == p {
								delete(m.pools, key)
							}
							m.poolsMu.Unlock()
						}
					}(key, p)
				default:
				}
			}
		}
	}
}

func publicTarget(ctx context.Context, host string) (net.IP, error) {
	addresses, e := net.DefaultResolver.LookupIPAddr(ctx, host)
	if e != nil {
		return nil, e
	}
	for _, address := range addresses {
		if publicIP(address.IP) {
			return address.IP, nil
		}
	}
	return nil, errors.New("destination has no permitted public address")
}
func publicIP(ip net.IP) bool {
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return false
	}
	for _, prefix := range []string{"0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4", "64:ff9b::/96", "64:ff9b:1::/48", "2001::/32", "2001:db8::/32", "2002::/16", "fec0::/10", "2001:2::/48", "2001:10::/28", "2001:20::/28", "3ffe::/16", "3fff::/20"} {
		_, network, _ := net.ParseCIDR(prefix)
		if network.Contains(ip) {
			return false
		}
	}
	return true
}
func sniff(c net.Conn) (string, []byte, error) {
	first := make([]byte, 1)
	if _, e := io.ReadFull(c, first); e != nil {
		return "", nil, e
	}
	if first[0] == 22 {
		header := make([]byte, 5)
		header[0] = 22
		if _, e := io.ReadFull(c, header[1:]); e != nil {
			return "", nil, e
		}
		size := int(binary.BigEndian.Uint16(header[3:]))
		if size < 43 || size > 16384 {
			return "", nil, errors.New("invalid TLS record")
		}
		data := make([]byte, size)
		if _, e := io.ReadFull(c, data); e != nil {
			return "", nil, e
		}
		host, e := serverName(data)
		return host, append(header, data...), e
	}
	// Preserve any body bytes prefetched with the header; charge and forward once.
	data := first
	block := make([]byte, 4096)
	for len(data) < 16384 && !bytes.Contains(data, []byte("\r\n\r\n")) {
		limit := len(block)
		if 16384-len(data) < limit {
			limit = 16384 - len(data)
		}
		n, e := c.Read(block[:limit])
		if n > 0 {
			data = append(data, block[:n]...)
		}
		if e != nil {
			return "", nil, e
		}
	}
	boundary := bytes.Index(data, []byte("\r\n\r\n"))
	if boundary < 0 {
		return "", nil, errors.New("HTTP header too large")
	}
	lines := strings.Split(string(data[:boundary]), "\r\n")
	for _, line := range lines[1:] {
		parts := strings.SplitN(line, ":", 2)
		if len(parts) == 2 && strings.EqualFold(parts[0], "host") {
			host := strings.ToLower(strings.TrimSpace(parts[1]))
			if h, _, e := net.SplitHostPort(host); e == nil {
				host = h
			}
			if domainOK(host) {
				return host, data, nil
			}
		}
	}
	return "", nil, errors.New("explicit TLS SNI or HTTP Host required")
}
func serverName(b []byte) (string, error) {
	bad := func() (string, error) { return "", errors.New("invalid ClientHello or missing SNI") }
	if len(b) < 43 || b[0] != 1 {
		return bad()
	}
	i := 38
	i += 1 + int(b[i])
	if i+2 > len(b) {
		return bad()
	}
	size := int(binary.BigEndian.Uint16(b[i:]))
	i += 2 + size
	if i >= len(b) {
		return bad()
	}
	i += 1 + int(b[i])
	if i+2 > len(b) {
		return bad()
	}
	size = int(binary.BigEndian.Uint16(b[i:]))
	i += 2
	end := i + size
	if end > len(b) {
		return bad()
	}
	for i+4 <= end {
		kind := binary.BigEndian.Uint16(b[i:])
		size = int(binary.BigEndian.Uint16(b[i+2:]))
		i += 4
		if i+size > end {
			return bad()
		}
		if kind == 0 {
			value := b[i : i+size]
			if len(value) < 5 || value[2] != 0 {
				return bad()
			}
			n := int(binary.BigEndian.Uint16(value[3:]))
			if n < 1 || 5+n > len(value) {
				return bad()
			}
			host := strings.ToLower(string(value[5 : 5+n]))
			if !domainOK(host) {
				return bad()
			}
			return host, nil
		}
		i += size
	}
	return bad()
}

// Keep the on-disk fingerprint stable without exposing private runtime pointers.
func fingerprint(c Config) [32]byte { b, _ := json.Marshal(c); return sha256.Sum256(b) }
