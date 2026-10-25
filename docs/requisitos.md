# Requisitos — PocketSlicer

Fatiador 3D (FDM) para tablets Android, baseado no núcleo do OrcaSlicer (`libslic3r`),
100% offline. Nenhum servidor externo: todo o processamento ocorre no dispositivo.

## 1. Decisões fechadas

| Tema | Decisão |
|---|---|
| Motor de fatiamento | Port de `libslic3r` (OrcaSlicer) para Android via NDK, chamado por JNI |
| Licença | AGPL-3.0 (herdada do OrcaSlicer); repositório público |
| UI | Kotlin + Jetpack Compose; viewport 3D em OpenGL ES 3.x |
| Alvo | Android 10+ (API 29), ABI `arm64-v8a` apenas, layout para tablet (≥ 7") |
| Perfis | Reuso integral de `resources/profiles` do OrcaSlicer (impressora / filamento / processo) |
| Rede | Só na rede local, para enviar o G-code à impressora (docs/plano-envio.md); nada vai para a internet |

## 2. Escopo do MVP

### 2.1 Importação e preparo
- RF-01 Importar STL (binário e ASCII), 3MF (geometria + metadados Orca quando presentes) e OBJ via Storage Access Framework (`ACTION_OPEN_DOCUMENT`) e via "Abrir com" / compartilhamento de outros apps.
- RF-02 Exibir modelo(s) sobre a mesa da impressora selecionada, com forma/dimensões da mesa vindas do perfil.
- RF-03 Transformações por gesto e por campo numérico: mover (X/Y), rotacionar (X/Y/Z), escalar (uniforme e por eixo), deitar na mesa ("lay on face").
- RF-04 Múltiplos objetos na mesa; seleção por toque; duplicar; excluir.
- RF-05 Arranjo automático (libnest2d) e centralizar na mesa.
- RF-06 Aviso quando objeto está fora do volume de impressão.

### 2.2 Perfis e parâmetros
- RF-10 Seleção de impressora, filamento(s) e processo a partir dos perfis empacotados do Orca (com resolução da herança `inherits`).
- RF-11 Edição dos parâmetros mais usados em painel lateral (altura de camada, paredes, infill %, padrão de infill, suportes on/off e tipo, aderência: skirt/brim/raft, temperaturas, velocidades principais).
- RF-12 Modo "avançado" expondo todos os parâmetros do processo/filamento em lista pesquisável, gerados a partir das definições de `PrintConfigDef` (sem lista manual).
- RF-13 Salvar presets de usuário (impressora / filamento / processo) localmente; importar/exportar preset `.json` do Orca.
- RF-14 Perfis de usuário sobrevivem a atualizações do app; perfis de sistema são somente leitura.

### 2.3 Fatiamento
- RF-20 Fatiar em segundo plano (thread nativa) sem travar a UI; progresso e cancelamento.
- RF-21 Resultado idêntico ao OrcaSlicer desktop para mesmo modelo + perfis (teste de regressão por comparação de G-code, ver RNF-05).
- RF-22 Exibir estatísticas: tempo estimado, filamento (g / m / custo), número de camadas.
- RF-23 Relatar erros e avisos do fatiador (ex.: objeto fora da mesa, malha com erros) em linguagem clara.

### 2.4 Preview
- RF-30 Visualização 3D do G-code por camada com barra deslizante de camadas (vertical, estilo Orca) e por movimento dentro da camada (horizontal).
- RF-31 Colorir por tipo de feature (perímetro externo/interno, infill, suporte, etc.), velocidade, altura de camada, largura de linha, tipo de filamento.
- RF-32 Legenda com toggle por tipo de feature.
- RF-33 Alternar entre vista de preparo e preview sem refatiar quando nada mudou.

### 2.5 Exportação
- RF-40 Salvar G-code via SAF (o usuário escolhe pasta/arquivo) e compartilhar via `Intent.ACTION_SEND`.
- RF-41 Salvar projeto como 3MF (geometria + configuração + miniatura), reabrível no app e no OrcaSlicer desktop.
- RF-42 Miniatura PNG embutida no G-code segundo o perfil da impressora (Bambu, Klipper, Marlin).

### 2.6 UI / UX (tablet)
- RF-50 Layout em três regiões: barra de ferramentas superior, viewport 3D central, painel lateral de parâmetros recolhível.
- RF-51 Gestos no viewport: 1 dedo = rotacionar câmera, 2 dedos = pan/zoom, toque = selecionar, toque longo = menu de contexto do objeto.
- RF-52 Orientação paisagem como padrão; retrato suportado com painel em bottom sheet.
- RF-53 Tema claro/escuro seguindo o sistema.
- RF-54 Idiomas: pt-BR e en no MVP (strings do app; nomes de parâmetros podem vir em inglês do Orca inicialmente).

## 3. Fora do escopo do MVP (fases posteriores)
- Envio para impressora na LAN (Moonraker/Klipper, Bambu LAN mode, OctoPrint, PrusaLink).
- Pintura de suportes, pintura de seam, pintura multi-material.
- Modificadores (height range modifiers, volumes modificadores, negative volumes).
- Multi-material / AMS / MMU com torre de purga (fatiar com 1 extrusor no MVP).
- Importação STEP (exige OCCT — pesado; avaliar na fase 3).
- Texto 3D / SVG embutidos (exige FreeType/NanoSVG + mcut).
- Corte de modelo (cut tool), reparo de malha avançado.
- Calibração (torres de temperatura, flow, etc.) — pode reaproveitar o gerador do Orca depois.
- Câmera / monitoramento de impressão.

## 4. Requisitos não funcionais
- RNF-01 Offline total. Permissão `INTERNET` não declarada no MVP.
- RNF-02 Fatiamento de um modelo de 50 MB STL em perfil 0,2 mm padrão em ≤ 60 s num tablet mid-range (ex.: Snapdragon 7-series, 6 GB RAM). Meta, não bloqueio.
- RNF-03 Consumo de memória: limite de trabalho 1,5 GB durante o slice; degradar (avisar) acima disso em vez de crashar. Usar `largeHeap` só se necessário.
- RNF-04 Tamanho do APK ≤ 150 MB (biblioteca nativa + perfis). Perfis empacotados como asset comprimido; avaliar remover perfis de impressoras não selecionadas via Play Asset Delivery depois.
- RNF-05 Paridade com o Orca: suíte de testes que fatia modelos de referência com o CLI desktop e com a lib Android e compara G-code (ignorando cabeçalho/timestamps).
- RNF-06 Nenhum crash nativo não tratado: exceções C++ convertidas em exceções Java na fronteira JNI.
- RNF-07 Build reproduzível por script (`scripts/build-native.sh`) a partir de uma máquina Linux limpa com SDK + NDK.
- RNF-08 Código do app sob AGPL-3.0 com atribuição ao OrcaSlicer, Bambu Studio, PrusaSlicer e Slic3r; arquivo `NOTICE` listando licenças de todas as dependências nativas.

## 5. Riscos principais
| # | Risco | Impacto | Mitigação |
|---|---|---|---|
| R1 | Cross-compilar as ~30 dependências de `libslic3r` para Android (OCCT, OpenCV, OpenVDB, CGAL, TBB, Boost) | Alto — pode travar o projeto | Fase 0 é uma prova de conceito que compila `libslic3r` mínima com features pesadas desligadas/stubadas antes de escrever qualquer UI |
| R2 | `libslic3r` tem trechos `#ifdef` para Windows/macOS/Linux; Android cai em "Linux" mas sem fontconfig, DBus, etc. | Médio | Patches locais mantidos em `native/patches/`, aplicados por script sobre um submódulo fixo em um tag do Orca |
| R3 | Memória em tablets fracos | Médio | Medir cedo com modelos reais; expor configuração de resolução de slicing |
| R4 | Tamanho dos perfis (`resources/profiles`) | Baixo | Empacotar comprimido; carregar sob demanda |
| R5 | Atualizações do Orca quebrando os patches | Médio | Fixar tag; atualizar em ciclos planejados com a suíte de paridade (RNF-05) como gate |
| R6 | Pouca experiência da equipe com C++/NDK | Médio | Fronteira JNI mínima e bem documentada; toda a lógica de app fica em Kotlin; scripts de build prontos |

## 6. Perguntas em aberto
- Quais modelos de tablet estão disponíveis para teste? (define medições de RNF-02/03)
- Distribuição: Play Store, F-Droid, GitHub Releases, ou os três.
