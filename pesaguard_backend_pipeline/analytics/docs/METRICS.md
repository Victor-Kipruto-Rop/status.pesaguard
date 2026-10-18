# Analytics Metric Definitions

All metrics are defined in `analytics/metrics.yml` and follow dbt metrics YAML format.

## Business Metrics

### transaction_volume
- **Type:** cumulative
- **Window:** day
- **Description:** Total transaction volume in minor currency units
- **Dimensions:** tenant_id, currency, provider, status
- **SQL:** `sum(trans_amount)`

### transaction_count
- **Type:** count
- **Window:** day
- **Description:** Number of transactions processed
- **Dimensions:** tenant_id, currency, provider, status
- **SQL:** `count(*)`

### reconciliation_rate
- **Type:** ratio
- **Window:** day
- **Description:** Percentage of transactions reconciled successfully
- **Numerator:** reconciliation_matches_count
- **Denominator:** transaction_count
- **Dimensions:** tenant_id
- **SQL:** `reconciliation_matches_count / transaction_count`

### discrepancy_count
- **Type:** count
- **Window:** day
- **Description:** Number of open discrepancies requiring investigation
- **Dimensions:** tenant_id, anomaly_type, severity
- **SQL:** `count(*)` where resolved = false

### avg_processing_latency_ms
- **Type:** average
- **Window:** hour
- **Description:** Average end-to-end transaction processing latency in milliseconds
- **Dimensions:** tenant_id, provider
- **SQL:** `avg(processing_latency_ms)`

### merchant_failure_rate
- **Type:** ratio
- **Window:** day
- **Description:** Percentage of merchant transactions that failed
- **Numerator:** failed_transactions
- **Denominator:** transaction_count
- **Dimensions:** tenant_id, merchant_id, currency
- **SQL:** `failed_transactions / transaction_count`

### anomaly_rate
- **Type:** ratio
- **Window:** day
- **Description:** Percentage of transactions flagged as anomalies
- **Numerator:** anomaly_count
- **Denominator:** transaction_count
- **Dimensions:** tenant_id
- **SQL:** `anomaly_count / transaction_count`

## Entities

| Entity | Expression | Description |
|--------|-----------|-------------|
| tenant | tenant_id | Unique tenant identifier |
| merchant | merchant_id | Merchant or account identifier |
| provider | provider | Payment provider (e.g., mpesa) |
| transaction | id | Transaction identifier |

## Dimensions

| Dimension | Type | Description |
|-----------|------|-------------|
| tenant_id | categorical | Tenant identifier for data isolation |
| currency | categorical | ISO 4217 currency code |
| provider | categorical | Payment provider |
| status | categorical | Transaction lifecycle status |
| anomaly_type | categorical | Type of anomaly detected |
| severity | categorical | Discrepancy severity level |
| merchant_id | categorical | Merchant identifier |
