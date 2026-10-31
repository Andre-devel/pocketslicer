#!/usr/bin/env python3
"""Moonraker falso para testar o envio do app sem impressora.

Responde GET /server/info e aceita POST /server/files/upload (multipart, campo "file"),
gravando o arquivo em <pasta>. POST /printer/print/start só registra o pedido (e passa o estado
para "printing", com virtual_sdcard.progress subindo 1% a cada 3 s até "complete"); --state
define o print_stats.state inicial. No emulador, o endereço do PC é
10.0.2.2.

--u1 TIPO:RRGGBB,,... simula a Snapmaker U1: o objeto print_task_config com o filamento de cada
cabeça (vazio = sem filamento), GET /server/files/metadata e POST /printer/gcode/script, que
registra o SDCARD_PRINT_FILE_WITH_PARAMETERS recebido e inicia como /printer/print/start.

Uso: scripts/fake-moonraker.py [--port 7125] [--api-key CHAVE] [--state standby]
     [--u1 PLA:FF0000,PETG:000000,,] [pasta]
"""
import argparse
import email.parser
import email.policy
import json
import os
import re
import sys
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
    parser.add_argument("folder", nargs="?", default="fake-moonraker-files")
    args = parser.parse_args()
    os.makedirs(args.folder, exist_ok=True)
    state = {"print_stats": args.state, "filename": "", "started": None}

    def status():
        """print_stats e virtual_sdcard; a impressão "termina" em 300 s."""
        progress = 0.0
        if state["started"] is not None:
            progress = min((time.time() - state["started"]) / 300, 1.0)
            if progress >= 1.0:
                state["print_stats"], state["started"] = "complete", None
        result = {"print_stats": {"state": state["print_stats"], "filename": state["filename"]},
                  "virtual_sdcard": {"progress": progress}}
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

        def do_GET(self):
            if not self.authorized():
                return
            if self.path == "/server/info":
                self.reply(200, {"result": {"klippy_connected": True, "klippy_state": "ready",
                                            "moonraker_version": "v0.9.3-fake"}})
            elif self.path.startswith("/server/files/metadata?") and args.u1 is not None:
                query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
                result = metadata(query.get("filename", [""])[0])
                if result is None:
                    self.reply(404, {"error": {"code": 404, "message": "Metadata not available"}})
                else:
                    self.reply(200, {"result": result})
            elif self.path.startswith("/printer/objects/query?"):
                wanted = self.path.split("?", 1)[1].split("&")
                self.reply(200, {"result": {"status": {k: v for k, v in status().items() if k in wanted}}})
            else:
                self.reply(404, {"error": {"code": 404, "message": "Not Found"}})

        def do_POST(self):
            if not self.authorized():
                return
            if self.path == "/printer/print/start":
                request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                print(f"iniciar impressão de {request.get('filename')}", file=sys.stderr)
                state.update(print_stats="printing", filename=request.get("filename", ""), started=time.time())
                self.reply(200, {"result": "ok"})
                return
            if self.path == "/printer/gcode/script" and args.u1 is not None:
                script = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))["script"]
                print(f"script: {script}", file=sys.stderr)
                m = re.match(r'SDCARD_PRINT_FILE_WITH_PARAMETERS FILENAME="((?:[^"\\]|\\.)*)"', script)
                if m:
                    state.update(print_stats="printing", filename=m.group(1), started=time.time())
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
