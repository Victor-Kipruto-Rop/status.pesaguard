"""Safe formula DSL for calculated columns and no-code transformations.

Formulas are parsed with ``ast`` and evaluated against a strict whitelist:
only literals, field references, comparisons, boolean logic, arithmetic,
conditional expressions, and approved functions are permitted. Arbitrary
Python (attribute access, comprehensions, lambdas, imports) is rejected at
parse time, so untrusted formula documents can never execute code.

Numbers are ``Decimal`` end-to-end so monetary values never pass through
binary floating point (Rule: financial correctness first).

Natural-language assistance is deterministic and pattern-based, never an
invented LLM. ``FormulaAssister`` is a pluggable interface so a
tenant-configured model can be introduced later without API changes.
"""

from __future__ import annotations

import ast
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import re
from typing import Any, Iterable, List, Mapping, Optional, Protocol, Sequence

from normalization import NormalizationError, normalize_amount, normalize_timestamp


class FormulaError(ValueError):
    """Raised when a formula cannot be parsed, validated, or evaluated."""


class FormulaSyntaxError(FormulaError):
    """Raised when a formula is not valid DSL syntax."""


class FormulaEvaluationError(FormulaError):
    """Raised when evaluation fails (type mismatch, division by zero, ...)."""


SUPPORTED_DTYPES = frozenset({"number", "string", "boolean", "timestamp", "amount"})

_ALLOWED_NODES: tuple[type, ...] = (
    ast.Expression,
    ast.Constant,
    ast.Name,
    ast.Load,
    ast.BinOp,
    ast.UnaryOp,
    ast.BoolOp,
    ast.Compare,
    ast.IfExp,
    ast.Call,
    ast.keyword,
    ast.List,
    ast.Tuple,
    ast.Add,
    ast.Sub,
    ast.Mult,
    ast.Div,
    ast.Mod,
    ast.USub,
    ast.UAdd,
    ast.Not,
    ast.And,
    ast.Or,
    ast.Eq,
    ast.NotEq,
    ast.Lt,
    ast.LtE,
    ast.Gt,
    ast.GtE,
    ast.In,
    ast.NotIn,
)

_FUNCTIONS = frozenset(
    {
        "abs",
        "round",
        "min",
        "max",
        "coalesce",
        "len",
        "str",
        "upper",
        "lower",
        "trim",
        "contains",
        "startswith",
        "endswith",
    }
)


def _parse(expression: str) -> ast.Expression:
    text = str(expression or "").strip()
    if not text:
        raise FormulaSyntaxError("formula is empty")
    if len(text) > 2000:
        raise FormulaSyntaxError("formula exceeds 2000 characters")
    try:
        tree = ast.parse(text, mode="eval")
    except SyntaxError as exc:
        raise FormulaSyntaxError(f"invalid formula syntax: {exc.msg}") from exc
    for node in ast.walk(tree):
        if not isinstance(node, _ALLOWED_NODES):
            raise FormulaSyntaxError(
                f"unsupported expression element: {type(node).__name__}"
            )
        if isinstance(node, ast.Call):
            if not isinstance(node.func, ast.Name) or node.func.id not in _FUNCTIONS:
                raise FormulaSyntaxError("only approved functions may be called")
            if node.keywords:
                raise FormulaSyntaxError("keyword arguments are not supported")
    return tree


@dataclass(frozen=True)
class FormulaCheck:
    """Structured outcome of formula validation (API-friendly)."""

    valid: bool
    errors: List[str] = field(default_factory=list)
    fields_used: List[str] = field(default_factory=list)
    functions_used: List[str] = field(default_factory=list)


def validate_formula(
    expression: str,
    available_fields: Optional[Iterable[str]] = None,
) -> FormulaCheck:
    """Validate syntax plus optional field availability; never raises."""
    errors: List[str] = []
    fields_used: List[str] = []
    functions_used: List[str] = []
    try:
        tree = _parse(expression)
    except FormulaError as exc:
        return FormulaCheck(valid=False, errors=[str(exc)])
    known = None if available_fields is None else {str(name) for name in available_fields}
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load):
            if node.id not in fields_used:
                fields_used.append(node.id)
            if node.id not in _FUNCTIONS and known is not None and node.id not in known:
                errors.append(f"unknown field: {node.id}")
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            if node.func.id not in functions_used:
                functions_used.append(node.func.id)
            if node.func.id == "round" and len(node.args) == 2 and not isinstance(
                node.args[1], ast.Constant
            ):
                errors.append("round() digits argument must be a literal")
    return FormulaCheck(
        valid=not errors,
        errors=errors,
        fields_used=fields_used,
        functions_used=functions_used,
    )


def _to_decimal(value: Any, context: str) -> Decimal:
    if isinstance(value, bool):
        raise FormulaEvaluationError(f"{context} does not accept a boolean")
    if isinstance(value, Decimal):
        return value
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, float):
        # Only produced by JSON parsing; convert via str to avoid binary noise.
        return Decimal(str(value))
    if isinstance(value, str):
        try:
            return Decimal(value.replace(",", ""))
        except InvalidOperation as exc:
            raise FormulaEvaluationError(f"{context} is not numeric: {value!r}") from exc
    raise FormulaEvaluationError(f"{context} is not numeric: {type(value).__name__}")


def _evaluate(node: ast.AST, record: Mapping[str, Any]) -> Any:
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, ast.Name):
        return record.get(node.id)
    if isinstance(node, (ast.List, ast.Tuple)):
        return [_evaluate(item, record) for item in node.elts]
    if isinstance(node, ast.UnaryOp):
        operand = _evaluate(node.operand, record)
        if isinstance(node.op, ast.Not):
            return not bool(operand)
        number = _to_decimal(operand, "unary operand")
        return -number if isinstance(node.op, ast.USub) else +number
    if isinstance(node, ast.BinOp):
        left = _to_decimal(_evaluate(node.left, record), "left operand")
        right = _to_decimal(_evaluate(node.right, record), "right operand")
        if isinstance(node.op, ast.Add):
            return left + right
        if isinstance(node.op, ast.Sub):
            return left - right
        if isinstance(node.op, ast.Mult):
            return left * right
        if isinstance(node.op, ast.Div):
            if right == 0:
                raise FormulaEvaluationError("division by zero")
            return left / right
        if isinstance(node.op, ast.Mod):
            if right == 0:
                raise FormulaEvaluationError("modulo by zero")
            return left % right
        raise FormulaEvaluationError("unsupported arithmetic operator")
    if isinstance(node, ast.BoolOp):
        values = [_evaluate(item, record) for item in node.values]
        if isinstance(node.op, ast.And):
            return all(bool(value) for value in values)
        return any(bool(value) for value in values)
    if isinstance(node, ast.Compare):
        left = _evaluate(node.left, record)
        for op, comparator in zip(node.ops, node.comparators):
            right = _evaluate(comparator, record)
            if not _compare(left, op, right):
                return False
            left = right
        return True
    if isinstance(node, ast.IfExp):
        return (
            _evaluate(node.body, record)
            if bool(_evaluate(node.test, record))
            else _evaluate(node.orelse, record)
        )
    if isinstance(node, ast.Call):
        args = [_evaluate(item, record) for item in node.args]
        return _call_function(node.func.id, args)  # type: ignore[attr-defined]
    raise FormulaEvaluationError(f"unsupported expression element: {type(node).__name__}")


def _numeric_or_none(value: Any) -> Optional[Decimal]:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (Decimal, int, float)):
        return _to_decimal(value, "comparison operand")
    if isinstance(value, str):
        try:
            return Decimal(value.replace(",", ""))
        except InvalidOperation:
            return None
    return None


def _compare(left: Any, op: ast.cmpop, right: Any) -> bool:
    if isinstance(op, (ast.In, ast.NotIn)):
        container = right if isinstance(right, list) else [right]
        member = left in container
        return member if isinstance(op, ast.In) else not member
    if left is None or right is None:
        if isinstance(op, ast.Eq):
            return bool(left is right)
        if isinstance(op, ast.NotEq):
            return not bool(left is right)
        return False
    left_number = _numeric_or_none(left)
    right_number = _numeric_or_none(right)
    if left_number is not None and right_number is not None:
        left_value: Any = left_number
        right_value: Any = right_number
    elif left_number is None and right_number is None and isinstance(left, str) and isinstance(right, str):
        # Pure text comparison (numeric-looking strings compared above).
        left_value = left
        right_value = right
    else:
        raise FormulaEvaluationError("cannot compare text with numbers")
    if isinstance(op, ast.Eq):
        return left_value == right_value
    if isinstance(op, ast.NotEq):
        return left_value != right_value
    if isinstance(op, ast.Lt):
        return left_value < right_value
    if isinstance(op, ast.LtE):
        return left_value <= right_value
    if isinstance(op, ast.Gt):
        return left_value > right_value
    if isinstance(op, ast.GtE):
        return left_value >= right_value
    raise FormulaEvaluationError("unsupported comparison operator")


def _call_function(name: str, args: List[Any]) -> Any:
    if name == "abs":
        return abs(_to_decimal(args[0], "abs()"))
    if name == "round":
        number = _to_decimal(args[0], "round()")
        digits = int(args[1]) if len(args) > 1 else 2
        if digits < 0 or digits > 9:
            raise FormulaEvaluationError("round() digits must be between 0 and 9")
        return number.quantize(Decimal(1).scaleb(-digits), rounding=ROUND_HALF_UP)
    if name in {"min", "max"}:
        if not args:
            raise FormulaEvaluationError(f"{name}() requires at least one argument")
        numbers = [_to_decimal(item, f"{name}()") for item in args]
        return min(numbers) if name == "min" else max(numbers)
    if name == "coalesce":
        for item in args:
            if item is not None:
                return item
        return None
    if name == "len":
        if args[0] is None:
            raise FormulaEvaluationError("len() does not accept null")
        return Decimal(len(str(args[0])))
    if name == "str":
        return "" if args[0] is None else str(args[0])
    if name in {"upper", "lower", "trim"}:
        if args[0] is None:
            raise FormulaEvaluationError(f"{name}() does not accept null")
        text = str(args[0])
        if name == "upper":
            return text.upper()
        if name == "lower":
            return text.lower()
        return text.strip()
    if name in {"contains", "startswith", "endswith"}:
        if args[0] is None or args[1] is None:
            return False
        haystack, needle = str(args[0]), str(args[1])
        if name == "contains":
            return needle in haystack
        if name == "startswith":
            return haystack.startswith(needle)
        return haystack.endswith(needle)
    raise FormulaEvaluationError(f"unknown function: {name}")


def evaluate_formula(expression: str, record: Mapping[str, Any]) -> Any:
    """Evaluate one formula against a record. Raises FormulaError on failure."""
    tree = _parse(expression)
    return _evaluate(tree.body, record)


@dataclass(frozen=True)
class CalculatedColumn:
    """A configurable calculated column definition (no-code contract)."""

    name: str
    formula: str
    dtype: str = "number"


def validate_calculated_column(
    column: CalculatedColumn,
    available_fields: Optional[Iterable[str]] = None,
) -> FormulaCheck:
    """Validate a calculated column definition; never raises."""
    errors: List[str] = []
    name = str(column.name or "").strip()
    if not name:
        errors.append("calculated column name is required")
    elif not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", name):
        errors.append(f"invalid calculated column name: {name!r}")
    if column.dtype not in SUPPORTED_DTYPES:
        errors.append(
            f"unsupported dtype {column.dtype!r}; expected one of {', '.join(sorted(SUPPORTED_DTYPES))}"
        )
    check = validate_formula(column.formula, available_fields)
    errors.extend(check.errors)
    return FormulaCheck(
        valid=not errors,
        errors=errors,
        fields_used=check.fields_used,
        functions_used=check.functions_used,
    )


def evaluate_calculated_column(
    column: CalculatedColumn,
    record: Mapping[str, Any],
) -> Any:
    """Evaluate and coerce one calculated column value to its dtype."""
    value = evaluate_formula(column.formula, record)
    if column.dtype == "number":
        return None if value is None else _to_decimal(value, f"column {column.name}")
    if column.dtype == "amount":
        if value is None:
            return None
        currency = str(record.get("currency") or "KES")
        try:
            return normalize_amount(value, currency=currency)
        except NormalizationError as exc:
            raise FormulaEvaluationError(
                f"column {column.name} amount is invalid: {exc}"
            ) from exc
    if column.dtype == "timestamp":
        if value is None:
            return None
        try:
            return normalize_timestamp(value)
        except NormalizationError as exc:
            raise FormulaEvaluationError(
                f"column {column.name} timestamp is invalid: {exc}"
            ) from exc
    if column.dtype == "boolean":
        return None if value is None else bool(value)
    return None if value is None else str(value)


@dataclass(frozen=True)
class FormulaSuggestion:
    """A natural-language -> formula suggestion with confidence evidence."""

    formula: str
    explanation: str
    confidence: float
    matched_pattern: str = ""
    fields_used: List[str] = field(default_factory=list)


class FormulaAssister(Protocol):
    """Pluggable interface for natural-language formula assistance.

    The default implementation is deterministic (pattern-based). A
    tenant-configured model can implement this protocol later without any
    change to callers; no model is fabricated or implied here.
    """

    def suggest(self, utterance: str, fields: Sequence[str]) -> FormulaSuggestion:
        ...


_NUMBER = re.compile(r"^[0-9]+(\.[0-9]+)?$")


def _field_token(text: str, fields: Sequence[str]) -> Optional[str]:
    """Resolve one spoken token to a known field (longest name wins)."""
    cleaned = text.strip().strip(".,?!").lower()
    for name in sorted(fields, key=len, reverse=True):
        if cleaned == name.lower() or cleaned == name.replace("_", " ").lower():
            return name
    return None


def _literal_token(text: str) -> str:
    cleaned = text.strip().strip(".,?!")
    if _NUMBER.fullmatch(cleaned):
        return cleaned
    return f"'{cleaned}'"


class DeterministicFormulaAssister:
    """Pattern-based natural-language formula assistance (no model involved).

    Supported intents (English): add/sum, subtract, multiply, divide,
    if/then/else conditionals, and round-to-N-decimals. Suggestions are
    validated with :func:`validate_formula` before being returned, so a
    returned suggestion is always syntactically valid.
    """

    _ARITHMETIC = (
        (re.compile(r"(?:sum of|add(?:ed)? )(.+?) (?:and|to) (.+)", re.IGNORECASE), "+", "addition"),
        (re.compile(r"(.+?) plus (.+)", re.IGNORECASE), "+", "addition"),
        (re.compile(r"(.+?) minus (.+)", re.IGNORECASE), "-", "subtraction"),
        (re.compile(r"(.+?) (?:times|multiplied by) (.+)", re.IGNORECASE), "*", "multiplication"),
        (re.compile(r"(.+?) (?:divided by|over) (.+)", re.IGNORECASE), "/", "division"),
    )

    def suggest(self, utterance: str, fields: Sequence[str]) -> FormulaSuggestion:
        text = " ".join(str(utterance or "").split())
        if not text.strip():
            return FormulaSuggestion("", "empty request", 0.0)
        arithmetic = self._match_arithmetic(text, fields)
        if arithmetic is not None:
            return arithmetic
        for matcher in (self._match_conditional, self._match_round):
            suggestion = matcher(text, fields)
            if suggestion is not None:
                return suggestion
        return FormulaSuggestion("", "no deterministic pattern matched", 0.0)

    def _match_arithmetic(self, text: str, fields: Sequence[str]) -> Optional[FormulaSuggestion]:
        for pattern, operator, name in self._ARITHMETIC:
            match = pattern.search(text)
            if not match:
                continue
            left = _field_token(match.group(1), fields)
            right = _field_token(match.group(2), fields)
            if left is None or right is None:
                return FormulaSuggestion(
                    "",
                    f"matched {name} but a spoken term is not a known field",
                    0.3,
                    name,
                )
            return self._build(f"{left} {operator} {right}", f"{name} of {left} and {right}", name, [left, right])
        return None

    def _build(
        self,
        formula: str,
        explanation: str,
        pattern: str,
        used: list,
    ) -> FormulaSuggestion:
        valid = self._check(formula, used)
        return FormulaSuggestion(
            formula if valid else "",
            explanation,
            0.9 if valid else 0.0,
            pattern,
            used,
        )

    @staticmethod
    def _check(formula: str, fields: Sequence[str]) -> bool:
        return validate_formula(formula, fields).valid

    def _match_conditional(self, text: str, fields: Sequence[str]) -> Optional[FormulaSuggestion]:
        match = re.search(r"if (.+?) is (.+?) then (.+?) else (.+)", text, re.IGNORECASE)
        if not match:
            return None
        condition_field = _field_token(match.group(1), fields)
        if condition_field is None:
            return FormulaSuggestion(
                "",
                "matched if/then/else but the condition term is not a known field",
                0.3,
                "conditional",
            )
        value = _literal_token(match.group(2))
        then_field = _field_token(match.group(3), fields)
        else_field = _field_token(match.group(4), fields)
        then_expr = then_field or _literal_token(match.group(3))
        else_expr = else_field or _literal_token(match.group(4))
        formula = f"{then_expr} if {condition_field} == {value} else {else_expr}"
        used = [name for name in (condition_field, then_field, else_field) if name]
        return self._build(formula, f"conditional on {condition_field}", "conditional", used)

    def _match_round(self, text: str, fields: Sequence[str]) -> Optional[FormulaSuggestion]:
        match = re.search(r"round (.+?) to ([0-9]+) decimal", text, re.IGNORECASE)
        if not match:
            return None
        target = _field_token(match.group(1), fields)
        if target is None:
            return FormulaSuggestion(
                "", "matched round() but the term is not a known field", 0.3, "rounding"
            )
        digits = int(match.group(2))
        formula = f"round({target}, {digits})"
        return self._build(
            formula,
            f"round {target} to {digits} decimal places",
            "rounding",
            [target],
        )


def suggest_formula(
    utterance: str,
    fields: Sequence[str],
    assister: Optional[FormulaAssister] = None,
) -> FormulaSuggestion:
    """Entry point for natural-language formula assistance."""
    return (assister or DeterministicFormulaAssister()).suggest(utterance, fields)