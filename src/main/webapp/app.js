'use strict';
const $ = id => document.getElementById(id);
let providers = [], catalog, rawCatalog, busy = false, ready = false, realtimeActive = false, realtimeConnected = false;
let libraryPage = 0, detailModel;
const effortNames = {none:t("关闭"),minimal:t("最低"),low:t("低"),medium:t("中"),high:t("高"),xhigh:t("超高"),max:t("最高")};
const costNames = {"free-key":t("免费模型 · 需 Key"),paid:t("按量计费 · 需 Key"),account:t("费用 / 免费额度取决于账户"),keyless:t("免费 · 无需 Key")};
let chats = [], current, cloudHydrating=false, chatActivation=0;
const restoredSelection=StarStore.selection();
const keys = new Map(), settings = new Map(Object.entries(restoredSelection.models&&typeof restoredSelection.models==='object'?restoredSelection.models:{}));
const directionSettings = new Map(Object.entries(restoredSelection.directions&&typeof restoredSelection.directions==='object'?restoredSelection.directions:{}));
const storedModelControls=StarStore.preference('modelControls',{});
const modelControls=new Map(Object.entries(storedModelControls&&typeof storedModelControls==='object'&&!Array.isArray(storedModelControls)?storedModelControls:{}));
const storedWebSearch=StarStore.preference('webSearch',null);
if(typeof storedWebSearch==='boolean')$('webSearch').checked=storedWebSearch;
const providerRetries = new Map(); // Frozen requests remain in memory only; credentials never enter chat history.
const publicInsecure = location.protocol !== 'https:' && !['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
function showError(message) { $('error').textContent = errorText(message); $('error').hidden = !message; }
function newChat() {
  if (busy || realtimeActive) return;
  if (current) current.draft = $('prompt').value;
  let blank = chats.find(chat => chat.messages.length === 0);
  if (!blank) {
    blank = {id: crypto.randomUUID(), title: t("新的对话"), messages: [], draft: ''};
    chats.unshift(blank);
  }
  activateChat(blank);
}
function activateChat(chat) {
  if(realtimeActive)return;
  const activation=++chatActivation,owner=accountEmail,ownerRevision=accountIdentityRevision;
  if (current) current.draft = $('prompt').value;
  if(current&&current!==chat){invalidateGeneratedPreviews();releaseGeneratedPreview(current);}
  current = chat;
  $('prompt').value = chat.draft || '';
  if(typeof resetMemoryInputTracking==='function')resetMemoryInputTracking();
  render(); showError(''); $('prompt').focus({preventScroll:true});
  if(accountEmail&&chat.hydrated===false&&!chat.hydrating){cloudHydrating=true;chat.hydrating=true;updateConnection();hydrateChat(chat).then(()=>{if(current===chat&&accountEmail===owner&&accountIdentityRevision===ownerRevision)scheduleLatestGeneratedPreview(chat,owner,ownerRevision,chatActivation);}).catch(e=>{if(current===chat&&accountEmail===owner&&accountIdentityRevision===ownerRevision)showError(e.message);}).finally(()=>{chat.hydrating=false;if(current===chat){cloudHydrating=false;recoverLatestProviderReply(chat);}updateConnection();queueRender();});}else{cloudHydrating=Boolean(chat.hydrating);if(!chat.hydrating){scheduleLatestGeneratedPreview(chat,owner,ownerRevision,activation);recoverLatestProviderReply(chat);}}
}
async function deleteChat(chat) {
  if (busy || realtimeActive || !chats.includes(chat)) return;
  if (!window.confirm(t('确定删除对话“{0}”吗？删除后无法撤销。',chat.title||t('新的对话')))) return;
  const owner=accountEmail,epoch=accountIdentityRevision,index=chats.indexOf(chat);
  if (current) current.draft = $('prompt').value;
  const accountControls=['accountButton','accountLogout'].map(id=>$(id)).filter(Boolean).map(control=>({control,disabled:control.disabled}));
  clearTimeout(historySaveTimer);chat.deleting=true;setBusy(true);for(const {control} of accountControls)control.disabled=true;let failure,guestFlushUncertain=false;
  try{
    if(owner)await deleteCloudChat(chat);
    else{
      const remaining=chats.filter(item=>item!==chat),next=current===chat?remaining[Math.min(index,remaining.length-1)]:current;
      if(!StarStore.saveHistory(remaining,next?.id))throw new Error(t('无法保存删除结果，对话仍保留。'));
      guestFlushUncertain=true;await StarStore.flushConfirmed();guestFlushUncertain=false;
    }
    if(accountEmail!==owner||accountIdentityRevision!==epoch||!chats.includes(chat))return;
    if(current===chat)invalidateGeneratedPreviews();
    releaseGeneratedPreview(chat);chats.splice(chats.indexOf(chat),1);
    if(current===chat){current=null;if(!chats.length)chats.push({id:crypto.randomUUID(),title:t('新的对话'),messages:[],draft:''});activateChat(chats[Math.min(index,chats.length-1)]);}
    else render();
  }catch(error){if(accountEmail===owner&&accountIdentityRevision===epoch)failure=guestFlushUncertain||error.code==='OPERATION_PENDING'?new Error(t('删除结果尚未确认，对话暂时保留在当前页面；请稍后检查或重试。')):error;}
  finally{delete chat.deleting;for(const {control,disabled} of accountControls)control.disabled=disabled;setBusy(false);if(accountEmail===owner&&accountIdentityRevision===epoch&&typeof scheduleHistorySave==='function')scheduleHistorySave();}
  if(failure)showError(failure.message||t('删除未确认，对话仍保留。'));
}
function updateConnection() {
  const p = providers.find(p => p.id === $('provider').value);
  const configured = Boolean(p && $('model').value.trim() && $('apiKey').value.trim());
  $('connectionState').textContent = configured ? t("已填写") : t("待配置");
  $('activeProvider').textContent = p?.name || 'Star Hub';
  $('modelHint').textContent = p ? `${p.name} · ${$('model').value || t("选择模型")}` : t("先在左侧连接一个模型");
  $('send').disabled = busy || cloudHydrating || !ready || publicInsecure || !selectedModel() || selectedModel().execution === 'external' || ($('task').value==='realtime'&&(!realtimeConnected||!selectedModel().outputs.includes('audio')));
}
function saveSettings() {
  const id = $('provider').value;
  keys.set(id, $('apiKey').value);
  if(!StarStore.saveKeys(keys))showError(t('无法保存临时会话，请检查浏览器存储空间。'));
  if(ready&&!StarStore.saveSelection(id,settings,directionSettings))showError(t('无法保存设置，请检查浏览器存储空间。'));
  updateConnection();if(typeof refreshConnectionSummary==='function')refreshConnectionSummary();
}
function modelControlKey(model){return `${model.provider}|${model.id}`;}
function saveModelControls(){
  const model=selectedModel();if(!ready||!model)return;
  modelControls.set(modelControlKey(model),{task:$('task').value,effort:$('effort').value});
  if(!StarStore.write('modelControls',Object.fromEntries(modelControls)))showError(t('无法保存设置，请检查浏览器存储空间。'));
}
function selectedModel() { return catalog?.models.find(m => m.provider === $('provider').value && m.id === $('model').value); }
function validatedProviderContext(value,provider,model) {
  if(provider!=='moonshot'||typeof model!=='string'||!model||!value||typeof value!=='object'||value.provider!==provider||value.model!==model||typeof value.token!=='string'||!value.token.length||value.token.length>140000||!/^[A-Za-z0-9_-]+$/.test(value.token))return undefined;
  return {provider,model,token:value.token};
}
function outgoingHistoryMessage(message,provider,model) {
  const out={role:message.role,content:message.content,...(message.image?{image:message.image}:{}),...(message.images?.length?{images:message.images}:{})};
  if(message.role==='assistant') {
    if(typeof message.provider==='string'&&message.provider.length<=200)out.provider=message.provider;
    if(typeof message.model==='string'&&message.model.length<=200)out.model=message.model;
    const context=message.provider===provider&&message.model===model?validatedProviderContext(message.providerContext,provider,model):undefined;
    if(context)out.providerContext=context;
  }
  return out;
}
function primaryDirection(model) {
  if(model.batch||model.categories.includes('embeddings'))return undefined;
  if(model.categories.includes('chat'))return 'general';
  if(model.categories.includes('image'))return 'image';
  if(model.categories.includes('video'))return 'video';
  if(model.categories.some(x=>['speech','transcription','realtime'].includes(x)))return 'voice';
  return undefined;
}
function directionModels(direction) {
  return catalog.models.filter(m=>primaryDirection(m)===direction);
}
function modelTierLabel(model) { return model.selectionTier==='flagship'?t('旗舰'):model.selectionTier==='secondary'?t('次旗舰'):''; }
function modelOptionLabel(model) { return [model.name,modelTierLabel(model),model.accessLabel].filter(Boolean).join(' · '); }
function preferredModelId(provider,direction,models,saved) {
  const usable=models.filter(m=>m.execution!=='external');
  const byId=value=>usable.find(m=>m.id===value||m.uid===value);
  const preferred=catalog.featured?.preferredDefault?.[provider]?.[direction];
  const primary=catalog.featured?.primary?.[provider]?.[direction];
  return (byId(saved)||byId(preferred)||byId(primary)||[...usable].sort((a,b)=>a.id.localeCompare(b.id))[0])?.id;
}
function rememberModelChoice(model) {
  const direction=model&&primaryDirection(model);
  if(!direction)return;
  settings.set(model.provider,model.id);
  directionSettings.set(`${model.provider}|${direction}`,model.id);
}

function updateAvailableDirections() {
  for(const id of ['modelDirection','libraryDirection']) {
    const control=$(id);
    const provider=id==='modelDirection'?$('provider').value:'';
    for(const option of control.options) { const available=directionModels(option.value).some(m=>(!provider||m.provider===provider)&&m.execution!=='external'); option.hidden=!available; option.disabled=!available; }
    if(!control.value||control.selectedOptions[0]?.disabled)control.value=Array.from(control.options).find(option=>option.value==='general'&&!option.disabled)?.value||Array.from(control.options).find(option=>!option.disabled)?.value||'';
  }
}
function selectProvider(wanted) {
  const p = providers.find(p => p.id === $('provider').value);
  if(typeof wanted==='string'){const restored=catalog.models.find(m=>m.provider===p.id&&m.id===wanted);if(restored&&primaryDirection(restored))$('modelDirection').value=primaryDirection(restored);}
  updateAvailableDirections();
  const direction=$('modelDirection').value;
  const models = directionModels(direction).filter(m => m.provider === p.id);
  $('model').replaceChildren();
  const groups = new Map();
  for (const m of models) {
    const name = `${m.vendorName} · ${m.family}`;
    if (!groups.has(name)) { const g = document.createElement('optgroup'); g.label = name; groups.set(name,g); $('model').append(g); }
    groups.get(name).append(new Option(modelOptionLabel(m),m.id));
  }
  const choice = typeof wanted === 'string' && models.some(m=>m.id===wanted) ? wanted : (directionSettings.get(`${p.id}|${direction}`)||settings.get(p.id));
  const selected = preferredModelId(p.id,direction,models,choice);
  if (selected) $('model').value = selected;
  $('model').disabled = !models.length;
  $('apiKey').value = keys.get(p.id) || '';
  if(selected)rememberModelChoice(selectedModel());
  showError('');configureModel();
}
function refreshReasoningControl() {
  const control=$('reasoningControl'), effort=$('effort'), model=selectedModel();
  const supported=$('task').value==='chat'&&model?.execution!=='external'&&Boolean(model?.reasoning.length);
  control.hidden=!supported;
  effort.disabled=!supported||busy||realtimeActive;
  control.dataset.custom=String(supported&&effort.value!=='default');
  effort.title=t('推理强度')+' · '+(effort.selectedOptions[0]?.textContent||t('平台默认'));
}
function configureModel() {
  const m = selectedModel();
  $('task').replaceChildren(); $('effort').replaceChildren(new Option(t("平台默认"),'default'));
  if (!m) { document.dispatchEvent(new CustomEvent('starhub:modelchanged')); refreshReasoningControl(); for(const id of ['realtimePanel','taskControls'])$(id).hidden=true; $('attachChatFile').hidden=true; $('chatAttachments').hidden=true; $('chatForm').hidden=false; $('prompt').disabled=false; updateConnection(); return; }
  const tasks=m.categories.filter(c => c!=='vision' && c!=='embeddings');
  $('task').replaceChildren(...tasks.map(c => new Option(catalog.categories[c],c)));
  $('effort').append(...m.reasoning.map(e => new Option(uiText(effortNames[e]) || e,e)));
  const savedControls=modelControls.get(modelControlKey(m));
  if(savedControls&&tasks.includes(savedControls.task))$('task').value=savedControls.task;
  if(savedControls&&m.reasoning.includes(savedControls.effort))$('effort').value=savedControls.effort;
  configureTask(); saveSettings();
}
function refreshSearchControls() {
  const m=selectedModel(), task=$('task').value;
  const realtimeSearch=task==='realtime'&&m?.provider==='openai'&&m.outputs.includes('audio');
  const searchSupported=realtimeSearch||(task==='chat' && ((m?.provider==='openai' && /^(gpt-[56]|o[134])/.test(m.id)) || m?.provider==='anthropic' || m?.provider==='gemini' || (m?.provider==='deepseek' && m.id==='deepseek-flash') || m?.provider==='moonshot' || (m?.provider==='qwen' && ['qwen4-max','qwen3.8-flash'].includes(m.id))));
  $('searchControls').hidden=task!=='chat'&&!realtimeSearch; $('webSearch').disabled=!searchSupported||Boolean(realtimeSearch&&realtimeActive);
  $('searchLabel').textContent=language==='zh'?'联网搜索':'Web search';
  $('searchHelp').textContent=realtimeSearch?(language==='zh'?'语音模型可按需调用联网搜索，可能产生额外费用。连接期间不能更改。':'The live model can search when needed; extra charges may apply. Set this before connecting.'):(language==='zh'?(searchSupported?(m.provider==='qwen'?'模型按需搜索，可能额外收费；当前接口不返回来源列表。':(m.provider==='moonshot'||m.provider==='deepseek'&&m.id==='deepseek-flash'?'每次发送都会搜索，可能额外收费。':'模型按需搜索，可能产生额外费用。')):'此模型的联网搜索尚未接入。'):(searchSupported?(m.provider==='qwen'?'Search may cost extra; this interface does not return source lists.':(m.provider==='moonshot'||m.provider==='deepseek'&&m.id==='deepseek-flash'?'Each message triggers a paid search.':'The model may search when needed. Searches can incur extra charges.')):'Web search is not integrated for this model.'));
}
let realtimeRecognitionLanguage='auto';
function configureTask() {
  const m=selectedModel(), task=$('task').value;
  refreshSearchControls();refreshReasoningControl();
  $('taskControls').hidden=task==='chat'; $('attachChatFile').hidden=task!=='chat'||!m.inputs.includes('image'); $('chatForm').hidden=task==='realtime'&&m.id==='gpt-live-1'; $('realtimePanel').hidden=task!=='realtime';   $('generationOptions').replaceChildren(); $('inputFile').value=''; $('prompt').disabled=busy;
  $('fileLabel').hidden=!(['image','video','3d','transcription','audiofx'].includes(task)||(['chat','realtime'].includes(task)&&m.inputs.includes('image')));
  $('inputFile').multiple=(task==='chat'&&m.inputs.includes('image'))||(task==='image'&&(m.referenceLimits?.image||1)>1);
  $('fileLabel').firstChild.textContent=task==='image'?t("参考图片（{0}–{1} 张，附件合计最多 4 MB）",m.referenceLimits?.minImages||0,m.referenceLimits?.image||1):task==='video'?t("首帧图片（附件合计最多 4 MB）"):t("参考文件（最多 4 MB）");
  if(['image','video','3d'].includes(task))$('fileLabel').hidden=!(m.referenceLimits?.image>0);
  $('inputFile').accept=['transcription','audiofx'].includes(task)?'audio/*,.mp4,.m4a':'image/png,image/jpeg,image/webp';
  renderChatAttachments();
  $('documentsLabel').hidden=task!=='rerank'; $('questionLabel').hidden=task!=='decisions';
  const helps={chat:t("可附一张 PNG、JPEG 或 WebP 图片。图片保留在本页对话上下文中；更换为不支持图片的模型时请新建对话。"),image:t("描述想生成的图片，也可上传参考图。部分编辑模型必须提供图片。"),video:t("描述视频内容。异步任务会显示任务 ID，可手动检查进度；不要重复提交同一任务。"),speech:t("输入需要朗读的文字。"),music:t("描述音乐风格、节奏或音效内容。"),audio:t("描述要生成的语音或音乐，返回后可直接播放。"),audiofx:t("上传源音频。人声隔离至少需要 4.6 秒；变声可选择声音；音轨分离返回 ZIP。"),transcription:t("上传音频后发送，文本框可填写备注。"),rerank:t("主输入框填写查询内容，下方每行填写一条待排序文档。"),decisions:t("主输入框填写待分析材料，再填写需要判断的是非问题。"),'3d':t("描述需要生成的 3D 资产；部分模型要求参考图片。"),realtime:t("点击连接后可以开始说话；对话型模型也支持在下方输入文字，转写模型只接收语音。")};
  $('taskHelp').textContent=task==='realtime'&&m.id==='gpt-live-1'?t('连接后即可开始语音对话。')+' '+t('GPT Live 按会话时长计费，后台模型和工具另计费。麦克风权限被拒绝后，会话仍在运行并继续计费；结束请点击结束连接。'):helps[task]||'';
  $('prompt').placeholder=task==='chat'?t("有什么想法，尽管说说…"):task==='transcription'?t("可选：本次转写备注"):t("输入生成描述、文本或查询内容…");
  $('prompt').required=!['transcription','audiofx'].includes(task);
  if(task==='realtime'&&m.outputs.includes('audio')){
    const label=document.createElement('label');label.textContent=t('英语口音');const select=document.createElement('select');select.id='accent';
    select.append(new Option(t('自然口音'),'auto'),new Option(t('美式英语'),'american'),new Option(t('英式英语'),'british'));label.append(select);$('generationOptions').append(label);
    if(m.id!=='gpt-live-1'){const recognition=document.createElement('label');recognition.textContent=t('识别语言');const languages=document.createElement('select');languages.id='recognitionLanguage';
    languages.append(new Option(t('自动识别'),'auto'),new Option(t('中文'),'zh'),new Option(t('英语'),'en'));languages.value=realtimeRecognitionLanguage;languages.addEventListener('change',()=>{if(['auto','zh','en'].includes(languages.value))realtimeRecognitionLanguage=languages.value;});recognition.append(languages);$('generationOptions').append(recognition);}
  }
  if(['speech','music','realtime','audiofx'].includes(task)&&m.voices.length){const label=document.createElement('label');label.textContent=t(task==='realtime'?'音色':'声音');const select=document.createElement('select');select.id='voice';select.append(...m.voices.map((v,i)=>new Option(task==='realtime'?t('音色 {0}',i+1):(typeof v==='string'?v:JSON.stringify(v)),typeof v==='string'?v:JSON.stringify(v))));label.append(select);$('generationOptions').append(label);}
  updateConnection(); render(); document.dispatchEvent(new CustomEvent('starhub:modelchanged'));
}
// A user's upward scroll takes precedence over streaming and delayed media.
// Resume following only when the user actually returns to the bottom.
function createConversationScrollController(pane, content) {
  let following=true,lastTop=pane.scrollTop,touchY=null;
  const atBottom=()=>pane.scrollHeight-pane.clientHeight-pane.scrollTop<=2;
  const scrollable=()=>pane.scrollHeight-pane.clientHeight>2;
  const stopFollowing=()=>{if(scrollable())following=false;};
  pane.addEventListener('wheel',event=>{if(event.deltaY<0)stopFollowing();},{passive:true});
  pane.addEventListener('touchstart',event=>{touchY=event.touches.length===1?event.touches[0].clientY:null;},{passive:true});
  pane.addEventListener('touchmove',event=>{if(touchY!==null&&event.touches.length===1){const y=event.touches[0].clientY;if(y>touchY+1)stopFollowing();touchY=y;}},{passive:true});
  pane.addEventListener('touchend',()=>{touchY=null;},{passive:true});
  pane.addEventListener('keydown',event=>{if(['ArrowUp','PageUp','Home'].includes(event.key))stopFollowing();});
  pane.addEventListener('scroll',()=>{
    const top=pane.scrollTop;
    if(top<lastTop-0.5)following=false;
    else if(top>lastTop+0.5&&atBottom())following=true;
    lastTop=top;
  },{passive:true});
  function followBottom(){pane.scrollTop=pane.scrollHeight;lastTop=pane.scrollTop;}
  if(typeof ResizeObserver==='function'){
    const observer=new ResizeObserver(()=>{if(following)followBottom();});
    observer.observe(content);observer.observe(pane);
  }
  return {
    begin(force){if(force)following=true;return following;},
    finish(follow,hasMessages){if(follow){if(hasMessages)followBottom();else{pane.scrollTop=0;lastTop=pane.scrollTop;}}else lastTop=pane.scrollTop;}
  };
}
const conversationScroll=createConversationScrollController($('conversation'),$('messages'));
const messageViews = new WeakMap();
const messageMaps = new WeakMap();
let renderedChat, historySignature = '', pendingRender = 0;
function queueRender() {
  if (!pendingRender) pendingRender = requestAnimationFrame(() => { pendingRender = 0; render(); });
}
function render({toBottom = false} = {}) {
  if (pendingRender) { cancelAnimationFrame(pendingRender); pendingRender = 0; }
  const messages = $('messages');
  const follow = conversationScroll.begin(toBottom || renderedChat !== current);
  const nextHistory = JSON.stringify([language, busy, current.id, chats.map(c => [c.id, c.title, c.messages.length > 0])]);
  if (historySignature !== nextHistory) {
  historySignature = nextHistory;
  $('history').replaceChildren();
  for (const chat of chats) {
    if (!chat.messages.length) continue;
    const row = document.createElement('div'); row.className = 'history-row';
    const button = document.createElement('button'); button.textContent = chat.messages.length?chat.title:t('新的对话');
    button.className = `history-chat${chat === current ? ' active' : ''}`; button.disabled = busy;
    button.addEventListener('click', () => activateChat(chat));
    const rename = document.createElement('button'); rename.className='rename-chat'; rename.textContent='✎'; rename.title=t('重命名'); rename.setAttribute('aria-label',t('重命名')); rename.disabled=busy; rename.onclick=()=>{const name=window.prompt(t('重命名对话'),chat.title);if(name!==null&&name.trim()){chat.title=name.trim().slice(0,200);render();}};
    const remove = document.createElement('button'); remove.className = 'delete-chat'; remove.textContent = '×'; remove.disabled = busy;
    remove.title = t("删除对话"); remove.setAttribute('aria-label', t("删除对话：{0}",chat.title));
    remove.addEventListener('click', () => deleteChat(chat));
    row.append(button, rename, remove); $('history').append(row);
  }
  if (!$('history').childElementCount) {
    const empty = document.createElement('p'); empty.className = 'history-empty'; empty.textContent = t("开始聊天后，对话会显示在这里。"); $('history').append(empty);
  }
  }
  $('welcome').hidden = current.messages.length > 0 || Boolean(selectedModel()?.batch) || $('task').value==='realtime';
  $('messages').hidden = current.messages.length === 0;
  if (renderedChat !== current) { for(const map of messages.querySelectorAll('.city-map'))map.cityMapSuspend?.();messages.replaceChildren(); }
  let index = 0;
  for (const message of current.messages) {
    // Text deltas reuse their row; media/metadata changes replace only that message.
    const state = [message.media, message.details, message.status, message.jobId, message.image, message.seconds, message.providerName, message.model, message.role, message.warning, language, message.attachmentOmitted, message.imageUnavailable, message.imageUnavailableReason, message.incomplete, message.recoveryNotice, message.providerSavePending, message.continuationUnavailable, message.mediaRefs, message.mediaLoading, message.images, message.attachments, Boolean(message.startedAt), Boolean(message.reasoningSummary), message.thinkingSeconds!=null];
    let view = messageViews.get(message);
    if (!view || state.some((value, i) => value !== view.state[i])) {

    const row = document.createElement('article'); row.className = `message ${message.role}`;
    const avatar = document.createElement('div'); avatar.className = 'avatar'; avatar.textContent = message.role === 'user' ? t("你") : '✳';
    const body = document.createElement('div'); body.className = 'message-body';
    const label = document.createElement('div'); label.className = 'message-label'; label.textContent = message.role === 'user' ? t("你") : `${message.providerName} · ${message.model}`;
    const content = document.createElement('div'); content.className = 'message-content';
    if(message.role==='assistant')body.append(label);
    if(message.role==='assistant'&&(message.startedAt||message.reasoningSummary||message.thinkingSeconds!=null)){
      const reasoning=document.createElement('details');reasoning.className='reasoning-block';
      const heading=document.createElement('summary');heading.className='reasoning-heading';const text=document.createElement('div');text.className='reasoning-text';reasoning.append(heading,text);body.append(reasoning);
    }
    body.append(content);
    if(message.attachmentOmitted)body.append(element('p',t("附件未保存在本机。"),'reply-warning'));
    if(message.imageUnavailable)body.append(element('p',t(message.imageUnavailableReason==='busy'?'服务器繁忙，图片暂时无法读取，请稍后重试。':message.imageUnavailableReason==='missing'?'图片不存在或无法访问，请检查文件库或回收箱。':'图片暂时不可读取，请保留原请求并稍后重试。'),'reply-warning'));
    if(message.startedAt==null&&(message.incomplete||message.providerSavePending)){
      const notice=message.recoveryNotice||(message.providerSavePending?'回复已收到，保存尚未确认。':'这条回复在保存时尚未完成。');
      body.append(element('p',t(notice),'reply-warning'));
    }
    if(message.continuationUnavailable===true&&message.provider==='moonshot')body.append(element('p',t('此回复无法继续对话，请新建对话；原内容已保留。'),'reply-warning'));
    const visibleWarning=String(message.warning||'').split('Degraded mode: this result currently has one active copy.').join('').trim();if(visibleWarning)body.append(element('p',errorText(visibleWarning),'reply-warning'));if(message.details?.webSearchUsed)body.append(element('p',t('已进行联网搜索。'),'search-used'));if(message.details?.realtimeSource==='oai-weather')body.append(element('p',t('实时数据源：OpenAI 搜索天气数据（未提供网页链接）'),'search-used'));if(message.details?.googleWidget){const frame=document.createElement('iframe');frame.className='google-suggestions';frame.title=t('Google 搜索建议');frame.setAttribute('sandbox','allow-popups allow-popups-to-escape-sandbox');frame.srcdoc=message.details.googleWidget;body.append(frame);}
    appendMedia(body,message.media);appendGeneratedPreviewControl(body,message);if(message.details&&Object.keys(message.details).length){const details=element('details',undefined,'result-details');details.append(element('summary',t("查看完整结果 / 参数反馈")),element('pre',JSON.stringify(message.details,null,2)));const save=element('button',t("导出详细结果"));save.onclick=()=>downloadJson(message.details,'Star-Hub-result.json');details.append(save);body.append(details);}if(message.image)appendMedia(body,[{type:'image',url:message.image}]);if(message.images?.length)appendMedia(body,message.images.map(url=>({type:'image',url}))); if(message.attachments?.length)appendMedia(body,message.attachments.map(url=>({type:url.startsWith('data:image/')?'image':url.startsWith('data:audio/')?'audio':'file',url})));
    if(message.jobId && !['completed','failed','cancelled','expired'].includes(message.status)) { const check=document.createElement('button');check.className='text-button';check.textContent=t("检查视频进度");check.disabled=busy;check.addEventListener('click',()=>checkVideo(message));body.append(check); }
    let recoveryButton;
    if (message.role === 'assistant') {
      const tools = document.createElement('div'); tools.className = 'message-tools';
      const copy = document.createElement('button'); copy.textContent = t("复制回答");
      copy.hidden=message.incomplete&&message.startedAt==null&&!message.content;
      copy.addEventListener('click', async () => { try { await navigator.clipboard.writeText(message.content); copy.textContent = t("已复制"); setTimeout(() => copy.textContent = t("复制回答"), 1800); } catch { showError(t("无法访问剪贴板，请直接选择回答文本复制。")); } });
      const timing = document.createElement('span'); timing.className='reply-state';
      tools.append(copy, timing);
      if(needsProviderReplyRecovery(message)){
        const chat=current;
        recoveryButton=element('button',t('查看原结果'));
        recoveryButton.type='button';
        recoveryButton.onclick=async()=>{
          recoveryButton.disabled=true;
          try{await checkSavedProviderReply(chat,message,true);}
          finally{recoveryButton.disabled=busy||cloudHydrating||realtimeActive;}
        };
        tools.append(recoveryButton);
      }
      body.append(tools);
    }
    row.append(avatar, body);
    view = {row, content, state, recoveryButton, reasoning:body.querySelector('.reasoning-block'), timing:body.querySelector('.reply-state'), check: body.querySelector('.text-button')};
    messageViews.set(message, view);
    }
    // Wait for both the transcript and reply to finish, then bind the map to
    // this message. Subsequent text deltas never rebuild the map frame.
    const previous=current.messages[index-1];
    if(message.role==='assistant'&&!message.startedAt&&!message.realtimeReplyPending&&message.content&&previous?.role==='user'&&!previous.mapTranscriptPending&&!previous.mapTranscriptFailed&&!message.content.startsWith('```star-explain\n')){
      const query=StarCityMaps.queryFor(previous.content);
      if(query){let map=messageMaps.get(message);if(!map){const ownerChat=current;map=StarCityMaps.create(message,query,{locale:()=>language,canUpdate:()=>current===ownerChat&&ownerChat.messages.includes(message),onChange:()=>{if(typeof scheduleHistorySave==='function')scheduleHistorySave();}});messageMaps.set(message,map);}if(map.parentElement!==view.content.parentElement)view.content.after(map);}
    }
    const shown=message.displayText ?? message.content;
    const citations=message.revealing?[]:(message.details?.webCitations||[]);
    const renderKey=shown+'\u0000'+JSON.stringify(citations)+'\u0000'+Boolean(message.revealing);
    if (view.renderKey !== renderKey) {
      if(message.role==='assistant'){renderMarkdown(view.content,shown,citations,{images:!message.revealing});StarVisuals.enhance(view.content,!message.revealing);}
      else view.content.textContent=shown;
      view.renderKey=renderKey;
    }
    view.content.classList.toggle('reply-typing',Boolean(message.revealing));
    if(view.reasoning){const elapsed=message.thinkingSeconds??((performance.now()-(message.startedAt||performance.now()))/1000);view.reasoning.querySelector('summary').textContent=`${message.thinkingSeconds==null?t('思考中'):t('已思考')} · ${Number(elapsed).toFixed(1)}s`;view.reasoning.classList.toggle('thinking-active',message.thinkingSeconds==null&&Boolean(message.startedAt));const summary=message.reasoningSummary||'';if(view.reasoningKey!==summary){renderMarkdown(view.reasoning.querySelector('.reasoning-text'),summary,[],{images:false});view.reasoningKey=summary;}}
    if(view.timing)view.timing.textContent=message.revealing?`${t("正在回复…")} ${((performance.now()-(message.startedAt||performance.now()))/1000).toFixed(1)}s`:message.seconds==='realtime'?t("实时"):message.seconds?`${message.seconds}s`:'';
    if (view.check) view.check.disabled = busy;
    if (view.recoveryButton) view.recoveryButton.disabled = busy||cloudHydrating||realtimeActive||providerReplyChecks.has(message);
    if (messages.children[index] !== view.row) messages.insertBefore(view.row, messages.children[index] || null);
    view.row.querySelector('.city-map')?.cityMapResume?.();
    index++;
  }
  while (messages.children.length > index) {const row=messages.lastElementChild;for(const map of row.querySelectorAll('.city-map'))map.cityMapSuspend?.();row.remove();}
  renderedChat = current;
  $('exportChat').disabled = !current.messages.length || busy;
  conversationScroll.finish(follow,Boolean(current.messages.length));
  if(typeof scheduleHistorySave==='function')scheduleHistorySave();
}
function renderChatAttachments() {
  const container=$('chatAttachments'), files=[...$('inputFile').files];
  container.replaceChildren();
  container.hidden=$('attachChatFile').hidden||!files.length;
  for(const [index,file] of files.entries()){
    const chip=document.createElement('span'); chip.className='attachment-chip';
    const name=document.createElement('span'); name.className='attachment-name'; name.textContent=file.name; name.title=file.name;
    const remove=document.createElement('button'); remove.type='button'; remove.className='remove-attachment'; remove.textContent='×'; remove.disabled=busy; remove.setAttribute('aria-label',t('移除附件：{0}',file.name));
    remove.addEventListener('click',()=>{const transfer=new DataTransfer(); files.forEach((item,i)=>{if(i!==index)transfer.items.add(item);}); $('inputFile').files=transfer.files; $('inputFile').dispatchEvent(new Event('change'));});
    chip.append(name,remove); container.append(chip);
  }
}
$('attachChatFile').addEventListener('click',()=>$('inputFile').click());
document.addEventListener('starhub:languagechanged',renderChatAttachments);
function setBusy(value, toBottom = false) {
  busy = value; $('busyStatus').hidden = !value; $('messages').setAttribute('aria-busy',String(value));
  for (const id of ['provider','model','apiKey','newChat','prompt','task','browseModels','modelDirection','inputFile','attachChatFile','documents','question']) $(id).disabled = value;
  document.querySelectorAll('.prompt-card').forEach(b => b.disabled = value);
  refreshReasoningControl();
  renderChatAttachments();
  updateConnection(); if(typeof refreshConnectionSummary==='function')refreshConnectionSummary(); render({toBottom});
}
// A paste, drop or restored draft has unknown provenance. In particular, do
// not feed such content into automatic memory extraction.
let memoryDirectInput=$('prompt').value==='';
let memoryTrackedValue=$('prompt').value;
function resetMemoryInputTracking(){memoryTrackedValue=$('prompt').value;memoryDirectInput=memoryTrackedValue==='';}
$('prompt').addEventListener('paste',()=>{memoryDirectInput=false;});
$('prompt').addEventListener('drop',()=>{memoryDirectInput=false;});
$('prompt').addEventListener('beforeinput',()=>{if($('prompt').value!==memoryTrackedValue)memoryDirectInput=false;});
$('prompt').addEventListener('input',event=>{if(!event.isTrusted||/paste|drop|replacement/i.test(event.inputType||''))memoryDirectInput=false;memoryTrackedValue=$('prompt').value;if(!memoryTrackedValue)memoryDirectInput=true;});
$('chatForm').addEventListener('submit', async event => {
  event.preventDefault(); if (busy || cloudHydrating || !ready || publicInsecure) return;
  if($('task').value==='realtime'){document.dispatchEvent(new CustomEvent('starhub:realtime-text'));return;}
  if(selectedModel()?.execution==='external'){showError(selectedModel().executionNote);return;}
  if(selectedModel()?.batch)return;
  let task=$('task').value;
  const text = $('prompt').value.trim() || (['transcription','audiofx'].includes(task) ? t("处理上传的音频") : ''); if (!text) return;
  if (!$('apiKey').value.trim() || !$('model').value.trim()) {
    showError(t("请选择模型，并填写对应服务商的 API Key。")); openSetup(); return;
  }
  if (current.messages.length >= 59) { showError(t("当前对话已达到长度限制，请新建对话。")); return; }
  const p = providers.find(p => p.id === $('provider').value), requestAccount=accountEmail,requestChat=current,requestAccountRevision=accountIdentityRevision,requestApiKey=$('apiKey').value.trim(),requestFiles=[...$('inputFile').files];
  const memoryInput=typeof memoryDirectInput!=='undefined'&&memoryDirectInput&&typeof memoryTrackedValue!=='undefined'&&$('prompt').value===memoryTrackedValue&&requestFiles.length===0?text:null;
  function requireRequestOwner(){if(accountEmail!==requestAccount||current!==requestChat||accountIdentityRevision!==requestAccountRevision)throw Error('账户、模型或对话已更改，原请求尚未发送。');}
  let model = $('model').value.trim();
  const imageIntent=['chat','image'].includes(task)?StarImageIntent.resolve(text,requestChat.messages,{hasAttachment:requestFiles.length>0,imageTask:task==='image'}):null;
  const imageEdit=imageIntent?.kind==='edit'||(imageIntent?.kind==='missing'&&imageIntent.intent==='edit'&&requestFiles.length===1);
  if(imageIntent){
    if(imageIntent.kind==='missing-subject'){showError(t('请告诉我图里要画什么。'));return;}
    if(imageIntent.kind==='missing'&&!imageEdit){showError(t('当前对话中没有可用的上一张图片，请先生成图片或提供图片及修改要求。'));return;}
    if(imageIntent.kind==='variation'&&!imageIntent.prompts.length){showError(t('无法找到上一张图片的描述，请告诉我希望生成什么图片。'));return;}
    const imageModel=task==='image'?selectedModel():StarImageIntent.imageModel(catalog,p.id);
    if(!imageModel){showError(t('当前服务商尚未接入聊天中的图片生成。请选择已接入图片生成的 OpenAI 模型并配置对应 API Key。'));return;}
    if(requestFiles.length&&(!imageEdit||requestFiles.length!==1)){showError(t('图片编辑一次只能使用一张参考图片；其他图片生成请求请先移除附件。'));return;}
    task='image';model=imageModel.id;
  }
  setBusy(true);
  if(imageEdit)$('busyStatus').textContent=t('正在准备参考图片…');
  const effort=task==='chat'?$('effort').value:'default';
  let file,files,editInputWarning='';
  try {
    files=await readInputFiles();requireRequestOwner();file=files[0];
    if(files.length>(selectedModel()?.referenceLimits?.image||1)&&task==='image')throw new Error(t("参考图片数量超过此模型限制。"));
    if(imageEdit&&!file){
      const source=requestChat.messages[imageIntent.sourceIndex],refs=StarProviderMedia.references(source?.mediaRefs||source?.media||[]);
      if(!source||!refs.length)throw Error(t('无法读取上一张图片，请重新选择图片后再试。'));
      if(refs.length!==1)throw Error(t('上一条包含多张图片，请选择或上传一张后提出修改要求。'));
      const active=()=>accountEmail===requestAccount&&current===requestChat&&accountIdentityRevision===requestAccountRevision;
      let loaded=[],legacyUrl='',legacyBlob;
      try{
        if(/^[0-9a-f-]{36}$/.test(source.providerOperationId||''))loaded=await StarProviderMedia.hydrate([refs[0]],{operationId:source.providerOperationId,expectedEmail:requestAccount,active,includeBlob:true});
        else if(requestAccount&&refs[0].fileId){
          const legacy=await readCloudFile(refs[0].fileId);requireRequestOwner();
          legacyBlob=StarImageEditInput.fromDataUrl(legacy.url);requireRequestOwner();legacyUrl=URL.createObjectURL(legacyBlob);
        }else throw Error(t('无法读取上一张图片，请重新选择图片后再试。'));
        const editBlob=legacyBlob||loaded[0].blob;
        requireRequestOwner();const prepared=await StarImageEditInput.prepare(legacyUrl||loaded[0].url,{blob:editBlob,owns:url=>legacyUrl?url===legacyUrl&&editBlob===legacyBlob:StarProviderMedia.blobFor(url)===editBlob,active});requireRequestOwner();file=prepared.dataUrl;
        if(prepared.wasResized)editInputWarning=t('参考图片已缩小以满足编辑输入大小限制。');else if(prepared.wasConverted)editInputWarning=t('参考图片已转换格式以满足编辑输入大小限制。');
      }finally{StarProviderMedia.release(loaded);if(legacyUrl)URL.revokeObjectURL(legacyUrl);}
    }
  } catch(error) {setBusy(false);showError(error.message);return;}
  if(['transcription','audiofx'].includes(task)&&!file){setBusy(false);showError(t("请上传音频文件。"));return;}
  const options={};
  if (!current.messages.length) current.title = text.slice(0, 20);
  const userMessage={role:'user',content:text,memorySource:task==='chat'&&memoryInput?'typed':undefined,images:task==='chat'||imageEdit?files:[],imageCount:task==='chat'||imageEdit?files.length:0,fileIds:[],attachments:task==='chat'||imageEdit?[]:files};
  const memorySourceIndex=requestChat.messages.filter(m=>typeof m.content==='string'&&(m.content||/^[0-9a-f-]{36}$/.test(m.providerOperationId||''))).length;
  const messages=task==='image'?[]:[...requestChat.messages,userMessage]
    .filter(message=>!message.media?.length&&!message.mediaRefs?.some(item=>item?.type==='image')&&!message.jobId&&!(message.role==='assistant'&&!message.content))
    .map(message=>outgoingHistoryMessage(message,p.id,model));
  const priorPrompts=imageEdit&&files.length?[]:(imageIntent?.prompts||[]);
  let imagePrompt=text;
  if(imageIntent?.context){
    const context=imageIntent.context.map(({role,content})=>`${role}: ${content}`).join('\n\n');
    imagePrompt=`Create an image for the latest request, using the most recent relevant subject from this conversation.\n\n${context}\n\nLatest request: ${text}`;
  }else if(imageIntent?.kind==='variation'){
    imagePrompt=`Create a new image based on these earlier requests:\n${priorPrompts.join('\n')}\nLatest request: ${text}`;
  }else if(imageEdit){
    imagePrompt=`Edit the supplied image according to the latest request. Preserve other details unless asked to change them.\n${priorPrompts.length?'Earlier requests:\n'+priorPrompts.join('\n')+'\n':''}Latest request: ${text}`;
  }
  if(task==='image'&&imagePrompt.length>30000){setBusy(false);showError(t('图片描述及上下文过长，请缩短修改要求后重试。'));return;}
  let operation;
  let memoryToken=null;
  try{
    if(task==='chat'&&memoryInput&&typeof StarMemory!=='undefined')memoryToken=await StarMemory.prepareSemantic(memoryInput,requestAccount,requestAccountRevision,{provider:p.id,model,apiKey:requestApiKey,sourceChatId:requestChat.id,sourceMessageIndex:memorySourceIndex}).catch(()=>null);
    requireRequestOwner();
    const system=task==='chat'
      ? [StarVisuals.conversationInstruction,StarVisuals.instruction,StarVisuals.explanationInstruction].join('\n')
      : '';
    operation=await StarProviderOperations.prepare('run',{provider:p.id,model,task,stream:task==='chat',system,effort,prompt:imagePrompt,options,parameters:{},files:task==='image'&&!imageEdit?files:undefined,imageEdit:imageEdit||undefined,voice:$('voice')?.value||'',file:imageEdit?file:['chat','image'].includes(task)?'':file||'',fileFormat:$('inputFile').files[0]?.name.split('.').pop().toLowerCase(),documents:task==='image'?[]:$('documents').value.split('\n').filter(s=>s.trim()),question:task==='image'?'':$('question').value,apiKey:requestApiKey,expectedEmail:requestAccount,timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC',webSearch:task==='chat'&&!$('webSearch').disabled&&$('webSearch').checked,messages});
    requireRequestOwner();
    if(requestAccount&&files.length)userMessage.fileIds=await uploadSubmittedFiles(files,requestFiles,requestAccount,()=>current===requestChat&&accountIdentityRevision===requestAccountRevision);
    requireRequestOwner();
  }catch(error){if(operation)StarProviderOperations.discardUnsent(operation);setBusy(false);showError(error.message);return;}
  StarStore.savePending(null);
  requestChat.messages.push(userMessage);
  const answer={role:'assistant',content:'',provider:p.id,providerName:p.name,model,providerOperationId:operation.id,providerOwnerTab:requestAccount?operation.body?.ownerTab:undefined,incomplete:true,startedAt:performance.now()};
  requestChat.messages.push(answer);
  $('prompt').value='';showError('');setBusy(true,true);$('busyStatus').textContent=task==='image'?t(imageEdit?'正在编辑图片…':'正在生成图片…'):t("正在连接模型，回复将逐步显示…");
  if(typeof resetMemoryInputTracking==='function')resetMemoryInputTracking();
  $('sidebar').classList.remove('open');
  const executeRequest=async()=>{
  if(requestAccount!==accountEmail||current!==requestChat){setBusy(false);showError('Return to the original account and conversation before retrying this request.');return;}
  setBusy(true,true);delete answer.warning;delete answer.recoveryError;const reveal=new ReplyReveal(answer);
  const started=performance.now(),controller=new AbortController();answer.startedAt=started;const timer=setInterval(queueRender,250);
  const timeout=setTimeout(()=>controller.abort(),190000);
  let dispatchStarted=false;
  try {
    // Persist the original message and its empty result placeholder first. A
    // reload can then associate a receipt with the original conversation.
    await persistProviderHistory(requestAccount,requestChat.id);requireRequestOwner();
    dispatchStarted=true;
    const {response}=await StarProviderOperations.requestPrepared(operation,{endpoint:'run',headers:{'Accept':task==='chat'?'text/event-stream':'application/json'},signal:controller.signal});
    let result;
    if(response.ok&&response.headers.get('Content-Type')?.includes('text/event-stream')){
      result=await readReplyEvents(response,text=>{requireRequestOwner();if(answer.thinkingSeconds==null)answer.thinkingSeconds=(performance.now()-started)/1000;reveal.append(text);$('busyStatus').textContent=t("正在接收回复…");},summary=>{requireRequestOwner();answer.reasoningSummary=(answer.reasoningSummary||'')+summary;queueRender();});
    }else{
      result=await response.json().catch(()=>({error:t("服务暂时不可用（HTTP {0}），请稍后重试。",response.status)}));
      requireRequestOwner();
      StarProviderOperations.observe(operation.id,result,{keepActive:true});
      if(['CLUSTER_UNAVAILABLE','OPERATION_PENDING'].includes(result.code)||['CLUSTER_UNAVAILABLE','OPERATION_PENDING'].includes(result.storageErrorCode))setClusterAvailability(false);
      if(!response.ok||!result.text)throw new Error(result.code==='CLUSTER_UNAVAILABLE'?t('服务器故障，请等待恢复'):(result.error||result.message||t("模型没有返回文本。")));
      if(answer.thinkingSeconds==null)answer.thinkingSeconds=(performance.now()-started)/1000;reveal.append(task==='image'||task==='speech'?uiText(result.text):result.text);
    }
    clearTimeout(timeout);
    requireRequestOwner();
    StarProviderOperations.observe(operation.id,result,{keepActive:true});
    if(!answer.content)throw new Error(t("模型没有返回文本。"));
    const providerContext=validatedProviderContext(result.providerContext,p.id,model);if(providerContext&&result.continuationUnavailable!==true)answer.providerContext=providerContext;if(p.id==='moonshot'&&result.continuationUnavailable===true)answer.continuationUnavailable=true;
    invalidateGeneratedPreviews();releaseGeneratedMessage(answer);
    Object.assign(answer,{media:result.media,mediaRefs:StarProviderMedia.references(result.media),details:result.details,jobId:result.jobId,status:result.status,warning:[result.warning,editInputWarning].filter(Boolean).join(' '),imageSaveFailed:Boolean(result.imageSaveFailed)});
    // The full text is already in answer.content. Save it before completing the
    // visual reveal so a refresh cannot turn a completed response into a partial one.
    answer.incomplete=false;answer.providerSavePending=true;answer.seconds=((performance.now()-started)/1000).toFixed(1);
    await persistProviderHistory(requestAccount,requestChat.id);requireRequestOwner();delete answer.providerSavePending;
    if(memoryToken&&typeof StarMemory!=='undefined')StarMemory.rememberSemantic(memoryToken).catch(()=>{});
    if(current===requestChat)await displayGeneratedPreview(answer,requestChat,requestAccount);requireRequestOwner();
    if(!answer.imageSaveFailed){StarProviderOperations.acknowledge(operation.id);providerRetries.delete(operation.id);}
    $('inputFile').value='';renderChatAttachments();StarStore.savePending(null);
    $('busyStatus').textContent=t("正在显示回复…");reveal.showAll();await reveal.finish();requireRequestOwner();
  }catch(error){
    clearTimeout(timeout);
    if(!answer.incomplete){
      StarProviderOperations.markUnknown(operation.id);
      reveal.showAll();await reveal.finish();
      if(current===requestChat&&accountEmail===requestAccount&&accountIdentityRevision===requestAccountRevision){
        answer.recoveryError=answer.providerSavePending?t('回复已收到，保存尚未确认。'):error.message;
        showError(answer.recoveryError);
      }
      return;
    }
    let needsRecovery=false;
    if(error.operationId===operation.id&&['COMPLETED','COMPLETED_NO_RESULT'].includes(error.operationState))StarProviderOperations.observe(operation.id,error);
    else{
      StarProviderOperations.markUnknown(operation.id);
      try{needsRecovery=StarProviderOperations.records().some(item=>item.id===operation.id&&!item.archived&&['PENDING','ABSENT','CLAIMED','SENT','UNKNOWN'].includes(item.state));}catch{}
    }
    answer.incomplete=true;answer.warning=dispatchStarted?'Result unconfirmed. The original message and received text are retained. Check the original request before sending again.':'The provider request was not sent because conversation storage could not be confirmed. Keep this page and retry the original request after storage recovers.';await reveal.finish();answer.seconds=((performance.now()-started)/1000).toFixed(1);
    try{await persistProviderHistory(requestAccount,requestChat.id);}catch{}
    const notice=error.message||answer.warning,hint=t('请打开连接设置，在请求记录中查看原请求。');
    if(requestAccount===accountEmail&&requestAccountRevision===accountIdentityRevision&&current===requestChat){
      answer.recoveryError=needsRecovery&&!notice.includes(hint)?notice+' '+hint:notice;
      showError(answer.recoveryError);
    }
  }finally{clearInterval(timer);delete answer.startedAt;clearTimeout(timeout);setBusy(false);$('prompt').focus({preventScroll:true});if(dispatchStarted&&current===requestChat)recoverLatestProviderReply(requestChat);}
  };
  providerRetries.set(operation.id,executeRequest);await executeRequest();

});
document.addEventListener('starhub:provider-retry',async event=>{
  if(busy)return;const retry=providerRetries.get(event.detail.operationId);
  if(!retry){showError('The original inputs are no longer in memory. Check the original result, or explicitly allow a separate request.');return;}
  try{const status=await StarProviderOperations.query(event.detail.operationId);if(!['ABSENT','CLAIMED'].includes(status.operationState))throw new Error('The original request may already have been sent. Check its result.');await retry();}catch(error){showError(error.message);}
});
document.addEventListener('starhub:provider-result',async event=>{
  const value=event.detail;
  const chat=chats.find(chat=>chat.messages.some(message=>message.providerOperationId===value.operationId));if(!chat)return;
  const answer=chat.messages.find(message=>message.providerOperationId===value.operationId);
  if(answer)await restoreProviderReply(chat,answer,value);
});

const providerReplyChecks=new WeakMap(), providerReplyRecovery=new WeakMap();
const replyRecoveryDelays=[5000,15000,30000];
function needsProviderReplyRecovery(message){
  return message.role==='assistant'&&message.startedAt==null&&(message.incomplete||message.providerSavePending)
    &&/^[0-9a-f-]{36}$/.test(message.providerOperationId||'');
}
function canCheckProviderReply(chat,message,includeSaved=false){
  return ready&&!busy&&!cloudHydrating&&!realtimeActive&&current===chat&&!chat.deleting&&!chat.deleted
    &&chat.messages.includes(message)&&(includeSaved||needsProviderReplyRecovery(message));
}
async function restoreProviderReply(chat,answer,receipt){
  const result=receipt.result;
  if(receipt.operationId!==answer.providerOperationId||receipt.operationState!=='COMPLETED'
    ||(receipt.httpStatus!=null&&(receipt.httpStatus<200||receipt.httpStatus>=300))
    ||typeof result?.text!=='string'||!result.text.trim()||!chats.includes(chat)||chat.deleting||chat.deleted)return false;
  const email=accountEmail,epoch=accountIdentityRevision;
  const original=()=>accountEmail===email&&accountIdentityRevision===epoch&&chats.includes(chat)&&!chat.deleting&&!chat.deleted;
  const recoveryError=answer.recoveryError;
  const providerContext=validatedProviderContext(result.providerContext,answer.provider,answer.model);
  if(providerContext&&result.continuationUnavailable!==true)answer.providerContext=providerContext;
  if(answer.provider==='moonshot'&&result.continuationUnavailable===true)answer.continuationUnavailable=true;
  invalidateGeneratedPreviews();releaseGeneratedMessage(answer);
  delete answer.recoveryNotice;
  Object.assign(answer,{content:result.text,incomplete:false,providerSavePending:true,media:result.media,
    mediaRefs:StarProviderMedia.references(result.media),details:result.details,jobId:result.jobId,status:result.status,
    warning:result.warning||result.error||'',imageSaveFailed:Boolean(result.imageSaveFailed)});
  if(typeof result.reasoningSummary==='string')answer.reasoningSummary=result.reasoningSummary;
  queueRender();
  try{
    await persistProviderHistory(email,chat.id);
    if(!original())return true;
    delete answer.providerSavePending;queueRender();
    if(current===chat&&recoveryError&&$('error').textContent===errorText(recoveryError))showError('');
    delete answer.recoveryError;
    if(!answer.imageSaveFailed){StarProviderOperations.acknowledge(receipt.operationId);providerRetries.delete(receipt.operationId);}
    if(current===chat)await displayGeneratedPreview(answer,chat,email);
  }catch(error){if(original()&&current===chat)showError(error.message);}
  return true;
}
async function checkSavedProviderReply(chat,message,interactive=false,includeSaved=false){
  const email=accountEmail,epoch=accountIdentityRevision,activation=chatActivation;
  const currentOwner=()=>accountEmail===email&&accountIdentityRevision===epoch&&chatActivation===activation&&current===chat;
  if(!canCheckProviderReply(chat,message,includeSaved))return null;
  let lookup=providerReplyChecks.get(message);
  if(!lookup||lookup.epoch!==epoch||lookup.activation!==activation){
    const promise=(async()=>{
      const id=message.providerOperationId;
      const receipt=email&&message.providerOwnerTab
        ? await StarProviderOperations.querySaved(id,message.providerOwnerTab)
        : await StarProviderOperations.query(id);
      if(!currentOwner()||!canCheckProviderReply(chat,message,includeSaved))return null;
      if(includeSaved&&receipt.result?.text!==message.content)return receipt;
      if(await restoreProviderReply(chat,message,receipt))return null;
      return receipt;
    })().finally(()=>{
      if(providerReplyChecks.get(message)?.promise===promise){providerReplyChecks.delete(message);queueRender();}
    });
    lookup={epoch,activation,promise};
    providerReplyChecks.set(message,lookup);
  }
  try{
    const receipt=await lookup.promise;
    if(receipt&&interactive&&currentOwner()){
      const state=receipt.operationState;
      message.recoveryNotice=receipt.result?.error
        ||(['ABSENT','CLAIMED'].includes(state)?'原请求尚未发送。':state==='COMPLETED_NO_RESULT'?'原请求已结束，结果未保留。':'原请求还没有可读取的结果。');
      queueRender();
    }
    return receipt?.operationState;
  }catch(error){if(interactive&&currentOwner())showError(error.message);}
}
function recoverLatestProviderReply(chat){
  if(!ready||busy||realtimeActive||cloudHydrating||!chat||chat!==current)return;
  const incomplete=chat.messages.findLast(needsProviderReplyRecovery);
  let message=incomplete;
  if(!message){
    // A saved reply may already have been recovered in another session while
    // this tab still has an unacknowledged receipt for its original request.
    try{
      const ids=new Set(StarProviderOperations.records().filter(item=>item.kind==='run'&&!item.archived).map(item=>item.id));
      message=chat.messages.findLast(reply=>reply.role==='assistant'&&reply.startedAt==null&&reply.content&&ids.has(reply.providerOperationId));
    }catch{return;}
  }
  const includeSaved=!incomplete;
  if(!message||!canCheckProviderReply(chat,message,includeSaved))return;
  const activation=chatActivation,epoch=accountIdentityRevision;
  const pending=providerReplyRecovery.get(message);
  if(pending?.activation===activation&&pending.epoch===epoch)return;
  const attempt={activation,epoch};
  providerReplyRecovery.set(message,attempt);
  async function check(index){
    if(providerReplyRecovery.get(message)!==attempt)return;
    if(chatActivation===activation&&accountIdentityRevision===epoch&&canCheckProviderReply(chat,message,includeSaved)){
      const state=await checkSavedProviderReply(chat,message,false,includeSaved);
      if((state==='SENT'||state==='UNKNOWN')&&index<replyRecoveryDelays.length&&providerReplyRecovery.get(message)===attempt){
        setTimeout(()=>check(index+1),replyRecoveryDelays[index]);
        return;
      }
    }
    if(providerReplyRecovery.get(message)===attempt)providerReplyRecovery.delete(message);
  }
  void check(0);
}
$('prompt').addEventListener('keydown', event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); $('chatForm').requestSubmit(); } });
$('newChat').addEventListener('click', newChat);
document.addEventListener('keydown', event => { if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); newChat(); } });
$('provider').addEventListener('change', () => selectProvider());
$('model').addEventListener('change', () => { rememberModelChoice(selectedModel()); configureModel(); });
$('task').addEventListener('change',()=>{saveModelControls();configureTask();});
$('effort').addEventListener('change',()=>{saveModelControls();refreshReasoningControl();});
$('webSearch').addEventListener('change',()=>{if(!StarStore.write('webSearch',$('webSearch').checked))showError(t('无法保存设置，请检查浏览器存储空间。'));});
$('modelDirection').addEventListener('change',()=>selectProvider());
document.querySelectorAll('.prompt-card').forEach(card => card.addEventListener('click', () => { $('prompt').value = card.dataset.prompt; resetMemoryInputTracking(); $('prompt').focus({preventScroll:true}); }));
$('exportChat').addEventListener('click', () => {
  const text = `# ${current.title}\n\n` + current.messages.map(m => `## ${m.role === 'user' ? t("你") : `${m.providerName} · ${m.model}`}\n\n${m.content}`).join('\n\n');
  const url = URL.createObjectURL(new Blob([text], {type:'text/markdown;charset=utf-8'}));
  const a = document.createElement('a'); a.href = url; a.download = 'Star-Hub-conversation.md'; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000);
});
document.addEventListener('starhub:ready',()=>recoverLatestProviderReply(current));
document.addEventListener('visibilitychange',()=>{if(!document.hidden)recoverLatestProviderReply(current);});
window.addEventListener('online',()=>recoverLatestProviderReply(current));
async function init() {
  await new Promise(resolve=>document.addEventListener("DOMContentLoaded",resolve,{once:true}));
  if(window.StarHubDesktopConnection?.start())return;
  const catalogRequest=fetch('api/catalog').then(async response=>{if(!response.ok)throw new Error(t("无法加载服务商，请刷新页面重试。"));return response.json();}).then(data=>({data}),error=>({error}));
  await restoreAccount(true);
  const guestInitialization=StarStore.initGuest();
  if(!accountEmail){await guestInitialization;for(const [provider,key] of StarStore.loadKeys())keys.set(provider,key);}
  let cloudLoaded=false;if(accountEmail){try{cloudLoaded=await initCloudSession();}catch(error){showError(error.message);return;}}
  const restored=cloudLoaded?null:StarStore.loadHistory();
  if(cloudLoaded){}else if(restored){chats=restored.chats;current=undefined;activateChat(chats.find(c=>c.id===restored.currentId)||chats[0]);}else newChat();
  try {
    const catalogResult=await catalogRequest;if(catalogResult.error)throw catalogResult.error;
    rawCatalog = catalogResult.data; catalog=localizedCatalog(rawCatalog); providers=catalog.providers; updateAvailableDirections(); setupLibrary(); $('provider').replaceChildren(...providers.map(p => new Option(p.name, p.id)));
    if(!await guestInitialization)throw Error('Guest session hydration is unconfirmed. Keep the original request identity.');
    ready = true; $('provider').disabled = false; $('browseModels').disabled=false; if(providers.some(p=>p.id===restoredSelection.provider))$('provider').value=restoredSelection.provider;selectProvider(settings.get($('provider').value));
    $('serviceStatus').classList.add('online'); $('serviceStatus').lastChild.textContent = t(" 服务已就绪");
    document.dispatchEvent(new CustomEvent('starhub:ready'));
    if (publicInsecure) showError(t("请通过 https://starhub.zhongjiehuang.com 访问后填写密钥。"));
  } catch (error) { showError(error.message); $('serviceStatus').lastChild.textContent = t(" 连接失败"); }
}
init();
function element(tag,text,className){const e=document.createElement(tag);if(text!==undefined)e.textContent=['pre','code'].includes(tag)?text:uiText(text);if(className)e.className=className;return e;}
function setupLibrary(){
  $('libraryStats').textContent=t('目前仅支持 OpenAI。');
  $('coverageNotice').textContent=catalog.coverage;
}

function libraryEmptyMessage(direction){
  return direction==='video'
    ? t('视频生成暂未接入；视频理解不等于视频生成。')
    : t('此方向暂无已接入模型。');
}

function renderLibrary(){
  const direction=$('libraryDirection').value;
  const models=directionModels(direction),pages=Math.max(1,Math.ceil(models.length/24));libraryPage=Math.min(libraryPage,pages-1);
  $('resultCount').textContent=t("找到 {0} 个接入项",models.length.toLocaleString());$('pageInfo').textContent=`${libraryPage+1} / ${pages}`;$('prevPage').disabled=libraryPage===0;$('nextPage').disabled=libraryPage>=pages-1;
  $('modelCards').replaceChildren();
  for(const m of models.slice(libraryPage*24,libraryPage*24+24)){
    const card=element('article',undefined,'model-card');
    card.append(element('div',`${m.vendorName} / ${m.family}`,'model-kicker'),element('h3',m.name),element('p',m.description,'model-intro'));
    const badges=element('div',undefined,'model-tags');for(const c of m.categories)badges.append(element('span',catalog.categories[c]||c));
    badges.append(element('span',uiText(costNames[m.cost]),m.cost==='free-key'?'free-badge':''));if(['down','degraded'].includes(m.status))badges.append(element('span',m.status==='down'?t("平台报告故障"):t("平台报告降级")));if(m.categories.includes('realtime'))badges.append(element('span',t("实时连接")));
    if(modelTierLabel(m))badges.append(element('span',modelTierLabel(m)));
    if(m.accessLabel)badges.append(element('span',m.accessLabel,'restricted-badge'));
    card.append(badges,element('p',`${m.providerName} · ${m.reasoning.length ? t("推理 ")+m.reasoning.map(e=>uiText(effortNames[e])||e).join(' / '):m.reasoningKnown?t("推理：平台默认"):t("未公开推理档位")}`,'model-route'));
    const actions=element('div',undefined,'model-actions');
    const use=element('button',m.execution==='external'?t("查看接入说明"):t("选择此模型"));
    use.addEventListener('click',()=>m.execution==='external'?showDetails(m):chooseModel(m));
    actions.append(use);card.append(actions);$('modelCards').append(card);
  }
  if(!models.length)$('modelCards').append(element('p',libraryEmptyMessage(direction),'empty-models'));
}
function showDetails(m){
  detailModel=m;$('detailTitle').textContent=m.name;const root=$('detailContent');root.replaceChildren(element('p',m.description,'detail-intro'));
  const rows=[[t("模型 ID"),m.id],[t("模型厂商 / 来源"),m.vendorName],[t("系列"),m.family],[t("实际官方厂商"),m.providerName],[t("用途"),m.categories.map(c=>catalog.categories[c]).join('、')],[t("用途细分"),m.subcategories.join('、')],[t("输入类型"),m.inputs.map(modalityName).join(' / ')],[t("输出类型"),m.outputs.map(modalityName).join(' / ')],[t("上下文"),m.context?`${m.context.toLocaleString()} token`:t("平台未公布")],[t("费用"),uiText(costNames[m.cost])],[t("密钥要求"),m.requiresKey?t("请使用 {0}，不同厂商的密钥不能通用。",m.keyLabel):t("无需 API Key")],[t("推理强度"),m.reasoning.length?m.reasoning.map(e=>`${uiText(effortNames[e])||e} (${e})`).join(' / '):m.reasoningKnown?t("支持推理；此接入项未公开强度档位，使用默认。"):t("未公开可调档位")],[t("推理默认"),m.reasoningDefault||t("由平台决定")],[t("状态"),({healthy:t("平台健康检查正常"),degraded:t("平台报告服务降级"),down:t("平台报告暂时故障"),unknown:t("平台未公布健康状态")})[m.status]||m.status],[t("必须启用推理"),m.reasoningMandatory?t("是"):t("由模型与平台决定")],[t("核对日期"),m.verifiedAt]];
  if(m.expiration)rows.push([t("计划停用"),m.expiration]);
  const dl=element('dl');for(const [k,v]of rows){dl.append(element('dt',k),element('dd',v));}root.append(dl);
  if(m.tags.length)root.append(element('p',t("系列标签：{0}。标签依据名称和公开元数据，不代表性能排名。",m.tags.join(' · ')),'field-help'));
  root.append(element('h3',t("调用费用与限制")));
  if(m.cost==='free-key')root.append(element('p',t("平台当前公布的此接入项推理单价为零，仍需注册并提供 API Key；额度、排队、速率及其他工具费用以平台为准。")));
  else root.append(element('p',t("官方 API 的价格、免费额度与地区权限取决于你的账户，请查看官方价格页。")));
  renderPricing(root,m);
  root.append(element('h3',t("本站支持范围")),element('p',m.execution==='external'?m.executionNote||t("此模型需使用平台专用接口。"):m.executionNote||m.workbenchNote||t("支持此类型的基础调用。可调参数以所选模型的设置面板为准；工具执行、长文件等流程尚未接入。实际可用性取决于账户。")));
  appendModelApiLinks(root,m);
  $('useDetailModel').hidden=m.execution==='external';if(!$('detailDialog').open)$('detailDialog').showModal();
}
function chooseModel(m){
  if(busy)return;
  $('provider').value=m.provider;rememberModelChoice(m);selectProvider(m.id);
  $('detailDialog').close();$('modelLibrary').close();
  if(!$('apiKey').value.trim())openSetup();
  else $('prompt').focus({preventScroll:true});
}

$('browseModels').addEventListener('click',()=>{renderLibrary();$('modelLibrary').showModal();});
$('closeLibrary').addEventListener('click',()=>$('modelLibrary').close());$('closeDetails').addEventListener('click',()=>$('detailDialog').close());
$('useDetailModel').addEventListener('click',()=>chooseModel(detailModel));
$('libraryDirection').addEventListener('change',()=>{
  libraryPage=0;
  renderLibrary();
});
$('prevPage').addEventListener('click',()=>{libraryPage--;renderLibrary();$('modelCards').scrollIntoView({block:'start'});});$('nextPage').addEventListener('click',()=>{libraryPage++;renderLibrary();$('modelCards').scrollIntoView({block:'start'});});
function readFileData(file){
  if(!file)return Promise.resolve('');if(file.size>4*1024*1024)return Promise.reject(new Error(t("单个文件最多 4 MB。")));
  return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=()=>reject(new Error(t("文件读取失败。")));reader.readAsDataURL(file);});
}
async function readInputFiles(){const files=[...$('inputFile').files];if(files.reduce((sum,f)=>sum+f.size,0)>4*1024*1024)throw new Error(t("本次上传的附件合计最多 4 MB。"));return Promise.all(files.map(readFileData));}
async function readInputFile(){return (await readInputFiles())[0]||'';}
function appendMedia(root,media){for(const item of media||[]){if(!StarProviderMedia.owns(item.url)&&!/^(https:\/\/|data:((image|audio|video)\/|application\/(zip|octet-stream);base64,))/.test(item.url))continue;let e;if(item.type==='image'){e=element('img');e.alt=t("模型生成的图片");e.loading='lazy';}else if(['audio','video'].includes(item.type)){e=element(item.type);e.controls=true;e.preload='metadata';}else{e=element('a',t("打开生成的文件 ↗"));e.href=item.url;e.target='_blank';e.rel='noopener noreferrer';if(item.filename)e.download=item.filename;}if(e.tagName!=='A')e.src=item.url;e.className='generated-media';root.append(e);}}
async function checkVideo(message){
  if(busy)return;const key=keys.get(message.provider);if(!key){showError(t("请先填写此视频任务对应平台的 API Key。"));return;}
  setBusy(true);showError('');
  try{const {response,operationId}=await StarProviderOperations.request('video-status',{provider:message.provider,model:message.model,apiKey:key,task:'video',jobId:message.jobId,expectedEmail:accountEmail},{endpoint:'run',readOnly:true});const r=await response.json();StarProviderOperations.observe(operationId,r);if(!response.ok)throw new Error(r.error||t("查询失败"));Object.assign(message,{content:r.text,media:r.media,status:r.status});queueRender();StarProviderOperations.acknowledge(operationId);}catch(e){showError(e.message);}finally{setBusy(false);}
}

function renderPricing(root,m){
  const labels={prompt:t("文本输入"),completion:t("文本输出"),input_cache_read:t("缓存读取"),input_cache_write:t("缓存写入"),promptTextTokens:t("文本输入"),promptCachedTokens:t("缓存输入"),promptImageTokens:t("图片输入"),promptAudioTokens:t("音频输入"),completionTextTokens:t("文本输出"),completionImageTokens:t("图像输出"),completionAudioTokens:t("音频输出"),completionReasoningTokens:t("推理输出"),image:t("图片"),audio:t("音频"),request:t("每次请求"),web_search:t("联网搜索"),internal_reasoning:t("推理"),input_audio_cache:t("音频缓存"),input_audio:t("音频输入"),output_audio:t("音频输出"),imageTokens:t("图像 token"),video:t("视频"),second:t("每秒"),perSecond:t("每秒"),perImage:t("每张图片")};
  const table=element('table',undefined,'price-table');
  for(const [k,v] of Object.entries(m.pricing||{})){
    if(k==='currency'||v===null||typeof v==='object')continue;
    let number=Number(v),unit='USD';
    const token=/Tokens$/.test(k)||['prompt','completion','input_cache_read','input_cache_write','internal_reasoning','input_audio_cache','input_audio','output_audio'].includes(k);
    if(token){number*=1000000;unit+=t(" / 百万 token");}else if(!['perImage','perSecond','request','web_search'].includes(k))unit+=t(" / 平台计价单位");
    const row=element('tr');row.append(element('td',labels[k]||k),element('td',`${Number.isFinite(number)?number.toLocaleString('en',{maximumFractionDigits:8}):v} ${unit}`));table.append(row);
  }
  if(table.childElementCount)root.append(table);
  for(const tier of m.pricing?.overrides||[]) {
    root.append(element('p',t("当输入达到 {0} token 时适用阶梯价格：",Number(tier.min_prompt_tokens||0).toLocaleString())));
    const prices={...tier};delete prices.min_prompt_tokens;
    renderPricing(root,{...m,pricing:prices});
  }
}

function modalityName(v){return ({text:t("文本"),image:t("图片"),video:t("视频"),audio:t("音频"),file:t("文件"),speech:t("语音"),transcription:t("转写文本"),rerank:t("相关性排序"),decisions:t("结构化决策"),'3d':t("3D 资产")})[v]||v;}

document.addEventListener("starhub:languagechanged",()=>{if(selectedModel())refreshSearchControls();});

let generatedPreviewMessage=null,generatedPreviewRevision=0;
function scheduleLatestGeneratedPreview(chat,owner,ownerRevision,activation){
 // Defer until the activation completes (including synchronous account changes).
 Promise.resolve().then(async()=>{
  // A preview and guest hydration share a bounded server memory pool. Wait
  // for hydration so reopening a chat cannot starve its own startup.
  try{await StarStore.whenHydrated();}catch{return;}
  if(current!==chat||accountEmail!==owner||accountIdentityRevision!==ownerRevision||chatActivation!==activation)return;
  const message=[...chat.messages].reverse().find(m=>StarProviderMedia.references(m.mediaRefs||m.media||[]).length);
  if(!message||(message.media||[]).some(item=>item.url))return;
  if(message.mediaLoading){message.pendingPreviewActivation={chat,owner,ownerRevision,activation};return;}
  void displayGeneratedPreview(message,chat,owner);
 });
}
function invalidateGeneratedPreviews(){generatedPreviewRevision++;StarProviderMedia.invalidate();}
function releaseGeneratedMessage(message){
 const prior=message.media||[],owned=new Set(prior.filter(item=>StarProviderMedia.owns(item.url)||(item.type==='image'&&(item.fileId||item.mediaRef))).map(item=>item.url));
 const view=messageViews.get(message);for(const node of view?.row?.querySelectorAll?.('img,audio,video')||[]){node.removeAttribute('src');if(typeof node.load==='function')node.load();}messageViews.delete(message);
 StarProviderMedia.release(prior);message.media=prior.map(item=>owned.has(item.url)?Object.fromEntries(Object.entries(item).filter(([key])=>key!=='url')):item);
 if(generatedPreviewMessage===message)generatedPreviewMessage=null;
}
function releaseGeneratedPreview(chat){for(const message of chat?.messages||[])releaseGeneratedMessage(message);}
function clearGeneratedPreviews(){invalidateGeneratedPreviews();for(const chat of chats)releaseGeneratedPreview(chat);StarProviderMedia.clear();queueRender();}
async function displayGeneratedPreview(message,chat,owner,offset=0){
 const refs=StarProviderMedia.references(message.mediaRefs||message.media||[]);if(!refs.length||message.mediaLoading)return;
 invalidateGeneratedPreviews();const revision=generatedPreviewRevision,visibleRefs=refs.slice(offset,offset+4);
 if(generatedPreviewMessage&&generatedPreviewMessage!==message)releaseGeneratedMessage(generatedPreviewMessage);
 message.mediaRefs=refs;message.mediaLoading=true;queueRender();let images;
 try{
  if(/^[0-9a-f-]{36}$/.test(message.providerOperationId||''))images=await StarProviderMedia.hydrate(visibleRefs,{operationId:message.providerOperationId,expectedEmail:owner,active:()=>current===chat&&accountEmail===owner&&revision===generatedPreviewRevision});
  else{
   // Pre-receipt account history retains its original authenticated file reader.
   images=[];for(const ref of visibleRefs){if(current!==chat||accountEmail!==owner||revision!==generatedPreviewRevision||!owner||!ref.fileId)throw Error('The original image owner is unavailable.');const file=await readCloudFile(ref.fileId);if(current!==chat||accountEmail!==owner||revision!==generatedPreviewRevision)throw Error('The image account changed.');images.push({...ref,url:file.url});}
  }
  if(current!==chat||accountEmail!==owner||revision!==generatedPreviewRevision){StarProviderMedia.release(images);return;}
  if(generatedPreviewMessage&&generatedPreviewMessage!==message)releaseGeneratedMessage(generatedPreviewMessage);
  releaseGeneratedMessage(message);message.media=[...(message.media||[]).filter(item=>item.type!=='image'),...images];generatedPreviewMessage=message;message.mediaPreviewOffset=offset;delete message.imageUnavailable;delete message.imageUnavailableReason;
 }catch(error){if(current===chat&&accountEmail===owner&&revision===generatedPreviewRevision){message.imageUnavailable=true;message.imageUnavailableReason=error.previewStatus||'unavailable';showError(error.message);}}
 finally{message.mediaLoading=false;queueRender();const pending=message.pendingPreviewActivation;delete message.pendingPreviewActivation;if(pending)scheduleLatestGeneratedPreview(pending.chat,pending.owner,pending.ownerRevision,pending.activation);}
}
function appendGeneratedPreviewControl(root,message){
 const refs=StarProviderMedia.references(message.mediaRefs||message.media||[]);const loaded=(message.media||[]).some(item=>item.url&&(StarProviderMedia.owns(item.url)||/^(https:\/\/|data:image\/)/.test(item.url)));if(!refs.length||(loaded&&refs.length<=4))return;
 const button=element('button',message.mediaLoading?t('正在读取图片…'):loaded?t('读取下一组图片'):t('读取已保存图片'));button.type='button';button.disabled=Boolean(message.mediaLoading);const ownerChat=current,owner=accountEmail;button.onclick=()=>{if(current===ownerChat&&accountEmail===owner)displayGeneratedPreview(message,ownerChat,owner,loaded?((message.mediaPreviewOffset||0)+4>=refs.length?0:(message.mediaPreviewOffset||0)+4):0);};root.append(button);
}
