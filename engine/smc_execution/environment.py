from __future__ import annotations

import os
from pathlib import Path


def load_env_file(path: str | Path = ".env", *, override: bool = False) -> None:
    """Load a small, dependency-free KEY=VALUE env file.

    This intentionally supports the subset used by the supplied templates. It does
    not execute shell syntax and therefore should not be treated like `source`.
    """

    file_path = Path(path)
    if not file_path.exists():
        return
    for raw_line in file_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key or not key.replace("_", "A").isalnum():
            continue
        if value and value[0] in {'"', "'"} and value[-1:] == value[0]:
            value = value[1:-1]
        if override or key not in os.environ:
            os.environ[key] = value


def env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def env_csv(name: str, default: str) -> tuple[str, ...]:
    return tuple(
        item.strip().upper()
        for item in os.getenv(name, default).split(",")
        if item.strip()
    )


def env_secret(
    name: str,
    *,
    legacy_name: str | None = None,
    require_file: bool = False,
) -> str:
    """Read a secret from a credential file, with Testnet-only env fallback.

    ``<NAME>_FILE`` is the preferred interface and works with systemd credentials,
    Docker secrets, Vault agents and similar secret injectors.  Direct environment
    values remain available to avoid breaking local Testnet workflows; callers must
    set ``require_file=True`` for live credentials.
    """

    file_variable = f"{name}_FILE"
    file_value = os.getenv(file_variable, "").strip()
    direct_name = name if os.getenv(name) is not None else legacy_name
    direct_value = os.getenv(direct_name, "") if direct_name else ""

    if file_value and direct_value:
        raise ValueError(
            f"Set only {file_variable}; do not define {direct_name} at the same time"
        )
    if file_value:
        path = Path(file_value)
        if not path.is_file():
            raise ValueError(f"Credential file does not exist: {path}")
        mode = path.stat().st_mode & 0o777
        if mode & 0o022:
            raise ValueError(
                f"Credential file {path} must not be group/world writable"
            )
        resolved = path.resolve()
        managed_runtime_secret = (
            str(resolved).startswith("/run/credentials/")
            or str(resolved).startswith("/run/secrets/")
        )
        if mode & 0o044 and not managed_runtime_secret:
            raise ValueError(
                f"Credential file {path} must have mode 0600 or be supplied by a "
                "managed /run credential mount"
            )
        value = path.read_text(encoding="utf-8").strip()
        if not value:
            raise ValueError(f"Credential file is empty: {path}")
        return value

    if require_file:
        raise ValueError(f"{file_variable} is required for live operation")
    value = direct_value.strip()
    if not value:
        choices = file_variable if legacy_name is None else f"{file_variable} or {legacy_name}"
        raise ValueError(f"Missing secret: {choices}")
    return value
