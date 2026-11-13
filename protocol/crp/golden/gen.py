#!/usr/bin/env python3
"""Golden vectors for CRP/1 (claude-remote protocol), spec: B0-spec.md revision 2 (docs/crd.md links it).

Since B1a this directory (protocol/crp/golden/ in the repo) is the source of truth. The only change against
revision 2 is the `modelInput` key: the inputs of the reference models (ring operations, transcript contents,
the reference negotiation parameters) recorded next to `expect`, so the Go and Kotlin tests replay them instead
of re-typing build_vectors(). Frames, streams and expect are byte-for-byte unchanged, except the login junk of
vector 01, which names the documentation address 192.0.2.1 (RFC 5737) instead of a LAN address (B1a review A-F18).

Run:  python3 -I gen.py            (writes NN-*.json + index.json next to this file, then re-verifies them)
      python3 -I gen.py --check    (only verifies the files on disk; exit 1 on any mismatch)

Everything a vector claims is COMPUTED here: frame bytes by the reference encoder, the decoded form by
the reference decoder (round trip asserted, per connection and direction), and every "expect" block by the
small reference models below (version negotiation, connection table / supersede, resume ring, ack guard,
transcript watch, input dedup + pacing, rpc exec deny-scan, window-size owner). The Go daemon and the
Kotlin client MUST both pass every vector (spec section 10).
No randomness: ids, epochs and nonces are fixed strings.

Pure stdlib, no network, writes only into this directory.
"""
import hashlib
import json
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# --------------------------------------------------------------------------------------------------
# Framing (spec section 3)
# --------------------------------------------------------------------------------------------------
MAGIC = bytes([0x89, 0x43, 0x52, 0x50, 0x0D, 0x0A, 0x1A, 0x0A])  # "\x89CRP\r\n\x1a\n"
JUNK_LIMIT = 65536            # bytes a client skips before the magic (login-shell noise)
DEFAULT_MAX_FRAME = 1 << 20   # value of the length field, i.e. bytes after it
MIN_MAX_FRAME = 65536         # a HELLO below this is refused (every limit below is sized to fit it)
HARD_MAX_FRAME = 16 << 20
PRE_NEGOTIATION_MAX = 65536   # first frame of each direction (HELLO / WELCOME / BYE)
PIECE = 49152                 # max blob bytes of one TX / TOUT / RPCPART / RPCRES frame (fits MIN_MAX_FRAME)
FLAG_BLOB = 0x01

TYPES = {
    0x01: "HELLO", 0x02: "WELCOME", 0x03: "BYE", 0x04: "PING", 0x05: "PONG", 0x06: "ERR", 0x07: "STATE",
    0x10: "SUB", 0x11: "UNSUB", 0x12: "ACK",
    0x20: "EV", 0x21: "SNAP", 0x22: "TX",
    0x30: "TOUT", 0x31: "TSTATE", 0x32: "TSIZE",
    0x40: "IN", 0x41: "INACK", 0x42: "INSKIP",
    0x50: "RPC", 0x51: "RPCRES", 0x52: "RPCPART",
}
CODE = {v: k for k, v in TYPES.items()}
C2S, S2C, BOTH = {"c2s"}, {"s2c"}, {"c2s", "s2c"}
DIRS = {
    "HELLO": C2S, "WELCOME": S2C, "BYE": BOTH, "PING": BOTH, "PONG": BOTH, "ERR": S2C, "STATE": C2S,
    "SUB": C2S, "UNSUB": C2S, "ACK": C2S, "EV": S2C, "SNAP": S2C, "TX": S2C,
    "TOUT": S2C, "TSTATE": S2C, "TSIZE": C2S, "IN": C2S, "INACK": S2C, "INSKIP": C2S,
    "RPC": C2S, "RPCRES": S2C, "RPCPART": S2C,
}
BLOB_TYPES = {"EV", "TX", "TOUT", "IN", "RPC", "RPCRES", "RPCPART"}
FIRST_FRAME = {"c2s": {"HELLO"}, "s2c": {"WELCOME", "BYE"}}

REQUIRED = {
    "HELLO": ["proto", "dev", "link", "att", "ie", "app", "feats", "maxFrame"],
    "WELCOME": ["proto", "conn", "srv", "feats", "maxFrame", "now", "host", "streams", "inGen"],
    "BYE": ["code"],
    "PING": ["t"], "PONG": ["t"],
    "ERR": ["code", "re"],
    "STATE": ["fg"],
    "SUB": ["st"], "UNSUB": ["st"], "ACK": ["acks"],
    "EV": ["st", "ep", "seq"],
    "SNAP": ["st", "ep", "seq", "from", "reason"],
    "TX": ["wid", "u", "f", "o", "enc"],
    "TOUT": ["sid", "tseq"],
    "TSTATE": ["sid", "state"],
    "TSIZE": ["sid", "cols", "rows"],
    "IN": ["sid", "iseq", "exp"],
    "INACK": ["sid", "iseq", "st"],
    "INSKIP": ["sid", "to"],
    "RPC": ["id", "m", "tag", "cmd", "ms"],
    "RPCRES": ["id", "ok"],
    "RPCPART": ["id", "part"],
}
# Top-level header keys with a fixed JSON type wherever they appear (nested objects are not checked here).
INT_FIELDS = {"hi", "at", "seq", "iseq", "tseq", "f", "o", "t", "maxFrame", "cols", "rows", "part", "att", "id", "ms", "to",
              "from", "now", "pingMs", "retryMs", "min", "max", "exp"}
STR_FIELDS = {"st", "ep", "sid", "dev", "link", "ie", "code", "re", "tag", "cmd", "m", "wid", "u", "enc", "reason",
              "state", "app", "srv", "conn", "inGen", "owner", "via", "orig", "rule", "msg"}
BOOL_FIELDS = {"fg", "ok", "last", "more", "reset", "redraw", "active"}
NONNEG_INT = INT_FIELDS - {"t"}


class ProtoError(Exception):
    def __init__(self, code, msg):
        super().__init__(f"{code}: {msg}")
        self.code = code


def canon(header):
    """Canonical header JSON: sorted keys (code point), no whitespace, UTF-8 not \\u-escaped (U+2028, <, >, & raw);
    only '"', '\\' and C0 controls are escaped (\\b \\t \\n \\f \\r short forms, others \\u00xx lowercase)."""
    return json.dumps(header, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def encode(type_name, header, blob=None):
    h = canon(header) if header else b""
    if len(h) > 0xFFFF:
        raise ProtoError("E_FRAME", "header over 65535 bytes")
    flags = FLAG_BLOB if blob is not None else 0
    body = struct.pack(">BBH", CODE[type_name], flags, len(h)) + h + (blob or b"")
    return struct.pack(">I", len(body)) + body


def validate(name, header, has_blob):
    for k in REQUIRED[name]:
        if k not in header:
            raise ProtoError("E_PROTO", f"{name} without '{k}'")
    for k, v in header.items():
        if k in INT_FIELDS and (not isinstance(v, int) or isinstance(v, bool) or (k in NONNEG_INT and v < 0)
                                or abs(v) > (1 << 53)):
            raise ProtoError("E_PROTO", f"{name}.{k} is not a valid integer")
        if k in STR_FIELDS and not isinstance(v, str):
            raise ProtoError("E_PROTO", f"{name}.{k} is not a string")
        if k in BOOL_FIELDS and not isinstance(v, bool):
            raise ProtoError("E_PROTO", f"{name}.{k} is not a boolean")
    if has_blob and name not in BLOB_TYPES:
        raise ProtoError("E_PROTO", f"{name} carries a blob")
    if name in ("TX", "TOUT", "IN") and not has_blob:
        raise ProtoError("E_PROTO", f"{name} without blob")
    if name == "EV":
        st = header.get("st")
        if st == "ev" and not has_blob:
            raise ProtoError("E_PROTO", "EV(ev) without blob")
        if st == "sess" and ("ops" not in header or has_blob):
            raise ProtoError("E_PROTO", "EV(sess) needs ops in the header and no blob")
        if st not in ("ev", "sess"):
            raise ProtoError("E_PROTO", f"EV of unknown stream {st!r}")


def decode_one(buf, pos, max_frame, direction):
    """Decode one frame at buf[pos:]. Returns (frame_dict, new_pos) or (None, pos) when incomplete."""
    if len(buf) - pos < 4:
        return None, pos
    (ln,) = struct.unpack_from(">I", buf, pos)
    if ln < 4:
        raise ProtoError("E_FRAME", f"length {ln} < 4")
    if ln > max_frame:
        raise ProtoError("E_FRAME", f"length {ln} > maxFrame {max_frame}")
    if len(buf) - pos - 4 < ln:
        return None, pos
    t, flags, hlen = struct.unpack_from(">BBH", buf, pos + 4)
    if flags & ~FLAG_BLOB:
        raise ProtoError("E_PROTO", f"reserved flag bits 0x{flags:02x}")
    if 4 + hlen > ln:
        raise ProtoError("E_FRAME", "header longer than frame")
    hraw = bytes(buf[pos + 8: pos + 8 + hlen])
    blob = bytes(buf[pos + 8 + hlen: pos + 4 + ln])
    has_blob = bool(flags & FLAG_BLOB)
    if not has_blob and blob:
        raise ProtoError("E_PROTO", "bytes after header without the blob flag")
    if t not in TYPES:
        if t >= 0x80:  # extension range: skip silently
            return {"type": t, "typeName": None, "ignored": True}, pos + 4 + ln
        raise ProtoError("E_PROTO", f"unknown core type 0x{t:02x}")
    name = TYPES[t]
    if direction not in DIRS[name]:
        raise ProtoError("E_PROTO", f"{name} in direction {direction}")
    try:
        header = json.loads(hraw.decode("utf-8")) if hlen else {}
    except (UnicodeDecodeError, ValueError) as e:
        raise ProtoError("E_PROTO", f"header is not UTF-8 JSON ({e.__class__.__name__})")
    if not isinstance(header, dict):
        raise ProtoError("E_PROTO", "header is not a JSON object")
    validate(name, header, has_blob)
    fr = {"type": t, "typeName": name, "flags": flags, "header": header}
    if has_blob:
        fr["blobHex"] = blob.hex()
    return fr, pos + 4 + ln


def decode_stream(buf, direction, max_frame=DEFAULT_MAX_FRAME, excerpt=False, skip_junk=False):
    """Decode ONE direction of ONE connection. Returns {"junkSkipped": n, "frames": [...]} or raises ProtoError.
    excerpt=False: the first frame is the handshake frame of that direction (<= 64 KiB, HELLO resp. WELCOME/BYE).
    excerpt=True: the bytes start after a handshake that negotiated `max_frame` (used by mid-connection vectors)."""
    pos = 0
    junk = 0
    if skip_junk:
        idx = buf.find(MAGIC, 0, JUNK_LIMIT + len(MAGIC))
        if idx < 0:
            raise ProtoError("E_NO_DAEMON", f"no CRP magic in the first {JUNK_LIMIT} bytes")
        junk = idx
        pos = idx + len(MAGIC)
    else:
        if buf[: len(MAGIC)] != MAGIC:
            raise ProtoError("E_PROTO", "stream does not start with the CRP magic")
        pos = len(MAGIC)
    frames = []
    first = not excerpt
    while pos < len(buf):
        limit = PRE_NEGOTIATION_MAX if first else max_frame
        fr, npos = decode_one(buf, pos, limit, direction)
        if fr is None:
            raise ProtoError("E_FRAME", "truncated frame at end of buffer")
        if first and fr.get("typeName") not in FIRST_FRAME[direction]:
            raise ProtoError("E_PROTO", f"first frame {fr.get('typeName')} is not a handshake frame of {direction}")
        frames.append(fr)
        pos = npos
        first = False
    return {"junkSkipped": junk, "frames": frames}


# --------------------------------------------------------------------------------------------------
# Reference models (spec sections 2, 4, 5, 6)
# --------------------------------------------------------------------------------------------------
SERVER_PROTO = (1, 1)  # (min, max) this daemon speaks
SERVER_FEATS = ["ev", "sess", "tx", "term", "in", "rpc.exec", "gzip"]


def negotiate(hello, server_proto=SERVER_PROTO, server_feats=SERVER_FEATS, server_max_frame=DEFAULT_MAX_FRAME):
    common = [p for p in hello["proto"] if server_proto[0] <= p <= server_proto[1]]
    if not common:
        return {"result": "BYE", "code": "E_VERSION", "min": server_proto[0], "max": server_proto[1]}
    if int(hello["maxFrame"]) < MIN_MAX_FRAME:
        return {"result": "BYE", "code": "E_PROTO", "msg": f"maxFrame below {MIN_MAX_FRAME}"}
    feats = [f for f in server_feats if f in hello["feats"]]  # server order, intersection
    return {"result": "WELCOME", "proto": max(common), "feats": feats,
            "maxFrame": min(int(hello["maxFrame"]), server_max_frame, HARD_MAX_FRAME)}


class ConnTable:
    """Daemon side, spec 2.5: one connection per (dev, link); a higher `att` supersedes, a lower/equal one is STALE."""

    def __init__(self):
        self.by_key = {}

    def hello(self, conn, dev, link, att):
        key = (dev, link)
        cur = self.by_key.get(key)
        if cur is None:
            self.by_key[key] = (conn, att)
            return {"accept": conn}
        if att > cur[1]:
            self.by_key[key] = (conn, att)
            return {"accept": conn, "superseded": cur[0]}
        return {"reject": conn, "code": "STALE", "current": cur[0]}


EV_SOURCES = ["events", "notify", "legacy", "forget"]  # seeding order at daemon start (spec 4.4)


class Ring:
    """Per-host durable stream (ev or sess). `last` is the stream counter, independent of retention.
    items: the CONTIGUOUS resume ring (trimmed from the front by count here; bytes/age too in the daemon).
    store: newest `per_src` items of every source, never trimmed by age (the SNAP backlog = Stage A's tail -n 25)."""

    def __init__(self, ep, capacity, per_src=25):
        self.ep, self.capacity, self.per_src = ep, capacity, per_src
        self.items, self.last, self.store = [], 0, {}
        self.ops, self.recording = [], True

    def append(self, src, payload):
        if self.recording:
            self.ops.append({"op": "append", "src": src, "line": payload})
        self.last += 1
        it = (self.last, src, payload)
        self.items.append(it)
        del self.items[: max(0, len(self.items) - self.capacity)]
        lst = self.store.setdefault(src, [])
        lst.append(it)
        del lst[: max(0, len(lst) - self.per_src)]
        return self.last

    def trim_before(self, seq):
        """Retention by age/bytes in the daemon: drop ring items with seq < `seq` (the store keeps its 25 per source)."""
        self.ops.append({"op": "trimBefore", "seq": seq})
        self.items = [it for it in self.items if it[0] >= seq]

    def seed(self, files):
        """Daemon start: the last `per_src` lines of every source file, sources in EV_SOURCES order, then follow from EOF."""
        self.ops.append({"op": "seed", "files": {k: list(v) for k, v in files.items()}})
        self.recording = False
        for src in EV_SOURCES:
            for line in files.get(src, [])[-self.per_src:]:
                self.append(src, line)
        self.recording = True

    @property
    def first(self):
        return self.items[0][0] if self.items else self.last + 1

    def backlog(self):
        return sorted((it for lst in self.store.values() for it in lst), key=lambda it: it[0])

    def snap_header(self, st, reason):
        back = self.backlog()
        return {"st": st, "ep": self.ep, "seq": self.last, "from": back[0][0] if back else self.last + 1, "reason": reason}

    def resume(self, client):
        """client = {"ep":..,"seq":..} or None. Returns ("replay", items) or ("snapshot", reason, backlog)."""
        self.ops.append({"op": "resume", "client": client})
        if client is None:
            return ("snapshot", "new", self.backlog())
        if client["ep"] != self.ep:
            return ("snapshot", "epoch", self.backlog())
        if client["seq"] > self.last:
            return ("snapshot", "ahead", self.backlog())
        if client["seq"] + 1 < self.first:
            return ("snapshot", "overflow", self.backlog())
        return ("replay", [it for it in self.items if it[0] > client["seq"]])

    def next_for(self, sent):
        """What a connection that has sent up to `sent` gets next (spec 4.3 'mid-connection overflow')."""
        self.ops.append({"op": "nextFor", "sent": sent})
        nxt = sent + 1
        if nxt > self.last:
            return ("idle",)
        if nxt < self.first:
            return ("snapshot", "overflow", self.backlog())
        return ("item", next(it for it in self.items if it[0] == nxt))


class AckGuard:
    """Daemon side ACK rules (spec 4.2): ceiling = max(resume position, highest seq sent, S of the last SNAP)."""

    def __init__(self, ep, resume_seq=0):
        self.ep, self.ceiling, self.acked = ep, resume_seq, 0

    def sent(self, seq):
        self.ceiling = max(self.ceiling, seq)

    def ack(self, ep, seq):
        if ep != self.ep:
            return "ignored:ep"
        if seq > self.ceiling:
            return "E_PROTO"
        if seq < self.acked:
            return "ignored:old"
        self.acked = seq
        return "ok"


class ClientStream:
    """Client side state machine of one durable stream (spec 4.2): NORMAL, BACKLOG (after a SNAP), close-and-resume on a gap."""

    def __init__(self, ep=None, pos=0):
        self.ep, self.pos, self.mode, self.snap_s = ep, pos, "normal", None

    def on_snap(self, ep, s, frm):
        self.ep, self.snap_s = ep, s
        if frm > s:  # empty backlog
            self.pos, self.mode = s, "normal"
        else:
            self.mode = "backlog"
        return "snapshot"

    def on_item(self, ep, seq):
        if ep != self.ep:
            return "close-resume:ep"
        if self.mode == "backlog":
            if seq == self.snap_s:
                self.pos, self.mode = seq, "normal"
            return "process"
        if seq <= self.pos:
            return "drop:dup"
        if seq != self.pos + 1:
            return "close-resume:gap"
        self.pos = seq
        return "process"


TAIL = 200000  # streamd v4 backlog bytes for off == 0


class TxWatch:
    """One transcript watch with streamd v4 semantics (~/.claude-remote/streamd.py:80-117) plus CRP's `reset`,
    which is held as pending until the next emitted chunk (an adjusted read can come back empty)."""

    def __init__(self, off):
        self.off, self.pending_reset = off, False

    def poll(self, content):
        sz = len(content)
        off = self.off
        if off < 0:
            self.off = sz
            return None
        adjusted = False
        if sz < off or (off == 0 and sz > TAIL):
            off = max(0, sz - TAIL)
            adjusted = True
        if sz <= off:
            return None
        data = content[off:sz]
        nl = data.rfind(b"\n")
        if nl < 0:
            return None  # streamd leaves its offset unchanged: the next poll adjusts again
        chunk, new_off, start = data[: nl + 1], off + nl + 1, off
        if adjusted and off > 0:
            first = chunk.find(b"\n")
            chunk, start = chunk[first + 1:], off + first + 1
        self.off = new_off
        if adjusted:
            self.pending_reset = True
        if not chunk:
            return None
        reset, self.pending_reset = self.pending_reset, False
        return {"f": start, "o": new_off, "reset": reset, "chunk": chunk}


def tx_pieces(wid, uuid, res, enc="none"):
    """Split one delta into TX frames of <= PIECE bytes; every piece but the last carries more:true (spec 4.6)."""
    out, chunk, f = [], res["chunk"], res["f"]
    for i in range(0, len(chunk), PIECE):
        piece = chunk[i:i + PIECE]
        h = {"wid": wid, "u": uuid, "f": f + i, "o": f + i + len(piece), "enc": enc}
        if i == 0 and res["reset"]:
            h["reset"] = True
        if i + PIECE < len(chunk):
            h["more"] = True
        out.append((h, piece))
    return out


TRANSIENT_IN = {"E_TMUX_SOCKET", "E_SHUTDOWN", "E_OFF", "E_BUSY"}  # do not consume the iseq; resend later


def in_hash(payload):
    return hashlib.sha256(payload).hexdigest()[:16]


class InDedup:
    """At-most-once input per key (dev, ie, sid) (spec 5.4). Every FINAL answer consumes the iseq and is remembered
    with the payload hash; transient refusals, E_IN_GAP and E_IN_CONFLICT consume nothing."""

    def __init__(self, gen="g-1"):
        self.gen, self.hi, self.res, self.unk = gen, {}, {}, {}

    def offer(self, key, iseq, payload, outcome="applied"):
        """`outcome` = what delivery would produce now: 'applied' | 'refused:E_X'."""
        hi = self.hi.get(key, 0)
        h = in_hash(payload)
        if iseq <= hi:
            rec = self.res.get(key, {}).get(iseq)
            if rec is None:
                return {"st": "dup", "orig": "old"}
            if rec[1] is not None and rec[1] != h:
                return {"st": "refused", "code": "E_IN_CONFLICT"}
            if iseq in self.unk.get(key, set()):
                return {"st": "unknown"}
            return {"st": "dup", "orig": rec[0]}
        if iseq != hi + 1:
            return {"st": "refused", "code": "E_IN_GAP", "hi": hi}
        if outcome.startswith("refused:") and outcome.split(":")[1] in TRANSIENT_IN:
            return {"st": "refused", "code": outcome.split(":")[1]}
        self.hi[key] = iseq
        self.res.setdefault(key, {})[iseq] = (outcome, h)
        if outcome == "applied":
            return {"st": "applied"}
        return {"st": "refused", "code": outcome.split(":")[1]}

    def skip(self, key, to):
        hi = self.hi.get(key, 0)
        for i in range(hi + 1, to + 1):
            self.res.setdefault(key, {})[i] = ("skipped", None)
        self.hi[key] = max(hi, to)
        return {"st": "skipped", "hi": self.hi[key]}

    def crash_after_intent(self, key, iseq, payload):
        """Daemon persisted the intent for iseq, then died before tmux confirmed it."""
        self.hi[key] = iseq
        self.res.setdefault(key, {})[iseq] = ("unknown", in_hash(payload))
        self.unk.setdefault(key, set()).add(iseq)


def client_on_welcome(stored_gen, welcome_gen, local_acked_hi, in_hi):
    """Client rule (spec 5.4): rotate `ie` when the daemon's input state is not the one it talked to."""
    if stored_gen is not None and stored_gen != welcome_gen:
        return {"action": "rotate-ie", "why": "inGen"}
    for sid, hi in local_acked_hi.items():
        if sid in in_hi and in_hi[sid]["iseq"] < hi:
            return {"action": "rotate-ie", "why": "inHi-below-acked", "sid": sid}
    nxt = {sid: max(local_acked_hi.get(sid, 0), in_hi.get(sid, {}).get("iseq", 0)) + 1
           for sid in sorted(set(local_acked_hi) | set(in_hi))}
    return {"action": "keep", "nextIseq": nxt}


PASTE_OVER = 400  # bytes; S0.4 canonical script: longer text goes through load-buffer + paste-buffer -p
GAP_BEFORE_ENTER_MS = 60
GAP_AFTER_ESCAPE_MS = 300
CSI_KEYS = {b"\x1b[A": "Up", b"\x1b[B": "Down", b"\x1b[C": "Right", b"\x1b[D": "Left"}
SINGLE_KEYS = {b"\r": "Enter", b"\t": "Tab", b"\x03": "C-c", b"\x7f": "BSpace"}


def pacing_plan(name, data, buf_name="crp-in-1"):
    """Parked-input delivery plan (spec 5.4) derived from S0.4 (davka-A/s0-hook-spike.md section 8).
    A text run goes through load-buffer + paste-buffer -p when it is > 400 B, contains a newline, or ends with ';'
    (a send-keys argv ending in ';' is a tmux command separator)."""
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return {"refused": "E_IN_UTF8"}
    target = "=" + name + ":"
    ops, text = [], b""

    def flush():
        nonlocal text
        if not text:
            return
        s = text.decode("utf-8")
        if len(text) <= PASTE_OVER and b"\n" not in text and not text.endswith(b";"):
            ops.append({"tmux": ["send-keys", "-t", target, "-l", "--", s]})
        else:
            ops.append({"tmux": ["load-buffer", "-b", buf_name, "-"], "stdin": s})
            ops.append({"tmux": ["paste-buffer", "-p", "-d", "-b", buf_name, "-t", target]})
        text = b""

    i = 0
    while i < len(data):
        three = data[i:i + 3]
        one = data[i:i + 1]
        if three in CSI_KEYS:
            flush(); ops.append({"tmux": ["send-keys", "-t", target, CSI_KEYS[three]]}); i += 3
        elif one == b"\x1b":
            flush(); ops.append({"tmux": ["send-keys", "-t", target, "Escape"]}); ops.append({"sleepMs": GAP_AFTER_ESCAPE_MS}); i += 1
        elif one in SINGLE_KEYS:
            had_text = bool(text)
            flush()
            if one == b"\r" and (had_text or (ops and "tmux" in ops[-1])):
                ops.append({"sleepMs": GAP_BEFORE_ENTER_MS})
            ops.append({"tmux": ["send-keys", "-t", target, SINGLE_KEYS[one]]}); i += 1
        elif one == b"\n":
            text += one; i += 1
        elif one[0] < 0x20:
            return {"refused": "E_IN_UNSUPPORTED", "byte": one.hex()}
        else:
            text += one; i += 1
    flush()
    return {"ops": ops}


# rpc exec deny-scan (spec 6.2). A safety net against client bugs, NOT an auth boundary. ASCII-only tokenizer, no
# regex lookbehind (Go RE2 has none): Go and Kotlin port this function, not a regex.
RPC_TAGS = {
    "reconcile", "git.status", "health", "redraw", "probe.has-session", "probe.transcript", "remote.scroll",
    "logship", "flags",
}
RPC_TMUX_VERBS = {
    "list-sessions", "list-panes", "list-clients", "list-windows", "has-session", "display-message",
    "show-environment", "capture-pane", "refresh-client", "send-keys", "copy-mode",
}
RPC_FORBIDDEN_TEXT = [
    "kill-server", "start-server", "new-session", "kill-session", "respawn-pane", "respawn-window",
    "rename-session", "resize-window", "window-size", "source-file", "run-shell", "if-shell",
    "new-window", "split-window", "systemd-run", "systemctl", "daemon-reload", "set-option", "set-window-option",
    # revision 2
    "#(", "copy-pipe", "pipe-pane", "respawn", "tmux-sockheal", "drift.sh", "restore.sh", "/tmux-", "TMUX_TMPDIR",
    "pkill", "killall", "kill -", ".credentials", "loginctl",
]
FORBIDDEN_CMD_WORDS = {"eval", "source", ".", "kill", "pkill", "killall", "loginctl"}
SHELLS = {"sh", "bash", "dash", "zsh", "ksh"}
KEYWORDS_CMD = {"if", "then", "else", "elif", "do", "while", "until", "!", "{", "}", "time", "fi", "done", "esac"}
KEYWORDS_NONCMD = {"case", "for", "select"}
SCROLL_KEYS = {"page-up", "page-down", "halfpage-up", "halfpage-down", "scroll-up", "scroll-down", "cancel"}
ASSIGN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*=")
REDIR = re.compile(r"[0-9]*(?:>>|<>|>&|<&|>|<)")
FLAG_CLUSTER = re.compile(r"-[A-Za-z]+")


class Denied(Exception):
    pass


def sh_tokens(s):
    """ASCII shell-ish tokenizer. Yields ("w", text, [(quote, content), ...]) and ("op", op).
    Blanks: space and tab only. Operators: newline ; & | ( ) ` and `$(`. Quotes and backslashes are resolved."""
    out, i, n = [], 0, len(s)
    cur, parts, inword = [], [], False

    def end():
        nonlocal cur, parts, inword
        if inword:
            out.append(("w", "".join(cur), parts))
        cur, parts, inword = [], [], False

    while i < n:
        c = s[i]
        if c in " \t":
            end(); i += 1
        elif c == "$" and i + 1 < n and s[i + 1] == "(":
            end(); out.append(("op", "$(")); i += 2
        elif c in "\n;&|()`":
            end(); out.append(("op", c)); i += 1
        elif c == "'":
            j = s.find("'", i + 1)
            j = n if j < 0 else j
            content = s[i + 1:j]
            cur.append(content); parts.append(("'", content)); inword = True; i = j + 1
        elif c == '"':
            j, buf = i + 1, []
            while j < n and s[j] != '"':
                if s[j] == "\\" and j + 1 < n and s[j + 1] in '"\\$`':
                    buf.append(s[j + 1]); j += 2
                else:
                    buf.append(s[j]); j += 1
            content = "".join(buf)
            cur.append(content); parts.append(('"', content)); inword = True; i = j + 1
        elif c == "\\":
            if i + 1 < n and s[i + 1] == "\n":
                i += 2
            else:
                cur.append(s[i + 1:i + 2]); inword = True; i += 2
        else:
            cur.append(c); inword = True; i += 1
    end()
    return out


def _tmux_check(words, tag):
    """words = the words after a `tmux` word up to the next shell operator."""
    words = list(words)
    i = 0
    while i < len(words) and words[i].startswith("-"):
        if words[i] not in ("-u", "-N"):
            raise Denied("tmux-flag:" + words[i])
        i += 1
    while True:
        if i >= len(words) or words[i] in ("", ";"):
            raise Denied("tmux-verb:" + (words[i] if i < len(words) else ""))
        verb, i, sep = words[i], i + 1, False
        if verb.endswith(";"):
            verb, sep = verb[:-1], True
        if verb not in RPC_TMUX_VERBS:
            raise Denied("tmux-verb:" + verb)
        args = []
        while not sep and i < len(words):
            w = words[i]
            i += 1
            if w.endswith(";"):
                sep = True
                w = w[:-1]
                if not w:
                    break
            args.append(w)
        clean, k = [], 0
        while k < len(args):  # drop redirections: `2>/dev/null`, `> f`
            m = REDIR.match(args[k])
            if m:
                k += 2 if m.end() == len(args[k]) else 1
                continue
            clean.append(args[k]); k += 1
        for a in clean:
            if FLAG_CLUSTER.fullmatch(a) and ("A" in a or "g" in a):
                raise Denied("tmux-flag:" + ("-A" if "A" in a else "-g"))
        if verb == "send-keys":
            if tag != "remote.scroll" or len(clean) != 4 or clean[0] != "-t" or clean[2] != "-X" or clean[3] not in SCROLL_KEYS:
                raise Denied("tmux-args:send-keys")
        if verb == "refresh-client":
            k = 0
            while k < len(clean):
                if clean[k] == "-t" and k + 1 < len(clean):
                    k += 2
                elif clean[k] == "-S":
                    k += 1
                else:
                    raise Denied("tmux-args:refresh-client")
        if not sep:
            return


def _scan(cmd, tag, full, depth):
    if depth > 4:
        raise Denied("depth")
    toks = sh_tokens(cmd)
    cmd_pos = True
    simple = []  # words of the current simple command
    idx = 0
    while idx <= len(toks):
        tok = toks[idx] if idx < len(toks) else ("op", "\n")
        idx += 1
        if tok[0] == "op":
            if simple:
                _check_simple(simple, tag, full, depth)
            simple = []
            cmd_pos = tok[1] != ")"
            continue
        text, parts = tok[1], tok[2]
        simple.append((text, parts, cmd_pos))
        if cmd_pos:
            if ASSIGN.match(text) or text in KEYWORDS_CMD:
                continue
            cmd_pos = False
            if text in KEYWORDS_NONCMD:
                continue
    return None


def _check_simple(simple, tag, full, depth):
    words = [w[0] for w in simple]
    for k, (text, parts, at_cmd) in enumerate(simple):
        base = text.rsplit("/", 1)[-1]
        if full and at_cmd and not ASSIGN.match(text) and text not in KEYWORDS_CMD and text not in KEYWORDS_NONCMD:
            if "$" in text or "`" in text:
                raise Denied("cmdvar")
            if base in FORBIDDEN_CMD_WORDS:
                raise Denied("cmd:" + base)
        if base == "tmux":
            _tmux_check(words[k + 1:], tag)
        if base in SHELLS:
            for j in range(k + 1, len(simple) - 1):
                if FLAG_CLUSTER.fullmatch(words[j]) and "c" in words[j]:
                    _scan(words[j + 1], tag, True, depth + 1)
                    break
        for _q, content in parts:
            _scan(content, tag, False, depth + 1)


def deny_scan(tag, cmd):
    """None = allowed; otherwise the rule that refused it."""
    if tag not in RPC_TAGS:
        return "tag:" + tag
    for word in RPC_FORBIDDEN_TEXT:
        if word in cmd:
            return "text:" + word
    try:
        _scan(cmd, tag, True, 0)
    except Denied as e:
        return str(e)
    return None


def size_owner(events):
    """Window-size owner per session (spec 5.3). Ownership moves only on SUB{active:true}, or on IN from a device that
    holds a term subscription for that sid; a TSTATE goes to EVERY subscriber whenever owner or geometry changes."""
    owner, size, subs, out = {}, {}, {}, []

    def bcast(sid):
        return [{"to": d, "tstate": {"sid": sid, "state": "attached", "cols": size[sid][0], "rows": size[sid][1],
                                     "owner": owner.get(sid)}} for d in sorted(subs.get(sid, set()))]

    for ev in events:
        sid, dev, kind = ev["sid"], ev["dev"], ev["kind"]
        if kind == "sub":
            subs.setdefault(sid, set()).add(dev)
            changed = False
            if ev.get("active"):
                changed = owner.get(sid) != dev or size.get(sid) != (ev["cols"], ev["rows"])
                owner[sid], size[sid] = dev, (ev["cols"], ev["rows"])
            out.append({"event": ev, "owner": owner.get(sid), "tstate": bcast(sid) if changed else [
                {"to": dev, "tstate": {"sid": sid, "state": "attached", "cols": size[sid][0], "rows": size[sid][1],
                                       "owner": owner.get(sid)}}]})
        elif kind == "in":
            moved = dev in subs.get(sid, set()) and owner.get(sid) != dev
            if moved:
                owner[sid] = dev
            out.append({"event": ev, "owner": owner.get(sid), "ownerMoved": moved, "tstate": bcast(sid) if moved else []})
        elif kind == "tsize":
            applied = owner.get(sid) == dev
            if applied:
                size[sid] = (ev["cols"], ev["rows"])
            out.append({"event": ev, "applied": applied, **({"tstate": bcast(sid)} if applied else {"err": "E_NOT_ACTIVE"})})
    return out


# --------------------------------------------------------------------------------------------------
# Real command shapes of the tagged sites (2841e18), for the allow table. B2's test renders the builders instead.
# --------------------------------------------------------------------------------------------------
RECONCILE_CMD = r"""set +e
D="$HOME/.claude-remote"; mkdir -p "$D"; LOCK="$D/sessions.lock"; touch "$LOCK"
( flock -s 9
cat "$D/forgotten" 2>/dev/null
find "$D/forgotten.d" -maxdepth 1 -type f -mtime -30 -printf '%f\n' 2>/dev/null
echo ---
cat "$D/sessions.json" 2>/dev/null || :
echo
) 9<>"$LOCK"; echo "__END_snap__ rc=$?"
tmux -u list-sessions -F '#{session_name}' 2>/dev/null; echo "__END_tmux__ rc=$?"
NAMES=$(printf '%s\n' 'claude-server-a' 'claude-server-b')
PANES=$(tmux -u list-panes -a -F '#{session_name} #{pane_pid}' 2>/dev/null); R=$?
printf '%s\n' "$PANES" | while IFS= read -r l; do n="${l% *}"; printf '%s\n' "$NAMES" | grep -qxF -- "$n" && printf '%s\n' "$l"; done
echo "__END_panes__ rc=$R"
PS=$(ps -eo pid=,ppid=,comm= 2>/dev/null); R=$?; printf '%s\n' "$PS"; echo "__END_ps__ rc=$R"
dis() { f="$1"; if [ -r "$f" ]; then if grep -Eq '"disableAllHooks"[[:space:]]*:[[:space:]]*true' "$f" 2>/dev/null; then echo 1; else echo 0; fi; elif [ -e "$f" ]; then echo -; else echo 0; fi; }
if [ -e "$D/hookev.off" ]; then OFF=1; else OFF=0; fi
for p in $(printf '%s\n' "$PS" | awk '$3=="claude" || $3 ~ /^[0-9]+(\.[0-9]+)+$/ {print $1}'); do
E=$(readlink /proc/$p/exe 2>/dev/null); echo "EXE $p ${E:--}"
CFG=$(tr '\0' '\n' < /proc/$p/environ 2>/dev/null | sed -n 's|^CLAUDE_CONFIG_DIR=||p' | head -1); C="${CFG:-$HOME/.claude}"
if [ -r "$C/settings.json" ]; then if grep -q 'claude-remote-ev v' "$C/settings.json" 2>/dev/null; then M=1; else M=0; fi; elif [ -e "$C/settings.json" ]; then M=-; else M=0; fi
D1=$(dis "$C/settings.json"); D2=$(dis "$C/settings.local.json")
if [ "$D1" = 1 ] || [ "$D2" = 1 ]; then X=1; elif [ "$D1" = - ] || [ "$D2" = - ]; then X=-; else X=0; fi
echo "CAPF $p $M $X $OFF"
f="$C/sessions/$p.json"
if [ -f "$f" ]; then v=$(jq -r '.sessionId // empty' "$f" 2>/dev/null); ok=-; [ -n "$v" ] && { ls "$HOME"/.claude/projects/*/"$v".jsonl >/dev/null 2>&1 || ls "$C"/projects/*/"$v".jsonl >/dev/null 2>&1; } && ok=$v; echo "SID $p $ok"; fi
done; true; echo "__END_claude__ rc=$?""" + '"'
GIT_STATUS_CMD = """F='~/claude-remote'
E="${F/#~/$HOME}"
case "$E" in /*) ;; *) E="$HOME/$E";; esac
cd "$E" 2>/dev/null || exit 0
git rev-parse --abbrev-ref HEAD 2>/dev/null
git status --porcelain 2>/dev/null | head -1
git rev-list --left-right --count @{u}...HEAD 2>/dev/null"""
PROBE_TRANSCRIPT_CMD = """F='~/claude-remote'
E="${F/#~/$HOME}"
case "$E" in /*) ;; *) E="$HOME/$E";; esac
# UUID is globally unique — a transcript matching it anywhere means
# it exists, immune to the lossy cwd->dir encoding. Fall back to the
# corrected encoding (every non-alphanumeric -> '-') for a not-yet-
# globbable path.
ENC=$(echo "$E" | sed 's|[^a-zA-Z0-9]|-|g')
if ls "$HOME/.claude/projects/"*/"0f8e2c1a-1111-4222-8333-944455556666.jsonl" >/dev/null 2>&1 || [ -f "$HOME/.claude/projects/$ENC/0f8e2c1a-1111-4222-8333-944455556666.jsonl" ]; then echo YES; else echo NO; fi"""
ALLOW_TABLE = [
    ("reconcile", RECONCILE_CMD, "ManifestCommands.kt:190 RECONCILE_ALL (@@TD@@=30, two names)"),
    ("git.status", GIT_STATUS_CMD, "GitStatusService.kt:73-80"),
    ("probe.transcript", PROBE_TRANSCRIPT_CMD, "TmuxProbes.kt:243-256"),
    ("probe.has-session", "tmux has-session -t '=claude-server-cashy' 2>/dev/null && echo YES || echo NO", "TmuxProbes.kt:236"),
    ("redraw", "tmux list-clients -t '=claude-server-cashy' -F '#{client_name}' 2>/dev/null | while IFS= read -r c; do "
               "tmux refresh-client -t \"$c\" 2>/dev/null && echo OK; done", "TmuxProbes.kt:155-156"),
    ("remote.scroll", "tmux copy-mode -e -t '=claude-server-cashy:'; tmux send-keys -t '=claude-server-cashy:' -X page-up",
     "RemoteOpsService.kt:53"),
    ("health", "echo pong", "ServerHealthService.kt:126"),
    ("logship", "D=\"$HOME/.claude-remote/logs\"; mkdir -p \"$D\"; F=\"$D/android-706cbd7b.log\"; if [ -f \"$F\" ] && "
                "[ \"$(wc -c < \"$F\")\" -gt 41943040 ]; then tail -c 37748736 \"$F\" | tail -n +2 > \"$F.tmp\" && "
                "mv \"$F.tmp\" \"$F\"; fi; cat >> \"$F\"", "LogShipper.kt:180-185"),
    ("flags", "echo '===CRB flags==='; CF=\"$HOME/.claude-remote/client-flags.json\"; [ -f \"$CF\" ] && head -c 4096 \"$CF\"; echo",
     "ServerBootstrap.kt:135"),
    ("reconcile", "timeout 2 sh -c \"tmux list-sessions -F '#{session_name}|#{session_created}'\"", "vector 17 shape"),
]
DENY_TABLE = [
    # (tag, cmd, why) -- every shape a critique showed slipping through revision 1, plus the old vectors' shapes
    ("redraw", "/usr/bin/tmux new -A -s x", "absolute path to the second tmux on the box (3.3a)"),
    ("redraw", "\\tmux new -d -s x", "backslash-escaped command word"),
    ("redraw", "'tmux' new -d -s x", "quoted command word"),
    ("redraw", "tm\\ux new -d -s x", "backslash inside the word"),
    ("redraw", "command tmux new -s x", "wrapper"),
    ("redraw", "env tmux new -s x", "wrapper"),
    ("redraw", "echo x|xargs tmux kill-ser", "tmux as an argument of xargs"),
    ("redraw", "T=tmux; $T new -d -s x", "variable at command position"),
    ("redraw", "\"$(command -v tmux)\" kill-ser", "command substitution at command position"),
    ("redraw", "tmux $(echo kill-ser)", "verb from a substitution: bare tmux = new-session"),
    ("redraw", "tmux\nnew -s x", "newline: bare tmux is new-session"),
    ("redraw", "tmux list-sessions ';' new -d -s x", "quoted ';' argv is a tmux separator"),
    ("redraw", "tmux list-sessions \";\" new -d -s x", "double-quoted ';'"),
    ("redraw", "tmux list-sessions foo\\; new -d -s x", "argument ending in ';' is a tmux separator"),
    ("redraw", "tmux -S /tmp/other list-sessions", "global -S"),
    ("redraw", "tmux -L x list-sessions", "global -L"),
    ("redraw", "tmux list-sessions && tmux detach-client -a", "verb not allowlisted"),
    ("redraw", "tmux refresh-client -t /dev/pts/3 -C 10x10", "refresh-client may only take -t and -S"),
    ("redraw", "tmux show-environment -g", "-g"),
    ("redraw", "tmux list-panes -ag -F x", "-g inside a flag cluster"),
    ("redraw", "tmux display-message -p '#(pkill -f claude)'", "#() runs a shell in the tmux server"),
    ("redraw", "tmux list-sessions -F '#(tmux kill-ser)'", "#() runs a shell in the tmux server"),
    ("reconcile", "tmux send-keys -t =x: '/exit' Enter", "send-keys outside remote.scroll"),
    ("remote.scroll", "tmux send-keys -t =x: C-d", "send-keys not in the scroll shape"),
    ("remote.scroll", "tmux send-keys -X -t x copy-pipe-and-cancel 'rm -rf ~'", "copy-pipe"),
    ("reconcile", "kill -9 $(pgrep -f 'tmux: server')", "kill"),
    ("reconcile", "pkill -x tmux", "pkill"),
    ("reconcile", "rm -rf /tmp/tmux-1000", "the socket directory"),
    ("reconcile", "rm -f ${TMUX_TMPDIR:-/tmp}/tmux-$(id -u)/default", "the socket"),
    ("reconcile", "bash ~/.claude-remote/tmux-sockheal.sh", "a heal script"),
    ("reconcile", "bash ~/.claude-remote/drift.sh", "drift"),
    ("reconcile", "eval \"$X\"", "eval"),
    ("reconcile", "cat ~/.claude/.credentials.json", "credentials"),
    ("reconcile", "loginctl terminate-user lucas", "loginctl"),
    ("reconcile", "sh -c 'X=tmux; $X kill-ser'", "nested sh -c gets the full rules"),
    ("probe.has-session", "tmux has-session -t '=claude-server-tmux-split-window-demo' 2>/dev/null && echo YES || echo NO",
     "data-dependent over-refusal: the client pre-scans and sends this untagged over Stage A (spec 6.2)"),
]


# --------------------------------------------------------------------------------------------------
# Vectors
# --------------------------------------------------------------------------------------------------
DEV_P, DEV_D = "android-706cbd7b", "desktop-d14d0402"
LINK_LAN, LINK_CF = "5b1e2f0c9a7d3e41", "c03d81aa47e2b915"   # first 16 hex of sha256(serverId)
IE_P = "5f1c0e9a7b3d2c41"
EP = "a1b2c3d4"
EP_OLD = "0badc0de"
NOW = 1793990000000
EXP = NOW + 60000
INGEN = "6e2a91f0"
SID = "claude-server-claude-remote--crtest-1"
EV_LINE = "claude-remote-ev ask Y2xhdWRlLXNlcnZlci1jbGF1ZGUtcmVtb3RlLS1jcnRlc3QtMQ== 1793989999123 2475820 SmFrb3UgYmFydnUgbcOhxaEgbmVqcmFkxaFpPw=="


def hello(proto=(1,), resume=None, feats=("ev", "sess", "tx", "term", "in", "rpc.exec", "gzip"), dev=DEV_P, ie=IE_P,
          link=LINK_LAN, att=7, max_frame=DEFAULT_MAX_FRAME):
    h = {"proto": list(proto), "dev": dev, "link": link, "att": att, "ie": ie, "app": "1.0.560", "feats": list(feats),
         "maxFrame": max_frame, "fg": True, "pingMs": 15000}
    if resume is not None:
        h["resume"] = resume
    return h


def welcome_from(neg, streams, resumed=None, conn="c-0001", inhi=None):
    h = {"proto": neg["proto"], "conn": conn, "srv": "crd 0.1.0", "feats": neg["feats"], "maxFrame": neg["maxFrame"],
         "now": NOW, "host": {"id": "9f2c41aa", "boot": "b-77e1", "tmuxPid": 3167208}, "streams": streams, "inGen": INGEN}
    if resumed is not None:
        h["resumed"] = resumed
    if inhi is not None:
        h["inHi"] = inhi
    return h


def fr(direction, type_name, header, blob=None, note=None, conn="A"):
    d = {"conn": conn, "dir": direction, "type": type_name, "header": header}
    if blob is not None:
        d["blob"] = blob
    if note:
        d["note"] = note
    return d


def ev_line_frames(ring, items, conn="A"):
    return [fr("s2c", "EV", {"st": "ev", "ep": ring.ep, "seq": s, "src": src, "at": NOW}, p.encode(), conn=conn)
            for s, src, p in items]


def ring_streams(ring):
    return {"ep": ring.ep, "first": ring.first, "last": ring.last}


def append_lines(ring, a, b, cycle):
    """ring.append(cycle[s % len(cycle)], "line-<s>") for s in a..b, recorded as one op (modelInput)."""
    ring.ops.append({"op": "appendLines", "from": a, "to": b, "srcCycle": list(cycle)})
    ring.recording = False
    for s in range(a, b + 1):
        ring.append(cycle[s % len(cycle)], f"line-{s}")
    ring.recording = True


def ring_input(ring):
    return {"ep": ring.ep, "capacity": ring.capacity, "perSrc": ring.per_src, "ops": ring.ops}


def content_of(segs):
    """Transcript content from (text, times) segments; tx_input() records the same segments."""
    return b"".join(t.encode() * n for t, n in segs)


def tx_input(off, *polls):
    return {"off": off, "polls": [[{"text": t, "times": n} for t, n in segs] for segs in polls]}


NEG_REF = {"serverProto": list(SERVER_PROTO), "serverFeats": SERVER_FEATS, "serverMaxFrame": DEFAULT_MAX_FRAME}


def in_ack(sid, iseq, r, **extra):
    h = {"sid": sid, "iseq": iseq, "st": r["st"]}
    for k in ("code", "orig", "hi"):
        if k in r:
            h[k] = r[k]
    h.update(extra)
    return h


def build_vectors():
    V = []
    W = welcome_from

    # 1. preamble behind login-shell noise
    neg = negotiate(hello())
    junk = "Last login: Fri Nov  6 17:00:01 2026 from 192.0.2.1\n"  # RFC 5737 documentation address
    V.append(dict(
        id="01-preamble-junk", spec="2.2, 3.1",
        title="Server stream behind login-shell noise",
        description="sshd ran `crd --stdio` through the user's shell, which printed a motd line first. The client skips "
                    "up to 64 KiB before the magic; everything after the magic is frames. An empty ring reports "
                    "first = last + 1.",
        junk={"A/s2c": junk},
        frames=[fr("s2c", "WELCOME", W(neg, {"ev": {"ep": EP, "first": 1, "last": 0}, "sess": {"ep": EP, "first": 1, "last": 0}}))],
        expect={"junkSkipped": len(junk)},
    ))

    # 2. hello fresh + welcome + SUB + ev snapshot (new)
    ring = Ring(EP, 4096)
    for s in range(1, 4):
        ring.append("events", EV_LINE.replace("1793989999123", str(1793989999000 + s)))
    res = ring.resume(None)
    neg = negotiate(hello())
    V.append(dict(
        id="02-handshake-fresh", spec="2.3, 4.3",
        title="First connection: HELLO, WELCOME, then SUB ev and SNAP(new) with backlog",
        description="The client sends nothing but HELLO before WELCOME. A SUB without a position (and no HELLO.resume "
                    "for that stream) gets SNAP(reason=new) and the backlog (newest 25 per source); live items continue "
                    "at seq+1.",
        frames=[fr("c2s", "HELLO", hello()),
                fr("s2c", "WELCOME", W(neg, {"ev": ring_streams(ring)})),
                fr("c2s", "SUB", {"st": "ev"}),
                fr("s2c", "SNAP", ring.snap_header("ev", res[1]))]
               + ev_line_frames(ring, res[2]),
        expect={"negotiation": neg, "resume": res[0], "reason": res[1], "backlogSeqs": [i[0] for i in res[2]]},
        modelInput={"ring": ring_input(ring)},
    ))

    # 3. version mismatch
    h = hello(proto=(2,))
    neg = negotiate(h)
    V.append(dict(
        id="03-version-mismatch", spec="2.3, 8.3",
        title="No common protocol version: BYE E_VERSION, client falls back to Stage A",
        description="Client speaks only CRP/2, daemon 1..1. The daemon sends BYE{code:E_VERSION,min,max} and closes; "
                    "the client marks crd incompatible for this server and uses the Stage A exec paths.",
        frames=[fr("c2s", "HELLO", h), fr("s2c", "BYE", {"code": neg["code"], "min": neg["min"], "max": neg["max"],
                                                         "msg": "no common protocol version"})],
        expect={"negotiation": neg, "client": "fallback-stage-a"},
    ))

    # 4. down-level negotiation + feature intersection
    h = hello(proto=(1, 2), feats=("ev", "tx", "rpc.exec", "zstd"))
    neg = negotiate(h)
    V.append(dict(
        id="04-version-downlevel", spec="2.3",
        title="Client 1..2, daemon 1..1: WELCOME proto 1, features = intersection",
        description="The higher common version wins; unknown client features (zstd) are dropped; the client MUST NOT "
                    "use a feature that is not in WELCOME.feats.",
        frames=[fr("c2s", "HELLO", h), fr("s2c", "WELCOME", W(neg, {"ev": {"ep": EP, "first": 1, "last": 0}}))],
        expect={"negotiation": neg},
    ))

    # 5. ev delta + ack
    g = AckGuard(EP)
    g.sent(42)
    V.append(dict(
        id="05-ev-delta-ack", spec="4.2",
        title="Durable ev item and the client's ACK",
        description="The blob is the events-file line without its newline, unchanged, so HookEventReducer parses it as today. "
                    "The ACK names the highest seq the client has processed in order (excerpt of a live connection).",
        excerpt=True,
        frames=[fr("s2c", "EV", {"st": "ev", "ep": EP, "seq": 42, "src": "events", "at": NOW}, EV_LINE.encode()),
                fr("c2s", "ACK", {"acks": {"ev": {"ep": EP, "seq": 42}}})],
        expect={"clientPosition": {"ev": {"ep": EP, "seq": 42}}, "daemonAck": g.ack(EP, 42)},
    ))

    # 6. resume inside the ring -> replay
    ring = Ring(EP, 4096)
    append_lines(ring, 1, 45, ["notify", "events", "events"])  # "events" if s % 3 else "notify"
    resume = {"ev": {"ep": EP, "seq": 43}}
    res = ring.resume(resume["ev"])
    neg = negotiate(hello(resume=resume))
    V.append(dict(
        id="06-resume-replay", spec="4.3",
        title="Reconnect over another transport, position inside the ring: exact replay",
        description="The channel died on CF, the client reconnected over LAN and resumed with ev seq 43. The ring holds "
                    "1..45, so the daemon replays 44..45 and nothing else.",
        frames=[fr("c2s", "HELLO", hello(resume=resume, att=8)),
                fr("s2c", "WELCOME", W(neg, {"ev": ring_streams(ring)}, resumed={"ev": "replay"}))]
               + ev_line_frames(ring, res[1]),
        expect={"resume": res[0], "replaySeqs": [i[0] for i in res[1]]},
        modelInput={"ring": ring_input(ring)},
    ))

    # 7. resume after overflow -> snapshot
    ring = Ring(EP, 1000)
    append_lines(ring, 1, 2000, ["events", "notify", "forget"])
    resume = {"ev": {"ep": EP, "seq": 500}}
    res = ring.resume(resume["ev"])
    back = res[2]
    snap = ring.snap_header("ev", res[1])
    V.append(dict(
        id="07-resume-overflow", spec="4.3, 4.4",
        title="Position older than the ring: SNAP(overflow) + backlog",
        description="Client was away; it has seq 500, the ring kept 1001..2000. The daemon sends SNAP(reason=overflow, "
                    "from, seq) and the backlog (newest 25 per source, ending with the item seq S). The client re-reduces "
                    "the backlog with the same replay rules as Stage A's `tail -n 25` (dedup on (tmux,ev,ms), age limits).",
        frames=[fr("c2s", "HELLO", hello(resume=resume)),
                fr("s2c", "WELCOME", W(negotiate(hello(resume=resume)), {"ev": ring_streams(ring)}, resumed={"ev": "snapshot"})),
                fr("s2c", "SNAP", snap)]
               + ev_line_frames(ring, back[:3]),
        expect={"resume": res[0], "reason": res[1], "ringFirst": ring.first, "backlogCount": len(back),
                "backlogFirstSeq": back[0][0], "backlogLastSeq": back[-1][0], "framesShown": 3},
        modelInput={"ring": ring_input(ring)},
    ))

    # 8. epoch change (daemon restarted, ring seeded from the files)
    ring = Ring(EP, 4096)
    ring.seed({"events": [f"ev-{i}" for i in range(1, 4)], "notify": ["stop-1", "stop-2"]})
    resume = {"ev": {"ep": EP_OLD, "seq": 812}}
    res = ring.resume(resume["ev"])
    V.append(dict(
        id="08-resume-epoch-change", spec="4.3, 4.4, 8.2",
        title="Daemon restarted (new epoch): SNAP(epoch) with the seeded backlog",
        description="Rings live in memory; a restarted daemon has a new ep and seeded its ring from the last 25 lines of "
                    "each source file. Any resume with the old ep is a SNAP(reason=epoch).",
        frames=[fr("c2s", "HELLO", hello(resume=resume)),
                fr("s2c", "WELCOME", W(negotiate(hello(resume=resume)), {"ev": ring_streams(ring)}, resumed={"ev": "snapshot"})),
                fr("s2c", "SNAP", ring.snap_header("ev", res[1]))] + ev_line_frames(ring, res[2]),
        expect={"resume": res[0], "reason": res[1], "backlogSeqs": [i[0] for i in res[2]]},
        modelInput={"ring": ring_input(ring)},
    ))

    # 9. sess snapshot + diff
    table = [{"name": SID, "created": 1793900000, "clients": 1, "panePid": 2475820, "cmd": "claude", "dead": False},
             {"name": "claude-server-cashy", "created": 1793800000, "clients": 0, "panePid": 2400001, "cmd": "claude", "dead": False}]
    V.append(dict(
        id="09-sess-snapshot-diff", spec="4.5",
        title="Session table: SNAP with the full table, then a diff",
        description="Informational stream (online dots, [exited], titles). It NEVER drives a forget, a rebuild or a "
                    "relaunch: forget stays on the reconcile exec with tombstones (CLAUDE.md forget rules).",
        excerpt=True,
        frames=[fr("c2s", "SUB", {"st": "sess"}),
                fr("s2c", "SNAP", {"st": "sess", "ep": EP, "seq": 17, "from": 18, "reason": "new", "sessions": table}),
                fr("s2c", "EV", {"st": "sess", "ep": EP, "seq": 18, "ops": [{"op": "put", "row": dict(table[1], clients=1)}, {"op": "del", "name": SID}]})],
        expect={"tableAfter": [dict(table[1], clients=1)]},
    ))

    # 10. tx subscribe + delta
    segs = [('{"type":"user","n":1}\n', 40), ('{"type":"assistant","n":2}\n{"type":"assist', 1)]
    content = content_of(segs)
    off = 22 * 40
    w = TxWatch(off)
    r = w.poll(content)
    (h10, b10), = tx_pieces("w7", "0f8e2c1a-1111-4222-8333-944455556666", r)
    V.append(dict(
        id="10-tx-sub-delta", spec="4.6",
        title="Transcript watch from an offset: one delta up to the last complete line",
        description="Same semantics as streamd v4: f = start offset, o = offset after the chunk; a partial last line is "
                    "held back. The file itself is the durable log, so a resume is a new SUB with the client's offset.",
        excerpt=True,
        frames=[fr("c2s", "SUB", {"st": "tx", "wid": "w7", "uuid": "0f8e2c1a-1111-4222-8333-944455556666", "cwd": "~/claude-remote", "off": off, "enc": "none"}),
                fr("s2c", "TX", h10, b10),
                fr("c2s", "ACK", {"acks": {}, "tx": {"w7": r["o"]}})],
        expect={"model": {k: v for k, v in r.items() if k != "chunk"}, "watchOff": w.off},
        modelInput={"tx": tx_input(off, segs)},
    ))

    # 11. tx after truncation
    segs = [('{"n":1}\n{"n":2}\n', 1), ('{"n":3}\n', 30)]
    content = content_of(segs)
    off = 5000
    w = TxWatch(off)
    r = w.poll(content)
    (h11, b11), = tx_pieces("w8", "1a2b3c4d-0000-4000-8000-000000000001", r)
    V.append(dict(
        id="11-tx-truncated", spec="4.6, 4.4",
        title="Transcript shorter than the client's offset: reset delta",
        description="sz < off (file replaced). streamd re-reads from max(0, sz-TAIL); CRP marks it reset:true so the client "
                    "drops what it had for this watch instead of appending.",
        excerpt=True,
        frames=[fr("c2s", "SUB", {"st": "tx", "wid": "w8", "uuid": "1a2b3c4d-0000-4000-8000-000000000001", "cwd": "~", "off": off, "enc": "none"}),
                fr("s2c", "TX", h11, b11)],
        expect={"model": {k: v for k, v in r.items() if k != "chunk"}},
        modelInput={"tx": tx_input(off, segs)},
    ))

    # 12. term subscribe, output
    out1 = b"\x1b[?1049h\x1b[H\x1b[2J\xe2\x95\xad\xe2\x94\x80 Claude Code"
    out2 = b"\r\n> "
    V.append(dict(
        id="12-term-sub-out", spec="5.1, 5.2",
        title="Terminal subscribe from the active device; raw PTY bytes",
        description="Non-durable. The blob is the tmux client's PTY output byte for byte (here the alternate screen and a "
                    "UTF-8 box character). tseq counts frames per (connection, sid) from 1. The daemon's tmux argv carries "
                    "-N (never start a server) and -S with the guarded socket path.",
        excerpt=True,
        frames=[fr("c2s", "SUB", {"st": "term", "sid": SID, "cols": 120, "rows": 40, "active": True}),
                fr("s2c", "TSTATE", {"sid": SID, "state": "attached", "cols": 120, "rows": 40, "owner": DEV_P}),
                fr("s2c", "TOUT", {"sid": SID, "tseq": 1}, out1),
                fr("s2c", "TOUT", {"sid": SID, "tseq": 2}, out2)],
        expect={"daemonTmux": ["tmux", "-N", "-S", "<guarded socket path>", "attach-session", "-t", "=" + SID, "-f", "!ignore-size"],
                "ptySize": [120, 40]},
    ))

    # 13. term gap -> redraw
    V.append(dict(
        id="13-term-gap-redraw", spec="5.2",
        title="Output dropped under backpressure: tseq jump + redraw",
        description="The per-subscriber queue overflowed, frames 8..10 were dropped. The daemon ran `refresh-client` on its "
                    "own client and the next TOUT carries redraw:true; the client never guesses bytes, it lets the full "
                    "repaint overwrite the screen.",
        excerpt=True,
        frames=[fr("s2c", "TOUT", {"sid": SID, "tseq": 7}, b"abc"),
                fr("s2c", "TOUT", {"sid": SID, "tseq": 11, "redraw": True}, b"\x1b[H\x1b[2J...full screen...")],
        expect={"gap": [8, 10], "daemonTmux": ["tmux", "-N", "-S", "<guarded socket path>", "refresh-client", "-t", "<own client tty>"]},
    ))

    # 14. size owner with two subscribers on two connections
    evs = [{"sid": SID, "dev": DEV_D, "kind": "sub", "active": True, "cols": 200, "rows": 50},
           {"sid": SID, "dev": DEV_P, "kind": "tsize", "cols": 60, "rows": 30},
           {"sid": SID, "dev": DEV_P, "kind": "in", "note": "watch reply, phone has no term SUB"},
           {"sid": SID, "dev": DEV_P, "kind": "tsize", "cols": 60, "rows": 30},
           {"sid": SID, "dev": DEV_P, "kind": "sub", "active": True, "cols": 60, "rows": 30}]
    own = size_owner(evs)
    ts = lambda o, dev: o["tstate"][[x["to"] for x in o["tstate"]].index(dev)]["tstate"]
    V.append(dict(
        id="14-term-resize-owner", spec="5.3",
        title="Only the owner sizes the window; IN without a term SUB does not take ownership; TSTATE goes to all",
        description="Desktop (conn A) subscribed active at 200x50. The phone (conn B) TSIZE is refused (ERR E_NOT_ACTIVE, "
                    "non-fatal). A watch reply from the phone (IN, no term SUB) is typed through the existing attach but "
                    "does NOT move ownership, so the next TSIZE is refused too. Then the phone subscribes active: it owns "
                    "the size, and the TSTATE with the new geometry goes to EVERY subscriber; the desktop adopts 60x30 "
                    "(letterbox) instead of drawing 60x30 bytes into a 200x50 emulator.",
        excerpt=True,
        frames=[fr("c2s", "SUB", {"st": "term", "sid": SID, "cols": 200, "rows": 50, "active": True}, conn="A"),
                fr("s2c", "TSTATE", ts(own[0], DEV_D), conn="A"),
                fr("c2s", "TSIZE", {"sid": SID, "cols": 60, "rows": 30}, conn="B"),
                fr("s2c", "ERR", {"code": "E_NOT_ACTIVE", "re": "TSIZE", "sid": SID}, conn="B"),
                fr("c2s", "IN", {"sid": SID, "iseq": 3, "exp": EXP}, b"x", conn="B"),
                fr("s2c", "INACK", {"sid": SID, "iseq": 3, "st": "applied", "via": "pty"}, conn="B"),
                fr("c2s", "TSIZE", {"sid": SID, "cols": 60, "rows": 30}, conn="B"),
                fr("s2c", "ERR", {"code": "E_NOT_ACTIVE", "re": "TSIZE", "sid": SID}, conn="B"),
                fr("c2s", "SUB", {"st": "term", "sid": SID, "cols": 60, "rows": 30, "active": True}, conn="B"),
                fr("s2c", "TSTATE", ts(own[4], DEV_P), conn="B"),
                fr("s2c", "TSTATE", ts(own[4], DEV_D), conn="A")],
        expect={"model": own},
    ))

    # 15. parked input applied, then duplicate
    dd = InDedup()
    key = (DEV_P, IE_P, SID)
    dd.hi[key] = 6
    payload = "Odpověz jen slovem OK\r".encode()
    s1 = dd.offer(key, 7, payload)
    s2 = dd.offer(key, 7, payload)
    V.append(dict(
        id="15-in-applied-dup", spec="5.4",
        title="Parked input with server pacing; the retransmit after reconnect is a dup with the original result",
        description="No term attach for this sid, so the daemon delivers through send-keys with the S0.4 pacing: text with "
                    "-l, 60 ms, Enter. The same iseq (same payload) resent after a reconnect is answered dup, orig=applied, "
                    "and NOT typed again.",
        excerpt=True,
        frames=[fr("c2s", "IN", {"sid": SID, "iseq": 7, "exp": EXP}, payload),
                fr("s2c", "INACK", in_ack(SID, 7, s1, via="keys")),
                fr("c2s", "IN", {"sid": SID, "iseq": 7, "exp": EXP}, payload, note="retransmit on the new connection"),
                fr("s2c", "INACK", in_ack(SID, 7, s2))],
        expect={"statuses": [s1, s2], "plan": pacing_plan(SID, payload), "payloadHash": in_hash(payload)},
    ))

    # 16. input across a daemon crash + gap
    dd = InDedup()
    dd.hi[key] = 8
    dd.crash_after_intent(key, 9, b"1")
    ring = Ring(EP, 4096)
    ring.seed({"events": ["ev-a"]})
    a = dd.offer(key, 9, b"1")
    b = dd.offer(key, 11, b"y")
    c = dd.offer(key, 10, b"\x1b")
    d = dd.offer(key, 11, b"y")
    neg = negotiate(hello())
    V.append(dict(
        id="16-in-unknown-and-gap", spec="5.4, 8.2",
        title="Intent persisted, daemon died before tmux confirmed: unknown; a gap is refused and consumes nothing",
        description="At-most-once: iseq 9 is never typed twice; the client is told 'unknown' and shows that the reply may "
                    "have arrived only partly. iseq 11 before 10 is refused E_IN_GAP (not consumed); the client sends 10, "
                    "then 11 again.",
        frames=[fr("c2s", "HELLO", hello(resume={"ev": {"ep": EP_OLD, "seq": 3}})),
                fr("s2c", "WELCOME", W(neg, {"ev": ring_streams(ring)}, resumed={"ev": "snapshot"},
                                       inhi={SID: {"iseq": 9, "unknown": [9]}})),
                fr("s2c", "SNAP", ring.snap_header("ev", "epoch"))] + ev_line_frames(ring, ring.backlog()) + [
                fr("c2s", "IN", {"sid": SID, "iseq": 9, "exp": EXP}, b"1"),
                fr("s2c", "INACK", in_ack(SID, 9, a)),
                fr("c2s", "IN", {"sid": SID, "iseq": 11, "exp": EXP}, b"y"),
                fr("s2c", "INACK", in_ack(SID, 11, b)),
                fr("c2s", "IN", {"sid": SID, "iseq": 10, "exp": EXP}, b"\x1b"),
                fr("s2c", "INACK", in_ack(SID, 10, c, via="keys")),
                fr("c2s", "IN", {"sid": SID, "iseq": 11, "exp": EXP}, b"y"),
                fr("s2c", "INACK", in_ack(SID, 11, d, via="keys"))],
        expect={"statuses": [a, b, c, d], "planEscape": pacing_plan(SID, b"\x1b")},
    ))

    # 17. rpc exec allowed
    cmd = "timeout 2 sh -c \"tmux list-sessions -F '#{session_name}|#{session_created}'\""
    assert deny_scan("reconcile", cmd) is None
    out = (SID + "|1793900000\nclaude-server-cashy|1793800000\n").encode()
    V.append(dict(
        id="17-rpc-exec-allowed", spec="6.1, 6.2",
        title="rpc exec: allowed tag and verbs, complete result",
        description="RPCRES is sent once with the whole stdout (or RPCPART pieces before it); never a partial result with ok:true.",
        excerpt=True,
        frames=[fr("c2s", "RPC", {"id": 31, "m": "exec", "tag": "reconcile", "cmd": cmd, "ms": 10000, "kind": "short"}),
                fr("s2c", "RPCRES", {"id": 31, "ok": True, "rc": 0, "part": 0, "last": True}, out)],
        expect={"denyScan": None},
    ))

    # 18. rpc exec with a stdout split in parts
    big = ("x" * 50 + "\n").encode() * 3
    V.append(dict(
        id="18-rpc-exec-parts", spec="6.1",
        title="rpc exec output in pieces: RPCPART frames, then RPCRES",
        description="Parts are numbered from 0 and concatenated in order; ok/rc arrive only with the last part. (Pieces are "
                    "<= 48 KiB on the wire; this vector uses 100 B to stay small.)",
        excerpt=True,
        frames=[fr("c2s", "RPC", {"id": 32, "m": "exec", "tag": "git.status", "cmd": "git -C ~/claude-remote status --porcelain", "ms": 20000, "kind": "short"}),
                fr("s2c", "RPCPART", {"id": 32, "part": 0}, big[:100]),
                fr("s2c", "RPCRES", {"id": 32, "ok": True, "rc": 0, "part": 1, "last": True}, big[100:])],
        expect={"stdoutBytes": len(big), "denyScan": deny_scan("git.status", "git -C ~/claude-remote status --porcelain")},
    ))

    # 19-23. rpc exec refused
    refused = [
        ("19-rpc-denied-kill-server", "reconcile", "tmux list-sessions; tmux kill-server", "A forbidden verb anywhere in the command"),
        ("20-rpc-denied-unknown-tag", "debug.shell", "id -u", "A tag that is not on the list"),
        ("21-rpc-denied-new-alias", "redraw", "tmux new -A -s x", "tmux accepts aliases and unique prefixes: the verb must be an exact allowlisted name"),
        ("22-rpc-denied-fresh-create", "probe.has-session",
         "export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}; CR_SG=0; [ -x \"$HOME/.claude-remote/tmux-sockheal.sh\" ] && "
         "{ \"$HOME/.claude-remote/tmux-sockheal.sh\"; CR_SG=$?; }; tmux list-sessions >/dev/null 2>&1 || systemd-run --user --scope "
         "--quiet tmux new-session -d -x 200 -y 50 -s __anchor__ sleep infinity >/dev/null 2>&1 || true; "
         "tmux set-option -g exit-empty off 2>/dev/null || true",
         "The shape of ClaudeConfig.buildTmuxRunCommand (V16 fresh create) is refused: creation stays on the Stage A exec channel (D-B0-4)"),
        ("23-rpc-denied-set-g", "redraw", "tmux refresh-client -S \\; set -g status off", "A chained `\\;` verb is checked like the first one"),
    ]
    rid = 40
    for vid, tag, c, why in refused:
        rule = deny_scan(tag, c)
        assert rule is not None, vid
        V.append(dict(
            id=vid, spec="6.2",
            title="rpc exec refused: " + rule,
            description=why + ". The daemon runs nothing; RPCRES ok:false E_DENIED with the rule. The client maps it to "
                              "ExecUnavailable(REFUSED) and does NOT retry it on another transport.",
            excerpt=True,
            frames=[fr("c2s", "RPC", {"id": rid, "m": "exec", "tag": tag, "cmd": c, "ms": 5000, "kind": "short"}),
                    fr("s2c", "RPCRES", {"id": rid, "ok": False, "code": "E_DENIED", "rule": rule, "part": 0, "last": True})],
            expect={"denyScan": rule, "kotlin": "ExecUnavailable(REFUSED)"},
        ))
        rid += 1

    # 24. ping/pong + superseded by a newer attempt of the same (dev, link)
    t = ConnTable()
    r1 = t.hello("A", DEV_P, LINK_CF, 7)
    r2 = t.hello("B", DEV_P, LINK_CF, 8)
    V.append(dict(
        id="24-ping-pong-superseded", spec="2.4, 2.5",
        title="Liveness, and a newer HELLO of the same (dev, link) supersedes the old connection",
        description="PONG echoes t and adds the server clock. Conn A (att 7, a half-open CF path) is superseded by conn B "
                    "(same dev and link, att 8): A gets BYE SUPERSEDED (no retryMs) and is closed; frames still arriving "
                    "on A are discarded. The client ignores a SUPERSEDED on a connection it has already replaced.",
        frames=[fr("c2s", "HELLO", hello(link=LINK_CF, att=7), conn="A"),
                fr("s2c", "WELCOME", W(negotiate(hello()), {"ev": {"ep": EP, "first": 1, "last": 0}}, conn="c-0007"), conn="A"),
                fr("c2s", "PING", {"t": 123456}, conn="A"), fr("s2c", "PONG", {"t": 123456, "now": NOW}, conn="A"),
                fr("c2s", "HELLO", hello(link=LINK_CF, att=8), conn="B"),
                fr("s2c", "WELCOME", W(negotiate(hello()), {"ev": {"ep": EP, "first": 1, "last": 0}}, conn="c-0008"), conn="B"),
                fr("s2c", "BYE", {"code": "SUPERSEDED", "msg": "newer connection of this device and link"}, conn="A")],
        expect={"table": [r1, r2], "clientOnSuperseded": "ignore if already replaced; else Down(superseded), no reconnect for 60 s"},
    ))

    # 25. transcript line larger than a frame: TX pieces with more:true
    segs = [('{"n":1}\n{"type":"user","message":{"content":[{"type":"image","source":{"data":"', 1), ("A", 70000),
            ('"}}]}}\n{"n":3}\n', 1)]
    content = content_of(segs)
    w = TxWatch(8)
    r = w.poll(content)
    pieces = tx_pieces("w9", "0f8e2c1a-1111-4222-8333-944455556666", r)
    neg = negotiate(hello(max_frame=MIN_MAX_FRAME))
    V.append(dict(
        id="25-tx-line-pieces", spec="2.6, 4.6",
        title="A transcript line larger than maxFrame: TX pieces with more:true",
        description="Real transcripts carry lines up to 1.5 MB (base64 images). The daemon never sends a frame above the "
                    "negotiated maxFrame: a delta is cut into pieces of <= 48 KiB regardless of line ends; every piece but "
                    "the last carries more:true, f/o are file offsets of the piece. The client concatenates pieces and "
                    "hands the delta (f of the first, o of the last) to the transcript reader once the last piece arrived. "
                    "This connection negotiated the minimum maxFrame 65536.",
        maxFrame=MIN_MAX_FRAME,
        frames=[fr("c2s", "HELLO", hello(max_frame=MIN_MAX_FRAME)),
                fr("s2c", "WELCOME", W(neg, {})),
                fr("c2s", "SUB", {"st": "tx", "wid": "w9", "uuid": "0f8e2c1a-1111-4222-8333-944455556666", "cwd": "~/claude-remote", "off": 8, "enc": "none"})]
               + [fr("s2c", "TX", h, b) for h, b in pieces]
               + [fr("c2s", "ACK", {"acks": {}, "tx": {"w9": pieces[-1][0]["o"]}})],
        expect={"negotiation": neg, "pieces": [{"f": h["f"], "o": h["o"], "more": h.get("more", False)} for h, _ in pieces],
                "delta": {"f": r["f"], "o": r["o"], "bytes": len(r["chunk"])}},
        modelInput={"tx": tx_input(8, segs)},
    ))

    # 26. reset survives an empty adjusted read
    segs1 = [('{"a":1}\n', 1), ("x", TAIL + 5000), ("\n", 1)]
    segs2 = segs1 + [('{"b":2}\n', 1)]
    content1 = content_of(segs1)
    w = TxWatch(0)
    p1 = w.poll(content1)
    content2 = content_of(segs2)
    p2 = w.poll(content2)
    V.append(dict(
        id="26-tx-reset-pending", spec="4.6",
        title="An adjusted read that yields nothing keeps reset pending until the next delta",
        description="off=0 on a file larger than 200 000 B whose last 200 000 B lie inside one giant line: streamd drops "
                    "the partial first line, the chunk is empty and nothing is sent, but the offset moves. CRP keeps "
                    "reset pending, so the next delta carries reset:true (revision 1 lost it).",
        excerpt=True,
        frames=[fr("c2s", "SUB", {"st": "tx", "wid": "w10", "uuid": "1a2b3c4d-0000-4000-8000-000000000002", "cwd": "~", "off": 0, "enc": "none"})]
               + [fr("s2c", "TX", h, b) for h, b in tx_pieces("w10", "1a2b3c4d-0000-4000-8000-000000000002", p2)],
        expect={"poll1": p1, "poll2": {k: v for k, v in p2.items() if k != "chunk"}},
        modelInput={"tx": tx_input(0, segs1, segs2)},
    ))

    # 27. empty ring after a quiet weekend
    ring = Ring(EP, 4096)
    append_lines(ring, 1, 4000, ["events", "notify"])
    ring.trim_before(4001)  # 24 h without hooks: the age limit emptied the resume ring
    ra = ring.resume({"ep": EP, "seq": 4000})
    rb = ring.resume({"ep": EP, "seq": 3990})
    rc = ring.resume({"ep": EP, "seq": 4005})
    nxt = ring.append("events", "line-4001")
    V.append(dict(
        id="27-ev-empty-ring", spec="4.3, 4.4",
        title="Resume against an empty ring: `last` is the counter, the backlog ignores age",
        description="After 24 h without hook events the age limit emptied the resume ring (first = last + 1 = 4001). "
                    "Device A at 4000 resumes with an empty replay; device B at 3990 gets SNAP(overflow) whose backlog is "
                    "still the newest 25 per source (Stage A's tail -n 25 has no age limit either) and ends at S = 4000. "
                    "The next live item is 4001 on both. A position above `last` (client bug) is SNAP(ahead) (expect only).",
        frames=[fr("c2s", "HELLO", hello(resume={"ev": {"ep": EP, "seq": 4000}}), conn="A"),
                fr("s2c", "WELCOME", W(negotiate(hello()), {"ev": {"ep": EP, "first": 4001, "last": 4000}}, resumed={"ev": "replay"}), conn="A"),
                fr("c2s", "HELLO", hello(dev=DEV_D, resume={"ev": {"ep": EP, "seq": 3990}}), conn="B"),
                fr("s2c", "WELCOME", W(negotiate(hello()), {"ev": {"ep": EP, "first": 4001, "last": 4000}}, resumed={"ev": "snapshot"}, conn="c-0002"), conn="B"),
                fr("s2c", "SNAP", {"st": "ev", "ep": EP, "seq": 4000, "from": rb[2][0][0], "reason": rb[1]}, conn="B")]
               + ev_line_frames(ring, rb[2][-2:], conn="B")
               + ev_line_frames(ring, [ring.items[-1]], conn="A") + ev_line_frames(ring, [ring.items[-1]], conn="B"),
        expect={"A": {"resume": ra[0], "replaySeqs": [i[0] for i in ra[1]]},
                "B": {"resume": rb[0], "reason": rb[1], "backlogCount": len(rb[2]), "backlogLastSeq": rb[2][-1][0], "framesShown": 2},
                "C-ahead (model only)": {"resume": rc[0], "reason": rc[1], "backlogCount": len(rc[2])},
                "nextLiveSeq": nxt},
        modelInput={"ring": ring_input(ring)},
    ))

    # 28. daemon start seeds the ring from the files
    files = {"events": [f"claude-remote-ev work x {i}" for i in range(1, 31)], "notify": ["stop-a", "stop-b"],
             "legacy": [], "forget": ["forget claude-server-old"]}
    ring = Ring(EP, 4096)
    ring.seed(files)
    V.append(dict(
        id="28-ev-restart-seed", spec="4.4, 4.7",
        title="Daemon start: seed the ring with the last 25 lines of each source, then follow from EOF",
        description="A new epoch does not start empty: the daemon reads the last 25 lines of events, notify, legacy and "
                    "forget (in that order), assigns seq 1.. and only then follows the files from EOF. A Stop marker written "
                    "during the restart window is therefore in the backlog, as with Stage A's tail -n 25.",
        frames=[fr("c2s", "HELLO", hello()),
                fr("s2c", "WELCOME", W(negotiate(hello()), {"ev": ring_streams(ring)})),
                fr("c2s", "SUB", {"st": "ev"}),
                fr("s2c", "SNAP", ring.snap_header("ev", "new"))] + ev_line_frames(ring, ring.backlog()[-3:]),
        expect={"seeded": [{"seq": s, "src": src} for s, src, _ in ring.items], "last": ring.last, "framesShown": 3},
        modelInput={"ring": ring_input(ring)},
    ))

    # 29. mid-connection overflow of a paused stream
    ring = Ring(EP, 100)
    append_lines(ring, 1, 50, ["events"])
    sent = 50  # the connection is paused: 50 unacked items (window shrunk to 50 in this model)
    append_lines(ring, 51, 180, ["events"])
    nx = ring.next_for(sent)
    cs = ClientStream(EP, 50)
    steps = [cs.on_snap(EP, ring.last, nx[2][0][0])] + [cs.on_item(EP, it[0]) for it in nx[2]] + [cs.on_item(EP, 182)]
    V.append(dict(
        id="29-ev-midstream-overflow", spec="4.2, 4.3",
        title="A paused connection whose next item left the ring gets SNAP(overflow) in the middle of the connection",
        description="The client stopped acking (background), the daemon paused the stream at seq 50; meanwhile the ring "
                    "(capacity 100 in this model) moved to 81..180. On the next ACK the daemon cannot send 51, so it sends "
                    "SNAP(overflow) + backlog in the live connection. A client that ever sees seq > position+1 in the same "
                    "ep outside a backlog MUST close and resume (here: 182 after 180).",
        excerpt=True,
        frames=[fr("c2s", "ACK", {"acks": {"ev": {"ep": EP, "seq": 50}}}),
                fr("s2c", "SNAP", ring.snap_header("ev", "overflow"))] + ev_line_frames(ring, nx[2][-2:]),
        expect={"next": [nx[0], nx[1]], "backlogCount": len(nx[2]), "clientSteps": steps[:1] + steps[-3:],
                "clientPosition": cs.pos},
        modelInput={"ring": ring_input(ring)},
    ))

    # 30. ack rules
    g = AckGuard(EP, resume_seq=43)
    acks = [("after-resume", EP, 43), ("old-ep", EP_OLD, 900), ("below", EP, 40), ("above-sent", EP, 99)]
    results = {}
    for name, ep, seq in acks:
        results[name] = g.ack(ep, seq)
    ring = Ring(EP, 4096)
    append_lines(ring, 1, 43, ["events"])
    V.append(dict(
        id="30-ack-rules", spec="4.2",
        title="ACK right after resume is legal; an ACK with another ep is ignored; above what was sent is E_PROTO",
        description="'Sent' = max(resume position, highest seq sent, S of the last SNAP). The client may ACK its resume "
                    "position at once (revision 1 would have answered E_PROTO and looped). An ACK carrying an older ep "
                    "(delayed across a SNAP(epoch)) is ignored; one below the last ACK is ignored; one above 'sent' is a "
                    "protocol error.",
        frames=[fr("c2s", "HELLO", hello(resume={"ev": {"ep": EP, "seq": 43}})),
                fr("s2c", "WELCOME", W(negotiate(hello()), {"ev": ring_streams(ring)}, resumed={"ev": "replay"})),
                fr("c2s", "ACK", {"acks": {"ev": {"ep": EP, "seq": 43}}}),
                fr("c2s", "ACK", {"acks": {"ev": {"ep": EP_OLD, "seq": 900}}}),
                fr("c2s", "ACK", {"acks": {"ev": {"ep": EP, "seq": 40}}}),
                fr("c2s", "ACK", {"acks": {"ev": {"ep": EP, "seq": 99}}}),
                fr("s2c", "BYE", {"code": "E_PROTO", "msg": "ACK above sent"})],
        expect={"daemon": results},
        modelInput={"ring": ring_input(ring)},
    ))

    # 31. two links of one device to the same host: no supersede
    t = ConnTable()
    a = t.hello("A", DEV_P, LINK_LAN, 3)
    b = t.hello("B", DEV_P, LINK_CF, 1)
    V.append(dict(
        id="31-hello-two-links", spec="2.1, 2.5",
        title="Same device, two serverIds (LAN entry and CF entry) to one host: two connections, no supersede",
        description="Connections are keyed by (dev, link), link = first 16 hex of sha256(serverId). Revision 1 keyed by dev "
                    "alone and the two entries would supersede each other forever. Each link has its own `ie`, so the "
                    "two connections never share an input key.",
        frames=[fr("c2s", "HELLO", hello(link=LINK_LAN, att=3), conn="A"),
                fr("s2c", "WELCOME", W(negotiate(hello()), {}, conn="c-0011"), conn="A"),
                fr("c2s", "HELLO", hello(link=LINK_CF, att=1, ie="9d0c7e3b2a1f4856"), conn="B"),
                fr("s2c", "WELCOME", W(negotiate(hello()), {}, conn="c-0012"), conn="B")],
        expect={"table": [a, b]},
    ))

    # 32. stale HELLO from an abandoned path
    t = ConnTable()
    a = t.hello("A", DEV_P, LINK_CF, 9)
    b = t.hello("B", DEV_P, LINK_CF, 8)
    V.append(dict(
        id="32-hello-stale-att", spec="2.5",
        title="A delayed HELLO with a lower attempt number is refused STALE and does not touch the live connection",
        description="The client gave up on a half-open CF channel (attempt 8) and reconnected over the same link with "
                    "attempt 9. The bytes of attempt 8 that were stuck on the dead path arrive afterwards. Without `att` the daemon would let "
                    "the stale HELLO supersede the live connection. The client increments and persists att before every HELLO.",
        frames=[fr("c2s", "HELLO", hello(link=LINK_CF, att=9), conn="A"),
                fr("s2c", "WELCOME", W(negotiate(hello()), {}, conn="c-0021"), conn="A"),
                fr("c2s", "HELLO", hello(link=LINK_CF, att=8), conn="B"),
                fr("s2c", "BYE", {"code": "STALE", "msg": "older attempt than the live connection"}, conn="B")],
        expect={"table": [a, b]},
    ))

    # 33. refusal in the middle of a pipeline, permanent vs transient
    dd = InDedup()
    dd.hi[key] = 9
    bad = b"\x01"
    r10 = dd.offer(key, 10, bad, outcome="refused:" + pacing_plan(SID, bad)["refused"])
    r11 = dd.offer(key, 11, b"a")
    r12t = dd.offer(key, 12, b"b", outcome="refused:E_TMUX_SOCKET")
    r12 = dd.offer(key, 12, b"b")
    r10d = dd.offer(key, 10, bad)
    V.append(dict(
        id="33-in-refused-midpipeline", spec="5.4",
        title="A permanent refusal consumes its iseq; a transient one does not; a dup reports the original result",
        description="10 carries an unsupported control byte: refused E_IN_UNSUPPORTED, final, iseq consumed, so 11 applies "
                    "(revision 1 would answer E_IN_GAP for 11 forever). 12 meets an unusable tmux socket: E_TMUX_SOCKET is "
                    "transient, nothing is consumed, the client resends 12 later and it applies. A resend of 10 is "
                    "dup with orig=refused:E_IN_UNSUPPORTED.",
        excerpt=True,
        frames=[fr("c2s", "IN", {"sid": SID, "iseq": 10, "exp": EXP}, bad),
                fr("c2s", "IN", {"sid": SID, "iseq": 11, "exp": EXP}, b"a"),
                fr("s2c", "INACK", in_ack(SID, 10, r10)),
                fr("s2c", "INACK", in_ack(SID, 11, r11, via="keys")),
                fr("c2s", "IN", {"sid": SID, "iseq": 12, "exp": EXP}, b"b"),
                fr("s2c", "INACK", in_ack(SID, 12, r12t)),
                fr("c2s", "IN", {"sid": SID, "iseq": 12, "exp": EXP}, b"b"),
                fr("s2c", "INACK", in_ack(SID, 12, r12, via="keys")),
                fr("c2s", "IN", {"sid": SID, "iseq": 10, "exp": EXP}, bad),
                fr("s2c", "INACK", in_ack(SID, 10, r10d))],
        expect={"statuses": [r10, r11, r12t, r12, r10d]},
    ))

    # 34. same iseq, different payload
    dd = InDedup()
    dd.hi[key] = 20
    first_ = dd.offer(key, 21, b"ano\r")
    conflict = dd.offer(key, 21, b"ne\r")
    V.append(dict(
        id="34-in-conflict", spec="5.4",
        title="A reused iseq with a different payload is E_IN_CONFLICT, never a silent dup",
        description="The daemon records sha256(payload)[:16] with every final answer. A different payload under an iseq it "
                    "already answered means the client's counter and the daemon's state disagree (lost outbox, cloned host); "
                    "the daemon refuses with E_IN_CONFLICT (nothing consumed) and the client rotates `ie` for that link "
                    "and sends the text as iseq 1 of the new key.",
        excerpt=True,
        frames=[fr("c2s", "IN", {"sid": SID, "iseq": 21, "exp": EXP}, b"ano\r"),
                fr("s2c", "INACK", in_ack(SID, 21, first_, via="keys")),
                fr("c2s", "IN", {"sid": SID, "iseq": 21, "exp": EXP}, b"ne\r"),
                fr("s2c", "INACK", in_ack(SID, 21, conflict))],
        expect={"statuses": [first_, conflict], "client": "rotate-ie"},
    ))

    # 35. INSKIP: abandon iseqs the client no longer has
    dd = InDedup()
    gap = dd.offer(key, 57, b"ok\r")
    sk = dd.skip(key, 56)
    ok = dd.offer(key, 57, b"ok\r")
    old = dd.offer(key, 30, b"whatever")
    V.append(dict(
        id="35-in-skip", spec="5.4",
        title="A gap the client cannot fill: INSKIP moves hi without typing anything",
        description="The daemon pruned the key of a session that came back under the same name (hi 0); the client's next "
                    "iseq is 57 and its outbox holds nothing below it. E_IN_GAP carries the daemon's hi; the client sends "
                    "INSKIP{to:56} (only ever for iseqs it has no payload for), then 57 applies. A later resend of an "
                    "abandoned iseq is dup orig=skipped.",
        excerpt=True,
        frames=[fr("c2s", "IN", {"sid": SID, "iseq": 57, "exp": EXP}, b"ok\r"),
                fr("s2c", "INACK", in_ack(SID, 57, gap)),
                fr("c2s", "INSKIP", {"sid": SID, "to": 56}),
                fr("s2c", "INACK", {"sid": SID, "iseq": 56, "st": "skipped"}),
                fr("c2s", "IN", {"sid": SID, "iseq": 57, "exp": EXP}, b"ok\r"),
                fr("s2c", "INACK", in_ack(SID, 57, ok, via="keys"))],
        expect={"statuses": [gap, sk, ok], "resendOf30": old},
    ))

    # 36. daemon lost its input state
    cases = {
        "inGen-changed": client_on_welcome("1c0ffee0", INGEN, {SID: 57}, {SID: {"iseq": 57}}),
        "inHi-below-acked": client_on_welcome(INGEN, INGEN, {SID: 57}, {SID: {"iseq": 40}}),
        "normal": client_on_welcome(INGEN, INGEN, {SID: 57}, {SID: {"iseq": 59}}),
    }
    V.append(dict(
        id="36-in-state-lost", spec="5.4",
        title="The daemon's input state is not the one the client talked to: rotate `ie`, never resend",
        description="WELCOME.inGen identifies the daemon's input.log generation (new when the state is created from "
                    "scratch). A changed inGen, or inHi below what the client already had acknowledged (an older copy of "
                    "the state, e.g. the cloned CT of 2026-10-31), makes the client rotate `ie` for that link: counters "
                    "restart at 1, acknowledged input is never resent, unacknowledged input is shown 'unknown'. Otherwise "
                    "the next iseq is max(local, inHi) + 1 and the outbox above inHi is resent in order.",
        frames=[fr("c2s", "HELLO", hello()),
                fr("s2c", "WELCOME", W(negotiate(hello()), {}, inhi={SID: {"iseq": 40}}))],
        expect=cases,
    ))

    # 37. paste plan: newline, trailing ';', invalid UTF-8
    plans = {
        "newline": pacing_plan(SID, "první řádek\ndruhý\r".encode()),
        "trailing-semicolon": pacing_plan(SID, b"echo a;\r"),
        "split-utf8": pacing_plan(SID, "ž".encode()[:1]),
        "ctrl-u": pacing_plan(SID, b"\x15"),
    }
    V.append(dict(
        id="37-in-paste-plan", spec="5.4",
        title="Delivery plans: multi-line text and a trailing ';' go through paste-buffer; broken UTF-8 is refused",
        description="A newline inside a reply is pasted (bracketed paste keeps it a newline, not a submit); a run ending "
                    "in ';' is pasted because tmux reads an argv ending in ';' as a command separator; an IN that does not "
                    "hold complete UTF-8 sequences is refused E_IN_UTF8 (final, consumes the iseq).",
        excerpt=True,
        frames=[fr("c2s", "IN", {"sid": SID, "iseq": 70, "exp": EXP}, "první řádek\ndruhý\r".encode()),
                fr("s2c", "INACK", {"sid": SID, "iseq": 70, "st": "applied", "via": "keys"}),
                fr("c2s", "IN", {"sid": SID, "iseq": 71, "exp": EXP}, "ž".encode()[:1]),
                fr("s2c", "INACK", {"sid": SID, "iseq": 71, "st": "refused", "code": "E_IN_UTF8"})],
        expect={"plans": plans},
    ))

    # 38. deny table
    deny = [{"tag": tg, "cmd": c, "rule": deny_scan(tg, c), "why": why} for tg, c, why in DENY_TABLE]
    for d_ in deny:
        assert d_["rule"] is not None, d_
    V.append(dict(
        id="38-rpc-deny-table", spec="6.2",
        title="deny_scan negative table: every bypass the critiques found is refused",
        description="Go and Kotlin MUST return exactly `rule` for every row. The frames show the first row on the wire.",
        excerpt=True,
        frames=[fr("c2s", "RPC", {"id": 60, "m": "exec", "tag": deny[0]["tag"], "cmd": deny[0]["cmd"], "ms": 5000, "kind": "short"}),
                fr("s2c", "RPCRES", {"id": 60, "ok": False, "code": "E_DENIED", "rule": deny[0]["rule"], "part": 0, "last": True})],
        expect={"cases": deny},
    ))

    # 39. allow table
    allow = [{"tag": tg, "cmd": c, "rule": deny_scan(tg, c), "site": site} for tg, c, site in ALLOW_TABLE]
    for a_ in allow:
        assert a_["rule"] is None, a_
    V.append(dict(
        id="39-rpc-allow-table", spec="6.2, 9.3",
        title="deny_scan positive table: the tagged sites' real command shapes pass",
        description="Rendered from the builders at 2841e18 with representative inputs. B2's test renders the builders "
                    "themselves; this table pins the scanner against false refusals of today's shapes.",
        excerpt=True,
        frames=[fr("c2s", "RPC", {"id": 61, "m": "exec", "tag": "health", "cmd": "echo pong", "ms": 10000, "kind": "short"}),
                fr("s2c", "RPCRES", {"id": 61, "ok": True, "rc": 0, "part": 0, "last": True}, b"pong\n")],
        expect={"cases": allow},
    ))

    # 40. rpc errors
    V.append(dict(
        id="40-rpc-errors", spec="6.1, 9.2",
        title="rpc exec failures: E_TIMEOUT, E_BUSY, E_OUTPUT",
        description="ms exceeded (child killed, TERM, 1 s, KILL) => E_TIMEOUT => ExecUnavailable(TIMEOUT); over the in-flight "
                    "limit => E_BUSY => GATE_TIMEOUT; stdout over 8 MiB => E_OUTPUT => IO, never a partial ok:true.",
        excerpt=True,
        frames=[fr("c2s", "RPC", {"id": 70, "m": "exec", "tag": "git.status", "cmd": "git status", "ms": 20000, "kind": "short"}),
                fr("s2c", "RPCRES", {"id": 70, "ok": False, "code": "E_TIMEOUT", "rc": -1, "part": 0, "last": True}),
                fr("c2s", "RPC", {"id": 71, "m": "exec", "tag": "health", "cmd": "echo pong", "ms": 10000, "kind": "short"}),
                fr("s2c", "RPCRES", {"id": 71, "ok": False, "code": "E_BUSY", "part": 0, "last": True}),
                fr("c2s", "RPC", {"id": 72, "m": "exec", "tag": "reconcile", "cmd": "cat big", "ms": 10000, "kind": "short"}),
                fr("s2c", "RPCRES", {"id": 72, "ok": False, "code": "E_OUTPUT", "rc": 0, "part": 0, "last": True})],
        expect={"kotlin": {"E_TIMEOUT": "TIMEOUT", "E_BUSY": "GATE_TIMEOUT", "E_OUTPUT": "IO"}},
    ))

    # 41. canonical JSON with non-ASCII and characters Go escapes by default
    sub = {"st": "tx", "wid": "w11", "uuid": "1a2b3c4d-0000-4000-8000-000000000003",
           "cwd": "~/Dokumenty/Účetnictví 2026/<a&b> x\"q\\\t", "off": -1, "enc": "none"}
    V.append(dict(
        id="41-header-nonascii", spec="3.1",
        title="Canonical header JSON: UTF-8 raw, U+2028 and <>& raw, only quote, backslash and C0 escaped",
        description="Go's json.Marshal escapes <, >, & and U+2028 and MUST NOT be used for headers; kotlinx.serialization "
                    "does not sort keys and MUST NOT be used either. Both implementations need a small canonical writer.",
        excerpt=True,
        frames=[fr("c2s", "SUB", sub)],
        expect={"headerHex": canon(sub).hex()},
    ))

    # 42. tmux socket unusable, tmux server replaced
    table = [{"name": "claude-server-cashy", "created": 1793800000, "clients": 0, "panePid": 2400001, "cmd": "claude", "dead": False}]
    V.append(dict(
        id="42-tmux-socket-and-server-change", spec="5.1, 6.3",
        title="Socket unusable: TSTATE refused; tmux server replaced: server-changed and SNAP(tmux-restart), never mass `gone`",
        description="The guard refuses while the socket is missing or has != 1 listener (E_TMUX_SOCKET, transient). When the "
                    "listener's identity (pid + start time) changes, the daemon sends TSTATE server-changed to term "
                    "subscribers and a sess SNAP(reason=tmux-restart) instead of `del` for every row. Neither ever drives "
                    "a rebuild, relaunch or respawn on the client: that stays a Stage A decision with its own guard.",
        excerpt=True,
        frames=[fr("s2c", "TSTATE", {"sid": SID, "state": "refused", "reason": "E_TMUX_SOCKET"}),
                fr("s2c", "TSTATE", {"sid": SID, "state": "server-changed"}),
                fr("s2c", "SNAP", {"st": "sess", "ep": EP, "seq": 90, "from": 91, "reason": "tmux-restart", "sessions": table})],
        expect={"client": {"server-changed": "re-SUB later; no rebuild", "tmux-restart": "replace table; no forget, no rebuild"}},
    ))

    # 43. STATE{fg}
    V.append(dict(
        id="43-state-fg", spec="2.4",
        title="Foreground/background change mid-connection: STATE{fg, pingMs}",
        description="The client tells the daemon its ping interval (15 s foreground, 60 s background, 5 s with an active "
                    "term SUB). The daemon never pings on its own and closes a connection only after 3 x pingMs (min 45 s) "
                    "without any frame. The client declares the connection dead after 2 x pingMs + 5 s without any frame "
                    "RECEIVED, counted from the last received frame, not from its own last send.",
        excerpt=True,
        frames=[fr("c2s", "STATE", {"fg": False, "pingMs": 60000}),
                fr("c2s", "PING", {"t": 900}), fr("s2c", "PONG", {"t": 900, "now": NOW})],
        expect={"daemonCloseAfterMs": max(45000, 3 * 60000), "clientDeadAfterMs": 2 * 60000 + 5000},
    ))

    # 44. E_OFF and E_SHUTDOWN mid-connection
    V.append(dict(
        id="44-bye-off-shutdown", spec="1.4, 7.3, 7.8",
        title="crd.off appears: BYE E_OFF on every live connection; daemon stop: BYE E_SHUTDOWN with retryMs",
        description="The daemon watches ~/.claude-remote/crd.off; on its appearance every connection gets BYE E_OFF (client: "
                    "Stage A, retry only after the next bootstrap). On SIGTERM it finishes the running per-session input "
                    "plan, refuses the queue E_SHUTDOWN (transient), and sends BYE E_SHUTDOWN{retryMs}: the client falls back "
                    "at once and retries CRP after retryMs.",
        excerpt=True,
        frames=[fr("s2c", "BYE", {"code": "E_OFF", "msg": "crd.off present"}, conn="A"),
                fr("s2c", "INACK", {"sid": SID, "iseq": 80, "st": "refused", "code": "E_SHUTDOWN"}, conn="B"),
                fr("s2c", "BYE", {"code": "E_SHUTDOWN", "retryMs": 3000}, conn="B")],
        expect={"client": {"E_OFF": "fallback off", "E_SHUTDOWN": "fallback, retry after retryMs"}},
    ))

    # 45. HELLO with a maxFrame below the minimum
    h = hello(max_frame=4096)
    neg = negotiate(h)
    V.append(dict(
        id="45-hello-maxframe-too-small", spec="2.6, 3.2",
        title="maxFrame below 65536 is refused at the handshake",
        description="Every per-frame limit (pieces of 48 KiB, RPC header <= 32 KiB) is sized to fit the minimum maxFrame.",
        frames=[fr("c2s", "HELLO", h), fr("s2c", "BYE", {"code": neg["code"], "msg": neg["msg"]})],
        expect={"negotiation": neg},
    ))

    for v in V:
        if any(f["type"] == "HELLO" for f in v["frames"]):
            v.setdefault("modelInput", {})["negotiate"] = NEG_REF
    return V


NEGATIVE = [
    # (id, title, raw bytes builder, decode kwargs, expected code)
    ("90-neg-frame-too-large", "Length field above maxFrame",
     lambda: MAGIC + struct.pack(">I", 2 << 20) + b"\x20\x01\x00\x00", {"direction": "s2c", "excerpt": True}, "E_FRAME"),
    ("91-neg-reserved-flags", "Reserved flag bits set",
     lambda: MAGIC + encode("PING", {"t": 1})[:5] + b"\x02" + encode("PING", {"t": 1})[6:], {"direction": "c2s", "excerpt": True}, "E_PROTO"),
    ("92-neg-unknown-core-type", "Unknown type below 0x80",
     lambda: MAGIC + struct.pack(">IBBH", 4, 0x7E, 0, 0), {"direction": "c2s", "excerpt": True}, "E_PROTO"),
    ("93-ext-type-ignored", "Unknown type in the extension range 0x80..0xFF is skipped",
     lambda: MAGIC + struct.pack(">IBBH", 6, 0x90, 0, 2) + b"{}" + encode("PING", {"t": 7}), {"direction": "c2s", "excerpt": True}, None),
    ("94-neg-no-magic", "No magic within 64 KiB (crd missing: shell printed 'command not found')",
     lambda: b"sh: 1: crd: not found\n", {"direction": "s2c", "skip_junk": True}, "E_NO_DAEMON"),
    ("95-neg-missing-field", "EV without seq",
     lambda: MAGIC + encode("EV", {"st": "ev", "ep": EP}, b"x"), {"direction": "s2c", "excerpt": True}, "E_PROTO"),
    ("96-neg-wrong-direction", "A server-to-client type (TX) in the client's stream",
     lambda: MAGIC + encode("TX", {"wid": "w", "u": "u", "f": 0, "o": 1, "enc": "none"}, b"x"), {"direction": "c2s", "excerpt": True}, "E_PROTO"),
    ("97-neg-blob-on-blobless-type", "PING with the blob flag and bytes",
     lambda: MAGIC + struct.pack(">IBBH", 4 + 7 + 1, 0x04, 0x01, 7) + b'{"t":1}' + b"z", {"direction": "c2s", "excerpt": True}, "E_PROTO"),
    ("98-neg-field-type", "EV with seq as a string",
     lambda: MAGIC + encode("EV", {"st": "ev", "ep": EP, "seq": "5"}, b"x"), {"direction": "s2c", "excerpt": True}, "E_PROTO"),
    ("99-neg-first-not-hello", "Client stream whose first frame is not HELLO",
     lambda: MAGIC + encode("PING", {"t": 1}), {"direction": "c2s"}, "E_PROTO"),
]

BLOB_TEXT_MAX = 4096  # blobText is recorded only for small UTF-8 blobs (keeps the files readable)


def stream_key(f):
    return f.get("conn", "A") + "/" + f["dir"]


def materialize(v):
    raws, order = {}, []
    out_frames = []
    for f in v["frames"]:
        b = encode(f["type"], f["header"], f.get("blob"))
        d = {"conn": f.get("conn", "A"), "dir": f["dir"], "hex": b.hex(), "type": CODE[f["type"]], "typeName": f["type"],
             "header": f["header"]}
        if "blob" in f:
            d["blobHex"] = f["blob"].hex()
            if len(f["blob"]) <= BLOB_TEXT_MAX:
                try:
                    d["blobText"] = f["blob"].decode("utf-8")
                except UnicodeDecodeError:
                    pass
        if "note" in f:
            d["note"] = f["note"]
        out_frames.append(d)
        k = stream_key(f)
        if k not in raws:
            raws[k] = MAGIC
            order.append(k)
        raws[k] += b
    streams = []
    for k in order:
        conn, direction = k.split("/")
        junk = v.get("junk", {}).get(k, "").encode()
        s = {"conn": conn, "dir": direction, "excerpt": bool(v.get("excerpt", False)),
             "maxFrame": v.get("maxFrame", DEFAULT_MAX_FRAME), "hex": (junk + raws[k]).hex()}
        if junk:
            s["junkHex"] = junk.hex()
        streams.append(s)
    doc = {"id": v["id"], "title": v["title"], "spec": v["spec"], "description": v["description"],
           "framing": "every frame: u32be len | u8 type | u8 flags | u16be hlen | header JSON | blob; each (conn, dir) "
                      "stream starts with the 8-byte magic",
           "frames": out_frames, "streams": streams, "expect": v["expect"]}
    if "modelInput" in v:
        doc["modelInput"] = v["modelInput"]
    return doc


def verify(doc):
    """Re-decode every stream; it must reproduce that (conn, dir)'s frames (header, blob, type) exactly."""
    for s in doc["streams"]:
        raw = bytes.fromhex(s["hex"])
        skip = "junkHex" in s
        got = decode_stream(raw, s["dir"], max_frame=s["maxFrame"], excerpt=s["excerpt"], skip_junk=skip)
        if skip and got["junkSkipped"] != len(bytes.fromhex(s["junkHex"])):
            raise AssertionError(doc["id"] + ": junk length")
        mine = [f for f in doc["frames"] if f["conn"] == s["conn"] and f["dir"] == s["dir"]]
        if len(got["frames"]) != len(mine):
            raise AssertionError(f"{doc['id']} {s['conn']}/{s['dir']}: {len(got['frames'])} frames decoded, {len(mine)} recorded")
        for g, r in zip(got["frames"], mine):
            if g["typeName"] != r["typeName"] or g["header"] != r["header"] or g.get("blobHex") != r.get("blobHex"):
                raise AssertionError(f"{doc['id']}: frame mismatch {g} vs {r}")
            if encode(r["typeName"], r["header"], bytes.fromhex(r["blobHex"]) if "blobHex" in r else None).hex() != r["hex"]:
                raise AssertionError(f"{doc['id']}: re-encode differs")
            if len(bytes.fromhex(r["hex"])) - 4 > s["maxFrame"]:
                raise AssertionError(f"{doc['id']}: frame above maxFrame")


def negative_docs():
    docs = []
    for vid, title, build, kw, code in NEGATIVE:
        raw = build()
        try:
            got = decode_stream(raw, **kw)
            err = None
            decoded = [{"typeName": f.get("typeName"), "header": f.get("header"), "ignored": f.get("ignored", False)} for f in got["frames"]]
        except ProtoError as e:
            err, decoded = e.code, None
        if err != code:
            raise AssertionError(f"{vid}: expected {code}, got {err}")
        docs.append({"id": vid, "title": title, "spec": "3.1, 3.2", "streamHex": raw.hex(),
                     "description": "Receiver behaviour on a malformed or foreign stream.",
                     "expect": {"error": code, "decoded": decoded}, "decodeOptions": kw})
    return docs


def main():
    check_only = "--check" in sys.argv[1:]
    docs = [materialize(v) for v in build_vectors()] + negative_docs()
    for d in docs:
        if "frames" in d:
            verify(d)
    if check_only:
        bad = 0
        for d in docs:
            p = os.path.join(HERE, d["id"] + ".json")
            on_disk = json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None
            if on_disk != d:
                print("MISMATCH", d["id"]); bad += 1
        known = {d["id"] + ".json" for d in docs} | {"index.json"}
        for name in sorted(os.listdir(HERE)):
            if name.endswith(".json") and name not in known:
                print("STALE FILE", name); bad += 1
        print(f"checked {len(docs)} vectors, {bad} mismatches")
        return 1 if bad else 0
    for d in docs:
        with open(os.path.join(HERE, d["id"] + ".json"), "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=2, sort_keys=False)
            f.write("\n")
    index = [{"id": d["id"], "title": d["title"], "spec": d["spec"], "frames": len(d.get("frames", [])),
              "expectError": d["expect"].get("error") if "error" in d["expect"] else None} for d in docs]
    with open(os.path.join(HERE, "index.json"), "w", encoding="utf-8") as f:
        json.dump({"protocol": "CRP/1", "revision": 2, "magicHex": MAGIC.hex(), "generator": "gen.py", "count": len(docs),
                   "vectors": index}, f, ensure_ascii=False, indent=2)
        f.write("\n")
    # read back what was written and verify it again
    for d in docs:
        back = json.load(open(os.path.join(HERE, d["id"] + ".json"), encoding="utf-8"))
        if back != d:
            raise AssertionError("write/read mismatch " + d["id"])
        if "frames" in back:
            verify(back)
    print(f"wrote and verified {len(docs)} vectors ({sum(1 for d in docs if 'frames' in d)} positive, "
          f"{sum(1 for d in docs if 'frames' not in d)} negative) in {HERE}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
