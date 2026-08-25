#!/usr/bin/env python3
"""Probe whether TWO `codex app-server` processes can hold the SAME thread, and what a rollout fd means.

Answers the two unknowns that gate external-Codex liveness detection in the daemon (the "B6" questions
from the 2026-08-09 running-dot investigation):

  Q1  A second app-server doing `thread/resume` on a thread a FIRST, still-live server is holding:
      does it error, silently fork, or let both write the same rollout? This decides whether an
      externally-running Codex session (ChatGPT.app, a terminal `codex`) may be surfaced as a
      resumable "running" row in cc-pocket, or only as a read-only "busy elsewhere" one.

  Q2  Is the rollout `.jsonl` fd held only while a turn EXECUTES, or for as long as the thread stays
      loaded? That fd is the liveness signal the daemon would key on, so its granularity decides
      whether it maps to DirectoryEntry.open or to ActiveSession.executing.

Safety: everything runs in a fresh temp dir with sandbox=read-only + approvalPolicy=never, on threads
this script creates itself. It never resumes, writes to, or otherwise touches a pre-existing session.
Costs ~3 tiny model turns on the logged-in account.

Usage:
    python3 scripts/probe-codex-concurrent.py [workdir]

Speaks the exact dialect daemon/.../codex/CodexBackend.kt speaks: bare {id, method, params} objects
with NO "jsonrpc" field. Re-run after every Codex CLI upgrade — app-server is experimental and drifts.
"""
import json, os, queue, subprocess, sys, tempfile, threading, time

CODEX = os.environ.get("CC_POCKET_CODEX_BIN") or "codex"
CWD = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else tempfile.mkdtemp(prefix="codex-concurrent-probe-")
LOG = os.path.join(CWD, "codex-concurrent-probe-%d.jsonl" % time.time())
SESSIONS_ROOT = os.path.join(os.environ.get("CODEX_HOME") or os.path.expanduser("~/.codex"), "sessions")
TURN_TIMEOUT = 120.0
RPC_TIMEOUT = 60.0

log = open(LOG, "w", encoding="utf-8")


def record(tag, line):
    log.write("%s %s\n" % (tag, line))
    log.flush()


class Server:
    """One `codex app-server` child, with a reader thread demuxing responses from notifications."""

    def __init__(self, name):
        self.name = name
        self.proc = subprocess.Popen(
            [CODEX, "app-server"], cwd=CWD,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        self._id = 0
        self._replies = {}          # id -> queue, so a request can await exactly its own reply
        self._lock = threading.Lock()
        self.notes = queue.Queue()  # every server->client notification, in arrival order
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._read_err, daemon=True).start()

    def _read(self):
        for line in self.proc.stdout:
            line = line.strip()
            if not line:
                continue
            record("<<<%s" % self.name, line)
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if "id" in msg and ("result" in msg or "error" in msg):
                with self._lock:
                    q = self._replies.pop(msg["id"], None)
                if q:
                    q.put(msg)
            elif "method" in msg:
                self.notes.put(msg)

    def _read_err(self):
        for line in self.proc.stderr:
            record("!!!%s" % self.name, line.rstrip())

    def _write(self, obj):
        line = json.dumps(obj, ensure_ascii=False)
        record(">>>%s" % self.name, line)
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()

    def request(self, method, params=None, timeout=RPC_TIMEOUT):
        """Send a request and block for ITS reply. Returns the whole envelope (result OR error)."""
        with self._lock:
            self._id += 1
            rid = self._id
            q = queue.Queue()
            self._replies[rid] = q
        obj = {"id": rid, "method": method}
        if params is not None:
            obj["params"] = params
        self._write(obj)
        try:
            return q.get(timeout=timeout)
        except queue.Empty:
            return {"id": rid, "error": {"code": None, "message": "TIMEOUT after %.0fs" % timeout}}

    def notify(self, method, params=None):
        obj = {"method": method}
        if params is not None:
            obj["params"] = params
        self._write(obj)

    def wait_note(self, methods, timeout):
        """Drain notifications until one of [methods] shows up. Returns it, or None on timeout."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                msg = self.notes.get(timeout=min(1.0, max(0.05, deadline - time.time())))
            except queue.Empty:
                continue
            if msg.get("method") in methods:
                return msg
        return None

    def handshake(self):
        r = self.request("initialize", {
            "clientInfo": {"name": "cc-pocket-probe", "version": "0"},
            "capabilities": {"experimentalApi": False},
        })
        self.notify("initialized")
        return r

    def alive(self):
        return self.proc.poll() is None

    def kill(self):
        if self.alive():
            self.proc.kill()


def rollout_for(thread_id):
    """The rollout file whose name ends in -<thread_id>.jsonl (CodexPaths.findSession's rule)."""
    for root, _dirs, files in os.walk(SESSIONS_ROOT):
        for f in files:
            if f.endswith("-%s.jsonl" % thread_id):
                return os.path.join(root, f)
    return None


def fd_holders(path):
    """pids holding [path] open. lsof exits 1 for 'no results', which is not an error here."""
    if not path:
        return set()
    try:
        out = subprocess.run(["lsof", "-t", "--", path], capture_output=True, text=True, timeout=10).stdout
    except Exception:
        return set()
    return {int(x) for x in out.split() if x.strip().isdigit()}


def ps_of(pid):
    """(ppid, command) for [pid] — the fd holder is NOT necessarily the app-server itself, and a
    detector that only enumerates top-level `codex app-server` pids would miss a worker child."""
    try:
        out = subprocess.run(["ps", "-o", "ppid=,command=", "-p", str(pid)],
                             capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        return None, ""
    if not out:
        return None, ""
    ppid, _, cmd = out.strip().partition(" ")
    return (int(ppid) if ppid.strip().isdigit() else None), cmd.strip()


def who(pids, servers):
    """Render a pid set, naming probe servers and resolving strangers to ppid + command."""
    named = {s.proc.pid: s.name for s in servers}
    out = []
    for p in sorted(pids):
        if p in named:
            out.append("%s(pid %d, the app-server itself)" % (named[p], p))
            continue
        ppid, cmd = ps_of(p)
        owner = named.get(ppid)
        out.append("pid %d [%s] ppid=%s%s" % (
            p, cmd[:70] or "<gone>", ppid, " = child of %s" % owner if owner else ""))
    return out or ["<nobody>"]


def start_thread(srv):
    r = srv.request("thread/start", {
        "cwd": CWD,
        "approvalPolicy": "never",   # non-interactive: the probe can never block on an approval
        "sandbox": "read-only",      # and can never write anything outside the temp dir
    })
    thread = (r.get("result") or {}).get("thread") or {}
    return thread.get("id"), r


def run_turn(srv, text):
    """One turn, awaited to a terminal notification. Returns (outcome, envelope)."""
    r = srv.request("turn/start", {"threadId": srv.thread_id, "input": [{"type": "text", "text": text}]})
    if "error" in r:
        return "rpc-error", r
    note = srv.wait_note({"turn/completed", "turn/failed", "error"}, TURN_TIMEOUT)
    if note is None:
        return "no-terminal-notification", r
    return note.get("method"), note


def main():
    findings = []

    def note(q, answer):
        findings.append((q, answer))
        print("  → %s" % answer)

    print("probe workdir : %s" % CWD)
    print("codex binary  : %s" % CODEX)
    print("wire log      : %s\n" % LOG)

    a = Server("A")
    servers = [a]
    try:
        a.handshake()
        a.thread_id, started = start_thread(a)
        if not a.thread_id:
            print("!! thread/start gave no thread id — app-server API drifted. Envelope:")
            print(json.dumps(started, indent=2)[:2000])
            return 1
        print("A thread      : %s (pid %d)" % (a.thread_id, a.proc.pid))

        # ── Q2: what does holding the fd actually mean? ───────────────────────────────────────────
        print("Q2  fd granularity")
        # A thread that has started but never taken a turn: does it exist on disk at all yet? An
        # fd-based detector can only ever see sessions that reached this point.
        pre_turn = rollout_for(a.thread_id)
        print("  after thread/start, before any turn : %s" %
              ("rollout %s" % os.path.basename(pre_turn) if pre_turn else "NO ROLLOUT ON DISK YET"))
        idle_before = fd_holders(pre_turn)

        outcome, _ = run_turn(a, "Reply with exactly: OK")
        print("  turn 1 outcome                     : %s" % outcome)

        roll = None
        for _ in range(40):  # the rollout is created lazily — with the first turn, not with the thread
            roll = rollout_for(a.thread_id)
            if roll:
                break
            time.sleep(0.25)
        print("  rollout after turn 1               : %s" % (roll or "<STILL not found>"))
        if not roll:
            print("\n!! no rollout even after a completed turn — an fd-based liveness signal cannot")
            print("   exist for this Codex build. Re-check CODEX_HOME and CodexPaths' layout.")
            return 1

        time.sleep(0.5)
        idle_after = fd_holders(roll)
        print("  after turn/completed (thread idle) : %s" % who(idle_after, servers))
        # The holder may be a WORKER CHILD rather than the app-server, so ask "does anyone hold it",
        # then report who — an implementation has to enumerate whatever that turns out to be.
        if idle_after:
            holders = [(p, ps_of(p)) for p in sorted(idle_after)]
            kinds = {"codex-named" if "codex" in cmd.lower() else "NOT-codex-named"
                     for _p, (_pp, cmd) in holders}
            note("Q2", "fd IS still held while the thread sits IDLE → it means 'loaded in a live "
                       "process', so it maps to DirectoryEntry.open, NOT ActiveSession.executing "
                       "(executing still needs the rollout-mtime heuristic). Holder command is %s."
                       % "/".join(sorted(kinds)))
            for p, (pp, cmd) in holders:
                findings.append(("Q2-holder", "pid %d ppid=%s → %s" % (p, pp, cmd[:90])))
        else:
            note("Q2", "fd is NOT held once the turn ends → it may be usable as an executing signal; "
                       "re-check whether it is held DURING a long turn before relying on it")
        if not pre_turn:
            findings.append(("Q2b", "a thread that never took a turn has NO rollout on disk → an "
                                    "fd/rollout detector is blind to a just-opened external session "
                                    "until its first turn persists"))
            print("  → %s" % findings[-1][1])

        # ── Q1: can a second app-server take the same thread? ────────────────────────────────────
        print("\nQ1  concurrent thread/resume (A stays alive and holds the thread)")
        b = Server("B")
        servers.append(b)
        b.handshake()
        r = b.request("thread/resume", {"threadId": a.thread_id})
        b_err = r.get("error")
        if b_err:
            print("  B thread/resume  : REFUSED %s" % json.dumps(b_err, ensure_ascii=False))
            note("Q1", "the second app-server is REFUSED while the first holds the thread "
                       "→ safe: cc-pocket cannot double-drive, so an external session must be shown "
                       "as 'busy elsewhere', not as a resumable running row")
        else:
            b.thread_id = ((r.get("result") or {}).get("thread") or {}).get("id") or a.thread_id
            both = fd_holders(roll)
            print("  B thread/resume  : ACCEPTED (thread id %s)" % b.thread_id)
            print("  fd holders now   : %s" % who(both, servers))
            b_outcome, _ = run_turn(b, "Reply with exactly: TWO")
            print("  B turn outcome   : %s" % b_outcome)
            a_outcome, _ = run_turn(a, "Reply with exactly: THREE")
            print("  A turn AFTER B   : %s (A alive: %s)" % (a_outcome, a.alive()))
            same_file = b.thread_id == a.thread_id
            if same_file and b_outcome == "turn/completed" and a_outcome == "turn/completed":
                note("Q1", "BOTH servers drove the SAME thread and both turns succeeded "
                           "→ silent dual-write, the dangerous case: cc-pocket must NOT offer to resume "
                           "an externally-held session (interleaved rollout, lost context)")
            elif not same_file:
                note("Q1", "resume produced a DIFFERENT thread id (%s vs %s) → it forks rather than "
                           "shares; safe for the rollout but a tap would silently branch the user's "
                           "session" % (b.thread_id, a.thread_id))
            else:
                note("Q1", "resume accepted but the turns did not both complete "
                           "(B=%s, A=%s) → partial/degraded sharing; treat as unsafe" % (b_outcome, a_outcome))

            tail = os.path.getsize(roll)
            print("  rollout size     : %d bytes" % tail)

        print("\n" + "=" * 78)
        print("VERDICT")
        for q, answer in findings:
            print("  %-3s %s" % (q, answer))
        print("=" * 78)
        print("\nfull wire: %s" % LOG)
        return 0
    finally:
        for s in servers:
            s.kill()
        log.close()


if __name__ == "__main__":
    sys.exit(main())
