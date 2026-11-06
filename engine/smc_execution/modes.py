from __future__ import annotations

import asyncio
import time
from dataclasses import asdict, dataclass, replace
from enum import Enum
from typing import Any, Mapping

from .config import ExecutionConfig
from .governor import PortfolioGovernor, PortfolioGovernorConfig
from .models import D, OrderKind, ZERO, encode_json
from .ports import ControlPlane, ExchangeAPI
from .recovery import RecoveryManager
from .supervisor import ProtectionSupervisor
from .engine import ExecutionEngine


class TradingMode(str, Enum):
    CAPITAL_PROTECTION = "CAPITAL_PROTECTION"
    BALANCED = "BALANCED"
    PROFITABILITY = "PROFITABILITY"
    PAUSED = "PAUSED"


@dataclass(frozen=True)
class DynamicProtectionPolicy:
    enabled: bool = True
    breakeven_activation_r: D = D("1.0")
    breakeven_lock_bps: D = D("10")
    trailing_activation_r: D | None = D("2.0")
    trailing_atr_multiple: D | None = D("1.5")

    def __post_init__(self) -> None:
        if self.breakeven_activation_r <= ZERO:
            raise ValueError("breakeven_activation_r must be positive")
        if self.breakeven_lock_bps < ZERO:
            raise ValueError("breakeven_lock_bps cannot be negative")
        if self.trailing_activation_r is not None and self.trailing_activation_r <= ZERO:
            raise ValueError("trailing_activation_r must be positive")
        if self.trailing_atr_multiple is not None and self.trailing_atr_multiple <= ZERO:
            raise ValueError("trailing_atr_multiple must be positive")


@dataclass(frozen=True)
class ModeProfile:
    mode: TradingMode
    title: str
    description: str
    execution_overrides: Mapping[str, Any]
    governor_overrides: Mapping[str, Any]
    dynamic_protection: DynamicProtectionPolicy
    risk_label: str

    def to_dict(self) -> dict[str, Any]:
        return encode_json(
            {
                "mode": self.mode.value,
                "title": self.title,
                "description": self.description,
                "execution_overrides": dict(self.execution_overrides),
                "governor_overrides": dict(self.governor_overrides),
                "dynamic_protection": asdict(self.dynamic_protection),
                "risk_label": self.risk_label,
            }
        )


DEFAULT_MODE_PROFILES: dict[TradingMode, ModeProfile] = {
    TradingMode.CAPITAL_PROTECTION: ModeProfile(
        mode=TradingMode.CAPITAL_PROTECTION,
        title="Защита капитала / безубыток",
        description=(
            "Минимальный риск, строгий отбор сигналов и ранний перенос стопа к "
            "цене входа с резервом на комиссии. Не гарантирует отсутствие убытков."
        ),
        execution_overrides={
            "risk_per_trade_pct": D("0.0015"),
            "max_portfolio_risk_pct": D("0.0075"),
            "max_position_notional": D("500"),
            "min_confidence": D("0.78"),
            "max_spread_bps": D("8"),
            "take_profit_atr_multiple": D("1.75"),
            "minimum_reward_risk": D("1.20"),
        },
        governor_overrides={
            "max_daily_loss_pct": D("0.005"),
            "max_peak_drawdown_pct": D("0.015"),
            "max_open_risk_pct": D("0.0075"),
        },
        dynamic_protection=DynamicProtectionPolicy(
            enabled=True,
            breakeven_activation_r=D("0.80"),
            breakeven_lock_bps=D("12"),
            trailing_activation_r=D("1.40"),
            trailing_atr_multiple=D("1.15"),
        ),
        risk_label="Низкий",
    ),
    TradingMode.BALANCED: ModeProfile(
        mode=TradingMode.BALANCED,
        title="Сбалансированный",
        description=(
            "Умеренный риск, стандартный фильтр качества исполнения и перенос "
            "стопа после подтверждённого движения в пользу позиции."
        ),
        execution_overrides={
            "risk_per_trade_pct": D("0.003"),
            "max_portfolio_risk_pct": D("0.015"),
            "max_position_notional": D("2000"),
            "min_confidence": D("0.70"),
            "max_spread_bps": D("10"),
            "take_profit_atr_multiple": D("2.25"),
            "minimum_reward_risk": D("1.35"),
        },
        governor_overrides={
            "max_daily_loss_pct": D("0.012"),
            "max_peak_drawdown_pct": D("0.03"),
            "max_open_risk_pct": D("0.015"),
        },
        dynamic_protection=DynamicProtectionPolicy(
            enabled=True,
            breakeven_activation_r=D("1.00"),
            breakeven_lock_bps=D("10"),
            trailing_activation_r=D("1.80"),
            trailing_atr_multiple=D("1.35"),
        ),
        risk_label="Средний",
    ),
    TradingMode.PROFITABILITY: ModeProfile(
        mode=TradingMode.PROFITABILITY,
        title="Прибыльность",
        description=(
            "Даёт прибыльным позициям больше пространства и более поздний "
            "динамический стоп. Это повышенный, но ограниченный риск, а не гарантия прибыли."
        ),
        execution_overrides={
            "risk_per_trade_pct": D("0.005"),
            "max_portfolio_risk_pct": D("0.025"),
            "max_position_notional": D("5000"),
            "min_confidence": D("0.66"),
            "max_spread_bps": D("12"),
            "take_profit_atr_multiple": D("3.0"),
            "minimum_reward_risk": D("1.60"),
        },
        governor_overrides={
            "max_daily_loss_pct": D("0.02"),
            "max_peak_drawdown_pct": D("0.05"),
            "max_open_risk_pct": D("0.025"),
        },
        dynamic_protection=DynamicProtectionPolicy(
            enabled=True,
            breakeven_activation_r=D("1.50"),
            breakeven_lock_bps=D("8"),
            trailing_activation_r=D("2.20"),
            trailing_atr_multiple=D("1.60"),
        ),
        risk_label="Повышенный",
    ),
    TradingMode.PAUSED: ModeProfile(
        mode=TradingMode.PAUSED,
        title="Пауза",
        description="Новые входы запрещены. Supervisor и recovery продолжают защищать существующие позиции.",
        execution_overrides={},
        governor_overrides={},
        dynamic_protection=DynamicProtectionPolicy(enabled=False),
        risk_label="Входы отключены",
    ),
}


class ModeSwitchRejected(RuntimeError):
    pass


class TradingModeController:
    """Durable, fail-closed mode selection for *new* entries.

    A profile switch is only allowed when the account is flat and no active orders
    or non-terminal sagas exist. That keeps an in-flight trade bound to the policy
    under which it was created.
    """

    STATE_KEY = "trading_mode"

    def __init__(
        self,
        control_plane: ControlPlane,
        exchange_api: ExchangeAPI,
        engine: ExecutionEngine,
        supervisor: ProtectionSupervisor,
        recovery: RecoveryManager,
        governor: PortfolioGovernor,
        *,
        base_execution_config: ExecutionConfig,
        base_governor_config: PortfolioGovernorConfig,
        profiles: Mapping[TradingMode, ModeProfile] = DEFAULT_MODE_PROFILES,
        default_mode: TradingMode = TradingMode.CAPITAL_PROTECTION,
    ) -> None:
        self.control = control_plane
        self.api = exchange_api
        self.engine = engine
        self.supervisor = supervisor
        self.recovery = recovery
        self.governor = governor
        self.base_execution_config = base_execution_config
        self.base_governor_config = base_governor_config
        self.profiles = dict(profiles)
        self.default_mode = default_mode
        self._current = self.profiles[default_mode]
        self._active_profile = self.profiles[default_mode]
        self._lock = asyncio.Lock()

    @property
    def current(self) -> ModeProfile:
        return self._current

    @property
    def active_profile(self) -> ModeProfile:
        """Runtime policy retained while PAUSED blocks only new admissions."""
        return self._active_profile

    def list_profiles(self) -> tuple[ModeProfile, ...]:
        return tuple(self.profiles[mode] for mode in TradingMode)

    async def initialize(self) -> ModeProfile:
        raw = await self.control.get_system_value(self.STATE_KEY)
        try:
            mode = TradingMode(str((raw or {}).get("mode", self.default_mode.value)))
        except ValueError:
            mode = self.default_mode

        if mode is TradingMode.PAUSED:
            try:
                active_mode = TradingMode(
                    str((raw or {}).get("active_mode", self.default_mode.value))
                )
            except ValueError:
                active_mode = self.default_mode
            if active_mode is TradingMode.PAUSED:
                active_mode = self.default_mode
            self._apply_in_memory(self.profiles[active_mode])
            self._current = self.profiles[TradingMode.PAUSED]
            await self.control.halt_admission("trading mode is PAUSED")
        else:
            self._apply_in_memory(self.profiles[mode])

        if raw is None:
            await self._persist(mode, actor="system:init")
        return self._current

    async def switch(self, mode: TradingMode, *, actor: str) -> ModeProfile:
        async with self._lock:
            if mode is self._current.mode:
                return self._current
            if mode is TradingMode.PAUSED:
                await self.control.halt_admission(f"operator selected PAUSED: {actor}")
                # PAUSED is an admission overlay. It must not mutate the policy
                # used by an already-running execution or an existing position.
                self._current = self.profiles[TradingMode.PAUSED]
                await self._persist(mode, actor=actor)
                return self._current

            reasons = await self._unsafe_switch_reasons()
            if reasons:
                raise ModeSwitchRejected("; ".join(reasons))

            # Keep admission closed while all policy holders are replaced atomically
            # from the runtime's perspective.
            await self.control.halt_admission(f"mode switch to {mode.value} in progress")
            self._apply_in_memory(self.profiles[mode])
            await self._persist(mode, actor=actor)
            return self._current

    async def _unsafe_switch_reasons(self) -> list[str]:
        reasons: list[str] = []
        nonterminal = await self.control.list_nonterminal()
        active = list(nonterminal)
        if active:
            reasons.append(f"non-terminal executions={len(active)}")
        positions = [p for p in await self.api.list_positions() if not p.is_flat]
        if positions:
            reasons.append(f"open positions={len(positions)}")
        regular = await self.api.list_open_orders(None, OrderKind.REGULAR)
        algo = await self.api.list_open_orders(None, OrderKind.ALGO)
        if regular or algo:
            reasons.append(f"open orders={len(regular) + len(algo)}")
        return reasons

    def _apply_in_memory(self, profile: ModeProfile) -> None:
        execution_config = replace(
            self.base_execution_config, **dict(profile.execution_overrides)
        )
        governor_config = replace(
            self.base_governor_config, **dict(profile.governor_overrides)
        )
        self.engine.config = execution_config
        self.engine.quality_guard.config = execution_config
        self.supervisor.config = execution_config
        self.supervisor.dynamic_protection = profile.dynamic_protection
        self.recovery.execution_config = execution_config
        self.governor.config = governor_config
        self._active_profile = profile
        self._current = profile

    async def _persist(self, mode: TradingMode, *, actor: str) -> None:
        await self.control.set_system_value(
            self.STATE_KEY,
            {
                "mode": mode.value,
                "active_mode": self._active_profile.mode.value,
                "actor": actor,
                "updated_at_ms": int(time.time() * 1000),
            },
        )
