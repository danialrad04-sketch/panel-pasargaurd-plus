#!/usr/bin/env bash
# Manager for a PasarGuard Plus install made by install.sh.
# Installed as /usr/local/bin/pasarguard-plus. Run without arguments for help.
set -euo pipefail

APP_DIR="${PGP_DIR:-/opt/pasarguard-plus}"
DATA_DIR="${PGP_DATA:-/var/lib/pasarguard}"
ENV_FILE="$APP_DIR/.env"
REPO_URL="${PGP_REPO:-https://github.com/danialrad04-sketch/panel-pasargaurd-plus.git}"
REF="${PGP_REF:-main}"

die() { echo "error: $*" >&2; exit 1; }
need_root() { [ "$(id -u)" -eq 0 ] || die "run as root (sudo pasarguard-plus $1)"; }
compose() { (cd "$APP_DIR" && docker compose "$@"); }

# set_env KEY VALUE: replace an existing (possibly commented) assignment or append one.
set_env() {
    local key="$1" value="$2" tmp
    tmp="$(mktemp)"
    if grep -Eq "^[#[:space:]]*${key}[[:space:]]*=" "$ENV_FILE"; then
        awk -v k="$key" -v v="$value" '
            !done && $0 ~ "^[#[:space:]]*" k "[[:space:]]*=" { print k "=" v; done=1; next } { print }
        ' "$ENV_FILE" >"$tmp"
    else
        cp "$ENV_FILE" "$tmp"
        printf '%s=%s\n' "$key" "$value" >>"$tmp"
    fi
    cat "$tmp" >"$ENV_FILE"
    rm -f "$tmp"
}

get_env() {
    grep -E "^[[:space:]]*$1[[:space:]]*=" "$ENV_FILE" 2>/dev/null | tail -n1 | sed -E 's/^[^=]*=[[:space:]]*//; s/^"//; s/"$//'
}

public_ip() {
    local ip=""
    for url in https://api.ipify.org https://ifconfig.me https://icanhazip.com; do
        ip="$(curl -4fsS --max-time 5 "$url" 2>/dev/null | tr -d '[:space:]')" || ip=""
        [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] && { echo "$ip"; return; }
    done
    hostname -I 2>/dev/null | awk '{print $1}'
}

panel_url() {
    local scheme=http
    [ -n "$(get_env UVICORN_SSL_CERTFILE)" ] && scheme=https
    echo "$scheme://$(public_ip):$(get_env UVICORN_PORT | tr -d ' "' | sed 's/^$/8000/')"
}

cmd_status() {
    compose ps
    echo
    echo "Panel: $(panel_url)/dashboard/"
    echo "HTTP-only mode: $(get_env UVICORN_ALLOW_INSECURE_HTTP || true)"
}

cmd_logs() { compose logs --tail="${1:-100}" -f pasarguard; }
cmd_restart() { need_root restart; compose restart pasarguard; }

cmd_key() {
    compose exec -T pasarguard pasarguard-cli generate-temp-key
    echo "The key is private, valid for 5 minutes and works once."
}

cmd_update() {
    need_root update
    local before after
    before="$(git -C "$APP_DIR" rev-parse HEAD)"
    git -C "$APP_DIR" fetch --quiet origin "$REF"
    git -C "$APP_DIR" checkout --quiet "$REF"
    git -C "$APP_DIR" merge --ff-only --quiet "origin/$REF" || die "local changes block a fast-forward; commit or stash them in $APP_DIR"
    after="$(git -C "$APP_DIR" rev-parse HEAD)"
    if [ "$before" = "$after" ] && [ "${1:-}" != "--force" ]; then
        echo "Already up to date ($after). Use --force to rebuild anyway."
        return
    fi
    # Database backup first: migrations run automatically on start.
    if [ -f "$DATA_DIR/db.sqlite3" ]; then
        cp -a "$DATA_DIR/db.sqlite3" "$DATA_DIR/db.sqlite3.pre-update-$(date +%Y%m%d%H%M%S)"
        echo "SQLite database copied before updating."
    fi
    compose build
    compose up -d pasarguard
    echo "Updated $before -> $after"
}

cmd_dns() {
    need_root dns
    set_env DNS_ENABLED true
    compose --profile dns up -d
    echo "Native DNS worker and local resolver started (resolver listens on 127.0.0.1:5335 only)."
    echo "Set DNS_LISTEN_ADDRESSES / DNS_PORT in $ENV_FILE to expose the DNS port, then: pasarguard-plus restart"
}

# Gaming DNS gateways on nodes talk to the panel over HTTPS. Without a domain, a self-signed
# certificate for the server IP works because the panel hands its public certificate to the node.
cmd_https_ip() {
    need_root https-ip
    command -v openssl >/dev/null || die "openssl is required"
    local ip="${1:-$(public_ip)}" port dir
    [ -n "$ip" ] || die "could not detect the server IP; pass it: pasarguard-plus https-ip 203.0.113.10"
    port="$(get_env UVICORN_PORT | tr -d ' "')"; port="${port:-8000}"
    dir="$DATA_DIR/certs/ip"
    mkdir -p "$dir"
    openssl req -x509 -newkey rsa:2048 -nodes -days 825 -keyout "$dir/key.pem" -out "$dir/cert.pem" \
        -subj "/CN=$ip" -addext "subjectAltName=IP:$ip" -addext "extendedKeyUsage=serverAuth" 2>/dev/null
    chmod 600 "$dir/key.pem"
    chmod 644 "$dir/cert.pem"
    set_env UVICORN_SSL_CERTFILE "$dir/cert.pem"
    set_env UVICORN_SSL_KEYFILE "$dir/key.pem"
    set_env UVICORN_SSL_CA_TYPE private
    set_env SMARTDNS_PANEL_URL "https://$ip:$port"
    set_env SMARTDNS_PANEL_CA_FILE "$dir/cert.pem"
    compose up -d --force-recreate pasarguard
    echo "Panel now serves HTTPS with a self-signed certificate for $ip."
    echo "Open: https://$ip:$port/dashboard/ (the browser warns once; that is expected for an IP certificate)."
    echo "SMARTDNS_PANEL_URL and its CA file were set, so gaming DNS gateways can reach the panel."
}

# Read-only inspection of THIS server (tunnels, proxy configs, DNS, firewall); secrets are masked on the spot.
cmd_detect() {
    need_root detect
    local out="${1:-/root/tunnel-facts.json}"
    python3 -I "$APP_DIR/app/discovery/collector.py" >"$out"
    chmod 600 "$out"
    echo "Saved $out (read-only scan; nothing was changed). Use the dashboard: Tunnels -> Detect existing tunnels,"
    echo "or POST it to /api/tunnel/discovery/analyze with {\"address\": \"<this server IP>\", \"facts\": <file>}."
}

cmd_env() { ${EDITOR:-nano} "$ENV_FILE"; }

cmd_uninstall() {
    need_root uninstall
    read -r -p "Stop the panel and remove containers? Data in $DATA_DIR is kept. [y/N] " answer
    [ "$answer" = "y" ] || { echo "Cancelled."; return; }
    compose --profile dns down
    echo "Containers removed. Delete $APP_DIR and $DATA_DIR yourself if you also want the data gone."
}

usage() {
    cat <<EOF
pasarguard-plus <command>

  status        containers and panel address
  logs [N]      follow the panel log (last N lines, default 100)
  key           print a one-time owner key (first login / reset)
  update        fetch the latest version of this fork, back up SQLite, rebuild, restart
  restart       restart the panel
  dns           enable native DNS worker + local resolver
  https-ip [IP] switch to HTTPS with a self-signed certificate for the server IP
  detect [FILE] read-only scan of this server for existing tunnels (default /root/tunnel-facts.json)
  env           edit $ENV_FILE
  uninstall     stop containers (data is kept)
EOF
}

[ -f "$ENV_FILE" ] || die "no install found in $APP_DIR (set PGP_DIR if you installed elsewhere)"
case "${1:-}" in
    status) cmd_status ;;
    logs) shift; cmd_logs "$@" ;;
    key) cmd_key ;;
    update) shift; cmd_update "$@" ;;
    restart) cmd_restart ;;
    dns) cmd_dns ;;
    https-ip) shift; cmd_https_ip "$@" ;;
    detect) shift; cmd_detect "$@" ;;
    env) cmd_env ;;
    uninstall) cmd_uninstall ;;
    *) usage ;;
esac
