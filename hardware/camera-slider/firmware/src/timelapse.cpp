#include "timelapse.h"

#include <ArduinoJson.h>

#include "slider_math.h"

namespace {

const char *panModeName(PanMode mode) {
  switch (mode) {
    case PanMode::Follow: return "FOLLOW";
    case PanMode::Sweep: return "SWEEP";
    default: return "FIXED";
  }
}

int sign(float v) { return v > 0 ? 1 : v < 0 ? -1 : 0; }

}  // namespace

float TimelapsePlan::fraction(int layer) const { return sliderFraction(layer, layers, passes, dwellLayers); }

float TimelapsePlan::carriage(int layer) const { return start + (end - start) * fraction(layer); }

float TimelapsePlan::pan(int layer) const {
  float f = fraction(layer);
  float deg;
  switch (panMode) {
    case PanMode::Follow: deg = degrees(atan2f(targetX - carriage(layer), targetDistance)); break;
    case PanMode::Sweep: deg = sweepStart + (sweepEnd - sweepStart) * f; break;
    default: deg = fixedAngle; break;
  }
  return constrain(deg, -Motion::kPanLimitDeg, Motion::kPanLimitDeg);
}

bool TimelapsePlan::fromJson(const String &text, String &error) {
  JsonDocument doc;
  if (deserializeJson(doc, text)) {
    error = "JSON inválido";
    return false;
  }
  TimelapsePlan p;
  p.layers = doc["layers"] | 0;
  p.start = doc["start"] | p.start;
  p.end = doc["end"] | p.end;
  String mode = doc["panMode"] | "FIXED";
  if (mode == "FOLLOW") p.panMode = PanMode::Follow;
  else if (mode == "SWEEP") p.panMode = PanMode::Sweep;
  else if (mode == "FIXED") p.panMode = PanMode::Fixed;
  else {
    error = "panMode desconhecido";
    return false;
  }
  p.fixedAngle = doc["fixedAngle"] | 0.0f;
  p.sweepStart = doc["sweepStart"] | 0.0f;
  p.sweepEnd = doc["sweepEnd"] | 0.0f;
  p.targetX = doc["targetX"] | p.targetX;
  p.targetDistance = doc["targetDistance"] | p.targetDistance;
  p.pauseMs = doc["pauseMs"] | p.pauseMs;
  p.passes = doc["passes"] | 0;
  p.dwellLayers = doc["dwellLayers"] | 0;
  if (p.layers < 0 || p.start < 0 || p.end < 0 || p.start > Motion::kTravelMm || p.end > Motion::kTravelMm ||
      p.targetDistance <= 0 || p.pauseMs < 0 || p.pauseMs > 10000 || p.passes < 0 || p.passes > 20 || p.dwellLayers < 0) {
    error = "valor fora da faixa";
    return false;
  }
  *this = p;
  return true;
}

String TimelapsePlan::toJson() const {
  JsonDocument doc;
  doc["layers"] = layers;
  doc["start"] = start;
  doc["end"] = end;
  doc["panMode"] = panModeName(panMode);
  doc["fixedAngle"] = fixedAngle;
  doc["sweepStart"] = sweepStart;
  doc["sweepEnd"] = sweepEnd;
  doc["targetX"] = targetX;
  doc["targetDistance"] = targetDistance;
  doc["pauseMs"] = pauseMs;
  doc["passes"] = passes;
  doc["dwellLayers"] = dwellLayers;
  String text;
  serializeJson(doc, text);
  return text;
}

void Timelapse::begin(Motion &motion, PhotoFn takePhoto) {
  motion_ = &motion;
  takePhoto_ = std::move(takePhoto);
}

void Timelapse::goToLayer(int layer) {
  if (plan_.layers <= 0) return;
  // Chega no sentido em que vai seguir depois desta camada: a folga da redução só aparece na
  // primeira chegada e em cada virada (ida e volta), não entre camadas do mesmo trecho.
  auto direction = [&](const std::function<float(int)> &value) {
    int ahead = 0;
    for (int next = layer + 1; next < plan_.layers && ahead == 0; next++) ahead = sign(value(next) - value(layer));
    if (ahead != 0) return ahead;
    return layer > 0 ? sign(value(layer) - value(layer - 1)) : 0;
  };
  int dirSlider = direction([&](int l) { return plan_.carriage(l); });
  int dirPan = direction([&](int l) { return plan_.pan(l); });
  motion_->moveTo(plan_.carriage(layer), plan_.pan(layer), dirSlider, dirPan);
}

void Timelapse::onPrintState(const String &state, bool initial) {
  String previous = state_;
  state_ = state;
  if (initial) {
    if (state != "printing" && state != "paused") printing_ = false;
    return;
  }
  if (state == "printing" && previous != "printing" && previous != "paused") {
    printing_ = true;
    nextLayer_ = 0;
    photoPending_ = false;
    moveAtMs_ = 0;
    Serial.printf("[time-lapse] impressão começou; plano com %d camadas\n", plan_.layers);
    positioning_ = false;
    if (plan_.layers > 0) startPositioning();
  } else if (state != "printing" && state != "paused" && printing_) {
    printing_ = false;
    positioning_ = false;
    moveAtMs_ = 0;
    photoPending_ = false;
    Serial.printf("[time-lapse] impressão terminou (%s)\n", state.c_str());
  }
}

void Timelapse::startPositioning() {
  positioning_ = true;
  motion_->home();
}

void Timelapse::setPlan(const TimelapsePlan &plan) {
  plan_ = plan;
  if (!printing_ || nextLayer_ > 0 || plan_.layers <= 0) return;
  // Impressão já começou com o plano anterior (ou sem plano): refaz a ida à camada 0.
  moveAtMs_ = 0;
  if (motion_->homing()) {
    positioning_ = true;  // ao terminar, loop() vai à camada 0 do plano novo
  } else if (motion_->homed()) {
    positioning_ = false;
    motion_->stop();
    goToLayer(0);
  } else {
    startPositioning();
  }
}

void Timelapse::onShot(uint32_t shots) {
  Serial.printf("[time-lapse] TIMELAPSE_FOTO #%u (camada %d)\n", shots, nextLayer_);
  if (!printing_ || plan_.layers <= 0) {
    // Teste pelo console, ou sem plano: só a foto.
    takePhoto_();
    return;
  }
  // Ida à camada anterior ainda agendada (pausa maior que o intervalo): vai agora.
  if (moveAtMs_) {
    moveAtMs_ = 0;
    goToLayer(nextLayer_);
  }
  if (motion_->busy()) {
    // Carrinho ainda andando (homing demorado ou camada muito rápida): foto quando parar, enquanto
    // a macro ainda espera; foto atrasada é melhor que borrada.
    Serial.println("[time-lapse] motor andando; foto quando parar");
    photoPending_ = true;
    return;
  }
  shootAndSchedule();
}

void Timelapse::shootAndSchedule() {
  takePhoto_();
  nextLayer_++;
  moveAtMs_ = millis() + plan_.pauseMs;
  if (moveAtMs_ == 0) moveAtMs_ = 1;
}

void Timelapse::loop() {
  if (positioning_ && !motion_->busy()) {
    positioning_ = false;
    // Homing falhou: segue só com as fotos (motion.error() aparece na página).
    if (motion_->homed()) goToLayer(0);
  }
  if (photoPending_ && !motion_->busy()) {
    photoPending_ = false;
    shootAndSchedule();
  }
  if (moveAtMs_ && int32_t(millis() - moveAtMs_) >= 0) {
    moveAtMs_ = 0;
    goToLayer(nextLayer_);
  }
}
