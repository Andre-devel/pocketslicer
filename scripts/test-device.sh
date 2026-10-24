#!/usr/bin/env bash
# Fase 0: fatia testdata/cube20.stl com slice_cli num aparelho/emulador via adb
# e compara com o G-code de referência do OrcaSlicer desktop.
#
# Uso: [ANDROID_ABI=x86_64] scripts/test-device.sh
#   Requer scripts/build-native.sh para a mesma ABI e um aparelho visível no adb.
#   Para o emulador: $ANDROID_SDK_ROOT/emulator/emulator -avd slicer_x86_64 &
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
BIN="$ROOT/native/build-$ANDROID_ABI/slice_cli"
PROFILES="$ROOT/third_party/OrcaSlicer/resources/profiles"
REF="$ROOT/testdata/reference/cube20_A1-0.4_0.20mm-Standard_PLA-Basic.orca-2.4.2.gcode.gz"
OUT="$ROOT/native/build-$ANDROID_ABI/test"
DEV=/data/local/tmp/slicer

"$ADB" wait-for-device
DEV_ABI="$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
if [ "$DEV_ABI" != "$ANDROID_ABI" ]; then
    echo "aparelho é $DEV_ABI, binário é $ANDROID_ABI (ajuste ANDROID_ABI)" >&2
    exit 1
fi

# Binário sem símbolos de depuração: o original passa de 500 MB.
mkdir -p "$OUT"
"$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$OUT/slice_cli" "$BIN"

"$ADB" shell "rm -rf $DEV && mkdir -p $DEV/resources/profiles"
"$ADB" push "$OUT/slice_cli" "$DEV/"
"$ADB" push "$PROFILES/BBL" "$DEV/resources/profiles/"
"$ADB" push "$PROFILES/../info" "$DEV/resources/"
"$ADB" push "$ROOT/testdata/cube20.stl" "$DEV/"

P="$DEV/resources/profiles/BBL"
"$ADB" shell "cd $DEV && chmod 755 slice_cli && time ./slice_cli resources/profiles \
    '$P/machine/Bambu Lab A1 0.4 nozzle.json' \
    '$P/process/0.20mm Standard @BBL A1.json' \
    '$P/filament/Bambu PLA Basic @BBL A1.json' \
    cube20.stl cube20.gcode"
"$ADB" pull "$DEV/cube20.gcode" "$OUT/cube20.gcode"

# Referência: CLI do Orca 2.4.2 desktop. Esse CLI rebaixa as acelerações de
# impressoras Bambu (resources/profiles/BBL/cli_config.json, "cli_safe_*"), o que
# a GUI não faz; por isso o tempo estimado difere e não é comparado.
gunzip -c "$REF" > "$OUT/reference.gcode"
echo "--- estatísticas (referência desktop | Android)"
for f in "$OUT/reference.gcode" "$OUT/cube20.gcode"; do
    grep -E '^; (total estimated time|filament used \[(mm|cm3)\]|total layer number)' "$f" | tr '\n' ' '
    echo
done
python3 "$ROOT/scripts/compare-gcode.py" "$OUT/reference.gcode" "$OUT/cube20.gcode"
