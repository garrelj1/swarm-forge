#!/usr/bin/env zsh
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RESET='\033[0m'

usage() {
  echo "Usage: swarm-merge.sh <role-or-worktree> [git-merge-args...]" >&2
  echo "Merge a role's swarmforge branch back into the project's base branch." >&2
  exit 1
}

[[ $# -ge 1 ]] || usage
TARGET="$1"
shift
typeset -a MERGE_ARGS
MERGE_ARGS=("$@")

find_project_dir() {
  local d="$PWD"
  while [[ "$d" != "/" ]]; do
    [[ -f "$d/.swarmforge/sessions.tsv" ]] && { echo "$d"; return 0; }
    d="${d:h}"
  done
  return 1
}

resolve_worktree() {
  local target="${1:l}"
  local config="$2"
  local line
  local -a fields

  [[ -f "$config" ]] || return 1
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    fields=(${=line})
    (( ${#fields[@]} == 4 )) || continue
    [[ "${fields[1]}" == "window" ]] || continue
    if [[ "${fields[2]:l}" == "$target" || "${fields[4]:l}" == "$target" ]]; then
      echo "${fields[4]}"
      return 0
    fi
  done < "$config"
  return 1
}

PROJECT_DIR="$(find_project_dir)" || {
  echo "${RED}Error:${RESET} not inside a SwarmForge project (.swarmforge not found)" >&2
  exit 1
}

BASE_FILE="$PROJECT_DIR/.swarmforge/base-branch"
CONFIG="$PROJECT_DIR/swarmforge/swarmforge.conf"

[[ -f "$BASE_FILE" ]] || {
  echo "${RED}Error:${RESET} base branch not recorded ($BASE_FILE missing). Re-run the swarm to capture it." >&2
  exit 1
}
BASE_BRANCH="$(<"$BASE_FILE")"

WORKTREE="$(resolve_worktree "$TARGET" "$CONFIG")" || WORKTREE="$TARGET"

if [[ "$WORKTREE" == "none" || "$WORKTREE" == "master" ]]; then
  echo "${YELLOW}'$TARGET' runs in the main working directory on '$BASE_BRANCH'; nothing to merge.${RESET}"
  exit 0
fi

BRANCH="swarmforge-$WORKTREE"

git -C "$PROJECT_DIR" rev-parse --verify "$BRANCH" >/dev/null 2>&1 || {
  echo "${RED}Error:${RESET} branch '$BRANCH' not found." >&2
  exit 1
}

CURRENT="$(git -C "$PROJECT_DIR" branch --show-current)"
if [[ "$CURRENT" != "$BASE_BRANCH" ]]; then
  echo "${RED}Error:${RESET} project is on '$CURRENT' but the recorded base is '$BASE_BRANCH'." >&2
  echo "Check out the base branch first:  git -C '$PROJECT_DIR' checkout '$BASE_BRANCH'" >&2
  exit 1
fi

if [[ -n "$(git -C "$PROJECT_DIR" status --porcelain)" ]]; then
  echo "${RED}Error:${RESET} working tree is not clean; commit or stash changes before merging." >&2
  exit 1
fi

echo "${GREEN}Merging ${BRANCH} -> ${BASE_BRANCH}...${RESET}"
git -C "$PROJECT_DIR" merge "${MERGE_ARGS[@]}" "$BRANCH"
echo "${GREEN}Done.${RESET}"
