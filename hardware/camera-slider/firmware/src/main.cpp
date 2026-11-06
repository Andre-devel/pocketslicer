// Firmware de teste do slider de câmera: valida a placa, o Wi-Fi com página web e o disparo
// da foto no celular como teclado BLE ("volume +"). Ainda sem motores.
//
// Pela serial (115200): "f" tira foto, "s" mostra o estado, "t"/"e" testam teclas comuns.
// Pela rede: http://slider-cam.local/ (ou o IP mostrado na serial), GET /foto, GET /status.

#include <Arduino.h>
#include <BleKeyboard.h>
#include <ESPmDNS.h>
#include <WebServer.h>
#include <WiFi.h>

#if __has_include("secrets.h")
#include "secrets.h"
#endif
#ifndef WIFI_SSID
#define WIFI_SSID ""
#endif
#ifndef WIFI_PASSWORD
#define WIFI_PASSWORD ""
#endif
#ifndef WIFI_AP_PASSWORD
#define WIFI_AP_PASSWORD ""
#endif

namespace {

constexpr const char *kName = "Slider Cam";
constexpr const char *kHostname = "slider-cam";
constexpr const char *kApSsid = "Slider-Cam";
// LED azul da placa, quando existe; em placas sem ele o pino só fica sem uso.
constexpr int kLedPin = 2;
constexpr uint32_t kWifiTimeoutMs = 15000;
constexpr uint32_t kHeartbeatMs = 5000;

BleKeyboard keyboard(kName, "Dimension Lab 3D", 100);
WebServer server(80);
bool apMode = false;
uint32_t photos = 0;

const char kPage[] PROGMEM = R"HTML(<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Slider Cam</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;padding:16px;background:#111;color:#eee}
h1{font-size:1.3rem}button{font-size:1.2rem;padding:16px 24px;border:0;border-radius:12px;background:#2b7;color:#000;width:100%}
button:disabled{background:#555}#s{margin:16px 0;line-height:1.6}.bad{color:#f66}.ok{color:#6d6}
</style></head><body>
<h1>Slider Cam</h1>
<div id="s">Carregando…</div>
<button id="b" onclick="foto()">Tirar foto</button>
<p id="r"></p>
<script>
async function st(){
  try{const j=await (await fetch('/status')).json();
    document.getElementById('s').innerHTML=
      'Bluetooth: '+(j.ble?'<span class=ok>conectado</span>':'<span class=bad>não conectado — pareie "Slider Cam" no celular</span>')+
      '<br>Rede: '+j.wifi+' ('+j.ip+')<br>Fotos: '+j.photos+'<br>Ligado há '+j.uptime_s+' s';
  }catch(e){document.getElementById('s').textContent='Sem resposta da placa';}
}
async function foto(){
  const b=document.getElementById('b');b.disabled=true;
  try{const j=await (await fetch('/foto')).json();
    document.getElementById('r').textContent=j.ok?'Foto disparada':'Bluetooth não conectado';
  }catch(e){document.getElementById('r').textContent='Falhou';}
  b.disabled=false;st();
}
st();setInterval(st,3000);
</script></body></html>)HTML";

String ipText() { return apMode ? WiFi.softAPIP().toString() : WiFi.localIP().toString(); }

String statusJson() {
  String json = "{\"ble\":";
  json += keyboard.isConnected() ? "true" : "false";
  json += ",\"wifi\":\"";
  json += apMode ? String("rede própria ") + kApSsid : String("conectado a ") + WiFi.SSID();
  json += "\",\"ip\":\"" + ipText() + "\",\"photos\":" + String(photos);
  json += ",\"uptime_s\":" + String(millis() / 1000) + "}";
  return json;
}

// Envia "volume +", que a câmera do celular usa como disparador. Falso se o BLE não está pareado.
bool takePhoto() {
  if (!keyboard.isConnected()) {
    Serial.println("[foto] Bluetooth não conectado");
    return false;
  }
  keyboard.write(KEY_MEDIA_VOLUME_UP);
  photos++;
  Serial.printf("[foto] disparada (#%u)\n", photos);
  digitalWrite(kLedPin, HIGH);
  delay(80);
  digitalWrite(kLedPin, LOW);
  return true;
}

void printStatus() { Serial.println("[estado] " + statusJson()); }

void startWifi() {
  WiFi.setHostname(kHostname);
  if (strlen(WIFI_SSID) > 0) {
    Serial.printf("[wifi] conectando a \"%s\"...\n", WIFI_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - start < kWifiTimeoutMs) delay(250);
    if (WiFi.status() == WL_CONNECTED) {
      Serial.printf("[wifi] conectado, IP %s\n", WiFi.localIP().toString().c_str());
      return;
    }
    Serial.println("[wifi] não conectou; criando rede própria");
    WiFi.disconnect(true);
  } else {
    Serial.println("[wifi] sem secrets.h; criando rede própria");
  }
  apMode = true;
  WiFi.mode(WIFI_AP);
  const char *pass = strlen(WIFI_AP_PASSWORD) >= 8 ? WIFI_AP_PASSWORD : nullptr;
  WiFi.softAP(kApSsid, pass);
  Serial.printf("[wifi] rede \"%s\" (%s), IP %s\n", kApSsid, pass ? "com senha" : "aberta", WiFi.softAPIP().toString().c_str());
}

void startServer() {
  server.on("/", HTTP_GET, [] { server.send_P(200, "text/html; charset=utf-8", kPage); });
  server.on("/status", HTTP_GET, [] { server.send(200, "application/json", statusJson()); });
  server.on("/foto", [] {
    bool ok = takePhoto();
    server.send(ok ? 200 : 503, "application/json", ok ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"ble\"}");
  });
  server.onNotFound([] { server.send(404, "text/plain", "não encontrado"); });
  server.begin();
  if (MDNS.begin(kHostname)) MDNS.addService("http", "tcp", 80);
  Serial.printf("[web] http://%s.local/ ou http://%s/\n", kHostname, ipText().c_str());
}

}  // namespace

void setup() {
  pinMode(kLedPin, OUTPUT);
  Serial.begin(115200);
  delay(300);
  Serial.println();
  Serial.println("=== Slider Cam: firmware de teste ===");
  Serial.printf("[placa] %s rev %d, %d núcleos, %u MHz, flash %u MB, MAC %s\n", ESP.getChipModel(), ESP.getChipRevision(),
                ESP.getChipCores(), ESP.getCpuFreqMHz(), ESP.getFlashChipSize() / (1024 * 1024), WiFi.macAddress().c_str());
  startWifi();
  startServer();
  keyboard.begin();
  Serial.printf("[ble] anunciando como \"%s\"; pareie no Bluetooth do celular\n", kName);
  Serial.println("[serial] comandos: f = foto, s = estado, t = digita \"teste\", e = Enter");
}

void loop() {
  server.handleClient();

  while (Serial.available()) {
    char c = Serial.read();
    if (c == 'f') takePhoto();
    else if (c == 's') printStatus();
    // Diagnóstico: teclas comuns, para separar "teclado não chega" de "volume não chega".
    else if (c == 't' && keyboard.isConnected()) { keyboard.print("teste"); Serial.println("[teste] digitou \"teste\""); }
    else if (c == 'e' && keyboard.isConnected()) { keyboard.write(KEY_RETURN); Serial.println("[teste] enviou Enter"); }
  }

  static bool wasConnected = false;
  bool connected = keyboard.isConnected();
  if (connected != wasConnected) {
    Serial.println(connected ? "[ble] celular conectado" : "[ble] celular desconectado");
    wasConnected = connected;
  }

  // Pulso a cada 5 s: mostra que a placa está viva mesmo sem LED.
  static uint32_t lastBeat = 0;
  if (millis() - lastBeat >= kHeartbeatMs) {
    lastBeat = millis();
    digitalWrite(kLedPin, HIGH);
    delay(30);
    digitalWrite(kLedPin, LOW);
    printStatus();
  }
}
