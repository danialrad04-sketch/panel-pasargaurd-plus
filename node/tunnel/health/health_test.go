package health

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func TestEchoAuthenticationAndBoundedPreparation(t *testing.T) {
	m, e := New("/unused")
	if e != nil {
		t.Fatal(e)
	}
	defer m.Close()
	h := m.Handler("private-node-key")
	r := httptest.NewRequest("POST", "/v1/health/prepare", bytes.NewBufferString(`{"token":"bad"}`))
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal(w.Code)
	}
	token := randomHex(32)
	b, _ := json.Marshal(map[string]string{"token": token})
	r = httptest.NewRequest("POST", "/v1/health/prepare", bytes.NewReader(b))
	r.Header.Set("x-api-key", "private-node-key")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	if bytes.Contains(w.Body.Bytes(), []byte(token)) {
		t.Fatal("echo capability leaked token")
	}
	packet := make([]byte, 64)
	if m.allow(packet) {
		t.Fatal("unauthenticated echo")
	}
}
func TestActualEncryptedCarrierTCPUDP(t *testing.T) {
	executable := os.Getenv("XRAY_TEST_BINARY")
	if executable == "" {
		t.Skip("Set XRAY_TEST_BINARY for real carrier acceptance")
	}
	echo, e := New(executable)
	if e != nil {
		t.Fatal(e)
	}
	defer echo.Close()
	token := randomHex(32)
	echo.tokens[token] = tokenState{time.Now().Add(30 * time.Second), 12}
	l, e := net.Listen("tcp4", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	secret := base64.StdEncoding.EncodeToString(make([]byte, 16))
	config := map[string]any{"log": map[string]any{"loglevel": "none"}, "inbounds": []any{map[string]any{"listen": "127.0.0.1", "port": port, "protocol": "shadowsocks", "settings": map[string]any{"method": "2022-blake3-aes-128-gcm", "password": secret, "network": "tcp,udp"}}}, "outbounds": []any{map[string]any{"protocol": "freedom", "settings": map[string]any{"finalRules": []any{map[string]any{"action": "allow", "ip": []string{"127.0.0.1"}}}}}}}
	b, _ := json.Marshal(config)
	path := filepath.Join(t.TempDir(), "server.json")
	os.WriteFile(path, b, 0600)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, executable, "run", "-c", path)
	if e = cmd.Start(); e != nil {
		t.Fatal(e)
	}
	defer func() { cmd.Process.Kill(); cmd.Wait() }()
	time.Sleep(250 * time.Millisecond)
	source, e := New(executable)
	if e != nil {
		t.Fatal(e)
	}
	defer source.Close()
	p := Probe{Address: "127.0.0.1", Port: port, Password: secret, TargetPort: echo.tcp.Addr().(*net.TCPAddr).Port, Token: token}
	result, e := source.Probe(ctx, p)
	if e != nil || !result.TCP || !result.UDP || result.Loss != 0 || result.Received != 3 || result.SampleMbps <= 0 {
		t.Fatalf("actual encrypted carrier failed: %+v %v", result, e)
	}
	cmd.Process.Kill()
	cmd.Wait()
	failed, e := source.Probe(ctx, p)
	if e == nil && (failed.TCP || failed.UDP) {
		t.Fatalf("dead encrypted exit was reported healthy: %+v", failed)
	}
}
