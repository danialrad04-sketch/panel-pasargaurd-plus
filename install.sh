#!/usr/bin/env bash
# PasarGuard Plus one-line installer.
#
#   curl -fsSL https://raw.githubusercontent.com/danialrad04-sketch/panel-pasargaurd-plus/main/install.sh | sudo bash
#
# Options (environment variables):
#   PGP_MODE=http|https-ip   http (default): plain HTTP on the server IP, no domain needed.
#                            https-ip: self-signed certificate for the IP (needed for gaming DNS gateways).
#   PGP_PORT=8000            panel port
#   PGP_DIR=/opt/pasarguard-plus
#   PGP_REF=main             branch or tag to install
#   PGP_DNS=1                also start the native DNS worker and local resolver
#   PGP_BUILD=1              always build the image locally instead of pulling it first
set -euo pipefail

REPO_URL="${PGP_REPO:-https://github.com/danialrad04-sketch/panel-pasargaurd-plus.git}"
IMAGE="${PGP_IMAGE:-ghcr.io/danialrad04-sketch/panel-pasargaurd-plus:latest}"
APP_DIR="${PGP_DIR:-/opt/pasarguard-plus}"
DATA_DIR="${PGP_DATA:-/var/lib/pasarguard}"
REF="${PGP_REF:-main}"
MODE="${PGP_MODE:-http}"
PORT="${PGP_PORT:-8000}"

say() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root: curl -fsSL <url> | sudo bash"
[ "$(uname -s)" = Linux ] || die "Linux only"
case "$MODE" in http | https-ip) ;; *) die "PGP_MODE must be http or https-ip" ;; esac
[[ "$PORT" =~ ^[0-9]+$ ]] && [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "PGP_PORT must be 1-65535"
case "$(uname -m)" in x86_64 | amd64 | aarch64 | arm64) ;; *) die "unsupported CPU architecture: $(uname -m)" ;; esac

mem_mb="$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)"
if [ "${PGP_BUILD:-0}" = 1 ] && [ "$mem_mb" -lt 2000 ]; then
    echo "warning: ${mem_mb} MB RAM; a local image build needs about 2 GB (add swap if it fails)."
fi

pkg_install() {
    if command -v apt-get >/dev/null; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$@"
    elif command -v dnf >/dev/null; then dnf install -y -q "$@"
    elif command -v yum >/dev/null; then yum install -y -q "$@"
    else die "install these packages yourself and re-run: $*"; fi
}

say "Checking prerequisites"
for tool in curl git openssl; do
    command -v "$tool" >/dev/null || pkg_install "$tool"
done
if ! command -v docker >/dev/null; then
    say "Installing Docker"
    curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker >/dev/null 2>&1 || true
docker compose version >/dev/null 2>&1 || die "Docker Compose v2 is missing (install the docker-compose-plugin package)"

say "Fetching PasarGuard Plus ($REF)"
if [ -d "$APP_DIR/.git" ]; then
    git -C "$APP_DIR" fetch --quiet origin "$REF"
    git -C "$APP_DIR" checkout --quiet "$REF"
    git -C "$APP_DIR" merge --ff-only --quiet "origin/$REF" || die "local changes in $APP_DIR block the update"
else
    [ ! -e "$APP_DIR" ] || [ -z "$(ls -A "$APP_DIR" 2>/dev/null)" ] || die "$APP_DIR exists and is not an install"
    git clone --quiet --branch "$REF" "$REPO_URL" "$APP_DIR"
fi

mkdir -p "$DATA_DIR"
install -m 755 "$APP_DIR/deploy/pasarguard-plus.sh" /usr/local/bin/pasarguard-plus
export PGP_DIR="$APP_DIR" PGP_DATA="$DATA_DIR" PGP_REF="$REF"

if [ ! -f "$APP_DIR/.env" ]; then
    cp "$APP_DIR/.env.example" "$APP_DIR/.env"
    chmod 600 "$APP_DIR/.env"
fi
# set_env KEY VALUE: replace an existing (possibly commented) assignment or append one,
# so a re-run keeps every other setting the owner changed.
set_env() {
    local key="$1" value="$2" tmp
    tmp="$(mktemp)"
    if grep -Eq "^[#[:space:]]*${key}[[:space:]]*=" "$APP_DIR/.env"; then
        awk -v k="$key" -v v="$value" '
            !done && $0 ~ "^[#[:space:]]*" k "[[:space:]]*=" { print k "=" v; done=1; next } { print }
        ' "$APP_DIR/.env" >"$tmp"
    else
        cp "$APP_DIR/.env" "$tmp"
        printf '%s=%s\n' "$key" "$value" >>"$tmp"
    fi
    cat "$tmp" >"$APP_DIR/.env"
    rm -f "$tmp"
}
set_env UVICORN_HOST "0.0.0.0"
set_env UVICORN_PORT "$PORT"
if [ "$MODE" = http ]; then
    set_env UVICORN_ALLOW_INSECURE_HTTP true
fi
[ "${PGP_DNS:-0}" = 1 ] && set_env DNS_ENABLED true

say "Preparing the image"
docker tag pasarguard-plus:local pasarguard-plus:previous 2>/dev/null || true
if [ "${PGP_BUILD:-0}" != 1 ] && docker pull "$IMAGE" >/dev/null 2>&1; then
    docker tag "$IMAGE" pasarguard-plus:local
    echo "Pulled prebuilt image."
else
    echo "Building the image from source (first build takes several minutes: dashboard, node cores, Python)."
    (cd "$APP_DIR" && docker compose build)
fi

say "Starting the panel"
(cd "$APP_DIR" && docker compose up -d pasarguard)
[ "${PGP_DNS:-0}" = 1 ] && (cd "$APP_DIR" && docker compose --profile dns up -d)

healthy=0
for _ in $(seq 1 60); do
    state="$(docker inspect -f '{{.State.Health.Status}}' "$(cd "$APP_DIR" && docker compose ps -q pasarguard)" 2>/dev/null || true)"
    if [ "$state" = healthy ]; then healthy=1; break; fi
    sleep 3
done
[ "$healthy" = 1 ] || { echo "The panel did not become healthy in time. Logs: pasarguard-plus logs"; exit 1; }

if [ "$MODE" = https-ip ]; then
    say "Enabling HTTPS with a self-signed IP certificate"
    pasarguard-plus https-ip
fi

ip="$(curl -4fsS --max-time 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"
scheme=http; [ "$MODE" = https-ip ] && scheme=https
key="$(cd "$APP_DIR" && docker compose exec -T pasarguard pasarguard-cli generate-temp-key 2>/dev/null | tail -n1 || true)"

cat <<EOF

================================================================
 PasarGuard Plus is running.

 Panel:     $scheme://$ip:$PORT/dashboard/
 Owner key: ${key:-run: pasarguard-plus key}
            (private, valid 5 minutes, one use; enter it on the setup page
             together with your username and a strong password)

 Manage:    pasarguard-plus status | logs | update | key | dns | https-ip
 Open TCP port $PORT in your firewall / cloud security group.
EOF
if [ "$MODE" = http ]; then
    cat <<'EOF'

 Warning: HTTP mode is not encrypted. Passwords, tokens and subscription
 links can be read on the network. Fine for first setup; for real use run
 "pasarguard-plus https-ip" (self-signed) or put a domain + TLS in front.
 Gaming DNS gateways need HTTPS: run "pasarguard-plus https-ip" before
 creating a gaming DNS profile.
EOF
fi
echo "================================================================"
