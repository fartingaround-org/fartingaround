#!/usr/bin/env bash
# Cross-compile fa for the platforms we care about: ./scripts/release.sh v0.1.0
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:-dev}"
mkdir -p dist
for target in linux/amd64 linux/arm64 darwin/arm64 darwin/amd64; do
  os="${target%/*}"; arch="${target#*/}"
  out="dist/fa-$os-$arch"
  CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" go build -trimpath -ldflags "-s -w -X main.version=$VERSION" -o "$out" .
  echo "built $out"
done
(cd dist && shasum -a 256 fa-* > SHA256SUMS)
