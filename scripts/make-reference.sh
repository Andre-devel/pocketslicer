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
    mkdir -p "$WORK/$name"
    # O CLI não resolve "inherits"; recebe os perfis já achatados (como a GUI exporta).
    python3 "$ROOT/scripts/flatten-profile.py" "$PROFILES" "$vendor" machine "$machine" "$WORK/$name/machine.json"
    python3 "$ROOT/scripts/flatten-profile.py" "$PROFILES" "$vendor" process "$process" "$WORK/$name/process.json"
    python3 "$ROOT/scripts/flatten-profile.py" "$PROFILES" "$vendor" filament "$filament" "$WORK/$name/filament.json"
    "$ORCA" --slice 0 --arrange 1 \
        --load-settings "$WORK/$name/machine.json;$WORK/$name/process.json" \
        --load-filaments "$WORK/$name/filament.json" \
        --outputdir "$WORK/$name" "$ROOT/testdata/$model" > "$WORK/$name.log" 2>&1 || {
        echo "$name: falhou, ver log abaixo" >&2; tail -20 "$WORK/$name.log" >&2; exit 1; }
    gzip -9c "$WORK/$name/plate_1.gcode" > "$ROOT/testdata/reference/$name.gcode.gz"
    echo "$name: ok"
done
