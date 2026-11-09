// Firmware do slider de câmera: dispara a foto no celular como teclado BLE ("volume +"), pela
// página web, por GET /foto ou quando a macro TIMELAPSE_FOTO da impressora avisa (ver
// printer_link.h e ../klipper/slider_cam.cfg), e move o carrinho e o pan a cada camada pelo plano
// que o app manda (timelapse.h, motion.h).
//
// Pela serial (115200): "f" tira foto, "s" mostra o estado, "h" faz homing, "t"/"e" testam teclas.
// Pela rede: http://slider-cam.local/ (ou o IP mostrado na serial), GET /foto, GET /status,
// GET /impressora?host=<IP> (vazio desliga; fica salvo na placa), GET|POST /plano (JSON do
// app; fica salvo na placa), GET /home, GET /mover?x=<mm>&pan=<graus> (faz o homing antes se preciso), GET /parar,
// GET /pan-zero (posição atual do pan vira 0).

#include <Arduino.h>
#include <BleKeyboard.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>

#include "motion.h"
#include "printer_link.h"
#include "timelapse.h"

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
// Impressora usada enquanto nenhuma foi salva pela página; vazio = sem impressora.
#ifndef PRINTER_HOST
#define PRINTER_HOST ""
#endif

namespace {

constexpr const char *kName = "Slider Cam";
constexpr const char *kHostname = "slider-cam";
constexpr const char *kApSsid = "Slider-Cam";
// LED azul da placa, quando existe; em placas sem ele o pino só fica sem uso.
constexpr int kLedPin = 2;
constexpr uint32_t kWifiTimeoutMs = 15000;
constexpr uint32_t kHeartbeatMs = 5000;
constexpr uint16_t kMoonrakerPort = 7125;

BleKeyboard keyboard(kName, "Dimension Lab 3D", 100);
WebServer server(80);
Preferences prefs;
PrinterLink printer;
Motion motion;
Timelapse timelapse;
bool apMode = false;
// /mover antes do homing: faz o homing e vai depois (o app manda direto para conferir o enquadramento).
bool moveAfterHoming = false;
float pendingMm = 0, pendingDeg = 0;
uint32_t photos = 0;

const char kPage[] PROGMEM = R"HTML(<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Slider Cam</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;padding:16px;background:#111;color:#eee}
h1{font-size:1.3rem}button{font-size:1.2rem;padding:16px 24px;border:0;border-radius:12px;background:#2b7;color:#000;width:100%}
button:disabled{background:#555}.row button{width:auto;padding:10px 14px;font-size:1rem;margin:0 6px 6px 0}#s{margin:16px 0;line-height:1.6}.bad{color:#f66}.ok{color:#6d6}
</style></head><body>
<h1>Slider Cam</h1>
<div id="s">Carregando…</div>
<button id="b" onclick="foto()">Tirar foto</button>
<p id="r"></p>
<h2 style="font-size:1rem">Impressora (Moonraker)</h2>
<input id="h" placeholder="IP da impressora" style="font-size:1rem;padding:10px;width:60%">
<button style="width:auto;padding:10px 16px;font-size:1rem" onclick="salvar()">Salvar</button>
<h2 style="font-size:1rem">Motores</h2>
<div id="m"></div>
<p>X (mm) <input id="x" type="number" value="0" style="width:5em;font-size:1rem;padding:6px">
Pan (°) <input id="p" type="number" value="0" style="width:4em;font-size:1rem;padding:6px"></p>
<p class="row"><button onclick="cmd('/mover?x='+v('x')+'&pan='+v('p'))">Mover</button>
<button onclick="cmd('/home')">Homing</button><button onclick="cmd('/parar')">Parar</button>
<button onclick="cmd('/pan-zero')">Zero do pan aqui</button></p>
<script>
function v(id){return encodeURIComponent(document.getElementById(id).value);}
async function cmd(u){try{const j=await (await fetch(u)).json();if(!j.ok)alert(j.error||'falhou');}catch(e){}st();}
async function st(){
  try{const j=await (await fetch('/status')).json();
    document.getElementById('s').innerHTML=
      'Bluetooth: '+(j.ble?'<span class=ok>conectado</span>':'<span class=bad>não conectado — pareie "Slider Cam" no celular</span>')+
      '<br>Rede: '+j.wifi+' ('+j.ip+')<br>Fotos: '+j.photos+'<br>Ligado há '+j.uptime_s+' s'+
      '<br>Impressora: '+(!j.printer.host?'nenhuma':j.printer.host+' — '+
        (!j.printer.connected?'<span class=bad>sem conexão</span>':
         j.printer.subscribed?'<span class=ok>macro TIMELAPSE_FOTO ok</span> (shots '+j.printer.shots+')':
         '<span class=bad>conectada, sem a macro TIMELAPSE_FOTO</span>'));
    const h=document.getElementById('h');if(document.activeElement!==h)h.value=j.printer.host;
    const m=j.motion;document.getElementById('m').innerHTML=
      'Carrinho: '+(m.homed?m.x.toFixed(1)+' mm':'<span class=bad>sem homing</span>')+(m.homing?' (homing…)':m.busy?' (andando)':'')+
      ' · Pan: '+m.pan.toFixed(1)+'°'+(m.endstop?' · <span class=bad>fim de curso apertado</span>':'')+
      (m.error?'<br><span class=bad>'+m.error+'</span>':'')+
      '<br>Plano: '+(j.plan.layers?j.plan.layers+' camadas, '+j.plan.start+' → '+j.plan.end+' mm, pan '+j.plan.panMode:'nenhum')+
      (j.layer>=0?' · próxima foto: camada '+(j.layer+1):'');
  }catch(e){document.getElementById('s').textContent='Sem resposta da placa';}
}
async function foto(){
  const b=document.getElementById('b');b.disabled=true;
  try{const j=await (await fetch('/foto')).json();
    document.getElementById('r').textContent=j.ok?'Foto disparada':'Bluetooth não conectado';
  }catch(e){document.getElementById('r').textContent='Falhou';}
  b.disabled=false;st();
}
async function salvar(){
  await fetch('/impressora?host='+encodeURIComponent(document.getElementById('h').value.trim()));st();
}
st();setInterval(st,3000);
</script></body></html>)HTML";

// LED aceso por [ms] sem parar o laço (os motores precisam do loop() o tempo todo).
uint32_t ledOffAt = 0;
void blink(uint32_t ms) {
  digitalWrite(kLedPin, HIGH);
  ledOffAt = millis() + ms;
  if (ledOffAt == 0) ledOffAt = 1;
}

String ipText() { return apMode ? WiFi.softAPIP().toString() : WiFi.localIP().toString(); }

String statusJson() {
  String json = "{\"ble\":";
  json += keyboard.isConnected() ? "true" : "false";
  json += ",\"wifi\":\"";
  json += apMode ? String("rede própria ") + kApSsid : String("conectado a ") + WiFi.SSID();
  json += "\",\"ip\":\"" + ipText() + "\",\"photos\":" + String(photos);
  json += ",\"uptime_s\":" + String(millis() / 1000);
  json += ",\"printer\":{\"host\":\"" + printer.host() + "\",\"connected\":";
  json += printer.connected() ? "true" : "false";
  json += ",\"subscribed\":";
  json += printer.subscribed() ? "true" : "false";
  json += ",\"shots\":" + String(printer.shots()) + ",\"state\":\"" + printer.printState() + "\"}";
  json += ",\"motion\":{\"homed\":";
  json += motion.homed() ? "true" : "false";
  json += ",\"homing\":";
  json += motion.homing() ? "true" : "false";
  json += ",\"busy\":";
  json += motion.busy() ? "true" : "false";
  json += ",\"endstop\":";
  json += motion.endstopPressed() ? "true" : "false";
  json += ",\"x\":" + String(motion.sliderMm(), 1) + ",\"pan\":" + String(motion.panDeg(), 1);
  json += ",\"error\":\"" + motion.error() + "\"}";
  json += ",\"plan\":" + timelapse.plan().toJson() + ",\"layer\":" + String(timelapse.nextLayer()) + "}";
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
  blink(80);
  return true;
}

// A macro da impressora estacionou o cabeçote e somou "shots": o time-lapse tira a foto e leva o
// carrinho à próxima camada.
void startPrinter(const String &host) {
  printer.begin(
      host, kMoonrakerPort, [](uint32_t shots) { timelapse.onShot(shots); },
      [](const String &state, bool initial) { timelapse.onPrintState(state, initial); });
}

void sendOk(bool ok, const char *error = "") {
  server.send(ok ? 200 : 409, "application/json", ok ? String("{\"ok\":true}") : String("{\"ok\":false,\"error\":\"") + error + "\"}");
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
  server.on("/impressora", HTTP_GET, [] {
    if (server.hasArg("host")) {
      String host = server.arg("host");
      host.trim();
      // Só IP ou nome simples: o valor vai para a URL do websocket e para o JSON de estado.
      for (char c : host) {
        if (!isalnum(static_cast<unsigned char>(c)) && c != '.' && c != '-') {
          server.send(400, "application/json", "{\"ok\":false,\"error\":\"host\"}");
          return;
        }
      }
      prefs.putString("printer", host);
      startPrinter(host);
    }
    server.send(200, "application/json", "{\"ok\":true,\"host\":\"" + printer.host() + "\"}");
  });
  server.on("/plano", [] {
    if (server.method() == HTTP_POST) {
      TimelapsePlan plan;
      String error;
      if (!plan.fromJson(server.arg("plain"), error)) {
        server.send(400, "application/json", "{\"ok\":false,\"error\":\"" + error + "\"}");
        return;
      }
      timelapse.setPlan(plan);
      prefs.putString("plan", plan.toJson());
      Serial.println("[plano] novo: " + plan.toJson());
    }
    server.send(200, "application/json", "{\"ok\":true,\"plan\":" + timelapse.plan().toJson() + "}");
  });
  server.on("/home", HTTP_GET, [] {
    moveAfterHoming = false;
    motion.home();
    sendOk(true);
  });
  server.on("/mover", HTTP_GET, [] {
    if (motion.homing()) return sendOk(false, "homing em andamento");
    float x = server.hasArg("x") ? server.arg("x").toFloat() : motion.sliderMm();
    float deg = server.hasArg("pan") ? server.arg("pan").toFloat() : motion.panDeg();
    if (timelapse.nextLayer() >= 0) return sendOk(false, "impressão em andamento");
    if (server.hasArg("x") && !motion.homed()) {
      moveAfterHoming = true;
      pendingMm = x;
      pendingDeg = deg;
      motion.home();
      return sendOk(true);
    }
    motion.moveTo(x, deg);
    sendOk(true);
  });
  server.on("/parar", HTTP_GET, [] {
    moveAfterHoming = false;
    motion.stop();
    sendOk(true);
  });
  server.on("/pan-zero", HTTP_GET, [] {
    if (motion.busy()) return sendOk(false, "motor andando");
    motion.setPanZero();
    sendOk(true);
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
  prefs.begin("slider", false);
  motion.begin();
  timelapse.begin(motion, takePhoto);
  if (prefs.isKey("plan")) {
    TimelapsePlan plan;
    String error;
    if (plan.fromJson(prefs.getString("plan"), error)) timelapse.setPlan(plan);
  }
  startPrinter(prefs.isKey("printer") ? prefs.getString("printer") : String(PRINTER_HOST));
  keyboard.begin();
  Serial.printf("[ble] anunciando como \"%s\"; pareie no Bluetooth do celular\n", kName);
  Serial.println("[serial] comandos: f = foto, s = estado, h = homing, t = digita \"teste\", e = Enter");
}

void loop() {
  motion.loop();
  server.handleClient();
  printer.loop();
  timelapse.loop();
  motion.loop();
  if (moveAfterHoming && (timelapse.nextLayer() >= 0 || !motion.busy())) {
    // Homing que falhou (sem homed) ou impressão que começou: o plano manda, não o pedido.
    if (timelapse.nextLayer() < 0 && motion.homed()) motion.moveTo(pendingMm, pendingDeg);
    moveAfterHoming = false;
  }
  if (ledOffAt && int32_t(millis() - ledOffAt) >= 0) {
    ledOffAt = 0;
    digitalWrite(kLedPin, LOW);
  }

  while (Serial.available()) {
    char c = Serial.read();
    if (c == 'f') takePhoto();
    else if (c == 's') printStatus();
    else if (c == 'h') motion.home();
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
    blink(30);
    // Estado na serial só com os motores parados: imprimir leva alguns ms e atrasa os passos.
    if (!motion.busy()) printStatus();
  }
}
