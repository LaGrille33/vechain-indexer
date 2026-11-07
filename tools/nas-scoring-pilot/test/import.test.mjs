import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PGlite } from '@electric-sql/pglite';
import { importSnapshot } from '../src/snapshot.mjs';

const id = digit => '0x'+digit.repeat(64);
const sample = complete => ({network:'mainnet',round_id:96,source_block_number:'1',source_block_id:id('a'),
  round_end_timestamp:1,new_user_history_complete:complete, source_manifest:{source:'synthetic validation only'},
  app_ids:[id('1'),id('2')],metrics:[
    {app_id:id('1'),active_users:'10',new_users:complete?'2':null,actions:'20',allocated:'10',distributed:'5'},
    {app_id:id('2'),active_users:'0',new_users:complete?'0':null,actions:'0',allocated:'10',distributed:'0'},
  ]});
async function setup(t) {
  const db = new PGlite(); await db.waitReady;
  t.after(() => db.close());
  for (const name of ['001-schema.sql','002-calculation.sql']) await db.exec(await readFile(new URL('../sql/'+name,import.meta.url),'utf8'));
  return db;
}

test('normalized import, retry and SQL execution persist one exact version; frozen history rejects changes', async t => {
  const db=await setup(t), input=sample(true);
  const a=await db.transaction(tx=>importSnapshot(tx,input,'test-revision'));
  const b=await db.transaction(tx=>importSnapshot(tx,input,'test-revision'));
  assert.equal(a.runId,b.runId);assert.equal(a.snapshotId,b.snapshotId);
  assert.equal((await db.query('SELECT count(*) AS n FROM reputation_pilot.app_round_metric')).rows[0].n,2);
  for (let i=0;i<2;i++) assert.equal((await db.query('SELECT reputation_pilot.execute_run($1) AS status',[a.runId])).rows[0].status,'completed');
  const rows=(await db.query(`SELECT app_id,activity_score::text,distribution_score::text,subtotal::text
    FROM reputation_pilot.app_round_score ORDER BY app_id`)).rows;
  assert.equal(rows.length,2);
  assert.deepEqual(rows.map(x=>[x.activity_score,x.distribution_score,x.subtotal]),[
    ['50.0000','50.0000','30.00'],['0.0000','0.0000','0.00'],
  ]);
  await assert.rejects(db.query('UPDATE reputation_pilot.app_round_metric SET actions=21 WHERE snapshot_id=$1',[a.snapshotId]));
  const changed=structuredClone(input);changed.metrics[0].actions='21';
  const c=await db.transaction(tx=>importSnapshot(tx,changed,'test-revision'));
  assert.notEqual(c.inputHash,a.inputHash);assert.notEqual(c.snapshotId,a.snapshotId);
});

test('unknown new-user history is stored without a scoring run; later verified data creates another snapshot', async t => {
  const db=await setup(t);
  const a=await db.transaction(tx=>importSnapshot(tx,sample(false),'test-revision'));
  assert.equal(a.status,'stored_incomplete');assert.equal(a.runId,null);
  assert.equal((await db.query('SELECT count(*) AS n FROM reputation_pilot.scoring_run')).rows[0].n,0);
  await assert.rejects(db.query('SELECT * FROM reputation_pilot.calculate_snapshot($1)',[a.snapshotId]));
  const b=await db.transaction(tx=>importSnapshot(tx,sample(true),'test-revision'));
  assert.notEqual(b.snapshotId,a.snapshotId);assert.equal(b.status,'ready');
});
