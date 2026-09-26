#!/usr/bin/env bash
set -euo pipefail

if [ -n "${FLEET_ROOTS:-}" ]; then
  IFS=: read -r -a ROOTS <<<"$FLEET_ROOTS"
else
  ROOTS=(
    "$HOME/workspace/andrew/books-management/books-done"
    "$HOME/workspace/andrew/books-management/new-books"
    "$HOME/workspace/andrew/handbooks"
  )
fi
BATCH="${FLEET_BATCH:-150}"
# The floor is only a guard against a runaway cron; the real pacing is the
# capacity gate below — a batch goes out the minute a runner is idle.
INTERVAL="${FLEET_INTERVAL:-60}"
STAGGER="${FLEET_STAGGER:-2}"
MAX_FAIL="${FLEET_MAX_FAIL:-10}"
BACKOFF="${FLEET_BACKOFF:-900}"
ORG="${FLEET_ORG:-nplus-father}"
LABEL="${FLEET_LABEL:-hugobook}"
STATE_DIR="${FLEET_STATE_DIR:-$HOME/.local/state/fleet-push}"
LOG="$STATE_DIR/fleet-push.log"
LAST="$STATE_DIR/last-batch"
LAST_REPOS="$STATE_DIR/last-batch-repos"
HOLD="$STATE_DIR/hold-until"
LOCK="$STATE_DIR/lock"

export GIT_TERMINAL_PROMPT=0
mkdir -p "$STATE_DIR"

log() { printf '%s %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG"; }

# Repos whose checked-out branch is ahead of its upstream. Computed from local
# remote-tracking refs on purpose: no fetch, so a full sweep is a few seconds.
repos() {
  find "${ROOTS[@]}" -maxdepth 5 -name .git -type d 2>/dev/null | sort | sed 's#/\.git$##'
}

pending() {
  repos | while IFS= read -r repo; do
    ahead="$(git -C "$repo" rev-list --count '@{u}..HEAD' 2>/dev/null || echo "")"
    [ -n "$ahead" ] && [ "$ahead" -gt 0 ] && printf '%s\n' "$repo"
  done
}

repo_name() {
  git -C "$1" remote get-url origin 2>/dev/null | sed 's#.*/##; s#\.git$##'
}

# Share of the previous batch whose latest Actions check suite completed as a
# failure, as a percentage. Prints "" when nothing has completed yet. One
# GraphQL query per 40 repos; filterBy appId 15368 keeps Renovate's and
# Claude's permanently-queued suites out of the count.
last_batch_failure_pct() {
  [ -s "$LAST_REPOS" ] || { echo ""; return; }
  command -v gh >/dev/null || { echo ""; return; }
  local names=() done=0 failed=0 chunk q i
  mapfile -t names < "$LAST_REPOS"
  for ((i = 0; i < ${#names[@]}; i += 40)); do
    q="query {"
    for ((chunk = i; chunk < i + 40 && chunk < ${#names[@]}; chunk++)); do
      q+=" r$chunk: repository(owner:\"$ORG\", name:\"${names[chunk]}\") { defaultBranchRef { target { ... on Commit { checkSuites(last:1, filterBy:{appId:15368}) { nodes { status conclusion } } } } } }"
    done
    q+=" }"
    out="$(env -u GITHUB_TOKEN gh api graphql -f query="$q" \
            --jq '[.data[] | .defaultBranchRef.target.checkSuites.nodes[0] | select(. != null and .status == "COMPLETED") | .conclusion] | "\(length) \(map(select(. == "FAILURE" or . == "TIMED_OUT")) | length)"' 2>/dev/null || true)"
    [ -z "$out" ] && continue
    read -r d f <<<"$out"
    done=$((done + d)); failed=$((failed + f))
  done
  [ "$done" -eq 0 ] && { echo ""; return; }
  echo $(( failed * 100 / done ))
}

holding() {
  local until
  until="$(cat "$HOLD" 2>/dev/null || echo 0)"
  [ "$(date +%s)" -lt "$until" ]
}

# 0 = at least one online runner is idle (or the pool cannot be read — do not
# stall on a monitoring failure). 1 = every online runner is busy.
capacity() {
  local out
  if ! command -v gh >/dev/null; then return 0; fi
  # Only the runners that can take these jobs count: the pool also holds
  # andrew-PC-1 (label nplus-host), which never runs a book build and would
  # otherwise read as a permanently idle slot.
  # gh api --jq takes no --arg, so the label is interpolated into the filter.
  out="$(env -u GITHUB_TOKEN gh api "orgs/$ORG/actions/runners?per_page=100" \
          --jq "[.runners[] | select(.status==\"online\" and ([.labels[].name] | index(\"$LABEL\")))] | \"\(length) \(map(select(.busy)) | length)\"" 2>/dev/null || true)"
  if [ -z "$out" ]; then log "runner pool unreadable; proceeding without the gate"; return 0; fi
  local online busy
  read -r online busy <<<"$out"
  if [ "$online" -gt 0 ] && [ "$busy" -ge "$online" ]; then
    log "all $online $LABEL runners busy; holding this batch"
    return 1
  fi
  return 0
}

seconds_since_last() {
  local last
  last="$(cat "$LAST" 2>/dev/null || echo 0)"
  echo $(( $(date +%s) - last ))
}

# Local remote-tracking refs go stale (Renovate and the portal commit to these
# repos directly), so a push straight from the scan would be rejected with
# "fetch first". Each pending repo is fetched and, when behind, rebased onto
# its upstream before the push. Only ever fast-forward-safe pushes: no force.
sync_one() {
  local repo="$1" behind
  git -C "$repo" fetch -q origin 2>&1 || { echo "fetch failed"; return 1; }
  behind="$(git -C "$repo" rev-list --count 'HEAD..@{u}' 2>/dev/null || echo 0)"
  [ "$behind" -eq 0 ] && return 0
  # Untracked files (drafts, notes) do not block a rebase; only tracked changes do.
  if [ -n "$(git -C "$repo" status --porcelain --untracked-files=no)" ]; then
    echo "behind by $behind and working tree dirty"
    return 1
  fi
  if ! git -C "$repo" rebase -q '@{u}' >/dev/null 2>&1; then
    git -C "$repo" rebase --abort >/dev/null 2>&1 || true
    echo "rebase onto upstream conflicted (behind by $behind)"
    return 1
  fi
  echo "rebased onto upstream (was behind by $behind)"
  return 0
}

push_batch() {
  local list=() ok=0 fail=0 repo note out
  mapfile -t list < <(pending | head -n "$BATCH")
  [ "${#list[@]}" -eq 0 ] && return 1
  log "batch start: ${#list[@]} repos (batch=$BATCH stagger=${STAGGER}s)"
  date +%s > "$LAST"
  : > "$LAST_REPOS"
  for repo in "${list[@]}"; do
    repo_name "$repo" >> "$LAST_REPOS"
    if ! note="$(sync_one "$repo")"; then
      fail=$((fail+1))
      log "FAIL ${repo#$HOME/workspace/andrew/} :: $note"
      continue
    fi
    if out="$(git -C "$repo" push --quiet 2>&1)"; then
      ok=$((ok+1))
      log "ok   ${repo#$HOME/workspace/andrew/}${note:+ ($note)}"
    else
      fail=$((fail+1))
      log "FAIL ${repo#$HOME/workspace/andrew/} :: $(printf '%s' "$out" | grep -v '^hint:' | tail -1)"
    fi
    sleep "$STAGGER"
  done
  log "batch done: ok=$ok fail=$fail remaining=$(pending | wc -l)"
  return 0
}

# Push one batch if there is something to push, the interval has passed and
# the pool has room. Returns 0 when a batch went out, 1 otherwise.
once() {
  local n wait pct
  n="$(pending | wc -l)"
  [ "$n" -eq 0 ] && return 1
  if holding; then
    log "$n pending; holding until $(date -d "@$(cat "$HOLD")" '+%T') after a failure spike"
    return 1
  fi
  wait="$(seconds_since_last)"
  if [ "$wait" -lt "$INTERVAL" ]; then
    log "$n pending; last batch ${wait}s ago, floor is ${INTERVAL}s — waiting"
    return 1
  fi
  capacity || return 1
  pct="$(last_batch_failure_pct)"
  if [ -n "$pct" ] && [ "$pct" -ge "$MAX_FAIL" ]; then
    echo $(( $(date +%s) + BACKOFF )) > "$HOLD"
    log "previous batch failing at ${pct}% (limit ${MAX_FAIL}%); holding ${BACKOFF}s — check Actions for 429s before resuming"
    return 1
  fi
  push_batch
}

# Empty commit on every clean repo that is not already pending. No fetch here:
# the pusher fetches and rebases each repo right before its push, and an empty
# commit rebases onto anything.
stamp() {
  local msg="$1" made=0 skipped=0 repo ahead
  [ -n "$msg" ] || { echo "usage: $0 --stamp \"<commit message>\""; return 2; }
  while IFS= read -r repo; do
    # The template clone lives among the books but is not a site; rebuilding it
    # is noise (and it is the one local clone without the hugobook topic).
    case "$(repo_name "$repo")" in hugo-book-template) skipped=$((skipped+1)); continue ;; esac
    ahead="$(git -C "$repo" rev-list --count '@{u}..HEAD' 2>/dev/null || echo "")"
    if [ -z "$ahead" ] || [ "$ahead" -gt 0 ] || [ -n "$(git -C "$repo" status --porcelain --untracked-files=no)" ]; then
      skipped=$((skipped+1)); continue
    fi
    # Idempotent: a repo whose HEAD already carries this exact message was
    # stamped and pushed by an earlier run — stamping again would rebuild it twice.
    if [ "$(git -C "$repo" log -1 --format=%s)" = "$msg" ]; then
      skipped=$((skipped+1)); continue
    fi
    if err="$(git -C "$repo" commit -q --allow-empty --no-verify -m "$msg" 2>&1 >/dev/null)"; then
      made=$((made+1))
    else
      skipped=$((skipped+1))
      log "stamp FAIL ${repo#$HOME/workspace/andrew/} :: $(printf '%s' "$err" | grep -m1 -E 'fatal|error' || printf '%s' "$err" | tail -1)"
    fi
  done < <(repos)
  log "stamp: $made repos committed \"$msg\"; $skipped skipped (already ahead, dirty, or no upstream)"
}

mode="${1:---once}"
case "$mode" in
  --dry-run)
    pending
    ;;
  --status)
    echo "pending: $(pending | wc -l)"
    if [ -s "$LAST" ]; then
      echo "last batch: $(date -d "@$(cat "$LAST")" '+%F %T') ($(seconds_since_last)s ago)"
    else
      echo "last batch: never"
    fi
    echo "batch=$BATCH floor=${INTERVAL}s stagger=${STAGGER}s breaker=${MAX_FAIL}% backoff=${BACKOFF}s"
    holding && echo "HOLDING until $(date -d "@$(cat "$HOLD")" '+%F %T')"
    pct="$(last_batch_failure_pct)"
    [ -n "$pct" ] && echo "last batch failure share: ${pct}% ($(wc -l < "$LAST_REPOS") repos)"
    [ -f "$LOG" ] && { echo "--- log"; tail -n 12 "$LOG"; }
    ;;
  --stamp)
    stamp "${2:-}"
    ;;
  --once|--daemon)
    exec 9>"$LOCK"
    if ! flock -n 9; then
      echo "another fleet-push run holds the lock; exiting"
      exit 0
    fi
    if [ "$mode" = "--once" ]; then
      once || true
    else
      while :; do
        once || true
        [ "$(pending | wc -l)" -eq 0 ] && { log "nothing pending; daemon exiting"; break; }
        sleep 60
      done
    fi
    ;;
  *)
    sed -n '2,36p' "$0"
    exit 2
    ;;
esac
