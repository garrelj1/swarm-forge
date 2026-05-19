# SwarmForge Web UI Design

## Problem

Managing 4+ tmux windows to monitor and control swarm agents is unwieldy. Agents can also get stuck on permission prompts that require a typed response, which currently requires switching to the right terminal window.

## Solution

A Rust/Axum web server (`swarmforge-ui`) launched automatically by `swarmforge.sh` that provides a single browser-based dashboard showing all agent terminals and inter-agent messages, with per-agent keystroke relay for responding to permission prompts.

---

## Architecture

```
swarmforge.sh
  ├── launches tmux sessions (existing)
  ├── runs `tmux pipe-pane` on each session → .swarmforge/panes/<role>.log
  └── launches swarmforge-ui --port 7777 --working-dir <path>

swarmforge-ui (axum)
  ├── GET  /               → serves index.html
  ├── GET  /events         → SSE stream (pane output + message log)
  └── POST /send/<role>    → tmux send-keys to that role's session
```

The server reads `sessions.tsv` at startup to discover roles and their tmux sessions. It tails `.swarmforge/panes/<role>.log` per agent and `logs/agent_messages.log` for inter-agent messages, multiplexing both into a single SSE stream tagged by source.

The binary is optional — if not present, `swarmforge.sh` continues to work as before.

---

## Frontend Layout

Single HTML page, no build step. xterm.js and its fit addon loaded from CDN.

```
┌─────────────────────────────────────────────────────┐
│  SwarmForge                              [port 7777] │
├───────────────┬───────────────┬─────────────────────┤
│  Specifier    │  Coder        │  Messages           │
│  ┌─────────┐  │  ┌─────────┐  │  ┌───────────────┐  │
│  │ xterm   │  │  │ xterm   │  │  │ scrollable    │  │
│  │         │  │  │         │  │  │ log of inter- │  │
│  └─────────┘  │  └─────────┘  │  │ agent msgs    │  │
│  [input + ↵]  │  [input + ↵]  │  └───────────────┘  │
├───────────────┴───────────────┤                     │
│  Refactorer   │  Architect    │                     │
│  ┌─────────┐  │  ┌─────────┐  │                     │
│  │ xterm   │  │  │ xterm   │  │                     │
│  └─────────┘  │  └─────────┘  │                     │
│  [input + ↵]  │  [input + ↵]  │                     │
└───────────────┴───────────────┴─────────────────────┘
```

Agent panels are generated dynamically from the SSE `roles` event — no hardcoded role names. Each panel has a text input and a standalone Enter button for responding to prompts without additional text. The Messages panel auto-scrolls to bottom unless the user has scrolled up.

---

## SSE Stream Format

```
event: roles
data: ["specifier","coder","refactorer","architect"]

event: pane
data: {"role":"coder","text":"\r\nRunning tests...\r\n"}

event: message
data: {"from":"coder","to":"refactorer","text":"done, branch swarmforge-coder"}
```

- `roles` fires once on connect so the frontend can create panels
- `pane` carries raw terminal escape sequences; xterm.js renders them
- `message` comes from tailing `logs/agent_messages.log`

Server-side: each log file is tailed by a dedicated `tokio` async task (seek to end on open, poll for new bytes every 200ms). Tasks fan into a `tokio::sync::broadcast` channel; the SSE handler subscribes and forwards.

---

## Keystroke Relay

```
POST /send/:role
Body: { "keys": "y" }
```

Handler runs:
```
tmux send-keys -t <session>:0.0 -l -- "<keys>"
```

The frontend sends raw text from the input box. The standalone Enter button POSTs an empty body, and the handler sends `Enter` only — for confirming prompts without additional input.

---

## swarmforge.sh Changes

### 1. pipe-pane per agent

After each agent session is launched:
```bash
tmux pipe-pane -t "${session}:${display}.0" -o "cat >> '$STATE_DIR/panes/${role}.log'"
```

`-o` captures stdout only (no stdin echo). Files land in `.swarmforge/panes/` which is already gitignored.

### 2. Launch swarmforge-ui

After all agents are started, before opening Terminal windows:
```bash
if [[ -x "$SCRIPT_DIR/swarmforge-ui" ]]; then
  "$SCRIPT_DIR/swarmforge-ui" --port 7777 --working-dir "$WORKING_DIR" &
  echo -e "${GREEN}SwarmForge UI: http://localhost:7777${RESET}"
  if has_command osascript; then
    open "http://localhost:7777"
  fi
fi
```

### 3. Cleanup

`swarm-cleanup.sh` kills the `swarmforge-ui` process when the swarm shuts down.

---

## Out of Scope

- Full PTY proxy / interactive terminal (fall back to `tmux attach` when needed)
- Auth / multi-user access
- Mobile layout
