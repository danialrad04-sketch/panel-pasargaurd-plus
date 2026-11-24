#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

version=v26.11.9
checksum=7c58346709f1856782a48d8f9926fb815c8ad1bbaf9e5d9dec4ee0cd1f41ed6f
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
curl -fsSL --retry 3 --retry-all-errors \
    "https://github.com/XTLS/libXray/releases/download/$version/libxray-apple-cgo.zip" \
    -o "$scratch/libxray.zip"
printf '%s  %s\n' "$checksum" "$scratch/libxray.zip" | shasum -a 256 -c -
unzip -q "$scratch/libxray.zip" -d "$scratch"
mkdir -p Dependencies
for target in ios-arm64 ios-arm64_x86_64-simulator; do
    test -f "$scratch/libxray-apple-cgo/LibXray.xcframework/$target/libXray.a"
done
if [[ -e Dependencies/LibXray.xcframework ]]; then
    rm -rf Dependencies/LibXray.xcframework
fi
mv "$scratch/libxray-apple-cgo/LibXray.xcframework" Dependencies/

mkdir -p Dependencies/Geo
fetch_geo() {
    local url=$1 hash=$2 name=$3
    curl -fsSL --retry 3 --retry-all-errors "$url" -o "$scratch/$name"
    printf '%s  %s\n' "$hash" "$scratch/$name" | shasum -a 256 -c -
    mv "$scratch/$name" "Dependencies/Geo/$name"
}
fetch_geo https://github.com/v2fly/geoip/releases/download/202611080027/geoip.dat \
    eedb24b3b9fcba27e06b92c0428dab90d9496083091ae4cf39e11387fd99d0e2 geoip.dat
fetch_geo https://github.com/v2fly/domain-list-community/releases/download/20261123144211/dlc.dat \
    b5aab90aeb07df4b1248a1c2a77917b43580d6542e490c287b952c699b484a32 geosite.dat
