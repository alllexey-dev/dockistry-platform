#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Usage:
  scripts/check-staged-independent.sh [-- command...]

Checks whether the currently staged changes can stand alone from unstaged and
untracked worktree changes by applying only the staged patch to a clean HEAD
copy in /tmp.

Examples:
  scripts/check-staged-independent.sh
  scripts/check-staged-independent.sh -- ./gradlew test
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

if [[ "${1:-}" == "--" ]]; then
    shift
fi

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

if git diff --cached --quiet --exit-code; then
    echo "No staged changes to check."
    exit 1
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/staged-independent.XXXXXX")"
clean_dir="$tmp_dir/repo"
patch_file="$tmp_dir/staged.patch"
archive_file="$tmp_dir/head.tar"

cleanup() {
    rm -rf "$tmp_dir"
}
trap cleanup EXIT

echo "Checking staged diff for whitespace/errors..."
git diff --cached --check

echo "Writing staged patch..."
git diff --cached --binary --output="$patch_file"

echo "Creating clean HEAD copy..."
mkdir "$clean_dir"
git archive --format=tar --output="$archive_file" HEAD
tar -xf "$archive_file" -C "$clean_dir"

echo "Applying staged patch to clean copy..."
(
    cd "$clean_dir"
    git apply --check "$patch_file"
    git apply "$patch_file"
)

if (($# > 0)); then
    echo "Running validation command in clean copy: $*"
    (
        cd "$clean_dir"
        "$@"
    )
else
    echo "No validation command provided. Patch applies independently."
    echo "For a stronger check, run: scripts/check-staged-independent.sh -- ./gradlew test"
fi

echo "Staged changes are independent of unstaged/untracked worktree changes."
