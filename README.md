# Hugo Book Manager

Kotlin + Gradle CLI，替 nplus.wiki 書庫（1,700+ 本 Hugo Book repo）建立與維護
GitHub repositories，並產生 portal 儀表板吃的衍生資料檔。

## 這個 repo 裡有什麼

```
src/                  # Kotlin CLI 本體（Clikt subcommands + services）
scripts/
├── audit-overview.py     # 深度概覽品檢：單本 gate ＋ 全庫掃描（--all/--json/--todo/--weak）
├── audit-empty-leaves.py # 列出內文過薄的葉章節
├── todo-lists.py         # 資料待辦名單（blurb、章節標題、Mermaid shortcode…），每次重算
├── fleet-push.sh         # /fleet-push skill 從這裡跑
└── handbook-cover/       # 手冊站封面產生器（SVG → PNG）
templates/
├── books-queue.yaml          # 建書佇列（gitignore；範本見 books-queue.example.yaml）
├── existing-repos.yaml       # 既有 repo 快取索引（tracked，refreshRepoIndex 重生；重複偵測用）
├── topic-taxonomy.yaml       # 三層分類白名單
├── ai-task-guide.md          # 「請處理 AI 任務」的處理指引
└── prompts/                  # AI 任務的 prompt 模板
ai-tasks/             # AI 任務交換區（input/ CLI 寫，output/ Claude 寫；內容 gitignore）
```

## 設定

設定放在專案根目錄的 `local.properties`（gitignored）。環境變數可作為
fallback，但只有下表這幾個 key 會被讀取。

```properties
GITHUB_USERNAME=nplus-father
DEFAULT_WORK_DIR=/home/andrew/workspace/andrew/books-management/new-books
TEMPLATE_REPO=nplus-father/hugo-book-template
HOMEPAGE_BASE_URL=https://nplus.wiki
```

| Key | 必填 | 用途 |
|---|---|---|
| `GITHUB_USERNAME` | ✅ | 建立 repo 的 owner（可以是 org），也用來組 Pages URL |
| `DEFAULT_WORK_DIR` | ✅ | 新書 clone 的落地目錄。**這層是扁平的**（`<workDir>/<repoName>`）；三層分類只在書畢業搬進 `books-done/` 時才套用 |
| `TEMPLATE_REPO` | ✅ | 建新書用的模板 repo，格式 `owner/repo` |
| `HOMEPAGE_BASE_URL` | ✅ | repo homepage 的前綴，最終為 `{base}/{repoName}/` |
| `PORTAL_DIR` | — | nplus.wiki portal 的本機 clone；`refreshBookHealth` / `refreshOverviewCoverage` 需要 |
| `BOOKS_DIR` | — | `books-done` 根目錄；`refreshBookHealth` / `refreshOverviewCoverage` 需要 |

## 指令

```bash
./gradlew checkEnv                # 檢查環境（gh CLI、認證、ImageMagick…）
./gradlew refreshRepoIndex        # 更新既有 book repo 的快取索引（initBooks 前先跑）

# 從 queue 初始化書籍專案（兩階段 AI 互動；一輪一本）
./gradlew initBooks                          # 處理下一本待辦
./gradlew initBooks -Pid=<book-id>           # 處理指定 ID
./gradlew initBooks -Pstatus=true            # 顯示 queue 狀態
./gradlew initBooks -Pid=<book-id> -Preset=true   # 重設為 pending

# portal 衍生檔（跑完要 commit + push portal repo 才會上儀表板）
./gradlew refreshBookHealth       # src/data/health.json（內容厚度 → /health/）
./gradlew refreshOverviewCoverage # src/data/overview.json（深度概覽改寫進度 → /health/）
```

> Gradle 不吃裸旗標。`--status` / `--reset` 這類要寫成 `-Pstatus=true` / `-Preset=true`。

衍生檔皆為 B 架構的本地端：portal 的 `fetch-*.ts` 每日從部署站台自動更新同一批檔案，
本地指令給「剛改完想馬上看」。

## AI 任務處理

本工具用 **Claude Code 互動式工作流程** 取代 API 呼叫：

1. 執行 CLI 指令（Phase 1）→ 在 `ai-tasks/input/` 生成任務檔
2. 告訴 Claude Code：「請處理 AI 任務」
3. 再次執行相同指令（Phase 2）→ 讀取 `ai-tasks/output/` 的結果並繼續

詳見 `templates/ai-task-guide.md`。注意：檔名叫 `batch-*` 但**一輪只處理一本**。

## 開發

```bash
./gradlew test spotlessCheck      # commit 前必跑；CI 跑同樣兩項加 CLI smoke test
```

慣例與 gotchas（ProcessRunner…）見 [CLAUDE.md](CLAUDE.md)。
