//go:build linux

package paqet

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"strconv"
	"strings"
)

func (m *Manager) Handler(key string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		if key == "" || subtle.ConstantTimeCompare([]byte(r.Header.Get("x-api-key")), []byte(key)) != 1 {
			http.Error(w, "unauthorized", 401)
			return
		}
		respond := func(value any) { _ = json.NewEncoder(w).Encode(value) }
		switch r.URL.Path {
		case "/v1/paqet/capabilities":
			if r.Method != "GET" {
				w.WriteHeader(405)
				return
			}
			_, err := os.Stat(m.binary)
			respond(map[string]any{"contract": ContractVersion, "engine": EngineVersion, "installed": err == nil, "source_commit": "4b81ce0ab56b7cc9aec7501c87c4804676cdb1b4", "networks": []string{"tcp", "udp"}})
			return
		case "/v1/paqet/network":
			if r.Method != "GET" {
				w.WriteHeader(405)
				return
			}
			value, err := Discover(r.URL.Query().Get("peer"))
			if err != nil {
				http.Error(w, err.Error(), 422)
				return
			}
			respond(value)
			return
		case "/v1/paqet/links":
			if r.Method != "GET" {
				w.WriteHeader(405)
				return
			}
			respond(m.List())
			return
		}
		path := strings.TrimPrefix(r.URL.Path, "/v1/paqet/links/")
		parts := strings.Split(path, "/")
		if len(parts) > 2 || !validID(parts[0]) {
			http.NotFound(w, r)
			return
		}
		id := parts[0]
		var value any
		var err error
		if len(parts) == 1 && r.Method == "GET" {
			value, err = m.Get(id, true)
		} else if len(parts) == 1 && r.Method == "PUT" {
			var req Request
			decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 262144))
			decoder.DisallowUnknownFields()
			if decoder.Decode(&req) != nil {
				http.Error(w, "invalid request", 422)
				return
			}
			value, err = m.Apply(id, req)
		} else if len(parts) == 2 && r.Method == "POST" {
			rev, e := strconv.Atoi(r.URL.Query().Get("revision"))
			if e != nil {
				http.Error(w, "revision required", 422)
				return
			}
			if parts[1] == "restart" {
				value, err = m.Restart(id, rev)
			} else {
				var output string
				output, err = m.Diagnostic(id, rev, parts[1])
				value = map[string]any{"output": output}
			}
		} else {
			w.WriteHeader(405)
			return
		}
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				http.Error(w, "link not found", 404)
			} else {
				http.Error(w, err.Error(), 409)
			}
			return
		}
		respond(value)
	})
}
