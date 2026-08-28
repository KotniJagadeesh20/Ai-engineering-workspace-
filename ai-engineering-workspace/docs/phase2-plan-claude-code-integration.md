# Phase 2 Plan — Coding Agent via Claude Code Integration

**Status: PLANNED, not yet implemented.** This is the design for Phase 2,
written before any code exists for it. Phase 1 (auth, GitHub OAuth, repo
connect, RAG indexing/query) is built but **not yet run end-to-end** — see
the "Validation dependency" section before starting on any of this.

---

## 1. What changed from the original roadmap

The original 7-phase roadmap scoped Phase 2 as: *"sandboxed file editing,
test running, diff review — build a custom coding agent, no Git writes yet."*

That plan assumed building the agent loop (plan → act → observe → repeat)
from scratch, most likely with LangGraph. After discussion, the decision is
to **not build a custom agent loop** and instead integrate **Claude Code's
Agent SDK**, run headlessly (`claude -p`), as the actual coding engine.

**Why this change, plainly stated:**
- The agent loop is the single hardest, highest-risk piece of the entire
  roadmap — reinventing it means solving an already-solved problem.
- Claude Code's headless mode already provides: the full agentic loop, file
  read/edit/list tools, shell execution, test iteration, and structured
  (JSON) output — exactly what a hand-built LangGraph agent would have had
  to reproduce.
- This is a conscious product tradeoff, not a free win: it shifts this
  project from "we built a differentiated coding agent" toward "we built a
  strong platform/orchestration layer around Anthropic's agent." That's an
  acceptable tradeoff for a solo build that wants working software sooner,
  but it's worth naming explicitly rather than treating as incidental.
- **Not building "let the user choose their agent"** (Claude Code vs Codex
  vs custom) either, for now — same reasoning as everywhere else in this
  project: don't build an abstraction with only one real consumer behind
  it. The integration is written behind one narrow function so a second
  provider could be added later without a rewrite, but multi-provider
  support itself is explicitly out of scope until there's a real second
  need for it.

**What's still true from the original scope:** no Git writes in Phase 2.
Claude Code can edit files inside its sandbox, but nothing gets pushed,
branched, or opened as a PR until Phase 3's human-approval-gated flow picks
up the resulting diff. That boundary hasn't moved.

---

## 2. Validation dependency — read before building

Phase 1 has been reviewed carefully (multiple security passes, static
checks) but **never actually run** — no `docker compose up`, no real
Postgres, no confirmed working RAG query against a real repo. Phase 2 leans
directly on Phase 1's plumbing:

- The shared Docker volume (`repo-data`) that Java clones into
- The `Repo` entity and its `connectedByUserId`/ownership model
- The auth flow that determines who's allowed to trigger a coding task at
  all

If any of that has an undiscovered bug, Phase 2 will be built on top of it
blind. The recommended path is validating Phase 1 via a cloud dev
environment (GitHub Codespaces or similar) that has Docker available,
rather than requiring a working local laptop. **This document can be
designed and reviewed without that validation happening first — but nothing
here should be treated as tested until Phase 1 has actually been run.**

---

## 3. Architecture — where Phase 2 sits

```
                         User
                          │
                          ▼
                  Java (unchanged role)
          auth · ownership checks · task dispatch
                          │
                          ▼
                Python (NEW: task orchestration)
                          │
              ┌───────────┴───────────┐
              │                       │
      Existing (Phase 1)       NEW (Phase 2)
      RAG indexing/query       Coding task orchestration
              │                       │
              ▼                       ▼
      pgvector (Postgres)     Sandboxed container
                               │
                               ▼
                        Claude Code (headless, -p)
                        operating on an ISOLATED
                        COPY of the repo checkout
                               │
                               ▼
                        Diff + result (JSON)
                               │
                               ▼
                    Stored, surfaced to Java/frontend
                    for human review (Phase 3 picks up
                    from here - no auto Git writes)
```

**Key architectural decision, correcting an earlier draft of this project's
docs:** the coding agent does **not** operate directly on the shared master
checkout that Java clones into. Two concurrent coding tasks editing the same
checkout would race. Instead, each task gets its own **isolated copy** (a
`git worktree` or a plain directory copy) carved out from the shared volume,
edited inside a throwaway container, and discarded once the diff is
captured. This was flagged as a correction during Phase 1's review and
applies directly here.

---

## 4. What Phase 1 already provides — reused, not rebuilt

| Phase 1 piece | How Phase 2 reuses it |
|---|---|
| `Repo` entity + shared Docker volume | The canonical checkout a task's isolated copy is carved from |
| `RepoRepository.findByIdAndConnectedByUserId` | Same ownership check, applied before anyone can trigger a coding task on a repo |
| JWT auth (`JwtAuthFilter`, `CurrentUserUtil`) | Unchanged — the same Bearer-token flow gates the new task-trigger endpoint |
| `INTERNAL_SERVICE_SECRET` / `/internal/**` pattern | Reused for whatever callback Python needs to report task completion back to Java, same shape as the existing indexing-status callback |
| Docker Compose / shared volume design | Extended, not replaced — Phase 2 needs one more thing mounted (see section 6) |
| `IndexStatus` enum pattern | Same pattern reused for a new `TaskStatus` enum (PENDING/RUNNING/READY_FOR_REVIEW/FAILED) |

Nothing in Phase 1's auth, ownership, or repo-connect logic needs to change
for Phase 2 — it's additive.

---

## 5. New components

### 5.1 Java side (small additions)

**New entity: `CodingTask`**
```java
@Entity
@Table(name = "coding_tasks")
public class CodingTask {
    @Id @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID repoId;

    @Column(nullable = false)
    private UUID requestedByUserId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String taskDescription;   // the prompt given to Claude Code

    @Enumerated(EnumType.STRING)
    private TaskStatus status;        // PENDING, RUNNING, READY_FOR_REVIEW, FAILED

    @Column(columnDefinition = "TEXT")
    private String resultDiff;        // populated once Claude Code finishes

    @Column(columnDefinition = "TEXT")
    private String resultSummary;     // Claude Code's own summary of what it did

    private Instant createdAt;
    private Instant completedAt;
}
```

**New controller endpoints** (same ownership-check pattern as `RepoController`):
```
POST /api/repos/{id}/tasks          - create a coding task, hand off to Python
GET  /api/repos/{id}/tasks/{taskId} - poll status + result
```

**New internal callback** (same pattern as `/internal/repos/{id}/index-status`):
```
PATCH /internal/tasks/{taskId}/result   - Python reports completion, gated
                                           by the same X-Internal-Secret check
```

### 5.2 Python side (the real new work)

**New service: `coding_agent_service.py`**
```python
import subprocess, json, shutil, uuid

def run_coding_task(repo_path: str, task_description: str) -> dict:
    """
    Carves an isolated worktree out of the shared repo checkout, runs Claude
    Code headlessly inside it, captures the resulting diff, and cleans up.
    Returns a dict matching CodingTask's result fields.
    """
    task_workspace = f"/workspace/tasks/{uuid.uuid4()}"

    # Isolated copy, not the shared master checkout - see architecture note
    # in section 3. A git worktree is preferable to a plain copy once repos
    # are large (shares history, avoids duplicating the whole .git dir).
    subprocess.run(["git", "worktree", "add", task_workspace], cwd=repo_path, check=True)

    try:
        result = subprocess.run(
            [
                "claude", "-p", task_description,
                "--output-format", "json",
                "--allowedTools", "Read,Edit,Bash",
                "--cwd", task_workspace,
                # NOT --dangerously-skip-permissions in a first pass - start
                # conservative, revisit only once the flow is trusted.
            ],
            capture_output=True, text=True, timeout=600,
        )
        parsed = json.loads(result.stdout)

        diff = subprocess.run(
            ["git", "diff"], cwd=task_workspace, capture_output=True, text=True
        ).stdout

        return {
            "status": "READY_FOR_REVIEW" if result.returncode == 0 else "FAILED",
            "resultDiff": diff,
            "resultSummary": parsed.get("result", ""),
        }
    finally:
        subprocess.run(["git", "worktree", "remove", task_workspace, "--force"], cwd=repo_path)
```

**New router: `task_router.py`**
```python
@router.post("/tasks/run")
async def run_task(request: TaskRequest, background_tasks: BackgroundTasks):
    background_tasks.add_task(execute_and_report, request.repo_id,
                               request.repo_path, request.task_description)
    return {"status": "accepted"}
```

Same `BackgroundTasks` + internal-callback pattern already used for indexing
in Phase 1 — nothing structurally new there, just a different piece of work
running in the background.

---

## 6. Docker Compose changes

The sandbox Claude Code runs in needs:
- Access to the same `repo-data` volume (for `git worktree add` to work
  against the shared checkout)
- Its own `ANTHROPIC_API_KEY` (same key already used for RAG queries -
  reused, not duplicated)
- The `claude` CLI installed in the Python service's image

```dockerfile
# python-service/Dockerfile - additions
RUN npm install -g @anthropic-ai/claude-code
```

**Open question, not resolved in this doc:** whether Claude Code itself runs
inside the existing `python-service` container, or gets its own dedicated
short-lived container per task (closer to true sandboxing - a bug or runaway
process in one task's Claude Code session can't affect the long-running
Python service). The per-task-container approach is safer and is the
better long-term answer, but adds real complexity (spinning up containers
from within a container needs Docker-in-Docker or access to the host Docker
socket, which is its own security consideration). **Recommendation: start
with Claude Code running inside the existing python-service container,
scoped only by `--allowedTools` and the isolated worktree, and revisit
per-task containers if that proves insufficient once this is actually
tested.** Stated explicitly as a deferred decision, not an oversight.

---

## 7. Safety boundaries for this phase

- **No `--dangerously-skip-permissions`.** Start with Claude Code's normal
  permission model even in headless mode, scoped via `--allowedTools` to
  only `Read`, `Edit`, `Bash` — no network tools, no arbitrary shell access
  beyond what's needed to run tests.
- **No Git writes reach real GitHub.** `git worktree` operations stay local
  to the shared volume; nothing in this phase calls GitHub's API to push,
  branch on the remote, or open a PR. That's still exclusively Phase 3.
- **Timeouts.** The `subprocess.run(..., timeout=600)` above is a hard cap -
  a task that runs long either finished or should be killed, not left
  running indefinitely and consuming cost.
- **Cost visibility.** Claude Code's JSON output includes `cost_usd` per
  run - worth storing on `CodingTask` even though it's not in the sketch
  above, so cost is visible per task before this scales to many tasks.

---

## 8. What this document deliberately does NOT cover

- **Multi-provider agent support** (Claude Code vs Codex vs custom) - parked,
  per section 1. `run_coding_task()` is the seam if it's ever needed.
- **Git writes / PR creation** - Phase 3, unchanged from the original roadmap.
- **Per-task container isolation** - open question in section 6, not decided.
- **Multi-step/multi-file task planning beyond what Claude Code already
  does internally** - Claude Code's own agent loop handles this; this
  project doesn't need to add another planning layer on top of it.

---

## 9. Suggested build order, once Phase 1 is validated

1. Add `claude` CLI to `python-service`'s Dockerfile, confirm it runs inside
   the container at all (`claude --version`) before wiring anything else up.
2. Build `coding_agent_service.py` and test it standalone against a throwaway
   repo - no Java integration yet, just confirm the subprocess/worktree/diff
   mechanics work.
3. Add the Python router + Java entity/controller/internal-callback pieces
   from section 5.
4. Wire up a minimal UI path in `dev-tools/test-harness.html` (a "run coding
   task" panel, same pattern as the existing query panel) to test the full
   loop manually before considering this phase done.
