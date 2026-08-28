# AI Engineering Workspace — Phase 1

**Phase 1 of 7** on the roadmap: repository intelligence — connect a GitHub
repo, index it, ask questions about it. No code editing, no Git writes yet
(those are Phase 2 and Phase 3).

Two services, split by responsibility:

```
java-service/     — the platform layer: auth, GitHub OAuth, repo CRUD
python-service/   — the intelligence layer: RAG indexing, question answering
docs/             — full implementation reference, both services, plus parked future-architecture designs
dev-tools/        — a plain-HTML test harness (not the product UI)
```

Start with **`docs/phase1-implementation-guide.md`** — it walks through both
services' flows step by step, with the actual code, and a table mapping
every class to what it depends on.

`docs/future-source-control-integrations.md` is a **parked** design doc for
separating platform login from GitHub-as-an-integration (so Bitbucket/GitLab
or a second identity provider could be added later without touching auth).
It's deliberately not implemented — see its "Why this is parked" section for
what has to become true first.

`docs/phase2-plan-claude-code-integration.md` is the design for Phase 2 —
integrating Claude Code's Agent SDK as the coding engine instead of building
a custom agent loop. Planned, not yet implemented.

---

## Running everything with Docker Compose (recommended)

```bash
cp .env.example .env
# fill in JWT_SECRET, ENCRYPTION_SECRET_KEY, GITHUB_CLIENT_ID/SECRET,
# ANTHROPIC_API_KEY, INTERNAL_SERVICE_SECRET - see .env.example for how to
# generate each one

docker compose up --build
```

This starts Postgres (with pgvector), both services, and — critically —
gives java-service and python-service a **shared Docker volume** for cloned
repos. Without that, Java clones a repo to a path Python's container simply
can't see, since they'd otherwise be separate filesystems. See
`docs/phase1-implementation-guide.md` section 8 for the full explanation.

## Running each service manually (alternative, no Docker)

```bash
# 1. Postgres, with pgvector - shared by both services
docker run -d --name ai-eng-postgres -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=ai_engineering -p 5432:5432 pgvector/pgvector:pg16
# (pgvector/pgvector image ships the extension pre-installed; if you use
# plain postgres:16 instead, run `CREATE EXTENSION vector;` yourself first)

# 2. Java service
cd java-service
export JWT_SECRET=$(openssl rand -base64 32)
export ENCRYPTION_SECRET_KEY=$(openssl rand -base64 32)
export GITHUB_CLIENT_ID=your_client_id
export GITHUB_CLIENT_SECRET=your_client_secret
export INTERNAL_SERVICE_SECRET=$(openssl rand -base64 32)  # required - app won't start without it
mvn spring-boot:run
# → runs on :8080

# 3. Python service (separate terminal)
cd python-service
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # fill in ANTHROPIC_API_KEY and INTERNAL_SERVICE_SECRET
                        # (must match the value you exported for java-service above)
uvicorn app.main:app --reload --port 8000
# → runs on :8000
```

Each service has its own README with full setup detail, env vars, and a
"known gaps" section — read those before doing anything beyond local testing.

## Testing it without a real frontend

Once both services are up, open `dev-tools/test-harness.html` directly in a
browser — no build step. It walks through login, connecting a repo, and
asking questions, and logs every request/response so you can see exactly
what's happening. See `dev-tools/README.md` for the one manual step (copying
tokens after the GitHub OAuth redirect).

---

## Why two services, and where the line is drawn

Java owns anything **deterministic** — auth, permission checks, calling
GitHub's REST API once a decision's already been made. Python owns anything
that requires the LLM to **reason** — embeddings, retrieval, answering
questions, and (starting Phase 2) planning/acting in a loop. See section 1 of
the implementation guide for the full architecture diagram.

---

## Roadmap (for context — only Phase 1 is built here)

1. **Repository Intelligence** ← you are here
2. Coding Agent (sandboxed editing, tests, diffs — no Git writes; planned via Claude Code integration, see docs/phase2-plan-claude-code-integration.md)
3. Git Automation (branch/commit/push/PR, human-approval gated)
4. Slack notifications
5. CI/CD awareness (read GitHub Actions status)
6. Observability (one vendor, read-only)
7. Autonomous loop (alert → investigate → fix → PR → approval → deploy → verify)
