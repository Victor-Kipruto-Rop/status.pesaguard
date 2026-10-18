# Developer data exports

Exports download CSV directly from the authenticated API. They use the same
tenant and permission-scoped query services as the corresponding portal pages;
the browser does not build exports from only the currently visible page.

| Export | Permission / scope | Limit and exclusions |
|---|---|---|
| Usage | `usage:read`, active organization, visible projects and optional project/environment filters | 5,000 rows; aggregated usage dimensions only |
| Request logs | `usage:read`, active organization and visible projects | 5,000 rows, maximum 31 days; no request bodies, response bodies, headers, or credentials |
| Webhook deliveries | `webhook:read`, active organization and visible projects | 5,000 rows; no webhook URL, signing secret, or event payload |
| Audit events | `audit:read`, active organization | 10,000 rows |
| Organization | Active membership required by the organization detail service | Identity and lifecycle fields only; excludes verification references and arbitrary metadata |

CSV cells are quoted and formula-leading values are escaped. Responses use
`Cache-Control: no-store`. The usage, request-log, and webhook-delivery downloads
return `X-Export-Truncated` when the row cap excludes matching rows.

The portal exposes usage, request-log, webhook-delivery, audit, and organization
exports from their relevant pages. The API contract is in
`backend/src/main/resources/static/openapi/pesaguard-developer-v1.yaml`.
