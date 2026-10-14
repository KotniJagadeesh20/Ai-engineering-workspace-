import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, SESSION_EXPIRED_EVENT, tokens } from "./api";
import type { AuthTokens } from "./types";

interface AuthState {
  signedIn: boolean;
  username: string | null;
  signIn: (t: AuthTokens) => void;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

/** Reads the display name from the access token's payload. Display only - the server never trusts this. */
function usernameFrom(accessToken: string | null): string | null {
  if (!accessToken) return null;
  try {
    const payload = accessToken.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const claims = JSON.parse(atob(payload)) as { username?: string };
    return claims.username ?? null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [signedIn, setSignedIn] = useState(() => tokens.refresh() !== null);
  const [username, setUsername] = useState(() => usernameFrom(tokens.access()));

  const signIn = useCallback((t: AuthTokens) => {
    tokens.set(t);
    setUsername(usernameFrom(t.accessToken));
    setSignedIn(true);
  }, []);

  const signOut = useCallback(async () => {
    await api.logout();
    setUsername(null);
    setSignedIn(false);
  }, []);

  useEffect(() => {
    const onExpired = () => { setUsername(null); setSignedIn(false); };
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
  }, []);

  const value = useMemo(() => ({ signedIn, username, signIn, signOut }), [signedIn, username, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}
