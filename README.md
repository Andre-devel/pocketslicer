# Android Slicer

Fatiador 3D (FDM) para tablets Android, totalmente offline, construído sobre o núcleo
`libslic3r` do [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer).

- UI: Kotlin + Jetpack Compose + OpenGL ES 3
- Motor: `libslic3r` compilado com o NDK para `arm64-v8a`, acessado via JNI
- Perfis: os mesmos do OrcaSlicer (impressora / filamento / processo)
- Alvo: Android 10+ (API 29), tablets
- Licença: AGPL-3.0

## Documentação
- [Requisitos](docs/requisitos.md)
- [Arquitetura](docs/arquitetura.md)
- [Roadmap](docs/roadmap.md)

## Estado
Fase 1 (esqueleto do app) em andamento — ver [roadmap](docs/roadmap.md). Fase 0 aprovada no
emulador ([resultados](docs/fase0-resultados.md)).

## Build
Uma vez por ABI (≈1 h na primeira vez), as dependências nativas:

```bash
git submodule update --init
scripts/apply-patches.sh
scripts/build-deps.sh                     # arm64-v8a
ANDROID_ABI=x86_64 scripts/build-deps.sh  # emulador
```

Depois, o app (o Gradle compila libslic3r + JNI para as duas ABIs no build debug):

```bash
./gradlew :app:assembleDebug                          # app/build/outputs/apk/debug/
./gradlew :slicer-core:connectedDebugAndroidTest      # testes no aparelho/emulador
ANDROID_ABI=x86_64 scripts/build-native.sh slice_cli && ANDROID_ABI=x86_64 scripts/test-device.sh  # paridade
```

## Créditos
Baseado em OrcaSlicer (SoftFever), Bambu Studio (Bambu Lab), PrusaSlicer (Prusa Research)
e Slic3r (Alessandro Ranellucci e comunidade). Todos sob AGPL-3.0.
