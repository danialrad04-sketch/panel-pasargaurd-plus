// Isolated opt-in acceptance helper. Never part of the node runtime.
package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"time"
)

const target = "127.0.0.1:11190"

func main() {
	if len(os.Args) > 1 && os.Args[1] == "socks" {
		if err := socks(); err != nil {
			panic(err)
		}
		fmt.Println("authenticated SOCKS TCP and UDP passed")
		return
	}
	tcp, err := net.Listen("tcp", target)
	if err != nil {
		panic(err)
	}
	udp, err := net.ListenPacket("udp", target)
	if err != nil {
		panic(err)
	}
	go func() {
		buf := make([]byte, 65535)
		for {
			n, a, err := udp.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = udp.WriteTo(buf[:n], a)
		}
	}()
	for {
		conn, err := tcp.Accept()
		if err != nil {
			return
		}
		go func() { defer conn.Close(); _, _ = io.Copy(conn, conn) }()
	}
}
func auth() (net.Conn, error) {
	c, err := net.DialTimeout("tcp", "127.0.0.1:11091", 3*time.Second)
	if err != nil {
		return nil, err
	}
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = c.Write([]byte{5, 1, 2})
	b := make([]byte, 2)
	if _, err = io.ReadFull(c, b); err != nil || b[1] != 2 {
		return nil, fmt.Errorf("auth method %v %v", b, err)
	}
	user, password := "fixture-user", "fixture-password"
	pkt := append([]byte{1, byte(len(user))}, []byte(user)...)
	pkt = append(pkt, byte(len(password)))
	pkt = append(pkt, []byte(password)...)
	_, _ = c.Write(pkt)
	if _, err = io.ReadFull(c, b); err != nil || b[1] != 0 {
		return nil, fmt.Errorf("authentication %v %v", b, err)
	}
	return c, nil
}
func reply(c net.Conn) (*net.UDPAddr, error) {
	h := make([]byte, 4)
	if _, err := io.ReadFull(c, h); err != nil {
		return nil, err
	}
	if h[1] != 0 {
		return nil, fmt.Errorf("SOCKS reply %v", h)
	}
	n := 4
	if h[3] == 4 {
		n = 16
	} else if h[3] != 1 {
		return nil, fmt.Errorf("SOCKS address type %v", h)
	}
	b := make([]byte, n+2)
	if _, err := io.ReadFull(c, b); err != nil {
		return nil, err
	}
	return &net.UDPAddr{IP: net.IP(b[:n]), Port: int(binary.BigEndian.Uint16(b[n:]))}, nil
}
func socks() error {
	c, err := auth()
	if err != nil {
		return err
	}
	defer c.Close()
	port := []byte{byte(11190 >> 8), byte(11190 & 255)}
	request := append([]byte{5, 1, 0, 1, 127, 0, 0, 1}, port...)
	_, _ = c.Write(request)
	if _, err = reply(c); err != nil {
		return err
	}
	payload := bytes.Repeat([]byte("native-socks-tcp"), 1000)
	_, _ = c.Write(payload)
	out := make([]byte, len(payload))
	if _, err = io.ReadFull(c, out); err != nil || !bytes.Equal(out, payload) {
		return fmt.Errorf("SOCKS TCP failed: %v", err)
	}
	control, err := auth()
	if err != nil {
		return err
	}
	defer control.Close()
	_, _ = control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	address, err := reply(control)
	if err != nil {
		return err
	}
	if address.IP.IsUnspecified() {
		address.IP = net.IPv4(127, 0, 0, 1)
	}
	udp, err := net.DialUDP("udp", nil, address)
	if err != nil {
		return err
	}
	defer udp.Close()
	_ = udp.SetDeadline(time.Now().Add(5 * time.Second))
	for i := 0; i < 8; i++ {
		p := bytes.Repeat([]byte{byte(i)}, 1200)
		packet := append(append([]byte{0, 0, 0, 1, 127, 0, 0, 1}, port...), p...)
		_, _ = udp.Write(packet)
		out := make([]byte, 8192)
		n, err := udp.Read(out)
		if err != nil || n < 10 || !bytes.Equal(out[10:n], p) {
			return fmt.Errorf("SOCKS UDP failed: %v", err)
		}
	}
	return nil
}
