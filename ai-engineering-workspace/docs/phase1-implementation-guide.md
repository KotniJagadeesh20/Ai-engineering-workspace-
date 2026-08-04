# Phase 1 Implementation Guide — Repository Intelligence (Java Backend)

**Scope of this document:** Authentication (access/refresh JWT pattern) + GitHub OAuth flow + Repository connect/clone + Python RAG indexing/query, plus a security hardening pass (section 8) covering six real gaps caught in review. This is the complete Phase 1 reference, covering both services.

---

## 1. High-Level Architecture

```
                         Frontend (React/Next.js)
                                   │
                                   ▼
                     ┌─────────────────────────┐
                     │   Java (Spring Boot)     │
                     │   — the platform layer   │
                     │                          │
                     │  Auth · GitHub OAuth ·   │
                     │  Repo CRUD · Approval    │
                     │  gates · GitHub API      │
                     │  calls                   │
                     └────────────┬─────────────┘
                                  │  HTTP (internal)
                                  ▼
                     ┌─────────────────────────┐
                     │  Python (FastAPI)        │
                     │  — the intelligence layer│
                     │                          │
                     │  Phase 1 (this doc):     │
                     │  RAG · embeddings ·      │
                     │  code retrieval          │
                     │                          │
                     │  Phase 2 (not built yet):│
                     │  LangGraph agent ·       │
                     │  sandboxed code editing  │
                     └────────────┬─────────────┘
                                  │
                                  ▼
                     Postgres (+ pgvector) — shared DB
```

**Division of responsibility:** Java owns anything that's a deterministic action or a business rule (who can do what, when to call GitHub, when to notify). Python owns anything that requires the LLM to reason about what to do next. This document covers both services as they exist through Phase 1 — the LangGraph agent loop and sandboxed editing shown above are Phase 2 scope, included in the diagram only to show where they'll eventually sit, not because they're built yet.

---

## 2. Project Structure

```
src/main/java/com/aiengineering/
├── config/
│   └── SecurityConfig.java        — stateless JWT security rules
├── controller/
│   ├── AuthController.java        — /auth/refresh, /auth/logout
│   ├── GitHubAuthController.java  — /auth/github/login, /auth/github/callback
│   └── RepoController.java        — /api/repos/*
├── service/
│   ├── JwtService.java            — generate/validate access tokens
│   ├── AuthService.java           — login/refresh/logout orchestration
│   ├── GitHubOAuthService.java    — GitHub code exchange, profile fetch, user upsert
│   ├── GitHubService.java         — ongoing GitHub API data calls (list repos, metadata) using the stored token
│   ├── EncryptionService.java     — AES-256-GCM for encrypting GitHub tokens at rest
│   ├── RepoCloneService.java      — JGit clone
│   └── IndexingClient.java        — async hand-off to Python
├── entity/
│   ├── User.java
│   ├── RefreshToken.java          — opaque, revocable, server-side
│   ├── GitHubCredential.java      — encrypted GitHub token, separate lifecycle from RefreshToken
│   └── Repo.java
├── repository/                    — Spring Data JPA interfaces
├── dto/                           — request/response records
├── filter/
│   └── JwtAuthFilter.java         — validates Bearer token on every request
└── exception/                     — UnauthorizedException, ResourceNotFoundException, GlobalExceptionHandler
```

**Key structural decision:** `RefreshToken` (our own auth) and `GitHubCredential` (GitHub's token) are two separate entities with two separate lifecycles. Never conflate them — one is what the *frontend* uses to talk to *our backend*; the other is what *our backend* uses to talk to *GitHub's API*.

---

## 3. Flow A — Access/Refresh Token Authentication

### 3.1 Pattern in one diagram

```
Login (once)
   │
   ▼
Issue: access token (JWT, ~15 min) + refresh token (opaque string, ~7 days)
   │
   ▼
Frontend uses access token for every API call
   │
   ▼
Access token expires
   │
   ▼
Frontend calls /auth/refresh with the refresh token
   │
   ▼
Backend validates refresh token against DB (not revoked, not expired)
   │
   ▼
Issue a new access token — refresh token reused (Phase 1; rotation is a later hardening step)
```

### 3.2 Why two different token *types*

| | Access Token | Refresh Token |
|---|---|---|
| Format | Self-contained JWT | Opaque random string |
| Lifespan | Short (~15 min) | Long (~7 days) |
| Revocable? | No — expires fast, so blast radius is limited | Yes — stored in DB, can be flagged `revoked` instantly |
| Validated by | Signature check only (stateless) | DB lookup (stateful) |

This split exists because a JWT can't be revoked without maintaining a blocklist (defeats the point of being stateless), so short expiry does that job instead. The refresh token *is* revocable because it's just a DB row — deleting/flagging it kills the session immediately.

### 3.3 Step-by-step implementation

**Step 1 — `RefreshToken` entity: stores the server-side, revocable record**

```java
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id @GeneratedValue
    private UUID id;

    @Column(unique = true, nullable = false)
    private String token;          // opaque UUID string, NOT a JWT

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private Instant expiresAt;

    private boolean revoked = false;
}
```

**Step 2 — `JwtService`: generates and validates the access token only**

```java
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenExpiryMinutes;

    public JwtService(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.access-token-expiry-minutes}") long expiry) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMinutes = expiry;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .setSubject(user.getId().toString())
            .claim("username", user.getUsername())
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(now.plus(accessTokenExpiryMinutes, ChronoUnit.MINUTES)))
            .signWith(signingKey, SignatureAlgorithm.HS256)
            .compact();
    }

    public boolean isValid(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(signingKey).build().parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public UUID extractUserId(String token) {
        Claims claims = Jwts.parserBuilder().setSigningKey(signingKey).build()
            .parseClaimsJws(token).getBody();
        return UUID.fromString(claims.getSubject());
    }
}
```

**Step 3 — `AuthService`: login / refresh / logout orchestration**

```java
@Service
public class AuthService {

    public AuthResponse login(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        RefreshToken refreshToken = issueRefreshToken(user.getId());
        return new AuthResponse(accessToken, refreshToken.getToken(),
                                 jwtService.getAccessTokenExpirySeconds());
    }

    public AuthResponse refresh(String refreshTokenValue) {
        RefreshToken stored = refreshTokenRepository.findByToken(refreshTokenValue)
            .filter(rt -> !rt.isRevoked() && rt.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired refresh token"));

        User user = userRepository.findById(stored.getUserId())
            .orElseThrow(() -> new UnauthorizedException("User no longer exists"));

        String newAccessToken = jwtService.generateAccessToken(user);
        return new AuthResponse(newAccessToken, stored.getToken(),
                                 jwtService.getAccessTokenExpirySeconds());
    }

    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
    }

    private RefreshToken issueRefreshToken(UUID userId) {
        RefreshToken rt = new RefreshToken();
        rt.setToken(UUID.randomUUID().toString());
        rt.setUserId(userId);
        rt.setExpiresAt(Instant.now().plus(refreshTokenExpiryDays, ChronoUnit.DAYS));
        return refreshTokenRepository.save(rt);
    }
}
```

**Step 4 — `JwtAuthFilter`: runs on every request, populates the security context**

```java
public class JwtAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                     FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtService.isValid(token)) {
                UUID userId = jwtService.extractUserId(token);
                var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(req, res);
    }
}
```

**Step 5 — `SecurityConfig`: wires the filter in, marks public vs protected routes**

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/auth/github/**", "/auth/refresh", "/actuator/health").permitAll()
            .anyRequest().authenticated()
        )
        .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```

**Step 6 — `AuthController`: exposes refresh/logout** (login is issued as part of the GitHub callback — see Flow B)

```java
@RestController
@RequestMapping("/auth")
public class AuthController {

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ResponseEntity.ok(authService.refresh(req.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
```

---

## 4. Flow B — GitHub OAuth

### 4.1 Pattern in one diagram

```
Browser → GET /auth/github/login
   │
   ▼
Redirect to GitHub's authorize page
   │
   ▼
User approves
   │
   ▼
GitHub → GET /auth/github/callback?code=...
   │
   ▼
Exchange code for GitHub access token
   │
   ▼
Fetch GitHub profile (id, username, email, avatar)
   │
   ▼
Upsert local User record
   │
   ▼
Encrypt + store GitHub token → GitHubCredential
   │
   ▼
Issue OUR OWN access + refresh tokens (Flow A, step 3: AuthService.login)
   │
   ▼
Redirect browser back to frontend with our tokens
```

**Critical distinction:** the GitHub token obtained here is never handed to the frontend and never used as our session token. It's stored encrypted, server-side only, and used later when our backend needs to act on GitHub on the user's behalf (clone a private repo, eventually push/PR in Phase 3).

### 4.2 Step-by-step implementation

**Step 1 — `GitHubCredential` entity: encrypted, separate lifecycle from `RefreshToken`**

```java
@Entity
@Table(name = "github_credentials")
public class GitHubCredential {
    @Id @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedAccessToken;

    private Instant expiresAt;   // GitHub classic tokens don't expire; GitHub App tokens do
    private Instant updatedAt = Instant.now();
}
```

**Step 2 — `EncryptionService`: AES-256-GCM for the token at rest**

```java
@Service
public class EncryptionService {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec keySpec;

    public EncryptionService(@Value("${encryption.secret-key}") String base64Key) {
        byte[] decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length != 32) {
            throw new IllegalStateException("encryption.secret-key must decode to 32 bytes");
        }
        this.keySpec = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(iv) + ":" +
               Base64.getEncoder().encodeToString(ciphertext);
    }

    public String decrypt(String encrypted) {
        String[] parts = encrypted.split(":", 2);
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
```

**Step 3 — `GitHubOAuthService`: the four-step handoff**

```java
@Service
public class GitHubOAuthService {

    public String buildAuthorizationUrl(String state) {
        return "https://github.com/login/oauth/authorize"
            + "?client_id=" + clientId
            + "&redirect_uri=" + redirectUri
            + "&scope=" + scope
            + "&state=" + state;
    }

    // Step A: exchange the temporary code for a real GitHub access token
    public GitHubTokenResponse exchangeCodeForToken(String code) {
        return webClient.post()
            .uri("https://github.com/login/oauth/access_token")
            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
            .bodyValue(Map.of(
                "client_id", clientId, "client_secret", clientSecret,
                "code", code, "redirect_uri", redirectUri
            ))
            .retrieve().bodyToMono(GitHubTokenResponse.class).block();
    }

    // Step B: fetch the profile using that token
    public GitHubUserProfile fetchUserProfile(String githubAccessToken) {
        return webClient.get()
            .uri("https://api.github.com/user")
            .header("Authorization", "Bearer " + githubAccessToken)
            .retrieve().bodyToMono(GitHubUserProfile.class).block();
    }

    // Step C: upsert our local User from the GitHub profile
    public User upsertUser(GitHubUserProfile profile) {
        String githubId = String.valueOf(profile.id());
        User user = userRepository.findByGithubId(githubId).orElseGet(User::new);
        user.setGithubId(githubId);
        user.setUsername(profile.login());
        user.setEmail(profile.email());
        user.setAvatarUrl(profile.avatarUrl());
        return userRepository.save(user);
    }

    // Step D: encrypt and store the GitHub token
    public void storeGitHubCredential(User user, GitHubTokenResponse tokenResponse) {
        GitHubCredential credential = credentialRepository.findByUserId(user.getId())
            .orElseGet(GitHubCredential::new);
        credential.setUserId(user.getId());
        credential.setEncryptedAccessToken(encryptionService.encrypt(tokenResponse.accessToken()));
        credential.setUpdatedAt(Instant.now());
        credentialRepository.save(credential);
    }
}
```

**Step 4 — `GitHubAuthController`: ties the whole flow together end to end**

```java
@RestController
@RequestMapping("/auth/github")
public class GitHubAuthController {

    @GetMapping("/login")
    public void login(HttpServletResponse response) throws IOException {
        String state = UUID.randomUUID().toString();
        response.sendRedirect(gitHubOAuthService.buildAuthorizationUrl(state));
    }

    @GetMapping("/callback")
    public void callback(@RequestParam("code") String code,
                          HttpServletResponse response) throws IOException {

        GitHubTokenResponse tokenResponse = gitHubOAuthService.exchangeCodeForToken(code);
        GitHubUserProfile profile = gitHubOAuthService.fetchUserProfile(tokenResponse.accessToken());

        User user = gitHubOAuthService.upsertUser(profile);
        gitHubOAuthService.storeGitHubCredential(user, tokenResponse);

        AuthResponse authResponse = authService.login(user);   // Flow A kicks in here

        String redirectUrl = String.format("%s/auth/callback?accessToken=%s&refreshToken=%s",
            frontendUrl, authResponse.accessToken(), authResponse.refreshToken());

        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader("Location", redirectUrl);
    }
}
```

**Where Flow A and Flow B meet:** the last line of `callback()` — `authService.login(user)` — is exactly the `login()` method from section 3.3, Step 3. GitHub OAuth is the *trigger*; the access/refresh token pattern is what actually issues our session.

---

## 5. Flow C — Repository Connect

### 5.1 Pattern in one diagram

```
GET /api/repos/github/available   (browse what's on GitHub, before connecting)
   │
   ▼
POST /api/repos/connect  (Bearer token required — Flow A's filter validates it)
   │
   ▼
RepoCloneService: decrypt the user's GitHub token (Flow B's GitHubCredential)
   │
   ▼
JGit clone using that token as credentials
   │
   ▼
Save Repo row, status = PENDING
   │
   ▼
IndexingClient: async POST to Python /index
   │
   ▼
Python indexes → calls back PATCH /api/repos/{id}/index-status
```

This is the point where Flow A (who is this user) and Flow B (what can we do on their behalf against GitHub) both get used together — the controller reads the authenticated user's ID off the security context (populated by `JwtAuthFilter`), and `RepoCloneService` pulls that same user's *decrypted GitHub token* to actually authenticate the clone.

### 5.2 `GitHubService` — data calls, separate from the OAuth handshake

`GitHubOAuthService` (Flow B) only runs the auth handshake — exchange code, fetch profile, store token — and that only happens once, at login. Everything after that — listing the user's repos, checking a specific repo's metadata — is a separate, ongoing concern with its own class:

```java
@Service
public class GitHubService {

    private final WebClient webClient = WebClient.create();
    private final GitHubOAuthService gitHubOAuthService;

    public GitHubService(GitHubOAuthService gitHubOAuthService) {
        this.gitHubOAuthService = gitHubOAuthService;
    }

    // Used to show a "pick a repo to connect" screen in the frontend,
    // BEFORE the user hits /connect with a URL.
    public List<GitHubRepoSummary> listUserRepos(UUID userId) {
        String token = gitHubOAuthService.getDecryptedGitHubToken(userId);

        return webClient.get()
            .uri("https://api.github.com/user/repos?per_page=100&sort=updated")
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .retrieve()
            .bodyToFlux(GitHubRepoSummary.class)
            .collectList()
            .block();
    }

    // Validates a specific repo is accessible before attempting to clone it.
    public GitHubRepoSummary getRepoMetadata(UUID userId, String owner, String repoName) {
        String token = gitHubOAuthService.getDecryptedGitHubToken(userId);

        return webClient.get()
            .uri("https://api.github.com/repos/" + owner + "/" + repoName)
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github+json")
            .retrieve()
            .bodyToMono(GitHubRepoSummary.class)
            .block();
    }
}
```

**Why this is a separate class from `GitHubOAuthService`:** the two have different call frequencies and different failure modes. The OAuth handshake runs once per login and, if it fails, the user simply can't log in. `GitHubService` runs constantly — every time someone opens the "connect a repo" screen — and its calls can fail for reasons that have nothing to do with auth (rate limiting, a repo being deleted, a token's scope not covering an org). Keeping it separate means a GitHub API hiccup here can't accidentally break the login flow, and vice versa.

### 5.3 `RepoCloneService` — the clone itself

```java
@Service
public class RepoCloneService {

    private final RepoRepository repoRepository;
    private final GitHubOAuthService gitHubOAuthService;
    private final String repoStoragePath;

    public RepoCloneService(RepoRepository repoRepository,
                             GitHubOAuthService gitHubOAuthService,
                             @Value("${app.repo-storage-path}") String repoStoragePath) {
        this.repoRepository = repoRepository;
        this.gitHubOAuthService = gitHubOAuthService;
        this.repoStoragePath = repoStoragePath;
    }

    public Repo cloneAndSave(String githubUrl, UUID connectedByUserId) {
        String localPath = repoStoragePath + "/" + UUID.randomUUID();

        Repo repo = new Repo();
        repo.setGithubUrl(githubUrl);
        repo.setLocalPath(localPath);
        repo.setConnectedByUserId(connectedByUserId);
        repo.setStatus(IndexStatus.CLONING);
        repo.setName(extractRepoName(githubUrl));
        repo.setOwner(extractOwner(githubUrl));
        repo = repoRepository.save(repo);

        try {
            String githubToken = gitHubOAuthService.getDecryptedGitHubToken(connectedByUserId);

            Git.cloneRepository()
                .setURI(githubUrl)
                .setDirectory(new File(localPath))
                // GitHub accepts the OAuth token as the "password" with any username
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(githubToken, ""))
                .call()
                .close();

            repo.setStatus(IndexStatus.PENDING); // cloned; awaiting Python indexing
            return repoRepository.save(repo);

        } catch (Exception e) {
            repo.setStatus(IndexStatus.FAILED);
            repoRepository.save(repo);
            throw new IllegalStateException("Failed to clone repository: " + githubUrl, e);
        }
    }

    private String extractRepoName(String githubUrl) { /* strips .git, takes last path segment */ }
    private String extractOwner(String githubUrl) { /* strips .git, takes second-to-last segment */ }
}
```

Note this pulls the token via `gitHubOAuthService.getDecryptedGitHubToken(...)` — the same credential store from Flow B, section 4.2. No separate token storage for cloning; it reuses what was captured at login.

### 5.4 `RepoController` — ties browse, connect, and status together

```java
@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoCloneService repoCloneService;
    private final IndexingClient indexingClient;
    private final RepoRepository repoRepository;
    private final GitHubService gitHubService;

    // constructor omitted for brevity - standard DI of the four collaborators above

    // Browse what's available on GitHub before connecting anything
    @GetMapping("/github/available")
    public ResponseEntity<List<GitHubRepoSummary>> listAvailableGitHubRepos() {
        UUID currentUserId = CurrentUserUtil.getCurrentUserId();
        return ResponseEntity.ok(gitHubService.listUserRepos(currentUserId));
    }

    // Clone + kick off indexing
    @PostMapping("/connect")
    public ResponseEntity<Repo> connectRepo(@Valid @RequestBody RepoConnectRequest request) {
        UUID currentUserId = CurrentUserUtil.getCurrentUserId();
        Repo repo = repoCloneService.cloneAndSave(request.githubUrl(), currentUserId);
        indexingClient.triggerIndex(repo); // async hand-off to the Python service
        return ResponseEntity.ok(repo);
    }

    @GetMapping
    public ResponseEntity<List<Repo>> listMyRepos() {
        UUID currentUserId = CurrentUserUtil.getCurrentUserId();
        return ResponseEntity.ok(repoRepository.findByConnectedByUserId(currentUserId));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<String> getStatus(@PathVariable UUID id) {
        Repo repo = repoRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Repo not found: " + id));
        return ResponseEntity.ok(repo.getStatus().name());
    }

    // Python calls this back when indexing finishes/fails - see section 5.1 diagram
    @PatchMapping("/{id}/index-status")
    public ResponseEntity<Void> updateIndexStatus(@PathVariable UUID id,
                                                   @RequestBody IndexStatusUpdateRequest request) {
        Repo repo = repoRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Repo not found: " + id));
        repo.setStatus(request.status());
        repoRepository.save(repo);
        return ResponseEntity.noContent().build();
    }
}
```

---

## 6. Summary Table — Where Each Piece Lives

| Concern | Class | Depends on |
|---|---|---|
| Sign/validate our session token | `JwtService` | `jwt.secret` config |
| Login/refresh/logout orchestration | `AuthService` | `JwtService`, `RefreshTokenRepository` |
| Revocable session record | `RefreshToken` entity | — |
| Per-request auth check | `JwtAuthFilter` | `JwtService` |
| Route-level access rules | `SecurityConfig` | `JwtAuthFilter` |
| GitHub OAuth handshake | `GitHubOAuthService` | GitHub API, `EncryptionService` |
| Encrypted GitHub token storage | `GitHubCredential` entity | `EncryptionService` |
| Symmetric encryption | `EncryptionService` | `encryption.secret-key` config |
| Ties OAuth flow + issues our session | `GitHubAuthController` | `GitHubOAuthService`, `AuthService` |
| List/browse GitHub repos, metadata | `GitHubService` | `GitHubOAuthService.getDecryptedGitHubToken()` |
| Repo clone using stored GitHub token | `RepoCloneService` | `GitHubOAuthService.getDecryptedGitHubToken()` |
| Hand-off to Python | `IndexingClient` | `python-service.base-url` config |

---

## 7. Flow D — Python Indexing & RAG Query

### 7.1 Pattern in one diagram

```
Java: POST /index  { repo_id, repo_path }
   │
   ▼
FastAPI accepts, returns "accepted" immediately, work continues in a
BackgroundTask (Python's equivalent of Java firing IndexingClient's
WebClient call with .subscribe() and not waiting)
   │
   ▼
Walk repo on disk → filter to code/doc files → skip .git, node_modules, etc
   │
   ▼
Chunk with RecursiveCharacterTextSplitter (1000 chars, 200 overlap)
   │
   ▼
Embed with HuggingFaceEmbeddings (local model, no API key needed for Phase 1)
   │
   ▼
Store in PGVector, one collection per repo_id, same Postgres as Java
   │
   ▼
PATCH back to Java: /api/repos/{id}/index-status  { status: READY | FAILED }
```

```
Java: POST /rag/query  { repo_id, question }   (Java has already checked
                                                  the user can access this repo)
   │
   ▼
Load that repo's PGVector collection, retrieve top-5 chunks
   │
   ▼
RetrievalQA chain: chunks + question → ChatAnthropic → answer
   │
   ▼
Return { answer, sources }  (sources = the file paths the answer drew from)
```

### 7.2 Step-by-step implementation

**Step 1 — `config.py`: typed settings, same role as Java's `@ConfigurationProperties`**

```python
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    database_url: str = "postgresql+psycopg://postgres:postgres@localhost:5432/ai_engineering"
    anthropic_api_key: str = ""
    java_service_url: str = "http://localhost:8080"
    internal_service_secret: str = ""
    port: int = 8000

    class Config:
        env_file = ".env"

settings = Settings()
```

**Step 2 — `schemas.py`: Pydantic models, the Python equivalent of Java's DTO records**

```python
class IndexRequest(BaseModel):
    repo_id: str
    repo_path: str

class QueryRequest(BaseModel):
    repo_id: str
    question: str

class QueryResponse(BaseModel):
    answer: str
    sources: list[str]
```

**Step 3 — `indexing_service.py`: walk, chunk, embed, store**

```python
INCLUDED_EXTENSIONS = {".java", ".py", ".js", ".ts", ".md", ".yml", ".json", ...}
EXCLUDED_DIRS = {".git", "node_modules", "target", "build", "__pycache__", ...}

def _collect_documents(repo_path: str) -> list[Document]:
    documents = []
    for root, dirs, files in os.walk(repo_path):
        dirs[:] = [d for d in dirs if d not in EXCLUDED_DIRS]
        for filename in files:
            _, ext = os.path.splitext(filename)
            if ext not in INCLUDED_EXTENSIONS:
                continue
            file_path = os.path.join(root, filename)
            try:
                loader = TextLoader(file_path, autodetect_encoding=True)
                loaded = loader.load()
                relative_path = os.path.relpath(file_path, repo_path)
                for doc in loaded:
                    doc.metadata["source"] = relative_path
                documents.extend(loaded)
            except Exception as e:
                logger.warning("Skipping file %s: %s", file_path, e)
    return documents

def get_vectorstore(repo_id: str) -> PGVector:
    return PGVector(
        embeddings=HuggingFaceEmbeddings(model_name="all-MiniLM-L6-v2"),
        collection_name=repo_id,
        connection=settings.database_url,
        use_jsonb=True,
    )

def index_repository(repo_id: str, repo_path: str) -> None:
    try:
        documents = _collect_documents(repo_path)
        splitter = RecursiveCharacterTextSplitter(chunk_size=1000, chunk_overlap=200)
        chunks = splitter.split_documents(documents)

        vectorstore = get_vectorstore(repo_id)
        vectorstore.delete_collection()   # avoid stale duplicates on re-index
        vectorstore.create_collection()
        vectorstore.add_documents(chunks)

        _report_status(repo_id, status="READY")
    except Exception as e:
        _report_status(repo_id, status="FAILED", error_message=str(e))

def _report_status(repo_id: str, status: str, error_message: str | None = None) -> None:
    httpx.patch(
        f"{settings.java_service_url}/api/repos/{repo_id}/index-status",
        json={"status": status, "errorMessage": error_message},
        timeout=10.0,
    )
```

**Chunking roadmap — this is a known upgrade path, not an open question.** Given the input is predominantly source code, not prose, generic fixed-size chunking is a deliberate *stopgap* for getting the pipeline working end to end, not the intended final state. The planned progression:

```
Tier 1 (current): RecursiveCharacterTextSplitter, chunk_size ≈ 1000
    generic, language-agnostic, zero config
    ↓
Get RAG working end-to-end → test on a real repo → evaluate retrieval quality
    ↓
Tier 2: Language-aware separator splitting
    RecursiveCharacterTextSplitter.from_language(Language.JAVA, ...) etc
    still pattern/separator-based, but prefers splitting at class/method
    boundaries instead of arbitrary character counts. This is the first
    planned RAG-quality improvement - not conditional on first finding a
    bad answer, since the source-code-heavy input already makes it a
    strong bet. What real-repo testing determines is tuning specifics
    (chunk size per language, whether Tier 1 stays as a fallback for
    non-code files like .yml/.json/.md), not whether to do it at all.
    ↓
Tier 3 (later, if Tier 2 isn't enough): AST/parser-based chunking
    e.g. Tree-sitter - actually parses code into a syntax tree and chunks
    along real structural boundaries (a chunk becomes "this method,
    complete," not just "text that happened to start after a class
    keyword"). Categorically stronger than separator-based splitting, at
    the cost of a parser dependency per language. Worth reaching for only
    if Tier 2 still produces retrieval failures - it's real added
    complexity, not a default first move.
```

**Why chunking matters this much:** fixed-size character chunking can split a function mid-body, which directly hurts retrieval quality — a chunk with half a method's logic is a worse match for a question about that method than a chunk with the whole thing. Tier 2 is a genuine near-term plan, not a maybe.

**Step 4 — `rag_service.py`: retrieve + generate, single-shot (no agent loop yet)**

```python
def answer_question(repo_id: str, question: str) -> QueryResponse:
    vectorstore = get_vectorstore(repo_id)
    retriever = vectorstore.as_retriever(search_kwargs={"k": 5})

    llm = ChatAnthropic(model="claude-sonnet-4-6", temperature=0)
    qa_chain = RetrievalQA.from_chain_type(llm=llm, retriever=retriever,
                                           return_source_documents=True)

    result = qa_chain.invoke({"query": question})
    sources = sorted({d.metadata.get("source", "unknown")
                       for d in result.get("source_documents", [])})

    return QueryResponse(answer=result["result"], sources=sources)
```

**Why no LangGraph here yet:** Phase 1 only needs one question in, one grounded answer out — a plain `RetrievalQA` chain does that completely. LangGraph earns its place in Phase 2, where the coding agent genuinely needs to loop (retrieve → reason → decide to inspect another file → reason again). Introducing a graph-based agent loop here would add real complexity with nothing behind it to justify it yet.

**Step 5 — Routers: the FastAPI equivalent of Java's `@RestController`**

```python
# index_router.py
@router.post("/index", response_model=IndexAcceptedResponse)
async def trigger_index(request: IndexRequest, background_tasks: BackgroundTasks):
    background_tasks.add_task(index_repository, request.repo_id, request.repo_path)
    return IndexAcceptedResponse()

# query_router.py
@router.post("/rag/query", response_model=QueryResponse)
async def query_repo(request: QueryRequest):
    return answer_question(request.repo_id, request.question)
```

`BackgroundTasks` here plays the same role as Java's `IndexingClient` calling `.subscribe()` on the WebClient request and not blocking the HTTP response on it — both sides agree the actual indexing work happens *after* the initial request returns.

### 7.3 The full round trip, both services together

```
1. User connects a repo → Java clones it, saves Repo row (status: PENDING)
2. Java POSTs to Python /index → Python accepts, starts background task
3. Python walks/chunks/embeds/stores → PATCHes Java's /index-status → READY
4. User asks a question → Java validates they can access this repo_id
5. Java POSTs to Python /rag/query → Python retrieves + answers
6. Java returns Python's { answer, sources } straight through to the frontend
```

Nowhere in this round trip does Python touch the `users`, `repos`, or `refresh_tokens` tables directly, and nowhere does Java do embeddings or LLM calls directly — each service stays inside the boundary set out in section 1.

---

## 8. Security Hardening Pass

Everything above (sections 1–8) reflects the *first working version* of
Phase 1. A review afterward caught six real gaps — the kind that are easy to
miss on a first pass because each individual piece looked reasonable in
isolation, but the combination left real holes. All six are fixed as of this
version. This section documents what changed and why, since the code samples
in sections 3–4 above are now slightly out of date on these specific points
(rather than rewrite every embedded snippet, the deltas are captured here).

### 8.1 OAuth `state` — generated but never checked (fixed)

**The gap:** `login()` generated a `state` value and sent it to GitHub, but
`callback()` never read it back or compared it against anything. `state`
exists specifically to prevent CSRF on the OAuth redirect — generating it
without validating it provides *none* of that protection; it was decorative.

**The fix:** `login()` now stores `state` in a short-lived (10 min), HttpOnly
cookie scoped to `/auth/github`. `callback()` reads that cookie via
`@CookieValue` and compares it against GitHub's returned `state` query
param. Mismatch or missing cookie → `UnauthorizedException`, request
rejected before any token exchange happens.

```java
@GetMapping("/login")
public void login(HttpServletResponse response) throws IOException {
    String state = TokenUtil.generateOpaqueToken();
    Cookie stateCookie = new Cookie("oauth_state", state);
    stateCookie.setHttpOnly(true);
    stateCookie.setSecure(cookieSecure);     // false for local http, true in real deployments
    stateCookie.setPath("/auth/github");
    stateCookie.setMaxAge(600);
    response.addCookie(stateCookie);
    response.sendRedirect(gitHubOAuthService.buildAuthorizationUrl(state));
}

@GetMapping("/callback")
public void callback(@RequestParam("code") String code,
                      @RequestParam(value = "state", required = false) String returnedState,
                      @CookieValue(value = "oauth_state", required = false) String expectedState,
                      HttpServletResponse response) throws IOException {
    if (expectedState == null || returnedState == null || !expectedState.equals(returnedState)) {
        throw new UnauthorizedException("OAuth state mismatch - possible CSRF attempt");
    }
    // ... proceed with token exchange only after this check passes
}
```

### 8.2 Refresh tokens stored raw (fixed)

**The gap:** `RefreshToken.token` stored the actual bearer credential in
plaintext. A database leak would hand out immediately usable sessions for
every user — the exact failure mode password hashing exists to prevent,
just applied to a different kind of secret.

**The fix:** the entity field is now `tokenHash`. A new `TokenUtil` class
generates the raw token with `SecureRandom` (not `UUID.randomUUID()`, which
is fine for uniqueness but isn't specified to be cryptographically
unpredictable) and separately exposes `sha256Hex()`. `AuthService` hashes
the raw token before persisting it, and returns the *raw* value to the
caller exactly once, at issuance — it is never written to disk or logged in
that form again. Every later lookup (`refresh()`, `logout()`) hashes the
incoming value and matches against the stored hash.

```java
private String issueRefreshToken(UUID userId) {
    String rawToken = TokenUtil.generateOpaqueToken();      // shown to the client once
    RefreshToken refreshToken = new RefreshToken();
    refreshToken.setTokenHash(TokenUtil.sha256Hex(rawToken)); // only the hash is persisted
    refreshToken.setUserId(userId);
    refreshToken.setExpiresAt(Instant.now().plus(refreshTokenExpiryDays, ChronoUnit.DAYS));
    refreshTokenRepository.save(refreshToken);
    return rawToken;
}
```

### 8.3 Tokens in the redirect URL (fixed)

**The gap:** the original `callback()` redirected to
`{frontendUrl}/auth/callback?accessToken=...&refreshToken=...` — both real,
usable credentials sitting in a URL, which means browser history, server
access logs, proxy logs, and `Referer` headers could all end up holding a
live session token.

**The fix considered and rejected:** the obvious fix is "put the refresh
token in an HttpOnly cookie instead." That's a reasonable pattern in
general, but it doesn't fit this project cleanly: the dev-tools test harness
(section on `dev-tools/`) is a plain `file://` page, and HttpOnly cookies
set by `http://localhost:8080` interacting with a `file://` origin via
CORS-with-credentials is genuinely inconsistent across browsers — no
reliable `Origin` header, `SameSite` edge cases that differ by browser.

**The fix used instead:** a short-lived (60s), single-use, opaque exchange
code. `LoginCodeService` holds a small in-memory map (`code -> AuthResponse`,
with expiry). `callback()` calls `authService.login(user)` exactly as
before, but instead of putting the result in the URL, it hands it to
`loginCodeService.issueCode(...)` and redirects with only that code:

```java
AuthResponse authResponse = authService.login(user);
String loginCode = loginCodeService.issueCode(authResponse);
String redirectUrl = String.format("%s/auth/callback?code=%s", frontendUrl, loginCode);
```

The frontend (or the test harness) then trades that code for the real
tokens via a POST, receiving them in a JSON response body — never a URL:

```java
@PostMapping("/exchange")
public ResponseEntity<AuthResponse> exchange(@Valid @RequestBody ExchangeCodeRequest request) {
    return ResponseEntity.ok(loginCodeService.consumeCode(request.code()));
}
```

`consumeCode` removes the entry on read (single-use) and checks expiry — a
leaked code is useless after one use or after 60 seconds, unlike a leaked
token, which stays valid for its full lifetime (up to 7 days for a refresh
token) wherever it ends up.

**Known limitation, stated plainly:** `LoginCodeService`'s map is in-memory,
single-process. Fine for Phase 1 (one instance). If this ever runs behind a
load balancer with multiple instances, a code issued by instance A won't be
visible to instance B — swap the map for Redis with a short TTL at that
point; the calling code doesn't need to change.

### 8.4 Python → Java callback had no authentication (fixed)

**The gap:** `internal_service_secret` existed in config on both sides, but
nothing actually checked it. `PATCH /api/repos/{id}/index-status` was
reachable by anyone who could reach the Java service at all — including
normal end users — and would happily mark any repo `READY` or `FAILED` on
request, no proof required that the caller was actually the Python service.

**The fix:** the endpoint moved out of `/api/repos/**` entirely, to
`/internal/repos/{id}/index-status`, handled by a new
`InternalRepoController`. `SecurityConfig` permits `/internal/**` at the
Spring Security layer (there's no user JWT to check — Python isn't a logged
in user), but `InternalRepoController` does its own manual check against a
shared secret header:

```java
@PatchMapping("/{id}/index-status")
public void updateIndexStatus(@PathVariable UUID id,
                               @RequestHeader(value = "X-Internal-Secret", required = false) String providedSecret,
                               @RequestBody IndexStatusUpdateRequest request) {
    verifyInternalSecret(providedSecret);
    // ... update repo status
}

private void verifyInternalSecret(String providedSecret) {
    if (configuredSecret == null || configuredSecret.isBlank()) {
        logger.warning("internal.service-secret is not configured - /internal/** is UNPROTECTED");
        return; // zero-config local dev only, loudly flagged
    }
    if (providedSecret == null || !configuredSecret.equals(providedSecret)) {
        throw new UnauthorizedException("Invalid or missing internal service secret");
    }
}
```

Python's `_report_status()` sends that header, sourced from its own
`INTERNAL_SERVICE_SECRET` — the two must be configured to the exact same
value, which is why `docker-compose.yml` sources both from a single
`.env` entry rather than letting them drift independently.

### 8.5 Missing ownership checks (fixed)

**The gap:** `GET /api/repos/{id}`, `GET /api/repos/{id}/status`, and the
query endpoint all looked repos up with plain `repoRepository.findById(id)`
— which finds a repo regardless of who connected it. User A could read User
B's repo status, or even ask questions about User B's private code, just by
knowing (or guessing/enumerating) a UUID.

**The fix:** a new repository method, and every single-repo lookup now
routes through it:

```java
// RepoRepository
Optional<Repo> findByIdAndConnectedByUserId(UUID id, UUID connectedByUserId);

// RepoController - one shared helper used by every endpoint that takes an {id}
private Repo findOwnedRepoOrThrow(UUID repoId) {
    UUID currentUserId = CurrentUserUtil.getCurrentUserId();
    return repoRepository.findByIdAndConnectedByUserId(repoId, currentUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Repo not found: " + repoId));
}
```

**Deliberate detail:** a repo that exists but belongs to someone else
returns the *same* 404 as a repo that doesn't exist at all, rather than a
403. This is intentional — a 403 confirms to the caller that the `repo_id`
they guessed is real, just not theirs, which is itself information leakage.
404 for both cases reveals nothing.

### 8.6 Java and Python couldn't actually share cloned repos (fixed)

**The gap:** the `repo_path` string that crosses from Java's
`IndexingClient` to Python's `/index` only means anything if both processes
can see that exact filesystem path. That's true when both run on the same
machine during early local development, but false the moment they run as
separate Docker containers — `/app/repos/123` in the Java container and
`/app/repos/123` in the Python container are two *unrelated* empty
directories, not the same folder.

**The fix:** a root `docker-compose.yml` gives both services a **shared
named volume**, mounted at the same path (`/workspace/repos`) in both
containers:

```yaml
services:
  java-service:
    volumes:
      - repo-data:/workspace/repos
  python-service:
    volumes:
      - repo-data:/workspace/repos
volumes:
  repo-data:
```

Java clones into `/workspace/repos/<uuid>` inside its own container; because
that path is backed by the same Docker volume, Python's container sees the
exact same files at the exact same path. No code change was needed on
either service's side — `REPO_STORAGE_PATH` was already externalized to
config, this was purely a deployment/infrastructure fix.

**Why this matters beyond Phase 1, with a correction:** an earlier version of
this document said Phase 2's coding agent would "need this same shared-access
pattern" — true in the narrow sense that Java and Python still need to agree
on a filesystem, but stated too loosely. The agent almost certainly
shouldn't edit the same master checkout this volume holds directly — two
concurrent agent tasks editing one shared checkout is a race condition
waiting to happen. The more likely Phase 2 shape is: this shared volume
holds the canonical clone, and each agent task gets its own isolated copy or
git worktree carved out from it, edited in a sandbox, then discarded (or
turned into a diff/PR) when the task ends. That's a Phase 2 design decision,
not something to build now — noted here so this document doesn't imply an
architecture that hasn't actually been decided yet.

---

## 9. What Comes Next (Not in This Document)

- **Phase 2** — Coding agent: sandboxed file editing, test running, diff review (Python side, no Git writes yet)
- **Phase 3** — Git write ops: branch/commit/push/PR, gated behind human approval
- Everything from Phase 3 onward reuses the *same* auth (Flow A) and GitHub credential storage (Flow B) built here — this is why getting these two flows right in Phase 1 matters disproportionately to how small they look on their own.
- **Not roadmapped, parked instead** — separating platform login from GitHub-as-an-integration (so a second identity provider or a second source-control provider like Bitbucket/GitLab could be added without touching auth) is real target-state architecture, but has a hard prerequisite Phase 1 doesn't meet: an alternate way to become a platform user that isn't GitHub OAuth. See `docs/future-source-control-integrations.md` for the full design and exactly what has to become true before it's worth building.
