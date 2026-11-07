import { formatUnits } from 'ethers';
import { CONTRACTS,REWARD_TOPIC,SOURCE_POLICY,digest,parseReward,nextCursor } from './chain-client.mjs';

export async function ensureCache(db,round,metadata){
  const from=round===1?0:metadata.start.position[0],to=metadata.end.position[0];
  await db.query(`INSERT INTO reputation_pilot.chain_round_cache
    (network,policy,round_id,from_block,to_block,end_block_id,boundaries,cursor_block,digest)
    VALUES ('mainnet',$1,$2,$3,$4,$5,$6::jsonb,$3,$7) ON CONFLICT DO NOTHING`,
    [SOURCE_POLICY,round,from,to,metadata.end.blockId,JSON.stringify({start:metadata.start,end:metadata.end}),digest([])]);
  const cache=(await db.query(`SELECT * FROM reputation_pilot.chain_round_cache
    WHERE network='mainnet' AND policy=$1 AND round_id=$2`,[SOURCE_POLICY,round])).rows[0];
  if(Number(cache.from_block)!==from||Number(cache.to_block)!==to||cache.end_block_id!==metadata.end.blockId
    ||digest(cache.boundaries)!==digest({start:metadata.start,end:metadata.end}))throw new Error('SourceCacheBoundaryMismatch');
  return cache;
}

export async function commitPage(db,round,cache,page,limit){
  const current=(await db.query(`SELECT * FROM reputation_pilot.chain_round_cache
    WHERE network='mainnet' AND policy=$1 AND round_id=$2 FOR UPDATE`,[SOURCE_POLICY,round])).rows[0];
  if(current.finished||current.cursor_block!==cache.cursor_block||current.cursor_offset!==cache.cursor_offset)return current;
  const next=nextCursor(page,Number(cache.cursor_block),cache.cursor_offset,limit);
  const groups=new Map();
  for(const log of page){
    if(log.meta.blockNumber>Number(cache.to_block))throw new Error('SourceLogOutsideRange');
    const reward=parseReward(log,cache.boundaries.start.position,cache.boundaries.end.position,round);
    if(!reward)continue;
    const key=reward.round_id+reward.app_id+reward.wallet;
    const previous=groups.get(key);
    if(previous){previous.actions=(BigInt(previous.actions)+1n).toString();previous.amount_raw=(BigInt(previous.amount_raw)+BigInt(reward.amount_raw)).toString();}
    else groups.set(key,reward);
  }
  if(groups.size){
    const rows=JSON.stringify([...groups.values()]);
    await db.query(`INSERT INTO reputation_pilot.chain_wallet_round(network,policy,round_id,app_id,wallet,actions,amount_raw)
      SELECT 'mainnet',$1,round_id,app_id,wallet,actions,amount_raw FROM jsonb_to_recordset($2::jsonb)
      AS r(round_id int,app_id text,wallet text,actions bigint,amount_raw numeric)
      ON CONFLICT(network,policy,round_id,app_id,wallet) DO UPDATE SET
      actions=chain_wallet_round.actions+EXCLUDED.actions,amount_raw=chain_wallet_round.amount_raw+EXCLUDED.amount_raw`,[SOURCE_POLICY,rows]);
    await db.query(`INSERT INTO reputation_pilot.chain_wallet_first(network,policy,app_id,wallet,first_round)
      SELECT 'mainnet',$1,app_id,wallet,min(round_id) FROM jsonb_to_recordset($2::jsonb)
      AS r(round_id int,app_id text,wallet text) GROUP BY app_id,wallet
      ON CONFLICT(network,policy,app_id,wallet) DO UPDATE SET first_round=EXCLUDED.first_round
      WHERE chain_wallet_first.first_round>EXCLUDED.first_round`,[SOURCE_POLICY,rows]);
  }
  return (await db.query(`UPDATE reputation_pilot.chain_round_cache SET cursor_block=$3,cursor_offset=$4,
    pages=pages+1,event_count=event_count+$5,digest=$6,finished=$7
    WHERE network='mainnet' AND policy=$1 AND round_id=$2 RETURNING *`,
    [SOURCE_POLICY,round,next.block,next.offset,page.length,digest([current.digest,page]),next.finished])).rows[0];
}

export async function sourceSnapshot(db,round,metadata,endBlock){
  // Large historical rounds exceeded the NAS's general 120-second limit.
  // Bound each aggregate by one app using the existing round/app and first-seen
  // indexes; avoid the previous round-wide GROUP BY/join and parallel workers.
  // These settings are LOCAL to this transaction, not ingestion or the server.
  await db.query("SET LOCAL statement_timeout = '600s'");
  await db.query('SET LOCAL max_parallel_workers_per_gather = 0');
  const coverage=(await db.query(`SELECT NOT EXISTS (
    SELECT 1 FROM generate_series(1,$2::int) r LEFT JOIN reputation_pilot.chain_round_cache c
    ON c.network='mainnet' AND c.policy=$1 AND c.round_id=r WHERE c.finished IS DISTINCT FROM TRUE
    OR (r=1 AND c.from_block<>0)) AS complete`,[SOURCE_POLICY,round])).rows[0].complete;
  const metrics=[];
  for(const appId of metadata.ids){
    const totals=(await db.query(`/* pilot:source-app-totals */
      SELECT count(*)::text AS active_users,coalesce(sum(actions),0)::text AS actions,
        coalesce(sum(amount_raw),0)::text AS amount_raw
      FROM reputation_pilot.chain_wallet_round
      WHERE network='mainnet' AND policy=$1 AND round_id=$2 AND app_id=$3`,[SOURCE_POLICY,round,appId])).rows[0];
    const fresh=coverage?(await db.query(`/* pilot:source-app-first-seen */
      SELECT count(*)::text AS n FROM reputation_pilot.chain_wallet_first
      WHERE network='mainnet' AND policy=$1 AND first_round=$2 AND app_id=$3`,[SOURCE_POLICY,round,appId])).rows[0].n:null;
    metrics.push({app_id:appId,...totals,new_users:fresh});
  }
  const cache=(await db.query(`SELECT pages,event_count::text,digest,finished FROM reputation_pilot.chain_round_cache
    WHERE network='mainnet' AND policy=$1 AND round_id=$2`,[SOURCE_POLICY,round])).rows[0];
  if(!cache?.finished)throw new Error('SourceRoundIncomplete');
  return {network:'mainnet',round_id:round,source_block_number:String(endBlock.number),source_block_id:endBlock.id,
    round_end_timestamp:endBlock.timestamp,new_user_history_complete:coverage,app_ids:metadata.ids,
    source_manifest:{source_policy:SOURCE_POLICY,source:'public-mainnet-chain',contracts:CONTRACTS,
      round_definition:'EmissionDistributed/EmissionDistributedV2 event order; end event excluded',
      historical_population:'getAppIdsOfRound at round end emissions block',
      allocation_revision:metadata.end.blockId,
      boundaries:{start:metadata.start,end:metadata.end},event_pages:cache.pages,event_count:cache.event_count,
      source_digest:cache.digest,history_from_genesis_complete:coverage,app_names:metadata.names},
    metrics:metrics.map(row=>({app_id:row.app_id,active_users:row.active_users,new_users:row.new_users,actions:row.actions,
      allocated:metadata.allocations[row.app_id],distributed:formatUnits(BigInt(row.amount_raw),18)}))};
}

export async function fetchChainRound(database,client,plan,round,checkpoint){
  const metadata=await client.metadata(round,plan.anchor);
  const endBlock=await client.block(metadata.end.blockId);
  if(endBlock.number!==metadata.end.position[0]||endBlock.id!==metadata.end.blockId)throw new Error('SourceBlockMismatch');
  let cache=await database.transaction(db=>ensureCache(db,round,metadata));
  await checkpoint({unitsDone:cache.pages,recordsProcessed:Number(cache.event_count)});
  while(!cache.finished){
    const page=await client.logs(Number(cache.cursor_block),Number(cache.to_block),[{address:CONTRACTS.rewards,topic0:REWARD_TOPIC}],cache.cursor_offset,1000);
    cache=await database.transaction(db=>commitPage(db,round,cache,page,1000));
    await checkpoint({unitsDone:cache.pages,unitsTotal:cache.finished?cache.pages:null,recordsProcessed:Number(cache.event_count)});
  }
  const canonicalEnd=await client.block(Number(cache.to_block));
  if(canonicalEnd.id!==cache.end_block_id)throw new Error('FinalizedSourceChanged');
  const snapshot=await database.transaction(db=>sourceSnapshot(db,round,metadata,endBlock));
  // Public indexer values are diagnostics only; exact calculation amounts above
  // come from on-chain uint256 events and views, never binary floating point.
  const checks={matched:0,missing:0};
  for(const row of snapshot.metrics){
    const api=await client.overview(row.app_id,round);
    if(api===null){checks.missing++;continue;}
    if(!Number.isSafeInteger(api.totalUniqueUserInteractions)||!Number.isSafeInteger(api.actionsRewarded)
      ||String(api.totalUniqueUserInteractions)!==row.active_users||String(api.actionsRewarded)!==row.actions)throw new Error('SourceCountReconciliationMismatch');
    const amount=Number(row.distributed),reported=api.totalRewardAmount;
    if(!Number.isFinite(reported)||Math.abs(amount-reported)>Math.max(1e-8,Math.abs(amount)*1e-12))throw new Error('SourceAmountReconciliationMismatch');
    checks.matched++;
  }
  snapshot.source_manifest.api_reconciliation=checks;
  snapshot.source_manifest.finalized_round_end_verified=true;
  return snapshot;
}
