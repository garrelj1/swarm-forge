#!/usr/bin/env zsh
set -euo pipefail

REPO_URL="https://github.com/garrelj1/swarm-forge/archive/refs/heads/main.tar.gz"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

DRY_RUN=false
INCLUDE_ROLE_PROMPTS=false
WORKING_DIR="$PWD"

usage() {
  echo "Usage: $(basename "$0") [--dry-run] [--include-role-prompts] [working-dir]"
  echo ""
  echo "Update SwarmForge framework files from upstream."
  echo "Project-specific files (swarmforge.conf, project.prompt, stack.prompt) are never touched."
  echo ""
  echo "  --dry-run               Show what would change without applying updates"
  echo "  --include-role-prompts  Also update role prompt files (architect, coder, etc.)"
  echo "  working-dir             Directory to update (default: current directory)"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)               DRY_RUN=true; shift ;;
    --include-role-prompts)  INCLUDE_ROLE_PROMPTS=true; shift ;;
    --help|-h)               usage ;;
    -*)                      echo "Unknown option: $1"; usage ;;
    *)                       WORKING_DIR="$1"; shift ;;
  esac
done

WORKING_DIR="$(cd "$WORKING_DIR" && pwd)"

# Always update: framework shell scripts
FRAMEWORK_FILES=(
  swarmforge.sh
  swarm-cleanup.sh
  swarm-terminal-adapter.sh
  swarmlog.sh
  swarm-window-watchdog.sh
  update-swarmforge.sh
  swarmforge/constitution.prompt
  swarmforge/constitution/engineering.prompt
  swarmforge/constitution/workflow.prompt
)

# Updated only with --include-role-prompts
ROLE_PROMPT_FILES=(
  swarmforge/architect.prompt
  swarmforge/coder.prompt
  swarmforge/refactorer.prompt
  swarmforge/reviewer.prompt
  swarmforge/specifier.prompt
)

# Never touched (project-specific):
#   swarmforge/swarmforge.conf
#   swarmforge/constitution/project.prompt
#   swarmforge/stack.prompt

TMPDIR_WORK="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_WORK"' EXIT

echo -e "${CYAN}Downloading latest SwarmForge from upstream...${RESET}"
if ! curl -fsSL "$REPO_URL" | tar -xz --strip-components=1 -C "$TMPDIR_WORK"; then
  echo -e "${RED}Download failed.${RESET}" >&2
  exit 1
fi

FILES_TO_UPDATE=("${FRAMEWORK_FILES[@]}")
if [[ "$INCLUDE_ROLE_PROMPTS" == true ]]; then
  FILES_TO_UPDATE+=("${ROLE_PROMPT_FILES[@]}")
fi

CHANGED=0
ALREADY_CURRENT=0

for file in "${FILES_TO_UPDATE[@]}"; do
  src="$TMPDIR_WORK/$file"
  dst="$WORKING_DIR/$file"

  if [[ ! -f "$src" ]]; then
    echo -e "${YELLOW}  not in upstream: $file${RESET}"
    continue
  fi

  if [[ -f "$dst" ]] && diff -q "$src" "$dst" > /dev/null 2>&1; then
    (( ALREADY_CURRENT++ )) || true
    continue
  fi

  if [[ "$DRY_RUN" == true ]]; then
    if [[ -f "$dst" ]]; then
      echo -e "${CYAN}  would update: $file${RESET}"
      diff "$dst" "$src" || true
    else
      echo -e "${GREEN}  would add: $file${RESET}"
    fi
  else
    mkdir -p "$(dirname "$dst")"
    cp "$src" "$dst"
    if [[ -x "$src" ]]; then
      chmod +x "$dst"
    fi
    echo -e "${GREEN}  updated: $file${RESET}"
  fi
  (( CHANGED++ )) || true
done

# Sync terminal-adapters/
TERMINAL_ADAPTERS_UPSTREAM="$TMPDIR_WORK/terminal-adapters"
TERMINAL_ADAPTERS_LOCAL="$WORKING_DIR/terminal-adapters"
if [[ -d "$TERMINAL_ADAPTERS_UPSTREAM" ]]; then
  while IFS= read -r -d '' src; do
    rel="${src#$TERMINAL_ADAPTERS_UPSTREAM/}"
    dst="$TERMINAL_ADAPTERS_LOCAL/$rel"

    if [[ -f "$dst" ]] && diff -q "$src" "$dst" > /dev/null 2>&1; then
      (( ALREADY_CURRENT++ )) || true
      continue
    fi

    if [[ "$DRY_RUN" == true ]]; then
      if [[ -f "$dst" ]]; then
        echo -e "${CYAN}  would update: terminal-adapters/$rel${RESET}"
        diff "$dst" "$src" || true
      else
        echo -e "${GREEN}  would add: terminal-adapters/$rel${RESET}"
      fi
    else
      mkdir -p "$(dirname "$dst")"
      cp "$src" "$dst"
      [[ -x "$src" ]] && chmod +x "$dst"
      echo -e "${GREEN}  updated: terminal-adapters/$rel${RESET}"
    fi
    (( CHANGED++ )) || true
  done < <(find "$TERMINAL_ADAPTERS_UPSTREAM" -type f -print0)
fi

# Sync swarmforge-electron/ lib and renderer trees (skip node_modules and tests)
SF_ELECTRON_UPSTREAM="$TMPDIR_WORK/swarmforge-electron"
SF_ELECTRON_LOCAL="$WORKING_DIR/swarmforge-electron"
if [[ -d "$SF_ELECTRON_UPSTREAM" ]]; then
  while IFS= read -r -d '' src; do
    rel="${src#$SF_ELECTRON_UPSTREAM/}"
    dst="$SF_ELECTRON_LOCAL/$rel"

    if [[ -f "$dst" ]] && diff -q "$src" "$dst" > /dev/null 2>&1; then
      (( ALREADY_CURRENT++ )) || true
      continue
    fi

    if [[ "$DRY_RUN" == true ]]; then
      if [[ -f "$dst" ]]; then
        echo -e "${CYAN}  would update: swarmforge-electron/$rel${RESET}"
        diff "$dst" "$src" || true
      else
        echo -e "${GREEN}  would add: swarmforge-electron/$rel${RESET}"
      fi
    else
      mkdir -p "$(dirname "$dst")"
      cp "$src" "$dst"
      [[ -x "$src" ]] && chmod +x "$dst"
      echo -e "${GREEN}  updated: swarmforge-electron/$rel${RESET}"
    fi
    (( CHANGED++ )) || true
  done < <(find "$SF_ELECTRON_UPSTREAM" -not -path "*/node_modules/*" -not -path "*/tests/*" -type f -print0)
fi

echo ""
if [[ "$DRY_RUN" == true ]]; then
  echo -e "${BOLD}Dry run: $CHANGED file(s) would change, $ALREADY_CURRENT already up to date${RESET}"
else
  echo -e "${BOLD}Done: $CHANGED file(s) updated, $ALREADY_CURRENT already up to date${RESET}"
fi

if [[ "$INCLUDE_ROLE_PROMPTS" == false && ${#ROLE_PROMPT_FILES[@]} -gt 0 ]]; then
  echo -e "${YELLOW}Role prompts were not updated. Use --include-role-prompts if you haven't customized them.${RESET}"
fi
