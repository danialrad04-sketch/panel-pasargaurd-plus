#!/bin/sh
# Shared source builder for the node image and the panel's offline install bundle.
set -eu
out="${1:-/out}"
mkdir -p "$out"
CGO_ENABLED=0 go build -trimpath -o "$out/pg-node" ./cmd/node
CGO_ENABLED=1 go -C paqet build -trimpath -o "$out/paqet" ./cmd
CGO_ENABLED=0 go -C engines/backhaul build -trimpath -o "$out/backhaul" .
CGO_ENABLED=0 go -C engines/gost build -trimpath -o "$out/gost" ./cmd/gost
CGO_ENABLED=0 go -C engines/frp build -tags frps,noweb -trimpath -o "$out/frps" ./cmd/frps
CGO_ENABLED=0 go -C engines/frp build -tags frpc,noweb -trimpath -o "$out/frpc" ./cmd/frpc
case "$(go env GOARCH)" in
 amd64) archive=Xray-linux-64.zip; checksum=0eec3ed4163c273d8c8bf821f7d219e257921cf584aa3fb970715e458e631978 ;;
 arm64) archive=Xray-linux-arm64-v8a.zip; checksum=d1304946c486d98e6b886e9b931eaaaebb23835b30e217638037b7f2721d4e9c ;;
 *) echo 'Unsupported native bundle architecture' >&2; exit 1 ;;
esac
curl -fsSL --retry 3 "https://github.com/XTLS/Xray-core/releases/download/v26.10.21/$archive" -o "$out/xray.zip"
printf '%s  %s\n' "$checksum" "$out/xray.zip" | sha256sum -c -
unzip -qo "$out/xray.zip" -d "$out/xray"
make GEOFILES_SUDO= GEOFILES_XRAY="$out/xray/xray" GEOFILES_DIR="$out/assets" install_geofiles
rm "$out/xray.zip"
