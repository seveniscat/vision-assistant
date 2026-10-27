"""编排层骨架：Recipe = 一次确定性多步骤流水线（一期方案；LLM Agent 编排二期在此基础上扩展）。"""

from __future__ import annotations

import abc
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from typing import Any, Type

from pydantic import BaseModel

from ..capabilities.base import CapabilityProvider


@dataclass
class MediaInput:
    """归一化图片输入：上传字节或 URL，二选一。"""

    data: bytes | None = None
    filename: str | None = None
    url: str | None = None

    def validate(self) -> None:
        has_file, has_url = bool(self.data), bool(self.url)
        if has_file == has_url:  # 都有或都无
            raise ValueError("图片输入必须二选一：image（文件）或 image_url")


@dataclass
class StepHandle:
    name: str
    detail: dict[str, Any] = field(default_factory=dict)


@dataclass
class StepRecord:
    name: str
    status: str  # ok | error
    elapsed_ms: int
    detail: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {"name": self.name, "status": self.status, "elapsed_ms": self.elapsed_ms, **({"detail": self.detail} if self.detail else {})}


class RunContext:
    """单次流水线执行上下文：providers 注入 + 步骤计时记录。"""

    def __init__(self, task_id: str, providers: dict[str, CapabilityProvider]):
        self.task_id = task_id
        self.providers = providers
        self.steps: list[StepRecord] = []

    @asynccontextmanager
    async def step(self, name: str):
        handle = StepHandle(name=name)
        started = time.perf_counter()
        try:
            yield handle
        except Exception as exc:
            self.steps.append(StepRecord(name, "error", int((time.perf_counter() - started) * 1000),
                                         {"error": str(exc)[:300]}))
            raise
        self.steps.append(StepRecord(name, "ok", int((time.perf_counter() - started) * 1000), handle.detail))

    def steps_payload(self) -> list[dict]:
        return [step.to_dict() for step in self.steps]


class Recipe(abc.ABC):
    """一条流水线：名称 + 参数模型 + run()。"""

    name: str
    title: str
    description: str
    Params: Type[BaseModel]

    @abc.abstractmethod
    async def run(self, ctx: RunContext, params: BaseModel, media: MediaInput) -> dict: ...


_RECIPES: dict[str, Recipe] = {}


def register(recipe: Recipe) -> None:
    _RECIPES[recipe.name] = recipe


def get_recipe(name: str) -> Recipe | None:
    return _RECIPES.get(name)


def list_recipes() -> list[Recipe]:
    return list(_RECIPES.values())
