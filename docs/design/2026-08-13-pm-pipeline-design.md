# PM as the Six-Pack Pipeline Entry — Design

**Date:** 2026-08-13
**Status:** Approved for implementation

**Changes:** [swarmforge/roles/PM.prompt](../../swarmforge/roles/PM.prompt), which today declares "the PM is launched by the user outside the swarm... do not add a `swarmforge.conf` window for it" and "there is no PM→swarm handoff channel." This design makes that wiring conditional rather than fixed, and gives the six-pack an in-swarm PM.

## Goal & Scope

Close the loop between program management and the pipeline. Today a six-pack slice starts when the user briefs the specifier and ends when the specifier merges; the tracker is updated by hand, and deciding what to build next happens outside any role. This design puts a PM window at the head of the six-pack, gives it the GitHub tracker as its instrument, and makes the slice loop self-sustaining between user decisions:

1. The PM briefs an issue and hands it to the specifier.
2. The specifier cuts a slice branch off trunk and runs it through the pipeline.
3. On QA completion the specifier opens a pull request and notifies the PM.
4. The PM waits for the merge, does tracker upkeep, and presents three candidate next slices with one recommended.
5. The user picks; the loop repeats.

Scope is the `six-pack` branch only. `two-pack` and `four-pack` keep the out-of-swarm PM they have today. A read-only dashboard joining tracker state to live pipeline state is in scope; writing to GitHub from that dashboard is not.

The user remains the decision-maker at two points — approving the specification, and picking the next slice — and remains the only party who merges a pull request.

## Architecture & Components

**Pipeline topology.** `swarmforge/swarmforge.conf` on `six-pack` becomes:

```text
window PM        claude pm
window specifier claude specifier
window coder     claude coder
window cleaner   claude cleaner
window architect claude architect
window hardender claude hardender batch
window QA        claude QA
```

Two changes: the PM gains a window at the head, and the specifier moves off the shared `master` checkout into its own worktree. Nothing is assigned `master` any more, so no two roles share a working tree — which is what makes an in-swarm PM safe. This needs no launcher change: `swarmforge.bb` creates `.worktrees/pm` on branch `swarmforge-pm`, provisions its inbox and outbox like any other role, and its only role-name restriction is that names may not contain underscores (swarmforge.bb:181), which `PM` satisfies.

**The PM as a handoff participant.** The PM stops being a special case. It runs `ready_for_next.sh` and `done_with_current.sh` on its own inbox and sends through `swarm_handoff.sh` like every other role. It keeps every ownership boundary it has today: documentation and tracker state only, never product code, specifications, tests, or another role's prompt; and it never merges a pull request.

**PM → specifier is a `git_handoff`.** The PM commits the brief in its own worktree and hands off with the slice's task name and that commit. The specifier's `merge_and_process PM <sha>` brings the brief into the slice branch, so the brief travels with the work and lands in the pull request as part of the diff. No new message type is needed, and `swarm_handoff.sh` already validates everything involved — `task` need only be non-blank and at most 80 characters (swarm_handoff.bb:214-217).

**specifier → PM is a `note`.** One line under 80 characters: `PR #57 open for 42-add-login`. The handoff rules forbid `note` unless the user, a role prompt, or the constitution explicitly authorises it; both prompts will carry that authorisation explicitly, so this stays inside the existing discipline rather than eroding it.

**`swarm_pr_wait.sh`** is the one new runtime helper. It polls `gh pr view <n> --json state,mergedAt` in a bounded sleep loop and prints `MERGED`, `CLOSED`, or `TIMEOUT`. It exists so that waiting for a merge costs one blocking shell call instead of one agent turn per check.

**The PM window is the user interface.** The user reviews the PM's three options and picks one by typing in the PM window, exactly as specifications are approved in the specifier window today. No new user-facing channel is introduced.

## Slice Lifecycle

```text
user ──pick option──▶ PM
  PM: write docs/briefs/42-add-login.md, mirror to issue #42, commit
      git_handoff → specifier   task: 42-add-login

specifier: git fetch origin main
           git switch -c slice/42-add-login origin/main
           merge_and_process PM <sha>
           ...Gherkin + end-to-end QA suite, user approval...
           git_handoff → coder → cleaner → architect → hardender → QA

QA: verification passes, priority-00 broadcast (unchanged)

specifier: merge_and_process QA <sha>
           git push -u origin slice/42-add-login
           gh pr create --base main --body "Closes #42"
           note → PM: "PR #57 open for 42-add-login"

PM: issue #42 → In Review, record the PR
    swarm_pr_wait.sh 57            (blocks until merged)
    close #42, tick the epic checklist, unblock "Blocked by #42",
    milestone and board upkeep, roadmap check
    present three options ──▶ user
```

**The slice branch is cut by the specifier, off trunk, before the pipeline runs.** On accepting the PM's handoff it fetches the base branch named in `.swarmforge/base-branch` and creates `slice/<task-name>` from `origin/<base>`. Downstream roles are unaffected: `merge_and_process` follows commits, not branches, so their long-lived `swarmforge-<role>` branches keep working exactly as they do now.

**QA's priority-00 broadcast is unchanged, and the PM is deliberately not on it.** The PM should wake when the pull request exists, not when QA finishes, because the intervening step — merge, push, open — belongs to the specifier and can fail.

**Handoff pacing reuses `swarm_status.sh --quiet`,** which already exits non-zero when anything is in flight. The PM runs it before releasing any slice; no new occupancy mechanism is introduced.

## Prompt & Configuration Changes

| File | Branch | Change |
|---|---|---|
| `swarmforge/roles/PM.prompt` | main | `## Runs Outside The Swarm` becomes `## Wiring`, decided at startup; new `## Next Slice Options`; thinner roadmap rules; briefs move to `docs/briefs/` |
| `.gitignore` | main | `docs/plans/` stays ignored; `docs/design/` and `docs/briefs/` are tracked |
| `swarmforge/scripts/swarm_pr_wait.{sh,bb}` | main | new blocking pull-request-state helper |
| `swarmforge/scripts/swarm_project.{sh,bb}` | main | tracker + pipeline state assembler |
| `swarmforge/scripts/swarm_web.{sh,bb}` | main | localhost dashboard server |
| `swarmforge/scripts/update-swarmforge.sh` | main | the comment at lines 130-131 now states something false |
| `README.md` | main | six-pack section gains the PM and the slice loop |
| `swarmforge/swarmforge.conf` | six-pack | PM window; specifier moved off `master` |
| `swarmforge/roles/specifier.prompt` | six-pack | cut the slice branch; open the pull request; notify the PM |
| `swarmforge/constitution/articles/local-workflow.prompt` | six-pack | slice and pull-request conventions |

**Why the PM prompt stays on `main` and becomes mode-aware.** `update-swarmforge.sh` syncs `main`'s `PM.prompt` into every project (line 132) and then overlays the pack's `roles/` directory (line 147), so a pack could ship its own PM prompt and win. That was rejected: roughly forty lines of backlog, board, and roadmap rules are pack-independent, and two copies of them will drift. Instead the prompt decides its own wiring from an observable fact:

> If `swarmforge/swarmforge.conf` has a `window PM` line, you are an in-swarm role: work in your assigned worktree and use `swarm_handoff.sh`, `ready_for_next.sh`, and `done_with_current.sh` like any other role. Otherwise you run outside the swarm: work in the main checkout on the base branch and hand off through the user.

Two smaller edits follow from the mode. "Do not notify any swarm role directly; there is no PM→swarm handoff channel" becomes "notify only the pipeline's entry role, and never a mid-pipeline role." And the brief is committed in the PM's own worktree rather than on the base branch, at the tracked path given under Document Paths.

**`## Next Slice Options`** makes the PM's closing move explicit: after a merge, present exactly three candidate slices, each with scope, why now, and what it unblocks; mark one recommended and say why; never start one without the user's pick. This is pack-independent PM behaviour, so it belongs on `main` alongside the rest.

**The six-pack article carries only what is genuinely pack-local**: branch naming `slice/<task-name>`, the task-naming convention below, `Closes #N` in every pull request body, and the never-squash-merge constraint.

## Tracker Model

**One source per fact, chosen by who writes it.** Facts GitHub owns natively and other tools write — issue state, pull request links, milestone completion, board status — live only in GitHub and are never mirrored into a document. Facts only the user and the PM author, which are prose and benefit from review — phase definitions, entry and exit triggers, economics, explicit non-goals — live in the repository, because editing a milestone description leaves no diff, no pull request, and no history, and this project's whole discipline is review gates.

**The join key is the task name, and it carries the issue number**: `42-add-login`. That name is already the only identifier that travels the whole pipeline — the PM invents it, every `git_handoff` forwards it in the `task:` header, `swarm_status.sh --task` routes by it, and the slice branch and pull request derive from it. Prefixing the issue number lets handoff, issue, branch, and pull request join on a string split, with no side file and no extra state to keep consistent.

**Milestones map to roadmap phases**, one milestone per phase, and every slice issue gets one. Milestone progress is therefore the release view, and the roadmap document is the prose behind it.

**The project board holds coarse status only** — `Backlog → Briefed → In Pipeline → In Review → Done`, plus priority and phase fields. It deliberately does not track which role currently holds a slice: the PM is blocked waiting on a merge for most of a slice's life and cannot keep a per-role field current, and a stale board is worse than a coarse one. Live stage is derived from the handoff files at read time, in the dashboard. That leaves three GitHub writes per slice: `Briefed` at handoff, `In Review` on the specifier's note, `Done` at merge.

**The roadmap document is thin.** It holds phases, triggers, economics, and non-goals, and nothing else — it never lists issues, never restates status, and never enumerates what is in a milestone. A phase links to its milestone by name, and the milestone answers "which issues". The current prompt's instruction to keep the roadmap, the board's roadmap view, and the backlog "consistent with each other" is the line that invites drift, and it is replaced by that division.

**Document paths.** `docs/plans/` is ignored by `.gitignore`, and deliberately so — commit `9a2f24d` purged plans from history. That is incompatible with a brief the PM has to commit and hand off, so tracked documentation gets its own paths, split by job:

- `docs/design/` — reviewed designs, including this one, which reach the repository through a pull request.
- `docs/briefs/<issue>-<slug>.md` — per-slice PM briefs, which travel with the slice and land in its pull request diff.
- `docs/roadmap/` — the thin roadmap described above.
- `docs/plans/` — unchanged: ignored local scratch.

This replaces the current PM prompt's brief location, `docs/plans/YYYY-MM-DD-<slug>-brief.md`, which cannot be committed at all while that directory is ignored.

**Bootstrap.** This repository has neither a `docs/roadmap/` nor a project board. The PM's first run reports both as missing and offers to create them; the existing rule that structural board changes are proposed before they are applied already governs this. Epics keep the `[epic]` title suffix and story checklist the prompt already defines.

## Dashboard

Two pack-independent scripts on `main`.

**`swarm_project.bb` — the assembler.** It shells out to `swarm_status.sh --json` for the pipeline half rather than reimplementing it, so `swarm_status` remains the single owner of pipeline facts. The tracker half is four `gh` calls: `issue list`, `pr list`, `api .../milestones`, and `api graphql` for Projects v2 items and field values, the last being the only awkward one since Projects v2 has no plain REST surface. It joins the two halves on the issue-number prefix of the task name. Default output is a terminal table; `--json` emits the state map.

**`swarm_web.bb` — the server.** Roughly forty lines of `org.httpkit.server`, which ships inside babashka (confirmed on v1.12.218), so there is no new dependency, no npm, and no build step. It binds to localhost and serves two routes: `GET /` returns an embedded HTML page, `GET /api/state` returns the assembler's JSON, and the page polls. The tracker half is cached for about thirty seconds because `gh` costs a second or two per call; the pipeline half is filesystem-cheap and refreshes on every poll.

The page renders milestone swimlanes of slice cards — issue, title, board status, live role from the handoff files, pull request state — above a pipeline occupancy strip. It is read-only, and every card links out to GitHub for anything actionable.

Deliberately out of scope for the first version: authentication, non-localhost binding, writes from the browser, and websockets. Polling is sufficient at this cadence and keeps the server trivial.

## Error Handling

**`gh` unavailable, unauthenticated, or rate-limited.** The dashboard still renders the pipeline half, with tracker columns marked unavailable. A dashboard that goes blank when offline is worse than a partial one. `swarm_project.sh --json` reports the tracker half as null with a reason rather than failing.

**Pull request closed without merging.** `swarm_pr_wait.sh` prints `CLOSED`; the PM reports it, returns the issue to `Backlog`, and does not present next-slice options until the user says how to proceed. The abandoned slice branch's commits remain in the downstream role worktrees and need manual cleanup — see Constraints.

**`swarm_pr_wait.sh` times out.** It prints `TIMEOUT` and exits non-zero; the PM reports that the pull request is still open and stops rather than looping indefinitely. Re-running the helper resumes the wait.

**Pipeline busy when a slice is released.** `swarm_status.sh --quiet` exits non-zero and the PM refuses the handoff, naming the in-flight task the new slice is waiting on.

**Specifier fails to push or open the pull request.** No note reaches the PM, so the PM stays idle rather than acting on a pull request that does not exist. The specifier reports the failure in its own window, which is where the user already watches for approval requests.

**Brief handoff arrives while the specifier has work in process.** Standard queue behaviour applies: `ready_for_next.sh` refuses to accept new work until the current task is completed, and the brief waits in `inbox/new/`.

## Constraints

**Slice pull requests must not be squash-merged.** Downstream roles work on long-lived `swarmforge-<role>` branches that accumulate every slice's history, and that history returns to the slice branch when the specifier merges QA's final commit. With merge commits, previously merged slice content is genuinely in trunk and contributes no diff. Squash-merging breaks that: the original commits stay absent from trunk and reappear in every later slice's pull request. The alternative — resetting every role branch to trunk at slice start — was considered and rejected as unnecessary given this constraint.

**An abandoned slice leaves orphan commits** in the role worktrees, which will surface in the next slice's pull request until those branches are reset by hand.

## Testing Plan

**Assembler.** `swarm_project.bb` is pure given fixture JSON — `swarm_status --json` output plus recorded `gh` responses — so its joining, grouping, and degraded-tracker behaviour are unit-testable. Tests go in `test/swarmforge/` under the existing `bb test` task.

**Server.** `swarm_web.bb` is tested by starting it on an ephemeral port, asserting that `/api/state` returns the assembler's map and that `/` returns HTML, then stopping it.

**`swarm_pr_wait.sh`.** Tested against a stubbed `gh` on `PATH` that returns open, then merged; and separately closed; and a case that never merges, to assert the timeout path.

**Prompt changes** are not unit-testable. They are verified by running one real slice end to end on this repository, which is the dogfooding path the project already uses: brief an issue, watch it through the pipeline, confirm the pull request opens with `Closes #N` and the brief in its diff, merge it, and confirm the PM closes the issue and presents three options.
