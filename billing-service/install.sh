#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

service_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
compose=(docker compose --project-name billing-service -f "$service_dir/compose.yml")
env_file="$service_dir/.env"
tls_mode=auto
billing_host=
panel_url=
acme_email=
show_credentials=false

die() { printf 'Error: %s\n' "$*" >&2; exit 1; }
usage() {
    cat <<'USAGE'
Usage: sudo bash billing-service/install.sh [--domain HOST] [--panel-url HTTPS_ORIGIN]
       [--email ADDRESS] [--tls auto|caddy|nginx|external] [--show-credentials]

On first install, the script prompts for the destination card, account shown in
bank SMS, cardholder, and bank. It generates the admin and webhook secrets.
Automatic TLS requires the domain to point to this server and inbound ports
80 and 443. External mode uses an existing HTTPS proxy to 127.0.0.1:8087.
USAGE
}

while (( $# )); do
    case "$1" in
        --domain|--panel-url|--email|--tls)
            (( $# >= 2 )) || die "Missing value for $1"
            case "$1" in
                --domain) billing_host=$2 ;;
                --panel-url) panel_url=$2 ;;
                --email) acme_email=$2 ;;
                --tls) tls_mode=$2 ;;
            esac
            shift 2 ;;
        --show-credentials) show_credentials=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "Unknown option: $1" ;;
    esac
done
[[ $EUID == 0 ]] || die "Run with sudo or as root"
case "$tls_mode" in auto|caddy|nginx|external) ;; *) die "Invalid --tls mode" ;; esac

prompt() {
    local label=$1 default=$2 response
    if ! read -r -p "$label [$default]: " response </dev/tty; then
        die "A terminal is required for first-time configuration"
    fi
    printf '%s' "${response:-$default}"
}

confirm_value() {
    local name=$1 value=$2
    [[ -n $value && $value != *"'"* && $value != *'\'* && $value != *$'\r'* && $value != *$'\n'* ]] ||
        die "Invalid $name"
}

install_packages() {
    command -v apt-get >/dev/null || die "Install the missing packages first: $*"
    update_packages
    DEBIAN_FRONTEND=noninteractive apt-get install -y "$@"
}

update_packages() {
    if [[ ${apt_updated:-false} == false ]]; then
        apt-get update
        apt_updated=true
    fi
}

missing=()
command -v curl >/dev/null || missing+=(curl)
command -v openssl >/dev/null || missing+=(openssl)
command -v ss >/dev/null || missing+=(iproute2)
command -v flock >/dev/null || missing+=(util-linux)
command -v python3 >/dev/null || missing+=(python3)
if (( ${#missing[@]} )); then install_packages "${missing[@]}"; fi

exec 9>/run/lock/pasarguard-billing.lock
flock -n 9 || die "Another PasarGuard billing install is running"

if ! command -v docker >/dev/null; then install_packages docker.io; fi
if ! docker compose version >/dev/null 2>&1; then
    command -v apt-cache >/dev/null || die "Docker Compose v2 is required"
    update_packages
    for package in docker-compose-plugin docker-compose-v2 docker-compose; do
        candidate=$(apt-cache policy "$package" | awk '/Candidate:/ {print $2; exit}')
        if [[ ${candidate:-} =~ ^([0-9]+:)?([2-9]|[1-9][0-9]+)\. ]]; then
            install_packages "$package"
            break
        fi
    done
    docker compose version >/dev/null 2>&1 || die "Enable Docker Compose v2 for this distribution"
fi
compose_help=$(docker compose up --help)
[[ $compose_help == *--wait-timeout* ]] || die "Update Docker Compose v2 to a release with up --wait-timeout"
if ! docker info >/dev/null 2>&1; then
    systemctl enable --now docker
    docker info >/dev/null 2>&1 || die "Docker is not responding"
fi

[[ ! -L $env_file ]] || die "The .env file must not be a symbolic link"
created_env=false
temporary_dir=$(mktemp -d)
temp_env=
nginx_changed=false
nginx_path=
cleanup() {
    local status=$?
    if (( status != 0 )) && [[ $nginx_changed == true ]]; then
        if [[ -f $temporary_dir/previous-nginx.conf ]]; then
            cp -- "$temporary_dir/previous-nginx.conf" "$nginx_path"
        else
            rm -f -- "$nginx_path"
        fi
        nginx -t && systemctl reload nginx || true
    fi
    if [[ -n $temp_env ]]; then rm -f -- "$temp_env"; fi
    rm -rf -- "$temporary_dir"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

normalize_digits() {
    local value=$1 source='۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩' target='01234567890123456789' index
    for ((index=0; index<20; index++)); do
        value=${value//"${source:index:1}"/"${target:index:1}"}
    done
    printf '%s' "$value"
}

validate_origins() {
    [[ $billing_host =~ ^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]([a-z0-9-]*[a-z0-9])?$ ]] ||
        die "Use a public DNS name for --domain"
    [[ $panel_url =~ ^https://[a-zA-Z0-9.-]+(:[0-9]+)?$ ]] ||
        die "Use the HTTPS origin of the existing panel, without a path"
    [[ $panel_url != "https://$billing_host" && $panel_url != "https://$billing_host:443" ]] ||
        die "The VPN panel and billing site need different origins"
}

if [[ -f $env_file ]]; then
    chmod 600 "$env_file"
    # Ask Compose to resolve its own env-file syntax without executing it as shell code.
    configured=$(
        "${compose[@]}" config --format json | python3 -c '
import json, sys
env = json.load(sys.stdin)["services"]["billing"]["environment"]
print(env.get("BILLING_PUBLIC_URL", ""))
print(env.get("PANEL_URL", ""))
print(env.get("BILLING_ACME_EMAIL", ""))
'
    ) || die "Could not read existing billing settings"
    mapfile -t values <<< "$configured"
    saved_origin=${values[0]:-}
    saved_panel=${values[1]:-}
    [[ $saved_origin == https://* ]] || die "Set BILLING_PUBLIC_URL in $env_file"
    [[ -z $billing_host || "https://$billing_host" == "$saved_origin" ]] || die "Update BILLING_PUBLIC_URL in $env_file before changing domains"
    [[ -z $panel_url || $panel_url == "$saved_panel" ]] || die "Update PANEL_URL in $env_file before changing panel addresses"
    billing_host=${saved_origin#https://}
    panel_url=$saved_panel
    acme_email=${acme_email:-${values[2]:-}}
else
    billing_host=${billing_host:-$(prompt "Billing domain" "pay.esfahanmarket.shop")}
    panel_url=${panel_url:-$(prompt "Existing PasarGuard origin" "https://got.esfahanmarket.shop:8443")}
fi
validate_origins

listeners=$(ss -H -ltn '( sport = :80 or sport = :443 )')
nginx_active=false
if command -v nginx >/dev/null && systemctl is-active --quiet nginx; then nginx_active=true; fi
managed_caddy=$(docker ps --filter label=com.docker.compose.project=billing-service \
    --filter label=com.docker.compose.service=caddy --format '{{.ID}}')
if [[ $tls_mode == auto ]]; then
    if [[ -n $managed_caddy || -z $listeners ]]; then
        tls_mode=caddy
    elif [[ $nginx_active == true ]]; then
        tls_mode=nginx
    else
        die "Ports 80/443 already have a listener. Use --tls external with that proxy"
    fi
fi
if [[ $tls_mode == caddy && -z $managed_caddy && -n $listeners ]]; then
    die "Caddy needs ports 80 and 443. Use --tls nginx or --tls external with the existing proxy"
fi
if [[ $tls_mode == nginx && ( $nginx_active == false || -n $managed_caddy ) ]]; then
    die "Nginx mode needs an active host Nginx with access to ports 80 and 443"
fi
[[ $tls_mode != external || -z $managed_caddy ]] ||
    die "Managed Caddy is already active. Stop its service before switching to an external proxy"
if [[ $tls_mode != external ]]; then
    acme_email=${acme_email:-$(prompt "Contact email for TLS" "")}
    [[ $acme_email =~ ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$ ]] || die "A valid --email is required"
    curl -fsS --max-time 10 -o /dev/null \
        "https://acme-v02.api.letsencrypt.org/directory" || die "Cannot reach Let's Encrypt over HTTPS"
fi

if [[ ! -f $env_file ]]; then
    card=$(normalize_digits "$(prompt "Destination card (16 digits)" "")")
    card=${card//[ -]/}
    holder=$(prompt "Cardholder name" "")
    bank=$(prompt "Bank name" "")
    account=$(normalize_digits "$(prompt "Destination account identifier as it appears in bank SMS" "")")
    [[ $card =~ ^[0-9]{16}$ ]] || die "The destination card requires 16 digits"
    [[ $account =~ ^[0-9][0-9.-]+$ ]] || die "Invalid bank SMS account identifier"
    confirm_value "cardholder name" "$holder"
    confirm_value "bank name" "$bank"
    billing_key=$(openssl rand -hex 32)
    admin_password=$(openssl rand -hex 32)
    webhook_secret=$(openssl rand -hex 32)
    temp_env=$(mktemp "$service_dir/.env.XXXXXX")
    {
        printf "PANEL_URL='%s'\nBILLING_PUBLIC_URL='https://%s'\nBILLING_ACME_EMAIL='%s'\n" "$panel_url" "$billing_host" "$acme_email"
        printf "DESTINATION_CARD='%s'\nCARD_HOLDER='%s'\nCARD_BANK='%s'\nSMS_ACCOUNT='%s'\n" "$card" "$holder" "$bank" "$account"
        printf "BILLING_KEY='%s'\nADMIN_PASSWORD='%s'\nSMS_WEBHOOK_SECRET='%s'\n" "$billing_key" "$admin_password" "$webhook_secret"
    } > "$temp_env"
    mv -- "$temp_env" "$env_file"
    temp_env=
    created_env=true
fi

install_nginx_tls() {
    local nginx_dump nginx_config webroot cert_dir hook
    nginx_path="/etc/nginx/conf.d/pasarguard-billing-$billing_host.conf"
    nginx_dump=$(nginx -T 2>&1) || die "The existing Nginx configuration does not validate"
    [[ $nginx_dump == *'include /etc/nginx/conf.d/*.conf;'* ]] ||
        die "Nginx must include /etc/nginx/conf.d/*.conf. Otherwise use --tls external"
    [[ ! -L $nginx_path ]] || die "Refusing to replace a symbolic link: $nginx_path"
    if [[ -f $nginx_path ]]; then
        grep -Fxq '# Managed by the PasarGuard billing installer' "$nginx_path" ||
            die "An existing config already owns $nginx_path"
        cp -- "$nginx_path" "$temporary_dir/previous-nginx.conf"
    elif [[ $nginx_dump == *"$billing_host"* ]]; then
        die "The payment domain is already present in Nginx. Use --tls external for that configuration"
    fi
    command -v certbot >/dev/null || install_packages certbot
    webroot=/var/lib/pasarguard-billing/acme
    cert_dir="/etc/letsencrypt/live/$billing_host"
    install -d -m 755 "$webroot/.well-known/acme-challenge"
    nginx_config="$temporary_dir/nginx.conf"
    cat > "$nginx_config" <<CONFIG
# Managed by the PasarGuard billing installer
server {
    listen 80;
    server_name $billing_host;
    location ^~ /.well-known/acme-challenge/ { root $webroot; }
    location / { return 308 https://$billing_host\$request_uri; }
}
CONFIG
    nginx_changed=true
    if [[ ! -s $cert_dir/fullchain.pem || ! -s $cert_dir/privkey.pem ]]; then
        install -m 644 "$nginx_config" "$nginx_path"
        nginx -t && systemctl reload nginx || die "Nginx could not serve the ACME HTTP challenge"
    fi
    certbot certonly --non-interactive --agree-tos --email "$acme_email" --webroot \
        -w "$webroot" --cert-name "$billing_host" -d "$billing_host" --keep-until-expiring
    cat >> "$nginx_config" <<CONFIG
server {
    listen 443 ssl;
    server_name $billing_host;
    ssl_certificate $cert_dir/fullchain.pem;
    ssl_certificate_key $cert_dir/privkey.pem;
    add_header Strict-Transport-Security "max-age=31536000" always;
    client_max_body_size 16k;
    location / {
        proxy_pass http://127.0.0.1:8087;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }
}
CONFIG
    install -m 644 "$nginx_config" "$nginx_path"
    nginx -t && systemctl reload nginx || die "The billing HTTPS configuration did not load"
    install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
    hook="/etc/letsencrypt/renewal-hooks/deploy/pasarguard-billing-$billing_host"
    cat > "$temporary_dir/renew" <<HOOK
#!/bin/sh
case " \$RENEWED_DOMAINS " in
  *" $billing_host "*) nginx -t && systemctl reload nginx ;;
esac
HOOK
    install -m 755 "$temporary_dir/renew" "$hook"
    if systemctl cat certbot.timer >/dev/null 2>&1; then
        systemctl enable --now certbot.timer
    elif ! systemctl list-timers --all --no-legend | grep -E 'certbot|letsencrypt' >/dev/null; then
        die "Enable your Certbot renewal timer before proceeding"
    fi
}

if [[ $tls_mode == caddy ]]; then
    export BILLING_HOST="$billing_host" ACME_EMAIL="$acme_email"
    "${compose[@]}" -f "$service_dir/compose.tls.yml" up -d --build --wait --wait-timeout 90
else
    "${compose[@]}" up -d --build --wait --wait-timeout 90
    if [[ $tls_mode == nginx ]]; then install_nginx_tls; fi
fi

[[ $(curl -fsS --max-time 5 http://127.0.0.1:8087/healthz) == '{"status":"ok"}' ]] ||
    die "The billing backend did not pass its local health check"
if [[ $tls_mode != external ]]; then
    https_ready=false
    for ((attempt=0; attempt<36; attempt++)); do
        if [[ $(curl -fsS --noproxy '*' --resolve "$billing_host:443:127.0.0.1" \
            --max-time 5 "https://$billing_host/healthz" 2>/dev/null) == '{"status":"ok"}' ]]; then
            https_ready=true
            break
        fi
        sleep 3
    done
    [[ $https_ready == true ]] ||
        die "HTTPS did not become ready. Check public DNS, inbound ports 80/443, and proxy logs"
else
    printf 'External HTTPS proxy target: http://127.0.0.1:8087\n'
fi
nginx_changed=false
printf '\nCheckout: https://%s/\nAdmin (user admin): https://%s/admin\n' "$billing_host" "$billing_host"
printf 'Bank SMS webhook: https://%s/web/shop/payments/card-deposit/webhook\n' "$billing_host"
printf 'Settings stored in %s (mode 600)\n' "$env_file"
if [[ $show_credentials == true ]]; then
    [[ -t 1 ]] || die "Show credentials only on an interactive terminal"
    grep -E '^(ADMIN_PASSWORD|SMS_WEBHOOK_SECRET)=' "$env_file"
elif [[ $created_env == true ]]; then
    printf 'Use --show-credentials to display the generated login and SMS webhook secrets in a terminal.\n'
fi
