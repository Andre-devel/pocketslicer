// Plano do time-lapse que o app manda (POST /plano) e a sequência por camada durante a impressão.
//
// Ao começar a impressão (print_stats.state vira "printing"): homing e ida à posição da camada 0.
// A cada TIMELAPSE_FOTO: foto, pausa de pauseMs e ida à posição da próxima camada; o carrinho já
// chega parado na foto seguinte (motores.md, decisão 3).
#pragma once

#include <Arduino.h>
#include <functional>

#include "motion.h"

enum class PanMode { Follow, Fixed, Sweep };

// Mesmos campos do SliderConfig/SliderPlan do app. Distâncias em mm, ângulos em graus.
struct TimelapsePlan {
  int layers = 0;  // 0 = sem plano: só tira a foto, motores parados
  float start = 0, end = Motion::kTravelMm;
  PanMode panMode = PanMode::Fixed;
  float fixedAngle = 0, sweepStart = 0, sweepEnd = 0;
  // Objeto seguido (modo Follow), no sistema do trilho: X a partir do zero do trilho e distância
  // da linha do trilho até ele.
  float targetX = Motion::kTravelMm / 2, targetDistance = 510;
  int pauseMs = 300;
  // Idas e voltas (0 = só a ida) e camadas paradas em cada virada; ver slider_math.h.
  int passes = 0;
  int dwellLayers = 0;
  // Chave de fim de curso na ponta direita (montagem do app); main.cpp repassa ao Motion.
  bool switchRight = false;

  // [layer] do plano; fracionária na retomada (resumedLayer em slider_math.h).
  float fraction(float layer) const;
  float carriage(float layer) const;
  float pan(float layer) const;
  // Falso (com [error]) se o JSON não tem um plano válido.
  bool fromJson(const String &text, String &error);
  String toJson() const;
};

class Timelapse {
 public:
  using PhotoFn = std::function<bool()>;

  void begin(Motion &motion, PhotoFn takePhoto);
  void loop();

  // Plano novo do app (já salvo pelo chamador). O app manda depois de iniciar a impressão; se
  // ela já começou e a primeira foto ainda não saiu, o plano novo vale desde a camada 0.
  void setPlan(const TimelapsePlan &plan);
  const TimelapsePlan &plan() const { return plan_; }
  // [initial]: estado lido ao conectar: só registra, sem homing nem sequência; quem sabe a camada
  // atual é resync().
  void onPrintState(const String &state, bool initial);
  void onShot(uint32_t shots);
  // Camada atual da impressora (1 = primeira) lida ao (re)conectar. Depois de uma queda de rede
  // com camadas perdidas, o carrinho não pula: continua de onde parou e o resto do plano é
  // esticado até a última camada. Com a placa religada no meio (posição perdida), vai à camada
  // da impressora; resumeFrom() corrige depois.
  void resync(int currentLayer);
  // Carrinho volta à camada [layer] do plano (a "próxima foto" que a página mostrava antes) e
  // o resto do plano é esticado a partir dali. Falso fora de impressão ou com camada inválida.
  bool resumeFrom(int layer);

  // Camada da próxima foto (0 = primeira), ou -1 fora de impressão.
  int nextLayer() const { return printing_ ? nextLayer_ : -1; }

 private:
  // [layer] real (da impressão); a posição vem do plano por resumedLayer.
  void goToLayer(int layer);
  float planLayer(int layer) const;
  // Retomada a partir de agora: a camada real [nextLayer_] fica na camada [planFrom] do plano.
  void stretchFrom(float planFrom);
  // Homing e ida à camada 0 do plano (sai em loop() quando o homing acaba).
  void startPositioning();
  // Foto da camada atual e, depois de pauseMs, ida à próxima.
  void shootAndSchedule();

  Motion *motion_ = nullptr;
  PhotoFn takePhoto_;
  TimelapsePlan plan_;
  String state_;
  bool printing_ = false;
  int nextLayer_ = 0;
  // Retomada (ver resumedLayer): 0 e 0 = plano sem ajuste.
  float planFrom_ = 0;
  int realFrom_ = 0;
  // Foto pedida com o motor ainda andando: sai quando ele parar.
  bool photoPending_ = false;
  // Homing do início da impressão (ou da retomada) em andamento; ao terminar, vai a nextLayer_.
  bool positioning_ = false;
  // Hora de começar a ir à próxima camada (0 = nada agendado).
  uint32_t moveAtMs_ = 0;
};
