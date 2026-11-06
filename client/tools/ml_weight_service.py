#!/usr/bin/env python3
"""
Local ML weight microservice for SMC Chart.

Run:
  python tools/ml_weight_service.py

Then start Java with:
  -Dsmc.ml.bridge.url=http://127.0.0.1:8765/weights
  -Dsmc.ml.bridge.transport=binary
  -Dsmc.ml.bridge.fast.url=tcp://127.0.0.1:8766

The service intentionally uses Python stdlib so it works before XGBoost or
PyTorch are installed. Replace `predict_weights` with a real model later.
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
import socketserver
import struct
import threading
import time
from pathlib import Path


BINARY_MAGIC = 0x534D434D  # SMCM
BINARY_VERSION = 1
MAX_FRAME_BYTES = 4096
BRIDGE_HOST = os.environ.get("SMC_ML_BRIDGE_HOST", "127.0.0.1")
HTTP_PORT = int(os.environ.get("SMC_ML_BRIDGE_HTTP_PORT", "8765"))
BINARY_PORT = int(os.environ.get("SMC_ML_BRIDGE_BINARY_PORT", "8766"))
PROJECT_ROOT = Path(os.environ.get("SMC_PROJECT_ROOT", Path(__file__).resolve().parents[1]))
WEIGHTS_ROOT = Path(os.environ.get("SMC_WEIGHTS_ROOT", PROJECT_ROOT / "weights"))
MODEL_PATH = WEIGHTS_ROOT / "ml_model.json"
_MODEL = None
_MODEL_MTIME = 0.0


def clamp(value, lo=0.2, hi=2.2):
    return max(lo, min(hi, float(value)))


def predict_weights(features):
    learned = learned_weights(features)
    if learned:
        return learned

    smc = float(features.get("smcScore", 50.0))
    oi = float(features.get("openInterestChangePct", 0.0))
    funding = float(features.get("fundingRate", 0.0))
    dom = float(features.get("domImbalanceRatio", 1.0))
    spoof = bool(features.get("spoofingDetected", False))
    regime_score = float(features.get("regimeScore", 50.0))
    regime_type = str(features.get("regimeType", "")).lower()

    displacement = 1.0 + max(0.0, smc - 70.0) / 180.0
    liquidity = 1.0 + min(0.35, abs(funding) * 520.0) + (0.12 if abs(oi) >= 3.0 else 0.0)
    order_flow = 1.0 + min(0.30, abs(math.log(max(dom, 0.05))) * 0.18) + (0.08 if spoof else 0.0)
    volume = 1.0 + (0.12 if oi > 2.0 else -0.06 if oi < -4.0 else 0.0)
    regime = 1.0 + max(-0.25, min(0.25, (regime_score - 50.0) / 160.0))
    if "chop" in regime_type:
        displacement -= 0.12
        volume -= 0.08
        regime -= 0.15

    return {
        "displacement": clamp(displacement),
        "volume": clamp(volume),
        "liquidity": clamp(liquidity),
        "orderFlow": clamp(order_flow),
        "regime": clamp(regime),
    }


def learned_weights(features):
    model = load_model()
    if not model:
        return None
    symbol = safe_symbol(features.get("symbol", ""))
    symbols = model.get("symbols", {})
    entry = symbols.get(symbol)
    if not entry:
        return None
    weights = entry.get("weights", {})
    if not weights:
        return None
    return {
        "displacement": clamp(weights.get("displacement", 1.0)),
        "volume": clamp(weights.get("volume", 1.0)),
        "liquidity": clamp(weights.get("liquidity", 1.0)),
        "orderFlow": clamp(weights.get("orderFlow", 1.0)),
        "regime": clamp(weights.get("regime", 1.0)),
    }


def safe_symbol(value):
    text = str(value or "UNKNOWN").upper()
    return "".join(ch for ch in text if ch.isalnum() or ch in "_-") or "UNKNOWN"


def load_model():
    global _MODEL, _MODEL_MTIME
    try:
        stat = MODEL_PATH.stat()
    except OSError:
        _MODEL = None
        _MODEL_MTIME = 0.0
        return None
    if _MODEL is not None and stat.st_mtime <= _MODEL_MTIME:
        return _MODEL
    try:
        _MODEL = json.loads(MODEL_PATH.read_text(encoding="utf-8"))
        _MODEL_MTIME = stat.st_mtime
        return _MODEL
    except Exception:
        _MODEL = None
        _MODEL_MTIME = time.time()
        return None


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/weights":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        try:
            features = json.loads(body or "{}")
            response = predict_weights(features)
            payload = json.dumps(response).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except Exception as exc:
            payload = json.dumps({"error": str(exc)}).encode("utf-8")
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    def log_message(self, fmt, *args):
        return


class BinaryWeightHandler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            size_bytes = self._read_exact(4)
            if not size_bytes:
                return
            (size,) = struct.unpack(">I", size_bytes)
            if size <= 0 or size > MAX_FRAME_BYTES:
                return
            frame = self._read_exact(size)
            features = parse_binary_features(frame)
            weights = predict_weights(features)
            payload = struct.pack(
                ">IBddddd",
                BINARY_MAGIC,
                BINARY_VERSION,
                float(weights["displacement"]),
                float(weights["volume"]),
                float(weights["liquidity"]),
                float(weights["orderFlow"]),
                float(weights["regime"]),
            )
            self.request.sendall(struct.pack(">I", len(payload)) + payload)
        except Exception:
            return

    def _read_exact(self, size):
        chunks = bytearray()
        while len(chunks) < size:
            chunk = self.request.recv(size - len(chunks))
            if not chunk:
                return b""
            chunks.extend(chunk)
        return bytes(chunks)


class ThreadingBinaryServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True


def parse_binary_features(frame):
    offset = 0

    def read(fmt):
        nonlocal offset
        size = struct.calcsize(fmt)
        values = struct.unpack_from(fmt, frame, offset)
        offset += size
        return values[0] if len(values) == 1 else values

    def read_text():
        nonlocal offset
        if offset + 2 > len(frame):
            raise ValueError("string length out of frame")
        (length,) = struct.unpack_from(">H", frame, offset)
        offset += 2
        end = offset + length
        if end > len(frame):
            raise ValueError("string out of frame")
        text = frame[offset:end].decode("utf-8")
        offset = end
        return text

    magic = read(">I")
    version = read(">B")
    if magic != BINARY_MAGIC or version != BINARY_VERSION:
        raise ValueError("unsupported ML binary frame")

    symbol = read_text()
    smc = read(">i")
    oi = read(">d")
    funding = read(">d")
    dom = read(">d")
    spoof = bool(read(">?"))
    regime_score = read(">i")
    regime_type = read_text()

    return {
        "symbol": symbol,
        "smcScore": smc,
        "openInterestChangePct": oi,
        "fundingRate": funding,
        "domImbalanceRatio": dom,
        "spoofingDetected": spoof,
        "regimeScore": regime_score,
        "regimeType": regime_type,
    }


def start_binary_server():
    server = ThreadingBinaryServer((BRIDGE_HOST, BINARY_PORT), BinaryWeightHandler)
    print(f"SMC ML binary bridge listening on tcp://{BRIDGE_HOST}:{BINARY_PORT}")
    server.serve_forever()


if __name__ == "__main__":
    threading.Thread(target=start_binary_server, daemon=True).start()
    server = ThreadingHTTPServer((BRIDGE_HOST, HTTP_PORT), Handler)
    print(f"SMC ML HTTP fallback listening on http://{BRIDGE_HOST}:{HTTP_PORT}/weights")
    server.serve_forever()
