# Contract Ownership Registry

> **Purpose:** Every event contract, Kafka topic, and external interface in PesaGuard
> must have a named owner, a clearly identified producing team, an explicit list of
> consumers, and a documented change-and-escalation process. This document is the
> single source of truth for those mappings.

This registry covers **all versioned event contracts** produced or consumed by the
PesaGuard platform. Contract definitions are authored in code:

- **`pesaguard_backend_pipeline/topics.py`** — the canonical `EVENT_TYPE_TOPICS`
  mapping that binds each logical event type to a physical Kafka/Redpanda topic.
- **`pesaguard_backend_pipeline/event_bus.py`** — the `EVENT_TYPES` registry and
  event envelope schema (`schema_version = "1.0"`, `event_version = 1`).
- **`pesaguard_backend_pipeline/event_consumer.py`** — `default_consumer_groups()`
  which assigns each event type to one or more consumer groups.

Each entry below follows the template:

```
<event.type>
        │
        ├── Producer:       <code module / service that emits the event>
        ├── Owner:          <contract owner (business)>
        ├── Technical Owner: <code owner (team responsible for the code)>
        ├── Responsible Team: <delivery team>
        ├── Approving Process: <who must sign off on changes>
        ├── Change Process:   <how changes are made>
        ├── Escalation:       <escalation path>
        ├── Consumers:
        │       ├── <Consumer A>
        │       └── <Consumer B>
        └── Compatibility:    <backward | forward | breaking-change-required>
```

**Annotation convention used in this registry:**

- `(consumer group: X)` — the consumer is a Kafka consumer group that actually
  subscribes to this event type in
  `event_consumer.default_consumer_groups()`. Only subscriptions verified against
  code carry this annotation.
- An **unannotated** consumer bullet is an intended or downstream consumer that
  reads the data through another path (database view, API, analytics export), or a
  planned Kafka consumer that is not yet registered. Unannotated consumers are not
  currently wired to the event stream.
- Routing coverage is complete: every event type in `event_bus.EVENT_TYPES` has an
  entry in `topics.EVENT_TYPE_TOPICS`, and every route target is a provisioned
  topic. Section 8 documents the routing model and the regression guard.


---

## 1. Transaction lifecycle events

### transaction.received
```
transaction.received
        │
        ├── Producer:       Webhook ingress (app.py → event_store.py → producer.py)
        ├── Owner:          Transaction Processing
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Payments & Ingestion
        ├── Approving Process: Data Platform Lead + Compliance Sign-off (schema changes)
        ├── Change Process:  Propose ADR, update infra/redpanda/schemas/transaction-event.json, run contract tests
        ├── Escalation:       #oncall-backend (PagerDuty) → Data Platform Lead (SLA 15 min P1)
        ├── Consumers:
        │       ├── Transaction Validation (consumer group: transaction-validation)
        │       ├── Fraud Detection (consumer group: fraud)
        │       ├── Reconciliation (consumer group: reconciliation)
        │       ├── Audit (consumer group: audit)
        └── Compatibility:   Backward — new optional fields only; never remove required fields
```

### transaction.validated
```
transaction.validated
        │
        ├── Producer:       Transaction Validation Service (validation pipeline)
        ├── Owner:          Transaction Processing
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Payments & Ingestion
        ├── Approving Process: Data Platform Lead + Compliance Sign-off
        ├── Change Process:  Propose ADR, update schema registry, run contract tests
        ├── Escalation:       #oncall-backend → Data Platform Lead (SLA 15 min P1)
        ├── Consumers:
        │       ├── Transaction Normalization (consumer group: transaction-normalization)
        │       ├── Fraud Detection (consumer group: fraud)
        │       ├── Reconciliation (consumer group: reconciliation)
        │       ├── Audit (consumer group: audit)
        └── Compatibility:   Backward — new optional fields only
```

### transaction.normalized
```
transaction.normalized
        │
        ├── Producer:       Normalization Service (provider-adaptor → canonical model)
        ├── Owner:          Data Platform
        ├── Technical Owner: Data Engineering
        ├── Responsible Team: Data Platform
        ├── Approving Process: Data Platform Lead + Compliance Sign-off
        ├── Change Process:  Propose ADR, update schema registry, run contract tests
        ├── Escalation:       #oncall-data-platform → Head of Data Platform (SLA 15 min P1)
        ├── Consumers:
        │       ├── Transaction Processors (consumer group: transaction-processors)
        │       ├── Fraud Detection
        │       ├── Reconciliation
        │       ├── Analytics
        │       ├── Audit (consumer group: audit)
        └── Compatibility:   Backward — canonical model evolves with ADR
```

### transaction.enriched
```
transaction.enriched
        │
        ├── Producer:       Enrichment Service (tenant, provider-account, merchant context)
        ├── Owner:          Data Platform
        ├── Technical Owner: Data Engineering
        ├── Responsible Team: Data Platform
        ├── Approving Process: Data Platform Lead + Risk Sign-off
        ├── Change Process:  Update enrichment contract, run data-quality tests
        ├── Escalation:       #oncall-data-platform → Head of Data Platform (SLA 30 min P1)
        ├── Consumers:
        │       ├── Transaction Processors
        │       ├── Fraud Detection
        │       ├── Analytics
        │       ├── Audit
        └── Compatibility:   Backward — enrichment fields are additive
```

### transaction.processed
```
transaction.processed
        │
        ├── Producer:       Transaction Processing Service (transaction-processors consumer group)
        ├── Owner:          Transaction Processing
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Payments & Ingestion
        ├── Approving Process: Data Platform Lead (+ Compliance for new fields)
        ├── Change Process:  Propose ADR if fields change, run contract tests
        ├── Escalation:       #oncall-backend → Data Platform Lead (SLA 15 min P1)
        ├── Consumers:
        │       ├── Audit (consumer group: audit)
        └── Compatibility:   Backward — new optional fields only
```

### transaction.reconciled
```
transaction.reconciled
        │
        ├── Producer:       Reconciliation Engine (reconciliation_engine.py)
        ├── Owner:          Reconciliation
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Reconciliation & Anomaly
        ├── Approving Process: Head of Reconciliation + Compliance Sign-off
        ├── Change Process:  Propose ADR if match logic changes, run reconciliation tests
        ├── Escalation:       #oncall-reconciliation → Head of Reconciliation (SLA 15 min P1)
        ├── Consumers:
        │       ├── Reconciliation (consumer group: reconciliation)
        │       ├── Alerts
        │       ├── Analytics
        │       ├── Audit (consumer group: audit)
        └── Compatibility:   Backward — classification changes require Compliance sign-off
```

### transaction.exception_created
```
transaction.exception_created
        │
        ├── Producer:       Reconciliation Engine (discrepancy/anomaly detection)
        ├── Owner:          Reconciliation
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Reconciliation & Anomaly
        ├── Approving Process: Head of Reconciliation + Risk Sign-off
        ├── Change Process:  Update discrepancy model, run anomaly-detection tests
        ├── Escalation:       #oncall-reconciliation → Head of Reconciliation (SLA 15 min P1)
        ├── Consumers:
        │       ├── Alerts (consumer group: alerts)
        │       ├── Reconciliation (consumer group: reconciliation)
        │       ├── Audit (consumer group: audit)
        └── Compatibility:   Backward — severity/owner fields are additive
```

### transaction.fraud_detected
```
transaction.fraud_detected
        │
        ├── Producer:       Fraud Detection Service (fraud_scoring.py)
        ├── Owner:          Fraud Prevention
        ├── Technical Owner: Security Engineering
        ├── Responsible Team: Fraud & Security
        ├── Approving Process: Head of Fraud + CISO Sign-off
        ├── Change Process:  Propose ADR, update fraud model, run fraud-scenario tests
        ├── Escalation:       #oncall-fraud → Head of Fraud (SLA 5 min P1) → CISO
        ├── Consumers:
        │       ├── Fraud (consumer group: fraud)
        │       ├── Alerts (consumer group: alerts)
        │       ├── Reconciliation
        │       ├── Audit (consumer group: audit)
        └── Compatibility:   Backward — risk-score/threshold changes require Fraud + CISO sign-off
```

### transaction.rejected
```
transaction.rejected
        │
        ├── Producer:       Validation Service (validation boundary)
        ├── Owner:          Transaction Processing
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Payments & Ingestion
        ├── Approving Process: Data Platform Lead + Compliance Sign-off
        ├── Change Process:  Update validation rules, run rejection-path tests
        ├── Escalation:       #oncall-backend → Data Platform Lead (SLA 15 min P1)
        ├── Consumers:
        │       ├── Audit
        │       ├── Analytics (failure metrics)
        └── Compatibility:   Backward — error_code/error_message may be added
```

### transaction.completed
```
transaction.completed
        │
        ├── Producer:       Transaction Lifecycle Manager (lifecycle.py)
        ├── Owner:          Transaction Processing
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Payments & Ingestion
        ├── Approving Process: Data Platform Lead + Compliance Sign-off
        ├── Change Process:  Update lifecycle transitions, run lifecycle tests
        ├── Escalation:       #oncall-backend → Data Platform Lead (SLA 15 min P1)
        ├── Consumers:
        │       ├── Reconciliation
        │       ├── Analytics
        │       ├── Audit
        └── Compatibility:   Backward — terminal states only, no field removal
```

### transaction.failed
```
transaction.failed
        │
        ├── Producer:       Transaction Lifecycle Manager (lifecycle.py)
        ├── Owner:          Transaction Processing
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Payments & Ingestion
        ├── Approving Process: Data Platform Lead + Compliance Sign-off
        ├── Change Process:  Update failure classification, run lifecycle tests
        ├── Escalation:       #oncall-backend → Data Platform Lead (SLA 15 min P1)
        ├── Consumers:
        │       ├── Alerts
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — new failure_reason may be added
```

## 2. Reconciliation events

### reconciliation.requested
```
reconciliation.requested
        │
        ├── Producer:       Reconciliation Service (reconciliation_engine.py)
        ├── Owner:          Reconciliation
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Reconciliation & Anomaly
        ├── Approving Process: Head of Reconciliation + Compliance Sign-off
        ├── Change Process:  Update reconciliation request model, run reconciliation tests
        ├── Escalation:       #oncall-reconciliation → Head of Reconciliation (SLA 15 min P1)
        ├── Consumers:
        │       ├── Reconciliation
        │       ├── Analytics
        │       ├── Audit
        └── Compatibility:   Backward — request parameters are additive
```

### reconciliation.completed
```
reconciliation.completed
        │
        ├── Producer:       Reconciliation Engine (reconciliation_engine.py)
        ├── Owner:          Reconciliation
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Reconciliation & Anomaly
        ├── Approving Process: Head of Reconciliation + Compliance Sign-off
        ├── Change Process:  Propose ADR if match logic changes, run reconciliation tests
        ├── Escalation:       #oncall-reconciliation → Head of Reconciliation (SLA 15 min P1)
        ├── Consumers:
        │       ├── Reconciliation
        │       ├── Analytics
        │       ├── Audit
        └── Compatibility:   Backward — match classification changes require Compliance sign-off
```

### reconciliation.exception
```
reconciliation.exception
        │
        ├── Producer:       Reconciliation Engine (discrepancy classification)
        ├── Owner:          Reconciliation
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Reconciliation & Anomaly
        ├── Approving Process: Head of Reconciliation + Risk Sign-off
        ├── Change Process:  Update discrepancy model, run reconciliation-exception tests
        ├── Escalation:       #oncall-reconciliation → Head of Reconciliation (SLA 15 min P1)
        ├── Consumers:
        │       ├── Alerts
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — severity and owner fields are additive
```

### reconciliation.started
```
reconciliation.started
        │
        ├── Producer:       Reconciliation Engine (reconciliation_engine.py)
        ├── Owner:          Reconciliation
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Reconciliation & Anomaly
        ├── Approving Process: Head of Reconciliation
        ├── Change Process:  Update reconciliation lifecycle events, run reconciliation tests
        ├── Escalation:       #oncall-reconciliation → Head of Reconciliation (SLA 30 min P2)
        ├── Consumers:
        │       ├── Analytics
        │       ├── Audit
        └── Compatibility:   Backward — timing and performance metadata is additive
```

### reconciliation.failed
```
reconciliation.failed
        │
        ├── Producer:       Reconciliation Engine (fatal reconciliation error)
        ├── Owner:          Reconciliation
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Reconciliation & Anomaly
        ├── Approving Process: Head of Reconciliation + Compliance Sign-off
        ├── Change Process:  Update failure classification, run reconciliation-failure tests
        ├── Escalation:       #oncall-reconciliation → Head of Reconciliation (SLA 15 min P1)
        ├── Consumers:
        │       ├── Alerts
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — error details are additive
```

### reconciliation.exception.detected
```
reconciliation.exception.detected
        │
        ├── Producer:       Reconciliation Engine (new exception found)
        ├── Owner:          Reconciliation
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Reconciliation & Anomaly
        ├── Approving Process: Head of Reconciliation + Risk Sign-off
        ├── Change Process:  Update exception model, run exception-detection tests
        ├── Escalation:       #oncall-reconciliation → Head of Reconciliation (SLA 15 min P1)
        ├── Consumers:
        │       ├── Alerts
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — exception metadata is additive
```

### reconciliation.exception.resolved
```
reconciliation.exception.resolved
        │
        ├── Producer:       Reconciliation Engine (exception cleared)
        ├── Owner:          Reconciliation
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Reconciliation & Anomaly
        ├── Approving Process: Head of Reconciliation + Compliance Sign-off
        ├── Change Process:  Update resolution model, run reconciliation tests
        ├── Escalation:       #oncall-reconciliation → Head of Reconciliation (SLA 30 min P2)
        ├── Consumers:
        │       ├── Alerts
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — resolution metadata is additive
```

## 3. Fraud detection events

### fraud.analysis_requested
```
fraud.analysis_requested
        │
        ├── Producer:       Fraud Detection Service (fraud_scoring.py)
        ├── Owner:          Fraud Prevention
        ├── Technical Owner: Security Engineering
        ├── Responsible Team: Fraud & Security
        ├── Approving Process: Head of Fraud + CISO Sign-off
        ├── Change Process:  Update fraud analysis request model, run fraud tests
        ├── Escalation:       #oncall-fraud → Head of Fraud (SLA 5 min P1)
        ├── Consumers:
        │       ├── Fraud
        │       ├── Audit
        └── Compatibility:   Backward — new detection signals are additive
```

### fraud.analysis.started
```
fraud.analysis.started
        │
        ├── Producer:       Fraud Analysis Worker
        ├── Owner:          Fraud Prevention
        ├── Technical Owner: Security Engineering
        ├── Responsible Team: Fraud & Security
        ├── Approving Process: Head of Fraud
        ├── Change Process:  Update analysis lifecycle, run fraud tests
        ├── Escalation:       #oncall-fraud → Head of Fraud (SLA 30 min P2)
        ├── Consumers:
        │       ├── Analytics
        │       ├── Audit
        └── Compatibility:   Backward — analysis metadata is additive
```

### fraud.analysis.completed
```
fraud.analysis.completed
        │
        ├── Producer:       Fraud Analysis Worker
        ├── Owner:          Fraud Prevention
        ├── Technical Owner: Security Engineering
        ├── Responsible Team: Fraud & Security
        ├── Approving Process: Head of Fraud + CISO Sign-off
        ├── Change Process:  Update analysis completion model, run fraud tests
        ├── Escalation:       #oncall-fraud → Head of Fraud (SLA 5 min P1)
        ├── Consumers:
        │       ├── Analytics
        │       ├── Audit
        └── Compatibility:   Backward — model-version and score fields are additive
```

### fraud.anomaly_detected
```
fraud.anomaly_detected
        │
        ├── Producer:       Anomaly Detection Service (fraud_scoring.py)
        ├── Owner:          Fraud Prevention
        ├── Technical Owner: Security Engineering
        ├── Responsible Team: Fraud & Security
        ├── Approving Process: Head of Fraud + CISO Sign-off
        ├── Change Process:  Update anomaly model, run anomaly-detection tests
        ├── Escalation:       #oncall-fraud → Head of Fraud (SLA 5 min P1) → CISO
        ├── Consumers:
        │       ├── Fraud
        │       ├── Alerts
        │       ├── Analytics
        │       ├── Audit
        └── Compatibility:   Backward — anomaly-scoring changes require Fraud + CISO sign-off
```

### fraud.decision_created
```
fraud.decision_created
        │
        ├── Producer:       Fraud Decision Engine (fraud_decision_service.py)
        ├── Owner:          Fraud Prevention
        ├── Technical Owner: Security Engineering
        ├── Responsible Team: Fraud & Security
        ├── Approving Process: Head of Fraud + CISO + Legal Sign-off
        ├── Change Process:  Update fraud decision model, run fraud-decision tests
        ├── Escalation:       #oncall-fraud → Head of Fraud (SLA 5 min P1) → CISO → Legal
        ├── Consumers:
        │       ├── Fraud
        │       ├── Alerts
        │       ├── Reconciliation
        │       ├── Audit
        └── Compatibility:   Backward — decision logic changes require Fraud + CISO + Legal sign-off
```

### fraud.anomaly.reviewed
```
fraud.anomaly.reviewed
        │
        ├── Producer:       Fraud Analyst Interface (manual review)
        ├── Owner:          Fraud Prevention
        ├── Technical Owner: Security Engineering
        ├── Responsible Team: Fraud & Security
        ├── Approving Process: Head of Fraud + CISO Sign-off
        ├── Change Process:  Update review model, run review-workflow tests
        ├── Escalation:       #oncall-fraud → Head of Fraud (SLA 30 min P2)
        ├── Consumers:
        │       ├── Alerts
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — review-status values may be extended
```

## 4. Notification events

### notification.requested
```
notification.requested
        │
        ├── Producer:       Alerting Service (alerting_service.py)
        ├── Owner:          Notifications
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Communications
        ├── Approving Process: Head of Communications + Compliance Sign-off
        ├── Change Process:  Update notification model, run notification-tests
        ├── Escalation:       #oncall-communications → Head of Communications (SLA 30 min P1)
        ├── Consumers:
        │       ├── Alerts (consumer group: alerts)
        │       ├── Audit (consumer group: audit)
        │       ├── Notification Workers
        └── Compatibility:   Backward — channel and template fields are additive
```

### notification.sent
```
notification.sent
        │
        ├── Producer:       Notification Delivery Worker (africas_talking.py, notifier.py)
        ├── Owner:          Notifications
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Communications
        ├── Approving Process: Head of Communications + Compliance Sign-off
        ├── Change Process:  Update delivery report model, run delivery-tests
        ├── Escalation:       #oncall-communications → Head of Communications (SLA 30 min P1)
        ├── Consumers:
        │       ├── Audit
        │       ├── Analytics
        │       ├── Delivery Analytics
        └── Compatibility:   Backward — provider-reference fields are additive
```

### notification.failed
```
notification.failed
        │
        ├── Producer:       Notification Delivery Worker (africas_talking.py, notifier.py)
        ├── Owner:          Notifications
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Communications
        ├── Approving Process: Head of Communications + Risk Sign-off
        ├── Change Process:  Update failure model, run delivery-failure tests
        ├── Escalation:       #oncall-communications → Head of Communications (SLA 30 min P1)
        ├── Consumers:
        │       ├── Alerts
        │       ├── Audit
        │       ├── Analytics
        │       ├── Delivery Analytics
        └── Compatibility:   Backward — error-code and retry fields are additive
```

### notification.retry
```
notification.retry
        │
        ├── Producer:       Notification Retry Worker
        ├── Owner:          Notifications
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Communications
        ├── Approving Process: Head of Communications
        ├── Change Process:  Update retry policy, run retry tests
        ├── Escalation:       #oncall-communications → Head of Communications (SLA 1 h P2)
        ├── Consumers:
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — retry-attempt metadata is additive
```

### notification.exhausted
```
notification.exhausted
        │
        ├── Producer:       Notification Retry Worker (max retries exceeded)
        ├── Owner:          Notifications
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Communications
        ├── Approving Process: Head of Communications + Risk Sign-off
        ├── Change Process:  Update exhaustion policy, run retry tests
        ├── Escalation:       #oncall-communications → Head of Communications (SLA 30 min P1)
        ├── Consumers:
        │       ├── Alerts
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — final failure metadata is additive
```

## 5. Webhook ingestion and audit events

### webhook.received
```
webhook.received
        │
        ├── Producer:       Webhook ingress (app.py webhook endpoint)
        ├── Owner:          Transaction Processing
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Payments & Ingestion
        ├── Approving Process: Data Platform Lead + Security Lead Sign-off
        ├── Change Process:  Update webhook envelope model, run webhook-security tests
        ├── Escalation:       #oncall-backend → Data Platform Lead (SLA 15 min P1)
        ├── Consumers:
        │       ├── Fraud Detection
        │       ├── Reconciliation
        │       ├── Audit
        │       ├── Webhook Processing Workers
        └── Compatibility:   Backward — raw payload metadata is additive
```

### audit.event.created
```
audit.event.created
        │
        ├── Producer:       Audit Service (action_audit.py)
        ├── Owner:          Compliance & Audit
        ├── Technical Owner: Security Engineering
        ├── Responsible Team: Security & Compliance
        ├── Approving Process: CISO + Head of Compliance Sign-off
        ├── Change Process:  Propose ADR, update audit schema, run audit-integrity tests
        ├── Escalation:       #oncall-security → CISO (SLA 15 min P1)
        ├── Consumers:
        │       ├── Audit
        │       ├── Analytics
        │       ├── Compliance Reporting
        └── Compatibility:   Backward — audit fields are append-only
```

## 6. Batch import events

### batch_import.received
```
batch_import.received
        │
        ├── Producer:       Batch Import Service (batch_import_service.py)
        ├── Owner:          Data Platform
        ├── Technical Owner: Data Engineering
        ├── Responsible Team: Data Platform
        ├── Approving Process: Data Platform Lead + Compliance Sign-off
        ├── Change Process:  Update import envelope, run batch-import tests
        ├── Escalation:       #oncall-data-platform → Head of Data Platform (SLA 30 min P1)
        ├── Consumers:
        │       ├── Batch Import Workers
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — source-file metadata is additive
```

### batch_import.completed
```
batch_import.completed
        │
        ├── Producer:       Batch Import Worker
        ├── Owner:          Data Platform
        ├── Technical Owner: Data Engineering
        ├── Responsible Team: Data Platform
        ├── Approving Process: Data Platform Lead + Compliance Sign-off
        ├── Change Process:  Update completion model, run batch-import tests
        ├── Escalation:       #oncall-data-platform → Head of Data Platform (SLA 30 min P1)
        ├── Consumers:
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — record-count and timing fields are additive
```

### batch_import.failed
```
batch_import.failed
        │
        ├── Producer:       Batch Import Worker (fatal import error)
        ├── Owner:          Data Platform
        ├── Technical Owner: Data Engineering
        ├── Responsible Team: Data Platform
        ├── Approving Process: Data Platform Lead + Risk Sign-off
        ├── Change Process:  Update failure model, run batch-import-failure tests
        ├── Escalation:       #oncall-data-platform → Head of Data Platform (SLA 30 min P1)
        ├── Consumers:
        │       ├── Alerts
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — error details are additive
```

### batch.import.started
```
batch.import.started
        │
        ├── Producer:       Batch Import Worker
        ├── Owner:          Data Platform
        ├── Technical Owner: Data Engineering
        ├── Responsible Team: Data Platform
        ├── Approving Process: Data Platform Lead
        ├── Change Process:  Update import lifecycle, run batch-import tests
        ├── Escalation:       #oncall-data-platform → Head of Data Platform (SLA 1 h P2)
        ├── Consumers:
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — job metadata is additive
```

### batch.import.completed
```
batch.import.completed
        │
        ├── Producer:       Batch Import Worker
        ├── Owner:          Data Platform
        ├── Technical Owner: Data Engineering
        ├── Responsible Team: Data Platform
        ├── Approving Process: Data Platform Lead + Compliance Sign-off
        ├── Change Process:  Update completion model, run batch-import tests
        ├── Escalation:       #oncall-data-platform → Head of Data Platform (SLA 30 min P1)
        ├── Consumers:
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — record-count and timing fields are additive
```

### batch.import.failed
```
batch.import.failed
        │
        ├── Producer:       Batch Import Worker
        ├── Owner:          Data Platform
        ├── Technical Owner: Data Engineering
        ├── Responsible Team: Data Platform
        ├── Approving Process: Data Platform Lead + Risk Sign-off
        ├── Change Process:  Update failure model, run batch-import-failure tests
        ├── Escalation:       #oncall-data-platform → Head of Data Platform (SLA 30 min P1)
        ├── Consumers:
        │       ├── Alerts
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — error details are additive
```

### batch.record.rejected
```
batch.record.rejected
        │
        ├── Producer:       Batch Import Worker (individual record failure)
        ├── Owner:          Data Platform
        ├── Technical Owner: Data Engineering
        ├── Responsible Team: Data Platform
        ├── Approving Process: Data Platform Lead + Compliance Sign-off
        ├── Change Process:  Update rejection model, run batch-import tests
        ├── Escalation:       #oncall-data-platform → Head of Data Platform (SLA 30 min P1)
        ├── Consumers:
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — rejection-reason fields are additive
```

> **Note:** `batch.import.completed` and `batch.import.failed` (dotted forms) are
> aliases of `batch_import.completed` / `batch_import.failed` and share the same
> ownership, consumers, and compatibility rules as their underscore counterparts.

## 7. System and infrastructure events

### event.retry_scheduled
```
event.retry_scheduled
        │
        ├── Producer:       Event Delivery Controller (event_bus.py retry queue)
        ├── Owner:          Platform Reliability
        ├── Technical Owner: Backend Engineering
        ├── Responsible Team: Platform Engineering
        ├── Approving Process: Head of Platform Engineering
        ├── Change Process:  Update retry policy, run retry-queue tests
        ├── Escalation:       #oncall-platform → Head of Platform Engineering (SLA 30 min P2)
        ├── Consumers:
        │       ├── Retry Workers
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — retry-at metadata is additive
```

### system.event
```
system.event
        │
        ├── Producer:       System Health Monitor (health.py, observability.py)
        ├── Owner:          Platform Reliability
        ├── Technical Owner: Platform Engineering
        ├── Responsible Team: Platform Engineering
        ├── Approving Process: Head of Platform Engineering
        ├── Change Process:  Update system-event model, run health tests
        ├── Escalation:       #oncall-platform → Head of Platform Engineering (SLA 30 min P2)
        ├── Consumers:
        │       ├── System Monitors
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — system-metadata is additive
```

### data_processing.event
```
data_processing.event
        │
        ├── Producer:       Data Processing Pipeline
        ├── Owner:          Data Platform
        ├── Technical Owner: Data Engineering
        ├── Responsible Team: Data Platform
        ├── Approving Process: Data Platform Lead
        ├── Change Process:  Update processing model, run data-quality tests
        ├── Escalation:       #oncall-data-platform → Head of Data Platform (SLA 30 min P2)
        ├── Consumers:
        │       ├── Audit
        │       ├── Analytics
        └── Compatibility:   Backward — processing-metadata is additive
```


## 8. Routing coverage, consumer groups, and gaps

### 8.1 Shared-topic and alias mappings

`topics.EVENT_TYPE_TOPICS` contains one true alias, where two event types share a
single topic for the same semantic event:

| Alias event type | Canonical contract | Notes |
|---|---|---|
| `transaction.created` | `transaction.received` | Both explicitly map to `pesaguard.transactions.raw` in `EVENT_TYPE_TOPICS`. Ownership, consumers, and compatibility follow `transaction.received`. |

Other event types share a physical topic because they describe one lifecycle of the
same underlying object, while remaining distinct contracts:

| Shared topic | Event types routed to it | Rationale |
|---|---|---|
| `pesaguard.reconciliation.requested` | `reconciliation.requested`, `reconciliation.started`, `reconciliation.failed` | All three describe one reconciliation *run*; results use separate topics. |
| `pesaguard.reconciliation.exceptions` | `reconciliation.exception`, `reconciliation.exception.detected`, `reconciliation.exception.resolved`, `transaction.exception_created` | All describe a discrepancy and its lifecycle. |
| `pesaguard.fraud.analysis` | `fraud.analysis_requested`, `fraud.analysis.requested`, `fraud.analysis.started`, `fraud.analysis.completed` | Fraud analysis lifecycle. |
| `pesaguard.fraud.anomalies` | `fraud.anomaly_detected`, `fraud.anomaly.detected`, `fraud.anomaly.reviewed` | Fraud anomaly lifecycle. |
| `pesaguard.notifications.failed` | `notification.failed`, `notification.retry`, `notification.exhausted` | Unsuccessful-delivery lifecycle. |
| `pesaguard.batch.imports` | `batch_import.received`, `batch.import.started` | Batch import run lifecycle. |
| `pesaguard.batch.imports.completed` | `batch_import.completed`, `batch.import.completed` | Batch import success. |
| `pesaguard.batch.imports.failed` | `batch_import.failed`, `batch.import.failed`, `batch.record.rejected` | Batch import failure, including per-record rejection. |

### 8.2 Routing coverage

`event_bus.EVENT_TYPES` is the **validation** registry; `topics.EVENT_TYPE_TOPICS`
is the **routing** registry. `producer.publish_versioned_event()` performs a direct
lookup (`EVENT_TYPE_TOPICS[event.event_type]`), so any registered event type without
a route would validate and then raise `KeyError` at publish time.

| Registry | Count |
|---|---|
| `EVENT_TYPES` (validated) | 44 |
| `EVENT_TYPE_TOPICS` (routed) | 44 |
| **Validated but unrouted** | **0** |
| Route targets not provisioned in `TOPIC_SPECIFICATIONS` | 0 |
| Route targets absent from `ALL_TOPICS` | 0 |

**Status: RESOLVED and TESTED.** The 19 previously unrouted event types now have
routes. Three new physical topics were created for the terminal transaction
outcomes (`pesaguard.transactions.completed`, `.failed`, `.rejected`); the remaining
16 event types were routed onto their existing family topic.

Terminal transaction outcomes use dedicated topics so that a consumer of a stage
topic (`raw`/`validated`/`normalized`/`enriched`/`processed`) can never receive a
terminal outcome event, and so rejected/failed records remain independently
replayable for audit and dispute investigation.

This invariant is enforced by regression tests in
`tests/test_contract_testing.py`:

| Test | Invariant |
|---|---|
| `test_every_registered_event_type_has_a_topic_route` | `EVENT_TYPES ⊆ EVENT_TYPE_TOPICS` |
| `test_every_event_route_targets_a_provisioned_topic` | Every route target is in `TOPIC_SPECIFICATIONS` and `ALL_TOPICS` |
| `test_publish_versioned_event_routes_every_registered_event_type` | Every registered event type publishes without `KeyError` |
| `test_terminal_transaction_outcomes_use_dedicated_topics` | Stage topics and terminal-outcome topics are disjoint |
| `test_reconciliation_run_lifecycle_shares_the_requested_topic` | Run lifecycle grouping holds |
| `test_notification_delivery_problems_share_the_failed_topic` | Delivery-problem grouping holds |

### 8.3 Normalization rules

1. Producers must emit only event types present in `EVENT_TYPE_TOPICS`.
2. Adding an event type to `EVENT_TYPES` requires adding a corresponding route to
   `EVENT_TYPE_TOPICS` in the same change, or the type must not be added.
3. Consumers registered in `default_consumer_groups()` receive events only when the
   group's `event_types` frozenset lists them explicitly; there is no implicit
   alias routing.
4. Retiring an alias or an event type requires the standard breaking-change process
   (new `event_version` + documented migration window).

### 8.4 Registered consumer groups (verified)

Generated from `event_consumer.default_consumer_groups()`. This is the complete set
of currently wired Kafka consumer groups; there are seven.

| Consumer group | Subscribed event types | Owning team |
|---|---|---|
| `transaction-validation` | `transaction.received` | Payments & Ingestion |
| `transaction-normalization` | `transaction.validated` | Payments & Ingestion |
| `transaction-processors` | `transaction.normalized` | Data Platform |
| `fraud` | `transaction.received`, `transaction.validated`, `transaction.fraud_detected` | Fraud & Security |
| `reconciliation` | `transaction.received`, `transaction.validated`, `transaction.reconciled`, `transaction.exception_created` | Reconciliation & Anomaly |
| `audit` | `transaction.received`, `transaction.validated`, `transaction.normalized`, `transaction.processed`, `transaction.reconciled`, `transaction.exception_created`, `transaction.fraud_detected`, `notification.requested` | Security & Compliance |
| `alerts` | `transaction.exception_created`, `transaction.fraud_detected`, `notification.requested` | Communications |

Inverted view — event types with at least one registered consumer group:

| Event type | Registered consumer groups |
|---|---|
| `transaction.received` | `transaction-validation`, `fraud`, `reconciliation`, `audit` |
| `transaction.validated` | `transaction-normalization`, `fraud`, `reconciliation`, `audit` |
| `transaction.normalized` | `transaction-processors`, `audit` |
| `transaction.processed` | `audit` |
| `transaction.reconciled` | `reconciliation`, `audit` |
| `transaction.exception_created` | `reconciliation`, `alerts`, `audit` |
| `transaction.fraud_detected` | `fraud`, `alerts`, `audit` |
| `notification.requested` | `alerts`, `audit` |

All other event types in this registry currently have **no registered Kafka
consumer group**. They remain documented here because they define contracts and
ownership obligations; a consumer must be registered in
`default_consumer_groups()` before any team relies on them in production.

> **Governance impact:** adding a consumer group that subscribes to a contract in
> this registry is a change to the contract's consumer surface. The owning team of
> that contract must be notified, and the new group must be added to
> `default_consumer_groups()` and to section 8.4 in the same change.




## 9. Kafka topic registry (physical topics)

The following physical Kafka/Redpanda topics are defined in `topics.py` and
provisioned through `provision_topics()`. Retention values reflect
`TOPIC_SPECIFICATIONS`; partition counts reflect the per-topic environment
overrides.

| Topic name | Governed by contract(s) | Retention | Partitions | Owning team |
|---|---|---|---|---|
| `pesaguard.transactions.raw` | `transaction.received`, `transaction.created`, `webhook.received` | 7 days | 6 | Payments & Ingestion |
| `pesaguard.transactions.validated` | `transaction.validated` | 7 days | 6 | Payments & Ingestion |
| `pesaguard.transactions.normalized` | `transaction.normalized` | 7 days | 6 | Data Platform |
| `pesaguard.transactions.enriched` | `transaction.enriched` | 7 days | 6 | Data Platform |
| `pesaguard.transactions.processed` | `transaction.processed` | 7 days | 6 | Payments & Ingestion |
| `pesaguard.transactions.completed` | `transaction.completed` | 90 days | 6 | Payments & Ingestion |
| `pesaguard.transactions.failed` | `transaction.failed` | 90 days | 3 | Payments & Ingestion |
| `pesaguard.transactions.rejected` | `transaction.rejected` | 90 days | 3 | Payments & Ingestion |
| `pesaguard.reconciliation.completed` | `transaction.reconciled`, `reconciliation.completed` | 30 days | 3 | Reconciliation & Anomaly |
| `pesaguard.reconciliation.exceptions` | `transaction.exception_created`, `reconciliation.exception`, `reconciliation.exception.detected`, `reconciliation.exception.resolved` | 30 days | 6 | Reconciliation & Anomaly |
| `pesaguard.reconciliation.requested` | `reconciliation.requested`, `reconciliation.started`, `reconciliation.failed` | 7 days | 6 | Reconciliation & Anomaly |
| `pesaguard.dlq` | Dead-letter fallback for all topics | 30 days | 3 | Platform Engineering |
| `pesaguard.audit.events` | `audit.event.created` | 1 year | 3 | Security & Compliance |
| `pesaguard.audit.communication` | Communication audit trail | 1 year | 3 | Security & Compliance |
| `pesaguard.notifications.requested` | `notification.requested` | 7 days | 6 | Communications |
| `pesaguard.notifications.sent` | `notification.sent` | 30 days | 3 | Communications |
| `pesaguard.notifications.failed` | `notification.failed`, `notification.retry`, `notification.exhausted` | 30 days | 3 | Communications |
| `pesaguard.webhooks.received` | `webhook.received` | 7 days | 6 | Payments & Ingestion |
| `pesaguard.fraud.analysis` | `fraud.analysis_requested`, `fraud.analysis.requested`, `fraud.analysis.started`, `fraud.analysis.completed` | 7 days | 6 | Fraud & Security |
| `pesaguard.fraud.anomalies` | `fraud.anomaly_detected`, `fraud.anomaly.detected`, `fraud.anomaly.reviewed` | 30 days | 3 | Fraud & Security |
| `pesaguard.fraud.decisions` | `fraud.decision_created`, `transaction.fraud_detected` | 30 days | 3 | Fraud & Security |
| `pesaguard.batch.imports` | `batch_import.received`, `batch.import.started` | 7 days | 6 | Data Platform |
| `pesaguard.batch.imports.completed` | `batch_import.completed`, `batch.import.completed` | 30 days | 3 | Data Platform |
| `pesaguard.batch.imports.failed` | `batch_import.failed`, `batch.import.failed`, `batch.record.rejected` | 30 days | 3 | Data Platform |
| `pesaguard.retries` | `event.retry_scheduled` | 30 days | 3 | Platform Engineering |
| `pesaguard.system.events` | `system.event` | 30 days | 3 | Platform Engineering |
| `pesaguard.data.processing` | `data_processing.event` | 30 days | 3 | Data Platform |

> **Topic change procedure:** Any change to topic configuration (partitions,
> retention, replication factor) must be reviewed by the owning team,
> provisioned through `provision_topics()`, and reflected in
> `infra/redpanda/topics.yaml` in the same change. The contract tests assert that
> every topic in the YAML inventory has identical partitions and retention in
> `TOPIC_SPECIFICATIONS` (code is authoritative for `provision_topics()` runtime
> behavior). Increasing partitions is a breaking change for keyed consumers and
> requires a migration plan with an explicit consumer-rebalance window.
> Partition-key semantics are defined in `partitioning.py` (`event_partition_key`).


## 10. Change approval matrix

| Contract domain | Change requires approval from |
|---|---|
| Transaction lifecycle (`received` → `completed`/`failed`) | Data Platform Lead + Compliance |
| Reconciliation matches / exceptions | Head of Reconciliation + Compliance |
| Fraud scoring, anomalies, decisions | Head of Fraud + CISO (+ Legal for decisions) |
| Notifications | Head of Communications + Compliance |
| Audit events | CISO + Head of Compliance |
| Webhooks (ingress) | Data Platform Lead + Security Lead |
| Batch imports | Data Platform Lead + Compliance |
| System / infrastructure events | Head of Platform Engineering |
| Data processing | Data Platform Lead |
| Schema field removal (any domain) | Domain owner + CISO (security review) |
| Kafka topic configuration | Owning team lead + Head of Platform Engineering |

## 11. Ownership directory

| Team | Lead | PagerDuty escalation | Slack |
|---|---|---|---|
| Payments & Ingestion | Data Platform Lead | `#oncall-backend` | `#team-payments` |
| Reconciliation & Anomaly | Head of Reconciliation | `#oncall-reconciliation` | `#team-reconciliation` |
| Fraud & Security | Head of Fraud / CISO | `#oncall-fraud` | `#team-fraud` |
| Communications | Head of Communications | `#oncall-communications` | `#team-communications` |
| Data Platform | Head of Data Platform | `#oncall-data-platform` | `#team-data` |
| Platform Reliability | Head of Platform Engineering | `#oncall-platform` | `#team-platform` |
| Security & Compliance | Head of Compliance / CISO | `#oncall-security` | `#team-compliance` |

---

## 12. Governance rules

1. **Every event type must appear in this registry.** A change that adds a new
   event type to `EVENT_TYPES` in `event_bus.py`, a new entry to
   `EVENT_TYPE_TOPICS` in `topics.py`, or a new consumer group to
   `default_consumer_groups()` in `event_consumer.py` must update this document in
   the same pull request.
2. **Contracts evolve backward-compatibly by default.** Removing a required field,
   renaming a field, changing a field's type, or changing the meaning of an
   existing value requires a breaking-change process: a new `event_version`,
   a documented consumer migration window, and domain-owner sign-off per the
   change approval matrix above.
3. **Ownership is not optional.** An event type without an accountable owner team
   may not be deployed to production. New contracts must name an owner, a
   technical owner, and a responsible team before merge.
4. **Schema source of truth.** The envelope is defined by the JSON Schema at
   `infra/redpanda/schemas/transaction-event.json` and validated by
   `contract_versioning.validate_contract_version` plus `event_bus.validate_event`.
   Producer-side payload validation happens in `producer._validate_payload_schema`.
5. **Consumers must be idempotent.** All consumers are at-least-once; every
   contract listed here is expected to be replay-safe, with duplicate suppression
   through `EventDeliveryController` and the completed-event set.
6. **No silent data loss.** Every contract in this registry must route exhausted
   or invalid messages to the DLQ (`pesaguard.dlq`) rather than dropping them.
7. **Review cadence.** This registry is reviewed by the Data Platform Lead
   whenever `event_bus.py`, `topics.py`, or `event_consumer.py` changes, and at
   least quarterly as part of the data governance review.

---

## 13. Related documents

- [EVENT_ARCHITECTURE.md](EVENT_ARCHITECTURE.md) — event taxonomy and routing model
- [STATE_OWNERSHIP.md](STATE_OWNERSHIP.md) — authoritative owner per state type
- [SERVICE_BOUNDARIES.md](SERVICE_BOUNDARIES.md) — service ownership model
- [DATA_FLOW.md](DATA_FLOW.md) — how money and events move through the platform
- [DATA_PLATFORM_ARCHITECTURE.md](DATA_PLATFORM_ARCHITECTURE.md) — stage contracts
- [docs/testing/CONTRACT_TESTING.md](../testing/CONTRACT_TESTING.md) — contract test layer
- [docs/runbooks/KAFKA_FAILURE.md](../runbooks/KAFKA_FAILURE.md) — Kafka incident response

