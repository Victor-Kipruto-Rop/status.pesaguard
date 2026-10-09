# PesaGuard Status (status.pesaguard.victorkipruto.com)

Static status page for PesaGuard. No build step: hand-written HTML plus vanilla
CSS/JS. The home page reads current API and dependency health from
`https://api.pesaguard.victorkipruto.com/public/status`.

The palette, typography and motion mirror
[docs.pesaguard.victorkipruto.com](https://docs.pesaguard.victorkipruto.com);
the favicon and colour tokens come from that site.

## Live status integration

The backend endpoint returns a sanitized status snapshot derived from its
database, Kafka, Redis, and Daraja health checks. It also checks HTTP
reachability for the public website, dashboard, documentation site, and status
site. These checks exclude internal error details and include the server-side
check time. The endpoint supports both normal responses and HTTP 503 responses
carrying a measured degraded/outage state. The browser refreshes the endpoint
automatically and renders failed requests as unavailable instead of treating
them as healthy.

The endpoint is public and read-only. Its CORS allowlist must include
`https://status.pesaguard.victorkipruto.com`; if
`PESAGUARD_CORS_ALLOWED_ORIGINS` is explicitly set in the backend environment,
include both that origin and the API's own origin in the comma-separated value.
The full production value must include
`https://api.pesaguard.victorkipruto.com,https://status.pesaguard.victorkipruto.com`.

The API does not currently publish public incident, maintenance, or historical
uptime records. Those sections remain unavailable/unknown and must not be
interpreted as an all-clear. No uptime history is derived from the current
health response.

### Verification contract

The API response declares its provenance and verification state:

```json
{
  "dataSource": "PesaGuard API health checks",
  "verified": true,
  "generatedAt": "server-generated UTC timestamp"
}
```

* A valid API response is rendered only when `verified: true`; dependency
  states are mapped from the backend's current health checks.
* HTTP 503 is accepted only when it carries a valid status payload, so real
  outages are displayed rather than discarded as network errors.
* If the API is unreachable, the page may read `data/status.json` only as an
  unverified fallback. It remains unknown and does not update the live
  `Last checked` timestamp.
* Static JSON files must stay unverified until backed by real measurements.
  Tests reject fabricated status, uptime, or timestamps.

## Preview

The page loads its header/footer via `fetch()`, so it needs a static server:

```powershell
npm run serve
# http://localhost:4174/
```

Browsing the files directly over `file://` shows page content but not the
shared header/footer.

## Validate

```powershell
npm run check
```

`npm run check` reads the data files and the page markup, and also executes
`js/status.js` against a mock DOM to assert the home page's honesty contract
(see `tests/home.test.js`). It is not a browser.

## Data files

| File | Purpose | Shape |
| --- | --- | --- |
| `data/status.json` | Unverified offline fallback | Same shape as the status payload; must not claim live health. |
| `api/status.json` | Static compatibility placeholder; not the browser's live endpoint | Same shape as the status payload. |
| `data/incidents.json` | Incident record | `{ version, dataSource, verified, note, incidents[] }` |
| `data/maintenance.json` | Maintenance windows | `{ version, dataSource, verified, note, maintenance[] }` |
| `data/uptime.json` | Uptime measurements | `{ version, dataSource, verified, note, overall, periods{}, services[], history[], recentDowntime[] }` |

Status values: `operational`, `degraded`, `outage`, `maintenance`, `unknown`.
Unknown is a first-class state and is what the page shows when nothing has been
measured.

## Home page behaviour

`js/status.js` renders the home page from the payloads above and owns these
contracts:

* The live status source is the backend's public `/public/status` endpoint.
  `#last-checked` changes only after a valid live API response; it stays blank
  when the page renders a static fallback.
* The payload's server-generated `lastUpdated` reaches the shared footer hook.
* A visible coverage notice distinguishes live API health from incident,
  maintenance, and uptime monitoring, which are not connected.
* `#autorefresh` carries `data-auto-refresh="30"`. The script reads the interval
  from the markup, pauses while the tab is hidden, and resumes on return.
* `#subscription-form` posts to the backend's public subscription endpoint.
  Subscriptions require double opt-in: users receive an email and must follow
  its confirmation link before status-change notifications are sent.
* Confirmation and signed unsubscribe links are handled by the status page and
  backend; the page displays the backend response rather than claiming an
  email was sent when delivery fails.
* An active incident id published in `status.json` is always surfaced with a
  link to the incident log, even when `incidents.json` holds no record for it.

## Structure

```
index.html  incidents.html  maintenance.html  uptime.html  404.html
css/    reset, variables, global, layout, components, status,
        incidents, maintenance, uptime, animations, responsive
js/     ui.js (helpers + component loader), app.js (chrome/behaviour),
        status.js, incidents.js, maintenance.js, uptime.js
data/   status.json, incidents.json, maintenance.json, uptime.json
api/    status.json (runtime endpoint)
components/  header, footer + reference markup templates for
             status-overview, service-list, incident-card,
             maintenance-card, uptime-card
assets/ logo/*.svg, icons/{operational,degraded,outage,maintenance}.svg
tests/  status, home, incidents, uptime, accessibility + run.js
```

`components/*.html` provides the shared chrome. The four card templates are
reference markup documenting the intended DOM; the page scripts build the same
structure from data.

## Accessibility and UX

`lang` on every page, skip link, landmark regions, keyboard-visible focus,
`prefers-reduced-motion`, a light-only theme (no dark variant, enforced by test), ARIA labels on
controls, `aria-label` on chart bars, and status conveyed by icon plus text plus
colour (never colour alone).

## Remaining gaps

* Email subscription code and tests are implemented, but production delivery
  requires deployment/configuration before it is live: apply the backend
  Alembic migration (`alembic -c pesaguard_backend_pipeline/alembic.ini upgrade
  head` from the repository root); configure `SMTP_HOST`, `SMTP_PORT`,
  `SMTP_FROM_EMAIL` (recommended sender: `no-reply@pesaguard.co.ke`), and TLS
  settings plus credentials when required; set
  `JWT_SECRET_KEY` and a random `PESAGUARD_STATUS_MONITOR_TOKEN` (at least 32
  characters) in the backend environment; add the same monitor token as the
  GitHub Actions repository secret; and deploy the backend and scheduled
  workflow. Ensure production `PESAGUARD_CORS_ALLOWED_ORIGINS` includes both
  API and status-site origins. Do not treat email delivery or production
  monitoring as verified until tested after deployment.
* The monitor workflow polls every five minutes. A status change can therefore
  take up to one polling interval to trigger an email, and a brief issue that
  starts and recovers between polls may not be observed. Notifications are
  sent only after a confirmed subscriber's status fingerprint changes.
* Public incident publishing, maintenance scheduling, and historical uptime
  collection are not yet wired to backend data sources; these remain
  unavailable rather than inferred.
* The status site still has no CSP. Its API status endpoint is subject to the
  backend's public API rate limit.
* `npm run check` validates data shape, page/component structure, SEO and the
  no-false-claims contract by reading files. It also runs `js/status.js` against
  a mock DOM (`tests/home.test.js`), including subscription, confirmation,
  manual refresh and timer refresh flows. This is not a browser and does not
  cover incidents.js, maintenance.js or uptime.js — a browser smoke test is
  therefore still required after front-end changes.
