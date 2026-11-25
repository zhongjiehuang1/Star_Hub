// Execute the actual application handlers with deterministic DOM/network edges.
// These tests are local VM regressions, not browser/HTTP acceptance results.
const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {webcrypto}=require('node:crypto');
const providerMedia=require('../../main/webapp/provider-media.js');
const imageIntent=require('../../main/webapp/image-intent.js');
const visuals=require('../../main/webapp/visuals.js');
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
  realtimeActive:false,cloudHydrating:false,cloudReady:true,ready:true,busy:false,publicInsecure:false,chatActivation:0,
  queueRender(){},render(){},renderChatAttachments(){},updateConnection(){},setBusy(value){ctx.busy=value;},showError:value=>{if(value)errors.push(value);},element,t:value=>value,uiText:value=>value,errorText:value=>value,
  catalog:{models:[{id:'image-model',provider:'openai',categories:['image'],inputs:['text'],selectionTier:'flagship'}]},selectedModel:()=>({inputs:['image'],referenceLimits:{image:4}}),providers:[{id:'synthetic',name:'Synthetic'}],
  crypto:webcrypto,AbortController,performance:{now:()=>0},setInterval:()=>1,clearInterval(){},setTimeout:()=>2,clearTimeout(){},
  providerRetries:new Map(),StarStore:{savePending(){},whenHydrated:async()=>{}},saveCloudChats:async()=>{},persistProviderHistory:async()=>{},
  readCloudFile:async()=>({url:'data:image/png;base64,AQID'}),
  StarProviderOperations:{prepare:async()=>({id:operation,fresh:true}),discardUnsent(){},observe(){},markUnknown(){},acknowledge(){return true;},
   requestPrepared:async()=>({response:new Response(JSON.stringify({operationId:operation,operationState:'COMPLETED',durability:'dual',text:'synthetic answer',media:[]}),{headers:{'Content-Type':'application/json'}})})},
  ReplyReveal:class{constructor(message){this.message=message;}append(text){this.message.content+=text;}showAll(){} async finish(){}},
  StarVisuals:visuals,StarImageIntent:imageIntent,
  readInputFiles:async()=>['data:image/png;base64,AQID'],uploadSubmittedFiles:async()=>[id(10)]};
 const api=providerMedia.create({crypto:webcrypto,Blob,actor:()=>ctx.accountEmail,tab:()=>ctx.ownerTab,wait:async()=>{},
  URL:{createObjectURL(){const url='blob:synthetic-'+(++serial);created.push(url);return url;},revokeObjectURL:url=>revoked.push(url)},
  fetch:async(_url,request)=>{const body=JSON.parse(request.body);requests.push(body);return options.fetch?options.fetch(body,request):binary(body);}});
 ctx.StarProviderMedia=api;vm.createContext(ctx);
 vm.runInContext(section(app,'function validatedProviderContext(','function primaryDirection('),ctx);
 vm.runInContext(section(app,'let generatedPreviewMessage='),ctx);
 vm.runInContext(section(app,'function activateChat(','async function deleteChat('),ctx);
 vm.runInContext(section(app,"document.addEventListener('starhub:provider-retry'","document.addEventListener('starhub:provider-result'"),ctx);
 vm.runInContext(section(app,"document.addEventListener('starhub:provider-result'","$('prompt').addEventListener('keydown'"),ctx);
 vm.runInContext(section(app,"$('chatForm').addEventListener('submit'","document.addEventListener('starhub:provider-retry'"),ctx);
 for(const [name,value] of Object.entries({task:'chat',prompt:'original draft',provider:'synthetic',model:'model',apiKey:'synthetic-placeholder',effort:'default'}))$(name).value=value;
 $('inputFile').files=[{name:'original.png'}];$('webSearch').disabled=true;
 return {ctx,chat,$,api,events,created,revoked,requests,errors,cachedViews,
  change(type){const other={id:'other-chat',title:'Other',messages:[]};ctx.accountEmail='second@example.test';ctx.accountIdentityRevision++;ctx.current=other;ctx.chats=[other];
   if(type==='ABA'){ctx.accountEmail=owner;ctx.accountIdentityRevision++;ctx.current=chat;ctx.chats=[chat];}return other;},
  submit:()=>events.get('chatForm:submit')({preventDefault(){}})};
}

for(const boundary of ['readInputFiles','prepare'])for(const change of ['B','ABA']){
 test('owner '+change+' during '+boundary+' cannot upload or append to either conversation',async()=>{
  const f=fixture(),entered=deferred(),release=deferred();let uploads=0,dispatches=0,discarded=0;
  const wait=async result=>{entered.resolve();await release.promise;return result;};
  if(boundary==='prepare')f.ctx.StarProviderOperations.prepare=()=>wait({id:operation,fresh:true});
  else f.ctx.readInputFiles=()=>wait(['data:image/png;base64,AQID']);
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

test('a dropped chat stream restores the saved reply from the same operation without sending again',async()=>{
 const f=fixture(),originalTab='b'.repeat(32),saves=[];
 let dispatches=0,reads=0,acknowledgements=0;
 f.ctx.StarProviderOperations.prepare=async()=>({id:operation,fresh:true,body:{ownerTab:originalTab}});
 f.ctx.StarProviderOperations.requestPrepared=async()=>{
  dispatches++;
  return {response:new Response('',{headers:{'Content-Type':'text/event-stream'}})};
 };
 f.ctx.readReplyEvents=async()=>{throw Error('Stream closed before the terminal event');};
 f.ctx.StarProviderOperations.querySaved=async(id,tab)=>{
  assert.equal(id,operation);assert.equal(tab,originalTab);reads++;
  return {operationId:operation,operationState:'COMPLETED',httpStatus:200,durability:'single',
   result:{text:'Recovered friction diagram',reasoningSummary:'Saved reasoning'}};
 };
 f.ctx.persistProviderHistory=async()=>{
  const reply=f.chat.messages[1];
  saves.push([reply.content,reply.incomplete,reply.providerOwnerTab]);
 };
 f.ctx.StarProviderOperations.acknowledge=()=>{
  assert.deepEqual(saves.at(-1),['Recovered friction diagram',false,originalTab]);acknowledgements++;
 };
 await f.submit();
 await settle(()=>acknowledgements===1);
 assert.equal(dispatches,1);assert.equal(reads,1);assert.equal(f.chat.messages.length,2);
 assert.deepEqual(saves[0],['',true,originalTab]);
 assert.equal(f.chat.messages[1].reasoningSummary,'Saved reasoning');
});

test('a saved placeholder can recover on opening its original tab, even without a stored owner tab',async()=>{
 const f=fixture();
 const reply={role:'assistant',content:'',incomplete:true,providerOperationId:operation};
 f.chat.messages.push({role:'user',content:'Follow-up diagram'},reply);
 f.ctx.StarProviderOperations.query=async id=>{
  assert.equal(id,operation);
  return {operationId:id,operationState:'COMPLETED',httpStatus:200,durability:'single',result:{text:'Original result'}};
 };
 f.ctx.activateChat(f.chat);
 await settle(()=>reply.content==='Original result'&&!reply.providerSavePending);
 assert.equal(reply.incomplete,false);
});

test('reopening an already restored reply clears a matching receipt in the original tab',async()=>{
 const f=fixture();
 const reply={role:'assistant',content:'Original result',incomplete:false,providerOperationId:operation};
 f.chat.messages.push({role:'user',content:'Follow-up diagram'},reply);
 let acknowledged=false;
 f.ctx.StarProviderOperations.records=()=>[{id:operation,kind:'run',state:'UNKNOWN',archived:false}];
 f.ctx.StarProviderOperations.query=async id=>({operationId:id,operationState:'COMPLETED',httpStatus:200,durability:'single',result:{text:'Original result'}});
 f.ctx.StarProviderOperations.acknowledge=id=>{assert.equal(id,operation);acknowledged=true;};
 f.ctx.activateChat(f.chat);
 await settle(()=>acknowledged);
 assert.equal(reply.content,'Original result');
});

test('a final save error leaves the complete response readable and the receipt available',async()=>{
 const f=fixture();let saves=0,acknowledgements=0;
 f.ctx.persistProviderHistory=async()=>{if(++saves===2)throw Error('Cloud save failed');};
 f.ctx.StarProviderOperations.acknowledge=()=>{acknowledgements++;};
 await f.submit();
 const reply=f.chat.messages[1];
 assert.equal(reply.content,'synthetic answer');
 assert.equal(reply.incomplete,false);
 assert.equal(reply.providerSavePending,true);
 assert.equal(acknowledgements,0);
});

test('replacing an inline diagram sends the current discussion to the chat model',async()=>{
 const f=fixture();
 const diagram='传送带上的摩擦力\n\n```star-visual\n{}\n```';
 f.chat.messages.push({role:'user',content:'图解摩擦力'},{role:'assistant',content:diagram});
 f.$('prompt').value='换一个';f.$('inputFile').files=[];f.ctx.readInputFiles=async()=>[];
 let prepared;
 f.ctx.StarProviderOperations.prepare=async(_,request)=>{prepared=request;return {id:operation,fresh:true};};
 await f.submit();
 assert.equal(prepared.task,'chat');
 assert.equal(prepared.model,'model');
 assert.deepEqual(Array.from(prepared.messages,message=>message.content),['图解摩擦力',diagram,'换一个']);
});

test('drawing an explanation sends the current CPA turns to chat and skips the old image result',async()=>{
 const f=fixture();
 f.chat.messages.push(
  {role:'user',content:'注册会计师怎么干活的'},
  {role:'assistant',content:'先评估重大错报风险，再取证并发表审计意见。'},
  {role:'user',content:'画图讲解'},
  {role:'assistant',content:'Image generated.',mediaRefs:[reference(12)]}
 );
 f.$('prompt').value='画图讲解';
 f.$('inputFile').files=[];
 f.ctx.readInputFiles=async()=>[];
 let prepared;
 f.ctx.StarProviderOperations.prepare=async(_,request)=>{prepared=request;return {id:operation,fresh:true};};
 await f.submit();
 assert.equal(prepared.task,'chat');
 assert.equal(prepared.model,'model');
 assert.deepEqual(Array.from(prepared.messages,message=>message.content),[
  '注册会计师怎么干活的','先评估重大错报风险，再取证并发表审计意见。','画图讲解','画图讲解'
 ]);
 assert.match(prepared.system,/most recent relevant topic/);
 assert.match(prepared.system,/star-visual/);
});

test('unconfirmed requests point to request history without changing completed-result errors',async()=>{
 const hint='请打开连接设置，在请求记录中查看原请求。';
 for(const state of ['UNKNOWN','SENT','COMPLETED','COMPLETED_NO_RESULT']){
  const f=fixture();f.ctx.StarProviderOperations.records=()=>[{id:operation,state,archived:false}];
  f.ctx.StarProviderOperations.requestPrepared=async()=>{const error=new Error('synthetic failure');if(state.startsWith('COMPLETED'))Object.assign(error,{operationId:operation,operationState:state});throw error;};
  await f.submit();const message=f.errors.at(-1);assert.ok(message.includes('synthetic failure'));
  assert.equal(message.includes(hint),!state.startsWith('COMPLETED'),state);
 }
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
 await settle(()=>message.media?.length===1);
 assert.equal(f.ctx.current,f.chat);assert.equal(f.requests.length,2);assert.equal(f.created.length,1);assert.equal(f.api.owns(message.media[0].url),true);assert.equal(message.mediaLoading,false);
});

test('activating a saved conversation automatically loads its latest image only',async()=>{
 const f=fixture(),older={providerOperationId:operation,mediaRefs:[reference(1)]},latest={providerOperationId:operation,mediaRefs:[reference(2)]};f.chat.messages=[older,latest];
 f.ctx.activateChat(f.chat);await settle(()=>latest.media?.length===1);
 assert.equal(f.requests.length,1);assert.equal(f.requests[0].mediaId,reference(2).fileId);assert.equal(older.media,undefined);
});

test('automatic preview does not retry a failed read or cross an account change',async()=>{
 const f=fixture({fetch:async()=>{throw Error('unavailable');}}),message={providerOperationId:operation,mediaRefs:[reference(1)]};f.chat.messages=[message];
 f.ctx.activateChat(f.chat);await settle(()=>message.imageUnavailable===true);await new Promise(resolve=>setImmediate(resolve));assert.equal(f.requests.length,1);
 const other=fixture(),saved={providerOperationId:operation,mediaRefs:[reference(2)]};other.chat.messages=[saved];other.ctx.activateChat(other.chat);other.change('ABA');await new Promise(resolve=>setImmediate(resolve));assert.equal(other.requests.length,0);
});

test('busy and missing images receive distinct preview states without implying deletion',async()=>{
 for(const [status,code,expected] of [[429,'PROVIDER_BUSY','busy'],[404,'FILE_NOT_FOUND','missing'],[503,'UNAVAILABLE','unavailable']]){
  const f=fixture({fetch:async()=>new Response(JSON.stringify({code}),{status,headers:{'Content-Type':'application/json','Retry-After':'1'}})}),message={providerOperationId:operation,mediaRefs:[reference(1)]};f.chat.messages=[message];
  await f.ctx.displayGeneratedPreview(message,f.chat,owner);assert.equal(message.imageUnavailable,true);assert.equal(message.imageUnavailableReason,expected);assert.equal(f.requests.length,status===429?3:1);
 }
});

test('automatic preview waits for account conversation hydration',async()=>{
 const f=fixture(),gate=deferred(),message={providerOperationId:operation,mediaRefs:[reference(3)]};f.chat.messages=[message];f.chat.hydrated=false;
 f.ctx.hydrateChat=async chat=>{await gate.promise;chat.hydrated=true;};f.ctx.activateChat(f.chat);await new Promise(resolve=>setImmediate(resolve));assert.equal(f.requests.length,0);
 gate.resolve();await settle(()=>message.media?.length===1);assert.equal(f.requests.length,1);assert.equal(f.requests[0].mediaId,reference(3).fileId);
});

test('automatic preview waits for guest hydration and rechecks its account and activation',async()=>{
 const gate=deferred(),f=fixture(),message={providerOperationId:operation,mediaRefs:[reference(3)]};f.chat.messages=[message];f.ctx.StarStore.whenHydrated=()=>gate.promise;
 f.ctx.activateChat(f.chat);await new Promise(resolve=>setImmediate(resolve));assert.equal(f.requests.length,0);
 gate.resolve();await settle(()=>message.media?.length===1);assert.equal(f.requests.length,1);
 const next=deferred(),g=fixture(),saved={providerOperationId:operation,mediaRefs:[reference(4)]};g.chat.messages=[saved];g.ctx.StarStore.whenHydrated=()=>next.promise;
 g.ctx.activateChat(g.chat);await new Promise(resolve=>setImmediate(resolve));g.change('ABA');next.resolve();await new Promise(resolve=>setImmediate(resolve));assert.equal(g.requests.length,0);
 const h=fixture(),last={providerOperationId:operation,mediaRefs:[reference(5)]};h.chat.messages=[last];h.ctx.StarStore.whenHydrated=async()=>{throw Error('unconfirmed');};h.ctx.activateChat(h.chat);await new Promise(resolve=>setImmediate(resolve));assert.equal(h.requests.length,0);
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

function realConversationStorage(f){
 const entries=new Map(),guest={values:{}},actions=[];
 const sessionStorage={getItem:key=>entries.get(key)??null,setItem:(key,value)=>entries.set(key,value),removeItem:key=>entries.delete(key)};
 f.ctx.TextEncoder=TextEncoder;f.ctx.sessionStorage=sessionStorage;f.ctx.window={sessionStorage,localStorage:sessionStorage,dispatchEvent(){}};
 f.ctx.cloudQueue=Promise.resolve();f.ctx.cloudFailed=false;f.ctx.pendingFileOperations=new Map();f.ctx.historySaveTimer=0;
 f.ctx.fetch=async(url,options)=>{
  const body=JSON.parse(options.body);actions.push({url,body});
  const headers={'Content-Type':'application/json','X-Four-Project-Durability':'dual'};
  if(url==='api/guest-session'){
   if(body.action==='get')return new Response(JSON.stringify(guest.values),{headers});
   if(body.action==='begin')guest.staged='';if(body.action==='chunk')guest.staged+=body.data;
   if(body.action==='commit')guest.values[body.field]=JSON.parse(guest.staged);
   return new Response(JSON.stringify({state:'COMMITTED',action:body.action,transactionId:body.tx,operationId:operation}),{headers});
  }
  assert.equal(url,'api/workspace');assert.equal(body.action,'saveChat');
  return new Response(JSON.stringify({operationId:body.operationId,operationState:'COMMITTED',revision:body.revision+1}),{headers});
 };
 vm.runInContext(fs.readFileSync(path.join(web,'storage.js'),'utf8'),f.ctx);
 vm.runInContext(section(cloud,'function workspaceOwner(','function forgetFileOperation('),f.ctx);
 vm.runInContext(section(cloud,'function serialCloud(','async function importGeneratedImage('),f.ctx);
 const experience=fs.readFileSync(path.join(web,'experience.js'),'utf8');
 vm.runInContext(section(experience,'async function persistProviderHistory(','function refreshConnectionSummary('),f.ctx);
 return actions;
}
test('real submission confirms one pre-dispatch save with no empty guest mutation or duplicate save',async()=>{
 const f=fixture(),actions=realConversationStorage(f);f.ctx.readInputFiles=async()=>[];f.$('inputFile').files=[];
 await vm.runInContext('StarStore.initGuest()',f.ctx);actions.length=0;let dispatches=0;
 const original=f.ctx.StarProviderOperations.requestPrepared;
 f.ctx.StarProviderOperations.requestPrepared=async(...args)=>{
  dispatches++;const saves=actions.filter(q=>q.url==='api/workspace');assert.equal(saves.length,1);
  assert.equal(saves[0].body.data.messages.length,2);assert.equal(saves[0].body.data.messages[1].providerOperationId,operation);
  assert.equal(saves[0].body.data.messages[1].content,'');assert.equal(f.chat.revision,1);
  return original(...args);
 };
 await f.submit();assert.equal(dispatches,1);assert.equal(actions.filter(q=>q.url==='api/guest-session').length,0);
 assert.equal(actions.filter(q=>q.url==='api/workspace').length,2);assert.equal(f.chat.revision,2);
 assert.equal(f.chat.messages[1].incomplete,false);assert.equal(f.ctx.cloudFailed,false);
});
test('retry of an ABSENT original provider operation re-confirms history before any paid dispatch',async()=>{
 const f=fixture();let confirms=0,dispatches=0,queries=0;
 f.ctx.persistProviderHistory=async()=>{confirms++;if(confirms<=2)throw Error('synthetic storage busy');};
 const original=f.ctx.StarProviderOperations.requestPrepared;
 f.ctx.StarProviderOperations.requestPrepared=async(...args)=>{dispatches++;assert.equal(confirms,3);assert.equal(args[0].id,operation);return original(...args);};
 f.ctx.StarProviderOperations.query=async candidate=>{queries++;assert.equal(candidate,operation);return {operationState:'ABSENT'};};
 await f.submit();assert.equal(dispatches,0);assert.equal(f.chat.messages.length,2);assert.equal(f.chat.messages[1].incomplete,true);
 await f.events.get('starhub:provider-retry')({detail:{operationId:operation}});
 assert.equal(queries,1);assert.equal(dispatches,1);assert.equal(confirms,4);assert.equal(f.chat.messages.length,2);
});
test('an original SENT or UNKNOWN provider operation is never dispatched by the retry event',async()=>{
 for(const state of ['SENT','UNKNOWN']){
  const f=fixture();let dispatches=0;f.ctx.persistProviderHistory=async()=>{throw Error('synthetic storage busy');};
  f.ctx.StarProviderOperations.requestPrepared=async()=>{dispatches++;throw Error('Must not dispatch');};
  f.ctx.StarProviderOperations.query=async()=>({operationState:state});await f.submit();
  await f.events.get('starhub:provider-retry')({detail:{operationId:operation}});assert.equal(dispatches,0);assert.equal(f.chat.messages.length,2);
 }
});

test('single database success does not invent a degraded warning and preserves provider warnings',async()=>{
 for(const warning of ['', 'Provider maintenance notice']){
  const f=fixture();f.ctx.StarProviderOperations.requestPrepared=async()=>({response:new Response(JSON.stringify({operationId:operation,operationState:'COMPLETED',durability:'single',text:'synthetic answer',warning}),{headers:{'Content-Type':'application/json'}})});
  await f.submit();assert.equal(f.chat.messages[1].warning,warning);assert.equal(f.chat.messages[1].content,'synthetic answer');
 }
});

test('completed answer is durably saved before visual reveal finishes',async()=>{
 const f=fixture(),hold=deferred();let finalSave,ack=0;
 f.ctx.ReplyReveal=class{constructor(message){this.message=message;message.revealing=true}append(text){this.message.content+=text}showAll(){}async finish(){await hold.promise;this.message.revealing=false}};
 f.ctx.persistProviderHistory=async()=>{if(f.chat.messages[1]?.content)finalSave={content:f.chat.messages[1].content,incomplete:f.chat.messages[1].incomplete,revealing:f.chat.messages[1].revealing}};
 f.ctx.StarProviderOperations.acknowledge=()=>{ack++;return true};const pending=f.submit();await settle(()=>ack===1);
 assert.deepEqual(finalSave,{content:'synthetic answer',incomplete:false,revealing:true});hold.resolve();await pending;
});

test('explicit image request uses the selected OpenAI key and durable image operation',async()=>{
 const f=fixture();f.$('prompt').value='生成一张女孩图';f.$('provider').value='openai';f.$('model').value='chat-model';f.$('inputFile').files=[];f.ctx.providers=[{id:'openai',name:'OpenAI'}];f.ctx.readInputFiles=async()=>[];
 let payload;f.ctx.StarProviderOperations.prepare=async(_kind,body)=>{payload=body;return{id:operation};};
 await f.submit();assert.equal(payload.task,'image');assert.equal(payload.model,'image-model');assert.equal(payload.apiKey,'synthetic-placeholder');assert.equal(payload.stream,false);assert.equal(payload.webSearch,false);assert.deepEqual(Object.keys(payload.parameters),[]);assert.equal(f.chat.messages[1].model,'image-model');assert.equal(f.chat.messages[1].providerOperationId,operation);
});
test('image requests do not send another provider key or unsupported image attachments',async()=>{
 for(const scenario of ['provider','attachment']){const f=fixture();f.$('prompt').value='生成一张女孩图';let dispatched=false;f.ctx.StarProviderOperations.prepare=async()=>{dispatched=true;return{id:operation};};
  if(scenario==='attachment'){f.$('provider').value='openai';f.ctx.providers=[{id:'openai',name:'OpenAI'}];}
  await f.submit();assert.equal(dispatched,false);assert.equal(f.chat.messages.length,0);assert.ok(f.errors.length);
 }
});

function imageFollowupFixture(){
 const f=fixture();f.$('provider').value='openai';f.$('model').value='chat-model';f.$('inputFile').files=[];
 f.ctx.providers=[{id:'openai',name:'OpenAI'}];f.ctx.readInputFiles=async()=>[];
 const sourceOperation=id(90);f.chat.messages=[{role:'user',content:'画一个穿紫色裙子的女孩'},
  {role:'assistant',content:'Image generated.',providerOperationId:sourceOperation,mediaRefs:[reference(91)]}];
 let payload,dispatches=0;f.ctx.StarProviderOperations.prepare=async(_kind,body)=>{payload=body;return{id:operation};};
 const send=f.ctx.StarProviderOperations.requestPrepared;f.ctx.StarProviderOperations.requestPrepared=async(...args)=>{dispatches++;return send(...args);};
 return {...f,sourceOperation,payload:()=>payload,dispatches:()=>dispatches};
}

test('follow-up variation retains the preceding image description and creates exactly one new operation',async()=>{
 const f=imageFollowupFixture();f.$('prompt').value='再生成一张';await f.submit();
 const body=f.payload();assert.equal(body.task,'image');assert.equal(body.model,'image-model');assert.equal(body.imageEdit,undefined);
 assert.match(body.prompt,/画一个穿紫色裙子的女孩/);assert.match(body.prompt,/再生成一张/);assert.equal(f.requests.length,0);assert.equal(f.dispatches(),1);
 assert.equal(f.chat.messages.at(-2).content,'再生成一张');assert.equal(f.chat.messages.at(-1).providerOperationId,operation);
});

test('an image edit reads the source with its original operation and then dispatches one edit',async()=>{
 const f=imageFollowupFixture();f.$('prompt').value='把背景改成蓝色';let converted=0;
 f.ctx.StarImageEditInput={prepare:async(url,{blob,owns,active})=>{converted++;assert.ok(owns(url));assert.equal(active(),true);assert.equal(blob instanceof Blob,true);assert.equal(f.api.blobFor(url),blob);assert.equal(blob.size,3);return {dataUrl:'data:image/png;base64,AQID'};}};
 await f.submit();const body=f.payload();assert.equal(f.requests.length,1);assert.equal(f.requests[0].operationId,f.sourceOperation);assert.equal(f.requests[0].mediaId,id(91));
 assert.equal(f.requests[0].expectedEmail,owner);assert.equal(converted,1);assert.equal(body.imageEdit,true);assert.equal(body.file,'data:image/png;base64,AQID');assert.match(body.prompt,/把背景改成蓝色/);
 assert.equal(f.dispatches(),1);assert.ok(f.revoked.includes('blob:synthetic-1'));
});

test('an attached image wins over a previous generated image when editing',async()=>{
 const f=imageFollowupFixture();f.$('prompt').value='把背景改成蓝色';f.$('inputFile').files=[{name:'new.png'}];f.ctx.readInputFiles=async()=>['data:image/png;base64,BAUG'];
 f.ctx.StarImageEditInput={prepare:async()=>{throw Error('The previous image must not be read');}};
 await f.submit();assert.equal(f.requests.length,0);assert.equal(f.payload().file,'data:image/png;base64,BAUG');assert.equal(f.payload().imageEdit,true);
 assert.doesNotMatch(f.payload().prompt,/穿紫色裙子的女孩/);assert.equal(f.dispatches(),1);
});

test('missing or unreadable source image cannot create or dispatch a paid operation',async()=>{
 for(const mode of ['missing','unreadable']){
  const f=imageFollowupFixture();if(mode==='missing')f.chat.messages=[];else{f.chat.messages[1].providerOperationId='';f.ctx.readCloudFile=async()=>{throw Error('file unavailable');};}
  f.$('prompt').value=mode==='missing'?'再生成一张':'把背景改成蓝色';await f.submit();
  assert.equal(f.payload(),undefined);assert.equal(f.dispatches(),0);assert.equal(f.requests.length,0);assert.ok(f.errors.length);
 }
});

test('legacy account image is read under the original owner and its temporary URL is released',async()=>{
 const f=imageFollowupFixture(),created=[],released=[];f.chat.messages[1].providerOperationId='';f.$('prompt').value='把背景改成蓝色';
 f.ctx.readCloudFile=async fileId=>{assert.equal(fileId,id(91));assert.equal(f.ctx.accountEmail,owner);return {url:'data:image/png;base64,AQID'};};
 f.ctx.fetch=async()=>{throw Error('Data and blob URLs must never be fetched under CSP');};
 f.ctx.URL={createObjectURL(blob){assert.equal(blob.type,'image/png');created.push('blob:legacy');return 'blob:legacy';},revokeObjectURL:url=>released.push(url)};
 f.ctx.StarImageEditInput={fromDataUrl:url=>{assert.equal(url,'data:image/png;base64,AQID');return new Blob([new Uint8Array([1,2,3])],{type:'image/png'});},prepare:async(url,{blob,owns,active})=>{assert.equal(url,'blob:legacy');assert.equal(blob instanceof Blob,true);assert.equal(blob.size,3);assert.equal(owns(url),true);assert.equal(owns('blob:other'),false);assert.equal(active(),true);return {dataUrl:'data:image/png;base64,AQID'};}};
 await f.submit();assert.deepEqual(created,['blob:legacy']);assert.deepEqual(released,['blob:legacy']);assert.equal(f.requests.length,0);
 assert.equal(f.payload().imageEdit,true);assert.equal(f.payload().file,'data:image/png;base64,AQID');assert.equal(f.dispatches(),1);
});

test('legacy image editing is denied without its account or for ambiguous and invalid references',async()=>{
 for(const mode of ['guest','multi','unsupported']){
  const f=imageFollowupFixture();f.chat.messages[1].providerOperationId='';f.$('prompt').value='把背景改成蓝色';let reads=0;
  if(mode==='guest')f.ctx.accountEmail='';if(mode==='multi')f.chat.messages[1].mediaRefs=[reference(91),reference(92)];
  f.ctx.readCloudFile=async()=>{reads++;return {url:'data:text/plain;base64,AQID'};};
  f.ctx.fetch=async()=>{throw Error('Must not fetch invalid data');};f.ctx.StarImageEditInput={fromDataUrl:()=>{throw Error('invalid data URL');},prepare:async()=>{throw Error('Must not convert');}};
  await f.submit();assert.equal(reads,mode==='unsupported'?1:0,mode);assert.equal(f.payload(),undefined,mode);assert.equal(f.dispatches(),0,mode);assert.ok(f.errors.length,mode);
 }
});

test('account ABA during legacy file read cannot prepare a paid operation',async()=>{
 const entered=deferred(),release=deferred(),f=imageFollowupFixture();f.chat.messages[1].providerOperationId='';f.$('prompt').value='把背景改成蓝色';let fetches=0;
 f.ctx.readCloudFile=async()=>{entered.resolve();await release.promise;return {url:'data:image/png;base64,AQID'};};f.ctx.fetch=async()=>{fetches++;throw Error('Must not fetch stale data');};
 const pending=f.submit();await entered.promise;f.change('ABA');release.resolve();await pending;
 assert.equal(fetches,0);assert.equal(f.payload(),undefined);assert.equal(f.dispatches(),0);assert.equal(f.chat.messages.length,2);assert.ok(f.errors.length);
});

test('account ABA during legacy conversion releases the temporary URL and stops dispatch',async()=>{
 const entered=deferred(),release=deferred(),f=imageFollowupFixture(),released=[];f.chat.messages[1].providerOperationId='';f.$('prompt').value='把背景改成蓝色';
 f.ctx.readCloudFile=async()=>({url:'data:image/png;base64,AQID'});f.ctx.fetch=async()=>{throw Error('Data and blob URLs must never be fetched under CSP');};
 f.ctx.URL={createObjectURL:()=> 'blob:legacy',revokeObjectURL:url=>released.push(url)};
 f.ctx.StarImageEditInput={fromDataUrl:()=>new Blob([new Uint8Array([1,2,3])],{type:'image/png'}),prepare:async(_url,{blob,active})=>{assert.equal(blob instanceof Blob,true);entered.resolve();await release.promise;assert.equal(active(),false);return {dataUrl:'data:image/png;base64,AQID'};}};
 const pending=f.submit();await entered.promise;f.change('ABA');release.resolve();await pending;
 assert.deepEqual(released,['blob:legacy']);assert.equal(f.payload(),undefined);assert.equal(f.dispatches(),0);assert.ok(f.errors.length);
});

test('authenticated image reads expose Blob bytes only for explicit editing',async()=>{
 const f=imageFollowupFixture(),referenceList=[reference(91)];
 const preview=await f.api.hydrate(referenceList,{operationId:f.sourceOperation,expectedEmail:owner});
 assert.equal('blob' in preview[0],false);assert.equal(f.api.owns(preview[0].url),true);const previewUrl=preview[0].url;assert.equal(f.api.blobFor(previewUrl) instanceof Blob,true);f.api.release(preview);assert.equal(f.api.blobFor(previewUrl),undefined);
 const edit=await f.api.hydrate(referenceList,{operationId:f.sourceOperation,expectedEmail:owner,includeBlob:true});
 assert.equal(edit[0].blob instanceof Blob,true);assert.equal(f.api.blobFor(edit[0].url),edit[0].blob);assert.deepEqual([...new Uint8Array(await edit[0].blob.arrayBuffer())],[1,2,3]);
 const url=edit[0].url;f.api.release(edit);assert.equal(f.api.owns(url),false);assert.equal(f.api.blobFor(url),undefined);assert.ok(f.revoked.includes(url));
});

test('an account ABA while reading the original image cannot create or dispatch a paid operation',async()=>{
 const entered=deferred(),release=deferred(),f=imageFollowupFixture();f.$('prompt').value='把背景改成蓝色';
 f.ctx.StarImageEditInput={prepare:async()=>{throw Error('The stale image must not be converted');}};
 const hydrate=f.ctx.StarProviderMedia.hydrate;f.ctx.StarProviderMedia.hydrate=async(...args)=>{entered.resolve();await release.promise;return hydrate(...args);};
 const pending=f.submit();await entered.promise;f.change('ABA');release.resolve();await pending;
 assert.equal(f.payload(),undefined);assert.equal(f.dispatches(),0);assert.equal(f.chat.messages.length,2);assert.ok(f.errors.length);
});

test('image follow-ups retain the chosen image model and use provider defaults',async()=>{
 for(const mode of ['variation','edit']){
  const f=imageFollowupFixture();f.$('task').value='image';f.$('model').value='chosen-image';f.$('effort').value='high';
  f.$('prompt').value=mode==='variation'?'再生成一张':'把背景改成蓝色';
  f.ctx.selectedModel=()=>({id:'chosen-image',provider:'openai',inputs:['text','image'],referenceLimits:{image:1}});
  f.ctx.StarImageEditInput={prepare:async()=>({dataUrl:'data:image/png;base64,AQID'})};await f.submit();
  assert.equal(f.payload().model,'chosen-image');assert.deepEqual(Object.keys(f.payload().parameters),[]);
  assert.equal(f.payload().effort,'default');assert.equal(f.payload().imageEdit,mode==='edit'?true:undefined);assert.equal(f.dispatches(),1);
 }
});

test('a text request forwards the selected effort without token or output format overrides',async()=>{
 const f=fixture();f.$('effort').value='high';
 let payload;
 f.ctx.StarProviderOperations.prepare=async(_kind,body)=>{payload=body;return {id:operation,fresh:true};};
 await f.submit();
 assert.equal(payload.effort,'high');assert.deepEqual(Object.keys(payload.parameters),[]);
 assert.ok(payload.system.includes(f.ctx.StarVisuals.conversationInstruction));
});

test('an ambiguous multi-image source is never silently chosen for an edit',async()=>{
 const f=imageFollowupFixture();f.chat.messages[1].mediaRefs=[reference(91),reference(92)];f.$('prompt').value='把背景改成蓝色';
 f.ctx.StarImageEditInput={prepare:async()=>({dataUrl:'data:image/png;base64,AQID'})};await f.submit();
 assert.equal(f.payload(),undefined);assert.equal(f.requests.length,0);assert.equal(f.dispatches(),0);assert.ok(f.errors.length);
});

test('explicit image-model mode rejects unsupported references for a new image',async()=>{
 const f=fixture();f.$('provider').value='openai';f.$('task').value='image';f.$('model').value='chosen-image';f.$('prompt').value='生成一张女孩图';
 f.ctx.providers=[{id:'openai',name:'OpenAI'}];f.ctx.selectedModel=()=>({id:'chosen-image',provider:'openai',inputs:['text','image'],referenceLimits:{image:1}});
 f.$('inputFile').files=[{name:'reference.png'}];f.ctx.readInputFiles=async()=>['data:image/png;base64,AQID'];
 let body;f.ctx.StarProviderOperations.prepare=async(_kind,payload)=>{body=payload;return {id:operation};};await f.submit();
 assert.equal(body,undefined);assert.ok(f.errors.length);
});
