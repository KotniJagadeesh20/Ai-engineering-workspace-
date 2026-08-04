# Future: Separating Platform Authentication from Source-Control Integrations

**Status: PARKED — not implemented.** This is target-state architecture for
a problem Phase 1 doesn't have yet. Read the "Why this is parked" section
before treating anything below as a near-term plan.

---

## Why this is parked

The core recommendation here — GitHub OAuth should be an *integration*, not
the *identity system* — is architecturally correct. But it has a hard
prerequisite this project doesn't meet yet: it requires an **alternate way
to become a platform user** that doesn't go through GitHub. Right now there
isn't one. GitHub OAuth isn't standing in for platform login as an accident
of implementation — for Phase 1, it's the *only* login mechanism that
exists. There is no "already-authenticated platform user who hasn't
connected GitHub yet" scenario possible today, because connecting GitHub is
literally how someone becomes a user.

Concretely, implementing this document as written means building actual
password-based registration first (hashing, storage, eventually password
reset / email verification) — a genuinely new subsystem, not a refactor of
what exists. That's a much larger scope addition than anything else built so
far in this project, in service of problems that don't currently occur:

- Nobody has hit "GitHub is down, I can't log in" — there's exactly one path
  in, and it works.
- Nobody needs "disconnect GitHub but keep my account" — without a second
  identity provider, disconnecting the only one *is* logging out,
  unavoidably.
- "Bind OAuth state to the already-authenticated platform user" (section 7
  below) doesn't apply yet either — there's no pre-existing platform user to
  bind to at connect time. The state-validation CSRF fix already built (see
  `docs/phase1-implementation-guide.md` section 8.1) already fully solves
  the actual problem the current flow has.

This is the same category of trap flagged elsewhere in this project for
premature workspaces/multi-tenancy and the knowledge graph: good future
architecture, but building it now means engineering for a requirement that
doesn't exist yet, on the promise that it eventually will.

## When to actually pick this up

Revisit this document — and only then — when either of these becomes true:

1. **A second identity provider is needed** (e.g. email/password signup, or
   a second OAuth provider like Google, used for *login* rather than repo
   access), which finally creates the "platform user who hasn't connected
   GitHub" case this architecture is designed for.
2. **A second source-control provider is needed** (Bitbucket, GitLab) for
   repo access specifically — even without a second identity provider, this
   would justify generalizing `GitHubCredential` into the
   `source_control_connections` shape described below, and introducing the
   `SourceControlProvider` interface.

Until one of those is real, `GitHubOAuthService` correctly serves a dual
role — identity *and* integration — because GitHub is the only provider.
That coupling is a deliberate, temporary simplification, not an oversight.

---

## The original proposal (for when it's needed)

### Target architecture

```
Platform Authentication
        +
Source-Control Integrations
```

rather than the current:

```
GitHub Authentication = Platform Authentication
```

### Two independent credential lifecycles

**Platform credentials** (frontend ↔ our backend):
```
Access Token  — short-lived JWT
Refresh Token — long-lived opaque credential, stored hashed, revocable
```

**Provider credentials** (our backend ↔ external provider):
```
GitHub Access Token / Bitbucket Access Token / GitLab Access Token
```
Never returned to the frontend; encrypted at rest; decrypted only when
needed; used server-side; scoped to the provider; independently
revocable/reconnectable.

### Updated GitHub flow (once platform auth exists independently)

```
GET /api/integrations/github/connect
GET /api/integrations/github/callback
```

`/connect` requires the user to already belong to the platform (via
whatever independent auth mechanism exists at that point). The callback
must **not** create/login a platform user or issue platform tokens — that
stays exclusively the job of platform authentication. Instead:

```
Authenticated Platform User
        ↓
Connect GitHub → GitHub OAuth → user approves → callback
        ↓
Validate OAuth state (now bound to the initiating platform user, not just
a CSRF nonce — see "OAuth state must bind to platform user" below)
        ↓
Exchange code → fetch GitHub identity → encrypt token
        ↓
Associate credential with the CURRENT platform user
        ↓
GitHub connected (platform session unaffected)
```

### OAuth state must bind to the platform user

Once GitHub connection happens for an already-authenticated user, the
callback needs to know *which* platform user initiated it — not just that
the request isn't forged (which the current state-cookie CSRF check
already handles). Generate a random, short-lived, single-use state value
and store `state → platformUserId` server-side (in-memory is fine for a
single instance, same pattern as `LoginCodeService`; move to Redis if/when
multi-instance). Resolve it on callback before storing the credential
against that specific user.

### Data model evolution

Keep as-is while GitHub is the only provider:

```
github_credentials
├── id
├── user_id
├── encrypted_access_token
├── expires_at
└── updated_at
```

Generalize only once a second provider is real:

```
source_control_connections
├── id
├── user_id
├── provider              (GITHUB | BITBUCKET | GITLAB)
├── provider_user_id
├── provider_username
├── encrypted_access_token
├── encrypted_refresh_token
├── expires_at
├── scopes
├── status
├── created_at
└── updated_at
```

### Provider abstraction (also deferred)

Avoid long-term direct coupling like `RepoCloneService → GitHubOAuthService`
by routing through an interface once a second provider is real:

```java
interface SourceControlProvider {
    ProviderType provider();
    List<RepositorySummary> listRepositories(UUID userId);
    RepositoryMetadata getRepository(UUID userId, String owner, String repository);
    String getCloneCredential(UUID userId);
}
```

`GitHubSourceControlProvider`, `BitbucketSourceControlProvider`, etc. would
implement it. **Do not build this interface with only one implementation
behind it** — that's abstraction with no second case to validate it against,
which usually just means guessing at the wrong seams. Introduce it when
Bitbucket or GitLab support is an actual, scheduled piece of work.

### Failure independence (the strongest long-term argument for this)

Once separated, a GitHub outage should mean:
```
User CAN:  login, access platform, view previously connected repos, use
           unrelated features
User CANNOT temporarily: fetch new GitHub repos, connect/reconnect GitHub,
           perform GitHub operations
```
Today, a GitHub outage means the user can't log in at all, since GitHub
*is* the login mechanism. This is the single clearest signal that it's time
to revisit this document — the day "GitHub was down and nobody could use
the product at all" actually happens, or is judged too risky to accept.

### Disconnecting GitHub (also blocked on the same prerequisite)

```
DELETE /api/integrations/github
```
should revoke the stored GitHub credential and disable GitHub operations
while leaving the platform account and session untouched — i.e.
`Disconnect GitHub ≠ Logout`. This is impossible to build meaningfully
until logout and "lose GitHub access" are actually different events, which
again requires the second identity provider described above.

---

## Final principle to preserve when this is revisited

```
AUTHENTICATION           "Who are you?"           → our platform, JWT + refresh token
INTEGRATION    "What external systems have you authorised us to use?" → GitHub / Bitbucket / GitLab
```

GitHub should eventually be *an integration of the platform*, not *the
identity system of the platform*. Build the GitHub integration first
(already done, Phase 1). Design its boundary so another provider can be
added later (this document is that design). Do not introduce multi-provider
complexity — or split identity from GitHub — until a second provider or a
second identity mechanism is an actual, scheduled requirement.
