"""
PesaGuard email template packages.

Importing this package loads every template module so each registers its
renderer with the central registry and dispatcher.
"""

from __future__ import annotations

from . import auth  # noqa: F401
from . import onboarding  # noqa: F401
from . import mpesa  # noqa: F401
from . import reconciliation  # noqa: F401
from . import risk  # noqa: F401
from . import developer  # noqa: F401
from . import team  # noqa: F401
from . import reports  # noqa: F401
from . import support  # noqa: F401
from . import operations  # noqa: F401

__all__ = ["auth", "onboarding", "mpesa", "reconciliation", "risk", "developer", "team", "reports", "support", "operations"]
