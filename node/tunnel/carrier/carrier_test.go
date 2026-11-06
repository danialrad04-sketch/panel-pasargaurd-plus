//go:build linux

package carrier

import (
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/pasarguard/node/tunnel/paqet"
)

func sample(profile string) Config {
	return Config{Role: "source", Profile: profile, Peer: "127.0.0.1", PeerName: "127.0.0.1", Token: "native-test-token-012345678901234567890", ClientPort: 12081, ExitPort: 12082, ControlPort: 12083, Pool: 2, Keepalive: 10, Retry: 1, DialTimeout: 3, NoDelay: true, Mux: true, MuxConcurrency: 2, MuxVersion: 1, FrameSize: 32768, ReceiveBuffer: 4194304, StreamBuffer: 65536, ChannelSize: 128, Heartbeat: 5, UDPBuffer: 4096}
}
func request(c Config, rev int) paqet.Request {
	raw, _ := json.Marshal(c)
	var v map[string]any
	_ = json.Unmarshal(raw, &v)
	return paqet.Request{Revision: rev, Enabled: true, Config: v}
}
func freePort(t *testing.T) int {
	t.Helper()
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	p := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return p
}
func certificate(t *testing.T, dir string) (string, string, string) {
	t.Helper()
	key, e := rsa.GenerateKey(rand.Reader, 2048)
	if e != nil {
		t.Fatal(e)
	}
	c := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "native-test"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, e := x509.CreateCertificate(rand.Reader, c, c, &key.PublicKey, key)
	if e != nil {
		t.Fatal(e)
	}
	cert := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	path := filepath.Join(dir, "cert.pem")
	private := filepath.Join(dir, "key.pem")
	if e = os.WriteFile(path, cert, 0600); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(private, pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)}), 0600); e != nil {
		t.Fatal(e)
	}
	return path, private, string(cert)
}
func echo(t *testing.T, port int) {
	t.Helper()
	tcp, e := net.Listen("tcp", local(port))
	if e != nil {
		t.Fatal(e)
	}
	udp, e := net.ListenPacket("udp", local(port))
	if e != nil {
		tcp.Close()
		t.Fatal(e)
	}
	t.Cleanup(func() { tcp.Close(); udp.Close() })
	go func() {
		for {
			c, e := tcp.Accept()
			if e != nil {
				return
			}
			go func() {
				defer c.Close()
				b := make([]byte, 8192)
				for {
					n, e := c.Read(b)
					if n > 0 {
						_, _ = c.Write(b[:n])
					}
					if e != nil {
						return
					}
				}
			}()
		}
	}()
	go func() {
		b := make([]byte, 65535)
		for {
			n, a, e := udp.ReadFrom(b)
			if e != nil {
				return
			}
			_, _ = udp.WriteTo(b[:n], a)
		}
	}()
}
func roundtrip(network string, port int) bool {
	c, e := net.DialTimeout(network, local(port), time.Second)
	if e != nil {
		return false
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(time.Second))
	payload := bytes.Repeat([]byte("native-carrier"), 80)
	if _, e = c.Write(payload); e != nil {
		return false
	}
	out := make([]byte, len(payload))
	var n int
	if network == "tcp" {
		n, e = io.ReadFull(c, out)
	} else {
		n, e = c.Read(out)
	}
	return e == nil && n == len(payload) && bytes.Equal(out, payload)
}
func awaitTraffic(t *testing.T, network string, port int) {
	t.Helper()
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		if roundtrip(network, port) {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("%s traffic did not traverse native carrier", network)
}
func TestRejectUnsafeCarrierContracts(t *testing.T) {
	for _, def := range Definitions {
		c := sample("tcp")
		req := request(c, 1)
		req.Config["command"] = "arbitrary"
		if _, e := decode(req, def.Name); e == nil {
			t.Fatal("unknown command accepted")
		}
		req = request(c, 1)
		req.ManageFirewall = true
		if _, e := decode(req, def.Name); e == nil {
			t.Fatal("firewall request accepted")
		}
	}
	c := sample("tcpmux")
	if _, e := decode(request(c, 1), "backhaul"); e == nil {
		t.Fatal("UDP-incompatible profile accepted")
	}
}
func TestActualSourceBuiltCarriersTCPUDPAndLifecycle(t *testing.T) {
	bins := os.Getenv("NATIVE_CARRIER_TEST_BIN")
	if bins == "" {
		t.Skip("set NATIVE_CARRIER_TEST_BIN to source-built engine directory")
	}
	cases := map[string][]string{"backhaul": {"tcp"}, "gost": {"tcp", "ws", "tls", "wss"}, "frp": {"tcp", "websocket", "kcp", "quic"}}
	for _, def := range Definitions {
		for _, profile := range cases[def.Name] {
			t.Run(def.Name+"/"+profile, func(t *testing.T) {
				dir := t.TempDir()
				cert, key, ca := certificate(t, dir)
				c := sample(profile)
				c.ClientPort = freePort(t)
				c.ExitPort = freePort(t)
				c.ControlPort = freePort(t)
				c.PeerCA = ca
				echo(t, c.ExitPort)
				source, e := newWithBinaries(def.Name, filepath.Join(dir, "source"), cert, key, bins)
				if e != nil {
					t.Fatal(e)
				}
				t.Cleanup(source.Close)
				exit, e := newWithBinaries(def.Name, filepath.Join(dir, "exit"), cert, key, bins)
				if e != nil {
					t.Fatal(e)
				}
				t.Cleanup(exit.Close)
				t.Cleanup(func() {
					if t.Failed() {
						a, _ := source.Get("00000000000000000000000000000001", true)
						b, _ := exit.Get("00000000000000000000000000000001", true)
						t.Logf("source: %+v exit: %+v", a, b)
					}
				})
				id := "00000000000000000000000000000001"
				other := c
				other.Role = "exit"
				apply := func(manager *paqet.Manager, cfg Config) {
					if _, e := manager.Apply(id, request(cfg, 1)); e != nil {
						t.Fatal(e)
					}
				}
				if def.Name == "gost" {
					apply(exit, other)
					apply(source, c)
				} else {
					apply(source, c)
					apply(exit, other)
				}
				awaitTraffic(t, "tcp", c.ClientPort)
				awaitTraffic(t, "udp", c.ClientPort)
				state, e := source.Get(id, true)
				if e != nil || !state.Running || bytes.Contains([]byte(state.Logs), []byte(c.Token)) {
					t.Fatal("runtime state or redaction invalid", e)
				}
				if _, e = source.Restart(id, 1); e != nil {
					t.Fatal(e)
				}
				awaitTraffic(t, "tcp", c.ClientPort)
				awaitTraffic(t, "udp", c.ClientPort)
				disabled := request(c, 2)
				disabled.Enabled = false
				if _, e = source.Apply(id, disabled); e != nil {
					t.Fatal(e)
				}
				if _, e = source.Apply(id, request(c, 1)); e == nil {
					t.Fatal("old revision accepted")
				}
				source.Close()
				reopened, e := newWithBinaries(def.Name, filepath.Join(dir, "source"), cert, key, bins)
				if e != nil {
					t.Fatal(e)
				}
				defer reopened.Close()
				state, e = reopened.Get(id, false)
				if e != nil || state.Running || state.Revision != 2 {
					t.Fatal("disabled journal not preserved", e)
				}
			})
		}
	}
}

func TestVerifiedTLSRejectsUntrustedPeerAndRecoversAtNewRevision(t *testing.T) {
	bins := os.Getenv("NATIVE_CARRIER_TEST_BIN")
	if bins == "" {
		t.Skip("set NATIVE_CARRIER_TEST_BIN")
	}
	for _, engine := range []string{"gost", "frp"} {
		t.Run(engine, func(t *testing.T) {
			dir := t.TempDir()
			cert, key, ca := certificate(t, dir)
			foreign := t.TempDir()
			_, _, wrongCA := certificate(t, foreign)
			c := sample("tls")
			if engine == "frp" {
				c.Profile = "tcp"
			}
			c.ClientPort = freePort(t)
			c.ExitPort = freePort(t)
			c.ControlPort = freePort(t)
			c.PeerCA = ca
			echo(t, c.ExitPort)
			source, e := newWithBinaries(engine, filepath.Join(dir, "source"), cert, key, bins)
			if e != nil {
				t.Fatal(e)
			}
			defer source.Close()
			exit, e := newWithBinaries(engine, filepath.Join(dir, "exit"), cert, key, bins)
			if e != nil {
				t.Fatal(e)
			}
			defer exit.Close()
			other := c
			other.Role = "exit"
			id := "00000000000000000000000000000002"
			client := source
			bad := c
			if engine == "frp" {
				client = exit
				bad = other
			}
			bad.PeerCA = wrongCA
			if engine == "gost" {
				_, e = exit.Apply(id, request(other, 1))
			} else {
				_, e = source.Apply(id, request(c, 1))
			}
			if e != nil {
				t.Fatal(e)
			}
			if _, e = client.Apply(id, request(bad, 1)); e != nil {
				t.Fatal(e)
			}
			for i := 0; i < 3; i++ {
				if roundtrip("tcp", c.ClientPort) {
					t.Fatal("untrusted carrier certificate accepted")
				}
				time.Sleep(100 * time.Millisecond)
			}
			bad.PeerCA = ca
			if _, e = client.Apply(id, request(bad, 2)); e != nil {
				t.Fatal(e)
			}
			awaitTraffic(t, "tcp", c.ClientPort)
			awaitTraffic(t, "udp", c.ClientPort)
		})
	}
}
