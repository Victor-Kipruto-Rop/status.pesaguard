"""OpenTelemetry compatible tracing with W3C trace context propagation."""

from __future__ import annotations

import logging
import os
import secrets
import time
from contextlib import contextmanager
from typing import Any, Dict, Optional

logger = logging.getLogger("pesaguard.otel_tracing")

_otel_tracer = None
_otel_initialized = False


def init_opentelemetry(app: Any = None, engine: Any = None) -> bool:
    global _otel_tracer, _otel_initialized
    if _otel_initialized:
        return True
    try:
        from opentelemetry import trace
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
        from opentelemetry.instrumentation.flask import FlaskInstrumentor
        from opentelemetry.instrumentation.redis import RedisInstrumentor
        from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
        resource = Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", "pesaguard"), "service.version": _release_name(), "deployment.environment": _normalized_environment()})
        provider = TracerProvider(resource=resource)
        exporter = OTLPSpanExporter(endpoint=os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4317"), insecure=True)
        provider.add_span_processor(BatchSpanProcessor(exporter))
        trace.set_tracer_provider(provider)
        _otel_tracer = trace.get_tracer("pesaguard")
        if app is not None:
            FlaskInstrumentor().instrument_app(app)
        if engine is not None:
            SQLAlchemyInstrumentor().instrument(engine=engine)
        RedisInstrumentor().instrument()
        _otel_initialized = True
        return True
    except ImportError:
        logger.info("OpenTelemetry packages unavailable; using structured trace fallback.")
        return False
    except Exception:
        logger.exception("OpenTelemetry initialization failed; using structured trace fallback.")
        return False


def new_trace_id() -> str:
    return secrets.token_hex(16)


def new_span_id() -> str:
    return secrets.token_hex(8)


def build_traceparent(trace_id: str, span_id: str, sampled: bool = True) -> str:
    flags = "01" if sampled else "00"
    return f"00-{trace_id}-{span_id}-{flags}"


def parse_traceparent(header: str) -> Optional[Dict[str, str]]:
    if not header:
        return None
    parts = header.strip().split("-")
    if len(parts) != 4:
        return None
    version, trace_id, span_id, flags = parts
    if version != "00":
        return None
    if len(trace_id) != 32 or len(span_id) != 16:
        return None
    return {"version": version, "trace_id": trace_id, "span_id": span_id, "flags": flags, "sampled": flags == "01"}


@contextmanager
def trace_span(name: str, trace_id: Optional[str] = None, span_id: Optional[str] = None, **fields: Any):
    from logging_utils import bind_observability_context, get_observability_context
    context = get_observability_context()
    effective_trace_id = trace_id or context.get("trace_id") or new_trace_id()
    effective_span_id = span_id or new_span_id()
    if _otel_tracer is not None:
        from opentelemetry.propagate import extract
        carrier = {"traceparent": build_traceparent(effective_trace_id, effective_span_id)}
        ctx = extract(carrier) if trace_id else None
        with _otel_tracer.start_as_current_span(name, context=ctx) as span:
            for key, value in fields.items():
                span.set_attribute(key, str(value))
            bind_observability_context(trace_id=effective_trace_id)
            try:
                yield effective_trace_id
            except Exception as exc:
                span.record_exception(exc)
                from opentelemetry.trace import Status, StatusCode
                span.set_status(Status(StatusCode.ERROR, str(exc)))
                raise
        return
    bind_observability_context(trace_id=effective_trace_id)
    started = time.perf_counter()
    logger.info("trace_span_started", extra={"span_name": name, "span_id": effective_span_id, "trace_id": effective_trace_id, **fields})
    try:
        yield effective_trace_id
    except Exception as exc:
        logger.exception("trace_span_failed", extra={"span_name": name, "duration_ms": (time.perf_counter() - started) * 1000, "error_type": type(exc).__name__, "trace_id": effective_trace_id})
        raise
    else:
        logger.info("trace_span_finished", extra={"span_name": name, "duration_ms": (time.perf_counter() - started) * 1000, "trace_id": effective_trace_id})


def inject_trace_context(carrier: Dict[str, str], trace_id: Optional[str] = None, span_id: Optional[str] = None) -> Dict[str, str]:
    tid = trace_id or new_trace_id()
    sid = span_id or new_span_id()
    carrier["traceparent"] = build_traceparent(tid, sid)
    return carrier


def extract_trace_context(carrier: Dict[str, str]) -> Optional[Dict[str, str]]:
    lower_carrier = {k.lower(): v for k, v in carrier.items()}
    traceparent = lower_carrier.get("traceparent")
    if not traceparent:
        return None
    return parse_traceparent(traceparent)


def get_current_trace_context() -> Dict[str, str]:
    from logging_utils import get_observability_context
    context = get_observability_context()
    trace_id = context.get("trace_id") or new_trace_id()
    span_id = new_span_id()
    return {"trace_id": trace_id, "span_id": span_id, "traceparent": build_traceparent(trace_id, span_id)}


def _normalized_environment() -> str:
    raw = (os.getenv("SENTRY_ENVIRONMENT") or os.getenv("FLASK_ENV") or os.getenv("PESAGUARD_ENV") or "development").lower()
    lookup = {"development": "development", "dev": "development", "staging": "staging", "stage": "staging", "production": "production", "prod": "production"}
    return lookup.get(raw, "development")


def _release_name() -> str:
    return os.getenv("SENTRY_RELEASE") or os.getenv("GIT_SHA") or "pesaguard@local"


# Keep the historical module path compatible while routing all runtime callers
# through the centralized OpenTelemetry bootstrap used by the applications.
from observability import (
    init_opentelemetry as _central_init_opentelemetry,
    trace_span as _central_trace_span,
    inject_trace_context as _central_inject_trace_context,
)


def init_opentelemetry(app: Any = None, engine: Any = None) -> bool:
    return _central_init_opentelemetry(app=app, engine=engine)


def trace_span(name: str, trace_id: Optional[str] = None, span_id: Optional[str] = None, **fields: Any):
    del trace_id, span_id
    return _central_trace_span(name, **fields)


def inject_trace_context(carrier: Dict[str, str], trace_id: Optional[str] = None, span_id: Optional[str] = None) -> Dict[str, str]:
    del trace_id, span_id
    return _central_inject_trace_context(carrier)