'use strict';
const batchJobs=new Map();let batchBusy=false,batchCursor='',batchPollCursor=0;
const batchStates={validating:t("正在校验"),in_progress:t("执行中"),finalizing:t("整理结果"),completed:t("已完成"),failed:t("失败"),expired:t("已过期"),cancelling:t("取消中"),cancelled:t("已取消")};
const terminalBatch=s=>['completed','failed','expired','cancelled'].includes(s);
function batchNotice(text){$('batchNotice').textContent=errorText(text);}
function lockBatch(value){batchBusy=value;for(const id of ['submitBatch','loadBatches','recoverBatch','moreBatches'])$(id).disabled=value;}
async function batchCall(action,extra={}){
  const key=keys.get('openai')||($('provider').value==='openai'?$('apiKey').value.trim():'');
  if(!key){openSetup();throw new Error(t("请填写 OpenAI API Key。"));}
  const response=await fetch('api/batch',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify({provider:'openai',apiKey:key,action,...extra}),signal:AbortSignal.timeout(110000)});
  const result=await response.json();if(!response.ok)throw new Error(result.error||t("批处理请求失败。"));return result;
}
function rememberBatch(job){const previous=batchJobs.get(job.id);batchJobs.set(job.id,{...previous,...job,results:job.results?.length?job.results:previous?.results||[]});}
function renderBatches(){
  $('batchJobs').replaceChildren();
  for(const job of batchJobs.values()){
    const card=element('article',undefined,'batch-job'),top=element('div',undefined,'batch-job-title');
    top.append(element('strong',batchStates[job.status]||job.status),element('code',job.id));
    card.append(top,element('p',t("{0} · 共 {1} 条 · 完成 {2} · 失败 {3}",job.model||'',job.counts?.total||0,job.counts?.completed||0,job.counts?.failed||0)));
    const actions=element('div',undefined,'panel-actions');const refresh=element('button',t("刷新此任务"));refresh.disabled=batchBusy;refresh.onclick=()=>refreshBatch(job.id);actions.append(refresh);
    const copy=element('button',t("复制任务 ID"));copy.onclick=()=>navigator.clipboard.writeText(job.id).then(()=>batchNotice(t("任务 ID 已复制。"))).catch(()=>batchNotice(t("请直接选中任务 ID 复制。")));actions.append(copy);
    if(job.results?.length){const download=element('button',t("导出结果 JSON"));download.onclick=()=>downloadJson(job,`Star-Hub-${job.id}.json`);actions.append(download);}
    card.append(actions);if(job.error)card.append(element('p',errorText(job.error),'error'));
    for(const result of job.results||[]){const details=element('details');details.append(element('summary',`${result.id} · ${result.error?t("失败"):t("结果")}`),element('pre',result.error||result.text||(result.vectors?JSON.stringify(result.vectors):t("无文本结果"))));card.append(details);}
    $('batchJobs').append(card);
  }
}
function downloadJson(value,name){const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));const a=element('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
async function refreshBatch(id,automatic=false){
  if(batchBusy)return;lockBatch(true);
  try{const result=await batchCall('get',{jobId:id});rememberBatch(result.job);if(!automatic)batchNotice(t("任务状态已更新。"));}
  catch(e){batchNotice(e.name==='TimeoutError'?t("查询超时，任务仍可稍后查询。"):e.message);}
  finally{lockBatch(false);renderBatches();}
}
$('submitBatch').onclick=async()=>{
  if(batchBusy||busy||publicInsecure)return;const model=selectedModel();if(!model?.batch)return;
  const inputs=$('batchInputs').value.split('\n').map(s=>s.trim()).filter(Boolean);
  if(!inputs.length||inputs.length>100){batchNotice(t("请输入 1–100 条任务，每行一条。"));return;}
  lockBatch(true);setBusy(true);batchNotice(t("正在提交；请勿重复点击或刷新页面。"));
  try{const result=await batchCall('submit',{model:model.id,inputs,effort:$('effort').value,parameters:await collectParameters()});rememberBatch(result.job);$('batchInputs').value='';batchNotice(t("提交成功。平台执行期间每 30 秒轮流检查一项任务；刷新页面后可填写 Key 读取平台任务。"));}
  catch(e){batchNotice((e.name==='TimeoutError'?t("提交响应超时。"):e.message)+t(" 若无法确认是否提交成功，请先读取平台任务，避免重复计费。"));}
  finally{lockBatch(false);setBusy(false);renderBatches();}
};
async function loadBatchPage(append){
  if(batchBusy)return;lockBatch(true);
  try{const r=await batchCall('list',{after:append?batchCursor:''});r.jobs.forEach(rememberBatch);batchCursor=r.after;$('moreBatches').hidden=!r.hasMore;batchNotice(t("已从平台恢复任务。点击刷新可读取每项任务结果。"));}
  catch(e){batchNotice(e.message);}finally{lockBatch(false);renderBatches();}
}
$('loadBatches').onclick=()=>loadBatchPage(false);$('moreBatches').onclick=()=>loadBatchPage(true);
$('recoverBatch').onclick=()=>refreshBatch($('batchJobId').value.trim());
setInterval(()=>{if(document.hidden||batchBusy||!keys.get('openai'))return;const pending=[...batchJobs.values()].filter(j=>!terminalBatch(j.status));if(pending.length)refreshBatch(pending[batchPollCursor++%pending.length].id,true);},30000);

let rt=null,rtGeneration=0;
function realtimeState(text){$('realtimeState').textContent=text;}
function realtimeNotice(text){$('realtimeNotice').textContent=errorText(text);}
function realtimeUi(){
  const active=Boolean(rt); realtimeActive=active;refreshConnectionSummary(); realtimeConnected=Boolean(rt?.ready);for(const id of ['provider','model','modelSearch','browseModels','modelDetails','modelSettings','task','apiKey','newChat','undoDelete'])$(id).disabled=active||busy;
  if($('voice'))$('voice').disabled=active;
  $('connectRealtime').disabled=active||busy||publicInsecure;$('endRealtime').disabled=!active;
  $('recordRealtime').disabled=!rt?.ready||rt?.stopping;$('interruptRealtime').disabled=!rt?.ready||rt.transcription;
  $('recordRealtime').textContent=rt?.recording?t("发送本段"):t("开始说话");
  $('send').disabled=busy||!ready||publicInsecure||($('task').value==='realtime'&&(!rt?.ready||rt.transcription));
  document.querySelectorAll('.history button').forEach(b=>b.disabled=active||busy);
}
function rtSend(event){if(!rt?.socket||rt.socket.readyState!==WebSocket.OPEN)throw new Error(t("实时连接尚未就绪。"));if(rt.socket.bufferedAmount>(event.type==='input_audio_buffer.append'?512000:8*1024*1024)){endRealtime(t("网络发送拥堵，已停止连接。"));throw new Error(t("网络发送拥堵。"));}rt.socket.send(JSON.stringify(event));}
function stopPlayback(state=rt){if(!state)return;for(const node of state.playing){try{node.stop();}catch{}}state.playing.clear();state.nextPlay=state.audio.currentTime;}
function playAudio(base64,event){
  if(!rt)return;const raw=atob(base64);if(raw.length%2)return;const array=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)array[i]=raw.charCodeAt(i);
  const view=new DataView(array.buffer),buffer=rt.audio.createBuffer(1,array.length/2,24000),channel=buffer.getChannelData(0);
  for(let i=0;i<channel.length;i++)channel[i]=view.getInt16(i*2,true)/32768;
  if(rt.nextPlay-rt.audio.currentTime>20){endRealtime(t("语音播放积压，已停止连接，请检查设备或网络。"));return;}
  if(rt.audioItem!==event.item_id){rt.audioItem=event.item_id;rt.audioStart=Math.max(rt.nextPlay,rt.audio.currentTime);rt.audioDuration=0;}
  rt.audioDuration+=buffer.duration;rt.contentIndex=event.content_index||0;
  const node=rt.audio.createBufferSource();node.buffer=buffer;node.connect(rt.audio.destination);const state=rt;state.playing.add(node);node.onended=()=>state.playing.delete(node);
  const start=Math.max(rt.nextPlay,rt.audio.currentTime);node.start(start);rt.nextPlay=start+buffer.duration;
}
function interruptRealtime(){
  if(!rt?.ready||rt.transcription)return;
  if(rt.currentResponse)rt.ignoredResponses.add(rt.currentResponse);if(rt.responding)rtSend({type:'response.cancel'});
  if(rt.audioItem){const ms=Math.floor(Math.max(0,Math.min(rt.audioDuration,rt.audio.currentTime-rt.audioStart))*1000);rtSend({type:'conversation.item.truncate',item_id:rt.audioItem,content_index:rt.contentIndex,audio_end_ms:ms});}
  stopPlayback();rt.audioItem=null;rt.responding=false;rt.acceptAudio=false;realtimeState(t("已打断，等待输入"));
}
function addRtMessage(role,text){
  if(!rt)return;const message={role,content:text,providerName:rt.model.providerName,model:rt.model.id,seconds:'realtime'};
  if(!current.messages.length)current.title=t("实时语音 · ")+rt.model.name;
  current.messages.push(message);render();realtimeUi();return message;
}
function rtEvent(event){
  if(!rt)return;
  if(event.type==='starhub.ready'){rt.ready=true;realtimeState(rt.transcription?t("转写已连接"):t("语音已连接"));realtimeNotice(t("点击开始说话后才会使用麦克风。闲置 2 分钟断开，会话最长 30 分钟。"));realtimeUi();return;}
  if(event.type==='starhub.error'){realtimeNotice(event.message);if(!rt.ready)endRealtime(event.message);return;}
  if(event.response_id&&rt.ignoredResponses.has(event.response_id))return;
  if(event.type==='response.created'){rt.currentResponse=event.response?.id;rt.responding=true;rt.acceptAudio=true;rt.assistant=null;realtimeState(t("模型正在回复"));return;}
  if(['response.output_audio.delta','response.audio.delta'].includes(event.type)&&rt.acceptAudio){playAudio(event.delta,event);return;}
  if(['response.output_audio_transcript.delta','response.audio_transcript.delta','response.output_text.delta','response.text.delta'].includes(event.type)){
    if(!rt.assistant)rt.assistant=addRtMessage('assistant','');rt.assistant.content+=event.delta||'';queueRender();return;
  }
  if(event.type.startsWith('conversation.item.input_audio_transcription.')){
    const key=event.item_id||'current';let message=rt.transcripts.get(key);if(!message){message=addRtMessage('assistant','');rt.transcripts.set(key,message);}
    if(event.type.endsWith('.completed'))message.content=event.transcript||event.text||message.content;
    else if(event.type.endsWith('.intermediate'))message.content=event.transcript||event.text||event.delta||message.content;
    else if(event.type.endsWith('.delta'))message.content+=event.delta||'';
    else if(event.type.endsWith('.failed'))message.content=t("本段转写失败。");
    queueRender();return;
  }
  if(event.type==='response.done'){if(rt.ignoredResponses.has(event.response?.id))return;rt.responding=false;realtimeState(event.response?.status==='failed'?t("本次回复失败"):t("等待输入"));}
}
$('connectRealtime').onclick=async()=>{
  if(rt||busy||publicInsecure)return;const model=selectedModel();if(!model?.categories.includes('realtime'))return;
  const key=$('apiKey').value.trim();if(!key){realtimeNotice(t("请填写 OpenAI API Key。"));openSetup();return;}
  let parameters;try{parameters=await collectParameters();}catch{return;}
  const generation=++rtGeneration;
  try{
    const audio=new AudioContext({sampleRate:24000});rt={model,audio,generation,ready:false,recording:false,playing:new Set(),nextPlay:0,transcripts:new Map(),ignoredResponses:new Set(),transcription:!model.outputs.includes('audio'),acceptAudio:true};
    realtimeState(t("正在连接"));realtimeNotice('');realtimeUi();await audio.resume();
    if(!rt||rt.generation!==generation)return;
    const url=new URL('realtime',location.href);url.protocol=location.protocol==='https:'?'wss:':'ws:';
    const socket=new WebSocket(url);rt.socket=socket;
    socket.onopen=()=>{if(rt?.generation===generation)rtSend({type:'starhub.connect',provider:model.provider,model:model.id,apiKey:key,voice:$('voice')?.value||'',parameters});};
    socket.onmessage=e=>{if(rt?.generation!==generation)return;try{rtEvent(JSON.parse(e.data));}catch{endRealtime(t("无法处理实时数据，连接已结束。"));}};
    socket.onerror=()=>{if(rt?.generation===generation)endRealtime(t("实时连接失败，请检查 Key、余额、模型权限及网络。"));};
    socket.onclose=()=>{if(rt?.generation===generation)endRealtime(t("实时连接已结束。"));};
    rt.connectTimer=setTimeout(()=>{if(rt?.generation===generation&&!rt.ready)endRealtime(t("实时连接超时，请检查网络或账户权限。"));},20000);
  }catch(e){endRealtime(e.message||t("浏览器无法建立实时音频连接。"));}
};
async function beginRecording(){
  if(!rt?.ready||rt.recording||rt.stopping)return;const state=rt;state.stopping=true;realtimeUi();
  try{
    const stream=await navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true},video:false});
    if(rt!==state){stream.getTracks().forEach(t=>t.stop());return;}
    state.stream=stream;if(!state.workletLoaded){await state.audio.audioWorklet.addModule('audio-capture.js');state.workletLoaded=true;}
    if(rt!==state){stream.getTracks().forEach(t=>t.stop());return;}
    if(!state.transcription)interruptRealtime();
    state.source=state.audio.createMediaStreamSource(stream);state.capture=new AudioWorkletNode(state.audio,'starhub-capture');state.sentBytes=0;
    state.capture.port.onmessage=e=>{
      if(rt!==state)return;
      if(e.data.audio){const bytes=new Uint8Array(e.data.audio);let binary='';for(const byte of bytes)binary+=String.fromCharCode(byte);state.sentBytes+=bytes.length;try{rtSend({type:'input_audio_buffer.append',audio:btoa(binary)});}catch(error){endRealtime(error.message);}}
      if(e.data.flushed&&state.flushDone){state.flushDone();state.flushDone=null;}
    };
    state.source.connect(state.capture);state.capture.connect(state.audio.destination);state.recording=true;state.stopping=false;state.recordStart=performance.now();
    state.recordTimer=setTimeout(()=>{if(rt===state&&state.recording)finishRecording();},60000);
    realtimeState(t("● 正在录音并上传"));realtimeNotice(t("说完点击“发送本段”。单段最多 60 秒；结束连接会释放麦克风。"));realtimeUi();
  }catch(e){state.stream?.getTracks().forEach(t=>t.stop());state.source?.disconnect();state.capture?.disconnect();if(rt===state){state.stopping=false;realtimeNotice(e.name==='NotAllowedError'?t("麦克风权限未开启，请在浏览器地址栏允许本站使用麦克风。"):e.message);realtimeUi();}}
}
async function finishRecording(){
  const state=rt;if(!state?.recording||state.stopping)return;state.stopping=true;realtimeUi();clearTimeout(state.recordTimer);
  state.source?.disconnect();state.stream?.getTracks().forEach(t=>t.stop());
  try{
    await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error(t("音频处理超时，请重新连接。"))),2000);state.flushDone=()=>{clearTimeout(timer);resolve();};state.capture.port.postMessage('flush');});
    if(rt!==state)return;
    state.capture.disconnect();state.recording=false;state.stopping=false;
    if(state.sentBytes<4800){endRealtime(t("本段录音不足 0.1 秒，请重新连接后再说一段。"));return;}
    const seconds=(state.sentBytes/48000).toFixed(1);addRtMessage('user',t("[语音片段 {0} 秒]",seconds));rtSend({type:'input_audio_buffer.commit'});
    realtimeState(state.transcription?t("正在转写"):t("等待语音回复"));if(!state.transcription){state.acceptAudio=true;rtSend({type:'response.create'});}realtimeUi();
  }catch(e){endRealtime(e.message);}
}
$('recordRealtime').onclick=()=>rt?.recording?finishRecording():beginRecording();$('interruptRealtime').onclick=interruptRealtime;
function endRealtime(message=t("实时连接已结束。")){
  const state=rt;rt=null;rtGeneration++;
  if(state){clearTimeout(state.recordTimer);clearTimeout(state.connectTimer);state.stream?.getTracks().forEach(t=>t.stop());state.source?.disconnect();state.capture?.disconnect();stopPlayback(state);state.socket?.close();state.audio.close().catch(()=>{});}
  realtimeState(t("未连接"));realtimeNotice(message);realtimeUi();scheduleHistorySave();
}
$('endRealtime').onclick=()=>endRealtime();window.addEventListener('pagehide',()=>endRealtime());
document.addEventListener('starhub:realtime-text',async()=>{
  if(!rt?.ready||rt.transcription){realtimeNotice(t("请先连接语音对话模型；转写模型只接收语音。"));return;}
  if(rt.recording||rt.stopping||rt.textPending){realtimeNotice(t("请先发送本段录音，或等待当前输入发送。"));return;}
  const text=$('prompt').value.trim();if(!text)return;const state=rt;state.textPending=true;
  try{const image=await readInputFile();if(rt!==state)return;interruptRealtime();const content=[{type:'input_text',text}];if(image)content.push({type:'input_image',image_url:image});rtSend({type:'conversation.item.create',item:{type:'message',role:'user',content}});rt.acceptAudio=true;rtSend({type:'response.create'});addRtMessage('user',text);$('prompt').value='';realtimeState(t("等待回复"));}catch(e){realtimeNotice(e.message);}finally{state.textPending=false;}
});
document.addEventListener('starhub:modelchanged',()=>{
  if(rt)endRealtime();const m=selectedModel();$('batchModel').textContent=m?.batch?m.name:'';
  $('realtimeTitle').textContent=m?.outputs.includes('audio')?t("实时语音对话"):t("实时语音转写");
  if(m?.categories.includes('realtime')){$('prompt').disabled=!m.outputs.includes('audio');realtimeUi();}
});
