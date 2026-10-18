"""Tests for the transformation formula DSL (validation, evaluation, NL help)."""

from decimal import Decimal

import pytest

from transformation_dsl import (
    CalculatedColumn,
    DeterministicFormulaAssister,
    FormulaError,
    FormulaSyntaxError,
    evaluate_calculated_column,
    evaluate_formula,
    suggest_formula,
    validate_calculated_column,
    validate_formula,
)


def test_formula_arithmetic_is_decimal_native():
    assert evaluate_formula("amount + fee", {"amount": "10.50", "fee": "0.25"}) == Decimal("10.75")
    assert evaluate_formula("amount / 3", {"amount": "10"}) == Decimal("10") / Decimal("3")
    result = evaluate_formula("amount * 1.5", {"amount": "2"})
    assert isinstance(result, Decimal) and result == Decimal("3.0")


def test_formula_division_by_zero_is_rejected():
    with pytest.raises(FormulaError, match="division by zero"):
        evaluate_formula("amount / 0", {"amount": "10"})


def test_formula_rejects_code_execution_attempts():
    for hostile in (
        "__import__('os').system('id')",
        "amount.__class__",
        "[x for x in amount]",
        "lambda: 1",
        "open('/etc/passwd')",
    ):
        with pytest.raises(FormulaSyntaxError):
            evaluate_formula(hostile, {"amount": "1"})


def test_formula_validation_reports_unknown_fields():
    check = validate_formula("amount + tax", ["amount"])
    assert not check.valid
    assert check.errors == ["unknown field: tax"]
    assert check.fields_used == ["amount", "tax"]


def test_formula_conditionals_and_functions():
    record = {"status": "COMPLETED", "amount": "12.345", "name": "  Ada  "}
    assert evaluate_formula("1 if status == 'COMPLETED' else 0", record) == 1
    assert evaluate_formula("round(amount, 2)", record) == Decimal("12.35")
    assert evaluate_formula("upper(trim(name))", record) == "ADA"
    assert evaluate_formula("coalesce(missing, 'fallback')", record) == "fallback"
    assert evaluate_formula("contains(status, 'COMPLETE')", record) is True
    assert evaluate_formula("amount > 10 and status in ['COMPLETED', 'PENDING']", record) is True


def test_formula_null_comparisons_are_false_not_errors():
    record = {"missing": None}
    assert evaluate_formula("missing > 5", record) is False
    assert evaluate_formula("missing == None", record) is True


def test_calculated_columns_validate_and_coerce_dtypes():
    columns = [
        CalculatedColumn("fee_total", "amount + fee", "amount"),
        CalculatedColumn("ratio", "amount / qty", "number"),
        CalculatedColumn("is_big", "amount >= 1000", "boolean"),
        CalculatedColumn("label", "upper(currency)", "string"),
    ]
    for column in columns:
        assert validate_calculated_column(column, ["amount", "fee", "qty", "currency"]).valid
    record = {"amount": "1200.50", "fee": "10", "qty": "4", "currency": "kes"}
    assert evaluate_calculated_column(columns[0], record) == "1210.50"
    assert evaluate_calculated_column(columns[1], record) == Decimal("300.125")
    assert evaluate_calculated_column(columns[2], record) is True
    assert evaluate_calculated_column(columns[3], record) == "KES"


def test_calculated_column_rejects_bad_name_and_dtype():
    check = validate_calculated_column(CalculatedColumn("bad name!", "amount + 1", "number"), ["amount"])
    assert not check.valid and any("invalid calculated column name" in e for e in check.errors)
    check = validate_calculated_column(CalculatedColumn("ok", "amount + 1", "json"), ["amount"])
    assert not check.valid and any("unsupported dtype" in e for e in check.errors)


def test_timestamp_dtype_uses_standardized_handling():
    column = CalculatedColumn("posted", "raw_time", "timestamp")
    record = {"raw_time": "20260923120000"}
    value = evaluate_calculated_column(column, record)
    assert value.endswith("Z") or "+" in value


def test_natural_language_suggestions_are_validated_formulas():
    fields = ["amount", "fee", "status", "posted_at"]
    suggestion = suggest_formula("sum of amount and fee", fields)
    assert suggestion.formula == "amount + fee"
    assert suggestion.confidence == 0.9
    assert validate_formula(suggestion.formula, fields).valid

    division = suggest_formula("amount divided by fee", fields)
    assert division.formula == "amount / fee"

    conditional = suggest_formula("if status is COMPLETED then amount else 0", fields)
    assert conditional.formula == "amount if status == 'COMPLETED' else 0"
    assert validate_formula(conditional.formula, fields).valid

    rounding = suggest_formula("round amount to 2 decimals", fields)
    assert rounding.formula == "round(amount, 2)"


def test_natural_language_assistance_reports_low_confidence_honestly():
    fields = ["amount"]
    unknown = suggest_formula("sum of amount and mystery", fields)
    assert unknown.formula == "" and unknown.confidence == 0.3
    unmatched = suggest_formula("turn it into a pie chart", fields)
    assert unmatched.formula == "" and unmatched.confidence == 0.0


def test_assister_protocol_is_pluggable():
    class Stub:
        def suggest(self, utterance, fields):
            from transformation_dsl import FormulaSuggestion

            return FormulaSuggestion("amount * 2", "stub", 1.0, "stub", ["amount"])

    suggestion = suggest_formula("anything", ["amount"], assister=Stub())
    assert suggestion.formula == "amount * 2" and suggestion.confidence == 1.0


def test_deterministic_assister_direct_usage():
    assister = DeterministicFormulaAssister()
    result = assister.suggest("amount times fee", ["amount", "fee"])
    assert result.formula == "amount * fee"
    assert result.matched_pattern == "multiplication"