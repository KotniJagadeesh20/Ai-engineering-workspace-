"""
Indexing service - the Python equivalent of a Spring @Service class, just
orchestrated as plain functions rather than an injected bean (FastAPI doesn't
need a DI container the way Spring does; module-level functions + a
singleton settings object cover the same ground for a service this size).

Pipeline: walk repo -> filter to code files -> chunk -> embed -> store in
pgvector, in a collection named after the repo_id. Then report back to Java.
"""

import os
import logging

import httpx
from langchain_community.document_loaders import TextLoader
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_postgres import PGVector
from langchain_core.documents import Document

from app.config import settings

logger = logging.getLogger(__name__)

# Only index files that are actually source/docs - skip binaries, build
# artifacts, dependency folders. This list is deliberately conservative;
# extend it as you connect repos in other languages.
INCLUDED_EXTENSIONS = {
    ".java", ".py", ".js", ".ts", ".tsx", ".jsx", ".go", ".rb", ".rs",
    ".md", ".yml", ".yaml", ".json", ".sql", ".gradle", ".xml",
}

EXCLUDED_DIRS = {
    ".git", "node_modules", "target", "build", "dist", ".idea", ".vscode",
    "__pycache__", ".venv", "venv", ".mvn",
}

# Chunking is deliberately simple/generic here (fixed size + overlap) rather
# than language-aware splitting. Splitting code purely by character count can
# cut a function in half mid-body, which hurts retrieval quality - a common
# Phase 1 upgrade is swapping this for RecursiveCharacterTextSplitter.from_language(...)
# per file type once you've felt that pain on a real repo.
CHUNK_SIZE = 1000
CHUNK_OVERLAP = 200


def _collect_documents(repo_path: str) -> list[Document]:
    """Walks the cloned repo on disk and loads eligible files as Documents."""
    documents: list[Document] = []

    for root, dirs, files in os.walk(repo_path):
        # Prune excluded directories in place so os.walk doesn't descend into them
        dirs[:] = [d for d in dirs if d not in EXCLUDED_DIRS]

        for filename in files:
            _, ext = os.path.splitext(filename)
            if ext not in INCLUDED_EXTENSIONS:
                continue

            file_path = os.path.join(root, filename)
            try:
                loader = TextLoader(file_path, autodetect_encoding=True)
                loaded = loader.load()
                # Store a repo-relative path so answers can cite readable
                # locations (e.g. "PaymentController.java") instead of the
                # full /data/repos/<uuid>/... disk path.
                relative_path = os.path.relpath(file_path, repo_path)
                for doc in loaded:
                    doc.metadata["source"] = relative_path
                documents.extend(loaded)
            except Exception as e:
                # Don't let one unreadable file (binary misdetected as text,
                # weird encoding, etc) kill the whole indexing run.
                logger.warning("Skipping file %s: %s", file_path, e)

    return documents


def _get_embeddings() -> HuggingFaceEmbeddings:
    # Local model - no API key needed, good default for getting Phase 1
    # running without adding a paid dependency. Swap for OpenAIEmbeddings
    # (langchain-openai) later if retrieval quality needs improving.
    return HuggingFaceEmbeddings(model_name="all-MiniLM-L6-v2")


def get_vectorstore(repo_id: str) -> PGVector:
    """Returns the pgvector-backed store scoped to one repo's collection.
    Reused by both indexing (writes) and RAG query (reads)."""
    return PGVector(
        embeddings=_get_embeddings(),
        collection_name=repo_id,
        connection=settings.database_url,
        use_jsonb=True,
    )


def index_repository(repo_id: str, repo_path: str) -> None:
    """
    The core indexing pipeline. Runs in a background task (see
    routers/index_router.py) so the HTTP call from Java returns immediately -
    this can take anywhere from seconds to minutes depending on repo size.
    """
    try:
        documents = _collect_documents(repo_path)
        if not documents:
            raise ValueError(f"No indexable files found under {repo_path}")

        splitter = RecursiveCharacterTextSplitter(
            chunk_size=CHUNK_SIZE,
            chunk_overlap=CHUNK_OVERLAP,
        )
        chunks = splitter.split_documents(documents)

        vectorstore = get_vectorstore(repo_id)
        # Clear out any previous indexing run for this repo before writing -
        # otherwise re-indexing after a code change just appends duplicates.
        vectorstore.delete_collection()
        vectorstore.create_collection()
        vectorstore.add_documents(chunks)

        logger.info("Indexed %d chunks from %d files for repo %s",
                    len(chunks), len(documents), repo_id)

        _report_status(repo_id, status="READY")

    except Exception as e:
        logger.exception("Indexing failed for repo %s", repo_id)
        _report_status(repo_id, status="FAILED", error_message=str(e))


def _report_status(repo_id: str, status: str, error_message: str | None = None) -> None:
    """Calls back to the Java service's internal PATCH endpoint - this is
    the other half of the async hand-off that Java's IndexingClient started.

    Note the /internal prefix: this is deliberately NOT the same path a user
    could hit (that would be /api/repos/...). Java gates this path with a
    shared secret rather than a user JWT, since Python isn't a logged-in
    user - see InternalRepoController on the Java side."""
    try:
        headers = {}
        if settings.internal_service_secret:
            headers["X-Internal-Secret"] = settings.internal_service_secret
        else:
            logger.warning(
                "INTERNAL_SERVICE_SECRET is not set - the callback to Java will "
                "only succeed if Java's internal.service-secret is ALSO unset "
                "(local dev only). Set both to the same value before this runs "
                "anywhere reachable by anyone else."
            )

        httpx.patch(
            f"{settings.java_service_url}/internal/repos/{repo_id}/index-status",
            json={"status": status, "errorMessage": error_message},
            headers=headers,
            timeout=10.0,
        )
    except Exception:
        # If the callback itself fails, log it - don't raise, since there's
        # no one left "waiting" synchronously for this background task.
        logger.exception("Failed to report index status back to Java for repo %s", repo_id)
