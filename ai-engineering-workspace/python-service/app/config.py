"""
Central config, read from environment variables (or a .env file locally).

If you know Spring: this is the same idea as an @ConfigurationProperties
class bound to application.yml - one typed object, injected wherever needed,
instead of scattering os.environ.get() calls through the codebase.
"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql+psycopg://postgres:postgres@localhost:5432/ai_engineering"
    anthropic_api_key: str = ""
    java_service_url: str = "http://localhost:8080"
    internal_service_secret: str = ""
    port: int = 8000

    class Config:
        env_file = ".env"


# Singleton, imported wherever config is needed - equivalent to injecting
# a single @ConfigurationProperties bean everywhere in Spring.
settings = Settings()
