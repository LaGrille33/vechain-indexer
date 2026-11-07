import { createHash, randomUUID } from 'node:crypto';

const ID = /^0x[0-9a-f]{64}$/;
const integer = value => {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]*)$/.test(value) || BigInt(value) > 9223372036854775807n) {
    throw new Error('IntegerStringRequired');
  }
  return value;
};
const decimal = value => {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]{0,59})(\.[0-9]{1,18})?$/.test(value)) throw new Error('DecimalStringRequired');
  return value.includes('.') ? value.replace(/0+$/, '').replace(/\.$/, '') : value;
};
const canonical = value => {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, canonical(value[k])]));
  return value;
};

export function normalizeSnapshot(input) {
  if (!['mainnet', 'testnet'].includes(input.network) || !Number.isInteger(input.round_id)
      || input.round_id < 1 || input.round_id > 2147483647) throw new Error('InvalidNetworkOrRound');
  if (!ID.test(input.source_block_id) || !Number.isSafeInteger(input.round_end_timestamp) || input.round_end_timestamp < 0) throw new Error('InvalidSourceCheckpoint');
  if (typeof input.new_user_history_complete !== 'boolean' || !input.source_manifest
      || Array.isArray(input.source_manifest) || typeof input.source_manifest !== 'object') throw new Error('CoverageAndManifestRequired');
  if (!Array.isArray(input.app_ids) || !input.app_ids.length || !input.app_ids.every(x => typeof x === 'string' && ID.test(x))) throw new Error('AuthoritativeAppIdsRequired');
  const appIds = [...input.app_ids].sort();
  if (new Set(appIds).size !== appIds.length) throw new Error('DuplicateApp');
  if (!Array.isArray(input.metrics)) throw new Error('MetricsRequired');
  const metrics = input.metrics.map(m => {
    if (!ID.test(m.app_id)) throw new Error('InvalidAppId');
    const active = integer(m.active_users), fresh = m.new_users === null ? null : integer(m.new_users);
    if (fresh !== null && BigInt(fresh) > BigInt(active)) throw new Error('NewUsersExceedActive');
    return {app_id:m.app_id, active_users:active, new_users:fresh, actions:integer(m.actions),
      allocated:decimal(m.allocated), distributed:decimal(m.distributed)};
  }).sort((a,b) => a.app_id < b.app_id ? -1 : a.app_id > b.app_id ? 1 : 0);
  if (metrics.length !== appIds.length || metrics.some((m,i) => m.app_id !== appIds[i])) throw new Error('IncompleteAppPopulation');
  if (input.new_user_history_complete && metrics.some(m => m.new_users === null)) throw new Error('CoverageContradiction');
  const normalized = canonical({format_version:'nas-snapshot-v1',network:input.network, round_id:input.round_id,
    source_block_number:integer(input.source_block_number), source_block_id:input.source_block_id,
    round_end_timestamp:input.round_end_timestamp, new_user_history_complete:input.new_user_history_complete,
    source_manifest:input.source_manifest, app_ids:appIds, metrics});
  return {normalized, hash:createHash('sha256').update(JSON.stringify(normalized)).digest('hex')};
}

export async function importSnapshot(db, input, codeRevision) {
  const {normalized:n,hash} = normalizeSnapshot(input);
  // One importer per identical snapshot, without blocking unrelated rounds.
  await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [hash]);
  const existing = await db.query('SELECT snapshot_id FROM reputation_pilot.round_snapshot WHERE network=$1 AND round_id=$2 AND input_hash=$3', [n.network,n.round_id,hash]);
  const snapshotId = existing.rows[0]?.snapshot_id ?? randomUUID();
  if (!existing.rows.length) {
    await db.query(`INSERT INTO reputation_pilot.round_snapshot
      (snapshot_id,network,round_id,source_block_number,source_block_id,round_end_timestamp,input_hash,
       expected_app_count,new_user_history_complete,source_manifest) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10::jsonb)`,
      [snapshotId,n.network,n.round_id,n.source_block_number,n.source_block_id,n.round_end_timestamp,hash,n.app_ids.length,
        n.new_user_history_complete,JSON.stringify({...n.source_manifest,canonical_format:n.format_version})]);
    for (const m of n.metrics) await db.query(`INSERT INTO reputation_pilot.app_round_metric
      (snapshot_id,app_id,active_users,new_users,actions,allocated,distributed) VALUES ($1,$2,$3,$4,$5,$6,$7)`,
      [snapshotId,m.app_id,m.active_users,m.new_users,m.actions,m.allocated,m.distributed]);
  }
  if (!n.new_user_history_complete) return {snapshotId,inputHash:hash,status:'stored_incomplete',runId:null};
  const result = await db.query(`INSERT INTO reputation_pilot.scoring_run (run_id,snapshot_id,formula_version,code_revision)
    VALUES ($1,$2,'activity-distribution-mongo-parity-v1',$3)
    ON CONFLICT (snapshot_id,formula_version,code_revision) DO NOTHING RETURNING run_id`,[randomUUID(),snapshotId,codeRevision]);
  const runId = result.rows[0]?.run_id ?? (await db.query(`SELECT run_id FROM reputation_pilot.scoring_run
    WHERE snapshot_id=$1 AND formula_version='activity-distribution-mongo-parity-v1' AND code_revision=$2`,[snapshotId,codeRevision])).rows[0].run_id;
  return {snapshotId,inputHash:hash,status:'ready',runId};
}
