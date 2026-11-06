#!/usr/bin/env python3
import datetime as _dt
import hashlib
import hmac
import os
import secrets
import sqlite3

DEFAULT_DB = os.environ.get("LICENSE_DB", "/var/lib/smc-license/licenses.db")
HASH_ITERATIONS = 240_000


def normalize_hwid(value: str) -> str:
    return (value or "").strip().replace(" ", "").replace("_", "-").upper()


def utc_now() -> _dt.datetime:
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0)


def iso_now() -> str:
    return utc_now().isoformat().replace("+00:00", "Z")


def expires_after_days(days: int) -> str:
    return (utc_now() + _dt.timedelta(days=max(1, int(days)))).isoformat().replace("+00:00", "Z")


def parse_utc(value: str) -> _dt.datetime:
    text = (value or "").strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = _dt.datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=_dt.timezone.utc)
    return parsed.astimezone(_dt.timezone.utc)


def connect(db_path: str = DEFAULT_DB) -> sqlite3.Connection:
    parent = os.path.dirname(os.path.abspath(db_path))
    if parent:
        os.makedirs(parent, exist_ok=True)
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    init_db(conn)
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS licenses (
            hwid TEXT PRIMARY KEY,
            password_hash TEXT NOT NULL,
            expires_at TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 1,
            note TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            last_seen_at TEXT
        )
        """
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_licenses_active ON licenses(active)")
    conn.commit()


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, HASH_ITERATIONS)
    return f"pbkdf2_sha256${HASH_ITERATIONS}${salt.hex()}${digest.hex()}"


def verify_password(stored: str, password: str) -> bool:
    try:
        algo, iterations_text, salt_hex, digest_hex = stored.split("$", 3)
        if algo != "pbkdf2_sha256":
            return False
        iterations = int(iterations_text)
        salt = bytes.fromhex(salt_hex)
        expected = bytes.fromhex(digest_hex)
        actual = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
        return hmac.compare_digest(actual, expected)
    except Exception:
        return False


def upsert_license(conn: sqlite3.Connection, hwid: str, password: str, expires_at: str, note: str = "") -> None:
    normalized = normalize_hwid(hwid)
    if not normalized:
        raise ValueError("HWID is empty")
    if not password:
        raise ValueError("Password is empty")
    now = iso_now()
    password_hash = hash_password(password)
    conn.execute(
        """
        INSERT INTO licenses(hwid, password_hash, expires_at, active, note, created_at, updated_at)
        VALUES (?, ?, ?, 1, ?, ?, ?)
        ON CONFLICT(hwid) DO UPDATE SET
            password_hash = excluded.password_hash,
            expires_at = excluded.expires_at,
            active = 1,
            note = excluded.note,
            updated_at = excluded.updated_at
        """,
        (normalized, password_hash, expires_at, note or "", now, now),
    )
    conn.commit()


def set_password(conn: sqlite3.Connection, hwid: str, password: str) -> None:
    normalized = normalize_hwid(hwid)
    changed = conn.execute(
        "UPDATE licenses SET password_hash = ?, updated_at = ? WHERE hwid = ?",
        (hash_password(password), iso_now(), normalized),
    ).rowcount
    conn.commit()
    if changed == 0:
        raise ValueError("License not found")


def set_active(conn: sqlite3.Connection, hwid: str, active: bool) -> None:
    normalized = normalize_hwid(hwid)
    changed = conn.execute(
        "UPDATE licenses SET active = ?, updated_at = ? WHERE hwid = ?",
        (1 if active else 0, iso_now(), normalized),
    ).rowcount
    conn.commit()
    if changed == 0:
        raise ValueError("License not found")


def extend(conn: sqlite3.Connection, hwid: str, days: int) -> str:
    normalized = normalize_hwid(hwid)
    row = conn.execute("SELECT expires_at FROM licenses WHERE hwid = ?", (normalized,)).fetchone()
    if not row:
        raise ValueError("License not found")
    base = max(parse_utc(row["expires_at"]), utc_now())
    expires_at = (base + _dt.timedelta(days=max(1, int(days)))).isoformat().replace("+00:00", "Z")
    conn.execute(
        "UPDATE licenses SET expires_at = ?, active = 1, updated_at = ? WHERE hwid = ?",
        (expires_at, iso_now(), normalized),
    )
    conn.commit()
    return expires_at


def check_license(conn: sqlite3.Connection, hwid, password: str) -> dict:
    candidates = hwid if isinstance(hwid, list) else [hwid]
    normalized_candidates = []
    for value in candidates:
        normalized = normalize_hwid(str(value))
        if normalized and normalized not in normalized_candidates:
            normalized_candidates.append(normalized)
    found_any = False
    active_any = False
    expired_any = False
    first_expired_at = ""
    for normalized in normalized_candidates:
        row = conn.execute("SELECT * FROM licenses WHERE hwid = ?", (normalized,)).fetchone()
        if not row:
            continue
        found_any = True
        if not bool(row["active"]):
            continue
        active_any = True
        expires_at = parse_utc(row["expires_at"])
        seconds_left = int((expires_at - utc_now()).total_seconds())
        if seconds_left <= 0:
            expired_any = True
            if not first_expired_at:
                first_expired_at = row["expires_at"]
            continue
        if not verify_password(row["password_hash"], password or ""):
            continue
        conn.execute("UPDATE licenses SET last_seen_at = ? WHERE hwid = ?", (iso_now(), normalized))
        conn.commit()
        return {
            "ok": True,
            "message": "Доступ разрешен",
            "hwid": normalized,
            "expiresAt": row["expires_at"],
            "secondsLeft": seconds_left,
        }
    if not found_any:
        return {"ok": False, "message": "HWID не найден на сервере."}
    if not active_any:
        return {"ok": False, "message": "Лицензия отключена владельцем."}
    if expired_any:
        return {"ok": False, "message": "Срок доступа истек.", "expiresAt": first_expired_at}
    if len(normalized_candidates) > 1:
        return {"ok": False, "message": "Неверный пароль для найденных HWID."}
    else:
        return {"ok": False, "message": "Неверный пароль."}
