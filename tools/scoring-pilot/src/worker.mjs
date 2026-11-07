import pg from 'pg';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { createHandler } from './handler.mjs';

async function verifyAccess(request, env) {
  const domain = env.ACCESS_TEAM_DOMAIN;
  if (!domain || !/^[a-z0-9-]+\.cloudflareaccess\.com$/.test(domain) || !env.ACCESS_AUDIENCE) return false;
  const token = request.headers.get('Cf-Access-Jwt-Assertion');
  if (!token) return false;
  try {
    await jwtVerify(token, createRemoteJWKSet(new URL(`https://${domain}/cdn-cgi/access/certs`)), {
      issuer: `https://${domain}`, audience: env.ACCESS_AUDIENCE, algorithms: ['RS256'],
    });
    return true;
  } catch { return false; }
}

async function withDatabase(env, operation) {
  if (!env.DB) throw new Error('DatabaseNotConfigured');
  const client = new pg.Client({ connectionString: env.DB.connectionString, connectionTimeoutMillis: 3000 });
  try {
    await client.connect();
    await client.query('BEGIN');
    await client.query("SET LOCAL statement_timeout = '5s'");
    await client.query("SET LOCAL lock_timeout = '1s'");
    const result = await operation(client);
    await client.query('COMMIT');
    return result;
  } finally {
    // Closing the connection rolls back any failed transaction; Hyperdrive pools upstream sessions.
    await client.end();
  }
}

export default {
  fetch(request, env) {
    return createHandler({
      authorize: request => verifyAccess(request, env),
      database: operation => withDatabase(env, operation),
      executionEnabled: env.EXECUTION_ENABLED === 'true',
    })(request);
  },
  async scheduled(_event, env) {
    if (env.EXECUTION_ENABLED !== 'true') return;
    // One already-snapshotted round per invocation; no chain ingestion in this handler.
    await withDatabase(env, async db => {
      const { rows } = await db.query(`SELECT run_id FROM reputation_pilot.scoring_run
        WHERE status='pending' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED`);
      if (rows.length) {
        await db.query('SELECT reputation_pilot.execute_run($1)', [rows[0].run_id]);
        console.log(JSON.stringify({ event: 'pilot_run_completed', runId: rows[0].run_id }));
      }
    });
  },
};
