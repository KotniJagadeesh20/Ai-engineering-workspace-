import { Navigate } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../auth";

export function Login() {
  const { signedIn } = useAuth();
  if (signedIn) return <Navigate to="/" replace />;

  return (
    <div className="center-screen">
      <div className="card login-card">
        <h1>AI Engineering Workspace</h1>
        <p className="muted">Connect a GitHub repository, let it index, then ask questions about the code.</p>
        <a className="btn btn-primary btn-block" href={api.loginUrl}>Continue with GitHub</a>
      </div>
    </div>
  );
}
