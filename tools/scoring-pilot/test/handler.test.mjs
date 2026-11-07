import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHandler } from '../src/handler.mjs';
import { dashboardJs } from '../src/dashboard.mjs';

const run = '00000000-0000-4000-8000-000000000001';
const req = (path, options) => new Request(`https://pilot.example${path}`, options);

test('protected routes fail closed before database access', async () => {
  const handler = createHandler({ authorize: async () => false, database: () => assert.fail('DB must not be accessed') });
  assert.equal((await handler(req('/api/runs?round=122'))).status, 401);
  assert.equal((await handler(req('/health'))).status, 200);
  assert.equal((await handler(req('/'))).status, 401);
});

test('the dashboard and its script require authorization and use a restrictive script policy', async () => {
  const handler = createHandler({ authorize: async () => true, database: () => assert.fail('DB must not be accessed') });
  const page = await handler(req('/'));
  assert.equal(page.status, 200);
  assert.match(page.headers.get('Content-Security-Policy'), /script-src 'self'/);
  assert.match(await page.text(), /Subtotal \/60/);
  const script = await handler(req('/dashboard.js'));
  assert.match(script.headers.get('Content-Type'), /javascript/);
  assert.doesNotThrow(() => new Function(dashboardJs));
});
test('round and run IDs are validated before SQL', async () => {
  const handler = createHandler({ authorize: async () => true, database: () => assert.fail('DB must not be accessed') });
  assert.equal((await handler(req('/api/runs?round=1%20OR%201=1'))).status, 400);
  assert.equal((await handler(req('/api/runs?round=2147483648'))).status, 400);
  assert.equal((await handler(req('/api/runs/invalid/scores'))).status, 400);
});
test('API preserves numeric strings and clearly labels the partial subtotal', async () => {
  const handler = createHandler({ authorize: async () => true, database: operation => operation({
    query: async (_sql, params) => {
      assert.deepEqual(params, [run]);
      return { rows: [{ subtotal: '40.00', activity_score: '66.6667' }] };
    },
  }) });
  const result = await (await handler(req(`/api/runs/${run}/scores`))).json();
  assert.equal(result.subtotalMaximum, 60);
  assert.equal(result.scores[0].activity_score, '66.6667');
});
test('execution requires both explicit enablement and same origin', async () => {
  const dependencies = { authorize: async () => true, database: () => assert.fail('DB must not be accessed') };
  const disabled = createHandler(dependencies);
  assert.equal((await disabled(req(`/api/runs/${run}/execute`, { method: 'POST' }))).status, 403);
  const enabled = createHandler({ ...dependencies, executionEnabled: true });
  assert.equal((await enabled(req(`/api/runs/${run}/execute`, { method: 'POST', headers: { Origin: 'https://other.example' } }))).status, 403);
});
test('database failures do not expose exception contents', async () => {
  const handler = createHandler({ authorize: async () => true,
    database: async () => { throw new Error('private-database-details'); } });
  const result = await handler(req('/api/runs?round=122'));
  assert.equal(result.status, 503);
  assert.ok(!(await result.text()).includes('private-database-details'));
});
