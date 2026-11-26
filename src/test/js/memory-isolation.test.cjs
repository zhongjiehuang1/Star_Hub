'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');const path=require('node:path');
function setup(window={}){
 const handlers={},pending=[];
 class Node{constructor(tag){this.tag=tag;this.children=[];this.listeners={};this._text='';this.id='';this.open=false;}set textContent(value){this._text=String(value);this.children=[];}get textContent(){return this._text+this.children.map(c=>typeof c==='string'?c:c.textContent).join('');}append(...items){this.children.push(...items);}replaceChildren(...items){this._text='';this.children=[...items];}insertBefore(node){this.children.push(node);}before(node){root.children.push(node);}setAttribute(){}addEventListener(name,fn){(this.listeners[name]??=[]).push(fn);}fire(name){for(const fn of this.listeners[name]||[])fn();}}
 const root=new Node('root'),settings=new Node('dialog'),close=new Node('button'),form=new Node('form');settings.id='accountSettings';close.id='closeAccountSettings';form.id='chatForm';root.append(settings,close,form);
 function find(node,id){if(node.id===id)return node;for(const child of node.children||[]){if(typeof child==='string')continue;const value=find(child,id);if(value)return value;}return null;}
 const document={createElement:tag=>new Node(tag),createTextNode:text=>text,getElementById:id=>find(root,id),addEventListener(name,fn){(handlers[name]??=[]).push(fn);},fire(name){for(const fn of handlers[name]||[])fn();}};
 const ctx={document,accountEmail:'a@example.test',accountIdentityRevision:1,language:'en',window,errorText:x=>x,workspaceRequest:()=>new Promise((resolve,reject)=>pending.push({resolve,reject}))};vm.createContext(ctx);vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/webapp/memory.js'),'utf8'),ctx);return {ctx,document,settings,root,pending};
}
const tick=()=>new Promise(resolve=>setImmediate(resolve));
test('memory summary uses saved entries and discards a result when the account changes',async()=>{
 const x=setup();let resolveSummary,request;
 const pending=new Promise(resolve=>{resolveSummary=resolve;});
 Object.assign(x.ctx,{
  AbortSignal,keys:new Map([['openai','synthetic-key']]),
  directionModels:()=>[{provider:'openai',id:'summary-model',categories:['chat']}],
  preferredModelId:(_provider,_direction,models)=>models[0].id,
  selectedModel:()=>({provider:'openai',id:'image-model'}),
  StarProviderOperations:{request(kind,payload,options){request={kind,payload,options};return pending;},observe(){},acknowledge(){}}
 });
 x.document.fire('starhub:account-settings-opening');x.settings.open=true;
 x.pending.shift().resolve({enabled:false,revision:4,memories:[{id:'1',text:'prefers short explanations'}]});await tick();
 const summaryButton=x.document.getElementById('memoryPanel').children.find(node=>node.textContent==='Generate memory summary');
 const generating=summaryButton.onclick();
 assert.equal(request.kind,'memory-summary');assert.equal(request.options.endpoint,'run');
 assert.equal(request.payload.webSearch,false);assert.equal(request.payload.model,'summary-model');
 assert.equal(request.payload.messages[0].content,'["prefers short explanations"]');
 x.ctx.accountEmail='b@example.test';x.ctx.accountIdentityRevision=2;x.document.fire('starhub:account-changed');
 resolveSummary({operationId:'id',response:{ok:true,async json(){return {operationState:'COMPLETED',text:'private summary'};}}});await generating;
 assert.doesNotMatch(x.root.textContent,/private summary|short explanations/);
});
test('a generated summary is shown and invalidated by a new memory revision',async()=>{
 const x=setup();
 Object.assign(x.ctx,{
  AbortSignal,keys:new Map([['openai','synthetic-key']]),
  directionModels:()=>[{provider:'openai',id:'summary-model',categories:['chat']}],
  preferredModelId:()=> 'summary-model',selectedModel:()=>null,
  StarProviderOperations:{async request(){return {operationId:'id',response:{ok:true,async json(){return {operationState:'COMPLETED',text:'prefers diagrams'};}}};},observe(){},acknowledge(){}}
 });
 x.document.fire('starhub:account-settings-opening');
 x.pending.shift().resolve({enabled:true,revision:3,memories:[{id:'1',text:'likes flowcharts'}]});await tick();
 await x.document.getElementById('memoryPanel').children.find(node=>node.textContent==='Generate memory summary').onclick();
 assert.match(x.root.textContent,/Refresh memory summary.*prefers diagrams/);
 const refreshing=vm.runInContext('StarMemory.refresh()',x.ctx);
 x.pending.shift().resolve({enabled:true,revision:4,memories:[{id:'1',text:'likes short notes'}]});await refreshing;
 assert.doesNotMatch(x.root.textContent,/prefers diagrams/);
});
test('account switch and failed reopening never leave the previous account memory visible',async()=>{
 const x=setup();x.document.fire('starhub:account-settings-opening');x.settings.open=true;x.pending.shift().resolve({enabled:true,revision:1,memories:[{id:'1',text:'private A'}]});await tick();assert.match(x.root.textContent,/private A/);
 x.ctx.accountEmail='b@example.test';x.ctx.accountIdentityRevision=2;x.document.fire('starhub:account-changed');assert.doesNotMatch(x.root.textContent,/private A/);
 x.document.fire('starhub:account-settings-opening');assert.doesNotMatch(x.root.textContent,/private A/);x.pending.shift().reject(Error('offline'));await tick();assert.doesNotMatch(x.root.textContent,/private A/);assert.match(x.root.textContent,/offline/);
});
test('close and account changes discard delayed results even after reopening',async()=>{
 const x=setup();x.document.fire('starhub:account-settings-opening');x.settings.open=true;const a=x.pending.shift();x.document.fire('starhub:account-settings-closing');x.settings.open=false;assert.doesNotMatch(x.root.textContent,/private A/);
 x.ctx.accountEmail='b@example.test';x.ctx.accountIdentityRevision=2;x.document.fire('starhub:account-changed');x.document.fire('starhub:account-settings-opening');x.settings.open=true;const b=x.pending.shift();b.resolve({enabled:true,revision:1,memories:[{id:'2',text:'private B'}]});await tick();a.resolve({enabled:true,revision:1,memories:[{id:'1',text:'private A'}]});await tick();assert.match(x.root.textContent,/private B/);assert.doesNotMatch(x.root.textContent,/private A/);
 x.settings.open=false;x.settings.fire('close');assert.doesNotMatch(x.root.textContent,/private B/);
});
test('background memory updates are visible and stale account results cannot change the status',async()=>{
 const x=setup();let resolvePaid;const paid=new Promise(resolve=>{resolvePaid=resolve;});x.ctx.StarProviderOperations={async prepare(kind,payload){return {id:'id',body:{...payload,operationId:'id'}};},async requestPrepared(){return paid;},observe(){},acknowledge(){},markUnknown(){}};
 x.ctx.workspaceRequest=async body=>body.action==='saveSemanticMemory'?{saved:1}:{};
 const api=vm.runInContext('StarMemory',x.ctx),token={account:'a@example.test',accountRevision:1,text:'我喜欢徒步',revision:1,provider:'openai',model:'x',apiKey:'synthetic'};
 const flight=api.rememberSemantic(token);await tick();assert.match(x.root.textContent,/Updating memory/);
 x.ctx.accountEmail='b@example.test';x.ctx.accountIdentityRevision=2;x.document.fire('starhub:account-changed');assert.doesNotMatch(x.root.textContent,/Updating memory/);
 resolvePaid({response:{ok:true,status:200,async json(){return {operationId:'id',operationState:'COMPLETED',durability:'single'};}}});await flight;assert.doesNotMatch(x.root.textContent,/Memory updated/);
});
test('failed background memory updates tell the user without retrying',async()=>{
 const x=setup();let sends=0;x.ctx.StarProviderOperations={async prepare(kind,payload){return {id:'id',body:payload};},async requestPrepared(){sends++;throw Error('network unknown');}};
 const api=vm.runInContext('StarMemory',x.ctx),token={account:'a@example.test',accountRevision:1,text:'我喜欢徒步',revision:1};await assert.rejects(()=>api.rememberSemantic(token));assert.equal(sends,1);assert.match(x.root.textContent,/Memory update is unconfirmed/);
});
test('a second memory update blocked by an earlier pending operation says this turn was not saved',async()=>{
 const x=setup();let sends=0;x.ctx.StarProviderOperations={async prepare(){const error=Error('previous request pending');error.code='PROVIDER_OPERATION_UNKNOWN';throw error;},async requestPrepared(){sends++;}};
 const api=vm.runInContext('StarMemory',x.ctx),token={account:'a@example.test',accountRevision:1,text:'我喜欢徒步',revision:1};await api.rememberSemantic(token);assert.equal(sends,0);assert.match(x.root.textContent,/this turn was not saved/);assert.doesNotMatch(x.root.textContent,/Memory update is unconfirmed/);
});
test('a saved web edit survives disconnect and reconciles on the online event',async()=>{
 const events={},storage=new Map();
 const win={addEventListener(name,fn){events[name]=fn;},prompt(){return 'practice with tables';}};
 const x=setup(win);let online=true,website='practice with text',revision=5;
 x.ctx.crypto=require('node:crypto').webcrypto;
 x.ctx.localStorage={get length(){return storage.size;},key:i=>[...storage.keys()][i],getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)};
 x.ctx.workspaceRequest=async body=>{
  if(!online)throw Error('offline');
  if(body.action==='listMemories')return {owner:'owner-A',enabled:true,revision,memories:[{id:'note-A',text:website}]};
  assert.equal(body.action,'editMemory');assert.equal(body.memoryRevision,revision);
  website=body.text;revision++;return {revision};
 };
 x.document.fire('starhub:account-settings-opening');x.settings.open=true;await tick();
 const list=x.document.getElementById('memoryPanel').children.find(n=>n.tag==='ul');
 online=false;list.children[0].children.find(n=>n.textContent==='Edit').onclick();await tick();
 assert.equal(website,'practice with text');assert.match(x.root.textContent,/practice with tables/);
 assert.equal(storage.size,1);
 const restartedEvents={};
 const restored=setup({addEventListener(name,fn){restartedEvents[name]=fn;}});
 restored.ctx.localStorage=x.ctx.localStorage;
 restored.ctx.workspaceRequest=x.ctx.workspaceRequest;
 online=true;restartedEvents.online();await tick();await tick();
 assert.equal(website,'practice with tables');assert.equal(storage.size,0);
});
test('reconnecting another account leaves the first account’s durable draft untouched',async()=>{
 const events={},storage=new Map([['starhub.memory.draft.owner-A.k',JSON.stringify({
  key:'k',id:'a',before:'old note',text:'private A draft',time:1
 })]]);
 const x=setup({addEventListener(name,fn){events[name]=fn;}});
 x.ctx.localStorage={get length(){return storage.size;},key:i=>[...storage.keys()][i],getItem:k=>storage.get(k)};
 x.ctx.accountEmail='b@example.test';x.ctx.accountIdentityRevision=2;
 const calls=[];
 x.ctx.workspaceRequest=async body=>{calls.push(body.action);return {owner:'owner-B',enabled:true,revision:1,memories:[]};};
 events.online();await tick();
 assert.deepEqual(calls,['listMemories']);assert.equal(storage.size,1);
 assert.doesNotMatch(x.root.textContent,/private A draft/);
});
