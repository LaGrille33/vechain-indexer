import { createHash, randomUUID } from 'node:crypto';
import { appendFile, mkdir, rename, stat } from 'node:fs/promises';
import { join, basename } from 'node:path';

export const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function safeFailureCode(error){
  // PostgreSQL SQLSTATE is controlled metadata; never persist SQL/exception text.
  const databaseCodes={'57014':'database_query_canceled','55P03':'database_lock_unavailable',
    '53100':'database_disk_full','53200':'database_out_of_memory','53300':'database_connection_limit'};
  if(databaseCodes[error?.code])return databaseCodes[error.code];
  const codes={PublicSourceRequestFailed:'public_source_request_failed',SourceCountReconciliationMismatch:'source_counts_mismatch',
    SourceAmountReconciliationMismatch:'source_amount_mismatch',SourceCacheBoundaryMismatch:'source_boundary_mismatch',
    EmissionBoundaryUnavailable:'emission_boundary_unavailable',SingleBlockLogLimit:'single_block_log_limit',
    LogIndexesRequired:'log_indexes_required',SourceViewReverted:'source_view_reverted',FinalizedSourceChanged:'finalized_source_changed'};
  return codes[error?.message]??'job_step_failed';
}
export function normalizePlan(plan) {
  if (!['mainnet','testnet'].includes(plan.network) || !Array.isArray(plan.rounds) || !plan.rounds.length) throw new Error('InvalidJobPlan');
  const rounds=plan.rounds.map(r=>{
    if (!Number.isInteger(r.round) || r.round<1 || r.round>2147483647 || typeof r.file!=='string'
      || !/^\/imports\/[^\0]+\.json$/.test(r.file) || r.file.split('/').includes('..')
      || typeof r.sha256!=='string'||!/^[0-9a-f]{64}$/.test(r.sha256)) throw new Error('InvalidRoundPlan');
    return {round:r.round,file:r.file,sha256:r.sha256};
  }).sort((a,b)=>a.round-b.round);
  if (new Set(rounds.map(r=>r.round)).size!==rounds.length) throw new Error('DuplicateRoundPlan');
  const normalized={network:plan.network,source_kind:'prepared-snapshots',rounds};
  return {...normalized,hash:createHash('sha256').update(JSON.stringify(normalized)).digest('hex')};
}

export async function createJob(db,plan,jobId=randomUUID()) {
  if (!UUID.test(jobId)) throw new Error('JobUuidRequired');
  await db.query(`INSERT INTO reputation_pilot.backfill_job(job_id,network,source_kind,plan_hash,round_ids)
    VALUES ($1,$2,$3,$4,$5) ON CONFLICT (job_id) DO NOTHING`,[jobId,plan.network,plan.source_kind,plan.hash,plan.rounds.map(r=>r.round)]);
  const job=(await db.query('SELECT plan_hash FROM reputation_pilot.backfill_job WHERE job_id=$1',[jobId])).rows[0];
  if (job.plan_hash!==plan.hash) throw new Error('ResumePlanChanged');
  for (const round of plan.rounds) await db.query(`INSERT INTO reputation_pilot.backfill_round(job_id,round_id)
    VALUES ($1,$2) ON CONFLICT DO NOTHING`,[jobId,round.round]);
  return jobId;
}

export async function requireWorker(db,jobId,workerId) {
  const result=await db.query('SELECT worker_id FROM reputation_pilot.backfill_job WHERE job_id=$1 FOR UPDATE',[jobId]);
  if (result.rows[0]?.worker_id!==workerId) throw new Error('JobOwnershipLost');
}

export function estimateProgress(job,tasks,now=Date.now()) {
  const completed=tasks.filter(r=>r.status==='completed'),remaining=tasks.length-completed.length;
  const stale=job.status==='running' && now-new Date(job.updated_at).getTime()>45000;
  const stoppedAt=job.completed_at??(job.status==='failed'?job.updated_at:null);
  const elapsed=job.started_at?Math.max(0,((stoppedAt?new Date(stoppedAt).getTime():now)-new Date(job.started_at).getTime())/1000):0;
  let eta=null,reason='Waiting for a running job';
  if (job.status==='completed') {eta={seconds:0,lowerSeconds:0,upperSeconds:0,estimatedFinish:job.completed_at};reason='Completed';}
  else if (stale) reason='No heartbeat for over 45 seconds; estimate withheld';
  else if (job.status==='failed') reason='Job failed; resume required';
  else if (job.phase==='reporting') reason='Rounds complete; report generation in progress';
  else if (job.status==='running') {
    const samples=completed.filter(r=>Number.isFinite(Number(r.duration_seconds)) && Number(r.duration_seconds)>0)
      .sort((a,b)=>new Date(a.completed_at)-new Date(b.completed_at)).slice(-5).map(r=>Number(r.duration_seconds));
    if (samples.length<3) reason=`Collecting timings (${samples.length}/3 completed rounds)`;
    else {
      const mean=samples.reduce((a,b)=>a+b,0)/samples.length;
      const current=tasks.find(r=>r.round_id===job.current_round && r.status!=='completed');
      const currentElapsed=current?.started_at?Math.max(0,(now-new Date(current.started_at).getTime())/1000):0;
      const seconds=Math.max(0,mean*remaining-Math.min(mean,currentElapsed));
      eta={seconds:Math.ceil(seconds),lowerSeconds:Math.floor(seconds*0.6),upperSeconds:Math.ceil(seconds*1.8),
        estimatedFinish:new Date(now+seconds*1000).toISOString(),sampleRounds:samples.length};
      reason='Rough estimate from the last completed rounds; round sizes and network speed may vary';
    }
  }
  const count=field=>tasks.reduce((n,r)=>n+BigInt(r[field]??0),0n).toString();
  return {jobId:job.job_id,network:job.network,sourceKind:job.source_kind,status:job.status,phase:job.phase,
    currentRound:job.current_round,completedRounds:completed.length,totalRounds:tasks.length,
    roundPercent:tasks.length?Math.floor(completed.length/tasks.length*100):0,partialRounds:completed.filter(r=>r.partial).length,
    recordsProcessed:count('records_processed'),unitsDone:count('units_done'),
    retries:tasks.reduce((n,r)=>n+Math.max(0,r.attempts-1),0),elapsedSeconds:Math.floor(elapsed),
    lastUpdate:job.updated_at,stale,eta,etaReason:reason,failureCode:job.failure_code,
    reportHtml:job.report_html&&/^[a-zA-Z0-9_.-]+\.html$/.test(job.report_html)?'/reports/'+encodeURIComponent(job.report_html):null,
    reportJson:job.report_json&&/^[a-zA-Z0-9_.-]+\.json$/.test(job.report_json)?'/reports/'+encodeURIComponent(job.report_json):null,
    currentTask:tasks.find(r=>r.round_id===job.current_round)??null};
}

export async function loadProgress(db,jobId=null) {
  if (jobId!==null && !UUID.test(jobId)) throw new Error('JobUuidRequired');
  const jobs=(await db.query(`SELECT job_id,network,source_kind,status,phase,current_round,started_at,
    updated_at,completed_at,failure_code,report_html,report_json FROM reputation_pilot.backfill_job
    WHERE ($1::uuid IS NULL OR job_id=$1::uuid) ORDER BY created_at DESC LIMIT 10`,[jobId])).rows;
  const statuses=[];
  for (const job of jobs) {
    const tasks=(await db.query(`SELECT round_id,status,attempts,units_done::text,units_total::text,
      records_processed::text,started_at,completed_at,duration_seconds,partial
      FROM reputation_pilot.backfill_round WHERE job_id=$1 ORDER BY round_id`,[job.job_id])).rows;
    statuses.push(estimateProgress(job,tasks));
  }
  return statuses;
}

export async function createLogger(directory,jobId) {
  if (!UUID.test(jobId)) throw new Error('JobUuidRequired');
  await mkdir(directory,{recursive:true,mode:0o700});
  const path=join(directory,`backfill-${jobId}.jsonl`);
  return async function log(event,round=null,phase=null) {
    // Only controlled phase/event names and numeric rounds are logged. No source
    // payloads, cursors, wallet records, credentials or exception messages.
    if (!/^[a-z_]+$/.test(event) || (round!==null && !Number.isInteger(round))
      || (phase!==null && !/^[a-z_]+$/.test(phase))) throw new Error('InvalidLogEvent');
    if ((await stat(path).catch(()=>({size:0}))).size>=5*1024*1024) {
      await rename(path+'.1',path+'.2').catch(e=>{if(e.code!=='ENOENT')throw e;});
      await rename(path,path+'.1');
    }
    await appendFile(path,JSON.stringify({time:new Date().toISOString(),jobId,event,round,phase})+'\n',{mode:0o600});
  };
}

// Fetch callbacks can report page/block/app progress through checkpoint().
// Source adapters supply source-specific fetching and cursor persistence.
export async function runRounds(database,{plan,jobId,workerId=randomUUID(),fetchRound,calculateRound,writeReport,log=async()=>{}}) {
  // Caller must hold database.withJobLock(jobId, ...) for the entire run.
  const completed=new Set((await database.transaction(db=>db.query(
    'SELECT round_id FROM reputation_pilot.backfill_round WHERE job_id=$1 AND status=$2',[jobId,'completed']))).rows.map(r=>r.round_id));
  await database.transaction(async db=>{
    await db.query(`UPDATE reputation_pilot.backfill_job SET worker_id=$2,status='running',phase='waiting',
      started_at=coalesce(started_at,clock_timestamp()),updated_at=clock_timestamp(),completed_at=NULL,failure_code=NULL WHERE job_id=$1`,[jobId,workerId]);
  });
  let heartbeatError=null,heartbeatBusy=false;
  const timer=setInterval(async()=>{
    if(heartbeatBusy)return;heartbeatBusy=true;
    try {await database.transaction(async db=>{await requireWorker(db,jobId,workerId);await db.query(
      'UPDATE reputation_pilot.backfill_job SET updated_at=clock_timestamp() WHERE job_id=$1',[jobId]);});}
    catch(error){if(error.code!=='55P03')heartbeatError=error;}finally{heartbeatBusy=false;}
  },15000);
  const owned=operation=>database.transaction(async db=>{if(heartbeatError)throw heartbeatError;await requireWorker(db,jobId,workerId);return operation(db);});
  try {
    for (const task of plan.rounds) {
      if(completed.has(task.round))continue;
      await owned(async db=>{
        await db.query(`UPDATE reputation_pilot.backfill_job SET current_round=$2,phase='fetching',updated_at=clock_timestamp() WHERE job_id=$1`,[jobId,task.round]);
        await db.query(`UPDATE reputation_pilot.backfill_round SET status='fetching',attempts=attempts+1,
          started_at=clock_timestamp(),units_done=0,units_total=NULL,records_processed=0 WHERE job_id=$1 AND round_id=$2`,[jobId,task.round]);
      });
      await log('round_started',task.round,'fetching');
      const checkpoint=async({unitsDone,unitsTotal=null,recordsProcessed})=>{
        if (![unitsDone,recordsProcessed].every(x=>Number.isSafeInteger(x)&&x>=0)
          || (unitsTotal!==null && (!Number.isSafeInteger(unitsTotal)||unitsTotal<unitsDone))) throw new Error('InvalidCheckpoint');
        await owned(async db=>{
          await db.query(`UPDATE reputation_pilot.backfill_round SET units_done=$3,units_total=$4,records_processed=$5
            WHERE job_id=$1 AND round_id=$2`,[jobId,task.round,unitsDone,unitsTotal,recordsProcessed]);
          await db.query('UPDATE reputation_pilot.backfill_job SET updated_at=clock_timestamp() WHERE job_id=$1',[jobId]);
        });
      };
      const input=await fetchRound(task,checkpoint);
      await owned(async db=>{
        await db.query(`UPDATE reputation_pilot.backfill_job SET phase='calculating',updated_at=clock_timestamp() WHERE job_id=$1`,[jobId]);
        await db.query(`UPDATE reputation_pilot.backfill_round SET status='calculating' WHERE job_id=$1 AND round_id=$2`,[jobId,task.round]);
      });
      await log('round_fetched',task.round,'calculating');
      await owned(async db=>{
        const result=await calculateRound(db,input);
        await db.query(`UPDATE reputation_pilot.backfill_round SET status='completed',completed_at=clock_timestamp(),
          duration_seconds=extract(epoch FROM clock_timestamp()-started_at),snapshot_id=$3,run_id=$4,partial=$5
          WHERE job_id=$1 AND round_id=$2`,[jobId,task.round,result.snapshotId,result.runId,result.runId===null]);
        await db.query('UPDATE reputation_pilot.backfill_job SET updated_at=clock_timestamp() WHERE job_id=$1',[jobId]);
      });
      await log('round_completed',task.round,'calculating');
    }
    await owned(db=>db.query(`UPDATE reputation_pilot.backfill_job SET phase='reporting',updated_at=clock_timestamp() WHERE job_id=$1`,[jobId]));
    await log('report_started',null,'reporting');
    const report=await writeReport();
    const html=report.html?basename(report.html):null,json=report.json?basename(report.json):null;
    if((html&&!/^[a-zA-Z0-9_.-]+\.html$/.test(html))||(json&&!/^[a-zA-Z0-9_.-]+\.json$/.test(json)))throw new Error('InvalidReportFile');
    await owned(db=>db.query(`UPDATE reputation_pilot.backfill_job SET status='completed',phase='completed',
      completed_at=clock_timestamp(),updated_at=clock_timestamp(),report_html=$2,report_json=$3 WHERE job_id=$1`,[jobId,html,json]));
    await log('job_completed',null,'completed');
    return {jobId,status:'completed',report};
  }catch(error){
    await owned(async db=>{
      await db.query(`UPDATE reputation_pilot.backfill_job SET status='failed',phase='failed',
        failure_code=$2,updated_at=clock_timestamp() WHERE job_id=$1`,[jobId,safeFailureCode(error)]);
      await db.query(`UPDATE reputation_pilot.backfill_round SET status='failed'
        WHERE job_id=$1 AND status IN ('fetching','calculating')`,[jobId]);
    }).catch(()=>{});
    await log('job_failed',null,'failed').catch(()=>{});throw error;
  }finally{clearInterval(timer);}
}
