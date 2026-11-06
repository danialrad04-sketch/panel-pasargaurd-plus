//go:build !linux

package firewall

import (
	"errors"
	"net/http"
)

type Manager struct{}

func New(dir string, protected ...int) (*Manager, error) {
	return nil, errors.New("native ingress requires Linux")
}
func (m *Manager) Handler(key string) http.Handler { return http.NotFoundHandler() }

func NewBootstrap(dir string) (*Manager, error) { return New(dir) }
