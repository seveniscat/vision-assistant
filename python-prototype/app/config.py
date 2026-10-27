"""vision-assistant 配置（环境变量 / .env，前缀 VA_）。"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


def _split_csv(raw: str) -> list[str]:
    return [item.strip() for item in raw.split(",") if item.strip()]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="VA_", env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    # ── 服务 ──
    host: str = "0.0.0.0"
    port: int = 9000
    data_dir: Path = Path("data")

    # ── 鉴权 ──
    api_keys: str = ""  # 逗号分隔；空 = 关闭鉴权（仅限本机开发）

    # ── 下游能力服务 ──
    ocr_base_url: str = "http://127.0.0.1:48763"
    ipcheck_base_url: str = "http://127.0.0.1:8001"

    # ── 下游调用 ──
    http_timeout_seconds: float = 600.0
    ocr_concurrency: int = 1
    ipcheck_concurrency: int = 2
    ocr_poll_interval_seconds: float = 2.0
    ocr_poll_timeout_seconds: float = 900.0

    # ── 任务回调 ──
    task_webhook_max_attempts: int = 3
    task_webhook_backoff_seconds: float = 2.0
    webhook_secret: str = ""

    # ── CORS ──
    cors_origins: str = ""

    @property
    def api_key_list(self) -> list[str]:
        return _split_csv(self.api_keys)

    @property
    def cors_origin_list(self) -> list[str]:
        return _split_csv(self.cors_origins)

    @property
    def auth_enabled(self) -> bool:
        return bool(self.api_key_list)


@lru_cache
def get_settings() -> Settings:
    return Settings()
