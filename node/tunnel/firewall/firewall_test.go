//go:build linux

package firewall

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestOwnedIngressNeverChangesGlobalPolicyAndFencesRevisions(t *testing.T) {
	dir := t.TempDir()
	calls := []string{}
	m := &Manager{path: filepath.Join(dir, "ingress.json"), chain: "PG_PLUS_AUTO", run: func(binary string, args ...string) error {
		calls = append(calls, binary+" "+strings.Join(args, " "))
		if len(args) > 2 && args[2] == "-C" {
			return errors.New("not present")
		}
		return nil
	}}
	c := Config{Revision: 1, Rules: []Rule{{Port: 40900, Network: "tcp", Source: "192.0.2.1/32"}}}
	if e := m.Apply(c); e != nil {
		t.Fatal(e)
	}
	for _, call := range calls {
		if strings.Contains(call, " -P ") || strings.Contains(call, " -F INPUT") || strings.Contains(call, "FORWARD") {
			t.Fatal("global firewall mutated", call)
		}
	}
	b, e := os.ReadFile(m.path)
	if e != nil {
		t.Fatal(e)
	}
	var saved Config
	if json.Unmarshal(b, &saved) != nil || saved.Revision != 1 {
		t.Fatal("journal missing")
	}
	if e = m.Apply(c); e == nil {
		t.Fatal("stale revision accepted")
	}
	if e = m.Apply(Config{Revision: 2, Rules: []Rule{{Port: 22, Network: "tcp", Source: "0.0.0.0/0"}}}); e == nil {
		t.Fatal("SSH ingress accepted")
	}
	m.run = func(binary string, args ...string) error { return errors.New("failed") }
	if e = m.Apply(Config{Revision: 2, Rules: c.Rules}); e == nil {
		t.Fatal("failed host command acknowledged")
	}
	b, _ = os.ReadFile(m.path)
	_ = json.Unmarshal(b, &saved)
	if saved.Revision != 1 {
		t.Fatal("failed config committed")
	}
}
func TestIngressAPIRequiresNativeKeyAndDoesNotAcceptCommands(t *testing.T) {
	m := &Manager{path: filepath.Join(t.TempDir(), "ingress.json"), chain: "PG_PLUS_AUTO", run: func(string, ...string) error { return nil }}
	h := m.Handler("native-key")
	req := httptest.NewRequest(http.MethodPut, "/v1/network/ingress", bytes.NewBufferString(`{"revision":1,"rules":[]}`))
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != 401 {
		t.Fatal("unauthorized accepted")
	}
	req = httptest.NewRequest(http.MethodPut, "/v1/network/ingress", bytes.NewBufferString(`{"revision":1,"rules":[],"command":"arbitrary"}`))
	req.Header.Set("x-api-key", "native-key")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != 422 {
		t.Fatal("unknown command accepted")
	}
}

func TestCustomSSHAndManagementPortsAreProtectedWhileHTTPSIsAllowed(t *testing.T) {
	m := &Manager{protected: []int{2222, 62050, 62052, 62053}}
	for _, port := range []int{22, 2222, 62050, 62052, 62053} {
		if err := m.validate(Config{Revision: 1, Rules: []Rule{{Port: port, Network: "tcp", Source: "0.0.0.0/0"}}}); err == nil {
			t.Fatalf("protected port %d accepted", port)
		}
	}
	if err := m.validate(Config{Revision: 1, Rules: []Rule{{Port: 443, Network: "tcp", Source: "0.0.0.0/0"}}}); err != nil {
		t.Fatal("HTTPS data ingress rejected", err)
	}
}
