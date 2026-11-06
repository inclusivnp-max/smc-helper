from __future__ import annotations

import base64
import hashlib
import json
import sqlite3
import time
from contextlib import closing

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from smc_execution.environment import env_secret
from smc_execution.live_guard import (
    LIVE_ACCEPTANCE_GATES,
    LIVE_CAPITAL_ENABLED,
    LIVE_CAPITAL_STATUS,
    LiveAuthorizationError,
    canonical_json,
    enforce_live_capital_policy,
    load_live_authorization,
)
from smc_execution.operator_store import OperatorStore
from smc_execution.research_service import ResearchService
from smc_execution.research_store import ResearchStore


def _live_document(tmp_path):
    now = int(time.time() * 1000)
    recovery = {
        "schema_version": 1,
        "state_backend": "sqlite-single-node",
        "completed_at_ms": now - 1_000,
        "passed": True,
        "scenarios": {
            "kill_before_submit": True,
            "kill_after_submit_timeout": True,
            "kill_after_partial_fill": True,
            "kill_before_stop_ack": True,
            "restart_reconciliation": True,
            "backup_restore": True,
        },
    }
    recovery_path = tmp_path / "recovery-report.json"
    recovery_path.write_text(json.dumps(recovery), encoding="utf-8")
    recovery_path.chmod(0o444)
    recovery_hash = hashlib.sha256(recovery_path.read_bytes()).hexdigest()

    authorization = {
        "schema_version": 1,
        "authorization_id": "auth-2026-07-11",
        "environment": "live",
        "issued_at_ms": now - 2_000,
        "not_before_ms": now - 1_000,
        "expires_at_ms": now + 60_000,
        "deployment_id": "deployment-sha256",
        "subaccount_id": "dedicated-live-subaccount",
        "symbols": ["BTCUSDT"],
        "engine_code_fingerprint": "a" * 64,
        "risk_profile": "CAPITAL_PROTECTION",
        "risk_fingerprint": "b" * 64,
        "state_backend": "sqlite-single-node",
        "recovery_evidence": {
            "report_file": str(recovery_path),
            "sha256": recovery_hash,
        },
        "approved_runs": [
            {
                "run_id": "approved-run-1",
                "strategy_id": "smc-composite",
                "strategy_version": "1.0.0",
                "code_fingerprint": "c" * 64,
                "dataset_fingerprint": "d" * 64,
            }
        ],
        "approvers": [
            {"operator_id": "risk-operator", "key_id": "risk-key"},
            {"operator_id": "execution-operator", "key_id": "execution-key"},
        ],
    }
    keyring = tmp_path / "approvers"
    keyring.mkdir()
    signatures = []
    payload = canonical_json(authorization)
    for operator_id, key_id in (
        ("risk-operator", "risk-key"),
        ("execution-operator", "execution-key"),
    ):
        private_key = Ed25519PrivateKey.generate()
        public_pem = private_key.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        (keyring / f"{key_id}.pem").write_bytes(public_pem)
        (keyring / f"{key_id}.pem").chmod(0o444)
        signatures.append(
            {
                "operator_id": operator_id,
                "key_id": key_id,
                "signature": base64.b64encode(private_key.sign(payload)).decode(),
            }
        )
    document = {"authorization": authorization, "signatures": signatures}
    config_path = tmp_path / "live-authorization.json"
    config_path.write_text(json.dumps(document), encoding="utf-8")
    config_path.chmod(0o400)
    keyring.chmod(0o555)
    return config_path, keyring, document


def _load(config_path, keyring, **overrides):
    values = {
        "expected_deployment_id": "deployment-sha256",
        "expected_subaccount_id": "dedicated-live-subaccount",
        "expected_symbols": ("BTCUSDT",),
        "expected_run_ids": ("approved-run-1",),
        "expected_engine_code_fingerprint": "a" * 64,
        "expected_risk_profile": "CAPITAL_PROTECTION",
        "expected_risk_fingerprint": "b" * 64,
        "expected_state_backend": "sqlite-single-node",
        "credential_api_keys": {
            "trade": "trade-key",
            "user_data": "user-data-key",
            "monitor": "monitor-key",
        },
    }
    values.update(overrides)
    return load_live_authorization(config_path, keyring, **values)


def test_live_authorization_requires_two_valid_operator_signatures(tmp_path):
    config_path, keyring, document = _live_document(tmp_path)
    authorization = _load(config_path, keyring)
    assert authorization.approvers == ("risk-operator", "execution-operator")

    document["signatures"].pop()
    config_path.chmod(0o600)
    config_path.write_text(json.dumps(document), encoding="utf-8")
    config_path.chmod(0o400)
    with pytest.raises(LiveAuthorizationError, match="Signature identities"):
        _load(config_path, keyring)


def test_live_capital_is_hard_prohibited_pending_acceptance():
    assert LIVE_CAPITAL_ENABLED is False
    assert LIVE_CAPITAL_STATUS == "PROHIBITED_PENDING_ACCEPTANCE"
    assert "CANARY_LIVE_COMPLETED" in LIVE_ACCEPTANCE_GATES
    with pytest.raises(LiveAuthorizationError, match="cannot unlock capital"):
        enforce_live_capital_policy()


def test_operator_runner_cannot_enable_live_with_environment(monkeypatch, tmp_path):
    from examples.run_operator_dashboard import build_app

    monkeypatch.setenv("ENV_FILE", str(tmp_path / "missing.env"))
    monkeypatch.setenv("BINANCE_TESTNET", "false")
    monkeypatch.setenv("AUTO_SIGNAL_GENERATOR", "false")
    with pytest.raises(SystemExit, match="PROHIBITED_PENDING_ACCEPTANCE"):
        build_app()


def test_live_authorization_requires_role_separated_api_keys(tmp_path):
    config_path, keyring, _ = _live_document(tmp_path)
    with pytest.raises(LiveAuthorizationError, match="role-separated"):
        _load(
            config_path,
            keyring,
            credential_api_keys={
                "trade": "shared-key",
                "user_data": "shared-key",
                "monitor": "shared-key",
            },
        )


def test_env_secret_requires_protected_file_for_live(tmp_path, monkeypatch):
    credential = tmp_path / "credential"
    credential.write_text("secret-value\n", encoding="utf-8")
    credential.chmod(0o600)
    monkeypatch.setenv("P0_SECRET_FILE", str(credential))
    assert env_secret("P0_SECRET", require_file=True) == "secret-value"

    monkeypatch.delenv("P0_SECRET_FILE")
    monkeypatch.setenv("P0_SECRET", "direct-value")
    with pytest.raises(ValueError, match="P0_SECRET_FILE is required"):
        env_secret("P0_SECRET", require_file=True)


@pytest.mark.asyncio
async def test_incomplete_research_job_is_durable_and_interrupted_after_restart(tmp_path):
    store = ResearchStore(tmp_path / "research.db")
    await store.initialize()
    await store.save_job(
        {
            "job_id": "download-1",
            "job_type": "DATASET_DOWNLOAD",
            "status": "RUNNING",
            "created_at_ms": 1,
            "updated_at_ms": 1,
            "progress": "downloading",
        }
    )

    service = ResearchService(store, OperatorStore(tmp_path / "operator.db"))
    await service.initialize()
    jobs = await service.list_data_jobs()
    assert jobs[0]["job_id"] == "download-1"
    assert jobs[0]["status"] == "INTERRUPTED"
    assert jobs[0]["completed_at_ms"] >= jobs[0]["updated_at_ms"]
    with closing(sqlite3.connect(tmp_path / "research.db")) as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 2


@pytest.mark.asyncio
async def test_store_refuses_newer_unknown_schema(tmp_path):
    path = tmp_path / "future.db"
    with closing(sqlite3.connect(path)) as connection:
        connection.execute("PRAGMA user_version=999")
        connection.commit()
    with pytest.raises(RuntimeError, match="newer than supported"):
        await ResearchStore(path).initialize()
