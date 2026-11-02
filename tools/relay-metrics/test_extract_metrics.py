#!/usr/bin/env python3
"""Tests for extract_metrics.py:  python3 -m unittest tools/relay-metrics/test_extract_metrics.py

Fixtures are short cut-outs of REAL log lines (android-706cbd7b.log / desktop-d14d0402.log /
forget.log, 2026-10-31 .. 11-02) with session ids and tmux names anonymised; the timestamps and the
message shapes are untouched.  Where a fixture is derived (a number edited, a line added) the test
says so.  M14/M15/M16 formats do not exist in any log yet: those fixtures follow the plan text.
"""
import datetime as dt
import os
import stat
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True  # `python3 -I` ignores PYTHONDONTWRITEBYTECODE; keep the tree free of __pycache__
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract_metrics as X  # noqa: E402

SID = ["%032x" % (0xA1 * (i + 1)) for i in range(40)]  # anonymised session ids


def batch(header, *lines):
    return header + "\n" + "\n".join(lines) + "\n"


def metrics(text, device="android-x", days=None):
    events, stats = X.parse_log_text(text)
    days = days or {e.day for e in events}
    return X.device_metrics(events, X.platform_of(device), days), events, stats


CF_POLL = ("D/CloudflareProxy: cf-open-by: CloudflareProxy.connect <- SshSessionHelper$withSession$2.invokeSuspend "
           "<- SshSessionHelper$withSession$2.invoke <- SshSessionHelper.withSession <- TranscriptStream.doPoll "
           "<- TranscriptStream.runTail <- TranscriptStream.access$runTail")
CF_LEASE = ("D/CloudflareProxy: cf-open-by: CloudflareProxy.connect <- ServerTransportPool$connectTransport$2.invokeSuspend "
            "<- ServerTransportPool.connectTransport <- ServerTransportPool.lease <- ServerTransportPool$lease$1.invokeSuspend")


class ParsingTest(unittest.TestCase):
    # android head of the real log: continuation fragment + undated cf-open-by lines, then the
    # first flush header (2026-10-31 17:16:38 +0100)
    HEAD = (
        "nHelper.withSession <- TranscriptStream.doPoll <- TranscriptStream.runTail <- TranscriptStream.access$runTail\n"
        "17:12:17.595 " + CF_POLL + "\n"
        "17:12:17.595 " + CF_POLL + "\n"
        "17:12:17.606 " + CF_LEASE + "\n"
        "17:16:07.674 D/CloudflareProxy: Closing Cloudflare proxy\n"
    )

    def test_undated_head_is_dropped(self):
        text = self.HEAD + batch("--- flush 2026-10-31 17:16:38 +0100 ---",
                                 "17:16:07.964 D/CloudflareProxy: WebSocket connected to ssh.hanaktech.org",
                                 "17:16:08.100 " + CF_POLL)
        events, stats = X.parse_log_text(text)
        self.assertEqual(stats["undated_lines"], 4)
        self.assertEqual(stats["undated_cf_open_by"], 3)
        self.assertEqual(len(events), 2)
        m, _, _ = metrics(text)
        self.assertEqual(m["2026-10-31"]["M3"], {"TranscriptStream.doPoll": 1})  # not 3

    def test_continuation_lines_are_skipped(self):
        text = batch("--- flush 2026-11-02 10:40:50 +0100 ---",
                     "10:40:20.930 E/CloudflareProxy: WebSocket failure: sent ping but didn't receive pong within 15000ms (after 433 successful ping/pongs)",
                     "  SocketTimeoutException: sent ping but didn't receive pong within 15000ms (after 433 successful ping/pongs)",
                     "    at okhttp3.internal.ws.RealWebSocket$PingRunnable.run(RealWebSocket.kt:420)")
        m, events, _ = metrics(text)
        self.assertEqual(len(events), 1)
        self.assertEqual(m["2026-11-02"]["M9"], 1)  # M9 counts the E/ line once, not the stack line

    def test_midnight_rollover_real_batch(self):
        # desktop batch with header 2026-10-31 00:01:09 holding 23:02 lines (previous day) and 00:00 lines
        text = batch("--- flush 2026-10-31 00:01:09 +0100 ---",
                     "[shipper dropped 1425 lines]",
                     "23:02:39.434 D/SshManager: Connection lost",
                     "23:26:49.009 D/SshManager: Connection lost",
                     "00:00:59.060 E/SessionOrchestrator: Tmux probe failed for claude-server-p-yolo--a: x")
        events, _ = X.parse_log_text(text)
        self.assertEqual([e.day for e in events], ["2026-10-30", "2026-10-30", "2026-10-31"])
        self.assertEqual(events[0].ts.isoformat(), "2026-10-30T23:02:39.434000+01:00")

    def test_glued_header_is_split_into_two_batches(self):
        # REAL android log shape (line 12894): the requeue left a stack line without its newline, so the next
        # flush header is glued to it; the old reading took 19:09 lines for a "header older than its lines" case.
        text = (batch("--- flush 2026-10-31 18:49:11 +0100 ---",
                      "18:48:40.899 D/SessionOrchestrator: Connecting via Eternal Terminal to server (ssh.hanaktech.org)",
                      "    at com.clauderemote.session.SessionOrchestrator$connectEt$1.invokeSuspend(SessionOrchestrator.kt:16)",
                      "    at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(Co")[:-1] +
                "--- flush 2026-10-31 19:10:48 +0100 ---\n"
                "[shipper dropped 3967 lines]\n"
                "19:09:42.290 E/SessionOrchestrator: Eternal Terminal path failed — falling back to SSH\n"
                "19:09:42.290 " + CF_LEASE + "\n")
        events, stats = X.parse_log_text(text)
        self.assertEqual(stats["batches"], 2)
        self.assertEqual({e.day for e in events}, {"2026-10-31"})
        self.assertEqual([e.ts.strftime("%H:%M") for e in events], ["18:48", "19:09", "19:09"])
        m, _, _ = metrics(text)
        self.assertEqual(m["2026-10-31"]["M3"], {"ServerTransportPool.lease": 1})
        # a header that is really at the start of a line is untouched, and text merely mentioning one is not split
        plain = batch("--- flush 2026-10-31 18:49:11 +0100 ---", "18:48:40.899 D/A: see '--- flush' in docs")
        self.assertEqual(X.parse_log_text(plain)[1]["batches"], 1)

    def test_lines_are_placed_backwards_from_the_header_and_never_in_the_future(self):
        # offline batch (derived from the adversarial review): flush 2026-11-03 09:05, lines of two days
        text = batch("--- flush 2026-11-03 09:05:00 +0100 ---",
                     "20:00:00.000 D/A: a", "22:00:00.000 D/A: b", "08:00:00.000 D/A: c", "09:00:00.000 D/A: d")
        events, _ = X.parse_log_text(text)
        self.assertEqual([e.ts.strftime("%m-%d %H:%M") for e in events], ["11-02 20:00", "11-02 22:00", "11-03 08:00", "11-03 09:00"])
        # skew: a line up to 120 s after the header is the same day, 121 s after it is the previous day
        ok = X.parse_log_text(batch("--- flush 2026-11-01 10:00:00 +0100 ---", "10:02:00.000 D/A: x"))[0]
        late = X.parse_log_text(batch("--- flush 2026-11-01 10:00:00 +0100 ---", "10:02:01.000 D/A: x"))[0]
        self.assertEqual((ok[0].day, late[0].day), ("2026-11-01", "2026-10-31"))

    def test_midnight_rollover_forward_direction(self):
        # header 23:59:50 and a line stamped 00:00:30 (clock skew past midnight) is tomorrow, not 23.5 h earlier
        ev = X.parse_log_text(batch("--- flush 2026-11-01 23:59:50 +0100 ---", "00:00:30.000 D/A: x"))[0]
        self.assertEqual(ev[0].day, "2026-11-02")

    def test_negative_timezone_offset_sign(self):
        events, _ = X.parse_log_text(batch("--- flush 2026-11-01 10:00:00 -0500 ---", "09:59:00.000 D/A: x"))
        self.assertEqual(events[0].ts.astimezone(dt.timezone.utc).strftime("%H:%M"), "14:59")
        events, _ = X.parse_log_text(batch("--- flush 2026-11-01 10:00:00 +0530 ---", "09:59:00.000 D/A: x"))
        self.assertEqual(events[0].ts.astimezone(dt.timezone.utc).strftime("%H:%M"), "04:29")

    def test_ambiguous_when_headers_are_more_than_24h_apart(self):
        # shape of the real desktop-301 batch 615: header 07-18 13:36, lines from 00:00:01 of 07-15
        old = batch("--- flush 2026-07-15 00:05:00 +0200 ---", "00:04:00.000 D/A: x")
        far = batch("--- flush 2026-07-18 13:36:11 +0200 ---", "00:00:01.000 D/A: a", "13:35:00.000 D/A: b")
        events, stats = X.parse_log_text(old + far)
        self.assertEqual(max(e.ts for e in events).strftime("%m-%d"), "07-18")  # never a phantom 07-19
        self.assertEqual(stats["ambiguous_days"], ["2026-07-18"])
        r = X.collect({"desktop-x": old + far}, [], [])
        self.assertTrue(r["devices"]["desktop-x"]["days"]["2026-07-18"]["ambiguous"])
        self.assertFalse(r["devices"]["desktop-x"]["days"]["2026-07-15"]["ambiguous"])
        # exactly 24 h is not ambiguous, one second more is
        a = batch("--- flush 2026-07-15 10:00:00 +0200 ---", "09:59:00.000 D/A: x")
        for hdr, expect in (("2026-07-16 10:00:00", []), ("2026-07-16 10:00:01", ["2026-07-16"])):
            self.assertEqual(X.parse_log_text(a + batch("--- flush %s +0200 ---" % hdr, "09:59:59.000 D/A: y"))[1]["ambiguous_days"], expect)

    def test_impossible_dates_are_counted_not_fatal(self):
        bad_hdr = [batch("--- flush 2026-13-45 10:00:00 +0100 ---", "09:00:00.000 D/A: x"),
                   batch("--- flush 2026-11-01 25:00:00 +0100 ---", "09:00:00.000 D/A: x"),
                   batch("--- flush 2026-11-01 10:00:00 +9999 ---", "09:00:00.000 D/A: x"),
                   batch("--- flush 2026-02-30 10:00:00 +0100 ---", "09:00:00.000 D/A: x")]
        good = batch("--- flush 2026-11-01 10:00:00 +0100 ---", "09:00:00.000 D/A: ok", "99:99:99.000 D/A: bad", "24:00:00.000 D/A: bad2")
        events, stats = X.parse_log_text("".join(bad_hdr) + good)
        self.assertEqual([e.msg for e in events], ["ok"])
        self.assertEqual(stats["bad_lines"], 4 * 2 + 2)  # per bad batch: header + one line; two bad lines in the good one
        self.assertEqual(X.collect({"android-x": "".join(bad_hdr) + good}, [], [])["devices"]["android-x"]["parse"]["bad_lines"], 10)

    def test_bom_before_the_first_header(self):
        events, stats = X.parse_log_text("\ufeff" + batch("--- flush 2026-11-01 10:00:00 +0100 ---", "09:00:00.000 D/A: x"))
        self.assertEqual((len(events), stats["undated_lines"], stats["batches"]), (1, 0, 1))

    def test_shipper_dropped_lines_make_the_day_low_confidence(self):
        lines = ["00:%02d:13.172 D/SessionOrchestrator: data/60s: bg sessions=48(et=0) conn=16 | content term=9KB "
                 "tr=0KB poll=0KB | appRx=101KB appTx=69KB overhead≈170KB" % i for i in range(60)]
        clean = batch("--- flush 2026-11-01 01:00:00 +0100 ---", *lines)
        dropped = batch("--- flush 2026-11-01 01:00:00 +0100 ---", "[shipper dropped 375 lines]", *lines)
        c = X.collect({"desktop-x": clean}, [], [])["devices"]["desktop-x"]
        d = X.collect({"desktop-x": dropped}, [], [])["devices"]["desktop-x"]
        self.assertEqual((c["days"]["2026-11-01"]["low_confidence"], c["days"]["2026-11-01"]["dropped_lines"]), (False, 0))
        self.assertEqual((d["days"]["2026-11-01"]["low_confidence"], d["days"]["2026-11-01"]["dropped_lines"]), (True, 375))
        self.assertEqual(d["parse"]["dropped_lines"], {"2026-11-01": 375})

    def test_duplicate_batches_are_counted_once(self):
        b = batch("--- flush 2026-11-02 10:40:50 +0100 ---", "10:40:20.930 D/KeepAlive: WakeLock acquired")
        events, stats = X.parse_log_text(b + b)
        self.assertEqual(len(events), 1)
        self.assertEqual(stats["duplicate_batches"], 1)
        # same header, different body is a different batch
        b2 = batch("--- flush 2026-11-02 10:40:50 +0100 ---", "10:40:21.930 D/KeepAlive: WakeLock acquired")
        self.assertEqual(len(X.parse_log_text(b + b2)[0]), 2)

    def test_exclude_day_default_and_include_day(self):
        text = (batch("--- flush 2026-10-31 17:16:38 +0100 ---", "17:16:08.100 " + CF_POLL, "17:16:09.100 " + CF_POLL) +
                batch("--- flush 2026-11-01 17:16:38 +0100 ---", "17:16:08.100 " + CF_POLL))
        default = X.collect({"android-x": text}, X.DEFAULT_EXCLUDE_DAYS, [])
        self.assertEqual(list(default["devices"]["android-x"]["days"]), ["2026-11-01"])
        self.assertEqual(default["excluded_days"], ["2026-10-31"])
        inc = X.collect({"android-x": text}, X.DEFAULT_EXCLUDE_DAYS, ["2026-10-31"])
        days = inc["devices"]["android-x"]["days"]
        self.assertEqual({d: v["M3"]["TranscriptStream.doPoll"] for d, v in days.items()},
                         {"2026-10-31": 2, "2026-11-01": 1})

    def test_low_confidence_below_60_samples(self):
        def day(n):
            lines = ["00:%02d:13.172 D/SessionOrchestrator: data/60s: bg sessions=48(et=0) conn=16 | content term=9KB "
                     "tr=0KB poll=0KB | appRx=101KB appTx=69KB overhead≈170KB" % i for i in range(n)]
            return batch("--- flush 2026-11-01 01:00:00 +0100 ---", *lines)
        self.assertTrue(metrics(day(59))[0]["2026-11-01"]["low_confidence"])
        self.assertFalse(metrics(day(60))[0]["2026-11-01"]["low_confidence"])
        # the count is fg + bg samples: 30 + 30 is enough, 30 + 29 is not
        mix = lambda nfg: batch("--- flush 2026-11-01 01:00:00 +0100 ---",
            *["00:%02d:13.172 D/SessionOrchestrator: data/60s: bg sessions=1 conn=16 | content term=9KB | appRx=1KB appTx=1KB" % i for i in range(30)],
            *["00:%02d:14.172 D/SessionOrchestrator: data/60s: fg sessions=1 conn=16 | content term=9KB | appRx=1KB appTx=1KB" % (30 + i) for i in range(nfg)])
        self.assertFalse(metrics(mix(30))[0]["2026-11-01"]["low_confidence"])
        self.assertTrue(metrics(mix(29))[0]["2026-11-01"]["low_confidence"])


class MetricsTest(unittest.TestCase):
    def test_m3_callers(self):
        text = batch("--- flush 2026-11-01 12:00:00 +0100 ---",
                     "11:00:00.000 " + CF_POLL, "11:00:01.000 " + CF_LEASE, "11:00:02.000 " + CF_LEASE,
                     "11:00:03.000 D/CloudflareProxy: cf-open-by: CloudflareProxy.connect <- SessionStatusPoller.runPoll",
                     "11:00:04.000 D/CloudflareProxy: cf-open-by: CloudflareProxy.connect <- AccountService.withServerSession",
                     "11:00:05.000 D/CloudflareProxy: cf-open-by: CloudflareProxy.connect <- SshManager.connectForCleanup")
        m, _, _ = metrics(text)
        self.assertEqual(m["2026-11-01"]["M3"], {
            "TranscriptStream.doPoll": 1, "ServerTransportPool.lease": 2, "SessionStatusPoller": 1,
            "other withSession": 1, "connectForCleanup": 1})

    def test_android_storm_m1_m2(self):
        # real 2026-10-31 18:46:48 onResume sweep (34 dead tabs, only 6 recovered within the window)
        text = batch(
            "--- flush 2026-10-31 18:47:18 +0100 ---",
            "18:46:48.140 D/Network: Reconnect sweep (onResume): 34 dead tab(s)",
            "18:46:48.143 D/Network: Default network available, checking for dead sessions",
            "18:46:48.144 D/Network: Reconnect sweep already running, skipping (onAvailable)",
            "18:46:48.873 D/ServerTransportPool: New transport to ssh.hanaktech.org:22 (1 in pool)",
            "18:46:50.146 D/ServerTransportPool: New transport to ssh.hanaktech.org:22 (2 in pool)",
            "18:46:51.163 D/ServerTransportPool: New transport to ssh.hanaktech.org:22 (3 in pool)",
            "18:46:51.608 D/SessionOrchestrator: Reconnected: " + SID[0],
            "18:46:51.705 D/SessionOrchestrator: Reconnected: " + SID[1],
            "18:46:51.706 D/SessionOrchestrator: Auto-reconnect succeeded for " + SID[1],  # same tab: counted once
            "18:46:52.058 D/SessionOrchestrator: Reconnected: " + SID[2],
            "18:48:30.000 D/SessionOrchestrator: Reconnected: " + SID[3])  # > 90 s after the trigger
        m, _, _ = metrics(text)
        d = m["2026-10-31"]
        self.assertEqual(d["M1"]["storms"], 1)
        self.assertAlmostEqual(d["M1"]["duration_s"]["p50"], 3.9, delta=0.05)  # last success <= 90 s
        self.assertEqual(d["M2"]["per_trigger"]["p50"], 3)
        self.assertEqual(d["M6"], {"sweeps": 1, "dead_tabs": {"n": 1, "p50": 34, "p90": 34}, "max": 34})
        # derived: the sweep reports 2 dead tabs -> duration is the time of the 2nd success
        text2 = text.replace("34 dead tab(s)", "2 dead tab(s)")
        d2 = metrics(text2)[0]["2026-10-31"]
        self.assertAlmostEqual(d2["M1"]["duration_s"]["p50"], 3.6, delta=0.05)

    def test_desktop_burst_trigger(self):
        # real desktop burst 2026-10-30 20:00:45: 9 'Connection lost' within 0.1 s, then 3 successes
        lost = ["20:00:45.714", "20:00:45.716", "20:00:45.716", "20:00:45.751", "20:00:45.751", "20:00:45.751",
                "20:00:45.805", "20:00:45.806", "20:00:45.806"]
        ok = ["20:00:45.907", "20:00:45.909", "20:00:45.910"]
        text = batch("--- flush 2026-10-30 20:01:00 +0200 ---",
                     *[t + " D/SshManager: Connection lost" for t in lost],
                     *["%s D/SessionOrchestrator: Auto-reconnect succeeded for %s" % (t, SID[i]) for i, t in enumerate(ok)])
        d = metrics(text, "desktop-x")[0]["2026-10-30"]
        self.assertEqual(d["M1"]["storms"], 1)
        self.assertAlmostEqual(d["M1"]["duration_s"]["p50"], 0.2, delta=0.05)
        self.assertEqual(d["M10"]["connection_lost"], 9)
        # four lost lines in 2 s are not a burst, and an android log never uses the burst rule
        few = batch("--- flush 2026-10-30 20:01:00 +0200 ---", *[t + " D/SshManager: Connection lost" for t in lost[:4]])
        self.assertEqual(metrics(few, "desktop-x")[0]["2026-10-30"]["M1"]["storms"], 0)
        self.assertEqual(metrics(text, "android-x")[0]["2026-10-30"]["M1"]["storms"], 0)
        # five lines spread over more than 2 s are not a burst either
        slow = batch("--- flush 2026-10-30 20:01:00 +0200 ---",
                     *["20:00:%02d.000 D/SshManager: Connection lost" % s for s in (10, 12, 14, 16, 18)])
        self.assertEqual(metrics(slow, "desktop-x")[0]["2026-10-30"]["M1"]["storms"], 0)

    def test_m1s_silent_death_needs_60s_quiet(self):
        text = batch("--- flush 2026-11-01 12:00:00 +0100 ---",
                     "11:00:00.000 D/SshManager: Connection lost",
                     "11:00:30.000 D/SshManager: Connection lost",   # < 60 s after the previous: same event
                     "11:02:00.000 D/SshManager: Connection lost",   # >= 60 s quiet: silent death
                     "11:02:07.500 D/SessionOrchestrator: Reconnected: " + SID[0])
        d = metrics(text)[0]["2026-11-01"]
        self.assertEqual(d["M1s"]["deaths"], 1)
        self.assertEqual(d["M1s"]["recovery_s"]["p50"], 7.5)

    def test_m4_m5_and_overhead_buckets(self):
        bg = "D/SessionOrchestrator: data/60s: bg sessions=48(et=0) conn=%d | content term=%dKB tr=0KB poll=0KB | appRx=%dKB appTx=%dKB overhead≈0KB"
        text = batch("--- flush 2026-11-01 08:00:00 +0100 ---",
                     "07:01:00.000 " + bg % (16, 9, 101, 69),    # idle, 16+ : 170
                     "07:02:00.000 " + bg % (16, 25, 126, 71),   # term >= 20KB: not idle
                     "07:03:00.000 " + bg % (16, 9, 106, 71),    # idle, 16+ : 177
                     "07:04:00.000 " + bg % (15, 6, 77, 62),     # idle, 12-15 : 139
                     "07:05:00.000 " + bg % (0, 0, 0, 6),        # idle, 0-3 : 6
                     "07:06:00.000 D/SessionOrchestrator: data/60s: fg sessions=48(et=0) conn=15 | content term=444KB tr=23KB poll=0KB | appRx=478KB appTx=266KB overhead≈277KB",
                     "07:07:00.000 D/SessionOrchestrator: data/60s: fg sessions=48(et=0) conn=2 | content term=1206KB tr=0KB poll=0KB | net=baseline")
        m, events, _ = metrics(text)
        d = m["2026-11-01"]
        self.assertEqual(d["M4"]["n"], 5)
        self.assertEqual(d["M4"]["rxtx_kb_min"]["mean"], round((170 + 197 + 177 + 139 + 6) / 5, 1))
        self.assertEqual(d["M4"]["mean_attached"], "n/a")
        self.assertEqual(d["M5"]["n_fg_samples"], 2)
        self.assertEqual(d["M5"]["n_with_app"], 1)
        self.assertEqual(d["M5"]["rxtx_kb_min"]["mean"], 744)
        tab = X.overhead_table(events, {"2026-11-01"})
        self.assertEqual({b: (v["n"], v["p50"]) for b, v in tab.items()},
                         {"0-3": (1, "n/a"), "12-15": (1, 139), "16+": (2, 173.5)})  # conn 0-3 with n < 20 is n/a
        # desktop: M5 is machine-wide sysRx and must not be reported
        self.assertTrue(metrics(text, "desktop-x")[0]["2026-11-01"]["M5"].startswith("n/a"))

    def test_conn_0_3_bucket_is_n_a_below_20_samples_and_numeric_from_20(self):
        bg = "%02d:00:00.000 D/SessionOrchestrator: data/60s: bg sessions=1(et=0) conn=1 | content term=0KB | appRx=%dKB appTx=0KB"
        for n, expect in ((19, "n/a"), (20, 7)):
            text = batch("--- flush 2026-11-01 23:59:00 +0100 ---", *[bg % (i % 24, 7) + "" for i in range(n)])
            tab = X.overhead_table(X.parse_log_text(text)[0], {"2026-11-01"})
            self.assertEqual((tab["0-3"]["n"], tab["0-3"]["p50"], tab["0-3"]["mean"]), (n, expect, expect), n)

    def test_idle_filter_is_term_below_20kb(self):
        bg = "07:0%d:00.000 D/SessionOrchestrator: data/60s: bg sessions=1(et=0) conn=16 | content term=%dKB tr=0KB poll=0KB | appRx=10KB appTx=0KB"
        text = batch("--- flush 2026-11-01 08:00:00 +0100 ---", bg % (1, 19), bg % (2, 20), bg % (3, 21))
        tab = X.overhead_table(X.parse_log_text(text)[0], {"2026-11-01"})
        self.assertEqual(tab["16+"]["n"], 1)

    def test_conn_bucket_boundaries(self):
        self.assertEqual([X.conn_bucket(c) for c in (0, 3, 4, 11, 12, 15, 16, 19, 30)],
                         ["0-3", "0-3", "4-11", "4-11", "12-15", "12-15", "16+", "16+", "16+"])

    def test_m4_a0_fields_when_present(self):
        text = batch("--- flush 2026-11-01 08:00:00 +0100 ---",
                     "07:01:00.000 D/SessionOrchestrator: data/60s: bg sessions=48(et=0) conn=7 | content term=9KB tr=0KB "
                     "poll=0KB | appRx=50KB appTx=20KB overhead≈70KB attached=1 warm=6 parked=41")
        d = metrics(text)[0]["2026-11-01"]["M4"]
        self.assertEqual((d["mean_attached"], d["mean_warm"], d["mean_parked"]), (1, 6, 41))

    def test_m7_ts_death_rate(self):
        text = batch("--- flush 2026-11-01 08:00:00 +0100 ---",
                     "07:00:50.634 D/SessionOrchestrator: Transport for server: TAILSCALE -> 192.168.1.7 (cf=false)",
                     "07:00:55.586 D/SessionOrchestrator: Tailscale transport died 4355ms after connect for " + SID[0],
                     "07:10:50.634 D/SessionOrchestrator: Transport for server: TAILSCALE -> 192.168.1.7 (cf=false)",
                     "07:20:50.634 D/SessionOrchestrator: Transport for server: TAILSCALE -> 192.168.1.7 (cf=false)",
                     "07:30:00.000 D/SessionOrchestrator: Tailscale transport died 91000ms after connect for " + SID[0])
        d = metrics(text)[0]["2026-11-01"]["M7"]
        self.assertEqual((d["deaths"], d["deaths_le_30s"], d["ts_connects"], d["death_rate"]), (2, 1, 3, 0.333))

    def test_m8_m10_m17_counters(self):
        text = batch("--- flush 2026-11-01 08:00:00 +0100 ---",
                     "07:00:00.000 D/SessionOrchestrator: Stop hook setup: HOOK_EXISTS",
                     "07:00:01.000 D/SessionOrchestrator: streamd setup: STREAMD_OK",
                     "07:00:02.000 D/SessionOrchestrator: Server bootstrap ok",
                     "07:00:03.000 D/SessionOrchestrator: Auto-reconnect attempt 2/3 for " + SID[0],
                     "07:00:04.000 E/SessionOrchestrator: Auto-reconnect attempt 1 failed",
                     "07:00:05.000 D/KeepAlive: WakeLock acquired")
        d = metrics(text)[0]["2026-11-01"]
        self.assertEqual(d["M8"], {"stop_hook_setup": 1, "streamd_setup": 1, "server_bootstrap": 1, "total": 3})
        self.assertEqual(d["M10"], {"connection_lost": 0, "auto_reconnect_attempts": 1})
        self.assertEqual(d["M17"], 1)

    def test_m11_notification_latency(self):
        text = batch("--- flush 2026-10-31 21:51:15 +0100 ---",
                     "21:51:03.781 D/SessionOrchestrator: Stop hook fired for %s: claude-remote-notify claude-server-p-yolo--second 1793479865" % SID[0],
                     "21:51:04.148 D/AlertNotifier: Alert posted for %s: second — text" % SID[0],
                     "21:52:00.000 D/SessionOrchestrator: Stop hook fired for %s: claude-remote-notify claude-server-p-yolo--other 1" % SID[1])
        d = metrics(text)[0]["2026-10-31"]["M11"]
        self.assertEqual((d["n"], d["unmatched"]), (1, 1))
        self.assertAlmostEqual(d["p50"], 0.37, delta=0.01)

    def test_m13_restore_to_first_reconnect(self):
        text = batch("--- flush 2026-11-01 18:47:18 +0100 ---",
                     "18:46:48.135 D/SessionOrchestrator: Restored 48 persisted sessions to tabs",
                     "18:46:51.608 D/SessionOrchestrator: Reconnected: " + SID[0])
        self.assertAlmostEqual(metrics(text)[0]["2026-11-01"]["M13"]["p50"], 3.47, delta=0.01)

    def test_new_formats_m14_m15_m16_follow_the_plan(self):
        # no real lines exist yet; formats from plan S0.2 (M14/M15/M16)
        text = batch("--- flush 2026-11-05 10:00:00 +0100 ---",
                     "09:00:00.000 D/SessionOrchestrator: Attach %s kind=cold ms=900 phase=shell=100,prompt=200,decide=50,attach=400,firstByte=150" % SID[0],
                     "09:00:01.000 D/SessionOrchestrator: Attach %s kind=cold ms=1100 phase=100,200,50,400,150" % SID[1],
                     "09:00:02.000 D/SessionOrchestrator: Attach %s kind=warm ms=40" % SID[2],
                     "09:00:03.000 D/SessionOrchestrator: Hook event perm for %s (bg) age=120" % SID[0],
                     "09:00:04.000 D/SessionOrchestrator: Hook event ask for %s (fg) age=80" % SID[0],
                     "09:00:05.000 D/SessionOrchestrator: Hook event stop for %s (bg) age=80" % SID[0],
                     "09:00:06.000 D/SessionOrchestrator: ParkedInput %s bytes=12 ok" % SID[0],
                     "09:00:07.000 D/SessionOrchestrator: ParkedInput %s bytes=3 fail" % SID[1])
        d = metrics(text)[0]["2026-11-05"]
        self.assertEqual(d["M14"]["cold"]["n"], 2)
        self.assertEqual(d["M14"]["cold"]["p50"], 1000.0)
        self.assertEqual(d["M14"]["warm"]["p50"], 40)
        self.assertEqual(d["M14"]["phase_p50_ms"]["attach"], 400)
        self.assertEqual(d["M15"]["events"], {"perm/bg": 1, "ask/fg": 1, "stop/bg": 1})
        self.assertEqual(d["M15"]["bg_perm_ask"], 1)
        self.assertEqual(d["M16"], {"n": 2, "fail": 1, "fail_rate": 0.5})

    def test_baseline_new_formats_are_n_a(self):
        text = batch("--- flush 2026-11-01 08:00:00 +0100 ---", "07:00:00.000 D/KeepAlive: WakeLock acquired")
        d = metrics(text)[0]["2026-11-01"]
        self.assertEqual((d["M14"]["cold"], d["M14"]["warm"], d["M15"]["bg_perm_ask"], d["M16"]["fail_rate"]),
                         ("n/a", "n/a", "n/a", "n/a"))  # M15 is n/a while no "Hook event" line exists, not 0


class MetricsBoundaryTest(unittest.TestCase):
    """Boundaries and rules that the first round's mutations showed to be unpinned."""

    def test_storm_merges_triggers_within_90s_and_takes_n_from_the_sweep(self):
        ok = lambda t, i: "%s D/SessionOrchestrator: Reconnected: %s" % (t, SID[i])
        text = batch("--- flush 2026-11-01 12:00:00 +0100 ---",
                     "11:00:00.000 D/Network: Default network lost",
                     "11:00:30.000 D/Network: Reconnect sweep (onAvailable): 2 dead tab(s)",  # merged, brings N=2
                     ok("11:00:31.000", 0), ok("11:00:32.000", 1), ok("11:00:40.000", 2),
                     "11:02:00.000 D/Network: Default network lost",  # 120 s after the start: a second storm
                     "11:03:30.000 D/Network: Default network lost")  # exactly 90 s after the second start: merged
        d = metrics(text)[0]["2026-11-01"]["M1"]
        self.assertEqual(d["storms"], 2)
        self.assertEqual(d["duration_s"]["n"], 1)
        self.assertEqual(d["duration_s"]["p50"], 32.0)  # N=2 -> second success, not the last one
        self.assertEqual(d["unrecovered"], 1)
        # one second later than 90 s is a third storm
        text2 = text.replace("11:03:30.000", "11:03:31.000")
        self.assertEqual(metrics(text2)[0]["2026-11-01"]["M1"]["storms"], 3)

    def test_storm_counts_each_reconnected_tab_once(self):
        text = batch("--- flush 2026-11-01 12:00:00 +0100 ---",
                     "11:00:00.000 D/Network: Reconnect sweep (onResume): 2 dead tab(s)",
                     "11:00:01.000 D/SessionOrchestrator: Reconnected: " + SID[0],
                     "11:00:02.000 D/SessionOrchestrator: Auto-reconnect succeeded for " + SID[0],  # same tab again
                     "11:00:03.000 D/SessionOrchestrator: Reconnected: " + SID[1])
        self.assertEqual(metrics(text)[0]["2026-11-01"]["M1"]["duration_s"]["p50"], 3.0)

    def test_m11_window_is_300s(self):
        fired = "20:00:00.000 D/SessionOrchestrator: Stop hook fired for %s: claude-remote-notify t 1" % SID[0]
        alert = "%s D/AlertNotifier: Alert posted for %s: t — x"
        for t, matched in (("20:05:00.000", 1), ("20:05:01.000", 0)):
            d = metrics(batch("--- flush 2026-11-01 21:00:00 +0100 ---", fired, alert % (t, SID[0])))[0]["2026-11-01"]["M11"]
            self.assertEqual((d["n"], d["unmatched"]), (matched, 1 - matched), t)

    def test_m11_is_n_a_on_desktop(self):
        text = batch("--- flush 2026-11-01 21:00:00 +0100 ---",
                     "20:00:00.000 D/SessionOrchestrator: Stop hook fired for %s: claude-remote-notify t 1" % SID[0])
        self.assertTrue(metrics(text, "desktop-x")[0]["2026-11-01"]["M11"].startswith("n/a"))
        self.assertEqual(metrics(text, "android-x")[0]["2026-11-01"]["M11"]["unmatched"], 1)

    def test_m13_window_is_120s(self):
        for t, n in (("11:02:00.000", 1), ("11:02:01.000", 0)):
            text = batch("--- flush 2026-11-01 12:00:00 +0100 ---",
                         "11:00:00.000 D/SessionOrchestrator: Restored 48 persisted sessions to tabs",
                         "%s D/SessionOrchestrator: Reconnected: %s" % (t, SID[0]))
            self.assertEqual(metrics(text)[0]["2026-11-01"]["M13"]["n"], n, t)

    def test_m7_boundary_30s_and_route_field(self):
        conn = "07:00:00.000 D/SessionOrchestrator: Transport for server: TAILSCALE -> 192.168.1.7 (cf=false)"
        died = lambda ms: "07:00:%02d.000 D/SessionOrchestrator: Tailscale transport died %dms after connect for %s" % (ms // 1000 % 60, ms, SID[0])
        d = metrics(batch("--- flush 2026-11-01 08:00:00 +0100 ---", conn, died(30000), died(30001)))[0]["2026-11-01"]["M7"]
        self.assertEqual((d["deaths"], d["deaths_le_30s"]), (2, 1))
        # no route field today: tunnel rate is n/a; with a (future) route=... field the tunnel rate is computed
        self.assertEqual((d["ts_connects_tunnel"], d["ts_connects_lan"], d["death_rate_tunnel"]), (0, 0, "n/a"))
        text = batch("--- flush 2026-11-01 08:00:00 +0100 ---", conn + " route=lan", conn + " route=tunnel", conn + " route=tunnel",
                     died(4000))
        d = metrics(text)[0]["2026-11-01"]["M7"]
        self.assertEqual((d["ts_connects"], d["ts_connects_tunnel"], d["ts_connects_lan"], d["death_rate_tunnel"]), (3, 2, 1, 0.5))

    def test_spill_counters(self):
        text = batch("--- flush 2026-11-01 08:00:00 +0100 ---",
                     "07:00:00.000 D/TranscriptStream: transcript poll error (attempt 44): channel is not opened.",
                     "07:00:01.000 D/TranscriptStream: transcript poll error (attempt 13): channel is not opened.",
                     "07:00:02.000 E/SshManager: open failed: administratively prohibited")
        self.assertEqual(metrics(text)[0]["2026-11-01"]["spill"], {"channel_not_opened": 2, "administratively_prohibited": 1})

    def test_report_prints_n_a_not_none(self):
        text = batch("--- flush 2026-11-01 08:00:00 +0100 ---", "07:00:00.000 D/KeepAlive: WakeLock acquired")
        for dev in ("desktop-x", "android-x"):
            server = ("[2026-10-01T10:00:00Z] forget: a (durable=1)\n[2026-10-01T10:00:00Z] forget: a (durable=0)\n")
            md = X.render_md(X.collect({dev: text}, [], [], forget_text=server, drift_text=""))
            self.assertIn("## forget.log", md)
            self.assertNotIn("None", md)
            self.assertIn("n/a", md)


# real forget.log lines (2026-08-24 .. 2026-11-02), tmux names anonymised
FORGET = """\
[2026-08-24T08:17:01Z] forget: claude-server-p-yolo--files5 (durable=1)
[2026-08-24T08:17:01Z] forget: claude-server-p-yolo--files5 (durable=0)
[2026-08-24T08:17:36Z] forget: claude-server-p-yolo--files5 (durable=1)
[2026-08-24T08:18:41Z] forget: claude-server-p-yolo--files3 (durable=0)
[2026-11-02T09:48:40Z] forget: claude-server-p-yolo--new-features (durable=1)
[2026-11-02T09:48:40Z] forget: claude-server-p-yolo--new-features (durable=0)
[2026-11-02T18:10:12Z] forget: claude-server-p-yolo--connection (durable=1, vedení)
"""


class ForgetTest(unittest.TestCase):
    def test_durable_pair_is_one_user_close(self):
        r = X.analyze_forget(FORGET)
        self.assertEqual([(t.isoformat(), n) for t, n in r["user_closes"]], [
            ("2026-08-24T08:17:01+00:00", "claude-server-p-yolo--files5"),
            ("2026-11-02T09:48:40+00:00", "claude-server-p-yolo--new-features")])
        self.assertEqual([n for _, n in r["prunes"]], ["claude-server-p-yolo--files3"])  # lone durable=0
        self.assertEqual([n for _, n in r["lone_durable1"]],
                         ["claude-server-p-yolo--files5", "claude-server-p-yolo--connection"])

    def test_pair_gap_1_to_5s_is_a_user_close_in_either_order(self):
        for gap in (1, 5):
            r = X.analyze_forget("[2026-08-24T08:17:00Z] forget: n (durable=1)\n[2026-08-24T08:17:%02dZ] forget: n (durable=0)\n" % gap)
            self.assertEqual((len(r["user_closes"]), len(r["prunes"])), (1, 0), gap)
        r = X.analyze_forget("[2026-08-24T08:17:03Z] forget: n (durable=0)\n[2026-08-24T08:17:00Z] forget: n (durable=1)\n")
        self.assertEqual(len(r["user_closes"]), 1)

    def test_pair_further_apart_than_5s_is_not_a_user_close(self):
        r = X.analyze_forget("[2026-08-24T08:17:00Z] forget: n (durable=1)\n[2026-08-24T08:17:06Z] forget: n (durable=0)\n")
        self.assertEqual(len(r["user_closes"]), 0)
        self.assertEqual((len(r["prunes"]), len(r["lone_durable1"])), (1, 1))

    def test_pair_must_be_for_the_same_name(self):
        r = X.analyze_forget("[2026-08-24T08:17:00Z] forget: a (durable=1)\n[2026-08-24T08:17:01Z] forget: b (durable=0)\n")
        self.assertEqual(len(r["user_closes"]), 0)

    def test_server_log_per_utc_day_and_drift(self):
        drift = ("[2026-07-27T08:59:26Z] drift: REFUSING 5 tombstones at once (mass-forget after outage, not user closes) — moved aside\n"
                 "[2026-07-27T09:00:00Z] drift: self-heal — relaunching missing: x \n"
                 "[2026-07-27T09:00:01Z] drift: reconciled 52 live (was 51)\n")
        self.assertEqual(X.analyze_drift(drift), {"2026-07-27": {"REFUSING": 1, "self-heal": 1}})

    def test_timezone_conversion_client_to_utc(self):
        # real client line: 17:13:03.530 +0100 on 2026-11-01 -> 16:13:03Z.  The matching server pair is
        # CONSTRUCTED at the converted time (forget.log is UTC); one at the unconverted wall-clock must not match.
        client = batch("--- flush 2026-11-01 17:13:38 +0100 ---",
                       "17:13:03.530 D/SessionOrchestrator: Tmux 'claude-server-p-yolo--tab1' missing, untracked, and live peers exist — closed on another device, forgetting locally")
        events, _ = X.parse_log_text(client)
        fg = X.client_forgets(events, {"2026-11-01"})
        self.assertEqual(fg[0][0].astimezone(dt.timezone.utc).isoformat(), "2026-11-01T16:13:03.530000+00:00")
        good = X.analyze_forget("[2026-11-01T16:13:10Z] forget: claude-server-p-yolo--tab1 (durable=1)\n"
                                "[2026-11-01T16:13:10Z] forget: claude-server-p-yolo--tab1 (durable=0)\n")
        r = X.match_forgets(fg, good["user_closes"])
        self.assertEqual((r["explained"], len(r["unexplained"])), (1, 0))
        wrong = X.analyze_forget("[2026-11-01T17:13:10Z] forget: claude-server-p-yolo--tab1 (durable=1)\n"
                                 "[2026-11-01T17:13:10Z] forget: claude-server-p-yolo--tab1 (durable=0)\n")
        r = X.match_forgets(fg, wrong["user_closes"])
        self.assertEqual((r["explained"], len(r["unexplained"])), (0, 1))
        self.assertEqual(r["unexplained"][0]["nearest_same_name_close_s"], 3606)

    def test_summer_offset_header_converts_with_plus_0200(self):
        text = batch("--- flush 2026-08-03 12:00:00 +0200 ---",
                     "11:59:00.000 D/SessionOrchestrator: Auto-reconnect for %s aborted — closed on another device" % SID[0])
        events, _ = X.parse_log_text(text)
        ts = X.client_forgets(events, {"2026-08-03"})[0][0]
        self.assertEqual(ts.astimezone(dt.timezone.utc).strftime("%H:%M"), "09:59")

    def test_match_window_r5_48h_before_60s_after_and_name_rule(self):
        # R-5: client forget at t <= server user close of the same name in (t - 48 h, t + 60 s]
        t = dt.datetime(2026, 11, 1, 16, 13, 10, tzinfo=dt.timezone.utc)
        forget = lambda name: [(t, "k", name)]
        close = lambda sec: [(t + dt.timedelta(seconds=sec), "n1")]
        ex = lambda sec, name="n1": X.match_forgets(forget(name), close(sec))["explained"]
        self.assertEqual(ex(60), 1)                       # close 60 s AFTER the forget: clock skew, allowed
        self.assertEqual(ex(61), 0)
        self.assertEqual(ex(-3600), 1)                    # a sleeping client forgets an hour late
        self.assertEqual(ex(-(48 * 3600) + 1), 1)
        self.assertEqual(ex(-(48 * 3600)), 0)             # window is open at t - 48 h
        self.assertEqual(ex(-1, "other"), 0)              # named forget needs the same name
        self.assertEqual(ex(-1, None), 1)                 # 'aborted' lines carry no name
        self.assertEqual(X.match_forgets(forget(None), close(100))["explained"], 0)
        r = X.match_forgets(forget("n1"), close(-(48 * 3600)))
        self.assertEqual(r["unexplained"][0]["nearest_same_name_close_s"], 48 * 3600)

    def test_tombstoned_and_gone_line_is_a_client_forget(self):
        # REAL android lines (2026-11-02 14:12:45): a different wording than "missing, tombstoned"
        text = batch("--- flush 2026-11-02 14:34:00 +0100 ---",
                     "14:12:45.686 D/SessionOrchestrator: Tmux 'claude-server-claude-remote-yolo--issues' tombstoned and gone — closed on another device, forgetting locally",
                     "14:12:45.950 D/SessionOrchestrator: Tmux 'claude-server-kontexta-yolo--qdrant' tombstoned and gone — closed on another device, forgetting locally",
                     "14:33:34.899 D/SessionOrchestrator: Tmux 'claude-server-claude-remote-yolo--issues' missing, tombstoned — closed on another device, forgetting locally")
        events, _ = X.parse_log_text(text)
        fg = X.client_forgets(events, {"2026-11-02"})
        self.assertEqual([f[2] for f in fg], ["claude-server-claude-remote-yolo--issues", "claude-server-kontexta-yolo--qdrant",
                                              "claude-server-claude-remote-yolo--issues"])

    def test_forgetting_locally_and_aborted_at_the_same_moment_are_one_forget(self):
        # REAL android lines 13:07:35.837 / 13:07:35.940 (0.1 s apart); a lone aborted line still counts
        text = batch("--- flush 2026-11-01 13:21:00 +0100 ---",
                     "13:07:35.837 D/SessionOrchestrator: Tmux 'claude-server-claude-remote-yolo--notifikace' missing, tombstoned — closed on another device, forgetting locally",
                     "13:07:35.940 D/SessionOrchestrator: reconnectSession(%s) aborted — closed on another device" % SID[0],
                     "13:07:38.000 D/SessionOrchestrator: reconnectSession(%s) aborted — closed on another device" % SID[1],  # 2.163 s later
                     "13:20:00.000 D/SessionOrchestrator: reconnectSession(%s) aborted — closed on another device" % SID[2])
        fg = X.client_forgets(X.parse_log_text(text)[0], {"2026-11-01"})
        self.assertEqual([f[1] for f in fg], ["forgetting-locally", "aborted-closed-elsewhere", "aborted-closed-elsewhere"])
        self.assertEqual(fg[1][0].strftime("%H:%M:%S"), "13:07:38")

    def test_prune_stale_tab_is_a_client_forget(self):
        # REAL desktop line shape (desktop-301a8d84, 10:49:45.356)
        text = batch("--- flush 2026-09-18 10:50:00 +0200 ---",
                     "10:49:45.356 D/App: Pruning stale tab 078e004b786447051fa1281043b0a127 (tmux claude-server-~-yolo--home, 2 strikes)")
        fg = X.client_forgets(X.parse_log_text(text)[0], {"2026-09-18"})
        self.assertEqual([(f[1], f[2]) for f in fg], [("pruning-stale-tab", "claude-server-~-yolo--home")])

    def test_forget_log_report_leaves_out_excluded_days(self):
        client = (batch("--- flush 2026-10-30 17:13:38 +0100 ---", "17:13:03.530 D/A: x") +
                  batch("--- flush 2026-11-01 17:13:38 +0100 ---", "17:13:03.530 D/A: x"))
        server = ("[2026-10-31T10:00:00Z] forget: a (durable=1)\n[2026-10-31T10:00:00Z] forget: a (durable=0)\n"
                  "[2026-11-01T10:00:00Z] forget: b (durable=1)\n[2026-11-01T10:00:00Z] forget: b (durable=0)\n")
        r = X.collect({"android-x": client}, ["2026-10-31"], [], forget_text=server)
        self.assertEqual(list(r["forget_log"]["days_utc"]), ["2026-11-01"])
        r = X.collect({"android-x": client}, ["2026-10-31"], ["2026-10-31"], forget_text=server)
        self.assertEqual(r["excluded_days"], [])
        self.assertEqual(list(r["forget_log"]["days_utc"]), ["2026-10-31", "2026-11-01"])

    def test_collect_ties_client_and_server_together(self):
        client = batch("--- flush 2026-11-01 17:13:38 +0100 ---",
                       "17:13:03.530 D/SessionOrchestrator: Tmux 'claude-server-p-yolo--tab1' missing, tombstoned — closed on another device, forgetting locally")
        server = ("[2026-08-24T08:17:01Z] forget: claude-server-p-yolo--x (durable=1)\n"
                  "[2026-08-24T08:17:01Z] forget: claude-server-p-yolo--x (durable=0)\n"
                  "[2026-11-01T16:13:10Z] forget: claude-server-p-yolo--tab1 (durable=1)\n"
                  "[2026-11-01T16:13:10Z] forget: claude-server-p-yolo--tab1 (durable=0)\n")
        r = X.collect({"android-x": client}, [], [], forget_text=server)
        self.assertEqual(r["forget_log"]["M12"]["explained"], 1)
        self.assertEqual(r["forget_log"]["days_utc"]["2026-11-01"]["user_closes"], 1)


def age(path, seconds=3600):
    """Make a log file look `seconds` old (the snapshot leaves the newest batch of a fresh file alone)."""
    t = os.path.getmtime(path) - seconds
    os.utime(path, (t, t))


class SnapshotTest(unittest.TestCase):
    def test_snapshot_dedup_and_partial_tail(self):
        with tempfile.TemporaryDirectory() as tmp:
            logs, root = os.path.join(tmp, "logs"), os.path.join(tmp, "root")
            os.makedirs(logs)
            path = os.path.join(logs, "android-x.log")
            head = "17:12:17.595 " + CF_POLL + "\n"  # undated: never stored
            b1 = batch("--- flush 2026-10-31 23:59:50 +0100 ---", "23:59:40.000 D/KeepAlive: WakeLock acquired")
            b2 = batch("--- flush 2026-11-01 00:00:50 +0100 ---", "00:00:40.000 D/KeepAlive: WakeLock acquired")
            with open(path, "w") as f:
                f.write(head + b1 + b2)
            age(path)
            self.assertEqual(X.snapshot_logs(logs, root), 2)
            self.assertEqual(X.snapshot_logs(logs, root), 0)  # idempotent
            raw = os.path.join(root, "raw", "android-x")
            self.assertEqual(sorted(f for f in os.listdir(raw) if f.endswith(".log")), ["2026-10-31.log", "2026-11-01.log"])
            with open(os.path.join(raw, "2026-10-31.log")) as f:
                self.assertEqual(f.read(), b1)
            # LogShipper truncation keeps old batches and appends a new one; a half-written tail is skipped
            b3 = batch("--- flush 2026-11-01 00:01:50 +0100 ---", "00:01:40.000 D/KeepAlive: WakeLock acquired")
            with open(path, "w") as f:
                f.write(b2 + b3 + "--- flush 2026-11-01 00:02:50 +0100 ---\n00:02:40.000 D/KeepAl")
            age(path)
            self.assertEqual(X.snapshot_logs(logs, root), 1)
            # same header, different content is kept
            b3b = batch("--- flush 2026-11-01 00:01:50 +0100 ---", "00:01:41.000 D/KeepAlive: WakeLock acquired")
            with open(path, "w") as f:
                f.write(b2 + b3 + b3b)
            age(path)
            self.assertEqual(X.snapshot_logs(logs, root), 1)
            # extractor reads the snapshot root back
            text = "".join(X._read(os.path.join(raw, d)) for d in sorted(os.listdir(raw)) if d.endswith(".log"))
            events, stats = X.parse_log_text(text)
            self.assertEqual(len(events), 4)
            self.assertEqual(stats["undated_lines"], 0)

    def test_snapshot_sh_writes_only_under_root(self):
        import subprocess
        here = os.path.dirname(os.path.abspath(__file__))
        with tempfile.TemporaryDirectory() as tmp:
            logs, srv, root = (os.path.join(tmp, n) for n in ("logs", "srv", "root"))
            os.makedirs(logs)
            os.makedirs(srv)
            with open(os.path.join(logs, "android-x.log"), "w") as f:
                f.write(batch("--- flush 2026-11-01 00:00:50 +0100 ---", "00:00:40.000 D/KeepAlive: WakeLock acquired"))
            age(os.path.join(logs, "android-x.log"))
            with open(os.path.join(srv, "drift.log"), "w") as f:
                f.write("[2026-11-01T08:00:00Z] drift: REFUSING 5 tombstones\n[2026-11-01T08:00:01Z] drift: self-heal — x\n")
            with open(os.path.join(srv, "forget.log"), "w") as f:
                f.write(FORGET)
            env = dict(os.environ, RELAY_LOGS_DIR=logs, RELAY_SERVER_DIR=srv)
            for _ in range(2):  # second run: dedup, one more canary line
                r = subprocess.run(["bash", os.path.join(here, "snapshot.sh"), root], env=env,
                                   capture_output=True, text=True, timeout=60)
                self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(os.listdir(os.path.join(root, "raw", "android-x")).count("2026-11-01.log"), 1)
            with open(os.path.join(root, "raw", "android-x", "2026-11-01.log")) as f:
                self.assertEqual(f.read().count("--- flush"), 1)
            with open(os.path.join(root, "canaries", "server.log")) as f:
                lines = f.read().splitlines()
            self.assertEqual(len(lines), 2)
            with open(os.path.join(root, ".gitignore")) as f:
                self.assertEqual(f.read(), "*\n")
            self.assertRegex(lines[0], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ tmux_pid=(\d+|unavailable) "
                                      r"drift_refusing=1 drift_selfheal=1 forget_lines=7$")


SECRET = "Beze změny. Heslo k serveru je hunter2"


class SnapshotSafetyTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.logs, self.root = (os.path.join(self._tmp.name, n) for n in ("logs", "root"))
        os.makedirs(self.logs)

    def write(self, name, text, old=True):
        path = os.path.join(self.logs, name)
        with open(path, "w") as f:
            f.write(text)
        if old:
            age(path)
        return path

    def raw_text(self, device="android-x"):
        d = os.path.join(self.root, "raw", device)
        return "".join(X._read(os.path.join(d, f)) for f in sorted(os.listdir(d)) if f.endswith(".log"))

    def test_conversation_text_never_reaches_raw(self):
        # REAL carriers (AlertNotifier.kt:162, MainActivity.kt:339/373); the second alert has a multi-line body
        b = batch("--- flush 2026-11-01 08:00:00 +0100 ---",
                  "07:00:00.000 D/SessionOrchestrator: Stop hook fired for %s: claude-remote-notify t 1" % SID[0],
                  "07:00:01.000 D/AlertNotifier: Alert posted for %s: PR reviews — %s" % (SID[0], SECRET),
                  "07:00:02.000 D/AlertNotifier: Alert posted for %s: second — %s" % (SID[1], SECRET),
                  "  druhý řádek odpovědi: " + SECRET,
                  "07:00:03.000 D/Notify: Claude needs input: '%s' fg=false activeTab=true keepAlive=true notif=true" % SECRET,
                  "07:00:04.000 D/Notify: Sending alert for '%s'" % SECRET,
                  "07:00:05.000 D/Foo: Summarised lastMessage=%s" % SECRET,
                  "07:00:06.000 D/KeepAlive: WakeLock acquired")
        self.write("android-x.log", b)
        self.assertEqual(X.snapshot_logs(self.logs, self.root), 1)
        raw = self.raw_text()
        for leak in ("hunter2", "Beze změny", "druhý řádek", "PR reviews"):
            self.assertNotIn(leak, raw)
        self.assertIn("Alert posted for %s: <redacted>" % SID[0], raw)
        self.assertIn("Claude needs input: '<redacted>' fg=false", raw)
        self.assertIn("Sending alert for '<redacted>'", raw)
        self.assertIn("lastMessage=<redacted>", raw)
        self.assertIn("Stop hook fired for", raw)  # what the metrics count survives
        # the redacted raw still yields the metric: M11 matched, and the seen-key is of the redacted batch
        d = metrics(raw)[0]["2026-11-01"]["M11"]
        self.assertEqual((d["n"], d["unmatched"]), (1, 0))
        # the same batch with other text is the same batch (hash after redaction): not stored twice
        self.write("android-x.log", b + b.replace(SECRET, "jiný text"))
        self.assertEqual(X.snapshot_logs(self.logs, self.root), 0)

    def test_last_batch_waits_while_the_file_is_young(self):
        b1 = batch("--- flush 2026-11-01 08:00:00 +0100 ---", "07:59:00.000 D/A: x")
        b2 = batch("--- flush 2026-11-01 08:01:00 +0100 ---", "08:00:30.000 D/A: y")
        path = self.write("android-x.log", b1 + b2, old=False)
        self.assertEqual(X.snapshot_logs(self.logs, self.root), 1)       # fresh file: b2 may be mid-write
        age(path, 29)
        self.assertEqual(X.snapshot_logs(self.logs, self.root, now=os.path.getmtime(path) + 29), 0)
        self.assertEqual(X.snapshot_logs(self.logs, self.root, now=os.path.getmtime(path) + 30), 1)  # 30 s: taken
        self.assertEqual(self.raw_text().count("--- flush"), 2)
        # an unterminated file loses its last batch whatever its age
        self.write("android-y.log", b1 + b2.rstrip("\n"))
        self.assertEqual(X.snapshot_logs(self.logs, self.root), 1)

    def test_batch_with_an_invalid_header_is_not_stored(self):
        text = (batch("--- flush 2026-02-30 08:00:00 +0100 ---", "07:00:00.000 D/A: bad") +
                batch("--- flush 2026-11-01 08:00:00 +0100 ---", "07:00:00.000 D/A: ok") +
                batch("--- flush 2026-11-01 08:01:00 +0100 ---", "07:00:30.000 D/A: tail"))
        self.write("android-x.log", text)
        import contextlib
        import io
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(X.snapshot_logs(self.logs, self.root), 2)  # the good two; the bad one is reported
        self.assertIn("invalid header", err.getvalue())
        self.assertEqual(os.listdir(os.path.join(self.root, "raw", "android-x")).count("2026-02-30.log"), 0)
        X.collect({"android-x": self.raw_text()}, [], [])  # and the extractor does not choke on what is stored

    def test_unreadable_things_are_skipped_not_fatal(self):
        good = batch("--- flush 2026-11-01 08:00:00 +0100 ---", "07:00:00.000 D/A: ok")
        self.write("android-x.log", good)
        os.mkdir(os.path.join(self.logs, "aaa-dir.log"))
        os.mkfifo(os.path.join(self.logs, "aab-fifo.log"))  # opening a FIFO for reading would block forever
        bad = self.write("aac-noperm.log", good.replace("08:00:00", "09:00:00"))
        os.chmod(bad, 0)
        self.addCleanup(os.chmod, bad, stat.S_IRUSR | stat.S_IWUSR)
        import contextlib
        import io
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            added = X.snapshot_logs(self.logs, self.root)
        self.assertEqual(added, 1 if os.geteuid() != 0 else 2)
        self.assertIn("aaa-dir.log", err.getvalue())
        self.assertIn("aab-fifo.log", err.getvalue())
        self.assertIn("07:00:00.000", self.raw_text())
        # the analysis side skips them too
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(X.main(["--logs-dir", self.logs, "--json", os.path.join(self._tmp.name, "o.json")]), 0)

    def test_raw_older_than_60_days_is_deleted(self):
        d = os.path.join(self.root, "raw", "android-x")
        os.makedirs(d)
        for day in ("2026-09-02", "2026-09-03", "2026-11-01", "notaday"):
            open(os.path.join(d, day + ".log"), "w").close()
        today = dt.date(2026, 11, 2)  # 2026-09-03 is exactly 60 days old: kept
        self.assertEqual(X.prune_raw(self.root, today=today), 1)
        self.assertEqual(sorted(os.listdir(d)), ["2026-09-03.log", "2026-11-01.log", "notaday.log"])

    def run_sh(self, env_extra=None, root=None):
        here = os.path.dirname(os.path.abspath(__file__))
        srv = os.path.join(self._tmp.name, "srv")
        os.makedirs(srv, exist_ok=True)
        env = dict(os.environ, RELAY_LOGS_DIR=self.logs, RELAY_SERVER_DIR=srv, **(env_extra or {}))
        return subprocess.run(["bash", os.path.join(here, "snapshot.sh"), root or self.root], env=env,
                              capture_output=True, text=True, timeout=60)

    def test_snapshot_sh_unopenable_lock_is_an_error_not_a_skip(self):
        os.makedirs(os.path.join(self.root, ".snapshot.lock"))  # a directory: `exec 9>` fails
        r = self.run_sh()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("cannot open the lock", r.stderr)
        self.assertNotIn("another run", r.stderr)

    def test_snapshot_sh_canary_is_written_before_python_runs(self):
        fake = os.path.join(self._tmp.name, "bin")
        os.makedirs(fake)
        with open(os.path.join(fake, "python3"), "w") as f:
            f.write("#!/bin/sh\nexit 1\n")
        os.chmod(os.path.join(fake, "python3"), 0o755)
        r = self.run_sh({"PATH": fake + ":" + os.environ["PATH"]})
        self.assertEqual(r.returncode, 1, r.stderr)  # a failing extractor is still an error ...
        with open(os.path.join(self.root, "canaries", "server.log")) as f:
            self.assertEqual(len(f.read().splitlines()), 1)  # ... but the canary line is there


if __name__ == "__main__":
    unittest.main()
