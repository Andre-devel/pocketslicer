# Roadmap — Android Slicer

Fases sequenciais; cada uma tem critério de saída objetivo. Estimativas assumem 1 pessoa
em tempo parcial com apoio de IA; são ordens de grandeza, não compromissos.

## Fase 0 — Prova de conceito nativa (2–4 semanas) — **gate do projeto**
**Status:** aprovado no emulador em 2026-10-24; falta confirmar em tablet arm64. Ver [fase0-resultados.md](fase0-resultados.md).
Objetivo: `libslic3r` fatiando um STL dentro de um Android arm64, sem UI.

1. Preparar ambiente: instalar NDK + CMake via `sdkmanager`, liberar disco, `JAVA_HOME` = JDK 17.
2. Adicionar `third_party/OrcaSlicer` como submódulo em tag fixo.
3. `scripts/build-deps.sh`: cross-compilar na ordem Boost → TBB → ZLIB/PNG/JPEG/EXPAT → GMP/MPFR → NLopt → OpenSSL → FreeType.
4. `native/CMakeLists.txt` com `SLIC3R_GUI=0`, `SLIC3R_CAD=0`, OpenVDB/OCCT/CURL/OpenCV desligados; patches mínimos em `native/patches/`.
5. Executável de teste (`native/tests/slice_cli`) empurrado via `adb push` que carrega STL + perfis Orca, roda `Print::process()`, grava G-code.
6. Comparar G-code com o OrcaSlicer desktop para 3 modelos de referência.

**Saída:** G-code gerado no tablet equivalente ao desktop; lista fechada de patches e de features desligadas; tamanho do `.so` medido.
**Se falhar** (dependência intransponível): reavaliar CuraEngine ou motor próprio antes de investir em UI.

## Fase 1 — Esqueleto do app (2–3 semanas)
**Status:** itens 1–5 implementados em 2026-10-24 e validados no emulador (x86_64): APK abre STL
pelo SAF, fatia e salva G-code; 3 testes instrumentados e a paridade de `scripts/test-device.sh`
passam. Falta repetir num tablet arm64 real. Ver README para build e testes.
1. Projeto Gradle: `:app`, `:slicer-core`, `:profiles`; Compose; Kotlin 2.x; AGP atual; minSdk 29; `abiFilters arm64-v8a`.
2. Bridge JNI (`bridge.cpp` + `SlicerEngine.kt`) cobrindo `create`, `load_model`, `list_presets`, `select_preset`, `slice`, `export_gcode`.
3. Empacotar `resources/profiles` como asset comprimido; extração/índice na primeira execução.
4. Tela única: abrir arquivo (SAF) → escolher impressora/filamento/processo (dropdowns) → botão Fatiar → progresso → salvar G-code (SAF).
5. Teste instrumentado que fatia um STL do `testdata/` no device.

**Saída:** APK que fatia e exporta G-code, sem visualização 3D.

## Fase 2 — Viewport 3D e preparo (3–5 semanas)
1. `SceneRenderer` GL ES 3: mesa do perfil, malha do modelo, câmera orbital, gestos.
2. Seleção por toque, gizmo mover/rotacionar/escalar, campos numéricos, lay-on-face.
3. Múltiplos objetos, duplicar/excluir, arranjo automático, aviso fora da mesa.
4. Layout tablet (toolbar / viewport / painel lateral), paisagem e retrato.

**Saída:** preparo de cena completo (RF-01…RF-06, RF-50…RF-52).

## Fase 3 — Parâmetros e presets (2–3 semanas)
1. `engine_get_config_def` → geração automática da lista completa de parâmetros (tipo, enum, default, tooltip, grupo).
2. Painel "básico" curado + modo "avançado" pesquisável.
3. Presets de usuário: salvar, renomear, excluir, import/export JSON.
4. Resolução de herança `inherits` e compatibilidade impressora↔filamento↔processo igual ao Orca (usar `PresetBundle` do próprio Orca para não reimplementar).

**Saída:** RF-10…RF-14.

## Fase 4 — Preview de G-code (3–4 semanas)
1. `engine_get_toolpaths` a partir de `GCodeProcessorResult`.
2. `PreviewRenderer`: segmentos extrudados na GPU, cores por feature/velocidade/altura, barras de camada e de movimento, legenda com toggles.
3. Estatísticas (tempo, filamento, custo).
4. Alternância preparo ↔ preview sem refatiar.

**Saída:** RF-20…RF-33.

## Fase 5 — Projeto 3MF, polimento e release (2–3 semanas)
1. Salvar/abrir projeto 3MF com configuração e miniatura; "Abrir com" para STL/3MF/OBJ/G-code.
2. Miniatura no G-code por perfil de impressora.
3. Tema claro/escuro, pt-BR/en, tratamento de erros, telemetria **nenhuma**.
4. Suíte de paridade automatizada (RNF-05) rodando em CI contra device físico ou pelo menos no host build.
5. `NOTICE`, `LICENSE` (AGPL-3.0), README, release no GitHub.

**Saída:** MVP v1.0.

## Pós-MVP (ordem sugerida)
1. Envio LAN: Moonraker (HTTP upload + start), Bambu LAN mode (FTPS + MQTT), OctoPrint.
2. Modificadores por faixa de altura e volumes modificadores.
3. Pintura de suportes/seam (gizmos de pintura do Orca são GUI; reimplementar sobre `TriangleSelector` de libslic3r).
4. Multi-material (AMS/MMU) com torre de purga.
5. Reativar OpenVDB (hollowing, reparo) e OCCT (STEP) se o tamanho do APK permitir.
6. Ferramentas de calibração reaproveitando os geradores do Orca.
7. Play Asset Delivery para perfis sob demanda; ABI x86_64 para emulador.

## Cadência de atualização do OrcaSlicer
A cada release maior do Orca: branch `upgrade/orca-vX.Y`, mover submódulo, reaplicar patches, rodar paridade; merge só com paridade verde.
