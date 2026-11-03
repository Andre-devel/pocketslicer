# Plano: tela dedicada da impressora (Pós-MVP item 8)

Pedido do usuário (2026-11-02): uma tela própria para cada impressora, com tudo o que dá para
fazer nela pela rede. Hoje o detalhe da impressora (`PrinterDetail.kt`) mostra câmera, progresso,
temperaturas, filamento e pausar/continuar/parar; ele fica ao lado da lista na aba Impressoras.

Impressoras: Creality K3 e K2, Snapmaker U1, Qidi Q2 (Moonraker) e Bambu A1 (MQTT + FTPS).

## Fonte dos comandos

Lista lida em 2026-11-02 de cada impressora com `GET /printer/gcode/help` (só leitura):
K3 344 comandos, K2 238, U1 220. A Q2 estava fora da rede e ainda falta. A A1 não tem lista;
para ela vale o que o app já usa e a documentação pública do MQTT da Bambu (OpenBambuAPI),
a conferir na impressora antes de cada recurso.

Regras para escolher comandos:
- Nunca expor comandos de fábrica ou depuração: `MOTOR_*`, `EEPROM_*`, `RFID_SET_*`,
  `RFID_TEST_*`, `*_TEST`, `INJECT_*`, `MCLIB_*`, `SET_TMC_*`, `SAVE_CONFIG`, `CXSAVE_CONFIG`,
  `FORCE_MOVE`, `SET_KINEMATIC_POSITION`. Podem estragar a calibração ou o firmware.
- Usar a macro de alto nível do fabricante (a que a tela da impressora usa), não as etapas
  internas (`BOX_LOAD_MATERIAL_HEATING`, `INNER_*`...).
- Parâmetros de cada macro conferidos no `configfile` da impressora
  (`/printer/objects/query?configfile=settings`) ou no app oficial antes de usar; nunca chutar.

## Arquitetura

- **Capacidades por impressora.** Na primeira conexão (e depois de cada `FIRMWARE_RESTART`) o app
  lê `/printer/gcode/help` e guarda o conjunto de comandos. Cada recurso da tela aparece só se os
  comandos dele existirem. Isso cobre diferença entre modelos (K2 sem `CHAMBER_MODE`) e
  versões de firmware sem lista fixa por modelo.
- **`Uploader` ganha os comandos.** `PrintAction` vira uma ação mais geral (`PrinterCommand`, selado),
  e cada protocolo traduz: Moonraker em `/printer/gcode/script` (ou o WebSocket 9999 da Creality,
  quando a tela dela usa esse caminho), Bambu em MQTT `print.*`/`system.*`. A tela só conhece as
  ações e as capacidades; não monta G-code.
- **Estado ao vivo por WebSocket no Moonraker** (`printer.objects.subscribe`) em vez do polling
  atual, para jog, temperaturas e console responderem sem atraso. A Bambu já manda o estado por MQTT.
- **Segurança.** Movimento e aquecimento pedem confirmação quando a impressora está imprimindo;
  jog, home e calibrações ficam bloqueados durante a impressão. Parada de emergência sempre visível.
  Comando que falha mostra a resposta da impressora (`RESPOND`/erro do Klipper).

## A tela

Rota própria (abre ao tocar na impressora na lista; na tela larga ocupa a área toda, com a lista
recolhível). Cabeçalho com nome, estado, IP, editar e parada de emergência. Abas:

1. **Visão geral** — o que existe hoje: câmera, progresso, temperaturas, filamento, alerta;
   pausar/continuar/parar; mais os ajustes ao vivo (seção 3) quando imprimindo.
2. **Controle** — jog XYZ (passos 0,1/1/10/50 mm), home, desligar motores, temperaturas com
   valores dos materiais, ventoinhas, luz.
3. **Filamento** — caixas e slots com carregar, descarregar, editar tipo/cor e reler RFID; secagem.
   Seleção de vários slots com a fila de operações (etapa 5).
4. **Arquivos** — arquivos na impressora com miniatura, imprimir de novo, apagar; histórico de
   impressões e totais.
5. **Calibração e manutenção** — calibrações da impressora e limpeza do bico.
6. **Console** — histórico (`/server/gcode_store`) e campo para mandar G-code, com aviso.

## Recursos por impressora

| Recurso | K3 | K2 | U1 | Q2 | A1 |
|---|---|---|---|---|---|
| Jog, home, motores | `G28`, `G1`, `M84` | idem | idem | a ler | `gcode_line` |
| Temperaturas | `SET_HEATER_TEMPERATURE`, `M141` câmara | idem, `M141` | `SET_HEATER_TEMPERATURE` | a ler | `gcode_line` |
| Ventoinhas | `M106`, `SET_CHAMBER_FAN` | `M106`, `SET_TEMPERATURE_FAN_*` | `SET_FAN_SPEED`, `M106` | a ler | `gcode_line M106 P1/2/3` |
| Luz | a descobrir (WS 9999 `lightSw`?) | idem | `SET_LED` | a ler | `system.ledctrl` |
| Velocidade/fluxo ao vivo | `M220`, `NOZZLE_FLOW_SET`, `SET_PRINT_MODE` | `M220`/`M221` | `M220`/`M221` | `M220`/`M221` | `print.print_speed` 1–4 |
| Babystep Z | `NOZZLE_PRINT_Z_OFFSET` | `SET_GCODE_OFFSET` | `SET_GCODE_OFFSET` | `SET_GCODE_OFFSET` | — |
| Cancelar objeto | `EXCLUDE_OBJECT` | idem | idem | a ler | `print.skip_objects` |
| Pausar numa camada | ação por camada no fatiamento | idem | `SET_PAUSE_AT_LAYER` ao vivo | a ler | — |
| Carregar/descarregar caixa | `BOX_LOAD_MATERIAL`/`BOX_QUIT_MATERIAL` | idem | `AUTO_FEEDING EXTRUDER=n`/`INNER_FILAMENT_UNLOAD` | macros da Box a ler | `print.ams_change_filament` |
| Externo | `LOAD_MATERIAL`/`QUIT_MATERIAL` | idem | `MANUAL_FEEDING` | a ler | `print.unload_filament` |
| Editar slot / RFID | `BOX_INFO_REFRESH`, WS `set` | idem | — | a ler | `print.ams_filament_setting` |
| Secagem | `CHAMBER_BOX_DRY ENABLE=` | — | — | a ler | — |
| Câmara | `CHAMBER_MODE`, persianas | — | — | a ler | — |
| Limpar bico | `NOZZLE_CLEAR` | `NOZZLE_CLEAR` | `ROUGHLY_CLEAN_NOZZLE` | a ler | — |
| Calibrações | `AUTOTUNE_SHAPERS`, `BED_MESH_CALIBRATE`, `NOZZLE_PID`, `BEDPID`, `XYZ_OFFSET_CALC` | `AUTOTUNE_SHAPERS`, `BED_MESH_CALIBRATE`, `BELT_TENSION` | `SHAPER_CALIBRATE`, `AUTO_BED_MESH_CALIBRATE`, `FLOW_CALIBRATE`, `XYZ_OFFSET_CALIBRATE_ALL`, `AUTO_SCREWS_TILT_ADJUST` | a ler | `print.calibration` |
| Timelapse | — | `M5001` | `TIMELAPSE_START/STOP` | a ler | `camera.ipcam_timelapse` |
| Arquivos/histórico | `/server/files`, `/server/history` | idem | idem | idem | FTPS (lista, apagar, timelapses) |
| Detecções (espaguete etc.) | — | — | — | — | `xcam_control_set` |

## Pendências antes de implementar cada parte

- **Parâmetros das macros da Creality.** `BOX_LOAD_MATERIAL` e `BOX_QUIT_MATERIAL` só chamam as
  etapas; o slot e a temperatura vêm do módulo `box` compilado. Descobrir como a tela da K2/K3 os
  dispara (parâmetros da macro ou `set` no WebSocket 9999), comparando com o repositório público
  `CrealityOfficial/K2_Series_Klipper` e com o tráfego do Creality Print. Idem para a luz.
- **U1:** `AUTO_FEEDING EXTRUDER=<cabeça>` resolve módulo/canal; conferir os demais parâmetros em
  `FEED_AUTO` (u1-klipper) e o que a tela da U1 manda.
- **Q2:** ler a lista de comandos e o `configfile` quando ela estiver na rede.
- **A1:** comandos MQTT em modo nuvem: o início de impressão é recusado fora do modo LAN; conferir
  quais comandos de controle a A1 aceita no modo atual antes de mostrá-los.
- **IP muda ao religar** e o usuário não tem acesso ao roteador: fazer a etapa 0 primeiro.

## Etapas

Cada etapa termina com teste no emulador (tablet e celular, `scripts/fake-moonraker.py` estendido
com os comandos novos) e conferência em pelo menos uma impressora real. Comando que move,
aquece ou carrega filamento numa impressora real só com OK do usuário a cada teste.

0. **Reencontrar a impressora quando o IP muda.** Guardar o hostname do Klipper (`/printer/info`)
   ou o serial da Bambu no `PrinterHost`; se o endereço salvo não responder, varrer a rede com o
   `PrinterScanner`, achar pela identidade e atualizar o endereço. Impressoras já salvas ganham a
   identidade na primeira conexão.
   - Feito (2026-11-02): a identidade é o MAC das interfaces em `/machine/system_info` (o hostname
     do Klipper só sem MAC) e o serial da Bambu (certificado do MQTT; `USN` do anúncio SSDP, a
     conferir numa A1 real). Endereço ou protocolo editado à mão apaga a identidade. A varredura
     roda no máximo uma vez por minuto, só com impressora salva sem resposta; duas achadas com a
     mesma identidade não mudam nada. Testado no emulador com o `fake-moonraker.py --mac`.
1. **Tela dedicada e capacidades.** Rota nova com cabeçalho e abas; Visão geral = o detalhe atual;
   leitura e cache de `/printer/gcode/help`; `PrinterCommand` no `Uploader`; parada de emergência
   (`/printer/emergency_stop`, recuperação com `FIRMWARE_RESTART`; Bambu `print.stop`).
   - Feito em parte (2026-11-02): a impressora aberta ocupa a área toda na tela larga, com a
     lista recolhível (o cadastro e a busca continuam lado a lado); voltar do sistema fecha a
     impressora na tela estreita. `PrinterCommand` (pausar, continuar, parar, parada de
     emergência, reiniciar o firmware) substitui `PrintAction`. A parada de emergência fica no
     cabeçalho, com confirmação; com o Klipper parado (`webhooks.state` "shutdown"/"error") a tela
     mostra a mensagem dele e "Reiniciar firmware". Testado no emulador com o fake-moonraker;
     falta numa impressora real.
   - Ficam para quando tiverem uso: as abas (aparecem com a segunda, Controle, na etapa 3) e a
     leitura de `/printer/gcode/help` (o primeiro recurso que depende dela também é da etapa 3).
     Bambu: sem pausar/parar ainda; a A1 precisa estar na rede para conferir o MQTT.
2. **Estado por WebSocket no Moonraker.** Substituir o polling da tela aberta; manter o polling da lista.
   - Feito (2026-11-02): com a tela da impressora aberta e o app visível, o estado vem de
     `printer.objects.subscribe` no `/websocket` do Moonraker (mesmos objetos da consulta, as
     mudanças juntadas campo a campo), e a consulta periódica pula essa impressora. A tela da
     Creality (alerta, impressão antes do Klipper) segue lida a cada 5 s. Klipper parado ou de
     volta: assina de novo; Klipper desconectado ou conexão caída (20 s sem nenhum quadro, com
     ping na metade): volta à consulta periódica e tenta o WebSocket de novo em 10 s. O cliente
     WebSocket da Creality virou o `WebSocketClient` (ping/pong, continuação, fechamento).
     Testado no emulador com o `fake-moonraker.py` (assinatura, parada de emergência, reinício,
     queda) e só leitura na K3 real.
3. **Controle.** Jog, home, motores, temperaturas, ventoinhas, luz; bloqueios durante a impressão.
   - Feito (2026-11-02), Moonraker: abas Visão geral e Controle (a segunda só quando a impressora
     tem o que mexer). O que aparece vem de `/printer/objects/list`, `/printer/gcode/help` e do
     `configfile`, lidos uma vez por impressora e de novo depois do reinício do firmware:
     aquecedores de `heaters.available_heaters` (`SET_HEATER_TEMPERATURE`, limite `max_temp`) e a
     câmara da K3 pelo `M141` (`heater_chamber`, até `target_max`); ventoinha `fan` (`M106`),
     `fan_generic` (`SET_FAN_SPEED`) e as da Creality (`output_pin fan0`.., quantas a macro
     `PRINTER_PARAM` diz: 0 peça, 2 auxiliar como o `M106 P2` do Orca, a de `case_fan_pins` do
     `heater_chamber` a da câmara; o `M106 P<n>` da macro); luzes `led`/`neopixel`/... por
     `SET_LED`. Jog relativo entre `SAVE_GCODE_STATE`/`RESTORE_GCODE_STATE` (XY 100 mm/s, Z 10
     mm/s), desligado nos eixos sem home; home de todos ou por eixo; `M84`. Os valores ao vivo
     (posição, eixos com home, ventoinhas, luzes) vêm da assinatura do WebSocket. Valores dos
     materiais dos perfis genéricos do Orca. Durante a impressão movimento, home e motores
     ficam bloqueados e mudar temperatura, ventoinha ou luz pede confirmação. A recusa do
     Klipper aparece por cima, no rodapé, sem mexer nos botões.
   - Testado no emulador (tablet e celular) com o `fake-moonraker.py`; na K3 só a leitura (sem
     comandos). Falta: a luz da K3 (não está no Klipper; talvez o WebSocket 9999), a Q2, a U1 e a
     K2 conferidas, e a Bambu (sem controle ainda).
4. **Ajustes ao vivo.** Velocidade, fluxo, modo de impressão, babystep Z, pausar numa camada (U1).
   - Feito (2026-11-02), Moonraker: seção Ajustes na Visão geral, imprimindo ou pausada, sem
     confirmação (mudar a impressão em curso é o propósito). Velocidade e fluxo por `M220`/`M221`
     (os mesmos que a tela da K3 manda, vistos no `klippy.log` dela; o `gcode/help` não os lista),
     com o valor atual do `gcode_move`. Modo de impressão da K3 por `SET_PRINT_MODE MODE=`, com os
     modos da seção `print_mode` do configfile (`<modo>_speed_factor`: standard, extreme, smooth,
     silent) e o atual no objeto `print_mode`. Babystep por `SET_GCODE_OFFSET Z_ADJUST= MOVE=1`
     (±0,01/0,05 mm), valor do `gcode_move.homing_origin`. Pausar na camada pela macro
     `SET_PAUSE_AT_LAYER` do Fluidd/Mainsail (a U1 a tem; estado em
     `gcode_macro SET_PRINT_STATS_INFO.pause_at_layer`), só camada que ainda não começou.
   - Testado no emulador (tablet e celular) com o `fake-moonraker.py --print-modes`; nada mandado a
     uma impressora real. Falta:
     - **Babystep na K3:** fica escondido. Ela tem `NOZZLE_PRINT_Z_OFFSET` ("Set or adjust global
       print-time nozzle Z offset") para os quatro bicos, com o valor em
       `nozzle_manager.print_z_adjust`; o módulo é compilado e os parâmetros não estão no
       configfile nem no log. O `SET_GCODE_OFFSET` provavelmente se perde na troca de bico.
     - **Fluxo na K3:** ela guarda o fluxo de cada bico em `nozzle_manager.print_flow`
       (`NOZZLE_FLOW_SET`); conferir numa impressão real se o `M221` muda o `extrude_factor` que o
       app mostra.
     - U1, K2 e Q2 fora da rede hoje: conferir os comandos nelas. A1: `print.print_speed` 1–4, a
       conferir com ela na rede.
5. **Filamento.** Carregar/descarregar por slot e externo, reler RFID, editar slot da Bambu,
   secagem da K3 (não tem caixa de secagem). Depende das pendências da Creality e da U1.
   - **Várias operações de uma vez (pedido do usuário).** Na tela da K3 é preciso esperar um
     descarregamento terminar para escolher o próximo. No app o usuário marca vários slots (ou
     cabeças) e toca uma vez; o app monta uma fila e manda uma operação por vez: manda a
     primeira, detecta que acabou, manda a seguinte. Vale para descarregar e carregar, em todas
     as impressoras com caixa ou várias cabeças (K3, K2, U1, Q2, A1).
   - A tela mostra a fila: qual está em andamento, quais faltam, quais terminaram. Dá para
     cancelar o que ainda não começou. Se uma falhar (erro da impressora, alerta, filamento
     preso), a fila para e mostra o erro; não segue para a próxima sozinha.
   - A fila fica no ViewModel/serviço, não na tela, para continuar com a tela fechada ou o app
     em segundo plano.
   - **Como saber que terminou** (conferir em cada impressora antes, sem chutar): no Moonraker,
     `POST /printer/gcode/script` só responde quando a macro acaba, e o `idle_timeout.state` volta
     de `Printing` para `Ready`; quando o comando vai pelo WebSocket 9999 da Creality, ele volta
     na hora, então é preciso olhar o estado do slot no objeto `box` (filamento saiu do bico) e o
     estado da impressora. Na Bambu, o `ams` do relatório MQTT (`tray_now`, estado da troca).
     Tempo máximo por operação, com aviso se passar.
   - Feito (2026-11-02), K3 (CFS pelo Moonraker): na Visão geral cada slot tem uma marca e o
     botão de reler o RFID (`BOX_INFO_REFRESH ADDR=<caixa> NUM=<A-D>`); Carregar e Descarregar
     põem os marcados na fila do ViewModel (`queueFilament`), que manda
     `BOX_LOAD_MATERIAL_STEP`/`BOX_QUIT_MATERIAL_STEP TNN=T<caixa><A-D>` (o que a tela da K3
     manda, visto no `klippy.log`) um por vez. Fim observado na K3 com o gravador do objeto `box`:
     `slot_status` da posição "running" até "done", `status` 2..4 na carga e 11..14 na descarga,
     100 no fim, ~100 s cada; o app consulta o `box` a cada 2 s e `err_status` ≠ 0 para a fila com
     o código. Limite de 8 min por operação. Só com a impressora livre. A fila mostra cada
     operação (aguardando, em andamento, concluído, falhou com o motivo), tira da fila as que não
     começaram, tenta de novo a que falhou e limpa.
   - Testado no emulador com `fake-moonraker.py --cfs` (o comando respondendo na hora e com
     `--cfs-blocking` só no fim; `--cfs-fail` para o erro). Falta:
     - Conferido na K3 real (2026-11-02, com OK do usuário): descarga e carga do T1A pelo app,
       ~75 s cada, a fila marcou as duas como concluídas no fim certo.
     - O que a K3 mostra num erro de verdade (`err_status`, `err_slot_status`) e se o comando
       `_STEP` responde no começo ou no fim.
     - Externo da K3 (`LOAD_MATERIAL`/`QUIT_MATERIAL`): o usuário não usa, fica de fora.
     - Secagem: a K3 do usuário não tem caixa de secagem (o `CHAMBER_BOX_DRY` do firmware fica
       sem uso).
     - K2 (mesmos comandos, a conferir), U1, Q2 e A1 quando estiverem na rede.
6. **Cancelar objeto.** Desenho da placa com os polígonos de `exclude_object` (Moonraker) ou dos
   objetos do 3MF (Bambu); tocar no objeto pede confirmação e cancela.
   - Feito (2026-11-02), Moonraker: seção Objetos na Visão geral durante a impressão, quando o
     Klipper tem o `exclude_object` e o G-code define os objetos (opção "Excluir objetos" do
     processo no Orca, ligada na maioria dos perfis da Creality). Mesa de cima (área pelo
     `toolhead.axis_minimum/maximum`, mínimo negativo cortado em 0) com o contorno de cada objeto,
     o atual em destaque e os cancelados apagados; a lista embaixo com Cancelar em cada um, para
     os pequenos. Tocar pede confirmação com o nome e manda `EXCLUDE_OBJECT NAME=`.
   - Testado no emulador com o `fake-moonraker.py` (três objetos durante a impressão). Falta:
     conferir numa impressão real da K3 (ela tem `EXCLUDE_OBJECT`; o objeto estava vazio sem
     impressão) e a Bambu (`print.skip_objects`).
7. **Arquivos e histórico.** Lista com miniatura, reimprimir, apagar (confirmação), histórico e totais;
   timelapses (U1, K2, A1) para baixar.
   - Feito (2026-11-02), Moonraker: aba Arquivos com os G-codes da raiz `gcodes`
     (`/server/files/list`, o mais novo primeiro, de 20 em 20). Miniatura, tempo estimado e gasto
     lidos do próprio arquivo por `Range` (64 KB do começo, 128 KB do fim), porque o Moonraker da
     K3 não reconhece o Orca e os metadados dele vêm sem miniatura nem tempo. Imprimir de novo usa
     a mesma confirmação do envio (`PrintConfirmDialog`), com o mapeamento pelos filamentos do
     rodapé (`filament used [g]`, `filament_colour`, `filament_type`) e o início de cada
     impressora (`Uploader.startFile`: CFS pelo WebSocket 9999, U1 com `MAP_TABLE`, QIDI); com
     bicos fixos a posição fora do bico só mostra o erro (o arquivo não dá para reordenar).
     Apagar (`DELETE /server/files/gcodes/...`) com confirmação, desligado no arquivo em
     impressão. Histórico (`/server/history/list`, as 50 últimas, e `/totals`) com o estado de
     cada uma e imprimir de novo se o arquivo ainda existe.
   - Testado no emulador com o `fake-moonraker.py --sample-files --cfs` (lista, miniaturas,
     reimprimir com o mapa do CFS, apagar, histórico) e só leitura na K3 real (28 arquivos com
     miniatura, tempo e gasto). Falta: reimprimir e apagar numa impressora real (pedem OK do
     usuário), timelapses (U1 e K2 fora da rede), a Bambu (FTPS).
   - **Timelapse da K3:** não é do Klipper nem do Moonraker (sem raiz `timelapse`). O WebSocket
     9999 informa `videoElapse` (0, provavelmente desligado), `videoElapseFrame` 15 e
     `videoElapseInterval` 1; `http://<ip>/downloads/video/` existe (pasta). Falta descobrir como
     o Creality Print lista e baixa os vídeos: gravar um timelapse curto na K3 e olhar só lendo.
8. **Calibração e manutenção.** Os comandos da tabela, um por vez, com o tempo estimado e o aviso
   de mesa livre; limpeza do bico; câmara da K3.
   - Feito (2026-11-02), Creality pelo Moonraker: aba Manutenção com as macros da K3 sem
     parâmetros (configfile lido nela): `G29` (nivelamento: aquece, `Z_TILT_ADJUST`, malha e
     `CXSAVE_CONFIG`, que grava sem reiniciar), `Z_AXIS_CALIBRATION` (o mesmo sem a malha),
     `INPUTSHAPER` (`SHAPER_CALIBRATE` em X, Y e Z e `CXSAVE_CONFIG`), `NOZZLE_PID` e `BEDPID`
     (230 e 100 °C e `SAVE_CONFIG`, que reinicia o Klipper) e `NOZZLE_CLEAR` (a tela da K3 manda
     esse, visto no `klippy.log`). Cada uma aparece se está no `gcode/help` e a impressora tem a
     macro `PRINTER_PARAM` da Creality. Uma por vez, com confirmação (aviso de mesa livre nas que
     descem o bico), só com a impressora livre e sem operação de filamento; roda no ViewModel
     (`runMaintenance`), então a parada de emergência segue livre e a tela pode fechar. O
     `/printer/gcode/script` responde no fim (limite de 60 min); depois do `SAVE_CONFIG` o
     Moonraker devolve 503 "Klippy Disconnected" (ou a conexão cai) e o app conta como concluída
     quando o Klipper volta "ready" em até 2 min. A tela mostra há quanto tempo roda e como
     terminou (tempo ou o erro do Klipper); sem tempo estimado, porque não há medida real ainda.
   - Testado no emulador com `fake-moonraker.py --calibration` (PID com o reinício e
     `--calibration-fail` para o erro); nada rodado numa impressora real. Falta:
     - Rodar cada uma na K3 com o OK do usuário e anotar o tempo (para mostrar a estimativa) e o
       que a resposta traz.
     - Calibração dos bicos da K3 (`XYZ_OFFSET_CALC`, módulo compilado; o `help` cita `MODE` 0/1/2
       e `WIPE`) e `BELT_TENSION`: ficam de fora até ver o que a tela da K3 manda.
     - Câmara da K3 (`CHAMBER_MODE MODE=off|auto|outer_cool|inner_fast_heat|inner_keep`): o
       significado de cada modo não está conferido; o alvo da câmara já está no Controle (`M141`).
     - K2 (os mesmos nomes, macros a conferir no configfile dela), U1 (`SHAPER_CALIBRATE`,
       `AUTO_BED_MESH_CALIBRATE`, `ROUGHLY_CLEAN_NOZZLE`...), Q2 e A1 (`print.calibration`).
9. **Console.** Histórico e envio de G-code livre, com a lista de comandos da impressora como sugestão.
10. **Notificações.** Fim de impressão e erro com o app em segundo plano (serviço com o WebSocket
    ou MQTT; avaliar bateria).
