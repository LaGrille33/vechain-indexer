-- Standalone proposal, NOT an automatic Flyway migration. Apply only after DB-owner review.
-- This schema stores derived data; it does not modify any existing indexer table.
BEGIN;
CREATE SCHEMA IF NOT EXISTS reputation_pilot;

CREATE TABLE reputation_pilot.round_snapshot (
    snapshot_id UUID PRIMARY KEY,
    network TEXT NOT NULL CHECK (network IN ('mainnet', 'testnet')),
    round_id INT NOT NULL CHECK (round_id > 0),
    source_block_number BIGINT NOT NULL CHECK (source_block_number >= 0),
    source_block_id TEXT NOT NULL CHECK (source_block_id ~ '^0x[0-9a-f]{64}$'),
    round_end_timestamp BIGINT NOT NULL,
    input_hash TEXT NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    expected_app_count INT NOT NULL CHECK (expected_app_count > 0),
    new_user_history_complete BOOLEAN NOT NULL,
    source_manifest JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (network, round_id, input_hash)
);

CREATE TABLE reputation_pilot.app_round_metric (
    snapshot_id UUID NOT NULL REFERENCES reputation_pilot.round_snapshot,
    app_id TEXT NOT NULL CHECK (app_id ~ '^0x[0-9a-f]{64}$'),
    active_users BIGINT NOT NULL CHECK (active_users >= 0),
    new_users BIGINT CHECK (new_users >= 0 AND new_users <= active_users),
    actions BIGINT NOT NULL CHECK (actions >= 0),
    allocated NUMERIC(78,18) NOT NULL CHECK (allocated >= 0),
    distributed NUMERIC(78,18) NOT NULL CHECK (distributed >= 0),
    PRIMARY KEY (snapshot_id, app_id)
);

CREATE TABLE reputation_pilot.scoring_run (
    run_id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES reputation_pilot.round_snapshot,
    formula_version TEXT NOT NULL,
    code_revision TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'running', 'completed', 'validated', 'approved', 'failed')),
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    failure_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (snapshot_id, formula_version, code_revision),
    UNIQUE (run_id, snapshot_id)
);

CREATE TABLE reputation_pilot.app_round_score (
    run_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    app_id TEXT NOT NULL,
    p_active_users NUMERIC(7,4) NOT NULL CHECK (p_active_users BETWEEN 0 AND 100),
    p_new_users NUMERIC(7,4) NOT NULL CHECK (p_new_users BETWEEN 0 AND 100),
    p_actions NUMERIC(7,4) NOT NULL CHECK (p_actions BETWEEN 0 AND 100),
    activity_score NUMERIC(7,4) NOT NULL CHECK (activity_score BETWEEN 0 AND 100),
    distribution_efficiency NUMERIC(7,6) NOT NULL CHECK (distribution_efficiency BETWEEN 0 AND 1),
    distribution_score NUMERIC(7,4) NOT NULL CHECK (distribution_score BETWEEN 0 AND 100),
    activity_contribution NUMERIC(4,2) NOT NULL CHECK (activity_contribution BETWEEN 0 AND 30),
    distribution_contribution NUMERIC(4,2) NOT NULL CHECK (distribution_contribution BETWEEN 0 AND 30),
    subtotal NUMERIC(4,2) NOT NULL CHECK (subtotal BETWEEN 0 AND 60),
    activity_rank INT NOT NULL CHECK (activity_rank > 0),
    distribution_rank INT NOT NULL CHECK (distribution_rank > 0),
    subtotal_rank INT NOT NULL CHECK (subtotal_rank > 0),
    PRIMARY KEY (run_id, app_id),
    FOREIGN KEY (run_id, snapshot_id) REFERENCES reputation_pilot.scoring_run (run_id, snapshot_id),
    FOREIGN KEY (snapshot_id, app_id) REFERENCES reputation_pilot.app_round_metric,
    CHECK (subtotal = activity_contribution + distribution_contribution)
);

CREATE INDEX scoring_run_pending_idx ON reputation_pilot.scoring_run (status, lease_until, created_at);
CREATE INDEX round_snapshot_history_idx ON reputation_pilot.round_snapshot (network, round_id DESC);
CREATE INDEX app_round_metric_history_idx ON reputation_pilot.app_round_metric (app_id, snapshot_id);
COMMIT;
