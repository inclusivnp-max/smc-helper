from __future__ import annotations

import csv
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence

from .models import D, encode_json
from .research_models import DataQualityError, ResearchBar, dataset_fingerprint


@dataclass(frozen=True)
class DataQualityReport:
    rows: int
    duplicate_timestamps: int
    out_of_order_rows: int
    gaps: int
    inconsistent_intervals: int
    unavailable_future_features: int
    warnings: tuple[str, ...]

    @property
    def passed(self) -> bool:
        return (
            self.rows > 0
            and self.duplicate_timestamps == 0
            and self.out_of_order_rows == 0
            and self.gaps == 0
            and self.inconsistent_intervals == 0
        )

    def to_dict(self) -> dict[str, Any]:
        return encode_json(asdict(self))


@dataclass(frozen=True)
class ResearchDataset:
    dataset_id: str
    symbol: str
    interval: str
    bars: tuple[ResearchBar, ...]
    fingerprint: str
    quality: DataQualityReport
    source: str

    def to_dict(self, *, include_bars: bool = False) -> dict[str, Any]:
        payload = {
            "dataset_id": self.dataset_id,
            "symbol": self.symbol,
            "interval": self.interval,
            "rows": len(self.bars),
            "start_time_ms": self.bars[0].open_time_ms if self.bars else None,
            "end_time_ms": self.bars[-1].close_time_ms if self.bars else None,
            "fingerprint": self.fingerprint,
            "quality": self.quality.to_dict(),
            "source": self.source,
        }
        if include_bars:
            payload["bars"] = [bar.to_dict() for bar in self.bars]
        return payload


_REQUIRED_COLUMNS = {
    "open_time_ms",
    "close_time_ms",
    "open",
    "high",
    "low",
    "close",
    "volume",
}


def _optional_decimal(row: dict[str, str], key: str) -> D | None:
    value = row.get(key)
    if value is None or value.strip() == "":
        return None
    return D(value.strip())


def _optional_int(row: dict[str, str], key: str) -> int | None:
    value = row.get(key)
    if value is None or value.strip() == "":
        return None
    return int(value.strip())


def load_csv_dataset(
    path: str | Path,
    *,
    dataset_id: str | None = None,
    symbol: str | None = None,
    interval: str | None = None,
    strict: bool = True,
) -> ResearchDataset:
    source_path = Path(path).expanduser().resolve()
    if not source_path.is_file():
        raise FileNotFoundError(source_path)

    bars: list[ResearchBar] = []
    with source_path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            raise DataQualityError("CSV has no header")
        missing = _REQUIRED_COLUMNS - set(reader.fieldnames)
        if missing:
            raise DataQualityError(f"CSV is missing required columns: {sorted(missing)}")
        for line_no, row in enumerate(reader, start=2):
            try:
                row_symbol = (row.get("symbol") or symbol or "").upper().strip()
                row_interval = (row.get("interval") or interval or "").strip()
                if not row_symbol or not row_interval:
                    raise ValueError("symbol and interval are required")
                bars.append(
                    ResearchBar(
                        symbol=row_symbol,
                        interval=row_interval,
                        open_time_ms=int(row["open_time_ms"]),
                        close_time_ms=int(row["close_time_ms"]),
                        open=D(row["open"]),
                        high=D(row["high"]),
                        low=D(row["low"]),
                        close=D(row["close"]),
                        volume=D(row["volume"]),
                        quote_volume=D(row.get("quote_volume") or "0"),
                        trades=int(row.get("trades") or 0),
                        taker_buy_base=D(row.get("taker_buy_base") or "0"),
                        taker_buy_quote=D(row.get("taker_buy_quote") or "0"),
                        mark_close=_optional_decimal(row, "mark_close"),
                        open_interest=_optional_decimal(row, "open_interest"),
                        funding_rate=_optional_decimal(row, "funding_rate"),
                        open_interest_available_at_ms=_optional_int(
                            row, "open_interest_available_at_ms"
                        ),
                        funding_available_at_ms=_optional_int(
                            row, "funding_available_at_ms"
                        ),
                    )
                )
            except Exception as exc:
                raise DataQualityError(f"Invalid CSV row {line_no}: {exc}") from exc

    quality = validate_bars(bars)
    if strict and not quality.passed:
        raise DataQualityError(
            "Dataset failed quality checks: " + "; ".join(quality.warnings)
        )
    if not bars:
        raise DataQualityError("Dataset is empty")
    symbols = {bar.symbol for bar in bars}
    intervals = {bar.interval for bar in bars}
    if len(symbols) != 1 or len(intervals) != 1:
        raise DataQualityError("A dataset must contain exactly one symbol and interval")
    fingerprint = dataset_fingerprint(bars)
    resolved_id = dataset_id or f"{bars[0].symbol}-{bars[0].interval}-{fingerprint[:12]}"
    return ResearchDataset(
        dataset_id=resolved_id,
        symbol=bars[0].symbol,
        interval=bars[0].interval,
        bars=tuple(bars),
        fingerprint=fingerprint,
        quality=quality,
        source=str(source_path),
    )


def validate_bars(bars: Sequence[ResearchBar]) -> DataQualityReport:
    duplicate = 0
    out_of_order = 0
    gaps = 0
    inconsistent = 0
    future_features = 0
    warnings: list[str] = []
    if not bars:
        return DataQualityReport(0, 0, 0, 0, 0, 0, ("dataset is empty",))

    expected_duration = bars[0].close_time_ms - bars[0].open_time_ms + 1
    expected_symbol = bars[0].symbol
    expected_interval_name = bars[0].interval
    seen: set[int] = set()
    previous: ResearchBar | None = None
    for bar in bars:
        if bar.open_time_ms in seen:
            duplicate += 1
        seen.add(bar.open_time_ms)
        duration = bar.close_time_ms - bar.open_time_ms + 1
        if (
            duration != expected_duration
            or bar.symbol != expected_symbol
            or bar.interval != expected_interval_name
        ):
            inconsistent += 1
        if previous is not None:
            if bar.open_time_ms <= previous.open_time_ms:
                out_of_order += 1
            expected_open = previous.close_time_ms + 1
            if bar.open_time_ms != expected_open:
                gaps += 1
        if (
            bar.open_interest_available_at_ms is not None
            and bar.open_interest_available_at_ms > bar.close_time_ms
        ):
            future_features += 1
        if (
            bar.funding_available_at_ms is not None
            and bar.funding_available_at_ms > bar.close_time_ms
        ):
            future_features += 1
        previous = bar

    if duplicate:
        warnings.append(f"duplicate open timestamps: {duplicate}")
    if out_of_order:
        warnings.append(f"out-of-order rows: {out_of_order}")
    if gaps:
        warnings.append(f"time gaps: {gaps}")
    if inconsistent:
        warnings.append(f"inconsistent candle duration: {inconsistent}")
    if future_features:
        warnings.append(
            f"features unavailable at candle close (ignored by strategy): {future_features}"
        )
    return DataQualityReport(
        rows=len(bars),
        duplicate_timestamps=duplicate,
        out_of_order_rows=out_of_order,
        gaps=gaps,
        inconsistent_intervals=inconsistent,
        unavailable_future_features=future_features,
        warnings=tuple(warnings),
    )


def write_csv_dataset(path: str | Path, bars: Iterable[ResearchBar]) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "symbol",
        "interval",
        "open_time_ms",
        "close_time_ms",
        "open",
        "high",
        "low",
        "close",
        "volume",
        "quote_volume",
        "trades",
        "taker_buy_base",
        "taker_buy_quote",
        "mark_close",
        "open_interest",
        "funding_rate",
        "open_interest_available_at_ms",
        "funding_available_at_ms",
    ]
    with target.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for bar in bars:
            row = bar.to_dict()
            writer.writerow({key: row.get(key, "") for key in fields})
