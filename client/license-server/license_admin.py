#!/usr/bin/env python3
import argparse
import getpass

from license_common import (
    DEFAULT_DB,
    connect,
    expires_after_days,
    extend,
    iso_now,
    set_active,
    set_password,
    upsert_license,
)


def password_arg(value: str | None) -> str:
    if value:
        return value
    first = getpass.getpass("Password: ")
    second = getpass.getpass("Repeat password: ")
    if first != second:
        raise SystemExit("Passwords do not match")
    return first


def cmd_init(args):
    with connect(args.db):
        pass
    print(f"DB ready: {args.db}")


def cmd_add(args):
    password = password_arg(args.password)
    expires_at = args.until or expires_after_days(args.days)
    with connect(args.db) as conn:
        upsert_license(conn, args.hwid, password, expires_at, args.note)
    print(f"License saved: {args.hwid} until {expires_at}")


def cmd_extend(args):
    with connect(args.db) as conn:
        expires_at = extend(conn, args.hwid, args.days)
    print(f"License extended: {args.hwid} until {expires_at}")


def cmd_set_password(args):
    with connect(args.db) as conn:
        set_password(conn, args.hwid, password_arg(args.password))
    print(f"Password updated: {args.hwid}")


def cmd_disable(args):
    with connect(args.db) as conn:
        set_active(conn, args.hwid, False)
    print(f"License disabled: {args.hwid}")


def cmd_enable(args):
    with connect(args.db) as conn:
        set_active(conn, args.hwid, True)
    print(f"License enabled: {args.hwid}")


def cmd_list(args):
    with connect(args.db) as conn:
        rows = conn.execute(
            "SELECT hwid, active, expires_at, last_seen_at, note FROM licenses ORDER BY updated_at DESC"
        ).fetchall()
    if not rows:
        print("No licenses yet.")
        return
    print(f"{'ACTIVE':<7} {'EXPIRES':<25} {'LAST SEEN':<25} {'HWID':<45} NOTE")
    for row in rows:
        print(
            f"{'yes' if row['active'] else 'no':<7} "
            f"{row['expires_at']:<25} "
            f"{(row['last_seen_at'] or '-'):<25} "
            f"{row['hwid']:<45} "
            f"{row['note']}"
        )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="SMC Chart license database admin")
    parser.add_argument("--db", default=DEFAULT_DB, help=f"SQLite DB path, default: {DEFAULT_DB}")
    sub = parser.add_subparsers(required=True)

    init = sub.add_parser("init", help="Create database schema")
    init.set_defaults(func=cmd_init)

    add = sub.add_parser("add", help="Add or replace HWID license")
    add.add_argument("--hwid", required=True)
    add.add_argument("--password")
    add.add_argument("--days", type=int, default=30)
    add.add_argument("--until", help="ISO UTC date, for example 2026-07-08T00:00:00Z")
    add.add_argument("--note", default="")
    add.set_defaults(func=cmd_add)

    ext = sub.add_parser("extend", help="Extend active license from current expiry or now")
    ext.add_argument("--hwid", required=True)
    ext.add_argument("--days", type=int, default=30)
    ext.set_defaults(func=cmd_extend)

    pw = sub.add_parser("set-password", help="Change password")
    pw.add_argument("--hwid", required=True)
    pw.add_argument("--password")
    pw.set_defaults(func=cmd_set_password)

    disable = sub.add_parser("disable", help="Disable license")
    disable.add_argument("--hwid", required=True)
    disable.set_defaults(func=cmd_disable)

    enable = sub.add_parser("enable", help="Enable license")
    enable.add_argument("--hwid", required=True)
    enable.set_defaults(func=cmd_enable)

    listing = sub.add_parser("list", help="List licenses")
    listing.set_defaults(func=cmd_list)
    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
