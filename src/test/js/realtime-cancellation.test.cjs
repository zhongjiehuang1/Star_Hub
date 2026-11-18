'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../main/webapp/advanced.js'),'utf8');
const section=(start,end)=>source.slice(source.indexOf(start),source.indexOf(end,source.indexOf(start)));
function deferred(){let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};}
async function until(predicate){for(let i=0;i<50;i++){if(predicate())return;await Promise.resolve();}assert.fail('expected asynchronous stage was not reached');}
function harness({parameterGate,prepareGate,dispatchGate,resumeGate}={}){
 const calls=[],sockets=[],operations=[];
 const controls={connectRealtime:{},apiKey:{value:'test-key'},voice:{value:''},accent:{value:'auto'},recognitionLanguage:{value:'auto'},webSearch:{checked:false}};
 const model={provider:'openai',id:'test-model',categories:['realtime'],outputs:['audio']};
 class Socket{constructor(){this.readyState=1;this.bufferedAmount=0;sockets.push(this)}send(value){calls.push(['send',JSON.parse(value).operationId]);}close(){calls.push(['close'])}}Socket.OPEN=1;
 class Audio{constructor(){this.currentTime=0}async resume(){if(resumeGate)await resumeGate.promise}close(){return Promise.resolve()}}
 const api={
  async prepare(kind,body){calls.push(['prepare']);if(prepareGate)await prepareGate.promise;const operation={id:'operation-'+(operations.length+1),fresh:true,body:{...body,operationId:'operation-'+(operations.length+1)}};operations.push(operation);return operation;},
  async beforeDispatch(){calls.push(['beforeDispatch']);if(dispatchGate)await dispatchGate.promise},
  discardUnsent(operation){calls.push(['discard',operation.id,operation.fresh]);return operation.fresh},
  markUnknown(id){calls.push(['unknown',id])},ownerGuard(){return ()=>true},
 };
 const ctx=vm.createContext({rt:null,rtGeneration:0,rtConnecting:false,realtimePreparation:Promise.resolve(),busy:false,publicInsecure:false,accountEmail:'',t:x=>x,$:id=>controls[id],selectedModel:()=>model,
  collectParameters:async()=>{calls.push(['parameters']);if(parameterGate)await parameterGate.promise;return {}},realtimeUi(){},realtimeNotice(){},realtimeState(){},realtimeMicStatus(){},scheduleHistorySave(){},queueRender(){},stopPlayback(){},
  location:{href:'https://example.test/',protocol:'https:'},URL,WebSocket:Socket,AudioContext:Audio,setTimeout:()=>1,clearTimeout(){},StarProviderOperations:api});
 vm.runInContext(section('function rtSend(event){','function stopPlayback('),ctx);
 vm.runInContext(section('function finishRealtimeMapReply(state){','function interruptRealtime(){'),ctx);
 vm.runInContext(section("$('connectRealtime').onclick=async()=>{",'async function beginRecording(){'),ctx);
 vm.runInContext(section('function endRealtime(',"$('endRealtime').onclick"),ctx);
 return {ctx,calls,sockets,operations,connect:()=>controls.connectRealtime.onclick(),cancel:()=>ctx.endRealtime('cancel',true)};
}
test('cancelling while parameters are pending never prepares a paid operation',async()=>{
 const gate=deferred(),h=harness({parameterGate:gate});const pending=h.connect();await until(()=>h.calls.some(c=>c[0]==='parameters'));h.cancel();gate.resolve();await pending;
 assert.equal(h.sockets.length,0);assert.equal(h.calls.some(c=>c[0]==='prepare'),false);assert.equal(h.ctx.rt,null);
});
test('rapid reconnect waits for cancelled preparation to discard its unsent receipt',async()=>{
 const gate=deferred(),h=harness({prepareGate:gate});const first=h.connect();await until(()=>h.calls.some(c=>c[0]==='prepare'));h.cancel();const second=h.connect();
 assert.equal(h.calls.filter(c=>c[0]==='prepare').length,1);gate.resolve();await Promise.all([first,second]);
 assert.deepEqual(h.calls.filter(c=>['prepare','discard','unknown'].includes(c[0])),[['prepare'],['discard','operation-1',true],['prepare']]);
 assert.equal(h.sockets.length,1);assert.equal(h.ctx.rt.operationId,'operation-2');assert.equal(h.ctx.rtConnecting,false);
});
test('cancel before dispatch discards a new identity and delayed onopen cannot send',async()=>{
 const gate=deferred(),h=harness({dispatchGate:gate});await h.connect();const opening=h.sockets[0].onopen();await until(()=>h.calls.some(c=>c[0]==='beforeDispatch'));h.cancel();gate.resolve();await opening;
 assert.deepEqual(h.calls.filter(c=>['discard','send','unknown'].includes(c[0])),[['discard','operation-1',true]]);assert.equal(h.ctx.rt,null);
});
test('cancel during audio resume cleans up without constructing a socket',async()=>{
 const gate=deferred(),h=harness({resumeGate:gate});const pending=h.connect();await until(()=>Boolean(h.ctx.rt));h.cancel();gate.resolve();await pending;
 assert.equal(h.sockets.length,0);assert.deepEqual(h.calls.filter(c=>['discard','unknown'].includes(c[0])),[['discard','operation-1',true]]);
});
test('once the connect event is dispatched cancellation keeps its identity unknown',async()=>{
 const h=harness();await h.connect();await h.sockets[0].onopen();h.cancel();
 assert.deepEqual(h.calls.filter(c=>['discard','send','unknown'].includes(c[0])),[['send','operation-1'],['unknown','operation-1']]);
});
test('ending a session releases the reply and preserves unfinished transcript as ineligible for maps',async()=>{
 const h=harness();await h.connect();const old=h.ctx.rt;old.assistant={realtimeReplyPending:true,content:'partial'};const user={content:'滁州地图',mapTranscriptPending:true};old.transcripts.set('turn',user);
 h.cancel();assert.equal(old.assistant.realtimeReplyPending,undefined);assert.equal(user.mapTranscriptPending,undefined);assert.equal(user.mapTranscriptFailed,true);assert.equal(user.content,'滁州地图');
});
