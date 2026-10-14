"""
Central config, read from environment variables (or a .env file locally).

If you know Spring: this is the same idea as an @ConfigurationProperties
class bound to application.yml - one typed object, injected wherever needed,
instead of scattering os.environ.get() calls through the codebase.
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = "postgresql+psycopg://postgres:postgres@localhost:5432/ai_engineering"
    anthropic_api_key: str = ""
    llm_model: str = "claude-sonnet-4-6"
    java_service_url: str = "http://localhost:8080"
    internal_service_secret: str = ""
    # Must match java-service's REPO_STORAGE_PATH. /index only accepts paths under it.
    repo_storage_path: str = "/workspace/repos"
    port: int = 8000

    model_config = SettingsConfigDict(env_file=".env")


# Singleton, imported wherever config is needed - equivalent to injecting
# a single @ConfigurationProperties bean everywhere in Spring.
settings = Settings()
