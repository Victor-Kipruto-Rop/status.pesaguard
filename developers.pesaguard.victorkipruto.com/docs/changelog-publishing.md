# Developer platform changelog

The developer portal changelog is a published feed backed by
`platform_changelog_entries`. The public `GET /api/v1/changelog` endpoint and
the portal's **Resources → Changelog** drawer return published entries only,
ordered newest first. There are no generated or seeded release notes: an empty
feed is expected until the platform team has confirmed changes that shipped.

## Operator publishing workflow

Authoring is restricted to internal operator tokens. It is intentionally not
available to developer accounts or through the developer-facing portal UI.
Use an approved operator credential over the internal API; never put an
operator token in a browser bundle, developer session, or public documentation.

The operator endpoints are under `/internal/configuration/changelog`:

| Method | Path | Capability | Result |
| --- | --- | --- | --- |
| `GET` | `/internal/configuration/changelog` | `PLATFORM_CONFIG_READ` | Lists private drafts |
| `POST` | `/internal/configuration/changelog` | `PLATFORM_CONFIG_WRITE` | Creates a draft |
| `PUT` | `/internal/configuration/changelog/{entryId}` | `PLATFORM_CONFIG_WRITE` | Updates a draft |
| `POST` | `/internal/configuration/changelog/{entryId}/publish` | `PLATFORM_CONFIG_WRITE` | Publishes a draft |

Every write requires the request-scoped `X-Operator-Reason` header. Each
successful create, edit, and first publication is recorded in
`platform_changelog_entry_audit` in the same database transaction as the
change. Published entries are immutable; correct an already-published entry
with a new, clearly described entry rather than editing history.

Before creating a release note, confirm the change has shipped and collect:

- the release version (up to 64 characters);
- a concise title (up to 160 characters);
- a plain-text description of the shipped behavior (up to 10,000 characters);
- one category: `FEATURE`, `IMPROVEMENT`, `FIX`, or `SECURITY`.

Do not write plans, estimates, simulated behavior, or unverified claims as
released changes. The entry body is rendered as plain text in the portal; it
is not interpreted as Markdown or HTML.

Create a draft:

```http
POST /internal/configuration/changelog
Authorization: Bearer <approved-operator-token>
X-Operator-Reason: Publishing verified developer platform release notes
Content-Type: application/json

{
  "version": "<verified-release-version>",
  "title": "<title for a verified shipped change>",
  "body": "<describe only behavior available to developers>",
  "category": "IMPROVEMENT"
}
```

Review it with the operator draft-list endpoint, edit with `PUT` if needed, and
publish only after verification using
`POST /internal/configuration/changelog/{entryId}/publish` with a fresh
`X-Operator-Reason`. The public feed updates from the persisted published
entry; drafts never appear there.
