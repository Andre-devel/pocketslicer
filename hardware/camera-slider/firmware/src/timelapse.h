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

  float fraction(int layer) const;
  float carriage(int layer) const;
  float pan(int layer) const;  // já limitado a ±Motion::kPanLimitDeg
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
  // [initial]: estado lido ao conectar (placa religada no meio da impressão): só registra, sem
  // homing nem sequência, porque a camada atual é desconhecida.
  void onPrintState(const String &state, bool initial);
  void onShot(uint32_t shots);

  // Camada da próxima foto (0 = primeira), ou -1 fora de impressão.
  int nextLayer() const { return printing_ ? nextLayer_ : -1; }

 private:
  void goToLayer(int layer);
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
  // Foto pedida com o motor ainda andando: sai quando ele parar.
  bool photoPending_ = false;
  // Homing do início da impressão em andamento; ao terminar, vai à camada 0.
  bool positioning_ = false;
  // Hora de começar a ir à próxima camada (0 = nada agendado).
  uint32_t moveAtMs_ = 0;
};
