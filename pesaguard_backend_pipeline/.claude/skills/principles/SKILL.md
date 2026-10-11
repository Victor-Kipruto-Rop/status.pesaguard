---
name: principles
description: >-
  Engineering principles that bind any code Molcajete plans, builds, or
  reviews — integration tests as the trust contract, hexagonal default,
  dependency injection, 80% coverage floor, universal software craft
  (small functions, clear boundaries, no god files, refactor to reuse),
  technology-agnostic. Loaded by /m:plan and /m:execute. Mirrored to the
  host project at `.claude/rules/principles.md` by /m:setup.
---

# Engineering Principles

These are the rules every plan, every line of code, and every test must respect — regardless of the host project's language, framework, or stack. Molcajete uses them; AI agents working in a Molcajete-set-up project see them in `CLAUDE.md` and in `.claude/rules/principles.md`.

## The Meta-Principle: Trust Comes from Tests, Not Code Shape

In AI-assisted development, code churns. Functions move, names change, files split and merge. The only signal that survives that churn is **behavior verified by tests**. If the integration tests pass and they cover the right thing, the code does its job — regardless of how it looks. If the tests are shallow or absent, no amount of human review compensates.

"Cover the right thing" is a **gate, not an assumption**: every task passes a correctness review (`/m:execute`'s correctness-review step, the Reviewer role in the `testing` skill) in which a separate agent reads the UC scenarios independently of the test's assertions. A task is not done until both the mechanical gate (green + coverage + non-vacuous mutation) and the correctness gate pass.

Everything below follows from that.

## 1. Integration Tests Are the Trust Contract

Integration tests drive the system through its public boundary — the same path real callers take. When they pass, the system *as a whole* satisfies the spec.

Integration tests are **the only test type Molcajete generates**. Every UC and feature is backed by integration tests, no exceptions. They are written first, drive through a driver port with the real internal stack, and own the coverage floor (see Principle 4).

Molcajete does not produce unit tests. If the host team wants unit tests for algorithmic code (parsers, encoders, hash routines, math), they write and maintain those themselves — outside Molcajete's lifecycle. Pre-existing host-project unit tests are ignored for coverage math; the floor is met by integration tests only. A unit test's existence never waives the requirement for an integration test — every scenario in every UC still needs its integration coverage.

### Test Writing Rules

Tests are not just verification — they are the most important documentation an AI-assisted codebase has. The next agent reads tests to learn what the code is supposed to do.

Examples below use TypeScript with a Jest/Vitest-style runner because that surface is broadly familiar. The rules are language-agnostic — translate `describe` to test class (Java, C#, Solidity) or test module (Python, Go); `it` to method or function; `//` to the idiomatic comment for the language.

**1.1 Descriptive test names.**

Test method / `it` / function names read as sentences describing the behavior being verified. No spec IDs in the name (`test_SC_REQ3`, `it('SC-0KTh works')`, `test1` are all wrong). Avoid abbreviations beyond standard ones.

```ts
it('rejects an empty email with a validation error', () => { ... })
it('returns the user profile for an authenticated request', () => { ... })
it('reverts when oracle equals operator', () => { ... })
```

**1.2 IDs in comments, not in names.**

Spec traceability lives in leading-line comments above tests, not in their names.

- **File header** — `// UC-XXXX: {use case name}` and `// T-NNN: {task outcome}` at the top of every test file. One UC and one task per file.
- **`describe` / test group** — `// SC-XXXX: {short scenario description}` at the top of each group. One scenario per group.
- **`it` / test method** — `// SC-XXXX: {short scenario description}` immediately above the test. When a single test covers multiple scenarios, list them comma-separated: `// SC-0KTh, SC-0KTi: Email validation rules`.

The task's `Covers` list in the plan file is the canonical machine-readable mapping. The comments are for humans.

```ts
// UC-0KTg: Register User
// T-001: Validate and register a new user's email

describe('Email validation', () => {
  // SC-0KTh: Reject empty email
  it('rejects an empty email with a validation error', () => { ... })

  // SC-0KTi: Reject malformed email
  it('rejects an email without an @ symbol', () => { ... })
})
```

The same shape works in any runner — file-header comment for the UC and task, group- and method-header comments for the scenario — with the comment syntax (`//`, `#`, `--`, `/* */`) the language uses.

**1.3 Precise, realistic values.**

Assertions pin exact values that match production reality:

```ts
expect(balance).toBe(1_500_000n)
expect(user.email).toBe('ada@example.com')
expect(result.status).toBe('approved')
```

When the expected value depends on inputs, the test computes it explicitly so the calculation reads as documentation:

```ts
const fee = (amount * FEE_BPS) / 10_000n  // 0.5% of 1_000_000 = 5_000
expect(result.fee).toBe(fee)
```

Vague assertions (`gt(0)`, `not null`, `truthy`) are forbidden unless the spec genuinely says "any positive value" or "any defined value" — rare, and the test must include a comment explaining the looseness.

**1.4 Comments that document intent.**

Every test (or every scenario block of tests) carries a comment explaining what the test pins and why that behavior exists. Treat the comment as the file's documentation — it is what the next agent reads to learn what the code is supposed to do.

```ts
// SC-0KTi: Reject malformed email
//
// Email addresses must conform to RFC 5321: a local part, an `@`,
// and a domain. The validator rejects strings missing the `@` or
// with empty local/domain parts before the request reaches any
// downstream code.
//
// Example: 'ada.example.com' (missing @) and '@example.com' (empty
// local part) both produce a 422 with code = 'invalid_email'.
it('rejects an email without an @ symbol', () => { ... })
```

A comment covers what behavior is under test, why it exists in business or safety terms, and any edge case the assertion alone does not reveal. Add a concrete example when the assertion does not make the behavior obvious. Write what those need, and stop there.

**1.5 Test only current behavior.**

Tests, comments, and code describe only what the system does **now**. When behavior changes, assert the new behavior directly and delete every trace of the old.

- **Assert the new behavior, never the absence of the old.** A test whose purpose is to prove a removed capability is gone is forbidden — it is a tautology that pins nothing and rots into a lie. Do not write `expect(oldThing).toThrow()` or "no longer returns X" checks to memorialize a change. Assert what the code *does* now, with precise values (1.3).
- **When a scenario / FR / NFR changes, rewrite its tests to the new expected values.** When one is removed, delete its test cases outright — and delete the explanatory comments (1.4) that traveled with them. Never leave a test asserting removed behavior; never add a "this used to…" comment to a test.
- **Every new functional requirement gets a positive test.** Every new **behaviorally-observable** non-functional requirement — anything reachable through a driver port, e.g. authorization, input validation, error handling, idempotency, rate limiting — gets a positive test too. NFRs that cannot be exercised as driver-port behavior (raw latency, load, throughput) stay spec-only acceptance criteria; name them as out-of-scope for the automated loop rather than faking a test.

The changelog — not the test file — is where "what changed" lives (see 5.5).

## 2. Hexagonal Architecture Is the Default Shape

Code is organized around two kinds of ports:

- **Driver ports** — how the outside reaches the code: HTTP routes, GraphQL resolvers, CLI commands, event handlers, queue workers, cron tasks, public service methods. Listed in `specs/MODULES.md` per module under `Driving Ports`.
- **Driven ports** — how the code reaches the outside: databases, message buses, internal HTTP clients, file system, OS clock, external service SDKs.

Integration tests **drive through the driver ports** with the real internal stack running. They use **real driven ports** for everything the project owns (its own database, its own queues, its own internal services). Only **outer-edge driven ports** without sandboxes — third-party payment gateways, SMS providers, external APIs without test modes — get mocked.

This shape is universal. It does not dictate language, framework, or library.

## 3. Dependency Injection Makes Adapters Swappable

Wire dependencies through constructors, function arguments, or a DI container. Avoid module-level globals, ambient singletons, and import-time side effects. The test sets up the system with the adapters it wants and stubs only what it must.

DI is the principle. The mechanism (a DI container library, constructor injection, factory functions, pass-through arguments) is the host project's choice and lives in `specs/TECH-STACK.md`.

## 4. 80% Coverage Floor on Touched Files

Every task's touched files (the files the task creates and modifies, plus its integration test file) must hit at least 80% line coverage.

- The threshold is configurable via `.molcajete/settings.json testing.threshold`; 80% is the default and the floor.
- Coverage is scoped to **touched files**, not the whole project. The goal is "we proved this change works," not "we hit a global percentage."
- The host project's coverage collector (declared in `specs/TECH-STACK.md`'s **Coverage** rows per module) is the source of truth. When a module declares `not available`, `/m:execute` makes a best-effort estimate against the floor and surfaces the estimate in its report.

## 5. Universal Software Craft

These rules apply regardless of stack. They are **navigation rules for the next AI agent** working in this code, not aesthetics:

- **Single responsibility.** One function does one thing. One module owns one concern.
- **Small functions.** Long functions hide bugs. If the function does not fit on one screen, split it.
- **Tell, don't ask.** Push work into the object that owns the data; don't pull data out to act on it externally.
- **Clear module boundaries.** Every module has a public API and an internal world. Other modules touch the public API only.
- **No god files.** When a file passes substantive responsibility for more than one concern, split it by responsibility — not by line count alone, but line count is a signal.
- **Refactor to reuse, never duplicate.** When you see a function that already exists, call it. When you see two functions doing the same thing, extract the shared logic. AI is uniquely prone to silent duplication — treat every "let me write a small helper" as an opportunity to grep first.
- **Patterns where they earn their keep.** Use well-known patterns (repository, command, observer, strategy) only where the situation calls for them. Don't impose patterns; recognize them.

### Code Comments

The same documentation discipline that applies to tests applies to production code. Code expresses *mechanism*; comments express *intent*. Examples stay in TypeScript; the patterns apply identically in any language.

**5.1 Spec traceability in code comments.**

Production code carries leading-line comments tying files and functions back to the spec.

- **File header** — `// FEAT-XXXX: {feature name}` and `// UC-XXXX: {use case name}` at the top of every production file produced for a feature/UC.
- **Function / method** — `// SC-XXXX, SC-YYYY: {short description}` immediately above the function declaration when it satisfies specific scenarios. List all SC IDs the function helps satisfy; the task's `Covers` list in the plan file remains the canonical machine-readable mapping.

```ts
// FEAT-0Fy0: User Onboarding
// UC-0KTg: Register User

// SC-0KTh, SC-0KTi: Email validation rules
// Returns the normalized email on success or throws InvalidEmailError.
export function validateRegistrationEmail(raw: string): string { ... }
```

**5.2 Function / method header comments.**

Every non-trivial function carries a header comment explaining what it does and why. Trivial accessors (one-line getters/setters that wrap a field) can skip. The header covers:

- **What** — one or two sentences describing what the function does.
- **Why** — one sentence explaining why it exists in business or domain terms.
- **Non-obvious** — constraints, invariants, side effects the next reader needs to know.

A comment that just repeats the signature in English (`// Validates the email`) does not count.

```ts
// Validates a registration email before any downstream code processes it.
// Returns the normalized email (trimmed, lowercased) on success, or throws
// InvalidEmailError with code='invalid_email'.
//
// Used during the public registration flow to keep malformed addresses
// from reaching the user store. The format follows RFC 5321 plus the
// local-part-non-empty constraint our user model requires.
export function validateRegistrationEmail(raw: string): string { ... }
```

**5.3 Inline comments for non-obvious blocks.**

A group of lines that accomplishes a discrete step gets a comment when the reason for the step is not visible in the code. Don't comment obvious lines (`x = x + 1` doesn't need a comment); comment the *intent* of multi-line operations — what the step is for and which constraint made it necessary.

**5.4 Comment where the code cannot explain itself.**

Match comment density to how much intent the code hides. Self-evident code earns no comment. Code whose reason is invisible earns one. Concentrate them in:

- **Complicated control flow** — multi-branch conditions, early returns with side effects, error-handling chains.
- **External-system interactions** — network, file system, hardware, external APIs. Comment what could go wrong and why the code handles it that way.
- **Domain-heavy logic** — math, business rules, or invariants that aren't self-evident.
- **Performance-sensitive sections** — comment the trade-off being made.

A comment that restates the line below it is noise. Delete it.

**5.5 Comments and code describe only current behavior.**

The comment discipline above documents *what the code does now* — never what it used to do. This is the code-and-comment half of Principle 1.5.

- **No comment narrates history.** `previously`, `used to`, `formerly`, `no longer`, `deprecated`, "changed from X to Y", "was Z before" have no place in code or test comments. A comment describing old behavior is a trap: the next reader believes it. The CHANGELOG is the only record of history.
- **When behavior is removed, delete the code and comments that served it.** Do not annotate them as obsolete, comment them out, or wrap them in "kept for reference." Dead code and stale comments are fog of war; the git history and changelog preserve what was there.

## 6. Principles Are Technology-Agnostic

This document does not specify a language, framework, runner, DI container, ORM, queue library, or coverage tool. Those are the host project's choices, recorded in `specs/TECH-STACK.md`. Principles bind regardless.

## 7. Every Word Earns Its Place

Specs, plans, comments, and reports are read by the next AI agent with no back-channel. It cannot ask what an ambiguous sentence meant, so it guesses. Ambiguous prose is therefore a defect, in exactly the way a vague assertion (1.3) is a defect. Prose the reader did not need is a defect too, because it dilutes the prose they did.

Everything written — spec files, plans, review documents, changelog entries, commit messages, code comments, test comments, and on-screen reports — obeys two rules at once.

- **Simplified Technical English**, as defined by ASD-STE100: one meaning per word, active voice, simple tenses, one instruction per sentence, no idioms. An opaque identifier never appears without the concept it names — write "register user (UC-0KTg)", never `UC-0KTg` alone. The `writing-style` shared skill holds the full rules and the self-check.
- **Output economy**: carry what the reader needs for their next action, then stop. The `output-economy` shared skill holds the content test, the per-surface budgets, and the cases where completeness outranks a budget.

Every command loads both before it writes.

## How Molcajete Enforces These

| Command | Enforcement |
|---------|-------------|
| `/m:plan` | Designs architecture in hexagonal vocabulary. Each task names the driver port it drives and the driven ports its code reaches, and delivers one vertical, working increment (never a layer). Decomposition covers every scenario exactly once. |
| `/m:execute` | Runs each task through scaffold integration test → implement → mutation check → coverage gate → **correctness review**, writing code that respects Principle 5. The coverage gate enforces Principle 4; the correctness review enforces the Meta-Principle before a task's checkbox flips. |
| `uc-log` shared skill | Records every change, so new work stays explicit over time. |
| `writing-style` shared skill | Every command loads it before it writes. Enforces the sentence half of Principle 7 across every generated document and every printed message. |
| `output-economy` shared skill | Every command loads it before it writes. Enforces the volume half of Principle 7 across files, screen output, question briefs, and command output. |

## Override

The host project can edit `.claude/rules/principles.md` to adapt principles to their context — for example: "we always hit Stripe test mode and never mock the payments adapter." Molcajete reads the **host file first**; the plugin's `principles` skill is the default that ships in `.claude/rules/principles.md` at first `/m:setup`. Re-running `/m:setup` preserves the host file by default; the user can opt to regenerate from the plugin skill.
