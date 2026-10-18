"""
PesaGuard premium email brand configuration.

All brand values are sourced from the existing project brand assets and the
verified palette (mint #2ECC87, deep forest #0B2E24, soft green #F2F7F4,
white #FFFFFF). No colours are invented here.
"""

from __future__ import annotations

from dataclasses import dataclass

# Publicly accessible, HTTPS logo assets on the verified PesaGuard domain.
# The public website (pesaguard.co.ke) serves these assets; they are the same
# files committed under assets/logo/ and pesaguard.co.ke/public/logo/.
BRAND_LOGO_URL = "https://pesaguard.co.ke/logo/logo.svg"
BRAND_LOGO_DARK_URL = "https://pesaguard.co.ke/logo/logo-dark.svg"
BRAND_FAVICON_URL = "https://pesaguard.co.ke/favicon.svg"
BRAND_ICON_URL = "https://pesaguard.co.ke/logo/icon.svg"
BRAND_MARK_URL = "https://pesaguard.co.ke/logo/brand-mark.svg"

# Icon assets live under /logo/ so they pass the existing email-branding URL
# allowlist (email_branding._is_safe_email_url permits pesaguard.co.ke/logo/*).
ICON_BASE_URL = "https://pesaguard.co.ke/logo/"

# Canonical service hosts. Provision DNS, TLS, and routing before production use.
DEFAULT_DASHBOARD_URL = "https://app.pesaguard.co.ke"
DEFAULT_DEVELOPER_URL = "https://developers.pesaguard.co.ke"
DEFAULT_API_URL = "https://api.pesaguard.co.ke"
DEFAULT_PUBLIC_URL = "https://pesaguard.co.ke"
DEFAULT_STATUS_URL = "https://status.pesaguard.co.ke"

# Sender identities (verified via email_addresses.py purpose routing).
SENDER_NO_REPLY = "no-reply@pesaguard.co.ke"
SENDER_SUPPORT = "support@pesaguard.co.ke"
SENDER_SECURITY = "security@pesaguard.co.ke"
SENDER_ONBOARDING = "onboarding@pesaguard.co.ke"
SENDER_OPERATIONS = "operations@pesaguard.co.ke"
SENDER_INCIDENTS = "incidents@pesaguard.co.ke"
SENDER_DEVELOPERS = "developers@pesaguard.co.ke"


@dataclass(frozen=True)
class BrandPalette:
    mint: str = "#2ECC87"
    white: str = "#FFFFFF"
    deep_forest: str = "#0B2E24"
    soft_green: str = "#F2F7F4"
    gray: str = "#6B7280"
    light_gray: str = "#F3F4F6"
    border: str = "#D1DDB6"
    red: str = "#C0392B"
    red_light: str = "#FBEAE7"
    amber: str = "#E67E22"
    amber_light: str = "#FDF3E3"
    success_text: str = "#1E7A46"
    warning_text: str = "#9A6410"
    error_text: str = "#B3261E"


PALETTE = BrandPalette()

# Semantic colour roles used across templates. Colour is never the only signal;
# every status is accompanied by visible text.
STATUS_COLOURS = {
    "success": PALETTE.mint,
    "pending": PALETTE.amber,
    "warning": PALETTE.amber,
    "error": PALETTE.red,
    "failure": PALETTE.red,
    "critical": PALETTE.red,
    "neutral": PALETTE.gray,
    "info": PALETTE.deep_forest,
}

# Maximum content width for the premium email shell (px).
CONTENT_WIDTH = 600

# System-font stack: no dependence on remote web fonts loading reliably.
FONT_STACK = "Arial, Helvetica, sans-serif"

BRAND_NAME = "PesaGuard"
PRIVACY_URL = f"{DEFAULT_PUBLIC_URL}/privacy"
TERMS_URL = f"{DEFAULT_PUBLIC_URL}/terms"
SUPPORT_URL = f"{DEFAULT_DASHBOARD_URL}/help-support"
