"""
Shared rendering helpers for PesaGuard email templates.

Provides small, reusable HTML fragments (detail rows, status chips, dividers)
and safe formatting utilities so templates stay consistent and never invent
financial figures or statuses.
"""

from __future__ import annotations

from datetime import datetime, timezone
from html import escape

from localization_utils import format_ke_currency, format_ke_datetime


def detail_row(label: str, value: str) -> str:
    return (
        '<tr><td style="padding:8px 0;border-bottom:1px solid #EEF2EF;'
        'color:#6B7280;font-size:13.5px;width:34%;">'
        + escape(label)
        + '</td><td style="padding:8px 0;border-bottom:1px solid #EEF2EF;'
        'color:#0B2E24;font-size:13.5px;font-weight:600;text-align:right;">'
        + escape(value)
        + "</td></tr>"
    )


def status_chip(status: str, kind: str = "neutral") -> str:
    colours = {
        "success": ("#EEF6EF", "#1E7A46"),
        "pending": ("#FDF3E3", "#9A6410"),
        "warning": ("#FDF3E3", "#9A6410"),
        "error": ("#FBEAE7", "#B3261E"),
        "failure": ("#FBEAE7", "#B3261E"),
        "critical": ("#FBEAE7", "#B3261E"),
        "neutral": ("#F3F4F6", "#4B5563"),
        "info": ("#EEF6EF", "#0B2E24"),
    }
    bg, text = colours.get(kind, colours["neutral"])
    return (
        '<span style="display:inline-block;padding:4px 10px;border-radius:12px;'
        f'background:{bg};color:{text};font-size:12px;font-weight:700;'
        'text-transform:uppercase;letter-spacing:0.4px;">'
        + escape(status)
        + "</span>"
    )


def divider() -> str:
    return (
        '<table width="100%"><tr><td height="1" style="padding:0;"></td></tr></table>'
    )


def paragraph(text: str) -> str:
    return "<p>" + escape(text) + "</p>"


def link(text: str, url: str) -> str:
    return '<a href="' + escape(url) + '">' + escape(text) + "</a>"


def mask_email(value: str) -> str:
    if "@" not in value:
        return escape(value)
    local, domain = value.rsplit("@", 1)
    if len(local) <= 2:
        masked = local
    else:
        masked = local[0] + "•" * (len(local) - 2) + local[-1]
    return escape(masked) + "@" + escape(domain)


def mask_reference(value: str, keep: int = 4) -> str:
    if not value:
        return ""
    value = str(value)
    if len(value) <= keep:
        return escape(value)
    return "•" * (len(value) - keep) + escape(value[-keep:])


def format_datetime(value: str) -> str:
    if not value:
        return "not specified"
    return format_ke_datetime(value) or str(value)


def format_currency(value: str) -> str:
    if value is None or value == "":
        return "KES 0.00"
    return format_ke_currency(value)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def paragraph_list(items: list[str]) -> str:
    return "".join("<li style=\"margin-bottom:6px;\">" + escape(i) + "</li>" for i in items)


__all__ = [
    "detail_row",
    "status_chip",
    "divider",
    "paragraph",
    "link",
    "mask_email",
    "mask_reference",
    "format_datetime",
    "format_currency",
    "now_iso",
    "paragraph_list",
]
