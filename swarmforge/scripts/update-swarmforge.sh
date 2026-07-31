#!/usr/bin/env zsh
set -euo pipefail

MAIN_URL="https://github.com/garrelj1/swarm-forge/archive/refs/heads/main.tar.gz"
PACK_BASE_URL="https://github.com/garrelj1/swarm-forge/archive/refs/heads"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

DRY_RUN=false
PACK=""
WORKING_DIR="$PWD"

usage() {
  echo "Usage: $(basename "$0") [--dry-run] [--pack four-pack|six-pack] [working-dir]"
  echo ""
  echo "Update SwarmForge framework files from the fork."
  echo ""
  echo "  --dry-run                   Show what would change without applying updates"
  echo "  --pack four-pack|six-pack   Also update role prompts from the specified pack branch"
  echo "  working-dir                 Directory to update (default: current directory)"
  echo ""
  echo "Never overwritten (project-specific): swarmforge/swarmforge.conf,"
  echo "               swarmforge/constitution/articles/project.prompt,"
  echo "               swarmforge/constitution/articles/stack.prompt,"
  echo "               swarmforge/constitution/articles/local-*.prompt"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)   DRY_RUN=true; shift ;;
    --pack)      PACK="$2"; shift 2 ;;
    --help|-h)   usage ;;
    -*)          echo "Unknown option: $1"; usage ;;
    *)           WORKING_DIR="$1"; shift ;;
  esac
done

if [[ -n "$PACK" && "$PACK" != "four-pack" && "$PACK" != "six-pack" ]]; then
  echo -e "${RED}Error: --pack must be 'four-pack' or 'six-pack'${RESET}" >&2
  exit 1
fi

WORKING_DIR="$(cd "$WORKING_DIR" && pwd)"
TMPDIR_MAIN="$(mktemp -d)"
TMPDIR_PACK=""
trap 'rm -rf "$TMPDIR_MAIN" ${TMPDIR_PACK:+"$TMPDIR_PACK"}' EXIT

CHANGED=0
ALREADY_CURRENT=0

sync_file() {
  local src="$1" dst="$2"
  if [[ ! -f "$src" ]]; then
    echo -e "${YELLOW}  not in source: ${dst#$WORKING_DIR/}${RESET}"
    return
  fi
  if [[ -f "$dst" ]] && diff -q "$src" "$dst" > /dev/null 2>&1; then
    (( ALREADY_CURRENT++ )) || true
    return
  fi
  if [[ "$DRY_RUN" == true ]]; then
    if [[ -f "$dst" ]]; then
      echo -e "${CYAN}  would update: ${dst#$WORKING_DIR/}${RESET}"
      diff "$dst" "$src" || true
    else
      echo -e "${GREEN}  would add: ${dst#$WORKING_DIR/}${RESET}"
    fi
  else
    mkdir -p "$(dirname "$dst")"
    cp "$src" "$dst"
    [[ -x "$src" ]] && chmod +x "$dst"
    echo -e "${GREEN}  updated: ${dst#$WORKING_DIR/}${RESET}"
  fi
  (( CHANGED++ )) || true
}

sync_dir() {
  local src_dir="$1" dst_dir="$2"
  [[ -d "$src_dir" ]] || return 0
  while IFS= read -r -d '' src; do
    local rel="${src#$src_dir/}"
    sync_file "$src" "$dst_dir/$rel"
  done < <(find "$src_dir" -type f -print0)
}

# Sync constitution articles from a source dir, keeping shared framework
# articles current but never clobbering the project's own project-specific
# prompts: project.prompt holds the language, stack.prompt the stack, and
# local-*.prompt is the documented per-project specialization slot (see the
# Constitution section of README.md). Those are bootstrapped from the pack only
# when absent; every other article is kept up to date.
sync_articles() {
  local src_dir="$1"
  [[ -d "$src_dir" ]] || return 0
  local src rel
  while IFS= read -r -d '' src; do
    rel="${src#$src_dir/}"
    if [[ ( "$rel" == "project.prompt" || "$rel" == "stack.prompt" \
            || "$rel" == local-*.prompt ) \
          && -f "$WORKING_DIR/swarmforge/constitution/articles/$rel" ]]; then
      continue
    fi
    sync_file "$src" "$WORKING_DIR/swarmforge/constitution/articles/$rel"
  done < <(find "$src_dir" -type f -print0)
}

# --- Download main branch ---
echo -e "${CYAN}Downloading main branch from fork...${RESET}"
if ! curl -fsSL "$MAIN_URL" | tar -xz --strip-components=1 -C "$TMPDIR_MAIN"; then
  echo -e "${RED}Download failed.${RESET}" >&2
  exit 1
fi

sync_dir "$TMPDIR_MAIN/swarmforge/scripts" "$WORKING_DIR/swarmforge/scripts"
# Keep a top-level update-swarmforge.sh in sync only if the project keeps one
# (the canonical copy lives under swarmforge/scripts and is synced above).
if [[ -f "$WORKING_DIR/update-swarmforge.sh" ]]; then
  sync_file "$TMPDIR_MAIN/swarmforge/scripts/update-swarmforge.sh" "$WORKING_DIR/update-swarmforge.sh"
fi
# Framework-shared constitution articles (engineering/handoffs/workflow) and the
# handoff protocol doc live only on main, so keep them current from here.
sync_file "$TMPDIR_MAIN/swarmforge/handoff-protocol.md" "$WORKING_DIR/swarmforge/handoff-protocol.md"
sync_articles "$TMPDIR_MAIN/swarmforge/constitution/articles"
# The PM runs outside the swarm and is pack-independent, so its prompt lives on
# main and is kept current from here regardless of the selected pack.
sync_file "$TMPDIR_MAIN/swarmforge/roles/PM.prompt" "$WORKING_DIR/swarmforge/roles/PM.prompt"

# --- Update swarm wrapper and constitution/role prompts from pack branch ---
if [[ -n "$PACK" ]]; then
  TMPDIR_PACK="$(mktemp -d)"
  echo -e "${CYAN}Downloading ${PACK} branch from fork...${RESET}"
  if ! curl -fsSL "$PACK_BASE_URL/${PACK}.tar.gz" | tar -xz --strip-components=1 -C "$TMPDIR_PACK"; then
    echo -e "${RED}Pack branch download failed.${RESET}" >&2
    exit 1
  fi
  sync_file "$TMPDIR_PACK/swarm" "$WORKING_DIR/swarm"
  sync_file "$TMPDIR_PACK/swarmforge/constitution.prompt" "$WORKING_DIR/swarmforge/constitution.prompt"
  # Sync the pack's constitution articles (never clobbering project.prompt /
  # stack.prompt — see sync_articles).
  sync_articles "$TMPDIR_PACK/swarmforge/constitution/articles"
  sync_dir "$TMPDIR_PACK/swarmforge/roles" "$WORKING_DIR/swarmforge/roles"
  # Bootstrap swarmforge.conf only if it doesn't exist yet
  if [[ ! -f "$WORKING_DIR/swarmforge/swarmforge.conf" ]]; then
    sync_file "$TMPDIR_PACK/swarmforge/swarmforge.conf" "$WORKING_DIR/swarmforge/swarmforge.conf"
  fi
fi

echo ""
if [[ "$DRY_RUN" == true ]]; then
  echo -e "${BOLD}Dry run: $CHANGED file(s) would change, $ALREADY_CURRENT already up to date${RESET}"
else
  echo -e "${BOLD}Done: $CHANGED file(s) updated, $ALREADY_CURRENT already up to date${RESET}"
fi

if [[ -z "$PACK" ]]; then
  echo -e "${YELLOW}swarm wrapper, constitution, and role prompts not updated. Use --pack four-pack or --pack six-pack to update them.${RESET}"
fi
