import { Link, Navigate, Route, Routes } from "react-router-dom";
import type { ReactNode } from "react";
import { useAuth } from "./auth";
import { Callback } from "./pages/Callback";
import { Dashboard } from "./pages/Dashboard";
import { Login } from "./pages/Login";
import { RepoChat } from "./pages/RepoChat";

function Protected({ children }: { children: ReactNode }) {
  const { signedIn } = useAuth();
  return signedIn ? <>{children}</> : <Navigate to="/login" replace />;
}

function Shell({ children }: { children: ReactNode }) {
  const { username, signOut } = useAuth();
  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">AI Engineering Workspace</Link>
        <div className="topbar-right">
          {username && <span className="muted">{username}</span>}
          <button className="btn btn-ghost" onClick={() => void signOut()}>Sign out</button>
        </div>
      </header>
      <main className="container">{children}</main>
    </>
  );
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/auth/callback" element={<Callback />} />
      <Route path="/" element={<Protected><Shell><Dashboard /></Shell></Protected>} />
      <Route path="/repos/:id" element={<Protected><Shell><RepoChat /></Shell></Protected>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
