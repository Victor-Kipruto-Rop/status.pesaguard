"""
Central PesaGuard email icon registry.

Maps each email event category to an existing project icon asset, a semantic
colour, and an accessible label. All templates use this shared mapping; icons
are never hardcoded independently per template.

Icon assets are the existing SVG icons committed under assets/logo/ and
assets/icons/, served from the verified pesaguard.co.ke domain. Where an SVG
cannot be rendered by a mail client, the visible text category label preserves
meaning (images are never the only signal).
"""

from __future__ import annotations

from dataclasses import dataclass

from .brand import ICON_BASE_URL


@dataclass(frozen=True)
class EmailIcon:
    """A single event-profile icon entry."""

    category: str
    asset: str  # filename under ICON_BASE_URL
    colour: str  # semantic brand colour (hex)
    label: str  # accessible, visible text label


# Category -> icon. Asset filenames reference existing project icons.
# logo/icon.svg is the brand mark used for general/unknown categories.
ICON_REGISTRY: dict[str, EmailIcon] = {
    "authentication": EmailIcon(
        category="authentication",
        asset="icon.svg",
        colour="#6B7280",
        label="Account",
    ),
    "email_verification": EmailIcon(
        category="email_verification",
        asset="icon.svg",
        colour="#2ECC87",
        label="Email verification",
    ),
    "password_recovery": EmailIcon(
        category="password_recovery",
        asset="icon.svg",
        colour="#6B7280",
        label="Password recovery",
    ),
    "security_alert": EmailIcon(
        category="security_alert",
        asset="icon.svg",
        colour="#C0392B",
        label="Security alert",
    ),
    "account_access": EmailIcon(
        category="account_access",
        asset="icon.svg",
        colour="#2ECC87",
        label="Account access",
    ),
    "tenant_onboarding": EmailIcon(
        category="tenant_onboarding",
        asset="icon.svg",
        colour="#6B7280",
        label="Tenant onboarding",
    ),
    "approval": EmailIcon(
        category="approval",
        asset="icon.svg",
        colour="#2ECC87",
        label="Approved",
    ),
    "rejection": EmailIcon(
        category="rejection",
        asset="icon.svg",
        colour="#C0392B",
        label="Rejected",
    ),
    "mpesa_integration": EmailIcon(
        category="mpesa_integration",
        asset="icon.svg",
        colour="#2ECC87",
        label="M-Pesa integration",
    ),
    "transaction_received": EmailIcon(
        category="transaction_received",
        asset="icon.svg",
        colour="#2ECC87",
        label="Transaction received",
    ),
    "payment_failure": EmailIcon(
        category="payment_failure",
        asset="icon.svg",
        colour="#C0392B",
        label="Payment failure",
    ),
    "reconciliation": EmailIcon(
        category="reconciliation",
        asset="icon.svg",
        colour="#2ECC87",
        label="Reconciliation",
    ),
    "data_quality": EmailIcon(
        category="data_quality",
        asset="icon.svg",
        colour="#6B7280",
        label="Data quality",
    ),
    "unmatched_transaction": EmailIcon(
        category="unmatched_transaction",
        asset="icon.svg",
        colour="#E67E22",
        label="Unmatched transaction",
    ),
    "fraud_risk": EmailIcon(
        category="fraud_risk",
        asset="icon.svg",
        colour="#C0392B",
        label="Fraud / risk",
    ),
    "api": EmailIcon(
        category="api",
        asset="icon.svg",
        colour="#6B7280",
        label="API",
    ),
    "api_key": EmailIcon(
        category="api_key",
        asset="icon.svg",
        colour="#6B7280",
        label="API key",
    ),
    "webhooks": EmailIcon(
        category="webhooks",
        asset="icon.svg",
        colour="#6B7280",
        label="Webhooks",
    ),
    "rate_limiting": EmailIcon(
        category="rate_limiting",
        asset="icon.svg",
        colour="#E67E22",
        label="Rate limiting",
    ),
    "team_invitation": EmailIcon(
        category="team_invitation",
        asset="icon.svg",
        colour="#6B7280",
        label="Team invitation",
    ),
    "reports": EmailIcon(
        category="reports",
        asset="icon.svg",
        colour="#2ECC87",
        label="Reports",
    ),
    "support": EmailIcon(
        category="support",
        asset="icon.svg",
        colour="#6B7280",
        label="Support",
    ),
    "maintenance": EmailIcon(
        category="maintenance",
        asset="icon.svg",
        colour="#6B7280",
        label="Maintenance",
    ),
    "incident": EmailIcon(
        category="incident",
        asset="icon.svg",
        colour="#C0392B",
        label="Incident",
    ),
    "recovery": EmailIcon(
        category="recovery",
        asset="icon.svg",
        colour="#2ECC87",
        label="Recovery",
    ),
    "privacy": EmailIcon(
        category="privacy",
        asset="icon.svg",
        colour="#6B7280",
        label="Privacy",
    ),
    "administrative": EmailIcon(
        category="administrative",
        asset="icon.svg",
        colour="#6B7280",
        label="Administrative action",
    ),
}


def icon_for(category: str) -> EmailIcon:
    """Return the icon entry for a category, falling back to the neutral mark."""
    entry = ICON_REGISTRY.get(category)
    if entry is None:
        return ICON_REGISTRY["administrative"]
    return entry


def icon_image_html(icon: EmailIcon, *, width: int = 28, height: int = 28) -> str:
    """Render an inline image tag for an icon asset.

    The asset URL is served from the verified domain. The alt text carries the
    accessible label so the email remains meaningful when images are blocked.
    """
    from html import escape

    return (
        f'<img src="{ICON_BASE_URL}{escape(icon.asset)}" '
        f'width="{width}" height="{height}" '
        f'alt="{escape(icon.label)}" '
        f'style="display:block;width:{width}px;height:{height}px;" '
        'role="img" aria-label=""/>'
    )


__all__ = ["EmailIcon", "ICON_REGISTRY", "icon_for", "icon_image_html"]
