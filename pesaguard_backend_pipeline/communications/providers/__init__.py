"""Provider adapters for the communications platform."""

from .email import (
    EmailProviderAnalytics,
    EmailProviderConfig,
    EmailProviderFactory,
    EmailProviderHealth,
    EmailProviderRegistry,
    EmailRouter,
    MailgunEmailProvider,
    MockEmailProvider,
    ResendEmailProvider,
    SendGridEmailProvider,
    SesEmailProvider,
)

__all__ = [
    "EmailProviderAnalytics",
    "EmailProviderConfig",
    "EmailProviderFactory",
    "EmailProviderHealth",
    "EmailProviderRegistry",
    "EmailRouter",
    "MailgunEmailProvider",
    "MockEmailProvider",
    "ResendEmailProvider",
    "SendGridEmailProvider",
    "SesEmailProvider",
]
