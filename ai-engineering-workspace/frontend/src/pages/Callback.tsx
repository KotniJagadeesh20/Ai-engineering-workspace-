import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../auth";

/** Landing page for GitHub's redirect: trades the single-use ?code for tokens. */
export function Callback() {
  const [params] = useSearchParams();
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  // The code is single-use; React StrictMode runs effects twice in dev.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    const code = params.get("code");
    if (!code) { setError("No login code was provided."); return; }

    api.exchangeCode(code)
      .then((t) => { signIn(t); navigate("/", { replace: true }); })
      .catch((e: Error) => setError(e.message));
  }, [params, signIn, navigate]);

  return (
    <div className="center-screen">
      <div className="card login-card">
        {error ? (
          <>
            <h2>Sign-in failed</h2>
            <p className="error-text">{error}</p>
            <Link className="btn btn-primary btn-block" to="/login">Try again</Link>
          </>
        ) : (
          <p className="muted"><span className="spinner" /> Signing you in…</p>
        )}
      </div>
    </div>
  );
}
