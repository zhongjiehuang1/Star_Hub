'use strict';
// Browser storage contains identities/digests only, never request bodies or credentials.
(function(root){
function create({storage,crypto,fetch,actor=()=>'',actorEpoch=()=>0,tab,changed=()=>{}}){
 const KEY='starhub.provider.operations',terminal=new Set(['COMPLETED','COMPLETED_NO_RESULT']),uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
 const operationOwners=new WeakMap(); // Incarnations are RAM-only, never receipt metadata or request bodies.
 class Pending extends Error{constructor(id,state,message){super(message||'The result is unconfirmed. Check the original request before sending again.');this.operationId=id;this.operationState=state;this.code='PROVIDER_OPERATION_UNKNOWN';}}
 class ActorChanged extends Error{constructor(id){super('The account or tab changed. Return to the original workspace and check its request identity.');this.operationId=id;this.code='ACTOR_CHANGED';}}
 function captureOwner(){const owner={email:String(actor()),tab:tab(),epoch:actorEpoch()};if(!/^[0-9a-f]{32}$/.test(owner.tab)||!Number.isSafeInteger(owner.epoch)||owner.epoch<0)throw new ActorChanged();return owner;}
 function sameOwner(owner){return Boolean(owner)&&String(actor())===owner.email&&tab()===owner.tab&&actorEpoch()===owner.epoch;}
 function checkOwner(owner,id){if(!sameOwner(owner))throw new ActorChanged(id);}
 function operationOwner(operation){const owner=operationOwners.get(operation);checkOwner(owner,operation?.id);return owner;}
 function ownerGuard(){const owner=captureOwner();return ()=>sameOwner(owner);}
 function records(){try{const rows=JSON.parse(storage.getItem(KEY)||'[]');if(!Array.isArray(rows)||rows.length>40||!rows.every(v=>v&&uuid.test(v.id)&&/^[a-z-]{1,40}$/.test(v.kind)&&/^[0-9a-f]{64}$/.test(v.fingerprint)&&/^[0-9a-f]{64}$/.test(v.actorHash)&&/^[0-9a-f]{32}$/.test(v.tab)&&['PENDING','ABSENT','CLAIMED','SENT','UNKNOWN','COMPLETED','COMPLETED_NO_RESULT'].includes(v.state)&&typeof v.archived==='boolean'))throw 0;return rows;}catch{throw new Error('Pending request storage is unavailable. No provider request was sent.');}}
 function save(rows){const value=JSON.stringify(rows);try{storage.setItem(KEY,value);if(storage.getItem(KEY)!==value)throw 0;}catch{throw new Error('The request identity could not be saved. No new provider request was sent.');}changed();}
 function clean(value){if(Array.isArray(value))return value.map(clean);if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().filter(k=>!['apiKey','password','token','operationId','ownerTab','storageOperationId'].includes(k)).map(k=>[k,clean(value[k])]));return value;}
 async function digest(value){return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(value)))),v=>v.toString(16).padStart(2,'0')).join('');}
 async function visible(){const owner=captureOwner(),hash=await digest(owner.email);checkOwner(owner);return records().filter(v=>v.actorHash===hash&&v.tab===owner.tab);}
 async function prepare(kind,payload={},options={}){
  if(!/^[a-z-]{1,40}$/.test(kind))throw new Error('Unknown provider operation kind.');
  const owner=captureOwner();payload=JSON.parse(JSON.stringify(payload));checkOwner(owner);
  const ownerTab=owner.tab,expectedEmail=String(payload.expectedEmail??owner.email);if(expectedEmail!==owner.email)throw new ActorChanged();
  const actorHash=await digest(expectedEmail);checkOwner(owner);
  const fingerprint=await digest({kind,payload:clean({...payload,expectedEmail})});checkOwner(owner);const all=records();
  const scope=all.filter(v=>!v.archived&&v.tab===ownerTab&&v.actorHash===actorHash&&v.kind===kind);let item=scope.find(v=>v.fingerprint===fingerprint);const fresh=!item;
  if(!item&&scope.length&&!options.readOnly)throw new Pending(scope[0].id,scope[0].state,'An earlier request is unconfirmed. Check it, or explicitly allow a separate request that may incur another charge.');
  if(fresh){if(all.length>=40)throw new Error('Pending request history is full. Check earlier results first.');item={id:crypto.randomUUID(),kind,tab:ownerTab,actorHash,fingerprint,state:'PENDING',archived:false};all.push(item);save(all);}
  checkOwner(owner,item.id);const body={...payload,expectedEmail,ownerTab,operationId:item.id};if(kind==='run'&&body.task==='image')body.storageOperationId=item.id;const operation={id:item.id,fresh,body,kind};operationOwners.set(operation,owner);return operation;
 }
 function markUnknown(id){const all=records(),item=all.find(v=>v.id===id);if(item&&!terminal.has(item.state)){item.state='UNKNOWN';save(all);}}
 function observe(id,value){if(!value||value.operationId!==id||!['ABSENT','CLAIMED','SENT','UNKNOWN','COMPLETED','COMPLETED_NO_RESULT'].includes(value.operationState)||(terminal.has(value.operationState)&&!['dual','single'].includes(value.durability)))throw new Pending(id,'UNKNOWN');const all=records(),item=all.find(v=>v.id===id);if(item){item.state=value.operationState;if(terminal.has(value.operationState))item.durability=value.durability;else delete item.durability;save(all);}return value;}
 async function queryOwned(id,owner){
  checkOwner(owner,id);const item=records().find(v=>v.id===id),hash=await digest(owner.email);checkOwner(owner,id);
  if(!item||item.tab!==owner.tab||item.actorHash!==hash)throw new Error('This request belongs to another account or tab.');
  const response=await fetch('api/provider-operation',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify({operationId:id,ownerTab:owner.tab,expectedEmail:owner.email}),signal:AbortSignal.timeout(15000)});checkOwner(owner,id);
  const value=await response.json();checkOwner(owner,id);if(!response.ok)throw new Pending(id,'UNKNOWN',value.error||'The original result is temporarily unavailable.');const result=observe(id,value);checkOwner(owner,id);return result;
 }
 async function query(id){const owner=captureOwner(),value=await queryOwned(id,owner);checkOwner(owner,id);return value;}
 async function retryDisposition(operation){
  const owner=operationOwner(operation);if(operation.fresh){checkOwner(owner,operation.id);return null;}const value=await queryOwned(operation.id,owner);checkOwner(owner,operation.id);
  if(value.operationState==='COMPLETED'){if(value.result==null)throw new Pending(operation.id,'COMPLETED_NO_RESULT','The request finished without an available response. It will not be sent again automatically.');return new Response(JSON.stringify({...value.result,operationId:operation.id,operationState:'COMPLETED',durability:value.durability}),{status:value.httpStatus||200,headers:{'Content-Type':'application/json'}});}
  if(value.operationState==='ABSENT'||value.operationState==='CLAIMED')return null;
  throw new Pending(operation.id,value.operationState,value.operationState==='COMPLETED_NO_RESULT'?'The request finished without a retained result. A separate request may incur another charge.':undefined);
 }
 async function requestPrepared(operation,options={}){
  const owner=operationOwners.get(operation);
  try{checkOwner(owner,operation?.id);const replay=await retryDisposition(operation);checkOwner(owner,operation.id);let response=replay;
   if(!response){response=await fetch('api/'+(options.endpoint||operation.kind),{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web',...(options.headers||{})},body:JSON.stringify(operation.body),signal:options.signal});checkOwner(owner,operation.id);}
   checkOwner(owner,operation.id);return {response,operationId:operation.id};}
  catch(error){if(error.code!=='ACTOR_CHANGED'&&sameOwner(owner))markUnknown(operation.id);if(!error.operationId)error.operationId=operation?.id;throw error;}
  finally{operation.fresh=false;}
 }
 async function request(kind,payload,options={}){const operation=await prepare(kind,payload,options),owner=operationOwner(operation);const result=await requestPrepared(operation,{...options,endpoint:options.endpoint||kind});checkOwner(owner,operation.id);return result;}
 function acknowledge(id){const all=records(),item=all.find(v=>v.id===id);if(!item||!terminal.has(item.state))return false;save(all.filter(v=>v.id!==id));return true;}
 function discardUnsent(operation){if(!operation?.fresh)return false;const all=records(),item=all.find(v=>v.id===operation.id);if(!item||item.state!=='PENDING')return false;save(all.filter(v=>v.id!==operation.id));return true;}
 function separate(id){const all=records(),item=all.find(v=>v.id===id);if(item){item.archived=true;save(all);}}
 return {prepare,requestPrepared,request,query,observe,markUnknown,acknowledge,discardUnsent,separate,records,visible,retryDisposition,ownerGuard,Pending};
}
if(typeof module==='object'&&module.exports)module.exports={create};
if(root.document){
 let drawing=false,again=false,viewRevision=0;const summaries=new Map();
 const api=create({storage:{getItem:key=>root.sessionStorage.getItem(key),setItem:(key,value)=>root.sessionStorage.setItem(key,value)},crypto:root.crypto,fetch:(...args)=>root.fetch(...args),actor:()=>typeof accountEmail==='string'?accountEmail:'',actorEpoch:()=>typeof accountIdentityRevision==='number'?accountIdentityRevision:0,tab:()=>StarStore.providerTab(),changed:()=>queueMicrotask(render)});
 function invalidatePanel(){viewRevision++;summaries.clear();const panel=document.getElementById('providerPendingOperations');if(panel){panel.hidden=true;panel.replaceChildren();}queueMicrotask(render);}
 async function render(){if(drawing){again=true;return;}drawing=true;
  try{const panel=document.getElementById('providerPendingOperations');if(!panel)return;const revision=viewRevision,owner=api.ownerGuard(),active=()=>revision===viewRevision&&owner();const rows=await api.visible();if(!active())return;panel.hidden=!rows.length;panel.replaceChildren();if(!rows.length)return;
   const title=document.createElement('p');title.textContent='Unconfirmed requests: check the original result before sending again. A separate request may incur another charge.';panel.append(title);
   for(const item of rows){const row=document.createElement('p'),label=document.createElement('span');label.textContent=item.kind+' · '+item.id+' · '+item.state+(item.durability==='single'?' · degraded: one copy':'')+(item.archived?' · separate intent allowed':'');row.append(label);
    if(summaries.has(item.id)){const summary=document.createElement('pre');summary.textContent=summaries.get(item.id);row.append(summary);}
    const check=document.createElement('button');check.type='button';check.textContent='Check original result';check.onclick=async()=>{if(!active())return;check.disabled=true;try{const value=await api.query(item.id);if(!active())return;summaries.set(item.id,String(value.result?.text||value.result?.error||(value.result?.job?'Job '+value.result.job.id+' · '+value.result.job.status:value.operationState==='COMPLETED_NO_RESULT'?'Finished without a retained response. A new request may incur another charge.':value.operationState)).slice(0,4000));document.dispatchEvent(new CustomEvent('starhub:provider-result',{detail:value}));queueMicrotask(render);}catch(error){if(active())label.textContent=error.message;}finally{if(active())check.disabled=false;}};row.append(check);
    if(['ABSENT','CLAIMED'].includes(item.state)){const retry=document.createElement('button');retry.type='button';retry.textContent='Retry original request';retry.onclick=()=>{if(active())document.dispatchEvent(new CustomEvent('starhub:provider-retry',{detail:{operationId:item.id}}));};row.append(retry);}
    const separate=document.createElement('button');separate.type='button';separate.textContent=terminal(item.state)?'Acknowledge finished result':'Allow a separate request (may charge again)';separate.onclick=()=>{if(active())return terminal(item.state)?api.acknowledge(item.id):api.separate(item.id);};row.append(separate);panel.append(row);
   }
  }catch{}finally{drawing=false;if(again){again=false;queueMicrotask(render);}}
 }
 const terminal=state=>['COMPLETED','COMPLETED_NO_RESULT'].includes(state);root.StarProviderOperations=api;
 document.addEventListener('DOMContentLoaded',render);document.addEventListener('starhub:ready',render);document.addEventListener('starhub:account-changed',invalidatePanel);
}
})(globalThis);
