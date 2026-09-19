# Sentinel AML

Real-time  anti-money-laundering transaction monitoring: ingest customer, account
and transaction data, screen every transaction against a configurable rule set,
raise risk-scored alerts with the evidence attached, and carry those alerts
through a full investigation and SAR-filing workflow with an immutable audit
trail behind it.

Built on Java 17, Spring Boot 4.1, PostgreSQL and Flyway.

---

## Table of contents

1. [What the system does](#what-the-system-does)
2. [Architecture](#architecture)
3. [Detection rules](#detection-rules)
4. [Rule configuration approach](#rule-configuration-approach)
5. [Security, roles and PII](#security-roles-and-pii)
6. [Getting started](#getting-started)
7. [Demo walkthrough](#demo-walkthrough)
8. [API reference](#api-reference)
9. [Seed data](#seed-data)
10. [Testing](#testing)
11. [Project layout](#project-layout)

---

## What the system does

A transaction enters the system through one of three doors - a REST call, a bulk
CSV upload, or a Kafka topic - and follows the same path regardless:

```
 ingest  ->  validate  ->  normalise FX  ->  persist  ->  detect  ->  alert  ->  investigate  ->  SAR
```

Each stage exists for a reason:

- **Validate** — a malformed row is rejected individually and reported with its
  line number, rather than failing the whole batch. A hundred-thousand-row file
  with three bad rows should yield 99,997 monitored transactions and three
  errors, not zero of either.
- **Normalise FX** — every rule threshold is expressed in a single base currency
  (`sentinel.base-currency`, USD by default). A transaction stores both its
  original amount and its base-currency equivalent, computed from the exchange
  rate effective on its own value date. Without this, a rule saying "over
  10,000" would mean eight different things across eight currencies.
- **Detect** — every enabled rule is evaluated against the transaction and its
  surrounding window. Rules are independent and additive.
- **Alert** — findings are de-duplicated, risk-scored and aggregated. The same
  pattern re-detected inside the de-duplication window folds into the existing
  alert instead of creating a second one, because an analyst who sees the same
  problem forty times will stop reading alerts.
- **Investigate** — alerts are triaged, assigned, dispositioned, and where
  warranted promoted into a case that can carry multiple alerts, investigator
  notes and a drafted SAR narrative.

Everything that changes state writes an audit event. The audit table is mapped
immutable and exposes no update or delete path, because the evidential value of
the trail depends on the application being unable to rewrite it.

## Architecture

```
                REST / CSV upload            Kafka topic
                        |                         |
                        v                         v
            +-----------------------+   +---------------------+
            |  web/controller       |   | streaming           |
            |  (DTOs, validation,   |   | TransactionStream-  |
            |   RBAC, PII masking)  |   | Listener            |
            +-----------+-----------+   +----------+----------+
                        |                          |
                        v                          v
            +--------------------------------------------------+
            |  service/ingestion                               |
            |  CsvIngestionService -> CsvSupport                |
            |  MasterDataIngestionService (customers, accounts) |
            |  TransactionIngestionService (validate, FX,       |
            |                               idempotency)       |
            +---------------------+----------------------------+
                                  |
                                  v
            +--------------------------------------------------+
            |  detection                                       |
            |  DetectionEngine  -> DetectionRule x8            |
            |                   -> RollingWindow, RiskScorer   |
            +---------------------+----------------------------+
                                  |
                                  v
            +--------------------------------------------------+
            |  service                                         |
            |  AlertService (dedupe, aggregate, score)         |
            |  CaseService (workflow, SAR narrative)           |
            |  AuditService (append-only)                      |
            |  DashboardService, RuleConfigService,            |
            |  WatchlistService, CustomerService               |
            +---------------------+----------------------------+
                                  |
                                  v
                        repository  ->  PostgreSQL
                                        (schema owned by Flyway)
```

**Layering rule:** controllers never touch repositories, services never return
entities to the wire. Every response crosses the boundary as a DTO record in
`web/dto`, which is also where PII masking is applied — so it is structurally
impossible to leak an unmasked field by forgetting to call a helper.

The schema is owned by Flyway (`db/migration`) and Hibernate is set to
`ddl-auto=validate`. The application can therefore never silently alter the
shape of the data it is legally required to retain. See
[`docs/data-model.md`](docs/data-model.md) for the entity-relationship detail.

## Detection rules

Eight rules ship enabled. All thresholds are in the base currency and are
compared against `transactions.base_amount`.

| Code | Typology | Fires when | Severity |
|---|---|---|---|
| `CTR_THRESHOLD_10K` | Threshold breach | A single transaction is >= USD 10,000 | HIGH |
| `STRUCTURING_24H` | Structuring | 3+ transactions on one account within a rolling 24h, each between USD 9,000 and 9,999.99 | HIGH |
| `RAPID_MOVEMENT_48H` | Rapid movement | >= 80% of an inbound deposit leaves the account again within 48h | HIGH |
| `HIGH_RISK_JURISDICTION` | Jurisdiction | Counterparty, origin or destination country is on a sanctions, FATF blacklist or greylist entry | CRITICAL |
| `BEHAVIOURAL_DEVIATION_3X` | Behavioural | A customer's daily value or count exceeds 3x their own 90-day baseline | MEDIUM |
| `ROUND_AMOUNT_PATTERN` | Round amounts | 3+ transactions within 24h that are exact multiples of 1,000 | MEDIUM |
| `HIGH_RISK_COUNTERPARTY` | Counterparty | Counterparty name, bank or account matches a sanctions, adverse-media or internal high-risk entry | HIGH |
| `DORMANT_REACTIVATION` | Mule account | Significant activity on an account dormant for 180+ days | MEDIUM |

Two of these are deliberately *relative* rather than absolute.
`BEHAVIOURAL_DEVIATION_3X` compares a customer against themselves, and
`DORMANT_REACTIVATION` compares a transaction against a silence. Absolute
thresholds alone are trivially evaded by anyone who knows what they are; the
relative rules are what catch the launderer who stays under every published
limit.

### Risk scoring

Each rule carries a `risk_weight`. When several rules fire on the same subject,
`RiskScorer` combines the weights with contributing factors from the customer
and account profile (PEP status, KYC state, account and customer risk rating,
cross-border exposure) into a 0–100 score, which drives alert severity and queue
ordering. A single rule firing is a question; four rules firing together is a
case.

## Rule configuration approach

**No threshold is a constant in Java.** Every number in the table above lives in
the `rule_configs` table and is read at evaluation time.

This is not a stylistic preference. AML thresholds change by regulation, by
jurisdiction and by the institution's own tuning as false-positive rates come
in, and they change on timescales far shorter than a release cycle. A system
that requires a code deployment to move a threshold from 10,000 to 8,000 is a
system whose compliance team will route around it.

Each rule configuration row carries:

- `enabled`, `execution_order`, `severity`, `risk_weight`
- the threshold fields the rule needs — `threshold_amount`,
  `threshold_amount_min`, `min_occurrences`, `time_window_hours`,
  `deviation_multiplier`, `lookback_days`, `ratio_threshold`
- `dedupe_window_hours` — how long before the same detection counts as new
- `parameters`, a JSON column for anything rule-specific that does not deserve
  its own column (matched fields, applicable transaction types, scope)
- `config_version` and `effective_from` — so a past alert can be explained by
  the configuration that was actually in force when it was raised, not the one
  in force today

Tuning happens over the API (`PUT /api/v1/rules/{code}`), is restricted to
compliance and admin roles, and writes an audit event recording the old and new
values. Re-screening a historical window under the new configuration is a single
call to `POST /api/v1/detection/sweep`.

Watchlists are data too. Countries and counterparties are rows in
`watchlist_entries` with a type, a risk weight and an effective period — adding
a jurisdiction to the blacklist is an API call, not a patch.

## Security, roles and PII

Authentication is HTTP Basic over a stateless session; passwords are BCrypt
strength 12. Five roles:

| Role | Can do |
|---|---|
| `ANALYST` | Work the alert queue, view masked customer data, disposition alerts |
| `SENIOR_ANALYST` | The above, plus unmasked PII and case management |
| `COMPLIANCE_OFFICER` | The above, plus rule tuning, watchlist maintenance, SAR filing |
| `AUDITOR` | Read-only across alerts, cases, rules and the full audit trail |
| `ADMIN` | Platform administration, ingestion, actuator |

Enforcement is layered: URL rules in `SecurityConfig` for coarse path
protection, `@PreAuthorize` on individual methods for the fine-grained cases.

**PII masking** is applied at the DTO boundary. List endpoints mask
unconditionally — nobody needs a full national ID to triage a queue. Detail
endpoints unmask only for roles with `canViewFullPii()`, and every unmasked
disclosure writes a `PII_REVEALED` audit event naming the viewer, the subject
and the moment. An analyst may look; the institution will know they looked.

### Demo credentials

Seeded by migration `V3`. **Password for every account: `Sentinel#2026`**

| Username | Role |
|---|---|
| `admin` | ADMIN |
| `compliance.officer` | COMPLIANCE_OFFICER |
| `senior.analyst` | SENIOR_ANALYST |
| `analyst` | ANALYST |
| `auditor` | AUDITOR |

> These credentials are published and therefore public. Delete or rotate every
> one of them before this schema goes anywhere near real customer data.

## Getting started

### Prerequisites

- JDK 17+
- PostgreSQL 14+
- Python 3.9+ (only to regenerate the seed dataset)
- Kafka (optional — streaming is off unless enabled)

### 1. Create the database

```bash
createdb sentinel_aml
```

### 2. Configure the connection

Defaults target a local throwaway instance. Override with environment variables
anywhere else — the committed defaults must never be relied on:

```bash
export SENTINEL_DB_URL=jdbc:postgresql://localhost:5432/sentinel_aml
export SENTINEL_DB_USER=postgres
export SENTINEL_DB_PASSWORD=yourpassword
```

### 3. Run

```bash
./mvnw spring-boot:run          # Linux / macOS
.\mvnw.cmd spring-boot:run      # Windows
```

Flyway applies the schema and reference data on first boot. Then:

- Swagger UI — <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON — <http://localhost:8080/api/v1/api-docs>

### 4. Optional: enable Kafka streaming

```properties
sentinel.streaming.enabled=true
sentinel.streaming.topic=sentinel.transactions
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.consumer.group-id=sentinel-aml
```

The listener is `@ConditionalOnProperty`-guarded, so the application starts
cleanly with no broker present — a demo must not require infrastructure it is
not demonstrating.

## Demo walkthrough

Load the data, watch the alerts appear, work one to a SAR.

```bash
BASE=http://localhost:8080/api/v1
ADMIN='admin:Sentinel#2026'
CO='compliance.officer:Sentinel#2026'
ANALYST='analyst:Sentinel#2026'

# 1. Generate a dataset dated relative to today
python tools/generate_seed_data.py --out seed

# 2. Ingest master data first - accounts reference customers, and transactions
#    reference accounts. Ingesting out of order rejects rows as UNKNOWN_CUSTOMER.
curl -u $ADMIN -F file=@seed/customers.csv    $BASE/ingestion/customers/csv
curl -u $ADMIN -F file=@seed/accounts.csv     $BASE/ingestion/accounts/csv

# 3. Transactions. Detection runs inline during this call.
curl -u $ADMIN -F file=@seed/transactions.csv $BASE/ingestion/transactions/csv

# 4. See what fired
curl -u $ANALYST "$BASE/dashboard?lookbackDays=30"
curl -u $ANALYST "$BASE/alerts?page=0&size=20"

# 5. Open one alert - note the evidence list naming the exact transactions
curl -u $ANALYST "$BASE/alerts/1"

# 6. Triage it
curl -u $ANALYST -X POST "$BASE/alerts/1/assign" \
     -H 'Content-Type: application/json' -d '{"assignee":"analyst"}'
curl -u $ANALYST -X PATCH "$BASE/alerts/1/status" \
     -H 'Content-Type: application/json' -d '{"status":"UNDER_REVIEW"}'

# 7. Escalate into a case
curl -u $CO -X POST "$BASE/cases" -H 'Content-Type: application/json' \
     -d '{"title":"Structuring - CUST_20002","description":"Five sub-threshold cash deposits inside 24 hours across two branches.","alertIds":[1]}'

# 8. Draft the SAR narrative from the evidence, then record the filing
curl -u $CO "$BASE/cases/1/sar-draft"
curl -u $CO -X POST "$BASE/cases/1/sar" -H 'Content-Type: application/json' \
     -d '{"sarReference":"FIU-IND-2026-004411"}'

# 9. Close the investigation. The disposition reason is mandatory - an alert is
#    never deleted, it is resolved with a recorded justification and an owner.
curl -u $CO -X POST "$BASE/cases/1/close" -H 'Content-Type: application/json' \
     -d '{"closureReason":"SAR_FILED","narrative":"Filed with the FIU; account placed under enhanced monitoring."}'

# 10. Everything that just happened, in order
curl -u auditor:'Sentinel#2026' "$BASE/audit/entity/Case/1"
```

To resolve an alert without escalating, disposition it directly - which is the
other half of the workflow and equally audited:

```bash
curl -u $ANALYST -X POST "$BASE/alerts/2/disposition" \
     -H 'Content-Type: application/json' \
     -d '{"disposition":"FALSE_POSITIVE","reason":"Deposit matches a documented property sale; supporting contract on file."}'
```

To demonstrate rule tuning: lower `CTR_THRESHOLD_10K` to 5,000 with
`PUT $BASE/rules/CTR_THRESHOLD_10K` (body `{"thresholdAmount":5000}`), then
re-screen the same window with `POST $BASE/detection/sweep` and watch the alert
count move without a redeploy.

## API reference

All paths are prefixed `/api/v1`. Full request and response schemas are in
Swagger UI.

| Area | Endpoints |
|---|---|
| **Ingestion** | `POST /ingestion/{customers,accounts,transactions}/csv` (multipart), `POST /ingestion/{customers,accounts,transactions}` (JSON batch), `POST /ingestion/transactions/stream` (single), `GET /ingestion/batches`, `GET /ingestion/batches/{ref}`, `GET /ingestion/batches/{ref}/errors` |
| **Alerts** | `GET /alerts`, `/alerts/mine`, `/alerts/by-typology/{t}`, `/alerts/{id}`, `POST /alerts/{id}/assign`, `PATCH /alerts/{id}/status`, `POST /alerts/{id}/disposition` |
| **Cases** | `GET /cases`, `/cases/{id}`, `/cases/{id}/notes`, `/cases/{id}/sar-draft`, `POST /cases`, `/cases/{id}/alerts/{alertId}`, `/assign`, `/notes`, `/sar`, `/close`, `PATCH /cases/{id}/status` |
| **Customers** | `GET /customers`, `/customers/politically-exposed`, `/customers/{id}`, `/accounts`, `/transactions`, `/alerts`, `/cases` |
| **Accounts** | `GET /accounts/{id}`, `/accounts/{id}/transactions` |
| **Transactions** | `GET /transactions/{externalTxnId}` |
| **Rules** | `GET /rules`, `/rules/{code}`, `/rules/typologies`, `PUT /rules/{code}`, `POST /rules/{code}/{enable,disable}` |
| **Watchlist** | `GET /watchlist`, `/watchlist/high-risk-countries`, `POST /watchlist` |
| **Detection** | `POST /detection/sweep`, `/detection/screen-pending`, `GET /detection/rules` |
| **Dashboard** | `GET /dashboard` |
| **Audit** | `GET /audit`, `/audit/entity/{type}/{id}`, `/audit/actor/{actor}`, `/audit/action/{action}` |

Ingestion returns `201` when every record succeeded, `207 Multi-Status` on a
partial batch with the per-row errors attached, and `422` when nothing could be
processed. A partial batch is a normal outcome in this domain, not an error
condition, and the status code says so.

## Seed data

`tools/generate_seed_data.py` writes `seed/customers.csv`,
`seed/accounts.csv` and `seed/transactions.csv`.

It is a generator rather than three static files because dates matter. A fixed
file that demonstrated a 90-day behavioural baseline when it was written stops
demonstrating anything three months later. The script positions every scenario
relative to an anchor date — today by default, `--anchor YYYY-MM-DD` for
reproducible output — and is seeded deterministically, so the same anchor always
produces byte-identical files.

The dataset is small and hand-designed rather than large and random, because
what a reviewer needs is to trace each alert back to the exact rows that caused
it. It contains:

| Scenario | Subject | Expected rule |
|---|---|---|
| Four clean control customers, including a deposit deliberately just under the CTR threshold | `CUST_10001`–`CUST_10004` | *none* — proves the thresholds discriminate |
| Cash deposit of USD 12,500 | `CUST_20001` | `CTR_THRESHOLD_10K` |
| Five deposits of 9,200–9,950 within 24h across two branches | `CUST_20002` | `STRUCTURING_24H` |
| USD 60,000 in, 55,500 out to three beneficiaries within 28h | `CUST_20003` | `RAPID_MOVEMENT_48H` |
| Wires to Iran and from North Korea | `CUST_20004` | `HIGH_RISK_JURISDICTION` |
| 100 days at ~USD 160/day, then one day at ~8,300 | `CUST_20005` | `BEHAVIOURAL_DEVIATION_3X` |
| Four exact-thousand transfers in one day | `CUST_20006` | `ROUND_AMOUNT_PATTERN` |
| PEP paying a sanctioned entity and a crypto exchange | `CUST_20007` | `HIGH_RISK_COUNTERPARTY` |
| Dormant 400 days, then 24,000 in and 21,500 straight out | `CUST_20008` | `DORMANT_REACTIVATION` |
| Structuring + jurisdiction + rapid movement in one week | `CUST_20009` | three rules, one investigation — demonstrates risk aggregation |

All amounts are USD so the thresholds can be read straight off the amount column
without mental FX. The ingestion pipeline handles other currencies via
`exchange_rates`; `CUST_20003` and `CUST_20009` carry cross-border flags to
exercise that path.

## Testing

```bash
./mvnw test                                        # everything (41 tests)
./mvnw test -Dtest='com.azentio.aml.detection.**'  # rules only
./mvnw test -Dtest=IngestionToAlertPipelineTest    # end-to-end pipeline
```

The detection tests cover every rule at its boundary — one case that must fire,
one that must not, positioned either side of the configured threshold. Boundary
behaviour is where a rule engine actually earns or loses trust: a rule that
fires at 9,999.99 but not at 10,000.00 is worse than no rule at all.

`RollingWindow`, `RiskScorer` and alert de-duplication key generation are tested
directly, since all three are shared by every rule and a defect in any of them
would be attributed to the wrong place.

`IngestionToAlertPipelineTest` runs the whole chain in one go — CSV in, alerts
out — and asserts that six typologies fire, that FX normalisation populated
every row, that replaying the same file produces duplicates rather than new
transactions, that re-sweeping aggregates into existing alerts rather than
creating new ones, and that the clean control customers produced **no** alerts
at all. That last assertion is the one that matters most: proving the rules stay
quiet on ordinary behaviour is harder, and more valuable, than proving they
fire on obvious behaviour.

Tests run against H2 in PostgreSQL mode with the schema derived from the entity
model, so `./mvnw test` needs no database and no network.

## Project layout

```
src/main/java/com/azentio/aml/
  config/         Security, OpenAPI, scheduling, typed properties
  domain/         JPA entities
    enums/        Every closed vocabulary in the model
  repository/     Spring Data repositories, including window queries
  detection/      DetectionEngine, RollingWindow, RiskScorer
    rule/         The eight rule implementations
  service/        Alert, Case, Audit, Dashboard, RuleConfig, Watchlist, Customer
    ingestion/    CSV support, master data, transactions, batch bookkeeping
  streaming/      Kafka listener (conditional)
  security/       Authentication, current-user context, PII masking
  web/
    controller/   REST endpoints
    dto/          Request and response records - the masking boundary
    error/        Problem-detail exception handling

src/main/resources/db/migration/   Flyway: schema, reference data, demo users
src/test/java/                     Rule, window, scoring, dedupe and
                                   end-to-end pipeline tests
tools/generate_seed_data.py        Synthetic dataset generator
seed/                              Generated demo CSVs
docs/data-model.md                 Entity-relationship reference
```
