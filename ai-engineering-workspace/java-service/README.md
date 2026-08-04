# AI Engineering Workspace — Java Backend (Phase 1)

Spring Boot service covering:
- **Authentication** — access/refresh JWT pattern (own tokens, not GitHub's)
- **GitHub OAuth** — login flow, encrypted token storage
- **Repo connect** — clone a GitHub repo via JGit, hand off to the Python
  service for RAG indexing (Python side not included in this zip)

This is Phase 1 only, per the roadmap: **no code editing, no git writes yet
(branch/commit/push/PR come in Phase 3)**. This service can authenticate a
user, connect a repo, clone it, and kick off indexing.

---

## Prerequisites

- Java 17+
- Maven 3.9+
- PostgreSQL 15+ with the `pgvector` extension available (the Python service
  will use it; this Java service just needs a normal Postgres database)
- A GitHub OAuth App (create one at https://github.com/settings/developers)
  - Homepage URL: `http://localhost:3000` (or wherever your frontend lives)
  - Authorization callback URL: `http://localhost:8080/auth/github/callback`

---

## Environment variables

Copy these into a `.env` file or export them directly — **do not commit real
secrets**. Defaults in `application.yml` are placeholders and will not work
as-is (the JWT secret and encryption key especially — see below).

| Variable | Purpose | Example |
|---|---|---|
| `DB_URL` | Postgres JDBC URL | `jdbc:postgresql://localhost:5432/ai_engineering` |
| `DB_USERNAME` | Postgres user | `postgres` |
| `DB_PASSWORD` | Postgres password | `postgres` |
| `JWT_SECRET` | Signing key for access tokens (min 256 bits) | generate with `openssl rand -base64 32` |
| `JWT_ACCESS_EXPIRY` | Access token lifetime, minutes | `15` |
| `JWT_REFRESH_EXPIRY` | Refresh token lifetime, days | `7` |
| `GITHUB_CLIENT_ID` | From your GitHub OAuth App | — |
| `GITHUB_CLIENT_SECRET` | From your GitHub OAuth App | — |
| `GITHUB_REDIRECT_URI` | Must match the OAuth App's callback URL exactly | `http://localhost:8080/auth/github/callback` |
| `ENCRYPTION_SECRET_KEY` | AES-256 key (32 bytes, base64) for encrypting stored GitHub tokens | generate with `openssl rand -base64 32` |
| `FRONTEND_URL` | Where the OAuth callback redirects after login | `http://localhost:3000` |
| `REPO_STORAGE_PATH` | Local disk path for cloned repos | `/data/repos` |
| `PYTHON_SERVICE_URL` | Base URL of the Python RAG/indexing service | `http://localhost:8000` |
| `INTERNAL_SERVICE_SECRET` | **Required** — the app refuses to start without it. Shared secret Python must send back on the indexing-status callback. **Must match python-service's value exactly.** Any placeholder works for local dev. | generate with `openssl rand -base64 32` |
| `COOKIE_SECURE` | Whether the OAuth state cookie requires HTTPS | `false` for local http dev, `true` for any real deployment |
| `CORS_ALLOWED_ORIGINS` | Comma-separated origins allowed to call this API from a browser | `*` for local dev only |

**Important:** the shipped defaults for `JWT_SECRET` and
`ENCRYPTION_SECRET_KEY` are placeholders only. Generate real values before
running anything beyond a local smoke test:

```bash
openssl rand -base64 32
```

---

## Running locally

```bash
# 1. Start Postgres (adjust as needed)
docker run -d --name ai-eng-postgres -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=ai_engineering -p 5432:5432 postgres:16

# 2. Export your env vars (or use a .env loader of your choice)
export JWT_SECRET=$(openssl rand -base64 32)
export ENCRYPTION_SECRET_KEY=$(openssl rand -base64 32)
export GITHUB_CLIENT_ID=your_client_id
export GITHUB_CLIENT_SECRET=your_client_secret

# 3. Run
mvn spring-boot:run
```

Service starts on `http://localhost:8080`.

---

## Endpoint reference

### Auth / GitHub OAuth

| Method | Path | Auth required | Purpose |
|---|---|---|---|
| GET | `/auth/github/login` | No | Redirects browser to GitHub's authorize page; sets a short-lived `oauth_state` cookie for CSRF protection |
| GET | `/auth/github/callback` | No | GitHub redirects here after approval; validates state, issues a one-time login code, redirects to frontend with only that code |
| POST | `/auth/exchange` | No (one-time code in body) | Trades the login code from the redirect for real access + refresh tokens, in a JSON response body (never a URL) |
| POST | `/auth/refresh` | No (refresh token in body) | Exchange a valid refresh token for a new access token |
| POST | `/auth/logout` | No (refresh token in body) | Revokes the given refresh token |

### Repos

| Method | Path | Auth required | Purpose |
|---|---|---|---|
| POST | `/api/repos/connect` | Yes (Bearer access token) | Clone a GitHub repo, trigger async indexing |
| GET | `/api/repos` | Yes | List repos connected by the current user |
| GET | `/api/repos/{id}` | Yes (must own the repo) | Get a single repo's details |
| GET | `/api/repos/{id}/status` | Yes (must own the repo) | Get indexing status |
| POST | `/api/repos/{id}/query` | Yes (must own the repo) | Ask a question; proxies to Python's `/rag/query` after ownership + READY-status checks |
| PATCH | `/internal/repos/{id}/index-status` | **Shared secret header, not a user token** | Called by the Python service when indexing finishes/fails |

**Authenticated requests** need the header:
```
Authorization: Bearer <accessToken>
```

**The internal endpoint** needs a different header entirely:
```
X-Internal-Secret: <value matching INTERNAL_SERVICE_SECRET>
```
It deliberately isn't gated by the JWT filter — Python isn't a logged-in user. `SecurityConfig` permits `/internal/**` at the Spring Security layer, and `InternalRepoController` does the actual secret check itself.

---

## Security hardening pass — what changed and why

This service went through a review that caught six real gaps. All are fixed
as of this version:

1. **OAuth `state` is now actually validated**, not just generated. `/login`
   stores it in a short-lived HttpOnly cookie; `/callback` compares it
   against what GitHub returns and rejects on mismatch. This is what
   actually provides CSRF protection for the OAuth flow — generating state
   without checking it (the previous version) provided none.
2. **Refresh tokens are hashed at rest** (SHA-256), same principle as
   password hashing. The raw token is generated with `SecureRandom` (not
   `UUID.randomUUID()`, which isn't specified to be cryptographically
   unpredictable), handed to the client once, and never stored in that form.
   A database leak no longer hands out usable sessions.
3. **Tokens never appear in a URL.** The OAuth callback redirects with a
   short-lived (60s), single-use opaque code instead of the actual tokens.
   The frontend trades that code for the real tokens via `POST /auth/exchange`,
   which returns them in a JSON body. A code that leaks into browser
   history or a proxy log is useless within a minute and after one use.
4. **The Python → Java indexing callback is no longer open to anyone.**
   Moved to `/internal/repos/{id}/index-status` and gated by a shared
   secret header, checked manually in `InternalRepoController` (this path
   bypasses the user-JWT filter entirely, since it's not a user request).
   The app **refuses to start** if `INTERNAL_SERVICE_SECRET` isn't set —
   deliberately fail-closed rather than falling back to "unprotected, with
   a warning logged," since a config mistake should never silently disable
   authentication.
5. **Every single-repo endpoint checks ownership**, not just existence.
   `RepoRepository.findByIdAndConnectedByUserId(...)` replaces plain
   `findById(...)` everywhere a repo is looked up by id — a repo that
   exists but belongs to someone else returns 404, same as one that
   doesn't exist, so a caller can't distinguish "not yours" from
   "doesn't exist."
6. **Java and Python now share a Docker volume** for cloned repos (see the
   root `docker-compose.yml`). Passing a `repo_path` string across the HTTP
   boundary only means anything if both processes can actually see that
   path on disk — which isn't true by default across separate containers.

## Known gaps / still worth tightening

- `LoginCodeService` (the exchange-code mechanism) is in-memory, single
  instance. Fine for Phase 1 (one process); swap for a shared store (Redis
  with a short TTL) before running multiple instances behind a load balancer.
- Refresh tokens are reused (not rotated) on every `/auth/refresh` call.
  Fine for Phase 1; consider rotation (revoke + reissue) later for tighter
  security.
- `spring.jpa.hibernate.ddl-auto: update` is a dev convenience. Switch to
  Flyway or Liquibase migrations before this has real data you care about.
- No rate limiting anywhere yet.

---

## What's intentionally NOT in this repo

- The Python RAG/agent service (`/index`, `/rag/query` endpoints) — separate
  service, separate codebase, per our architecture split (Java = platform,
  Python = intelligence).
- Any code-editing / sandboxing logic — that's Phase 2.
- Git write operations (branch/commit/push/PR) — that's Phase 3, deliberately
  gated behind the coding agent existing first.
- Multi-tenant workspace permission logic — `workspaceId` exists as a column
  on `Repo` as a reserved seam, but nothing enforces it yet (Phase 5).
