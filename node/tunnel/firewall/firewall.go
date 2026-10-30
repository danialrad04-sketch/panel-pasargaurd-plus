//go:build linux

// Package firewall owns only the dedicated native ingress chain.
package firewall

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

type Rule struct {
	Port    int    `json:"port"`
	Network string `json:"network"`
	Source  string `json:"source"`
}
type Config struct {
	Revision int    `json:"revision"`
	Rules    []Rule `json:"rules"`
}
type Manager struct {
	mu        sync.Mutex
	path      string
	chain     string
	protected []int
	config    Config
	run       func(string, ...string) error
}

func New(dir string, protected ...int) (*Manager, error) {
	return newManager(dir, "PG_PLUS_AUTO", protected...)
}
func NewBootstrap(dir string) (*Manager, error) { return newManager(dir, "PG_PLUS_BOOTSTRAP") }
func newManager(dir, chain string, protected ...int) (*Manager, error) {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	m := &Manager{protected: append([]int{22}, protected...), chain: chain, path: filepath.Join(dir, "ingress.json"), run: execute}
	if b, e := os.ReadFile(m.path); e == nil {
		if json.Unmarshal(b, &m.config) != nil {
			return nil, errors.New("invalid native ingress journal")
		}
		if e = m.validate(m.config); e != nil {
			return nil, e
		}
		if e = m.replace(m.config); e != nil {
			return nil, e
		}
	} else if !os.IsNotExist(e) {
		return nil, e
	}
	return m, nil
}
func execute(binary string, args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	if exec.CommandContext(ctx, binary, args...).Run() != nil {
		return errors.New("native ingress command failed")
	}
	return nil
}
func (m *Manager) validate(c Config) error {
	if c.Revision < 1 || len(c.Rules) > 256 {
		return errors.New("invalid ingress revision or capacity")
	}
	for _, r := range c.Rules {
		_, n, e := net.ParseCIDR(r.Source)
		if e != nil || n.String() != r.Source || r.Port < 1 || r.Port > 65535 || (r.Network != "tcp" && r.Network != "udp") {
			return errors.New("invalid ingress rule")
		}
	}
	for _, r := range c.Rules {
		for _, p := range append([]int{22}, m.protected...) {
			if r.Port == p {
				return errors.New("ingress cannot change SSH or node management access")
			}
		}
	}
	return nil
}
func (m *Manager) replace(c Config) error {
	chain := m.chain
	for _, binary := range []string{"iptables", "ip6tables"} {
		family := 4
		if binary == "ip6tables" {
			family = 6
		}
		relevant := []Rule{}
		for _, r := range c.Rules {
			ip, _, _ := net.ParseCIDR(r.Source)
			if (ip.To4() != nil && family == 4) || (ip.To4() == nil && family == 6) {
				relevant = append(relevant, r)
			}
		}
		if len(relevant) == 0 {
			_ = m.run(binary, "-w", "5", "-D", "INPUT", "-j", chain)
			_ = m.run(binary, "-w", "5", "-F", chain)
			_ = m.run(binary, "-w", "5", "-X", chain)
			continue
		}
		_ = m.run(binary, "-w", "5", "-N", chain)
		if e := m.run(binary, "-w", "5", "-F", chain); e != nil {
			return e
		}
		for _, r := range relevant {
			if e := m.run(binary, "-w", "5", "-A", chain, "-s", r.Source, "-p", r.Network, "--dport", strconv.Itoa(r.Port), "-m", "comment", "--comment", "pg-plus-native", "-j", "ACCEPT"); e != nil {
				return e
			}
		}
		if m.run(binary, "-w", "5", "-C", "INPUT", "-j", chain) != nil {
			if e := m.run(binary, "-w", "5", "-I", "INPUT", "1", "-j", chain); e != nil {
				return e
			}
		}
	}
	return nil
}
func (m *Manager) Apply(c Config) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if e := m.validate(c); e != nil {
		return e
	}
	if c.Revision <= m.config.Revision {
		return errors.New("ingress revision conflict")
	}
	old := m.config
	if e := m.replace(c); e != nil {
		_ = m.replace(old)
		return e
	}
	b, _ := json.Marshal(c)
	f, e := os.CreateTemp(filepath.Dir(m.path), ".ingress-")
	if e != nil {
		_ = m.replace(old)
		return e
	}
	name := f.Name()
	defer os.Remove(name)
	e = f.Chmod(0600)
	if e == nil {
		_, e = f.Write(b)
	}
	if e == nil {
		e = f.Sync()
	}
	_ = f.Close()
	if e == nil {
		e = os.Rename(name, m.path)
	}
	if e != nil {
		_ = m.replace(old)
		return e
	}
	m.config = c
	return nil
}
func (m *Manager) Handler(key string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		if key == "" || subtle.ConstantTimeCompare([]byte(key), []byte(r.Header.Get("x-api-key"))) != 1 {
			http.Error(w, "unauthorized", 401)
			return
		}
		if r.URL.Path != "/v1/network/ingress" {
			http.NotFound(w, r)
			return
		}
		if r.Method == "PUT" {
			var c Config
			d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 65536))
			d.DisallowUnknownFields()
			if d.Decode(&c) != nil {
				http.Error(w, "invalid ingress", 422)
				return
			}
			if e := m.Apply(c); e != nil {
				http.Error(w, "native ingress was not acknowledged", 409)
				return
			}
		} else if r.Method != "GET" {
			w.WriteHeader(405)
			return
		}
		m.mu.Lock()
		defer m.mu.Unlock()
		_ = json.NewEncoder(w).Encode(map[string]any{"revision": m.config.Revision, "rules": len(m.config.Rules), "scope": fmt.Sprint("native_ingress_only")})
	})
}
