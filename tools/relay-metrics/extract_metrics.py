#!/usr/bin/env python3
"""Relay metrics extractor (plan S0.2, metrics M1-M17 + server canaries).

Run as:  python3 -I tools/relay-metrics/extract_metrics.py [options]

Reads the client logs shipped by LogShipper (~/.claude-remote/logs/*.log), or the
hourly snapshots written by snapshot.sh (--root), plus the server canaries
(forget.log, drift.log).  Read-only: it never writes anywhere except the
--json / --md / --snapshot targets it is given.  Python 3 stdlib only.

Log format (LogShipper): batches introduced by
    --- flush YYYY-MM-DD HH:MM:SS +ZZZZ ---
followed by lines "HH:MM:SS.mmm L/Tag: message"; lines without that prefix are
continuations (stack traces, multi-line commands) and are skipped.  Lines before
the first header of a file have no date and are dropped.

Known deviations from the plan text (all pinned by tests):
  M9   counts only E/CloudflareProxy "didn't receive pong" lines (the stack lines repeat the text).
  M10  counts "Auto-reconnect attempt N/N for" only; the "attempt N failed" lines are a different event.
  M17  counts tag KeepAlive + "WakeLock acquired" (the tag is stripped from the message).
  M11  n/a on desktop (AlertNotifier exists only in androidApp); M15 n/a while no "Hook event" line exists.
  M12  every client forget needs a server user close of the same name in (t - 48 h, t + 60 s].
Limits (not fixed): placement of lines relative to the flush header is ambiguous when two headers of
one device are more than 24 h apart (flagged "ambiguous"); the day is the device's local day, a
header's fixed UTC offset is used for the whole batch (DST change / travel shifts by the difference);
several loops are quadratic (a real 40 MB log takes ~5 s).
"""
import argparse
import datetime as dt
import glob
import hashlib
import json
import math
import os
import re
import sys
import time
from collections import defaultdict, namedtuple

DEFAULT_EXCLUDE_DAYS = ("2026-10-31",)  # duplicate-CT outage
LOW_CONFIDENCE_SAMPLES = 60  # data/60s samples per (device, day)
STORM_WINDOW_S = 90
SILENT_GAP_S = 60
BURST_LINES = 5
BURST_SPAN_S = 2.0
TS_DEATH_MAX_MS = 30000
DEATH_WAVE_S = 2.0  # M7, lines WITHOUT a transport id (logs before V13): one server's lines this close are one transport death
TRANSPORT_DEATH_SPAN_S = 30.0  # M7: lines of ONE transport id (and server) this close are its one death (the id may be reused later)
IDLE_TERM_KB = 20
USER_CLOSE_PAIR_S = 5
FORGET_AFTER_S = 60  # a client forget may precede the server user close by this much (clock skew)
FORGET_BEFORE_S = 48 * 3600  # ... and may lag it by up to this (a sleeping client forgets late)
FORGET_DUP_S = 2  # "forgetting locally" + "aborted - closed on another device" within this = one forget
HEADER_SKEW_S = 120  # a line may carry a time this far after its flush header
AMBIGUOUS_GAP_S = 24 * 3600
SNAPSHOT_MIN_AGE_S = 30
RAW_RETENTION_DAYS = 60
LOW_CONF_N_CONN03 = 20
NOTIFY_MATCH_S = 300
RESTORE_MATCH_S = 120

HEADER_RE = re.compile(r"^--- flush (\d{4}-\d\d-\d\d) (\d\d):(\d\d):(\d\d) ([+-]\d{4}) ---\s*$")
GLUED_HEADER_RE = re.compile(r"--- flush \d{4}-\d\d-\d\d \d\d:\d\d:\d\d [+-]\d{4} ---\s*$")
DROPPED_RE = re.compile(r"^\[shipper dropped (\d+) lines\]$")
LINE_RE = re.compile(r"^(\d\d):(\d\d):(\d\d)\.(\d{3}) ([VDIWEFA])/([^:\s]+): (.*)$")

Event = namedtuple("Event", "ts day tag msg")  # ts: aware datetime


def _tz(off):
    """Raises ValueError for an offset timezone() rejects (e.g. +2400)."""
    sign = -1 if off[0] == "-" else 1
    return dt.timezone(sign * dt.timedelta(hours=int(off[1:3]), minutes=int(off[3:5])))


def _place_back(ref, h, mi, s, ms):
    """The latest wall-clock time h:mi:s.ms that is <= ref + HEADER_SKEW_S.

    LogShipper stamps the flush header when it sends, so a line is never later than its header
    (plus a little clock skew); a delayed batch is older, never newer.  The last line is placed
    against the header, every earlier line against the line after it.  (A symmetric "nearest to
    ref" rule put hours-old lines of an offline batch into the future.)  Raises ValueError for an
    impossible time such as 24:00 or 99:99."""
    limit = ref + dt.timedelta(seconds=HEADER_SKEW_S)
    best = None
    for delta in (-1, 0, 1):
        d = ref.date() + dt.timedelta(days=delta)
        cand = dt.datetime(d.year, d.month, d.day, h, mi, s, ms * 1000, tzinfo=ref.tzinfo)
        if cand <= limit and (best is None or cand > best):
            best = cand
    if best is None:
        raise ValueError("no placement")
    return best


def parse_header(header):
    """-> aware datetime of the flush header, or None when it is not a real date/time/offset."""
    m = HEADER_RE.match(header)
    if not m:
        return None
    try:
        hdate = dt.date.fromisoformat(m.group(1))
        return dt.datetime(hdate.year, hdate.month, hdate.day, int(m.group(2)), int(m.group(3)),
                           int(m.group(4)), tzinfo=_tz(m.group(5)))
    except ValueError:
        return None


def split_batches(text):
    """-> (undated_head_lines, [(header_line, [body lines])])"""
    head, batches, cur = [], [], None
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()  # the newline that ends the file is not a line (keeps batch hashes stable)
    if lines and lines[0].startswith("\ufeff"):
        lines[0] = lines[0][1:]  # BOM
    for line in lines:
        line = line.rstrip("\r")
        g = GLUED_HEADER_RE.search(line)
        if g and g.start() > 0 and not HEADER_RE.match(line):
            # the shipper's requeue left a line without its newline: "<cut line>--- flush ... ---"
            (head if cur is None else cur[1]).append(line[:g.start()])
            line = line[g.start():]
        if HEADER_RE.match(line):
            cur = (line, [])
            batches.append(cur)
        elif cur is None:
            head.append(line)
        else:
            cur[1].append(line)
    return head, batches


def batch_hash(body):
    return hashlib.md5("\n".join(body).encode("utf-8", "replace")).hexdigest()


def parse_log_text(text):
    """Parse one device log -> (events, stats).  Dedups identical (header, body) batches.

    A header or line that matches the regex but is not a real time (2026-13-45, 99:99:99, +2400) is
    skipped and counted in stats["bad_lines"].  stats["dropped_lines"] = {day: n} sums the
    "[shipper dropped N lines]" markers; stats["ambiguous_days"] lists days holding a batch whose
    header is more than 24 h after the previous header (line dates cannot be told apart there)."""
    head, batches = split_batches(text)
    stats = {
        "undated_lines": sum(1 for l in head if LINE_RE.match(l)),
        "undated_cf_open_by": sum(1 for l in head if "cf-open-by:" in l and LINE_RE.match(l)),
        "batches": 0,
        "duplicate_batches": 0,
        "bad_lines": 0,
        "dropped_lines": {},
        "ambiguous_days": [],
    }
    events, seen, ambiguous, prev_ref = [], set(), set(), None
    for header, body in batches:
        key = (header, batch_hash(body))
        if key in seen:
            stats["duplicate_batches"] += 1
            continue
        seen.add(key)
        stats["batches"] += 1
        ref = parse_header(header)
        if ref is None:
            stats["bad_lines"] += 1 + sum(1 for l in body if LINE_RE.match(l))
            continue
        gap = prev_ref is not None and (ref - prev_ref).total_seconds() > AMBIGUOUS_GAP_S
        prev_ref = ref
        placed = [None] * len(body)  # (kind, value): ("line", (ts, tag, msg)) / ("drop", n)
        cur = ref
        for k in range(len(body) - 1, -1, -1):  # backwards from the header
            line = body[k]
            dm = DROPPED_RE.match(line)
            if dm:
                placed[k] = ("drop", int(dm.group(1)))
                continue
            lm = LINE_RE.match(line)
            if not lm:
                continue  # continuation line
            try:
                ts = _place_back(cur, int(lm.group(1)), int(lm.group(2)), int(lm.group(3)), int(lm.group(4)))
            except ValueError:
                stats["bad_lines"] += 1
                continue
            cur = ts
            placed[k] = ("line", (ts, lm.group(6), lm.group(7)))
        pending, first_day, bdays = 0, None, set()
        for item in placed:
            if item is None:
                continue
            if item[0] == "drop":
                pending += item[1]
                continue
            ts, tag, msg = item[1]
            day = ts.date().isoformat()
            if first_day is None:
                first_day = day
            if pending:
                stats["dropped_lines"][day] = stats["dropped_lines"].get(day, 0) + pending
                pending = 0
            bdays.add(day)
            events.append(Event(ts, day, tag, msg))
        if pending:
            day = ref.date().isoformat()
            stats["dropped_lines"][day] = stats["dropped_lines"].get(day, 0) + pending
        if gap:
            ambiguous.update(bdays or {ref.date().isoformat()})
    stats["ambiguous_days"] = sorted(ambiguous)
    return events, stats


# ---------------------------------------------------------------- helpers

def pct(values, p):
    """Nearest-rank percentile (p=0.5 is the true median); None for empty input."""
    if not values:
        return None
    v = sorted(values)
    if p == 0.5:  # true median (mean of the two middle values for even n)
        mid = len(v) // 2
        return v[mid] if len(v) % 2 else round((v[mid - 1] + v[mid]) / 2, 1)
    return v[max(0, math.ceil(p * len(v)) - 1)]


def mean(values):
    return round(sum(values) / len(values), 1) if values else None


def dist(values):
    return {"n": len(values), "p50": pct(values, 0.5), "p90": pct(values, 0.9)}


def kv_ints(msg):
    return {k: int(v) for k, v in re.findall(r"(\w+)=(\d+)", msg)}


def conn_bucket(conn):
    if conn <= 3:
        return "0-3"
    if conn < 12:
        return "4-11"
    if conn < 16:
        return "12-15"
    return "16+"


BUCKETS = ("0-3", "4-11", "12-15", "16+")

DATA_RE = re.compile(r"^data/60s: (bg|fg) ")
SWEEP_RE = re.compile(r"^Reconnect sweep \(([^)]*)\): (\d+) dead tab")
SUCCESS_RE = re.compile(r"^(?:Auto-reconnect succeeded for|Reconnected:) (\S+)")
ATTEMPT_RE = re.compile(r"^Auto-reconnect attempt \d+/\d+ for ")
# M7 (V13): a tab's line names its server and its transport (the id of the shared jsch session; older lines lack one or
# both) and, since round 4, the transport's own age ("transport_age=", review B-3 L4; the "died Xms" is the TAB's age);
# an exec line is one transport, and one lost with the network ("... via TS (network lost)") is no early death.
# The Kotlin side checks its line against this regex (TransportResolverDeathLineTest): keep it in the Java/Python subset.
TS_DEATH_RE = re.compile(r"^(?:Tailscale transport died (\d+)ms after connect for \S+(?: on (.+?))?(?: transport=([0-9a-f]+))?(?: transport_age=(\d+)ms)?|Exec transport (.*) down after (\d+)ms via TS)$")
TS_CONNECT_RE = re.compile(r"^(?:Transport for .* -> .* \(cf=false\)|Exec transport .* up via TS)")
# LAN vs tunnel: today's lines do not say which (the 126 "TS deaths" of the baseline went over LAN).  The
# parser is ready for a future "route=lan|tunnel" field on the connect line; until then tunnel metrics are n/a.
ROUTE_RE = re.compile(r"\broute=(lan|tunnel)\b")
# M7 denominator (V13): the transports the pool opened (shell or exec) to a Tailscale endpoint, i.e. to a host some
# resolver decision chose as TAILSCALE ("Transport for <server>: TAILSCALE -> <host> (cf=false)").  Not the transports of
# servers without Tailscale, not Cloudflare ones, not every resolver decision.
TS_DECISION_RE = re.compile(r"^Transport for .*: TAILSCALE -> (\S+) \(cf=false\)")
NEW_TRANSPORT_RE = re.compile(r"^New transport to (\S+):\d+ role=\w+ ")
# Any "New transport" line: one without role= comes from a build before the denominator existed (its log generation).
ANY_NEW_TRANSPORT_RE = re.compile(r"^New transport to \S+:\d+ ")
SPILL_RE = re.compile(r"channel is not opened\.|administratively prohibited")
FIRED_RE = re.compile(r"^Stop hook fired for (\S+?):")
ALERT_RE = re.compile(r"^Alert posted for (\S+?):")
FORGET_LOCAL_RE = re.compile(r"^Tmux '([^']+)' .*forgetting locally")
PRUNE_RE = re.compile(r"^Pruning stale tab \S+ \(tmux (\S+?),")
ABORTED_RE = re.compile(r"aborted — closed on another device")
RESTORED_RE = re.compile(r"^Restored (\d+) persisted")
ATTACH_RE = re.compile(r"^Attach (\S+) kind=(cold|warm) ms=(\d+)(?: phase=(\S+))?")
HOOK_RE = re.compile(r"^Hook event (\S+) for (\S+) \((fg|bg)\) age=(\d+)")
PARKED_RE = re.compile(r"^ParkedInput (\S+) bytes=(\d+) (ok|fail)")
PHASE_NAMES = ("shell", "prompt", "decide", "attach", "firstByte")


def cf_caller(msg):
    """Classify a cf-open-by call chain by the first matching known caller."""
    chain = re.sub(r"\$[A-Za-z0-9$]+", "", msg.split("cf-open-by:", 1)[1])
    if "TranscriptStream.doPoll" in chain:
        return "TranscriptStream.doPoll"
    if "SessionStatusPoller" in chain:
        return "SessionStatusPoller"
    if "connectForCleanup" in chain:
        return "connectForCleanup"
    if "ServerTransportPool" in chain:
        return "ServerTransportPool.lease"
    if "withSession" in chain or "withServerSession" in chain:
        return "other withSession"
    return "other"


def parse_phase(raw):
    if not raw:
        return None
    parts = raw.split(",")
    out = {}
    for i, p in enumerate(parts):
        if "=" in p:
            k, v = p.split("=", 1)
        elif i < len(PHASE_NAMES):
            k, v = PHASE_NAMES[i], p
        else:
            continue
        try:
            out[k] = int(v)
        except ValueError:
            pass
    return out or None


# ---------------------------------------------------------------- storms (M1/M1s/M2)

def find_triggers(events, platform):
    """-> sorted list of (ts, kind, n_dead_or_None)."""
    trig = []
    if platform != "desktop":
        for e in events:
            if e.msg.startswith("Default network lost"):
                trig.append((e.ts, "network-lost", None))
            else:
                m = SWEEP_RE.match(e.msg)
                if m:
                    trig.append((e.ts, "sweep", int(m.group(2))))
    if platform != "android":
        lost = [e.ts for e in events if e.msg.startswith("Connection lost")]
        i = 0
        while i < len(lost):
            j = i
            while j + 1 < len(lost) and (lost[j + 1] - lost[j]).total_seconds() <= BURST_SPAN_S:
                j += 1
            cluster = lost[i:j + 1]
            if any((cluster[k + BURST_LINES - 1] - cluster[k]).total_seconds() <= BURST_SPAN_S
                   for k in range(len(cluster) - BURST_LINES + 1)):
                trig.append((cluster[0], "burst", None))
            i = j + 1
    trig.sort(key=lambda t: t[0])
    return trig


def build_storms(events, platform):
    """Merge triggers within STORM_WINDOW_S of the storm start; compute duration and handshakes."""
    storms = []
    for ts, kind, n in find_triggers(events, platform):
        if storms and (ts - storms[-1]["start"]).total_seconds() <= STORM_WINDOW_S:
            if storms[-1]["n"] is None and n is not None:
                storms[-1]["n"] = n
            continue
        storms.append({"start": ts, "kind": kind, "n": n})
    successes = [(e.ts, SUCCESS_RE.match(e.msg).group(1)) for e in events if SUCCESS_RE.match(e.msg)]
    handshakes = [e for e in events if e.msg.startswith("New transport to")]
    for i, s in enumerate(storms):
        end = s["start"] + dt.timedelta(seconds=STORM_WINDOW_S)
        if i + 1 < len(storms):
            end = min(end, storms[i + 1]["start"])
        seen, times = set(), []
        for ts, sid in successes:
            if s["start"] < ts <= end and sid not in seen:
                seen.add(sid)
                times.append(ts)
        if s["n"] and len(times) >= s["n"]:
            s["duration_s"] = round((times[s["n"] - 1] - s["start"]).total_seconds(), 1)
        elif times:
            s["duration_s"] = round((times[-1] - s["start"]).total_seconds(), 1)
        else:
            s["duration_s"] = None
        hs_end = s["start"] + dt.timedelta(seconds=STORM_WINDOW_S)
        s["handshakes"] = defaultdict(int)
        for e in handshakes:
            if s["start"] < e.ts <= hs_end:
                role = re.search(r"role=(\w+)", e.msg)
                s["handshakes"][role.group(1) if role else "any"] += 1
        s["handshakes"] = dict(s["handshakes"])
        s["day"] = s["start"].date().isoformat()
    return storms


def silent_deaths(events):
    """M1s: first 'Connection lost' after >=SILENT_GAP_S without one -> seconds to next success."""
    lost = [e for e in events if e.msg.startswith("Connection lost")]
    succ = [e for e in events if SUCCESS_RE.match(e.msg)]
    out, prev = [], None
    for e in lost:
        if prev is not None and (e.ts - prev).total_seconds() >= SILENT_GAP_S:
            nxt = next((s.ts for s in succ if s.ts > e.ts), None)
            dur = round((nxt - e.ts).total_seconds(), 1) if nxt else None
            out.append({"day": e.day, "start": e.ts.isoformat(), "duration_s": dur})
        prev = e.ts
    return out


# ---------------------------------------------------------------- server logs

FORGET_RE = re.compile(r"^\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ)\] forget: (\S+) \(durable=([01])")
DRIFT_RE = re.compile(r"^\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ)\] drift: (REFUSING|self-heal)")


def _utc(s):
    return dt.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)


def analyze_forget(text):
    """M12 server side: pair durable=1 + durable=0 of one name within 5 s -> user close;
    a lone durable=0 is a prune; a lone durable=1 is a tombstone without removal."""
    rows = []
    for line in text.split("\n"):
        m = FORGET_RE.match(line)
        if m:
            rows.append((_utc(m.group(1)), m.group(2), m.group(3) == "1"))
    rows.sort(key=lambda r: r[0])
    used = [False] * len(rows)
    user_closes, prunes, lone_durable = [], [], []
    for i, (ts, name, d1) in enumerate(rows):
        if used[i] or not d1:
            continue
        for j, (ts2, name2, d12) in enumerate(rows):
            if not used[j] and j != i and not d12 and name2 == name \
                    and abs((ts2 - ts).total_seconds()) <= USER_CLOSE_PAIR_S:
                used[i] = used[j] = True
                user_closes.append((min(ts, ts2), name))
                break
    for i, (ts, name, d1) in enumerate(rows):
        if not used[i]:
            (lone_durable if d1 else prunes).append((ts, name))
    return {"user_closes": sorted(user_closes), "prunes": prunes, "lone_durable1": lone_durable}


def analyze_drift(text):
    counts = defaultdict(lambda: {"REFUSING": 0, "self-heal": 0})
    for line in text.split("\n"):
        m = DRIFT_RE.match(line)
        if m:
            counts[m.group(1)[:10]][m.group(2)] += 1
    return dict(counts)


# ---------------------------------------------------------------- per-device metrics

def device_metrics(events, platform, days):
    """-> {day: metrics dict} for the given set of included days."""
    by_day = defaultdict(list)
    for e in events:
        if e.day in days:
            by_day[e.day].append(e)
    storms = [s for s in build_storms(events, platform) if s["day"] in days]
    silent = [s for s in silent_deaths(events) if s["day"] in days]
    alerts = [(e.ts, ALERT_RE.match(e.msg).group(1)) for e in events if ALERT_RE.match(e.msg)]
    # M7: the Tailscale endpoints are configuration, so they are collected over the whole log, not per day
    ts_hosts = {m.group(1) for m in (TS_DECISION_RE.match(e.msg) for e in events) if m}
    # M7 (review B-3 R3-3): the numerator counts only deaths of the log generation the denominator counts, i.e. after a
    # "New transport ... role=" line; a build before that logged its transports without role= (so they are in no
    # denominator) while its deaths still matched, and the day of the update came out at 218/18 = 12.1
    m7_counted, new_gen = set(), False
    for e in events:
        if ANY_NEW_TRANSPORT_RE.match(e.msg):
            new_gen = NEW_TRANSPORT_RE.match(e.msg) is not None
        elif new_gen and TS_DEATH_RE.match(e.msg):
            m7_counted.add(id(e))
    res = {}
    for day in sorted(by_day):
        ev = by_day[day]
        d = {}
        # data/60s
        bg, fg = [], []
        for e in ev:
            m = DATA_RE.match(e.msg)
            if m:
                (bg if m.group(1) == "bg" else fg).append(kv_ints(e.msg))
        d["data60_samples"] = len(bg) + len(fg)
        d["low_confidence"] = d["data60_samples"] < LOW_CONFIDENCE_SAMPLES
        # M4
        bgw = [s for s in bg if "appRx" in s and "appTx" in s]
        tot = [s["appRx"] + s["appTx"] for s in bgw]
        d["M4"] = {
            "n": len(bgw),
            "rxtx_kb_min": {"mean": mean(tot), "p90": pct(tot, 0.9)},
            "mean_rx": mean([s["appRx"] for s in bgw]),
            "mean_tx": mean([s["appTx"] for s in bgw]),
            "mean_term": mean([s["term"] for s in bgw if "term" in s]),
            "mean_conn": mean([s["conn"] for s in bgw if "conn" in s]),
        }
        for k in ("attached", "warm", "parked"):
            vals = [s[k] for s in bgw if k in s]
            d["M4"]["mean_" + k] = mean(vals) if vals else "n/a"
        # M5 (android only; desktop sysRx is machine-wide)
        if platform == "desktop":
            d["M5"] = "n/a (desktop sysRx is machine-wide)"
        else:
            fgw = [s for s in fg if "appRx" in s and "appTx" in s]
            ft = [s["appRx"] + s["appTx"] for s in fgw]
            d["M5"] = {"n_fg_samples": len(fg), "n_with_app": len(fgw),
                       "rxtx_kb_min": {"mean": mean(ft), "p90": pct(ft, 0.9)},
                       "mean_term": mean([s["term"] for s in fg if "term" in s])}
        # M1 / M1s / M2
        ds = [s["duration_s"] for s in storms if s["day"] == day and s["duration_s"] is not None]
        sd = [s for s in storms if s["day"] == day]
        d["M1"] = {"storms": len(sd), "unrecovered": sum(1 for s in sd if s["duration_s"] is None),
                   "duration_s": dist(ds)}
        d["M1s"] = {"deaths": len([s for s in silent if s["day"] == day]),
                    "recovery_s": dist([s["duration_s"] for s in silent
                                        if s["day"] == day and s["duration_s"] is not None])}
        hs = [sum(s["handshakes"].values()) for s in sd]
        roles = defaultdict(int)
        for s in sd:
            for r, n in s["handshakes"].items():
                roles[r] += n
        d["M2"] = {"per_trigger": dist(hs), "max": max(hs) if hs else None, "by_role_total": dict(roles)}
        # M3
        cf = defaultdict(int)
        for e in ev:
            if "cf-open-by:" in e.msg:
                cf[cf_caller(e.msg)] += 1
        d["M3"] = dict(cf)
        d["M3_total"] = sum(cf.values())
        # M6
        sweeps = [int(m.group(2)) for m in (SWEEP_RE.match(e.msg) for e in ev) if m]
        d["M6"] = {"sweeps": len(sweeps), "dead_tabs": dist(sweeps), "max": max(sweeps) if sweeps else None}
        # M7 (dávka B, V13 of round 3): numerator and denominator are both TRANSPORTS over Tailscale.  A transport's
        # death is logged once PER TAB, each line naming the server and the transport; the lines of one (server, transport)
        # within TRANSPORT_DEATH_SPAN_S are its one death.  Older lines without an id fall back to round 2: one server's
        # lines within DEATH_WAVE_S are one wave.  An exec line is one transport.  The denominator is the "New transport"
        # lines to a TS host (see TS_DECISION_RE).  The old ratio stays as death_rate_per_tab_line; it is not comparable
        # with death_rate (w1-20261104's 1.7 was that one).
        # (time, tab or exec ms, server, transport id, exec?, the transport's age where the line says it, counted by M7?)
        death_ev = [(e.ts, int(m.group(1) or m.group(6)), m.group(2) or m.group(5) or "", m.group(3), m.group(5) is not None,
                     int(m.group(4)) if m.group(4) else None, id(e) in m7_counted)
                    for e, m in ((e, TS_DEATH_RE.match(e.msg)) for e in ev) if m]
        deaths = [t[1] for t in death_ev]
        connects = sum(1 for e in ev if TS_CONNECT_RE.match(e.msg))
        early = sum(1 for x in deaths if x <= TS_DEATH_MAX_MS)
        ts_deaths, last = 0, {}
        young = (t for t in death_ev if t[6] and (t[5] if t[5] is not None else t[1]) <= TS_DEATH_MAX_MS)
        for ts, ms, server, tid, is_exec, _, _ in sorted(young, key=lambda t: t[0]):
            if is_exec:
                ts_deaths += 1
                continue
            key, span = ((server, tid), TRANSPORT_DEATH_SPAN_S) if tid else ((server, None), DEATH_WAVE_S)
            if key not in last or (ts - last[key]).total_seconds() > span:
                ts_deaths += 1
            last[key] = ts
        ts_transports = sum(1 for m in (NEW_TRANSPORT_RE.match(e.msg) for e in ev) if m and m.group(1) in ts_hosts)
        routes = [ROUTE_RE.search(e.msg) for e in ev if TS_CONNECT_RE.match(e.msg)]
        tunnel = sum(1 for r in routes if r and r.group(1) == "tunnel")
        lan = sum(1 for r in routes if r and r.group(1) == "lan")
        d["M7"] = {"deaths": len(deaths), "deaths_le_30s": early, "ts_transport_deaths": ts_deaths, "ts_transports": ts_transports,
                   "death_rate": round(ts_deaths / ts_transports, 3) if ts_transports else "n/a",
                   "ts_connects": connects,
                   "death_rate_per_tab_line": round(early / connects, 3) if connects else "n/a",
                   "ts_connects_tunnel": tunnel, "ts_connects_lan": lan,
                   "death_rate_tunnel": round(early / tunnel, 3) if tunnel else "n/a"}
        # M8
        d["M8"] = {"stop_hook_setup": sum(1 for e in ev if e.msg.startswith("Stop hook setup:")),
                   "streamd_setup": sum(1 for e in ev if e.msg.startswith("streamd setup:")),
                   "server_bootstrap": sum(1 for e in ev if e.msg.startswith("Server bootstrap "))}
        d["M8"]["total"] = sum(d["M8"].values())
        # M9, M10, M17
        d["M9"] = sum(1 for e in ev if "didn't receive pong within" in e.msg and e.tag == "CloudflareProxy")
        d["M10"] = {"connection_lost": sum(1 for e in ev if e.msg.startswith("Connection lost")),
                    "auto_reconnect_attempts": sum(1 for e in ev if ATTEMPT_RE.match(e.msg))}
        d["M17"] = sum(1 for e in ev if e.tag == "KeepAlive" and e.msg.startswith("WakeLock acquired"))
        d["spill"] = {"channel_not_opened": sum(1 for e in ev if "channel is not opened." in e.msg),
                      "administratively_prohibited": sum(1 for e in ev if "administratively prohibited" in e.msg)}
        # M11
        lat, unmatched = [], 0
        for e in ev:
            m = FIRED_RE.match(e.msg) if platform != "desktop" else None
            if m:
                nxt = next((t for t, sid in alerts if sid == m.group(1) and t >= e.ts), None)
                if nxt and (nxt - e.ts).total_seconds() <= NOTIFY_MATCH_S:
                    lat.append(round((nxt - e.ts).total_seconds(), 2))
                else:
                    unmatched += 1
        d["M11"] = dict(dist(lat), unmatched=unmatched) if platform != "desktop" else "n/a (no AlertNotifier on desktop)"
        # M13
        rest = []
        for i, e in enumerate(ev):
            m = RESTORED_RE.match(e.msg)
            if m:
                nxt = next((x.ts for x in events if x.ts > e.ts and (
                    x.msg.startswith("Reconnected: ") or
                    (x.msg.startswith("Attach ") and "kind=cold" in x.msg))), None)
                if nxt and (nxt - e.ts).total_seconds() <= RESTORE_MATCH_S:
                    rest.append(round((nxt - e.ts).total_seconds(), 2))
        d["M13"] = dist(rest)
        # M14 (A0 format; baseline n/a)
        att = {"cold": [], "warm": []}
        phases = defaultdict(list)
        for e in ev:
            m = ATTACH_RE.match(e.msg)
            if m:
                att[m.group(2)].append(int(m.group(3)))
                for k, v in (parse_phase(m.group(4)) or {}).items():
                    phases[k].append(v)
        d["M14"] = {k: (dist(v) if v else "n/a") for k, v in att.items()}
        d["M14"]["phase_p50_ms"] = {k: pct(v, 0.5) for k, v in phases.items()}
        # M15 (A6 format)
        hooks = defaultdict(int)
        bg_prompt = 0
        for e in ev:
            m = HOOK_RE.match(e.msg)
            if m:
                hooks["%s/%s" % (m.group(1), m.group(3))] += 1
                if m.group(3) == "bg" and m.group(1) in ("perm", "ask"):
                    bg_prompt += 1
        d["M15"] = {"events": dict(hooks), "bg_perm_ask": bg_prompt if hooks else "n/a"}
        # M16 (A2 format)
        pk = [PARKED_RE.match(e.msg) for e in ev]
        pk = [m for m in pk if m]
        fails = sum(1 for m in pk if m.group(3) == "fail")
        d["M16"] = {"n": len(pk), "fail": fails, "fail_rate": round(fails / len(pk), 3) if pk else "n/a"}
        res[day] = d
    return res


def overhead_table(events, days):
    """Idle (term<20KB) bg samples bucketed by conn: p50/mean of appRx+appTx KB/min."""
    buckets = defaultdict(list)
    for e in events:
        if e.day not in days:
            continue
        m = DATA_RE.match(e.msg)
        if m and m.group(1) == "bg":
            s = kv_ints(e.msg)
            if "appRx" in s and "appTx" in s and "conn" in s and s.get("term", 0) < IDLE_TERM_KB:
                buckets[conn_bucket(s["conn"])].append(s["appRx"] + s["appTx"])
    out = {}
    for b in BUCKETS:
        if not buckets[b]:
            continue
        n = len(buckets[b])
        if b == "0-3" and n < LOW_CONF_N_CONN03:  # bimodal and tiny: a number would mislead
            out[b] = {"n": n, "p50": "n/a", "mean": "n/a"}
        else:
            out[b] = {"n": n, "p50": pct(buckets[b], 0.5), "mean": mean(buckets[b])}
    return out


def client_forgets(events, days):
    """Client-side forgets: (ts, kind, name|None).  The "aborted - closed on another device" line that
    fires at the same moment as a named "forgetting locally" is the same forget and is not counted twice."""
    out = []
    for e in events:
        if e.day not in days:
            continue
        m = FORGET_LOCAL_RE.match(e.msg)
        if m:
            out.append((e.ts, "forgetting-locally", m.group(1)))
            continue
        m = PRUNE_RE.match(e.msg)
        if m:
            out.append((e.ts, "pruning-stale-tab", m.group(1)))
            continue
        if ABORTED_RE.search(e.msg):
            out.append((e.ts, "aborted-closed-elsewhere", None))
    named = [f[0] for f in out if f[1] == "forgetting-locally"]
    return [f for f in out if not (f[1] == "aborted-closed-elsewhere" and
                                   any(abs((f[0] - t).total_seconds()) <= FORGET_DUP_S for t in named))]


def match_forgets(forgets, user_closes):
    """R-5: a client forget of name N at t is explained by a server user close of N at v in
    (t - 48 h, t + 60 s]: the sleeping client forgets minutes to hours after the close.  A forget
    without a name (the 'aborted' line) is explained by a close of any name in that window."""
    explained, unexplained = 0, []
    for ts, kind, name in forgets:
        ok = any(-FORGET_AFTER_S <= (ts - uts).total_seconds() < FORGET_BEFORE_S
                 and (name is None or name == uname) for uts, uname in user_closes)
        if ok:
            explained += 1
        else:
            same = [abs((ts - uts).total_seconds()) for uts, uname in user_closes if uname == name]
            unexplained.append({"ts_utc": ts.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                                "kind": kind, "name": name,
                                "nearest_same_name_close_s": round(min(same)) if same else None})
    return {"client_forgets": len(forgets), "explained": explained, "unexplained": unexplained}


# ---------------------------------------------------------------- snapshot

# Lines that carry conversation text (answers, prompts, session titles).  The metrics never read these
# bodies, so raw/ keeps only the part that is counted.  Redaction happens BEFORE the batch hash.
REDACTIONS = (
    (re.compile(r"^(\d\d:\d\d:\d\d\.\d{3} [VDIWEFA]/AlertNotifier: Alert posted for \S+?:).*$"), r"\1 <redacted>"),
    (re.compile(r"^(\d\d:\d\d:\d\d\.\d{3} [VDIWEFA]/Notify: Claude needs input:) '.*' (fg=.*)$"), r"\1 '<redacted>' \2"),
    (re.compile(r"^(\d\d:\d\d:\d\d\.\d{3} [VDIWEFA]/Notify: Sending alert for) '.*'$"), r"\1 '<redacted>'"),
    (re.compile(r"^(\d\d:\d\d:\d\d\.\d{3} [VDIWEFA]/\S+: .*?\b(?:lastMessage|summary|prompt)=).*$"), r"\1<redacted>"),
)


def redact_body(body):
    """Redact text-carrying lines; a redacted line's continuation lines (multi-line text) are dropped."""
    out, skipping = [], False
    for line in body:
        if LINE_RE.match(line) or DROPPED_RE.match(line):
            skipping = False
            for rx, rep in REDACTIONS:
                new = rx.sub(rep, line)
                if new != line:
                    line, skipping = new, True
                    break
            out.append(line)
        elif not skipping:
            out.append(line)
    return out


def prune_raw(root, today=None, days=RAW_RETENTION_DAYS):
    """Delete raw/<device>/<YYYY-MM-DD>.log older than `days` (by the date in the file name)."""
    today = today or dt.date.today()
    removed = 0
    for path in glob.glob(os.path.join(root, "raw", "*", "*.log")):
        try:
            day = dt.date.fromisoformat(os.path.basename(path)[:-4])
        except ValueError:
            continue
        if (today - day).days > days:
            os.remove(path)
            removed += 1
    return removed


def snapshot_logs(logs_dir, root, min_age=SNAPSHOT_MIN_AGE_S, now=None):
    """Split every *.log in logs_dir on flush headers and append unseen batches to
    <root>/raw/<device>/<YYYY-MM-DD>.log (day = header date), redacted.  Dedup key: header + hash of
    the redacted batch, kept in <root>/raw/<device>/.seen.  Skipped: undated head; a batch whose header
    is not a real date; the last batch when the file does not end in a newline or was modified less than
    min_age seconds ago (the shipper may be mid-write; the next run takes it); anything that is not a
    readable regular file (reported on stderr).  -> number of batches added."""
    added = 0
    now = time.time() if now is None else now
    for path in sorted(glob.glob(os.path.join(logs_dir, "*.log"))):
        device = os.path.basename(path)[:-4]
        if not os.path.isfile(path):
            print("snapshot: skip %s: not a regular file" % path, file=sys.stderr)
            continue
        try:
            with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
                text = f.read()
            young = now - os.path.getmtime(path) < min_age
        except OSError as e:
            print("snapshot: skip %s: %s" % (path, e), file=sys.stderr)
            continue
        complete = text.endswith("\n")
        _, batches = split_batches(text)
        if batches and (not complete or young):
            batches = batches[:-1]
        ddir = os.path.join(root, "raw", device)
        os.makedirs(ddir, exist_ok=True)
        seen_path = os.path.join(ddir, ".seen")
        seen = set()
        if os.path.exists(seen_path):
            with open(seen_path) as f:
                seen = set(l.rstrip("\n") for l in f)
        new_keys = []
        for header, body in batches:
            if parse_header(header) is None:
                print("snapshot: skip batch with invalid header %r in %s" % (header, path), file=sys.stderr)
                continue
            body = redact_body(body)
            key = "%s %s" % (header, batch_hash(body))
            if key in seen:
                continue
            seen.add(key)
            new_keys.append(key)
            day = HEADER_RE.match(header).group(1)
            with open(os.path.join(ddir, day + ".log"), "a", encoding="utf-8") as out:
                out.write(header + "\n" + "\n".join(body) + "\n")
            added += 1
        if new_keys:
            with open(seen_path, "a") as f:
                f.write("\n".join(new_keys) + "\n")
    prune_raw(root)
    return added


# ---------------------------------------------------------------- orchestration

def platform_of(device):
    if device.startswith("android"):
        return "android"
    if device.startswith("desktop"):
        return "desktop"
    return "unknown"


def collect(logs, exclude_days, include_days, forget_text=None, drift_text=None):
    """logs: {device: text}.  -> result dict."""
    excluded = (set(exclude_days) - set(include_days))
    res = {"generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
           "excluded_days": sorted(excluded), "devices": {}, "overhead_table": {}}
    all_forgets = []
    for device, text in sorted(logs.items()):
        events, stats = parse_log_text(text)
        plat = platform_of(device)
        days = {e.day for e in events} - excluded
        per_day = device_metrics(events, plat, days)
        for day, d in per_day.items():
            d["dropped_lines"] = stats["dropped_lines"].get(day, 0)
            d["ambiguous"] = day in stats["ambiguous_days"]
            if d["dropped_lines"]:  # a gap in the log: every count of that day is a lower bound
                d["low_confidence"] = True
        last = max(events, key=lambda e: e.ts).ts.isoformat() if events else None
        res["devices"][device] = {"platform": plat, "parse": stats, "last_event": last,
                                  "days": per_day}
        if plat != "desktop":
            res["overhead_table"][device] = overhead_table(events, days)
        all_forgets += client_forgets(events, days)
    if forget_text is not None:
        fa = analyze_forget(forget_text)
        first = min((d for dv in res["devices"].values() for d in dv["days"]), default=None)

        def keep(ts):
            day = ts.strftime("%Y-%m-%d")
            return day not in excluded and (first is None or day >= first)
        by_day = defaultdict(lambda: {"user_closes": 0, "prunes": 0, "lone_durable1": 0})
        for k in ("user_closes", "prunes", "lone_durable1"):
            for ts, _ in fa[k]:
                if keep(ts):
                    by_day[ts.strftime("%Y-%m-%d")][k] += 1
        # client forgets in the first 48 h of forget.log (or before it) cannot be matched: set them aside
        starts = [ts for k in ("user_closes", "prunes", "lone_durable1") for ts, _ in fa[k]]
        start = min(starts) + dt.timedelta(seconds=FORGET_BEFORE_S) if starts else None
        inside = [f for f in all_forgets if start is not None and f[0] >= start]
        res["forget_log"] = {"days_utc": dict(sorted(by_day.items())),
                             "M12": dict(match_forgets(inside, fa["user_closes"]),
                                         before_forget_log=len(all_forgets) - len(inside))}
    if drift_text is not None:
        dr = analyze_drift(drift_text)
        first = min((d for dv in res["devices"].values() for d in dv["days"]), default=None)
        res["drift_log"] = {"days_utc": {d: c for d, c in sorted(dr.items())
                                         if d not in excluded and (first is None or d >= first)}}
    return res


def _read(path):
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
        return f.read()


def _read_safe(path):
    """Text of a regular readable file, else None (reported on stderr; a FIFO would block forever)."""
    if not os.path.isfile(path):
        print("extract: skip %s: not a regular file" % path, file=sys.stderr)
        return None
    try:
        return _read(path)
    except OSError as e:
        print("extract: skip %s: %s" % (path, e), file=sys.stderr)
        return None


def na(v):
    return "n/a" if v is None else v


def render_md(res):
    L = ["# Relay metrics", "",
         "Generated %s. Excluded days: %s." % (res["generated"], ", ".join(res["excluded_days"]) or "none"), ""]
    for dev, dv in res["devices"].items():
        L += ["## %s (%s)" % (dev, dv["platform"]),
              "Last event: %s. Undated lines dropped: %d (cf-open-by %d). Batches: %d (duplicates skipped %d, bad lines %d)."
              % (dv["last_event"], dv["parse"]["undated_lines"], dv["parse"]["undated_cf_open_by"],
                 dv["parse"]["batches"], dv["parse"]["duplicate_batches"], dv["parse"]["bad_lines"]), "",
              "| day | data/60s n | low-conf | ambiguous | M3 doPoll | M3 total | M4 mean rx+tx | M4 p90 | M1 storms | M1 p50 s | M2 p50 | M6 max | M7 rate (TS transport deaths/TS transports) | M8 | M9 | M10 lost | M17 |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for day, d in dv["days"].items():
            L.append("| %s | %d | %s | %s | %d | %d | %s | %s | %d | %s | %s | %s | %s (%d/%d) | %d | %d | %d | %d |" % (
                day, d["data60_samples"], "YES" if d["low_confidence"] else "no",
                "YES" if d.get("ambiguous") else "no",
                d["M3"].get("TranscriptStream.doPoll", 0), d["M3_total"],
                na(d["M4"]["rxtx_kb_min"]["mean"]), na(d["M4"]["rxtx_kb_min"]["p90"]),
                d["M1"]["storms"], na(d["M1"]["duration_s"]["p50"]), na(d["M2"]["per_trigger"]["p50"]),
                na(d["M6"]["max"]), d["M7"]["death_rate"], d["M7"]["ts_transport_deaths"], d["M7"]["ts_transports"],
                d["M8"]["total"], d["M9"], d["M10"]["connection_lost"], d["M17"]))
        L.append("")
        L += ["Other (n/a until the corresponding logs land): M11 / M13 / M14 / M15 / M16 per day:", "",
              "| day | M11 p50/p90 s (n, unmatched) | M13 p50 s | M14 cold | M14 warm | M15 bg perm/ask | M16 fail rate |",
              "|---|---|---|---|---|---|---|"]
        for day, d in dv["days"].items():
            m11 = d["M11"]
            m11s = m11 if isinstance(m11, str) else "%s/%s (%d, %d)" % (
                na(m11["p50"]), na(m11["p90"]), m11["n"], m11["unmatched"])
            L.append("| %s | %s | %s | %s | %s | %s | %s |" % (
                day, m11s, na(d["M13"]["p50"]),
                d["M14"]["cold"] if d["M14"]["cold"] == "n/a" else na(d["M14"]["cold"]["p50"]),
                d["M14"]["warm"] if d["M14"]["warm"] == "n/a" else na(d["M14"]["warm"]["p50"]),
                d["M15"]["bg_perm_ask"], d["M16"]["fail_rate"]))
        L.append("")
        for day, d in dv["days"].items():
            L.append("- %s M3 by caller: %s" % (day, json.dumps(d["M3"], sort_keys=True)))
        L.append("")
    for dev, tab in res["overhead_table"].items():
        L += ["## Overhead per transport (%s): idle bg samples (term<%dKB), rx+tx KB/min by conn" % (dev, IDLE_TERM_KB), "",
              "| conn | n | p50 | mean |", "|---|---|---|---|"]
        for b, v in tab.items():
            L.append("| %s | %d | %s | %s |" % (b, v["n"], v["p50"], v["mean"]))
        L.append("")
    if "forget_log" in res:
        f = res["forget_log"]
        L += ["## forget.log (UTC days; M12)", "", "| day | user closes | prunes | lone durable=1 |", "|---|---|---|---|"]
        for day, c in f["days_utc"].items():
            L.append("| %s | %d | %d | %d |" % (day, c["user_closes"], c["prunes"], c["lone_durable1"]))
        m = f["M12"]
        L += ["", "Client forgets: %d (+%d in the first 48 h of forget.log or before it, not matchable), explained by a user close of the same name in (t-48h, t+%ds]: %d, unexplained: %d"
              % (m["client_forgets"], m["before_forget_log"], FORGET_AFTER_S, m["explained"], len(m["unexplained"]))]
        for u in m["unexplained"]:
            L.append("- unexplained: %s %s %s (nearest same-name user close: %s s)"
                     % (u["ts_utc"], u["kind"], u["name"], u["nearest_same_name_close_s"]))
        L.append("")
    if "drift_log" in res:
        L += ["## drift.log (UTC days)", "", "| day | REFUSING | self-heal |", "|---|---|---|"]
        for day, c in res["drift_log"]["days_utc"].items():
            L.append("| %s | %d | %d |" % (day, c["REFUSING"], c["self-heal"]))
        L.append("")
    return "\n".join(L)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Relay metrics M1-M17 from client logs and server canaries.")
    home = os.path.expanduser("~/.claude-remote")
    ap.add_argument("--logs-dir", default=os.path.join(home, "logs"), help="directory with <device>.log files")
    ap.add_argument("--root", help="metrics root with raw/<device>/<day>.log snapshots (instead of --logs-dir)")
    ap.add_argument("--forget-log", default=os.path.join(home, "forget.log"))
    ap.add_argument("--drift-log", default=os.path.join(home, "drift.log"))
    ap.add_argument("--exclude-day", action="append", default=[], help="add an excluded day (default %s)" % ",".join(DEFAULT_EXCLUDE_DAYS))
    ap.add_argument("--include-day", action="append", default=[], help="remove a day from the exclusion set")
    ap.add_argument("--json", help="write JSON here (default: stdout when no --md)")
    ap.add_argument("--md", help="write the Markdown report here")
    ap.add_argument("--snapshot", metavar="ROOT", help="instead of analysing: snapshot --logs-dir into ROOT/raw")
    a = ap.parse_args(argv)
    if a.snapshot:
        print("snapshot: %d new batch(es)" % snapshot_logs(a.logs_dir, a.snapshot))
        return 0
    logs = {}
    if a.root:
        for ddir in sorted(glob.glob(os.path.join(a.root, "raw", "*"))):
            parts = [_read_safe(p) for p in sorted(glob.glob(os.path.join(ddir, "*.log")))]
            logs[os.path.basename(ddir)] = "".join(t for t in parts if t is not None)
    else:
        for p in sorted(glob.glob(os.path.join(a.logs_dir, "*.log"))):
            text = _read_safe(p)
            if text is not None:
                logs[os.path.basename(p)[:-4]] = text
    forget = _read(a.forget_log) if os.path.exists(a.forget_log) else None
    drift = _read(a.drift_log) if os.path.exists(a.drift_log) else None
    res = collect(logs, list(DEFAULT_EXCLUDE_DAYS) + a.exclude_day, a.include_day, forget, drift)
    if a.md:
        with open(a.md, "w") as f:
            f.write(render_md(res) + "\n")
    if a.json or not a.md:
        out = json.dumps(res, indent=1, sort_keys=True, default=str)
        if a.json:
            with open(a.json, "w") as f:
                f.write(out + "\n")
        else:
            print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
