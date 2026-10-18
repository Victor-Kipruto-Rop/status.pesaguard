from __future__ import annotations

import re
from html import escape
from html.parser import HTMLParser
from urllib.parse import urlsplit


BRAND_LOGO_URL = "https://pesaguard.co.ke/logo/logo.svg"
_BODY_RE = re.compile(r"<body\b[^>]*>(.*?)</body\s*>", re.IGNORECASE | re.DOTALL)
_HEAD_RE = re.compile(r"<head\b[^>]*>.*?</head\s*>", re.IGNORECASE | re.DOTALL)
_BRANDED_BODY_RE = re.compile(
    r"<body\b[^>]*\bdata-pesaguard-email-shell\s*=\s*['\"]v1['\"][^>]*>",
    re.IGNORECASE,
)
_HTML_TAG_RE = re.compile(r"<[a-z][^>]*>", re.IGNORECASE)
_DOCUMENT_TAG_RE = re.compile(
    r"<!doctype[^>]*>|</?(?:html|body)\b[^>]*>",
    re.IGNORECASE,
)
_HEADING_RE = re.compile(r"<h[1-6]\b", re.IGNORECASE)
_ALLOWED_TAGS = frozenset({
    "a", "b", "blockquote", "br", "caption", "code", "div", "em", "h1", "h2",
    "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "ol", "p", "span",
    "strong", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
})
_VOID_TAGS = frozenset({"br", "hr", "img"})
_ALLOWED_ATTRIBUTES = frozenset({
    "align", "alt", "border", "cellpadding", "cellspacing", "colspan", "height",
    "class", "href", "role", "rowspan", "src", "style", "title", "valign", "width",
})
_ALLOWED_CLASSES = frozenset({
    "email-outer", "email-card", "email-content", "email-footer",
})
_ALLOWED_STYLE_PROPERTIES = frozenset({
    "background", "background-color", "border", "border-collapse", "border-left",
    "border-radius", "border-spacing", "box-shadow", "color", "display", "font-family",
    "font-size", "font-weight", "height", "line-height", "margin", "margin-top",
    "max-width", "overflow-wrap", "padding", "text-align", "text-decoration",
    "vertical-align", "width",
})
_SAFE_CSS_VALUE_RE = re.compile(r"^[#(),.%\w\s+-]+$")
_BLOCKED_TAGS = frozenset({"iframe", "object", "script", "style", "svg"})


def render_branded_email_html(
    subject: str,
    *,
    html_body: str | None = None,
    text_body: str | None = None,
    preheader: str | None = None,
) -> str:
    """Wrap email content in the shared PesaGuard green, logo-led layout."""
    subject_text = subject.strip()
    preheader_text = (preheader or subject_text).strip()
    branded_html = (
        html_body
        if isinstance(html_body, str)
        and _BRANDED_BODY_RE.search(html_body)
        and BRAND_LOGO_URL in html_body
        else None
    )
    if branded_html is not None:
        existing_content = _extract_branded_content(branded_html)
        content = _sanitize_html_fragment(existing_content) if existing_content.strip() else ""
        if not content:
            content = _sanitize_html_fragment(_render_content(None, text_body))
            if not _HEADING_RE.search(content):
                content = (
                    '<h1 style="margin:0 0 20px;color:#0B2E24;font-family:Arial,Helvetica,sans-serif;'
                    'font-size:26px;line-height:1.3;font-weight:700;">'
                    f"{escape(subject_text)}</h1>{content}"
                )
    else:
        content = _render_content(html_body, text_body)
        if not _HEADING_RE.search(content):
            content = (
                '<h1 style="margin:0 0 20px;color:#0B2E24;font-family:Arial,Helvetica,sans-serif;'
                'font-size:26px;line-height:1.3;font-weight:700;">'
                f"{escape(subject_text)}</h1>{content}"
            )
        content = _sanitize_html_fragment(content)

    safe_subject = escape(subject_text)
    safe_preheader = escape(preheader_text)
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light">
  <meta name="supported-color-schemes" content="light">
  <title>{safe_subject}</title>
  <style>
    @media only screen and (max-width: 640px) {{
      .email-outer {{ padding: 20px 10px !important; }}
      .email-card {{ width: 100% !important; }}
      .email-content {{ padding: 28px 22px !important; }}
      .email-footer {{ padding: 20px 22px !important; }}
    }}
  </style>
</head>
<body data-pesaguard-email-shell="v1" style="margin:0;padding:0;background-color:#F3F7F4;color:#21392F;">
  <div data-pesaguard-email-preheader="v1" style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">
    {safe_preheader}
  </div>
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0"
         style="width:100%;border-collapse:collapse;background-color:#F3F7F4;">
    <tr>
      <td class="email-outer" align="center" style="padding:42px 16px;">
        <table class="email-card" role="presentation" width="620" cellspacing="0" cellpadding="0"
               border="0" style="width:100%;max-width:620px;border:1px solid #DCE9E1;
               border-radius:16px;border-collapse:separate;background-color:#FFFFFF;
               box-shadow:0 12px 36px rgba(11,46,36,0.08);overflow:hidden;">
          <tr>
            <td height="5" style="height:5px;background-color:#2ECC87;font-size:0;line-height:0;">&nbsp;</td>
          </tr>
          <tr>
            <td style="padding:26px 34px 22px;background-color:#FFFFFF;">
              <a href="https://pesaguard.co.ke/" style="display:inline-block;text-decoration:none;">
                <img src="{BRAND_LOGO_URL}" width="184" alt="PesaGuard"
                     style="display:block;width:184px;max-width:100%;height:auto;border:0;
                     color:#0B2E24;font-family:Arial,Helvetica,sans-serif;font-size:24px;
                     font-weight:700;text-decoration:none;">
              </a>
            </td>
          </tr>
          <tr>
            <td class="email-content"
                style="padding:34px;background-color:#FFFFFF;color:#21392F;
                font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.7;
                overflow-wrap:anywhere;">
              {content}
            </td>
          </tr>
          <tr>
            <td class="email-footer"
                style="padding:22px 34px;background-color:#F7FAF8;border-top:1px solid #E5EEE8;
                color:#64766D;font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:1.6;">
              <p style="margin:0 0 6px;color:#0B2E24;font-size:13px;font-weight:700;">
                PesaGuard
              </p>
              <p style="margin:0;">
                You are receiving this email from PesaGuard.
                <a href="https://pesaguard.co.ke/"
                   style="color:#117A52;text-decoration:underline;">pesaguard.co.ke</a>
              </p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>"""


def _render_content(html_body: str | None, text_body: str | None) -> str:
    html_value = html_body if isinstance(html_body, str) else ""
    text_value = text_body if isinstance(text_body, str) else ""
    if html_value.strip() and html_value.strip() != text_value.strip() and _HTML_TAG_RE.search(html_value):
        return _extract_document_body(html_value.strip())

    plain_text = text_value or html_value
    return escape(plain_text).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>\n")


def _extract_document_body(value: str) -> str:
    body_match = _BODY_RE.search(value)
    if body_match:
        return body_match.group(1).strip()
    without_head = _HEAD_RE.sub("", value)
    without_document_tags = _DOCUMENT_TAG_RE.sub("", without_head)
    return without_document_tags.strip()


def _extract_branded_content(value: str) -> str:
    parser = _BrandedContentExtractor()
    parser.feed(value)
    parser.close()
    return "".join(parser.output)


class _BrandedContentExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.output: list[str] = []
        self.depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if self.depth == 0:
            if tag.lower() == "td" and any(
                name.lower() == "class"
                and value is not None
                and "email-content" in value.split()
                for name, value in attrs
            ):
                self.depth = 1
            return
        self.output.append(self.get_starttag_text())
        if tag.lower() == "td":
            self.depth += 1

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if self.depth:
            self.output.append(self.get_starttag_text())

    def handle_endtag(self, tag: str) -> None:
        if not self.depth:
            return
        if tag.lower() == "td":
            self.depth -= 1
            if not self.depth:
                return
        self.output.append(f"</{tag}>")

    def handle_data(self, data: str) -> None:
        if self.depth:
            self.output.append(data)


class _EmailHTMLSanitizer(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.output: list[str] = []
        self.open_tags: list[str] = []
        self.blocked_tags: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        if self.blocked_tags:
            if tag in _BLOCKED_TAGS:
                self.blocked_tags.append(tag)
            return
        if tag in _BLOCKED_TAGS:
            self.blocked_tags.append(tag)
            return
        if tag not in _ALLOWED_TAGS:
            return
        safe_attrs = []
        for name, value in attrs:
            name = name.lower()
            if value is None or name not in _ALLOWED_ATTRIBUTES:
                continue
            if name == "style":
                value = _sanitize_inline_style(value)
                if not value:
                    continue
            elif name == "class":
                value = " ".join(
                    class_name for class_name in value.split()
                    if class_name in _ALLOWED_CLASSES
                )
                if not value:
                    continue
            elif name in {"href", "src"} and not _is_safe_email_url(value, image=name == "src"):
                continue
            else:
                value = value.strip()
            safe_attrs.append(f' {name}="{escape(value, quote=True)}"')
        self.output.append(f"<{tag}{''.join(safe_attrs)}>")
        if tag not in _VOID_TAGS:
            self.open_tags.append(tag)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() in _BLOCKED_TAGS:
            return
        self.handle_starttag(tag, attrs)
        if tag.lower() in self.open_tags:
            self.handle_endtag(tag)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if self.blocked_tags:
            if tag == self.blocked_tags[-1]:
                self.blocked_tags.pop()
            return
        if tag not in self.open_tags:
            return
        while self.open_tags:
            open_tag = self.open_tags.pop()
            self.output.append(f"</{open_tag}>")
            if open_tag == tag:
                break

    def handle_data(self, data: str) -> None:
        if not self.blocked_tags:
            self.output.append(escape(data))

    def close(self) -> None:
        super().close()
        while self.open_tags:
            self.output.append(f"</{self.open_tags.pop()}>")


def _sanitize_html_fragment(value: str) -> str:
    sanitizer = _EmailHTMLSanitizer()
    sanitizer.feed(value)
    sanitizer.close()
    return "".join(sanitizer.output)


def _sanitize_inline_style(value: str) -> str:
    declarations = []
    for declaration in value.split(";"):
        property_name, separator, property_value = declaration.partition(":")
        property_name = property_name.strip().lower()
        property_value = property_value.strip()
        if (
            separator
            and property_name in _ALLOWED_STYLE_PROPERTIES
            and _SAFE_CSS_VALUE_RE.fullmatch(property_value)
            and not any(
                token in property_value.lower()
                for token in ("url(", "expression", "javascript", "var(", "@import")
            )
        ):
            declarations.append(f"{property_name}:{property_value}")
    return ";".join(declarations)


def _is_safe_email_url(value: str, *, image: bool) -> bool:
    value = value.strip()
    parsed = urlsplit(value)
    if image:
        return (
            parsed.scheme.lower() == "https"
            and parsed.hostname is not None
            and parsed.hostname.lower() == "pesaguard.co.ke"
            and parsed.path.startswith("/logo/")
        )
    return (
        parsed.scheme.lower() in {"https", "mailto"}
        and bool(parsed.netloc or parsed.scheme.lower() == "mailto")
    ) or (value.startswith("/") and not value.startswith("//"))
