"""Tests for no-code transformation execution: mapping, normalization, calc, set ops."""

import os
from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from models import Base, LineageRecord
from transformation_engine import (
    TransformationError,
    execute,
    validate_definition,
)


def _definition(steps, inputs=("records",), output="out"):
    return {"inputs": list(inputs), "steps": steps, "output": output}


def _rows(*items):
    return [dict(item) for item in items]


def test_column_mapping_and_schema_normalization():
    definition = _definition(
        [
            {"op": "column_mapping", "input": "records", "output": "mapped",
             "mapping": {"amt": "amount", "posted": "transaction_time"}},
            {"op": "schema_normalization", "input": "mapped", "output": "out",
             "types": {"amount": "amount", "transaction_time": "timestamp"}},
        ]
    )
    assert validate_definition(
        definition, {"records": {"amt", "posted", "currency"}}
    ).valid
    run = execute(
        definition,
        {"records": _rows({"amt": "1250.5", "posted": "20260923120000", "currency": "KES"})},
        tenant_id="tenant-a",
    )
    assert run.row_count == 1 and run.output == "out"
    assert len(run.steps) == 2
    assert run.steps[0].rows_in == 1 and run.steps[0].rows_out == 1


def test_normalization_failures_and_null_mode():
    rows = _rows({"amount": "not-a-number", "transaction_time": "2026-09-23T10:00:00Z"})
    strict = _definition(
        [{"op": "schema_normalization", "input": "records", "output": "out",
          "types": {"amount": "amount"}}]
    )
    with pytest.raises(TransformationError, match="failed"):
        execute(strict, {"records": rows}, tenant_id="tenant-a")
    lenient = _definition(
        [{"op": "schema_normalization", "input": "records", "output": "out",
          "types": {"amount": "amount"}, "on_error": "null"}]
    )
    assert validate_definition(lenient, {"records": {"amount"}}).valid


def test_filter_and_calculated_columns():
    definition = _definition(
        [
            {"op": "filter", "input": "records", "output": "kept",
             "formula": "amount > 10 and status != 'FAILED'"},
            {"op": "calculated_columns", "input": "kept", "output": "out",
             "columns": [
                 {"name": "fee_net", "formula": "amount - fee", "dtype": "number"},
                 {"name": "big", "formula": "amount >= 100", "dtype": "boolean"},
             ]},
        ]
    )
    rows = _rows(
        {"amount": "150.00", "fee": "5.00", "status": "COMPLETED"},
        {"amount": "9.00", "fee": "1.00", "status": "COMPLETED"},
        {"amount": "200.00", "fee": "2.00", "status": "FAILED"},
    )
    run = execute(definition, {"records": rows}, tenant_id="tenant-a")
    assert run.row_count == 1


def test_union_with_dedupe_and_join_strategies():
    inputs = {
        "left": _rows(
            {"account_id": "a1", "amount": "10.00"},
            {"account_id": "a2", "amount": "20.00"},
        ),
        "right": _rows({"account_id": "a2", "amount": "20.00"}),
        "fx": _rows(
            {"account_id": "a1", "rate": "1.0"},
            {"account_id": "a9", "rate": "0.5"},
        ),
    }
    union_run = execute(
        _definition(
            [{"op": "union", "inputs": ["left", "right"], "output": "out",
              "dedupe_keys": ["account_id", "amount"]}],
            inputs=("left", "right"),
        ),
        inputs,
        tenant_id="tenant-a",
    )
    assert union_run.row_count == 2

    join_run = execute(
        _definition(
            [{"op": "join", "left": "left", "right": "fx", "output": "out",
              "on": {"account_id": "account_id"}, "how": "left", "prefix": "fx_"}],
            inputs=("left", "fx"),
        ),
        inputs,
        tenant_id="tenant-a",
    )
    assert join_run.row_count == 2

    inner_run = execute(
        _definition(
            [{"op": "join", "left": "left", "right": "fx", "output": "out",
              "on": {"account_id": "account_id"}, "how": "inner"}],
            inputs=("left", "fx"),
        ),
        inputs,
        tenant_id="tenant-a",
    )
    assert inner_run.row_count == 1


def test_consolidate_and_enrich_group_and_attach():
    rows = _rows(
        {"currency": "KES", "amount": "100.00", "merchant_id": "m1"},
        {"currency": "KES", "amount": "50.00", "merchant_id": "m2"},
        {"currency": "USD", "amount": "10.00", "merchant_id": "m1"},
    )
    run = execute(
        _definition(
            [
                {"op": "consolidate", "input": "records", "output": "totals",
                 "group_by": ["currency"],
                 "aggregations": {
                     "total_amount": {"op": "sum", "field": "amount"},
                     "tx_count": {"op": "count"},
                     "avg_amount": {"op": "avg", "field": "amount"},
                 }},
                {"op": "enrich", "input": "totals", "output": "out",
                 "reference": "labels", "on": {"currency": "currency"},
                 "select": ["label"], "prefix": "cur_"},
            ],
            inputs=("records", "labels"),
        ),
        {"records": rows, "labels": _rows({"currency": "KES", "label": "shilling"})},
        tenant_id="tenant-a",
    )
    assert run.row_count == 2


def test_apply_component_expands_reusable_steps():
    components = {
        "clean": [
            {"op": "column_mapping", "input": "{input}", "output": "m1", "mapping": {"a": "b"}},
            {"op": "filter", "input": "m1", "output": "m2", "formula": "b > 0"},
        ]
    }
    definition = _definition(
        [{"op": "apply_component", "component": "clean", "input": "records", "output": "out"}]
    )
    samples = {"records": {"a"}}
    check = validate_definition(definition, samples, components)
    assert check.valid, [issue.message for issue in check.issues]
    run = execute(
        definition,
        {"records": _rows({"a": "5"}, {"a": "-1"})},
        tenant_id="tenant-a",
        components=components,
    )
    assert run.row_count == 1


def test_reused_component_calls_do_not_collide():
    components = {
        "clean": [
            {"op": "column_mapping", "input": "{input}", "output": "m1", "mapping": {"a": "b"}},
            {"op": "filter", "input": "m1", "output": "m2", "formula": "b > 0"},
            {"op": "calculated_columns", "input": "m2", "output": "m3",
             "columns": [{"name": "b2", "formula": "b * 2", "dtype": "number"}]},
        ]
    }
    definition = _definition(
        [
            {"op": "apply_component", "component": "clean", "input": "left", "output": "cleaned_left"},
            {"op": "apply_component", "component": "clean", "input": "right", "output": "cleaned_right"},
            {"op": "union", "inputs": ["cleaned_left", "cleaned_right"], "output": "out"},
        ],
        inputs=("left", "right"),
    )
    check = validate_definition(definition, {"left": {"a"}, "right": {"a"}}, components)
    assert check.valid, [issue.message for issue in check.issues]
    run = execute(
        definition,
        {"left": _rows({"a": "4"}), "right": _rows({"a": "6"}, {"a": "-9"})},
        tenant_id="tenant-a",
        components=components,
    )
    assert run.row_count == 2


def test_missing_bound_input_and_unknown_component_are_rejected():
    definition = _definition(
        [{"op": "filter", "input": "records", "output": "out", "formula": "amount > 0"}]
    )
    with pytest.raises(TransformationError, match="missing bound rows"):
        execute(definition, {}, tenant_id="tenant-a")

    component_definition = _definition(
        [{"op": "apply_component", "component": "ghost", "input": "records", "output": "out"}]
    )
    with pytest.raises(TransformationError, match="unknown component"):
        execute(
            component_definition,
            {"records": _rows({"amount": "1"})},
            tenant_id="tenant-a",
            components={},
        )


def test_validation_rejects_structural_problems():
    samples = {"records": {"amount", "status"}}
    bad = _definition(
        [
            {"op": "column_mapping", "input": "records", "output": "mapped", "mapping": {}},
            {"op": "filter", "input": "mapped", "output": "kept", "formula": "bogus > 1"},
            {"op": "join", "left": "kept", "right": "kept", "output": "joined", "on": {}},
            {"op": "nope", "input": "kept", "output": "x"},
        ]
    )
    check = validate_definition(bad, samples)
    assert not check.valid
    assert {issue.step for issue in check.issues} == {0, 1, 2, 3}
    with pytest.raises(TransformationError, match="definition is invalid"):
        execute(bad, {"records": _rows({"amount": "1", "status": "ok"})}, tenant_id="tenant-a")


def test_output_must_be_a_known_dataset():
    check = validate_definition(
        {"inputs": ["records"], "steps": [], "output": "phantom"}, {"records": {"a"}}
    )
    assert not check.valid
    assert any("unknown" in issue.message for issue in check.issues)


def test_execution_records_durable_step_lineage():
    url = os.environ["DATABASE_URL"]
    engine = create_engine(
        url, connect_args={"check_same_thread": False} if url.startswith("sqlite") else {}
    )
    Base.metadata.create_all(engine)
    try:
        from sqlalchemy.orm import sessionmaker

        factory = sessionmaker(bind=engine, expire_on_commit=False)
        tenant = "tenant-lineage"
        with factory() as session:
            definition = _definition(
                [
                    {"op": "filter", "input": "records", "output": "kept",
                     "formula": "amount > 0"},
                ],
                output="kept",
            )
            run = execute(
                definition,
                {"records": _rows({"amount": "5", "status": "ok"})},
                tenant_id=tenant,
                session=session,
                resource_version=3,
                job_id="job-1",
            )
            session.commit()
            rows = (
                session.query(LineageRecord)
                .filter(
                    LineageRecord.tenant_id == tenant,
                    LineageRecord.correlation_id == run.execution_id,
                )
                .all()
            )
            assert len(rows) == 1
            assert rows[0].stage == "TRANSFORMED"
            assert rows[0].transformation_version == "3"
            assert rows[0].job_id == "job-1"
    finally:
        engine.dispose()