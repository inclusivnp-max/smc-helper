from __future__ import annotations

import asyncio
import datetime as dt
from dataclasses import dataclass

from .models import D, ZERO
from .ports import ControlPlane, ExchangeAPI


@dataclass(frozen=True)
class PortfolioGovernorConfig:
    max_daily_loss_pct: D = D("0.02")
    max_peak_drawdown_pct: D = D("0.05")
    min_available_balance_pct: D = D("0.10")
    max_open_risk_pct: D = D("0.03")
    max_api_error_streak: int = 5
    interval_seconds: float = 5.0


@dataclass(frozen=True)
class GovernorDecision:
    halt: bool
    reason: str | None
    daily_loss_pct: D
    drawdown_pct: D
    available_balance_pct: D
    open_risk_pct: D
    equity: D
    high_water_equity: D


class PortfolioGovernor:
    """Durable account-level kill switches independent of strategy signals."""

    STATE_KEY = "portfolio_governor"

    def __init__(
        self,
        exchange_api: ExchangeAPI,
        control_plane: ControlPlane,
        config: PortfolioGovernorConfig = PortfolioGovernorConfig(),
    ) -> None:
        self.api = exchange_api
        self.control = control_plane
        self.config = config
        self._stop_event = asyncio.Event()

    async def run(self) -> None:
        self._stop_event.clear()
        while not self._stop_event.is_set():
            try:
                await self.evaluate_once()
                await self.record_api_success()
            except Exception as exc:
                await self.record_api_failure(repr(exc))
            try:
                await asyncio.wait_for(
                    self._stop_event.wait(), timeout=self.config.interval_seconds
                )
            except TimeoutError:
                pass

    def stop(self) -> None:
        self._stop_event.set()

    async def evaluate_once(self) -> GovernorDecision:
        account = await self.api.get_account_snapshot()
        today = dt.datetime.now(dt.timezone.utc).date().isoformat()
        state = dict(await self.control.get_system_value(self.STATE_KEY) or {})
        start_equity_raw = state.get("day_start_equity", account.equity)
        try:
            stored_start_equity = D(str(start_equity_raw))
        except Exception:
            stored_start_equity = ZERO
        # A zero/invalid baseline can be left by an interrupted first startup.
        # Re-anchor it to the current positive equity instead of treating the
        # account as having a 100% daily loss.
        if state.get("utc_day") != today or (
            stored_start_equity <= ZERO and account.equity > ZERO
        ):
            state = {
                "utc_day": today,
                "day_start_equity": str(account.equity),
                "high_water_equity": str(account.equity),
                "api_error_streak": 0,
            }
        start_equity = D(str(state.get("day_start_equity", account.equity)))
        high_water = max(
            D(str(state.get("high_water_equity", account.equity))), account.equity
        )
        daily_loss = (
            max(ZERO, start_equity - account.equity) / start_equity
            if start_equity > ZERO
            else D("1")
        )
        drawdown = (
            max(ZERO, high_water - account.equity) / high_water
            if high_water > ZERO
            else D("1")
        )
        available_pct = (
            account.available_balance / account.equity
            if account.equity > ZERO
            else ZERO
        )
        open_risk_pct = (
            account.current_open_risk / account.equity
            if account.equity > ZERO
            else D("1")
        )
        reason: str | None = None
        if account.equity <= ZERO:
            reason = "account equity is non-positive"
        elif daily_loss >= self.config.max_daily_loss_pct:
            reason = (
                f"daily loss {daily_loss} exceeds "
                f"{self.config.max_daily_loss_pct}"
            )
        elif drawdown >= self.config.max_peak_drawdown_pct:
            reason = (
                f"peak drawdown {drawdown} exceeds "
                f"{self.config.max_peak_drawdown_pct}"
            )
        elif available_pct <= self.config.min_available_balance_pct:
            reason = (
                f"available balance ratio {available_pct} below "
                f"{self.config.min_available_balance_pct}"
            )
        elif open_risk_pct >= self.config.max_open_risk_pct:
            reason = (
                f"open risk ratio {open_risk_pct} exceeds "
                f"{self.config.max_open_risk_pct}"
            )
        elif account.risk_halt:
            reason = account.risk_halt_reason or "exchange account risk halt"

        state.update(
            {
                "high_water_equity": str(high_water),
                "last_equity": str(account.equity),
                "last_daily_loss_pct": str(daily_loss),
                "last_drawdown_pct": str(drawdown),
                "last_available_balance_pct": str(available_pct),
                "last_open_risk_pct": str(open_risk_pct),
                "last_halt_reason": reason,
            }
        )
        await self.control.set_system_value(self.STATE_KEY, state)
        if reason:
            await self.control.halt_admission(f"portfolio governor: {reason}")
        return GovernorDecision(
            halt=reason is not None,
            reason=reason,
            daily_loss_pct=daily_loss,
            drawdown_pct=drawdown,
            available_balance_pct=available_pct,
            open_risk_pct=open_risk_pct,
            equity=account.equity,
            high_water_equity=high_water,
        )

    async def record_api_failure(self, detail: str) -> None:
        state = dict(await self.control.get_system_value(self.STATE_KEY) or {})
        streak = int(state.get("api_error_streak", 0)) + 1
        state["api_error_streak"] = streak
        state["last_api_error"] = detail
        await self.control.set_system_value(self.STATE_KEY, state)
        if streak >= self.config.max_api_error_streak:
            await self.control.halt_admission(
                f"portfolio governor: API error streak={streak}: {detail}"
            )

    async def record_api_success(self) -> None:
        state = dict(await self.control.get_system_value(self.STATE_KEY) or {})
        if int(state.get("api_error_streak", 0)) != 0:
            state["api_error_streak"] = 0
            await self.control.set_system_value(self.STATE_KEY, state)
