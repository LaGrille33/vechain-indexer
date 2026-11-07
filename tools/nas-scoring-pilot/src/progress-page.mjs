import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

export const progressHtml=`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Backfill progress</title><style>
body{font:16px/1.5 system-ui,sans-serif;background:#f4f6f8;color:#172337;padding:24px}main{max-width:1000px;margin:auto}section{background:white;padding:24px;border:1px solid #dce3ec;border-radius:12px;margin:20px 0}select{font:inherit;padding:10px;width:100%}progress{width:100%;height:26px}dl{display:grid;grid-template-columns:180px 1fr;gap:12px}dd{margin:0;overflow-wrap:anywhere}dt{font-weight:650}#status{padding:12px;background:#fff4d6}.mono{font-family:ui-monospace,monospace;font-size:13px}</style></head><body><main>
<h1>Backfill progress</h1><p>Live saved job state · refreshes every 5 seconds · <a href="/">Saved scores</a></p>
<p id="status" role="status">Loading saved jobs…</p><section><label for="jobs">Job</label><select id="jobs"><option>No jobs loaded</option></select></section>
<section><h2 id="heading">No job selected</h2><progress id="bar" max="100" value="0"></progress><p id="rounds"></p><dl id="details"></dl></section>
<p>ETA is a rough range after at least three completed rounds. Uneven round sizes, first-seen history and network retries affect it. Reporting may continue after all rounds finish. No jobs means no backfill has been started.</p>
<p>Prepared-snapshot jobs import existing files. Blockchain jobs fetch finalized mainnet rewards, historical app membership and allocations; completed source pages and rounds are saved for resume.</p>
</main><script src="/progress.js" defer></script></body></html>`;

export const progressJs=`const byId=id=>document.getElementById(id);let jobs=[];let selected='';
const duration=s=>s<60?Math.ceil(s)+' seconds':s<3600?Math.ceil(s/60)+' minutes':(s/3600).toFixed(1)+' hours';
function show(){const job=jobs.find(x=>x.jobId===selected)??jobs[0];byId('details').replaceChildren();if(!job){byId('heading').textContent='No backfill has been started';byId('bar').value=0;byId('rounds').textContent='';return;}
byId('heading').textContent=job.status+' · '+job.phase+' · '+job.network;byId('bar').value=job.roundPercent;
byId('rounds').textContent=job.completedRounds+' / '+job.totalRounds+' rounds completed ('+job.roundPercent+'%). '+job.partialRounds+' partial rounds.';
const estimate=job.eta&&job.status!=='completed'?duration(job.eta.lowerSeconds)+'–'+duration(job.eta.upperSeconds)+' remaining; approximately '+new Date(job.eta.estimatedFinish).toLocaleString():job.etaReason;
const fields=[['Source',job.sourceKind],['Current round',job.currentRound??'Not started'],['ETA',estimate],['Estimate basis',job.etaReason],['Elapsed',duration(job.elapsedSeconds)],['Records processed',job.recordsProcessed],['Source units done',job.unitsDone],['Round retries',job.retries],['Last update',new Date(job.lastUpdate).toLocaleString()],['Health',job.stale?'No recent heartbeat; check logs':'Last saved update available'],['Job ID',job.jobId]];
if(job.currentTask)fields.push(['Current phase units',job.currentTask.units_done+' / '+(job.currentTask.units_total??'unknown total')]);
if(job.failureCode)fields.push(['Failure',job.failureCode]);
for(const [key,value]of fields){const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=key;dd.textContent=String(value);byId('details').append(dt,dd);}
if(job.reportHtml){const dt=document.createElement('dt'),dd=document.createElement('dd'),link=document.createElement('a');dt.textContent='Completed report';link.textContent='Open HTML calculation report';link.href=job.reportHtml;dd.append(link);byId('details').append(dt,dd);}}
async function refresh(){try{const response=await fetch('/api/progress',{credentials:'same-origin'});if(!response.ok)throw new Error('ProgressUnavailable');jobs=(await response.json()).jobs;byId('jobs').replaceChildren();for(const j of jobs){const option=document.createElement('option');option.value=j.jobId;option.textContent=j.network+' · '+j.sourceKind+' · '+j.status+' · '+j.jobId;byId('jobs').append(option);}if(!jobs.some(j=>j.jobId===selected))selected=jobs[0]?.jobId??'';byId('jobs').value=selected;show();byId('status').textContent=jobs.length?'Updated '+new Date().toLocaleTimeString():'No saved jobs. Start an approved round range with the job command.';}catch{byId('status').textContent='Progress unavailable or database migration pending. Previously displayed values may be stale.';}finally{setTimeout(refresh,5000);}}
byId('jobs').addEventListener('change',()=>{selected=byId('jobs').value;show();});refresh();`;

export function createProgressHandler(database,exportsDirectory='/exports') {
  return async request=>{
    const url=new URL(request.url);
    if(!['/progress','/progress.js','/api/progress'].includes(url.pathname)&&!url.pathname.startsWith('/api/progress/')&&!url.pathname.startsWith('/reports/'))return null;
    if(request.method!=='GET')return Response.json({error:'Read-only endpoint'},{status:405});
    if(url.pathname.startsWith('/reports/')) {
      let name;try{name=decodeURIComponent(url.pathname.slice('/reports/'.length));}catch{return new Response('Invalid filename',{status:400});}
      if(!/^[a-zA-Z0-9_.-]+\.(html|json)$/.test(name))return new Response('Invalid filename',{status:400});
      try{return new Response(await readFile(join(exportsDirectory,name)),{headers:{
        'Content-Type':name.endsWith('.html')?'text/html; charset=utf-8':'application/json',
        'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
        'Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'",
      }});}catch(error){return new Response('Report unavailable',{status:error.code==='ENOENT'?404:503});}
    }
    if(url.pathname==='/progress'||url.pathname==='/progress.js')return new Response(url.pathname==='/progress'?progressHtml:progressJs,{headers:{
      'Content-Type':url.pathname==='/progress'?'text/html; charset=utf-8':'text/javascript; charset=utf-8',
      'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
      'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'",
    }});
    try{
      const {loadProgress,UUID}=await import('./progress.mjs');
      const jobId=url.pathname==='/api/progress'?null:url.pathname.slice('/api/progress/'.length);
      if(jobId!==null&&!UUID.test(jobId))return Response.json({error:'Job UUID required'},{status:400});
      return Response.json({jobs:await database(db=>loadProgress(db,jobId))},{headers:{'Cache-Control':'no-store'}});
    }catch{return Response.json({error:'Progress unavailable; check connection and migration'},{status:503});}
  };
}
