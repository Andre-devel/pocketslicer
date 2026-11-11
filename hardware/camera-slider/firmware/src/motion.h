// Motores do slider (carrinho) e do pan (celular): 28BYJ-48 com ULN2003, ver ../motores.md.
//
// Carrinho em mm a partir da ponta esquerda do curso, olhando a impressora por trás do slider
// (0 a kTravelMm, mesmo sentido do X da impressora); só anda depois do homing. Com a chave de fim
// de curso na ponta direita (setSwitchRight), a conversão para passos fica toda aqui.
// Pan em graus, positivo para +X da impressora, zero = posição ao ligar (ou "definir zero").
// Os passos saem de uma tarefa própria do FreeRTOS (begin() a cria): o laço principal pode travar
// segundos (reconexão do websocket com a impressora desligada) sem parar os motores. Todos os
// métodos são seguros entre as duas tarefas.
#pragma once

#include <Arduino.h>

class Motion {
 public:
  static constexpr float kTravelMm = 440;

  void begin();

  // Procura a chave devagar, recua, volta mais devagar e define X = 0 (fica parado ali).
  void home();
  // Leva o carrinho a [mm] e o pan a [deg] (carrinho limitado ao curso). Sem homing, o carrinho fica parado.
  // [approach]: chega em cada eixo vindo deste sentido (+1 ou -1, 0 = tanto faz), para tirar a
  // folga da redução antes de uma sequência que vai andar nesse sentido.
  void moveTo(float mm, float deg, int approachSlider = 0, int approachPan = 0);
  void stop();
  void setPanZero();
  // Chave de fim de curso na ponta direita: o zero dos passos fica em kTravelMm.
  void setSwitchRight(bool right);
  bool switchRight() const;

  bool homed() const;
  bool homing() const;
  // Algum motor andando (ou homing, ou chegada com pré-movimento pendente).
  bool busy() const;
  float sliderMm() const;
  float panDeg() const;
  // Último erro (homing que não achou a chave); vazio se nenhum.
  String error() const;
  bool endstopPressed() const;

 private:
  enum class Phase { Idle, Moving, HomeSeek, HomeBackoff, HomeSlow };

  static void taskMain(void *self);
  // Um passo da máquina de estados; roda só na tarefa dos motores.
  void tick();
  // mm a partir da esquerda <-> mm a partir da chave (a conversão é a mesma nos dois sentidos).
  float fromSwitch(float mm) const { return switchRight_ ? kTravelMm - mm : mm; }
  bool homingLocked() const { return phase_ != Phase::Idle && phase_ != Phase::Moving; }
  void startMove();
  void readEndstop();
  void finishHoming();

  Phase phase_ = Phase::Idle;
  bool homed_ = false;
  bool endstopPressed_ = false;
  bool endstopRaw_ = false;
  uint32_t endstopChangedMs_ = 0;
  // Alvo final e pré-alvo (com folga) de cada eixo, em passos.
  long sliderTarget_ = 0, panTarget_ = 0;
  long sliderPre_ = 0, panPre_ = 0;
  bool preDone_ = true;
  // Trecho em andamento (de onde saiu e para onde vai, em passos) e qual eixo dita a proporção.
  long sliderFrom_ = 0, panFrom_ = 0, sliderGoal_ = 0, panGoal_ = 0;
  bool sliderLeads_ = true;
  bool switchRight_ = false;
  String error_;
};
