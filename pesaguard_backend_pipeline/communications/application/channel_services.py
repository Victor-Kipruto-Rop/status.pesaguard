from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sqlalchemy.orm import Session

from ..core.enums import CommunicationChannel
from ..core.interfaces import CommunicationProvider, NotificationRequest
from ..domain import approve_template, create_template, is_allowed_now, render_template
from ..models import CommunicationPreference, CommunicationTemplate
from .notification_service import NotificationService


class _ChannelNotificationService:
    channel: CommunicationChannel

    def __init__(self, session: Session, provider: CommunicationProvider):
        if self.channel not in provider.supported_channels:
            raise ValueError(f"provider does not support {self.channel.value}")
        self.notifications = NotificationService(
            session,
            provider,
            protect_recipient=self.channel == CommunicationChannel.PUSH,
        )

    def enqueue(self, request: NotificationRequest, **kwargs: Any):
        if request.channel != self.channel:
            raise ValueError(f"{self.__class__.__name__} only supports {self.channel.value}")
        return self.notifications.enqueue(request, **kwargs)

    def send(self, request: NotificationRequest, **kwargs: Any):
        if request.channel != self.channel:
            raise ValueError(f"{self.__class__.__name__} only supports {self.channel.value}")
        return self.notifications.send(request, **kwargs)


class SmsService(_ChannelNotificationService):
    channel = CommunicationChannel.SMS


class PushNotificationService(_ChannelNotificationService):
    channel = CommunicationChannel.PUSH


class NotificationPreferenceService:
    """Validated, tenant-scoped notification preference management."""

    _CHANNELS = frozenset(channel.value for channel in CommunicationChannel)

    def __init__(self, session: Session):
        self.session = session

    def get(self, *, tenant_id: str, recipient: str) -> CommunicationPreference | None:
        self._validate_scope(tenant_id, recipient)
        return self.session.query(CommunicationPreference).filter_by(
            tenant_id=tenant_id,
            recipient=recipient,
        ).one_or_none()

    def update(
        self,
        *,
        tenant_id: str,
        recipient: str,
        channels: list[str] | None = None,
        quiet_hours_start: str | None = None,
        quiet_hours_end: str | None = None,
        timezone_name: str | None = None,
        marketing_opt_in: bool | None = None,
    ) -> CommunicationPreference:
        self._validate_scope(tenant_id, recipient)
        if channels is not None:
            if not isinstance(channels, list) or any(
                not isinstance(channel, str) or channel not in self._CHANNELS for channel in channels
            ):
                raise ValueError("channels must contain supported communication channel names")
            if len(channels) != len(set(channels)):
                raise ValueError("channels must not contain duplicates")
        clear_quiet_hours = quiet_hours_start == "" and quiet_hours_end == ""
        if quiet_hours_start is not None and not clear_quiet_hours:
            self._validate_time(quiet_hours_start, "quiet_hours_start")
        if quiet_hours_end is not None and not clear_quiet_hours:
            self._validate_time(quiet_hours_end, "quiet_hours_end")
        if (quiet_hours_start is None) != (quiet_hours_end is None) and not clear_quiet_hours:
            current = self.get(tenant_id=tenant_id, recipient=recipient)
            effective_start = quiet_hours_start if quiet_hours_start is not None else (current.quiet_hours_start if current else None)
            effective_end = quiet_hours_end if quiet_hours_end is not None else (current.quiet_hours_end if current else None)
            if (effective_start is None) != (effective_end is None):
                raise ValueError("quiet hours start and end must be configured together")
        if marketing_opt_in is not None and not isinstance(marketing_opt_in, bool):
            raise ValueError("marketing_opt_in must be a boolean")
        if timezone_name is not None:
            try:
                ZoneInfo(timezone_name)
            except (ZoneInfoNotFoundError, ValueError) as exc:
                raise ValueError("timezone must be a valid IANA timezone") from exc

        preference = self.get(tenant_id=tenant_id, recipient=recipient)
        if preference is None:
            preference = CommunicationPreference(
                id=f"preference_{uuid.uuid4().hex}",
                tenant_id=tenant_id,
                recipient=recipient,
            )
            self.session.add(preference)
        if channels is not None:
            preference.channels = channels
        if clear_quiet_hours:
            preference.quiet_hours_start = None
            preference.quiet_hours_end = None
        elif quiet_hours_start is not None:
            preference.quiet_hours_start = quiet_hours_start
        if not clear_quiet_hours and quiet_hours_end is not None:
            preference.quiet_hours_end = quiet_hours_end
        if timezone_name is not None:
            preference.timezone = timezone_name
        if marketing_opt_in is not None:
            preference.marketing_opt_in = int(marketing_opt_in)
        preference.updated_at = datetime.now(timezone.utc)
        self.session.flush()
        return preference

    def is_allowed(self, *, tenant_id: str, recipient: str, channel: CommunicationChannel, now: datetime | None = None) -> bool:
        preference = self.get(tenant_id=tenant_id, recipient=recipient)
        if preference is None:
            return True
        selected_channels = preference.channels or []
        if not isinstance(selected_channels, list):
            return False
        if selected_channels and channel.value not in selected_channels:
            return False
        try:
            return is_allowed_now(preference, now=now)
        except (ValueError, ZoneInfoNotFoundError):
            return False

    @staticmethod
    def _validate_scope(tenant_id: str, recipient: str) -> None:
        if not isinstance(tenant_id, str) or not tenant_id.strip():
            raise ValueError("tenant_id is required")
        if not isinstance(recipient, str) or not recipient.strip():
            raise ValueError("recipient is required")

    @staticmethod
    def _validate_time(value: str, field: str) -> None:
        if not isinstance(value, str):
            raise ValueError(f"{field} must use HH:MM format")
        try:
            datetime.strptime(value, "%H:%M")
        except ValueError as exc:
            raise ValueError(f"{field} must use HH:MM format") from exc
        if len(value) != 5:
            raise ValueError(f"{field} must use HH:MM format")


class NotificationTemplateService:
    """Tenant-scoped template lifecycle and approved-template rendering."""

    def __init__(self, session: Session):
        self.session = session

    def create(
        self,
        *,
        tenant_id: str,
        slug: str,
        channel: CommunicationChannel,
        body: str,
        variables: list[str],
    ) -> CommunicationTemplate:
        if not tenant_id or not tenant_id.strip():
            raise ValueError("tenant_id is required")
        if not slug or not slug.strip():
            raise ValueError("template slug is required")
        if not isinstance(body, str) or not body.strip():
            raise ValueError("template body is required")
        if not isinstance(channel, CommunicationChannel):
            raise ValueError("unsupported communication channel")
        if not isinstance(variables, list) or any(not isinstance(name, str) or not name for name in variables):
            raise ValueError("template variables must be non-empty names")
        if len(variables) != len(set(variables)):
            raise ValueError("template variables must not contain duplicates")
        return create_template(
            self.session,
            tenant_id=tenant_id.strip(),
            slug=slug.strip(),
            channel=channel,
            body=body,
            variables=variables,
        )

    def approve(self, *, tenant_id: str, template_id: str, approver: str) -> CommunicationTemplate:
        if not tenant_id or not approver:
            raise ValueError("tenant_id and approver are required")
        template = self.session.query(CommunicationTemplate).filter_by(
            tenant_id=tenant_id,
            id=template_id,
        ).one_or_none()
        if template is None:
            raise ValueError("template not found")
        return approve_template(self.session, template, approver)

    def render(self, *, tenant_id: str, template_id: str, channel: CommunicationChannel, values: dict[str, Any]) -> str:
        template = self.session.query(CommunicationTemplate).filter_by(
            tenant_id=tenant_id,
            id=template_id,
        ).one_or_none()
        if template is None:
            raise ValueError("template not found")
        if template.status != "approved":
            raise ValueError("template is not approved")
        if template.channel != channel.value:
            raise ValueError("template channel does not match notification channel")
        return render_template(template, values)
