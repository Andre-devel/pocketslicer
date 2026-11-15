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

float TimelapsePlan::fraction(float layer) const { return sliderFraction(layer, layers, passes, dwellLayers); }

float TimelapsePlan::carriage(float layer) const { return start + (end - start) * fraction(layer); }

float TimelapsePlan::pan(float layer) const {
  float f = fraction(layer);
  switch (panMode) {
    case PanMode::Follow: return degrees(atan2f(targetX - carriage(layer), targetDistance));
    case PanMode::Sweep: return sweepStart + (sweepEnd - sweepStart) * f;
    default: return fixedAngle;
  }
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
  p.switchRight = doc["switchRight"] | false;
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
  doc["switchRight"] = switchRight;
  String text;
  serializeJson(doc, text);
  return text;
}

void Timelapse::begin(Motion &motion, PhotoFn takePhoto) {
  motion_ = &motion;
  takePhoto_ = std::move(takePhoto);
}

float Timelapse::planLayer(int layer) const { return resumedLayer(layer, plan_.layers, planFrom_, realFrom_); }

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
  int dirSlider = direction([&](int l) { return plan_.carriage(planLayer(l)); });
  int dirPan = direction([&](int l) { return plan_.pan(planLayer(l)); });
  float at = planLayer(layer);
  motion_->moveTo(plan_.carriage(at), plan_.pan(at), dirSlider, dirPan);
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
    planFrom_ = 0;
    realFrom_ = 0;
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

void Timelapse::resync(int currentLayer) {
  if (state_ != "printing" && state_ != "paused") return;
  if (plan_.layers <= 0 || currentLayer <= 0) return;
  // A foto da camada N sai na troca para a N+1: com current_layer = N, a próxima é a de índice
  // N - 1. Um a mais ou a menos não aparece no vídeo; dezenas de camadas perdidas aparecem.
  int target = constrain(currentLayer - 1, 0, plan_.layers - 1);
  if (printing_ && abs(target - nextLayer_) <= 1) return;
  if (printing_) {
    // Queda de rede com a placa ligada: o carrinho está na posição de nextLayer_ e fica lá.
    float planFrom = planLayer(nextLayer_);
    Serial.printf("[time-lapse] %d camada(s) sem foto (impressora na %d); carrinho continua da camada %.1f do plano, "
                  "resto esticado\n", target - nextLayer_, currentLayer, planFrom);
    nextLayer_ = target;
    stretchFrom(planFrom);
    return;
  }
  // Placa religada no meio: a posição do plano em que estava se perdeu; vai à camada da
  // impressora (GET /retomar?camada=N volta para onde parou).
  Serial.printf("[time-lapse] impressão em andamento na camada %d; indo à posição dela "
                "(GET /retomar?camada=N continua de onde parou)\n", currentLayer);
  printing_ = true;
  nextLayer_ = target;
  planFrom_ = 0;
  realFrom_ = 0;
  moveAtMs_ = 0;
  photoPending_ = false;
  if (motion_->homing() || !motion_->homed()) {
    startPositioning();  // ao terminar o homing, loop() vai a nextLayer_
  } else if (!motion_->busy()) {
    goToLayer(nextLayer_);
  }
}

bool Timelapse::resumeFrom(int layer) {
  if (!printing_ || plan_.layers <= 0 || layer < 0 || layer >= plan_.layers) return false;
  Serial.printf("[time-lapse] retomada: camada real %d na camada %d do plano, resto esticado\n", nextLayer_, layer);
  stretchFrom(layer);
  return true;
}

void Timelapse::stretchFrom(float planFrom) {
  planFrom_ = planFrom;
  realFrom_ = nextLayer_;
  moveAtMs_ = 0;
  photoPending_ = false;
  if (motion_->homing() || !motion_->homed()) {
    startPositioning();
  } else {
    // Mesmo lugar depois de uma queda (não anda); outro com /retomar.
    positioning_ = false;
    motion_->stop();
    goToLayer(nextLayer_);
  }
}

void Timelapse::startPositioning() {
  // loop() vai à camada 0 quando o motor parar. Com homing já feito (o app manda antes de enviar
  // o G-code) ou em andamento, não refaz: perder passo no caminho para a chave é corrigido no
  // Moving, e repetir o homing atrasaria as primeiras fotos.
  positioning_ = true;
  if (!motion_->homed() && !motion_->homing()) motion_->home();
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
    // Homing falhou: segue só com as fotos (motion.error() aparece na página). nextLayer_ é 0 no
    // começo da impressão e a camada da impressora depois de resync().
    if (motion_->homed()) goToLayer(nextLayer_);
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
