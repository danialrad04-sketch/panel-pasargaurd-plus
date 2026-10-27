// Package health probes TCP and UDP through the encrypted SS2022 carrier itself.
// The echo target is ephemeral, loopback-only, authenticated and bounded.
package health

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

type tokenState struct {
	until     time.Time
	remaining int
}
type Manager struct {
	mu         sync.Mutex
	tokens     map[string]tokenState
	tcp        net.Listener
	udp        *net.UDPConn
	slots      chan struct{}
	binary     string
	probeSlots chan struct{}
}

func New(binary string, ports ...int) (*Manager, error) {
	port := 0
	if len(ports) > 0 {
		port = ports[0]
	}
	l, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	u, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: l.Addr().(*net.TCPAddr).Port})
	if err != nil {
		l.Close()
		return nil, err
	}
	m := &Manager{tokens: map[string]tokenState{}, tcp: l, udp: u, slots: make(chan struct{}, 32), binary: binary, probeSlots: make(chan struct{}, 2)}
	go m.serveTCP()
	go m.serveUDP()
	return m, nil
}
func (m *Manager) Close() { m.tcp.Close(); m.udp.Close() }
func (m *Manager) allow(data []byte) bool {
	if len(data) < 32 {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	k := hex.EncodeToString(data[:32])
	v, ok := m.tokens[k]
	if !ok || time.Now().After(v.until) || v.remaining <= 0 {
		return false
	}
	v.remaining--
	m.tokens[k] = v
	return true
}
func (m *Manager) serveTCP() {
	for {
		c, e := m.tcp.Accept()
		if e != nil {
			return
		}
		select {
		case m.slots <- struct{}{}:
			go func() {
				defer func() { <-m.slots }()
				defer c.Close()
				c.SetDeadline(time.Now().Add(8 * time.Second))
				for i := 0; i < 3; i++ {
					var size uint32
					if binary.Read(c, binary.BigEndian, &size) != nil || size < 32 || size > 32768 {
						return
					}
					b := make([]byte, size)
					if _, e := io.ReadFull(c, b); e != nil || !m.allow(b) {
						return
					}
					if _, e = c.Write(b); e != nil {
						return
					}
				}
			}()
		default:
			c.Close()
		}
	}
}
func (m *Manager) serveUDP() {
	b := make([]byte, 1500)
	for {
		n, a, e := m.udp.ReadFromUDP(b)
		if e != nil {
			return
		}
		if m.allow(b[:n]) {
			m.udp.WriteToUDP(b[:n], a)
		}
	}
}
func (m *Manager) Handler(key string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		if key == "" || subtle.ConstantTimeCompare([]byte(key), []byte(r.Header.Get("x-api-key"))) != 1 {
			http.Error(w, "unauthorized", 401)
			return
		}
		if r.Method == "GET" && r.URL.Path == "/v1/health/capabilities" {
			json.NewEncoder(w).Encode(map[string]any{"contract": 1, "layer": "encrypted_carrier", "tcp": true, "udp": true})
			return
		}
		if r.Method != "POST" {
			w.WriteHeader(405)
			return
		}
		if r.URL.Path == "/v1/health/prepare" {
			var v struct {
				Token string `json:"token"`
			}
			if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&v) != nil {
				w.WriteHeader(422)
				return
			}
			b, e := hex.DecodeString(v.Token)
			if e != nil || len(b) != 32 {
				w.WriteHeader(422)
				return
			}
			m.mu.Lock()
			for k, v := range m.tokens {
				if time.Now().After(v.until) {
					delete(m.tokens, k)
				}
			}
			if len(m.tokens) >= 256 {
				m.mu.Unlock()
				w.WriteHeader(429)
				return
			}
			m.tokens[v.Token] = tokenState{time.Now().Add(30 * time.Second), 12}
			m.mu.Unlock()
			json.NewEncoder(w).Encode(map[string]any{"port": m.tcp.Addr().(*net.TCPAddr).Port})
			return
		}
		if r.URL.Path == "/v1/health/probe" {
			var p Probe
			if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&p) != nil {
				w.WriteHeader(422)
				return
			}
			ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
			defer cancel()
			v, e := m.Probe(ctx, p)
			if e != nil {
				http.Error(w, "Encrypted carrier probe unavailable", 503)
				return
			}
			json.NewEncoder(w).Encode(v)
			return
		}
		w.WriteHeader(404)
	})
}

type Probe struct {
	Address    string `json:"address"`
	Port       int    `json:"port"`
	Password   string `json:"password"`
	TargetPort int    `json:"target_port"`
	Token      string `json:"token"`
}
type Result struct {
	Layer      string  `json:"layer"`
	TCP        bool    `json:"tcp"`
	UDP        bool    `json:"udp"`
	TCPRTT     float64 `json:"tcp_rtt_ms"`
	UDPRTT     float64 `json:"udp_rtt_ms"`
	Loss       float64 `json:"udp_loss_percent"`
	SampleMbps float64 `json:"sample_mbps"`
	Sent       int     `json:"udp_sent"`
	Received   int     `json:"udp_received"`
}

func randomHex(n int) string { b := make([]byte, n); rand.Read(b); return hex.EncodeToString(b) }
func (m *Manager) Probe(ctx context.Context, p Probe) (Result, error) {
	select {
	case m.probeSlots <- struct{}{}:
		defer func() { <-m.probeSlots }()
	default:
		return Result{}, errors.New("probe busy")
	}
	result := Result{Layer: "encrypted_carrier", Sent: 3, Loss: 100}
	token, e := hex.DecodeString(p.Token)
	if e != nil || len(token) != 32 || p.Port < 1 || p.Port > 65535 || p.TargetPort < 1024 || p.TargetPort > 65535 || len(p.Address) > 253 || len(p.Password) > 256 {
		return result, errors.New("invalid probe")
	}
	directory, e := os.MkdirTemp("", "pg-carrier-probe-")
	if e != nil {
		return result, e
	}
	defer os.RemoveAll(directory)
	reserve, e := net.Listen("tcp4", "127.0.0.1:0")
	if e != nil {
		return result, e
	}
	port := reserve.Addr().(*net.TCPAddr).Port
	reserve.Close()
	username, password := randomHex(8), randomHex(16)
	cfg := map[string]any{"log": map[string]any{"loglevel": "none"}, "inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": port, "protocol": "socks", "settings": map[string]any{"auth": "password", "accounts": []any{map[string]any{"user": username, "pass": password}}, "udp": true}}}, "outbounds": []any{map[string]any{"protocol": "shadowsocks", "settings": map[string]any{"servers": []any{map[string]any{"address": p.Address, "port": p.Port, "method": "2022-blake3-aes-128-gcm", "password": p.Password}}}}}}
	b, _ := json.Marshal(cfg)
	path := filepath.Join(directory, "probe.json")
	if e = os.WriteFile(path, b, 0600); e != nil {
		return result, e
	}
	cmd := exec.CommandContext(ctx, m.binary, "run", "-c", path)
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	if e = cmd.Start(); e != nil {
		return result, e
	}
	defer func() { cmd.Process.Kill(); cmd.Wait() }()
	var c net.Conn
	for i := 0; i < 40; i++ {
		c, e = net.DialTimeout("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), 100*time.Millisecond)
		if e == nil {
			c.Close()
			break
		}
		select {
		case <-ctx.Done():
			return result, ctx.Err()
		case <-time.After(50 * time.Millisecond):
		}
	}
	if e != nil {
		return result, e
	}
	start := time.Now()
	c, _, e = socks(port, username, password, 1, p.TargetPort)
	if e == nil {
		c.SetDeadline(time.Now().Add(3 * time.Second))
		data := make([]byte, 32768)
		copy(data, token)
		rand.Read(data[32:])
		var elapsed time.Duration
		ok := true
		for i := 0; i < 3; i++ {
			tick := time.Now()
			if binary.Write(c, binary.BigEndian, uint32(len(data))) != nil {
				ok = false
				break
			}
			if _, e = c.Write(data); e != nil {
				ok = false
				break
			}
			back := make([]byte, len(data))
			if _, e = io.ReadFull(c, back); e != nil || subtle.ConstantTimeCompare(back, data) != 1 {
				ok = false
				break
			}
			elapsed += time.Since(tick)
		}
		c.Close()
		if ok {
			result.TCP = true
			result.TCPRTT = float64(time.Since(start).Microseconds()) / 1000 / 3
			result.SampleMbps = float64(3*len(data)*2*8) / elapsed.Seconds() / 1e6
		}
	}
	control, relay, e := socks(port, username, password, 3, 0)
	if e == nil {
		defer control.Close()
		u, e := net.DialUDP("udp4", nil, relay)
		if e == nil {
			defer u.Close()
			var total time.Duration
			for i := 0; i < 3; i++ {
				payload := make([]byte, 1200)
				copy(payload, token)
				rand.Read(payload[32:])
				packet := []byte{0, 0, 0, 1, 127, 0, 0, 1, byte(p.TargetPort >> 8), byte(p.TargetPort)}
				packet = append(packet, payload...)
				u.SetDeadline(time.Now().Add(700 * time.Millisecond))
				tick := time.Now()
				u.Write(packet)
				back := make([]byte, 2048)
				n, _, e := u.ReadFromUDP(back)
				if e == nil && n >= len(payload)+10 && subtle.ConstantTimeCompare(back[n-len(payload):n], payload) == 1 {
					result.Received++
					total += time.Since(tick)
				}
			}
			if result.Received > 0 {
				result.UDPRTT = float64(total.Microseconds()) / 1000 / float64(result.Received)
			}
			result.UDP = result.Received == 3
			result.Loss = float64(3-result.Received) * 100 / 3
		}
	}
	return result, nil
}
func socks(port int, user, password string, command byte, target int) (net.Conn, *net.UDPAddr, error) {
	c, e := net.DialTimeout("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), time.Second)
	if e != nil {
		return nil, nil, e
	}
	c.SetDeadline(time.Now().Add(3 * time.Second))
	fail := func() (net.Conn, *net.UDPAddr, error) {
		c.Close()
		return nil, nil, errors.New("probe proxy unavailable")
	}
	c.Write([]byte{5, 1, 2})
	b := make([]byte, 2)
	if _, e = io.ReadFull(c, b); e != nil || b[1] != 2 {
		return fail()
	}
	auth := []byte{1, byte(len(user))}
	auth = append(auth, []byte(user)...)
	auth = append(auth, byte(len(password)))
	auth = append(auth, []byte(password)...)
	c.Write(auth)
	if _, e = io.ReadFull(c, b); e != nil || b[1] != 0 {
		return fail()
	}
	c.Write([]byte{5, command, 0, 1, 127, 0, 0, 1, byte(target >> 8), byte(target)})
	head := make([]byte, 4)
	if _, e = io.ReadFull(c, head); e != nil || head[1] != 0 {
		return fail()
	}
	size := 4
	if head[3] == 4 {
		size = 16
	} else if head[3] != 1 {
		return fail()
	}
	addr := make([]byte, size+2)
	if _, e = io.ReadFull(c, addr); e != nil {
		return fail()
	}
	c.SetDeadline(time.Time{})
	return c, &net.UDPAddr{IP: net.IP(addr[:size]), Port: int(binary.BigEndian.Uint16(addr[size:]))}, nil
}
