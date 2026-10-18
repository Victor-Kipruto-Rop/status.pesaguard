"""
PesaGuard Analytics Engineering - Execution Module.

Provides a Python-based execution engine for the dbt SQL models,
allowing analytics transformations to be run directly against
SQLAlchemy/PostgreSQL without requiring dbt CLI installation.
"""

from __future__ import annotations

import re
import os
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from sqlalchemy import text
from sqlalchemy.orm import Session

MODEL_REGISTRY: Dict[str, str] = {
    "stg_transactions": "models/staging/stg_transactions.sql",
    "stg_discrepancies": "models/staging/stg_discrepancies.sql",
    "stg_reconciliation_matches": "models/staging/stg_reconciliation_matches.sql",
    "stg_merchant_metrics": "models/staging/stg_merchant_metrics.sql",
    "int_transactions_enriched": "models/intermediate/int_transactions_enriched.sql",
    "int_tenant_daily_summary": "models/intermediate/int_tenant_daily_summary.sql",
    "int_reconciliation_outcomes": "models/intermediate/int_reconciliation_outcomes.sql",
    "fct_reconciliation": "models/marts/fct_reconciliation.sql",
    "fct_transactions": "models/marts/fct_transactions.sql",
    "dim_tenant": "models/marts/dim_tenant.sql",
    "dim_merchant": "models/marts/dim_merchant.sql",
    "dim_provider": "models/marts/dim_provider.sql",
    "dim_date": "models/marts/dim_date.sql",
    "mart_tenant_daily_financials": "models/marts/mart_tenant_daily_financials.sql",
}


@dataclass
class ModelResult:
    model_name: str


class AnalyticsEngine:
    def __init__(self, session: Session, models_dir: str = "analytics/models"):
        self.session = session
        self.models_dir = models_dir
        self.results: List[ModelResult] = []
        self.test_results: List[TestResult] = []

    def _load_sql(self, model_name: str) -> str:
        if model_name not in MODEL_REGISTRY:
            raise ValueError(f"Unknown model: {model_name}")
        path = MODEL_REGISTRY[model_name]
        full_path = os.path.join(self.models_dir, path)
        with open(full_path, "r") as f:
            return f.read()

    def _strip_dbt_config(self, sql: str) -> str:
        sql = re.compile(r"\{\{[\s\S]*?config\([\s\S]*?\)[\s\S]*?\}\}").sub("", sql)
        sql = re.compile(r"\{\{\s*ref\(['\"]([^'\"]+)['\"]\)\s*\}\}").sub(r"\1", sql)
        sql = re.compile(r"\{\{\s*dbt_utils\.surrogate_key\([^)]*\)\s*\}\}").sub("gen_random_uuid()", sql)
        return sql.strip()

    def execute_model(self, model_name: str, tenant_id: Optional[str] = None) -> ModelResult:
        sql = self._load_sql(model_name)
        sql = self._strip_dbt_config(sql)
        if tenant_id:
            sql += f"\n-- tenant_id: {tenant_id}"
        try:
            result = self.session.execute(text(sql))
            self.session.commit()
            rows = result.rowcount if result.rowcount != -1 else 0
            model_result = ModelResult(
                model_name=model_name,
                sql=sql,
                rows_affected=rows,
                executed_at=datetime.now(timezone.utc).isoformat(),
                success=True,
            )
        except Exception as exc:
            self.session.rollback()
            model_result = ModelResult(
                model_name=model_name,
                sql=sql,
                rows_affected=0,
                executed_at=datetime.now(timezone.utc).isoformat(),
                success=False,
                error=str(exc),
            )
        self.results.append(model_result)
        return model_result
    sql: str

    def execute_all(self, tenant_id: Optional[str] = None) -> List[ModelResult]:
        order = [
            "stg_transactions", "stg_discrepancies",
            "stg_reconciliation_matches", "stg_merchant_metrics",
            "int_transactions_enriched", "int_tenant_daily_summary",
            "int_reconciliation_outcomes",
            "dim_tenant", "dim_merchant", "dim_provider", "dim_date",
            "fct_reconciliation", "fct_transactions", "mart_tenant_daily_financials",
        ]
        results = []
        for model_name in order:
            result = self.execute_model(model_name, tenant_id=tenant_id)
            results.append(result)
            if not result.success:
                raise RuntimeError(f"Model {model_name} failed: {result.error}")
        return results

    def run_test_unique(self, model_name: str, column: str) -> TestResult:
        sql = f"select count(*) as cnt, count(distinct {column}) as uniq from {model_name}"
        result = self.session.execute(text(sql)).fetchone()
        passed = result.cnt == result.uniq
        detail = f"total={result.cnt}, unique={result.uniq}"
        test = TestResult("unique", model_name, column, passed, detail)
        self.test_results.append(test)
        return test

    def run_test_not_null(self, model_name: str, column: str) -> TestResult:
        sql = f"select count(*) as null_count from {model_name} where {column} is null"
        result = self.session.execute(text(sql)).fetchone()
        passed = result.null_count == 0
        detail = f"null_count={result.null_count}"
        test = TestResult("not_null", model_name, column, passed, detail)
        self.test_results.append(test)
        return test

    def run_test_accepted_values(self, model_name: str, column: str, values: List[str]) -> TestResult:
        placeholders = ",".join([f"'{v}'" for v in values])
        sql = f"select count(*) as invalid_count from {model_name} where {column} not in ({placeholders})"
        result = self.session.execute(text(sql)).fetchone()
        passed = result.invalid_count == 0
        detail = f"invalid_count={result.invalid_count}, expected_values={values}"
        test = TestResult("accepted_values", model_name, column, passed, detail)
        self.test_results.append(test)
        return test

    def run_all_tests(self) -> List[TestResult]:
        import glob
        import yaml
        for schema_file in glob.glob(os.path.join(self.models_dir, "**", "schema.yml"), recursive=True):
            with open(schema_file, "r") as f:
                schema = yaml.safe_load(f)
            for model_def in schema.get("models", []):
                model_name = model_def["name"]
                for col_def in model_def.get("columns", []):
                    column = col_def["name"]
                    for test in col_def.get("tests", []):
                        if test == "unique":
                            self.run_test_unique(model_name, column)
                        elif test == "not_null":
                            self.run_test_not_null(model_name, column)
                        elif isinstance(test, dict) and "accepted_values" in test:
                            values = test["accepted_values"]["values"]
                            self.run_test_accepted_values(model_name, column, values)
        return self.test_results

    def get_summary(self) -> Dict[str, Any]:
        total = len(self.results)
        successful = sum(1 for r in self.results if r.success)
        failed = total - successful
        total_tests = len(self.test_results)
        passed_tests = sum(1 for t in self.test_results if t.passed)
        failed_tests = total_tests - passed_tests
        return {
            "models_executed": total,
            "models_succeeded": successful,
            "models_failed": failed,
            "tests_run": total_tests,
            "tests_passed": passed_tests,
            "tests_failed": failed_tests,
            "executed_at": datetime.now(timezone.utc).isoformat(),
        }
    rows_affected: int
    executed_at: str
    success: bool
    error: Optional[str] = None


@dataclass
class TestResult:
    test_name: str
    model_name: str
    column: str
    passed: bool
    detail: str
