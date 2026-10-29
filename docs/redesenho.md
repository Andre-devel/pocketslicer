# Redesenho da interface

Fonte: projeto "Redesenho da interface PocketSlicer" no Claude Design (`PocketSlicer.dc.html`),
2026-10-29. Grafite neutro, um único laranja de sinal (ação principal e seleção), vermelho só
para erro, números em mono. Material 3, alvos ≥ 48 dp.

## Estrutura

- `ui/theme`: `PocketSlicerTheme` (paleta fixa clara/escura, não usa as cores do papel de
  parede), `PocketTheme.colors` (sucesso, aviso, informação, eixos X/Y/Z, navegação),
  `AppTypography` (IBM Plex Sans) e `MonoStyles` (JetBrains Mono). Fontes variáveis em
  `res/font`, ícones Material Symbols Rounded como vetores em `res/drawable/ic_*.xml`.
- `ui/components`: peças sem dependência do ViewModel, para reaproveitar em telas novas:
  - navegação: `AppNavigationRail`, `AppNavigationBar`, `NavItem`, `AppMark`;
  - barra e superfícies: `ScreenHeader`, `BusyLine`, `SectionLabel`, `FloatingCard`,
    `InfoCard`, `Tag`, `CountBadge`, `StatusDot`, `MetricTile`, `EmptyState`, `PanelColumn`;
  - mensagens: `Banner` com `Tone` (erro, aviso, informação, sucesso);
  - botões: `AppButton` com `ButtonStyle` (um só PRIMARY por tela), `AppIconButton`,
    `ToolButton`/`ToolColumn`, `FloatingPill`;
  - campos: `CommitField` (aplica ao confirmar/perder o foco), `NumberField`, `AxisFields`,
    `StepperField`, `FormField`, `SelectField`;
  - seleção: `SegmentedTabs`/`Segment`, `PillTabs`, `SelectableCard`, `NavigationRow`,
    `LabeledRow`, `ColumnDivider`;
  - modificadores: `Modifier.vertical()` (slider vertical), `Modifier.blockTouches()`.
- Telas: `SlicerScreen` (navegação e área de trabalho), `PrepareScreen`, `PreviewScreen`,
  `ParametersScreen`, `PrintersScreen`, `SendDialog`. Preparar e Visualizar dividem o mesmo
  viewport 3D, sempre composto; Parâmetros e Impressoras são desenhados por cima.
- Larguras: navegação lateral a partir de 600 dp; painel lateral (e três colunas em
  Impressoras) a partir de 840 dp; abaixo disso, bottom sheet.

## Plano para o que falta

O que o design mostra e o app ainda não tem, em etapas. Cada item é um commit, verificado no
emulador do tablet (e no celular quando mexer em layout). Telas novas usam `ui/components`; peça
que faltar entra lá, sem depender do ViewModel.

### Decisões

1. Moeda do custo: campo em configurações (tela nova de Configurações, com a moeda; padrão pelo
   idioma, R$ em pt-BR). O Orca guarda `filament_cost` sem moeda.
2. Fatiar em segundo plano: fazer, com serviço em primeiro plano e notificação de progresso
   (permissão POST_NOTIFICATIONS no Android 13+).
3. G-code desatualizado: só "Fatiar de novo"; salvar e enviar ficam desabilitados até fatiar.
4. Placas: gerenciar placas em qualquer projeto (não só 3MF que já as tem).

### Etapa 1 — só app (sem mexer no motor)

1. (Feito) Vistas da câmera Inicial / De cima / Frontal: presets em `render/OrbitCamera.kt`
   (yaw/pitch fixos + `frame` na mesa), `ViewportView.setView(view)`, botões num `FloatingCard`
   no topo do viewport (Preparar e Visualizar).
2. (Feito) Projeto com alterações não salvas: `dirty` no `SlicerUiState`, ligado em cada edição de cena
   e de perfil, desligado ao abrir/salvar projeto; ponto laranja e "Alterações não salvas" no
   `ScreenHeader`.
3. (Feito) Arquivos recentes: guardar as URIs abertas (com `takePersistableUriPermission`) num JSON em
   `filesDir`, no máximo 5; seção "Recentes" no menu Arquivo e botão no estado vazio. URI que não
   abre mais sai da lista.
4. (Feito) "Fatiado há N min" no subtítulo de Visualizar (hora do fim do fatiamento no `SliceState.Done`).
5. (Feito) Impressoras: porta em campo separado (o `PrinterHost.address` continua "ip:porta"; o
   formulário divide e junta), validação do IP enquanto digita, busca na rede começando sozinha
   ao abrir a tela com a sub-rede no texto (expor `PrinterScanner.subnetHosts`).
6. (Feito) Teste de conexão com detalhe: Moonraker `/server/info` (versão) e tempo de resposta;
   resultado "Conectada · Moonraker v0.9 · 38 ms".
7. (Feito) Etapas do envio: `SendState.Working` com etapa (conectar / enviar / iniciar), bytes enviados
   e tempo de cada uma; lista com ícones como no design.
8. (Feito, menos "Abrir com … Desfazer") Mensagens de estado (4a): mapear os erros do ViewModel para `Banner` com ação (Escolher
   outro, Tentar de novo, Detalhes), "Aberto via Abrir com … Desfazer" e "Projeto salvo com
   miniatura" no snackbar.

### Etapa 2 — motor (engine.cpp, jni_bridge.cpp, SlicerEngine.kt)

9. Nome e dimensões dos objetos: `ModelObject::name` e a caixa da instância já transformada
   (mm) em `nativeObjects`/`nativePlacements`; mostrar no painel e no sheet.
10. Filamentos do projeto: presets de filamento por extrusor e quais cada objeto usa (já há
    `ObjectMesh.filaments`); lista "1 · PLA Preto, 2 · PLA Laranja" no Perfil.
11. Resumo por filamento e custo: `print_statistics.total_volumes_per_extruder` → gramas e custo
    por filamento; preço por kg do preset; moeda da tela de Configurações (decisão 1), que entra
    como destino novo na navegação ou no menu da marca.
12. Tempo por tipo de extrusão: `print_statistics.modes[0].roles_times` no `SliceStats`; a
    legenda passa a mostrar a fração do tempo (hoje é volume).
13. Valor do perfil e restaurar uma opção: valores do preset salvo (`PresetCollection` sem as
    edições) em `nativeSavedValuesJson`, e `nativeResetOption(kind, key)`; "Perfil: X" e ↺ em
    cada opção alterada, contagem de alterações por categoria.
14. Etapas do fatiamento: agrupar o progresso do `Print` (posPerimeters, posInfill,
    posSupportMaterial, gcode export) em 4 etapas com ✓; camada atual quando o motor informar.
    Segundo plano (decisão 2): o fatiamento roda num serviço em primeiro plano com notificação de
    progresso; ao terminar com o app fechado, a notificação abre Visualizar.

### Etapa 3 — comportamento novo

15. Estado ao vivo das impressoras: Moonraker `print_stats` (+ `virtual_sdcard.progress`) e
    Bambu MQTT `pushall` (report com `gcode_state`, `mc_percent`), consultados enquanto
    Impressoras ou o diálogo de envio estão abertos (a cada ~5 s, parar ao sair). Ponto colorido
    na lista, aviso "está imprimindo: dá para só enviar" e "Enviar e imprimir" desabilitado.
16. G-code desatualizado: guardar o último fatiamento ao alterar cena/perfil, marcá-lo
    desatualizado com o nome da alteração e mostrar o aviso com "Fatiar de novo"; salvar e enviar
    desabilitados até fatiar de novo (decisão 3).
17. Placas (decisão 4), no motor e na interface:
    - o motor mantém a lista de placas (PartPlateList do Orca) também para STL/OBJ e projetos
      novos, com uma placa ao começar;
    - criar placa vazia ("+" nas placas) e excluir placa (os objetos dela vão para outra ou são
      excluídos, com confirmação);
    - mover o objeto selecionado para outra placa (ação no painel e no menu do toque longo);
    - Arranjar e Fatiar agem na placa escolhida;
    - salvar o 3MF com as placas, conferindo que o OrcaSlicer 2.4.2 desktop abre igual;
    - no celular, "Placa 1 de 3 ▾" como menu.
18. Reproduzir movimentos: botão ▶ na barra de movimentos que avança o slider até o fim da
    camada.
