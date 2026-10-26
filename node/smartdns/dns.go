package smartdns

import (
	"encoding/base64"
	"encoding/binary"
	dns "github.com/miekg/dns"
	"io"
	"net"
	"strconv"
	"time"
)

type DNSListener struct {
	Enabled     bool   `json:"enabled"`
	BindAddress string `json:"bind_address"`
	Port        int    `json:"port"`
}

func (m *Manager) dnsAnswer(c *Config, wire []byte, ip string, udp bool) []byte {
	query := new(dns.Msg)
	if len(wire) > 4096 || query.Unpack(wire) != nil {
		return nil
	}
	var result struct {
		Wire string `json:"wire"`
	}
	p := &pool{m: m, c: c}
	if p.api("resolve", map[string]any{"revision": c.Revision, "source_ip": ip, "udp": udp, "wire": base64.StdEncoding.EncodeToString(wire)}, &result) {
		decoded, e := base64.StdEncoding.DecodeString(result.Wire)
		if e == nil && len(decoded) >= 12 && len(decoded) <= 65535 {
			return decoded
		}
	}
	reply := new(dns.Msg)
	reply.SetRcode(query, dns.RcodeServerFailure)
	data, _ := reply.Pack()
	return data
}
func (m *Manager) serveDNSTCP(listener net.Listener) {
	for {
		c, e := listener.Accept()
		if e != nil {
			return
		}
		select {
		case m.slots <- struct{}{}:
			go func() {
				defer func() { <-m.slots }()
				defer c.Close()
				ip, _, _ := net.SplitHostPort(c.RemoteAddr().String())
				for i := 0; i < 32; i++ {
					c.SetDeadline(time.Now().Add(10 * time.Second))
					var size uint16
					if binary.Read(c, binary.BigEndian, &size) != nil || size < 12 || size > 4096 {
						return
					}
					wire := make([]byte, size)
					if _, e := io.ReadFull(c, wire); e != nil {
						return
					}
					cfg := m.current.Load()
					if cfg.DNS == nil || !cfg.DNS.Enabled {
						return
					}
					answer := m.dnsAnswer(cfg, wire, ip, false)
					if len(answer) == 0 {
						return
					}
					if binary.Write(c, binary.BigEndian, uint16(len(answer))) != nil {
						return
					}
					if _, e = c.Write(answer); e != nil {
						return
					}
				}
			}()
		default:
			c.Close()
		}
	}
}
func (m *Manager) serveDNSUDP(listener *net.UDPConn) {
	buffer := make([]byte, 4097)
	for {
		n, address, e := listener.ReadFromUDP(buffer)
		if e != nil {
			return
		}
		if n < 12 || n > 4096 {
			continue
		}
		select {
		case m.slots <- struct{}{}:
			wire := append([]byte(nil), buffer[:n]...)
			go func() {
				defer func() { <-m.slots }()
				cfg := m.current.Load()
				if cfg.DNS == nil || !cfg.DNS.Enabled {
					return
				}
				answer := m.dnsAnswer(cfg, wire, address.IP.String(), true)
				if len(answer) > 0 && len(answer) <= 1232 {
					listener.SetWriteDeadline(time.Now().Add(time.Second))
					listener.WriteToUDP(answer, address)
				}
			}()
		default:
		}
	}
}
func dnsAddress(value *DNSListener) (string, error) {
	if value == nil || !value.Enabled {
		return "", nil
	}
	if net.ParseIP(value.BindAddress) == nil || value.Port < 1 || value.Port > 65535 {
		return "", strconv.ErrSyntax
	}
	return net.JoinHostPort(value.BindAddress, strconv.Itoa(value.Port)), nil
}
