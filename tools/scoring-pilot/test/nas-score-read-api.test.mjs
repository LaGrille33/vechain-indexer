import assert from 'node:assert/strict';
import test from 'node:test';

import { createNasScoreReadHandler } from '../src/nas-score-read-api.mjs';

const RUN_ID = '11111111-1111-4111-8111-111111111111';
const APP_ID = `0x${'a'.repeat(64)}`;

test('read API returns only a selected completed run as exact decimal strings', async () => {
  let observedQuery;
  const handler = createNasScoreReadHandler({
    publishedRunId: RUN_ID,
    database: {
      readOnly: async operation => operation({
        query: async (query, values) => {
          observedQuery = { query, values };
          return { rows: [{
            app_id: APP_ID, network: 'mainnet', round_id: 122, round_end_timestamp: '1770000000',
            run_id: RUN_ID, status: 'completed', formula_version: 'v1', code_revision: 'abc',
            new_user_history_complete: false, subtotal: '51.00', subtotal_rank: 3,
            activity_contribution: '21.00', distribution_contribution: '30.00',
            activity_score: '70.0000', distribution_score: '100.0000',
          }] };
        },
      }),
    },
  });

  const result = await handler(new Request(`http://score-api:8789/v1/apps/${APP_ID}/score`));
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), {
    appId: APP_ID, sourceAppId: APP_ID, source: 'nas-postgres', status: 'completed', partial: true, maximum: '60',
    network: 'mainnet', round: 122, roundDate: '1770000000', runId: RUN_ID,
    formulaVersion: 'v1', codeRevision: 'abc', newUserHistoryComplete: false,
    subtotal: '51.00', rank: 3, activityContribution: '21.00', distributionContribution: '30.00',
    activityScore: '70.0000', distributionScore: '100.0000',
  });
  assert.deepEqual(observedQuery.values, [RUN_ID, APP_ID, ['completed', 'validated', 'approved']]);
});

test('read API uses an explicit testnet-to-mainnet app mapping', async () => {
  const testnetAppId = `0x${'b'.repeat(64)}`;
  let sourceAppId;
  const handler = createNasScoreReadHandler({
    publishedRunId: RUN_ID,
    appIdMap: { [testnetAppId]: APP_ID },
    database: { readOnly: async operation => operation({ query: async (_query, values) => {
      sourceAppId = values[1];
      return { rows: [{
        app_id: APP_ID, network: 'mainnet', round_id: 1, round_end_timestamp: '1', run_id: RUN_ID,
        status: 'completed', formula_version: 'v1', code_revision: 'abc', new_user_history_complete: true,
        subtotal: '60.00', subtotal_rank: 1, activity_contribution: '30.00', distribution_contribution: '30.00',
        activity_score: '100', distribution_score: '100',
      }] };
    } }) },
  });
  const result = await handler(new Request(`http://score-api:8789/v1/apps/${testnetAppId}/score`));
  assert.equal(result.status, 200);
  assert.equal(sourceAppId, APP_ID);
  assert.equal((await result.json()).appId, testnetAppId);
});


test('read API rejects malformed requests and hides unpublished data', async () => {
  const handler = createNasScoreReadHandler({ publishedRunId: RUN_ID, database: { readOnly: async () => ({}) } });
  assert.equal((await handler(new Request('http://score-api:8789/v1/apps/not-an-app/score'))).status, 404);
  assert.equal((await handler(new Request(`http://score-api:8789/v1/apps/${APP_ID}/score`, { method: 'POST' }))).status, 405);
  assert.throws(() => createNasScoreReadHandler({ publishedRunId: 'not-a-uuid', database: {} }), /PublishedRunIdRequired/);
});
