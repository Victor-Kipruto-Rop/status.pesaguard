"""Validated request, correlation, and trace context handling."""

from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from typing import Mapping

from observability import build_traceparent, new_span_id, new_trace_id, parse_traceparent

_CONTEXT_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
_TRACE_ID = re.compile(r"^[0-9a-f]{32}$")


@dataclass(frozen=True)
class RequestContext:
    request_id: str
    correlation_id: str
    trace_id: str
    span_id: str
    traceparent: str
    tenant_id: str = ""

    def as_dict(self) -> dict[str, str]:
        return {
            "request_id": self.request_id,
            "correlation_id": self.correlation_id,
            "trace_id": self.trace_id,
            "span_id": self.span_id,
            "traceparent": self.traceparent,
            "tenant_id": self.tenant_id,
        }


class RequestContextService:
    """Builds a bounded context from untrusted headers and trusted tenant data."""

    @staticmethod
    def create(
        headers: Mapping[str, str],
        *,
        trusted_tenant_id: str | None = None,
    ) -> RequestContext:
        request_id = RequestContextService._valid_context_id(headers.get("X-Request-ID")) or str(uuid.uuid4())
        correlation_id = RequestContextService._valid_context_id(headers.get("X-Correlation-ID")) or request_id

        parsed = parse_traceparent(headers.get("traceparent", ""))
        trace_header = headers.get("X-Trace-ID", "")
        if parsed is not None:
            trace_id = parsed["trace_id"]
        elif isinstance(trace_header, str) and _TRACE_ID.fullmatch(trace_header) and trace_header != "0" * 32:
            trace_id = trace_header
        else:
            trace_id = new_trace_id()
        span_id = new_span_id()
        tenant_id = trusted_tenant_id.strip() if isinstance(trusted_tenant_id, str) else ""
        return RequestContext(
            request_id=request_id,
            correlation_id=correlation_id,
            trace_id=trace_id,
            span_id=span_id,
            traceparent=build_traceparent(trace_id, span_id),
            tenant_id=tenant_id,
        )

    @staticmethod
    def _valid_context_id(value: str | None) -> str:
        if not isinstance(value, str):
            return ""
        return value if _CONTEXT_ID.fullmatch(value) else ""
