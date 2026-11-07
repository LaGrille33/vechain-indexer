export const dashboardHtml = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Scoring pilot</title><style>
body{font:16px system-ui,sans-serif;background:#f5f7fa;color:#172337;margin:0;padding:clamp(16px,4vw,48px)}
main{max-width:1400px;margin:auto}h1{margin-bottom:8px}p{line-height:1.5;color:#46556b}
form,.panel{background:white;border:1px solid #d6dfe9;border-radius:10px;padding:20px;margin:20px 0}
form{display:flex;gap:16px;flex-wrap:wrap;align-items:end}label{display:grid;gap:6px}
input,select,button{font:inherit;padding:10px;border:1px solid #9eacc0;border-radius:6px;min-height:44px}
button{background:#203b64;color:white;cursor:pointer}button:disabled{opacity:.5;cursor:wait}
.scroll{overflow:auto}table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums;font-size:14px}
th,td{padding:12px;text-align:right;border-bottom:1px solid #e3e8ef;white-space:nowrap}th:first-child,td:first-child{text-align:left}
.app{font:12px ui-monospace,monospace;max-width:160px;overflow:hidden;text-overflow:ellipsis}
#status{min-height:24px}small{color:#58697d}select#runs{max-width:100%;width:100%}
</style></head><body><main><small>Historical data · Activity + Distribution</small>
<h1>Scoring pilot</h1><p>Inspect saved calculations by round. The subtotal is out of 60; Community and Profile are not included.</p>
<form id="query"><label>Network<select id="network"><option value="mainnet">Mainnet</option><option value="testnet">Testnet</option></select></label>
<label>Completed round<input id="round" type="number" min="1" max="2147483647" value="122" required></label>
<button id="load" type="submit">Load saved runs</button></form>
<p id="status" role="status" aria-live="polite">Choose a completed round. Only results already saved in Postgres are shown.</p>
<section class="panel"><label>Saved calculation<select id="runs" disabled><option>No runs loaded</option></select></label><p id="provenance"></p></section>
<section class="panel scroll"><table><thead><tr><th>App ID</th><th>Active users</th><th>New users</th><th>Actions</th><th>Activity /100</th><th>Activity rank</th><th>Efficiency</th><th>Distribution /100</th><th>Distribution rank</th><th>Subtotal /60</th><th>Subtotal rank</th></tr></thead><tbody id="scores"></tbody></table></section>
<p><small>Saved results retain their input snapshot and formula version. Rankings compare all apps in that round. Unavailable or uncompleted calculations are not shown as zero.</small></p>
</main><script src="/dashboard.js" defer></script></body></html>`;

export const dashboardJs = `const byId=id=>document.getElementById(id);
let runs=[];
async function read(url){const response=await fetch(url,{credentials:'same-origin'});const result=await response.json();if(!response.ok)throw new Error(result.error||'Request failed');return result;}
async function showScores(){const run=runs.find(r=>r.run_id===byId('runs').value);if(!run)return;
byId('scores').replaceChildren();byId('provenance').textContent='Formula: '+run.formula_version+' · Code: '+run.code_revision+' · Status: '+run.status+' · Expected apps: '+run.expected_app_count;
if(!['completed','validated','approved'].includes(run.status)){byId('status').textContent='This run has not completed. No final ranking is available.';return;}
try{const data=await read('/api/runs/'+encodeURIComponent(run.run_id)+'/scores');
for(const item of data.scores){const row=document.createElement('tr');const fields=['app_id','active_users','new_users','actions','activity_score','activity_rank','distribution_efficiency','distribution_score','distribution_rank','subtotal','subtotal_rank'];
for(const field of fields){const cell=document.createElement('td');cell.textContent=item[field]??'Unavailable';if(field==='app_id'){cell.className='app';cell.title=item[field];}row.append(cell);}byId('scores').append(row);}
byId('status').textContent=data.scores.length+' apps · Saved subtotal rankings out of 60.';
}catch(error){byId('status').textContent=error.message;}}
byId('query').addEventListener('submit',async event=>{event.preventDefault();byId('load').disabled=true;byId('status').textContent='Loading saved runs…';byId('scores').replaceChildren();byId('runs').replaceChildren();byId('runs').disabled=true;
try{const params=new URLSearchParams({network:byId('network').value,round:byId('round').value});const data=await read('/api/runs?'+params);runs=data.runs;
for(const run of runs){const option=document.createElement('option');option.value=run.run_id;option.textContent=run.status+' · '+run.formula_version+' · '+run.run_id;byId('runs').append(option);}byId('runs').disabled=!runs.length;
if(runs.length)await showScores();else{byId('status').textContent='No saved calculations for this round.';byId('provenance').textContent='';}
}catch(error){byId('status').textContent=error.message;}finally{byId('load').disabled=false;}});
byId('runs').addEventListener('change',showScores);`;
