# Safe error handling

The web client sends API requests through `frontend/src/lib/api.ts`. That layer
maps HTTP status and controlled public error codes to user-facing copy, discards
server-provided messages and validation strings, normalizes transport and
timeout failures, and rejects malformed successful responses with a generic
message. Automatic retries remain limited to safe `GET` and `HEAD` requests
configured with a retry policy.

The Java API uses `GlobalExceptionHandler` to keep exception details out of
responses. It returns the established `{ error: { code, message, requestId,
timestamp, violations } }` envelope, maps messages from status and approved
challenge codes, and replaces validation detail with a safe generic field
message. Rate-limit rejections use the same envelope and fixed public messages.
Backend diagnostics retain exception types and stack frames but omit exception
messages, which can contain secrets or user data.

The API Explorer shows only the HTTP status, safe message, and a short `PG-`
support reference. It does not display the server URL, raw response body, or
provider diagnostics. Developer logs may show request IDs and other product
diagnostics, but must not include credentials.

Backend integration verification requires a PostgreSQL service. Validate the
public response contract and migration in that environment before deployment;
frontend production builds and backend unit tests alone do not prove the
database migration has been applied.
