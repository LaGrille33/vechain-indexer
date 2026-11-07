import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PGlite } from '@electric-sql/pglite';
import { CONTRACTS,REWARD_TOPIC,nextCursor,parseReward,digest,ChainClient } from '../src/chain-client.mjs';
import { ensureCache,commitPage,sourceSnapshot,fetchChainRound } from '../src/chain-source.mjs';
const id=n=>'0x'+n.toString(16).padStart(64,'0');
const address=n=>'0x'+n.toString(16).padStart(40,'0');
const word=n=>BigInt(n).toString(16).padStart(64,'0');
function event(block,index,{amount='1000000000000000001',wallet=1,app=2}={}){return {
  address:CONTRACTS.rewards,topics:[REWARD_TOPIC,id(app),'0x'+address(wallet).slice(2).padStart(64,'0'),id(3)],
  data:'0x'+word(amount)+word(64)+word(0),meta:{blockNumber:block,txIndex:0,clauseIndex:0,logIndex:index,blockID:id(block)},
};}
const metadata=(round=1)=>({start:{position:[round*10,0,0,5],blockId:id(round*10)},end:{position:[(round+1)*10,0,0,5],blockId:id((round+1)*10)},
  ids:[id(2),id(9)],allocations:{[id(2)]:'10.0',[id(9)]:'0.0'},names:{[id(2)]:'Example', [id(9)]:'Inactive'}});
async function setup(t){const db=new PGlite();await db.waitReady;t.after(()=>db.close());
  for(const file of ['001-schema.sql','002-calculation.sql','003-progress.sql','004-chain-source.sql'])await db.exec(await readFile(new URL('../sql/'+file,import.meta.url),'utf8'));
  return db;}

test('block cursor keeps the tail offset, preserves same-block events and avoids unbounded whole-range offsets',()=>{
  const page=[event(10,1),event(10,2),event(11,1)];
  assert.deepEqual(nextCursor(page,10,0,3),{block:11,offset:1,finished:false});
  assert.deepEqual(nextCursor([event(11,2),event(11,3)],11,1,2),{block:11,offset:3,finished:false});
  assert.equal(nextCursor([],11,3,2).finished,true);
  assert.throws(()=>nextCursor([event(12,1),event(11,2)],10,0,2),/UnorderedSourcePage/);
  assert.throws(()=>nextCursor([event(10,1)],10,99000,1),/SingleBlockLogLimit/);
});

test('reward attribution respects exact within-block emission boundaries and preserves uint256 precision',()=>{
  const m=metadata();
  assert.equal(parseReward(event(10,4),m.start.position,m.end.position,1).round_id,0);
  assert.equal(parseReward(event(10,6),m.start.position,m.end.position,1).round_id,1);
  assert.equal(parseReward(event(20,4),m.start.position,m.end.position,1).round_id,1);
  assert.equal(parseReward(event(20,6),m.start.position,m.end.position,1),null);
  assert.equal(parseReward(event(10,4),m.start.position,m.end.position,2),null);
  assert.equal(parseReward(event(11,1),m.start.position,m.end.position,1).amount_raw,'1000000000000000001');
  assert.throws(()=>parseReward({...event(11,1),meta:{blockNumber:11}},m.start.position,m.end.position,1),/LogIndexesRequired/);
  assert.equal(digest({a:1,b:2}),digest({b:2,a:1}));
});

test('source page cursor and ledgers commit atomically; a repeated page cannot double count',async t=>{
  const db=await setup(t),m=metadata();
  let cache=await db.transaction(tx=>ensureCache(tx,1,m));
  const page=[event(10,4),event(11,1),event(11,2),event(20,4),event(20,6)];
  cache=await db.transaction(tx=>commitPage(tx,1,cache,page,5));
  const repeated=await db.transaction(tx=>commitPage(tx,1,{...cache,cursor_block:'0',cursor_offset:0},page,5));
  assert.equal(repeated.event_count,cache.event_count);
  cache=await db.transaction(tx=>commitPage(tx,1,cache,[],5));
  assert.equal(cache.finished,true);
  // JSONB key order must not break resumability.
  assert.equal((await ensureCache(db,1,m)).finished,true);
  const snapshot=await sourceSnapshot(db,1,m,{number:20,id:id(20),timestamp:100});
  assert.equal(snapshot.new_user_history_complete,true);
  assert.equal(snapshot.metrics[0].active_users,'1');assert.equal(snapshot.metrics[0].actions,'3');
  assert.equal(snapshot.metrics[0].distributed,'3.000000000000000003');
  assert.equal(snapshot.metrics[0].new_users,'0'); // this wallet was seen before cycle 1
  assert.equal(snapshot.metrics[1].active_users,'0');assert.equal(snapshot.metrics[1].distributed,'0.0');
  assert.equal((await db.query('SELECT count(*) AS n FROM reputation_pilot.chain_wallet_first')).rows[0].n,1);
});

test('partial historical start never claims verified new users; failed page writes roll back',async t=>{
  const db=await setup(t),m=metadata(2),cache=await db.transaction(tx=>ensureCache(tx,2,m));
  await assert.rejects(db.transaction(async tx=>{await commitPage(tx,2,cache,[event(21,1)],10);throw new Error('SyntheticCrash');}));
  assert.equal((await db.query('SELECT count(*) AS n FROM reputation_pilot.chain_wallet_round')).rows[0].n,0);
  assert.equal((await ensureCache(db,2,m)).pages,0);
  await db.transaction(tx=>commitPage(tx,2,cache,[event(21,1)],10));
  const snapshot=await sourceSnapshot(db,2,m,{number:30,id:id(30),timestamp:100});
  assert.equal(snapshot.new_user_history_complete,false);assert.equal(snapshot.metrics[0].new_users,null);
});

test('per-app totals preserve exact sums and earliest-wallet counts across out-of-order rounds and changing eligibility',async t=>{
  const db=await setup(t),first=metadata(),second=metadata(2);
  second.ids.push(id(77));second.allocations[id(77)]='20.0';
  let cache=await db.transaction(tx=>ensureCache(tx,2,second));
  await db.transaction(tx=>commitPage(tx,2,cache,[
    event(21,1,{wallet:1}),event(21,2,{wallet:1}),event(21,3,{wallet:3}),
    event(21,4,{wallet:1,app:77}),event(21,5,{wallet:4,app:77}),
  ],100));
  let snapshot=await db.transaction(tx=>sourceSnapshot(tx,2,second,{number:30,id:id(30),timestamp:100}));
  assert.equal(snapshot.new_user_history_complete,false);
  assert.ok(snapshot.metrics.every(r=>r.new_users===null));
  cache=await db.transaction(tx=>ensureCache(tx,1,first));
  await db.transaction(tx=>commitPage(tx,1,cache,[event(9,1,{wallet:2}),
    event(11,1,{wallet:1}),event(11,2,{wallet:1,app:77})],100));
  snapshot=await db.transaction(tx=>sourceSnapshot(tx,2,second,{number:30,id:id(30),timestamp:100}));
  assert.equal(snapshot.new_user_history_complete,true);
  assert.deepEqual(snapshot.metrics.map(r=>[r.app_id,r.active_users,r.new_users,r.actions,r.distributed]),[
    [id(2),'2','1','3','3.000000000000000003'],
    [id(9),'0','0','0','0.0'],
    [id(77),'2','1','2','2.000000000000000002'],
  ]);
  // App 77 was not eligible in round 1; its earlier rewarded wallet is still
  // history, so it must not become new again when the app enters this population.
  assert.equal((await db.query('SELECT first_round FROM reputation_pilot.chain_wallet_first WHERE app_id=$1 AND wallet=$2',[id(77),address(1)])).rows[0].first_round,1);
});

test('chain adapter reuses finished source pages, checks the canonical block and fails on production count mismatch',async t=>{
  const db=await setup(t),m=metadata(),adapter={transaction:fn=>db.transaction(fn)};let scans=0,wrong=false;
  const client={metadata:async()=>m,block:async()=>({number:20,id:id(20),timestamp:100}),
    logs:async()=>{scans++;return [event(11,1)];},overview:async app=>({totalUniqueUserInteractions:app===id(2)?1:0,
      actionsRewarded:app===id(2)?(wrong?2:1):0,totalRewardAmount:app===id(2)?1:0})};
  const plan={anchor:{id:id(100),number:100}};
  const first=await fetchChainRound(adapter,client,plan,1,async()=>{});
  assert.equal(first.source_manifest.api_reconciliation.matched,2);
  await fetchChainRound(adapter,client,plan,1,async()=>{});assert.equal(scans,1);
  wrong=true;await assert.rejects(fetchChainRound(adapter,client,plan,1,async()=>{}),/SourceCountReconciliationMismatch/);
});

test('public client reports unavailable source instead of silently substituting zeros',async()=>{
  const client=new ChainClient({fetcher:async()=>new Response('Unavailable',{status:403}),retries:0,delayMs:0});
  await assert.rejects(client.block('finalized'),/PublicSourceRequestFailed/);
});
