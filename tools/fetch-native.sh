#!/usr/bin/env bash
# Fetches prebuilt native libraries (not committed) into a local Maven repo at third_party/maven.
# Run once before building (CI does it automatically).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SHERPA_VERSION=1.13.8
SHERPA_SHA256=633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"

dest="$ROOT/third_party/maven/com/k2fsa/sherpa/onnx/sherpa-onnx/$SHERPA_VERSION"
aar="$dest/sherpa-onnx-$SHERPA_VERSION.aar"
mkdir -p "$dest"

verify() { echo "$SHERPA_SHA256  $1" | sha256sum -c --quiet - >/dev/null 2>&1; }

if [ -f "$aar" ] && verify "$aar"; then
  echo "sherpa-onnx $SHERPA_VERSION: already present"
else
  echo "downloading $SHERPA_URL"
  curl -fL --retry 4 --retry-delay 2 -o "$aar.tmp" "$SHERPA_URL"
  if ! verify "$aar.tmp"; then
    echo "SHA-256 mismatch for sherpa-onnx AAR: $(sha256sum "$aar.tmp" | cut -d' ' -f1)" >&2
    rm -f "$aar.tmp"; exit 1
  fi
  mv "$aar.tmp" "$aar"
fi

cat > "$dest/sherpa-onnx-$SHERPA_VERSION.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.k2fsa.sherpa.onnx</groupId>
  <artifactId>sherpa-onnx</artifactId>
  <version>$SHERPA_VERSION</version>
  <packaging>aar</packaging>
</project>
POM
echo "ok: $aar"
