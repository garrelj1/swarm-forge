# Upstream Merge Guide

This fork tracks `unclebob/swarm-forge` (`upstream` remote). This guide documents what to preserve vs. take from upstream during each merge.

## Fork Invariants

These are local customizations that must be kept on every merge. Upstream does not have them and will always try to overwrite or delete them.

### All branches (`main`, `four-pack`, `six-pack`)

| File | What to keep |
|------|-------------|
| `swarm` | `ARCHIVE_URL` must point to `garrelj1/swarm-forge`, not `unclebob/swarm-forge`. Upstream always reverts this. Take the rest of upstream's `swarm` changes (the bootstrap logic evolves). |
| `swarmforge/swarmforge.conf` | Agent backend must be `claude`, not `codex`. This usually auto-merges correctly. |

### `main` branch only

| File | What to keep |
|------|-------------|
| `swarmforge-electron/` | Entire Electron UI directory. Upstream deleted it. Always restore from HEAD. |
| `examples/clojureHTW/` | Example swarm config. Upstream deleted it. Always restore from HEAD. |
| `swarmforge/scripts/update-swarmforge.sh` | Fork's update script. Upstream doesn't have it. Restore from HEAD if deleted. |
| `README.md` | "Examples" and "Electron UI" sections at the bottom. Upstream ends the file earlier. Keep our additions. |
| `.gitignore` | Extra entries (`docs/plans/`, `node_modules/`, `logbook.json`). Upstream drops them. Keep ours. |

### `four-pack` / `six-pack` branches

| File | What to keep |
|------|-------------|
| `swarmforge/constitution/articles/stack.prompt` | Our generic stack template. Upstream doesn't have it. Upstream moves the articles directory; make sure `stack.prompt` lands in `articles/`. |
| `swarmforge/constitution/articles/project.prompt` | After merge, the language will say `Babashka`. Change to the project language or leave as a placeholder. |

## Merge Procedure

```sh
git fetch upstream
```

### Pack branches (`four-pack`, `six-pack`)

These usually have one conflict: `swarmforge/constitution.prompt`, because upstream changed from a numbered list to `Read and obey every file in swarmforge/constitution/articles/`. Take upstream's version.

```sh
# In the four-pack worktree
git merge upstream/four-pack --no-commit
# Resolve constitution.prompt: take upstream's articles-directory form
# Then commit
git commit
```

The `swarm` and `swarmforge.conf` files auto-merge correctly (our garrelj1 URL and claude backend win).

Upstream reorganizes the `constitution/` directory significantly over time:
- `project.prompt` moves to `constitution/articles/project.prompt`
- `engineering.prompt`, `workflow.prompt` are deleted (now shared articles from `main`)
- Our `stack.prompt` must be moved to `constitution/articles/stack.prompt`

### `main` branch (via `merge-upstream`)

Use the `merge-upstream` branch as a staging area so the merge can be reviewed as a PR before landing on `main`.

```sh
# In the merge-upstream worktree
git merge upstream/main --no-commit
```

Expected conflicts:

**`.gitignore`** — Take upstream's base, keep our extra entries (`docs/plans/`, `node_modules/`, `logbook.json`).

**`README.md`** — Upstream ends the file after the "Window Behavior" section. Keep our Examples and Electron UI sections.

**`swarmforge/scripts/swarmforge.sh`** — The most complex conflict. Common patterns:

- Variable declarations: upstream adds new state vars (`ROLES_FILE`, `DAEMON_DIR`, etc.). Take upstream's additions; drop any local vars that are no longer used.
- `setup_project_excludes()`: keep `.swarmforge/`, `.worktrees/`, and `CLAUDE.md` in the pattern list. Upstream trims this; we keep `CLAUDE.md`.
- `prepare_workspace()`: take upstream's version (it adds new directories like `NOTIFY_DIR`, `DAEMON_DIR`).
- When upstream refactors worktree setup into new functions (`prepare_handoff_dirs`, `sync_worktree_scripts`), take the upstream functions and drop our inline code that did the same thing.

After resolving, restore deleted fork content:

```sh
git checkout HEAD -- examples/clojureHTW/
git checkout HEAD -- swarmforge-electron/
git checkout HEAD -- swarmforge/scripts/update-swarmforge.sh
```

Then stage and commit, push `merge-upstream`, and open a PR into `main`.

## Keeping Packs Generic

Upstream sets `Project language: Babashka` in `project.prompt`. This fork keeps it as a placeholder so the packs work for any language:

```
- Project language: <language, e.g. Go>
```

After merging a pack branch, check `swarmforge/constitution/articles/project.prompt` and restore the placeholder if upstream reset it to `Babashka`.

The APS tooling references (`gherkin-parser`, `gherkin-mutator`) are intentionally Go-based — the Acceptance Pipeline Specification is written in Go and Babashka. Those references are correct regardless of target project language; leave them as-is.

## New Upstream Branches

Upstream occasionally adds new branches (e.g., `adversaries`, `handoff-protocol`). Evaluate each one before merging. They may be experimental or not yet stable for use in this fork.
