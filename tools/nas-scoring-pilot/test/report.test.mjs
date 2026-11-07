import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PGlite } from '@electric-sql/pglite';
import { importSnapshot } from '../src/snapshot.mjs';
import { loadReport, renderReport, saveReport } from '../src/report.mjs';

const id=n=>'0x'+n.toString(16).padStart(64,'0');
const snapshot=(round=96,complete=false)=>({network:'mainnet',round_id:round,source_block_number:'1',source_block_id:id(10),round_end_timestamp:1,
  new_user_history_complete:complete,source_manifest:{source:'synthetic test only'},app_ids:[id(1),id(2)],metrics:[
    {app_id:id(1),active_users:'10',new_users:complete?'2':null,actions:'20',allocated:'10',distributed:'5'},
    {app_id:id(2),active_users:'0',new_users:'0',actions:'0',allocated:'10',distributed:'0'},
  ]});
async function setup(t) {
  const db=new PGlite();await db.waitReady;t.after(()=>db.close());
  for (const file of ['001-schema.sql','002-calculation.sql']) await db.exec(await readFile(new URL('../sql/'+file,import.meta.url),'utf8'));
  return db;
}

test('report matches all exact Kotlin fixture decimals/ranks and reconciles persisted runs', async t=>{
  const db=await setup(t);
  const fixtures=JSON.parse(await readFile(new URL('./fixtures/kotlin-reference.json',import.meta.url),'utf8'));
  for (let i=0;i<fixtures.length;i++) {
    const fixture=fixtures[i],input=snapshot(100+i,true);
    input.app_ids=fixture.inputs.map(x=>x.appId);
    input.metrics=fixture.inputs.map(x=>({app_id:x.appId,active_users:x.activeUsers,new_users:x.newUsers,
      actions:x.actions,allocated:x.allocated,distributed:x.distributed}));
    const imported=await db.transaction(tx=>importSnapshot(tx,input,'fixture-test'));
    await db.query('SELECT reputation_pilot.execute_run($1)',[imported.runId]);
    const report=await loadReport(db,{network:'mainnet',round:input.round_id});
    const round=report.rounds[0];assert.equal(round.availableMaximum,60);assert.equal(round.savedParity,true,fixture.name);
    for (const expected of fixture.expected) {
      const row=round.rows.find(x=>x.app_id===expected.app_id);assert.ok(row);
      for (const [key,val] of Object.entries(expected)) assert.equal(row[key],val,`${fixture.name}: ${key}`);
    }
  }
  assert.equal((await loadReport(db,{network:'mainnet'})).rounds.length,fixtures.length);
});

test('unknown new-user history produces a labelled /51 partial report with no invented Activity/new-user scores',async t=>{
  const db=await setup(t);await db.transaction(tx=>importSnapshot(tx,snapshot(),'partial-test'));
  const report=await loadReport(db,{network:'mainnet',round:96}),r=report.rounds[0];
  assert.equal(r.newUsersAvailable,false);assert.equal(r.availableMaximum,51);assert.equal(r.savedRun,null);
  assert.equal(r.rows[0].activity_score,null);assert.equal(r.rows[0].p_new_users,null);
  assert.equal(r.rows[0].activity_available_score,'35.0000');assert.equal(r.rows[0].available_subtotal,'25.50');
  const html=renderReport(report);assert.match(html,/51\/100/);assert.match(html,/new-user component excluded/);
  assert.match(html,/no completed full scoring run/);assert.match(html,/not the full reputation score/);
});

test('report includes more than 200 apps, escapes app labels and excludes arbitrary private provenance',async t=>{
  const db=await setup(t),input=snapshot();
  input.app_ids=Array.from({length:205},(_,i)=>id(i+1));
  input.metrics=input.app_ids.map(app_id=>({app_id,active_users:'0',new_users:null,actions:'0',allocated:'0',distributed:'0'}));
  input.source_manifest={app_names:{[id(1)]:'<script>alert(1)</script>'},private_note:'PRIVATE_REPORT_TEST_MARKER'};
  await db.transaction(tx=>importSnapshot(tx,input,'large-population-test'));
  const report=await loadReport(db,{network:'mainnet'}),html=renderReport(report);
  assert.equal(report.rounds[0].rows.length,205);assert.match(html,new RegExp(id(205)));
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'));assert.ok(!html.includes('<script>alert(1)</script>'));
  assert.ok(!html.includes('PRIVATE_REPORT_TEST_MARKER'));assert.ok(!JSON.stringify(report).includes('PRIVATE_REPORT_TEST_MARKER'));
});

test('incomplete population withholds rankings and scores; invalid/no-data selectors fail explicitly',async t=>{
  const db=await setup(t),a=await db.transaction(tx=>importSnapshot(tx,snapshot(),'incomplete-test'));
  await db.query('UPDATE reputation_pilot.round_snapshot SET expected_app_count=3 WHERE snapshot_id=$1',[a.snapshotId]);
  const report=await loadReport(db,{network:'mainnet',round:96}),r=report.rounds[0];
  assert.equal(r.populationComplete,false);assert.equal(r.availableMaximum,null);
  for (const row of r.rows) {assert.equal(row.available_rank,null);assert.equal(row.available_subtotal,null);assert.equal(row.p_active_users,null);}
  assert.match(renderReport(report),/scores and ranks withheld/);
  await assert.rejects(loadReport(db,{network:'testnet',round:96}),/NoSavedSnapshots/);
  await assert.rejects(loadReport(db,{network:'mainnet',round:'96 OR 1=1'}),/InvalidReportRound/);
});

test('report export saves self-contained HTML and exact JSON under unique filenames',async t=>{
  const db=await setup(t);await db.transaction(tx=>importSnapshot(tx,snapshot(),'export-test'));
  const directory=await mkdtemp(join(tmpdir(),'vebetter-report-'));t.after(()=>rm(directory,{recursive:true,force:true}));
  const report=await loadReport(db,{network:'mainnet',round:96});report.reportCodeRevision='test';
  const first=await saveReport(report,directory),second=await saveReport(report,directory);
  assert.notEqual(first.html,second.html);assert.equal(first.apps,2);assert.match(first.htmlSha256,/^[0-9a-f]{64}$/);
  assert.ok((await readFile(first.html,'utf8')).startsWith('<!doctype html>'));
  assert.equal(JSON.parse(await readFile(first.json,'utf8')).rounds[0].rows[0].available_subtotal,'25.50');
});
