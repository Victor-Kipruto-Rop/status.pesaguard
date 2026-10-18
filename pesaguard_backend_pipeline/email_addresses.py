from __future__ import annotations

from dataclasses import asdict, dataclass
import re
from email.utils import parseaddr


@dataclass(frozen=True)
class EmailAddressDirectory:
    support: str = "support@pesaguard.co.ke"
    sales: str = "sales@pesaguard.co.ke"
    billing: str = "billing@pesaguard.co.ke"
    security: str = "security@pesaguard.co.ke"
    privacy: str = "privacy@pesaguard.co.ke"
    compliance: str = "compliance@pesaguard.co.ke"
    alerts: str = "alerts@pesaguard.co.ke"
    no_reply: str = "no-reply@pesaguard.co.ke"
    onboarding: str = "onboarding@pesaguard.co.ke"
    partnerships: str = "partnerships@pesaguard.co.ke"
    operations: str = "operations@pesaguard.co.ke"
    incidents: str = "incidents@pesaguard.co.ke"
    legal: str = "legal@pesaguard.co.ke"
    abuse: str = "abuse@pesaguard.co.ke"
    status: str = "status@pesaguard.co.ke"
    careers: str = "careers@pesaguard.co.ke"
    press: str = "press@pesaguard.co.ke"

    def as_mapping(self) -> dict[str, str]:
        return asdict(self)

    def for_role(self, role: str) -> str:
        try:
            return self.as_mapping()[role]
        except KeyError:
            raise ValueError(f"unsupported PesaGuard email role: {role}") from None


EMAIL_ADDRESSES = EmailAddressDirectory()


def is_pesaguard_email_address(value: str) -> bool:
    if not isinstance(value, str) or "\r" in value or "\n" in value:
        return False
    _, address = parseaddr(value)
    return bool(
        re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", address)
        and address.rsplit("@", 1)[1].lower() == "pesaguard.co.ke"
    )


EMAIL_ROLE_ROUTING: dict[str, tuple[str, str]] = {
    "transactional": ("no_reply", "support"),
    "reconciliation": ("no_reply", "support"),
    "escalation": ("alerts", "incidents"),
    "security": ("security", "security"),
    "support": ("support", "support"),
    "sales": ("sales", "sales"),
    "billing": ("billing", "billing"),
    "privacy": ("privacy", "privacy"),
    "compliance": ("compliance", "compliance"),
    "onboarding": ("onboarding", "support"),
    "partnerships": ("partnerships", "partnerships"),
    "operations": ("operations", "operations"),
    "incidents": ("incidents", "incidents"),
    "legal": ("legal", "legal"),
    "abuse": ("abuse", "abuse"),
    "status": ("status", "support"),
    "careers": ("careers", "careers"),
    "press": ("press", "press"),
}


def addresses_for_purpose(purpose: str) -> tuple[str, str]:
    """Return the configured sender and reply-to roles for a trusted mail purpose."""
    try:
        sender_role, reply_to_role = EMAIL_ROLE_ROUTING[purpose]
    except KeyError:
        raise ValueError(f"unsupported PesaGuard email purpose: {purpose}") from None
    return EMAIL_ADDRESSES.for_role(sender_role), EMAIL_ADDRESSES.for_role(reply_to_role)
