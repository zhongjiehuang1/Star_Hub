const {test}=require('node:test'),assert=require('node:assert/strict'),{webcrypto}=require('node:crypto');
const {create}=require('../../main/webapp/provider-operations.js');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function fixture(){const values=new Map(),calls=[];let state={operationState:'SENT'};
 const api=create({storage:{getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,v)},crypto:webcrypto,actor:()=>'',tab:()=> 'a'.repeat(32),fetch:async(url,options)=>{const body=JSON.parse(options.body);calls.push({url,body});if(url==='api/provider-operation')return new Response(JSON.stringify({operationId:body.operationId,...state}),{headers:{'Content-Type':'application/json'}});throw new TypeError('Synthetic lost response');}});
 return {api,values,calls,state:v=>state=v};}
test('unknown retry preserves identity without storing prompts or credentials or dispatching twice',async()=>{const f=fixture(),body={prompt:'private-prompt-value',apiKey:'private-key-value',task:'image'};
 await assert.rejects(f.api.request('run',body));const first=f.calls[0].body;assert.equal(first.operationId,first.storageOperationId);assert.equal(first.ownerTab,'a'.repeat(32));const stored=[...f.values.values()].join();assert.ok(!stored.includes(body.prompt));assert.ok(!stored.includes(body.apiKey));await assert.rejects(f.api.request('run',body),e=>e.operationId===first.operationId);assert.equal(f.calls.filter(v=>v.url==='api/run').length,1);
});
test('confirmed original result replays without provider dispatch and requires terminal observation before retirement',async()=>{const f=fixture(),body={prompt:'synthetic question',apiKey:'synthetic credential'};await assert.rejects(f.api.request('run',body));const id=f.calls[0].body.operationId;assert.equal(f.api.acknowledge(id),false);f.state({operationState:'COMPLETED',httpStatus:200,result:{text:'saved answer'},durability:'dual'});const {response,operationId}=await f.api.request('run',body),answer=await response.json();assert.equal(operationId,id);assert.equal(answer.text,'saved answer');assert.equal(f.calls.filter(v=>v.url==='api/run').length,1);f.api.observe(id,answer);assert.equal(f.api.acknowledge(id),true);assert.equal(f.api.records().length,0);});
test('changed intent requires explicit separation while preserving the original unknown request',async()=>{const f=fixture();await assert.rejects(f.api.request('run',{prompt:'first'}));const old=f.api.records()[0].id;await assert.rejects(f.api.prepare('run',{prompt:'changed'}));f.api.separate(old);const next=await f.api.prepare('run',{prompt:'changed'});assert.notEqual(next.id,old);assert.equal(f.api.records().length,2);f.api.observe(old,{operationId:old,operationState:'COMPLETED',durability:'dual'});f.api.acknowledge(old);assert.equal(f.api.records()[0].id,next.id);});
test('missing or mismatched terminal proof cannot retire an unknown request',async()=>{const f=fixture(),p=await f.api.prepare('run',{prompt:'first'});assert.throws(()=>f.api.observe(p.id,{operationId:webcrypto.randomUUID(),operationState:'COMPLETED'}));assert.equal(f.api.acknowledge(p.id),false);f.api.markUnknown(p.id);assert.equal(f.api.records()[0].state,'UNKNOWN');});
test('the original request is frozen in memory and concurrent preparation shares one identity',async()=>{const f=fixture(),payload={prompt:'first',parameters:{temperature:1}};const [a,b]=await Promise.all([f.api.prepare('run',payload),f.api.prepare('run',payload)]);assert.equal(a.id,b.id);assert.notEqual(a.fresh,b.fresh);payload.parameters.temperature=2;assert.equal(a.body.parameters.temperature,1);assert.equal(f.api.discardUnsent(a.fresh?a:b),true);});
test('unavailable or corrupt identity storage refuses a new provider dispatch',async()=>{let calls=0;const api=create({storage:{getItem:()=>'{broken',setItem:()=>{}},crypto:webcrypto,actor:()=>'',tab:()=> 'a'.repeat(32),fetch:async()=>{calls++;}});await assert.rejects(api.request('run',{prompt:'test'}),/storage/);assert.equal(calls,0);});
test('another account cannot query a previously owned receipt',async()=>{let owner='first@example.test',calls=0;const rows=new Map();const api=create({storage:{getItem:k=>rows.get(k)||null,setItem:(k,v)=>rows.set(k,v)},crypto:webcrypto,actor:()=>owner,tab:()=> 'a'.repeat(32),fetch:async()=>{calls++;}});const operation=await api.prepare('run',{prompt:'test'});owner='second@example.test';await assert.rejects(api.query(operation.id),/another account/);assert.equal(calls,0);assert.equal((await api.visible()).length,0);});

function deferred(){let resolve;const promise=new Promise(done=>resolve=done);return {promise,resolve};}
function ownerFixture(hooks={}){
 const values=new Map(),calls=[];let email='first@example.test',epoch=0,ownerTab='a'.repeat(32),digests=0;
 const f={values,calls,email:()=>email,epoch:()=>epoch,tab:()=>ownerTab,
  change(value){email=value;epoch++;},changeTab(value){ownerTab=value;epoch++;},
  aba(){email='second@example.test';epoch++;email='first@example.test';epoch++;}};
 const crypto={randomUUID:()=>webcrypto.randomUUID(),subtle:{digest:async(...args)=>{
  const bytes=await webcrypto.subtle.digest(...args);digests++;if(hooks.digest)await hooks.digest(f,digests);return bytes;
 }}};
 f.api=create({storage:{getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,v)},crypto,
  actor:()=>email,actorEpoch:()=>epoch,tab:()=>ownerTab,changed:()=>hooks.changed?.(f),
  fetch:async(url,options)=>{const body=JSON.parse(options.body);calls.push({url,body});if(hooks.fetch)return hooks.fetch(f,url,body);
   return {ok:true,json:async()=>({operationId:body.operationId,operationState:'ABSENT'})};}});
 return f;
}
const changedOwner=error=>error.code==='ACTOR_CHANGED';

test('an explicit stale expected owner cannot create a pending identity',async()=>{
 const f=ownerFixture();await assert.rejects(f.api.prepare('run',{expectedEmail:'second@example.test'}),changedOwner);
 assert.equal(f.values.size,0);assert.equal(f.calls.length,0);
});
test('ABA at either asynchronous preparation digest cannot publish an identity or dispatch',async()=>{
 for(const boundary of [1,2]){
  const f=ownerFixture({digest:async(value,count)=>{if(count===boundary)value.aba();}});
  await assert.rejects(f.api.prepare('run',{prompt:'synthetic intent'}),changedOwner);
  assert.equal(f.values.size,0);assert.equal(f.calls.length,0);
 }
});
test('visible records reject an ABA that finishes during the owner digest',async()=>{
 let armed=false;const f=ownerFixture({digest:async(value)=>{if(armed)value.aba();}});
 await f.api.prepare('run',{prompt:'synthetic intent'});armed=true;
 await assert.rejects(f.api.visible(),changedOwner);assert.equal(f.api.records().length,1);
});
test('receipt fetch and JSON boundaries reject owner ABA without observing its result',async()=>{
 for(const boundary of ['fetch','json']){
  const f=ownerFixture({fetch:async(value,_url,body)=>{
   if(boundary==='fetch')value.aba();
   return {ok:true,json:async()=>{if(boundary==='json')value.aba();return {operationId:body.operationId,operationState:'COMPLETED',durability:'dual',result:{text:'synthetic answer'}};}};
  }});
  const operation=await f.api.prepare('run',{prompt:'synthetic intent'});
  await assert.rejects(f.api.query(operation.id),changedOwner);
  assert.equal(f.api.records()[0].state,'PENDING');assert.equal(f.calls.length,1);
 }
});
test('an ABSENT result followed by ABA cannot authorize dispatch; a new context can safely recover the original UUID',async()=>{
 let armed=false;const f=ownerFixture({changed:value=>{if(armed){armed=false;queueMicrotask(()=>value.aba());}},
  fetch:async(_value,url,body)=>({ok:true,json:async()=>({operationId:body.operationId,operationState:url==='api/provider-operation'?'ABSENT':'COMPLETED',durability:'dual',text:'synthetic result'})})});
 const intent={prompt:'synthetic intent'},operation=await f.api.prepare('run',intent);operation.fresh=false;armed=true;
 await assert.rejects(f.api.requestPrepared(operation),changedOwner);
 assert.equal(f.calls.filter(call=>call.url==='api/run').length,0);assert.equal(f.api.records()[0].id,operation.id);
 await assert.rejects(f.api.requestPrepared(operation),changedOwner);
 const replacement=await f.api.prepare('run',intent);assert.equal(replacement.id,operation.id);assert.equal(replacement.fresh,false);
 const result=await f.api.requestPrepared(replacement);assert.equal(result.operationId,operation.id);
 assert.equal(f.calls.filter(call=>call.url==='api/run').length,1);
 assert.ok(f.calls.every(call=>call.body.expectedEmail==='first@example.test'));
 assert.ok(f.calls.every(call=>!Object.hasOwn(call.body,'epoch')&&!Object.hasOwn(call.body,'actorEpoch')));
 assert.ok(!JSON.stringify([...f.values.values()]).includes('actorEpoch'));
});
test('an old fresh operation cannot dispatch after account or tab incarnation changes',async()=>{
 for(const change of [value=>value.aba(),value=>value.changeTab('b'.repeat(32))]){
  const f=ownerFixture(),operation=await f.api.prepare('run',{prompt:'synthetic intent'});change(f);
  await assert.rejects(f.api.requestPrepared(operation),changedOwner);assert.equal(f.calls.length,0);
  assert.equal(f.api.records()[0].id,operation.id);
 }
});
test('owner change during an actual provider fetch withholds the response and keeps its original pending identity',async()=>{
 const f=ownerFixture({fetch:async(value)=>{value.aba();return new Response('synthetic response');}});
 const operation=await f.api.prepare('run',{prompt:'synthetic intent'});
 await assert.rejects(f.api.requestPrepared(operation),error=>changedOwner(error)&&error.operationId===operation.id);
 assert.equal(f.calls.length,1);assert.equal(f.api.records()[0].id,operation.id);assert.equal(operation.fresh,false);
});

async function browserFixture(){
 const seeded=ownerFixture();await seeded.api.prepare('run',{prompt:'synthetic intent'});
 const handlers=new Map(),dispatched=[],panel={children:[],hidden:true,replaceChildren(){this.children=[];},append(...children){this.children.push(...children);}};
 let hold=null,entered=null;
 const context={console,TextEncoder,Response,AbortSignal,queueMicrotask,CustomEvent:class{constructor(type,options){this.type=type;this.detail=options?.detail;}},
  accountEmail:'first@example.test',accountIdentityRevision:0,StarStore:{providerTab:()=>context.ownerTab},ownerTab:'a'.repeat(32),
  sessionStorage:{getItem:key=>seeded.values.get(key)||null,setItem:(key,value)=>seeded.values.set(key,value)},
  crypto:{randomUUID:()=>webcrypto.randomUUID(),subtle:{digest:async(...args)=>{const bytes=await webcrypto.subtle.digest(...args);if(hold){const wait=hold;entered?.resolve();await wait.promise;}return bytes;}}},
  fetch:async()=>{throw Error('No browser network is permitted in this test');},
  document:{getElementById:()=>panel,createElement:tag=>({tag,children:[],append(...children){this.children.push(...children);}}),
   addEventListener:(name,handler)=>handlers.set(name,handler),dispatchEvent:event=>{dispatched.push(event);return handlers.get(event.type)?.(event);}}};
 vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/webapp/provider-operations.js'),'utf8'),context);
 return {context,panel,handlers,dispatched,seeded,
  pause(){hold=deferred();entered=deferred();return entered.promise;},resume(){const wait=hold;hold=null;wait.resolve();}};
}
test('account change clears pending DOM synchronously and a stale awaited render cannot republish it',async()=>{
 const f=await browserFixture();await f.handlers.get('starhub:ready')();assert.equal(f.panel.hidden,false);assert.ok(f.panel.children.length>0);
 const entered=f.pause(),oldRender=f.handlers.get('starhub:ready')();await entered;
 f.context.accountEmail='second@example.test';f.context.accountIdentityRevision++;
 f.context.document.dispatchEvent(new f.context.CustomEvent('starhub:account-changed'));
 assert.equal(f.panel.hidden,true);assert.equal(f.panel.children.length,0);
 f.resume();await oldRender;await new Promise(resolve=>setTimeout(resolve,0));
 assert.equal(f.panel.hidden,true);assert.equal(f.panel.children.length,0);assert.equal(f.dispatched.filter(event=>event.type==='starhub:provider-result').length,0);
});
test('ready event renders original pending identities after the tab session initializes',async()=>{
 const f=await browserFixture();f.context.ownerTab='';await f.handlers.get('DOMContentLoaded')();assert.equal(f.panel.hidden,true);
 f.context.ownerTab='a'.repeat(32);await f.handlers.get('starhub:ready')();assert.equal(f.panel.hidden,false);assert.ok(f.panel.children.length>0);
});
