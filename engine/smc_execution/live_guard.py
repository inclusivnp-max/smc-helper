from __future__ import annotations

import base64
import binascii
import hashlib
import json
import os
import re
import time
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Any, Mapping, Sequence

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from .models import encode_json
from .research_models import METRICS_ALGORITHM_VERSION, ApprovalStage


_HEX_256 = re.compile(r"^[0-9a-f]{64}$")
_KEY_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
_RUNTIME_SUFFIXES = {".py", ".lua", ".html", ".css", ".js"}
_MAX_AUTHORIZATION_LIFETIME_MS = 24 * 60 * 60 * 1000
_MAX_RECOVERY_EVIDENCE_AGE_MS = 30 * 24 * 60 * 60 * 1000
_REQUIRED_RECOVERY_SCENARIOS = {
    "kill_before_submit",
    "kill_after_submit_timeout",
    "kill_after_partial_fill",
    "kill_before_stop_ack",
    "restart_reconciliation",
    "backup_restore",
}

LIVE_CAPITAL_STATUS = "PROHIBITED_PENDING_ACCEPTANCE"
LIVE_CAPITAL_ENABLED = False
LIVE_ACCEPTANCE_GATES = (
    "BINANCE_DEMO_TESTNET_CONTRACTS",
    "CHAOS_KILL_RESTART_PARTIAL_FILL",
    "BACKUP_RESTORE_DRILL",
    "FROZEN_SHADOW",
    "TESTNET_EXECUTION_REPORT",
    "INDEPENDENT_REVIEW",
    "CANARY_LIVE_COMPLETED",
)


class LiveAuthorizationError(RuntimeError):
    """A live startup condition could not be proved."""


def enforce_live_capital_policy() -> None:
    """Hard build-time gate; deliberately has no environment override."""

    if LIVE_CAPITAL_ENABLED:
        return
    raise LiveAuthorizationError(
        f"LIVE is {LIVE_CAPITAL_STATUS}. Required acceptance gates: "
        + ", ".join(LIVE_ACCEPTANCE_GATES)
        + ". A reviewed code change is required before a CANARY_ONLY build; "
        "an environment variable or signed authorization cannot unlock capital."
    )


@dataclass(frozen=True)
class ApprovedRunBinding:
    run_id: str
    strategy_id: str
    strategy_version: str
    code_fingerprint: str
    dataset_fingerprint: str


@dataclass(frozen=True)
class LiveAuthorization:
    authorization_id: str
    deployment_id: str
    subaccount_id: str
    symbols: tuple[str, ...]
    engine_code_fingerprint: str
    risk_profile: str
    risk_fingerprint: str
    state_backend: str
    approved_runs: tuple[ApprovedRunBinding, ...]
    approvers: tuple[str, ...]
    issued_at_ms: int
    not_before_ms: int
    expires_at_ms: int

    async def validate_research(self, store: Any) -> None:
        """Re-prove every signed strategy/data binding from the durable store."""

        for binding in self.approved_runs:
            report = await store.get_report(binding.run_id)
            if report is None:
                raise LiveAuthorizationError(
                    f"Signed live run is missing: {binding.run_id}"
                )
            report_version = int(report.get("metrics_algorithm_version") or 0)
            if report_version < METRICS_ALGORITHM_VERSION:
                raise LiveAuthorizationError(
                    f"Run {binding.run_id} uses metrics algorithm version "
                    f"{report_version}; this build requires "
                    f"{METRICS_ALGORITHM_VERSION}"
                )
            manifest = report.get("manifest") or {}
            actual = (
                str(manifest.get("strategy_id") or ""),
                str(manifest.get("version") or ""),
                str(manifest.get("code_fingerprint") or ""),
                str(report.get("dataset_fingerprint") or ""),
            )
            expected = (
                binding.strategy_id,
                binding.strategy_version,
                binding.code_fingerprint,
                binding.dataset_fingerprint,
            )
            if actual != expected:
                raise LiveAuthorizationError(
                    f"Research fingerprints changed for run {binding.run_id}"
                )
            dataset = await store.get_dataset(str(report.get("dataset_id") or ""))
            if dataset is None or dataset.fingerprint != binding.dataset_fingerprint:
                raise LiveAuthorizationError(
                    f"Signed dataset is missing or changed for run {binding.run_id}"
                )
            approval = await store.latest_approval(
                binding.strategy_id,
                binding.strategy_version,
                "live",
            )
            if (
                approval is None
                or not approval.active
                or approval.stage is not ApprovalStage.LIVE
                or approval.run_id != binding.run_id
                or approval.code_fingerprint != binding.code_fingerprint
                or approval.dataset_fingerprint != binding.dataset_fingerprint
            ):
                raise LiveAuthorizationError(
                    f"No active matching LIVE approval for run {binding.run_id}"
                )

    def validate_active_risk_profile(self, profile_name: str) -> None:
        if profile_name != self.risk_profile:
            raise LiveAuthorizationError(
                f"Active risk profile is {profile_name!r}; signed profile is "
                f"{self.risk_profile!r}"
            )

    def summary(self) -> dict[str, Any]:
        return {
            "authorization_id": self.authorization_id,
            "deployment_id": self.deployment_id,
            "subaccount_id": self.subaccount_id,
            "symbols": self.symbols,
            "risk_profile": self.risk_profile,
            "state_backend": self.state_backend,
            "approved_run_ids": tuple(item.run_id for item in self.approved_runs),
            "approvers": self.approvers,
            "expires_at_ms": self.expires_at_ms,
        }


def canonical_json(value: Mapping[str, Any]) -> bytes:
    return json.dumps(
        encode_json(dict(value)),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")


def value_fingerprint(value: Mapping[str, Any]) -> str:
    return hashlib.sha256(canonical_json(value)).hexdigest()


def risk_configuration_fingerprint(
    base_execution_config: Any,
    base_governor_config: Any,
    profile: Any,
) -> str:
    """Bind the named profile to the effective execution and governor values."""

    execution = replace(
        base_execution_config, **dict(profile.execution_overrides)
    )
    governor = replace(
        base_governor_config, **dict(profile.governor_overrides)
    )
    return value_fingerprint(
        {
            "profile": profile.to_dict(),
            "execution_config": asdict(execution),
            "governor_config": asdict(governor),
        }
    )


def runtime_code_fingerprint(package_root: str | Path | None = None) -> str:
    """Fingerprint executable package sources and bundled runtime assets."""

    root = Path(package_root) if package_root is not None else Path(__file__).parent
    root = root.resolve()
    if not root.is_dir():
        raise LiveAuthorizationError(f"Runtime package directory is missing: {root}")
    digest = hashlib.sha256()
    selected = sorted(
        path
        for path in root.rglob("*")
        if path.is_file()
        and "__pycache__" not in path.parts
        and path.suffix.lower() in _RUNTIME_SUFFIXES
    )
    if not selected:
        raise LiveAuthorizationError(f"No runtime files found under {root}")
    for path in selected:
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        content = path.read_bytes()
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def read_live_risk_profile(config_file: str | Path) -> str:
    """Read the profile selector; it is trusted only after full signature verification."""

    path = _trusted_file(config_file, label="live authorization")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise LiveAuthorizationError(f"Invalid live authorization file: {exc}") from exc
    authorization = document.get("authorization") if isinstance(document, dict) else None
    if not isinstance(authorization, dict):
        raise LiveAuthorizationError("Live authorization object is missing")
    return _text(authorization, "risk_profile")


def load_live_authorization(
    config_file: str | Path,
    keyring_dir: str | Path,
    *,
    expected_deployment_id: str,
    expected_subaccount_id: str,
    expected_symbols: Sequence[str],
    expected_run_ids: Sequence[str],
    expected_engine_code_fingerprint: str,
    expected_risk_profile: str,
    expected_risk_fingerprint: str,
    expected_state_backend: str,
    credential_api_keys: Mapping[str, str],
    now_ms: int | None = None,
) -> LiveAuthorization:
    """Load and verify the complete two-operator live startup contract."""

    now = int(time.time() * 1000) if now_ms is None else int(now_ms)
    path = _trusted_file(config_file, label="live authorization")
    keyring = Path(keyring_dir).resolve()
    if not keyring.is_dir():
        raise LiveAuthorizationError(f"Approver keyring does not exist: {keyring}")
    if keyring.stat().st_mode & 0o022:
        raise LiveAuthorizationError(
            f"Approver keyring must not be group/world writable: {keyring}"
        )
    if os.access(keyring, os.W_OK):
        raise LiveAuthorizationError(
            f"Approver keyring must not be writable by the execution process: {keyring}"
        )
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise LiveAuthorizationError(f"Invalid live authorization file: {exc}") from exc
    if not isinstance(document, dict):
        raise LiveAuthorizationError("Live authorization document must be a JSON object")
    authorization = document.get("authorization")
    signatures = document.get("signatures")
    if not isinstance(authorization, dict) or not isinstance(signatures, list):
        raise LiveAuthorizationError(
            "Live authorization requires authorization object and signatures array"
        )
    if authorization.get("schema_version") != 1:
        raise LiveAuthorizationError("Unsupported live authorization schema_version")
    if authorization.get("environment") != "live":
        raise LiveAuthorizationError("Signed authorization environment must be 'live'")

    issued = _integer(authorization, "issued_at_ms")
    not_before = _integer(authorization, "not_before_ms")
    expires = _integer(authorization, "expires_at_ms")
    if not (issued <= not_before < expires):
        raise LiveAuthorizationError("Invalid live authorization time window")
    if expires - issued > _MAX_AUTHORIZATION_LIFETIME_MS:
        raise LiveAuthorizationError("Live authorization lifetime exceeds 24 hours")
    if now < not_before:
        raise LiveAuthorizationError("Live authorization is not active yet")
    if now >= expires:
        raise LiveAuthorizationError("Live authorization has expired")

    authorization_id = _text(authorization, "authorization_id")
    deployment_id = _text(authorization, "deployment_id")
    subaccount_id = _text(authorization, "subaccount_id")
    risk_profile = _text(authorization, "risk_profile")
    state_backend = _text(authorization, "state_backend")
    engine_fingerprint = _fingerprint(authorization, "engine_code_fingerprint")
    risk_fingerprint = _fingerprint(authorization, "risk_fingerprint")
    symbols = _string_tuple(authorization, "symbols", upper=True)

    expected = {
        "deployment_id": expected_deployment_id,
        "subaccount_id": expected_subaccount_id,
        "engine_code_fingerprint": expected_engine_code_fingerprint,
        "risk_profile": expected_risk_profile,
        "risk_fingerprint": expected_risk_fingerprint,
        "state_backend": expected_state_backend,
    }
    actual = {
        "deployment_id": deployment_id,
        "subaccount_id": subaccount_id,
        "engine_code_fingerprint": engine_fingerprint,
        "risk_profile": risk_profile,
        "risk_fingerprint": risk_fingerprint,
        "state_backend": state_backend,
    }
    mismatches = [name for name, value in expected.items() if actual[name] != value]
    if mismatches:
        raise LiveAuthorizationError(
            "Signed live configuration mismatch: " + ", ".join(mismatches)
        )
    normalized_symbols = tuple(str(item).upper() for item in expected_symbols)
    if symbols != normalized_symbols:
        raise LiveAuthorizationError("Signed symbols do not match runtime symbols")

    raw_runs = authorization.get("approved_runs")
    if not isinstance(raw_runs, list) or not raw_runs:
        raise LiveAuthorizationError("At least one signed approved run is required")
    runs: list[ApprovedRunBinding] = []
    for raw in raw_runs:
        if not isinstance(raw, dict):
            raise LiveAuthorizationError("approved_runs entries must be objects")
        runs.append(
            ApprovedRunBinding(
                run_id=_text(raw, "run_id"),
                strategy_id=_text(raw, "strategy_id"),
                strategy_version=_text(raw, "strategy_version"),
                code_fingerprint=_fingerprint(raw, "code_fingerprint"),
                dataset_fingerprint=_fingerprint(raw, "dataset_fingerprint"),
            )
        )
    run_ids = tuple(item.run_id for item in runs)
    if len(set(run_ids)) != len(run_ids):
        raise LiveAuthorizationError("Signed approved run IDs must be unique")
    if run_ids != tuple(expected_run_ids):
        raise LiveAuthorizationError("Signed approved runs do not match runtime run IDs")

    required_roles = {"trade", "user_data", "monitor"}
    if set(credential_api_keys) != required_roles:
        raise LiveAuthorizationError(
            "Live requires trade, user_data and monitor API-key roles"
        )
    api_keys = [credential_api_keys[role].strip() for role in sorted(required_roles)]
    if any(not value for value in api_keys) or len(set(api_keys)) != len(api_keys):
        raise LiveAuthorizationError("Live API keys must be non-empty and role-separated")

    _verify_recovery_evidence(authorization, now, state_backend)
    approver_ids = _verify_operator_signatures(authorization, signatures, keyring)
    return LiveAuthorization(
        authorization_id=authorization_id,
        deployment_id=deployment_id,
        subaccount_id=subaccount_id,
        symbols=symbols,
        engine_code_fingerprint=engine_fingerprint,
        risk_profile=risk_profile,
        risk_fingerprint=risk_fingerprint,
        state_backend=state_backend,
        approved_runs=tuple(runs),
        approvers=approver_ids,
        issued_at_ms=issued,
        not_before_ms=not_before,
        expires_at_ms=expires,
    )


def _verify_operator_signatures(
    authorization: Mapping[str, Any],
    signatures: Sequence[Any],
    keyring: Path,
) -> tuple[str, ...]:
    raw_approvers = authorization.get("approvers")
    if not isinstance(raw_approvers, list) or len(raw_approvers) < 2:
        raise LiveAuthorizationError("Two signed operator approvals are required")
    expected_pairs: list[tuple[str, str]] = []
    for raw in raw_approvers:
        if not isinstance(raw, dict):
            raise LiveAuthorizationError("approvers entries must be objects")
        operator_id = _text(raw, "operator_id")
        key_id = _text(raw, "key_id")
        if not _KEY_ID.fullmatch(key_id):
            raise LiveAuthorizationError(f"Invalid approver key_id: {key_id!r}")
        expected_pairs.append((operator_id, key_id))
    if len({item[0] for item in expected_pairs}) != len(expected_pairs):
        raise LiveAuthorizationError("Approver operator IDs must be distinct")
    if len({item[1] for item in expected_pairs}) != len(expected_pairs):
        raise LiveAuthorizationError("Approver signing keys must be distinct")

    supplied: dict[tuple[str, str], str] = {}
    for raw in signatures:
        if not isinstance(raw, dict):
            raise LiveAuthorizationError("signatures entries must be objects")
        pair = (_text(raw, "operator_id"), _text(raw, "key_id"))
        if pair in supplied:
            raise LiveAuthorizationError("Duplicate operator signature")
        supplied[pair] = _text(raw, "signature")
    if set(supplied) != set(expected_pairs):
        raise LiveAuthorizationError("Signature identities do not match signed approvers")

    payload = canonical_json(authorization)
    for operator_id, key_id in expected_pairs:
        public_key_path = _trusted_file(
            keyring / f"{key_id}.pem", label=f"public key {key_id}"
        )
        try:
            public_key = serialization.load_pem_public_key(public_key_path.read_bytes())
        except (OSError, ValueError, TypeError) as exc:
            raise LiveAuthorizationError(
                f"Cannot load approver public key {key_id}: {exc}"
            ) from exc
        if not isinstance(public_key, Ed25519PublicKey):
            raise LiveAuthorizationError(f"Approver key {key_id} is not Ed25519")
        try:
            signature = base64.b64decode(supplied[(operator_id, key_id)], validate=True)
            public_key.verify(signature, payload)
        except (binascii.Error, InvalidSignature) as exc:
            raise LiveAuthorizationError(
                f"Invalid live approval signature for operator {operator_id}"
            ) from exc
    return tuple(item[0] for item in expected_pairs)


def _verify_recovery_evidence(
    authorization: Mapping[str, Any], now_ms: int, state_backend: str
) -> None:
    raw = authorization.get("recovery_evidence")
    if not isinstance(raw, dict):
        raise LiveAuthorizationError("Signed recovery_evidence is required")
    report_path = _trusted_file(_text(raw, "report_file"), label="recovery report")
    expected_hash = _fingerprint(raw, "sha256")
    actual_hash = hashlib.sha256(report_path.read_bytes()).hexdigest()
    if actual_hash != expected_hash:
        raise LiveAuthorizationError("Recovery report fingerprint mismatch")
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise LiveAuthorizationError(f"Invalid recovery report: {exc}") from exc
    if not isinstance(report, dict) or report.get("schema_version") != 1:
        raise LiveAuthorizationError("Unsupported recovery report schema")
    if report.get("state_backend") != state_backend or report.get("passed") is not True:
        raise LiveAuthorizationError("Recovery report does not pass for this state backend")
    completed = _integer(report, "completed_at_ms")
    if completed > now_ms or now_ms - completed > _MAX_RECOVERY_EVIDENCE_AGE_MS:
        raise LiveAuthorizationError("Recovery evidence is future-dated or older than 30 days")
    scenarios = report.get("scenarios")
    if not isinstance(scenarios, dict):
        raise LiveAuthorizationError("Recovery report scenarios are missing")
    missing = sorted(
        name for name in _REQUIRED_RECOVERY_SCENARIOS if scenarios.get(name) is not True
    )
    if missing:
        raise LiveAuthorizationError(
            "Recovery evidence has unpassed scenarios: " + ", ".join(missing)
        )


def _trusted_file(value: str | Path, *, label: str) -> Path:
    path = Path(value).resolve()
    if not path.is_file():
        raise LiveAuthorizationError(f"{label.capitalize()} does not exist: {path}")
    if path.stat().st_mode & 0o022:
        raise LiveAuthorizationError(
            f"{label.capitalize()} must not be group/world writable: {path}"
        )
    if os.access(path, os.W_OK):
        raise LiveAuthorizationError(
            f"{label.capitalize()} must not be writable by the execution process: {path}"
        )
    return path


def _text(value: Mapping[str, Any], name: str) -> str:
    result = value.get(name)
    if not isinstance(result, str) or not result.strip() or len(result) > 256:
        raise LiveAuthorizationError(f"Invalid or missing {name}")
    return result.strip()


def _integer(value: Mapping[str, Any], name: str) -> int:
    result = value.get(name)
    if isinstance(result, bool) or not isinstance(result, int) or result < 0:
        raise LiveAuthorizationError(f"Invalid or missing {name}")
    return result


def _fingerprint(value: Mapping[str, Any], name: str) -> str:
    result = _text(value, name).lower()
    if not _HEX_256.fullmatch(result):
        raise LiveAuthorizationError(f"{name} must be a lowercase SHA-256 digest")
    return result


def _string_tuple(
    value: Mapping[str, Any], name: str, *, upper: bool = False
) -> tuple[str, ...]:
    raw = value.get(name)
    if not isinstance(raw, list) or not raw:
        raise LiveAuthorizationError(f"Invalid or missing {name}")
    result = tuple(
        _normalize_string(item, upper=upper, field=name)
        for item in raw
    )
    if len(set(result)) != len(result):
        raise LiveAuthorizationError(f"{name} values must be unique")
    return result


def _normalize_string(value: Any, *, upper: bool, field: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > 256:
        raise LiveAuthorizationError(f"Invalid value in {field}")
    result = value.strip()
    return result.upper() if upper else result
