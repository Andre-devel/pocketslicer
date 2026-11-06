# Motores do slider — notas para discussão

Status: só anotação, nada implementado. Já funcionam: comunicação com o ESP32 e disparo da foto no time-lapse. Falta: movimentar os 2 motores.

## Hardware

- 2x 28BYJ-48 (5 V) com driver ULN2003: um move o carrinho (slider), o outro gira o celular (pan).
- Drivers nos 5 V da fonte USB (3,1 A), nunca no 3,3 V do ESP32. GND comum entre fonte, ESP32 e drivers.
- Fim de curso KW12 na ponta do motor do slider: COM no GND, NA num GPIO com `INPUT_PULLUP` (LOW quando aperta).

## Pinos sugeridos

| Função | GPIO |
|---|---|
| Slider IN1..IN4 | 16, 17, 18, 19 |
| Pan IN1..IN4 | 21, 22, 23, 25 |
| Fim de curso | 27 |

Não usar GPIO 6 a 11 (flash) nem 12 (atrapalha o boot). GPIO 34 a 39 são só entrada.

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
