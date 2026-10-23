//go:build linux

// Package paqet supervises the source-built Paqet engine as part of PasarGuard Node.
// It owns only its private state and tagged firewall rules; it never invokes a shell.
package paqet

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const EngineVersion = "v1.0.0-alpha.21"
const ContractVersion = 1

type Request struct {
	Revision       int            `json:"revision"`
	Enabled        bool           `json:"enabled"`
	Config         map[string]any `json:"config"`
	Peer           string         `json:"peer"`
	ManageFirewall bool           `json:"manage_firewall"`
}
type record struct {
	ID      string  `json:"id"`
	Request Request `json:"request"`
	Hash    string  `json:"hash"`
	Path    string  `json:"path"`
}
type process struct {
	cmd  *exec.Cmd
	done chan struct{}
}
type entry struct {
	record
	proc      *process
	logs      *logBuffer
	retries   int
	next      time.Time
	lastError string
}
type Status struct {
	ID         string `json:"id"`
	Revision   int    `json:"revision"`
	Enabled    bool   `json:"enabled"`
	Running    bool   `json:"running"`
	ConfigHash string `json:"config_hash"`
	Restarts   int    `json:"restarts"`
	LastError  string `json:"last_error"`
	Logs       string `json:"logs,omitempty"`
}
type Manager struct {
	mu          sync.Mutex
	binary, dir string
	entries     map[string]*entry
	done        chan struct{}
	closed      bool
}

func New(binary, dir string) (*Manager, error) {
	if !filepath.IsAbs(binary) || !filepath.IsAbs(dir) {
		return nil, errors.New("Paqet paths must be absolute")
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return nil, err
	}
	m := &Manager{binary: binary, dir: dir, entries: make(map[string]*entry), done: make(chan struct{})}
	files, _ := filepath.Glob(filepath.Join(dir, "link-*.state"))
	for _, file := range files {
		data, err := os.ReadFile(file)
		if err != nil {
			return nil, err
		}
		var rec record
		if json.Unmarshal(data, &rec) != nil || !validID(rec.ID) || rec.Path != m.configPath(rec.ID, rec.Request.Revision) {
			return nil, errors.New("invalid private Paqet journal")
		}
		e := &entry{record: rec, logs: &logBuffer{}}
		m.entries[rec.ID] = e
	}
	go m.watch()
	return m, nil
}
func validID(id string) bool {
	if len(id) == 32 {
		_, err := hex.DecodeString(id)
		return err == nil && id == strings.ToLower(id)
	}
	n, err := strconv.ParseUint(id, 10, 53)
	return err == nil && n > 0 && strconv.FormatUint(n, 10) == id
}
func (m *Manager) configPath(id string, rev int) string {
	return filepath.Join(m.dir, fmt.Sprintf("link-%s-r%d.json", id, rev))
}
func digest(req Request) string {
	b, _ := json.Marshal(req)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
func (m *Manager) persist(e *entry) error {
	b, err := json.Marshal(e.record)
	if err != nil {
		return err
	}
	return atomicFile(filepath.Join(m.dir, "link-"+e.ID+".state"), b)
}
func atomicFile(path string, data []byte) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".new-*")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(name, path)
}
func running(e *entry) bool {
	if e == nil || e.proc == nil {
		return false
	}
	select {
	case <-e.proc.done:
		return false
	default:
		return true
	}
}
func (m *Manager) launch(e *entry) error {
	cmd := exec.Command(m.binary, "run", "-c", e.Path)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true, Pdeathsig: syscall.SIGKILL}
	cmd.Stdout = e.logs
	cmd.Stderr = e.logs
	if err := cmd.Start(); err != nil {
		return errors.New("Paqet process could not start")
	}
	p := &process{cmd: cmd, done: make(chan struct{})}
	e.proc = p
	go func() { _ = cmd.Wait(); close(p.done) }()
	select {
	case <-p.done:
		return errors.New("Paqet exited during startup; inspect redacted logs")
	case <-time.After(300 * time.Millisecond):
		return nil
	}
}
func stop(e *entry) {
	if !running(e) {
		return
	}
	p := e.proc
	_ = syscall.Kill(-p.cmd.Process.Pid, syscall.SIGTERM)
	select {
	case <-p.done:
	case <-time.After(3 * time.Second):
		_ = syscall.Kill(-p.cmd.Process.Pid, syscall.SIGKILL)
		<-p.done
	}
}
func (m *Manager) status(e *entry, logs bool) Status {
	result := Status{ID: e.ID, Revision: e.Request.Revision, Enabled: e.Request.Enabled, Running: running(e), ConfigHash: e.Hash, Restarts: e.retries, LastError: e.lastError}
	if logs {
		result.Logs = redact(e.logs.String(), e.Request.Config)
	}
	return result
}
func (m *Manager) Get(id string, logs bool) (Status, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entries[id]
	if e == nil {
		return Status{}, os.ErrNotExist
	}
	return m.status(e, logs), nil
}
func (m *Manager) List() []Status {
	m.mu.Lock()
	defer m.mu.Unlock()
	result := []Status{}
	for _, e := range m.entries {
		result = append(result, m.status(e, false))
	}
	return result
}
func (m *Manager) Apply(id string, req Request) (Status, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed || !validID(id) || req.Revision < 1 {
		return Status{}, errors.New("invalid link or closed runtime")
	}
	old := m.entries[id]
	hash := digest(req)
	if old != nil {
		if req.Revision < old.Request.Revision || req.Revision == old.Request.Revision && hash != old.Hash {
			return Status{}, errors.New("revision conflict")
		}
		if req.Revision == old.Request.Revision && (!req.Enabled || running(old)) {
			return m.status(old, false), nil
		}
	}
	activeCount := 0
	for _, item := range m.entries {
		if item.Request.Enabled {
			activeCount++
		}
	}
	if req.Enabled && (old == nil || !old.Request.Enabled) && activeCount >= 64 {
		return Status{}, errors.New("node active link capacity reached")
	}
	e := &entry{record: record{ID: id, Request: req, Hash: hash, Path: m.configPath(id, req.Revision)}, logs: &logBuffer{}}
	if req.Enabled {
		if err := validateNetwork(req); err != nil {
			return Status{}, err
		}
		data, err := json.Marshal(req.Config)
		if err != nil {
			return Status{}, errors.New("invalid config")
		}
		if err = atomicFile(e.Path, data); err != nil {
			return Status{}, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		cmd := exec.CommandContext(ctx, m.binary, "check", "-c", e.Path)
		cmd.Stdout = io.Discard
		cmd.Stderr = io.Discard
		err = cmd.Run()
		cancel()
		if err != nil {
			return Status{}, errors.New("Paqet configuration check failed; verify node interfaces, addresses and settings")
		}
	}
	if old != nil {
		stop(old)
		if err := firewall(old, false); err != nil {
			_ = m.launch(old)
			return Status{}, err
		}
	}
	rollback := func() {
		stop(e)
		_ = firewall(e, false)
		if old != nil && old.Request.Enabled {
			_ = firewall(old, true)
			_ = m.launch(old)
		}
	}
	if req.Enabled {
		if err := firewall(e, true); err != nil {
			rollback()
			return Status{}, err
		}
		if err := m.launch(e); err != nil {
			rollback()
			return Status{}, err
		}
	}
	if err := m.persist(e); err != nil {
		rollback()
		return Status{}, err
	}
	m.entries[id] = e
	if old != nil && old.Path != e.Path {
		_ = os.Remove(old.Path)
	}
	return m.status(e, false), nil
}
func (m *Manager) Restart(id string, rev int) (Status, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entries[id]
	if e == nil || e.Request.Revision != rev || !e.Request.Enabled {
		return Status{}, errors.New("revision conflict or disabled link")
	}
	stop(e)
	if err := m.launch(e); err != nil {
		return Status{}, err
	}
	e.retries++
	return m.status(e, false), nil
}
func (m *Manager) Close() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return
	}
	m.closed = true
	close(m.done)
	for _, e := range m.entries {
		stop(e)
		_ = firewall(e, false)
	}
}
func (m *Manager) watch() {
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		select {
		case <-m.done:
			return
		case <-tick.C:
			m.mu.Lock()
			for _, e := range m.entries {
				if e.Request.Enabled && !running(e) && time.Now().After(e.next) {
					e.retries++
					err := firewall(e, true)
					if err == nil {
						err = m.launch(e)
					}
					if err != nil {
						e.lastError = "Paqet restart failed; inspect logs and node network"
					} else {
						e.lastError = ""
					}
					delay := time.Second * time.Duration(1<<min(e.retries, 6))
					e.next = time.Now().Add(delay)
				}
			}
			m.mu.Unlock()
		}
	}
}
func (m *Manager) Diagnostic(id string, rev int, action string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.entries[id]
	if e == nil || e.Request.Revision != rev {
		return "", errors.New("revision conflict")
	}
	if action == "logs" {
		return redact(e.logs.String(), e.Request.Config), nil
	}
	if action != "version" && action != "ping" && action != "dump" {
		return "", errors.New("unknown diagnostic")
	}
	role, _ := e.Request.Config["role"].(string)
	if action == "ping" && role != "client" || action == "dump" && role != "server" {
		return "", errors.New("diagnostic does not match role")
	}
	args := []string{action}
	if action != "version" {
		args = append(args, "-c", e.Path)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, m.binary, args...)
	buf := &logBuffer{}
	cmd.Stdout = buf
	cmd.Stderr = buf
	err := cmd.Run()
	if err != nil && ctx.Err() == nil {
		return redact(buf.String(), e.Request.Config), errors.New("diagnostic failed")
	}
	return redact(buf.String(), e.Request.Config), nil
}

type logBuffer struct {
	mu sync.Mutex
	b  []byte
}

func (l *logBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	n := len(p)
	if len(p) > 65536 {
		p = p[len(p)-65536:]
	}
	l.b = append(l.b, p...)
	if len(l.b) > 65536 {
		l.b = l.b[len(l.b)-65536:]
	}
	return n, nil
}
func (l *logBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return string(bytes.Clone(l.b))
}
func redact(s string, config map[string]any) string {
	var walk func(any)
	walk = func(value any) {
		switch v := value.(type) {
		case map[string]any:
			for k, x := range v {
				if k == "key" || k == "password" {
					if secret, ok := x.(string); ok && secret != "" {
						s = string(bytes.ReplaceAll([]byte(s), []byte(secret), []byte("[redacted]")))
					}
				} else {
					walk(x)
				}
			}
		case []any:
			for _, x := range v {
				walk(x)
			}
		}
	}
	walk(config)
	return s
}
