#include "motion.h"

#include <AccelStepper.h>

namespace {

// Ligações em ../motores.md. AccelStepper HALF4WIRE recebe IN1, IN3, IN2, IN4.
// Sentido trocado na montagem: true inverte a ordem das bobinas (IN4, IN2, IN3, IN1), o que faz
// o motor girar ao contrário sem mexer no resto. Calibrar na primeira montagem: o carrinho
// precisa sair da chave com X positivo e o pan positivo girar para +X da impressora.
constexpr bool kSliderReversed = true;
constexpr bool kPanReversed = false;
constexpr uint8_t kSliderPins[4] = {16, 17, 18, 19};  // IN1..IN4
constexpr uint8_t kPanPins[4] = {21, 22, 23, 25};
constexpr uint8_t kEndstopPin = 27;  // NA da chave; COM no GND (LOW = apertada)
constexpr uint32_t kDebounceMs = 20;

// Redução real ~63,68:1 em meio-passo: ~4076 por volta. Polia GT2 20 dentes = 40 mm por volta.
constexpr float kStepsPerRev = 4076;
constexpr float kSliderStepsPerMm = kStepsPerRev / 40;
constexpr float kPanStepsPerDeg = kStepsPerRev / 360;

// Velocidades em meios-passos/s. O 28BYJ-48 em 5 V perde passo acima de ~1000/s; 1500 µs por
// meio-passo (~667/s) andou firme na bancada.
constexpr float kSliderMaxSpeed = 700;  // ~6,9 mm/s
constexpr float kSliderAccel = 350;
constexpr float kPanMaxSpeed = 600;  // ~53°/s
constexpr float kPanAccel = 300;
constexpr float kHomeSeekSpeed = 200;
constexpr float kHomeSlowSpeed = 120;
constexpr float kHomeBackoffMm = 2;
// O homing desiste se andar mais que o perfil inteiro sem achar a chave.
constexpr float kHomeMaxMm = 520;
// Folga da redução (1 a 2° no eixo): o pré-movimento passa disto antes de chegar.
constexpr float kSliderBacklashMm = 2;
constexpr float kPanBacklashDeg = 3;

AccelStepper makeStepper(const uint8_t (&pins)[4], bool reversed) {
  if (reversed) return AccelStepper(AccelStepper::HALF4WIRE, pins[3], pins[1], pins[2], pins[0], false);
  return AccelStepper(AccelStepper::HALF4WIRE, pins[0], pins[2], pins[1], pins[3], false);
}

AccelStepper slider = makeStepper(kSliderPins, kSliderReversed);
AccelStepper pan = makeStepper(kPanPins, kPanReversed);

long sliderSteps(float mm) { return lroundf(constrain(mm, 0.0f, Motion::kTravelMm) * kSliderStepsPerMm); }
long panSteps(float deg) { return lroundf(constrain(deg, -Motion::kPanLimitDeg, Motion::kPanLimitDeg) * kPanStepsPerDeg); }

// Pré-alvo que faz a chegada em [target] acontecer andando no sentido [approach]. Já parado no
// alvo não mexe (camadas paradas na virada do ida e volta): a folga saiu na chegada.
long preTarget(long current, long target, int approach, long backlash, long minSteps, long maxSteps) {
  if (current == target) return target;
  if (approach > 0 && current >= target) return max(minSteps, target - backlash);
  if (approach < 0 && current <= target) return min(maxSteps, target + backlash);
  return target;
}

// Mutex recursivo: métodos públicos chamam uns aos outros (moveTo -> homing).
SemaphoreHandle_t lock_;

struct Guard {
  Guard() { xSemaphoreTakeRecursive(lock_, portMAX_DELAY); }
  ~Guard() { xSemaphoreGiveRecursive(lock_); }
};

}  // namespace

void Motion::begin() {
  lock_ = xSemaphoreCreateRecursiveMutex();
  Guard g;
  pinMode(kEndstopPin, INPUT_PULLUP);
  endstopRaw_ = endstopPressed_ = digitalRead(kEndstopPin) == LOW;
  slider.setMaxSpeed(kSliderMaxSpeed);
  slider.setAcceleration(kSliderAccel);
  pan.setMaxSpeed(kPanMaxSpeed);
  pan.setAcceleration(kPanAccel);
  // Parado com as bobinas desligadas: o 28BYJ esquenta ligado e a redução segura a posição.
  slider.disableOutputs();
  pan.disableOutputs();
  // Mesmo núcleo do loop() do Arduino, prioridade acima dele: quando o laço espera a rede, os
  // passos continuam. O vTaskDelay(1) devolve o núcleo a cada tique (1 ms), então a velocidade
  // máxima é 1000 passos/s por motor (acima do que o 28BYJ-48 aguenta).
  xTaskCreatePinnedToCore(taskMain, "motores", 4096, this, 2, nullptr, ARDUINO_RUNNING_CORE);
}

void Motion::taskMain(void *self) {
  auto *m = static_cast<Motion *>(self);
  for (;;) {
    {
      Guard g;
      m->tick();
    }
    vTaskDelay(1);
  }
}

bool Motion::homed() const { Guard g; return homed_; }
bool Motion::homing() const { Guard g; return homingLocked(); }
bool Motion::busy() const { Guard g; return phase_ != Phase::Idle; }
String Motion::error() const { Guard g; return error_; }
bool Motion::endstopPressed() const { Guard g; return endstopPressed_; }
float Motion::sliderMm() const { Guard g; return fromSwitch(slider.currentPosition() / kSliderStepsPerMm); }
bool Motion::switchRight() const { Guard g; return switchRight_; }

void Motion::setSwitchRight(bool right) {
  Guard g;
  switchRight_ = right;
}
float Motion::panDeg() const { Guard g; return pan.currentPosition() / kPanStepsPerDeg; }

void Motion::readEndstop() {
  bool raw = digitalRead(kEndstopPin) == LOW;
  uint32_t now = millis();
  if (raw != endstopRaw_) {
    endstopRaw_ = raw;
    endstopChangedMs_ = now;
  } else if (raw != endstopPressed_ && now - endstopChangedMs_ >= kDebounceMs) {
    endstopPressed_ = raw;
  }
}

void Motion::home() {
  Guard g;
  error_ = "";
  homed_ = false;
  pan.setCurrentPosition(pan.currentPosition());
  pan.disableOutputs();
  slider.enableOutputs();
  slider.setMaxSpeed(kHomeSeekSpeed);
  slider.setAcceleration(kSliderAccel);  // a última ida pode ter deixado reduzida
  if (endstopPressed_) {
    // Já na chave: só recua e faz a volta lenta.
    slider.setCurrentPosition(0);
    slider.moveTo(lroundf(kHomeBackoffMm * kSliderStepsPerMm));
    phase_ = Phase::HomeBackoff;
  } else {
    slider.setCurrentPosition(lroundf(kHomeMaxMm * kSliderStepsPerMm));
    slider.moveTo(0);
    phase_ = Phase::HomeSeek;
  }
  Serial.println("[motor] homing do carrinho");
}

void Motion::finishHoming() {
  slider.setCurrentPosition(0);
  slider.setMaxSpeed(kSliderMaxSpeed);
  homed_ = true;
  phase_ = Phase::Idle;
  slider.disableOutputs();
  Serial.println("[motor] homing ok (X = 0)");
}

void Motion::moveTo(float mm, float deg, int approachSlider, int approachPan) {
  Guard g;
  if (homingLocked()) return;
  sliderTarget_ = homed_ ? sliderSteps(fromSwitch(mm)) : slider.currentPosition();
  if (switchRight_) approachSlider = -approachSlider;
  panTarget_ = panSteps(deg);
  sliderPre_ = preTarget(slider.currentPosition(), sliderTarget_, approachSlider, lroundf(kSliderBacklashMm * kSliderStepsPerMm),
                         0, sliderSteps(kTravelMm));
  panPre_ = preTarget(pan.currentPosition(), panTarget_, approachPan, lroundf(kPanBacklashDeg * kPanStepsPerDeg),
                      panSteps(-kPanLimitDeg), panSteps(kPanLimitDeg));
  preDone_ = sliderPre_ == sliderTarget_ && panPre_ == panTarget_;
  startMove();
}

void Motion::startMove() {
  long s = preDone_ ? sliderTarget_ : sliderPre_;
  long p = preDone_ ? panTarget_ : panPre_;
  slider.moveTo(s);
  pan.moveTo(p);
  // Chegada juntos: o eixo com menos tempo de caminho anda mais devagar, na mesma proporção
  // (velocidade e aceleração), e o pan não chega antes do carrinho, nem o contrário. As duas
  // razões aceleração/velocidade são iguais, então os perfis têm o mesmo formato.
  float ts = abs(slider.distanceToGo()) / kSliderMaxSpeed;
  float tp = abs(pan.distanceToGo()) / kPanMaxSpeed;
  float longest = max(ts, tp);
  float ks = longest > 0 ? max(ts / longest, 0.05f) : 1;
  float kp = longest > 0 ? max(tp / longest, 0.05f) : 1;
  slider.setMaxSpeed(kSliderMaxSpeed * ks);
  slider.setAcceleration(kSliderAccel * ks);
  pan.setMaxSpeed(kPanMaxSpeed * kp);
  pan.setAcceleration(kPanAccel * kp);
  if (slider.distanceToGo() != 0) slider.enableOutputs();
  if (pan.distanceToGo() != 0) pan.enableOutputs();
  phase_ = Phase::Moving;
}

void Motion::stop() {
  Guard g;
  slider.setCurrentPosition(slider.currentPosition());
  pan.setCurrentPosition(pan.currentPosition());
  if (homingLocked()) slider.setMaxSpeed(kSliderMaxSpeed);
  phase_ = Phase::Idle;
  slider.disableOutputs();
  pan.disableOutputs();
}

void Motion::setPanZero() {
  Guard g;
  if (phase_ != Phase::Idle) return;
  pan.setCurrentPosition(0);
}

void Motion::tick() {
  readEndstop();
  switch (phase_) {
    case Phase::Idle:
      return;
    case Phase::HomeSeek:
      if (endstopPressed_) {
        slider.setCurrentPosition(0);
        slider.moveTo(lroundf(kHomeBackoffMm * kSliderStepsPerMm));
        phase_ = Phase::HomeBackoff;
      } else if (!slider.run()) {
        error_ = "fim de curso não encontrado";
        Serial.println("[motor] homing falhou: " + error_);
        stop();
      }
      return;
    case Phase::HomeBackoff:
      if (!slider.run()) {
        if (endstopPressed_) {
          error_ = "fim de curso continua apertado";
          Serial.println("[motor] homing falhou: " + error_);
          stop();
          return;
        }
        slider.setMaxSpeed(kHomeSlowSpeed);
        slider.moveTo(-lroundf((kHomeBackoffMm + 3) * kSliderStepsPerMm));
        phase_ = Phase::HomeSlow;
      }
      return;
    case Phase::HomeSlow:
      if (endstopPressed_) {
        finishHoming();
      } else if (!slider.run()) {
        error_ = "fim de curso não encontrado na volta lenta";
        Serial.println("[motor] homing falhou: " + error_);
        stop();
      }
      return;
    case Phase::Moving: {
      // Chave apertada andando para o zero: perdeu passo. Para ali e assume X = 0.
      if (endstopPressed_ && slider.distanceToGo() < 0) {
        Serial.printf("[motor] fim de curso em X = %.1f mm; corrigindo para 0\n", sliderMm());
        slider.setCurrentPosition(0);
        slider.moveTo(preDone_ ? sliderTarget_ : sliderPre_);
      }
      bool s = slider.run();
      bool p = pan.run();
      if (s || p) return;
      if (!preDone_) {
        preDone_ = true;
        startMove();
        return;
      }
      phase_ = Phase::Idle;
      slider.disableOutputs();
      pan.disableOutputs();
      return;
    }
  }
}
