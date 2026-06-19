# Upstream Merge Guide

This fork tracks `unclebob/swarm-forge` (`upstream` remote). It exists to provide
three things upstream does not. **Every merge from upstream must preserve all
three.** They are the lens for resolving any conflict: when upstream's change
would weaken a goal, keep the fork's behavior; otherwise take upstream's.

## Durable Goals (the invariants every merge must preserve)

1. **Generic swarm template.** The framework ships project-agnostic. No project
   language, stack, or app specifics are baked into framework files. Per-project
   config — `swarmforge/swarmforge.conf`, `swarmforge/constitution/articles/project.prompt`
   (the language), `swarmforge/constitution/articles/stack.prompt` — is owned by the downstream project
   and must never be overwritten by a merge or by `update-swarmforge.sh`.

2. **Isolated single-swarm operation.** One swarm runs cleanly in one project
   directory, with *all* runtime state under that directory: `.swarmforge/`,
   `.worktrees/`, a per-directory tmux socket, and a per-directory handoff daemon.
   Nothing a swarm writes or kills may reach outside its working directory.

3. **Concurrent multi-swarm operation.** Two or more swarms run on the same
   machine at once without colliding. This requires:
   - **distinct tmux session names** per swarm,
   - **distinct agent-CLI session names** (`-n` / `--name`) per swarm,
   - a handoff daemon that **only ever signals its own project's processes** and
     never kills a stale/recycled PID belonging to another swarm.

If a merge cannot satisfy all three, stop and resolve by hand — do not "take
upstream" on a file listed under Fork Invariants without re-checking the goal.

## How upstream threatens each goal

- **Generic template:** upstream sets `Project language: Babashka` in
  `project.prompt`; deletes `examples/`.
- **Isolation:** largely intact upstream (state is working-dir scoped). Watch for
  any new machine-global path (anything under `/tmp`, `$HOME`, or a fixed socket
  name that isn't keyed by the working directory).
- **Multi-swarm:** upstream's Babashka rewrite (`swarmforge.bb`) **regressed all
  three multi-swarm requirements** and they must be re-applied after any merge
  that touches `swarmforge.bb`, `handoffd.bb`, or `swarm-cleanup.sh`:
  - hardcodes `(def session-prefix "swarmforge")` → identical session names for
    every swarm;
  - hardcodes agent names as `"SwarmForge <display>"` → identical agent-CLI
    sessions across swarms;
  - starts the daemon with bare `process/process` (no `nohup`) → the daemon dies
    on terminal close and orphans a stale pid-file;
  - kills the daemon by raw PID with no identity check → a swarm restart can
    `kill -TERM` a recycled PID belonging to another swarm.

## Fork Invariants (per-file checklist)

### All branches (`main`, `four-pack`, `six-pack`)

| File | What to keep | Goal |
|------|--------------|------|
| `swarm` | `ARCHIVE_URL` points to `garrelj1/swarm-forge`, not `unclebob/swarm-forge`. Take the rest of upstream's bootstrap changes. | — |
| `swarmforge/swarmforge.conf` | Agent backend `claude`, not `codex`. Usually auto-merges. | 1 |
| `swarmforge/scripts/swarmforge.bb` | **Multi-swarm naming.** Session names and agent `-n`/`--name` must be scoped by `(:instance ctx)` (see `instance-tag`: working-dir basename by default, `$SWARMFORGE_INSTANCE` override, sanitized for tmux). Upstream resets these to the constant `"swarmforge"` / `"SwarmForge <display>"`. | 3 |
| `swarmforge/scripts/swarmforge.bb` | **Daemon hardening.** `stop-handoff-daemon!` must verify via `live-handoffd?` (`ps -p <pid> -o args=` contains `handoffd.bb` **and** this working-dir) before `kill`, and clear a stale pid-file otherwise. `start-handoff-daemon!` must launch under `nohup`. | 2, 3 |
| `swarmforge/scripts/handoffd.bb` | **Daemon singleton.** `claim-pid-file!` must refuse to start if a live handoffd already owns this project, and clear a stale pid-file otherwise. Upstream's `-main` overwrites the pid-file blindly. | 2, 3 |
| `swarmforge/scripts/swarm-cleanup.sh` | **Identity-checked kill.** Only `kill -TERM` the daemon PID when `ps` shows it is this project's `handoffd.bb`. Upstream kills the raw PID from the file. | 3 |
| `swarmforge/scripts/update-swarmforge.sh` | Fork's downstream updater. Must **bootstrap, never overwrite** project-specific files (`swarmforge.conf`, `constitution/articles/project.prompt`, `constitution/articles/stack.prompt`). Upstream doesn't have this script. | 1 |
| `swarmforge/constitution/articles/stack.prompt` | Generic stack template, lives in `articles/` on **all** branches (canonical path). Bootstrap-only via `update-swarmforge.sh`. | 1 |

### `main` branch only

| File | What to keep | Goal |
|------|--------------|------|
| `examples/clojureHTW/` | Example swarm config. Upstream deleted it. Restore from HEAD. | 1 |
| `swarmforge/scripts/swarm-dashboard.sh` | Fork's tiled tmux dashboard. Upstream doesn't have it; it rides along in the `scripts/` sync. Restore from HEAD if a merge drops it. | — |
| `README.md` | "Examples" and "Dashboard" sections at the bottom. Keep our additions. | — |
| `.gitignore` | Extra entries (`docs/plans/`, `node_modules/`, `logbook.json`). Keep ours. | — |

### `four-pack` / `six-pack` branches

| File | What to keep | Goal |
|------|--------------|------|
| `swarmforge/constitution/articles/project.prompt` | After merge the language reads `Babashka`. Reset to the placeholder `- Project language: <language, e.g. Go>`. (`stack.prompt` lives here too — see the all-branches row.) | 1 |

## Merge Procedure

```sh
git fetch upstream
```

### Pack branches (`four-pack`, `six-pack`)

Usual single conflict: `swarmforge/constitution.prompt` (upstream switched to
`Read and obey every file in swarmforge/constitution/articles/`). Take upstream's
version.

```sh
git merge upstream/four-pack --no-commit
# Resolve constitution.prompt: take upstream's articles-directory form
git commit
```

`swarm` and `swarmforge.conf` auto-merge (our `garrelj1` URL and `claude` backend
win). Upstream reorganizes `constitution/` over time: `project.prompt` moves into
`articles/`, `engineering.prompt`/`workflow.prompt` become shared articles, and
our `stack.prompt` must end up in `articles/`. After merging, **restore the
`project.prompt` placeholder** (Goal 1).

### `main` branch (via `merge-upstream`)

Use the `merge-upstream` branch as a staging area so the merge lands on `main` via
a reviewable PR.

```sh
git merge upstream/main --no-commit
```

Expected conflicts:

- **`.gitignore`** — take upstream's base, keep our extra entries.
- **`README.md`** — keep our Examples and Dashboard sections.
- **`swarmforge/scripts/swarmforge.sh`** — when upstream migrates the launcher to
  Babashka this becomes the thin wrapper `exec bb "$SCRIPT_DIR/swarmforge.bb"`.
  Take upstream's wrapper; the real logic lives in `swarmforge.bb`.

Restore deleted fork content:

```sh
git checkout HEAD -- examples/clojureHTW/
git checkout HEAD -- swarmforge/scripts/update-swarmforge.sh
```

### Verification (run before committing any merge)

The Fork Invariants above are the things upstream silently reverts. After
resolving, confirm each goal still holds:

```sh
# Goal 3 — naming is instance-scoped (must print two DIFFERENT session names):
bb swarmforge/scripts/swarmforge.bb --test-launch-command "$PWD" claude \
  | grep -o "-n 'SwarmForge[^']*'"
SWARMFORGE_INSTANCE=alpha bb swarmforge/scripts/swarmforge.bb --test-parse "$PWD" | grep swarmforge-
SWARMFORGE_INSTANCE=beta  bb swarmforge/scripts/swarmforge.bb --test-parse "$PWD" | grep swarmforge-

# Goals 2 & 3 — daemon identity guards still present:
grep -q 'live-handoffd?' swarmforge/scripts/swarmforge.bb
grep -q 'claim-pid-file!' swarmforge/scripts/handoffd.bb
grep -q 'nohup' swarmforge/scripts/swarmforge.bb
grep -q 'handoffd.bb\*' swarmforge/scripts/swarm-cleanup.sh

# All goals — the regression test suite must be green:
bb test
```

`bb test` includes targeted guards for the multi-swarm invariants
(`swarmforge-namespaces-sessions-by-instance`, `swarm-cleanup-spares-foreign-pids`).
If those fail after a merge, a Goal-3 invariant was reverted — re-apply it from
the checklist before committing.

Then stage, commit, push `merge-upstream`, and open a PR into `main`.

## Keeping Packs Generic (Goal 1 detail)

Upstream sets `Project language: Babashka` in `project.prompt`. Keep it a
placeholder so the packs work for any language:

```
- Project language: <language, e.g. Go>
```

The APS tooling references (`gherkin-parser`, `gherkin-mutator`) are intentionally
Go/Babashka-based — the Acceptance Pipeline Specification is written in Go and
Babashka. They are correct regardless of target language; leave them as-is.

## Running Multiple Swarms (Goal 3 detail)

Each swarm is isolated by its **working directory**: the tmux socket is keyed by a
CRC of the absolute path (`/tmp/swarmforge-<user>/<crc>.sock`), and all state lives
under `<dir>/.swarmforge`. To run two swarms, give each its own checkout/working
directory — they will not collide.

Session and agent names are additionally scoped by `instance-tag` (working-dir
basename by default). To run swarms whose directories share a basename, or to label
a swarm explicitly, set `SWARMFORGE_INSTANCE` before launching:

```sh
SWARMFORGE_INSTANCE=api  ./swarm   # sessions: swarmforge-api-<role>
SWARMFORGE_INSTANCE=web  ./swarm   # sessions: swarmforge-web-<role>
```

Running two swarms inside the *same* working directory is not supported — they
would share `.swarmforge/`, the socket, and the daemon. Use separate directories.

## New Upstream Branches

Upstream occasionally adds branches (e.g. `adversaries`, `handoff-protocol`).
Evaluate each before merging; they may be experimental or not yet stable for this
fork.
