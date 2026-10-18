"""
Premium PesaGuard email shell.

Produces a consistent, email-client-safe HTML wrapper for every transactional
email: branded header, event icon profile, category label, headline, body,
primary CTA, notices, support section, and footer. Uses table-based layout,
inline CSS, system fonts, and the verified PesaGuard logo. Colours never carry
meaning alone; every status is accompanied by visible text.
"""

from __future__ import annotations

from html import escape

from .brand import (
    BRAND_LOGO_DARK_URL,
    BRAND_LOGO_URL,
    BRAND_NAME,
    CONTENT_WIDTH,
    FONT_STACK,
    SUPPORT_URL,
    PRIVACY_URL,
    TERMS_URL,
)
from .icons import EmailIcon, icon_for, icon_image_html


def _style_block() -> str:
    return (
        "<style>"
        "body{margin:0;padding:0;background:#F2F7F4;color:#0B2E24;font-family:"
        + FONT_STACK
        + ";font-size:15px;line-height:1.6;-webkit-font-smoothing:antialiased;}"
        "table{border-collapse:collapse;}"
        ".outer{width:100%;background:#F2F7F4;padding:24px 0;}"
        ".wrap{max-width:" + str(CONTENT_WIDTH) + "px;margin:0 auto;background:#FFFFFF;"
        "border-radius:12px;overflow:hidden;}"
        ".header{padding:28px 32px 22px;}"
        ".logo{display:block;width:36px;height:36px;}"
        ".brandname{display:inline-block;margin-left:12px;font-size:20px;font-weight:700;"
        "color:#0B2E24;vertical-align:middle;letter-spacing:-0.3px;}"
        ".categorybar{display:flex;align-items:center;gap:10px;padding:0 32px 16px;}"
        ".categorylabel{font-size:12px;font-weight:700;letter-spacing:0.6px;"
        "text-transform:uppercase;color:#6B7280;white-space:nowrap;}"
        ".headline{padding:0 32px 10px;font-size:24px;font-weight:700;color:#0B2E24;"
        "line-height:1.3;margin:0;}"
        ".body{padding:0 32px 20px;color:#1F2937;font-size:15px;line-height:1.6;}"
        ".body p{margin:0 0 14px;}"
        ".body a{color:#2ECC87;text-decoration:none;font-weight:600;}"
        ".cta{padding:22px 32px;}"
        ".cta-button{display:block;background:#2ECC87;color:#FFFFFF;border:none;"
        "border-radius:8px;padding:14px 24px;font-size:15px;font-weight:700;"
        "text-align:center;width:100%;text-decoration:none;}"
        ".secondary{padding:0 32px 16px;}"
        ".secondary-link{display:inline-block;color:#6B7280;font-size:14px;"
        "text-decoration:underline;}"
        ".notice{padding:14px 32px;margin-bottom:16px;border-radius:8px;"
        "font-size:13.5px;line-height:1.5;}"
        ".notice-info{background:#EEF6EF;border-left:4px solid #2ECC87;color:#0B2E24;}"
        ".notice-warn{background:#FDF3E3;border-left:4px solid #E67E22;color:#5C3D0E;}"
        ".notice-error{background:#FBEAE7;border-left:4px solid #C0392B;color:#B3261E;}"
        ".footer{padding:24px 32px;border-top:1px solid #E4EDE6;color:#6B7280;"
        "font-size:12.5px;line-height:1.5;}"
        ".footer a{color:#0B2E24;text-decoration:none;}"
        ".footer .muted{color:#9CA3AF;}"
        ".preheader{display:none!important;max-height:0;overflow:hidden;"
        "mso-hide:all;visibility:hidden;}"
        ".mobile-pad{display:none;}"
        "@media only screen and (max-width:620px){"
        ".outer{padding:16px 0;}"
        ".wrap{border-radius:10px;}"
        ".header{padding:22px 20px 16px;}"
        ".categorybar{padding:0 20px 12px;}"
        ".headline{padding:0 20px 8px;font-size:20px;}"
        ".body{padding:0 20px 16px;font-size:14.5px;}"
        ".cta{padding:16px 20px;}"
        ".secondary{padding:0 20px 12px;}"
        ".notice{padding:12px 20px;font-size:13px;}"
        ".footer{padding:18px 20px;font-size:12px;}"
        ".mobile-pad{display:block;height:16px;}"
        "}"
        "</style>"
    )


def _preheader(text: str) -> str:
    return '<div class="preheader">' + escape(text) + "</div>"


def _logo_html(dark: bool = False) -> str:
    url = BRAND_LOGO_DARK_URL if dark else BRAND_LOGO_URL
    return (
        '<img src="' + url + '" width="36" height="36" alt="' + escape(BRAND_NAME)
        + '" class="logo" role="img" aria-label="PesaGuard"/>'
    )


def _notice_block(notice_html: str, notice_kind: str) -> str:
    notice_class = {
        "warn": "notice-warn",
        "warning": "notice-warn",
        "error": "notice-error",
        "failure": "notice-error",
        "critical": "notice-error",
    }.get(notice_kind, "notice-info")
    return (
        '<div class="notice ' + notice_class + '">' + escape(notice_html) + "</div>"
    )


def _support_block(support_html: str | None) -> str:
    if support_html:
        return (
            '<div class="notice notice-info">' + escape(support_html) + "</div>"
        )
    return (
        '<div class="notice notice-info">'
        "Need help? Contact <a href=\""
        + escape(SUPPORT_URL)
        + "\">PesaGuard support</a>."
        "</div>"
    )


def _footer(extra_footer_html: str | None) -> str:
    if extra_footer_html:
        inner = escape(extra_footer_html) + "<br/>"
    else:
        inner = ""
    inner += (
        f"{escape(BRAND_NAME)} &mdash; financial transaction reconciliation.<br/>"
        f'<a href="{escape(PRIVACY_URL)}">Privacy</a> &middot; '
        f'<a href="{escape(TERMS_URL)}">Terms</a> &middot; '
        f'<a href="{escape(SUPPORT_URL)}">Support</a>'
    )
    return (
        '<table class="footer" width="100%"><tr><td>' + inner + "</td></tr></table>"
    )


def render_shell(
    *,
    preheader: str,
    subject: str,
    category: str,
    headline: str,
    body_html: str,
    icon: EmailIcon | None = None,
    cta_label: str | None = None,
    cta_url: str | None = None,
    cta_button_html: str | None = None,
    secondary_html: str | None = None,
    notice_html: str | None = None,
    notice_kind: str = "info",
    support_html: str | None = None,
    extra_footer_html: str | None = None,
    plain_text: str | None = None,
    dark_logo: bool = False,
) -> tuple[str, str]:
    """Render the full premium email (HTML + plain text).

    Returns ``(html, plain_text)``. Callers pass escaped, safe HTML fragments
    for body/CTA/notice/support content.
    """
    icon_html = ""
    icon_label = ""
    if icon is not None:
        icon_html = icon_image_html(icon, width=28, height=28)
        icon_label = icon.label

    category_label = escape((icon.label if icon else "PesaGuard"))

    cta_block = ""
    if cta_button_html:
        cta_block = '<div class="cta">' + cta_button_html + "</div>"
    elif cta_label and cta_url:
        cta_block = (
            '<div class="cta">'
            '<a href="' + escape(cta_url) + '" class="cta-button">'
            + escape(cta_label) + "</a></div>"
        )

    notice_block = ""
    if notice_html:
        notice_block = _notice_block(notice_html, notice_kind)

    secondary_block = ""
    if secondary_html:
        secondary_block = (
            '<div class="secondary">' + escape(secondary_html) + "</div>"
        )

    support_block = _support_block(support_html)
    footer = _footer(extra_footer_html)

    parts = [
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\"",
        " content=\"width=device-width, initial-scale=1.0\"><title>",
        escape(subject),
        "</title>",
        _style_block(),
        "</head><body>",
        '<table class="outer" width="100%"><tr><td align="center">',
        '<table class="wrap" width="100%"><tr><td>',
        _preheader(preheader),
        '<table class="header" width="100%"><tr><td>',
        _logo_html(dark=dark_logo),
        '<span class="brandname">',
        escape(BRAND_NAME),
        "</span></td></tr></table>",
        '<table class="categorybar" width="100%"><tr><td>',
        icon_html,
        '<span class="categorylabel">',
        category_label,
        "</span></td></tr></table>",
        '<table class="headline" width="100%"><tr><td>',
        escape(headline),
        "</td></tr></table>",
        '<table class="body" width="100%"><tr><td>',
        body_html,
        "</td></tr></table>",
        cta_block,
        secondary_block,
        notice_block,
        support_block,
        footer,
        "</td></tr></table></td></tr></table>",
        "</body></html>",
    ]
    html = "".join(parts)

    plain = plain_text or _plain_text_fallback(
        subject=subject,
        category=icon_label,
        headline=headline,
        body_html=body_html,
        cta_label=cta_label,
        cta_url=cta_url,
        notice=notice_html,
        support=support_html,
    )
    return html, plain


def _plain_text_fallback(
    *,
    subject: str,
    category: str,
    headline: str,
    body_html: str,
    cta_label: str | None,
    cta_url: str | None,
    notice: str | None,
    support: str | None,
) -> str:
    from html import unescape

    lines = [subject, ""]
    if category:
        lines.append(category.upper())
    lines.append(unescape(headline))
    lines.append("")
    lines.append(unescape(_strip_tags(body_html)))
    if cta_label and cta_url:
        lines.append("")
        lines.append(f"{cta_label}: {cta_url}")
    if notice:
        lines.append("")
        lines.append(unescape(_strip_tags(notice)))
    lines.append("")
    lines.append(f"PesaGuard &mdash; {SUPPORT_URL}")
    lines.append(PRIVACY_URL)
    lines.append(TERMS_URL)
    return "\n".join(lines)


def _strip_tags(html: str) -> str:
    import re

    text = re.sub(r"<[^>]+>", " ", html)
    text = text.replace("&mdash;", "-").replace("&middot;", "·")
    return " ".join(text.split())


__all__ = ["render_shell"]
