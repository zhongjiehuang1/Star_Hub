// Execute the actual application handlers with deterministic DOM/network edges.
// These tests are local VM regressions, not browser/HTTP acceptance results.
const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {webcrypto}=require('node:crypto');
const providerMedia=require('../../main/webapp/provider-media.js');
const web=path.join(__dirname,'../../main/webapp');
const app=fs.readFileSync(path.join(web,'app.js'),'utf8');
const cloud=fs.readFileSync(path.join(web,'cloud.js'),'utf8');
const operation='11111111-1111-4111-8111-111111111111';
const owner='first@example.test';
function id(number){return String(number).padStart(8,'0')+'-2222-4222-8222-222222222222';}
function reference(number){return {type:'image',mime:'image/png',fileId:id(number)};}
function deferred(){let resolve;const promise=new Promise(done=>resolve=done);return {promise,resolve};}
function section(source,start,end){const begin=source.indexOf(start),finish=end?source.indexOf(end,begin):-1;assert.ok(begin>=0);assert.ok(!end||finish>begin);return source.slice(begin,end?finish:undefined);}
async function settle(predicate){for(let tick=0;tick<100;tick++){if(predicate())return;await new Promise(resolve=>setImmediate(resolve));}throw Error('Expected local asynchronous boundary was not reached');}
async function binary(body,data=new Uint8Array([1,2,3])){
 return new Response(data,{headers:{'Content-Type':'image/png','Content-Length':String(data.length),
  'X-Content-SHA256':Buffer.from(await webcrypto.subtle.digest('SHA-256',data)).toString('hex'),
  'X-Starhub-Operation-ID':body.operationId,'X-Starhub-Operation-State':'COMPLETED','X-Four-Project-Durability':'dual'}});
}
function fixture(options={}){
 const nodes=new Map(),events=new Map(),revoked=[],created=[],requests=[],errors=[],cachedViews=new WeakMap();let serial=0;
 function element(tag,text){return {tagName:tag.toUpperCase(),textContent:text||'',children:[],append(...children){this.children.push(...children);},setAttribute(){},removeAttribute(){},classList:{remove(){}}};}
 function $(name){if(!nodes.has(name))nodes.set(name,{...element('div'),value:'',files:[],disabled:false,checked:false,
  focus(){},addEventListener(type,callback){events.set(name+':'+type,callback);}});return nodes.get(name);}
 const chat={id:'original-chat',title:'Original',messages:[]};
 const ctx={$: $,document:{addEventListener:(type,callback)=>events.set(type,callback)},messageViews:cachedViews,
  accountEmail:owner,accountIdentityRevision:0,current:chat,chats:[chat],ownerTab:'a'.repeat(32),
  realtimeActive:false,cloudHydrating:false,cloudReady:true,ready:true,busy:false,publicInsecure:false,
  queueRender(){},render(){},updateConnection(){},setBusy(value){ctx.busy=value;},showError:value=>{if(value)errors.push(value);},element,t:value=>value,uiText:value=>value,
  selectedModel:()=>({inputs:['image'],referenceLimits:{image:4}}),providers:[{id:'synthetic',name:'Synthetic'}],
  crypto:webcrypto,AbortController,performance:{now:()=>0},setInterval:()=>1,clearInterval(){},setTimeout:()=>2,clearTimeout(){},
  providerRetries:new Map(),StarStore:{savePending(){}},saveCloudChats:async()=>{},persistProviderHistory:async()=>{},
  readCloudFile:async()=>({url:'data:image/png;base64,AQID'}),
  StarProviderOperations:{prepare:async()=>({id:operation,fresh:true}),discardUnsent(){},observe(){},markUnknown(){},acknowledge(){return true;},
   requestPrepared:async()=>({response:new Response(JSON.stringify({operationId:operation,operationState:'COMPLETED',durability:'dual',text:'synthetic answer',media:[]}),{headers:{'Content-Type':'application/json'}})})},
  ReplyReveal:class{constructor(message){this.message=message;}append(text){this.message.content+=text;}async finish(){}},
  collectParameters:async()=>({}),readInputFiles:async()=>['data:image/png;base64,AQID'],uploadSubmittedFiles:async()=>[id(10)]};
 const api=providerMedia.create({crypto:webcrypto,Blob,actor:()=>ctx.accountEmail,tab:()=>ctx.ownerTab,
  URL:{createObjectURL(){const url='blob:synthetic-'+(++serial);created.push(url);return url;},revokeObjectURL:url=>revoked.push(url)},
  fetch:async(_url,request)=>{const body=JSON.parse(request.body);requests.push(body);return options.fetch?options.fetch(body,request):binary(body);}});
 ctx.StarProviderMedia=api;vm.createContext(ctx);
 vm.runInContext(section(app,'let generatedPreviewMessage='),ctx);
 vm.runInContext(section(app,'function activateChat(','async function deleteChat('),ctx);
 vm.runInContext(section(app,"document.addEventListener('starhub:provider-result'","$('prompt').addEventListener('keydown'"),ctx);
 vm.runInContext(section(app,"$('chatForm').addEventListener('submit'","document.addEventListener('starhub:provider-retry'"),ctx);
 for(const [name,value] of Object.entries({task:'chat',prompt:'original draft',provider:'synthetic',model:'model',apiKey:'synthetic-placeholder',effort:'default'}))$(name).value=value;
 $('inputFile').files=[{name:'original.png'}];$('webSearch').disabled=true;
 return {ctx,chat,$,api,events,created,revoked,requests,errors,cachedViews,
  change(type){const other={id:'other-chat',title:'Other',messages:[]};ctx.accountEmail='second@example.test';ctx.accountIdentityRevision++;ctx.current=other;ctx.chats=[other];
   if(type==='ABA'){ctx.accountEmail=owner;ctx.accountIdentityRevision++;ctx.current=chat;ctx.chats=[chat];}return other;},
  submit:()=>events.get('chatForm:submit')({preventDefault(){}})};
}

for(const boundary of ['collectParameters','readInputFiles','prepare'])for(const change of ['B','ABA']){
 test('owner '+change+' during '+boundary+' cannot upload or append to either conversation',async()=>{
  const f=fixture(),entered=deferred(),release=deferred();let uploads=0,dispatches=0,discarded=0;
  const wait=async result=>{entered.resolve();await release.promise;return result;};
  if(boundary==='prepare')f.ctx.StarProviderOperations.prepare=()=>wait({id:operation,fresh:true});
  else f.ctx[boundary]=()=>wait(boundary==='readInputFiles'?['data:image/png;base64,AQID']:{});
  f.ctx.uploadSubmittedFiles=async()=>{uploads++;return[id(10)];};
  f.ctx.StarProviderOperations.requestPrepared=async()=>{dispatches++;throw Error('Must not dispatch');};
  f.ctx.StarProviderOperations.discardUnsent=()=>{discarded++;};
  const pending=f.submit();await entered.promise;const other=f.change(change);release.resolve();await pending;
  assert.equal(uploads,0);assert.equal(dispatches,0);assert.equal(f.chat.messages.length,0);assert.equal(other.messages.length,0);
  assert.equal(discarded,boundary==='prepare'?1:0);
 });
}

test('unchanged owner passes the real submission handler and stores a completed placeholder',async()=>{
 const f=fixture();let uploads=0,dispatches=0;
 f.ctx.uploadSubmittedFiles=async(_files,_names,email,active)=>{assert.equal(email,owner);assert.equal(active(),true);uploads++;return[id(10)];};
 const send=f.ctx.StarProviderOperations.requestPrepared;f.ctx.StarProviderOperations.requestPrepared=async(...args)=>{dispatches++;return send(...args);};
 await f.submit();assert.equal(uploads,1);assert.equal(dispatches,1);assert.equal(f.chat.messages.length,2);
 assert.equal(f.chat.messages[1].content,'synthetic answer');assert.equal(f.chat.messages[1].incomplete,false);
});

test('parallel messages publish only the latest preview and release a preceding preview',async()=>{
 const entered=deferred(),release=deferred();const f=fixture({fetch:async body=>{if(body.mediaId===id(2)){entered.resolve();await release.promise;}return binary(body);}});
 const first={providerOperationId:operation,mediaRefs:[reference(1)]},abandoned={providerOperationId:operation,mediaRefs:[reference(2)]},latest={providerOperationId:operation,mediaRefs:[reference(3)]};
 f.chat.messages=[first,abandoned,latest];await f.ctx.displayGeneratedPreview(first,f.chat,owner);const old=first.media[0].url;
 const a=f.ctx.displayGeneratedPreview(abandoned,f.chat,owner);await entered.promise;
 const b=f.ctx.displayGeneratedPreview(latest,f.chat,owner);release.resolve();await Promise.all([a,b]);
 assert.equal(f.api.owns(old),false);assert.ok(f.revoked.includes(old));
 assert.equal((abandoned.media||[]).filter(item=>f.api.owns(item.url)).length,0);
 assert.equal(latest.media.length,1);assert.equal(f.api.owns(latest.media[0].url),true);
 assert.equal(f.created.filter(url=>f.api.owns(url)).length,1);
});

test('actual chat activation A to B to A invalidates an awaiting image response',async()=>{
 const entered=deferred(),release=deferred(),f=fixture({fetch:async body=>{entered.resolve();await release.promise;return binary(body);}});
 const message={providerOperationId:operation,mediaRefs:[reference(1)]};f.chat.messages=[message];const other={id:'other',messages:[]};
 const pending=f.ctx.displayGeneratedPreview(message,f.chat,owner);await entered.promise;
 f.ctx.activateChat(other);f.ctx.activateChat(f.chat);release.resolve();await pending;
 assert.equal(f.ctx.current,f.chat);assert.equal(f.created.length,0);assert.equal((message.media||[]).length,0);assert.equal(message.mediaLoading,false);
});

test('same completed operation queried again releases its old Blob before replacing media',async()=>{
 const f=fixture(),message={role:'assistant',content:'original',providerOperationId:operation,mediaRefs:[reference(1)],imageSaveFailed:true};f.chat.messages=[message];
 await f.ctx.displayGeneratedPreview(message,f.chat,owner);const old=message.media[0].url;let retired=0;
 f.ctx.StarProviderOperations.acknowledge=()=>{retired++;};
 await f.events.get('starhub:provider-result')({detail:{operationId:operation,operationState:'COMPLETED',durability:'dual',result:{text:'same result',media:[reference(1)],imageSaveFailed:true}}});
 assert.equal(f.api.owns(old),false);assert.ok(f.revoked.includes(old));assert.equal(f.api.owns(message.media[0].url),true);
 assert.equal(f.created.filter(url=>f.api.owns(url)).length,1);assert.equal(retired,0);
 assert.equal(JSON.stringify(f.api.references(message.media)).includes('blob:'),false);
});

test('release removes cached DOM source attributes, playback resources and message view ownership',async()=>{
 const f=fixture(),message={providerOperationId:operation,mediaRefs:[reference(1)]};f.chat.messages=[message];
 await f.ctx.displayGeneratedPreview(message,f.chat,owner);const url=message.media[0].url,removed=[],loaded=[];
 const mediaNodes=['img','audio','video'].map(tag=>({removeAttribute:name=>removed.push(tag+':'+name),...(tag!=='img'?{load:()=>loaded.push(tag)}:{})}));
 f.cachedViews.set(message,{row:{querySelectorAll:selector=>{assert.equal(selector,'img,audio,video');return mediaNodes;}}});
 f.ctx.releaseGeneratedPreview(f.chat);
 assert.equal(f.cachedViews.has(message),false);assert.deepEqual(removed,['img:src','audio:src','video:src']);assert.deepEqual(loaded,['audio','video']);
 assert.equal(f.api.owns(url),false);assert.equal(message.media[0].url,undefined);assert.equal(message.mediaRefs[0].fileId,id(1));
});

test('legacy library data URLs are removed from RAM messages when their chat is released',async()=>{
 const f=fixture(),message={role:'assistant',content:'legacy',mediaRefs:[reference(1)]};f.chat.messages=[message];
 await f.ctx.displayGeneratedPreview(message,f.chat,owner);assert.ok(message.media[0].url.startsWith('data:'));
 f.ctx.releaseGeneratedPreview(f.chat);assert.equal(message.media[0].url,undefined);assert.equal(message.mediaRefs[0].fileId,id(1));
});

test('legacy histories preserve five refs in account and guest serialization while previewing only four per group',async()=>{
 const f=fixture(),message={role:'assistant',content:'legacy five',mediaRefs:[1,2,3,4,5].map(reference)};f.chat.messages=[message];
 vm.runInContext(section(cloud,'function cleanMessage(','async function cloudUpload('),f.ctx);
 assert.equal(f.ctx.cleanMessage(message).mediaRefs.length,5);
 const previous=global.StarProviderMedia;global.StarProviderMedia=providerMedia;
 try{const store=require('../../main/webapp/storage.js');assert.equal(store.snapshot([f.chat],f.chat.id).chats[0].messages[0].mediaRefs.length,5);}
 finally{if(previous===undefined)delete global.StarProviderMedia;else global.StarProviderMedia=previous;}
 const reads=[];f.ctx.readCloudFile=async file=>{reads.push(file);return {url:'data:image/png;base64,AQID'};};
 await f.ctx.displayGeneratedPreview(message,f.chat,owner);assert.equal(reads.length,4);assert.equal(message.media.length,4);assert.equal(message.mediaRefs.length,5);
 const controls={children:[],append(value){this.children.push(value);}};f.ctx.appendGeneratedPreviewControl(controls,message);assert.equal(controls.children.length,1);
 controls.children[0].onclick();await settle(()=>!message.mediaLoading);
 assert.equal(reads.length,5);assert.equal(message.media.length,1);assert.equal(message.mediaPreviewOffset,4);assert.equal(message.media[0].fileId,id(5));
 assert.equal(message.mediaRefs.length,5);
});

test('owner ABA during result JSON reading cannot publish media or retire the original request',async()=>{
 const entered=deferred(),release=deferred(),f=fixture();let retired=0;
 f.ctx.StarProviderOperations.acknowledge=()=>{retired++;};
 f.ctx.StarProviderOperations.requestPrepared=async()=>({response:{ok:true,headers:new Headers({'Content-Type':'application/json'}),json:async()=>{entered.resolve();await release.promise;return {operationId:operation,operationState:'COMPLETED',durability:'dual',text:'synthetic result',media:[reference(1)]};}}});
 const pending=f.submit();await entered.promise;f.change('ABA');release.resolve();await pending;
 assert.equal(f.created.length,0);assert.equal(retired,0);assert.equal(f.chat.messages.length,2);
 assert.equal(f.chat.messages[1].incomplete,true);assert.equal((f.chat.messages[1].mediaRefs||[]).length,0);
});
