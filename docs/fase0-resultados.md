# Fase 0 — Resultados

Estado em 2026-10-24. Objetivo da fase (ver [roadmap](roadmap.md)): `libslic3r` do
OrcaSlicer fatiando um STL dentro do Android, sem UI, com G-code equivalente ao desktop.

**Conclusão: gate aprovado no emulador** e no Xiaomi Pad 7 (arm64): `scripts/test-device.sh` 5/5 em 2026-10-28.

## Como reproduzir

```bash
scripts/apply-patches.sh                       # patches no submódulo OrcaSlicer
scripts/build-deps.sh                          # dependências (arm64-v8a)
scripts/build-native.sh                        # libslic3r + slice_cli
ANDROID_ABI=x86_64 scripts/build-deps.sh       # idem para o emulador
ANDROID_ABI=x86_64 scripts/build-native.sh
$ANDROID_SDK_ROOT/emulator/emulator -avd slicer_x86_64 -no-window &
ANDROID_ABI=x86_64 scripts/test-device.sh      # fatia e compara os casos
```

Com um tablet conectado por USB (depuração ativada): `scripts/test-device.sh` (arm64 é o padrão).

Os casos ficam em `testdata/cases.tsv`; as referências em `testdata/reference/` são geradas
pelo CLI do OrcaSlicer 2.4.2 desktop com `scripts/make-reference.sh`.

## Comparação com o desktop

Emulador Android 14 x86_64 (Pixel Tablet, API 34), OrcaSlicer v2.4.2 dos dois lados.

| Caso | Impressora / perfil | Camadas | Extrusão total (desktop × Android) | Pior camada |
|---|---|---|---|---|
| `cube20` — cubo 20 mm | Bambu A1 0.4, 0.20mm Standard, Bambu PLA Basic | 100 = 100 | 1189,57 × 1190,16 mm (0,050%) | 0,61% |
| `voron_cube` — Voron Design Cube v7 | Bambu A1 0.4, 0.20mm Standard, Bambu PLA Basic | 150 = 150 | 3059,50 × 3059,67 mm (0,006%) | 0,67% |
| `calicat` — calicat | Creality Ender-3 V3 0.4 (Klipper), 0.20mm Standard, Generic PLA | 174 = 174 | 2398,25 × 2398,19 mm (0,002%) | 0,28% |

**Correção (fase 1):** a primeira versão desta tabela comparava perfis incompletos. O CLI do
Orca desktop (`--load-settings`) não resolve `inherits`: usa só as chaves do JSON indicado mais
os defaults (ex.: infill 20% em vez de 15%, 4 camadas de topo em vez de 5). O `slice_cli` da
fase 0 tentava resolver a herança por conta própria, mas o G-code saía com os mesmos valores
incompletos, e por isso "batia" com a referência. Agora o `slice_cli` usa `PresetBundle` (a mesma fachada
`native/src/engine.hpp` do app) e `scripts/make-reference.sh` passa ao CLI perfis achatados por
`scripts/flatten-profile.py`. Os números acima são os novos.

O G-code não é idêntico byte a byte, e não se espera que seja:

- Paredes e preenchimento sólido saem com a mesma extrusão; muda a ordem e o sentido das
  linhas. O preenchimento esparso varia até ~1,5% por camada (a ligação entre linhas muda),
  com a soma estável. Causa provável: desempates de ordenação e arredondamento diferentes
  entre GCC/libstdc++ (desktop) e clang/libc++ (NDK). O mesmo binário em arm64 e x86_64
  difere só na ordem (≤ 0,005% por camada).
- O tempo estimado das Bambu difere (~1%) porque o CLI desktop rebaixa as acelerações dessas
  impressoras (`resources/profiles/BBL/cli_config.json`, chaves `cli_safe_*`); a GUI do Orca
  não faz isso, então o app também não fará.

`scripts/compare-gcode.py` aceita até 2% por camada e 0,1% no total.

## Tamanho e desempenho

- `slice_cli` arm64 sem símbolos: **12 MB** (estático: libslic3r + Boost + TBB + CGAL/GMP +
  OpenVDB etc.). A `.so` JNI da fase 1 deve ficar na mesma ordem.
- `liblibslic3r.a` arm64: 80 MB (objetos, antes do link).
- No emulador (CPU do PC): cubo 0,1 s, Voron cube 0,9 s, calicat 0,3 s. Não representa um tablet.

## Patches no OrcaSlicer

O primeiro patch, `native/patches/0001-android-no-occt.patch`, aplicado por `scripts/apply-patches.sh`:

- `src/libslic3r/CMakeLists.txt`: opção `SLIC3R_NO_OCCT` que tira OpenCASCADE, OpenCV e Draco
  do link, remove os fontes que dependem deles e adiciona `native/src/android_stubs.cpp`.
- `Format/STEP.hpp`, `Model.cpp`: STEP vira erro claro em vez de depender de OCCT.
- `LocalesUtils.hpp`: faltava `#include <sstream>` (com a libc++ do NDK o header não vem por tabela).

Depois vieram outros, na mesma pasta:

- `0002-chamber-cooling-mode.patch`: variável `chamber_cooling_mode` do G-code inicial da Snapmaker U1,
  como o Snapmaker Orca calcula.
- `0003-gcode-generator-name.patch`: nome do gerador e linha `printer_model` no cabeçalho do G-code,
  para o firmware da Creality K2/K3 reconhecer o arquivo.
- `0004-default-bed-type-names.patch`: `default_bed_type` pelo nome da mesa ("Textured PEI Plate"),
  como os perfis gravam; trazido do Orca atual (o 2.4.2 só lia o número e caía em High Temp Plate).

Fora dos patches, `native/CMakeLists.txt` substitui o CMake raiz do Orca (sem GUI, Python nem
desktop integration) e `native/deps/` cross-compila as dependências.

## Recursos desligados no Android

| Recurso | Motivo | Efeito para o usuário |
|---|---|---|
| Importar STEP | Exige OpenCASCADE (enorme) | Só STL/3MF/OBJ |
| Importar SVG / texto 3D | Exigem OCCT (fontes e extrusão) | Sem "adicionar texto" / SVG |
| Draco (`.drc`) | Compilada desde 2026-10-30 (modelos de calibração) | — |
| Cores de OBJ (`ObjColorUtils`) | Exige OpenCV | OBJ multicolorido entra sem mapeamento de cores |

Mantidos: OpenVDB (usado pelo suporte em árvore 3D), CGAL + GMP/MPFR (booleanas de malha),
TBB (paralelismo), NLopt, Qhull, OpenSSL, FreeType.

## Bugs encontrados no caminho

- `GCode::do_export` tenta criar o diretório pai da saída; com caminho relativo esse pai é
  vazio e o `create_directory("")` falha. `slice_cli` passa caminho absoluto.
- No Android não há `/tmp`; o diretório de dados é sempre passado à fachada (`slice_cli` usa `./data`).
- `Print::is_BBL_printer()` vem do `PresetBundle` no desktop; sem ele, a validação de
  "G92 E0" feita para Marlin barra as Bambu. Desde a fase 1 vem de `PresetBundle::is_bbl_vendor()`.

## Pendências

1. Rodar `scripts/test-device.sh` num tablet arm64 real e medir tempo de fatiamento.
