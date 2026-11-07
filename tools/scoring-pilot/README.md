# Scoring pilot — local implementation

Status: local implementation on `feature/cloudflare-scoring-pilot`. No Cloudflare
deployment or production DB changes. Nineteen Kotlin tests and twelve local
Postgres/API tests pass, with no skips. The Cloudflare bundle builds locally.

The calculator in `packages/indexer/.../xapp/scoring/ActivityDistributionCalculator.kt`
uses the current `AppRoundScoreService` percentile and logarithm helpers. It preserves
the unusual tie rule, rounds efficiencies before ranking at the same precision,
and returns separate Activity/Distribution ranks and a clearly labelled subtotal
out of 60. It never claims to calculate the complete reputation score.

## Storage

`sql/001-schema.sql` defines an isolated Postgres schema and
`sql/002-calculation.sql` adds numeric calculations, transactional/idempotent run
execution and input/output immutability guards. Neither is registered with Flyway.
A DB owner must review them, arrange a source SELECT role and
a result writer restricted to this schema, check capacity, and arrange private
connectivity before any migration is applied. Never paste credentials into chat.

Each round snapshot must contain the COMPLETE authoritative on-chain app set,
including inactive apps. Do not derive that set from rewarded apps or allocation
rows alone. Save source block IDs, table versions/checkpoints, complete-history
coverage, retrieval timestamps, and any RPC references in `source_manifest`.
Hash a canonical, explicitly versioned representation of membership and full
precision inputs. Ingestion must use a consistent database snapshot and wait for
all required source indexers to finish the round. Save null for unknown new-user
counts, never zero; incomplete history blocks an Activity calculation.

The schema separates inputs from runs and outputs. Registering a run freezes its
snapshot. Execution locks the run, validates population and history, writes all
results atomically and marks it completed. Completed retries return without
recalculating. Saved results cannot be updated/deleted; corrections create another
snapshot/run. Manual status progression is completed → validated → approved.
SQL parity tests use a local Postgres/WASM engine (PGlite), not the production DB
or Docker. Concurrency and role behavior still need testing against the team's
actual Postgres version before rollout.

The source-data importer, canonical input hashing, source-checkpoint readiness,
formal role grants and production approval/promotion integration are still pending.
Do not treat this as a deployed full backfill service. The database owner or an
approved importer must supply complete snapshots first. SQL deliberately omits
`ln(1+x)` because it preserves ordering; seven exact fixture populations match the
current Kotlin calculation, including Long.MAX_VALUE and efficiency-rounding ties.

## Local verification

From the repository root, with the project Java 21:

```sh
./gradlew :packages:indexer:test --tests '*ActivityDistribution*' --tests '*CommunityScoreServiceTest' --tests '*NavigatorDelegationTimelineTest' --tests '*UserFirstSeenAggregationServiceTest'
```

This generates `packages/indexer/build/reports/scoring-pilot/kotlin-reference.json`.
Then, from `tools/scoring-pilot`:

```sh
npm ci --ignore-scripts
npm test
npm run check
```

The pinned Cloudflare CLI requires Node 22.23+, 24.22+ or 26.12+ (per its package
engines). It is intentionally local to this package; no system CLI was installed.
Local build, using a supported temporary runtime if the host is still Node 24.14:

```sh
npm exec --yes --package=node@24.22.0 -- node node_modules/cf/bin/cf build
```

This builds only; it makes no deployment. The existing DAO checkout uses Node 20
for contracts and is independent of this pilot package.

## Worker and dashboard

`cloudflare.config.ts` targets VeChain Lab and uses the cf CLI, not Wrangler.
The bundled dashboard at `/` displays saved runs, raw activity counts, component
scores and ranks. `/api/runs?network=mainnet&round=122` lists at most 20 runs;
`/api/runs/:uuid/scores` returns at most 200 complete app rows with exact decimals
as strings. `/health` is public and contains no database details.

Every other route requires a verified Cloudflare Access JWT. Merely supplying an
email header is insufficient. The Access team domain and audience default blank,
so protected endpoints fail closed until configured. There are no wallet actions.

`POST /api/runs/:uuid/execute` additionally requires same origin and explicit
execution enablement. Execution defaults OFF and no cron trigger is installed.
The scheduled handler can process one pending, already-snapshotted run per call
with `SKIP LOCKED`; it performs no source ingestion. Transactions have a five-second
statement timeout and one-second lock timeout, to be measured before activation.
Missing DB/Access configuration or incomplete inputs never produce fake scores.

Before a deployment, VeCarbon must supply a reviewed Hyperdrive binding with
query caching DISABLED for the job connection, database region for placement,
and an approved source/result role. Configure the non-secret binding ID/domain/
audience through `PILOT_HYPERDRIVE_ID`, `PILOT_ACCESS_TEAM_DOMAIN` and
`PILOT_ACCESS_AUDIENCE`. Database credentials are managed by the operator through
Cloudflare, never in this package. Keep API and writer audiences separate if
additional read-only users will be invited before enabling execution.

## Cloudflare plan gate

Cloudflare Containers require Workers Paid. Dashboard inspection on 2026-11-05
confirmed BOTH Jerome's personal account and VeChain Lab use Workers Free.
No subscription was changed. Do not upgrade or start billable resources without
the user's budget approval (Workers Paid starts at USD 5/month plus usage).

Jerome chose Workers Free and the VeChain Lab account on 2026-11-05. The active
deployment design is therefore a small Worker orchestrating Postgres-side numeric
calculations, with a static test UI and the existing Postgres result storage.
The Kotlin calculator is the reference oracle for validating the SQL results;
it will not be deployed as a Container on the Free plan. SQL calculation parity
and the initial Worker implementation are locally tested; deployed free-plan CPU,
network, database load and connectivity measurements are still pending.
No D1 or replacement database is proposed. Hyperdrive connectivity and production
DB permissions remain unverified. Jerome identifies VeCarbon as the operator of
the database and contractor code; coordinate the following with that team.

The historical exposure was in MongoDB test connection strings, not verified
production Postgres credentials. Do not recover or reuse them to obtain access.
VeCarbon must arrange an authorized connection to the correct database.

## VeCarbon handoff prerequisites

1. Confirm the actual deployed PostgreSQL schema/version matches upstream
   `a0b86ae9`. We have only sampled the public mainnet API, not inspected the DB.
2. Approve a separate result schema and its retention/backup policy. Review the
   proposed SQL before applying it. The source importer is not implemented yet.
3. Provide source access to `b3tr_action.entity_round`,
   `b3tr_action.app_user_round`, and `b3tr_x_alloc.result` through a read-only
   connection or a team-managed snapshot/export interface. Prefer a replica or
   controlled exports when heavy backfill queries could affect production.
4. Confirm earliest wallet history and indexer checkpoints, app membership for
   every round, round boundaries, allocation settlement, and any pruning or gaps.
5. Arrange a separate result writer limited to `reputation_pilot`. The existing
   API account is SELECT-only and cannot write these calculations.
6. Establish private connectivity or a team-managed result API. The operator must
   provision credentials/bindings directly in the appropriate secure service;
   do not paste passwords, keys, or connection strings into this repository/chat.
7. Approve a small pilot scope before running bulk backfill queries. No migration
   or test has been executed against production. No infrastructure has been created.

The first deployment should be private and manual-only. Enable a schedule only
after a few complete rounds pass parity and resume/retry tests. A new-user backfill
needs earlier app/wallet history even when only recent rounds are being displayed.
Testnet user-interaction testing must use a separate network/snapshot namespace.
