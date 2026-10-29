#!/usr/bin/env python3
"""Moonraker falso para testar o envio do app sem impressora.

Responde GET /server/info e aceita POST /server/files/upload (multipart, campo "file"),
gravando o arquivo em <pasta>. POST /printer/print/start só registra o pedido (e passa o estado
para "printing"); --state define o print_stats.state inicial. No emulador, o endereço do PC é
10.0.2.2.

Uso: scripts/fake-moonraker.py [--port 7125] [--api-key CHAVE] [--state standby] [pasta]
"""
import argparse
import email.parser
import email.policy
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=7125)
    parser.add_argument("--api-key", help="exige o cabeçalho X-Api-Key com este valor")
    parser.add_argument("--state", default="standby", help="print_stats.state informado")
    parser.add_argument("folder", nargs="?", default="fake-moonraker-files")
    args = parser.parse_args()
    os.makedirs(args.folder, exist_ok=True)
    state = {"print_stats": args.state}

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
                                            "moonraker_version": "fake"}})
            elif self.path == "/printer/objects/query?print_stats":
                self.reply(200, {"result": {"status": {"print_stats": {"state": state["print_stats"]}}}})
            else:
                self.reply(404, {"error": {"code": 404, "message": "Not Found"}})

        def do_POST(self):
            if not self.authorized():
                return
            if self.path == "/printer/print/start":
                request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
                print(f"iniciar impressão de {request.get('filename')}", file=sys.stderr)
                state["print_stats"] = "printing"
                self.reply(200, {"result": "ok"})
                return
            if self.path != "/server/files/upload":
                self.reply(404, {"error": {"code": 404, "message": "Not Found"}})
                return
            length = int(self.headers.get("Content-Length", 0))
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
