//go:build linux

// Package carrier compiles fixed native link contracts for source-built engines.
package carrier

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/pasarguard/node/tunnel/paqet"
)

type Config struct {
	Role           string `json:"role"`
	Profile        string `json:"profile"`
	Peer           string `json:"peer"`
	PeerCA         string `json:"peer_ca"`
	PeerName       string `json:"peer_name"`
	Token          string `json:"token"`
	ClientPort     int    `json:"client_port"`
	ExitPort       int    `json:"exit_port"`
	ControlPort    int    `json:"control_port"`
	Pool           int    `json:"pool"`
	Keepalive      int    `json:"keepalive"`
	Retry          int    `json:"retry"`
	DialTimeout    int    `json:"dial_timeout"`
	NoDelay        bool   `json:"no_delay"`
	Mux            bool   `json:"mux"`
	MuxConcurrency int    `json:"mux_concurrency"`
	MuxVersion     int    `json:"mux_version"`
	FrameSize      int    `json:"frame_size"`
	ReceiveBuffer  int    `json:"receive_buffer"`
	StreamBuffer   int    `json:"stream_buffer"`
	ChannelSize    int    `json:"channel_size"`
	Heartbeat      int    `json:"heartbeat"`
	UDPBuffer      int    `json:"udp_buffer"`
}

type Definition struct{ Name, Version, Commit string }

var Definitions = []Definition{
	{"backhaul", "v0.7.2", "df7966f8f725837a680ea7b90bd37ea52666c277"},
	{"gost", "v3.3.1", "84dd70f9f65e6cb318268204887b2b8e78b93937"},
	{"frp", "v0.71.1", "d8cf1800dc1abd7fda1828cab105d7a6b5a50eb1"},
}

func decode(req paqet.Request, engine string) (Config, error) {
	var c Config
	raw, err := json.Marshal(req.Config)
	if err != nil {
		return c, err
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&c); err != nil {
		return c, errors.New("invalid native carrier fields")
	}
	if req.ManageFirewall || (c.Role != "source" && c.Role != "exit") || len(c.Token) < 32 || len(c.Token) > 256 {
		return c, errors.New("invalid native carrier role or token")
	}
	for _, p := range []int{c.ClientPort, c.ExitPort, c.ControlPort} {
		if p < 1024 || p > 65535 {
			return c, errors.New("invalid carrier port")
		}
	}
	if c.ClientPort == c.ExitPort || c.ClientPort == c.ControlPort || c.ExitPort == c.ControlPort {
		return c, errors.New("carrier ports must be distinct")
	}
	if c.Peer == "" || strings.ContainsAny(c.Peer, "\r\n/\\@ ") || len(c.Peer) > 253 {
		return c, errors.New("invalid carrier peer")
	}
	if c.Pool < 1 || c.Pool > 64 || c.Keepalive < 5 || c.Keepalive > 300 || c.Retry < 1 || c.Retry > 60 || c.DialTimeout < 1 || c.DialTimeout > 60 || c.Heartbeat < 5 || c.Heartbeat > 300 || c.UDPBuffer < 1500 || c.UDPBuffer > 65535 {
		return c, errors.New("invalid carrier connection bounds")
	}
	if c.MuxConcurrency < 1 || c.MuxConcurrency > 64 || c.MuxVersion < 1 || c.MuxVersion > 2 || c.FrameSize < 1024 || c.FrameSize > 65535 || c.ReceiveBuffer < 65536 || c.ReceiveBuffer > 16777216 || c.StreamBuffer < 4096 || c.StreamBuffer > 4194304 || c.ChannelSize < 16 || c.ChannelSize > 8192 {
		return c, errors.New("invalid carrier buffer bounds")
	}
	profiles := map[string]map[string]bool{"backhaul": {"tcp": true}, "gost": {"tcp": true, "ws": true, "tls": true, "wss": true}, "frp": {"tcp": true, "websocket": true, "kcp": true, "quic": true}}
	if !profiles[engine][c.Profile] {
		return c, errors.New("unsupported native carrier profile")
	}
	return c, nil
}
func address(host string, port int) string { return net.JoinHostPort(host, strconv.Itoa(port)) }
func local(port int) string                { return address("127.0.0.1", port) }
func writeCA(dir string, c Config) (string, error) {
	if !strings.Contains(c.PeerCA, "-----BEGIN CERTIFICATE-----") || len(c.PeerCA) > 32768 || c.PeerName == "" || strings.ContainsAny(c.PeerName, "\r\n/\\@ ") {
		return "", errors.New("verified peer certificate is required")
	}
	// The directory is a node-owned 0700 engine directory. CA contains no private key.
	path := filepath.Join(dir, "peer-ca-"+fmt.Sprintf("%x", hashCA(c.PeerCA))+".pem")
	err := os.WriteFile(path, []byte(c.PeerCA), 0600)
	return path, err
}
func New(name, dir, cert, key string) (*paqet.Manager, error) {
	return newWithBinaries(name, dir, cert, key, "/usr/local/bin")
}
func NewInDirectory(name, dir, cert, key, binDir string) (*paqet.Manager, error) {
	if !filepath.IsAbs(binDir) {
		return nil, errors.New("engine binary directory must be absolute")
	}
	return newWithBinaries(name, dir, cert, key, binDir)
}
func newWithBinaries(name, dir, cert, key, binDir string) (*paqet.Manager, error) {
	var definition Definition
	for _, d := range Definitions {
		if d.Name == name {
			definition = d
		}
	}
	if definition.Name == "" {
		return nil, errors.New("unknown built-in engine")
	}
	binary := filepath.Join(binDir, name)
	if name == "frp" {
		binary = filepath.Join(binDir, "frps")
	}
	adapter := paqet.Adapter{Name: name, Version: definition.Version, SourceCommit: definition.Commit}
	adapter.Installed = func() bool {
		binaries := []string{binary}
		if name == "frp" {
			binaries = append(binaries, filepath.Join(binDir, "frpc"))
		}
		for _, path := range binaries {
			info, err := os.Stat(path)
			if err != nil || !info.Mode().IsRegular() || info.Mode()&0111 == 0 {
				return false
			}
		}
		return true
	}
	adapter.Encode = func(req paqet.Request) ([]byte, error) {
		c, err := decode(req, name)
		if err != nil {
			return nil, err
		}
		switch name {
		case "backhaul":
			return backhaul(c), nil
		case "gost":
			return gost(c, dir, cert, key)
		case "frp":
			return frp(c, dir, cert, key)
		}
		return nil, errors.New("unknown engine")
	}
	adapter.Start = func(path string, req paqet.Request) (string, []string) {
		selected := binary
		if name == "frp" && req.Config["role"] == "exit" {
			selected = filepath.Join(binDir, "frpc")
		}
		if name == "gost" {
			return selected, []string{"-C", path}
		}
		return selected, []string{"-c", path}
	}
	if name == "frp" {
		adapter.Check = func(path string, req paqet.Request) (string, []string) {
			b, _ := adapter.Start(path, req)
			return b, []string{"verify", "-c", path}
		}
	}
	return paqet.NewAdapter(binary, dir, adapter)
}
func backhaul(c Config) []byte {
	// TCP transport supports BOTH TCP and accept_udp. Multiplexed/WS profiles do
	// not carry UDP in this pinned upstream and are not falsely advertised here.
	common := fmt.Sprintf("transport = \"tcp\"\ntoken = %s\nkeepalive_period = %d\nnodelay = %t\nlog_level = \"warn\"\nweb_port = 0\nsniffer = false\npprof = false\nskip_optz = true\n", strconv.Quote(c.Token), c.Keepalive, c.NoDelay)
	if c.Role == "source" {
		return []byte(fmt.Sprintf("[server]\nbind_addr = %s\naccept_udp = true\nports = [%s]\nchannel_size = %d\nheartbeat = %d\n", strconv.Quote(address("0.0.0.0", c.ControlPort)), strconv.Quote(local(c.ClientPort)+"="+local(c.ExitPort)), c.ChannelSize, c.Heartbeat) + common)
	}
	return []byte(fmt.Sprintf("[client]\nremote_addr = %s\nconnection_pool = %d\nretry_interval = %d\ndial_timeout = %d\n", strconv.Quote(address(c.Peer, c.ControlPort)), c.Pool, c.Retry, c.DialTimeout) + common)
}
func frp(c Config, dir, cert, key string) ([]byte, error) {
	auth := map[string]any{"method": "token", "token": c.Token, "additionalScopes": []string{"HeartBeats", "NewWorkConns"}}
	var cfg map[string]any
	if c.Role == "source" {
		cfg = map[string]any{"bindAddr": "0.0.0.0", "bindPort": c.ControlPort, "proxyBindAddr": "127.0.0.1", "auth": auth, "allowPorts": []map[string]int{{"single": c.ClientPort}}, "udpPacketSize": c.UDPBuffer, "transport": map[string]any{"maxPoolCount": c.Pool, "tcpMux": c.Mux, "heartbeatTimeout": c.Heartbeat * 3, "tls": map[string]any{"force": true, "certFile": cert, "keyFile": key}}, "log": map[string]any{"level": "warn", "disablePrintColor": true}}
		if c.Profile == "kcp" {
			cfg["kcpBindPort"] = c.ControlPort
		}
		if c.Profile == "quic" {
			cfg["quicBindPort"] = c.ControlPort
		}
	} else {
		ca, err := writeCA(dir, c)
		if err != nil {
			return nil, err
		}
		proxies := []map[string]any{}
		for _, network := range []string{"tcp", "udp"} {
			proxies = append(proxies, map[string]any{"name": "native-" + network, "type": network, "localIP": "127.0.0.1", "localPort": c.ExitPort, "remotePort": c.ClientPort})
		}
		cfg = map[string]any{"serverAddr": c.Peer, "serverPort": c.ControlPort, "loginFailExit": false, "auth": auth, "udpPacketSize": c.UDPBuffer, "proxies": proxies, "transport": map[string]any{"protocol": c.Profile, "poolCount": c.Pool, "tcpMux": c.Mux, "dialServerTimeout": c.DialTimeout, "heartbeatInterval": c.Heartbeat, "heartbeatTimeout": c.Heartbeat * 3, "tls": map[string]any{"enable": true, "trustedCaFile": ca, "serverName": c.PeerName}}, "log": map[string]any{"level": "warn", "disablePrintColor": true}}
	}
	return json.Marshal(cfg)
}
func gost(c Config, dir, cert, key string) ([]byte, error) {
	cfg := map[string]any{"log": map[string]any{"level": "warn"}}
	auth := map[string]any{"username": "native", "password": c.Token}
	if c.Role == "exit" {
		listener := map[string]any{"type": c.Profile}
		if c.Profile == "tls" || c.Profile == "wss" {
			listener["tls"] = map[string]any{"certFile": cert, "keyFile": key}
		}
		cfg["services"] = []map[string]any{{"name": "native-relay", "addr": address("0.0.0.0", c.ControlPort), "handler": map[string]any{"type": "relay", "auth": auth}, "listener": listener, "bypass": "native-target"}}
		cfg["bypasses"] = []map[string]any{{"name": "native-target", "whitelist": true, "matchers": []string{local(c.ExitPort)}}}
	} else {
		dialer := map[string]any{"type": c.Profile}
		if c.Profile == "tls" || c.Profile == "wss" {
			ca, err := writeCA(dir, c)
			if err != nil {
				return nil, err
			}
			dialer["tls"] = map[string]any{"secure": true, "caFile": ca, "serverName": c.PeerName}
		}
		cfg["chains"] = []map[string]any{{"name": "native-chain", "hops": []map[string]any{{"nodes": []map[string]any{{"addr": address(c.Peer, c.ControlPort), "connector": map[string]any{"type": "relay", "auth": auth}, "dialer": dialer}}}}}}
		services := []map[string]any{}
		for _, network := range []string{"tcp", "udp"} {
			services = append(services, map[string]any{"name": "native-" + network, "addr": local(c.ClientPort), "handler": map[string]any{"type": network, "chain": "native-chain"}, "listener": map[string]any{"type": network}, "forwarder": map[string]any{"nodes": []map[string]any{{"name": "native-exit", "addr": local(c.ExitPort)}}}})
		}
		cfg["services"] = services
	}
	return json.Marshal(cfg)
}
