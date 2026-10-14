import type { AuthTokens, GitHubRepo, QueryResponse, Repo } from "./types";

export const API_URL: string = (import.meta.env.VITE_API_URL ?? "http://localhost:8080").replace(/\/$/, "");

const ACCESS_KEY = "aiw.accessToken";
const REFRESH_KEY = "aiw.refreshToken";

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

// Tokens live in localStorage so a reload keeps you signed in. Trade-off: any
// XSS could read them. The app renders no raw HTML (markdown is sanitized by
// react-markdown), which is the main mitigation.
function read(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
function write(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch { /* storage unavailable - session just won't persist */ }
}

export const tokens = {
  access: () => read(ACCESS_KEY),
  refresh: () => read(REFRESH_KEY),
  set(t: AuthTokens) { write(ACCESS_KEY, t.accessToken); write(REFRESH_KEY, t.refreshToken); },
  clear() { write(ACCESS_KEY, null); write(REFRESH_KEY, null); },
};

/** Fired when the session can no longer be recovered; AuthProvider listens. */
export const SESSION_EXPIRED_EVENT = "aiw:session-expired";

async function parse(res: Response): Promise<unknown> {
  const text = await res.text();
  if (!text) return null;
  try { return JSON.parse(text); } catch { return text; }
}

function errorMessage(body: unknown, status: number): string {
  if (body && typeof body === "object" && "message" in body && typeof (body as { message: unknown }).message === "string") {
    return (body as { message: string }).message;
  }
  return `Request failed (HTTP ${status})`;
}

// Refresh tokens rotate and a reused one revokes the whole session, so
// concurrent 401s must share ONE refresh call.
let refreshing: Promise<boolean> | null = null;

function refreshSession(): Promise<boolean> {
  if (refreshing) return refreshing;
  const refreshToken = tokens.refresh();
  if (!refreshToken) return Promise.resolve(false);

  refreshing = (async () => {
    try {
      const res = await fetch(`${API_URL}/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken }),
      });
      if (!res.ok) return false;
      tokens.set((await res.json()) as AuthTokens);
      return true;
    } catch {
      return false;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

async function request<T>(path: string, init: { method?: string; body?: unknown } = {}, auth = true): Promise<T> {
  const send = () => {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    const access = tokens.access();
    if (auth && access) headers.Authorization = `Bearer ${access}`;
    return fetch(`${API_URL}${path}`, {
      method: init.method ?? "GET",
      headers,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });
  };

  let res: Response;
  try {
    res = await send();
  } catch {
    throw new ApiError(0, "Cannot reach the server. Is java-service running?");
  }

  if (res.status === 401 && auth) {
    if (await refreshSession()) {
      res = await send();
    }
    if (res.status === 401) {
      tokens.clear();
      window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
    }
  }

  const body = await parse(res);
  if (!res.ok) throw new ApiError(res.status, errorMessage(body, res.status));
  return body as T;
}

export const api = {
  loginUrl: `${API_URL}/auth/github/login`,
  exchangeCode: (code: string) => request<AuthTokens>("/auth/exchange", { method: "POST", body: { code } }, false),
  logout: async () => {
    const refreshToken = tokens.refresh();
    if (refreshToken) {
      try { await request<void>("/auth/logout", { method: "POST", body: { refreshToken } }, false); } catch { /* best effort */ }
    }
    tokens.clear();
  },
  listRepos: () => request<Repo[]>("/api/repos"),
  getRepo: (id: string) => request<Repo>(`/api/repos/${id}`),
  listGitHubRepos: () => request<GitHubRepo[]>("/api/repos/github/available"),
  connectRepo: (githubUrl: string) => request<Repo>("/api/repos/connect", { method: "POST", body: { githubUrl } }),
  query: (id: string, question: string) =>
    request<QueryResponse>(`/api/repos/${id}/query`, { method: "POST", body: { question } }),
};
