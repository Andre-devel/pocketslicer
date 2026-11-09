#include "printer_link.h"

#include <ArduinoJson.h>
#include <WebSocketsClient.h>

namespace {

constexpr const char *kMacroObject = "gcode_macro TIMELAPSE_FOTO";
constexpr const char *kStatsObject = "print_stats";
constexpr uint32_t kReconnectMs = 5000;

WebSocketsClient socket;
PrinterLink *active = nullptr;

}  // namespace

void PrinterLink::begin(const String &host, uint16_t port, ShotCallback onShot, StateCallback onState) {
  if (started_) socket.disconnect();
  host_ = host;
  onShot_ = std::move(onShot);
  onState_ = std::move(onState);
  printState_ = "";
  connected_ = subscribed_ = haveShots_ = false;
  started_ = host_.length() > 0;
  if (!started_) {
    Serial.println("[impressora] sem endereço; ligação desligada");
    return;
  }
  active = this;
  socket.begin(host_.c_str(), port, "/websocket");
  // A biblioteca manda "Origin: file://" por padrão e o Moonraker recusa (403); a origem da
  // própria impressora passa na checagem.
  socket.setExtraHeaders(("Origin: http://" + host_ + ":" + String(port)).c_str());
  socket.setReconnectInterval(kReconnectMs);
  // Detecta queda de Wi-Fi ou impressora desligada sem esperar o TCP.
  socket.enableHeartbeat(15000, 3000, 2);
  socket.onEvent([](WStype_t type, uint8_t *payload, size_t length) {
    if (!active) return;
    switch (type) {
      case WStype_CONNECTED:
        active->connected_ = true;
        Serial.printf("[impressora] conectado a %s\n", active->host_.c_str());
        active->subscribe();
        break;
      case WStype_DISCONNECTED:
        if (active->connected_) Serial.println("[impressora] desconectado");
        else Serial.println("[impressora] sem conexão; tentando de novo em 5 s");
        active->connected_ = active->subscribed_ = false;
        break;
      case WStype_TEXT:
        active->handleMessage(payload, length);
        break;
      case WStype_ERROR:
        Serial.printf("[impressora] erro no websocket: %.*s\n", (int)length, (const char *)payload);
        break;
      default:
        break;
    }
  });
  Serial.printf("[impressora] conectando a ws://%s:%u/websocket\n", host_.c_str(), port);
}

void PrinterLink::loop() {
  if (started_) socket.loop();
}

void PrinterLink::subscribe() {
  subscribeId_ = nextId_++;
  JsonDocument doc;
  doc["jsonrpc"] = "2.0";
  doc["method"] = "printer.objects.subscribe";
  doc["params"]["objects"][kMacroObject].add("shots");
  doc["params"]["objects"][kStatsObject].add("state");
  doc["id"] = subscribeId_;
  String text;
  serializeJson(doc, text);
  socket.sendTXT(text);
}

void PrinterLink::handleMessage(const uint8_t *payload, size_t length) {
  // O Moonraker manda muita coisa a todos os clientes (proc_stat a cada segundo); só interessa
  // a resposta da assinatura, as mudanças da macro e o aviso de Klipper pronto.
  JsonDocument filter;
  filter["id"] = true;
  filter["method"] = true;
  filter["error"]["message"] = true;
  filter["result"]["status"][kMacroObject]["shots"] = true;
  filter["result"]["status"][kStatsObject]["state"] = true;
  filter["params"][0][kMacroObject]["shots"] = true;
  filter["params"][0][kStatsObject]["state"] = true;
  JsonDocument doc;
  if (deserializeJson(doc, payload, length, DeserializationOption::Filter(filter))) return;

  const char *method = doc["method"] | "";
  if (strcmp(method, "notify_klippy_ready") == 0) {
    // Klipper reiniciou (por exemplo depois de incluir slider_cam.cfg): assina de novo.
    Serial.println("[impressora] Klipper pronto; assinando de novo");
    subscribe();
    return;
  }
  if (strcmp(method, "notify_klippy_shutdown") == 0 || strcmp(method, "notify_klippy_disconnected") == 0) {
    subscribed_ = false;
    return;
  }
  if (strcmp(method, "notify_status_update") == 0) {
    JsonVariant shots = doc["params"][0][kMacroObject]["shots"];
    if (!shots.isNull()) updateShots(shots.as<uint32_t>(), false);
    const char *state = doc["params"][0][kStatsObject]["state"];
    if (state) updateState(state, false);
    return;
  }
  if (doc["id"] == subscribeId_) {
    if (!doc["error"].isNull()) {
      subscribed_ = false;
      Serial.printf("[impressora] assinatura recusada: %s\n", doc["error"]["message"] | "?");
      return;
    }
    const char *state = doc["result"]["status"][kStatsObject]["state"];
    if (state) updateState(state, true);
    JsonVariant shots = doc["result"]["status"][kMacroObject]["shots"];
    subscribed_ = !shots.isNull();
    if (subscribed_) {
      updateShots(shots.as<uint32_t>(), true);
      Serial.printf("[impressora] macro TIMELAPSE_FOTO encontrada (shots=%u)\n", shots_);
    } else {
      // Sem a macro o Moonraker não devolve o objeto; ela aparece depois do include + restart.
      Serial.println("[impressora] macro TIMELAPSE_FOTO não existe na impressora");
    }
  }
}

void PrinterLink::updateShots(uint32_t value, bool initial) {
  // Só um aumento dispara: o valor inicial e uma volta a zero (início de impressão, restart)
  // apenas sincronizam.
  bool fire = !initial && haveShots_ && value > shots_;
  shots_ = value;
  haveShots_ = true;
  if (fire && onShot_) onShot_(value);
}

void PrinterLink::updateState(const char *state, bool initial) {
  if (printState_ == state) return;
  printState_ = state;
  Serial.printf("[impressora] estado: %s\n", state);
  if (onState_) onState_(printState_, initial);
}
