"""
PesaGuard email URL configuration.

All CTA and fallback URLs are built from trusted environment configuration,
never from user-controlled host headers. Defaults target the canonical PesaGuard
service hosts; override via environment variables in each deployment.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from .brand import (
    DEFAULT_API_URL,
    DEFAULT_DASHBOARD_URL,
    DEFAULT_DEVELOPER_URL,
    DEFAULT_PUBLIC_URL,
    DEFAULT_STATUS_URL,
)


def _env(name: str, default: str) -> str:
    value = os.environ.get(name)
    if not value or not value.strip():
        return default
    return value.strip()


@dataclass(frozen=True)
class EmailUrls:
    dashboard: str
    developer: str
    api: str
    public: str
    status: str

    def verify_email(self, token: str) -> str:
        return f"{self.api}/auth/verify-email?token={token}"

    def verify_email_resend(self) -> str:
        return f"{self.api}/auth/verify-email/resend"

    def reset_password(self, token: str) -> str:
        return f"{self.api}/auth/password-reset/confirm?token={token}"

    def reset_password_request(self) -> str:
        return f"{self.api}/auth/password-reset/request"

    def login(self) -> str:
        return f"{self.dashboard}/"

    def security_settings(self) -> str:
        return f"{self.dashboard}/account-authentication"

    def dashboard_home(self) -> str:
        return f"{self.dashboard}/"

    def transactions(self) -> str:
        return f"{self.dashboard}/transactions"

    def transaction_detail(self, transaction_id: str) -> str:
        return f"{self.dashboard}/transactions/{transaction_id}"

    def reconciliation(self) -> str:
        return f"{self.dashboard}/reconciliation"

    def exceptions(self) -> str:
        return f"{self.dashboard}/exceptions"

    def integrations(self) -> str:
        return f"{self.dashboard}/integrations"

    def webhooks(self) -> str:
        return f"{self.dashboard}/webhooks"

    def reports(self) -> str:
        return f"{self.dashboard}/reports"

    def settings(self) -> str:
        return f"{self.dashboard}/settings"

    def notifications(self) -> str:
        return f"{self.dashboard}/notifications"

    def preferences(self) -> str:
        return f"{self.dashboard}/preferences"

    def team_members(self) -> str:
        return f"{self.dashboard}/team-members"

    def api_developer_resources(self) -> str:
        return f"{self.dashboard}/api-developer-resources"

    def developer_project(self, project_id: str) -> str:
        return f"{self.developer}/projects/{project_id}"

    def developer_api_keys(self, project_id: str) -> str:
        return f"{self.developer}/projects/{project_id}/api-keys"

    def developer_webhooks(self, project_id: str) -> str:
        return f"{self.developer}/projects/{project_id}/webhooks"

    def support(self) -> str:
        return f"{self.dashboard}/help-support"

    def status_page(self) -> str:
        return self.status

    def public_home(self) -> str:
        return self.public

    def privacy(self) -> str:
        return f"{self.public}/privacy"

    def terms(self) -> str:
        return f"{self.public}/terms"

    def unsubscribe(self, recipient: str) -> str:
        """Preference/unsubscribe link. Must be verified against the real
        unsubscribe endpoint before use in production."""
        return f"{self.api}/communications/preferences/{recipient}"

    def api_keys(self, project_id: str) -> str:
        return self.developer_api_keys(project_id)

    def production_access(self, project_id: str) -> str:
        return f"{self.developer}/projects/{project_id}/production-access"


def urls_from_env() -> EmailUrls:
    """Build URL configuration from environment variables."""
    return EmailUrls(
        dashboard=_env("PESAGUARD_DASHBOARD_URL", DEFAULT_DASHBOARD_URL),
        developer=_env("PESAGUARD_DEVELOPER_URL", DEFAULT_DEVELOPER_URL),
        api=_env("PESAGUARD_API_URL", DEFAULT_API_URL),
        public=_env("PESAGUARD_PUBLIC_URL", DEFAULT_PUBLIC_URL),
        status=_env("PESAGUARD_STATUS_URL", DEFAULT_STATUS_URL),
    )


__all__ = ["EmailUrls", "urls_from_env"]
