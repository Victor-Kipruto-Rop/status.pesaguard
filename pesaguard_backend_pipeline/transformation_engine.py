"""Deterministic no-code transformation execution for dataset resources.

Dataset definitions are JSON documents a visual builder can produce and edit.
Each ``steps`` entry is one validated, side-effect-free operation over named
in-memory datasets:

- ``column_mapping`` maps source columns to canonical names
- ``schema_normalization`` coerces amounts, timestamps, numbers, currencies
- ``filter`` keeps rows whose formula evaluates truthy
- ``calculated_columns`` appends DSL-calculated columns
- ``union`` concatenates sources (optional key-based dedupe)
- ``join`` merges two datasets on key pairs (inner/left)
- ``consolidate`` groups and aggregates multiple-source rows
- ``enrich`` left-attaches reference-data columns by key
- ``apply_component`` expands a reusable component's steps inline

Execution never reads files, networks, or tenant data on its own: callers
bind real input datasets at execution time. Lineage is emitted through the
existing durable lineage boundary when a session is supplied.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Callable, Dict, Iterable, List, Mapping, Optional, Sequence

from normalization import (
    NormalizationError,
    normalize_amount,
    normalize_currency,
    normalize_timestamp,
)
from transformation_dsl import (
    CalculatedColumn,
    FormulaError,
    evaluate_calculated_column,
    evaluate_formula,
    validate_calculated_column,
    validate_formula,
)

SUPPORTED_OPS = frozenset(
    {
        "column_mapping",
        "schema_normalization",
        "filter",
        "calculated_columns",
        "union",
        "join",
        "consolidate",
        "enrich",
        "apply_component",
    }
)

SCHEMA_COLUMN_TYPES = frozenset({"amount", "timestamp", "number", "string", "currency"})
JOIN_STRATEGIES = frozenset({"inner", "left"})
AGGREGATION_OPS = frozenset({"sum", "count", "count_distinct", "min", "max", "avg"})
MAX_INPUT_ROWS = 250000
MAX_OUTPUT_ROWS = 250000


class TransformationError(ValueError):
    """Raised when a definition or execution cannot proceed safely."""


@dataclass(frozen=True)
class DefinitionIssue:
    step: int
    message: str


@dataclass(frozen=True)
class DefinitionCheck:
    valid: bool
    issues: List[DefinitionIssue] = field(default_factory=list)


@dataclass(frozen=True)
class StepReport:
    index: int
    op: str
    output: str
    rows_in: int
    rows_out: int
    duration_ms: float


@dataclass(frozen=True)
class TransformationRun:
    execution_id: str
    output: str
    row_count: int
    steps: List[StepReport] = field(default_factory=list)


def _require_mapping(value: Any, context: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise TransformationError(f"{context} must be an object")
    return value


def _require_list(value: Any, context: str) -> list:
    if not isinstance(value, (list, tuple)):
        raise TransformationError(f"{context} must be a list")
    return list(value)


def _resolve_working_name(name: Any, context: str) -> str:
    cleaned = str(name or "").strip()
    if not cleaned:
        raise TransformationError(f"{context} requires a dataset name")
    return cleaned


def validate_definition(
    definition: Mapping[str, Any],
    sample_columns: Optional[Mapping[str, Iterable[str]]] = None,
    components: Optional[Mapping[str, Sequence[Mapping[str, Any]]]] = None,
) -> DefinitionCheck:
    """Validate structure, formula, and column references; never raises."""
    issues: List[DefinitionIssue] = []
    try:
        inputs = [
            _resolve_working_name(item, "inputs")
            for item in _require_list(dict(definition).get("inputs", []), "inputs")
        ]
        raw_steps = _require_list(dict(definition).get("steps", []), "steps")
        output = _resolve_working_name(dict(definition).get("output", ""), "output")
    except TransformationError as exc:
        return DefinitionCheck(valid=False, issues=[DefinitionIssue(step=-1, message=str(exc))])
    samples: Dict[str, set] = {
        name: {str(column) for column in columns}
        for name, columns in (sample_columns or {}).items()
    }
    available: Dict[str, Optional[set]] = {name: samples.get(name) for name in inputs}
    for index, raw_step in enumerate(raw_steps):
        try:
            kind = raw_step.get("op") if isinstance(raw_step, Mapping) else None
            if kind not in SUPPORTED_OPS:
                raise TransformationError(f"unsupported step op: {kind!r}")
            _validate_step(index, raw_step, available, dict(components or {}))
        except TransformationError as exc:
            issues.append(DefinitionIssue(step=index, message=str(exc)))
    if not issues and output not in available:
        issues.append(DefinitionIssue(step=-1, message=f"output dataset is unknown: {output}"))
    return DefinitionCheck(valid=not issues, issues=issues)


def _require_known(
    available: Dict[str, Optional[set]], name: str, index: Any, role: str
) -> Optional[set]:
    if name not in available:
        raise TransformationError(
            f"step {index} references unknown {role} dataset: {name}"
        )
    return available[name]


def _remapped(
    source: Optional[set], mapping: Mapping[str, Any]
) -> Optional[set]:
    """Track columns after a rename step; unknown sources stay unrestricted."""
    known = {str(target).strip() for target in mapping.values() if str(target).strip()}
    if source is None:
        return known
    unmapped = {column for column in source if column not in mapping}
    return unmapped | known


def _validate_step(
    index: Any,
    step: Mapping[str, Any],
    available: Dict[str, Optional[set]],
    components: Mapping[str, Sequence[Mapping[str, Any]]],
) -> None:
    kind = step.get("op")
    if not isinstance(step, Mapping):
        raise TransformationError(f"step {index} must be an object")
    if "output" not in dict(step):
        raise TransformationError(f"step {index} requires an output dataset name")
    output_name = _resolve_working_name(step.get("output"), "output")
    if kind == "column_mapping":
        source_name = _resolve_working_name(step.get("input"), "input")
        source = _require_known(available, source_name, index, "input")
        mapping = _require_mapping(step.get("mapping", {}), "mapping")
        if not mapping:
            raise TransformationError(f"step {index} mapping must not be empty")
        for source_column, target in mapping.items():
            if not str(target).strip():
                raise TransformationError(
                    f"step {index} maps {source_column!r} to an empty column name"
                )
        available[output_name] = _remapped(source, mapping)
    elif kind == "schema_normalization":
        source_name = _resolve_working_name(step.get("input"), "input")
        source = _require_known(available, source_name, index, "input")
        types = _require_mapping(step.get("types", {}), "types")
        if not types:
            raise TransformationError(f"step {index} types must not be empty")
        for column, dtype in types.items():
            if dtype not in SCHEMA_COLUMN_TYPES:
                raise TransformationError(
                    f"step {index} has unsupported type {dtype!r} for {column!r}"
                )
            if source is not None and str(column) not in source:
                raise TransformationError(f"step {index} type column is unknown: {column}")
        if step.get("on_error", "fail") not in {"fail", "null"}:
            raise TransformationError(f"step {index} on_error must be 'fail' or 'null'")
        available[output_name] = set(types) if source is None else set(source) | set(types)
    elif kind == "filter":
        source_name = _resolve_working_name(step.get("input"), "input")
        source = _require_known(available, source_name, index, "input")
        check = validate_formula(str(step.get("formula", "")), source)
        if not check.valid:
            raise TransformationError(
                f"step {index} filter formula is invalid: {'; '.join(check.errors)}"
            )
        available[output_name] = source
    elif kind == "calculated_columns":
        source_name = _resolve_working_name(step.get("input"), "input")
        source = _require_known(available, source_name, index, "input")
        raw_columns = _require_list(step.get("columns", []), "columns")
        if not raw_columns:
            raise TransformationError(f"step {index} requires at least one column")
        new_names: List[str] = []
        for raw_column in raw_columns:
            if not isinstance(raw_column, Mapping):
                raise TransformationError(f"step {index} column entries must be objects")
            column = CalculatedColumn(
                name=str(raw_column.get("name", "")),
                formula=str(raw_column.get("formula", "")),
                dtype=str(raw_column.get("dtype", "number")),
            )
            check = validate_calculated_column(column, source)
            if not check.valid:
                raise TransformationError(
                    f"step {index} calculated column {column.name!r} is invalid: "
                    f"{'; '.join(check.errors)}"
                )
            if column.name in new_names:
                raise TransformationError(
                    f"step {index} defines {column.name!r} more than once"
                )
            new_names.append(column.name)
        target = set(new_names) if source is None else set(source) | set(new_names)
        available[output_name] = target
    elif kind == "union":
        bound = [
            _require_known(available, _resolve_working_name(item, "input"), index, "input")
            for item in _require_list(step.get("inputs", []), "inputs")
        ]
        if not bound:
            raise TransformationError(f"step {index} union requires at least one input")
        dedupe = [str(key) for key in _require_list(step.get("dedupe_keys", []), "dedupe_keys")]
        known: Optional[set] = None
        for columns in bound:
            if columns is None:
                continue
            known = set() if known is None else known
            known |= set(columns)
            for key in dedupe:
                if key not in columns:
                    raise TransformationError(f"step {index} dedupe key is unknown: {key}")
        available[output_name] = known
    elif kind == "join":
        left_name = _resolve_working_name(step.get("left"), "left")
        right_name = _resolve_working_name(step.get("right"), "right")
        if left_name == right_name:
            raise TransformationError(f"step {index} cannot join a dataset with itself")
        left_columns = _require_known(available, left_name, index, "left")
        right_columns = _require_known(available, right_name, index, "right")
        how = step.get("how", "inner")
        if how not in JOIN_STRATEGIES:
            raise TransformationError(
                f"step {index} join how must be one of {sorted(JOIN_STRATEGIES)}"
            )
        key_pairs = _require_mapping(step.get("on", {}), "on")
        if not key_pairs:
            raise TransformationError(f"step {index} join requires at least one key pair")
        for left_key, right_key in key_pairs.items():
            if left_columns is not None and left_key not in left_columns:
                raise TransformationError(f"step {index} left join key is unknown: {left_key}")
            if right_columns is not None and right_key not in right_columns:
                raise TransformationError(
                    f"step {index} right join key is unknown: {right_key}"
                )
        prefix = str(step.get("prefix", ""))
        joined: Optional[set] = (
            None if left_columns is None and right_columns is None else set(left_columns or set())
        )
        if right_columns is not None:
            if joined is None:
                joined = set()
            for right_column in right_columns:
                if right_column in key_pairs.values():
                    continue
                candidate = f"{prefix}{right_column}"
                if left_columns is not None and candidate in (left_columns | set(key_pairs)):
                    raise TransformationError(
                        f"step {index} join output collision: {candidate!r} (set prefix)"
                    )
                joined.add(candidate)
        available[output_name] = joined
    elif kind == "consolidate":
        source_name = _resolve_working_name(step.get("input"), "input")
        source = _require_known(available, source_name, index, "input")
        group_by = [str(item) for item in _require_list(step.get("group_by", []), "group_by")]
        aggregations = _require_mapping(step.get("aggregations", {}), "aggregations")
        if not aggregations:
            raise TransformationError(
                f"step {index} consolidate requires at least one aggregation"
            )
        for target_name, spec in aggregations.items():
            if not isinstance(spec, Mapping):
                raise TransformationError(
                    f"step {index} aggregation {target_name!r} must be an object"
                )
            if spec.get("op") not in AGGREGATION_OPS:
                raise TransformationError(
                    f"step {index} aggregation {target_name!r} op must be one of "
                    f"{sorted(AGGREGATION_OPS)}"
                )
            if spec.get("op") in {"count", "count_distinct"}:
                field_name = str(spec.get("field", "")).strip()
                if field_name and source is not None and field_name not in source:
                    raise TransformationError(
                        f"step {index} aggregation field is unknown: {field_name}"
                    )
            else:
                field_name = str(spec.get("field", "")).strip()
                if not field_name:
                    raise TransformationError(
                        f"step {index} aggregation {target_name!r} requires a field"
                    )
                if source is not None and field_name not in source:
                    raise TransformationError(
                        f"step {index} aggregation field is unknown: {field_name}"
                    )
        if source is not None:
            for key in group_by:
                if key not in source:
                    raise TransformationError(f"step {index} group key is unknown: {key}")
        available[output_name] = set(group_by) | set(aggregations)
    elif kind == "enrich":
        source_name = _resolve_working_name(step.get("input"), "input")
        source = _require_known(available, source_name, index, "input")
        reference_name = _resolve_working_name(step.get("reference"), "reference")
        reference = _require_known(available, reference_name, index, "reference")
        key_pairs = _require_mapping(step.get("on", {}), "on")
        if not key_pairs:
            raise TransformationError(f"step {index} enrich requires at least one key pair")
        select = [str(item) for item in _require_list(step.get("select", []), "select")]
        if not select:
            raise TransformationError(
                f"step {index} enrich requires at least one selected column"
            )
        prefix = str(step.get("prefix", ""))
        enriched: Optional[set] = None if source is None else set(source)
        if reference is not None:
            for right_column in select:
                if str(right_column) not in reference:
                    raise TransformationError(
                        f"step {index} enrich column {right_column!r} is not in {reference_name}"
                    )
            if enriched is None:
                enriched = set()
            enriched |= {f"{prefix}{column}" for column in select}
        available[output_name] = enriched
    elif kind == "apply_component":
        component_name = str(step.get("component", "")).strip()
        if component_name not in components:
            raise TransformationError(
                f"step {index} references unknown component: {component_name!r}"
            )
        source_name = _resolve_working_name(step.get("input"), "input")
        _require_known(available, source_name, index, "input")
        rewritten = _rewrite_component_steps(
            component_name,
            list(components[component_name]),
            source_name,
            output_name,
            index,
        )
        for offset, nested in enumerate(rewritten):
            _validate_step(f"{index}.{offset}", nested, available, components)
    else:  # pragma: no cover - guarded by SUPPORTED_OPS at dispatch
        raise TransformationError(f"unsupported step op: {kind!r}")


# --- execution -------------------------------------------------------------


def _rewrite_component_steps(
    component_name: str,
    component_steps: Sequence[Mapping[str, Any]],
    outer_input: str,
    outer_output: str,
    index: Any,
) -> List[Mapping[str, Any]]:
    """Rename component internals into the caller's dataset namespace.

    ``{input}`` becomes the apply-step input; the final step's output becomes
    the apply-step output; every other intermediate output is prefixed so two
    uses of one component cannot collide. Intermediate input references are
    rewired from their original names to the renamed outputs, so chained
    component steps resolve in the caller's namespace.
    """
    rewritten: List[Mapping[str, Any]] = []
    renames = {"{input}": outer_input}
    for offset, sub_step in enumerate(component_steps):
        if not isinstance(sub_step, Mapping):
            raise TransformationError(
                f"step {index}.{offset} component step must be an object"
            )
        nested = dict(sub_step)
        raw_input = str(nested.get("input", "{input}"))
        nested["input"] = renames.get(raw_input, raw_input)
        if offset == len(component_steps) - 1:
            nested["output"] = outer_output
        else:
            original_output = str(nested.get("output", "")).strip()
            if not original_output:
                raise TransformationError(
                    f"step {index}.{offset} component step requires an output"
                )
            nested["output"] = f"{outer_output}__component_{offset}"
            renames[original_output] = nested["output"]
        rewritten.append(nested)
    return rewritten


def _numeric_value(value: Any, context: str) -> Decimal:
    if isinstance(value, bool) or value is None:
        raise TransformationError(f"{context} is not numeric")
    try:
        return value if isinstance(value, Decimal) else Decimal(str(value).replace(",", ""))
    except (InvalidOperation, ValueError) as exc:
        raise TransformationError(f"{context} is not numeric: {value!r}") from exc


def _join_key(row: Mapping[str, Any], columns: Sequence[str]) -> tuple:
    """Hashable comparison key; values compared by their text form."""
    key: list = []
    for column in columns:
        value = row.get(column)
        if value is None:
            key.append(None)
        elif isinstance(value, Decimal):
            key.append(f"decimal:{format(value.normalize(), 'f')}")
        else:
            key.append(f"text:{value}")
    return tuple(key)


def _coerce_cell(value: Any, dtype: str, row: Mapping[str, Any], context: str) -> Any:
    currency = str(row.get("currency") or "KES")
    if value is None:
        return None
    try:
        if dtype == "amount":
            return normalize_amount(value, currency=currency)
        if dtype == "timestamp":
            return normalize_timestamp(value)
        if dtype == "number":
            return _numeric_value(value, context)
        if dtype == "currency":
            from normalization import normalize_currency

            return normalize_currency(value)
        return str(value)
    except (NormalizationError, TransformationError) as exc:
        raise TransformationError(f"{context} failed: {exc}") from exc


def _records(dataset: Any, context: str) -> List[Dict[str, Any]]:
    rows = _require_list(dataset, context)
    for row in rows:
        if not isinstance(row, Mapping):
            raise TransformationError(f"{context} rows must be objects")
    return [dict(row) for row in rows]


def _run_column_mapping(step: Mapping[str, Any], datasets: Dict[str, list]) -> str:
    name = _resolve_working_name(step.get("input"), "input")
    output_name = _resolve_working_name(step.get("output"), "output")
    mapping = {str(source): str(target).strip() for source, target in step.get("mapping", {}).items()}
    keep_unmapped = bool(step.get("keep_unmapped", True))
    output_rows = []
    for row in datasets[name]:
        renamed = {mapping[column]: value for column, value in row.items() if column in mapping}
        if keep_unmapped:
            for column, value in row.items():
                if column not in mapping:
                    renamed[column] = value
        output_rows.append(renamed)
    datasets[output_name] = output_rows
    return output_name


def _run_schema_normalization(step: Mapping[str, Any], datasets: Dict[str, list]) -> str:
    name = _resolve_working_name(step.get("input"), "input")
    output_name = _resolve_working_name(step.get("output"), "output")
    types = {str(column): str(dtype) for column, dtype in step.get("types", {}).items()}
    null_on_error = step.get("on_error", "fail") == "null"
    output_rows = []
    for index, row in enumerate(datasets[name]):
        normalized = dict(row)
        for column, dtype in types.items():
            if column not in row:
                raise TransformationError(
                    f"schema_normalization row {index} is missing column: {column}"
                )
            try:
                normalized[column] = _coerce_cell(
                    row[column], dtype, row, f"schema_normalization row {index} column {column!r}"
                )
            except TransformationError:
                if null_on_error:
                    normalized[column] = None
                else:
                    raise
        output_rows.append(normalized)
    datasets[output_name] = output_rows
    return output_name


def _run_filter(step: Mapping[str, Any], datasets: Dict[str, list]) -> str:
    name = _resolve_working_name(step.get("input"), "input")
    output_name = _resolve_working_name(step.get("output"), "output")
    formula = str(step.get("formula", ""))
    try:
        output_rows = [dict(row) for row in datasets[name] if bool(evaluate_formula(formula, row))]
    except FormulaError as exc:
        raise TransformationError(f"filter failed: {exc}") from exc
    datasets[output_name] = output_rows
    return output_name


def _run_calculated_columns(step: Mapping[str, Any], datasets: Dict[str, list]) -> str:
    name = _resolve_working_name(step.get("input"), "input")
    output_name = _resolve_working_name(step.get("output"), "output")
    columns = [
        CalculatedColumn(
            name=str(raw.get("name", "")),
            formula=str(raw.get("formula", "")),
            dtype=str(raw.get("dtype", "number")),
        )
        for raw in step.get("columns", [])
    ]
    output_rows = []
    for index, row in enumerate(datasets[name]):
        computed = dict(row)
        for column in columns:
            try:
                computed[column.name] = evaluate_calculated_column(column, computed)
            except FormulaError as exc:
                raise TransformationError(
                    f"calculated column {column.name!r} failed on row {index}: {exc}"
                ) from exc
        output_rows.append(computed)
    datasets[output_name] = output_rows
    return output_name


def _run_union(step: Mapping[str, Any], datasets: Dict[str, list]) -> str:
    names = [_resolve_working_name(item, "input") for item in step.get("inputs", [])]
    output_name = _resolve_working_name(step.get("output"), "output")
    dedupe_keys = [str(key) for key in step.get("dedupe_keys", [])]
    merged: List[Dict[str, Any]] = []
    for name in names:
        merged.extend(dict(row) for row in datasets[name])
    if dedupe_keys:
        seen: set = set()
        deduplicated: List[Dict[str, Any]] = []
        for row in merged:
            key = _join_key(row, dedupe_keys)
            if key in seen:
                continue
            seen.add(key)
            deduplicated.append(row)
        merged = deduplicated
    datasets[output_name] = merged
    return output_name


def _run_lookup(
    left_rows: List[Dict[str, Any]],
    right_index: Dict[tuple, List[Dict[str, Any]]],
    key_pairs: Mapping[str, Any],
    prefix: str,
    keep_right_key: bool,
    how: str,
) -> List[Dict[str, Any]]:
    left_keys = list(key_pairs)
    right_keys = [str(value) for value in key_pairs.values()]
    output_rows: List[Dict[str, Any]] = []
    for row in left_rows:
        matches = right_index.get(_join_key(row, left_keys), [])
        if not matches and how == "inner":
            continue
        attached = matches[0] if matches else {}
        merged = dict(row)
        for right_column, value in attached.items():
            if right_column in right_keys and not keep_right_key:
                continue
            merged[f"{prefix}{right_column}"] = value
        output_rows.append(merged)
    return output_rows


def _index_by(rows: List[Dict[str, Any]], columns: Sequence[str]) -> Dict[tuple, List[Dict[str, Any]]]:
    index: Dict[tuple, List[Dict[str, Any]]] = {}
    for row in rows:
        index.setdefault(_join_key(row, list(columns)), []).append(row)
    return index


def _run_join(step: Mapping[str, Any], datasets: Dict[str, list]) -> str:
    left_name = _resolve_working_name(step.get("left"), "left")
    right_name = _resolve_working_name(step.get("right"), "right")
    output_name = _resolve_working_name(step.get("output"), "output")
    key_pairs = {str(left): str(right) for left, right in step.get("on", {}).items()}
    right_index = _index_by(datasets[right_name], list(key_pairs.values()))
    datasets[output_name] = _run_lookup(
        datasets[left_name],
        right_index,
        key_pairs,
        str(step.get("prefix", "")),
        keep_right_key=False,
        how=step.get("how", "inner"),
    )
    return output_name


def _quantize_money(value: Decimal) -> Decimal:
    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def _aggregate(spec: Mapping[str, Any], rows: List[Dict[str, Any]], target: str, index: int) -> Any:
    op = spec.get("op")
    field_name = str(spec.get("field", "") or "")
    if op == "count":
        return Decimal(len(rows))
    if op == "count_distinct":
        return Decimal(len({_join_key(row, [field_name])[0] for row in rows})) if field_name else Decimal(0)
    values = [_numeric_value(row.get(field_name), f"consolidate row {index} {field_name!r}") for row in rows]
    if op == "sum":
        return sum(values, Decimal(0))
    if op == "min":
        return min(values)
    if op == "max":
        return max(values)
    if op == "avg":
        return _quantize_money(sum(values, Decimal(0)) / Decimal(len(values))) if values else Decimal(0)
    raise TransformationError(f"consolidate row {index} has unsupported aggregation op: {op!r}")


def _run_consolidate(step: Mapping[str, Any], datasets: Dict[str, list]) -> str:
    name = _resolve_working_name(step.get("input"), "input")
    output_name = _resolve_working_name(step.get("output"), "output")
    group_by = [str(item) for item in step.get("group_by", [])]
    aggregations = {str(target): dict(spec) for target, spec in step.get("aggregations", {}).items()}
    groups: Dict[tuple, List[Dict[str, Any]]] = {}
    group_labels: Dict[tuple, Dict[str, Any]] = {}
    for row in datasets[name]:
        key = _join_key(row, group_by)
        groups.setdefault(key, []).append(row)
        if key not in group_labels:
            group_labels[key] = {column: row.get(column) for column in group_by}
    output_rows = []
    for key in sorted(groups, key=repr):
        combined = dict(group_labels[key])
        for index, (target, spec) in enumerate(aggregations.items()):
            combined[target] = _aggregate(spec, groups[key], target, index)
        output_rows.append(combined)
    datasets[output_name] = output_rows
    return output_name


def _run_enrich(step: Mapping[str, Any], datasets: Dict[str, list]) -> str:
    name = _resolve_working_name(step.get("input"), "input")
    reference_name = _resolve_working_name(step.get("reference"), "reference")
    output_name = _resolve_working_name(step.get("output"), "output")
    key_pairs = {str(left): str(right) for left, right in step.get("on", {}).items()}
    select = [str(item) for item in step.get("select", [])]
    trimmed = [
        {column: row.get(column) for column in set(key_pairs.values()) | set(select)}
        for row in datasets[reference_name]
    ]
    reference_index = _index_by(trimmed, list(key_pairs.values()))
    datasets[output_name] = _run_lookup(
        datasets[name],
        reference_index,
        key_pairs,
        str(step.get("prefix", "")),
        keep_right_key=False,
        how="left",
    )
    return output_name


_RUNNERS = {
    "column_mapping": _run_column_mapping,
    "schema_normalization": _run_schema_normalization,
    "filter": _run_filter,
    "calculated_columns": _run_calculated_columns,
    "union": _run_union,
    "join": _run_join,
    "consolidate": _run_consolidate,
    "enrich": _run_enrich,
}


def execute(
    definition: Mapping[str, Any],
    inputs: Mapping[str, Sequence[Mapping[str, Any]]],
    *,
    tenant_id: str,
    session: Any = None,
    components: Optional[Mapping[str, Sequence[Mapping[str, Any]]]] = None,
    resource_version: Optional[int] = None,
    job_id: Optional[str] = None,
) -> TransformationRun:
    """Execute a validated dataset definition against bound input datasets.

    ``inputs`` maps every declared ``inputs`` name to its real rows; no
    execution reads files, networks, or databases on its own. Reusable
    ``components`` expand ``apply_component`` steps inline. When ``session``
    is supplied, one append-only lineage row is recorded per executed step
    (source → output traceability), scoped to ``tenant_id``.
    """
    scoped_tenant = str(tenant_id or "").strip()
    if not scoped_tenant:
        raise TransformationError("tenant_id is required")
    bound = dict(inputs or {})
    check = validate_definition(definition, None, dict(components or {}))
    if not check.valid:
        raise TransformationError(
            "definition is invalid: "
            + "; ".join(f"step {issue.step}: {issue.message}" for issue in check.issues)
        )
    steps = list(definition.get("steps", []))  # type: ignore[union-attr]
    bound_rows: Dict[str, list] = {}
    for declared in definition.get("inputs", []):  # type: ignore[union-attr]
        bound_name = _resolve_working_name(declared, "inputs")
        if bound_name not in bound:
            raise TransformationError(
                f"execution is missing bound rows for input: {bound_name}"
            )
        rows = _records(bound.get(bound_name), f"input dataset {bound_name!r}")
        if len(rows) > MAX_INPUT_ROWS:
            raise TransformationError(
                f"input dataset {bound_name!r} exceeds {MAX_INPUT_ROWS} rows"
            )
        bound_rows[bound_name] = rows
    datasets: Dict[str, list] = dict(bound_rows)
    execution_id = f"txf_{uuid.uuid4().hex}"
    reports: List[StepReport] = []
    flat_steps = _expand_components(steps, dict(components or {}))
    for index, step in enumerate(flat_steps):
        started = time.perf_counter()
        kind = step.get("op")
        output_name = _RUNNERS[kind](step, datasets)
        if len(datasets[output_name]) > MAX_OUTPUT_ROWS:
            raise TransformationError(
                f"step {index} output exceeds {MAX_OUTPUT_ROWS} rows"
            )
        duration_ms = round((time.perf_counter() - started) * 1000, 3)
        reports.append(
            StepReport(
                index=index,
                op=str(kind),
                output=output_name,
                rows_in=sum(len(datasets.get(name, [])) for name in _input_names(step)),
                rows_out=len(datasets[output_name]),
                duration_ms=duration_ms,
            )
        )
        if session is not None:
            _record_step_lineage(
                session,
                tenant_id=scoped_tenant,
                execution_id=execution_id,
                index=index,
                step=step,
                output_name=output_name,
                resource_version=resource_version,
                job_id=job_id,
            )
    output_name = _resolve_working_name(definition.get("output", ""), "output")  # type: ignore[union-attr]
    return TransformationRun(
        execution_id=execution_id,
        output=output_name,
        row_count=len(datasets[output_name]),
        steps=reports,
    )


def output_rows(run: TransformationRun, datasets: Any) -> list:
    """Convenience accessor used by callers that retained the dataset map."""
    return list(datasets[run.output])


def _expand_components(
    steps: Sequence[Mapping[str, Any]],
    components: Mapping[str, Sequence[Mapping[str, Any]]],
) -> List[Mapping[str, Any]]:
    """Inline ``apply_component`` steps into their reusable step lists."""
    flat: List[Mapping[str, Any]] = []
    for position, step in enumerate(steps):
        if step.get("op") != "apply_component":
            flat.append(step)
            continue
        component_name = str(step.get("component", ""))
        flat.extend(
            _rewrite_component_steps(
                component_name,
                list(components[component_name]),
                str(step.get("input")),
                str(step.get("output")),
                f"expand.{position}",
            )
        )
    return flat


def _input_names(step: Mapping[str, Any]) -> List[str]:
    names: List[str] = []
    for key in ("input", "left", "right", "reference"):
        value = step.get(key)
        if isinstance(value, str) and value.strip():
            names.append(value.strip())
    for value in step.get("inputs", []) or []:
        if isinstance(value, str) and value.strip():
            names.append(value.strip())
    return names


def _record_step_lineage(
    session: Any,
    *,
    tenant_id: str,
    execution_id: str,
    index: int,
    step: Mapping[str, Any],
    output_name: str,
    resource_version: Optional[int],
    job_id: Optional[str],
) -> None:
    """Emit one durable lineage row per executed step for source-to-output tracing."""
    from lineage import record_lineage

    sources = "+".join(_input_names(step) or ["adhoc"])
    record_lineage(
        session,
        tenant_id=tenant_id,
        transaction_id="",
        event_id=f"transform:{execution_id}:{index:04d}",
        stage="TRANSFORMED",
        source=sources[:64],
        source_event_id=f"transform:{execution_id}:{output_name}"[:255],
        upstream_event_id=f"transform:{execution_id}:{(index - 1):04d}" if index else "",
        pipeline_version="transformation-engine/1.0",
        transformation_version=str(resource_version if resource_version is not None else 1),
        job_id=job_id,
        correlation_id=execution_id,
    )