import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createDatabase } from './database.mjs';
import { importSnapshot } from './snapshot.mjs';
import { loadReport, saveReport } from './report.mjs';
import { normalizePlan, createJob, createLogger, runRounds, loadProgress, UUID, safeFailureCode } from './progress.mjs';
import { ChainClient,SOURCE_POLICY,digest } from './chain-client.mjs';
import { fetchChainRound } from './chain-source.mjs';

const [action, argument, reportRound, endRound, resumeJobId] = process.argv.slice(2);
const allowed = new Set(['import', 'execute', 'next', 'list', 'report', 'report-all', 'process', 'status', 'chain']);
if (!allowed.has(action)) { console.error('Usage: job.mjs import /imports/snapshot.json | execute RUN_UUID | next | list | report NETWORK ROUND | report-all NETWORK | process /imports/plan.json [JOB_UUID] | status [JOB_UUID] | chain mainnet FROM TO [JOB_UUID]'); process.exit(2); }
const revision = createHash('sha256');
for (const name of ['../package-lock.json','../sql/001-schema.sql','../sql/002-calculation.sql','../sql/003-progress.sql','../sql/004-chain-source.sql','./snapshot.mjs','./job.mjs','./database.mjs','./progress.mjs','./chain-client.mjs','./chain-source.mjs','./report.mjs']) {
  revision.update(await readFile(new URL(name,import.meta.url)));
}
const codeRevision = 'nas-v2:' + revision.digest('hex');
let db;
try {
  db = await createDatabase();
  if(action==='process'||action==='chain') {
    let plan,client=null,requestedJobId;
    if(action==='chain'){
      if(argument!=='mainnet'||!reportRound||!endRound)throw new Error('MainnetRangeRequired');
      client=new ChainClient();requestedJobId=resumeJobId;
      if(requestedJobId){
        if(!UUID.test(requestedJobId))throw new Error('JobUuidRequired');
        plan=await db.transaction(async connection=>(await connection.query('SELECT source_plan FROM reputation_pilot.backfill_job WHERE job_id=$1',[requestedJobId])).rows[0]?.source_plan);
        if(!plan||plan.network!=='mainnet'||plan.policy!==SOURCE_POLICY||plan.source_kind!=='blockchain')throw new Error('InvalidStoredSourcePlan');
        const {hash,...content}=plan;if(hash!==digest(content))throw new Error('StoredPlanHashMismatch');
      }else plan=await client.plan(reportRound,endRound);
    }else{
      if(!argument?.startsWith('/imports/')||argument.split('/').includes('..'))throw new Error('ImportsPathRequired');
      requestedJobId=reportRound;if(requestedJobId!==undefined&&!UUID.test(requestedJobId))throw new Error('JobUuidRequired');
      plan=normalizePlan(JSON.parse(await readFile(argument,'utf8')));
    }
    const jobId=await db.transaction(async connection=>{
      const result=await createJob(connection,plan,requestedJobId);
      if(action==='chain')await connection.query('UPDATE reputation_pilot.backfill_job SET source_plan=$2::jsonb WHERE job_id=$1',[result,JSON.stringify(plan)]);
      return result;
    });
    const log=await createLogger('/logs',jobId);
    console.log(JSON.stringify({event:'job_registered',jobId,source:plan.source_kind,from:plan.rounds[0].round,to:plan.rounds.at(-1).round}));
    const result=await db.withJobLock(jobId,()=>runRounds(db,{plan,jobId,log,
      fetchRound:action==='chain'?(task,checkpoint)=>fetchChainRound(db,client,plan,task.round,checkpoint):async(task,checkpoint)=>{
        const contents=await readFile(task.file);
        if(createHash('sha256').update(contents).digest('hex')!==task.sha256)throw new Error('RoundFileHashMismatch');
        const input=JSON.parse(contents.toString('utf8'));
        if(input.network!==plan.network||input.round_id!==task.round)throw new Error('RoundFileMismatch');
        await checkpoint({unitsDone:1,unitsTotal:1,recordsProcessed:input.metrics?.length??0});return input;
      },
      calculateRound:async(client,input)=>{
        const result=await importSnapshot(client,input,codeRevision);
        if(result.runId)await client.query('SELECT reputation_pilot.execute_run($1)',[result.runId]);
        return result;
      },
      writeReport:async()=>{
        const report=await db.transaction(async client=>{
          await client.query('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY');
          return loadReport(client,{network:plan.network});
        });
        report.reportCodeRevision='sha256:'+createHash('sha256').update(await readFile(new URL('./report.mjs',import.meta.url))).digest('hex');
        return saveReport(report,'/exports');
      },
    }));
    console.log(JSON.stringify(result));
  }else{
  const output = await db.transaction(async client => {
    if(action==='status')return {jobs:await loadProgress(client,argument??null)};
    if (action === 'report' || action === 'report-all') {
      if (action === 'report' && reportRound === undefined) throw new Error('ReportRoundRequired');
      await client.query('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY');
      const report=await loadReport(client,{network:argument,round:action==='report-all'?'all':reportRound});
      report.reportCodeRevision='sha256:'+createHash('sha256').update(await readFile(new URL('./report.mjs',import.meta.url))).digest('hex');
      return {report};
    }
    if (action === 'import') {
      if (!argument?.startsWith('/imports/') || argument.split('/').includes('..')) throw new Error('ImportsPathRequired');
      const input = JSON.parse(await readFile(argument,'utf8'));
      return importSnapshot(client,input,codeRevision);
    }
    if (action === 'list') return (await client.query(`SELECT run_id,snapshot_id,formula_version,code_revision,status,completed_at
      FROM reputation_pilot.scoring_run ORDER BY created_at DESC LIMIT 20`)).rows;
    let runId = argument;
    if (action === 'next') runId = (await client.query(`SELECT run_id FROM reputation_pilot.scoring_run
      WHERE status='pending' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED`)).rows[0]?.run_id;
    if (!runId && action === 'next') return {status:'no_pending_run'};
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(runId ?? '')) throw new Error('RunUuidRequired');
    return {runId,status:(await client.query('SELECT reputation_pilot.execute_run($1) AS status',[runId])).rows[0].status};
  });
  console.log(JSON.stringify(output.report ? await saveReport(output.report,'/exports') : output));
  }
} catch (error) {
  // No SQL text, connection details, input records or arbitrary exception messages.
  const sqlState=/^[0-9A-Z]{5}$/.test(error.code??'')?error.code:undefined;
  console.error(JSON.stringify({event:'nas_job_failed',kind:error.name,code:safeFailureCode(error),sqlState})); process.exitCode=1;
} finally { if (db) await db.close(); }
