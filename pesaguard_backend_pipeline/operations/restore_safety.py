"""Safety checks for destructive PostgreSQL restore operations."""

from __future__ import annotations

from urllib.parse import parse_qs, unquote, urlsplit


def _database_identity(database_url: str) -> tuple[str, int, str]:
    if not database_url:
        raise ValueError("PostgreSQL database URL is required")
    try:
        parsed = urlsplit(database_url)
        port = parsed.port
    except ValueError as exc:
        raise ValueError("PostgreSQL database URL is invalid") from exc

    scheme = parsed.scheme.split("+", 1)[0]
    database_name = unquote(parsed.path.lstrip("/"))
    query = parse_qs(parsed.query, keep_blank_values=True)
    if (
        scheme not in {"postgresql", "postgres"}
        or not parsed.hostname
        or not database_name
        or parsed.fragment
        or any(key.lower() in {"host", "hostaddr", "port", "dbname", "service"} for key in query)
    ):
        raise ValueError("restore target must be a complete PostgreSQL URL without connection overrides")

    return parsed.hostname.rstrip(".").lower(), port or 5432, database_name


def validate_restore_target(
    target_database_url: str,
    *,
    application_database_url: str | None = None,
    confirmed_isolated_target: bool = False,
) -> str:
    """Require operator confirmation and reject restoring over the app database."""
    target_identity = _database_identity(target_database_url)
    if not confirmed_isolated_target:
        raise ValueError(
            "restore requires explicit isolated-target confirmation; use --confirm-isolated-target only for a disposable recovery database"
        )
    if application_database_url:
        application_scheme = urlsplit(application_database_url).scheme.split("+", 1)[0]
        if (
            application_scheme in {"postgresql", "postgres"}
            and target_identity == _database_identity(application_database_url)
        ):
            raise ValueError("restore target matches the configured application database")
    host, port, database_name = target_identity
    display_host = f"[{host}]" if ":" in host else host
    return f"{display_host}:{port}/{database_name}"
