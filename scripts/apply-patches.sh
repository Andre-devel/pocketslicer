#!/usr/bin/env bash
# Aplica native/patches/*.patch sobre third_party/OrcaSlicer (idempotente).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORCA="$ROOT/third_party/OrcaSlicer"
for p in "$ROOT"/native/patches/*.patch; do
    if git -C "$ORCA" apply --reverse --check "$p" 2>/dev/null; then
        echo "já aplicado: $(basename "$p")"
    else
        git -C "$ORCA" apply "$p"
        echo "aplicado: $(basename "$p")"
    fi
done
