#!/usr/bin/env python3
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from license_common import DEFAULT_DB, check_license, connect, iso_now

HOST = os.environ.get("LICENSE_HOST", "127.0.0.1")
PORT = int(os.environ.get("LICENSE_PORT", "8787"))
DB_PATH = os.environ.get("LICENSE_DB", DEFAULT_DB)
MAX_BODY = 8192
RATE_LIMIT_WINDOW = 60
RATE_LIMIT_ATTEMPTS = 12
ATTEMPTS_BY_IP = {}


class LicenseHandler(BaseHTTPRequestHandler):
    server_version = "SMCLicense/1.0"

    def do_GET(self):
        if self.path == "/health":
            self.send_json(200, {"ok": True, "time": iso_now()})
            return
        self.send_json(404, {"ok": False, "message": "Not found"})

    def do_POST(self):
        if self.path != "/api/license/check":
            self.send_json(404, {"ok": False, "message": "Not found"})
            return
        try:
            client_ip = self.client_ip()
            if too_many_attempts(client_ip):
                self.send_json(429, {"ok": False, "message": "Слишком много попыток входа. Подожди минуту."})
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY:
                self.send_json(400, {"ok": False, "message": "Bad request size"})
                return
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            hwids = payload.get("hwids", payload.get("hwid", ""))
            password = str(payload.get("password", ""))
            with connect(DB_PATH) as conn:
                result = check_license(conn, hwids, password)
            self.send_json(200, result)
        except json.JSONDecodeError:
            self.send_json(400, {"ok": False, "message": "Invalid JSON"})
        except Exception as exc:
            self.send_json(500, {"ok": False, "message": f"Server error: {exc}"})

    def send_json(self, status: int, body: dict):
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, fmt, *args):
        print(f"{self.client_address[0]} - {fmt % args}", flush=True)

    def client_ip(self) -> str:
        forwarded = self.headers.get("X-Forwarded-For", "")
        if forwarded:
            return forwarded.split(",", 1)[0].strip()
        return self.client_address[0]


def too_many_attempts(ip: str) -> bool:
    now = time.time()
    attempts = [stamp for stamp in ATTEMPTS_BY_IP.get(ip, []) if now - stamp < RATE_LIMIT_WINDOW]
    attempts.append(now)
    ATTEMPTS_BY_IP[ip] = attempts
    return len(attempts) > RATE_LIMIT_ATTEMPTS


def main():
    with connect(DB_PATH):
        pass
    server = ThreadingHTTPServer((HOST, PORT), LicenseHandler)
    print(f"SMC license server listening on {HOST}:{PORT}, db={DB_PATH}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
