"""PesaGuard backend package compatibility layer."""

import importlib
import os
import sys

PACKAGE_ROOT = os.path.dirname(__file__)
if PACKAGE_ROOT not in sys.path:
    sys.path.insert(0, PACKAGE_ROOT)

# Load deployment values before compatibility imports read configuration.
from . import environment as _environment
sys.modules.setdefault("environment", _environment)

# Provide stable top-level module aliases so the package and the legacy
# top-level imports resolve to the same module objects during tests and local runs.
# Some optional services require environment or database configuration to import,
# so a locally incomplete context should not break the package-level compatibility
# layer for independent modules such as the algorithm registry.
for module_name in [
    # Establish the canonical SQLAlchemy metadata before sibling imports.
    "models",
    "action_audit",
    "algorithms",
    "auth_rbac",
    "event_store",
    "export_routes",
    "health",
    "init_db",
    "logging_utils",
    "observability",
    "producer",
    "rate_limiter",
    "security_helpers",
    "tenant_organization_service",
    "tenant_org_dashboard",
    "tenant_org_routes",
    "tenant_context",
    "tenant_registry",
    "tenant_settings",
    "validators",
]:
    try:
        module = importlib.import_module(f"pesaguard_backend_pipeline.{module_name}")
    except Exception:
        continue
    sys.modules.setdefault(module_name, module)

# The webhook gateway is imported through both `app` and
# `pesaguard_backend_pipeline.app` by legacy callers. Keep one module object so
# its EventStore, Flask app, and route registry cannot diverge.
try:
    canonical_app = importlib.import_module("pesaguard_backend_pipeline.app")
    sys.modules.setdefault("app", canonical_app)
except Exception:
    pass

try:
    sys.modules.setdefault("communications", importlib.import_module("pesaguard_backend_pipeline.communications"))
    sys.modules.setdefault(
        "communications.models",
        importlib.import_module("pesaguard_backend_pipeline.communications.models"),
    )
except Exception:
    pass
