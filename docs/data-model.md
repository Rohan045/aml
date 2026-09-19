# Sentinel AML — Data Model (ERD)

Base currency: **USD**. Every monetary amount is stored in its original currency
*and* normalised to USD (`base_amount`) so a single set of thresholds works
across the whole book.

## Entity relationship diagram

```mermaid
erDiagram
    CUSTOMERS  ||--o{ ACCOUNTS     : "owns"
    CUSTOMERS  ||--o{ TRANSACTIONS : "transacts"
    ACCOUNTS   ||--o{ TRANSACTIONS : "records"
    CUSTOMERS  ||--o{ ALERTS       : "is subject of"
    ACCOUNTS   ||--o{ ALERTS       : "is subject of"
    CUSTOMERS  ||--o{ AML_CASES    : "is subject of"
    AML_CASES  ||--o{ ALERTS       : "groups"
    AML_CASES  ||--o{ CASE_NOTES   : "is annotated by"

    ALERTS       ||--o{ ALERT_TRIGGERED_RULES : "was raised by"
    ALERTS       ||--o{ ALERT_EVIDENCE        : "is supported by"
    TRANSACTIONS ||--o{ ALERT_EVIDENCE        : "is cited as"
    RULE_CONFIGS ||..o{ ALERT_TRIGGERED_RULES : "configures (by rule_code)"

    INGESTION_BATCHES ||--o{ INGESTION_ERRORS : "quarantines"
    INGESTION_BATCHES ||..o{ TRANSACTIONS     : "loaded"

    CUSTOMERS {
        varchar customer_id PK
        varchar first_name
        varchar last_name
        varchar national_id "PII"
        varchar country
        varchar city
        numeric annual_income
        varchar customer_segment
        varchar kyc_status
        varchar risk_rating
        boolean is_politically_exposed
    }

    ACCOUNTS {
        varchar account_id PK
        varchar customer_id FK
        varchar account_type
        varchar account_status
        varchar currency
        date    open_date
        numeric current_balance
        numeric avg_monthly_balance_6m
        varchar risk_rating
    }

    TRANSACTIONS {
        bigint  id PK
        varchar external_txn_id UK "idempotency key"
        varchar account_id FK
        varchar customer_id FK
        varchar direction
        varchar transaction_type
        numeric amount
        varchar currency
        numeric base_amount "USD-normalised"
        timestamptz transaction_timestamp
        varchar counterparty_name
        varchar counterparty_country
        boolean is_cross_border
        varchar screening_status
        bigint  ingestion_batch_id
    }

    ALERTS {
        bigint  id PK
        varchar alert_reference UK
        varchar dedupe_key UK "concurrency guard"
        varchar customer_id FK
        varchar account_id FK
        bigint  case_id FK
        varchar typology
        varchar status
        varchar severity
        integer risk_score "0-100"
        text    explanation
        integer occurrence_count
        timestamptz detection_window_start
        timestamptz detection_window_end
        bigint  version "optimistic lock"
    }

    ALERT_TRIGGERED_RULES {
        bigint  id PK
        bigint  alert_id FK
        varchar rule_code
        varchar typology
        integer rule_weight
        integer contributed_score
    }

    ALERT_EVIDENCE {
        bigint  id PK
        bigint  alert_id FK
        bigint  transaction_id FK
        varchar external_txn_id
        varchar evidence_role
        numeric base_amount
    }

    AML_CASES {
        bigint  id PK
        varchar case_number UK
        varchar customer_id FK
        varchar status
        varchar priority
        integer aggregate_risk_score
        numeric total_exposure_amount
        boolean sar_filed
        bigint  version "optimistic lock"
    }

    CASE_NOTES {
        bigint  id PK
        bigint  case_id FK
        varchar author
        text    note
        timestamptz note_created_at
    }

    RULE_CONFIGS {
        bigint  id PK
        varchar rule_code UK
        varchar typology
        boolean enabled
        integer execution_order
        varchar severity
        integer risk_weight
        numeric threshold_amount
        numeric threshold_amount_min
        integer min_occurrences
        integer time_window_hours
        numeric deviation_multiplier
        integer lookback_days
        numeric ratio_threshold
        integer config_version
    }

    WATCHLIST_ENTRIES {
        bigint  id PK
        varchar subject_type
        varchar list_type
        varchar entry_value
        varchar normalized_value
        integer risk_weight
        boolean active
    }

    EXCHANGE_RATES {
        bigint  id PK
        varchar from_currency
        varchar to_currency
        numeric rate
        date    effective_from
        date    effective_to
    }

    INGESTION_BATCHES {
        bigint  id PK
        varchar batch_reference UK
        varchar source
        varchar entity_type
        varchar status
        integer total_records
        integer success_count
        integer failure_count
    }

    INGESTION_ERRORS {
        bigint  id PK
        bigint  batch_id FK
        integer record_number
        varchar error_code
        varchar error_message
        text    raw_record
    }

    APP_USERS {
        bigint  id PK
        varchar username UK
        varchar password_hash
        varchar role
        boolean enabled
    }

    AUDIT_EVENTS {
        bigint  id PK
        varchar entity_type
        varchar entity_id
        varchar action
        varchar from_state
        varchar to_state
        varchar actor
        timestamptz occurred_at
    }
```

`AUDIT_EVENTS` and `APP_USERS` are deliberately drawn without foreign keys.
The audit trail references entities by an `(entity_type, entity_id)` string pair
so that a row can never be orphaned or cascade-deleted along with the thing it
describes — an audit record must outlive its subject.

## The critical path

The flow the detection engine actually walks:

```
customers ──< accounts ──< transactions
                               │
                               ▼
              rule evaluation (rule_configs, watchlist_entries, exchange_rates)
                               │
                               ▼
     alerts ──< alert_triggered_rules   (why it fired)
        │   ──< alert_evidence ───────► transactions   (what it fired on)
        ▼
    aml_cases ──< case_notes
        │
        ▼
    audit_events (every transition, append-only)
```

## Design decisions worth knowing

| Decision | Reason |
|---|---|
| Natural string PKs on `customers` / `accounts` (`CUST_00001`, `ACC_000001`) | The source CSVs use these identifiers, so ingestion resolves foreign keys without a lookup table or a second pass. |
| `transactions.external_txn_id` is `UNIQUE` | Re-running a feed is idempotent. Replaying a file cannot double-count a transaction or raise duplicate alerts. |
| `alerts.dedupe_key` is `UNIQUE` | Database-level guarantee that concurrent detection threads cannot raise two alerts for the same `customer + typology + time window`. A repeat detection increments `occurrence_count` instead. |
| `version` column on `alerts` and `aml_cases` | Optimistic locking. Two analysts acting on the same alert cannot silently overwrite each other. |
| `base_amount` + `base_currency` on every transaction | Rules compare one normalised number. No per-rule currency logic, no per-evaluation FX lookups. |
| `exchange_rates` is effective-dated | An alert re-opened a year later re-evaluates with the rate that applied on the transaction date, so investigations are reproducible. |
| Enums stored as `varchar` + `CHECK`, not native PG enum types | Adding a value is an ordinary migration rather than an `ALTER TYPE`, and the columns stay readable in ad-hoc SQL. |
| `case_notes`, `audit_events`, `ingestion_errors` are immutable | Append-only by construction. There is no update path in the mapping, so the evidential record cannot be rewritten. |
| `ingestion_errors` quarantine table | One malformed row fails that row, not the batch. Errors are inspectable and re-submittable. |
| Composite indexes `(account_id, transaction_timestamp)` and `(customer_id, transaction_timestamp)` | Every windowed rule — structuring, rapid movement, behavioural deviation — filters by party plus time range. These two indexes serve all of them. |

## Alert lifecycle

```
NEW ──► ASSIGNED ──► IN_REVIEW ──┬──► CLOSED        (with disposition + analyst)
                      │          └──► ESCALATED ──► linked to an AML_CASE
                      └──► PENDING_INFO ──► IN_REVIEW
```

A `CHECK` constraint enforces that an alert cannot reach `CLOSED` without both a
`disposition` and a recorded `disposition_by`. Closing an alert is an
accountable act, so the schema refuses to record an anonymous one.

## Case lifecycle

```
OPEN ──► ASSIGNED ──► INVESTIGATING ──► PENDING_REVIEW ──┬──► CLOSED
                                                          └──► ESCALATED ──► SAR_FILED ──► CLOSED
```

## Seeded reference data (`V2__seed_reference_data.sql`)

| Table | Seeded content |
|---|---|
| `exchange_rates` | 12 currencies → USD, effective 2024-01-01. |
| `rule_configs` | 8 rules: the 5 mandated business rules plus round-amount, high-risk counterparty and dormant-reactivation. |
| `watchlist_entries` | FATF blacklist (3), FATF greylist (8), tax havens (7), fictional demo counterparties and banks (6). |
| `app_users` | One demo account per RBAC role. **Development credentials only — delete or rotate before any real deployment.** |
