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
│  SlicerEngine.kt  API Kotlin (suspend) ─┐                    │
│  jni/bridge.cpp   JNI ↔ C++             │                    │
│  jni/engine.cpp   fachada C++ sobre libslic3r               │
├──────────────────────────────────────────────────────────────┤
│ native (CMake, NDK, arm64-v8a)                               │
│  third_party/OrcaSlicer   submódulo em tag fixo              │
│  patches/                 patches aplicados sobre o submódulo│
│  deps/                    build cruzado das dependências     │
│  libslic3r.a + deps .a    → liblslicer_jni.so                │
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

### 2.5 Fachada C++ (`jni/engine.cpp`)
API intencionalmente pequena. Nada de expor classes do Orca para Kotlin.

```
Engine*  engine_create(const char* profiles_dir, const char* user_dir);
int      engine_load_model(Engine*, const char* path, ModelId* out);
void     engine_set_transform(Engine*, ModelId, const float mat4[16]);
int      engine_select_preset(Engine*, PresetKind, const char* name);
int      engine_set_option(Engine*, const char* key, const char* value_serialized);
int      engine_get_option(Engine*, const char* key, std::string& out);
int      engine_arrange(Engine*);
int      engine_slice(Engine*, ProgressCb, CancelFlag*);       // Print::process()
int      engine_export_gcode(Engine*, const char* path);
int      engine_get_toolpaths(Engine*, ToolpathBuffers* out);   // de GCodeProcessorResult
int      engine_save_3mf(Engine*, const char* path);
int      engine_list_presets(Engine*, PresetKind, std::vector<PresetInfo>& out);
int      engine_get_config_def(Engine*, std::string& json_out); // PrintConfigDef serializado
```

- Toolpaths saem como buffers planos (`float* positions`, `uint8* feature_type`, `float* width/height`, `int* layer_offsets`) para subir direto para VBOs sem cópia objeto-a-objeto.
- Malhas carregadas voltam para o Kotlin como `FloatBuffer` direto (vértices + normais) para render.
- Toda função retorna código de erro; mensagem via `engine_last_error()`. `bridge.cpp` converte em `SlicerException` Java.

### 2.6 Threading
- Uma `HandlerThread`/`Dispatchers` dedicada para chamadas nativas; o `Engine` **não** é thread-safe.
- `Print::process()` roda nessa thread; progresso via callback JNI → `StateFlow`; cancelamento via `Print::cancel()`.
- TBB usa seu pool interno; limitar threads ao número de cores *big* do SoC para não aquecer demais (configurável).

## 3. Camada Kotlin

### 3.1 Módulos Gradle
- `:app` — UI, navegação, injeção (Hilt ou Koin — decidir na fase 1; tendência Koin por simplicidade).
- `:slicer-core` — `SlicerEngine` (Kotlin), JNI, CMake. Sem dependência de Compose. Testável com instrumentação.
- `:profiles` — asset pack com `resources/profiles` do Orca comprimido + índice gerado em build (`vendors.json`).

### 3.2 Estado
- `SceneState` (objetos, transforms, seleção), `PresetState` (impressora/filamento/processo + overrides), `SliceState` (idle / running(progress) / done(result) / error).
- Tudo em `StateFlow`; Compose observa. Mudança em `SceneState`/`PresetState` invalida `SliceState`.

### 3.3 Render (OpenGL ES 3.0)
- `SceneRenderer`: mesa (grid + contorno do perfil), modelos (shader Phong simples, highlight de seleção, vermelho fora da mesa), gizmo de transformação.
- `PreviewRenderer`: toolpaths como triângulos extrudados por GPU (geometry via instancing de segmentos) com corte por intervalo de camada/movimento via uniforms, sem rebuild de VBO ao arrastar a barra.
- Câmera orbital; picking por ray-cast em CPU sobre a bounding box e depois triângulos (Kotlin) — suficiente para seleção.

### 3.4 Persistência
- Presets de usuário: arquivos JSON no formato do Orca em `filesDir/user/{machine,filament,process}/` (compatível com import/export desktop).
- Preferências do app: DataStore.
- Projetos: 3MF via `engine_save_3mf`.

## 4. Testes
- Nativo: `native/tests/` executável que roda na própria máquina (host build de `libslic3r`) e no device via `adb` — fatia modelos de `testdata/` e grava G-code.
- Paridade (RNF-05): script `scripts/parity.py` roda o OrcaSlicer desktop CLI (`--slice`) e a lib Android no device para os mesmos inputs e compara G-code normalizado.
- Kotlin: unit tests de ViewModels; testes instrumentados de `SlicerEngine` em tablet/emulador (emulador x86_64 **não** suportado no MVP — só device real arm64; considerar ABI x86_64 só se virar dor).

## 5. Ambiente de desenvolvimento (estado atual da máquina)
- SDK em `~/Android/Sdk` com platforms 36/37, build-tools 35–37, cmdline-tools. **Falta NDK e CMake do SDK**: `sdkmanager "ndk;27.2.12479018" "cmake;3.31.6"` (ou versões correntes).
- JDK: 17 e 21 disponíveis (`~/.jdks/corretto-17`, `corretto-21`); Gradle/AGP recentes exigem 17+. `java` padrão aponta para 8 — configurar `JAVA_HOME` no Gradle.
- Python 3.14 do sistema: o Orca exige 3.12 só para o módulo stubgen, que fica desligado.
- Disco: 16 GB livres. Build das deps + submódulo do Orca consome ~15–20 GB. **Liberar espaço antes da fase 0.**
- 16 cores / 30 GB RAM: suficiente.
