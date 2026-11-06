from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from .models import ExecutionResult, Signal, parse_signal
from .research_models import METRICS_ALGORITHM_VERSION, ApprovalStage
from .research_store import ResearchStore, StrategyApproval
from .runtime import TradingRuntime


class SignalAdmissionError(RuntimeError):
    pass


_STAGE_RANK = {
    ApprovalStage.DRAFT: 0,
    ApprovalStage.RESEARCH_PASSED: 1,
    ApprovalStage.SHADOW: 2,
    ApprovalStage.TESTNET: 3,
    ApprovalStage.LIVE: 4,
    ApprovalStage.REVOKED: -1,
}


@dataclass(frozen=True)
class AlphaGateSettings:
    environment: str = "testnet"
    require_governed_signals: bool = True

    @property
    def required_stage(self) -> ApprovalStage:
        environment = self.environment.lower()
        if environment == "live":
            return ApprovalStage.LIVE
        if environment == "testnet":
            return ApprovalStage.TESTNET
        if environment == "shadow":
            return ApprovalStage.SHADOW
        raise ValueError(f"Unknown alpha-gate environment: {self.environment}")


class GovernedSignalRouter:
    """Binds strategy governance to the existing execution runtime.

    The execution engine remains unaware of research policy. The router verifies
    immutable strategy/run fingerprints before forwarding a signal and enriches
    the durable execution payload with provenance metadata.
    """

    def __init__(
        self,
        runtime: TradingRuntime,
        store: ResearchStore,
        settings: AlphaGateSettings = AlphaGateSettings(),
    ) -> None:
        self.runtime = runtime
        self.store = store
        self.settings = settings

    async def submit(self, raw: Signal | Mapping[str, Any]) -> ExecutionResult:
        signal = parse_signal(raw)
        if not self.settings.require_governed_signals and not signal.strategy_id:
            return await self.runtime.submit_signal(signal)
        approval = await self._verify(signal)
        enriched = Signal(
            signal_id=signal.signal_id,
            symbol=signal.symbol,
            action=signal.action,
            confidence=signal.confidence,
            created_at_ms=signal.created_at_ms,
            reference_price=signal.reference_price,
            strategy_id=signal.strategy_id,
            strategy_version=signal.strategy_version,
            research_run_id=signal.research_run_id,
            code_fingerprint=signal.code_fingerprint,
            metadata={
                **dict(signal.metadata),
                "alpha_gate_stage": approval.stage.value,
                "alpha_gate_environment": approval.environment,
                "alpha_gate_approved_by": approval.actor,
                "alpha_gate_approved_at_ms": approval.created_at_ms,
            },
        )
        return await self.runtime.submit_signal(enriched)

    async def _verify(self, signal: Signal) -> StrategyApproval:
        missing = [
            name
            for name, value in {
                "strategy_id": signal.strategy_id,
                "strategy_version": signal.strategy_version,
                "research_run_id": signal.research_run_id,
                "code_fingerprint": signal.code_fingerprint,
            }.items()
            if not value
        ]
        if missing:
            raise SignalAdmissionError(
                "Governed signal is missing provenance: " + ", ".join(missing)
            )
        approval = await self.store.latest_approval(
            signal.strategy_id or "",
            signal.strategy_version or "",
            self.settings.environment,
        )
        if approval is None:
            raise SignalAdmissionError("Strategy has no approval for this environment")
        if not approval.active:
            raise SignalAdmissionError("Strategy approval is expired or revoked")
        required = self.settings.required_stage
        if _STAGE_RANK[approval.stage] < _STAGE_RANK[required]:
            raise SignalAdmissionError(
                f"Approval stage {approval.stage.value} is below required {required.value}"
            )
        if approval.run_id != signal.research_run_id:
            raise SignalAdmissionError("Signal research_run_id does not match approval")
        if approval.code_fingerprint != signal.code_fingerprint:
            raise SignalAdmissionError("Strategy code fingerprint does not match approval")

        report = await self.store.get_report(approval.run_id)
        if report is None:
            raise SignalAdmissionError("Approved research report is missing")
        # An approval is durable, so an upgrade that changes metric semantics would
        # otherwise keep honouring evidence produced under the previous rules.
        report_version = int(report.get("metrics_algorithm_version") or 0)
        if report_version < METRICS_ALGORITHM_VERSION:
            raise SignalAdmissionError(
                f"Approved report uses metrics algorithm version {report_version}; "
                f"this build requires {METRICS_ALGORITHM_VERSION}"
            )
        manifest = report.get("manifest") or {}
        if (
            manifest.get("strategy_id") != approval.strategy_id
            or manifest.get("version") != approval.strategy_version
            or manifest.get("code_fingerprint") != approval.code_fingerprint
        ):
            raise SignalAdmissionError("Approval no longer matches the stored manifest")
        if report.get("dataset_fingerprint") != approval.dataset_fingerprint:
            raise SignalAdmissionError("Approval dataset fingerprint mismatch")
        dataset_id = str(report.get("dataset_id") or "")
        dataset = await self.store.get_dataset(dataset_id)
        if dataset is None or dataset.fingerprint != approval.dataset_fingerprint:
            raise SignalAdmissionError("Approved immutable dataset is missing or changed")
        if signal.symbol != dataset.symbol:
            raise SignalAdmissionError(
                f"Research run is approved for {dataset.symbol}, not {signal.symbol}"
            )
        interval = str(signal.metadata.get("interval") or "")
        if not interval:
            raise SignalAdmissionError("Governed signal metadata must include interval")
        if interval != dataset.interval:
            raise SignalAdmissionError(
                f"Research run is approved for interval {dataset.interval}, not {interval}"
            )
        engine_timeframe = str(self.runtime.engine.config.atr_timeframe)
        if interval != engine_timeframe:
            raise SignalAdmissionError(
                f"Execution ATR timeframe is {engine_timeframe}, research interval is {interval}"
            )
        research_profile = str((report.get("simulation") or {}).get("profile") or "CUSTOM")
        if research_profile != "CUSTOM":
            mode_state = await self.runtime.control.get_system_value("trading_mode")
            active_mode = str((mode_state or {}).get("active_mode") or (mode_state or {}).get("mode") or "")
            if active_mode != research_profile:
                raise SignalAdmissionError(
                    f"Research profile is {research_profile}, active runtime profile is "
                    f"{active_mode or 'UNSPECIFIED'}"
                )
        return approval
