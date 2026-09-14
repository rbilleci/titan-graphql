#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

full=false
case "${1:-}" in
  "") ;;
  --full) full=true ;;
  *) echo "usage: scripts/release-check.sh [--full]" >&2; exit 2 ;;
esac

fail() {
  echo "release check failed: $1" >&2
  exit 1
}

[[ -f LICENSE ]] || fail "LICENSE is missing"
grep -q "GNU GENERAL PUBLIC LICENSE" LICENSE || fail "LICENSE is not GPL"
[[ "$(git remote get-url origin)" == "https://github.com/rbilleci/titan-graphql.git" ]] \
  || fail "origin is not the rbilleci Titan GraphQL repository"

status="$(git status --porcelain --untracked-files=all)"
[[ -z "$status" ]] || fail "worktree is not clean"

submodule_status="$(git submodule status)"
if grep -Eq '^[-+U]' <<<"$submodule_status"; then
  fail "submodules are missing, conflicted, or do not match the recorded commits"
fi

tracked_junk="$(git ls-files | grep -E '(^|/)(tmp|temp|scratch|coverage|target|build|out)(/|$)|\.(tmp|bak|orig|rej|swp|swo|log|class)$' || true)"
[[ -z "$tracked_junk" ]] || fail "tracked temporary/generated files found: $tracked_junk"

secret_pattern='BEGIN ([A-Z ]+ )?PRIVATE KEY|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{24,}|github_pat_[A-Za-z0-9_]{40,}|xox[baprs]-[A-Za-z0-9-]{20,}|sk-[A-Za-z0-9]{32,}'
tracked_secrets="$(git grep -n -I -E "$secret_pattern" -- . ':!scripts/release-check.sh' || true)"
[[ -z "$tracked_secrets" ]] || fail "high-confidence credential pattern in tracked files: $tracked_secrets"

history_secrets="$(git log --all --no-ext-diff -p -- . ':!gradle/wrapper/gradle-wrapper.jar' \
  ':!scripts/release-check.sh' \
  | grep -E "$secret_pattern" || true)"
[[ -z "$history_secrets" ]] || fail "high-confidence credential pattern in reachable history"

machine_paths="$(git grep -n -I -E '/home/[^ /]+/|/Users/[^ /]+/|[A-Za-z]:\\\\Users\\\\' -- . || true)"
[[ -z "$machine_paths" ]] || fail "machine-specific absolute path in tracked files: $machine_paths"

private_authors="$(git log --all --format='%ae' | sort -u \
  | grep -Ev '^[^@]+@users\.noreply\.github\.com$' || true)"
[[ -z "$private_authors" ]] || fail "non-noreply author email in reachable history: $private_authors"

./gradlew test
if [[ "$full" == true ]]; then
  ./gradlew compiledSchemaIntegrationTest legacySqlIntegrationTest
fi

echo "release check passed ($([[ "$full" == true ]] && echo full || echo fast))"
