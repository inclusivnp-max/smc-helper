from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import tempfile
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from smc_execution.config import ExecutionConfig
from smc_execution.governor import PortfolioGovernorConfig
from smc_execution.live_guard import (
    canonical_json,
    risk_configuration_fingerprint,
    runtime_code_fingerprint,
)
from smc_execution.modes import DEFAULT_MODE_PROFILES, TradingMode


def fingerprint(args: argparse.Namespace) -> None:
    mode = TradingMode(args.risk_profile)
    if mode is TradingMode.PAUSED:
        raise SystemExit("PAUSED is not a live risk profile")
    execution = ExecutionConfig(atr_timeframe=args.execution_timeframe)
    governor = PortfolioGovernorConfig(
        max_open_risk_pct=execution.max_portfolio_risk_pct
    )
    result = {
        "engine_code_fingerprint": runtime_code_fingerprint(),
        "risk_profile": mode.value,
        "risk_fingerprint": risk_configuration_fingerprint(
            execution, governor, DEFAULT_MODE_PROFILES[mode]
        ),
    }
    if args.recovery_report:
        report = Path(args.recovery_report)
        result["recovery_report_sha256"] = hashlib.sha256(report.read_bytes()).hexdigest()
    print(json.dumps(result, indent=2, sort_keys=True))


def sign(args: argparse.Namespace) -> None:
    document_path = Path(args.document)
    private_path = Path(args.private_key)
    if private_path.stat().st_mode & 0o077:
        raise SystemExit("Private signing key must have mode 0600 or stricter")
    document = json.loads(document_path.read_text(encoding="utf-8"))
    authorization = document.get("authorization")
    if not isinstance(authorization, dict):
        raise SystemExit("Document has no authorization object")
    expected = {
        (str(item.get("operator_id")), str(item.get("key_id")))
        for item in authorization.get("approvers", ())
        if isinstance(item, dict)
    }
    identity = (args.operator_id, args.key_id)
    if identity not in expected:
        raise SystemExit("Signer is not listed in authorization.approvers")
    private_key = serialization.load_pem_private_key(
        private_path.read_bytes(), password=None
    )
    if not isinstance(private_key, Ed25519PrivateKey):
        raise SystemExit("Signing key must be Ed25519")
    encoded = base64.b64encode(
        private_key.sign(canonical_json(authorization))
    ).decode("ascii")
    signatures = [
        item
        for item in document.get("signatures", ())
        if isinstance(item, dict)
        and (item.get("operator_id"), item.get("key_id")) != identity
    ]
    signatures.append(
        {
            "operator_id": args.operator_id,
            "key_id": args.key_id,
            "signature": encoded,
        }
    )
    document["signatures"] = signatures
    output = Path(args.output or args.document)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", dir=output.parent, delete=False
    ) as temporary:
        json.dump(document, temporary, indent=2, sort_keys=True)
        temporary.write("\n")
        temporary_path = Path(temporary.name)
    temporary_path.chmod(0o600)
    os.replace(temporary_path, output)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(
        description="Create deterministic fingerprints and sign a LIVE authorization"
    )
    commands = root.add_subparsers(dest="command", required=True)
    fingerprints = commands.add_parser("fingerprint")
    fingerprints.add_argument(
        "--risk-profile",
        choices=[
            TradingMode.CAPITAL_PROTECTION.value,
            TradingMode.BALANCED.value,
            TradingMode.PROFITABILITY.value,
        ],
        required=True,
    )
    fingerprints.add_argument("--execution-timeframe", default="5m")
    fingerprints.add_argument("--recovery-report")
    fingerprints.set_defaults(handler=fingerprint)

    signer = commands.add_parser("sign")
    signer.add_argument("--document", required=True)
    signer.add_argument("--private-key", required=True)
    signer.add_argument("--operator-id", required=True)
    signer.add_argument("--key-id", required=True)
    signer.add_argument("--output")
    signer.set_defaults(handler=sign)
    return root


def main() -> None:
    args = parser().parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
