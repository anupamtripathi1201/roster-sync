# HR Roster Sync 

Keeps employee records correct while HR systems send changes **twice, out of order, with bad data, and cut
short**. Java 21, Spring Boot 3.5, MySQL 8.

* Idempotent, version-ordered ingest of create / update / deactivate events, validated per record
* Field ownership: HR never overwrites transport-admin fields
* Nightly 200k-row reconciliation with a bounded-memory bucketed diff
* Safety brake: a broken export stops the run and deactivates nobody until an operator approves
* Transactional outbox for change events; leavers' future cab bookings cancelled and seats freed
* Login with DAO authentication (users + BCrypt hashes in MySQL) issuing a JWT, roles (employee / admin / operator / HR system) and tenant isolation
* Prometheus metrics, JSON logs, no personal data in logs or error responses

Architecture, sequence diagram, failure-mode table, trade-offs and cost analysis: [docs/DESIGN.md](docs/DESIGN.md)

## Run it

```bash
docker compose up --build
```

Starts MySQL 8.4 and the app on `http://localhost:8080`, applies the schema and seeds demo data.
MySQL is published on host port **3307** so it does not clash with a local MySQL on 3306.
Every demo user's password is `demo123`:

| User | Role | Tenant |
|---|---|---|
| `hr-acme` | HR_SYSTEM | acme |
| `admin-acme` | ADMIN | acme |
| `ops-acme` | OPERATOR | acme |
| `emp-e1001` | EMPLOYEE (E1001) | acme |
| `admin-globex` | ADMIN | globex |

Walk through every flow (needs `curl`, `jq`, `python3`; on Windows run it from Git Bash): `bash scripts/demo.sh`

Tests (H2 in MySQL mode, no Docker needed): `./mvnw test` (Windows: `.\mvnw.cmd test`)

## API

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/v1/auth/login` | public | Username + password → JWT (1 h) |
| GET | `/api/v1/auth/me` | any user | Role and tenant of a token |
| POST | `/api/v1/hr/events` | HR_SYSTEM | Up to 1000 events, per-record report |
| POST | `/api/v1/hr/snapshots` | HR_SYSTEM | Nightly CSV (`file`), runs reconciliation |
| GET | `/api/v1/employees/{id}` | ADMIN, OPERATOR | Employee of your tenant |
| GET | `/api/v1/employees/me` | EMPLOYEE | Your own record |
| PATCH | `/api/v1/employees/{id}/transport` | ADMIN | Pickup point / special assistance |
| GET | `/api/v1/ops/reconciliations/{id}` | OPERATOR | Run summary incl. missing count |
| GET | `/api/v1/ops/reconciliations/{id}/rejections` | OPERATOR | Per-row error report |
| POST | `/api/v1/ops/reconciliations/{id}/approve` · `/reject` | OPERATOR | Decide a braked run |
| GET | `/api/v1/ops/outbox` | OPERATOR | Pending events, oldest age |
| POST | `/api/v1/ops/failures/{point}?times=n` | OPERATOR | Demo-only failure injection |
| GET | `/actuator/prometheus` | OPERATOR | Metrics |

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"hr-acme","password":"demo123"}' | jq -r .accessToken)

curl -s -X POST localhost:8080/api/v1/hr/events -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"events":[
 {"eventType":"UPDATE","employeeId":"E1001","version":7,"name":"Demo Employee 1","email":"employee1@acme.example",
  "phone":"+919876543201","officeCode":"HYD1","shiftCode":"NIGHT"},
 {"eventType":"UPDATE","employeeId":"E1001","version":6,"name":"Demo Employee 1","email":"employee1@acme.example",
  "phone":"+919876543201","officeCode":"BLR1","shiftCode":"EVENING"}]}'
# {"received":2,"applied":1,"duplicates":0,"stale":1,"rejected":0,"errors":[],"ownershipIgnored":[]}

python3 scripts/generate_snapshot.py 200000 full.generated.csv --cut 120000   # a broken export
curl -s -X POST localhost:8080/api/v1/hr/snapshots -H "Authorization: Bearer $TOKEN" -F file=@full.generated.csv
```

Nightly file format (values contain no commas):

```
employee_id,version,name,email,phone,manager_id,office_code,shift_code
E000001,1,Employee 1,e1@acme.example,+919800000001,,BLR1,EVENING
```

## Every hard rule has a test

| Rule | Code | Test |
|---|---|---|
| Idempotency key, version comparison, replay changes nothing | `ChangeApplier` | `CoreRulesTest`, `IngestIT.replayingTheSameFeedTwiceChangesNothing` |
| Stale ignored, v7-before-v6 example | `ChangeApplier`, `RosterEvent` | `CoreRulesTest`, `IngestIT` |
| One bad record never fails a batch | `IngestService` (one tx per record) | `IngestIT`, `ResilienceIT.oneCrashingRecordDoesNotFailTheBatch` |
| HR never overwrites transport fields | `Field`, `RosterEvent.mergeHrFields` | `CoreRulesTest`, `IngestIT` |
| Per-employee order under concurrency | row lock + `ChangeExecutor` | `ResilienceIT` (8 threads × 200 shuffled versions; racing creates) |
| Outbox: DB and event never disagree | `Outbox` | `ResilienceIT` (crash before commit / before publish / after publish) |
| Leaver: bookings cancelled, seats freed | `BookingService` | `ResilienceIT` (incl. redelivery) |
| 200k diff < 60 s, bounded memory | `BucketedDiffEngine` | `ReconciliationCoreTest`, `BenchmarkIT` (512 MB heap) |
| Safety brake, configurable, nobody deactivated | `SafetyBrake`, `ReconciliationService` | `ReconciliationCoreTest`, `ReconciliationIT` |
| No personal data in logs / errors | ids only, `Violation` has no value | `IngestIT.noPersonalDataInLogsOrReports`, `SecurityIT` |
| Auth, roles, tenant isolation | `SecurityConfig`, `UserAccountService`, `JwtService`; tenant from the token only | `SecurityIT` |

## Benchmarks

`./mvnw test` prints `[benchmark]` lines (also kept in `target/surefire-reports`). Measured on a 16-logical-CPU Windows laptop, JDK 22, tests capped at a 512 MB heap:

| Benchmark | Result |
|---|---|
| Core diff, 197,000 file rows vs 200,000 stored (`ReconciliationCoreTest`) | 1,311 ms · 150,267 rows/s · at most 5,929 rows in memory |
| End-to-end reconciliation, 200,000-row CSV (`BenchmarkIT`) | 4,497 ms · 44,474 rows/s (limit: 60 s) |
| Ingest, 5,000 events, 8 threads (`BenchmarkIT`) | 1,614 events/s · p50 3.77 ms · p99 18.30 ms |

The ingest numbers run against in-process H2; MySQL in Docker will be slower per commit. The diff barely
changes because it reads two narrow columns per bucket.

## Assumptions

* Each HR create/update carries the full set of HR-owned fields at that version.
* Versions increase per employee in the source system (a timestamp works as a version).
* The export carries each record's version; ids match `[A-Za-z0-9_-]{1,64}`; values have no commas.
* Shift and phone are HR-owned (the case study names name, email, manager and office).
* Reconciliation deactivation keeps the HR version, so a later HR event can re-activate.
* One relay instance publishes the outbox.

## Monitoring

| Metric | Use |
|---|---|
| `rostersync_events_received_total`, `rostersync_events_processed_total{outcome}` | volume; applied / stale / duplicate |
| `rostersync_events_rejected_total{reason}` | rejections; error rate = rejected / received |
| `rostersync_ingest_lag` (p50, p99, ms) | how far behind HR we are |
| `rostersync_apply_retries_total`, `rostersync_apply_latency` | contention, write latency |
| `rostersync_outbox_queue_depth`, `rostersync_outbox_lag_seconds`, `rostersync_outbox_publish_failures_total` | downstream backlog |
| `rostersync_reconciliation_brake_tripped_total`, `rostersync_reconciliation_last_missing` | nightly file health |
| `cache_gets_total{cache,result}` | cache hit ratio |

Logs are JSON with `traceId`, `tenantId` and `user` on every line, and employee ids only.

## Caching

| Cache | Holds | Eviction | Can be stale | Bound |
|---|---|---|---|---|
| `offices` | office codes per tenant | size 10k, TTL 5 min, evicted on add | new office rejected on another instance | ≤ 5 min |
| `employees` | read view per (tenant, employee) | size 50k, TTL 30 s, evicted after commit | GET on another instance | ≤ 30 s |

## Project layout

```
src/main/java/com/moveinsync/rostersync
├── core/           plain Java: fields, events, change applier, validator, diff, safety brake
├── domain/         JPA entities
├── repository/     Spring Data repositories
├── service/        ingest, change (tx), reconciliation, bookings, cached lookups
├── messaging/      outbox + relay, event bus
├── security/       DAO login, JWT issue/verify, roles
├── observability/  metrics, correlation id
├── config/         properties, beans and caches, failure injector, demo data
├── controller/     REST controllers only
├── dto/            request / response records
└── exception/      ApiException + global exception handler
```
