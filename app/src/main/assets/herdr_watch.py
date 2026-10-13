# Kabuk's agent watcher: runs on the server over SSH, listens to herdr's event stream and prints one JSON
# line whenever the agents change (instead of Kabuk asking every few seconds). Ends when Kabuk disconnects.
#
# herdr replays its event history on every subscribe, so events are only a hint: after each burst the
# watcher reads the current state and prints it only if it differs from what the phone already has.
import json, os, selectors, socket, sys, time

def sock_path():
    p = os.environ.get("HERDR_SOCKET_PATH")
    if p and os.path.exists(p):
        return p
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, "herdr", "herdr.sock")

def call(method, params):
    s = socket.socket(socket.AF_UNIX)
    s.settimeout(5)
    s.connect(sock_path())
    s.sendall((json.dumps({"id": "k", "method": method, "params": params}) + "\n").encode())
    buf = b""
    while not buf.endswith(b"\n"):
        d = s.recv(1 << 16)
        if not d:
            break
        buf += d
    s.close()
    return json.loads(buf)

def subscribe(subs):
    s = socket.socket(socket.AF_UNIX)
    s.connect(sock_path())
    s.sendall((json.dumps({"id": "w", "method": "events.subscribe", "params": {"subscriptions": subs}}) + "\n").encode())
    s.setblocking(False)
    return s

last = None

def snapshot(force=False):
    global last
    agents = call("agent.list", {})
    workspaces = call("workspace.list", {})
    # the question of every agent waiting for an answer, so the phone can show and answer it
    questions = {}
    for a in agents.get("result", {}).get("agents", []):
        if a.get("agent_status") == "blocked":
            try:
                r = call("pane.read", {"pane_id": a["pane_id"], "source": "recent", "lines": 60})
                questions[a["pane_id"]] = r["result"]["read"]["text"]
            except Exception:
                pass
    out = json.dumps({"agents": agents, "workspaces": workspaces, "questions": questions})
    if out != last or force:
        last = out
        sys.stdout.write(out + "\n")
        sys.stdout.flush()

def pane_ids():
    return set(p["pane_id"] for p in call("pane.list", {}).get("result", {}).get("panes", []))

def watch():
    sel = selectors.DefaultSelector()
    structure = subscribe([{"type": t} for t in (
        "pane.created", "pane.closed", "pane.exited", "pane.agent_detected", "pane.focused",
        "workspace.created", "workspace.closed", "workspace.renamed")])
    sel.register(structure, selectors.EVENT_READ, None)
    # Kabuk disconnecting closes our stdin: stop right away instead of at the next output
    sel.register(sys.stdin, selectors.EVENT_READ, "stdin")
    per_pane = {}  # pane id -> its status subscription

    def sync_panes():
        ids = pane_ids()
        for p in ids - per_pane.keys():
            per_pane[p] = subscribe([{"type": "pane.agent_status_changed", "pane_id": p}])
            sel.register(per_pane[p], selectors.EVENT_READ, p)
        for p in per_pane.keys() - ids:
            sel.unregister(per_pane[p]); per_pane[p].close(); del per_pane[p]

    sync_panes()
    snapshot()
    beat = time.time()
    while True:
        ready = sel.select(timeout=15)
        if not ready:
            # quiet check (sent only if something changed), and a heartbeat once a minute
            sync_panes()
            snapshot(force=time.time() - beat > 60)
            if time.time() - beat > 60:
                beat = time.time()
            continue
        time.sleep(0.15)  # a burst of events becomes one update
        for key, _ in sel.select(timeout=0) or ready:
            if key.data == "stdin":
                if not sys.stdin.buffer.read1(1 << 16):
                    sys.exit(0)
                continue
            try:
                d = key.fileobj.recv(1 << 20)
            except BlockingIOError:
                continue
            if not d:
                if key.data is None:
                    raise OSError("herdr closed the stream")
                sel.unregister(key.fileobj); key.fileobj.close(); per_pane.pop(key.data, None)
        sync_panes()
        snapshot()

while True:
    try:
        watch()
    except BrokenPipeError:
        sys.exit(0)
    except Exception as e:
        try:
            sys.stdout.write(json.dumps({"error": str(e)}) + "\n")
            sys.stdout.flush()
        except BrokenPipeError:
            sys.exit(0)
        time.sleep(5)
