#!/usr/bin/env bash
# Fase 0: fatia os casos de testdata/cases.tsv com slice_cli num aparelho ou
# emulador via adb e compara com o G-code de referência do OrcaSlicer desktop.
#
# Uso: [ANDROID_ABI=x86_64] scripts/test-device.sh [nome ...]
#   Requer scripts/build-native.sh para a mesma ABI e um aparelho visível no adb.
#   Para o emulador: $ANDROID_SDK_ROOT/emulator/emulator -avd slicer_x86_64 &
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
BIN="$ROOT/native/build-$ANDROID_ABI/slice_cli"
PROFILES="$ROOT/third_party/OrcaSlicer/resources/profiles"
OUT="$ROOT/native/build-$ANDROID_ABI/test"
DEV=/data/local/tmp/slicer

"$ADB" wait-for-device
DEV_ABI="$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
if [ "$DEV_ABI" != "$ANDROID_ABI" ]; then
    echo "aparelho é $DEV_ABI, binário é $ANDROID_ABI (ajuste ANDROID_ABI)" >&2
    exit 1
fi

mkdir -p "$OUT"
"$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$OUT/slice_cli" "$BIN"
"$ADB" shell "rm -rf $DEV && mkdir -p $DEV/resources/profiles $DEV/models"
"$ADB" push "$OUT/slice_cli" "$DEV/" >/dev/null
"$ADB" push "$PROFILES/../info" "$DEV/resources/" >/dev/null
"$ADB" shell "chmod 755 $DEV/slice_cli"

# Referências: CLI do Orca desktop. Esse CLI rebaixa as acelerações de
# impressoras Bambu (resources/profiles/BBL/cli_config.json, "cli_safe_*"), o que
# a GUI não faz; por isso o tempo estimado difere e não entra na comparação.
failed=0
while IFS=$'\t' read -r name model vendor machine process filament; do
    if [ $# -gt 0 ] && [[ " $* " != *" $name "* ]]; then continue; fi
    echo "=== $name ($vendor / $machine)"
    if ! "$ADB" shell "[ -d $DEV/resources/profiles/$vendor ]" </dev/null; then
        "$ADB" push "$PROFILES/$vendor" "$DEV/resources/profiles/" >/dev/null </dev/null
    fi
    "$ADB" push "$ROOT/testdata/$model" "$DEV/models/" >/dev/null </dev/null

    P="$DEV/resources/profiles/$vendor"
    if ! "$ADB" shell "cd $DEV && time ./slice_cli resources/profiles \
            '$P/machine/$machine.json' '$P/process/$process.json' '$P/filament/$filament.json' \
            'models/$model' '$name.gcode' 2>&1 | grep -v '^\[ *[0-9]*%\]'" </dev/null; then
        failed=1
        continue
    fi
    "$ADB" pull "$DEV/$name.gcode" "$OUT/$name.gcode" >/dev/null </dev/null
    gunzip -c "$ROOT/testdata/reference/$name.gcode.gz" > "$OUT/$name.reference.gcode"
    python3 "$ROOT/scripts/compare-gcode.py" "$OUT/$name.reference.gcode" "$OUT/$name.gcode" || failed=1
done < <(grep -v '^#' "$ROOT/testdata/cases.tsv")

exit $failed
