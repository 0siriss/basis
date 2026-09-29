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

# llama.cpp sources (built from source via CMake in ml/llm), pinned to a tag and its commit hash.
LLAMA_TAG=b11249
LLAMA_COMMIT=6d78fb0727fdd8fbae15b6b5e9e0c0951a750d69
llama="$ROOT/third_party/llama.cpp"
if [ -d "$llama/.git" ] && [ "$(git -C "$llama" rev-parse HEAD)" = "$LLAMA_COMMIT" ]; then
  echo "llama.cpp $LLAMA_TAG: already present"
else
  rm -rf "$llama"
  GIT_LFS_SKIP_SMUDGE=1 git clone -q --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp "$llama"
  got="$(git -C "$llama" rev-parse HEAD)"
  if [ "$got" != "$LLAMA_COMMIT" ]; then
    echo "llama.cpp $LLAMA_TAG points to $got, expected $LLAMA_COMMIT" >&2; exit 1
  fi
  echo "ok: llama.cpp $LLAMA_TAG ($got)"
fi
