from __future__ import annotations

import hashlib
import time
from dataclasses import asdict, dataclass, field
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR, getcontext
from enum import Enum
from typing import Any, Mapping

getcontext().prec = 34
D = Decimal
ZERO = D("0")
ONE = D("1")
BPS = D("10000")


class Action(str, Enum):
    LONG = "LONG"
    SHORT = "SHORT"


class Side(str, Enum):
    BUY = "BUY"
    SELL = "SELL"


class PositionMode(str, Enum):
    ONEWAY = "ONEWAY"
    HEDGE = "HEDGE"


class OneWayPolicy(str, Enum):
    REJECT_IF_ANY_POSITION = "REJECT_IF_ANY_POSITION"
    CLOSE_THEN_OPEN = "CLOSE_THEN_OPEN"


class PartialFillPolicy(str, Enum):
    ACCEPT_AND_PROTECT = "ACCEPT_AND_PROTECT"
    FLATTEN_BELOW_MIN = "FLATTEN_BELOW_MIN"


class OrderKind(str, Enum):
    REGULAR = "REGULAR"
    ALGO = "ALGO"


class OrderStatus(str, Enum):
    NEW = "NEW"
    PARTIALLY_FILLED = "PARTIALLY_FILLED"
    FILLED = "FILLED"
    CANCELED = "CANCELED"
    EXPIRED = "EXPIRED"
    REJECTED = "REJECTED"
    EXPIRED_IN_MATCH = "EXPIRED_IN_MATCH"
    TRIGGERING = "TRIGGERING"
    TRIGGERED = "TRIGGERED"
    FINISHED = "FINISHED"
    UNKNOWN = "UNKNOWN"

    @property
    def terminal(self) -> bool:
        return self in {
            self.FILLED,
            self.CANCELED,
            self.EXPIRED,
            self.REJECTED,
            self.EXPIRED_IN_MATCH,
            self.FINISHED,
        }

    @property
    def active(self) -> bool:
        return self in {
            self.NEW,
            self.PARTIALLY_FILLED,
            self.TRIGGERING,
            self.TRIGGERED,
        }


class ExecutionStage(str, Enum):
    CLAIMED = "CLAIMED"
    PRECHECKED = "PRECHECKED"
    RISK_RESERVED = "RISK_RESERVED"
    ENTRY_SUBMITTING = "ENTRY_SUBMITTING"
    ENTRY_OPEN = "ENTRY_OPEN"
    ENTRY_TERMINAL = "ENTRY_TERMINAL"
    FLATTENING = "FLATTENING"
    PROTECTION_PENDING = "PROTECTION_PENDING"
    PROTECTED = "PROTECTED"
    CLOSED = "CLOSED"
    REJECTED = "REJECTED"
    ABORTED = "ABORTED"
    FAILED_SAFE = "FAILED_SAFE"
    FAILED_UNCERTAIN = "FAILED_UNCERTAIN"

    @property
    def terminal(self) -> bool:
        return self in {
            self.CLOSED,
            self.REJECTED,
            self.ABORTED,
            self.FAILED_SAFE,
        }


@dataclass(frozen=True)
class Signal:
    signal_id: str
    symbol: str
    action: Action
    confidence: D
    created_at_ms: int
    reference_price: D | None = None
    strategy_id: str | None = None
    strategy_version: str | None = None
    research_run_id: str | None = None
    code_fingerprint: str | None = None
    metadata: Mapping[str, Any] = field(default_factory=dict)


def parse_signal(raw: Signal | Mapping[str, Any]) -> Signal:
    """Parse an external signal into the immutable domain model.

    The function is deliberately public so governance, queues and the execution
    engine use one canonical parser instead of reaching into engine internals.
    """
    if isinstance(raw, Signal):
        return raw
    try:
        signal_id = str(raw.get("signal_id") or raw.get("id") or "")
        symbol = str(raw["symbol"]).upper().strip()
        action = Action(str(raw["action"]).upper())
        confidence = D(str(raw.get("confidence", raw.get("combined_signal", "0"))))
        created = int(raw.get("created_at_ms", raw.get("timestamp_ms", 0)))
        ref = raw.get("reference_price")
        strategy_id = raw.get("strategy_id")
        strategy_version = raw.get("strategy_version")
        research_run_id = raw.get("research_run_id")
        code_fingerprint = raw.get("code_fingerprint")
        metadata = raw.get("metadata") or {}
        if not isinstance(metadata, Mapping):
            raise TypeError("signal metadata must be a mapping")
    except Exception as exc:
        raise ValueError(f"Invalid signal: {raw!r}") from exc
    if not signal_id:
        canonical = f"{symbol}|{action.value}|{created}|{confidence}"
        signal_id = hashlib.sha256(canonical.encode()).hexdigest()[:24]
    if not symbol or created <= 0:
        raise ValueError("Signal requires symbol and created_at_ms")
    if confidence < ZERO or confidence > ONE:
        raise ValueError("Signal confidence must be between 0 and 1")
    return Signal(
        signal_id=signal_id,
        symbol=symbol,
        action=action,
        confidence=confidence,
        created_at_ms=created,
        reference_price=None if ref is None else D(str(ref)),
        strategy_id=None if strategy_id is None else str(strategy_id),
        strategy_version=None if strategy_version is None else str(strategy_version),
        research_run_id=None if research_run_id is None else str(research_run_id),
        code_fingerprint=None if code_fingerprint is None else str(code_fingerprint),
        metadata=dict(metadata),
    )


@dataclass(frozen=True)
class SymbolRules:
    symbol: str
    tick_size: D
    step_size: D
    min_qty: D
    max_qty: D
    min_notional: D
    max_notional: D | None = None
    status: str = "TRADING"
    market_step_size: D | None = None
    market_min_qty: D | None = None
    market_max_qty: D | None = None


@dataclass(frozen=True)
class Quote:
    symbol: str
    bid: D
    ask: D
    bid_qty: D
    ask_qty: D
    exchange_time_ms: int
    sequence_id: int | None = None
    exchange_age_at_receive_ms: int | None = None
    received_monotonic: float = field(default_factory=time.monotonic)

    @property
    def mid(self) -> D:
        return (self.bid + self.ask) / D("2")

    @property
    def spread(self) -> D:
        return self.ask - self.bid

    @property
    def spread_bps(self) -> D:
        return self.spread / self.mid * BPS if self.mid > ZERO else D("Infinity")


@dataclass(frozen=True)
class AccountSnapshot:
    equity: D
    available_balance: D
    max_new_position_notional: D | None
    current_open_risk: D = ZERO
    position_mode: PositionMode = PositionMode.ONEWAY
    risk_halt: bool = False
    risk_halt_reason: str | None = None
    update_time_ms: int = 0


@dataclass(frozen=True)
class PositionSnapshot:
    symbol: str
    position_mode: PositionMode
    position_side: str
    quantity: D
    entry_price: D
    mark_price: D
    update_time_ms: int

    @property
    def abs_qty(self) -> D:
        return abs(self.quantity)

    @property
    def is_flat(self) -> bool:
        return self.quantity == ZERO


@dataclass(frozen=True)
class Kline:
    open_time_ms: int
    close_time_ms: int
    high: D
    low: D
    close: D
    is_closed: bool = True


@dataclass(frozen=True)
class OrderRequest:
    symbol: str
    side: Side
    order_type: str
    client_order_id: str
    kind: OrderKind = OrderKind.REGULAR
    quantity: D | None = None
    price: D | None = None
    stop_price: D | None = None
    time_in_force: str | None = None
    reduce_only: bool = False
    post_only: bool = False
    working_type: str | None = None
    price_protect: bool | None = None
    position_side: str | None = None
    close_position: bool = False
    self_trade_prevention_mode: str | None = None


@dataclass(frozen=True)
class OrderSnapshot:
    order_id: str
    client_order_id: str
    symbol: str
    side: Side
    status: OrderStatus
    orig_qty: D
    executed_qty: D
    cumulative_quote_qty: D
    avg_price: D
    update_time_ms: int
    kind: OrderKind = OrderKind.REGULAR
    order_type: str = ""
    price: D = ZERO
    stop_price: D = ZERO

    @property
    def remaining_qty(self) -> D:
        return max(ZERO, self.orig_qty - self.executed_qty)


@dataclass(frozen=True)
class ProtectionOrders:
    symbol: str
    action: Action
    stop_order_id: str
    stop_client_order_id: str
    stop_kind: OrderKind
    take_profit_order_id: str | None
    take_profit_client_order_id: str | None
    take_profit_kind: OrderKind | None


@dataclass(frozen=True)
class ExecutionResult:
    status: str
    signal_id: str
    symbol: str
    action: Action
    requested_qty: D
    executed_qty: D
    avg_price: D | None
    arrival_price: D
    implementation_shortfall_bps: D | None
    fill_ratio: D
    elapsed_ms: int
    maker_qty: D = ZERO
    taker_qty: D = ZERO
    stop_price: D | None = None
    take_profit_price: D | None = None
    stop_order_id: str | None = None
    take_profit_order_id: str | None = None
    warnings: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ExecutionResult":
        return cls(
            status=str(value["status"]),
            signal_id=str(value["signal_id"]),
            symbol=str(value["symbol"]),
            action=Action(value["action"]),
            requested_qty=D(str(value["requested_qty"])),
            executed_qty=D(str(value["executed_qty"])),
            avg_price=None if value.get("avg_price") is None else D(str(value["avg_price"])),
            arrival_price=D(str(value["arrival_price"])),
            implementation_shortfall_bps=(
                None
                if value.get("implementation_shortfall_bps") is None
                else D(str(value["implementation_shortfall_bps"]))
            ),
            fill_ratio=D(str(value["fill_ratio"])),
            elapsed_ms=int(value["elapsed_ms"]),
            maker_qty=D(str(value.get("maker_qty", "0"))),
            taker_qty=D(str(value.get("taker_qty", "0"))),
            stop_price=None if value.get("stop_price") is None else D(str(value["stop_price"])),
            take_profit_price=(
                None
                if value.get("take_profit_price") is None
                else D(str(value["take_profit_price"]))
            ),
            stop_order_id=value.get("stop_order_id"),
            take_profit_order_id=value.get("take_profit_order_id"),
            warnings=tuple(value.get("warnings", ())),
        )


@dataclass(frozen=True)
class ExecutionRecord:
    execution_id: str
    symbol: str
    action: Action
    stage: ExecutionStage
    version: int
    created_at_ms: int
    updated_at_ms: int
    payload: Mapping[str, Any]
    result: Mapping[str, Any] | None = None

    @property
    def terminal(self) -> bool:
        return self.stage.terminal


@dataclass(frozen=True)
class RiskReservation:
    reservation_id: str
    amount: D
    state: str
    updated_at_ms: int


@dataclass(frozen=True)
class ReconciliationReport:
    scanned: int
    repaired: int
    closed: int
    unresolved: int
    messages: tuple[str, ...] = ()


def floor_to_step(value: D, step: D) -> D:
    if step <= ZERO:
        raise ValueError("step must be positive")
    return (value / step).to_integral_value(rounding=ROUND_FLOOR) * step


def ceil_to_step(value: D, step: D) -> D:
    if step <= ZERO:
        raise ValueError("step must be positive")
    return (value / step).to_integral_value(rounding=ROUND_CEILING) * step


def encode_json(value: Any) -> Any:
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, Mapping):
        return {str(k): encode_json(v) for k, v in value.items()}
    if isinstance(value, tuple):
        return [encode_json(v) for v in value]
    if isinstance(value, list):
        return [encode_json(v) for v in value]
    if isinstance(value, set):
        return sorted(encode_json(v) for v in value)
    return value
