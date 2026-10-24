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
Fase 0 (prova de conceito nativa) — ver roadmap.

## Créditos
Baseado em OrcaSlicer (SoftFever), Bambu Studio (Bambu Lab), PrusaSlicer (Prusa Research)
e Slic3r (Alessandro Ranellucci e comunidade). Todos sob AGPL-3.0.
