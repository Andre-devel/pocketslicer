// Ligação com a impressora (Klipper/Moonraker) pelo websocket do Moonraker.
//
// A macro TIMELAPSE_FOTO estaciona o cabeçote e soma 1 na variável "shots"; aqui assinamos essa
// variável e chamamos o callback a cada aumento. Assim a impressora não precisa de root nem de
// gcode_shell_command: quem procura a impressora é a placa.
//
// Sem a macro (Snapmaker U1 de fábrica, onde não dá para incluir slider_cam.cfg), o próprio G-code
// estaciona e manda "RESPOND PREFIX=TIMELAPSE_FOTO MSG=<camada>"; o Moonraker repassa a resposta
// a todos os clientes (notify_gcode_response) e cada uma conta como uma foto.
#pragma once

#include <Arduino.h>
#include <functional>

class PrinterLink {
 public:
  using ShotCallback = std::function<void(uint32_t shots)>;
  // print_stats.state do Klipper: standby, printing, paused, complete, cancelled, error.
  // [initial]: valor lido ao (re)conectar, não uma mudança vista acontecer.
  using StateCallback = std::function<void(const String &state, bool initial)>;
  // print_stats.info.current_layer (1 = primeira) lido ao (re)conectar, depois do estado. Só vem
  // quando o G-code usa SET_PRINT_STATS_INFO (perfil da Q2); serve para recuperar as camadas
  // perdidas com a conexão fora.
  using LayerCallback = std::function<void(int currentLayer)>;

  // host vazio = desligado. Pode ser chamado de novo para trocar de impressora.
  void begin(const String &host, uint16_t port, ShotCallback onShot, StateCallback onState, LayerCallback onLayer);
  void loop();
  // Foto disparada: grava "ack" na macro para a impressora não pausar (slider_cam.cfg, "Pausa
  // sem o slider"). Sem conexão não faz nada; a impressora pausa.
  void ack();

  const String &host() const { return host_; }
  bool connected() const { return connected_; }
  // Assinatura aceita (Klipper pronto): fotos pela macro ou por RESPOND.
  bool subscribed() const { return subscribed_; }
  // A macro TIMELAPSE_FOTO existe na impressora.
  bool hasMacro() const { return hasMacro_; }
  uint32_t shots() const { return shots_; }
  const String &printState() const { return printState_; }

 private:
  void subscribe();
  void handleMessage(const uint8_t *payload, size_t length);
  void updateShots(uint32_t value, bool initial);
  void updateState(const char *state, bool initial);
  // total_duration do print_stats: volta a zero numa impressão nova.
  void updateDuration(float seconds, bool initial);

  String host_;
  ShotCallback onShot_;
  StateCallback onState_;
  LayerCallback onLayer_;
  String printState_;
  float duration_ = 0;
  // Snapshot da reconexão com a duração menor que a última vista: outra impressão começou
  // enquanto a conexão estava fora, mesmo com o estado igual ("printing" antes e depois).
  bool restartedWhileAway_ = false;
  bool started_ = false;
  bool connected_ = false;
  bool subscribed_ = false;
  bool haveShots_ = false;
  bool hasMacro_ = false;
  // A macro tem a variável "ack" (slider_cam.cfg com a pausa sem o slider).
  bool macroHasAck_ = false;
  uint32_t shots_ = 0;
  uint32_t nextId_ = 1;
  uint32_t subscribeId_ = 0;
};
