#!/usr/bin/env python3
import argparse
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
BOOKS = os.path.normpath(os.path.join(HERE, "..", ".."))
DEFAULT_ROOTS = [
    os.path.join(BOOKS, "books-done"),
    os.path.join(BOOKS, "new-books"),
    os.path.normpath(os.path.join(BOOKS, "..", "handbooks")),
]
THEME = os.path.join(BOOKS, "nplus-book-core")
TOOLS = os.path.expanduser("~/.cache/nplus-book/mermaid-cli")
SKIP_DIRS = {"public", "resources", "node_modules", ".git", "build", "_vendor"}
FENCE = re.compile(r"^([ \t]*)(`{3,}|~{3,})mermaid[ \t]*$")


def find_books(paths):
    books = []
    for root in paths:
        root = os.path.abspath(root)
        if os.path.isfile(os.path.join(root, "site", "hugo.toml")):
            books.append(root)
            continue
        for current, dirs, _ in os.walk(root):
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            if os.path.isfile(os.path.join(current, "site", "hugo.toml")):
                books.append(current)
                dirs[:] = []
    return books


def diagrams(markdown):
    lines = markdown.split("\n")
    i = 0
    while i < len(lines):
        m = FENCE.match(lines[i])
        if not m:
            i += 1
            continue
        indent, marker = m.group(1), m.group(2)
        body = []
        j = i + 1
        while j < len(lines):
            line = lines[j]
            if line.strip().startswith(marker[0] * len(marker)) and line.strip().strip(marker[0]) == "":
                break
            body.append(line[len(indent):] if line.startswith(indent) else line.lstrip())
            j += 1
        if j < len(lines):
            yield "\n".join(body).rstrip("\n")
        i = j + 1


def plan(book):
    content = os.path.join(book, "site", "content")
    out_dir = os.path.join(book, "site", "assets", "mermaid")
    wanted = {}
    for file in glob.glob(os.path.join(content, "**", "*.md"), recursive=True):
        text = open(file, encoding="utf-8").read()
        if "mermaid" not in text:
            continue
        for code in diagrams(text):
            digest = hashlib.sha256(code.encode()).hexdigest()
            wanted[digest] = code
    have = {}
    if os.path.isdir(out_dir):
        for f in os.listdir(out_dir):
            if f.endswith(".svg"):
                have.setdefault(f.split(".")[0], []).append(os.path.join(out_dir, f))
    missing = [
        {"hash": h, "code": c, "out": os.path.join(out_dir, f"{h}.svg")}
        for h, c in wanted.items()
        if not (os.path.exists(os.path.join(out_dir, f"{h}.svg")) and os.path.exists(os.path.join(out_dir, f"{h}.dark.svg")))
    ]
    stale = [p for h, paths in have.items() if h not in wanted for p in paths]
    return wanted, missing, stale


def main():
    parser = argparse.ArgumentParser(
        description="把書裡的 Mermaid 預先畫成淺色與深色 SVG，放在 site/assets/mermaid/<sha256>.svg；主題找得到就直接嵌入，找不到就退回瀏覽器端繪製"
    )
    parser.add_argument("paths", nargs="*")
    parser.add_argument("--all", action="store_true", help="books-done、new-books 與 handbooks")
    parser.add_argument("--check", action="store_true", help="只回報缺少與過期的 SVG，不寫檔")
    parser.add_argument("--mermaid-js", help="繪圖用的 mermaid.min.js，預設取主題 static/ 裡那份")
    args = parser.parse_args()
    if not args.paths and not args.all:
        parser.error("給書的路徑，或用 --all")
    books = find_books(list(args.paths) + (DEFAULT_ROOTS if args.all else []))
    candidates = [os.path.join(THEME, "static", "mermaid.min.js")] + sorted(
        glob.glob(os.path.expanduser("~/go/pkg/mod/github.com/alex-shpak/hugo-book@*/static/mermaid.min.js")), reverse=True
    )
    mermaid_js = args.mermaid_js or next((c for c in candidates if os.path.exists(c)), None)
    if not mermaid_js:
        sys.exit("找不到 mermaid.min.js：用 --mermaid-js 指定，版本要和主題 static/ 裡的相同")
    playwright = os.path.join(TOOLS, "node_modules", "playwright")
    if not args.check and not os.path.isdir(playwright):
        sys.exit(f"先安裝：mkdir -p {TOOLS} && cd {TOOLS} && npm init -y && PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm i playwright@1.62.0")
    total_missing = total_stale = total_failed = 0
    renderer = os.path.join(HERE, "mermaid-render.cjs")
    for book in books:
        wanted, missing, stale = plan(book)
        if not wanted and not stale:
            continue
        rel = os.path.relpath(book, os.path.dirname(BOOKS))
        total_missing += len(missing)
        total_stale += len(stale)
        if args.check:
            if missing or stale:
                print(f"{rel}\t缺 {len(missing)}\t過期 {len(stale)}")
            continue
        for path in stale:
            os.remove(path)
        if missing:
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
                json.dump(missing, fh, ensure_ascii=False)
                jobs = fh.name
            env = dict(os.environ, PLAYWRIGHT_MODULE=playwright)
            run = subprocess.run(["node", renderer, jobs, mermaid_js], capture_output=True, text=True, env=env)
            os.remove(jobs)
            fails = [l for l in run.stdout.splitlines() if l.startswith("FAIL")]
            total_failed += len(fails)
            for line in fails:
                print(f"{rel}\t{line}")
            if run.returncode:
                print(f"{rel}\tERROR\t{run.stderr.strip()[-300:]}")
        print(f"{rel}\t圖 {len(wanted)}\t新畫 {len(missing)}\t刪掉過期 {len(stale)}")
    verb = "缺" if args.check else "畫了"
    print(f"{len(books)} 本；{verb} {total_missing} 張、過期 {total_stale} 張、失敗 {total_failed} 張", file=sys.stderr)
    return 1 if args.check and (total_missing or total_stale) else 0


if __name__ == "__main__":
    sys.exit(main())
