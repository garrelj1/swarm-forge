#!/usr/bin/env zsh
# Combine all SwarmForge role sessions into one tiled terminal view.
# Auto-detects the project's dedicated tmux socket and session list, then
# builds a single "dashboard" session whose window has one pane per role,
# each nested-attached to its live role session.
#
# Usage: swarm-dashboard.sh [working-dir]   (defaults to $PWD)
set -euo pipefail

WORKING_DIR="${1:-$PWD}"
WORKING_DIR="$(cd "$WORKING_DIR" && pwd)"
STATE_DIR="$WORKING_DIR/.swarmforge"
SOCKET_FILE="$STATE_DIR/tmux-socket"
SESSIONS_FILE="$STATE_DIR/sessions.tsv"

[[ -r "$SOCKET_FILE"   ]] || { echo "No SwarmForge socket file at $SOCKET_FILE (is the swarm running?)" >&2; exit 1; }
[[ -r "$SESSIONS_FILE" ]] || { echo "No sessions file at $SESSIONS_FILE" >&2; exit 1; }

SOCKET="$(<"$SOCKET_FILE")"
# Probe with list-sessions, not `info`: `tmux info` needs a current client and
# exits non-zero ("no current client") when run from outside tmux, even though
# the server is fully up. list-sessions only needs the server to be running.
tmux -S "$SOCKET" list-sessions >/dev/null 2>&1 || { echo "SwarmForge tmux server not running on $SOCKET" >&2; exit 1; }

# Collect the role sessions that are actually alive (col 3 = session, col 4 = display).
typeset -a SESSIONS DISPLAYS
while IFS=$'\t' read -r _idx _role session display _agent; do
  [[ -n "$session" ]] || continue
  if tmux -S "$SOCKET" has-session -t "$session" 2>/dev/null; then
    SESSIONS+=("$session")
    DISPLAYS+=("$display")
  fi
done < "$SESSIONS_FILE"

(( ${#SESSIONS} > 0 )) || { echo "No live SwarmForge sessions found." >&2; exit 1; }

DASH="swarmforge-$(basename "$WORKING_DIR")-dashboard"
tmux -S "$SOCKET" kill-session -t "$DASH" 2>/dev/null || true

# Nested attach on the same server requires clearing $TMUX in the pane.
attach_cmd() { echo "TMUX= exec tmux -S '$SOCKET' attach-session -t '$1'"; }

# Place pane index $1 (1-based into SESSIONS/DISPLAYS) into tmux pane id $2.
label_pane() {
  tmux -S "$SOCKET" select-pane -t "$2" -T "${DISPLAYS[$1]}" >/dev/null 2>&1 || true
}

typeset -i N=${#SESSIONS}
typeset -i COLS=${SWARM_DASH_COLS:-3}
(( COLS < 1 )) && COLS=1
typeset -i ROWS=$(( (N + COLS - 1) / COLS ))

# Equal-split percentage for the j-th split (1-based) of a line of K panes.
split_pct() { echo $(( 100 * ($1 - $2) / ($1 - $2 + 1) )); }

# First pane = row 1, col 1.
tmux -S "$SOCKET" new-session -d -s "$DASH" -n swarm "$(attach_cmd "${SESSIONS[1]}")"
typeset -a ROWSTART
ROWSTART[1]="$(tmux -S "$SOCKET" display-message -p -t "$DASH" '#{pane_id}')"
label_pane 1 "${ROWSTART[1]}"

# Build the left column: one pane per row, stacked vertically and equalized.
typeset -i r c idx pct
for (( r=2; r<=ROWS; r++ )); do
  idx=$(( (r-1)*COLS + 1 ))
  pct=$(split_pct "$ROWS" $(( r-1 )))
  ROWSTART[$r]="$(tmux -S "$SOCKET" split-window -v -t "${ROWSTART[$((r-1))]}" -l "${pct}%" \
    -P -F '#{pane_id}' "$(attach_cmd "${SESSIONS[$idx]}")")"
  label_pane "$idx" "${ROWSTART[$r]}"
done

# Fill each row out to COLS columns (or fewer for a short final row).
typeset rowcols prev newid base
for (( r=1; r<=ROWS; r++ )); do
  base=$(( (r-1)*COLS + 1 ))
  rowcols=$(( N - base + 1 )); (( rowcols > COLS )) && rowcols=$COLS
  prev="${ROWSTART[$r]}"
  for (( c=2; c<=rowcols; c++ )); do
    idx=$(( base + c - 1 ))
    pct=$(split_pct "$rowcols" $(( c-1 )))
    newid="$(tmux -S "$SOCKET" split-window -h -t "$prev" -l "${pct}%" \
      -P -F '#{pane_id}' "$(attach_cmd "${SESSIONS[$idx]}")")"
    label_pane "$idx" "$newid"
    prev="$newid"
  done
done

# Show role names (pane titles) in the pane borders; mouse on for click-to-focus.
tmux -S "$SOCKET" set-option -t "$DASH" pane-border-status top >/dev/null 2>&1 || true
tmux -S "$SOCKET" set-option -t "$DASH" pane-border-format ' #{pane_title} ' >/dev/null 2>&1 || true
tmux -S "$SOCKET" set-option -t "$DASH" mouse on >/dev/null 2>&1 || true
# Ctrl-b m toggles mouse: off = wheel scrolls the focused agent natively; on = click-to-focus.
tmux -S "$SOCKET" bind-key m set-option -g mouse \; \
  display-message 'mouse #{?mouse,on (click panes),off (scroll agents)}' >/dev/null 2>&1 || true

echo "Dashboard '$DASH' built with ${#SESSIONS} panes: ${DISPLAYS[*]}"
if [[ -n "${TMUX:-}" ]]; then
  echo "You're inside tmux; switch with:  tmux -S '$SOCKET' switch-client -t '$DASH'"
else
  tmux -S "$SOCKET" attach-session -t "$DASH"
fi
