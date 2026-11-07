import { createHash, randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

export const REPORT_VERSION = 'available-input-report-v1';
const FORMULA = 'activity-distribution-mongo-parity-v1';
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const value = v => v === null || v === undefined ? 'Unavailable' : esc(v);
const numericKey = v => String(v).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
const FULL_FIELDS = ['p_active_users','p_new_users','p_actions','activity_score','distribution_efficiency',
  'distribution_score','activity_contribution','distribution_contribution','subtotal',
  'activity_rank','distribution_rank','subtotal_rank'];

// Report-only arithmetic. Existing immutable full-score SQL is unchanged.
// Missing new-user history excludes that component round-wide; no renormalization.
export const breakdownSql = `WITH measures AS (
  SELECT m.*, CASE WHEN allocated > 0 THEN least(round(distributed/allocated,10),1::numeric)
    ELSE 0::numeric END AS efficiency
  FROM reputation_pilot.app_round_metric m WHERE snapshot_id=$1
), positions AS (
  SELECT measures.*, count(*) OVER ()::numeric AS n,
    (rank() OVER (ORDER BY active_users)-1)::numeric AS below_users,
    count(*) OVER (PARTITION BY active_users)::numeric AS ties_users,
    (rank() OVER (ORDER BY new_users)-1)::numeric AS below_new,
    count(*) OVER (PARTITION BY new_users)::numeric AS ties_new,
    count(*) FILTER (WHERE new_users IS NULL) OVER () AS unknown_new,
    (rank() OVER (ORDER BY actions)-1)::numeric AS below_actions,
    count(*) OVER (PARTITION BY actions)::numeric AS ties_actions,
    (rank() OVER (ORDER BY efficiency)-1)::numeric AS below_efficiency,
    count(*) OVER (PARTITION BY efficiency)::numeric AS ties_efficiency
  FROM measures
), percentiles AS (
  SELECT positions.*, n=$3::numeric AS population_complete,
    ($2::boolean AND unknown_new=0 AND n=$3::numeric) AS new_users_available,
    CASE WHEN n=$3::numeric THEN round((below_users+CASE WHEN ties_users=1 THEN 0 ELSE ties_users/2 END)/n,10)*100 END AS pu,
    CASE WHEN $2::boolean AND unknown_new=0 AND n=$3::numeric
      THEN round((below_new+CASE WHEN ties_new=1 THEN 0 ELSE ties_new/2 END)/n,10)*100 END AS pn,
    CASE WHEN n=$3::numeric THEN round((below_actions+CASE WHEN ties_actions=1 THEN 0 ELSE ties_actions/2 END)/n,10)*100 END AS pa,
    CASE WHEN n=$3::numeric THEN round((below_efficiency+CASE WHEN ties_efficiency=1 THEN 0 ELSE ties_efficiency/2 END)/n,10)*100 END AS pd
  FROM positions
), dimensions AS (
  SELECT percentiles.*, round(pu*0.5+coalesce(pn,0)*0.3+pa*0.2,4) AS available_activity,
    round(pd,4) AS distribution FROM percentiles
), contributions AS (
  SELECT dimensions.*, round(available_activity*0.3,2) AS ac, round(distribution*0.3,2) AS dc
  FROM dimensions
)
SELECT app_id, active_users::text, new_users::text, actions::text, allocated::text, distributed::text,
  n::text, below_users::text, ties_users::text, below_new::text, ties_new::text,
  below_actions::text, ties_actions::text, below_efficiency::text, ties_efficiency::text,
  population_complete, new_users_available, efficiency::text AS ranking_efficiency,
  round(pu,4)::text AS p_active_users, round(pn,4)::text AS p_new_users, round(pa,4)::text AS p_actions,
  CASE WHEN new_users_available THEN available_activity::text END AS activity_score,
  available_activity::text AS activity_available_score,
  round(efficiency,6)::text AS distribution_efficiency, distribution::text AS distribution_score,
  CASE WHEN new_users_available THEN ac::text END AS activity_contribution,
  ac::text AS activity_available_contribution, dc::text AS distribution_contribution,
  CASE WHEN new_users_available THEN (ac+dc)::text END AS subtotal,
  (ac+dc)::text AS available_subtotal,
  CASE WHEN new_users_available THEN (rank() OVER (ORDER BY available_activity DESC))::text END AS activity_rank,
  CASE WHEN population_complete THEN (rank() OVER (ORDER BY distribution DESC))::text END AS distribution_rank,
  CASE WHEN new_users_available THEN (rank() OVER (ORDER BY ac+dc DESC))::text END AS subtotal_rank,
  CASE WHEN population_complete THEN (rank() OVER (ORDER BY ac+dc DESC))::text END AS available_rank
FROM contributions ORDER BY ac+dc DESC NULLS LAST, app_id`;

export async function loadReport(db, {network, round = 'all', generatedAt = new Date().toISOString()} = {}) {
  if (!['mainnet','testnet'].includes(network)) throw new Error('ReportNetworkRequired');
  if (round !== 'all' && (!/^[1-9][0-9]*$/.test(String(round)) || Number(round)>2147483647)) throw new Error('InvalidReportRound');
  const snapshots = (await db.query(`SELECT DISTINCT ON (round_id) snapshot_id,network,round_id,
      source_block_number::text,source_block_id,round_end_timestamp::text,input_hash,
      expected_app_count,new_user_history_complete,source_manifest,created_at
    FROM reputation_pilot.round_snapshot WHERE network=$1 AND ($2::int IS NULL OR round_id=$2::int)
    ORDER BY round_id DESC,created_at DESC,snapshot_id DESC`,[network,round==='all'?null:Number(round)])).rows;
  if (!snapshots.length) throw new Error('NoSavedSnapshots');
  const rounds=[];
  for (const s of snapshots) {
    const rows=(await db.query(breakdownSql,[s.snapshot_id,s.new_user_history_complete,s.expected_app_count])).rows;
    const complete=rows.length===s.expected_app_count && rows.every(r=>r.population_complete);
    const fresh=complete && rows.every(r=>r.new_users_available);
    const run=(await db.query(`SELECT run_id,formula_version,code_revision,status,completed_at FROM reputation_pilot.scoring_run
      WHERE snapshot_id=$1 AND status IN ('completed','validated','approved')
      ORDER BY completed_at DESC NULLS LAST,created_at DESC,run_id DESC LIMIT 1`,[s.snapshot_id])).rows[0] ?? null;
    let savedParity=null;
    if (run?.formula_version===FORMULA && fresh) {
      const saved=(await db.query(`SELECT * FROM reputation_pilot.app_round_score WHERE run_id=$1`,[run.run_id])).rows;
      savedParity=saved.length===rows.length && rows.every(row=>{
        const other=saved.find(x=>x.app_id===row.app_id);
        return other && FULL_FIELDS.every(k=>numericKey(other[k])===numericKey(row[k]));
      });
    }
    // Whitelist exported provenance. Do not copy arbitrary source manifests,
    // connection strings, wallet records or other private source metadata.
    const appNames=s.source_manifest?.app_names;
    for (const row of rows) row.name=typeof appNames?.[row.app_id]==='string' ? appNames[row.app_id].slice(0,200) : null;
    rounds.push({network:s.network,round:s.round_id,snapshotId:s.snapshot_id,inputHash:s.input_hash,
      sourceBlockNumber:s.source_block_number,sourceBlockId:s.source_block_id,roundEndTimestamp:s.round_end_timestamp,
      snapshotCreatedAt:s.created_at,populationExpected:s.expected_app_count,populationActual:rows.length,
      populationComplete:complete,newUsersAvailable:fresh,availableMaximum:complete?(fresh?60:51):null,
      activityMaximum:fresh?100:70,profileAvailable:false,communityAvailable:false,
      savedRun:run,savedParity,rows});
  }
  return {reportVersion:REPORT_VERSION,formulaVersion:FORMULA,generatedAt,network,rounds};
}

function percentileLine(row, key, below, ties) {
  if (row[key]===null || row[key]===undefined) return 'Unavailable; excluded from subtotal';
  const adjustment=row[ties]==='1' ? '0 (unique value)' : `0.5 × ${value(row[ties])}`;
  return `(${value(row[below])} below + ${adjustment}) / ${value(row.n)} apps × 100 = ${value(row[key])}`;
}

export function renderReport(report) {
  const sections=report.rounds.map(round=>{
    const maximum=value(round.availableMaximum);
    const status=!round.populationComplete?'Incomplete app population: scores and ranks withheld.'
      :round.newUsersAvailable?'Activity and Distribution available; Profile and Community excluded.'
      :'New-user history unavailable: new-user component excluded for every app; Profile and Community excluded.';
    const parity=round.savedParity===true?'All displayed full-score decimals and ranks match the saved scoring run.'
      :round.savedParity===false?'WARNING: report arithmetic differs from the saved run. Do not promote these results.'
      :round.savedRun?'Saved run exists; its formula/coverage could not be reconciled with this report.'
      :'Calculated from saved input snapshots for this report; no completed full scoring run is attached.';
    const headers=['Rank','App','Active wallets','New wallets','Actions','Activity available','Distribution /100',`Available subtotal /${maximum}`];
    const table=round.rows.map(row=>`<tr><td>${value(row.available_rank)}</td><td><a href="#app-${esc(round.snapshotId)}-${esc(row.app_id)}">${esc(row.name??row.app_id)}</a></td>
      <td>${value(row.active_users)}</td><td>${value(row.new_users)}</td><td>${value(row.actions)}</td>
      <td>${value(row.activity_available_score)} /${round.activityMaximum}</td><td>${value(row.distribution_score)}</td><td><strong>${value(row.available_subtotal)}</strong></td></tr>`).join('');
    const details=round.rows.map(row=>`<details id="app-${esc(round.snapshotId)}-${esc(row.app_id)}"><summary>${esc(row.name??row.app_id)} · subtotal ${value(row.available_subtotal)} /${maximum}</summary>
      <p class="mono">${esc(row.app_id)}</p><dl>
      <dt>Active-wallet percentile</dt><dd>${percentileLine(row,'p_active_users','below_users','ties_users')}</dd>
      <dt>New-wallet percentile</dt><dd>${percentileLine(row,'p_new_users','below_new','ties_new')}</dd>
      <dt>Action percentile</dt><dd>${percentileLine(row,'p_actions','below_actions','ties_actions')}</dd>
      <dt>Activity calculation</dt><dd>round(0.5 × ${value(row.p_active_users)} + ${round.newUsersAvailable?`0.3 × ${value(row.p_new_users)} + `:'[new-user term excluded] + '}0.2 × ${value(row.p_actions)}, 4) = ${value(row.activity_available_score)} /${round.activityMaximum}</dd>
      <dt>Allocated / distributed B3TR</dt><dd>${value(row.allocated)} / ${value(row.distributed)} (saved input precision preserved)</dd>
      <dt>Distribution efficiency used for ranking</dt><dd>min(round(distributed / allocated, 10), 1); zero when allocation is zero. Result: ${value(row.ranking_efficiency)}</dd>
      <dt>Distribution percentile</dt><dd>${percentileLine(row,'distribution_score','below_efficiency','ties_efficiency')}</dd>
      <dt>Weighted contributions</dt><dd>round(${value(row.activity_available_score)} × 0.30, 2) = ${value(row.activity_available_contribution)}; round(${value(row.distribution_score)} × 0.30, 2) = ${value(row.distribution_contribution)}</dd>
      <dt>Available subtotal</dt><dd>${value(row.activity_available_contribution)} + ${value(row.distribution_contribution)} = ${value(row.available_subtotal)} /${maximum}; original weights retained.</dd>
      <dt>Profile / Community</dt><dd>Unavailable in this package; excluded, with no invented zero scores.</dd></dl></details>`).join('');
    return `<section><h2>${esc(round.network)} · Round ${round.round}</h2><p class="notice">${esc(status)} Available maximum: ${maximum}/100.</p>
      <p>${round.populationActual} of ${round.populationExpected} declared historical apps, including inactive apps. Ranks compare only this round/snapshot.</p>
      <p>${esc(parity)}</p><p class="mono">Snapshot: ${esc(round.snapshotId)}<br>Input SHA-256: ${esc(round.inputHash)}<br>Source block: ${esc(round.sourceBlockNumber)} · ${esc(round.sourceBlockId)}</p>
      ${round.savedRun?`<p class="mono">Saved run: ${esc(round.savedRun.run_id)} · ${esc(round.savedRun.status)}<br>Formula: ${esc(round.savedRun.formula_version)}<br>Calculation code: ${esc(round.savedRun.code_revision)}</p>`:''}
      <div class="scroll"><table><thead><tr>${headers.map(h=>`<th>${h}</th>`).join('')}</tr></thead><tbody>${table}</tbody></table></div>
      <h3>Calculation breakdown by app</h3>${details}</section>`;
  }).join('');
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
  <title>VeBetter app scores — ${esc(report.network)}</title><style>
  body{font:15px/1.55 system-ui,sans-serif;color:#172337;background:#f4f6f8;margin:0;padding:24px}main{max-width:1500px;margin:auto}
  h1,h2,h3{line-height:1.25}section,header{background:white;border:1px solid #dae1e9;border-radius:12px;padding:24px;margin:0 0 24px}
  .notice{background:#fff5d9;padding:12px;border-left:4px solid #b77800}.mono{font:12px/1.6 ui-monospace,monospace;overflow-wrap:anywhere}
  .scroll{overflow:auto}table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}th,td{padding:10px;text-align:right;border-bottom:1px solid #dce3ec;white-space:nowrap}
  th:nth-child(2),td:nth-child(2){text-align:left;white-space:normal;overflow-wrap:anywhere;min-width:180px}a{color:#204f8e}
  details{border-top:1px solid #dce3ec;padding:14px 0}summary{cursor:pointer;font-weight:650;overflow-wrap:anywhere}dt{font-weight:650;margin-top:12px}dd{margin-left:0;overflow-wrap:anywhere}
  @media print{body{background:white;padding:0}section,header{border:0;padding:0;break-before:page}.scroll{overflow:visible}table{font-size:9px}th,td{padding:4px}details{break-inside:avoid}}
  </style></head><body><main><header><h1>VeBetter app scores</h1><p>${esc(report.dataLabel??`Saved ${report.network} inputs · partial reputation scoring`)}</p>
  <p>Generated ${esc(report.generatedAt)} · ${report.rounds.length} saved round(s). This report reads saved snapshots; it does not download or backfill production history.</p>
  <p>Maximum 60/100 when Activity and Distribution are available; 51/100 when the new-user term is unavailable. Missing components are excluded without rescaling to 100. These are partial scores, not the full reputation score.</p>
  <p>Percentile P = round((below + adjustment) / N, 10) × 100. Adjustment is zero for a unique value, otherwise 0.5 × tied count. ln(1+x) preserves ordering, so exact integer ranks give the same percentiles. Percentiles displayed at 4 decimals; calculations use the unrounded percentile values before dimension rounding. Competition ranks use 1, 2, 2, 4.</p>
  <p>Each round uses its newest saved snapshot. No app-count display cap is applied. Historical app-list and source-history verification remain the responsibility of ingestion. Ranks are not compared across rounds or different coverage policies.</p>
  <p>Amounts retain the precision of the saved inputs; this report does not establish that an API supplied full on-chain monetary precision. Open each app section for the detailed calculation.</p>
  <p class="mono">Report policy: ${esc(report.reportVersion)}<br>Arithmetic reference: ${esc(report.formulaVersion)}<br>Report code: ${esc(report.reportCodeRevision??'not recorded')}</p></header>${sections}</main></body></html>`;
}

export async function saveReport(report, directory) {
  await mkdir(directory,{recursive:true});
  const stamp=report.generatedAt.replace(/[^0-9TZ]/g,'');
  const basename=`app-scores-${report.network}-${report.rounds.length===1?`round-${report.rounds[0].round}`:'all-rounds'}-${stamp}-${randomUUID().slice(0,8)}`;
  const html=renderReport(report), json=JSON.stringify(report,null,2)+'\n';
  const htmlPath=join(directory,basename+'.html'), jsonPath=join(directory,basename+'.json');
  await writeFile(jsonPath,json,{flag:'wx',mode:0o600});
  await writeFile(htmlPath,html,{flag:'wx',mode:0o600});
  return {html:htmlPath,json:jsonPath,htmlSha256:createHash('sha256').update(html).digest('hex'),
    rounds:report.rounds.length,apps:report.rounds.reduce((n,r)=>n+r.rows.length,0)};
}
