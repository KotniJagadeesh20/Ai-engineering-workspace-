export type IndexStatus = "PENDING" | "CLONING" | "INDEXING" | "READY" | "FAILED";

export interface Repo {
  id: string;
  githubUrl: string;
  name: string;
  owner: string;
  provider: string;
  status: IndexStatus;
  errorMessage: string | null;
  createdAt: string;
}

// GitHubRepoSummary is serialized with GitHub's own snake_case names.
export interface GitHubRepo {
  id: number;
  name: string;
  full_name: string;
  private: boolean;
  clone_url: string;
  default_branch: string | null;
  language: string | null;
  stargazers_count: number | null;
  updated_at: string | null;
}

export interface QueryResponse {
  answer: string;
  sources: string[];
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
}
