# PesaGuard

**Real-time M-Pesa reconciliation and anomaly detection for SACCOs, e-commerce operators, and small fintechs.**

PesaGuard watches your M-Pesa transaction flow as it happens, flags mismatches before they become disputes, and catches suspicious patterns before they become losses — so finance teams stop reconciling by hand and stop finding fraud after the money's gone.

---

## The Problem

Most SACCOs and small fintechs in East Africa reconcile M-Pesa transactions manually — cross-checking statements, spreadsheets, and internal records days after the fact. By the time a discrepancy is spotted, the transaction window for recovery has often closed, and fraud patterns (duplicate payments, phantom reversals, callback spoofing) go unnoticed until they've repeated dozens of times.

## What PesaGuard Does

- **Real-time reconciliation** — ingests M-Pesa (Daraja) callbacks and matches them against internal records as transactions happen, not at end-of-day.
- **Anomaly detection** — flags irregular patterns (duplicate transaction IDs, amount mismatches, timing anomalies, suspicious reversal sequences) using rule-based and statistical checks.
- **Webhook-first architecture** — built for idempotent, secure ingestion of Safaricom Daraja callbacks, so retried or duplicate webhooks never corrupt your ledger.
- **Alerting** — surfaces discrepancies to your team immediately, not at month-end audit.
- **Built for scale-down as much as scale-up** — designed to run affordably for a single SACCO branch or a growing fintech, not just enterprise volumes.

## Who It's For

- **SACCOs** reconciling member contributions and loan repayments via M-Pesa
- **E-commerce operators** processing high volumes of STK Push payments
- **Small fintechs** that need fraud visibility without building a data team

## Status

🚧 **MVP live with a pilot customer.** Currently in production hardening — focused on webhook idempotency, security, and reliability before wider rollout.

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | *(fill in: e.g. Django / FastAPI / Node.js)* |
| Transaction ingestion | Safaricom Daraja API (M-Pesa) |
| Deployment | Render (free-tier infrastructure) |
| Database | *(fill in: e.g. PostgreSQL)* |

*(Update the stack table with your actual services — happy to fill this in if you tell me the current setup.)*

## Getting Started

```bash
# Clone the repo
git clone https://github.com/Victor-Kipruto-Rop/pesaguard.git
cd pesaguard

# Install dependencies
# (add your install command here)

# Configure environment variables
cp .env.example .env
# Add your Daraja API credentials, database URL, etc.

# Run locally
# (add your run command here)
```

## Roadmap

- [x] Core reconciliation engine
- [x] MVP deployed with first pilot customer
- [ ] Webhook idempotency hardening
- [ ] Security audit (auth, secrets management, rate limiting)
- [ ] Multi-tenant support for additional SACCOs/fintechs
- [ ] Public dashboard for real-time transaction health

## Branding

PesaGuard uses a shield-and-"PG" mark in forest green — reflecting trust, security, and financial stewardship.

## Observability & Code Quality

PesaGuard ships with an optional Sentry-backed observability bootstrap and a Sourcery-enabled developer workflow.

Sentry:
- Production error tracking and exception monitoring
- Performance sampling and release/environment tagging
- Safe context enrichment and structured service tags

Sourcery:
- AI-powered code review on pull requests
- Repository-level quality configuration in `.sourcery.yaml`
- GitHub Actions workflow under `.github/workflows/sourcery.yml`

These integrations operate around the existing Flask and worker architecture and do not replace the repository’s business logic.

## License

MIT License — see [LICENSE](LICENSE) for details.

## Contact

Built by **Victor Kipruto Rop** ([DataForge](https://github.com/Victor-Kipruto-Rop)) — data engineer focused on East African fintech infrastructure.

For pilot inquiries or partnership questions, reach out via [GitHub](https://github.com/Victor-Kipruto-Rop).

## Public status service and email subscriptions

The status website consumes these public API routes:

- `GET /public/status` publishes the API/dependency health checks and HTTP
  reachability checks for the public website, dashboard, docs, and status site.
- `POST /public/status/subscriptions` starts a double-opt-in email subscription.
- `POST /public/status/subscriptions/confirm` confirms the email address.
- `POST /public/status/subscriptions/unsubscribe` removes a subscription using
  its signed link.
- `POST /public/status/monitor` compares the current status with confirmed
  subscribers' last-notified status and sends change emails. This endpoint
  requires a bearer token and is called by the scheduled GitHub Actions job.

For Docker Compose metrics scraping, set `PESAGUARD_PROMETHEUS_TOKEN_FILE` to
an operator-managed host file containing a high-entropy bearer token. The
Compose secret mounts it read-only into Prometheus and both API services;
Prometheus sends it when scraping `/metrics`. Use a different token per
environment, restrict file permissions, and never commit the token file.

### Production prerequisites

1. Deploy the backend code through the repository's
   [`backend-release.yml`](../.github/workflows/backend-release.yml) workflow. Its
   production job requires the protected `production` environment secrets
   `RENDER_API_KEY`, `RENDER_SERVICE_ID`, and `PRODUCTION_HEALTH_URL`. The
   `main` workflow deploys the same immutable image digest to staging and
   production, with staging health verification before production promotion.
2. Apply database migrations before enabling subscriptions. From the backend
   checkout root, with production `DATABASE_URL` configured, run:

   ```powershell
   python -m alembic -c pesaguard_backend_pipeline/alembic.ini upgrade head
   ```

   This creates `public_status_subscriptions` along with any other pending
   migrations. Use the normal backup and migration approval process for the
   production database.
3. Configure SMTP in the backend environment: `SMTP_HOST`, `SMTP_PORT`,
   `SMTP_FROM_EMAIL`, `SMTP_USE_TLS` (or `SMTP_USE_SSL`), and the paired
   `SMTP_USERNAME`/`SMTP_PASSWORD` when the provider requires authentication.
   The SMTP transport requires TLS.
4. Ensure `JWT_SECRET_KEY` is set to the existing production signing key; it
   also signs unsubscribe tokens. Do not rotate it solely for this feature.
5. Set `PESAGUARD_STATUS_MONITOR_TOKEN` to a newly generated random value of
   at least 32 characters in the backend environment. Set that exact value as
   the GitHub Actions repository secret with the same name. Never commit or
   paste the token into logs or chat.
6. Set `PESAGUARD_CORS_ALLOWED_ORIGINS` to include both
   `https://api.pesaguard.co.ke` and
   `https://status.pesaguard.victorkipruto.com` and
   `https://pesaguard.co.ke`. An explicit environment
   override replaces the default list, so retain any other origins required by
   deployed clients.
7. Enable the `Public status email monitor` workflow on the repository's
   default branch. It runs every five minutes and can also be run manually
   after configuration.

### Production verification

After deployment, verify without exposing secret values:

1. Confirm `GET https://api.pesaguard.co.ke/public/status` returns
   a valid `verified` payload and the expected service IDs. HTTP 503 is
   expected when the measured overall status is degraded or outage; inspect
   the payload rather than interpreting that code alone.
2. From the status-site origin, confirm the status GET and subscription
   preflight requests return `Access-Control-Allow-Origin` for the status site.
   From the public-site origin, confirm `GET /public/status` returns
   `Access-Control-Allow-Origin: https://pesaguard.co.ke`.
3. Submit a controlled test address, confirm the recipient gets the opt-in
   message, and follow its confirmation link. Do not use a real subscriber list
   for a smoke test.
4. Run the monitor workflow once and confirm it succeeds. The first successful
   monitor run establishes a baseline; it does not send a change notification.
   Verify unsubscribe with the controlled test subscription.
5. Check logs, then remove the test subscription and any test data.

Email updates are poll-based: delivery may take up to five minutes after a
change, and a short outage that begins and recovers between polls may not be
observed. The monitor only notifies confirmed subscribers after a status
fingerprint changes. Production delivery remains unverified until these checks
are completed against the deployed service.
