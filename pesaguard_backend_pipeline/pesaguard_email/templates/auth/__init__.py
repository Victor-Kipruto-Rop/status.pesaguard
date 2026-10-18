"""
Authentication and account lifecycle email templates (A01-A23).
"""

from __future__ import annotations

from . import account_deletion_completed  # noqa: F401
from . import account_deletion_requested  # noqa: F401
from . import account_locked  # noqa: F401
from . import account_recovery_completed  # noqa: F401
from . import account_recovery_requested  # noqa: F401
from . import account_unlocked  # noqa: F401
from . import email_change_completed  # noqa: F401
from . import email_change_requested  # noqa: F401
from . import login_notification  # noqa: F401
from . import mfa_challenge  # noqa: F401
from . import mfa_disabled  # noqa: F401
from . import mfa_enabled  # noqa: F401
from . import new_device  # noqa: F401
from . import password_change_blocked  # noqa: F401
from . import password_changed  # noqa: F401
from . import password_reset_completed  # noqa: F401
from . import password_reset_requested  # noqa: F401
from . import session_revoked  # noqa: F401
from . import suspicious_login  # noqa: F401
from . import verification  # noqa: F401
from . import verification_expired  # noqa: F401
from . import verification_reminder  # noqa: F401
from . import welcome  # noqa: F401

__all__ = [
    "account_deletion_completed",
    "account_deletion_requested",
    "account_locked",
    "account_recovery_completed",
    "account_recovery_requested",
    "account_unlocked",
    "email_change_completed",
    "email_change_requested",
    "login_notification",
    "mfa_challenge",
    "mfa_disabled",
    "mfa_enabled",
    "new_device",
    "password_change_blocked",
    "password_changed",
    "password_reset_completed",
    "password_reset_requested",
    "session_revoked",
    "suspicious_login",
    "verification",
    "verification_expired",
    "verification_reminder",
    "welcome",
]
