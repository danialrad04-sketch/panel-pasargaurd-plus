//go:build linux

package paqet

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func fixture(t *testing.T) (*Manager, string) {
	t.Helper()
	dir := t.TempDir()
	binary := filepath.Join(dir, "engine")
	script := `#!/bin/sh
case "$1" in
version) echo 'Version: v1.0.0-alpha.21'; exit 0;;
check) if grep -q fail-check "$3"; then exit 1; fi; exit 0;;
run) if grep -q fail-start "$3"; then exit 1; fi; echo 'raw-secret socks-private'; trap 'exit 0' TERM; while :; do sleep 1; done;;
ping) echo sent; exit 0;;
esac
exit 1
`
	if err := os.WriteFile(binary, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	m, err := New(binary, filepath.Join(dir, "state"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(m.Close)
	return m, binary
}
func config() map[string]any {
	return map[string]any{"role": "client", "network": map[string]any{"interface": "lo", "ipv4": map[string]any{"addr": "127.0.0.1:0", "router_mac": "00:00:00:00:00:00"}}, "server": map[string]any{"addr": "127.0.0.2:41000"}, "transport": map[string]any{"kcp": map[string]any{"key": "raw-secret"}}, "socks5": []any{map[string]any{"password": "socks-private"}}}
}
func req(rev int) Request {
	return Request{Revision: rev, Enabled: true, Config: config(), Peer: "127.0.0.2"}
}
func TestRevisionIdempotencyAndSecrets(t *testing.T) {
	m, _ := fixture(t)
	first, err := m.Apply("1", req(1))
	if err != nil || !first.Running {
		t.Fatal(first, err)
	}
	pid := m.entries["1"].proc.cmd.Process.Pid
	second, err := m.Apply("1", req(1))
	if err != nil || !second.Running || m.entries["1"].proc.cmd.Process.Pid != pid {
		t.Fatal("idempotency failed", err)
	}
	changed := req(1)
	changed.Peer = "127.0.0.3"
	if _, err = m.Apply("1", changed); err == nil {
		t.Fatal("same revision changed config")
	}
	state, _ := m.Get("1", true)
	b, _ := json.Marshal(state)
	if strings.Contains(string(b), "raw-secret") || strings.Contains(string(b), "socks-private") {
		t.Fatal("secret in public state")
	}
	info, err := os.Stat(m.entries["1"].Path)
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("file permissions", err)
	}
	info, err = os.Stat(m.dir)
	if err != nil || info.Mode().Perm() != 0700 {
		t.Fatal("directory permissions", err)
	}
}
func TestCheckFailurePreservesProcess(t *testing.T) {
	m, _ := fixture(t)
	_, _ = m.Apply("1", req(1))
	pid := m.entries["1"].proc.cmd.Process.Pid
	r := req(2)
	r.Config["marker"] = "fail-check"
	if _, err := m.Apply("1", r); err == nil {
		t.Fatal("invalid check succeeded")
	}
	s, _ := m.Get("1", false)
	if s.Revision != 1 || !s.Running || m.entries["1"].proc.cmd.Process.Pid != pid {
		t.Fatal("check interrupted process")
	}
}
func TestEarlyFailureRollsBack(t *testing.T) {
	m, _ := fixture(t)
	_, _ = m.Apply("1", req(1))
	r := req(2)
	r.Config["marker"] = "fail-start"
	if _, err := m.Apply("1", r); err == nil {
		t.Fatal("bad run succeeded")
	}
	s, _ := m.Get("1", false)
	if s.Revision != 1 || !s.Running {
		t.Fatal("rollback failed", s)
	}
}
func TestStopTombstoneSurvivesRestart(t *testing.T) {
	m, binary := fixture(t)
	_, _ = m.Apply("1", req(1))
	r := req(2)
	r.Enabled = false
	if s, err := m.Apply("1", r); err != nil || s.Running {
		t.Fatal(s, err)
	}
	m.Close()
	restored, err := New(binary, m.dir)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	if _, err = restored.Apply("1", req(1)); err == nil {
		t.Fatal("old request revived engine")
	}
}
func TestDesiredEngineResumes(t *testing.T) {
	m, binary := fixture(t)
	_, _ = m.Apply("1", req(1))
	m.Close()
	restored, err := New(binary, m.dir)
	if err != nil {
		t.Fatal(err)
	}
	defer restored.Close()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		s, _ := restored.Get("1", false)
		if s.Running {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("engine did not resume")
}
func TestConcurrentRevisionsCannotGoBackwards(t *testing.T) {
	m, _ := fixture(t)
	var wg sync.WaitGroup
	for _, rev := range []int{1, 2} {
		wg.Add(1)
		go func(rev int) { defer wg.Done(); _, _ = m.Apply("1", req(rev)) }(rev)
	}
	wg.Wait()
	s, _ := m.Get("1", false)
	if s.Revision != 2 {
		t.Fatal(s)
	}
}
func TestHTTPAuthAndSecretRedaction(t *testing.T) {
	m, _ := fixture(t)
	_, _ = m.Apply("1", req(1))
	h := m.Handler("owner-key")
	for _, path := range []string{"/v1/paqet/capabilities", "/v1/paqet/links", "/v1/paqet/links/1"} {
		r := httptest.NewRequest("GET", path, nil)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != 401 {
			t.Fatal("unauthenticated access")
		}
		r.Header.Set("x-api-key", "owner-key")
		w = httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != 200 || strings.Contains(w.Body.String(), "raw-secret") || strings.Contains(w.Body.String(), "socks-private") {
			t.Fatal("unsafe response", w.Body.String())
		}
	}
	r := httptest.NewRequest("POST", "/v1/paqet/links/1/shell?revision=1", nil)
	r.Header.Set("x-api-key", "owner-key")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code == 200 {
		t.Fatal("shell accepted")
	}
}
func TestDiagnosticsAreRoleAndRevisionBound(t *testing.T) {
	m, _ := fixture(t)
	_, _ = m.Apply("1", req(1))
	if _, err := m.Diagnostic("1", 1, "dump"); err == nil {
		t.Fatal("client dump accepted")
	}
	if _, err := m.Diagnostic("1", 2, "version"); err == nil {
		t.Fatal("stale diagnostic")
	}
	if output, err := m.Diagnostic("1", 1, "ping"); err != nil || !strings.Contains(output, "sent") {
		t.Fatal(output, err)
	}
}
func TestNetworkMustBelongToInterface(t *testing.T) {
	r := req(1)
	object(object(r.Config, "network"), "ipv4")["addr"] = "192.0.2.1:0"
	if validateNetwork(r) == nil {
		t.Fatal("unassigned local IP accepted")
	}
}
func TestOwnedFirewallScope(t *testing.T) {
	r := req(1)
	r.ManageFirewall = true
	e := &entry{record: record{ID: "9", Request: r}}
	tool, rules, err := firewallRules(e)
	if err != nil || tool != "iptables" || len(rules) != 5 {
		t.Fatal(tool, rules, err)
	}
	for _, rule := range rules {
		s := strings.Join(rule, " ")
		if !strings.Contains(s, "pg-plus-paqet-9") || !strings.Contains(s, "127.0.0.2") || !strings.Contains(s, "41000") {
			t.Fatal("unscoped rule", s)
		}
	}
}
func TestLogsBounded(t *testing.T) {
	b := &logBuffer{}
	_, _ = b.Write([]byte(strings.Repeat("x", 200000)))
	if len(b.String()) != 65536 {
		t.Fatal("unbounded log buffer")
	}
}
