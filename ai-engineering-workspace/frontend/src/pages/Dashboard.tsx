import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import { isInProgress, StatusBadge } from "../components/StatusBadge";
import type { GitHubRepo, Repo } from "../types";

const POLL_MS = 3000;
const normalize = (url: string) => url.replace(/\.git$/, "").toLowerCase();

export function Dashboard() {
  const [repos, setRepos] = useState<Repo[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setRepos(await api.listRepos());
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  // Poll only while something is still cloning/indexing.
  const anyInProgress = repos?.some((r) => isInProgress(r.status)) ?? false;
  useEffect(() => {
    if (!anyInProgress) return;
    const t = setInterval(() => void load(), POLL_MS);
    return () => clearInterval(t);
  }, [anyInProgress, load]);

  const connectedUrls = useMemo(() => new Set((repos ?? []).map((r) => normalize(r.githubUrl))), [repos]);

  return (
    <>
      <section>
        <h2>Your repositories</h2>
        {error && <p className="error-text">{error}</p>}
        {repos === null && !error && <p className="muted"><span className="spinner" /> Loading…</p>}
        {repos?.length === 0 && <p className="muted">Nothing connected yet. Add a repository below.</p>}
        <div className="grid">
          {repos?.map((r) => <RepoCard key={r.id} repo={r} />)}
        </div>
      </section>

      <ConnectPanel connectedUrls={connectedUrls} onConnected={load} />
    </>
  );
}

function RepoCard({ repo }: { repo: Repo }) {
  return (
    <div className="card repo-card">
      <div className="row-between">
        <strong className="repo-name">{repo.owner}/{repo.name}</strong>
        <StatusBadge status={repo.status} />
      </div>
      {repo.status === "FAILED" && (
        <p className="error-text small">{repo.errorMessage ?? "Something went wrong."} Reconnect it below to try again.</p>
      )}
      {repo.status === "READY" ? (
        <Link className="btn btn-primary" to={`/repos/${repo.id}`}>Ask questions</Link>
      ) : (
        <button className="btn" disabled>{repo.status === "FAILED" ? "Unavailable" : "Preparing…"}</button>
      )}
    </div>
  );
}

function ConnectPanel({ connectedUrls, onConnected }: { connectedUrls: Set<string>; onConnected: () => Promise<void> }) {
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [available, setAvailable] = useState<GitHubRepo[] | null>(null);
  const [loadingAvailable, setLoadingAvailable] = useState(false);
  const [filter, setFilter] = useState("");

  const connect = async (githubUrl: string) => {
    setBusy(githubUrl);
    setError(null);
    try {
      await api.connectRepo(githubUrl);
      setUrl("");
      await onConnected();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const browse = async () => {
    setLoadingAvailable(true);
    setError(null);
    try {
      setAvailable(await api.listGitHubRepos());
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoadingAvailable(false);
    }
  };

  const shown = (available ?? []).filter((r) => r.full_name.toLowerCase().includes(filter.toLowerCase()));

  return (
    <section>
      <h2>Add a repository</h2>
      <div className="card">
        <form
          className="row"
          onSubmit={(e) => { e.preventDefault(); if (url.trim()) void connect(url.trim()); }}
        >
          <input
            className="input"
            placeholder="https://github.com/owner/repo"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
          />
          <button className="btn btn-primary" disabled={!url.trim() || busy !== null}>Connect</button>
        </form>

        {error && <p className="error-text small">{error}</p>}

        <hr />

        {available === null ? (
          <button className="btn" onClick={() => void browse()} disabled={loadingAvailable}>
            {loadingAvailable ? "Loading from GitHub…" : "Browse my GitHub repositories"}
          </button>
        ) : (
          <>
            <input className="input" placeholder="Filter…" value={filter} onChange={(e) => setFilter(e.target.value)} />
            <ul className="list">
              {shown.map((r) => {
                const already = connectedUrls.has(normalize(r.clone_url));
                return (
                  <li key={r.id} className="row-between">
                    <span>
                      {r.full_name} <span className="chip">{r.private ? "private" : "public"}</span>
                      {r.language && <span className="muted small"> · {r.language}</span>}
                    </span>
                    <button
                      className="btn"
                      disabled={already || busy !== null}
                      onClick={() => void connect(r.clone_url)}
                    >
                      {already ? "Connected" : busy === r.clone_url ? "Connecting…" : "Connect"}
                    </button>
                  </li>
                );
              })}
              {shown.length === 0 && <li className="muted">No matching repositories.</li>}
            </ul>
          </>
        )}
      </div>
    </section>
  );
}
