import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile,mkdtemp,rm,writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PGlite } from '@electric-sql/pglite';
import { normalizePlan,createJob,runRounds,loadProgress,estimateProgress,createLogger,requireWorker } from '../src/progress.mjs';
import { createProgressHandler } from '../src/progress-page.mjs';
import { importSnapshot } from '../src/snapshot.mjs';

async function setup(t){const db=new PGlite();await db.waitReady;t.after(()=>db.close());
  for(const file of ['001-schema.sql','002-calculation.sql','003-progress.sql'])await db.exec(await readFile(new URL('../sql/'+file,import.meta.url),'utf8'));
  return db;}
const id=n=>'0x'+n.toString(16).padStart(64,'0');
const task=round=>({round,file:`/imports/${round}.json`,sha256:'0'.repeat(64)});
const input=round=>({network:'mainnet',round_id:round,source_block_number:'1',source_block_id:id(1),round_end_timestamp:1,
  new_user_history_complete:false,source_manifest:{source:'synthetic progress test'},app_ids:[id(2)],metrics:[
    {app_id:id(2),active_users:'1',new_users:null,actions:'2',allocated:'1',distributed:'1'},
  ]});

test('ETA waits for three completed rounds, adjusts for current work and disappears for stale/failed/reporting jobs',()=>{
  const now=Date.parse('2026-11-06T12:00:00Z');
  const job={job_id:randomUUID(),network:'mainnet',source_kind:'blockchain',status:'running',phase:'fetching',current_round:4,
    started_at:new Date(now-200000),updated_at:new Date(now),completed_at:null};
  const tasks=[30,60,90].map((seconds,i)=>({round_id:i+1,status:'completed',duration_seconds:seconds,
    completed_at:new Date(now-(3-i)*10000),units_done:'100',records_processed:'1000',attempts:1}));
  tasks.push({round_id:4,status:'fetching',started_at:new Date(now-10000),attempts:2,units_done:'10',records_processed:'50'});
  tasks.push({round_id:5,status:'pending',attempts:0,units_done:'0',records_processed:'0'});
  const result=estimateProgress(job,tasks,now);assert.equal(result.eta.seconds,110);assert.equal(result.completedRounds,3);
  assert.equal(result.recordsProcessed,'3050');assert.equal(result.retries,1);assert.equal(result.roundPercent,60);
  assert.equal(estimateProgress(job,tasks.slice(1),now).eta,null);
  assert.equal(estimateProgress({...job,updated_at:new Date(now-46000)},tasks,now).stale,true);
  assert.equal(estimateProgress({...job,updated_at:new Date(now-46000)},tasks,now).eta,null);
  assert.equal(estimateProgress({...job,status:'failed'},tasks,now).eta,null);
  assert.equal(estimateProgress({...job,phase:'reporting'},tasks,now).eta,null);
  const failed={...job,status:'failed',phase:'failed',updated_at:new Date(now-60000)};
  assert.equal(estimateProgress(failed,tasks,now).elapsedSeconds,140);
  assert.equal(estimateProgress(failed,tasks,now+3600000).elapsedSeconds,140);
});

test('database cancellation records a useful failure code without publishing SQL or committing a round',async t=>{
  const db=await setup(t),directory=await mkdtemp(join(tmpdir(),'vebetter-cancel-'));t.after(()=>rm(directory,{recursive:true,force:true}));
  const plan=normalizePlan({network:'mainnet',rounds:[task(50)]}),jobId=await db.transaction(tx=>createJob(tx,plan));
  const error=Object.assign(new Error('PRIVATE_SQL_PAYLOAD'),{code:'57014'});
  await assert.rejects(runRounds({transaction:fn=>db.transaction(fn)},{plan,jobId,log:await createLogger(directory,jobId),
    fetchRound:async()=>{throw error;},calculateRound:async()=>{assert.fail('Canceled fetch must not calculate');},
    writeReport:async()=>{assert.fail('Canceled fetch must not publish a report');}}),error);
  const status=(await loadProgress(db,jobId))[0];
  assert.equal(status.status,'failed');assert.equal(status.failureCode,'database_query_canceled');
  assert.equal(status.completedRounds,0);assert.equal(status.reportHtml,null);
  assert.ok(!JSON.stringify(status).includes('PRIVATE_SQL_PAYLOAD'));
  assert.ok(!(await readFile(join(directory,`backfill-${jobId}.jsonl`),'utf8')).includes('PRIVATE_SQL_PAYLOAD'));
});

test('round worker persists checkpoints and resumes after failure without rerunning completed rounds',async t=>{
  const db=await setup(t),directory=await mkdtemp(join(tmpdir(),'vebetter-progress-'));t.after(()=>rm(directory,{recursive:true,force:true}));
  const plan=normalizePlan({network:'mainnet',rounds:[task(96),task(97)]});
  const jobId=await db.transaction(tx=>createJob(tx,plan)),log=await createLogger(directory,jobId),calls=[];
  const adapter={transaction:fn=>db.transaction(fn)};
  const calculateRound=(tx,data)=>importSnapshot(tx,data,'progress-test');
  let fail=true;
  const fetchRound=async(task,checkpoint)=>{calls.push(task.round);
    await checkpoint({unitsDone:1,unitsTotal:1,recordsProcessed:1});
    if(task.round===97&&fail)throw new Error('PRIVATE_FAILURE_MARKER');return input(task.round);};
  await assert.rejects(runRounds(adapter,{plan,jobId,log,fetchRound,calculateRound,writeReport:async()=>({})}));
  let status=(await loadProgress(db,jobId))[0];assert.equal(status.status,'failed');assert.equal(status.completedRounds,1);
  const firstSnapshot=(await db.query('SELECT snapshot_id FROM reputation_pilot.backfill_round WHERE job_id=$1 AND round_id=96',[jobId])).rows[0].snapshot_id;
  fail=false;
  await runRounds(adapter,{plan,jobId,log,fetchRound,calculateRound,writeReport:async()=>({html:'/exports/example.html',json:'/exports/example.json'})});
  status=(await loadProgress(db,jobId))[0];assert.equal(status.status,'completed');assert.equal(status.completedRounds,2);
  assert.equal(status.partialRounds,2);assert.equal(status.reportHtml,'/reports/example.html');
  assert.deepEqual(calls,[96,97,97]);assert.equal(status.retries,1);
  assert.equal((await db.query('SELECT snapshot_id FROM reputation_pilot.backfill_round WHERE job_id=$1 AND round_id=96',[jobId])).rows[0].snapshot_id,firstSnapshot);
  const logs=await readFile(join(directory,`backfill-${jobId}.jsonl`),'utf8');assert.ok(!logs.includes('PRIVATE_FAILURE_MARKER'));
  assert.ok(logs.includes('job_failed'));assert.ok(logs.includes('job_completed'));
  await assert.rejects(db.transaction(tx=>requireWorker(tx,jobId,randomUUID())),/JobOwnershipLost/);
});

test('progress API serves read-only state, rejects malformed selectors and serves only safe report filenames',async t=>{
  const db=await setup(t),directory=await mkdtemp(join(tmpdir(),'vebetter-progress-api-'));t.after(()=>rm(directory,{recursive:true,force:true}));
  const plan=normalizePlan({network:'mainnet',rounds:[task(96)]});await db.transaction(tx=>createJob(tx,plan));
  const handle=createProgressHandler(fn=>fn(db),directory);
  const req=(path,method='GET')=>new Request('http://localhost:8788'+path,{method});
  let response=await handle(req('/api/progress'));assert.equal(response.status,200);assert.equal((await response.json()).jobs.length,1);
  assert.equal((await handle(req('/api/progress/not-a-uuid'))).status,400);
  assert.equal((await handle(req('/api/progress','POST'))).status,405);
  assert.equal((await handle(req('/reports/..%2fconfig%2fdatabase_password'))).status,400);
  await writeFile(join(directory,'safe.html'),'<!doctype html><h1>Report</h1>');
  response=await handle(req('/reports/safe.html'));assert.equal(response.status,200);assert.match(response.headers.get('Content-Security-Policy'),/default-src 'none'/);
  assert.equal((await handle(req('/reports/missing.html'))).status,404);
  const page=await handle(req('/progress'));assert.equal(page.status,200);assert.match(await page.text(),/Blockchain jobs fetch finalized mainnet rewards/);
});

test('resume requires the exact same plan, duplicate rounds and traversal are rejected',async t=>{
  const db=await setup(t),plan=normalizePlan({network:'mainnet',rounds:[task(96)]});
  const jobId=await db.transaction(tx=>createJob(tx,plan));assert.equal(await db.transaction(tx=>createJob(tx,plan,jobId)),jobId);
  await assert.rejects(db.transaction(tx=>createJob(tx,{...plan,hash:'a'.repeat(64)},jobId)),/ResumePlanChanged/);
  assert.throws(()=>normalizePlan({network:'mainnet',rounds:[{...task(96),file:'/imports/../private.json'}]}),/InvalidRoundPlan/);
  assert.throws(()=>normalizePlan({network:'mainnet',rounds:[task(96),{...task(96),file:'/imports/b.json'}]}),/DuplicateRoundPlan/);
  assert.throws(()=>normalizePlan({network:'mainnet',rounds:[{round:96,file:'/imports/96.json'}]}),/InvalidRoundPlan/);
});

test('round calculation and completion checkpoint commit together; report finalization is a separate visible phase',async t=>{
  const db=await setup(t),plan=normalizePlan({network:'mainnet',rounds:[task(96)]});
  const jobId=await db.transaction(tx=>createJob(tx,plan)),adapter={transaction:fn=>db.transaction(fn)};
  await assert.rejects(runRounds(adapter,{plan,jobId,fetchRound:async()=>input(96),calculateRound:async(tx,data)=>{
    await importSnapshot(tx,data,'atomic-test');throw new Error('SyntheticCalculationFailure');
  },writeReport:async()=>({})}));
  assert.equal((await db.query('SELECT count(*) AS n FROM reputation_pilot.round_snapshot')).rows[0].n,0);
  assert.equal((await loadProgress(db,jobId))[0].completedRounds,0);
  await runRounds(adapter,{plan,jobId,fetchRound:async()=>input(96),calculateRound:(tx,data)=>importSnapshot(tx,data,'atomic-test'),writeReport:async()=>{
    const status=(await loadProgress(db,jobId))[0];assert.equal(status.phase,'reporting');
    assert.equal(status.status,'running');assert.equal(status.roundPercent,100);assert.equal(status.eta,null);
    return {html:'/exports/atomic.html',json:'/exports/atomic.json'};
  }});
  assert.equal((await loadProgress(db,jobId))[0].status,'completed');
});
