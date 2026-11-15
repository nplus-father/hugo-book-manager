#!/usr/bin/env python3

import argparse
import collections
import os
import re
import subprocess
import sys

LINE = re.compile(r'<line bbox="([^"]+)"[^>]*>\s*<font name="([^"]+)" size="([^"]+)"(.*?)</line>', re.S)
PAGE = re.compile(r'<page [^>]*width="([^"]+)" height="([^"]+)"')
CAPTION = re.compile(r"^\s*(?:FIGURE|Figure|FIG\.|Fig\.)\s*(\d+)[.\-](\d+)")
HEADER_ZONE = 75
HEADER_GAP = 14
GAP = 3


def stext(pdf, page):
    r = subprocess.run(["mutool", "draw", "-F", "stext", "-o", "-", pdf, str(page)], capture_output=True, text=True)
    return r.stdout


def lines_of(xml):
    out = []
    for m in LINE.finditer(xml):
        bbox = tuple(map(float, m.group(1).split()))
        text = "".join(re.findall(r'c="([^"]*)"', m.group(4)))
        out.append((bbox, m.group(2), round(float(m.group(3)), 1), text))
    return out


def family(font):
    return font.split("+")[-1].split("-")[0].split(",")[0]


def page_count(pdf):
    r = subprocess.run(["mutool", "info", pdf], capture_output=True, text=True)
    m = re.search(r"Pages:\s*(\d+)", r.stdout)
    return int(m.group(1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf")
    ap.add_argument("outdir")
    ap.add_argument("--dpi", type=int, default=200)
    ap.add_argument("--pages", help="comma-separated page numbers; default scans every page")
    ap.add_argument("--caption-font", help="substring of the caption label font; default is any font other than the body font")
    a = ap.parse_args()
    os.makedirs(a.outdir, exist_ok=True)
    pages = [int(p) for p in a.pages.split(",")] if a.pages else range(1, page_count(a.pdf) + 1)
    parsed = {p: stext(a.pdf, p) for p in pages}
    fonts = collections.Counter()
    for xml in parsed.values():
        for _, font, size, text in lines_of(xml):
            fonts[(font, size)] += len(text)
    body_font = fonts.most_common(1)[0][0][0] if fonts else ""
    scale = a.dpi / 72
    for p, xml in parsed.items():
        lines = lines_of(xml)
        caps = []
        for bbox, font, size, text in lines:
            m = CAPTION.match(text)
            if not m:
                continue
            if a.caption_font and a.caption_font not in font:
                continue
            if not a.caption_font and family(font) == family(body_font):
                continue
            caps.append((bbox, m.group(1), m.group(2)))
        if not caps:
            continue
        pw, _ = map(float, PAGE.search(xml).groups())
        boundary = {family(body_font)} | {family(f) for b, f, s, t in lines if CAPTION.match(t)}
        page_png = os.path.join(a.outdir, f".page-{p}.png")
        subprocess.run(["mutool", "draw", "-r", str(a.dpi), "-o", page_png, a.pdf, str(p)], capture_output=True)
        for bbox, chap, num in caps:
            bottom = bbox[1] - GAP
            above = [b[3] for b, f, s, t in lines
                     if t.strip() and b[3] <= bbox[1] - 1
                     and (family(f) in boundary or b[3] < HEADER_ZONE)]
            if above:
                edge = max(above)
                top = edge + (HEADER_GAP if edge < HEADER_ZONE else GAP)
            else:
                top = 20
            name = f"fig-{chap}-{num}.png"
            geo = f"{int(pw * scale)}x{int((bottom - top) * scale)}+0+{int(top * scale)}"
            subprocess.run(["convert", page_png, "-crop", geo, "+repage", "-trim", "-bordercolor", "white",
                            "-border", "12", "-colors", "256", "PNG8:" + os.path.join(a.outdir, name)], check=True)
            print(f"page {p}\t{name}\ttop={top:.0f}\tbottom={bottom:.0f}")
        os.remove(page_png)
    return 0


if __name__ == "__main__":
    sys.exit(main())
