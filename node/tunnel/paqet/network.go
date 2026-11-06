//go:build linux

package paqet

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

func command(name string, args ...string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	return exec.CommandContext(ctx, name, args...).Output()
}
func object(m map[string]any, key string) map[string]any {
	if v, ok := m[key].(map[string]any); ok {
		return v
	}
	v := map[string]any{}
	m[key] = v
	return v
}
func Discover(peer string) (map[string]any, error) {
	ip := net.ParseIP(peer)
	if ip == nil || ip.IsUnspecified() || ip.IsMulticast() {
		return nil, errors.New("peer must be a unicast numeric IP")
	}
	data, err := command("ip", "-j", "route", "get", peer)
	if err != nil {
		return nil, errors.New("egress route unavailable")
	}
	var routes []map[string]any
	if json.Unmarshal(data, &routes) != nil || len(routes) == 0 {
		return nil, errors.New("egress route unavailable")
	}
	r := routes[0]
	dev, _ := r["dev"].(string)
	local, _ := r["prefsrc"].(string)
	if local == "" {
		local, _ = r["src"].(string)
	}
	gateway, _ := r["gateway"].(string)
	if gateway == "" {
		gateway = peer
	}
	if _, err = net.InterfaceByName(dev); err != nil || net.ParseIP(local) == nil {
		return nil, errors.New("no usable interface/address")
	}
	lookup := func() string {
		data, err := command("ip", "-j", "neigh", "show", "to", gateway, "dev", dev)
		if err != nil {
			return ""
		}
		var rows []map[string]any
		_ = json.Unmarshal(data, &rows)
		for _, row := range rows {
			mac, _ := row["lladdr"].(string)
			if _, err := net.ParseMAC(mac); err == nil {
				return mac
			}
		}
		return ""
	}
	mac := lookup()
	if mac == "" {
		family := "-4"
		if ip.To4() == nil {
			family = "-6"
		}
		_, _ = command("ping", family, "-c", "1", "-W", "1", gateway)
		mac = lookup()
	}
	if mac == "" {
		return nil, errors.New("gateway MAC unresolved; configure a network override")
	}
	return map[string]any{"interface": dev, "local_ip": local, "router_mac": mac}, nil
}
func validateNetwork(req Request) error {
	if req.Config == nil {
		return errors.New("configuration is required")
	}
	peer := net.ParseIP(req.Peer)
	if peer == nil || peer.IsUnspecified() || peer.IsMulticast() {
		return errors.New("peer must be a numeric unicast IP")
	}
	role, _ := req.Config["role"].(string)
	if role != "client" && role != "server" {
		return errors.New("invalid Paqet role")
	}
	network := object(req.Config, "network")
	family := "ipv4"
	if peer.To4() == nil {
		family = "ipv6"
	}
	addr := object(network, family)
	dev, _ := network["interface"].(string)
	address, _ := addr["addr"].(string)
	mac, _ := addr["router_mac"].(string)
	if dev == "" || address == "" || mac == "" {
		found, err := Discover(req.Peer)
		if err != nil {
			return err
		}
		if dev == "" {
			dev = found["interface"].(string)
			network["interface"] = dev
		}
		if address == "" {
			port := "0"
			if role == "server" {
				listen := object(req.Config, "listen")
				str, _ := listen["addr"].(string)
				_, port, _ = net.SplitHostPort(str)
			}
			address = net.JoinHostPort(found["local_ip"].(string), port)
			addr["addr"] = address
		}
		if mac == "" {
			addr["router_mac"] = found["router_mac"]
		}
	}
	iface, err := net.InterfaceByName(dev)
	if err != nil {
		return errors.New("interface does not exist")
	}
	assigned, err := iface.Addrs()
	if err != nil {
		return err
	}
	for _, name := range []string{"ipv4", "ipv6"} {
		row, ok := network[name].(map[string]any)
		if !ok || row["addr"] == nil {
			continue
		}
		a, _ := row["addr"].(string)
		host, port, err := net.SplitHostPort(a)
		if err != nil {
			return errors.New("invalid network endpoint")
		}
		ip := net.ParseIP(host)
		if ip == nil || ip.IsUnspecified() || ip.IsMulticast() {
			return errors.New("invalid local IP")
		}
		found := false
		for _, a := range assigned {
			prefix, _, err := net.ParseCIDR(a.String())
			if err == nil && prefix.Equal(ip) {
				found = true
			}
		}
		if !found {
			return errors.New("local IP is not assigned to selected interface")
		}
		n, err := strconv.Atoi(port)
		if err != nil || n < 0 || n > 65535 {
			return errors.New("invalid local port")
		}
		if role == "server" {
			listen := object(req.Config, "listen")
			str, _ := listen["addr"].(string)
			_, p, _ := net.SplitHostPort(str)
			if p != port {
				return errors.New("server network port differs from listener")
			}
		}
	}
	return nil
}
func firewallRules(e *entry) (string, [][]string, error) {
	req := e.Request
	if !req.ManageFirewall || !req.Enabled {
		return "", nil, nil
	}
	peer := net.ParseIP(req.Peer)
	if peer == nil {
		return "", nil, errors.New("invalid peer")
	}
	tool, family := "iptables", "ipv4"
	if peer.To4() == nil {
		tool, family = "ip6tables", "ipv6"
	}
	network := object(req.Config, "network")
	a, _ := object(network, family)["addr"].(string)
	local, port, err := net.SplitHostPort(a)
	if err != nil {
		return "", nil, err
	}
	role, _ := req.Config["role"].(string)
	inMatch := []string{"-s", req.Peer, "-d", local, "-p", "tcp"}
	outMatch := []string{"-s", local, "-d", req.Peer, "-p", "tcp"}
	if role == "server" {
		inMatch = append(inMatch, "--dport", port)
		outMatch = append(outMatch, "--sport", port)
	} else {
		server, _ := object(req.Config, "server")["addr"].(string)
		_, p, err := net.SplitHostPort(server)
		if err != nil {
			return "", nil, err
		}
		inMatch = append(inMatch, "--sport", p)
		outMatch = append(outMatch, "--dport", p)
	}
	tag := []string{"-m", "comment", "--comment", "pg-plus-paqet-" + e.ID}
	rule := func(table, chain string, match, extra []string, target string) []string {
		a := []string{"-t", table, chain}
		a = append(a, match...)
		a = append(a, extra...)
		a = append(a, tag...)
		return append(a, "-j", target)
	}
	return tool, [][]string{rule("raw", "PREROUTING", inMatch, nil, "NOTRACK"), rule("raw", "OUTPUT", outMatch, nil, "NOTRACK"), rule("mangle", "OUTPUT", outMatch, []string{"--tcp-flags", "RST", "RST"}, "DROP"), rule("filter", "INPUT", inMatch, nil, "ACCEPT"), rule("filter", "OUTPUT", outMatch, nil, "ACCEPT")}, nil
}
func firewall(e *entry, add bool) error {
	tool, rules, err := firewallRules(e)
	if err != nil {
		return err
	}
	for _, rule := range rules {
		prefix := []string{"-w", "2", "-t", rule[1]}
		check := append(append([]string{}, prefix...), "-C", rule[2])
		check = append(check, rule[3:]...)
		_, existsErr := command(tool, check...)
		if add && existsErr != nil {
			args := append(append([]string{}, prefix...), "-I", rule[2], "1")
			args = append(args, rule[3:]...)
			if _, err = command(tool, args...); err != nil {
				return fmt.Errorf("failed to install owned Paqet firewall rule (%s)", strings.Join(rule[:3], " "))
			}
		}
		if !add && existsErr == nil {
			args := append(append([]string{}, prefix...), "-D", rule[2])
			args = append(args, rule[3:]...)
			if _, err = command(tool, args...); err != nil {
				return errors.New("failed to remove owned Paqet firewall rule")
			}
		}
	}
	return nil
}
