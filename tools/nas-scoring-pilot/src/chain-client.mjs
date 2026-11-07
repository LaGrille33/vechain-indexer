import { Interface, AbiCoder, id, formatUnits } from 'ethers';
import { createHash } from 'node:crypto';

export const SOURCE_POLICY='mainnet-reward-events-v1';
export const CONTRACTS={
  voting:'0x89a00bb0947a30ff95beef77a66aede3842fe5b7',
  emissions:'0xdf94739bd169c84fe6478d8420bb807f1f47b135',
  rewards:'0x6bee7ddab6c99d5b2af0554eaea484ce18f52631',
  pool:'0x4191776f05f4be4848d3f4d587345078b439c7d3',
  apps:'0x8392b7ccc763db03b47afcd8e8f5e24f9cf0554d',
};
export const REWARD_TOPIC=id('RewardDistributed(uint256,bytes32,address,string,address)');
export const EMISSION_TOPICS=[id('EmissionDistributed(uint256,uint256,uint256,uint256)'),id('EmissionDistributedV2(uint256,uint256,uint256,uint256,uint256)')];
const ABI=new Interface([
  'function currentRoundId() view returns(uint256)',
  'function getCurrentCycle() view returns(uint256)',
  'function getAppIdsOfRound(uint256) view returns(bytes32[])',
  'function roundEarnings(uint256,bytes32) view returns(uint256,uint256,uint256,uint256)',
  'function app(bytes32) view returns((bytes32,address,string,string,uint256,bool))',
]);
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'
  ?Object.fromEntries(Object.keys(value).sort().map(k=>[k,canonical(value[k])])):value;
export const digest=value=>createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
export function position(log){
  const m=log?.meta;
  if(!m||![m.blockNumber,m.txIndex,m.clauseIndex,m.logIndex].every(x=>Number.isSafeInteger(x)&&x>=0))throw new Error('LogIndexesRequired');
  return [m.blockNumber,m.txIndex,m.clauseIndex,m.logIndex];
}
export function compare(a,b){for(let i=0;i<a.length;i++)if(a[i]!==b[i])return a[i]<b[i]?-1:1;return 0;}
export function parseReward(log,start,end,round){
  if(log.address?.toLowerCase()!==CONTRACTS.rewards||log.topics?.[0]?.toLowerCase()!==REWARD_TOPIC||log.topics.length!==4)throw new Error('UnexpectedRewardLog');
  if(!/^0x[0-9a-f]{64}$/.test(log.topics[1])||!/^0x0{24}[0-9a-f]{40}$/.test(log.topics[2])||!/^0x(?:[0-9a-fA-F]{64}){3,}$/.test(log.data))throw new Error('MalformedRewardLog');
  const p=position(log);
  if(compare(p,end)>=0)return null;
  const before=compare(p,start)<0;
  if(before&&round!==1)return null;
  return {round_id:before?0:round,app_id:log.topics[1],wallet:'0x'+log.topics[2].slice(-40),actions:'1',amount_raw:BigInt('0x'+log.data.slice(2,66)).toString()};
}
export function nextCursor(page,block,offset,limit){
  if(page.length>limit)throw new Error('UnexpectedPageSize');
  for(let i=0;i<page.length;i++){
    if(position(page[i])[0]<block||(i&&compare(position(page[i-1]),position(page[i]))>=0))throw new Error('UnorderedSourcePage');
  }
  if(page.length<limit)return {block,offset,finished:true};
  const last=position(page.at(-1))[0];
  const tail=page.filter(x=>position(x)[0]===last).length;
  const next={block:last,offset:last===block?offset+page.length:tail,finished:false};
  if(next.offset>99000)throw new Error('SingleBlockLogLimit');
  return next;
}

export class ChainClient {
  constructor({fetcher=fetch,delayMs=250,retries=4}={}){this.fetcher=fetcher;this.delayMs=delayMs;this.retries=retries;this.last=0;}
  async request(path,body=null,{api=false,notFound=false}={}){
    const base=api?'https://indexer.mainnet.vechain.org':'https://mainnet.vechain.org';
    for(let attempt=0;;attempt++){
      const wait=Math.max(0,this.last+this.delayMs-Date.now());if(wait)await new Promise(r=>setTimeout(r,wait));this.last=Date.now();
      try{
        const response=await this.fetcher(base+path,{method:body===null?'GET':'POST',headers:{'Content-Type':'application/json'},body:body===null?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)});
        if(notFound&&response.status===404)return null;
        if(!response.ok){const e=new Error('PublicSourceHttpError');e.retry=response.status===429||response.status>=500;throw e;}
        return await response.json();
      }catch(error){if(attempt>=this.retries||error.retry===false)throw new Error('PublicSourceRequestFailed');await new Promise(r=>setTimeout(r,Math.min(16000,1000*2**attempt)));}
    }
  }
  block(revision){return this.request('/blocks/'+revision);}
  async raw(address,name,args,revision){
    const result=await this.request('/accounts/'+address+'?revision='+revision,{data:ABI.encodeFunctionData(name,args),gas:1500000,caller:'0x'+'0'.repeat(40),value:'0x0'});
    if(result.reverted||typeof result.data!=='string')throw new Error('SourceViewReverted');
    return result.data;
  }
  async view(address,name,args,revision){return ABI.decodeFunctionResult(name,await this.raw(address,name,args,revision));}
  logs(from,to,criteria,offset=0,limit=1000){return this.request('/logs/event',{range:{unit:'block',from,to},criteriaSet:criteria,options:{offset,limit,includeIndexes:true},order:'asc'}).then(r=>{if(!Array.isArray(r))throw new Error('InvalidLogResponse');return r;});}
  async boundary(round,anchor){
    const logs=await this.logs(0,anchor.number,EMISSION_TOPICS.map(topic0=>({address:CONTRACTS.emissions,topic0,topic1:'0x'+BigInt(round).toString(16).padStart(64,'0')})),0,2);
    if(logs.length!==1)throw new Error('EmissionBoundaryUnavailable');
    return {position:position(logs[0]),blockId:logs[0].meta.blockID};
  }
  async plan(from,to){
    const anchor=await this.block('finalized');
    if(!Number.isSafeInteger(anchor.number)||!/^0x[0-9a-f]{64}$/.test(anchor.id))throw new Error('InvalidFinalizedAnchor');
    const current=Number((await this.view(CONTRACTS.voting,'currentRoundId',[],anchor.id))[0]);
    const cycle=Number((await this.view(CONTRACTS.emissions,'getCurrentCycle',[],anchor.id))[0]);
    if(current!==cycle||current<2)throw new Error('RoundCycleMismatch');
    const last=current-1,begin=from==='latest'?last:Number(from),end=to==='latest'?last:Number(to);
    if(!Number.isSafeInteger(begin)||!Number.isSafeInteger(end)||begin<1||end<begin||end>last)throw new Error('CompletedRoundsRequired');
    const plan={network:'mainnet',source_kind:'blockchain',policy:SOURCE_POLICY,anchor:{number:anchor.number,id:anchor.id,timestamp:anchor.timestamp},
      from:begin,to:end,rounds:Array.from({length:end-begin+1},(_,i)=>({round:begin+i}))};
    return {...plan,hash:digest(plan)};
  }
  async metadata(round,anchor){
    const start=await this.boundary(round,anchor),end=await this.boundary(round+1,anchor);
    if(compare(start.position,end.position)>=0)throw new Error('InvalidEmissionOrder');
    const ids=[...(await this.view(CONTRACTS.voting,'getAppIdsOfRound',[round],end.blockId))[0]].map(x=>x.toLowerCase()).sort();
    if(!ids.length||new Set(ids).size!==ids.length)throw new Error('InvalidHistoricalPopulation');
    const allocations={},names={};
    for(const app of ids){
      allocations[app]=formatUnits((await this.view(CONTRACTS.pool,'roundEarnings',[round,app],end.blockId))[0],18);
      try{const data=await this.raw(CONTRACTS.apps,'app',[app],end.blockId);
        names[app]=AbiCoder.defaultAbiCoder().decode(['tuple(bytes32,address,string)'],data)[0][2];
      }catch{/* App labels are optional; never invent zero numeric inputs. */}
    }
    return {start,end,ids,allocations,names};
  }
  overview(app,round){return this.request('/api/v1/b3tr/actions/apps/'+app+'/overview?roundId='+round,null,{api:true,notFound:true});}
}
