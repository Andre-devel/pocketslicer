// Motores do slider (carrinho) e do pan (celular): 28BYJ-48 com ULN2003, ver ../motores.md.
//
// Carrinho em mm a partir da chave de fim de curso (0 a kTravelMm); só anda depois do homing.
// Pan em graus, positivo para +X da impressora, zero = posição ao ligar (ou "definir zero").
// Tudo sem bloquear: loop() precisa rodar sempre (websocket, página e BLE seguem no mesmo laço).
#pragma once

#include <Arduino.h>

class Motion {
 public:
  static constexpr float kTravelMm = 440;
  static constexpr float kPanLimitDeg = 25;

  void begin();
  void loop();

  // Procura a chave devagar, recua, volta mais devagar e define X = 0. Depois vai a [thenMm].
  void home(float thenMm = 0);
  // Leva o carrinho a [mm] e o pan a [deg] (limitados). Sem homing, o carrinho fica parado.
  // [approach]: chega em cada eixo vindo deste sentido (+1 ou -1, 0 = tanto faz), para tirar a
  // folga da redução antes de uma sequência que vai andar nesse sentido.
  void moveTo(float mm, float deg, int approachSlider = 0, int approachPan = 0);
  void stop();
  void setPanZero();

  bool homed() const { return homed_; }
  bool homing() const { return phase_ != Phase::Idle && phase_ != Phase::Moving; }
  // Algum motor andando (ou homing, ou chegada com pré-movimento pendente).
  bool busy() const { return phase_ != Phase::Idle; }
  float sliderMm() const;
  float panDeg() const;
  // Último erro (homing que não achou a chave); vazio se nenhum.
  const String &error() const { return error_; }
  bool endstopPressed() const { return endstopPressed_; }

 private:
  enum class Phase { Idle, Moving, HomeSeek, HomeBackoff, HomeSlow };

  void startMove();
  void readEndstop();
  void finishHoming();

  Phase phase_ = Phase::Idle;
  bool homed_ = false;
  bool endstopPressed_ = false;
  bool endstopRaw_ = false;
  uint32_t endstopChangedMs_ = 0;
  float afterHomeMm_ = 0;
  // Alvo final e pré-alvo (com folga) de cada eixo, em passos.
  long sliderTarget_ = 0, panTarget_ = 0;
  long sliderPre_ = 0, panPre_ = 0;
  bool preDone_ = true;
  String error_;
};
