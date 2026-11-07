import { bindings, defineConfig } from 'cf/config';

const databaseId = process.env.PILOT_HYPERDRIVE_ID;

export default defineConfig({
  // VeChain Lab, selected by Jerome. Account IDs are not credentials.
  accountId: '06042335b2a3b462b909c97c7c123068',
  worker: {
    name: 'vebetter-scoring-pilot',
    entrypoint: './src/worker.mjs',
    compatibilityDate: '2026-11-05',
    compatibilityFlags: ['nodejs_compat'],
    env: {
      EXECUTION_ENABLED: bindings.text('false'),
      ACCESS_TEAM_DOMAIN: bindings.text(process.env.PILOT_ACCESS_TEAM_DOMAIN ?? ''),
      ACCESS_AUDIENCE: bindings.text(process.env.PILOT_ACCESS_AUDIENCE ?? ''),
      ...(databaseId ? { DB: bindings.hyperdrive({ id: databaseId }) } : {}),
    },
    observability: {
      enabled: true,
      logs: { enabled: true, headSamplingRate: 1 },
      traces: { enabled: true, headSamplingRate: 0.01 },
    },
    // No schedule or public route until Access, DB role, and region placement are reviewed.
  },
});
