import { dashboardHtml, dashboardJs } from './dashboard.mjs';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

/** Dependencies are request-scoped; numeric Postgres fields remain decimal strings. */
export function createHandler({ authorize, database, executionEnabled = false }) {
  return async request => {
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') {
      return json({ service: 'vebetter-scoring-pilot', status: 'local-pilot', executionEnabled });
    }
    try {
      if (!await authorize(request)) return json({ error: 'Unauthorized' }, 401);
      if (!['GET', 'POST'].includes(request.method)) return json({ error: 'Method not allowed' }, 405);
      if (request.method === 'GET' && (url.pathname === '/' || url.pathname === '/dashboard.js')) {
        const isPage = url.pathname === '/';
        return new Response(isPage ? dashboardHtml : dashboardJs, { headers: {
          'Content-Type': isPage ? 'text/html; charset=utf-8' : 'text/javascript; charset=utf-8',
          'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
          'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        } });
      }

      if (url.pathname === '/api/runs' && request.method === 'GET') {
        const network = url.searchParams.get('network') ?? 'mainnet';
        const round = url.searchParams.get('round');
        if (!['mainnet', 'testnet'].includes(network) || !round || !/^[1-9][0-9]{0,9}$/.test(round)
            || Number(round) > 2147483647) return json({ error: 'Valid network and round required' }, 400);
        const rows = await database(async db => (await db.query(`
          SELECT r.run_id, s.network, s.round_id, r.formula_version, r.code_revision, r.status,
                 r.completed_at, s.expected_app_count, s.new_user_history_complete
          FROM reputation_pilot.scoring_run r
          JOIN reputation_pilot.round_snapshot s USING (snapshot_id)
          WHERE s.network=$1 AND s.round_id=$2 ORDER BY r.created_at DESC LIMIT 20`, [network, round])).rows);
        return json({ runs: rows, maximum: 20 });
      }
      const match = url.pathname.match(/^\/api\/runs\/([^/]+)\/(scores|execute)$/);
      if (!match) return json({ error: 'Not found' }, 404);
      const [, runId, action] = match;
      if (!UUID.test(runId)) return json({ error: 'Valid run ID required' }, 400);
      if (action === 'scores' && request.method === 'GET') {
        const rows = await database(async db => (await db.query(`
          SELECT s.app_id, s.p_active_users, s.p_new_users, s.p_actions, s.activity_score,
                 s.distribution_efficiency, s.distribution_score, s.activity_contribution,
                 s.distribution_contribution, s.subtotal, s.activity_rank,
                 s.distribution_rank, s.subtotal_rank, m.active_users, m.new_users, m.actions,
                 m.allocated, m.distributed
          FROM reputation_pilot.app_round_score s
          JOIN reputation_pilot.scoring_run r USING (run_id)
          JOIN reputation_pilot.app_round_metric m ON m.snapshot_id=s.snapshot_id AND m.app_id=s.app_id
          WHERE s.run_id=$1 AND r.status IN ('completed','validated','approved')
          ORDER BY s.subtotal_rank, s.app_id LIMIT 201`, [runId])).rows);
        if (rows.length > 200) return json({ error: 'Population exceeds pilot response limit' }, 422);
        return json({ runId, subtotalMaximum: 60, scores: rows });
      }
      if (action === 'execute' && request.method === 'POST') {
        if (!executionEnabled) return json({ error: 'Execution disabled pending deployment review' }, 403);
        if (request.headers.get('Origin') !== url.origin) return json({ error: 'Same-origin request required' }, 403);
        const status = await database(async db => (await db.query(
          'SELECT reputation_pilot.execute_run($1) AS status', [runId])).rows[0].status);
        return json({ runId, status });
      }
      return json({ error: 'Method not allowed' }, 405);
    } catch (error) {
      // Never log connection strings, SQL details, user JWTs or arbitrary exception messages.
      console.error(JSON.stringify({ event: 'pilot_request_failed', kind: error?.name ?? 'Error' }));
      return json({ error: 'Pilot unavailable; check database configuration and run readiness' }, 503);
    }
  };
}
