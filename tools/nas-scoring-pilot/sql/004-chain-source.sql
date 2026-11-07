-- Additive source cache, confined to the pilot schema. No production DB access.
BEGIN;
ALTER TABLE reputation_pilot.backfill_job ADD COLUMN source_plan JSONB;
CREATE TABLE reputation_pilot.chain_round_cache (
  network TEXT NOT NULL CHECK (network='mainnet'),
  policy TEXT NOT NULL,
  round_id INT NOT NULL CHECK (round_id>0),
  from_block BIGINT NOT NULL,
  to_block BIGINT NOT NULL,
  end_block_id TEXT NOT NULL,
  boundaries JSONB NOT NULL,
  cursor_block BIGINT NOT NULL,
  cursor_offset INT NOT NULL DEFAULT 0,
  pages INT NOT NULL DEFAULT 0,
  event_count BIGINT NOT NULL DEFAULT 0,
  digest TEXT NOT NULL,
  finished BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY(network,policy,round_id)
);
CREATE TABLE reputation_pilot.chain_wallet_round (
  network TEXT NOT NULL,
  policy TEXT NOT NULL,
  round_id INT NOT NULL CHECK (round_id>=0),
  app_id TEXT NOT NULL,
  wallet TEXT NOT NULL,
  actions BIGINT NOT NULL CHECK (actions>0),
  amount_raw NUMERIC(78,0) NOT NULL CHECK (amount_raw>=0),
  PRIMARY KEY(network,policy,round_id,app_id,wallet)
);
CREATE TABLE reputation_pilot.chain_wallet_first (
  network TEXT NOT NULL,
  policy TEXT NOT NULL,
  app_id TEXT NOT NULL,
  wallet TEXT NOT NULL,
  first_round INT NOT NULL CHECK (first_round>=0),
  PRIMARY KEY(network,policy,app_id,wallet)
);
CREATE INDEX chain_first_round_idx ON reputation_pilot.chain_wallet_first(network,policy,first_round,app_id);
COMMIT;
