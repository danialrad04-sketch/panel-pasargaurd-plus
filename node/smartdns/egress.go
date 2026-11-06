package smartdns

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"strconv"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

// Egress is compiled only by the panel from a native tunnel; never accepted
// as a public route field or returned by the node status/config APIs.
type Egress struct {
	Address  string `json:"address"`
	Port     int    `json:"port"`
	Password string `json:"password"`
}

func startEgress(value *Egress) (*core.Instance, error) {
	if value == nil {
		return nil, nil
	}
	if value.Address == "" || len(value.Address) > 253 || value.Port < 1 || value.Port > 65535 || len(value.Password) > 256 {
		return nil, errors.New("invalid encrypted egress")
	}
	config := map[string]any{"log": map[string]any{"loglevel": "none"}, "outbounds": []any{map[string]any{"protocol": "shadowsocks", "settings": map[string]any{"servers": []any{map[string]any{"address": value.Address, "port": value.Port, "method": "2022-blake3-aes-128-gcm", "password": value.Password}}}}}}
	data, _ := json.Marshal(config)
	return core.StartInstance("json", data)
}
func (r Route) dialTCP(ctx context.Context, target net.IP, port int) (net.Conn, error) {
	if r.proxy != nil {
		return core.Dial(ctx, r.proxy, xnet.TCPDestination(xnet.IPAddress(target), xnet.Port(port)))
	}
	return (&net.Dialer{Timeout: 3 * time.Second}).DialContext(ctx, "tcp", net.JoinHostPort(target.String(), strconv.Itoa(port)))
}

type datagram interface {
	Read([]byte) (int, error)
	Write([]byte) (int, error)
	SetReadDeadline(time.Time) error
	Close() error
}
type encryptedDatagram struct {
	net.PacketConn
	target *net.UDPAddr
	cancel context.CancelFunc
}

func (c *encryptedDatagram) Read(b []byte) (int, error) {
	n, _, e := c.PacketConn.ReadFrom(b)
	return n, e
}
func (c *encryptedDatagram) Write(b []byte) (int, error) { return c.PacketConn.WriteTo(b, c.target) }
func (c *encryptedDatagram) Close() error                { c.cancel(); return c.PacketConn.Close() }
func (r Route) dialUDP(target net.IP, port int) (datagram, error) {
	if r.proxy == nil {
		return net.DialUDP("udp", nil, &net.UDPAddr{IP: target, Port: port})
	}
	ctx, cancel := context.WithCancel(context.Background())
	connection, e := core.DialUDP(ctx, r.proxy)
	if e != nil {
		cancel()
		return nil, e
	}
	return &encryptedDatagram{connection, &net.UDPAddr{IP: target, Port: port}, cancel}, nil
}
func closeEgress(c *Config) {
	seen := map[*core.Instance]bool{}
	for _, r := range c.Routes {
		if r.proxy != nil && !seen[r.proxy] {
			seen[r.proxy] = true
			r.proxy.Close()
		}
	}
}
