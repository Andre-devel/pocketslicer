# Motores do slider — notas para discussão

Status (2026-11-09): código dos motores no firmware (`firmware/src/motion.*` e `timelapse.*`), compilado mas ainda não testado no slider montado. Já funcionavam antes: comunicação com o ESP32, disparo da foto no time-lapse e os motores e a chave em sketches de bancada.

## Firmware (2026-11-09, falta testar)

- `motion.*`: AccelStepper sem bloquear; homing (busca a 400 meios-passos/s, recua 2 mm, volta a 120/s); limites 0 a 440 mm e ±25°; pré-movimento de 2 mm / 3° para chegar sempre no sentido da sequência; bobinas desligadas com o motor parado; chave apertada andando para o zero corrige X para 0.
- `timelapse.*`: plano do app (`POST /plano`, salvo na placa). Quando `print_stats.state` muda para `printing` (e não volta de `paused`), faz homing e vai à camada 0 (carrinho e pan). O app manda o plano logo depois de a impressão ser aceita; se ele chega antes da primeira foto, a ida à camada 0 é refeita com ele. A cada TIMELAPSE_FOTO: foto, espera `pauseMs`, vai à próxima camada. Se o motor ainda estiver andando, a foto sai quando ele parar. Placa religada no meio da impressão: só tira fotos (camada desconhecida). Se a conexão cai e outra impressão começa antes de voltar, a duração (`print_stats.total_duration`) menor na reconexão faz o ESP32 tratar como impressão nova.
- Página: posição, homing, mover, parar, zero do pan. Serial: `h` = homing.
- Plano (JSON): `layers`, `start`, `end` (mm do trilho), `panMode` (`FOLLOW`/`FIXED`/`SWEEP`), `fixedAngle`, `sweepStart`, `sweepEnd`, `targetX` (mm a partir do zero do trilho), `targetDistance` (mm da linha do trilho até o objeto), `pauseMs`, `passes`, `dwellLayers`.
- Primeiro teste no slider montado: conferir o sentido de cada motor (`kSliderReversed`/`kPanReversed` em `motion.cpp`), o homing devagar com a mão perto da chave e a velocidade (`kSliderMaxSpeed` 700 meios-passos/s ≈ 6,9 mm/s). Risco: o Wi-Fi e o websocket rodam no mesmo laço que os passos; se o movimento engasgar, passar os motores para uma tarefa própria do FreeRTOS.

## Hardware

- 2x 28BYJ-48 (5 V) com driver ULN2003: um move o carrinho (slider), o outro gira o celular (pan).
- Drivers nos 5 V da fonte USB (3,1 A), nunca no 3,3 V do ESP32. GND comum entre fonte, ESP32 e drivers.
- Fim de curso KW12 na ponta do motor do slider: COM no GND, NA num GPIO com `INPUT_PULLUP` (LOW quando aperta).

## Ligações (confirmadas na bancada em 2026-11-07)

Rótulos impressos na placa ESP32 DevKit V1 (30 pinos, CP2102).

| Função | Pino na placa | GPIO | Lado da placa |
|---|---|---|---|
| Slider IN1 | RX2 | 16 | esquerdo (lado do 3V3) |
| Slider IN2 | TX2 | 17 | esquerdo |
| Slider IN3 | D18 | 18 | esquerdo (pula o D5) |
| Slider IN4 | D19 | 19 | esquerdo |
| Pan IN1 | D21 | 21 | esquerdo |
| Pan IN2 | D22 | 22 | esquerdo (pula RX0 e TX0) |
| Pan IN3 | D23 | 23 | esquerdo |
| Pan IN4 | D25 | 25 | direito (lado do VIN, entre D26 e D33) |
| Fim de curso (NO) | D27 | 27 | direito (entre D14 e D26) |
| Fim de curso (COM) | GND | — | qualquer GND |

Força:

- Drivers ULN2003: `+` no 5 V da fonte USB, `-` no GND da fonte, jumper de força colocado. O "5-12V" da plaquinha é a faixa do driver; os motores são de 5 V, então só 5 V.
- GND comum obrigatório: `-` da fonte ligado a um GND do ESP32. Na bancada o `-` de um driver foi num GND do ESP32 e o da fonte no outro (funciona; a corrente do motor passa pela placa). Na montagem final, levar os `-` dos dois drivers e o GND do ESP32 a um ponto comum (Wago/borne) ligado à fonte.
- Fim de curso: `NC` da chave sem ligar. O firmware usa `INPUT_PULLUP` (LOW = chave apertada).
  Testado na bancada em 2026-11-08 (KW12 de rolete: 1 = COM, 3 = NO, 2 = NC): leitura sem repique com filtro de 20 ms, e o slider não dá passo com a chave apertada.

Não usar RX0/TX0 (USB, gravação e serial), GPIO 6 a 11 (flash) nem D12 (atrapalha o boot). D34, D35, VP e VN são só entrada.

Teste de bancada: cada motor sozinho na velocidade normal (~1500 µs por meio-passo) antes de testar juntos. No primeiro teste conjunto o pan seguia o ritmo do carrinho a ~1 rpm e parecia parado (só os LEDs piscavam).

## Biblioteca e passos

- AccelStepper em `HALF4WIRE`. Ordem dos pinos no construtor: IN1, IN3, IN2, IN4.
- Redução real ≈ 63,68:1, então ~4076 meios-passos por volta do eixo (não 4096).
- Slider: polia GT2 20 dentes = 40 mm/volta, ~101,9 meios-passos/mm.
- Pan: direto no eixo, ~11,32 meios-passos/grau.
- Constante de inversão de sentido por motor, calibrada na primeira montagem.

## Limites e coordenadas

- Slider: X de 0 a 440 mm; X = 0 na chave. Limite por software em 0 e 440.
- Pan: 0° = celular perpendicular ao trilho, olhando a impressora. Limite por software ±25°.
- Pan sem chave: zero = posição ao ligar, mais comando "definir zero aqui".

## Velocidade

- Máximo prático ~15 rpm: ~10 mm/s no slider, ~90°/s no pan. Filmagem bem abaixo disso, sempre com aceleração e desaceleração.

## Homing do slider

- Devagar rumo a X = 0 até a chave fechar, recuar 2 mm, voltar mais devagar até fechar de novo, definir X = 0.
- Homing antes de cada impressão (sem realimentação, pode perder passo).

## Cuidados

- `disableOutputs` com o motor parado: o 28BYJ esquenta e a redução segura a posição.
- Folga da redução ~1 a 2°. No time-lapse andar sempre no mesmo sentido; no pan, inverter no meio do movimento mostra a folga no vídeo.
- Motores parados durante a foto: mover, esperar ~400 ms, só então disparar.

## Modos que o app vai configurar

- Por camada: carrinho anda de X_inicio a X_fim distribuído pelo total de camadas.
- Ida e volta (2026-11-09): `passes` idas e voltas ao longo da impressão, cada trecho com aceleração e desaceleração em cosseno, e `dwellLayers` camadas paradas em cada virada (no máximo metade da impressão). A mesma conta no app (`sliderFraction`) e no firmware (`slider_math.h`, teste no PC em `firmware/test/slider_math_test.cpp`). A folga da redução aparece só na primeira chegada e em cada virada.
- Pan "seguir": ângulo = atan2(X_peça - X_carrinho, Z_peça). A peça seguida é escolhida no app quando houver mais de uma; a posição vem do fatiamento. "Montagem" informa onde o trilho fica: distância do trilho ao centro da mesa (~510 mm) e deslocamento lateral (0 = trilho centrado).
- Pan "fixo" (ângulo constante) e "varredura" (ângulo inicial a final ao longo da impressão).

## Decisões (2026-11-06)

1. Total de camadas: o app já sabe (aparece na interface) e manda junto com a configuração.
2. Homing: o ESP32 faz sozinho quando `print_stats.state` virar `printing` (já escuta o websocket; sem macro nova nem mudança no G-code inicial). Confirmado.
3. Ordem por camada: foto, pausa curta para não borrar, e então move para a posição da próxima camada. O carrinho já chega parado na foto seguinte.
4. Pan "seguir" além de ±25°: para no limite (a peça sai um pouco do centro nas pontas) e a tela avisa.
5. Pré-movimento no sentido certo depois do homing/zero, para tirar a folga antes da primeira foto.
6. Configuração: o app manda para o ESP32 junto com o envio da impressão para a K2 (perfil de time-lapse).

## Tela no app

- Painel "Slider" no Visualizar; o seletor de camadas move carrinho e pan na cena.
- 3D nativo (renderizador OpenGL do app): G-code real até a camada, slider e celular simplificados, e um quadro com a visão do celular (≈ quadro do time-lapse).
- Design primeiro no Claude Design, depois porta para Compose.
- Referência visual: animação three.js "Slider Dimension Lab" (zip em ~/Downloads).
- Canvas do Claude Design: https://claude.ai/artifact/8JNjAVuWNEcW5ngxwEX6XW (1a tablet 1280×800, 1b celular 390×844; interativos).
- Aba "Slider" só com o perfil de time-lapse (confirmado).
- Suposições do canvas: aba "Slider" ao lado de "Resumo" no painel do Visualizar; pausa após a foto configurável (padrão 300 ms); trilho centrado na frente da mesa; lente ~220 mm acima do perfil; status do ESP32 (conectado / não encontrado) no topo do painel.
