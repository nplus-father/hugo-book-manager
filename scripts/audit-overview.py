#!/usr/bin/env python3

import argparse
import io
import json
import os
import re
import sys
import unicodedata

_HERE = os.path.dirname(os.path.abspath(__file__))
_BOOKS_MGMT = os.path.dirname(os.path.dirname(_HERE))
DEFAULT_ROOT = os.path.join(_BOOKS_MGMT, "books-done")

SECTIONS = ["作者的位置", "完整摘要", "定位", "這本書的限制"]
LEGACY = ["作者背景", "完整摘要", "本書的貢獻與定位"]

FILLER = [
    "深刻", "精彩", "值得一讀", "發人深省", "令人震撼", "不可多得",
    "經典之作", "必讀", "震撼人心", "淋漓盡致", "鞭辟入裡", "字字珠璣",
]

SECTION_CHARS = {
    "作者的位置": (250, 800),
    "完整摘要": (600, 1700),
    "定位": (250, 800),
    "這本書的限制": (300, 900),
}

QTY_UNITS = "字人萬億千百元美歐英日台頁本名次篇件條項種個位座間所家公里呎磅噸%％"

YEAR_RE = re.compile(
    r"公元前\s*\d+"
    r"|(?<![\d.,])(?:19|20)\d{2}(?![\d.,]|\s*[" + QTY_UNITS + r"])"
    r"|(?<![\d.,])\d{3,4}\s*年"
    r"|\d{1,2}\s*世紀"
)

REF_RE = re.compile(
    r"（\*{0,2}[A-ZÀ-Þ][^（）\n一-鿿*]{3,}\*{0,2}）"
    r"|(?<!\w)_[A-Za-z][^_\n]{4,}_"
    r"|《[^》]{2,}》",
    re.M,
)

BLURB_NOISE = ["{{", "[!", "<br", "]("]

CODE_SPAN_RE = re.compile(r"`[^`\n]+`")

MIN_YEARS = 2
MIN_REFS = 3
MIN_LIMIT_SENTENCES = 3

MIN_NOTES = 8000


def zh_len(t):
    return len(re.sub(r"\s+", "", t))


_BOLD_RE = re.compile(r"\*\*([^*\n]{1,120}?)\*\*(.)")


def _is_punct(ch):
    return unicodedata.category(ch)[0] in "PS"


def dead_bold(txt):
    out = []
    for m in _BOLD_RE.finditer(txt):
        inner, nxt = m.group(1), m.group(2)
        if inner and _is_punct(inner[-1]) and not nxt.isspace() and not _is_punct(nxt):
            out.append(m.group(0))
    return out


_PAIRED_RE = re.compile(r"\{\{<\s*book-cover\b[^>]*?(?<!/)>\}\}(.*?)\{\{<\s*/book-cover\s*>\}\}", re.S)


def front_matter(s):
    if not s.startswith("---"):
        return {}
    end = s.find("\n---", 3)
    if end < 0:
        return {}
    try:
        import yaml
        fm = yaml.safe_load(s[3:end])
    except Exception:
        return {}
    return fm if isinstance(fm, dict) else {}


def read_blurb(s):
    m = _PAIRED_RE.search(s)
    if m:
        return "paired", m.group(1).strip()
    if "book-cover" not in s:
        return None, ""
    book = front_matter(s).get("book")
    if isinstance(book, dict) and book.get("blurb") is not None:
        return "field", str(book["blurb"]).strip()
    return "missing", ""


def read_overview(repo):
    p = os.path.join(repo, "site", "content", "_index.md")
    if not os.path.exists(p):
        return None, False, ""
    s = io.open(p, encoding="utf-8").read()

    legacy = "{{% details" in s and "深度概覽" in s
    start = s.find("{{% book-overview %}}")
    if start < 0:
        start = s.find("{{% details")
        if start < 0:
            return None, legacy, ""
    end = s.find("{{% /book-overview %}}")
    if end < 0:
        end = s.find("{{% /details %}}")
    blk = s[start:end] if end > start else s[start:]

    out = {}
    for name in SECTIONS + LEGACY:
        m = re.search(r"^##\s*%s\s*\n(.*?)(?=^##\s|\Z)" % re.escape(name), blk, re.S | re.M)
        if m:
            out[name] = m.group(1).strip()
    return out, legacy, blk


def check(repo):
    secs, legacy, blk = read_overview(repo)
    checks = []
    if secs is None:
        return [("有深度概覽", False, "找不到 book-overview 或 details 區塊")], {}
    if not secs:
        return [("有深度概覽", False, "區塊存在但抓不到任何 ## 段落")], {}

    present = [n for n in SECTIONS if n in secs]
    checks.append((
        "四段齊全", len(present) == 4,
        "有 %d/4：%s%s" % (len(present), "、".join(present) or "無",
                          "（舊三段格式，需改寫）" if legacy and len(present) < 4 else ""),
    ))

    for n in SECTIONS:
        body = secs.get(n)
        lo, hi = SECTION_CHARS[n]
        if body is None:
            checks.append(("%s 長度" % n, False, "缺這一段"))
        else:
            L = zh_len(body)
            if L < lo:
                detail = "%d 字（需 %d–%d，太短）" % (L, lo, hi)
            elif L > hi:
                detail = "%d 字（需 %d–%d，灌水了）" % (L, lo, hi)
            else:
                detail = "%d 字（需 %d–%d）" % (L, lo, hi)
            checks.append(("%s 長度" % n, lo <= L <= hi, detail))

    txt = "".join(secs.get(n, "") for n in SECTIONS) or "".join(secs.values())

    years = set(YEAR_RE.findall(txt))
    checks.append(("引用年份", len(years) >= MIN_YEARS,
                   "%d 個相異（需 ≥%d）" % (len(years), MIN_YEARS)))

    refs = set(REF_RE.findall(CODE_SPAN_RE.sub(" ", txt)))
    checks.append(("點名可查證的作品／人名", len(refs) >= MIN_REFS,
                   "%d 個相異（需 ≥%d）" % (len(refs), MIN_REFS)))

    hits = [f for f in FILLER if f in txt]
    checks.append(("無空洞讚美", not hits, "、".join(hits) if hits else "無"))

    dead = dead_bold(txt)
    checks.append(("粗體都收得起來", not dead,
                   "、".join(d[:18] for d in dead[:3]) if dead else "無"))

    lim = secs.get("這本書的限制", "")
    n_sent = len(re.findall(r"[。！？]", lim))
    checks.append((
        "限制段有實質內容", n_sent >= MIN_LIMIT_SENTENCES,
        "%d 個句子（需 ≥%d）" % (n_sent, MIN_LIMIT_SENTENCES),
    ))

    s = io.open(os.path.join(repo, "site", "content", "_index.md"), encoding="utf-8").read()
    if "{{% book-overview %}}" in s:
        closed = "{{% /book-overview %}}" in s
        checks.append(("區塊有關閉", closed, "有" if closed else "缺 {{% /book-overview %}}，Hugo build 會失敗"))

    kind, blurb = read_blurb(s)
    if kind == "missing":
        checks.append(("站內簡介是中文", False, "frontmatter 缺 book.blurb 欄位"))
    elif kind is not None:
        zh = re.search(r"[一-鿿]", blurb) and "這裡填寫書籍的簡介" not in blurb
        checks.append(("站內簡介是中文", bool(zh), "是" if zh else "佔位符或英文：" + blurb[:30]))
        if kind == "field":
            noise = [n for n in BLURB_NOISE if n in blurb]
            checks.append(("站內簡介無雜訊", not noise,
                           "無" if not noise else "含 " + " ".join(noise)))

    meta = dict(
        legacy=legacy,
        total=zh_len(txt),
        sections={n: zh_len(secs.get(n, "")) for n in SECTIONS},
        years=len(years), latin=len(refs), filler=len(hits),
        limit_sentences=n_sent,
    )
    return checks, meta


THIN_MARGIN = 0.15
THIN_YEARS = 2
THIN_REFS = 4
THIN_SENT = 5


def thin_flags(meta):
    flags = []
    for n in SECTIONS:
        L = meta.get("sections", {}).get(n, 0)
        lo, hi = SECTION_CHARS[n]
        if L and L <= lo + (hi - lo) * THIN_MARGIN:
            flags.append((n, "%d 字（下限 %d）" % (L, lo)))
    if meta.get("years", 0) <= THIN_YEARS:
        flags.append(("引用年份", "%d 個（門檻 %d）" % (meta.get("years", 0), MIN_YEARS)))
    if meta.get("latin", 0) <= THIN_REFS:
        flags.append(("點名對象", "%d 個（門檻 %d）" % (meta.get("latin", 0), MIN_REFS)))
    if meta.get("limit_sentences", 0) <= THIN_SENT:
        flags.append(("限制段", "%d 句（門檻 %d）"
                      % (meta.get("limit_sentences", 0), MIN_LIMIT_SENTENCES)))
    return flags


def note_volume(repo):
    total = 0
    d = os.path.join(repo, "site", "content", "docs")
    for r, _, fs in os.walk(d):
        for f in fs:
            if f.endswith(".md"):
                try:
                    total += os.path.getsize(os.path.join(r, f))
                except OSError:
                    pass
    return total


def find_books(root):
    skip = {".git", "build", "node_modules", "public", "resources"}
    out = []
    for r, dirs, fs in os.walk(root):
        dirs[:] = [d for d in dirs if d not in skip]
        if "hugo-book-template" in r:
            continue
        if r.endswith("/site/content") and "_index.md" in fs:
            out.append(os.path.dirname(os.path.dirname(r)))
    return sorted(out)


def is_written(repo):
    p = os.path.join(repo, "site", "content", "_index.md")
    if not os.path.exists(p):
        return False
    if "{{% book-overview %}}" not in io.open(p, encoding="utf-8").read():
        return False
    secs, _, _ = read_overview(repo)
    return bool(secs) and all(n in secs for n in SECTIONS)


def run_one(repo, verbose=True):
    if not os.path.isdir(repo):
        print("路徑不存在：%s" % repo)
        return 1, {}
    checks, meta = check(repo)
    failed = [c for c in checks if not c[1]]
    if verbose:
        print("%s" % os.path.basename(repo))
        for name, ok, detail in checks:
            print("  %-4s %-16s %s" % ("PASS" if ok else "FAIL", name, detail))
        print("  → %s" % ("全部通過" if not failed else "%d 項未通過，回去重寫" % len(failed)))
    return len(failed), meta


def run_all(root, as_json):
    books = find_books(root)
    rows = []
    for b in books:
        checks, meta = check(b)
        nfail = sum(1 for c in checks if not c[1])
        rows.append(dict(
            slug=os.path.relpath(b, root), fails=nfail,
            failed=[c[0] for c in checks if not c[1]],
            notes=note_volume(b), **meta,
        ))
    if as_json:
        json.dump(rows, sys.stdout, ensure_ascii=False, indent=2)
        return 0

    n = len(rows)
    clean = sum(1 for r in rows if r["fails"] == 0)
    print("掃描 %d 本，全數通過 %d 本（%.0f%%）\n" % (n, clean, clean * 100.0 / max(n, 1)))
    agg = {}
    for r in rows:
        for f in r["failed"]:
            agg[f] = agg.get(f, 0) + 1
    print("各項未通過數：")
    for k, v in sorted(agg.items(), key=lambda x: -x[1]):
        print("  %-20s %5d 本  (%.0f%%)" % (k, v, v * 100.0 / n))

    worst = sorted(rows, key=lambda r: (-r["fails"], -r.get("notes", 0)))
    print("\n最該重做的 30 本（未通過項數 → 筆記量）：")
    for r in worst[:30]:
        print("  [%d 項] %-46s 概覽 %5d 字 / 筆記 %6dk" % (
            r["fails"], r["slug"].split("/")[-1], r.get("total", 0), r.get("notes", 0) // 1000))
    return 0


def run_todo(root, n):
    todo, hollow, patch = [], [], []
    for b in find_books(root):
        checks, _ = check(b)
        nfail = sum(1 for c in checks if not c[1])
        if not nfail:
            continue
        if is_written(b):
            patch.append((b, [c[0] for c in checks if not c[1]]))
        elif note_volume(b) < MIN_NOTES:
            hollow.append(b)
        else:
            todo.append(b)
    for b in sorted(todo)[:n]:
        print(b)
    if hollow:
        sys.stderr.write(
            "\n略過 %d 本筆記是空殼的書（docs/ 只有 frontmatter，< %d bytes）。"
            "完整摘要只能取材自筆記，硬寫等於編造：\n" % (len(hollow), MIN_NOTES))
        for b in sorted(hollow):
            sys.stderr.write("  %s\n" % b)
    if patch:
        sys.stderr.write(
            "\n略過 %d 本已寫過、但品檢有項目未過的書。這些要補的是個別項目，"
            "不要整段重寫：\n" % len(patch))
        for b, f in sorted(patch):
            sys.stderr.write("  %s — %s\n" % (b, "、".join(f)))
    return 0


def run_weak(root, n):
    rows = []
    for b in find_books(root):
        checks, meta = check(b)
        if any(not c[1] for c in checks) or not is_written(b):
            continue
        flags = thin_flags(meta)
        if flags:
            rows.append((len(flags), note_volume(b), b, meta, flags))
    rows.sort(key=lambda r: (-r[0], -r[1]))
    print("已寫過且全過品檢的書中，有 %d 本至少一個維度貼著下限。" % len(rows))
    print("依（貼線維度數 → 筆記量）排序，前 %d 本：\n" % min(n, len(rows)))
    for cnt, notes, b, meta, flags in rows[:n]:
        print("[%d 項貼線] %s" % (cnt, b))
        print("           概覽 %d 字 / 筆記 %dk" % (meta.get("total", 0), notes // 1000))
        for name, detail in flags:
            print("           · %s — %s" % (name, detail))
    return 0


def main():
    ap = argparse.ArgumentParser(description="深度概覽品檢")
    ap.add_argument("repo", nargs="?", help="單一書本 repo 路徑")
    ap.add_argument("--all", action="store_true", help="掃描整個 books-done")
    ap.add_argument("--todo", type=int, metavar="N",
                    help="印出接下來該做的 N 本 repo 路徑（續作用）")
    ap.add_argument("--weak", type=int, metavar="N",
                    help="列出已寫過、品檢全過、但內容貼著下限的前 N 本")
    ap.add_argument("--root", default=DEFAULT_ROOT)
    ap.add_argument("--json", action="store_true", help="與 --all 併用，輸出 JSON")
    a = ap.parse_args()

    if a.todo is not None:
        return run_todo(a.root, a.todo)
    if a.weak is not None:
        return run_weak(a.root, a.weak)
    if a.all:
        return run_all(a.root, a.json)
    if not a.repo:
        ap.error("給一個 repo 路徑，或用 --all")
    nfail, _ = run_one(os.path.abspath(a.repo))
    return 1 if nfail else 0


if __name__ == "__main__":
    sys.exit(main())
