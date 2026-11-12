const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const root=path.join(__dirname,'../../main/webapp');
function fixture(handler,serverInfo={serverId:2},environment={}){
 const elements=new Map(),requests=[],stored=new Map();
 class Element{
  constructor(){this.children=[];this.attributes={};this.textContent='';this.hidden=true;this.disabled=false;this.checked=false;}
  append(...items){this.children.push(...items);}
  replaceChildren(...items){this.children=[...items];}
  setAttribute(k,v){this.attributes[k]=v;}
  showModal(){this.open=true;}
  close(){this.open=false;}
 }
 const element=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
 const context=vm.createContext({StarProviderMedia:{references:require('../../main/webapp/provider-media.js').references,release(){}},console,setInterval(){},crypto:require('node:crypto').webcrypto,TextEncoder,atob:s=>Buffer.from(s,'base64').toString('binary'),btoa:s=>Buffer.from(s,'binary').toString('base64'),sessionStorage:{getItem(k){return stored.get(k)||null;},setItem(k,v){stored.set(k,v);},removeItem(k){stored.delete(k);}},accountEmail:'owner@example.test',accountIdentityRevision:0,$:element,
  document:{createElement:()=>new Element(),createTextNode:text=>({textContent:text}),addEventListener(){}},
  fetch:async(url,options)=>{if(url==='api/server')return {ok:true,json:async()=>serverInfo};if(url==='api/cluster-health')return {ok:true,json:async()=>({status:'ready'})};const request=JSON.parse(options.body);requests.push(request);const value=await handler(request);return value?.__http?{ok:false,status:value.__http,json:async()=>value.body}:{ok:true,status:200,headers:{get:name=>name==='X-Four-Project-Durability'?(Object.hasOwn(value||{},'__proof')?value.__proof:'dual'):null},json:async()=>{let result=request.action==='reserveUpload'&&!value?.state?{...value,state:'RESERVED'}:value;if(result&&request.operationId&&['upload','deleteFile','trashFile','restoreFile','saveChat','deleteChat'].includes(request.action))result={...result,operationId:request.operationId,operationState:'COMMITTED'};return result;}};},confirm:()=>true,...environment});
 vm.runInContext(fs.readFileSync(path.join(root,'i18n-data.js'),'utf8'),context);
 vm.runInContext("function t(key,...args){return (UI_EN[key]||key).replace(/\\{(\\d+)\\}/g,(_,n)=>args[n]);}",context);
 vm.runInContext(fs.readFileSync(path.join(root,'cloud.js'),'utf8'),context);
 return {elements,requests,element,stored,context,run:code=>vm.runInContext(code,context)};
}
function page(number,total=6000,files){return {files:files||Array.from({length:100},(_,i)=>({id:`file-${number}-${i}`,name:`File ${i}`,size:1024,trashed:false})),used:900_000_000,quota:2_000_000_000,maxFile:4*1024*1024,page:number,pageSize:100,total,hasNext:number*100<total};}
test('file library requests pages beyond 5000 and displays the backend decimal quota',async()=>{
 const f=fixture(request=>page(request.page));await f.run('refreshFiles(51)');
 assert.equal(f.requests[0].page,51);assert.equal(f.requests[0].pageSize,100);
 assert.equal(f.element('libraryUsage').textContent,'0.900 / 2 GB · Includes trash and pending uploads');
 assert.equal(f.element('fileLibraryPageInfo').textContent,'5001–5100 of 6000 files · Page 51 of 60');
 assert.equal(f.element('fileLibraryPrevious').disabled,false);assert.equal(f.element('fileLibraryNext').disabled,false);
 await f.run('refreshFiles(60)');assert.equal(f.element('fileLibraryNext').disabled,true);
});
test('file selections survive page changes and are removed when a file is trashed',async()=>{
 const f=fixture(request=>request.action==='listFiles'?page(request.page):{});await f.run('refreshFiles(1)');
 const first=f.element('libraryFiles').children[0],checkbox=first.children[0].children[0];checkbox.checked=true;checkbox.onchange();
 await f.run('refreshFiles(2)');assert.equal(f.element('fileLibrarySelection').textContent,'1 files selected');
 await f.run('refreshFiles(1)');assert.equal(f.element('libraryFiles').children[0].children[0].children[0].checked,true);
 await f.element('libraryFiles').children[0].children[1].onclick();assert.equal(f.element('fileLibrarySelection').textContent,'0 files selected');
});
test('an empty last page returns to the current final page after deletion',async()=>{
 const f=fixture(request=>page(request.page,100,request.page===2?[]:undefined));await f.run('refreshFiles(2)');
 assert.deepEqual(f.requests.map(r=>r.page),[2,1]);assert.equal(f.element('fileLibraryNext').disabled,true);
});
test('a delayed older request cannot replace the more recent page',async()=>{
 const pending=new Map(),f=fixture(request=>new Promise(resolve=>pending.set(request.page,resolve)));
 const first=f.run('refreshFiles(1)'),second=f.run('refreshFiles(2)');await new Promise(resolve=>setImmediate(resolve));
 pending.get(2)(page(2));await second;pending.get(1)(page(1));await first;
 assert.equal(f.element('fileLibraryPageInfo').textContent,'101–200 of 6000 files · Page 2 of 60');
});
test('server badge only displays an explicit allowed server number',async()=>{
 const valid=fixture(()=>page(1));await valid.run('loadServerIdentity()');assert.equal(valid.element('serverBadge').hidden,false);assert.equal(valid.element('serverBadge').textContent,'Server 2');
 for(const id of [3,10]){const f=fixture(()=>page(1),{serverId:id});await f.run('loadServerIdentity()');assert.equal(f.element('serverBadge').textContent,`Server ${id}`);}
 for(const invalid of [{serverId:0},{serverId:'2'},{}]){const f=fixture(()=>page(1),invalid);await f.run('loadServerIdentity()');assert.equal(f.element('serverBadge').hidden,true);}
});

test('ambiguous upload retries reuse one operation ID until the original request completes',async()=>{
 let call=0;const f=fixture(()=>{call++;if(call===1)throw Error('network interruption');if(call===2)return {__http:503,body:{code:'OPERATION_PENDING',error:'服务器故障，请等待恢复'}};return {id:'saved-file'};});
 const request="workspaceRequest({action:'upload',name:'a.txt',mime:'text/plain',data:'YQ=='})";
 await assert.rejects(f.run(request),/network interruption/);await assert.rejects(f.run(request),/Server unavailable/);await f.run(request);
 assert.equal(f.requests[0].operationId,f.requests[1].operationId);assert.equal(f.requests[1].operationId,f.requests[2].operationId);assert.equal(f.requests[2].operationId,f.requests[3].operationId);
 await f.run(request);assert.notEqual(f.requests[3].operationId,f.requests[4].operationId);
});
test('content is sent only after durable metadata reservation and both phases use one identity',async()=>{
 const f=fixture(request=>request.action==='reserveUpload'?{state:'RESERVED'}:{id:'file-one',state:'COMMITTED'});
 await f.run("workspaceRequest({action:'upload',name:'a.txt',mime:'text/plain',data:'YQ=='})");
 assert.deepEqual(f.requests.map(r=>r.action),['reserveUpload','upload']);assert.equal(f.requests[0].operationId,f.requests[1].operationId);assert.equal(f.requests[0].files[0].size,1);assert(!('data' in f.requests[0]));
});
test('an uncertain reservation never sends file content or obtains a fresh identity',async()=>{
 const f=fixture(()=>({__http:503,body:{code:'OPERATION_PENDING'}}));const call="workspaceRequest({action:'upload',name:'a.txt',mime:'text/plain',data:'YQ=='})";
 await assert.rejects(f.run(call));await assert.rejects(f.run(call));assert.deepEqual(f.requests.map(r=>r.action),['reserveUpload','reserveUpload']);assert.equal(f.requests[0].operationId,f.requests[1].operationId);
});
test('authentication errors after an uncertain mutation retain the original operation identity',async()=>{
 let call=0;const f=fixture(()=>{if(++call===1)throw Error('network interruption');if(call===2)return {__http:401,body:{code:'AUTHENTICATION_REQUIRED'}};return {ok:true};});
 const request="workspaceRequest({action:'trashFile',id:'file-one'})";
 await assert.rejects(f.run(request));await assert.rejects(f.run(request));await f.run(request);
 assert.equal(f.requests[0].operationId,f.requests[1].operationId);assert.equal(f.requests[1].operationId,f.requests[2].operationId);
});
test('cluster failures show the generic banner while unrelated upstream failures do not',async()=>{
 let failure={__http:502,body:{code:'PROVIDER_UNAVAILABLE',error:'Provider error'}};const f=fixture(()=>failure);
 await f.run('checkClusterHealth()');await assert.rejects(f.run("workspaceRequest({action:'listFiles'})"));assert.equal(f.element('clusterUnavailable').hidden,true);
 failure={__http:503,body:{code:'CLUSTER_UNAVAILABLE',message:'服务器故障，请等待恢复'}};
 await assert.rejects(f.run("workspaceRequest({action:'listFiles'})"),/Server unavailable/);assert.equal(f.element('clusterUnavailable').hidden,false);assert.equal(f.element('libraryUpload').disabled,true);
 await f.run('checkClusterHealth()');assert.equal(f.element('clusterUnavailable').hidden,true);
});

test('confirmed account save rejects a silent not-ready return and persists empty provider placeholders',async()=>{
 const f=fixture(async request=>request.action==='saveChat'?{revision:1}:{}),id='11111111-1111-4111-8111-111111111111';
 f.run(`globalThis.chats=[{id:'chat-confirmed',title:'chat',messages:[{role:'assistant',content:'',providerOperationId:'${id}'}]}];`);
 await assert.rejects(f.run("saveCloudChatsConfirmed('owner@example.test','chat-confirmed')"));assert.equal(f.requests.length,0);
 f.run('cloudReady=true');const result=await f.run("saveCloudChatsConfirmed('owner@example.test','chat-confirmed')");assert.equal(result.durability,'dual');assert.equal(f.requests[0].data.messages[0].providerOperationId,id);assert.equal(f.requests[0].data.messages[0].content,'');
});
test('missing durable workspace acknowledgement cannot be marked confirmed',async()=>{
 const f=fixture(async()=>({revision:1,__proof:null}));f.run("globalThis.chats=[{id:'chat-no-proof',title:'chat',messages:[{role:'assistant',content:'',providerOperationId:'11111111-1111-4111-8111-111111111111'}]}];cloudReady=true;globalThis.showError=()=>{};");
 await assert.rejects(f.run("saveCloudChatsConfirmed('owner@example.test','chat-no-proof')"));assert.equal(f.run('cloudFailed'),true);
});

test('new mutations fail closed when session operation identity cannot be read, written or read back',async()=>{
 for(const change of ["sessionStorage.getItem=()=>{throw Error('blocked')}","sessionStorage.setItem=()=>{throw Error('full')}","sessionStorage.setItem=()=>{}","sessionStorage.getItem=()=> 'not-a-valid-operation-id'"]){
  const f=fixture(()=>({ok:true}));f.run(change);
  await assert.rejects(f.run("workspaceRequest({action:'trashFile',id:'file-one'})"),error=>error.code==='OPERATION_ID_NOT_RETAINED');
  assert.equal(f.requests.length,0);assert.equal(f.run('pendingFileOperations.size'),0);
 }
});
test('an already retained uncertain operation may retry in memory when session storage becomes unavailable',async()=>{
 let calls=0;const f=fixture(()=>{if(++calls===1)throw Error('reply lost');return {ok:true};});
 const request="workspaceRequest({action:'trashFile',id:'file-one'})";
 await assert.rejects(f.run(request),/reply lost/);const operation=f.requests[0].operationId;
 f.run("sessionStorage.getItem=()=>{throw Error('blocked')};sessionStorage.setItem=()=>{throw Error('blocked')};sessionStorage.removeItem=()=>{throw Error('blocked')}");
 await f.run(request);await f.run(request);
 assert(f.requests.every(r=>r.operationId===operation));assert.equal(f.run('pendingFileOperations.size'),1);
});
test('a retained operation identity survives a page reload and no UUID is substituted',async()=>{
 const retained=new Map(),storage={getItem:key=>retained.get(key)||null,setItem:(key,value)=>retained.set(key,value),removeItem:key=>retained.delete(key)};
 const first=fixture(()=>{throw Error('reply lost');},{serverId:1},{sessionStorage:storage}),request="workspaceRequest({action:'trashFile',id:'file-one'})";
 await assert.rejects(first.run(request));const original=first.requests[0].operationId;
 const reloaded=fixture(()=>({ok:true}),{serverId:2},{sessionStorage:storage});await reloaded.run(request);
 assert.equal(reloaded.requests[0].operationId,original);assert.equal(retained.size,0);
});
test('an account switch during operation hashing prevents dispatch of the old chat to either account',async()=>{
 let release;const real=require('node:crypto').webcrypto,crypto={randomUUID:()=>real.randomUUID(),subtle:{digest:(...args)=>new Promise(resolve=>{release=async()=>resolve(await real.subtle.digest(...args));})}};
 const f=fixture(()=>({revision:1}),{serverId:2},{crypto});
 const pending=f.run("workspaceRequest({action:'saveChat',id:'chat-one',title:'private',data:{messages:[{role:'user',content:'old account'}]},revision:0})");
 f.run("accountEmail='new@example.test'");await release();
 await assert.rejects(pending,error=>error.code==='ACTOR_CHANGED');assert.equal(f.requests.length,0);assert.equal(f.stored.size,0);
});
test('an account switch during upload content hashing cannot reserve under a new owner',async()=>{
 let release,count=0;const real=require('node:crypto').webcrypto,crypto={randomUUID:()=>real.randomUUID(),subtle:{digest:(...args)=>++count===1?real.subtle.digest(...args):new Promise(resolve=>{release=async()=>resolve(await real.subtle.digest(...args));})}};
 const f=fixture(()=>({state:'RESERVED'}),{serverId:2},{crypto}),pending=f.run("workspaceRequest({action:'upload',name:'private.txt',mime:'text/plain',data:'YQ=='})");
 while(!release)await new Promise(resolve=>setImmediate(resolve));f.run("accountEmail='new@example.test'");await release();
 await assert.rejects(pending,error=>error.code==='ACTOR_CHANGED'&&Boolean(error.operationId));assert.equal(f.requests.length,0);assert.equal(f.stored.size,1);
});
test('account changes after reservation stop content dispatch and preserve the original owner operation',async()=>{
 let f;f=fixture(request=>{assert.equal(request.expectedEmail,'owner@example.test');f.run("accountEmail='new@example.test'");return {state:'RESERVED'};});
 await assert.rejects(f.run("workspaceRequest({action:'upload',name:'private.txt',mime:'text/plain',data:'YQ=='})"),error=>error.code==='ACTOR_CHANGED');
 assert.deepEqual(f.requests.map(r=>r.action),['reserveUpload']);assert.equal(f.stored.size,1);assert.equal(f.run('pendingFileOperations.size'),1);
});
test('an account A-B-A transition during hashing cannot dispatch an old mutation',async()=>{
 let release;const real=require('node:crypto').webcrypto,crypto={randomUUID:()=>real.randomUUID(),subtle:{digest:(...args)=>new Promise(resolve=>{release=async()=>resolve(await real.subtle.digest(...args));})}};
 const f=fixture(()=>({revision:1}),undefined,{crypto});
 const pending=f.run("workspaceRequest({action:'saveChat',id:'old-chat',title:'old',data:{messages:[]},revision:0})");
 f.run("accountEmail='other@example.test';accountIdentityRevision++;accountEmail='owner@example.test';accountIdentityRevision++");await release();
 await assert.rejects(pending,error=>error.code==='ACTOR_CHANGED');assert.equal(f.requests.length,0);assert.equal(f.stored.size,0);
});
test('an account A-B-A transition during file hashing retains the original upload identity without dispatch',async()=>{
 let release,count=0;const real=require('node:crypto').webcrypto,crypto={randomUUID:()=>real.randomUUID(),subtle:{digest:(...args)=>++count===1?real.subtle.digest(...args):new Promise(resolve=>{release=async()=>resolve(await real.subtle.digest(...args));})}};
 const f=fixture(()=>({state:'RESERVED'}),undefined,{crypto});const pending=f.run("workspaceRequest({action:'upload',name:'old.txt',mime:'text/plain',data:'YQ=='})");
 while(!release)await new Promise(resolve=>setImmediate(resolve));f.run("accountEmail='other@example.test';accountIdentityRevision++;accountEmail='owner@example.test';accountIdentityRevision++");await release();
 await assert.rejects(pending,error=>error.code==='ACTOR_CHANGED'&&Boolean(error.operationId));assert.equal(f.requests.length,0);assert.equal(f.stored.size,1);
});
test('an A-B-A reservation reply cannot authorize file-content dispatch or retire its unknown identity',async()=>{
 let f;f=fixture(()=>{f.run("accountEmail='other@example.test';accountIdentityRevision++;accountEmail='owner@example.test';accountIdentityRevision++");return {state:'RESERVED'};});
 await assert.rejects(f.run("workspaceRequest({action:'upload',name:'old.txt',mime:'text/plain',data:'YQ=='})"),error=>error.code==='ACTOR_CHANGED');
 assert.deepEqual(f.requests.map(r=>r.action),['reserveUpload']);assert.equal(f.stored.size,1);
});
test('queued saves and deletes do not cross an account A-B-A transition',async()=>{
 for(const action of ['save','delete']){
  const f=fixture(()=>({revision:4}));f.run("cloudReady=true;oldChat={id:'old',title:'old',revision:3,messages:[{role:'user',content:'private'}]};chats=[oldChat];cloudQueue=new Promise(resolve=>globalThis.releaseQueue=resolve)");
  const pending=f.run(action==='save'?'saveCloudChats()':'deleteCloudChat(oldChat)');
  f.run("accountEmail='other@example.test';accountIdentityRevision++;accountEmail='owner@example.test';accountIdentityRevision++;chats=[{id:'new',title:'new',messages:[]}];releaseQueue()");
  await assert.rejects(pending,error=>error.code==='ACTOR_CHANGED');assert.equal(f.requests.length,0);assert.equal(f.run('oldChat.revision'),3);assert.equal(f.run('cloudFailed'),false);assert.equal(f.run('chats[0].id'),'new');
 }
});
test('a library FileReader result cannot upload into a later A-B-A account session',async()=>{
 let release;const f=fixture(()=>({}),undefined,{readFileData:()=>new Promise(resolve=>{release=resolve;})});f.element('libraryUpload').files=[{name:'private.txt',size:1}];f.element('libraryUpload').value='original';
 const pending=f.element('libraryUpload').onchange();f.run("accountEmail='other@example.test';accountIdentityRevision++;accountEmail='owner@example.test';accountIdentityRevision++");f.element('libraryUpload').value='new-selection';release('data:text/plain;base64,YQ==');await pending;
 assert.equal(f.requests.length,0);assert.equal(f.element('libraryUpload').value,'new-selection');
});
test('a delayed old-session list or attachment cannot render after an account A-B-A transition',async()=>{
 for(const action of ['list','hydrate']){
  let f;f=fixture(()=>{f.run("accountEmail='other@example.test';accountIdentityRevision++;accountEmail='owner@example.test';accountIdentityRevision++");return action==='list'?page(1):{name:'old.png',mime:'image/png',data:'YQ=='};});
  if(action==='list'){await f.run('refreshFiles(1)');assert.equal(f.run('fileLibraryResult'),null);assert.equal(f.element('libraryFiles').children.length,0);}
  else{f.run("oldChat={messages:[{role:'user',content:'private',fileIds:['old-file'],imageCount:1}]}");await assert.rejects(f.run('hydrateChat(oldChat)'),error=>error.code==='ACTOR_CHANGED');assert.equal(f.run('oldChat.hydrated'),undefined);assert.equal(f.run('oldChat.messages[0].images'),undefined);}
 }
});
const guestSource='11111111-1111-4111-8111-111111111111',guestMedia='22222222-2222-4222-8222-222222222222',importFile='33333333-3333-4333-8333-333333333333';
const guestRef={type:'image',mime:'image/png',mediaRef:{operationId:guestSource,mediaId:guestMedia,mimeType:'image/png'}};
function importReply(body,state='COMMITTED',valid=true){const value={operationId:body.operationId,mediaId:body.mediaId,importOperationId:body.importOperationId,state,...(state==='COMMITTED'?{fileId:importFile,mime:'image/png'}:{})};return {ok:true,status:200,headers:{get:name=>valid?({'X-Four-Project-Durability':'dual','X-Starhub-Import-Operation-ID':body.importOperationId,'X-Starhub-Import-State':state}[name]||null):null},json:async()=>value};}
test('guest image import queries its original committed file after a lost reply without a second copy',async()=>{
 const calls=[];let committed=false,writes=0;const f=fixture(()=>({}),undefined,{accountIdentityRevision:0,StarStore:{providerTab:()=> 'a'.repeat(32)},fetch:async(url,options)=>{const body=JSON.parse(options.body);calls.push(body);if(body.action==='import'){writes++;committed=true;throw Error('synthetic lost import reply');}return importReply(body,committed?'COMMITTED':'ABSENT');}});
 f.run('guestRef='+JSON.stringify(guestRef));const call=`importGeneratedImage(guestRef,'${guestSource}','owner@example.test',0)`;
 await assert.rejects(f.run(call),/lost import reply/);const result=await f.run(call);assert.equal(result.fileId,importFile);assert.equal(writes,1);assert.equal(calls[0].importOperationId,calls[1].importOperationId);assert.equal(calls[1].importOperationId,calls[2].importOperationId);assert.equal(result.importOperationId,calls[0].importOperationId);
});
test('a changed import account or missing import confirmation cannot rewrite guest references',async()=>{
 let f,calls=0;f=fixture(()=>({}),undefined,{accountIdentityRevision:0,StarStore:{providerTab:()=> 'a'.repeat(32)},fetch:async(url,options)=>{if(url!=='api/provider-media')return {ok:true,json:async()=>({serverId:2,status:'ready'})};calls++;const reply=importReply(JSON.parse(options.body));const json=reply.json;reply.json=async()=>{f.run("accountEmail='another@example.test';accountIdentityRevision++");return json();};return reply;}});
 f.run('guestRef='+JSON.stringify(guestRef));await assert.rejects(f.run(`importGeneratedImage(guestRef,'${guestSource}','owner@example.test',0)`));assert.equal(calls,1);assert.equal(f.run('guestRef.mediaRef.mediaId'),guestMedia);
 const broken=fixture(()=>({}),undefined,{accountIdentityRevision:0,StarStore:{providerTab:()=> 'a'.repeat(32)},fetch:async(url,options)=>importReply(JSON.parse(options.body),'COMMITTED',false)});broken.run('guestRef='+JSON.stringify(guestRef));await assert.rejects(broken.run(`importGeneratedImage(guestRef,'${guestSource}','owner@example.test',0)`),/unconfirmed/);
});
test('a partially failed conversation import preserves every original guest id and media reference',async()=>{
 let imports=0;const f=fixture(()=>({}),undefined,{accountIdentityRevision:0,StarStore:{providerTab:()=> 'a'.repeat(32)},fetch:async(url,options)=>{const body=JSON.parse(options.body);if(body.action==='import-status')return importReply(body,'ABSENT');if(++imports===2)throw Error('synthetic second-image failure');return importReply(body);}});
 f.run(`loadCloudChats=async()=>[];askImport=async()=>true;guestOriginal=[{id:'original-guest-chat',title:'Synthetic',messages:[{role:'assistant',content:'first',providerOperationId:'${guestSource}',mediaRefs:[${JSON.stringify(guestRef)}]},{role:'assistant',content:'second',providerOperationId:'${guestSource}',mediaRefs:[${JSON.stringify({...guestRef,mediaRef:{...guestRef.mediaRef,mediaId:importFile}})}]}]}];guestBefore=JSON.stringify(guestOriginal)`);
 await assert.rejects(f.run('accountChatsOnLogin(guestOriginal)'),/second-image failure/);assert.equal(f.run('JSON.stringify(guestOriginal)===guestBefore'),true);
});

// Exact single-file FileStorage.Operation.result() contract, not a separate
// frontend mock protocol: ids[0] and id agree, ok reflects state, and only
// RESERVED adds pending/reservedBytes. reserve replay/status retain the UUID.
function attachmentReply(request,row){return {operationId:request.operationId,state:row.state,ids:[row.fileId],id:row.fileId,ok:row.state==='COMMITTED',...(row.state==='RESERVED'?{pending:true,reservedBytes:row.metadata.size}:{})};}
function attachmentService(options={}){
 const operations=new Map(),writes=[],real=require('node:crypto').webcrypto;
 return {operations,writes,handler:async request=>{
  if(request.action==='reserveUpload'){
   assert.equal(request.files.length,1);let row=operations.get(request.operationId);
   if(!row){row={fileId:real.randomUUID(),state:'RESERVED',metadata:request.files[0]};operations.set(request.operationId,row);}
   else assert.equal(JSON.stringify(row.metadata),JSON.stringify(request.files[0]),'The same UUID must retain its exact metadata');
   return options.reserve?options.reserve(request,row):attachmentReply(request,row);
  }
  const row=operations.get(request.operationId);assert.ok(row,'Only an explicitly reserved original operation may be used');
  if(request.action==='operationStatus')return attachmentReply(request,row);
  assert.equal(request.action,'upload');assert.equal(row.state,'RESERVED');
  assert.equal(request.name,row.metadata.name);assert.equal(request.mime,row.metadata.mime);
  assert.equal(Buffer.from(request.data,'base64').length,row.metadata.size);
  assert.equal(Buffer.from(await real.subtle.digest('SHA-256',Buffer.from(request.data,'base64'))).toString('base64'),row.metadata.sha256);
  if(options.upload)await options.upload(request,row);
  row.state='COMMITTED';writes.push({operationId:request.operationId,fileId:row.fileId});
  if(options.afterCommit)await options.afterCommit(request,row);
  return attachmentReply(request,row);
 }};
}
function attachmentCall(data='data:text/plain;base64,YQ==',epoch=0){return `importGuestAttachment(${JSON.stringify(data)},'original-chat',0,0,'owner@example.test',${epoch})`;}
function prepareOrdinaryImport(f,guest){
 f.context.guestOriginal=guest;f.context.chats=[];f.context.current=null;f.run('loadCloudChats=async()=>[];askImport=async()=>true;activateChat=()=>{};newChat=()=>{};saveCloudChats=async()=>{};');
}
test('a second attachment failure and conversation retry reuse the first committed file and preserve the entire guest source',async()=>{
 let fail=true;const service=attachmentService({upload:async request=>{if(request.name==='Guest-attachment-1-2'&&fail){fail=false;throw Error('synthetic second attachment failure');}}});
 const f=fixture(service.handler),guest=[{id:'original-chat',title:'Original',draft:'retained draft',messages:[{role:'user',content:'original',images:['data:image/png;base64,AQID'],attachments:['data:text/plain;base64,Yg=='],providerOperationId:guestSource,mediaRefs:[guestRef],media:[{...guestRef,url:'blob:original-guest-preview'}],details:{retained:true}}]}];
 prepareOrdinaryImport(f,guest);f.run(`importGeneratedImage=async()=>({type:'image',mime:'image/png',fileId:'${importFile}',importOperationId:'${guestSource}'})`);const before=JSON.stringify(guest);
 await assert.rejects(f.run('accountChatsOnLogin(guestOriginal)'),/second attachment failure/);
 assert.equal(JSON.stringify(guest),before);assert.equal(service.operations.size,2);assert.equal(service.writes.length,1);
 const first=service.writes[0];await f.run('accountChatsOnLogin(guestOriginal)');
 assert.equal(JSON.stringify(guest),before);assert.equal(service.operations.size,2);assert.equal(service.writes.length,2);
 assert.equal(service.writes.filter(row=>row.operationId===first.operationId).length,1);
 assert.equal(f.run('chats[0].messages[0].fileIds[0]'),first.fileId);assert.equal(f.run('chats[0].messages[0].imageCount'),1);
 assert.equal(f.stored.size,3);for(const [key,value] of f.stored){const record=JSON.parse(value);assert.equal(record.version,1);assert.ok(key.startsWith('starhub.guest-chat-import.')?record.targetChatId:record.fileId);assert.equal(Object.hasOwn(record,'data'),false);assert.equal(Object.hasOwn(record,'owner'),false);}
 assert.equal(JSON.stringify([...f.stored]).includes('owner@example.test'),false);
});
test('a lost content reply is resolved by operationStatus under the original UUID without another upload',async()=>{
 let lose=true;const service=attachmentService({afterCommit:async()=>{if(lose){lose=false;throw Error('synthetic committed reply lost');}}}),f=fixture(service.handler);
 const file=await f.run(attachmentCall());assert.equal(service.writes.length,1);
 assert.deepEqual(f.requests.map(row=>row.action),['reserveUpload','upload','operationStatus']);
 assert.equal(await f.run(attachmentCall()),file);assert.equal(service.writes.length,1);assert.equal(service.operations.size,1);
 assert.ok(f.requests.every(request=>request.operationId===f.requests[0].operationId));assert.equal(f.stored.size,1);
});
test('ordinary attachment identity survives reload and successful child IDs are not retired',async()=>{
 let fail=true;const service=attachmentService({upload:async()=>{if(fail){fail=false;throw Error('synthetic upload interruption');}}}),retained=new Map();
 const storage={getItem:key=>retained.get(key)||null,setItem:(key,value)=>retained.set(key,value),removeItem:key=>retained.delete(key)};
 const first=fixture(service.handler,undefined,{sessionStorage:storage});await assert.rejects(first.run(attachmentCall()));const original=first.requests[0].operationId;
 const reloaded=fixture(service.handler,undefined,{sessionStorage:storage});await reloaded.run(attachmentCall());
 assert.ok(reloaded.requests.every(request=>request.operationId===original));assert.equal(service.writes.length,1);assert.equal(retained.size,1);
});
test('changed source content or metadata refuses the retained slot rather than silently choosing another UUID',async()=>{
 const service=attachmentService(),f=fixture(service.handler);await f.run(attachmentCall());
 const saved=JSON.stringify([...f.stored]),count=f.requests.length;
 for(const changed of ['data:text/plain;base64,Yg==','data:application/pdf;base64,YQ=='])await assert.rejects(f.run(attachmentCall(changed)),error=>error.code==='IMPORT_IDENTITY_UNAVAILABLE');
 assert.equal(f.requests.length,count);assert.equal(JSON.stringify([...f.stored]),saved);assert.equal(service.operations.size,1);
});
test('an account ABA during attachment hashing cannot create a reservation',async()=>{
 let f,armed=true;const real=require('node:crypto').webcrypto,crypto={randomUUID:()=>real.randomUUID(),subtle:{digest:async(...args)=>{const digest=await real.subtle.digest(...args);if(armed){armed=false;f.run("accountEmail='other@example.test';accountIdentityRevision++;accountEmail='owner@example.test';accountIdentityRevision++");}return digest;}}};
 f=fixture(()=>{throw Error('No SQL dispatch allowed');},undefined,{crypto});
 await assert.rejects(f.run(attachmentCall()),/import account changed/);assert.equal(f.requests.length,0);assert.equal(f.stored.size,0);
});
test('an account ABA after reservation prevents content; the new context can recover the original ID',async()=>{
 let f,armed=true;const service=attachmentService({reserve:(request,row)=>{if(armed){armed=false;f.run("accountEmail='other@example.test';accountIdentityRevision++;accountEmail='owner@example.test';accountIdentityRevision++");}return attachmentReply(request,row);}});f=fixture(service.handler);
 await assert.rejects(f.run(attachmentCall()),error=>error.code==='ACTOR_CHANGED');assert.deepEqual(f.requests.map(row=>row.action),['reserveUpload']);
 const original=f.requests[0].operationId;await f.run(attachmentCall(undefined,2));
 assert.equal(service.writes.length,1);assert.ok(f.requests.every(request=>request.operationId===original));
});
test('unreadable, corrupt or unretained import identities prevent any reservation',async()=>{
 for(const change of ["sessionStorage.getItem=()=>{throw Error('blocked')}","sessionStorage.setItem=()=>{}","sessionStorage.getItem=()=>'{broken'","sessionStorage.setItem=()=>{throw Error('full')}"]){
  const f=fixture(()=>{throw Error('No dispatch allowed');});f.run(change);await assert.rejects(f.run(attachmentCall()),error=>error.code==='IMPORT_IDENTITY_UNAVAILABLE');assert.equal(f.requests.length,0);
 }
});
test('incomplete or changed reservation identity and durability never authorize content',async()=>{
 for(const invalid of ['wrong-operation','wrong-file','missing-durability','contradictory-id']){
  const service=attachmentService({reserve:(request,row)=>{const result=attachmentReply(request,row);if(invalid==='wrong-operation')result.operationId=importFile;if(invalid==='wrong-file')result.ids=[];if(invalid==='missing-durability')result.__proof=null;if(invalid==='contradictory-id')result.id=guestMedia;return result;}}),f=fixture(service.handler);
  await assert.rejects(f.run(attachmentCall()));assert.equal(f.requests.filter(row=>row.action==='upload').length,0);assert.equal(f.stored.size,1);
 }
});
test('library selection never supplies ownership or bypasses an imported attachment reservation',async()=>{
 const service=attachmentService(),f=fixture(service.handler);f.run("librarySelection.set('data:text/plain;base64,YQ==','someone-elses-file')");
 const file=await f.run(attachmentCall());assert.notEqual(file,'someone-elses-file');assert.deepEqual(f.requests.map(row=>row.action),['reserveUpload','upload']);
 assert.equal(f.run("librarySelection.get('data:text/plain;base64,YQ==')"),'someone-elses-file');
});
test('legacy audio/x-wav guest attachments retain the backend-supported MIME during import',async()=>{
 const service=attachmentService(),f=fixture(service.handler);await f.run(attachmentCall('data:audio/x-wav;base64,AQID'));
 assert.equal(f.requests[0].files[0].mime,'audio/x-wav');assert.equal(f.requests[1].mime,'audio/x-wav');assert.equal(service.writes.length,1);
});
test('unsupported old inline generated media blocks the whole import before writes and retains all source data',async()=>{
 for(const encoded of ['AQID',Buffer.alloc(4*1024*1024+1).toString('base64')]){
  const f=fixture(()=>{throw Error('No write is allowed');}),guest=[{id:'original-chat',title:'Original',messages:[{role:'user',content:'first',images:['data:image/png;base64,AQID']},{role:'assistant',content:'old inline',media:[{type:'image',url:'data:image/png;base64,'+encoded}]}]}];
  prepareOrdinaryImport(f,guest);const before=JSON.stringify(guest);await assert.rejects(f.run('accountChatsOnLogin(guestOriginal)'),/old inline generated image/);
  assert.equal(f.requests.length,0);assert.equal(JSON.stringify(guest)===before,true);assert.equal(f.stored.size,0);
 }
});
test('ordinary imports enforce the exact 4 MiB boundary and reject a larger old inline attachment',async()=>{
 const service=attachmentService(),f=fixture(service.handler);f.context.exact='data:application/pdf;base64,'+Buffer.alloc(4*1024*1024).toString('base64');
 await f.run("importGuestAttachment(exact,'original-chat',0,0,'owner@example.test',0)");assert.equal(service.writes.length,1);
 f.context.large='data:application/pdf;base64,'+Buffer.alloc(4*1024*1024+1).toString('base64');
 const count=f.requests.length;await assert.rejects(f.run("importGuestAttachment(large,'original-chat',0,1,'owner@example.test',0)"),/4 MiB|oversized/);assert.equal(f.requests.length,count);
});

function chatImportFixture(options={}){
 const files=attachmentService(),saved=new Map();let saveCalls=0,lose=Boolean(options.loseReply);
 const clone=value=>JSON.parse(JSON.stringify(value));
 const f=fixture(async request=>{
  if(request.action!=='saveChat')return files.handler(request);
  saveCalls++;assert.equal(saved.has(request.id),false,'A retry must not try to create a second saved chat');
  saved.set(request.id,{id:request.id,title:request.title,revision:1,data:clone(request.data)});
  if(lose){lose=false;throw Error('synthetic chat commit reply lost');}
  return {revision:1};
 },undefined,{StarChatPages:{collect:async()=>({chats:clone([...saved.values()]),confirmedDurability:'dual'})}});
 const guest=[{id:'original-chat',title:'Original',draft:'draft',messages:[{role:'user',content:'source content',images:['data:image/png;base64,AQID'],details:{webSearchUsed:true,webCitations:[{url:'https://example.test/source',title:'Citation',start_index:0,end_index:3}]}}]}];
 f.context.guestOriginal=guest;f.context.chats=[];f.context.current=null;
 f.run('askImport=async()=>true;activateChat=()=>{};newChat=()=>{};showError=()=>{};');
 return {...f,files,saved,guest,saveCalls:()=>saveCalls};
}
test('a lost chat save reply followed by whole import retry uses the same target and does not append, save or copy twice',async()=>{
 const f=chatImportFixture({loseReply:true}),before=JSON.stringify(f.guest);
 await assert.rejects(f.run('accountChatsOnLogin(guestOriginal)'),/chat commit reply lost/);
 assert.equal(f.saved.size,1);assert.equal(f.files.writes.length,1);assert.equal(JSON.stringify(f.guest),before);
 const target=[...f.saved.keys()][0],saved=f.saved.get(target);
 // MySQL/JSON property order is not a business edit. Actual payload values and
 // array order remain unchanged; canonical comparison must recognize them.
 const citation=saved.data.messages[0].details.webCitations[0];saved.data.messages[0].details.webCitations[0]=Object.fromEntries(Object.entries(citation).reverse());
 await f.run('accountChatsOnLogin(guestOriginal)');
 assert.equal(f.saved.size,1);assert.equal(f.saveCalls(),1);assert.equal(f.files.writes.length,1);
 assert.equal(f.run('chats.length'),1);assert.equal(f.run('chats[0].id'),target);assert.equal(f.run('chats[0].revision'),1);
 assert.equal(JSON.stringify(f.guest),before);assert.equal([...f.stored.keys()].filter(key=>key.startsWith('starhub.guest-chat-import.')).length,1);
});
test('an existing imported target edited by the user is preserved and explicitly conflicts instead of being overwritten',async()=>{
 const f=chatImportFixture(),before=JSON.stringify(f.guest);await f.run('accountChatsOnLogin(guestOriginal)');
 const target=[...f.saved.values()][0];target.data.messages[0].content='user edit after import';const edited=JSON.stringify(target);
 await assert.rejects(f.run('accountChatsOnLogin(guestOriginal)'),error=>error.code==='GUEST_CHAT_IMPORT_CONFLICT');
 assert.equal(f.saved.size,1);assert.equal(f.saveCalls(),1);assert.equal(f.files.writes.length,1);
 assert.equal(JSON.stringify(target),edited);assert.equal(JSON.stringify(f.guest),before);
});
test('a changed source fingerprint refuses the original target slot before any new file copy or chat save',async()=>{
 const f=chatImportFixture();await f.run('accountChatsOnLogin(guestOriginal)');
 const target=[...f.saved.values()][0],originalTarget=JSON.stringify(target),mapping=JSON.stringify([...f.stored]),requests=f.requests.length;
 f.guest[0].messages[0].content='changed guest source';const changed=JSON.stringify(f.guest);
 await assert.rejects(f.run('accountChatsOnLogin(guestOriginal)'),error=>error.code==='GUEST_CHAT_IMPORT_CONFLICT');
 assert.equal(f.requests.length,requests);assert.equal(f.saveCalls(),1);assert.equal(f.saved.size,1);
 assert.equal(JSON.stringify(target),originalTarget);assert.equal(JSON.stringify(f.guest),changed);assert.equal(JSON.stringify([...f.stored]),mapping);
});
test('temporary preview URLs and loading state do not alter a retained chat source identity',async()=>{
 const f=fixture(()=>{throw Error('No network is allowed');});
 f.context.sourceChat={id:'original-chat',title:'Original',messages:[{role:'assistant',content:'source',providerOperationId:guestSource,mediaRefs:[guestRef],media:[{...guestRef,url:'blob:first'}]}]};
 const first=await f.run("guestChatTarget(sourceChat,'owner@example.test',0)");
 f.run("sourceChat.messages[0].media[0].url='blob:second';sourceChat.messages[0].mediaLoading=true;sourceChat.messages[0].mediaPreviewOffset=4");
 assert.equal(await f.run("guestChatTarget(sourceChat,'owner@example.test',0)"),first);assert.equal(f.stored.size,1);assert.equal(f.requests.length,0);
});
test('all target UUIDs must be retained before any attachment is copied',async()=>{
 const f=fixture(()=>{throw Error('No write should occur');}),guest=[1,2].map(n=>({id:'source-'+n,title:'Source '+n,messages:[{role:'user',content:'source',images:['data:image/png;base64,AQID']}]}));prepareOrdinaryImport(f,guest);const before=JSON.stringify(guest);let writes=0;
 const set=f.context.sessionStorage.setItem;f.context.sessionStorage.setItem=(key,value)=>{if(key.startsWith('starhub.guest-chat-import.')&&++writes===2)throw Error('synthetic storage full');set(key,value);};
 await assert.rejects(f.run('accountChatsOnLogin(guestOriginal)'),error=>error.code==='GUEST_CHAT_IMPORT_CONFLICT');
 assert.equal(f.requests.length,0);assert.equal(JSON.stringify(guest),before);assert.equal(f.stored.size,1);
});
test('account ABA during either chat source digest prevents target publication and file copies',async()=>{
 for(const boundary of [1,2]){
  let f,count=0;const real=require('node:crypto').webcrypto,crypto={randomUUID:()=>real.randomUUID(),subtle:{digest:async(...args)=>{const bytes=await real.subtle.digest(...args);if(++count===boundary)f.run("accountEmail='other@example.test';accountIdentityRevision++;accountEmail='owner@example.test';accountIdentityRevision++");return bytes;}}};
  f=fixture(()=>{throw Error('No write allowed');},undefined,{crypto});const guest=[{id:'original-chat',title:'Original',messages:[{role:'user',content:'source',images:['data:image/png;base64,AQID']}]}];prepareOrdinaryImport(f,guest);const before=JSON.stringify(guest);
  await assert.rejects(f.run('accountChatsOnLogin(guestOriginal)'),/import account changed/);assert.equal(f.requests.length,0);assert.equal(f.stored.size,0);assert.equal(JSON.stringify(guest),before);
 }
});
