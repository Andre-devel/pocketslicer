#!/usr/bin/env python3
"""Moonraker falso para testar o envio do app sem impressora.

Responde GET /server/info e aceita POST /server/files/upload (multipart, campo "file"),
gravando o arquivo em <pasta>. POST /printer/print/start só registra o pedido (e passa o estado
para "printing", com virtual_sdcard.progress subindo 1% a cada 3 s até "complete"); --state
define o print_stats.state inicial. No emulador, o endereço do PC é
10.0.2.2.

--u1 TIPO:RRGGBB,,... simula a Snapmaker U1: o objeto print_task_config com o filamento de cada
cabeça (vazio = sem filamento), GET /server/files/metadata e POST /printer/gcode/script, que
registra o SDCARD_PRINT_FILE_WITH_PARAMETERS recebido e inicia como /printer/print/start. Também
a câmera de fábrica: GET /server/files/camera/monitor.jpg (uma imagem com a hora, precisa do
Pillow) só responde até 30 s depois do último camera.start_monitor no WebSocket /websocket.

WebSocket /websocket (JSON-RPC): printer.objects.subscribe responde o estado dos objetos pedidos
e depois manda notify_status_update com os campos que mudaram, uma vez por segundo (o bico e a
mesa oscilam um pouco para sempre haver mudança).

POST /printer/emergency_stop deixa o Klipper parado (webhooks.state "shutdown",
notify_klippy_shutdown no WebSocket) até POST /printer/firmware_restart
(notify_klippy_disconnected e, 2 s depois, notify_klippy_ready).

Controle (a aba da tela da impressora): GET /printer/objects/list, /printer/gcode/help e o
configfile (max_temp) de um Klipper comum com toolhead, fan (M106), fan_generic exhaust
(SET_FAN_SPEED), led chamber_light (SET_LED) e heater_generic chamber. POST /printer/gcode/script
executa G28, G91/G90, G1, M84, SET_HEATER_TEMPERATURE, M106/M107, SET_FAN_SPEED, SET_LED e
SAVE/RESTORE_GCODE_STATE; mover sem home ou fora da mesa responde o erro do Klipper.

GET /machine/system_info informa o MAC de --mac (a identidade que o app guarda para reencontrar a
impressora quando o IP muda) e GET /printer/info o hostname "fake-moonraker".

Uso: scripts/fake-moonraker.py [--port 7125] [--api-key CHAVE] [--state standby]
     [--u1 PLA:FF0000,PETG:000000,,] [--mac 02:00:00:00:fa:01] [pasta]
"""
import argparse
import base64
import hashlib
import io
import email.parser
import email.policy
import json
import os
import re
import math
import sys
import threading
import urllib.parse
import zipfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=7125)
    parser.add_argument("--api-key", help="exige o cabeçalho X-Api-Key com este valor")
    parser.add_argument("--state", default="standby", help="print_stats.state informado")
    parser.add_argument("--slow", type=float, default=0, help="KB/s ao receber o upload, para ver o andamento no app")
    parser.add_argument("--u1", help="filamento de cada cabeça, TIPO:RRGGBB separados por vírgula")
    parser.add_argument("--mac", default="02:00:00:00:fa:01", help="MAC da wlan0 em /machine/system_info")
    parser.add_argument("folder", nargs="?", default="fake-moonraker-files")
    args = parser.parse_args()
    os.makedirs(args.folder, exist_ok=True)
    state = {"print_stats": args.state, "filename": "", "started": None, "monitor": 0, "webhooks": "ready"}
    # Klipper simulado para a aba Controle.
    machine = {"homed": "", "position": [0.0, 0.0, 0.0], "relative": False, "saved": {},
               "target": {"extruder": 0.0, "heater_bed": 0.0, "chamber": 0.0},
               "fan": 0.0, "exhaust": 0.0, "light": 0.0}
    limits = {"x": (0.0, 250.0), "y": (0.0, 250.0), "z": (0.0, 240.0)}
    max_temp = {"extruder": 300.0, "heater_bed": 120.0, "heater_generic chamber": 60.0}
    help_text = {"M106": "Set fan speed", "M107": "Turn fan off", "M84": "Disable steppers",
                 "SET_HEATER_TEMPERATURE": "Sets a heater temperature", "SET_FAN_SPEED": "Sets the speed of a fan",
                 "SET_LED": "Set the color of an LED", "SAVE_GCODE_STATE": "Save G-Code coordinate state",
                 "RESTORE_GCODE_STATE": "Restore a previously saved G-Code state", "TURN_OFF_HEATERS": "Turn off all heaters"}

    class KlipperError(Exception):
        pass

    def gcode(script):
        """Executa as linhas de [script] como o Klipper; KlipperError com a mensagem dele."""
        for line in script.splitlines():
            words = line.split(";")[0].split()
            if not words:
                continue
            command, params = words[0].upper(), {}
            for word in words[1:]:
                if "=" in word:
                    key, value = word.split("=", 1)
                    params[key.upper()] = value
                else:
                    params[word[0].upper()] = word[1:]
            if command == "G28":
                axes = [a for a in "XYZ" if a in params] or list("XYZ")
                for a in axes:
                    machine["position"]["XYZ".index(a)] = 0.0
                machine["homed"] = "".join(a for a in "xyz" if a in machine["homed"] or a.upper() in axes)
            elif command == "G90":
                machine["relative"] = False
            elif command == "G91":
                machine["relative"] = True
            elif command in ("G0", "G1"):
                new = list(machine["position"])
                for i, a in enumerate("XYZ"):
                    if a in params:
                        value = float(params[a])
                        new[i] = new[i] + value if machine["relative"] else value
                        if a.lower() not in machine["homed"]:
                            raise KlipperError("Must home axis first: %.3f %.3f %.3f [0.000]" % tuple(new))
                        low, high = limits[a.lower()]
                        if not low <= new[i] <= high:
                            raise KlipperError("Move out of range: %.3f %.3f %.3f [0.000]" % tuple(new))
                machine["position"] = new
            elif command == "M84":
                machine["homed"] = ""
            elif command == "SAVE_GCODE_STATE":
                machine["saved"][params.get("NAME", "default")] = machine["relative"]
            elif command == "RESTORE_GCODE_STATE":
                machine["relative"] = machine["saved"].get(params.get("NAME", "default"), False)
            elif command == "SET_HEATER_TEMPERATURE":
                heater = params.get("HEATER", "")
                if heater not in machine["target"]:
                    raise KlipperError(f"Heater {heater} not configured")
                target = float(params.get("TARGET", 0))
                limit = max_temp["heater_generic chamber" if heater == "chamber" else heater]
                if target > limit:
                    raise KlipperError(f"Requested temperature ({target:.1f}) out of range (0.0:{limit:.1f})")
                machine["target"][heater] = target
            elif command == "M106":
                machine["fan"] = min(float(params.get("S", 255)) / 255, 1.0)
            elif command == "M107":
                machine["fan"] = 0.0
            elif command == "SET_FAN_SPEED":
                if params.get("FAN") != "exhaust":
                    raise KlipperError(f"Fan {params.get('FAN')} not configured")
                machine["exhaust"] = float(params.get("SPEED", 0))
            elif command == "SET_LED":
                if params.get("LED") != "chamber_light":
                    raise KlipperError(f"Unknown LED '{params.get('LED')}'")
                machine["light"] = max(float(params.get(k, 0)) for k in ("RED", "GREEN", "BLUE", "WHITE"))
            elif command.startswith("SDCARD_PRINT_FILE_WITH_PARAMETERS") and args.u1 is not None:
                m = re.match(r'SDCARD_PRINT_FILE_WITH_PARAMETERS FILENAME="((?:[^"\\]|\\.)*)"', line)
                if m:
                    state.update(print_stats="printing", filename=m.group(1), started=time.time())
            elif command not in help_text and not (args.u1 is not None):
                raise KlipperError(f"Unknown command:\"{command}\"")

    def heater(name, ambient):
        target = machine["target"][name]
        return {"temperature": round((target or ambient) + math.sin(time.time() / 5), 2), "target": target}
    # Avisos pendentes (notify_klippy_*) de cada WebSocket aberto.
    sockets = []

    def klippy(event):
        for events in list(sockets):
            events.append(event)

    def status():
        """print_stats e virtual_sdcard; a impressão "termina" em 300 s."""
        progress = 0.0
        if state["started"] is not None:
            progress = min((time.time() - state["started"]) / 300, 1.0)
            if progress >= 1.0:
                state["print_stats"], state["started"] = "complete", None
        shutdown = state["webhooks"] == "shutdown"
        result = {"webhooks": {"state": state["webhooks"],
                               "state_message": "Shutdown due to M112 command" if shutdown else "Printer is ready"},
                  "print_stats": {"state": state["print_stats"], "filename": state["filename"]},
                  "virtual_sdcard": {"progress": progress},
                  "extruder": heater("extruder", 25),
                  "heater_bed": heater("heater_bed", 24),
                  "heater_generic chamber": heater("chamber", 23),
                  "heaters": {"available_heaters": ["extruder", "heater_bed", "heater_generic chamber"],
                              "available_sensors": ["extruder", "heater_bed", "heater_generic chamber"]},
                  "toolhead": {"homed_axes": machine["homed"], "position": machine["position"] + [0.0],
                               "axis_minimum": [v[0] for v in limits.values()] + [0.0],
                               "axis_maximum": [v[1] for v in limits.values()] + [0.0]},
                  "fan": {"speed": machine["fan"], "rpm": None},
                  "fan_generic exhaust": {"speed": machine["exhaust"], "rpm": None},
                  "led chamber_light": {"color_data": [[machine["light"]] * 4]},
                  "configfile": {"settings": {name: {"max_temp": value} for name, value in max_temp.items()}}}
        if args.u1 is not None:
            heads = [h.split(":") if h else None for h in args.u1.split(",")]
            result["print_task_config"] = {
                "filament_exist": [h is not None for h in heads],
                "filament_type": [h[0] if h else "NONE" for h in heads],
                "filament_color_rgba": [(h[1] + "FF") if h else "FFFFFFFF" for h in heads],
            }
        return result

    def metadata(filename):
        """Parte dos metadados que o Moonraker da U1 tira do rodapé do G-code do Orca."""
        path = os.path.join(args.folder, os.path.basename(filename))
        if not os.path.exists(path):
            return None
        if zipfile.is_zipfile(path):  # .gcode.3mf
            with zipfile.ZipFile(path) as z:
                footer = z.read("Metadata/plate_1.gcode").decode(errors="replace")
        else:
            with open(path, "rb") as f:
                f.seek(max(0, os.path.getsize(path) - 200_000))
                footer = f.read().decode(errors="replace")
        def floats(key):
            m = re.search(rf"; {re.escape(key)} = (.*)\n", footer)
            return [float(v) for v in re.findall(r"[-\d.]+", m.group(1))] if m else None
        m = re.search(r"; filament_type = (.*)\n", footer)
        return {"slicer": "OrcaSlicer", "slicer_version": "2.4.2", "filename": filename,
                "filament_type": m.group(1) if m else None,
                "filament_used_mm": floats("filament used [mm]"),
                "filament_weight": floats("filament used [g]"),
                "nozzle_diameter_list": floats("nozzle_diameter"),
                "print_sequence": "by layer"}

    class Handler(BaseHTTPRequestHandler):
        def reply(self, code, body):
            data = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def authorized(self):
            if args.api_key and self.headers.get("X-Api-Key") != args.api_key:
                self.reply(401, {"error": {"code": 401, "message": "Unauthorized"}})
                return False
            return True

        def send_text(self, lock, message):
            answer = json.dumps(message).encode()
            header = bytes([0x81, len(answer)]) if len(answer) < 126 else bytes([0x81, 126]) + len(answer).to_bytes(2, "big")
            with lock:
                self.wfile.write(header + answer)
                self.wfile.flush()

        def websocket(self):
            """JSON-RPC pelo WebSocket: printer.objects.subscribe e camera.start_monitor (u1-moonraker repeater.py)."""
            accept = base64.b64encode(hashlib.sha1((self.headers["Sec-WebSocket-Key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
            # O navegador só aceita o 101 em HTTP/1.1.
            self.protocol_version = "HTTP/1.1"
            self.send_response(101)
            self.send_header("Upgrade", "websocket")
            self.send_header("Connection", "Upgrade")
            self.send_header("Sec-WebSocket-Accept", accept)
            self.end_headers()
            self.wfile.flush()
            sock = self.rfile
            lock = threading.Lock()
            subscribed = {}  # objeto -> último estado mandado
            events = []
            sockets.append(events)
            closed = threading.Event()

            def notify():
                """Os campos que mudaram desde o último envio, uma vez por segundo, e os avisos do Klipper."""
                while not closed.wait(1):
                    try:
                        while events:
                            self.send_text(lock, {"jsonrpc": "2.0", "method": events.pop(0)})
                        if not subscribed or state["webhooks"] == "disconnected":
                            continue
                        current, changed = status(), {}
                        for name, last in subscribed.items():
                            fields = {k: v for k, v in current.get(name, {}).items() if last.get(k) != v}
                            if fields:
                                changed[name] = fields
                                last.update(fields)
                        if changed:
                            self.send_text(lock, {"jsonrpc": "2.0", "method": "notify_status_update", "params": [changed, time.time()]})
                    except OSError:
                        return

            threading.Thread(target=notify, daemon=True).start()
            try:
                self.websocket_requests(sock, lock, subscribed)
            finally:
                closed.set()
                sockets.remove(events)

        def websocket_requests(self, sock, lock, subscribed):
            while True:
                head = sock.read(2)
                if len(head) < 2:
                    return
                opcode, length = head[0] & 15, head[1] & 127
                if length == 126:
                    length = int.from_bytes(sock.read(2), "big")
                elif length == 127:
                    length = int.from_bytes(sock.read(8), "big")
                mask = sock.read(4) if head[1] & 128 else b"\0\0\0\0"
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(sock.read(length)))
                if opcode == 8:
                    return
                if opcode == 9:
                    with lock:
                        self.wfile.write(bytes([0x8A, len(payload)]) + payload)
                        self.wfile.flush()
                    continue
                if opcode != 1:
                    continue
                request = json.loads(payload)
                method = request.get("method")
                if method == "printer.objects.subscribe":
                    if state["webhooks"] == "disconnected":
                        self.send_text(lock, {"jsonrpc": "2.0", "error": {"code": 503, "message": "Klippy Disconnected"},
                                              "id": request.get("id")})
                        continue
                    wanted = request.get("params", {}).get("objects", {})
                    current = status()
                    subscribed.clear()
                    subscribed.update({k: dict(v) for k, v in current.items() if k in wanted})
                    print(f"subscribe {sorted(subscribed)}", file=sys.stderr)
                    result = {"eventtime": time.time(), "status": {k: dict(v) for k, v in subscribed.items()}}
                else:
                    if method == "camera.start_monitor" and args.u1 is not None:
                        state["monitor"] = time.time()
                        print(f"camera.start_monitor {request.get('params')}", file=sys.stderr)
                    result = {"state": "success"}
                self.send_text(lock, {"jsonrpc": "2.0", "result": result, "id": request.get("id")})

        def monitor_jpg(self):
            from PIL import Image, ImageDraw
            image = Image.new("RGB", (640, 360), (40, 90, 140))
            ImageDraw.Draw(image).text((20, 20), "U1 falsa " + time.strftime("%H:%M:%S"), fill=(255, 255, 255))
            data = io.BytesIO()
            image.save(data, "JPEG")
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(data.getvalue())))
            self.end_headers()
            self.wfile.write(data.getvalue())

        def do_GET(self):
            if not self.authorized():
                return
            if self.path == "/websocket":
                self.websocket()
            elif self.path.startswith("/server/files/camera/monitor.jpg") and args.u1 is not None:
                if time.time() - state["monitor"] < 30:
                    self.monitor_jpg()
                else:
                    self.reply(404, {"error": {"code": 404, "message": "File does not exist"}})
            elif self.path == "/server/info":
                self.reply(200, {"result": {"klippy_connected": True, "klippy_state": "ready",
                                            "moonraker_version": "v0.9.3-fake"}})
            elif self.path == "/machine/system_info":
                self.reply(200, {"result": {"system_info": {"network": {
                    "wlan0": {"mac_address": args.mac, "ip_addresses": []}}}}})
            elif self.path == "/printer/info":
                self.reply(200, {"result": {"state": "ready", "hostname": "fake-moonraker"}})
            elif self.path.startswith("/server/files/metadata?") and args.u1 is not None:
                query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
                result = metadata(query.get("filename", [""])[0])
                if result is None:
                    self.reply(404, {"error": {"code": 404, "message": "Metadata not available"}})
                else:
                    self.reply(200, {"result": result})
            elif self.path.startswith("/printer/objects/query?") and state["webhooks"] == "disconnected":
                self.reply(503, {"error": {"code": 503, "message": "Klippy Disconnected"}})
            elif self.path == "/printer/objects/list":
                self.reply(200, {"result": {"objects": [k for k in status() if k != "configfile"] + ["configfile"]}})
            elif self.path == "/printer/gcode/help":
                self.reply(200, {"result": help_text})
            elif self.path.startswith("/printer/objects/query?"):
                wanted = [urllib.parse.unquote(w.split("=", 1)[0]) for w in self.path.split("?", 1)[1].split("&")]
                self.reply(200, {"result": {"status": {k: v for k, v in status().items() if k in wanted}}})
            else:
                self.reply(404, {"error": {"code": 404, "message": "Not Found"}})

        def do_POST(self):
            if not self.authorized():
                return
            if self.path in ("/printer/emergency_stop", "/printer/firmware_restart"):
                stop = self.path == "/printer/emergency_stop"
                print("parada de emergência" if stop else "reinício do firmware", file=sys.stderr)
                if stop:
                    state.update(webhooks="shutdown", print_stats="error", started=None)
                    klippy("notify_klippy_shutdown")
                else:
                    state.update(webhooks="disconnected")
                    klippy("notify_klippy_disconnected")

                    def ready():
                        state.update(webhooks="ready")
                        if state["print_stats"] == "error":
                            state.update(print_stats="standby")
                        klippy("notify_klippy_ready")

                    threading.Timer(2, ready).start()
                self.reply(200, {"result": "ok"})
                return
            if self.path == "/printer/print/start":
                request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                print(f"iniciar impressão de {request.get('filename')}", file=sys.stderr)
                state.update(print_stats="printing", filename=request.get("filename", ""), started=time.time())
                self.reply(200, {"result": "ok"})
                return
            if self.path == "/printer/gcode/script":
                script = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))["script"]
                print(f"script: {script!r}", file=sys.stderr)
                try:
                    gcode(script)
                except KlipperError as e:
                    print(f"  erro: {e}", file=sys.stderr)
                    self.reply(400, {"error": {"code": 400, "message": str(e)}})
                    return
                self.reply(200, {"result": "ok"})
                return
            if self.path != "/server/files/upload":
                self.reply(404, {"error": {"code": 404, "message": "Not Found"}})
                return
            length = int(self.headers.get("Content-Length", 0))
            if args.slow > 0:
                chunk = 8192
                body = b""
                while len(body) < length:
                    body += self.rfile.read(min(chunk, length - len(body)))
                    time.sleep(chunk / (args.slow * 1024))
            else:
                body = self.rfile.read(length)
            head = f"Content-Type: {self.headers['Content-Type']}\r\n\r\n".encode()
            message = email.parser.BytesParser(policy=email.policy.HTTP).parsebytes(head + body)
            fields, upload = {}, None
            for part in message.iter_parts():
                name = part.get_param("name", header="content-disposition")
                if name == "file":
                    upload = (part.get_filename(), part.get_payload(decode=True))
                else:
                    fields[name] = part.get_content().strip()
            if upload is None:
                self.reply(400, {"error": {"code": 400, "message": "No file"}})
                return
            filename = os.path.basename(upload[0])
            with open(os.path.join(args.folder, filename), "wb") as f:
                f.write(upload[1])
            print(f"recebido {filename}: {len(upload[1])} bytes, campos {fields}", file=sys.stderr)
            self.reply(201, {"result": {"item": {"path": filename, "root": fields.get("root", "gcodes")},
                                        "action": "create_file"}})

    print(f"Moonraker falso na porta {args.port}, arquivos em {args.folder}", file=sys.stderr)
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
