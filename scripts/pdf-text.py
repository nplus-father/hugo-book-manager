#!/usr/bin/env python3

import argparse
import collections
import html
import re
import subprocess
import sys

DIGIT_WORDS = {
    "zero": "0", "one": "1", "two": "2", "three": "3", "four": "4",
    "five": "5", "six": "6", "seven": "7", "eight": "8", "nine": "9",
}
PUA_DIGITS = {
    **{f"uni{0xF730 + i:04X}": str(i) for i in range(10)},
    "uniF639": "0", "uniF6DC": "1", "uniF63A": "2", "uniF63B": "3", "uniF63C": "4",
    "uniF63D": "5", "uniF63E": "6", "uniF63F": "7", "uniF640": "8", "uniF641": "9",
}
GLYPH = re.compile(r'<g (?:unicode="([^"]*)" )?glyph="([^"]*)" x="([^"]*)" y="([^"]*)"')


def glyph_text(unicode, name, extra):
    if name in extra:
        return extra[name]
    if name in PUA_DIGITS:
        return PUA_DIGITS[name]
    base = name.split(".")[0]
    if base in DIGIT_WORDS and (unicode is None or unicode == ""):
        return DIGIT_WORDS[base]
    if "_" in name and not name.startswith("uni") and (unicode is None or len(html.unescape(unicode)) < 2):
        return "".join(p.split(".")[0] for p in name.split("_"))
    return html.unescape(unicode) if unicode else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf")
    ap.add_argument("out")
    ap.add_argument("--map", action="append", default=[], metavar="GLYPH=TEXT")
    a = ap.parse_args()
    extra = dict(m.split("=", 1) for m in a.map)
    proc = subprocess.Popen(["mutool", "trace", a.pdf], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    out, line, last_y = [], [], None
    unknown = collections.Counter()
    for raw in proc.stdout:
        if raw.lstrip().startswith("<page "):
            if line:
                out.append("".join(line))
                line = []
            out.append("\f")
            last_y = None
            continue
        m = GLYPH.search(raw)
        if not m:
            continue
        ch = glyph_text(m.group(1), m.group(2), extra)
        if ch is None:
            unknown[m.group(2)] += 1
            ch = ""
        y = float(m.group(4))
        if last_y is not None and abs(y - last_y) > 2:
            out.append("".join(line))
            line = []
        line.append(ch)
        last_y = y
    out.append("".join(line))
    proc.wait()
    with open(a.out, "w") as f:
        f.write("\n".join(out) + "\n")
    for name, n in unknown.most_common(20):
        print(f"unmapped glyph {name}: {n}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
