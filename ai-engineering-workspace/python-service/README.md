# AI Engineering Workspace — Python Service (Phase 1)

The "intelligence" half of the split described in `docs/phase1-implementation-guide.md`.
Java owns auth, GitHub OAuth, and repo CRUD; this service owns RAG indexing
and question-answering. It never talks to Postgres' user/repo tables and has
no auth of its own — Java validates who's allowed to ask before calling here.

---

## Endpoints

| Method | Path | Called by | Purpose |
|---|---|---|---|
| POST | `/index` | Java's `IndexingClient` | Accepts a repo path, indexes it in the background, reports status back |
| POST | `/rag/query` | Java (after it's checked the user can access the repo) | Answers a question about an already-indexed repo |
| GET | `/health` | Anyone | Liveness check |

---

## Setup

```bash
cd python-service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

cp .env.example .env
# fill in ANTHROPIC_API_KEY at minimum

uvicorn app.main:app --reload --port 8000
```

Requires the same Postgres instance the Java service uses, with the
`pgvector` extension enabled:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

---

## If you know Spring Boot, here's the mapping

| Spring/Java concept | This service's equivalent |
|---|---|
| `@SpringBootApplication` main class | `app/main.py` |
| `@RestController` | `APIRouter` in `app/routers/*.py` |
| `@Service` | plain functions in `app/services/*.py` (no DI container needed at this scale) |
| DTO records (`IndexRequest`, etc) | Pydantic models in `app/models/schemas.py` |
| `@ConfigurationProperties` bound to `application.yml` | `Settings` class in `app/config.py`, bound to `.env` |
| `@Async` fire-and-forget method | `BackgroundTasks` param in a FastAPI route handler |
| A JPA `@Repository` | `PGVector` from `langchain-postgres` — same idea (an abstraction over SQL), just doing similarity search instead of exact-match queries |

---

## How indexing actually works (`app/services/indexing_service.py`)

1. **Walk the repo** (`_collect_documents`) — skips `.git`, `node_modules`,
   `target`, `build`, etc, and only loads files with extensions in
   `INCLUDED_EXTENSIONS`. Any file that fails to load (binary misdetected as
   text, bad encoding) is logged and skipped rather than failing the whole run.
2. **Chunk** — `RecursiveCharacterTextSplitter`, fixed 1000-character chunks
   with 200-character overlap. Deliberately simple/generic for Tier 1 -
   this is a planned stopgap, not the intended final state, since the input
   here is predominantly source code, not prose. Splitting purely by
   character count can cut a function in half mid-body, which hurts
   retrieval quality. Planned upgrade path: Tier 2 is language-aware
   separator splitting (`RecursiveCharacterTextSplitter.from_language(Language.JAVA, ...)`,
   etc) - the first planned RAG-quality improvement, not conditional on
   first seeing a bad answer. Tier 3, if Tier 2 isn't enough, is AST/parser-
   based chunking (e.g. Tree-sitter). See `docs/phase1-implementation-guide.md`
   section 8.2 for the full roadmap.
3. **Embed** — `HuggingFaceEmbeddings` (`all-MiniLM-L6-v2`), a small local
   model. Chosen deliberately so Phase 1 doesn't require an OpenAI API key
   just to test indexing. Swap for `OpenAIEmbeddings` (via `langchain-openai`)
   later if retrieval quality needs improving — it's a one-line change in
   `_get_embeddings()`.
4. **Store** — `PGVector`, one **collection per repo** (`collection_name=repo_id`),
   in the same Postgres database the Java service uses. Re-indexing a repo
   deletes and recreates its collection first, so re-running indexing after a
   code change doesn't leave stale duplicate chunks behind.
5. **Report back** — `httpx.patch(...)` to Java's
   `/internal/repos/{id}/index-status`, matching the callback Java's `IndexingClient`
   is waiting on (Java doesn't poll; Python reports when it's actually done).

---

## How querying works (`app/services/rag_service.py`)

Plain `RetrievalQA` chain — retrieve top-5 chunks, hand them + the question to
Claude, return the answer plus the source files it drew from. No agent loop,
no LangGraph yet. That's deliberate: Phase 1 only needs single-shot
question-answering. Phase 2's coding agent is where a real loop (plan → act →
observe → repeat) becomes necessary — see `docs/phase2-plan-claude-code-integration.md`
for the current plan (integrating Claude Code's Agent SDK rather than
building a custom LangGraph loop).

---

## Known gaps / things to tighten later

- `/rag/query` itself is still unauthenticated at the Python layer, by
  design — Java validates ownership before ever calling it (see Java's
  `RepoController.queryRepo`). This service must never be reachable
  directly from outside the private network Java lives on.
- The `/internal/repos/{id}/index-status` callback this service calls on
  Java IS now protected by a shared secret (`INTERNAL_SERVICE_SECRET`,
  checked via the `X-Internal-Secret` header) — make sure it's set to the
  same value on both services, or the callback will be rejected.
- No retry logic if the callback to Java fails — it's logged, not retried.
  Fine for local dev; add a retry/backoff (or a dead-letter log) before this
  runs unattended.
- Local embedding model means indexing quality is "good enough to start,"
  not best-in-class. Revisit once RAG answers start missing things you know
  are in the repo.
