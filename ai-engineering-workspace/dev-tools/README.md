# Dev Tools — Test Harness

`test-harness.html` is a single static file (no build step, no framework) for
manually exercising the Phase 1 API from a browser instead of hand-typing
curl commands. It is **not** the product UI — see the main README's note on
when a real frontend becomes worth building.

## Usage

Just open the file directly in a browser:

```bash
open dev-tools/test-harness.html      # macOS
xdg-open dev-tools/test-harness.html  # Linux
# or just double-click it
```

Both services need CORS enabled for whatever origin this file loads from —
if you open it as a local `file://` path, both services already default to
`allow_origins: "*"` / `cors-allowed-origins: "*"` for exactly this reason.
Tighten both before deploying anywhere real (see each service's README).

## Flow

1. **Check both services** — confirms Java (`:8080`) and Python (`:8000`)
   are actually running before you try anything else.
2. **Login with GitHub** — opens the OAuth flow in a new tab. After you
   approve, Java redirects to `{frontendUrl}/auth/callback?code=...`.
   That path likely 404s (expected — nothing is built to live there yet).
   Copy the code value straight out of the browser's address bar and paste
   it into the harness — it's short-lived (60s) and single-use.
3. **Browse / connect / query** — the rest of the panels map directly to the
   endpoints documented in `docs/phase1-implementation-guide.md`.

All requests and responses are logged at the bottom of the page, including
failures — check there first if something doesn't behave as expected.

## Why tokens aren't persisted across reloads

They're kept in a plain in-memory JS variable rather than `localStorage`, so
you'll need to re-paste the login code if you refresh the page. This is a
deliberate simplification for a throwaway dev tool — feel free to add
`localStorage` yourself if the re-pasting gets annoying during a long
testing session.
