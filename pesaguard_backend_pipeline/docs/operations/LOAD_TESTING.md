# HTTP load testing

`operations/run_endpoint_load_test.py` drives a configured HTTP endpoint using
real HTTP requests. It supports bounded load, stress, spike, and soak profiles.
It measures response latency, HTTP status counts, transport failures, throttling,
and request throughput. It does not measure host resource use, database-pool
saturation, or production capacity by itself.

## Safety requirements

- Use a disposable tenant and a sandbox or isolated deployment.
- Pass `--confirm-test-tenant` for every run. The runner refuses traffic without
  this explicit confirmation.
- The runner accepts plain HTTP only for `localhost`, `127.0.0.1`, or `::1`.
  Other hosts require HTTPS and `--allow-nonlocal-target`.
- Put short-lived sandbox credentials in
  `PESAGUARD_LOAD_TEST_HEADERS_JSON`, a JSON object of header names and values.
  Do not put credentials in command-line arguments, payload fixtures, or reports.
- For Daraja callbacks, set the sandbox signing secret in an environment
  variable and pass its variable name with `--hmac-secret-env`. The runner signs
  each rendered JSON body with HMAC-SHA256 and places the hex or Base64 digest
  in the configured signature header.
- The runner does not follow redirects. A 3xx response is a functional failure,
  so credentials cannot be forwarded to a redirect target.
- Limits are 1,000 concurrent requests, 1,000,000 requests per phase, 20 stress
  levels, 24 hours per soak request, and 300 seconds per HTTP timeout. These are
  safety limits, not capacity recommendations.

`{{request_id}}`, `{{sequence}}`, `{{profile}}`, and `{{stage}}` are supported
payload and header placeholders. Use `{{request_id}}` for a unique event or
idempotency identifier only where the endpoint schema permits it. Supply a
schema-valid, non-customer sandbox fixture.

## Profiles and results

- `load` sends one phase at the configured concurrency.
- `stress` increases concurrency across the requested number of levels, up to
  the configured maximum. `--requests-per-stage` applies to every level.
- `spike` runs a baseline at one quarter of peak concurrency, a peak phase, and
  a recovery phase. Each phase uses `--requests-per-stage`.
- `soak` repeats fixed-size batches at the configured concurrency until at
  least `--duration-seconds` has elapsed. The final batch can extend the actual
  run beyond that duration.

Latency percentiles are upper bounds from a fixed histogram, not exact sample
percentiles. A run is diagnostic unless all four acceptance thresholds are
provided: `--max-error-rate`, `--max-throttle-rate`, `--max-p95-ms`, and
`--min-requests-per-second`. Error rate includes transport errors and HTTP 5xx
responses. HTTP 429 responses are measured separately. Other non-2xx responses
are functional failures. The tool does not supply approved product SLO values;
the service owner must set them before an acceptance run.

The JSON report includes the target origin, profile, phase measurements, and
status counts. It does not include request bodies, headers, or signing secrets.
Keep the report with the environment description, dataset size, resource
measurements, and the approved SLOs used to evaluate it.

## Example

Run from the backend project directory in PowerShell. Replace every placeholder
with a disposable sandbox endpoint, credentials, and schema-valid test fixture.
The values below do not represent approved PesaGuard SLOs.

```powershell
$env:PESAGUARD_LOAD_TEST_SIGNING_SECRET = "<short-lived sandbox secret>"
python -m operations.run_endpoint_load_test `
  --target-url "https://<approved-sandbox-host>/<webhook-path>" `
  --payload-template ".\sandbox-event-template.json" `
  --profile stress `
  --requests-per-stage 100 `
  --concurrency 20 `
  --stress-steps 4 `
  --duration-seconds 60 `
  --hmac-secret-env PESAGUARD_LOAD_TEST_SIGNING_SECRET `
  --signature-header X-MPESA-SIGNATURE `
  --signature-encoding hex `
  --max-error-rate <approved-error-rate> `
  --max-throttle-rate <approved-throttle-rate> `
  --max-p95-ms <approved-p95-limit-ms> `
  --min-requests-per-second <approved-throughput> `
  --confirm-test-tenant `
  --allow-nonlocal-target `
  --json-path ".\endpoint-load-validation.json"
```

The command performs no live production verification. Run it only after the
target owner approves the endpoint, tenant, request volume, and thresholds.
