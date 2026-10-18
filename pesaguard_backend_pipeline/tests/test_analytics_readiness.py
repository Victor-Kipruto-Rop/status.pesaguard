"""
Analytics engineering readiness tests (item 10).
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

import pytest


def test_dbt_project_exists():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "dbt_project.yml",
    )
    assert os.path.isfile(path), "dbt_project.yml must exist"
    with open(path) as f:
        content = f.read()
    assert "name:" in content
    assert "version:" in content
    assert "model-paths:" in content


def test_profiles_example_exists():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "profiles.yml.example",
    )
    assert os.path.isfile(path), "profiles.yml.example must exist"


def test_metrics_yml_exists():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "metrics.yml",
    )
    assert os.path.isfile(path), "metrics.yml must exist"
    with open(path) as f:
        content = f.read()
    assert "metrics:" in content
    assert "transaction_volume" in content
    assert "reconciliation_rate" in content
    assert "discrepancy_count" in content


def test_staging_models_exist():
    base = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models", "staging",
    )
    for fname in [
        "stg_transactions.sql", "stg_discrepancies.sql",
        "stg_reconciliation_matches.sql", "stg_merchant_metrics.sql",
        "schema.yml",
    ]:
        assert os.path.isfile(os.path.join(base, fname)), f"Staging model {fname} must exist"


def test_intermediate_models_exist():
    base = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models", "intermediate",
    )
    for fname in [
        "int_transactions_enriched.sql",
        "int_tenant_daily_summary.sql",
        "int_reconciliation_outcomes.sql", "schema.yml",
    ]:
        assert os.path.isfile(os.path.join(base, fname)), f"Intermediate model {fname} must exist"


def test_marts_models_exist():
    base = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models", "marts",
    )
    for fname in [
        "fct_reconciliation.sql", "fct_transactions.sql",
        "dim_tenant.sql", "dim_merchant.sql",
        "dim_provider.sql", "dim_date.sql",
        "mart_tenant_daily_financials.sql", "schema.yml",
    ]:
        assert os.path.isfile(os.path.join(base, fname)), f"Mart model {fname} must exist"


def test_reconciliation_fact_table():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models", "marts", "fct_reconciliation.sql",
    )
    with open(path) as f:
        content = f.read()
    assert "match_score" in content
    assert "tenant_id" in content


def test_tenant_dimension():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models", "marts", "dim_tenant.sql",
    )
    with open(path) as f:
        content = f.read()
    assert "tenant_key" in content
    assert "tenant_id" in content


def test_merchant_dimension():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models", "marts", "dim_merchant.sql",
    )
    with open(path) as f:
        content = f.read()
    assert "merchant_key" in content
    assert "merchant_id" in content


def test_provider_dimension():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models", "marts", "dim_provider.sql",
    )
    with open(path) as f:
        content = f.read()
    assert "provider_key" in content
    assert "provider" in content


def test_data_tests_exist():
    for subdir in ["staging", "intermediate", "marts"]:
        path = os.path.join(
            os.path.dirname(os.path.dirname(__file__)),
            "analytics", "models", subdir, "schema.yml",
        )
        assert os.path.isfile(path), f"Schema tests for {subdir} must exist"
        with open(path) as f:
            content = f.read()
        assert "unique" in content or "not_null" in content or "accepted_values" in content


def test_model_documentation_exists():
    docs_dir = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "docs",
    )
    for fname in ["MODELS.md", "LINEAGE.md", "METRICS.md"]:
        assert os.path.isfile(os.path.join(docs_dir, fname)), f"Documentation {fname} must exist"


def test_data_lineage_doc_exists():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "docs", "LINEAGE.md",
    )
    assert os.path.isfile(path), "LINEAGE.md must exist"
    with open(path) as f:
        content = f.read()
    assert "lineage" in content.lower()


def test_version_controlled_models():
    sql_files = []
    base = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models",
    )
    for root, dirs, files in os.walk(base):
        for f in files:
            if f.endswith(".sql"):
                sql_files.append(os.path.join(root, f))
    assert len(sql_files) >= 10, "At least 10 SQL model files must exist"


def test_analytics_engine_exists():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "analytics_engine.py",
    )
    assert os.path.isfile(path), "analytics_engine.py must exist"
    from analytics.analytics_engine import AnalyticsEngine, ModelResult, TestResult
    assert AnalyticsEngine is not None
    assert ModelResult is not None
    assert TestResult is not None


def test_tenant_isolation_macro_exists():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models", "macros", "tenant_isolation.sql",
    )
    assert os.path.isfile(path), "tenant_isolation.sql macro must exist"
    with open(path) as f:
        content = f.read()
    assert "tenant" in content.lower()
    assert "macro" in content.lower()


def test_date_dimension():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models", "marts", "dim_date.sql",
    )
    with open(path) as f:
        content = f.read()
    assert "date_key" in content
    assert "date_value" in content


def test_daily_financials_mart():
    path = os.path.join(
        os.path.dirname(os.path.dirname(__file__)),
        "analytics", "models", "marts", "mart_tenant_daily_financials.sql",
    )
    with open(path) as f:
        content = f.read()
    assert "financial_fact_key" in content
    assert "total_volume" in content
    assert "reconciliation_rate" in content
