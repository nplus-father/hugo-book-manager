#!/usr/bin/env python3

import argparse
import math
import os
import re
import sys

from PIL import Image, ImageChops, ImageOps, ImageStat

MAX_WIDTH = 1600
HARD_MAX_BYTES = 600_000
HARD_MAX_EDGE = 1600
JPEG_QUALITY = 82
QUALITY_LADDER = (85, 80, 75, 70)
MIN_GAIN = 0.85
MIN_BYTES = 150_000
PSNR_FLOOR = 40.0
EXT = (".png", ".jpg", ".jpeg")
SKIP_DIRS = {"public", "resources", "node_modules", ".git", "build"}


def is_book_cover(path):
    return os.path.basename(path) == "cover.png" and os.path.basename(os.path.dirname(path)) == "content"


def targets(paths):
    for p in paths:
        if os.path.isfile(p):
            yield p
            continue
        for r, dirs, fs in os.walk(p):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            for f in sorted(fs):
                full = os.path.join(r, f)
                if f.lower().endswith(EXT) and not is_book_cover(full):
                    yield full


def psnr(a, b):
    stat = ImageStat.Stat(ImageChops.difference(a.convert("RGB"), b.convert("RGB")))
    mse = sum(x * x for x in stat.rms) / 3
    return 99.0 if mse == 0 else 20 * math.log10(255) - 10 * math.log10(mse)


def has_alpha(im):
    if im.mode in ("RGBA", "LA"):
        return im.getchannel("A").getextrema()[0] < 255
    return im.mode == "P" and "transparency" in im.info


def shrink_png(im):
    if im.mode == "P" or has_alpha(im):
        return im
    if im.mode in ("RGBA", "LA"):
        im = im.convert("RGB" if im.mode == "RGBA" else "L")
    if im.mode in ("L", "1"):
        return im
    if im.mode != "RGB":
        im = im.convert("RGB")
    q = im.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    return q if psnr(im, q) >= PSNR_FLOOR else im


def fit(im, width=None, edge=None):
    w, h = im.size
    scale = 1.0
    if width and w > width:
        scale = width / w
    if edge and max(w, h) * scale > edge:
        scale = edge / max(w, h)
    if scale >= 1.0:
        return im
    if im.mode == "P":
        im = im.convert("RGBA" if "transparency" in im.info else "RGB")
    return im.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.Resampling.LANCZOS)


def load(path):
    with Image.open(path) as src:
        if getattr(src, "is_animated", False) or src.format not in ("PNG", "JPEG"):
            return None, None
        fmt = src.format
        im = ImageOps.exif_transpose(src)
        im.load()
    return im, fmt


def save_jpeg(im, path, quality, sharp=False):
    if im.mode not in ("RGB", "L", "CMYK"):
        im = im.convert("RGB")
    im.save(path, "JPEG", quality=quality, optimize=True, progressive=True, subsampling=0 if sharp else 2)


def soft_pass(path, apply):
    old = os.path.getsize(path)
    if old < MIN_BYTES:
        return None
    im, fmt = load(path)
    if im is None:
        return None
    width = im.size[0]
    im = fit(im, width=MAX_WIDTH)
    tmp = path + ".slim"
    if fmt == "JPEG":
        save_jpeg(im, tmp, JPEG_QUALITY)
    else:
        shrink_png(im).save(tmp, "PNG", optimize=True)
    new = os.path.getsize(tmp)
    if new >= old * MIN_GAIN:
        os.remove(tmp)
        return None
    if apply:
        os.replace(tmp, path)
    else:
        os.remove(tmp)
    return path, old, new, width, im.size[0]


def rewrite_refs(old_path, new_name):
    folder = os.path.dirname(old_path)
    old_name = os.path.basename(old_path)
    pat = re.compile(r"(?<![\w.-])" + re.escape(old_name) + r"(?![\w.-])")
    hits = 0
    for f in sorted(os.listdir(folder)):
        if not f.endswith(".md"):
            continue
        p = os.path.join(folder, f)
        s = open(p, encoding="utf-8").read()
        t, n = pat.subn(new_name, s)
        if n:
            hits += n
            open(p, "w", encoding="utf-8").write(t)
    return hits


def hard_pass(path, apply):
    old = os.path.getsize(path)
    if old <= HARD_MAX_BYTES:
        return None
    im, fmt = load(path)
    if im is None or has_alpha(im):
        return None
    width = im.size[0]
    im = fit(im, width=MAX_WIDTH, edge=HARD_MAX_EDGE)
    out = path
    if fmt == "PNG":
        out = os.path.splitext(path)[0] + ".jpg"
        if os.path.exists(out):
            return None
        if im.mode != "L":
            im = im.convert("RGB")
    tmp = path + ".slim"
    new = old
    for q in QUALITY_LADDER:
        save_jpeg(im, tmp, q, sharp=fmt == "PNG")
        new = os.path.getsize(tmp)
        if new <= HARD_MAX_BYTES:
            break
    if new >= old * MIN_GAIN:
        os.remove(tmp)
        return None
    if apply:
        os.replace(tmp, out)
        if out != path:
            os.remove(path)
            rewrite_refs(path, os.path.basename(out))
    else:
        os.remove(tmp)
    return out, old, new, width, im.size[0]


def main():
    ap = argparse.ArgumentParser(
        description="內文圖瘦身：限寬 1600px、JPEG 重壓、可無損降色的 PNG 轉調色板；"
                    "仍超過 600 KB 的照片型 PNG 轉成 JPEG 並改寫同資料夾 Markdown 的引用；封面不動")
    ap.add_argument("paths", nargs="+", help="書 repo、資料夾或圖檔")
    ap.add_argument("--apply", action="store_true", help="實際覆寫；不給就只報告")
    a = ap.parse_args()
    n = before = after = 0
    for p in targets(a.paths):
        for step in (soft_pass, hard_pass):
            if not os.path.exists(p):
                break
            try:
                r = step(p, a.apply)
            except Exception as e:
                sys.stderr.write("略過 %s：%s\n" % (p, e))
                break
            if r:
                n += 1
                before += r[1]
                after += r[2]
                print("%s\t%d KB → %d KB\t寬 %d → %d" % (r[0], r[1] // 1000, r[2] // 1000, r[3], r[4]))
                p = r[0]
    sys.stderr.write("%s %d 筆：%d MB → %d MB\n" % ("已瘦身" if a.apply else "可瘦身", n, before // 1_000_000, after // 1_000_000))
    return 0


if __name__ == "__main__":
    sys.exit(main())
