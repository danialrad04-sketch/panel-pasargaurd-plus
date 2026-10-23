//go:build !linux

// The raw-PCAP engine integration is Linux-only. Keep existing node builds valid.
package paqet

import (
	"errors"
	"net/http"
)

type Manager struct{}

func New(binary, dir string) (*Manager, error)     { return nil, errors.New("native Paqet requires Linux") }
func (m *Manager) Close()                          {}
func (m *Manager) Handler(key string) http.Handler { return http.NotFoundHandler() }
