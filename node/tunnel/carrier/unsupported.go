//go:build !linux

package carrier

import (
	"errors"
	"github.com/pasarguard/node/tunnel/paqet"
)

func New(name, dir, cert, key string) (*paqet.Manager, error) {
	return nil, errors.New("native carrier supervision requires Linux")
}

func NewInDirectory(name, dir, cert, key, binDir string) (*paqet.Manager, error) {
	return New(name, dir, cert, key)
}
