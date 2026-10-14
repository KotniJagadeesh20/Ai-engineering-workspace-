import type { IndexStatus } from "../types";

const LABELS: Record<IndexStatus, string> = {
  CLONING: "Cloning",
  PENDING: "Queued",
  INDEXING: "Indexing",
  READY: "Ready",
  FAILED: "Failed",
};

export const isInProgress = (s: IndexStatus) => s === "CLONING" || s === "PENDING" || s === "INDEXING";

export function StatusBadge({ status }: { status: IndexStatus }) {
  return (
    <span className={`badge badge-${status.toLowerCase()}`}>
      {isInProgress(status) && <span className="spinner" aria-hidden />}
      {LABELS[status]}
    </span>
  );
}
