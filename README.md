# dcre-mrw

Mandates Request Writer: the outbound leg of the M10 mandates flow (SCRUM-79) that turns each
`INITIALIZED` mandate request spine row into an ISO 20022 `pain.009` / `pain.010` / `pain.011`
message on the RMB `.001.03` profile, lands it in the client's `fint-req-man/out` exchange leaf, and
transitions the spine row `INITIALIZED -> SUBMITTED` in `dcre_man`.

## What it does

MRW is a terminal fork of the mandates DAG (`MRR -> MRV -> MAF -> MIS -> { MIR || MRW }`). AGT
launches it as a short-lived Kubernetes Job with `arrival.id` as the identifying JobParameter (R-16).
For every spine row the arrival has advanced to `INITIALIZED` (MIS is the single writer of
`INITIALIZED`) it:

1. mints a **deterministic** outbound `MsgId` from the full business identity
   (`arrival_id, sequence, action_code`) - never `now()`/epoch/UUID (SCRUM-90 full-identity
   idempotency), so a kill-resume re-mints the identical id;
2. persists a `man_outbound` registry row **write-ahead** of the file (R-10): `entry_id` UNIQUE +
   `out_msg_id` UNIQUE + `mndt_req_id` + `pain_type` + `written_at`, committed in its own
   transaction **before** the file exists, so MSR can always map the pain.012 replies and a crash
   leaves at most a registry row;
3. hand-builds the synthetic pain message (SYNTHETIC-CONTRACT, A-60; real bindings become JAXB from
   the Fintegrate XSDs when recovered) and lands it via `StagedWrite` (tmp + fsync + ATOMIC_MOVE) at
   `<client>/fint-req-man/out/<CLIENT>_<outMsgId>_PAIN00x.xml` - **one outbound message per
   instruction row** (no batch splitting in v1);
4. advances the spine row to `SUBMITTED` via a guarded atomic transition
   (`WHERE spine_state='INITIALIZED'`), making MRW the single writer of `SUBMITTED`.

### Action -> message mapping (`domain/PainType`)

| action | message | RMB profile |
|--------|---------|-------------|
| CREATE | pain.009 MandateInitiationRequest   | `pain.009.001.03` |
| AMEND  | pain.010 MandateAmendmentRequest    | `pain.010.001.03` |
| CANCEL | pain.011 MandateCancellationRequest | `pain.011.001.03` |

### Restart / crash-resume semantics

Restart safety falls out of the spine contract + full-identity idempotency: the reader selects only
`INITIALIZED` rows (a fully-submitted arrival re-runs to a no-op), the outbound `MsgId` is
deterministic, the registry INSERT is `ON CONFLICT (entry_id) DO NOTHING` (never UPSERT on the PK;
CRDB resolves UPSERT on PK only), `StagedWrite` is a file-existence no-op, and the `SUBMITTED`
transition is guarded. A kill in the gap between the registry commit and the file leaves a durable
registry row and no file; the resume re-emits the SAME id/file for **exactly one** outbound artifact
per instruction row.

## Data ownership

- **Owns:** `man_outbound` (registry) and the `mandate_request_entry.spine_state`
  `INITIALIZED -> SUBMITTED` transition (single writer, ruling note 2).
- **Reads:** the MRR-owned spine (`mandate_request_header` / `mandate_request_entry`) in shared
  `dcre_man`.

## Tech

Boot 4.1.0 / Batch 6.0.4 / Java 25, `platform-batch:0.1.0` persistent JobRepository, CockroachDB,
Liquibase pure-XML (own `mrw_databasechangelog` + `MRW_BATCH_` metadata), SERIALIZABLE isolation
(no READ COMMITTED override; only PRG carries RC per SCRUM-90).

## Tests

Testcontainers CockroachDB + filesystem (`MrwJobIT`, `MandateSubmitCrashIT`) plus focused unit tests
(`OutMsgIdMinterTest`, `PainMandateWriterTest`): pain.009/010/011 per action, the write-ahead
ordering (registry before file), the restart file no-op, and a crash-resume zero-duplicate audit
(exactly one file + one registry row per row, same ids on re-emit).
