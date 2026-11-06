from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable
from typing import TypeVar

from .errors import DeadlineExceeded

T = TypeVar("T")


class DeadlineBudget:
    def __init__(self, total_ms: int, *, label: str = "operation") -> None:
        if total_ms <= 0:
            raise ValueError("total_ms must be positive")
        self.label = label
        self.started = time.monotonic()
        self.deadline = self.started + total_ms / 1000

    @property
    def remaining_ms(self) -> int:
        return max(0, int((self.deadline - time.monotonic()) * 1000))

    @property
    def elapsed_ms(self) -> int:
        return int((time.monotonic() - self.started) * 1000)

    @property
    def expired(self) -> bool:
        return self.remaining_ms <= 0

    async def run(
        self,
        awaitable: Awaitable[T],
        *,
        operation: str,
        cap_ms: int | None = None,
    ) -> T:
        remaining = self.remaining_ms
        if remaining <= 0:
            if hasattr(awaitable, "close"):
                awaitable.close()  # type: ignore[attr-defined]
            raise DeadlineExceeded(f"{self.label}: deadline expired before {operation}")
        timeout_ms = min(remaining, cap_ms) if cap_ms is not None else remaining
        try:
            async with asyncio.timeout(max(0.001, timeout_ms / 1000)):
                return await awaitable
        except TimeoutError as exc:
            raise DeadlineExceeded(
                f"{self.label}: {operation} exceeded {timeout_ms} ms"
            ) from exc
