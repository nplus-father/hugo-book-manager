#!/usr/bin/env python3
import argparse
import concurrent.futures
import glob
import html
import json
import os
import re
import shutil
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
THEME_MODULE = "github.com/nplus-father/nplus-book-core"
CACHE = os.path.expanduser("~/.cache/nplus-book/audit-render")
SKIP_DIRS = {"public", "resources", "node_modules", ".git", "build", "_vendor"}
ALERTS = "NOTE|TIP|IMPORTANT|WARNING|CAUTION|QUOTE|QUESTION|EXAMPLE|INFO|FAILURE"

PROSE = re.compile(
    r'<div class="?prose"?>(.*?)(?:<section class="?kids|<nav class="?pager|<footer class="?chapter__foot)',
    re.S,
)
HOME = re.compile(r'<main class="?page"? id="?main"?>(.*?)<section class="?sec"? id="?contents"?', re.S)

LITERAL_STARS = re.compile(r"\*\*\*|顯著|信心水準|[pP]\s*[<＜]|significan")

TEXT_RULES = [
    ("leaked-bold", re.compile(r"(?<!\*)\*\*(?!\*)(?!\s*p\s*[<＜=])(?!\s*\d+(?:\.\d+)?\s*%)")),
    ("leaked-shortcode", re.compile(r"\{\{[<%]")),
    ("leaked-alert", re.compile(r"\[!(?:" + ALERTS + r")\]")),
    ("heading-no-space", re.compile(r"(?m)^\s*#{2,6}[^\s#]")),
    ("leaked-link", re.compile(r"\]\((?:https?://|/|\.|#)[^)\s]*\)")),
]


def hugo_binary():
    found = sorted(glob.glob(os.path.expanduser("~/.cache/nplus-book/hugo/*/bin/hugo")))
    return found[-1] if found else "hugo"


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
    return [b for b in books if os.path.basename(b) != "hugo-book-template"]


def visible_text(body):
    body = re.sub(r"<(pre|code|table)[^>]*>.*?</\1>", "\n", body, flags=re.S)
    body = re.sub(r"<annotation.*?</annotation>", " ", body, flags=re.S)
    body = re.sub(r'<a class="?anchor"?[^>]*>.*?</a>', " ", body, flags=re.S)
    body = re.sub(r"</(p|li|h[1-6]|blockquote|div|figcaption)>", "\n", body)
    return html.unescape(re.sub(r"<[^>]+>", "", body))


def scan_page(out, file):
    issues = []
    page = os.path.relpath(file, out)
    text = open(file, encoding="utf-8", errors="replace").read()
    match = (HOME if page == "index.html" else PROSE).search(text)
    body = match.group(1) if match else ""
    if "katex-error" in body:
        issues.append(("katex-error", page, ""))
    plain = visible_text(body)
    prose = "\n".join(line for line in plain.split("\n") if not LITERAL_STARS.search(line))
    for kind, pattern in TEXT_RULES:
        hit = pattern.search(prose if kind == "leaked-bold" else plain)
        if hit:
            start = max(0, hit.start() - 25)
            source = prose if kind == "leaked-bold" else plain
            issues.append((kind, page, source[start : hit.end() + 25].replace("\n", " ")))
    for src in re.findall(r'<img[^>]+src="?([^" >]+)', body):
        if src.startswith(("http://", "https://", "data:")):
            continue
        ref = src.split("?")[0].split("#")[0]
        if ref.startswith("/"):
            target = os.path.join(out, *ref.strip("/").split("/")[1:])
        else:
            target = os.path.normpath(os.path.join(os.path.dirname(file), ref))
        if not os.path.exists(target):
            issues.append(("img-404", page, src))
    for href in re.findall(r'<a href="?(/[^" >#]*)', body):
        if href.startswith("//"):
            continue
        target = os.path.join(out, *href.strip("/").split("/")[1:])
        if not (os.path.exists(target) or os.path.exists(os.path.join(target, "index.html"))):
            issues.append(("link-404", page, href))
    return issues


def audit(book, theme, hugo):
    name = os.path.basename(book)
    work = tempfile.mkdtemp(prefix="audit-render-")
    issues = []
    try:
        site = os.path.join(work, "site")
        os.makedirs(site)
        shutil.copytree(os.path.join(book, "site", "content"), os.path.join(site, "content"))
        shutil.copy(os.path.join(book, "site", "hugo.toml"), site)
        own_layouts = os.path.join(book, "site", "layouts")
        if os.path.isdir(own_layouts):
            shutil.copytree(own_layouts, os.path.join(site, "layouts"))
        gomod = "module audit.local/site\n\ngo 1.25\n"
        if theme:
            gomod += f"\nreplace {THEME_MODULE} => {theme}\n"
        open(os.path.join(site, "go.mod"), "w").write(gomod)
        open(os.path.join(site, "go.sum"), "w").close()
        out = os.path.join(work, "out")
        env = {k: v for k, v in os.environ.items() if k != "HUGO_MODULE_REPLACEMENTS"}
        run = subprocess.run(
            [hugo, "--enableGitInfo=false", "--baseURL", f"http://audit.local/{name}/", "-d", out, "--cacheDir", CACHE],
            cwd=site,
            env=env,
            capture_output=True,
            text=True,
        )
        log = run.stdout + run.stderr
        if run.returncode:
            errors = [line for line in log.splitlines() if "ERROR" in line or "error" in line]
            issues.append(("build-fail", "", (errors[0] if errors else log[-300:])[:400]))
        warnings = {}
        for line in log.splitlines():
            if line.startswith("WARN") and "deprecated" not in line:
                key = re.sub(r"HAHAHUGOSHORTCODE\w+", "<shortcode>", line)
                key = re.sub(r"(not found in ')[^']*'", r"\1…'", key)[:300]
                warnings[key] = warnings.get(key, 0) + 1
        for key, count in warnings.items():
            issues.append(("build-warn", "", f"{key} ×{count}" if count > 1 else key))
        if not run.returncode:
            pages = glob.glob(os.path.join(out, "docs", "**", "index.html"), recursive=True)
            for file in sorted(pages) + [os.path.join(out, "index.html")]:
                if os.path.exists(file):
                    issues.extend(scan_page(out, file))
    finally:
        subprocess.run(["chmod", "-R", "u+w", work], capture_output=True)
        shutil.rmtree(work, ignore_errors=True)
    return book, issues


def main():
    parser = argparse.ArgumentParser(
        description="把書用正式的模組模式實際建站，掃描輸出頁面裡沒渲染成功的標記、失效的圖與站內連結"
    )
    parser.add_argument("paths", nargs="*", help="書本 repo 或含多本書的資料夾")
    parser.add_argument("--all", action="store_true", help="掃 books-done、new-books 與 handbooks")
    parser.add_argument("--theme", help="用本機的 nplus-book-core 工作樹，不給就抓已發布的最新 tag")
    parser.add_argument("--jobs", type=int, default=3)
    parser.add_argument("--json", help="把完整結果另存成 JSON Lines")
    args = parser.parse_args()
    if not args.paths and not args.all:
        parser.error("給書的路徑，或用 --all")
    roots = list(args.paths) + (DEFAULT_ROOTS if args.all else [])
    books = find_books(roots)
    if not books:
        sys.exit("找不到任何含 site/hugo.toml 的書")
    theme = os.path.abspath(args.theme) if args.theme else None
    hugo = hugo_binary()
    os.makedirs(CACHE, exist_ok=True)
    totals = {}
    affected = {}
    sink = open(args.json, "w", encoding="utf-8") if args.json else None
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for book, issues in pool.map(lambda b: audit(b, theme, hugo), books):
            rel = os.path.relpath(book, os.path.dirname(BOOKS))
            if sink:
                sink.write(json.dumps({"book": rel, "issues": issues}, ensure_ascii=False) + "\n")
            seen = set()
            for kind, page, detail in issues:
                print(f"{kind}\t{rel}\t{page}\t{detail}")
                totals[kind] = totals.get(kind, 0) + 1
                if kind not in seen:
                    seen.add(kind)
                    affected[kind] = affected.get(kind, 0) + 1
    if sink:
        sink.close()
    print(f"掃了 {len(books)} 本", file=sys.stderr)
    for kind in sorted(totals, key=lambda k: -totals[k]):
        print(f"  {kind}\t{totals[kind]} 處\t{affected[kind]} 本", file=sys.stderr)
    if not totals:
        print("  沒有發現問題", file=sys.stderr)
    return 1 if totals.get("build-fail") else 0


if __name__ == "__main__":
    sys.exit(main())
