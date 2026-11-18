const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.join(__dirname,'../../main/webapp');
function section(source,start,end){const a=source.indexOf(start),b=source.indexOf(end,a);assert(a>=0&&b>a);return source.slice(a,b);}
function realtimeHarness(){
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),messages=[],sent=[],stopped=[];
 const state={transcription:false,ready:true,recording:true,ignoredResponses:new Set(),transcripts:new Map(),liveTurns:[],responding:true,currentResponse:'old',playing:new Set([{}]),audio:{currentTime:3},audioItem:'a1',audioStart:1,audioDuration:5,contentIndex:0};
 const ctx=vm.createContext({rt:state,t:x=>x,rtSend:event=>sent.push(event),stopPlayback:()=>{stopped.push(true);state.playing.clear();},realtimeState(){},realtimeNotice(){},realtimeUi(){},endRealtime(){},queueRender(){},playAudio(){},clearTimeout(){},StarProviderOperations:{},addRtMessage:(role,content,responseId)=>{const m={role,content,responseId};messages.push(m);return m;}});
 vm.runInContext(section(source,'function truncateRtAudio(){','function addRtMessage('),ctx);
 vm.runInContext(section(source,'function rtEvent(event){',"$('connectRealtime').onclick"),ctx);
 return {ctx,state,messages,sent,stopped};
}
function playbackHarness(){
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),notices=[],sent=[],nodes=[];let ended=0;
 const audio={currentTime:0,destination:{},createBuffer:(channels,frames,rate)=>({duration:frames/rate,getChannelData:()=>new Float32Array(frames)}),createBufferSource:()=>{const node={connect(){},start(at){this.at=at;},stop(){this.stopped=true;}};nodes.push(node);return node;}};
 const state={ready:true,transcription:false,responding:true,currentResponse:'r1',ignoredResponses:new Set(),playing:new Set(),nextPlay:0,audio,acceptAudio:true};
 const ctx=vm.createContext({rt:state,atob:x=>x,Uint8Array,DataView,t:x=>x,realtimeNotice:value=>notices.push(value),realtimeState(){},rtSend:event=>sent.push(event),endRealtime:()=>ended++});
 vm.runInContext(section(source,'function stopPlayback(', 'function addRtMessage('),ctx);
 vm.runInContext(section(source,'function rtEvent(event){',"$('connectRealtime').onclick"),ctx);
 return {ctx,state,nodes,notices,sent,ended:()=>ended};
}
test('long generated audio plays without disconnecting and remains interruptible on overflow',()=>{
 const {ctx,state,nodes,notices,ended}=playbackHarness(),second='\0'.repeat(48000),event={response_id:'r1',item_id:'audio1',content_index:0};
 for(let i=0;i<120;i++)ctx.playAudio(second,event);
 assert.equal(nodes.length,120);assert.equal(state.nextPlay,120);assert.equal(ended(),0);assert.equal(notices.length,0);
 ctx.playAudio(second,event);ctx.playAudio(second,event);
 assert.equal(nodes.length,120);assert.equal(ended(),0);assert.equal(notices.length,1);assert.match(notices[0],/完整内容/);
 state.audio.currentTime=10;ctx.interruptRealtime();assert.equal(state.playing.size,0);assert(nodes.every(node=>node.stopped));
 ctx.rtEvent({type:'response.created',response:{id:'r2'}});ctx.playAudio(second,{response_id:'r2',item_id:'audio2'});
 assert.equal(nodes.length,121);assert.equal(state.playing.size,1);assert.equal(ended(),0);
});
test('small audio fragments support a normal 60 second reply and remain node-bounded',()=>{
 const {ctx,state,nodes,notices,ended}=playbackHarness(),chunk='\0'.repeat(480),event={response_id:'r1',item_id:'audio1'};
 for(let i=0;i<6000;i++)ctx.playAudio(chunk,event);
 assert.equal(nodes.length,6000);assert.equal(notices.length,0);assert.equal(ended(),0);
 for(let i=6000;i<8200;i++)ctx.playAudio(chunk,event);
 assert.equal(nodes.length,8192);assert.equal(state.playing.size,8192);assert.equal(notices.length,1);assert.equal(ended(),0);
});
test('long audio notice has an English translation',()=>{
 const ctx=vm.createContext({});vm.runInContext(fs.readFileSync(path.join(root,'i18n-data.js'),'utf8'),ctx);
 assert.match(vm.runInContext('UI_EN["语音过长，后续语音已省略；完整内容可查看文字。"]',ctx),/full text/i);
});
test('speaking over a response stops playback, truncates unheard audio and keeps listening',()=>{
 const {ctx,state,messages,sent,stopped}=realtimeHarness();
 vm.runInContext("rtEvent({type:'input_audio_buffer.speech_started',item_id:'user1'});rtEvent({type:'response.output_audio_transcript.delta',response_id:'old',delta:'unheard'});",ctx);
 assert.equal(stopped.length,1);assert.deepEqual(JSON.parse(JSON.stringify(sent)),[{type:'conversation.item.truncate',item_id:'a1',content_index:0,audio_end_ms:2000}]);assert.equal(state.recording,true);assert.equal(state.acceptAudio,false);assert.equal(messages.length,1);
 vm.runInContext("rtEvent({type:'response.created',response:{id:'new'}});rtEvent({type:'response.output_audio_transcript.delta',response_id:'new',delta:'new answer'});",ctx);
 assert.equal(state.acceptAudio,true);assert.equal(messages[1].content,'new answer');
});
test('same-session transcripts stay with their own turns even when completions arrive out of order',()=>{
 const {ctx,state,messages,sent}=realtimeHarness();state.playing.clear();state.responding=false;
 vm.runInContext("rtEvent({type:'input_audio_buffer.speech_started',item_id:'u1'});rtEvent({type:'input_audio_buffer.speech_stopped',item_id:'u1'});rtEvent({type:'input_audio_buffer.speech_started',item_id:'u2'});rtEvent({type:'conversation.item.input_audio_transcription.completed',item_id:'u2',transcript:'第二句'});rtEvent({type:'conversation.item.input_audio_transcription.completed',item_id:'u1',transcript:'第一句'});",ctx);
 assert.deepEqual(messages.map(m=>m.content),['第一句','第二句']);assert.equal(sent.length,0);
});
test('map rendering waits until voice transcription and assistant reply are complete',()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),current={messages:[]},state={transcription:false,model:{providerName:'OpenAI',id:'realtime',name:'Voice'},transcripts:new Map(),ignoredResponses:new Set(),playing:new Set(),responding:false};
 const ctx=vm.createContext({rt:state,current,t:x=>x,render(){},queueRender(){},realtimeUi(){},realtimeState(){},realtimeNotice(){},playAudio(){},endRealtime(){}});
 vm.runInContext(section(source,'function finishRealtimeMapReply(state){','function interruptRealtime(){'),ctx);
 vm.runInContext(section(source,'function addRtMessage(role,text,responseId){',"$('connectRealtime').onclick"),ctx);
 ctx.rtEvent({type:'input_audio_buffer.speech_started',item_id:'u1'});
 ctx.rtEvent({type:'conversation.item.input_audio_transcription.delta',item_id:'u1',delta:'滁州地图'});
 assert.equal(current.messages[0].mapTranscriptPending,true);
 ctx.rtEvent({type:'response.created',response:{id:'r1'}});
 ctx.rtEvent({type:'response.output_text.delta',response_id:'r1',delta:'地图来了'});
 assert.equal(current.messages[1].realtimeReplyPending,true);
 ctx.rtEvent({type:'response.done',response:{id:'r1',status:'completed'}});
 assert.equal(current.messages[1].realtimeReplyPending,undefined);
 assert.equal(current.messages[0].mapTranscriptPending,true);
 ctx.rtEvent({type:'conversation.item.input_audio_transcription.completed',item_id:'u1',transcript:'滁州地图'});
 assert.equal(current.messages[0].mapTranscriptPending,undefined);
});
test('interruption and a newer response release the old reply without accepting stale events',()=>{
 for(const mode of ['button','speech','replacement']){
  const {ctx,state}=realtimeHarness();state.playing.clear();state.assistant={content:'partial',realtimeReplyPending:true};const previous=state.assistant;
  if(mode==='button')ctx.interruptRealtime();
  else if(mode==='speech')ctx.rtEvent({type:'input_audio_buffer.speech_started',item_id:'next'});
  else ctx.rtEvent({type:'response.created',response:{id:'new'}});
  assert.equal(previous.realtimeReplyPending,undefined,mode);
  if(mode==='replacement'){state.assistant={content:'new',realtimeReplyPending:true};ctx.rtEvent({type:'response.done',response:{id:'old',status:'completed'}});assert.equal(state.assistant.realtimeReplyPending,true);}
 }
});
test('failed and unfinished voice transcripts do not become map intents',()=>{
 const {ctx,state,messages}=realtimeHarness();state.playing.clear();state.responding=false;
 ctx.rtEvent({type:'input_audio_buffer.speech_started',item_id:'u1'});
 ctx.rtEvent({type:'conversation.item.input_audio_transcription.delta',item_id:'u1',delta:'滁州地图'});
 assert.equal(messages[0].mapTranscriptPending,true);
 ctx.rtEvent({type:'conversation.item.input_audio_transcription.failed',item_id:'u1'});
 assert.equal(messages[0].content,'滁州地图');assert.equal(messages[0].mapTranscriptPending,undefined);assert.equal(messages[0].mapTranscriptFailed,true);
});
test('late response events cannot receive another response\'s text, audio, sources, or completion',()=>{
 const {ctx,state,messages}=realtimeHarness(),played=[];state.playing.clear();state.responding=false;ctx.playAudio=(audio,event)=>played.push(event.response_id);
 vm.runInContext("rtEvent({type:'response.created',response:{id:'older'}});rtEvent({type:'response.created',response:{id:'search_reply'}});rtEvent({type:'response.output_audio_transcript.delta',response_id:'older',delta:'stale'});rtEvent({type:'response.output_audio.delta',response_id:'older',delta:'AAAA'});rtEvent({type:'response.done',response:{id:'older',status:'completed'}});",ctx);
 assert.equal(messages.length,0);assert.equal(played.length,0);assert.equal(state.responding,true);
 vm.runInContext("rtEvent({type:'response.output_audio_transcript.delta',response_id:'search_reply',delta:'fresh'});",ctx);
 assert.equal(messages.length,1);assert.equal(messages[0].responseId,'search_reply');assert.equal(messages[0].content,'fresh');
});
test('empty and failed recognition are shown without corrupting other turns',()=>{
 const {ctx,state,messages}=realtimeHarness();state.playing.clear();state.responding=false;
 vm.runInContext("rtEvent({type:'input_audio_buffer.speech_started',item_id:'u1'});rtEvent({type:'input_audio_buffer.speech_started',item_id:'u2'});rtEvent({type:'conversation.item.input_audio_transcription.delta',item_id:'u2',delta:'第二句'});rtEvent({type:'conversation.item.input_audio_transcription.completed',item_id:'u1',transcript:''});rtEvent({type:'input_audio_buffer.speech_started',item_id:'u3'});rtEvent({type:'conversation.item.input_audio_transcription.failed',item_id:'u3',error_code:'audio_unintelligible'});",ctx);
 assert.deepEqual(messages.map(m=>m.content),['这段语音没有识别出文字。','第二句','没有听清这句话。']);
});
test('voice conversation requests same-session transcription and opens only one socket',async()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),sockets=[],requests=[],controls={connectRealtime:{},apiKey:{value:'offline-key'},voice:{value:'alloy'},accent:{value:'british'},webSearch:{checked:true,disabled:false},recognitionLanguage:{value:'zh'}};
 class Socket{constructor(){this.readyState=1;this.bufferedAmount=0;sockets.push(this)}send(){} }Socket.OPEN=1;
 class Audio{constructor(){this.currentTime=0}async resume(){}}
 const model={provider:'openai',id:'gpt-realtime-2.1-mini',categories:['realtime'],outputs:['audio']};
 const ctx=vm.createContext({rt:null,rtGeneration:0,rtConnecting:false,realtimePreparation:Promise.resolve(),busy:false,publicInsecure:false,accountEmail:'',t:x=>x,$:id=>controls[id],selectedModel:()=>model,collectParameters:async()=>({}),realtimeUi(){},realtimeNotice(){},realtimeState(){},endRealtime(){},location:{href:'https://example.test/',protocol:'https:'},URL,WebSocket:Socket,AudioContext:Audio,setTimeout:()=>1,
  StarProviderOperations:{prepare:async(kind,body)=>{requests.push({kind,body});return {id:'r1',fresh:true,body}},beforeDispatch:async()=>{}}});
 vm.runInContext(section(source,"$('connectRealtime').onclick=async()=>{",'async function beginRecording(){'),ctx);await controls.connectRealtime.onclick();
 assert.equal(sockets.length,1);assert.equal(requests.length,1);assert.equal(requests[0].body.transcriptMode,'conversation');assert.equal(requests[0].body.accent,'british');assert.equal(requests[0].body.webSearch,true);assert.equal(requests[0].body.transcriptionLanguage,'zh');assert.equal(ctx.rt.webSearch,true);
});
test('search events update the current live session and interruptions discard pending citations',()=>{
 const {ctx,state}=realtimeHarness(),handled=[],cleared=[];ctx.StarRealtimeSearch={handle:(event,current)=>handled.push([event.type,current]),clear:current=>cleared.push(current)};
 vm.runInContext("rtEvent({type:'starhub.web_search',status:'started'});rtEvent({type:'input_audio_buffer.speech_started',item_id:'u1'});",ctx);
 assert.equal(handled.length,1);assert.equal(handled[0][1],state);assert.deepEqual(cleared,[state]);
});
test('manual interruption cancels a pending search even after the model response ended',()=>{
 const {ctx,state,sent}=realtimeHarness();state.responding=false;state.playing.clear();state.webSearch=true;state.pendingWebSearch={callId:'search1'};ctx.StarRealtimeSearch={clear:current=>{current.pendingWebSearch=null;}};
 vm.runInContext('interruptRealtime()',ctx);assert.deepEqual(JSON.parse(JSON.stringify(sent)),[{type:'starhub.cancel_search'}]);assert.equal(state.pendingWebSearch,null);
});
test('manual interruption cancels an in-flight search continuation even after pending citations clear',()=>{
 const {ctx,state,sent}=realtimeHarness();state.responding=false;state.playing.clear();state.webSearch=true;state.pendingWebSearch=null;
 vm.runInContext('interruptRealtime()',ctx);assert.deepEqual(JSON.parse(JSON.stringify(sent)),[{type:'starhub.cancel_search'}]);
 const other=realtimeHarness();other.state.responding=false;other.state.playing.clear();vm.runInContext('interruptRealtime()',other.ctx);assert.equal(other.sent.length,0);
});
test('pausing mid-speech finishes one turn; pausing while silent creates no reply',async()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),sent=[];let tracksStopped=0;
 const state={recording:true,stopping:false,speechActive:true,transcription:false,source:{disconnect(){}},stream:{getTracks:()=>[{stop:()=>tracksStopped++}]},capture:{disconnect(){},port:{postMessage(){state.flushDone();}}}};
 const ctx=vm.createContext({rt:state,rtSend:event=>sent.push(event.type),realtimeState(){},realtimeUi(){},realtimeMicStatus(){},endRealtime(){},setTimeout,clearTimeout,t:x=>x});
 vm.runInContext(section(source,'async function finishRecording(){',"$('recordRealtime').onclick"),ctx);
 await vm.runInContext('finishRecording()',ctx);assert.deepEqual(sent,['input_audio_buffer.commit','response.create']);assert.equal(tracksStopped,1);
 state.recording=true;state.speechActive=false;await vm.runInContext('finishRecording()',ctx);assert.equal(sent.length,2);
});
test('manual transcription commits complete segments and drops an uncommittable short final fragment',async()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8');
 for(const model of ['gpt-live-transcribe','gpt-realtime-whisper']){
  const sent=[],events={},track={readyState:'live',addEventListener:(name,fn)=>{events[name]=fn;},stop(){}},stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
  const audio={state:'running',audioWorklet:{addModule:async()=>{}},resume:async()=>{},destination:{},createMediaStreamSource:()=>({connect(){},disconnect(){}})};
  const state={ready:true,recording:false,stopping:false,transcription:true,model:{id:model},audio};
  class Worklet{constructor(){this.port={postMessage:()=>{this.port.onmessage({data:{flushed:true}});}};}connect(){}disconnect(){}}
  const ctx=vm.createContext({rt:state,navigator:{mediaDevices:{getUserMedia:async()=>stream}},AudioWorkletNode:Worklet,performance:{now:()=>1},Uint8Array,btoa:value=>Buffer.from(value,'binary').toString('base64'),t:x=>x,realtimeUi(){},realtimeNotice(){},realtimeState(){},realtimeMicStatus(){},rtSend:event=>sent.push(event.type),endRealtime(){},setTimeout,clearTimeout});
  vm.runInContext(section(source,'async function beginRecording(){',"$('recordRealtime').onclick"),ctx);
  await vm.runInContext('beginRecording()',ctx);
  // A sub-100ms segment must never reach the provider, because it cannot be committed.
  state.capture.port.onmessage({data:{audio:new Uint8Array(1000).buffer}});
  await vm.runInContext('finishRecording()',ctx);assert.deepEqual(sent,[]);
  await vm.runInContext('beginRecording()',ctx);
  state.capture.port.onmessage({data:{audio:new Uint8Array(4800).buffer}});
  state.capture.port.onmessage({data:{audio:new Uint8Array(1000).buffer}});
  await vm.runInContext('finishRecording()',ctx);
  assert.deepEqual(sent,['input_audio_buffer.append','input_audio_buffer.append','input_audio_buffer.commit']);
  await vm.runInContext('beginRecording()',ctx);
  state.uncommittedBytes=60*48000-4800;
  state.capture.port.onmessage({data:{audio:new Uint8Array(4800).buffer}});
  await vm.runInContext('finishRecording()',ctx);
  assert.deepEqual(sent.slice(-2),['input_audio_buffer.append','input_audio_buffer.commit']);
  await vm.runInContext('beginRecording()',ctx);
  const oldCapture=state.capture,oldEnded=events.ended;
  oldCapture.port.onmessage({data:{audio:new Uint8Array(4800).buffer}});oldEnded();
  assert.deepEqual(sent.slice(-2),['input_audio_buffer.append','input_audio_buffer.commit']);
  await vm.runInContext('beginRecording()',ctx);const before=sent.length;
  oldCapture.port.onmessage({data:{audio:new Uint8Array(4800).buffer}});oldEnded();
  assert.equal(sent.length,before);assert.equal(state.recording,true);
  await vm.runInContext('finishRecording()',ctx);
 }
});
test('microphone sends input while the assistant speaks and reports device loss',async()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),sent=[],levels=[],timers=[],events={};let stopped=0,resumed=0,constraints;
 const track={readyState:'live',addEventListener:(name,fn)=>{events[name]=fn;},stop(){stopped++;}};
 const stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
 const audio={state:'suspended',audioWorklet:{addModule:async()=>{}},destination:{},resume:async()=>{resumed++;audio.state='running';},createMediaStreamSource:()=>({connect(){},disconnect(){}})};
 const state={ready:true,recording:false,stopping:false,transcription:false,audio,responding:true,playing:new Set([{}])};
 class Worklet{constructor(){this.port={};}connect(){}disconnect(){}}
 const ctx=vm.createContext({rt:state,navigator:{mediaDevices:{getUserMedia:async value=>{constraints=value;return stream;}}},AudioWorkletNode:Worklet,performance:{now:()=>100},Uint8Array,btoa:value=>Buffer.from(value,'binary').toString('base64'),t:x=>x,realtimeUi(){},realtimeNotice(){},realtimeState(){},realtimeMicStatus:value=>levels.push(value),rtSend:event=>sent.push(event),interruptRealtime(){},endRealtime(){},setTimeout:fn=>{timers.push(fn);return timers.length;},clearTimeout(){}});
 vm.runInContext(section(source,'async function beginRecording(){','async function finishRecording(){'),ctx);await vm.runInContext('beginRecording()',ctx);
 assert.equal(resumed,1);assert.equal(constraints.audio.echoCancellation,true);assert.equal(constraints.audio.autoGainControl,true);assert.equal(state.recording,true);
 state.capture.port.onmessage({data:{audio:new Uint8Array([1,2,3,4]).buffer,level:0.2}});
 assert.equal(sent.length,1);assert.equal(sent[0].type,'input_audio_buffer.append');assert.equal(state.recording,true);assert.equal(levels.at(-1),'麦克风输入正常');
 const oldCapture=state.capture,oldEnded=events.ended;oldEnded();assert.equal(stopped,1);assert.equal(state.recording,false);assert.equal(levels.at(-1),'麦克风连接已中断，请重新开启。');
 oldCapture.port.onmessage({data:{audio:new Uint8Array([1,2]).buffer,level:0.5}});assert.equal(sent.length,1);
 await vm.runInContext('beginRecording()',ctx);assert.notEqual(state.capture,oldCapture);oldEnded();oldCapture.port.onmessage({data:{audio:new Uint8Array([1,2]).buffer,level:0.5}});assert.equal(stopped,1);assert.equal(sent.length,1);assert.equal(state.recording,true);
});
test('microphone reports a missing audio stream without inventing recognition',async()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),levels=[],timers=[];const track={readyState:'live',addEventListener(){},stop(){}};const stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
 const audio={state:'running',audioWorklet:{addModule:async()=>{}},resume:async()=>{},destination:{},createMediaStreamSource:()=>({connect(){},disconnect(){}})};const state={ready:true,recording:false,stopping:false,transcription:true,audio};
 class Worklet{constructor(){this.port={};}connect(){}disconnect(){}}
 const ctx=vm.createContext({rt:state,navigator:{mediaDevices:{getUserMedia:async()=>stream}},AudioWorkletNode:Worklet,performance:{now:()=>0},t:x=>x,realtimeUi(){},realtimeNotice(){},realtimeState(){},realtimeMicStatus:value=>levels.push(value),rtSend(){},endRealtime(){},setTimeout:fn=>{timers.push(fn);return 1;},clearTimeout(){}});
 vm.runInContext(section(source,'async function beginRecording(){','async function finishRecording(){'),ctx);await vm.runInContext('beginRecording()',ctx);timers[0]();
 assert.equal(levels.at(-1),'麦克风无音频数据，请检查浏览器或系统输入设备。');assert.equal(state.recording,true);
});
test('Chinese names apply only to the requested accounts and reverse when switching language',()=>{
 const source=fs.readFileSync(path.join(root,'account.js'),'utf8'),ctx=vm.createContext({language:'zh'});
 vm.runInContext(section(source,'function localizedAccountName(', 'function accountDisplay('),ctx);
 assert.equal(vm.runInContext("localizedAccountName('Zhongjie Huang')",ctx),'黄中杰');assert.equal(vm.runInContext("localizedAccountName('Rong Huang')",ctx),'黄蓉');assert.equal(vm.runInContext("localizedAccountName('Another Name')",ctx),'Another Name');
 ctx.language='en';assert.equal(vm.runInContext("localizedAccountName('Rong Huang')",ctx),'Rong Huang');
});
