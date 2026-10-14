"""Tests for the RAG flow and file collection, with the LLM and vector store faked."""

from langchain_core.documents import Document
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage

from app.services import indexing_service, rag_service


class FakeRetriever:
    def invoke(self, question):
        return [
            Document(page_content="class A {}", metadata={"source": "A.java"}),
            Document(page_content="def b(): pass", metadata={"source": "b.py"}),
            Document(page_content="more A", metadata={"source": "A.java"}),
        ]


class FakeStore:
    def as_retriever(self, **kwargs):
        return FakeRetriever()


def test_answer_question_returns_answer_and_unique_sorted_sources(monkeypatch):
    monkeypatch.setattr(rag_service, "get_vectorstore", lambda repo_id: FakeStore())
    monkeypatch.setattr(
        rag_service, "ChatAnthropic",
        lambda **kw: GenericFakeChatModel(messages=iter([AIMessage(content="It defines A.")])),
    )

    result = rag_service.answer_question("r1", "what is A?")

    assert result.answer == "It defines A."
    assert result.sources == ["A.java", "b.py"]


def test_collect_documents_filters_and_labels(tmp_path):
    (tmp_path / "src").mkdir()
    (tmp_path / "src" / "Main.java").write_text("class Main {}")
    (tmp_path / "notes.txt").write_text("not indexed")
    (tmp_path / "empty.py").write_text("   \n")
    (tmp_path / "node_modules").mkdir()
    (tmp_path / "node_modules" / "dep.js").write_text("x")
    (tmp_path / "big.json").write_text("x" * (indexing_service.MAX_FILE_BYTES + 1))
    (tmp_path / "latin.py").write_bytes("caf\xe9 = 1".encode("latin-1"))

    docs = indexing_service._collect_documents(str(tmp_path))

    sources = sorted(d.metadata["source"] for d in docs)
    assert sources == ["latin.py", "src/Main.java"]
