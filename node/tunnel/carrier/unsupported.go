//go:build !linux

package carrier

import (
	"errors"
	"github.com/pasarguard/node/tunnel/paqet"
)

func New(name, dir, cert, key string) (*paqet.Manager, error) {
	return nil, errors.New("native carrier supervision requires Linux")
}
