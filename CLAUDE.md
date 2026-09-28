# CLAUDE.md

Hugo Book Manager 是 Kotlin + Gradle 的 CLI 工具,為讀書筆記建立 GitHub repo,與 Claude Code 以兩階段互動流程協作。程式結構與慣例照現有程式碼即可,以下只列推斷不出來的事。

## AI 任務處理(「請處理 AI 任務」)

`init-books` 是兩階段流程:CLI 產生 `ai-tasks/input/batch-metadata-request.json` 後暫停等待。聽到「請處理 AI 任務」時:

1. 產生回應(metadata + structure)→ `ai-tasks/output/batch-metadata-response.json`
2. 執行 CLI:
   ```bash
   ./gradlew installDist --quiet
   echo -e "yes\nyes" | ./build/install/hugo-book-manager/bin/hugo-book-manager init-books
   ```
3. 回報結果(repo URL、網站 URL、本地路徑)

重複偵測由 CLI 讀 `templates/existing-repos.yaml` 處理;跑 `init-books` 前先 `./gradlew refreshRepoIndex` 更新索引。詳細處理指引見 `templates/ai-task-guide.md`。

## 指令

```bash
./gradlew initBooks [-Pid=<book-id>] [-Pstatus=true] [-Preset=true]
./gradlew refreshRepoIndex        # 更新既有書本 repo 的快取索引
./gradlew refreshGoalProgress     # 重建 portal 的 src/data/progress.json(該檔勿手改)
./gradlew refreshOverviewCoverage # 重建 portal 的 src/data/overview.json(深度概覽改寫進度;跑 audit-overview.py)
./gradlew markRead -Prepo=<repo> [-Pchapter=<dir>]
./gradlew migrateTopicTiers -Papply=true -PrepoName=<repo>   # 預設 dry-run
```

```bash
python3 scripts/audit-overview.py <repo> | --all | --todo N | --weak N   # 深度概覽與站內簡介品檢
python3 scripts/todo-lists.py blurb | chapter-titles | mermaid-shortcode | italic-adjacent | locale   # 資料待辦名單，每次重算
```

- `-Pname=` 無效——`Project.name` 會遮蔽它,用 `-PrepoName=`。
- Commit 前跑 `./gradlew test spotlessCheck`;CI 跑同樣兩項加 CLI smoke test。
- 書本專案的維護任務(KaTeX、Mermaid、翻譯等)用全域 `/book-*` slash commands,不用 in-repo prompt templates。

## Gotchas

- **`ProcessRunner`**:所有 shell 呼叫都走它。stream 在 daemon threads 排空,timeout 才殺得掉卡住的 `gh`;不要在呼叫執行緒讀 process stream,那會讓 timeout 失效。
- **`MigrationPlanner`**:`migrate-topic-tiers` 的決策邏輯全在這裡(pure、有單元測試)。它決定 1300+ repo 的資料夾搬移,root 錯了會靜默搬走整個書庫——新決策邏輯加在這裡,不要寫進 command。

## 設定與中央基礎設施

`local.properties`(gitignored):

```properties
GITHUB_USERNAME=nplus-father
DEFAULT_WORK_DIR=/home/andrew/workspace/andrew/books-management/new-books
TEMPLATE_REPO=nplus-father/hugo-book-template
HOMEPAGE_BASE_URL=https://nplus.wiki
```

書本 repo 共用的中央 repo:

| Repo | 用途 |
|------|------|
| `nplus-father/book-gradle-conventions` | Gradle convention plugin(Hugo 版本、Spotless)— 發佈到 GitHub Packages |
| `nplus-father/nplus-book-core` | Hugo theme(Go module) |
| `nplus-father/workflows` (`//renovate`) | 共用 Renovate preset — `extends: ["github>nplus-father/workflows//renovate"]` |
