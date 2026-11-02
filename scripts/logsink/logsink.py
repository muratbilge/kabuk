#!/usr/bin/env python3
"""Kabuk live-log receiver (copied from /projects/paperscanner/scripts/logsink). Stdlib only.

POST /log   body: newline-separated JSON log lines (one JSON record each, max 1 MB).
            Auth: X-Timestamp (unix seconds) + X-Signature = hex HMAC-SHA256("<timestamp>\\n<body>", token);
            the token itself is never sent. Requests older/newer than MAX_SKEW are rejected.
            X-Device: label (sanitized). De-duplication: X-Stream + X-Seq-First/X-Seq-Last number the lines;
            lines of a stream that were already stored are skipped (a batch is resent when the reply was lost).
            X-Dropped: lines the phone had to drop (recorded as one marker line).
            X-Once: id of a one-time record (crash report); stored at most once.
GET  /health
Writes <LOGSINK_DIR>/<device>.log, rotated to .log.1 at 20 MB.
Env: LOGSINK_TOKEN (required), LOGSINK_DIR, LOGSINK_BIND (default 127.0.0.1), LOGSINK_PORT (default 8792).
"""
import copy
import hashlib
import hmac
import json
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("LOGSINK_TOKEN", "")
OUT_DIR = os.environ.get("LOGSINK_DIR", "debug")
BIND = os.environ.get("LOGSINK_BIND", "127.0.0.1")
PORT = int(os.environ.get("LOGSINK_PORT", "8792"))
MAX_BODY = 1 << 20
MAX_SKEW = 600
ROTATE_AT = 20 << 20
STATE_FILE = "sink-state.json"

_lock = threading.Lock()
_state = {"seq": {}, "once": []}  # seq: "device/stream" -> highest stored seq; once: recent X-Once ids


def _load_state() -> None:
    try:
        with open(os.path.join(OUT_DIR, STATE_FILE), encoding="utf-8") as f:
            loaded = json.load(f)
        _state["seq"] = {str(k): int(v) for k, v in loaded.get("seq", {}).items()}
        _state["once"] = [str(x) for x in loaded.get("once", [])][-500:]
    except (OSError, ValueError):
        pass


def _save_state() -> None:
    path = os.path.join(OUT_DIR, STATE_FILE)
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(_state, f)
        f.flush()
        os.fsync(f.fileno())
    os.replace(path + ".tmp", path)
    _sync_directory()


def _append(device: str, text: str) -> None:
    path = os.path.join(OUT_DIR, f"{device}.log")
    with open(path, "a", encoding="utf-8") as f:
        f.write(text if text.endswith("\n") else text + "\n")
        f.flush()
        os.fsync(f.fileno())


def _sync_directory():
    descriptor = os.open(OUT_DIR, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _recover_pending():
    """Replay one durable batch at its original byte offset; retries cannot lose or duplicate lines."""
    global _state
    journal = os.path.join(OUT_DIR, "pending.json")
    if not os.path.exists(journal):
        return
    with open(journal, encoding="utf-8") as source:
        pending = json.load(source)
    path = os.path.join(OUT_DIR, pending["device"] + ".log")
    with open(path, "r+b" if os.path.exists(path) else "w+b") as target:
        target.truncate(pending["offset"])
    if pending["text"]:
        _append(pending["device"], pending["text"])
    previous = _state
    _state = pending["state"]
    try:
        _save_state()
    except OSError:
        _state = previous
        raise
    os.unlink(journal)
    _sync_directory()


def _persist_batch(device, text, state):
    path = os.path.join(OUT_DIR, device + ".log")
    if os.path.exists(path) and os.path.getsize(path) > ROTATE_AT:
        os.replace(path, path + ".1")
        _sync_directory()
    offset = os.path.getsize(path) if os.path.exists(path) else 0
    journal = os.path.join(OUT_DIR, "pending.json")
    with open(journal + ".tmp", "w", encoding="utf-8") as target:
        json.dump({"device": device, "text": text, "offset": offset, "state": state}, target)
        target.flush()
        os.fsync(target.fileno())
    os.replace(journal + ".tmp", journal)
    _sync_directory()
    _recover_pending()


class Handler(BaseHTTPRequestHandler):
    server_version = "kabuk-logsink"

    def log_message(self, fmt, *args):  # no request logging (paths/IPs are not needed)
        pass

    def _reply(self, code: int, body: bytes = b"") -> None:
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        self._reply(200, b"ok\n") if self.path == "/health" else self._reply(404)

    def _authorized(self, body: bytes) -> bool:
        ts, sig = self.headers.get("X-Timestamp", ""), self.headers.get("X-Signature", "")
        if not TOKEN or not ts.isdigit() or abs(time.time() - int(ts)) > MAX_SKEW:
            return False
        expected = hmac.new(TOKEN.encode(), ts.encode() + b"\n" + body, hashlib.sha256).hexdigest()
        return hmac.compare_digest(expected, sig)

    def do_POST(self):
        if self.path != "/log":
            return self._reply(404)
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return self._reply(413)
        body = self.rfile.read(length)
        if not self._authorized(body):
            return self._reply(403)
        device = re.sub(r"[^A-Za-z0-9_.-]", "_", self.headers.get("X-Device", "phone"))[:40] or "phone"
        lines = body.decode("utf-8", "replace").splitlines()
        with _lock:
            try:
                _recover_pending()
                previous = copy.deepcopy(_state)
                fresh = self._dedupe(device, lines)
                candidate = copy.deepcopy(_state)
                _state.clear()
                _state.update(previous)
                if fresh is None:
                    return self._reply(400)
                if fresh:
                    _persist_batch(device, "\n".join(fresh), candidate)
            except OSError as e:
                print(f"write failed: {e.__class__.__name__}", file=sys.stderr, flush=True)
                return self._reply(500)
        self._reply(204)

    def _dedupe(self, device: str, lines: list):
        """Called with _lock held. Returns the lines not stored before, or None for a malformed sequence."""
        once = self.headers.get("X-Once", "")
        if once:
            if once in _state["once"]:
                return []
            _state["once"] = (_state["once"] + [once])[-500:]
            return lines
        prefix = []
        dropped = self.headers.get("X-Dropped", "0")
        if dropped.isdigit() and int(dropped) > 0:
            prefix = [json.dumps({"sink": "dropped", "count": int(dropped)})]
        stream, first, last = (self.headers.get(h, "") for h in ("X-Stream", "X-Seq-First", "X-Seq-Last"))
        if not stream:
            return prefix + lines
        if not (first.isdigit() and last.isdigit()) or int(last) - int(first) + 1 != len(lines):
            return None
        key = f"{device}/{stream[:64]}"
        stored = _state["seq"].get(key, -1)
        start = int(first)
        fresh = [line for i, line in enumerate(lines) if start + i > stored]
        _state["seq"][key] = max(stored, int(last))
        return prefix + fresh if fresh else []


def serve() -> ThreadingHTTPServer:
    if not TOKEN:
        sys.exit("LOGSINK_TOKEN is required")
    os.makedirs(OUT_DIR, exist_ok=True)
    _load_state()
    _recover_pending()
    return ThreadingHTTPServer((BIND, PORT), Handler)


if __name__ == "__main__":
    srv = serve()
    print(f"kabuk logsink on {BIND}:{PORT} -> {OUT_DIR}", flush=True)
    srv.serve_forever()
