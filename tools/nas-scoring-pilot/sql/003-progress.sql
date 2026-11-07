-- Additive pilot migration. Apply explicitly to an existing pilot DB; new
-- Compose databases run it on first initialization. No source tables touched.
BEGIN;
CREATE TABLE reputation_pilot.backfill_job (
  job_id UUID PRIMARY KEY,
  network TEXT NOT NULL CHECK (network IN ('mainnet','testnet')),
  source_kind TEXT NOT NULL CHECK (source_kind IN ('prepared-snapshots','blockchain','production-db')),
  plan_hash TEXT NOT NULL CHECK (plan_hash ~ '^[0-9a-f]{64}$'),
  round_ids INT[] NOT NULL CHECK (cardinality(round_ids)>0),
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','running','failed','completed')),
  phase TEXT NOT NULL DEFAULT 'waiting' CHECK (phase IN ('waiting','fetching','calculating','reporting','completed','failed')),
  current_round INT,
  worker_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  started_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  completed_at TIMESTAMPTZ,
  failure_code TEXT,
  report_html TEXT,
  report_json TEXT
);
CREATE TABLE reputation_pilot.backfill_round (
  job_id UUID NOT NULL REFERENCES reputation_pilot.backfill_job,
  round_id INT NOT NULL CHECK (round_id>0),
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','fetching','calculating','completed','failed')),
  attempts INT NOT NULL DEFAULT 0,
  units_done BIGINT NOT NULL DEFAULT 0 CHECK (units_done>=0),
  units_total BIGINT CHECK (units_total>=units_done),
  records_processed BIGINT NOT NULL DEFAULT 0 CHECK (records_processed>=0),
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  duration_seconds DOUBLE PRECISION CHECK (duration_seconds>=0),
  snapshot_id UUID REFERENCES reputation_pilot.round_snapshot,
  run_id UUID REFERENCES reputation_pilot.scoring_run,
  partial BOOLEAN,
  PRIMARY KEY (job_id,round_id)
);
CREATE INDEX backfill_job_recent_idx ON reputation_pilot.backfill_job(created_at DESC);
COMMIT;
