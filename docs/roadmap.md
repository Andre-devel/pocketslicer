# Roadmap — PocketSlicer

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
**Status:** item 1 implementado em 2026-10-24 e visto no emulador: mesa do perfil (contorno + grade),
malha do modelo centralizada nela, câmera orbital (1 dedo gira, 2 dedos pan/pinça) e layout
viewport + painel (paisagem/retrato). Pinça/pan ainda não testados com toque real.
Item 2 implementado em 2026-10-24 e visto no emulador: toque seleciona; ferramentas Mover/Girar/Escalar
agem arrastando sobre o objeto selecionado (no lugar de um gizmo 3D com alças, mais adequado ao toque);
campos de posição, rotação e escala (uniforme ou por eixo); "Apoiar face" pelo triângulo tocado.
Item 3 implementado em 2026-10-24 e visto no emulador: "Adicionar modelo" acumula objetos; duplicar e
excluir pelo toque longo ou pelo painel; "Arranjar" (libnest2d do Orca); objeto fora do volume em
vermelho, com aviso, e fatiamento recusado. Adicionar/duplicar rearranja todos os objetos, como o
"Clonar" do Orca (o arranjo com objetos fixos do FirstFit do Orca os sobrepõe quando falha a 1ª tentativa).
Item 4 implementado em 2026-10-24 e visto no emulador: barra superior (Adicionar modelo, Arranjar,
Ocultar/Mostrar painel), painel lateral recolhível em paisagem e bottom sheet em retrato.
Falta da fase 2: testar gestos de 2 dedos e desempenho com malhas grandes num tablet real.
1. `SceneRenderer` GL ES 3: mesa do perfil, malha do modelo, câmera orbital, gestos.
2. Seleção por toque, gizmo mover/rotacionar/escalar, campos numéricos, lay-on-face.
3. Múltiplos objetos, duplicar/excluir, arranjo automático, aviso fora da mesa.
4. Layout tablet (toolbar / viewport / painel lateral), paisagem e retrato.

**Saída:** preparo de cena completo (RF-01…RF-06, RF-50…RF-52).

## Fase 3 — Parâmetros e presets (2–3 semanas)
**Status:** implementada em 2026-10-24 e vista no emulador. As opções vêm do `PrintConfigDef` (tipo,
enum, categoria, dica); o painel básico é o modo simples da GUI do Orca e o avançado mostra todas,
com busca. Presets de usuário (salvar, excluir, descartar alterações, importar/exportar `.json`)
usam o `PresetBundle` do Orca e ficam em `filesDir/orca-data/user/default` (sobrevivem a
atualizações). Renomear = salvar com outro nome e excluir o antigo.
1. `engine_get_config_def` → geração automática da lista completa de parâmetros (tipo, enum, default, tooltip, grupo).
2. Painel "básico" curado + modo "avançado" pesquisável.
3. Presets de usuário: salvar, renomear, excluir, import/export JSON.
4. Resolução de herança `inherits` e compatibilidade impressora↔filamento↔processo igual ao Orca (usar `PresetBundle` do próprio Orca para não reimplementar).

**Saída:** RF-10…RF-14.

## Fase 4 — Preview de G-code (3–4 semanas)
**Status:** implementada em 2026-10-24 e vista no emulador. O motor expõe os segmentos do
`GCodeProcessorResult` em buffers planos; o app desenha cada segmento como caixa instanciada
(`render/ToolpathRenderer.kt`), com cor por tipo/velocidade/altura/largura, tipos ocultáveis na
legenda, deslocamentos opcionais, barra de intervalo de camadas e barra de movimentos da camada de
cima (cortes por uniform, sem refazer VBO). Estatísticas: tempo, filamento (m, g) e custo. O botão
Preparar/Visualizar alterna sem refatiar.

1. `engine_get_toolpaths` a partir de `GCodeProcessorResult`.
2. `PreviewRenderer`: segmentos extrudados na GPU, cores por feature/velocidade/altura, barras de camada e de movimento, legenda com toggles.
3. Estatísticas (tempo, filamento, custo).
4. Alternância preparo ↔ preview sem refatiar.

**Saída:** RF-20…RF-33.

## Fase 5 — Projeto 3MF, polimento e release (2–3 semanas)
**Status:** item 1 implementado em 2026-10-24 e visto no emulador. Projetos são o 3MF do Orca
(`store_bbs_3mf`/`read_from_archive`), com configuração completa e miniatura 512×512 renderizada
fora da tela; o OrcaSlicer 2.4.2 desktop abre e fatia o projeto salvo no app com os mesmos perfis.
Ao abrir, o motor carrega o fabricante da impressora do projeto, e alterações aparecem como não
salvas; de projetos com várias placas, só a primeira. G-code aberto vai direto para o preview.
"Abrir com" e compartilhar aceitam 3MF, STL, OBJ e G-code. Item 2 também: o G-code leva as
miniaturas da opção `thumbnails` da impressora (tamanhos e formatos PNG/QOI/... do perfil),
renderizadas pelo app antes de fatiar; impressoras Bambu não têm miniatura no G-code, como no Orca.
Item 3 também: tema claro/escuro do sistema (paleta fixa do redesenho, ver
[redesenho.md](redesenho.md); viewport acompanha); app em inglês (padrão) e português, com escolha por app no Android 13+. Os textos do
motor (rótulos e dicas das opções, progresso, tipos de extrusão, erros) vêm do catálogo `.po` do
Orca, mais `native/i18n` para os da fachada. Erros de arquivo, falta de memória e falhas
inesperadas viram mensagem em vez de fechar o app. Sem telemetria (nem permissão de internet).
Item 4: `.github/workflows/parity.yml` (GitHub Actions) compila as dependências (em cache), o
`slice_cli` e os testes, e num emulador x86_64 roda a paridade de `scripts/test-device.sh` e os
testes instrumentados. Casos: BBL A1, Creality Ender-3 V3, Prusa MK4 e Voron 2.4, todos
equivalentes ao Orca 2.4.2 desktop. Ainda não rodou no GitHub (o repositório não tem remoto);
contra aparelho físico, só manualmente com `scripts/test-device.sh`.
Num aparelho físico (Galaxy S25, Android 16, arm64) os 5 casos passam, de 0,8 a 2 s cada, depois
de desligar a fusão de multiplicação e soma (FMA) do clang no arm64 (`-ffp-contract=off`).
Item 5 em parte: `LICENSE` (AGPL-3.0), `NOTICE` com as licenças das bibliotecas do APK e README.
Nome definitivo PocketSlicer (`applicationId` `io.github.andredevel.pocketslicer`; o pacote do
código continua `io.github.andredevel.slicer`, que também nomeia as funções JNI). APK de release
assinado com a chave de `scripts/make-release-key.sh`. Falta a release no GitHub.

1. Salvar/abrir projeto 3MF com configuração e miniatura; "Abrir com" para STL/3MF/OBJ/G-code.
2. Miniatura no G-code por perfil de impressora.
3. Tema claro/escuro, pt-BR/en, tratamento de erros, telemetria **nenhuma**.
4. Suíte de paridade automatizada (RNF-05) rodando em CI contra device físico ou pelo menos no host build.
5. `NOTICE`, `LICENSE` (AGPL-3.0), README, release no GitHub.

**Saída:** MVP v1.0.

## Pós-MVP (ordem sugerida)
1. Envio LAN: Moonraker (HTTP upload + start), Creality, Bambu LAN mode (FTPS + MQTT), OctoPrint — plano em [plano-envio.md](plano-envio.md).
2. Modificadores por faixa de altura e volumes modificadores.
3. Pintura de suportes/seam (gizmos de pintura do Orca são GUI; reimplementar sobre `TriangleSelector` de libslic3r).
4. Multi-material (AMS/MMU) com torre de purga.
5. Reativar OpenVDB (hollowing, reparo) e OCCT (STEP) se o tamanho do APK permitir.
6. Ferramentas de calibração reaproveitando os geradores do Orca.
7. Play Asset Delivery para perfis sob demanda; ABI x86_64 para emulador.

## Cadência de atualização do OrcaSlicer
A cada release maior do Orca: branch `upgrade/orca-vX.Y`, mover submódulo, reaplicar patches, rodar paridade; merge só com paridade verde.
