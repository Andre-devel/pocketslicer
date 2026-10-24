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
Fases 0 a 4 e itens 1–4 da fase 5 concluídos e verificados no emulador (ver
[roadmap](docs/roadmap.md)): viewport 3D com mover/girar/escalar/arranjar, perfis e parâmetros
do Orca com presets de usuário, fatiamento com pré-visualização do G-code, projetos 3MF,
miniaturas, tema claro/escuro e app em português e inglês. O G-code é equivalente ao do
OrcaSlicer 2.4.2 desktop nos casos de `testdata/cases.tsv`, conferidos pelo workflow de CI
`.github/workflows/parity.yml`. Falta testar num tablet arm64 físico.

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

## Licença e créditos
GNU AGPL-3.0 ([LICENSE](LICENSE)). Baseado em OrcaSlicer (SoftFever), Bambu Studio (Bambu Lab),
PrusaSlicer (Prusa Research) e Slic3r (Alessandro Ranellucci e comunidade), todos sob AGPL-3.0.
As bibliotecas de terceiros incluídas no APK e suas licenças estão em [NOTICE](NOTICE).
