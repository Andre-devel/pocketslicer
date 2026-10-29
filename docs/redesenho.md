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

## O que o design mostra e o app ainda não tem

Preparar
- Nome dos objetos ("base_headset") e dimensões em mm; hoje "Objeto N".
- Filamentos do projeto por objeto (lista "1 Hyper PLA Preto · 2 Hyper PLA Laranja").
- Projeto com alterações não salvas (ponto laranja e "Alterações não salvas").
- Vistas da câmera: Inicial, De cima, Frontal.
- Adicionar placa ("+" nas placas) e "Placa 1 de 3 ▾" como menu no celular.
- Etapas do fatiamento (preparando perfis, cortando camadas...) e "o fatiamento continua em
  segundo plano"; hoje só porcentagem e o texto do motor.
- Arquivos recentes (menu Arquivo e botão "Recentes" no estado vazio).

Visualizar
- Porcentagem de cada tipo de extrusão pelo tempo (hoje é pela fração do volume extrudado).
- Custo com moeda e preço por kg; consumo por filamento.
- Aviso de G-code desatualizado ("Você alterou … depois de fatiar") com "Fatiar de novo": hoje
  qualquer alteração descarta o fatiamento.
- "Fatiado há 2 min" no subtítulo; botão de reprodução dos movimentos.

Parâmetros
- Valor do perfil original ("Perfil: 2") e restaurar uma opção (↺); hoje só "Descartar
  alterações" do preset inteiro.
- Rótulo das categorias com a contagem de alterações.

Impressoras
- Estado ao vivo (Imprimindo 63%, Livre, Desligada, Pausada) e aviso "está imprimindo: dá para
  só enviar".
- Porta em campo separado (hoje no endereço, "192.168.0.41:7125").
- Resultado do teste com versão e latência ("Moonraker v0.9 · 38 ms").
- Validação do IP enquanto digita ("IP inválido. Use o formato 192.168.0.64.").
- Busca na rede começando sozinha ao abrir, com a sub-rede ("Procurando em 192.168.0.0/24…").

Envio
- Etapas com tempo (Conectando 0,4 s · Enviando 4,1 / 5,7 MB · Iniciando impressão); hoje
  texto e barra de progresso.

Estados (4a)
- Mensagens específicas: "Arquivo ilegível", "Memória insuficiente" com ação, "sem espaço no
  armazenamento", "Erro inesperado. Seu projeto foi preservado", "Aberto via Abrir com …
  Desfazer", "Projeto salvo com miniatura"; hoje os erros vão para o snackbar com o texto atual.
