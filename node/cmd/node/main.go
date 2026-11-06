package main

import (
	"context"
	"crypto/tls"
	"github.com/pasarguard/node/smartdns"
	"github.com/pasarguard/node/tunnel/carrier"
	"github.com/pasarguard/node/tunnel/firewall"
	"github.com/pasarguard/node/tunnel/health"
	"github.com/pasarguard/node/tunnel/paqet"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"

	"github.com/pasarguard/node/config"
	"github.com/pasarguard/node/controller"
	"github.com/pasarguard/node/controller/rest"
	"github.com/pasarguard/node/controller/rpc"
	"github.com/pasarguard/node/pkg/tlsutil"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}

	addr := net.JoinHostPort(cfg.NodeHost, strconv.Itoa(cfg.ServicePort))

	tlsConfig, err := tlsutil.LoadTLSCredentials(cfg.SslCertFile, cfg.SslKeyFile)
	if err != nil {
		log.Fatal(err)
	}

	log.Printf("Starting Node: v%s", controller.NodeVersion)

	var shutdownFunc func(ctx context.Context) error
	var service controller.Service

	if cfg.ServiceProtocol == "rest" {
		shutdownFunc, service, err = rest.StartHttpListener(tlsConfig, addr, cfg)
	} else {
		shutdownFunc, service, err = rpc.StartGRPCListener(tlsConfig, addr, cfg)
	}
	if err != nil {
		log.Fatal(err)
	}

	defer service.Disconnect()
	if cfg.PaqetEnabled {
		manager, err := paqet.New(cfg.PaqetBinary, cfg.PaqetStateDir)
		if err != nil {
			log.Fatal(err)
		}
		defer manager.Close()
		address := net.JoinHostPort(cfg.NodeHost, strconv.Itoa(cfg.PaqetPort))
		listener, err := tls.Listen("tcp", address, tlsConfig)
		if err != nil {
			log.Fatal(err)
		}
		healthManager, err := health.New(cfg.XrayExecutablePath, cfg.HealthPort)
		if err != nil {
			log.Printf("native carrier health unavailable: echo port is not available")
		} else {
			defer healthManager.Close()
		}
		gateway, gatewayError := smartdns.New(filepath.Join(filepath.Dir(cfg.PaqetStateDir), "smartdns"), cfg.ApiKey.String())
		if gatewayError != nil {
			log.Printf("native SmartDNS unavailable: inspect its private state and listener conflicts")
		} else {
			defer gateway.Close()
		}
		mux := http.NewServeMux()
		if _, err := firewall.NewBootstrap(filepath.Join(filepath.Dir(cfg.PaqetStateDir), "bootstrap")); err != nil {
			log.Printf("native management ingress could not be restored")
		}
		ingress, ingressError := firewall.New(filepath.Join(filepath.Dir(cfg.PaqetStateDir), "ingress"), cfg.SSHPort, cfg.ServicePort, cfg.PaqetPort, cfg.HealthPort)
		if ingressError != nil {
			log.Printf("native automatic ingress is unavailable")
		} else {
			mux.Handle("/v1/network/", ingress.Handler(cfg.ApiKey.String()))
		}
		mux.Handle("/v1/paqet/", manager.Handler(cfg.ApiKey.String()))
		for _, name := range []string{"backhaul", "gost", "frp"} {
			engine, engineError := carrier.NewInDirectory(name, filepath.Join(filepath.Dir(cfg.PaqetStateDir), name), cfg.SslCertFile, cfg.SslKeyFile, cfg.EngineBinDir)
			if engineError != nil {
				log.Fatal("native carrier journal could not initialize: ", name)
			}
			defer engine.Close()
			mux.Handle("/v1/"+name+"/", engine.Handler(cfg.ApiKey.String()))
		}
		if gateway != nil {
			mux.Handle("/v1/smartdns/", gateway.Handler(cfg.ApiKey.String()))
		}
		if healthManager != nil {
			mux.Handle("/v1/health/", healthManager.Handler(cfg.ApiKey.String()))
		}
		server := &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second, MaxHeaderBytes: 16384}
		defer server.Close()
		go func() {
			if err := server.Serve(listener); err != nil && err != http.ErrServerClosed {
				log.Printf("native Paqet API stopped: %v", err)
			}
		}()
	}

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	<-stopChan
	log.Println("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err = shutdownFunc(ctx); err != nil {
		log.Printf("Server shutdown error: %v", err)
	}

	log.Println("Server gracefully stopped")
}
