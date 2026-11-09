// Ligação com a impressora (Klipper/Moonraker) pelo websocket do Moonraker.
//
// A macro TIMELAPSE_FOTO estaciona o cabeçote e soma 1 na variável "shots"; aqui assinamos essa
// variável e chamamos o callback a cada aumento. Assim a impressora não precisa de root nem de
// gcode_shell_command: quem procura a impressora é a placa.
#pragma once

#include <Arduino.h>
#include <functional>

class PrinterLink {
 public:
  using ShotCallback = std::function<void(uint32_t shots)>;
  // print_stats.state do Klipper: standby, printing, paused, complete, cancelled, error.
  // [initial]: valor lido ao (re)conectar, não uma mudança vista acontecer.
  using StateCallback = std::function<void(const String &state, bool initial)>;

  // host vazio = desligado. Pode ser chamado de novo para trocar de impressora.
  void begin(const String &host, uint16_t port, ShotCallback onShot, StateCallback onState);
  void loop();

  const String &host() const { return host_; }
  bool connected() const { return connected_; }
  // A macro existe e a assinatura foi aceita (Klipper pronto).
  bool subscribed() const { return subscribed_; }
  uint32_t shots() const { return shots_; }
  const String &printState() const { return printState_; }

 private:
  void subscribe();
  void handleMessage(const uint8_t *payload, size_t length);
  void updateShots(uint32_t value, bool initial);
  void updateState(const char *state, bool initial);

  String host_;
  ShotCallback onShot_;
  StateCallback onState_;
  String printState_;
  bool started_ = false;
  bool connected_ = false;
  bool subscribed_ = false;
  bool haveShots_ = false;
  uint32_t shots_ = 0;
  uint32_t nextId_ = 1;
  uint32_t subscribeId_ = 0;
};
