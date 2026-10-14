import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import { Link, useParams } from "react-router-dom";
import { api } from "../api";
import { StatusBadge } from "../components/StatusBadge";
import type { Repo } from "../types";

interface Turn {
  question: string;
  answer?: string;
  sources?: string[];
  error?: string;
}

export function RepoChat() {
  const { id } = useParams<{ id: string }>();
  const [repo, setRepo] = useState<Repo | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [question, setQuestion] = useState("");
  const [asking, setAsking] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!id) return;
    api.getRepo(id).then(setRepo).catch((e: Error) => setLoadError(e.message));
  }, [id]);

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: "smooth" }); }, [turns]);

  const ask = async () => {
    const q = question.trim();
    if (!q || !id || asking) return;
    setQuestion("");
    setAsking(true);
    setTurns((t) => [...t, { question: q }]);
    const update = (patch: Partial<Turn>) =>
      setTurns((t) => t.map((turn, i) => (i === t.length - 1 ? { ...turn, ...patch } : turn)));
    try {
      const res = await api.query(id, q);
      update({ answer: res.answer, sources: res.sources });
    } catch (e) {
      update({ error: (e as Error).message });
    } finally {
      setAsking(false);
    }
  };

  if (loadError) {
    return (
      <>
        <Link to="/" className="back">← Repositories</Link>
        <p className="error-text">{loadError}</p>
      </>
    );
  }

  return (
    <div className="chat">
      <Link to="/" className="back">← Repositories</Link>
      <div className="row-between">
        <h2>{repo ? `${repo.owner}/${repo.name}` : "Loading…"}</h2>
        {repo && <StatusBadge status={repo.status} />}
      </div>

      <div className="turns">
        {turns.length === 0 && (
          <p className="muted">
            Ask anything about this codebase — e.g. “How does authentication work?” or “Where is the payment flow?”
            Answers are grounded in retrieved code excerpts and cite their source files.
          </p>
        )}
        {turns.map((t, i) => (
          <div key={i} className="turn">
            <div className="bubble bubble-user">{t.question}</div>
            {t.error ? (
              <div className="bubble bubble-error">{t.error}</div>
            ) : t.answer === undefined ? (
              <div className="bubble"><span className="spinner" /> Thinking…</div>
            ) : (
              <div className="bubble">
                <div className="markdown"><ReactMarkdown>{t.answer}</ReactMarkdown></div>
                {t.sources && t.sources.length > 0 && (
                  <div className="sources">
                    {t.sources.map((s) => <code key={s} className="chip">{s}</code>)}
                  </div>
                )}
              </div>
            )}
          </div>
        ))}
        <div ref={endRef} />
      </div>

      <form
        className="composer"
        onSubmit={(e) => { e.preventDefault(); void ask(); }}
      >
        <input
          className="input"
          placeholder="Ask a question about this repository…"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          disabled={repo?.status !== "READY"}
        />
        <button className="btn btn-primary" disabled={!question.trim() || asking || repo?.status !== "READY"}>Ask</button>
      </form>
    </div>
  );
}
