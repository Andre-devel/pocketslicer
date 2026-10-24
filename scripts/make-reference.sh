#!/usr/bin/env bash
# Gera testdata/reference/<nome>.gcode.gz com o OrcaSlicer desktop (CLI) para
# cada caso de testdata/cases.tsv. Só é preciso rodar ao trocar a versão do Orca
# ou adicionar casos.
#
# Uso: [ORCA=/caminho/AppRun] scripts/make-reference.sh [nome ...]
#   ORCA padrão: AppImage 2.4.2 extraído em ~/Applications/orca-2.4.2.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORCA="${ORCA:-$HOME/Applications/orca-2.4.2/squashfs-root/AppRun}"
PROFILES="$ROOT/third_party/OrcaSlicer/resources/profiles"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

grep -v '^#' "$ROOT/testdata/cases.tsv" | while IFS=$'\t' read -r name model vendor machine process filament; do
    if [ $# -gt 0 ] && [[ " $* " != *" $name "* ]]; then continue; fi
    P="$PROFILES/$vendor"
    mkdir -p "$WORK/$name"
    "$ORCA" --slice 0 --arrange 1 \
        --load-settings "$P/machine/$machine.json;$P/process/$process.json" \
        --load-filaments "$P/filament/$filament.json" \
        --outputdir "$WORK/$name" "$ROOT/testdata/$model" > "$WORK/$name.log" 2>&1 || {
        echo "$name: falhou, ver log abaixo" >&2; tail -20 "$WORK/$name.log" >&2; exit 1; }
    gzip -9c "$WORK/$name/plate_1.gcode" > "$ROOT/testdata/reference/$name.gcode.gz"
    echo "$name: ok"
done
