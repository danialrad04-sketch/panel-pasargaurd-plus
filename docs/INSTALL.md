# Install and prerequisites

## One-line install

```sh
curl -fsSL https://raw.githubusercontent.com/danialrad04-sketch/panel-pasargaurd-plus/main/install.sh | sudo bash
```

The script: installs `curl`, `git`, `openssl` and Docker if missing → clones this repo to `/opt/pasarguard-plus` → writes `.env` (keeping anything you changed on re-runs) → pulls the prebuilt image, or builds it if the pull fails → starts the panel → waits for the health check → prints the panel address and a one-time owner key → installs the `pasarguard-plus` command.

Read the script before running it: [install.sh](../install.sh).

### Modes

| Mode | Command | Use it when |
|---|---|---|
| HTTP on the IP (default) | `… \| sudo bash` | First setup, private network, no domain. Not encrypted. |
| HTTPS with a self-signed IP certificate | `… \| sudo PGP_MODE=https-ip bash` or later `pasarguard-plus https-ip` | No domain but you want encryption, or you want the gaming DNS gateway. The browser shows one certificate warning. |
| Your own domain + TLS | Put Caddy/nginx in front (set `UVICORN_HOST=127.0.0.1`, `UVICORN_PROXY_HEADERS=true`) or set `UVICORN_SSL_CERTFILE` / `UVICORN_SSL_KEYFILE` | Production. Then set `SMARTDNS_PANEL_URL=https://your.domain`. |

HTTP mode is `UVICORN_ALLOW_INSECURE_HTTP=true` in `.env`. Without it (and without TLS settings) the panel listens on localhost only.

### First login

1. Open `http://SERVER-IP:8000/dashboard/` (open the TCP port in the firewall / cloud security group).
2. Enter the owner key printed by the installer (`pasarguard-plus key` prints a new one; valid 5 minutes, one use), choose a username and a strong password.

## Manual install

```sh
git clone https://github.com/danialrad04-sketch/panel-pasargaurd-plus.git /opt/pasarguard-plus
cd /opt/pasarguard-plus
cp .env.example .env
# edit .env: UVICORN_ALLOW_INSECURE_HTTP=true for HTTP-by-IP, or the TLS settings
docker compose build
docker compose up -d pasarguard
docker compose exec pasarguard pasarguard-cli generate-temp-key
```

Migrations run automatically at start (`start.sh`). The database lives in `/var/lib/pasarguard`.

## Prerequisites by feature

| Feature | Required |
|---|---|
| Panel | Docker + Compose v2, free TCP port (default 8000) |
| Image build on the server | ~2 GB RAM, several minutes, internet access to the Go/Node/Python registries |
| Node install | SSH access to the node, fingerprint check, Linux x86_64/arm64 ([guide](NATIVE-AUTOMATIC-INSTALL.fa.md)) |
| Paqet tunnels | Raw-socket capability, correct NIC/IP/MAC on both nodes ([guide](NATIVE-PAQET.fa.md)) |
| User DNS links | `DNS_ENABLED=true` (`pasarguard-plus dns`) |
| Gaming DNS profile | Connected node with the SmartDNS adapter (installed by the automatic node install), HTTPS panel origin in `SMARTDNS_PANEL_URL` (+ `SMARTDNS_PANEL_CA_FILE` for a self-signed certificate; `pasarguard-plus https-ip` sets both), an egress node or tunnel outside Iran |
| DNS on port 53 | Port 53 free (disable the `systemd-resolved` stub listener), `DNS_LISTEN_ADDRESSES`, `DNS_PORT`; ports below 1024 need bind permission |
| DoT | `DNS_DOT_PORT`, `DNS_TLS_CERTFILE`, `DNS_TLS_KEYFILE` |

## Updating

```sh
pasarguard-plus update
```

Fetches `main`, copies the SQLite file next to the original (`db.sqlite3.pre-update-<time>`), rebuilds and restarts. If the build fails, the previous container keeps running.

## Uninstall

`pasarguard-plus uninstall` stops the containers and keeps `/var/lib/pasarguard`.
