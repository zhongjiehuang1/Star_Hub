const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../../main/webapp/advanced.js'),'utf8');
function section(start,end){const a=source.indexOf(start),b=source.indexOf(end,a);assert(a>=0&&b>a);return source.slice(a,b);}
test('Live audio and interleaved transcripts are handled without Realtime response events',()=>{
 const messages=[],played=[],state={model:{id:'gpt-live-1'},transcription:false,ready:true,playing:new Set(),ignoredResponses:new Set()};
 const ctx=vm.createContext({rt:state,current:{messages},t:x=>x,queueRender(){},realtimeState(){},realtimeNotice(){},realtimeUi(){},endRealtime(){},playAudio:(data)=>played.push(data),addRtMessage:(role,content)=>{const m={role,content};messages.push(m);return m;}});
 vm.runInContext(section('function rtEvent(event){',"$('connectRealtime').onclick"),ctx);
 for(const event of [{type:'session.output_audio.delta',delta:'AAAA'},{type:'session.input_transcript.delta',delta:'你好',start_ms:0,end_ms:500},{type:'session.output_transcript.delta',delta:'您好',start_ms:400,end_ms:700},{type:'session.input_transcript.delta',delta:'呀',start_ms:500,end_ms:800},{type:'session.input_transcript.delta',delta:'再见',start_ms:4000,end_ms:4300}])ctx.rtEvent(event);
 assert.deepEqual(played,['AAAA']);assert.deepEqual(messages.map(m=>[m.role,m.content]),[['user','你好呀'],['assistant','您好'],['user','再见']]);assert.equal(messages[0].mapTranscriptFailed,true);assert.deepEqual(JSON.parse(JSON.stringify(messages[0].liveFragments)),[{text:'你好',start_ms:0,end_ms:500},{text:'呀',start_ms:500,end_ms:800}]);
});
test('Live new transcript remains visible after an answer or source without breaking overlap',()=>{
 const search=require('../../main/webapp/realtime-search-ui.js'),messages=[],state={model:{id:'gpt-live-1'},webSearch:true};
 const ctx=vm.createContext({rt:state,current:{messages},StarRealtimeSearch:search,t:x=>x,queueRender(){},realtimeNotice(){},endRealtime(){},addRtMessage:(role,content)=>{const m={role,content};messages.push(m);return m;}});
 vm.runInContext(section('function rtEvent(event){',"$('connectRealtime').onclick"),ctx);
 for(const event of [
  {type:'session.input_transcript.delta',delta:'第一句',start_ms:0,end_ms:500},
  {type:'session.output_transcript.delta',delta:'回应',start_ms:600,end_ms:900},
  {type:'session.input_transcript.delta',delta:'第二句',start_ms:1000,end_ms:1200},
  {type:'starhub.live_citation',delegationId:'search1',url:'https://example.com/source',title:'Source'},
  {type:'session.input_transcript.delta',delta:'继续',start_ms:1300,end_ms:1400}
 ])ctx.rtEvent(event);
 assert.deepEqual(messages.map(m=>[m.role,m.content]),[['user','第一句'],['assistant','回应'],['user','第二句'],['assistant','联网搜索来源'],['user','继续']]);
 assert.deepEqual(JSON.parse(JSON.stringify(messages[4].liveFragments)),[{text:'继续',start_ms:1300,end_ms:1400}]);
});
test('Live backend validation error is clear without changing other errors',()=>{
 const notices=[],state={model:{id:'gpt-live-1'},ready:true,webSearch:true};
 const ctx=vm.createContext({rt:state,t:x=>x,realtimeNotice:x=>notices.push(x),endRealtime(){}});
 vm.runInContext(section('function rtEvent(event){',"$('connectRealtime').onclick"),ctx);
 ctx.rtEvent({type:'starhub.error',errorCode:'responses_validation_failed',message:'generic backend error'});
 ctx.rtEvent({type:'starhub.error',errorCode:'audio_error',message:'audio problem'});
 assert.doesNotThrow(()=>ctx.rtEvent({type:'session.input_audio.muted'}));
 assert.deepEqual(notices,['联网后台配置失败，请检查账户的模型和搜索权限，或关闭联网后重新连接。','audio problem']);
});
test('Live search reports status and keeps safe sources separate by delegation',()=>{
 const search=require('../../main/webapp/realtime-search-ui.js'),messages=[],notices=[],state={model:{id:'gpt-live-1'},webSearch:true};
 const ctx=vm.createContext({rt:state,StarRealtimeSearch:search,t:x=>x,queueRender(){},realtimeNotice:s=>notices.push(s),endRealtime(){},addRtMessage:(role,content)=>{const message={role,content,realtimeReplyPending:true};messages.push(message);return message;}});
 vm.runInContext(section('function rtEvent(event){',"$('connectRealtime').onclick"),ctx);
 ctx.rtEvent({type:'starhub.live_search',status:'searching',delegationId:'d1'});ctx.rtEvent({type:'starhub.live_search',status:'completed',delegationId:'d1'});
 for(const event of [{type:'starhub.live_citation',delegationId:'d1',url:'https://example.com/a',title:'First'},{type:'starhub.live_citation',delegationId:'d1',url:'https://example.com/a',title:'Duplicate'},{type:'starhub.live_citation',delegationId:'d2',url:'https://example.org/b',title:'Second'},{type:'starhub.live_citation',delegationId:'d1',url:'javascript:alert(1)',title:'Unsafe'}])ctx.rtEvent(event);
 assert.deepEqual(notices,['正在联网搜索…','联网搜索完成，正在整理回答。']);assert.equal(messages.length,2);assert.equal(messages[0].content,'联网搜索来源');assert.deepEqual(messages.map(m=>m.details.webCitations.length),[1,1]);assert.equal(messages[0].details.webCitations[0].url,'https://example.com/a');assert.equal(messages[0].realtimeReplyPending,undefined);
});
test('Live microphone resumes and mutes without sending Realtime turn controls',async()=>{
 const sent=[],track={readyState:'live',addEventListener(){},stop(){}},stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
 const audio={state:'running',audioWorklet:{addModule:async()=>{}},resume:async()=>{},destination:{},createMediaStreamSource:()=>({connect(){},disconnect(){}})};
 const state={model:{id:'gpt-live-1'},ready:true,transcription:false,recording:false,stopping:false,audio,speechActive:true};
 class Worklet{constructor(){this.port={postMessage:()=>this.port.onmessage({data:{flushed:true}})};}connect(){}disconnect(){}}
 const ctx=vm.createContext({rt:state,navigator:{mediaDevices:{getUserMedia:async()=>stream}},AudioWorkletNode:Worklet,performance:{now:()=>1},Uint8Array,btoa:value=>Buffer.from(value,'binary').toString('base64'),t:x=>x,realtimeUi(){},realtimeNotice(){},realtimeState(){},realtimeMicStatus(){},rtSend:event=>{sent.push(event.type);if(event.event_id)queueMicrotask(()=>ctx.rtEvent({type:event.type.endsWith('unmute')?'session.input_audio.unmuted':'session.input_audio.muted',client_event_id:event.event_id}));},interruptRealtime(){throw new Error('must not interrupt Live')},endRealtime(){},setTimeout,clearTimeout});
 vm.runInContext(section('function rtEvent(event){',"$('connectRealtime').onclick"),ctx);vm.runInContext(section('function liveAudioControl(action){',"$('recordRealtime').onclick"),ctx);
 await ctx.beginRecording();state.capture.port.onmessage({data:{audio:new Uint8Array(4).buffer}});await ctx.finishRecording();await ctx.beginRecording();
 assert.deepEqual(sent,['input_audio_buffer.append','starhub.input_audio.mute','starhub.input_audio.unmute']);state.recording=false;clearTimeout(state.micTimer);
});
test('Live orderly close waits for socket closure before reconciling the receipt',()=>{
 const sent=[],calls=[],socket={readyState:1,send:x=>sent.push(JSON.parse(x)),close:()=>calls.push('close')};
 const state={model:{id:'gpt-live-1'},ready:true,recording:true,playing:new Set(),transcripts:new Map(),socket,operationId:'op',audio:{close:async()=>{}},dispatched:true};
 const ctx=vm.createContext({rt:state,rtGeneration:0,rtConnecting:false,t:x=>x,finishRealtimeMapReply(){},clearTimeout(){},setTimeout:()=>1,realtimeMicStatus(){},stopPlayback(){},WebSocket:{OPEN:1},StarProviderOperations:{ownerGuard:()=>()=>true,markUnknown:()=>calls.push('unknown')},settleRealtimeReceipt:()=>calls.push('settle'),realtimeState(){},realtimeNotice(){},realtimeUi(){},scheduleHistorySave(){}});
 vm.runInContext(section('function endRealtime(',"$('endRealtime').onclick"),ctx);ctx.endRealtime('done',true);
 assert.deepEqual(sent,[{type:'starhub.close'}]);assert.deepEqual(calls,[]);socket.onclose();assert.deepEqual(calls,['settle']);
});
test('Live microphone control waits for its matching confirmation and reports rejection',async()=>{
 const sent=[],timers=new Map(),state={model:{id:'gpt-live-1'},ready:true};let next=0;
 const ctx=vm.createContext({rt:state,t:x=>x,rtSend:event=>sent.push(event),setTimeout:fn=>{timers.set(++next,fn);return next;},clearTimeout:id=>timers.delete(id),realtimeNotice(){},endRealtime(){}});
 vm.runInContext(section('function rtEvent(event){',"$('connectRealtime').onclick"),ctx);vm.runInContext(section('function liveAudioControl(action){','async function beginRecording(){'),ctx);
 let settled=false;const muted=ctx.liveAudioControl('mute').then(()=>{settled=true;});
 ctx.rtEvent({type:'session.input_audio.muted',client_event_id:'different'});await Promise.resolve();assert.equal(settled,false);
 ctx.rtEvent({type:'session.input_audio.muted',client_event_id:sent[0].event_id});await muted;assert.equal(settled,true);
 const unmuted=ctx.liveAudioControl('unmute');ctx.rtEvent({type:'starhub.error',client_event_id:sent[1].event_id,message:'control rejected'});await assert.rejects(unmuted,/control rejected/);assert.equal(timers.size,0);
 const timed=ctx.liveAudioControl('mute');[...timers.values()][0]();await assert.rejects(timed,/超时/);
});
test('Live microphone remains busy until the mute acknowledgement arrives',async()=>{
 let acknowledge;const muted=new Promise(resolve=>{acknowledge=resolve;});const calls=[];
 const state={model:{id:'gpt-live-1'},recording:true,stopping:false,transcription:false,source:{disconnect(){}},stream:{getTracks:()=>[{stop(){}}]},capture:{disconnect(){},port:{postMessage(){state.flushDone();}}}};
 const ctx=vm.createContext({rt:state,t:x=>x,clearTimeout,setTimeout,realtimeMicStatus(){},realtimeState(){},realtimeUi(){},liveAudioControl:action=>{calls.push(action);return muted;},endRealtime(){throw new Error('unexpected disconnect');}});
 vm.runInContext(section('async function finishRecording(){',"$('recordRealtime').onclick"),ctx);
 const completion=ctx.finishRecording();await Promise.resolve();
 assert.deepEqual(calls,['mute']);assert.equal(state.recording,false);assert.equal(state.stopping,true);assert.equal(state.liveMuted,undefined);
 acknowledge();await completion;assert.equal(state.stopping,false);assert.equal(state.liveMuted,true);
});
