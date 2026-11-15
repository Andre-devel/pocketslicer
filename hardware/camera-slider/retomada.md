# Retomada do time-lapse depois de queda de rede

O que fazer quando a impressora (ou o slider) perde a rede no meio de uma impressão com o slider.
Escrito para quem grava a placa pelo notebook — inclusive o Claude Code: os passos estão na ordem
em que devem ser executados, com o resultado esperado de cada um.

## O que o firmware faz

- **Queda de rede com a placa ligada:** ao reconectar, o slider lê a camada atual da impressora
  (`print_stats.info.current_layer`, que o perfil da Q2 grava com `SET_PRINT_STATS_INFO`). Se
  faltaram fotos, o carrinho **não pula**: continua da posição em que parou e o resto do plano é
  esticado para terminar no fim planejado na última camada (`resumedLayer` em
  `firmware/src/slider_math.h`). A câmera anda um pouco mais rápido dali em diante, sem salto.
- **Placa religada no meio da impressão** (por exemplo, depois de gravar o firmware): a posição em
  que ela estava se perdeu. Ela faz o homing e vai à posição da camada atual da impressora.
  Para continuar de onde parou, mande `GET /retomar?camada=<N>`, em que N é a "próxima foto" que a
  página mostrava antes (campo `layer` de `GET /status`).
- As camadas sem foto não voltam: durante a queda a macro estacionou o cabeçote, mas ninguém
  disparou o celular. No vídeo fica um salto da peça, que dá para preencher na edição (IA com
  quadro inicial e final: como a câmera não pula, só a peça precisa crescer).

Por que a conexão não voltava: `WEBSOCKETS_TCP_TIMEOUT` estava em 500 ms e vale para cada leitura
de mensagem. Com o Wi-Fi da Q2 lento depois da queda, mensagens chegavam picadas e a biblioteca
fechava com código 1002 a cada reconexão (visto no `moonraker.log`: "Close Code: 1002" do
`arduino-WebSocket-Client` a cada 5–15 s). Agora é 2000 ms.

## Pausa automática sem o slider

Com o `klipper/slider_cam.cfg` novo na impressora, ela pausa sozinha quando o slider para de
responder: a cada foto disparada o ESP32 grava o número dela na variável `ack` da macro, e se uma
foto fica `ack_timeout` (10 s) sem confirmação, `TIMELAPSE_FOTO_CHECK` dá `PAUSE` com a mensagem
"slider sem resposta" no console. Perde-se uma foto em vez de dezenas. Vale para rede caída, placa
desligada e celular desconectado do Bluetooth. Ao voltar, `RESUME`; o slider continua de onde parou.

- Só pausa se o slider já respondeu nesta impressão: imprimir com o perfil do slider e a placa
  desligada desde o início não pausa.
- `variable_pause_on_miss: 0` desliga; `variable_ack_timeout` muda o tempo.
- Firmware novo com o `.cfg` antigo não confirma (não existe `ack`); o `.cfg` antigo não pausa.

**Instalar na impressora só entre impressões:** copiar `klipper/slider_cam.cfg` (e o
`slider_cam_q2.cfg`, sem mudanças) para a pasta de configuração (Fluidd/Mainsail → Configuração,
ou `http://<IP>:7125` pelo upload de arquivos) e dar `FIRMWARE_RESTART`. **O restart cancela a
impressão em andamento.**

## Gravar a placa pelo notebook

1. Atualizar o repositório:
   ```
   git pull
   ```
2. Conferir o `firmware/include/secrets.h` (fica fora do git). Se não existir, copiar de
   `secrets.h.example` e preencher `WIFI_SSID` e `WIFI_PASSWORD` com a rede de casa. **Sem ele a
   placa sobe com a rede própria "Slider-Cam" e não acha a impressora.** Claude: pergunte a rede e
   a senha ao usuário; não invente.
3. PlatformIO: `pio --version`. Se não tiver, `pip install platformio` (ou a extensão PlatformIO
   do VS Code, que traz o `pio`).
4. Teste da conta do carrinho no PC (opcional, precisa de g++):
   ```
   cd hardware/camera-slider/firmware
   g++ -std=c++17 -I src test/slider_math_test.cpp -o slider_math_test && ./slider_math_test
   ```
   Esperado: `ok`.
5. Ligar o ESP32 no notebook pelo USB e gravar (a gravação não apaga o plano, o IP da impressora
   nem o pareamento Bluetooth, que ficam na NVS):
   ```
   cd hardware/camera-slider/firmware
   pio run -t upload
   ```
   Se não achar a porta, `pio device list` e `pio run -t upload --upload-port COMx`. Se travar em
   "Connecting...", segurar o botão BOOT da placa até começar a gravar.
6. Acompanhar a serial:
   ```
   pio device monitor
   ```
   Esperado, em sequência:
   - `[wifi] conectado, IP ...`
   - `[impressora] conectado a <IP da impressora>`
   - `[time-lapse] impressão em andamento na camada <C>; indo à posição dela ...`
   - `[impressora] macro TIMELAPSE_FOTO encontrada (shots=...)`
   e **não** pode aparecer `[impressora] desconectado` a cada poucos segundos.
7. Devolver o carrinho para onde parou (troque N e o endereço; o navegador também serve):
   ```
   curl "http://slider-cam.local/retomar?camada=N"
   ```
   Esperado: `{"ok":true}` e na serial `[time-lapse] retomada: camada real ... na camada N do
   plano, resto esticado`. O carrinho volta para a posição em que estava antes da queda.
8. Conferir `curl http://slider-cam.local/status`: `printer.connected` e `printer.subscribed`
   `true`, e `ble` `true` (se não, abrir o Bluetooth do celular e conectar "Slider Cam"; o app da
   câmera tem que estar aberto).
9. Só então retomar a impressão na impressora.

## Esta impressão (Q2, 15/11/2026)

- Impressora pausada na camada 586 de 815; o slider tinha tirado 442 fotos (página mostrava
  "próxima foto: camada 443", `layer` = 442). Camadas 443 a 585 ficaram sem foto (~143).
- No passo 7: `curl "http://slider-cam.local/retomar?camada=442"`. O carrinho volta a ~119,5 mm e
  pan ~1,3°, e as 229 camadas restantes levam o carrinho até 220 mm e o pan até −16°, como no
  plano.
