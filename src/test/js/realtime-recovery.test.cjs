'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../../main/webapp/advanced.js'),'utf8');
function harness(){
 let owner=true,model={id:'gpt-realtime-2.1',provider:'openai'},rows=[{id:'old',kind:'realtime',archived:false}],queryResult={operationState:'UNKNOWN'},pendingVisible;
 const calls=[],controls={realtimeRecovery:{hidden:true},realtimeCheckPrevious:{},realtimeStartSeparate:{},realtimeRecoveryStatus:{textContent:''},connectRealtime:{onclick:()=>calls.push('connect')}};
 const api={ownerGuard:()=>()=>owner,visible:()=>pendingVisible||Promise.resolve(rows),query:async id=>{calls.push('query:'+id);return queryResult},separate:id=>{calls.push('archive:'+id);rows.find(x=>x.id===id).archived=true}};
 const ctx=vm.createContext({$:id=>controls[id],StarProviderOperations:api,selectedModel:()=>model,t:(s,...args)=>s.replace(/\{(\d+)\}/g,(_,i)=>args[i]),errorText:x=>x,realtimeNotice:message=>calls.push('notice:'+message)});
 vm.runInContext(source.slice(source.indexOf('let realtimeRecoveryToken=0;'),source.indexOf("document.addEventListener('starhub:account-changed',")),ctx);
 return {ctx,controls,calls,setOwner:value=>owner=value,setModel:value=>model=value,setRows:value=>rows=value,setPendingVisible:value=>pendingVisible=value,setQuery:value=>queryResult=value};
}
test('unknown live requests are retained until the user explicitly starts a separately billed session',async()=>{
 const h=harness();vm.runInContext("showRealtimeRecovery({code:'PROVIDER_OPERATION_UNKNOWN',operationId:'old'},{id:'gpt-realtime-2.1',provider:'openai'})",h.ctx);
 assert.equal(h.controls.realtimeRecovery.hidden,false);assert.deepEqual(h.calls,[]);
 await h.controls.realtimeCheckPrevious.onclick();assert.deepEqual(h.calls,['query:old']);assert.match(h.controls.realtimeRecoveryStatus.textContent,/UNKNOWN/);
 await h.controls.realtimeStartSeparate.onclick();assert.deepEqual(h.calls,['query:old','archive:old','connect']);assert.equal(h.controls.realtimeRecovery.hidden,true);
});
test('changing identity while the request list loads cannot archive or create a new request',async()=>{
 const h=harness();vm.runInContext("showRealtimeRecovery({code:'PROVIDER_OPERATION_UNKNOWN',operationId:'old'},{id:'gpt-realtime-2.1',provider:'openai'})",h.ctx);
 let resolve;h.setPendingVisible(new Promise(r=>{resolve=r}));const pending=h.controls.realtimeStartSeparate.onclick();h.setOwner(false);resolve([{id:'old',kind:'realtime',archived:false}]);await pending;
 assert.deepEqual(h.calls,[]);
});
test('changed model, archived records and other request kinds cannot authorize a new live connection',async()=>{
 for(const scenario of ['model','archived','other']){
  const h=harness();vm.runInContext("showRealtimeRecovery({code:'PROVIDER_OPERATION_UNKNOWN',operationId:'old'},{id:'gpt-realtime-2.1',provider:'openai'})",h.ctx);
  if(scenario==='model')h.setModel({id:'other',provider:'openai'});if(scenario==='archived')h.setRows([{id:'old',kind:'realtime',archived:true}]);if(scenario==='other')h.setRows([{id:'old',kind:'run',archived:false}]);
  await h.controls.realtimeStartSeparate.onclick();assert.equal(h.calls.some(x=>x==='archive:old'||x==='connect'),false);
 }
});
test('the pending panel remains collapsed in the connection dialog',()=>{
 const html=fs.readFileSync(path.join(__dirname,'../../main/webapp/index.html'),'utf8');
 assert.match(html,/<details id="providerRequestHistory"><summary>Request history<\/summary><section id="providerPendingOperations"/);
 const setup=html.match(/<dialog id="setupDialog"[\s\S]*?<\/dialog>/)[0];
 assert(setup.includes('id="providerPendingOperations"'));assert.equal((html.match(/id="providerPendingOperations"/g)||[]).length,1);
});
test('same-fingerprint unknown live request opens recovery and never dispatches a second session',async()=>{
 const calls=[],controls={connectRealtime:{},apiKey:{value:'key'},voice:{value:'alloy'},accent:{value:'auto'},webSearch:{checked:false,disabled:false},recognitionLanguage:{value:'auto'}};
 class Pending extends Error{constructor(id,state,message){super(message);this.operationId=id;this.operationState=state;this.code='PROVIDER_OPERATION_UNKNOWN'}}
 const model={provider:'openai',id:'gpt-realtime-2.1',categories:['realtime'],outputs:['audio']};
 const ctx=vm.createContext({rt:null,rtGeneration:0,rtConnecting:false,realtimePreparation:Promise.resolve(),busy:false,publicInsecure:false,accountEmail:'',t:x=>x,$:id=>controls[id],selectedModel:()=>model,collectParameters:async()=>({}),realtimeUi(){},endRealtime:()=>calls.push('end'),showRealtimeRecovery:e=>calls.push('recovery:'+e.operationId),
  StarProviderOperations:{Pending,prepare:async()=>({id:'old',fresh:false}),query:async()=>({operationState:'UNKNOWN'}),discardUnsent:()=>false,markUnknown:id=>calls.push('unknown:'+id)},AudioContext:class{constructor(){calls.push('audio')}},WebSocket:class{constructor(){calls.push('socket')}}});
 vm.runInContext(source.slice(source.indexOf("$('connectRealtime').onclick=async()=>{"),source.indexOf('async function beginRecording(){')),ctx);await controls.connectRealtime.onclick();
 assert.deepEqual(calls,['end','recovery:old']);
});
