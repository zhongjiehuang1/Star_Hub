const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.join(__dirname,'../../main/webapp');
const visuals=require(path.join(root,'visuals.js'));
const scene={version:1,title:'Wave',description:'An oscillating field',duration:6,objects:[{type:'wave',y:120,amplitude:40,cycles:2,speed:1,color:'#7258c7'},{type:'arrow',x:100,y:90,x2:130,y2:90,motion:{dx:10,dy:0,phase:0}}]};
test('visual data is bounded and rejects markup, executable code and invalid geometry',()=>{
 assert.deepEqual(visuals.validate(JSON.stringify(scene)),scene);
 assert.equal(visuals.validate('{"version":1,"objects":[]'),null);
 assert.equal(visuals.validate(JSON.stringify({...scene,objects:[{type:'script',code:'alert(1)'}]})),null);
 assert.equal(visuals.validate(JSON.stringify({...scene,objects:[{...scene.objects[0],color:'url(https://example.test)'}]})),null);
 assert.equal(visuals.validate(JSON.stringify({...scene,objects:Array(25).fill(scene.objects[0])})),null);
 assert.equal(visuals.validate(JSON.stringify({...scene,objects:[{...scene.objects[0],amplitude:1e30}]})),null);
});
function section(source,start,end){const a=source.indexOf(start),b=source.indexOf(end,a);assert(a>=0&&b>a);return source.slice(a,b);}
test('realtime events display user transcription and stream assistant text without manual sending',()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8');const messages=[],statuses=[];let renders=0,stops=0;
 const ctx=vm.createContext({rt:{transcription:false,ignoredResponses:new Set(),transcripts:new Map(),liveTurns:[],responding:true,currentResponse:'old',playing:new Set(),audio:{currentTime:0}},t:x=>x,
  realtimeState:x=>statuses.push(x),realtimeNotice(){},realtimeUi(){},endRealtime(){},stopPlayback(){stops++},queueRender(){renders++},playAudio(){},
  addRtMessage:(role,content)=>{const m={role,content};messages.push(m);return m;}});
 vm.runInContext(section(source,'function finishRealtimeMapReply(state){','function interruptRealtime(){'),ctx);
 vm.runInContext(section(source,'function rtEvent(event){',"$('connectRealtime').onclick"),ctx);
 vm.runInContext("rtEvent({type:'input_audio_buffer.speech_started',item_id:'u1'})",ctx);assert.equal(stops,1);
 vm.runInContext("rtEvent({type:'conversation.item.input_audio_transcription.delta',item_id:'u1',delta:'hello '});rtEvent({type:'conversation.item.input_audio_transcription.delta',item_id:'u1',delta:'world'});rtEvent({type:'conversation.item.input_audio_transcription.completed',item_id:'u1',transcript:'hello world'});",ctx);
 vm.runInContext("rtEvent({type:'response.created',response:{id:'new'}});rtEvent({type:'response.output_audio_transcript.delta',response_id:'new',delta:'Hi there'});",ctx);
 assert.deepEqual(messages.map(m=>[m.role,m.content]),[['user','hello world'],['assistant','Hi there']]);assert.ok(renders>=3);assert.ok(statuses.length);
});
test('normal realtime close retires only an observed terminal receipt',async()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),calls=[];let count=0;
 const ctx=vm.createContext({StarProviderOperations:{query:async()=>{calls.push('query');return {operationState:++count===1?'SENT':'COMPLETED'}},acknowledge:()=>calls.push('ack'),markUnknown:()=>calls.push('unknown')},setTimeout:fn=>{fn();return 1;}});
 vm.runInContext(section(source,'async function settleRealtimeReceipt(', 'function endRealtime('),ctx);await vm.runInContext("settleRealtimeReceipt('id',()=>true)",ctx);assert.deepEqual(calls,['query','query','ack']);
 calls.length=0;ctx.StarProviderOperations.query=async()=>{calls.push('query');throw Error('network')};await vm.runInContext("settleRealtimeReceipt('id',()=>true)",ctx);assert.equal(calls.filter(x=>x==='query').length,6);assert.equal(calls.at(-1),'unknown');
});

 test('streaming transcript may arrive before speech detection without duplicating the user turn',()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),messages=[];
 const ctx=vm.createContext({rt:{transcription:false,ignoredResponses:new Set(),transcripts:new Map(),liveTurns:[],responding:false,playing:new Set(),audio:{currentTime:0}},t:x=>x,
  realtimeState(){},realtimeNotice(){},realtimeUi(){},endRealtime(){},stopPlayback(){},queueRender(){},playAudio(){},addRtMessage:(role,content)=>{const m={role,content};messages.push(m);return m;}});
 vm.runInContext(section(source,'function finishRealtimeMapReply(state){','function interruptRealtime(){'),ctx);
 vm.runInContext(section(source,'function rtEvent(event){',"$('connectRealtime').onclick"),ctx);
 vm.runInContext("rtEvent({type:'conversation.item.input_audio_transcription.delta',item_id:'s1',delta:'说话'});rtEvent({type:'input_audio_buffer.speech_started',item_id:'s1'});rtEvent({type:'conversation.item.input_audio_transcription.completed',item_id:'s1',transcript:'说话内容'});",ctx);
 assert.deepEqual(messages.map(m=>[m.role,m.content]),[['user','说话内容']]);
 });
