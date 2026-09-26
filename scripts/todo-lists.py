#!/usr/bin/env python3

import argparse
import importlib.util
import io
import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("audit_overview", os.path.join(_HERE, "audit-overview.py"))
audit = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(audit)

BLURB_MIN, BLURB_MAX = 30, 75
CJK = re.compile(r"[一-鿿]")
MERMAID_SC = re.compile(r"\{\{<\s*/?\s*mermaid")
ITALIC_ADJ = re.compile(r"[一-鿿]_[A-Za-z][^_\n]{4,}_|_[A-Za-z][^_\n]{4,}_[一-鿿]")


def index_md(book):
    return io.open(os.path.join(book, "site", "content", "_index.md"), encoding="utf-8").read()


def rel(book, root):
    return os.path.relpath(book, root)


def md_files(book):
    d = os.path.join(book, "site", "content", "docs")
    out = []
    for r, dirs, fs in os.walk(d):
        dirs.sort()
        out.extend(os.path.join(r, f) for f in sorted(fs) if f.endswith(".md"))
    return out


def cmd_blurb(root):
    rows, paired = [], 0
    for b in audit.find_books(root):
        kind, blurb = audit.read_blurb(index_md(b))
        if kind == "paired":
            paired += 1
            continue
        if kind is None:
            continue
        if kind == "missing":
            rows.append((rel(b, root), "缺欄位", 0))
            continue
        probs = []
        n = audit.zh_len(blurb)
        if not CJK.search(blurb) or "這裡填寫書籍的簡介" in blurb:
            probs.append("非中文")
        noise = [x for x in audit.BLURB_NOISE if x in blurb]
        if noise:
            probs.append("雜訊 " + " ".join(noise))
        if n < BLURB_MIN:
            probs.append("太短")
        elif n > BLURB_MAX:
            probs.append("太長")
        if probs:
            rows.append((rel(b, root), "、".join(probs), n))
    for path, p, n in rows:
        print("%s\t%s\t%d 字" % (path, p, n))
    sys.stderr.write("%d 本 blurb 待處理（需 %d–%d 字、繁中、無標記）；%d 本成對 book-cover 以內文為簡介，不列入\n"
                     % (len(rows), BLURB_MIN, BLURB_MAX, paired))


def fm_title(path):
    s = io.open(path, encoding="utf-8").read()
    t = audit.front_matter(s).get("title")
    return None if t is None else str(t)


def cmd_chapter_titles(root):
    books = 0
    files = 0
    for b in audit.find_books(root):
        bad = [f for f in md_files(b) if (lambda t: t is not None and not CJK.search(t))(fm_title(f))]
        if bad:
            books += 1
            files += len(bad)
            print("%s\t%d 檔" % (rel(b, root), len(bad)))
    sys.stderr.write("%d 本、%d 個章節檔的 title 不是中文\n" % (books, files))


def cmd_mermaid(root):
    books, files = set(), 0
    for b in audit.find_books(root):
        for f in md_files(b):
            if MERMAID_SC.search(io.open(f, encoding="utf-8").read()):
                books.add(b)
                files += 1
                print(os.path.relpath(f, root))
    sys.stderr.write("%d 本、%d 個檔仍用 {{< mermaid >}} shortcode\n" % (len(books), files))


def cmd_italic(root):
    n = 0
    for b in audit.find_books(root):
        _, _, blk = audit.read_overview(b)
        hits = ITALIC_ADJ.findall(audit.CODE_SPAN_RE.sub(" ", blk or ""))
        if hits:
            n += 1
            print("%s\t%d 處\t%s" % (rel(b, root), len(hits), hits[0][:40]))
    sys.stderr.write("%d 本概覽有緊貼漢字、不會渲染的 _斜體_\n" % n)


LOCALE_RE = re.compile(r"^locale\s*=\s*['\"]([^'\"]*)['\"]", re.M)


def cmd_locale(root):
    n = 0
    for b in audit.find_books(root):
        p = os.path.join(b, "site", "hugo.toml")
        if not os.path.exists(p):
            continue
        m = LOCALE_RE.search(io.open(p, encoding="utf-8").read())
        if m and m.group(1) != "zh-Hant-TW":
            n += 1
            print("%s\t%s" % (rel(b, root), m.group(1)))
    sys.stderr.write("%d 本 site/hugo.toml 的 locale 不是 zh-Hant-TW\n" % n)


def main():
    ap = argparse.ArgumentParser(description="書庫資料待辦名單（純檔案掃描，每次重算，不存狀態）")
    ap.add_argument("list", choices=["blurb", "chapter-titles", "mermaid-shortcode", "italic-adjacent", "locale"],
                    help="blurb：長度不在 30–75、非中文、含標記或缺欄位；chapter-titles：docs/ 章節 title 不是中文；"
                         "mermaid-shortcode：仍用 {{< mermaid >}}；italic-adjacent：概覽 _Title_ 緊貼漢字；"
                         "locale：hugo.toml locale 不是 zh-Hant-TW")
    ap.add_argument("--root", default=audit.DEFAULT_ROOT)
    a = ap.parse_args()
    {"blurb": cmd_blurb, "chapter-titles": cmd_chapter_titles,
     "mermaid-shortcode": cmd_mermaid, "italic-adjacent": cmd_italic, "locale": cmd_locale}[a.list](a.root)
    return 0


if __name__ == "__main__":
    sys.exit(main())
