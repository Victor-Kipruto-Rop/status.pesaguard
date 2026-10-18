# Platform status and incidents

`GET /api/v1/status` supplies public-safe component status, maintenance information, and active public incidents. `GET /api/v1/status/incidents` returns public incident history. Neither route returns dependency names, topology, tenant information, exception messages, internal update notes, or operator identities.

Incidents are created and maintained through `/internal/configuration/incidents`. Internal writes require `PLATFORM_CONFIG_WRITE`, a valid operator token, and a nonempty reason. Every create, state change, and update stores the operator identity, subject, and stated reason with the incident update. Read access requires `PLATFORM_CONFIG_READ`.

Send the action reason in `X-Operator-Reason`. It is request-scoped rather than encoded in the operator token, so one justification cannot silently carry over to a different customer action.

An incident may have public and private updates. The public routes return only incidents and updates marked public. Resolving an incident sets its resolution time; moving it back to an active state clears that time.
