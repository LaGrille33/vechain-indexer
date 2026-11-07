import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import pg from 'pg';

const APP_ID = /^0x[0-9a-f]{64}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const COMPLETED_RUN_STATUSES = ['completed', 'validated', 'approved'];

const response = (body, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: {
    'Cache-Control': 'no-store',
    'Content-Type': 'application/json; charset=utf-8',
    'X-Content-Type-Options': 'nosniff',
  },
});

/**
 * Read-only adapter between an operator-selected immutable scoring run and the
 * NAS test website. It never calculates, promotes or mutates a scoring run.
 */
export function createNasScoreReadHandler({ database, publishedRunId, appIdMap = {} }) {
  if (!UUID.test(publishedRunId ?? '')) throw new Error('PublishedRunIdRequired');

  return async request => {
    const url = new URL(request.url);
    if (request.method !== 'GET') return response({ error: 'Method not allowed' }, 405);
    if (url.pathname === '/health') return response({ service: 'nas-score-read-api', status: 'ready' });

    const match = url.pathname.match(/^\/v1\/apps\/(0x[0-9a-fA-F]{64})\/score$/);
    if (!match) return response({ error: 'Not found' }, 404);
    const requestedAppId = match[1].toLowerCase();
    if (!APP_ID.test(requestedAppId)) return response({ error: 'Valid app ID required' }, 400);
    // Testnet and mainnet registrations can have different app IDs. A mapping is
    // explicit and operator-reviewed; an unmapped ID is intentionally queried as-is.
    const sourceAppId = appIdMap[requestedAppId] ?? requestedAppId;

    try {
      const row = await database.readOnly(async client => (await client.query(
        `SELECT score.app_id, snapshot.network, snapshot.round_id, snapshot.round_end_timestamp,
                run.run_id, run.status, run.formula_version, run.code_revision,
                snapshot.new_user_history_complete, score.subtotal, score.subtotal_rank,
                score.activity_contribution, score.distribution_contribution,
                score.activity_score, score.distribution_score
           FROM reputation_pilot.app_round_score score
           JOIN reputation_pilot.scoring_run run USING (run_id)
           JOIN reputation_pilot.round_snapshot snapshot USING (snapshot_id)
          WHERE run.run_id=$1 AND score.app_id=$2
            AND run.status = ANY($3::text[])`,
        [publishedRunId, sourceAppId, COMPLETED_RUN_STATUSES],
      )).rows[0]);
      if (!row) return response({ error: 'Score not found' }, 404);

      // Numeric values remain strings. The browser must not introduce floating-point
      // differences into the independently saved score calculation.
      return response({
        appId: requestedAppId,
        sourceAppId: row.app_id,
        source: 'nas-postgres',
        status: row.status,
        partial: true,
        maximum: '60',
        network: row.network,
        round: row.round_id,
        roundDate: row.round_end_timestamp,
        runId: row.run_id,
        formulaVersion: row.formula_version,
        codeRevision: row.code_revision,
        newUserHistoryComplete: row.new_user_history_complete,
        subtotal: row.subtotal,
        rank: row.subtotal_rank,
        activityContribution: row.activity_contribution,
        distributionContribution: row.distribution_contribution,
        activityScore: row.activity_score,
        distributionScore: row.distribution_score,
      });
    } catch (error) {
      console.error(JSON.stringify({ event: 'nas_score_read_failed', kind: error?.name ?? 'Error' }));
      return response({ error: 'Score source unavailable' }, 503);
    }
  };
}

export async function loadAppIdMap(file) {
  if (!file) return {};
  const value = JSON.parse(await readFile(file, 'utf8'));
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('InvalidAppIdMap');
  return Object.fromEntries(Object.entries(value).map(([testnetAppId, mainnetAppId]) => {
    const testnet = testnetAppId.toLowerCase();
    const mainnet = typeof mainnetAppId === 'string' ? mainnetAppId.toLowerCase() : '';
    if (!APP_ID.test(testnet) || !APP_ID.test(mainnet)) throw new Error('InvalidAppIdMap');
    return [testnet, mainnet];
  }));
}

export async function createReadOnlyDatabase(env = process.env) {
  if (!env.PGPASSWORD_FILE) throw new Error('PasswordFileRequired');
  const password = (await readFile(env.PGPASSWORD_FILE, 'utf8')).replace(/\r?\n$/, '');
  if (!password) throw new Error('EmptyDatabasePassword');
  const pool = new pg.Pool({
    host: env.PGHOST,
    port: Number(env.PGPORT ?? 5432),
    database: env.PGDATABASE,
    user: env.PGUSER,
    password,
    max: 2,
    connectionTimeoutMillis: 5000,
    idleTimeoutMillis: 10000,
  });
  return {
    async readOnly(operation) {
      const client = await pool.connect();
      try {
        await client.query('BEGIN READ ONLY');
        await client.query("SET LOCAL statement_timeout = '5s'");
        await client.query("SET LOCAL lock_timeout = '1s'");
        const result = await operation(client);
        await client.query('ROLLBACK');
        return result;
      } catch (error) {
        await client.query('ROLLBACK').catch(() => {});
        throw error;
      } finally {
        client.release();
      }
    },
    close: () => pool.end(),
  };
}

export function createServer({ handler, origin = 'http://score-api:8789' }) {
  const expected = new URL(origin);
  return http.createServer(async (incoming, outgoing) => {
    const reject = (status, error) => {
      outgoing.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      outgoing.end(JSON.stringify({ error }));
    };
    if (incoming.headers.host !== expected.host) return reject(403, 'Unexpected host');
    if (!incoming.url?.startsWith('/') || incoming.url.startsWith('//')) return reject(400, 'Invalid request target');
    if (incoming.headers['sec-fetch-site'] === 'cross-site') return reject(403, 'Cross-site request denied');
    try {
      const result = await handler(new Request(new URL(incoming.url, expected), {
        method: incoming.method ?? 'GET', headers: incoming.headers,
      }));
      outgoing.writeHead(result.status, Object.fromEntries(result.headers));
      outgoing.end(Buffer.from(await result.arrayBuffer()));
    } catch {
      reject(503, 'Score source unavailable');
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const database = await createReadOnlyDatabase();
  const appIdMap = await loadAppIdMap(process.env.APP_ID_MAP_FILE);
  const handler = createNasScoreReadHandler({ database, publishedRunId: process.env.PUBLISHED_RUN_ID, appIdMap });
  const server = createServer({ handler, origin: process.env.SCORE_API_ORIGIN ?? 'http://score-api:8789' });
  server.listen(8789, '0.0.0.0', () => console.log(JSON.stringify({ event: 'nas_score_read_api_listening', port: 8789 })));
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => server.close(() => database.close().then(() => process.exit(0))));
}
