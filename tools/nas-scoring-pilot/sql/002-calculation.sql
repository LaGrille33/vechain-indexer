-- Run after 001-schema.sql, with DB-owner approval. No production source tables are touched.
BEGIN;

CREATE FUNCTION reputation_pilot.calculate_snapshot(p_snapshot UUID)
RETURNS TABLE (
    app_id TEXT, p_active_users NUMERIC, p_new_users NUMERIC, p_actions NUMERIC,
    activity_score NUMERIC, distribution_efficiency NUMERIC, distribution_score NUMERIC,
    activity_contribution NUMERIC, distribution_contribution NUMERIC, subtotal NUMERIC,
    activity_rank BIGINT, distribution_rank BIGINT, subtotal_rank BIGINT
)
LANGUAGE plpgsql STABLE SECURITY INVOKER
SET search_path = pg_catalog, reputation_pilot
AS $$
DECLARE
    expected INT;
    actual BIGINT;
    coverage BOOLEAN;
BEGIN
    SELECT expected_app_count, new_user_history_complete INTO expected, coverage
    FROM reputation_pilot.round_snapshot WHERE snapshot_id = p_snapshot;
    IF NOT FOUND THEN RAISE EXCEPTION 'snapshot_not_found'; END IF;
    SELECT count(*) INTO actual FROM reputation_pilot.app_round_metric WHERE snapshot_id = p_snapshot;
    IF actual <> expected THEN RAISE EXCEPTION 'incomplete_app_population'; END IF;
    IF NOT coverage OR EXISTS (
        SELECT 1 FROM reputation_pilot.app_round_metric
        WHERE snapshot_id = p_snapshot AND new_users IS NULL
    ) THEN RAISE EXCEPTION 'incomplete_new_user_history'; END IF;

    RETURN QUERY
    WITH measures AS (
        SELECT m.*, CASE WHEN allocated > 0
            THEN least(round(distributed / allocated, 10), 1::numeric)
            ELSE 0::numeric END AS efficiency
        FROM reputation_pilot.app_round_metric m WHERE snapshot_id = p_snapshot
    ), positions AS (
        SELECT measures.*,
            count(*) OVER ()::numeric AS n,
            (rank() OVER (ORDER BY active_users) - 1)::numeric AS below_users,
            count(*) OVER (PARTITION BY active_users)::numeric AS ties_users,
            (rank() OVER (ORDER BY new_users) - 1)::numeric AS below_new,
            count(*) OVER (PARTITION BY new_users)::numeric AS ties_new,
            (rank() OVER (ORDER BY actions) - 1)::numeric AS below_actions,
            count(*) OVER (PARTITION BY actions)::numeric AS ties_actions,
            (rank() OVER (ORDER BY efficiency) - 1)::numeric AS below_efficiency,
            count(*) OVER (PARTITION BY efficiency)::numeric AS ties_efficiency
        FROM measures
    ), percentiles AS (
        -- ln(1+x) is strictly increasing for the nonnegative integer activity metrics.
        -- Rank the integers directly, preserving the Kotlin helper's tie rule and 10-dp division.
        SELECT positions.*,
            round((below_users + CASE WHEN ties_users = 1 THEN 0 ELSE ties_users/2 END)/n, 10)*100 AS pu,
            round((below_new + CASE WHEN ties_new = 1 THEN 0 ELSE ties_new/2 END)/n, 10)*100 AS pn,
            round((below_actions + CASE WHEN ties_actions = 1 THEN 0 ELSE ties_actions/2 END)/n, 10)*100 AS pa,
            round((below_efficiency + CASE WHEN ties_efficiency = 1 THEN 0 ELSE ties_efficiency/2 END)/n, 10)*100 AS pd
        FROM positions
    ), dimensions AS (
        SELECT percentiles.*, round(pu*0.5 + pn*0.3 + pa*0.2, 4) AS activity,
            round(pd, 4) AS distribution
        FROM percentiles
    ), contributions AS (
        SELECT dimensions.*, round(activity*0.3, 2) AS ac, round(distribution*0.3, 2) AS dc
        FROM dimensions
    )
    SELECT c.app_id, round(pu, 4), round(pn, 4), round(pa, 4), activity,
        round(efficiency, 6), distribution, ac, dc, ac+dc,
        rank() OVER (ORDER BY activity DESC), rank() OVER (ORDER BY distribution DESC),
        rank() OVER (ORDER BY ac+dc DESC)
    FROM contributions c ORDER BY ac+dc DESC, c.app_id;
END;
$$;

CREATE FUNCTION reputation_pilot.execute_run(p_run UUID)
RETURNS TEXT
LANGUAGE plpgsql VOLATILE SECURITY INVOKER
SET search_path = pg_catalog, reputation_pilot
AS $$
DECLARE
    r reputation_pilot.scoring_run%ROWTYPE;
BEGIN
    -- Row lock serializes retries. A failed invocation rolls back outputs and state together.
    SELECT * INTO r FROM reputation_pilot.scoring_run WHERE run_id = p_run FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'run_not_found'; END IF;
    IF r.status IN ('completed', 'validated', 'approved') THEN RETURN r.status; END IF;
    IF r.formula_version <> 'activity-distribution-mongo-parity-v1' THEN
        RAISE EXCEPTION 'unsupported_formula_version';
    END IF;
    UPDATE reputation_pilot.scoring_run SET status = 'running',
        started_at = now(), attempts = attempts+1 WHERE run_id = p_run;
    INSERT INTO reputation_pilot.app_round_score
    SELECT p_run, r.snapshot_id, c.* FROM reputation_pilot.calculate_snapshot(r.snapshot_id) c;
    UPDATE reputation_pilot.scoring_run SET status = 'completed', completed_at = now(),
        failure_code = NULL, lease_until = NULL WHERE run_id = p_run;
    RETURN 'completed';
END;
$$;

-- Prevent recalculation from silently modifying saved inputs or completed output versions.
CREATE FUNCTION reputation_pilot.guard_frozen_data()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, reputation_pilot
AS $$
DECLARE
    s UUID;
    frozen BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'app_round_score' THEN
        IF TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'saved_scores_are_immutable';
        END IF;
        SELECT status IN ('completed','validated','approved') INTO frozen
        FROM reputation_pilot.scoring_run WHERE run_id = NEW.run_id FOR UPDATE;
    ELSE
        IF TG_OP <> 'INSERT' THEN
            s := OLD.snapshot_id;
            IF TG_TABLE_NAME = 'round_snapshot' AND TG_OP = 'UPDATE' AND NEW.snapshot_id <> OLD.snapshot_id THEN
                RAISE EXCEPTION 'snapshot_identity_is_immutable';
            END IF;
            IF TG_TABLE_NAME = 'app_round_metric' AND TG_OP = 'UPDATE' AND NEW.snapshot_id <> OLD.snapshot_id THEN
                RAISE EXCEPTION 'snapshot_identity_is_immutable';
            END IF;
        ELSE s := NEW.snapshot_id; END IF;
        -- Snapshot lock also serializes sealing against changes to its metric population.
        PERFORM 1 FROM reputation_pilot.round_snapshot WHERE snapshot_id = s FOR UPDATE;
        SELECT EXISTS (SELECT 1 FROM reputation_pilot.scoring_run WHERE snapshot_id = s)
        INTO frozen;
    END IF;
    IF frozen THEN RAISE EXCEPTION 'snapshot_or_run_is_frozen'; END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$;

CREATE TRIGGER guard_snapshot BEFORE UPDATE OR DELETE ON reputation_pilot.round_snapshot
FOR EACH ROW EXECUTE FUNCTION reputation_pilot.guard_frozen_data();
CREATE TRIGGER guard_metric BEFORE INSERT OR UPDATE OR DELETE ON reputation_pilot.app_round_metric
FOR EACH ROW EXECUTE FUNCTION reputation_pilot.guard_frozen_data();
CREATE TRIGGER guard_score BEFORE INSERT OR UPDATE OR DELETE ON reputation_pilot.app_round_score
FOR EACH ROW EXECUTE FUNCTION reputation_pilot.guard_frozen_data();

CREATE FUNCTION reputation_pilot.guard_run()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY INVOKER
SET search_path = pg_catalog, reputation_pilot
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'pending' THEN RAISE EXCEPTION 'new_run_must_be_pending'; END IF;
        PERFORM 1 FROM reputation_pilot.round_snapshot WHERE snapshot_id = NEW.snapshot_id FOR UPDATE;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'run_history_is_immutable'; END IF;
    IF NEW.snapshot_id <> OLD.snapshot_id OR NEW.formula_version <> OLD.formula_version
       OR NEW.code_revision <> OLD.code_revision OR NEW.run_id <> OLD.run_id THEN
        RAISE EXCEPTION 'run_provenance_is_immutable';
    END IF;
    IF NEW.status = 'completed' AND OLD.status <> 'completed' AND
       (SELECT count(*) FROM reputation_pilot.app_round_score WHERE run_id = OLD.run_id)
       <> (SELECT expected_app_count FROM reputation_pilot.round_snapshot WHERE snapshot_id = OLD.snapshot_id)
    THEN RAISE EXCEPTION 'incomplete_saved_results'; END IF;
    IF OLD.status IN ('completed','validated','approved') AND (
        (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status') OR
        NOT (NEW.status = OLD.status OR
             (OLD.status = 'completed' AND NEW.status = 'validated') OR
             (OLD.status = 'validated' AND NEW.status = 'approved'))
    ) THEN RAISE EXCEPTION 'completed_run_is_immutable'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_run BEFORE INSERT OR UPDATE OR DELETE ON reputation_pilot.scoring_run
FOR EACH ROW EXECUTE FUNCTION reputation_pilot.guard_run();

-- PostgreSQL grants function execution to PUBLIC by default. A DB owner must grant
-- explicit permissions to separate pilot reader/writer roles after reviewing these files.
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA reputation_pilot FROM PUBLIC;
COMMIT;
