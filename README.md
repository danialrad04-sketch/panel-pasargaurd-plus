<p align="center">
  <img width="120" height="120" src="dashboard/public/statics/favicon/favicon.svg" alt="PasarGuard Plus">
</p>

<h1 align="center">PasarGuard Plus</h1>

<p align="center">
  A fork of <a href="https://github.com/PasarGuard/panel">PasarGuard</a> for Iran-facing deployments:
  native node tunnels, DNS for gaming and sanctioned services, and a rebuilt dashboard and subscription page.
  <br>
  <a href="README-fa.md">فارسی</a>
</p>

---

## Install

On a fresh Ubuntu/Debian/CentOS server (as root), one line:

```sh
curl -fsSL https://raw.githubusercontent.com/danialrad04-sketch/panel-pasargaurd-plus/main/install.sh | sudo bash
```

It installs Docker if missing, pulls (or builds) the image, starts the panel and prints the panel address and a one-time owner key. **No domain is needed**: by default the panel runs over plain HTTP on the server IP.

Then open `http://SERVER-IP:8000/dashboard/`, enter the owner key with a username and a strong password, and open TCP port 8000 in your firewall.

> HTTP is not encrypted. Use it to get started or on a private network. For real use run `pasarguard-plus https-ip` (self-signed certificate for the IP, no domain) or put a domain with TLS in front. Gaming DNS gateways need HTTPS, so run `pasarguard-plus https-ip` before creating a gaming DNS profile.

Install options (environment variables in front of `bash`):

| Variable | Default | Meaning |
|---|---|---|
| `PGP_MODE` | `http` | `http` = plain HTTP on the IP; `https-ip` = self-signed IP certificate |
| `PGP_PORT` | `8000` | Panel port |
| `PGP_DNS` | off | `1` also starts the native DNS worker and local resolver |
| `PGP_BUILD` | off | `1` builds the image on the server instead of pulling the prebuilt one |
| `PGP_DIR` | `/opt/pasarguard-plus` | Install directory |
| `PGP_REF` | `main` | Branch or tag |

Example: `curl -fsSL …/install.sh | sudo PGP_MODE=https-ip PGP_PORT=8443 bash`

Building the image on the server compiles the dashboard and the node cores; plan for about 2 GB of RAM and several minutes. Re-running the installer upgrades an existing install and keeps your `.env`.

### Manage

```sh
pasarguard-plus status      # containers and panel address
pasarguard-plus logs        # follow the log
pasarguard-plus key         # new one-time owner key
pasarguard-plus update      # pull the latest version, back up SQLite, rebuild, restart
pasarguard-plus dns         # enable the native DNS worker + local resolver
pasarguard-plus https-ip    # switch to a self-signed HTTPS certificate for the IP
pasarguard-plus env         # edit .env
```

Manual install and the full prerequisites list: [docs/INSTALL.md](docs/INSTALL.md) (Persian: [docs/TESTING-START.fa.md](docs/TESTING-START.fa.md)).

---

## What this fork adds

| Area | What you get |
|---|---|
| **Existing-tunnel detection** | Read-only scan of a server finds a running Paqet tunnel, V2Ray/Xray configs that ride it, DNS paths and firewall rules, then reserves their ports so the panel never overwrites them. |
| **Native tunnels** | Paqet, Backhaul, GOST, FRP and SS2022 tunnels between nodes, managed from the panel; automatic node install over SSH; tunnel entries published in subscriptions. |
| **Gaming DNS** | A DNS profile (~890 domains, 66 services, one-click bundles) that routes game, store and sanctioned-service traffic through a gateway on your node. Built into the Add/Edit User form; users register their own IP from a personal link; daily IP-change limit; per-service usage; domain watch to find missing domains. |
| **Unified users** | DNS access follows the panel user: expiry, data limit, status and admin scope. No separate DNS accounts to maintain. |
| **UI** | New "Firuzeh & Lapis" theme and a rebuilt subscription page. |
| **Operations** | Health checks, encrypted SQLite backups, certificate-expiry checks. |

### Honest status

Panel-side features are covered by automated tests. **Nothing has been validated with a real game, console or ISP yet** (see [docs/GAMING-ACCEPTANCE.fa.md](docs/GAMING-ACCEPTANCE.fa.md)). Known limits: the gateway relays TCP only (no UDP game traffic yet), the node does not enforce per-user speed caps yet, and a gateway profile holds at most 256 domain routes.

---

## Requirements for each feature

| Feature | Needs |
|---|---|
| Panel | Linux x86_64 or arm64, Docker + Compose v2 (installed for you); about 2 GB RAM if the image is built on the server |
| Nodes / tunnels | A server per node reachable over SSH for the automatic install; for Paqet, raw-socket capability and a matching NIC/IP/MAC (see [docs/NATIVE-PAQET.fa.md](docs/NATIVE-PAQET.fa.md)) |
| User DNS links | `DNS_ENABLED=true` (`pasarguard-plus dns`) |
| Gaming DNS gateway | A connected node, HTTPS panel address (`SMARTDNS_PANEL_URL`, set by `pasarguard-plus https-ip` or your own domain), and an egress node/tunnel **outside Iran** for sanctions bypass |
| Public DNS port 53 | Free port 53 on the server (stop `systemd-resolved`'s stub listener) and `DNS_LISTEN_ADDRESSES` / `DNS_PORT` set |

Every environment variable is documented in [`.env.example`](.env.example).

---

## Documentation

- [Install and prerequisites](docs/INSTALL.md)
- [Gaming DNS](docs/GAMING-DNS.fa.md) · [roadmap](docs/GAMING-DNS-PRO-PLAN.fa.md) · [acceptance table](docs/GAMING-ACCEPTANCE.fa.md)
- [Detect existing tunnels](docs/TUNNEL-DISCOVERY.fa.md) (read-only; reserves ports of a running Paqet/V2Ray/DNS setup)
- [Native tunnels](docs/NATIVE-TUNNELS.fa.md) · [Paqet](docs/NATIVE-PAQET.fa.md) · [tunnel hosts in subscriptions](docs/TUNNEL-SUBSCRIPTION-HOSTS.fa.md)
- [Operations and backups](docs/NATIVE-OPERATIONS.fa.md)

The base PasarGuard documentation is at [docs.pasarguard.org](https://docs.pasarguard.org/en).

## License

[AGPL-3.0](LICENSE). Based on [PasarGuard](https://github.com/PasarGuard/panel); upstream copyright and license apply.
