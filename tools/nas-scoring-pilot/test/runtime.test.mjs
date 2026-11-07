import test from 'node:test';
import assert from 'node:assert/strict';
import { request } from 'node:http';
import { createServer } from '../src/server.mjs';
import { normalizeSnapshot } from '../src/snapshot.mjs';

const aid = digit => '0x' + digit.repeat(64);
const sample = () => ({network:'mainnet',round_id:96,source_block_number:'24613702',source_block_id:aid('a'),
  round_end_timestamp:1777852800,new_user_history_complete:false,
  source_manifest:{history_check:'not_yet_verified',population_source:'synthetic test only'},
  app_ids:[aid('2'),aid('1')], metrics:[
    {app_id:aid('2'),active_users:'7',new_users:null,actions:'12',allocated:'8.0',distributed:'1.000'},
    {app_id:aid('1'),active_users:'9007199254740993',new_users:'4',actions:'9007199254740994',allocated:'10.0000',distributed:'11.123456789012345678'},
  ]});

test('snapshot hash is deterministic across row/key order and decimal formatting, with exact large integers', () => {
  const first = sample(), second = sample(); second.metrics.reverse(); second.app_ids.reverse();
  second.metrics[0].allocated='10'; second.metrics[1].distributed='1';
  second.source_manifest={population_source:'synthetic test only',history_check:'not_yet_verified'};
  const a = normalizeSnapshot(first), b = normalizeSnapshot(second);
  assert.equal(a.hash,b.hash);
  assert.equal(a.normalized.metrics[0].active_users,'9007199254740993');
  assert.equal(a.normalized.metrics[0].distributed,'11.123456789012345678');
  second.metrics[0].new_users='5'; assert.notEqual(normalizeSnapshot(second).hash,a.hash);
});

test('snapshot rejects duplicate/missing membership, unsafe numeric counts, unknown coverage and impossible new users', () => {
  const cases = [
    x => x.app_ids.push(aid('1')),
    x => x.metrics.pop(),
    x => {x.metrics[0].active_users=7;},
    x => {x.metrics[0].new_users='8';},
    x => {delete x.new_user_history_complete;},
    x => {x.new_user_history_complete=true;},
    x => {x.metrics[0].distributed='0.0000000000000000001';},
  ];
  for (const change of cases) {const input=sample();change(input);assert.throws(() => normalizeSnapshot(input));}
});

async function start(t, options={}) {
  const server=createServer({database: async f => f({query: async () => ({rows:[]})}), ...options});
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  return (path, headers={}, method='GET', body) => new Promise((resolve,reject) => {
    const req=request({host:'127.0.0.1',port:server.address().port,path,method,headers:{Host:'localhost:8788',...headers}},res => {
      let data='';res.setEncoding('utf8');res.on('data',x=>data+=x);
      res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:data}));
    });req.on('error',reject);req.end(body);
  });
}

test('NAS adapter serves health/dashboard and rejects changed Host/cross-site/protocol-relative requests', async t => {
  const send=await start(t);
  assert.equal((await send('/health')).status,200);
  const page=await send('/');assert.equal(page.status,200);assert.match(page.body,/60/);
  assert.equal((await send('/health',{Host:'attacker.example'})).status,403);
  assert.equal((await send('/health',{'Sec-Fetch-Site':'cross-site'})).status,403);
  assert.equal((await send('//attacker.example/health')).status,400);
  assert.throws(() => createServer({origin:'http://0.0.0.0:8788'}));
});

test('NAS dashboard cannot execute while disabled; enabled execution still requires same Origin', async t => {
  let writes=0;
  const path='/api/runs/00000000-0000-4000-8000-000000000001/execute';
  const disabled=await start(t);
  assert.equal((await disabled(path,{Origin:'http://localhost:8788'},'POST')).status,403);
  const enabled=await start(t,{executionEnabled:true,database:async f=>f({query:async()=>{writes++;return {rows:[{status:'completed'}]};}})});
  assert.equal((await enabled(path,{Origin:'http://attacker.example'},'POST')).status,403);
  assert.equal(writes,0);
  assert.equal((await enabled(path,{Origin:'http://localhost:8788'},'POST')).status,200);
  assert.equal(writes,1);
});

test('NAS request size cap rejects excess data before any query', async t => {
  let queries=0;
  const send=await start(t,{database:async()=>{queries++;}});
  assert.equal((await send('/api/runs',{},'POST','x'.repeat(1025))).status,413);
  assert.equal(queries,0);
});
