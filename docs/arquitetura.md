# Arquitetura — Android Slicer

## 1. Visão geral

```
┌──────────────────────────────────────────────────────────────┐
│ app (Kotlin / Jetpack Compose)                               │
│  ui/        telas, viewport, painéis, gestos                 │
│  viewmodel/ estado da cena, presets, slice job               │
│  render/    OpenGL ES 3 — mesa, modelos, toolpaths           │
│  data/      presets de usuário, projeto 3MF, Room/DataStore  │
├──────────────────────────────────────────────────────────────┤
│ slicer-core (Android library module, Kotlin + JNI)           │
│  SlicerEngine.kt  API Kotlin (suspend)                       │
│ profiles (Android library module)                            │
│  orca-resources.zip (asset) + OrcaResources.kt (extração)    │
├──────────────────────────────────────────────────────────────┤
│ native (CMake, NDK, arm64-v8a)                               │
│  third_party/OrcaSlicer   submódulo em tag fixo              │
│  patches/                 patches aplicados sobre o submódulo│
│  deps/                    build cruzado das dependências     │
│  src/jni_bridge.cpp       JNI ↔ C++                          │
│  src/engine.cpp           fachada C++ sobre libslic3r        │
│  libslic3r.a + deps .a    → libslicer_jni.so                 │
└──────────────────────────────────────────────────────────────┘
```

Princípio: **toda a lógica de fatiamento é a do Orca, intocada**. O app só prepara entrada
(malhas + configuração), chama `Print::process()` e consome saída (G-code, toolpaths,
estatísticas). Nada de UI do Orca (wxWidgets) é portado.

## 2. Camada nativa

### 2.1 O que é usado do OrcaSlicer
- `src/libslic3r/` — tudo (≈250 .cpp). Classes centrais:
  - `Model`, `ModelObject`, `ModelInstance`, `TriangleMesh` — cena.
  - `DynamicPrintConfig`, `PrintConfigDef`, `PresetBundle`, `Preset` — configuração/perfis.
  - `Print`, `PrintObject`, `Print::process()`, `Print::export_gcode()` — fatiamento.
  - `GCode/GCodeProcessor` — parse do G-code para preview (gera `GCodeProcessorResult` com moves, tipos, cores, tempos).
  - `Format/3mf.cpp`, `Format/STL.cpp`, `Format/OBJ.cpp` — I/O.
  - `Arrange`/libnest2d — arranjo automático.
- `resources/profiles/` — perfis JSON (vendor → impressora/filamento/processo).
- Referência de uso: `src/OrcaSlicer.cpp` (modo CLI, trecho sem `SLIC3R_GUI`) mostra exatamente a sequência carregar → configurar → `Print::process()` → exportar. A fachada C++ replica essa sequência.

### 2.2 O que **não** é usado
- `src/slic3r/` (GUI wxWidgets), `libvgcode` (renderer GL do preview — reimplementado em GL ES), `glad`, `WebView2`, `DataChannel`, `wxInspector`, `python3` (stubgen).

### 2.3 Dependências e estratégia por dependência

| Dependência | Uso em libslic3r | Estratégia Android |
|---|---|---|
| Boost 1.83 (filesystem, log, thread, locale, regex, nowide, iostreams, program_options…) | Pervasivo | Cross-compile com `b2` toolset clang-android; `nowide` e `locale` com ICU desligado |
| TBB | Paralelismo do slice | Cross-compile (oneTBB tem suporte Android) |
| Eigen 5 | Header-only | Direto |
| Cereal | Header-only | Direto |
| Clipper2, libnest2d, admesh, libigl, miniz, qhull, semver, qoi, glu-libtess, noise | Vendorizados em `src/` do Orca | Compilam junto |
| CGAL 6.2 + GMP + MPFR | `libslic3r_cgal` (booleanos de malha, hull) | GMP/MPFR cross-compile; CGAL header-only |
| OpenVDB + Blosc + Imath | Hollowing / SLA / mesh repair | **Fase 0: desligar** (`OpenVDB::openvdb` é condicional ao target existir). Reavaliar na fase 3 |
| OCCT | Import STEP + `SLIC3R_CAD` | **Desligar** (`SLIC3R_CAD=0`); STEP fora do MVP. Patch para `#ifdef` o `Format/STEP.cpp` |
| OpenCV (`opencv_world`) | Link PUBLIC; uso restrito (imagens/texto) | **Stubar ou compilar módulos mínimos (core, imgproc)**. Investigar na fase 0 quais símbolos são realmente necessários |
| mcut | Booleanos / corte | Compila junto (vendorizado) |
| draco, assimp | Import de formatos extras (glTF, etc.) | Assimp: compilar só com STL/OBJ/3MF ou desligar; draco desligar |
| EXPAT, ZLIB, PNG, JPEG | Parsing 3MF, miniaturas | Cross-compile (NDK já traz zlib) |
| NLopt | Otimização de orientação/arranjo | Cross-compile |
| OpenSSL (Crypto) | Hash / assinatura de perfis Bambu | Cross-compile (ou `BoringSSL` do NDK se compatível) |
| CURL | Rede (download de perfis / cloud) | **Stubar**: Android build não precisa; patch para remover usos em libslic3r ou linkar curl sem backend |
| FreeType, fontconfig | Texto 3D | FreeType cross-compile; fontconfig **patch out** (Android não tem) |
| DBus, OpenGL, glfw | Só GUI/Linux desktop | Fora com `SLIC3R_GUI=0` |

Ordem recomendada de ataque na fase 0: Boost → TBB → ZLIB/PNG/JPEG/EXPAT → GMP/MPFR → NLopt → OpenSSL → FreeType → `libslic3r` com `SLIC3R_GUI=0 SLIC3R_CAD=0 ORCA_BUILD_PYTHON_STUBGEN_MODULE=OFF` e OpenVDB/OCCT/OpenCV/CURL desligados ou stubados.

### 2.4 Build
- `native/CMakeLists.txt` próprio (não o `CMakeLists.txt` raiz do Orca, que puxa GUI/Python). Inclui `add_subdirectory(third_party/OrcaSlicer/src/libslic3r)` depois de preparar os `find_package` com `CMAKE_PREFIX_PATH` apontando para `native/deps/out-android/`.
- Toolchain: `$ANDROID_NDK/build/cmake/android.toolchain.cmake`, `ANDROID_ABI=arm64-v8a`, `ANDROID_PLATFORM=android-29`, `ANDROID_STL=c++_static`.
- `scripts/build-deps.sh` — compila dependências uma vez (cache em `native/deps/out-android`, fora do Gradle). Demora ~1 h na primeira vez.
- Gradle (`externalNativeBuild.cmake`) compila só `libslic3r` + bridge JNI a cada build do app.
- Patches: `native/patches/*.patch`, aplicados por `scripts/apply-patches.sh` (idempotente, `git apply --check` antes). Submódulo fixado em um tag de release do Orca (ex.: `v2.3.x`); upgrade é tarefa explícita.

### 2.5 Fachada C++ (`native/src/engine.hpp`)
API intencionalmente pequena. Nada de expor classes do Orca para Kotlin. Usada pelo JNI
(`native/src/jni_bridge.cpp`) e pelo `slice_cli` (testes via adb), então o que o teste de
paridade mede é o mesmo caminho do app.

```cpp
class Engine {
    Engine(resources_dir, data_dir);
    std::vector<std::string> vendors() const;
    void load_vendor(vendor);                    // PresetBundle com o fabricante + OrcaFilamentLibrary
    std::vector<std::string> presets(PresetKind) const;   // filamento/processo: só os compatíveis
    std::string selected(PresetKind) const;
    void select(PresetKind, name);               // trocar impressora aplica os padrões dela
    Bed bed() const;                             // printable_area + printable_height
    void load_model(path);                       // STL/OBJ/3MF; substitui e centraliza na mesa
    void add_model(path);                        // acrescenta; rearranja se a mesa já tinha objetos
    void duplicate(i); void remove(i); void arrange();
    std::vector<ObjectMesh> objects() const;     // malha indexada, em coordenadas locais
    std::vector<Placement> placements() const;   // matriz da instância + offset/rotação/escala + fora do volume
    void set_transform(i, matrix);               // fim de um arraste no viewport
    void set_placement(i, offset, rotation, scale); // campos numéricos
    void lay_on_face(i, triangle);               // triângulo tocado vira a base
    SliceStats slice(gcode_path, ProgressFn);    // Print::process() + export_gcode()
    void cancel();                               // de qualquer thread
};
```

- Perfis: como na GUI do Orca, `PresetBundle::load_presets()` carrega os fabricantes presentes em
  `<data_dir>/system`; `load_vendor()` põe ali só symlinks para o fabricante escolhido e para
  `OrcaFilamentLibrary` (de onde os filamentos herdam). Carregar os 130+ fabricantes de uma vez
  custaria centenas de MB. Todos os modelos/variantes do fabricante ficam "instalados" no
  `AppConfig`; a lista de filamentos traz todos os compatíveis, não só os padrões.
- A configuração final é `PresetBundle::full_config()`; `Print::is_BBL_printer()` vem de
  `PresetBundle::is_bbl_vendor()`, como no Plater da GUI.
- Erros são exceções C++; o JNI converte em `SlicerException` e o cancelamento em
  `CancellationException`.
- O `Model` do libslic3r é a fonte de verdade da cena: o app só recebe malhas e transformações
  para desenhar. Fatiar usa as posições atuais (sem recentralizar). Como na GUI do Orca, a origem
  de cada objeto é o centro da malha (`center_around_origin`) e toda alteração o apoia na mesa.
  Uma instância por objeto (duplicar cria outro objeto). "Fora do volume" vem de
  `ModelInstance::calc_print_volume_state()` com o `BuildVolume` da impressora; `slice()` recusa
  fatiar nesse caso (o `Print` ignoraria a instância em silêncio).
- A crescer nas próximas fases: edição de transformações/arranjo, opções avulsas, `PrintConfigDef` serializado,
  toolpaths como buffers planos (`float* positions`, `uint8* feature_type`...) para VBOs, 3MF.

### 2.6 Threading
- Uma thread dedicada (dispatcher próprio dentro de `SlicerEngine`, pilha de 64 MB) para chamadas nativas; o `Engine` **não** é thread-safe.
- `Print::process()` roda nessa thread; progresso via callback JNI → `StateFlow` (callbacks vindos de threads do TBB são ignorados); cancelar a coroutine chama `Print::cancel()`.
- TBB usa seu pool interno; limitar threads ao número de cores *big* do SoC para não aquecer demais (configurável).

## 3. Camada Kotlin

### 3.1 Módulos Gradle
- `:app` — UI. Sem framework de injeção enquanto houver um único ViewModel (`AndroidViewModel`).
- `:slicer-core` — `SlicerEngine` (Kotlin) e o build CMake de `native/` (target `slicer_jni`). Sem dependência de Compose. Testes instrumentados em `src/androidTest` usando `testdata/`.
- `:profiles` — os JSON de `resources/profiles` do Orca + `info/` e `flush/` num zip (asset, ~9 MB), gerado no build e extraído por `OrcaResources.install()` para `filesDir` a cada instalação/atualização do APK. A lista de fabricantes vem do próprio diretório extraído.

### 3.2 Estado
- `SceneState` (objetos, transforms, seleção), `PresetState` (impressora/filamento/processo + overrides), `SliceState` (idle / running(progress) / done(result) / error).
- Tudo em `StateFlow`; Compose observa. Mudança em `SceneState`/`PresetState` invalida `SliceState`.

### 3.3 Render (OpenGL ES 3.0)
- `SceneRenderer` (`app/.../render/`, num `GLSurfaceView` dentro do Compose): mesa (grid + contorno do perfil), modelos (normal de face por `dFdx/dFdy` no fragment shader, sobre a malha indexada, highlight de seleção, vermelho fora da mesa), gizmo de transformação.
- `PreviewRenderer`: toolpaths como triângulos extrudados por GPU (geometry via instancing de segmentos) com corte por intervalo de camada/movimento via uniforms, sem rebuild de VBO ao arrastar a barra.
- Câmera orbital; picking por ray-cast em CPU sobre os triângulos (Kotlin, `render/Picking.kt`) — suficiente para um toque.
- Gestos interpretados na thread GL; o arraste de um objeto altera só uma matriz provisória no
  renderer e é gravado no motor ao soltar (o motor fica livre durante o gesto).

### 3.4 Persistência
- Presets de usuário: arquivos JSON no formato do Orca em `filesDir/user/{machine,filament,process}/` (compatível com import/export desktop).
- Preferências do app: DataStore.
- Projetos: 3MF via `engine_save_3mf`.

## 4. Testes
- Paridade (RNF-05): `scripts/test-device.sh` fatia os casos de `testdata/cases.tsv` com `native/tests/slice_cli` no device via `adb` e compara camada a camada (`scripts/compare-gcode.py`) com o G-code do OrcaSlicer desktop gerado por `scripts/make-reference.sh`.
- Kotlin: testes instrumentados de `SlicerEngine` (`./gradlew :slicer-core:connectedDebugAndroidTest`) em tablet ou emulador. O build debug inclui x86_64 para o emulador; o release só arm64-v8a.

## 5. Ambiente de desenvolvimento
- SDK em `~/Android/Sdk` com NDK 28.2.13676358 e CMake 3.31.6 (`scripts/env.sh`).
- JDK: o daemon do Gradle usa JDK 21 (`gradle/gradle-daemon-jvm.properties`; o Gradle acha os JDKs
  de `~/.jdks`), independentemente do `JAVA_HOME`. Os scripts nativos usam o JDK 17.
- Python 3 para os scripts de teste; o módulo stubgen do Orca (Python 3.12) fica desligado.
- Disco: dependências + builds nativos (scripts e Gradle, duas ABIs) ocupam ~20 GB.
- 16 cores / 30 GB RAM: suficiente.
