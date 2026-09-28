# dcre-mrw

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories) table.

Mandates Request Writer: the outbound leg of the M10 mandates flow (SCRUM-79) that turns each
`INITIALIZED` mandate request spine row into an ISO 20022 `pain.009` / `pain.010` / `pain.011`
message on the RMB `.001.03` profile, lands it in the client's `fint-req-man/out` exchange leaf, and
transitions the spine row `INITIALIZED -> SUBMITTED` in `dcre_man`.

## What it does

**Position in the fleet.** Stage `MRW`, mandates family, REQ leg, arrival-launched. AGT's `RouteDags.MAN`
(route `onhost-req-man`) is `MRR -> MRV -> MAS -> MIT -> fork {MIR, MRW}`: MRW is one terminal arm of the
fork after `MIT`, beside the whole-file responder `MIR`, and has no DAG successor (`Emission.NONE`, because
MRW is itself the writer). Upstream: `MIT`, the single writer of `INITIALIZED`. Downstream, outside the DAG:
Fintegrate consumes the pain file, and its pain.012 replies come back on `fint-resp-man` to the leg readers
`MIX`, `MSX` and `MPX`, which correlate them to MRW's `man_outbound`. Diagram sheet: `dcre-mandates-req` in
the design register.

AGT launches MRW as a short-lived Kubernetes Job with `arrival.id` as the identifying JobParameter (R-16).
For every spine row the arrival has advanced to `INITIALIZED` it:

1. mints a **deterministic** outbound `MsgId` from the full business identity
   (`arrival_id, sequence, action_code`), never `now()`/epoch/UUID (SCRUM-90 full-identity
   idempotency), so a kill-resume re-mints the identical id. `OutMsgIdMinter` is `OMS` plus the first
   32 hex characters of a SHA-256 over the length-framed tuple (35 characters); `OrgnlEndToEndIdMinter`
   mints the `E2E`-prefixed `orgnl_e2e` from the same tuple under a separate domain tag, so the two ids
   never coincide. A blank tuple component fails closed;
2. persists a `man_outbound` registry row **write-ahead** of the file (R-10): `entry_id` UNIQUE +
   `out_msg_id` UNIQUE + `orgnl_e2e` + `mndt_req_id` + `pain_type` + `written_at`, committed in its own
   transaction **before** the file exists, so the leg readers can always correlate the pain.012 replies
   and a crash leaves at most a registry row;
3. hand-builds the synthetic pain message (SYNTHETIC-CONTRACT, A-60; real bindings become JAXB from
   the Fintegrate XSDs when recovered) and lands it via platform-files `StagedWrite` (tmp + fsync +
   ATOMIC_MOVE) at `<exchange-root>/<client, lower-cased>/fint-req-man/out/<client>_<outMsgId>_PAIN00x.xml`,
   with the client token from `mandate_request_header`: **one outbound message per instruction row**
   (no batch splitting in v1);
4. advances the spine row to `SUBMITTED` via a guarded atomic transition
   (`WHERE spine_state='INITIALIZED'`), making MRW the single writer of `SUBMITTED`.

### Action -> message mapping (`domain/PainType`)

| action | message | RMB profile |
|--------|---------|-------------|
| CREATE | pain.009 MandateInitiationRequest   | `pain.009.001.03` |
| AMEND  | pain.010 MandateAmendmentRequest    | `pain.010.001.03` |
| CANCEL | pain.011 MandateCancellationRequest | `pain.011.001.03` |

Any other `action_code` fails the job before a registry row or a file is written.

### Restart / crash-resume semantics

Restart safety falls out of the spine contract + full-identity idempotency: the reader selects only
`INITIALIZED` rows (a fully-submitted arrival re-runs to a no-op), the outbound `MsgId` is
deterministic, the registry INSERT is `ON CONFLICT (entry_id) DO NOTHING` (never UPSERT on the PK;
CRDB resolves UPSERT on PK only), `StagedWrite` is a file-existence no-op, and the `SUBMITTED`
transition is guarded. A kill in the gap between the registry commit and the file leaves a durable
registry row and no file; the resume re-emits the SAME id/file for **exactly one** outbound artifact
per instruction row.

## Architecture and principles

Spring Boot 4.1.0 / Spring Batch 6 (metadata DDL is Spring Batch 6.0.4) / Java 25 on CockroachDB
(PostgreSQL driver). An ephemeral batch job, not a server: platform-batch `ExitCodeMain` maps the Batch
outcome to the JVM exit code. One job, `mrwJob`, with one tasklet step, `submitStep`: `SubmitTasklet` is a
thin adapter over `MandateSubmitService`; SQL lives only in `data/repo`.

Database today: the shared mandates database `dcre_man` (primary datasource `DCRE_DB_URL`, `DCRE_DB_USER`,
`DCRE_DB_PASSWORD`), plus `agt_ops` for the platform-batch heartbeat (`DCRE_AGTOPS_DB_URL`,
`DCRE_AGTOPS_DB_USER`, `DCRE_AGTOPS_DB_PASSWORD`).

- **Owns:** `man_outbound` (registry) and the `mandate_request_entry.spine_state`
  `INITIALIZED -> SUBMITTED` transition (single writer, ruling note 2).
- **Reads:** the MRR-owned spine in `dcre_man`: `mandate_request_header` by `arrival_id` (a missing header
  fails the job), and the arrival's `mandate_request_entry` rows in `spine_state='INITIALIZED'`, ordered by
  `sequence`. MRW does not create the spine tables.
- **Writes:** `man_outbound` (`INSERT ... ON CONFLICT (entry_id) DO NOTHING`), `mandate_request_entry.spine_state`
  (`UPDATE ... WHERE id = ? AND spine_state = 'INITIALIZED'`), the `MRW_BATCH_*` metadata tables, and the
  Liquibase history tables `mrw_databasechangelog` / `mrw_databasechangeloglock`.
- **40001 handling:** each DB effect (write-ahead insert, spine transition) runs in its own `REQUIRES_NEW`
  transaction under `CrdbRetry`, and `submitStep` registers platform-batch `CrdbRetryExceptionHandler` for
  aborts at the step commit.
- **Isolation:** CockroachDB's default SERIALIZABLE; `application.yml` sets no Hikari
  `transaction-isolation` override (CRG, PRG and MRG do run READ COMMITTED; checked 2026-09-28).
- **Outcome seam and heartbeat:** platform-batch `OutcomeSeamListener` writes `BUSINESS_ACCEPTED` to
  `<exchange-root>/outcomes/<JOB_NAME>` on `COMPLETED` (local fallback `local-mrw-<executionId>`);
  `HeartbeatWriter` ticks `agt_ops.launch_intent` while the job runs; `BatchMetaConfig` abandons stale
  `MRW_BATCH_` executions before the job runs.
- **12FactorApp Alignment: https://12factor.net/**: configuration from the environment over committed
  working defaults, so a clean clone runs with no `.env`; stateless one-shot process; the databases are
  attached resources.

### Schema

Liquibase, `db/changelog/db.changelog-master.xml`, calendar layout `2026/07/`. The changelog is a v1
baseline (SCRUM-107): every DCRE database is dropped and recreated for the cut-over, so there is no
retrofit changeset and no checksum override.

| Changelog | Objects | Ownership |
|---|---|---|
| `000-man-core-bootstrap.xml` | `account_type`, `account`, `mandate_reason_code` (seeded) | Shared core; `MARK_RAN`-guarded convergence with the dcre-infra seed and the sibling M-services |
| `001-man-outbound.xml` | `man_outbound` (`uq_man_outbound_entry`, `uq_man_outbound_out_msg_id`) | MRW, sole creator, so no guard |
| `002-batch-metadata.xml` | `MRW_BATCH_*` | MRW; Spring Batch 6 DDL in typed XML |

## Prerequisites

- Java 25: `.sdkmanrc` pins `java=25-tem` (`sdk env`); `build.gradle` sets source and target compatibility 25
- Gradle 9.5.1 via the included wrapper (`gradle/wrapper/gradle-wrapper.properties`)
- Docker (Testcontainers CockroachDB for tests, image build for deployment)
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-persistence:0.1.0`, `platform-files:0.1.0`, `platform-batch:0.1.0`
- For a real local run: a reachable CockroachDB at `localhost:26257` holding `dcre_man` (spine tables already
  created by MRR's migrations) and `agt_ops`

## Quickstart

```bash
sdk env                 # Java 25 from .sdkmanrc

# 1) Publish the platform libs to Maven Local (once), from each platform repo clone:
./gradlew publishToMavenLocal

# 2) Build and test (Docker required; no .env needed, dev defaults are committed)
./gradlew test

# 3) One arrival against a local CockroachDB
./gradlew bootJar       # build/libs/mrw-2.0.jar
java -jar build/libs/mrw-2.0.jar 'arrival.id=<uuid>,java.lang.String,true'
```

## Configuration

Committed defaults live in `application.yml` (the only profile); any environment variable below overrides
its default. The table is the documented set, not a closed total: Spring Boot relaxed binding lets any
property be overridden by its derived environment variable name (for example `dcre.batch.table-prefix` as
`DCRE_BATCH_TABLEPREFIX`).

| Env | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | Primary datasource: mandates DB, Liquibase and Batch metadata |
| `DCRE_DB_USER` | `root` | Primary DB user |
| `DCRE_DB_PASSWORD` | (empty) | Primary DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat datasource |
| `DCRE_AGTOPS_DB_USER` | `root` | Heartbeat DB user |
| `DCRE_AGTOPS_DB_PASSWORD` | (empty) | Heartbeat DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Exchange root for the pain files and the outcome seam |
| `DCRE_AMOUNT_SCALE` | `2` | Bound as `dcre.amount-scale`; no class in this repository reads it |
| `JOB_NAME` | `local-mrw-<executionId>` | Set by AGT on the K8s Job; names the outcome seam file |

Fixed in `application.yml`: Batch table prefix `MRW_BATCH_`, Liquibase history tables
`mrw_databasechangelog` / `mrw_databasechangeloglock`, virtual threads on.

JobParameters: `arrival.id` (identifying, R-16). No other parameter is read.

## Testing

```bash
./gradlew test   # needs Docker
./gradlew test --tests '*MandateSubmitCrashIT'   # one suite
```

Pinned test image: `cockroachdb/cockroach:v26.2.3` (`MrwTestcontainersBase`).

Testcontainers CockroachDB + filesystem (`MrwJobIT`, `MandateSubmitCrashIT`) plus focused unit tests
(`OutMsgIdMinterTest`, `OrgnlEndToEndIdMinterTest`, `PainMandateWriterTest`): pain.009/010/011 per action,
the write-ahead ordering (registry before file), the restart file no-op, an arrival with no `INITIALIZED`
rows emitting nothing, an unmapped action failing closed with no registry row or file, and a crash-resume
zero-duplicate audit (exactly one file + one registry row per row, same ids on re-emit). `build.gradle`
declares the Cucumber dependencies, but the repository has no feature files and no Cucumber suite.

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-mrw:<version> .
kind load docker-image --name dcre-dev dcre-mrw:<version>
```

The image is `eclipse-temurin:25-jre-alpine` running `build/libs/mrw-2.0.jar`. AGT launches MRW as an
ephemeral K8s Job as `MIT`'s DAG successor on an `onhost-req-man` arrival, in the mandates flow namespace
(`AGT_NAMESPACE_MAN`, default `dcre-man`), named `man-mrw-<arrival id without dashes>`, with the image from
`AGT_MRW_IMAGE` (empty default, which leaves the stage launch-disabled). Job argument: `arrival.id=<uuid>`.
Pod env: `DCRE_DB_URL` (AGT `AGT_MAN_SERVICE_DB_URL`, default
`jdbc:postgresql://crdb.dcre.svc.cluster.local:26257/dcre_man?sslmode=disable`), `DCRE_EXCHANGE_ROOT=/exchange`
(the `dcre-exchange` PVC), `JOB_NAME`, `DCRE_AGTOPS_DB_URL` and `DCRE_AGTOPS_DB_USER` (read from AGT on
origin/dev, checked 2026-09-28). Image versions are set fleet-wide by dcre-infra `scripts/switch-version.sh`
(mandates stages from the 2.3 release line, checked 2026-09-28); the cluster itself is defined in dcre-infra.
Release tags are digits-only 3-component SemVer; this repo carries 2.2.0 and 2.2.1, and tagging is not uniform across the fleet (`git ls-remote --tags`, checked 2026-09-28).

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
