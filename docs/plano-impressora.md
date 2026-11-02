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
4. **Ajustes ao vivo.** Velocidade, fluxo, modo de impressão, babystep Z, pausar numa camada (U1).
5. **Filamento.** Carregar/descarregar por slot e externo, reler RFID, editar slot da Bambu,
   secagem da K3. Depende das pendências da Creality e da U1.
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
6. **Cancelar objeto.** Desenho da placa com os polígonos de `exclude_object` (Moonraker) ou dos
   objetos do 3MF (Bambu); tocar no objeto pede confirmação e cancela.
7. **Arquivos e histórico.** Lista com miniatura, reimprimir, apagar (confirmação), histórico e totais;
   timelapses (U1, K2, A1) para baixar.
8. **Calibração e manutenção.** Os comandos da tabela, um por vez, com o tempo estimado e o aviso
   de mesa livre; limpeza do bico; câmara da K3.
9. **Console.** Histórico e envio de G-code livre, com a lista de comandos da impressora como sugestão.
10. **Notificações.** Fim de impressão e erro com o app em segundo plano (serviço com o WebSocket
    ou MQTT; avaliar bateria).
