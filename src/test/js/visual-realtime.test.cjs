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

test('voice cards preserve bounded visual data and reject invalid bar values',()=>{
 const steps={version:1,kind:'steps',title:'循环',items:[{label:'蒸发',detail:'水变成水蒸气'},{label:'凝结',detail:'形成水滴'}]};
 const bars={version:1,kind:'bars',title:'Comparison',unit:'kg',items:[{label:'A',value:0},{label:'B',value:24}]};
 assert.match(visuals.voiceVisual(JSON.stringify(steps)).block,/star-explain/);
 assert.deepEqual(visuals.validateExplanation(JSON.stringify(bars)),bars);
 assert.equal(visuals.voiceVisual(JSON.stringify({...bars,items:[{label:'A',value:-2},bars.items[1]]})),null);
 assert.equal(visuals.voiceVisual(JSON.stringify({...steps,items:Array(9).fill(steps.items[0])})),null);
});

test('voice visuals follow their spoken section and merge across the tool continuation',()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),messages=[];
 const state={transcription:false,currentResponse:'current',ignoredResponses:new Set(['interrupted'])};
 const ctx=vm.createContext({rt:state,StarVisuals:visuals,t:x=>x,queueRender(){},realtimeState(){},
  finishRealtimeMapReply(){},addRtMessage(role,content){const message={role,content};messages.push(message);return message;}});
 vm.runInContext(section(source,'function rtEvent(event){',"$('connectRealtime').onclick"),ctx);
 const first=JSON.stringify({...scene,kind:'diagram',cue:'第一部分'});
 const second=JSON.stringify({version:1,kind:'bars',title:'Comparison',cue:'第二部分',items:[{label:'A',value:4},{label:'B',value:8}]});
 for(const response_id of ['interrupted','old','current'])ctx.rtEvent({type:'starhub.explanation',response_id,spec:first});
 ctx.rtEvent({type:'starhub.explanation',response_id:'current',spec:second});
 ctx.rtEvent({type:'starhub.explanation',response_id:'current',spec:'invalid'});
 ctx.rtEvent({type:'response.created',response:{id:'speech'},visual_parent_response_id:'current'});
 ctx.rtEvent({type:'response.output_audio_transcript.delta',response_id:'speech',delta:'第一部分说明了波。第二部分比较两项。谢谢。'});
 assert.equal(messages.length,1);
 assert.equal(messages[0].voiceVisuals.length,2);
 assert.match(messages[0].content,/第一部分说明了波。\n\n```star-visual[\s\S]+第二部分比较两项。\n\n```star-explain[\s\S]+谢谢。/);
});
test('Live voice keeps visuals with the relevant turn and ignores stale delegations',()=>{
 const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8'),messages=[];
 const state={model:{id:'gpt-live-1'},transcription:false};
 const ctx=vm.createContext({rt:state,current:{messages},StarVisuals:visuals,t:x=>x,queueRender(){},
  addRtMessage(role,content){const message={role,content};messages.push(message);return message;}});
 vm.runInContext(section(source,'function rtEvent(event){',"$('connectRealtime').onclick"),ctx);
 const spec=JSON.stringify({...scene,kind:'diagram',cue:'波'});
 ctx.rtEvent({type:'session.input_transcript.delta',delta:'说明波',start_ms:100,end_ms:500});
 ctx.rtEvent({type:'starhub.explanation',offset_ms:600,spec});
 ctx.rtEvent({type:'session.output_transcript.delta',delta:'看这个波。继续讲。',start_ms:800,end_ms:1400});
 ctx.rtEvent({type:'session.input_transcript.delta',delta:'新问题',start_ms:1700,end_ms:2000});
 ctx.rtEvent({type:'starhub.explanation',offset_ms:1200,spec});
 assert.equal(messages.length,3);
 assert.match(messages[1].content,/看这个波。\n\n```star-visual[\s\S]+继续讲。/);
 assert.equal(state.pendingLiveVisuals.length,0);
});

test('missing voice cues use labels, titles, and descriptions to place distinct English figures',()=>{
 const lens=visuals.voiceVisual(JSON.stringify({...scene,title:'Image on a screen',cue:'A real image forms here',
  description:'The rays converge behind the lens',objects:[{type:'arrow',x:10,y:20,x2:100,y2:20,label:'converging rays'}]}));
 const mirror=visuals.voiceVisual(JSON.stringify({...scene,title:'Looking in glass',cue:'Now for your reflection',
  description:'The reflected light appears to come from behind the mirror.',objects:[{type:'text',x:10,y:20,text:'virtual image'}]}));
 const transcript='Here is the big picture. With the lens, converging rays meet on a screen. Reflected light appears to come from behind the mirror and makes a virtual image. The examples help us compare the cases.';
 const result=visuals.placeVoiceVisuals(transcript,[lens,mirror]);
 assert.match(result,/converging rays meet on a screen\.\n\n```star-visual[\s\S]+Reflected light appears to come from behind the mirror and makes a virtual image\.\n\n```star-visual[\s\S]+The examples/);
 assert.ok(result.indexOf(lens.block)<result.indexOf(mirror.block));
});

test('Chinese voice figures find related speech even when the model paraphrases both cues',()=>{
 const up=visuals.voiceVisual(JSON.stringify({version:1,kind:'steps',title:'水进入大气',cue:'图卡一向上走',items:[
  {label:'蒸腾',detail:'植物叶片释放水汽'},{label:'蒸发',detail:'液态水变成水蒸气'}]}));
 const down=visuals.voiceVisual(JSON.stringify({version:1,kind:'steps',title:'水回到地表',cue:'图卡二向下走',items:[
  {label:'凝结',detail:'小水滴汇聚成云'},{label:'降水',detail:'下雨时水回到地面'}]}));
 const transcript='我们分两段来看。首先地表的水蒸发，植物也会蒸腾。冷空气让水汽凝结，形成云和降水。这个循环持续进行。';
 const result=visuals.placeVoiceVisuals(transcript,[up,down]);
 assert.match(result,/植物也会蒸腾。\n\n```star-explain[\s\S]+形成云和降水。\n\n```star-explain[\s\S]+这个循环持续进行。/);
 assert.ok(result.indexOf(up.block)<result.indexOf(down.block));
});

test('unmatched visuals spread across speech and exact cues still win over stronger keywords',()=>{
 const cards=['Zirconium','Alabaster','Quartzite'].map(title=>visuals.voiceVisual(JSON.stringify({...scene,title,description:'Minerals under magnification'})));
 const transcript='I will introduce the topic. Start by listing the practical problems people face. Next we can build a fairly detailed solution. A short conclusion makes the final point.';
 const result=visuals.placeVoiceVisuals(transcript,cards);
 assert.ok(result.indexOf(cards[0].block)<result.indexOf('Next we'));
 assert.ok(result.indexOf(cards[1].block)<result.indexOf('A short conclusion'));
 assert.equal((result.match(/```star-visual/g)||[]).length,3);
 const anchored=visuals.voiceVisual(JSON.stringify({...scene,title:'Practical problems people face',cue:'A short conclusion'}));
 assert.ok(visuals.placeVoiceVisuals(transcript,[anchored]).indexOf(anchored.block)>transcript.indexOf('the final point'));
});
