#!/usr/bin/env python3
"""Profile of a real Claude transcript for crd's tx fixture (dodatek D-H9). Reads one .jsonl (never written,
never copied) and prints ONLY its shape: per line its length in bytes and its top-level JSON key names. No value
of any line leaves this script. The Go test synthesises a transcript of exactly these lengths from the profile.

    python3 -I txprofile.py <transcript.jsonl> [max_lines] > ../tx-profile.json

Lines are taken from the start up to max_lines (default 300); the longest line of the file over 48 KiB is
appended when the prefix has none, so the fixture always crosses the 48 KiB piece size.
"""
import json
import sys

PIECE = 49152


def keys_of(raw):
    try:
        v = json.loads(raw)
    except ValueError:
        return []
    return sorted(k for k in v.keys() if k.isidentifier()) if isinstance(v, dict) else []


def main():
    path = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 300
    lines, big = [], None
    with open(path, "rb") as f:
        for i, raw in enumerate(f):
            if not raw.endswith(b"\n"):
                break  # a partial last line is not part of the shape
            body = raw[:-1]
            entry = {"len": len(body), "keys": keys_of(body)}
            if i < limit:
                lines.append(entry)
            if len(body) > PIECE and (big is None or len(body) > big["len"]):
                big = entry
    if big is not None and not any(l["len"] > PIECE for l in lines):
        lines.append(big)
    json.dump({"note": "shape of one real transcript: line lengths and top-level key names only", "lines": lines},
              sys.stdout, indent=0)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
