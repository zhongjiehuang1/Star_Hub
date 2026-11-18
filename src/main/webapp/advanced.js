'use strict';
function downloadJson(value,name){const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));const a=element('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}

let rt=null,rtGeneration=0,rtConnecting=false,realtimePreparation=Promise.resolve();
function realtimeState(text){$('realtimeState').textContent=text;}
function realtimeNotice(text){$('realtimeNotice').textContent=errorText(text);}
function realtimeMicStatus(text){const indicator=$('realtimeMicLevel');if(!indicator)return;indicator.hidden=!text;const value=text?t(text):'';if(indicator.textContent!==value)indicator.textContent=value;}
let realtimeRecoveryToken=0;
function clearRealtimeRecovery(){realtimeRecoveryToken++;const box=$('realtimeRecovery');if(box)box.hidden=true;}
function showRealtimeRecovery(error,model){
  if(error?.code!=='PROVIDER_OPERATION_UNKNOWN'||!error.operationId)return;
  const box=$('realtimeRecovery'),check=$('realtimeCheckPrevious'),separate=$('realtimeStartSeparate'),status=$('realtimeRecoveryStatus');if(!box||!check||!separate||!status)return;
  const id=error.operationId,owner=StarProviderOperations.ownerGuard(),token=++realtimeRecoveryToken;
  const current=()=>token===realtimeRecoveryToken&&owner()&&selectedModel()?.id===model.id&&selectedModel()?.provider===model.provider;
  box.hidden=false;status.textContent='';check.disabled=false;separate.disabled=false;
  check.onclick=async()=>{
    if(!current()){clearRealtimeRecovery();realtimeNotice(t('账户或模型已改变，请重新点击连接。'));return;}
    check.disabled=true;try{const result=await StarProviderOperations.query(id);if(current())status.textContent=t('当前请求状态：{0}',result.operationState);}
    catch(e){if(current())status.textContent=errorText(e.message);}finally{if(current())check.disabled=false;}
  };
  separate.onclick=async()=>{
    if(!current()){clearRealtimeRecovery();realtimeNotice(t('账户或模型已改变，请重新点击连接。'));return;}
    separate.disabled=true;check.disabled=true;
    try{
      const records=await StarProviderOperations.visible();if(!current())return;
      if(!records.some(item=>item.id===id&&item.kind==='realtime'&&!item.archived)){clearRealtimeRecovery();realtimeNotice(t('账户或模型已改变，请重新点击连接。'));return;}
      StarProviderOperations.separate(id);clearRealtimeRecovery();$('connectRealtime').onclick();
    }catch(e){if(current())status.textContent=errorText(e.message);}finally{if(current()){separate.disabled=false;check.disabled=false;}}
  };
}
document.addEventListener('starhub:account-changed',()=>{clearRealtimeRecovery();if(rt||rtConnecting)endRealtime(t('账户已改变，实时连接已结束。'));});
let realtimeMicDevicesRevision=0;
function selectedRealtimeMicrophone(){return $('realtimeMicrophone')?.value||'';}
async function refreshRealtimeMicrophones(){
  const select=$('realtimeMicrophone'),media=navigator.mediaDevices;if(!select||!media?.enumerateDevices)return;
  const revision=++realtimeMicDevicesRevision;
  try{
    const devices=await media.enumerateDevices();if(revision!==realtimeMicDevicesRevision)return;
    const chosen=select.value,inputs=devices.filter(device=>device.kind==='audioinput'&&device.deviceId&&device.deviceId!=='default');
    const options=[],option=(value,label)=>{const node=document.createElement('option');node.value=value;node.textContent=label;return node;};
    options.push(option('',t('系统默认麦克风')));
    inputs.forEach((device,index)=>options.push(option(device.deviceId,device.label||t('麦克风 {0}',index+1))));
    select.replaceChildren(...options);
    if(chosen&&inputs.some(device=>device.deviceId===chosen))select.value=chosen;
    else if(chosen){select.value='';realtimeNotice(t('所选麦克风不可用，请选择其他设备后重试。'));}
  }catch{/* Unsupported enumeration must not block the default input. */}
}
$('realtimeDetails')?.addEventListener('toggle',()=>{if($('realtimeDetails').open)refreshRealtimeMicrophones();});
navigator.mediaDevices?.addEventListener?.('devicechange',refreshRealtimeMicrophones);
document.addEventListener('starhub:languagechanged',refreshRealtimeMicrophones);
function realtimeUi(){
  const active=Boolean(rt)||rtConnecting; realtimeActive=active;refreshConnectionSummary(); realtimeConnected=Boolean(rt?.ready);for(const id of ['provider','model','browseModels','modelDetails','modelSettings','task','apiKey','newChat'])$(id).disabled=active||busy;
  if(typeof refreshSearchControls==='function')refreshSearchControls();
  if($('voice'))$('voice').disabled=active;
  if($('accent'))$('accent').disabled=active;
  if($('recognitionLanguage'))$('recognitionLanguage').disabled=active;
  if($('realtimeMicrophone'))$('realtimeMicrophone').disabled=Boolean(rt?.recording||rt?.stopping);
  $('connectRealtime').disabled=active||busy||publicInsecure;$('endRealtime').disabled=!active;
  $('recordRealtime').disabled=!rt?.ready||rt?.stopping||rt?.idleEnded;$('interruptRealtime').disabled=!rt?.ready||rt.transcription||rt?.idleEnded;
  $('recordRealtime').textContent=rt?.recording?t('暂停麦克风'):t('开启麦克风');
  $('send').disabled=busy||!ready||publicInsecure||($('task').value==='realtime'&&(!rt?.ready||rt.transcription||rt.idleEnded));
  document.querySelectorAll('.history button').forEach(b=>b.disabled=active||busy);
}
function rtSend(event){if(rt?.idleEnded)throw new Error(t('登录状态已结束，请重新登录原账户。'));if(!rt?.socket||rt.socket.readyState!==WebSocket.OPEN)throw new Error(t("实时连接尚未就绪。"));if(rt.socket.bufferedAmount>(event.type==='input_audio_buffer.append'?512000:8*1024*1024)){endRealtime(t("网络发送拥堵，已停止连接。"));throw new Error(t("网络发送拥堵。"));}rt.socket.send(JSON.stringify(event));}
function expireRealtime(){
  const state=rt;if(!state)return false;if(state.idleEnded)return true;state.idleEnded=true;state.recording=false;state.stopping=false;state.speechActive=false;state.acceptAudio=false;stopPlayback(state);
  clearTimeout(state.micTimer);state.source?.disconnect();state.capture?.disconnect();state.stream?.getTracks().forEach(track=>track.stop());
  realtimeMicStatus('');realtimeNotice(t('登录状态已结束，实时语音可能中断；请重新登录原账户。'));realtimeUi();return true;
}
function stopPlayback(state=rt){if(!state)return;for(const node of state.playing){try{node.stop();}catch{}}state.playing.clear();state.nextPlay=state.audio.currentTime;}
function playAudio(base64,event){
  if(!rt)return;const responseId=event.response_id||rt.currentResponse||'unknown';
  if(rt.skippedAudioResponse===responseId)return;
  const raw=atob(base64);if(!raw.length||raw.length%2)return;
  const duration=raw.length/2/24000;
  // Generation can legitimately run ahead of real-time playback. Bound both
  // queued samples and nodes, but keep the live session usable on overflow.
  if(Math.max(0,rt.nextPlay-rt.audio.currentTime)+duration>120||rt.playing.size>=8192){
    rt.skippedAudioResponse=responseId;realtimeNotice(t('语音过长，后续语音已省略；完整内容可查看文字。'));return;
  }
  const array=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)array[i]=raw.charCodeAt(i);
  const view=new DataView(array.buffer),buffer=rt.audio.createBuffer(1,array.length/2,24000),channel=buffer.getChannelData(0);
  for(let i=0;i<channel.length;i++)channel[i]=view.getInt16(i*2,true)/32768;
  if(rt.audioItem!==event.item_id){rt.audioItem=event.item_id;rt.audioStart=Math.max(rt.nextPlay,rt.audio.currentTime);rt.audioDuration=0;}
  rt.audioDuration+=buffer.duration;rt.contentIndex=event.content_index||0;
  const node=rt.audio.createBufferSource();node.buffer=buffer;node.connect(rt.audio.destination);const state=rt;state.playing.add(node);node.onended=()=>state.playing.delete(node);
  const start=Math.max(rt.nextPlay,rt.audio.currentTime);node.start(start);rt.nextPlay=start+buffer.duration;
}
function truncateRtAudio(){
  if(!rt?.audioItem)return;
  const ms=Math.floor(Math.max(0,Math.min(rt.audioDuration,rt.audio.currentTime-rt.audioStart))*1000);
  rtSend({type:'conversation.item.truncate',item_id:rt.audioItem,content_index:rt.contentIndex,audio_end_ms:ms});
}
function finishRealtimeMapReply(state){
  if(state?.assistant?.realtimeReplyPending){delete state.assistant.realtimeReplyPending;queueRender();}
}
function interruptRealtime(){
  if(!rt?.ready||rt.transcription)return;
  if(rt.webSearch)rtSend({type:'starhub.cancel_search'});
  if(typeof StarRealtimeSearch!=='undefined')StarRealtimeSearch.clear(rt);
  if(rt.currentResponse)rt.ignoredResponses.add(rt.currentResponse);if(rt.responding)rtSend({type:'response.cancel'});
  finishRealtimeMapReply(rt);
  if(rt.playing.size)truncateRtAudio();
  stopPlayback();rt.audioItem=null;rt.responding=false;rt.acceptAudio=false;realtimeState(t("已打断，等待输入"));
}
function addRtMessage(role,text,responseId){
  if(!rt)return;const message={role,content:text,providerName:rt.model.providerName,model:rt.model.id,seconds:'realtime'};
  if(role==='assistant'&&!rt.transcription)message.realtimeReplyPending=true;
  if(role==='assistant'&&typeof StarRealtimeSearch!=='undefined')StarRealtimeSearch.attach(message,rt,responseId);
  if(!current.messages.length)current.title=t("实时语音 · ")+rt.model.name;
  current.messages.push(message);render();realtimeUi();return message;
}
function rtEvent(event){
  if(!rt)return;
  if(event.type==='starhub.web_search'){if(typeof StarRealtimeSearch!=='undefined')StarRealtimeSearch.handle(event,rt,{notice:realtimeNotice,t});return;}
  if(event.type==='starhub.session_ended'){expireRealtime();if(typeof StarSessionIdle!=='undefined')StarSessionIdle.check();return;}
  if(event.type==='starhub.ready'){rt.ready=true;realtimeState(rt.transcription?t("转写已连接"):t("语音已连接"));realtimeNotice(t('开启麦克风后开始实时对话；会话最长 30 分钟。'));realtimeUi();return;}
  if(event.type==='starhub.error'){rt.failureMessage=event.message;realtimeNotice(event.message);if(!rt.ready)endRealtime(event.message);return;}
  if(event.type==='input_audio_buffer.speech_started'){
    rt.speechActive=true;
    if(typeof StarSessionIdle!=='undefined')StarSessionIdle.activity(true,true);
    if(typeof StarRealtimeSearch!=='undefined')StarRealtimeSearch.clear(rt);
    if(rt.currentResponse&&(rt.responding||rt.playing.size))rt.ignoredResponses.add(rt.currentResponse);
    if(rt.responding||rt.playing.size){finishRealtimeMapReply(rt);if(rt.playing.size)truncateRtAudio();stopPlayback();rt.responding=false;rt.acceptAudio=false;rt.audioItem=null;}
    if(!rt.transcription){const key=event.item_id||'current';if(!rt.transcripts.has(key)){const message=addRtMessage('user',t('正在识别语音'));message.transcriptPending=true;message.mapTranscriptPending=true;rt.transcripts.set(key,message);}}
    realtimeState(t('正在听你说话'));return;
  }
  if(event.type==='input_audio_buffer.speech_stopped'){rt.speechActive=false;if(typeof StarSessionIdle!=='undefined')StarSessionIdle.activity(true,true);realtimeState(t('正在识别语音'));return;}
  if(event.response_id&&rt.ignoredResponses.has(event.response_id))return;
  if(event.type==='response.created'){finishRealtimeMapReply(rt);rt.currentResponse=event.response?.id;rt.skippedAudioResponse=null;rt.responding=true;rt.acceptAudio=true;rt.assistant=null;realtimeState(t("模型正在回复"));return;}
  // Events from a prior response can arrive after a newer response begins.
  // Never mix their text or audio with the newer response or its sources.
  if(event.response_id&&rt.currentResponse&&event.response_id!==rt.currentResponse)return;
  if(['response.output_audio.delta','response.audio.delta'].includes(event.type)&&rt.acceptAudio){playAudio(event.delta,event);return;}
  if(['response.output_audio_transcript.delta','response.audio_transcript.delta','response.output_text.delta','response.text.delta'].includes(event.type)){
    if(!rt.assistant)rt.assistant=addRtMessage('assistant','',event.response_id||rt.currentResponse);rt.assistant.content+=event.delta||'';queueRender();return;
  }
  if(event.type.startsWith('conversation.item.input_audio_transcription.')){
    const kind=event.type.split('.').pop();if(!['delta','intermediate','completed','failed'].includes(kind)||kind==='delta'&&!event.delta)return;
    const key=event.item_id||'current';let message=rt.transcripts.get(key);if(!message){message=addRtMessage(rt.transcription?'assistant':'user','');message.mapTranscriptPending=true;rt.transcripts.set(key,message);}if(kind==='completed'||kind==='failed'){delete message.mapTranscriptPending;if(kind==='failed')message.mapTranscriptFailed=true;else delete message.mapTranscriptFailed;}
    if(kind==='completed'&&!event.transcript&&!event.text&&(!message.content||message.transcriptPending)){message.content=t('这段语音没有识别出文字。');delete message.transcriptPending;queueRender();return;}
    if(message.transcriptPending){message.content='';delete message.transcriptPending;}
    if(kind==='completed')message.content=event.transcript||event.text||message.content;
    else if(kind==='intermediate')message.content=event.transcript||event.text||event.delta||message.content;
    else if(kind==='delta')message.content+=event.delta||'';
    else if(kind==='failed'&&!message.content)message.content=t(event.error_code==='audio_unintelligible'?'没有听清这句话。':'语音转写暂时不可用，请稍后重试。');
    queueRender();return;
  }
  if(event.type==='response.done'){if(rt.ignoredResponses.has(event.response?.id)||(event.response?.id&&rt.currentResponse&&event.response.id!==rt.currentResponse))return;rt.responding=false;if(rt.assistant){delete rt.assistant.realtimeReplyPending;queueRender();}realtimeState(event.response?.status==='failed'?t("本次回复失败"):(rt.recording?t('● 正在听'):t("等待输入")));}
}
$('connectRealtime').onclick=async()=>{
  if(rt||rtConnecting||busy||publicInsecure)return;const model=selectedModel();if(!model?.categories.includes('realtime'))return;
  if(typeof clearRealtimeRecovery==='function')clearRealtimeRecovery();
  const key=$('apiKey').value.trim();if(!key){realtimeNotice(t("请填写 OpenAI API Key。"));openSetup();return;}
  const search=$('webSearch'),webSearch=Boolean(model.outputs.includes('audio')&&search&&!search.disabled&&search.checked);
  const selectedLanguage=$('recognitionLanguage')?.value,transcriptionLanguage=model.outputs.includes('audio')&&['zh','en'].includes(selectedLanguage)?selectedLanguage:'auto';
  const generation=++rtGeneration,previousPreparation=realtimePreparation,expectedEmail=accountEmail;
  let releasePreparation;realtimePreparation=new Promise(resolve=>{releasePreparation=resolve;});
  const current=()=>rtGeneration===generation;
  let operation;rtConnecting=true;realtimeUi();
  try{
    // Wait for an earlier cancelled preparation to release any unsent identity.
    await previousPreparation;if(!current())return;
    const parameters=await collectParameters();if(!current())return;
    operation=await StarProviderOperations.prepare('realtime',{provider:model.provider,model:model.id,apiKey:key,voice:$('voice')?.value||'',accent:$('accent')?.value||'auto',transcriptMode:model.outputs.includes('audio')?'conversation':undefined,transcriptionLanguage:model.outputs.includes('audio')?transcriptionLanguage:undefined,webSearch,timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC',parameters,expectedEmail});
    if(!current()){if(operation.fresh)StarProviderOperations.discardUnsent(operation);return;}
    if(!operation.fresh){const previous=await StarProviderOperations.query(operation.id);if(!current())return;if(!['ABSENT','CLAIMED'].includes(previous.operationState))throw new StarProviderOperations.Pending(operation.id,previous.operationState,'The previous live session may already have been opened. Check its result or explicitly allow a separate session; audio will not be replayed.');}
    const audio=new AudioContext({sampleRate:24000});rt={model,audio,generation,ready:false,recording:false,playing:new Set(),nextPlay:0,transcripts:new Map(),ignoredResponses:new Set(),transcription:!model.outputs.includes('audio'),acceptAudio:true,webSearch};
    rt.operationId=operation.id;rt.operation=operation;rt.dispatched=false;
    realtimeState(t("正在连接"));realtimeNotice('');realtimeUi();await audio.resume();
    if(!current()||rt?.generation!==generation)return;
    const url=new URL('realtime',location.href);url.protocol=location.protocol==='https:'?'wss:':'ws:';
    const socket=new WebSocket(url);rt.socket=socket;
    socket.onopen=async()=>{try{await StarProviderOperations.beforeDispatch(operation);if(current()&&rt?.generation===generation){rt.dispatched=true;operation.fresh=false;rtSend({type:'starhub.connect',...operation.body});}}catch(error){if(current()&&rt?.generation===generation)endRealtime(error.message);}};
    socket.onmessage=e=>{if(rt?.generation!==generation)return;try{rtEvent(JSON.parse(e.data));}catch{endRealtime(t("无法处理实时数据，连接已结束。"));}};
    socket.onerror=()=>{if(rt?.generation===generation)endRealtime(t("实时连接失败，请检查 Key、余额、模型权限及网络。"));};
    socket.onclose=()=>{if(rt?.generation===generation)endRealtime(rt.failureMessage||(rt.idleEnded?t('登录状态已结束，实时语音可能中断；请重新登录原账户。'):t("实时连接已结束。")));};
    rt.connectTimer=setTimeout(()=>{if(rt?.generation===generation&&!rt.ready)endRealtime(t("实时连接超时，请检查网络或账户权限。"));},20000);
  }catch(e){
    if(!current()){if(operation?.fresh)StarProviderOperations.discardUnsent(operation);return;}
    if(operation?.fresh&&!rt)StarProviderOperations.discardUnsent(operation);
    endRealtime(e.message||t("浏览器无法建立实时音频连接。"));if(typeof showRealtimeRecovery==='function')showRealtimeRecovery(e,model);
  }finally{releasePreparation();if(current()){rtConnecting=false;realtimeUi();}}
};
async function beginRecording(){
  if(!rt?.ready||rt.idleEnded||rt.recording||rt.stopping)return;const state=rt;state.stopping=true;realtimeUi();
  try{
    if(!navigator.mediaDevices?.getUserMedia||!state.audio.audioWorklet)throw new Error(t('音频设备未能启动，请检查系统音频设置。'));
    const device=typeof selectedRealtimeMicrophone==='function'?selectedRealtimeMicrophone():'';
    const audioConstraints={channelCount:1,echoCancellation:true,noiseSuppression:true,autoGainControl:true};if(device)audioConstraints.deviceId={exact:device};
    const stream=await navigator.mediaDevices.getUserMedia({audio:audioConstraints,video:false});
    if(rt!==state){stream.getTracks().forEach(t=>t.stop());return;}
    if(typeof refreshRealtimeMicrophones==='function')refreshRealtimeMicrophones();
    const track=stream.getAudioTracks()[0];if(!track||track.readyState==='ended'){stream.getTracks().forEach(t=>t.stop());throw new Error(t('音频设备未能启动，请检查系统音频设置。'));}
    state.stream=stream;if(!state.workletLoaded){await state.audio.audioWorklet.addModule('audio-capture.js?v=20261117-realtime-input');state.workletLoaded=true;}
    if(rt!==state){stream.getTracks().forEach(t=>t.stop());return;}
    await state.audio.resume();if(rt!==state){stream.getTracks().forEach(t=>t.stop());return;}
    if(state.audio.state&&state.audio.state!=='running')throw new Error(t('音频设备未能启动，请检查系统音频设置。'));
    if(!state.transcription)interruptRealtime();
    state.source=state.audio.createMediaStreamSource(stream);const capture=state.capture=new AudioWorkletNode(state.audio,'starhub-capture');state.sentBytes=0;
    // These transcription models use manual turn detection. Commit bounded
    // segments while recording and the final segment when pausing.
    state.manualTranscription=state.transcription&&['gpt-live-transcribe','gpt-realtime-whisper'].includes(state.model?.id);
    state.uncommittedBytes=0;
    const failed=()=>{
      if(rt!==state||state.capture!==capture||!state.recording||state.stopping)return;
      clearTimeout(state.micTimer);state.stopping=true;state.source?.disconnect();capture.disconnect();state.stream?.getTracks().forEach(t=>t.stop());
      state.recording=false;state.stopping=false;state.speechActive=false;
      if(state.manualTranscription&&state.uncommittedBytes>=4800)try{rtSend({type:'input_audio_buffer.commit'});state.uncommittedBytes=0;}catch(error){endRealtime(error.message);return;}
      realtimeMicStatus('麦克风连接已中断，请重新开启。');realtimeState(t('麦克风已暂停'));realtimeUi();
    };
    track.addEventListener('ended',failed,{once:true});capture.onprocessorerror=failed;
    capture.port.onmessage=e=>{
      if(rt!==state||state.capture!==capture)return;
      if(e.data.audio&&(state.recording||state.stopping)){const bytes=new Uint8Array(e.data.audio);let binary='';for(const byte of bytes)binary+=String.fromCharCode(byte);
        const level=Number(e.data.level),now=performance.now();if(Number.isFinite(level)&&level>=0.0005)state.lastWeakMicSignal=now;if(Number.isFinite(level)&&level>=0.008)state.lastMicSignal=now;
        realtimeMicStatus(state.lastMicSignal&&now-state.lastMicSignal<2000?'麦克风输入正常':state.lastWeakMicSignal&&now-state.lastWeakMicSignal<2000?'麦克风声音很小，请检查输入音量或选择其他麦克风。':'麦克风已开启，尚未检测到声音');
        // A standalone partial worklet flush below 100 ms cannot be committed.
        if(!state.manualTranscription||state.uncommittedBytes||bytes.length>=4800)try{
          rtSend({type:'input_audio_buffer.append',audio:btoa(binary)});state.sentBytes+=bytes.length;
          if(state.manualTranscription){state.uncommittedBytes+=bytes.length;if(state.uncommittedBytes>=60*48000){rtSend({type:'input_audio_buffer.commit'});state.uncommittedBytes=0;}}
        }catch(error){endRealtime(error.message);}}
      if(e.data.flushed&&state.flushDone){state.flushDone();state.flushDone=null;}
    };
    state.source.connect(state.capture);state.capture.connect(state.audio.destination);state.recording=true;state.stopping=false;state.recordStart=performance.now();
    state.lastMicSignal=0;state.lastWeakMicSignal=0;realtimeMicStatus('麦克风已开启，尚未检测到声音');state.micTimer=setTimeout(()=>{if(rt===state&&state.recording&&!state.sentBytes)realtimeMicStatus('麦克风无音频数据，请检查浏览器或系统输入设备。');},4000);
    realtimeState(t('● 正在听'));realtimeNotice(t('语音会持续发送，停顿后自动回复；每段语音结束后显示识别文字，转写可能产生额外费用。')+(state.manualTranscription?' '+t('此转写模式请操作页面以保持登录；连续 10 分钟无操作后会话结束。'):''));realtimeUi();
  }catch(e){state.stream?.getTracks().forEach(t=>t.stop());state.source?.disconnect();state.capture?.disconnect();if(rt===state){state.stopping=false;realtimeNotice(e.name==='NotAllowedError'?t("麦克风权限未开启，请在浏览器地址栏允许本站使用麦克风。"):['NotFoundError','OverconstrainedError'].includes(e.name)?t('所选麦克风不可用，请选择其他设备后重试。'):e.message);realtimeUi();}}
}
async function finishRecording(){
  const state=rt;if(!state?.recording||state.stopping)return;state.stopping=true;realtimeUi();
  clearTimeout(state.micTimer);realtimeMicStatus('');
  state.source?.disconnect();state.stream?.getTracks().forEach(t=>t.stop());
  try{
    await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error(t("音频处理超时，请重新连接。"))),2000);state.flushDone=()=>{clearTimeout(timer);resolve();};state.capture.port.postMessage('flush');});
    if(rt!==state)return;
    state.capture.disconnect();state.recording=false;state.stopping=false;
    // Finish the current turn when the microphone is paused during speech.
    if(state.manualTranscription&&state.uncommittedBytes>=4800){rtSend({type:'input_audio_buffer.commit'});state.uncommittedBytes=0;}
    else if(state.speechActive&&!state.transcription){state.speechActive=false;rtSend({type:'input_audio_buffer.commit'});rtSend({type:'response.create'});}
    realtimeState(t('麦克风已暂停'));realtimeUi();
  }catch(e){endRealtime(e.message);}
}
$('recordRealtime').onclick=()=>rt?.recording?finishRecording():beginRecording();$('interruptRealtime').onclick=interruptRealtime;
async function settleRealtimeReceipt(id,owner){
  for(let attempt=0;attempt<6;attempt++){
    if(!owner())return;
    try{const result=await StarProviderOperations.query(id);if(!owner())return;if(['COMPLETED','COMPLETED_NO_RESULT'].includes(result.operationState)){StarProviderOperations.acknowledge(id);return;}}catch{}
    await new Promise(resolve=>setTimeout(resolve,500));
  }
  if(owner())StarProviderOperations.markUnknown(id);
}
function endRealtime(message=t("实时连接已结束。"),normal=false){
  const state=rt;finishRealtimeMapReply(state);rt=null;rtGeneration++;rtConnecting=false;
  if(state){clearTimeout(state.recordTimer);clearTimeout(state.connectTimer);clearTimeout(state.micTimer);realtimeMicStatus('');state.stream?.getTracks().forEach(t=>t.stop());state.source?.disconnect();state.capture?.disconnect();stopPlayback(state);
    const orderly=normal&&state.ready&&state.socket?.readyState===WebSocket.OPEN&&!state.failureMessage;
    for(const message of state.transcripts.values()){if(message.mapTranscriptPending){delete message.mapTranscriptPending;message.mapTranscriptFailed=true;}if(message.transcriptPending){message.content=t('语音转写尚未完成。');delete message.transcriptPending;}}
    if(orderly){const owner=StarProviderOperations.ownerGuard();state.socket.close(1000,'Session ended');settleRealtimeReceipt(state.operationId,owner);}
    else{if(!state.dispatched&&state.operation){if(state.operation.fresh)StarProviderOperations.discardUnsent(state.operation);}else if(state.operationId)StarProviderOperations.markUnknown(state.operationId);state.socket?.close();}
    state.audio.close().catch(()=>{});
  }
  realtimeState(t("未连接"));realtimeNotice(message);realtimeUi();scheduleHistorySave();
}
$('endRealtime').onclick=()=>endRealtime(t('实时连接已结束。'),true);window.addEventListener('pagehide',()=>endRealtime());
document.addEventListener('starhub:realtime-text',async()=>{
  if(!rt?.ready||rt.transcription){realtimeNotice(t("请先连接语音对话模型；转写模型只接收语音。"));return;}
  if(rt.stopping||rt.textPending){realtimeNotice(t('请等待当前输入处理完成。'));return;}
  const text=$('prompt').value.trim();if(!text)return;const state=rt;state.textPending=true;
  try{const image=await readInputFile();if(rt!==state)return;interruptRealtime();const content=[{type:'input_text',text}];if(image)content.push({type:'input_image',image_url:image});rtSend({type:'conversation.item.create',item:{type:'message',role:'user',content}});rt.acceptAudio=true;rtSend({type:'response.create'});addRtMessage('user',text);$('prompt').value='';if(typeof resetMemoryInputTracking==='function')resetMemoryInputTracking();realtimeState(t("等待回复"));}catch(e){realtimeNotice(e.message);}finally{state.textPending=false;}
});
document.addEventListener('starhub:modelchanged',()=>{
  if(rt||rtConnecting)endRealtime();const m=selectedModel();
  $('realtimeTitle').textContent=m?.outputs.includes('audio')?t("实时语音对话"):t("实时语音转写");
  if(m?.categories.includes('realtime')){$('prompt').disabled=!m.outputs.includes('audio');realtimeUi();}
});
