'use strict';
const $ = id => document.getElementById(id);
let providers = [], catalog, rawCatalog, busy = false, ready = false, realtimeActive = false, realtimeConnected = false;
let libraryPage = 0, detailModel;
const effortNames = {none:t("关闭"),minimal:t("最低"),low:t("低"),medium:t("中"),high:t("高"),xhigh:t("超高"),max:t("最高")};
const costNames = {"free-key":t("免费模型 · 需 Key"),paid:t("按量计费 · 需 Key"),account:t("费用 / 免费额度取决于账户"),keyless:t("免费 · 无需 Key")};
let chats = [], current, lastDeleted, cloudHydrating=false;
const restoredSelection=StarStore.selection();
const keys = new Map(), settings = new Map(Object.entries(restoredSelection.models&&typeof restoredSelection.models==='object'?restoredSelection.models:{}));
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
  if (current) current.draft = $('prompt').value;
  current = chat;
  $('prompt').value = chat.draft || '';
  render(); showError(''); $('prompt').focus({preventScroll:true});
  if(accountEmail&&chat.hydrated===false&&!chat.hydrating){cloudHydrating=true;chat.hydrating=true;updateConnection();hydrateChat(chat).catch(e=>showError(e.message)).finally(()=>{chat.hydrating=false;if(current===chat)cloudHydrating=false;updateConnection();queueRender();});}else cloudHydrating=Boolean(chat.hydrating);
}
async function deleteChat(chat) {
  if (busy || realtimeActive) return;
  if (current) current.draft = $('prompt').value;
  try{if(accountEmail)await deleteCloudChat(chat);}catch(error){showError(error.message);return;}
  const index = chats.indexOf(chat);
  lastDeleted = {chat, index};
  chats.splice(index, 1);
  if (current === chat) {
    current = null;
    if (chats.length) activateChat(chats[Math.min(index, chats.length - 1)]);
    else newChat();
  } else render();
  $('deletedNotice').hidden = false;
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
  if($('model').value)settings.set(id, $('model').value);
  if(ready&&$('model').value)StarStore.saveSelection(id,settings);
  updateConnection();if(typeof refreshConnectionSummary==='function')refreshConnectionSummary();
}
function selectedModel() { return catalog?.models.find(m => m.provider === $('provider').value && m.id === $('model').value); }
function selectProvider(wanted) {
  const p = providers.find(p => p.id === $('provider').value);
  const needle = $('modelSearch').value.trim().toLowerCase();
  const models = catalog.models.filter(m => m.provider === p.id && `${m.name} ${m.id} ${m.vendorName}`.toLowerCase().includes(needle));
  $('model').replaceChildren();
  const groups = new Map();
  for (const m of models) {
    const name = `${m.vendorName} · ${m.family}`;
    if (!groups.has(name)) { const g = document.createElement('optgroup'); g.label = name; groups.set(name,g); $('model').append(g); }
    groups.get(name).append(new Option(m.name+(m.accessLabel?` · ${m.accessLabel}`:''),m.id));
  }
  const choice = typeof wanted === 'string' ? wanted : settings.get(p.id);
  if (models.some(m => m.id === choice)) $('model').value = choice;
  $('model').disabled = !models.length;
  $('apiKey').value = keys.get(p.id) || '';
  $('apiKey').type = 'password'; $('toggleKey').textContent = t("显示"); $('toggleKey').setAttribute('aria-label',t("显示 API Key")); $('toggleKey').setAttribute('aria-pressed','false');
  configureModel(); showError('');
}
function configureModel() {
  const m = selectedModel();
  $('task').replaceChildren(); $('effort').replaceChildren(new Option(t("平台默认"),'default'));
  if (!m) { document.dispatchEvent(new CustomEvent('starhub:modelchanged')); $('modelSettings').hidden=true; $('selectedSummary').textContent = t("没有匹配模型，请调整搜索。"); for(const id of ['batchPanel','realtimePanel','taskControls'])$(id).hidden=true; $('chatForm').hidden=false; $('prompt').disabled=false; updateConnection(); return; }
  $('selectedSummary').textContent = `${m.vendorName} · ${m.family} · ${uiText(costNames[m.cost])}`;
  $('keyLabel').textContent = m.keyLabel;
  const tasks=m.categories.filter(c => c!=='vision');
  $('task').replaceChildren(...tasks.map(c => new Option(catalog.categories[c],c)));
  $('effort').append(...m.reasoning.map(e => new Option(`${uiText(effortNames[e]) || e} (${e})`,e)));
  $('effort').disabled = !m.reasoning.length;
  $('effortHelp').textContent = m.execution==='external' ? t("请选择符合当前调用方式的模型。") : m.reasoning.length ? t("仅显示该接入项公开支持的档位；高强度通常更慢、费用更高。") : m.reasoningKnown ? t("支持推理，当前平台未公开可调强度，使用默认。") : t("未公开可调推理档位，使用模型默认。");
  configureTask(); saveSettings();
}
function refreshSearchControls() {
  const m=selectedModel(), task=$('task').value;
  const searchSupported=task==='chat' && ((m?.provider==='openai' && /^(gpt-[56]|o[134])/.test(m.id)) || m?.provider==='anthropic' || m?.provider==='gemini' || (m?.provider==='deepseek' && ['deepseek-v4-pro','deepseek-flash'].includes(m.id)) || m?.provider==='moonshot' || (m?.provider==='xai' && m.id==='grok-4.7') || (m?.provider==='qwen' && ['qwen3.8-max','qwen3.8-flash','qwen3.7-plus'].includes(m.id)));
  $('searchControls').hidden=task!=='chat'; $('webSearch').disabled=!searchSupported;
  $('searchLabel').textContent=language==='zh'?'联网搜索':'Web search';
  $('searchHelp').textContent=language==='zh'?(searchSupported?(m.provider==='qwen'?'模型按需搜索，可能额外收费；当前接口不返回来源列表。':(m.provider==='moonshot'||m.provider==='deepseek'&&m.id==='deepseek-flash'?'每次发送都会搜索，可能额外收费。':'模型按需搜索，可能产生额外费用。')):'此模型的联网搜索尚未接入。'):(searchSupported?(m.provider==='qwen'?'Search may cost extra; this interface does not return source lists.':(m.provider==='moonshot'||m.provider==='deepseek'&&m.id==='deepseek-flash'?'Each message triggers a paid search.':'The model may search when needed. Searches can incur extra charges.')):'Web search is not integrated for this model.');
}
function configureTask() {
  const m=selectedModel(), task=$('task').value;
  refreshSearchControls();
  $('taskControls').hidden=(task==='chat'&&!m.inputs.includes('image'))||m?.batch; $('chatForm').hidden=Boolean(m?.batch); $('batchPanel').hidden=!m?.batch; $('realtimePanel').hidden=task!=='realtime';   $('generationOptions').replaceChildren(); $('inputFile').value=''; $('prompt').disabled=busy;
  $('fileLabel').hidden=!(['image','video','3d','transcription','audiofx'].includes(task)||(['chat','realtime'].includes(task)&&m.inputs.includes('image')));
  $('inputFile').multiple=(task==='chat'&&m.inputs.includes('image'))||(task==='image'&&(m.referenceLimits?.image||1)>1);
  $('fileLabel').firstChild.textContent=task==='image'?t("参考图片（{0}–{1} 张，附件合计最多 4 MB）",m.referenceLimits?.minImages||0,m.referenceLimits?.image||1):task==='video'?t("首帧图片（附件合计最多 4 MB）"):t("参考文件（最多 4 MB）");
  if(['image','video','3d'].includes(task))$('fileLabel').hidden=!(m.referenceLimits?.image>0);
  $('inputFile').accept=['transcription','audiofx'].includes(task)?'audio/*,.mp4,.m4a':'image/png,image/jpeg,image/webp';
  $('documentsLabel').hidden=task!=='rerank'; $('questionLabel').hidden=task!=='decisions';
  const helps={chat:t("可附一张 PNG、JPEG 或 WebP 图片。图片保留在本页对话上下文中；更换为不支持图片的模型时请新建对话。"),image:t("描述想生成的图片，也可上传参考图。部分编辑模型必须提供图片。"),video:t("描述视频内容。异步任务会显示任务 ID，可手动检查进度；不要重复提交同一任务。"),speech:t("输入需要朗读的文字。"),music:t("描述音乐风格、节奏或音效内容。"),audio:t("描述要生成的语音或音乐，返回后可直接播放。"),audiofx:t("上传源音频。人声隔离至少需要 4.6 秒；变声可选择声音；音轨分离返回 ZIP。"),transcription:t("上传音频后发送，文本框可填写备注。"),embeddings:t("输入需要转换为向量的文字，结果以 JSON 展示。"),rerank:t("主输入框填写查询内容，下方每行填写一条待排序文档。"),decisions:t("主输入框填写待分析材料，再填写需要判断的是非问题。"),'3d':t("描述需要生成的 3D 资产；部分模型要求参考图片。"),realtime:t("点击连接后可以开始说话；对话型模型也支持在下方输入文字，转写模型只接收语音。")};
  $('taskHelp').textContent=helps[task]||'';
  $('prompt').placeholder=task==='chat'?t("有什么想法，尽管说说…"):task==='transcription'?t("可选：本次转写备注"):t("输入生成描述、文本或查询内容…");
  $('prompt').required=!['transcription','audiofx'].includes(task);
  if(['speech','music','realtime','audiofx'].includes(task)&&m.voices.length){const label=document.createElement('label');label.textContent=t("声音");const select=document.createElement('select');select.id='voice';select.append(...m.voices.map(v=>new Option(typeof v==='string'?v:JSON.stringify(v),typeof v==='string'?v:JSON.stringify(v))));label.append(select);$('generationOptions').append(label);}
  updateConnection(); render(); document.dispatchEvent(new CustomEvent('starhub:modelchanged'));
}
const messageViews = new WeakMap();
let renderedChat, historySignature = '', pendingRender = 0;
function queueRender() {
  if (!pendingRender) pendingRender = requestAnimationFrame(() => { pendingRender = 0; render(); });
}
function render({toBottom = false} = {}) {
  if (pendingRender) { cancelAnimationFrame(pendingRender); pendingRender = 0; }
  const pane = $('conversation'), messages = $('messages');
  const follow = toBottom || renderedChat !== current || pane.scrollHeight - pane.clientHeight - pane.scrollTop < 80;
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
  if (renderedChat !== current) messages.replaceChildren();
  let index = 0;
  for (const message of current.messages) {
    // Text deltas reuse their row; media/metadata changes replace only that message.
    const state = [message.media, message.details, message.status, message.jobId, message.image, message.seconds, message.providerName, message.model, message.role, message.warning, language, message.attachmentOmitted, message.imageUnavailable, message.incomplete, message.images, message.attachments, Boolean(message.startedAt), Boolean(message.reasoningSummary), message.thinkingSeconds!=null];
    let view = messageViews.get(message);
    if (!view || state.some((value, i) => value !== view.state[i])) {

    const row = document.createElement('article'); row.className = `message ${message.role}`;
    const avatar = document.createElement('div'); avatar.className = 'avatar'; avatar.textContent = message.role === 'user' ? t("你") : '✳';
    const body = document.createElement('div'); body.className = 'message-body';
    const label = document.createElement('div'); label.className = 'message-label'; label.textContent = message.role === 'user' ? t("你") : `${message.providerName} · ${message.model}`;
    const content = document.createElement('div'); content.className = 'message-content';
    body.append(label);
    if(message.role==='assistant'&&(message.startedAt||message.reasoningSummary||message.thinkingSeconds!=null)){
      const reasoning=document.createElement('details');reasoning.className='reasoning-block';reasoning.open=true;
      const heading=document.createElement('summary');heading.className='reasoning-heading';const text=document.createElement('div');text.className='reasoning-text';reasoning.append(heading,text);body.append(reasoning);
    }
    body.append(content);
    if(message.attachmentOmitted)body.append(element('p',t("附件未保存在本机。"),'reply-warning'));
    if(message.imageUnavailable)body.append(element('p',t("图片已被删除或暂时无法读取；如在回收箱中，可恢复后刷新页面。"),'reply-warning'));
    if(message.incomplete)body.append(element('p',t("这条回复在保存时尚未完成。"),'reply-warning'));
    if(message.warning)body.append(element('p',errorText(message.warning),'reply-warning'));if(message.details?.webSearchUsed)body.append(element('p',t('已进行联网搜索。'),'search-used'));if(message.details?.googleWidget){const frame=document.createElement('iframe');frame.className='google-suggestions';frame.title=t('Google 搜索建议');frame.setAttribute('sandbox','allow-popups allow-popups-to-escape-sandbox');frame.srcdoc=message.details.googleWidget;body.append(frame);}
    appendMedia(body,message.media);if(message.details&&Object.keys(message.details).length){const details=element('details',undefined,'result-details');details.append(element('summary',t("查看完整结果 / 参数反馈")),element('pre',JSON.stringify(message.details,null,2)));const save=element('button',t("导出详细结果"));save.onclick=()=>downloadJson(message.details,'Star-Hub-result.json');details.append(save);body.append(details);}if(message.image)appendMedia(body,[{type:'image',url:message.image}]);if(message.images?.length)appendMedia(body,message.images.map(url=>({type:'image',url}))); if(message.attachments?.length)appendMedia(body,message.attachments.map(url=>({type:url.startsWith('data:image/')?'image':url.startsWith('data:audio/')?'audio':'file',url})));
    if(message.jobId && !['completed','failed','cancelled','expired'].includes(message.status)) { const check=document.createElement('button');check.className='text-button';check.textContent=t("检查视频进度");check.disabled=busy;check.addEventListener('click',()=>checkVideo(message));body.append(check); }
    if (message.role === 'assistant') {
      const tools = document.createElement('div'); tools.className = 'message-tools';
      const copy = document.createElement('button'); copy.textContent = t("复制回答");
      copy.addEventListener('click', async () => { try { await navigator.clipboard.writeText(message.content); copy.textContent = t("已复制"); setTimeout(() => copy.textContent = t("复制回答"), 1800); } catch { showError(t("无法访问剪贴板，请直接选择回答文本复制。")); } });
      const timing = document.createElement('span'); timing.className='reply-state';
      tools.append(copy, timing); body.append(tools);
    }
    row.append(avatar, body);
    view = {row, content, state, reasoning:body.querySelector('.reasoning-block'), timing:body.querySelector('.reply-state'), check: body.querySelector('.text-button')};
    messageViews.set(message, view);
    }
    const shown=message.displayText ?? message.content;
    const citations=message.revealing?[]:(message.details?.webCitations||[]);
    const renderKey=shown+'\u0000'+JSON.stringify(citations);
    if (view.renderKey !== renderKey) {
      if(message.role==='assistant') renderMarkdown(view.content,shown,citations);
      else view.content.textContent=shown;
      view.renderKey=renderKey;
    }
    view.content.classList.toggle('reply-typing',Boolean(message.revealing));
    if(view.reasoning){const elapsed=message.thinkingSeconds??((performance.now()-(message.startedAt||performance.now()))/1000);view.reasoning.querySelector('summary').textContent=`${message.thinkingSeconds==null?t('思考中'):t('已思考')} · ${Number(elapsed).toFixed(1)}s`;view.reasoning.classList.toggle('thinking-active',message.thinkingSeconds==null&&Boolean(message.startedAt));view.reasoning.querySelector('.reasoning-text').textContent=message.reasoningSummary||'';}
    if(view.timing)view.timing.textContent=message.revealing?`${t("正在回复…")} ${((performance.now()-(message.startedAt||performance.now()))/1000).toFixed(1)}s`:message.seconds==='realtime'?t("实时"):message.seconds?`${message.seconds}s`:'';
    if (view.check) view.check.disabled = busy;
    if (messages.children[index] !== view.row) messages.insertBefore(view.row, messages.children[index] || null);
    index++;
  }
  while (messages.children.length > index) messages.lastElementChild.remove();
  renderedChat = current;
  $('exportChat').disabled = !current.messages.length || busy;
  if (follow) pane.scrollTop = pane.scrollHeight;
  if(typeof scheduleHistorySave==='function')scheduleHistorySave();
}
function setBusy(value, toBottom = false) {
  busy = value; $('busyStatus').hidden = !value; $('messages').setAttribute('aria-busy',String(value));
  for (const id of ['provider','model','apiKey','newChat','prompt','undoDelete','task','effort','browseModels','modelDetails','modelSettings','modelSearch','inputFile','documents','question']) $(id).disabled = value;
  document.querySelectorAll('.prompt-card').forEach(b => b.disabled = value);
  if(!value) $('effort').disabled=!selectedModel()?.reasoning.length;
  updateConnection(); if(typeof refreshConnectionSummary==='function')refreshConnectionSummary(); render({toBottom});
}
$('chatForm').addEventListener('submit', async event => {
  event.preventDefault(); if (busy || cloudHydrating || !ready || publicInsecure) return;
  if($('task').value==='realtime'){document.dispatchEvent(new CustomEvent('starhub:realtime-text'));return;}
  if(selectedModel()?.execution==='external'){showError(selectedModel().executionNote);return;}
  if(selectedModel()?.batch)return;
  const task=$('task').value;
  const text = $('prompt').value.trim() || (['transcription','audiofx'].includes(task) ? t("处理上传的音频") : ''); if (!text) return;
  if (!$('apiKey').value.trim() || !$('model').value.trim()) {
    showError(t("请选择模型，并填写对应服务商的 API Key。")); openSetup(); return;
  }
  if (current.messages.length >= 59) { showError(t("当前对话已达到长度限制，请新建对话。")); return; }
  const p = providers.find(p => p.id === $('provider').value), requestAccount=accountEmail;
  const model = $('model').value.trim();
  setBusy(true);
  let file,files,parameters; try { parameters=await collectParameters(); files=await readInputFiles();file=files[0];if(files.length>(selectedModel()?.referenceLimits?.image||1)&&task==='image')throw new Error(t("参考图片数量超过此模型限制。")); } catch(error) {setBusy(false);showError(error.message);return;}
  if(['transcription','audiofx'].includes(task)&&!file){setBusy(false);showError(t("请上传音频文件。"));return;}
  const options={};
  const oldTitle = current.title;
  if (!current.messages.length) current.title = text.slice(0, 20);
  let fileIds=[];try{if(accountEmail&&files.length)fileIds=await uploadSubmittedFiles(files,[...$('inputFile').files]);}catch(error){setBusy(false);showError(error.message);return;}
  const userMessage={role:'user',content:text,images:task==='chat'?files:[],imageCount:task==='chat'?files.length:0,fileIds,attachments:task==='chat'?[]:files};
  StarStore.savePending(null);
  current.messages.push(userMessage);
  if(accountEmail)saveCloudChats().catch(()=>{});
  const messages=current.messages.filter(m=>!m.media?.length&&!m.jobId).map(({role,content,image,images})=>({role,content,...(image?{image}:{}),...(images?.length?{images}:{})}));
  const answer={role:'assistant',content:'',provider:p.id,providerName:p.name,model,startedAt:performance.now()};
  const reveal=new ReplyReveal(answer);current.messages.push(answer);
  $('prompt').value='';showError('');setBusy(true,true);$('busyStatus').textContent=t("正在连接模型，回复将逐步显示…");
  $('sidebar').classList.remove('open');$('menuToggle').setAttribute('aria-expanded','false');
  const started=performance.now(),controller=new AbortController();answer.startedAt=started;const timer=setInterval(queueRender,250);
  const timeout=setTimeout(()=>controller.abort(),190000);
  try {
    const response=await fetch('api/run',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web','Accept':task==='chat'?'text/event-stream':'application/json'},signal:controller.signal,
      body:JSON.stringify({provider:p.id,model,task,stream:task==='chat',effort:$('effort').value,prompt:text,options,parameters,files:task==='image'?files:undefined,voice:$('voice')?.value||'',file:['chat','image'].includes(task)?'':file||'',fileFormat:$('inputFile').files[0]?.name.split('.').pop().toLowerCase(),documents:$('documents').value.split('\n').filter(s=>s.trim()),question:$('question').value,apiKey:$('apiKey').value.trim(),expectedEmail:requestAccount,timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC',webSearch:task==='chat'&&!$('webSearch').disabled&&$('webSearch').checked,messages})});
    let result;
    if(response.ok&&response.headers.get('Content-Type')?.includes('text/event-stream')){
      result=await readReplyEvents(response,text=>{if(answer.thinkingSeconds==null)answer.thinkingSeconds=(performance.now()-started)/1000;reveal.append(text);$('busyStatus').textContent=t("正在接收回复…");},summary=>{answer.reasoningSummary=(answer.reasoningSummary||'')+summary;queueRender();});
    }else{
      result=await response.json().catch(()=>({error:t("服务暂时不可用（HTTP {0}），请稍后重试。",response.status)}));
      if(!response.ok||!result.text)throw new Error(result.error||t("模型没有返回文本。"));
      if(answer.thinkingSeconds==null)answer.thinkingSeconds=(performance.now()-started)/1000;reveal.append(task==='image'||task==='speech'?uiText(result.text):result.text);
    }
    clearTimeout(timeout);
    if(!answer.content)throw new Error(t("模型没有返回文本。"));
    Object.assign(answer,{media:result.media,details:result.details,jobId:result.jobId,status:result.status,warning:result.warning,imageSaveFailed:Boolean(result.imageSaveFailed)});
    $('busyStatus').textContent=t("正在显示回复…");await reveal.finish();
    answer.seconds=((performance.now()-started)/1000).toFixed(1);$('inputFile').value='';StarStore.savePending(null);
  }catch(error){
    clearTimeout(timeout);
    if(answer.content){answer.warning=t("回复未完成，已保留收到的文字。");await reveal.finish();answer.seconds=((performance.now()-started)/1000).toFixed(1);}
    else {await reveal.finish();current.messages.splice(current.messages.indexOf(userMessage),2);current.title=oldTitle;$('prompt').value=text;const inputFiles=[...$('inputFile').files];if(inputFiles.length&&!StarStore.savePending(files.map((data,i)=>({name:inputFiles[i]?.name||'file',type:inputFiles[i]?.type||'',data}))))showError(t('无法保存临时会话，请检查浏览器存储空间。'));}
    showError(error.name==='AbortError'?t("等待超时，已收到的文字会保留。服务商可能仍在处理请求。"):error.message==='Failed to fetch'?t("网络连接失败，请检查网络后重试。"):error.message);
  }finally{clearInterval(timer);delete answer.startedAt;clearTimeout(timeout);setBusy(false);$('prompt').focus({preventScroll:true});}

});
$('prompt').addEventListener('keydown', event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); $('chatForm').requestSubmit(); } });
$('newChat').addEventListener('click', newChat);
$('undoDelete').addEventListener('click', () => {
  if (busy || !lastDeleted) return;
  const {chat, index} = lastDeleted;
  chats.splice(Math.min(index, chats.length), 0, chat);
  lastDeleted = null; $('deletedNotice').hidden = true; activateChat(chat);
});
document.addEventListener('keydown', event => { if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); newChat(); } });
$('provider').addEventListener('change', () => { $('modelSearch').value=''; selectProvider(); });
$('model').addEventListener('change', configureModel);
$('task').addEventListener('change',configureTask);
$('modelSearch').addEventListener('input',()=>selectProvider());
$('apiKey').addEventListener('input', saveSettings);
$('toggleKey').addEventListener('click', () => { const show = $('apiKey').type === 'password'; $('apiKey').type = show ? 'text' : 'password'; $('toggleKey').textContent = show ? t("隐藏") : t("显示"); $('toggleKey').setAttribute('aria-label', show ? t("隐藏 API Key") : t("显示 API Key")); $('toggleKey').setAttribute('aria-pressed', String(show)); });
$('menuToggle').addEventListener('click', () => { const open = $('sidebar').classList.toggle('open'); $('menuToggle').setAttribute('aria-expanded', String(open)); });
document.querySelectorAll('.prompt-card').forEach(card => card.addEventListener('click', () => { $('prompt').value = card.dataset.prompt; $('prompt').focus({preventScroll:true}); }));
$('exportChat').addEventListener('click', () => {
  const text = `# ${current.title}\n\n` + current.messages.map(m => `## ${m.role === 'user' ? t("你") : `${m.providerName} · ${m.model}`}\n\n${m.content}`).join('\n\n');
  const url = URL.createObjectURL(new Blob([text], {type:'text/markdown;charset=utf-8'}));
  const a = document.createElement('a'); a.href = url; a.download = 'Star-Hub-conversation.md'; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000);
});
async function init() {
  await new Promise(resolve=>document.addEventListener("DOMContentLoaded",resolve,{once:true}));
  await restoreAccount();
  await StarStore.initGuest();if(!accountEmail)for(const [provider,key] of StarStore.loadKeys())keys.set(provider,key);
  let cloudLoaded=false;if(accountEmail){try{cloudLoaded=await initCloudSession();}catch(error){showError(error.message);return;}}
  const restored=cloudLoaded?null:StarStore.loadHistory();
  if(cloudLoaded){}else if(restored){chats=restored.chats;current=undefined;activateChat(chats.find(c=>c.id===restored.currentId)||chats[0]);}else newChat();
  try {
    const response = await fetch('api/catalog'); if (!response.ok) throw new Error(t("无法加载服务商，请刷新页面重试。"));
    rawCatalog = await response.json(); catalog=localizedCatalog(rawCatalog); providers=catalog.providers; setupLibrary(); $('provider').replaceChildren(...providers.map(p => new Option(p.name, p.id)));
    ready = true; $('provider').disabled = false; $('browseModels').disabled=false; if(providers.some(p=>p.id===restoredSelection.provider))$('provider').value=restoredSelection.provider;selectProvider();
    $('serviceStatus').classList.add('online'); $('serviceStatus').lastChild.textContent = t(" 服务已就绪");
    document.dispatchEvent(new CustomEvent('starhub:ready'));
    if (publicInsecure) showError(t("请通过 https://starhub.zhongjiehuang.com 访问后填写密钥。"));
  } catch (error) { showError(error.message); $('serviceStatus').lastChild.textContent = t(" 连接失败"); }
}
init();
function element(tag,text,className){const e=document.createElement(tag);if(text!==undefined)e.textContent=['pre','code'].includes(tag)?text:uiText(text);if(className)e.className=className;return e;}
function setupLibrary(){
  $('catalogCount').textContent=t("{0} 项",catalog.models.length.toLocaleString());
  $('libraryStats').textContent=t("{0} 个接入项 · {1} 个厂商 / 来源 · {2} 类能力 · 更新 {3}",catalog.models.length.toLocaleString(),new Set(catalog.models.map(m=>m.vendor)).size,Object.keys(catalog.categories).length,catalog.updatedAt);
  $('keylessNotice').textContent=catalog.noKeyNotice;$('coverageNotice').textContent=catalog.coverage;
  const populate=(id,entries,all)=>$(id).replaceChildren(new Option(all,''),...entries.map(([v,n])=>new Option(n,v)));
  const vendors=new Map(catalog.models.map(m=>[m.vendor,m.vendorName]));
  populate('filterVendor',[...vendors].sort((a,b)=>a[1].localeCompare(b[1],'zh')),t("全部厂商 / 来源"));
  populate('filterCategory',Object.entries(catalog.categories),t("全部能力"));
  populate('filterSpecialty',[...new Set(catalog.models.flatMap(m=>m.subcategories))].sort().map(v=>[v,v]),t("全部细分用途"));
  const inputNames={text:t("文本"),image:t("图片"),video:t("视频"),audio:t("音频"),file:t("文件")};
  populate('filterInput',[...new Set(catalog.models.flatMap(m=>m.inputs))].sort().map(v=>[v,inputNames[v]||v]),t("全部输入类型"));
  populate('filterProvider',providers.map(p=>[p.id,p.name]),t("全部官方厂商"));
  updateFamilies();
  const efforts=[...new Set(catalog.models.flatMap(m=>m.reasoning))];
  $('filterReasoning').append(...efforts.map(e=>new Option(t("支持 {0} ({1})",uiText(effortNames[e])||e,e),e)));
}
function updateFamilies(){
  const prior=$('filterFamily').value;const vendor=$('filterVendor').value;
  const families=[...new Set(catalog.models.filter(m=>!vendor||m.vendor===vendor).map(m=>m.family))].sort();
  $('filterFamily').replaceChildren(new Option(t("全部系列"),''),...families.map(f=>new Option(f,f)));
  if(families.includes(prior))$('filterFamily').value=prior;
}
function filteredModels(){
  const q=$('librarySearch').value.toLowerCase().trim(),v=$('filterVendor').value,c=$('filterCategory').value,f=$('filterFamily').value,p=$('filterProvider').value,cost=$('filterCost').value,reason=$('filterReasoning').value;
  return catalog.models.filter(m=>(!$('filterExecution').value||($('filterExecution').value==='batch'?m.batch:$('filterExecution').value==='realtime'?m.categories.includes('realtime'):m.execution===$('filterExecution').value))&&(!$('filterSpecialty').value||m.subcategories.includes($('filterSpecialty').value))&&(!$('filterInput').value||m.inputs.includes($('filterInput').value))&&(!q||`${m.name} ${m.id} ${m.vendorName} ${m.family} ${m.description} ${m.tags.join(' ')}`.toLowerCase().includes(q))&&(!v||m.vendor===v)&&(!c||m.categories.includes(c))&&(!f||m.family===f)&&(!p||m.provider===p)&&(!cost||(cost==='keyless'?!m.requiresKey:m.cost===cost))&&(!reason||(reason==='adjustable'?m.reasoning.length:reason==='reasoning'?m.reasoningKnown:reason==='none'?!m.reasoningKnown:m.reasoning.includes(reason))));
}
function renderLibrary(){
  const models=filteredModels(),pages=Math.max(1,Math.ceil(models.length/24));libraryPage=Math.min(libraryPage,pages-1);
  $('resultCount').textContent=t("找到 {0} 个接入项",models.length.toLocaleString());$('pageInfo').textContent=`${libraryPage+1} / ${pages}`;$('prevPage').disabled=libraryPage===0;$('nextPage').disabled=libraryPage>=pages-1;
  $('modelCards').replaceChildren();
  for(const m of models.slice(libraryPage*24,libraryPage*24+24)){
    const card=element('article',undefined,'model-card');
    card.append(element('div',`${m.vendorName} / ${m.family}`,'model-kicker'),element('h3',m.name),element('code',m.id),element('p',m.description,'model-intro'));
    const badges=element('div',undefined,'model-tags');for(const c of m.categories)badges.append(element('span',catalog.categories[c]||c));
    badges.append(element('span',uiText(costNames[m.cost]),m.cost==='free-key'?'free-badge':''));if(['down','degraded'].includes(m.status))badges.append(element('span',m.status==='down'?t("平台报告故障"):t("平台报告降级")));if(m.batch)badges.append(element('span',t("异步批处理")));if(m.categories.includes('realtime'))badges.append(element('span',t("实时连接")));
    if(m.accessLabel)badges.append(element('span',m.accessLabel,'restricted-badge'));
    card.append(badges,element('p',`${m.providerName} · ${m.reasoning.length ? t("推理 ")+m.reasoning.map(e=>uiText(effortNames[e])||e).join(' / '):m.reasoningKnown?t("推理：平台默认"):t("未公开推理档位")}`,'model-route'));
    const actions=element('div',undefined,'model-actions');const details=element('button',t("介绍与费用"));details.addEventListener('click',()=>showDetails(m));const use=element('button',m.execution==='external'?t("查看接入说明"):t("选择此模型"));use.addEventListener('click',()=>m.execution==='external'?showDetails(m):chooseModel(m));actions.append(details,use);card.append(actions);$('modelCards').append(card);
  }
  if(!models.length)$('modelCards').append(element('p',$('filterCost').value==='keyless'?catalog.noKeyNotice:t("没有匹配的模型，试试减少筛选条件。"),'empty-models'));
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
  $('modelSearch').value='';$('provider').value=m.provider;selectProvider(m.id);
  $('detailDialog').close();$('modelLibrary').close();$('sidebar').classList.add('open');$('menuToggle').setAttribute('aria-expanded','true');$('connectionSettings').open=true;$('apiKey').focus();
}

$('browseModels').addEventListener('click',()=>{renderLibrary();$('modelLibrary').showModal();});
$('closeLibrary').addEventListener('click',()=>$('modelLibrary').close());$('closeDetails').addEventListener('click',()=>$('detailDialog').close());
$('modelDetails').addEventListener('click',()=>{const m=selectedModel();if(m)showDetails(m);});$('useDetailModel').addEventListener('click',()=>chooseModel(detailModel));
for(const id of ['librarySearch','filterVendor','filterCategory','filterSpecialty','filterInput','filterFamily','filterProvider','filterReasoning','filterCost','filterExecution'])$(id).addEventListener(id==='librarySearch'?'input':'change',()=>{libraryPage=0;if(id==='filterVendor')updateFamilies();renderLibrary();});
$('resetFilters').addEventListener('click',()=>{for(const id of ['librarySearch','filterVendor','filterCategory','filterSpecialty','filterInput','filterFamily','filterProvider','filterReasoning','filterCost','filterExecution'])$(id).value='';updateFamilies();libraryPage=0;renderLibrary();});
$('prevPage').addEventListener('click',()=>{libraryPage--;renderLibrary();$('modelCards').scrollIntoView({block:'start'});});$('nextPage').addEventListener('click',()=>{libraryPage++;renderLibrary();$('modelCards').scrollIntoView({block:'start'});});
function readFileData(file){
  if(!file)return Promise.resolve('');if(file.size>4*1024*1024)return Promise.reject(new Error(t("单个文件最多 4 MB。")));
  return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=()=>reject(new Error(t("文件读取失败。")));reader.readAsDataURL(file);});
}
async function readInputFiles(){const files=[...$('inputFile').files];const extra=[...document.querySelectorAll('#parameterFields input[type="file"]:not(:disabled)')].flatMap(i=>[...i.files]);if([...files,...extra].reduce((sum,f)=>sum+f.size,0)>4*1024*1024)throw new Error(t("本次上传的附件合计最多 4 MB。"));return Promise.all(files.map(readFileData));}
async function readInputFile(){return (await readInputFiles())[0]||'';}
function appendMedia(root,media){for(const item of media||[]){if(!/^(https:\/\/|data:((image|audio|video)\/|application\/(zip|octet-stream);base64,))/.test(item.url))continue;let e;if(item.type==='image'){e=element('img');e.alt=t("模型生成的图片");e.loading='lazy';}else if(['audio','video'].includes(item.type)){e=element(item.type);e.controls=true;e.preload='metadata';}else{e=element('a',t("打开生成的文件 ↗"));e.href=item.url;e.target='_blank';e.rel='noopener noreferrer';if(item.filename)e.download=item.filename;}if(e.tagName!=='A')e.src=item.url;e.className='generated-media';root.append(e);}}
async function checkVideo(message){
  if(busy)return;const key=keys.get(message.provider);if(!key){showError(t("请先填写此视频任务对应平台的 API Key。"));return;}
  setBusy(true);showError('');
  try{const response=await fetch('api/run',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify({provider:message.provider,model:message.model,apiKey:key,task:'video',jobId:message.jobId})});const r=await response.json();if(!response.ok)throw new Error(r.error||t("查询失败"));Object.assign(message,{content:r.text,media:r.media,status:r.status});}catch(e){showError(e.message);}finally{setBusy(false);}
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

function modalityName(v){return ({text:t("文本"),image:t("图片"),video:t("视频"),audio:t("音频"),file:t("文件"),speech:t("语音"),transcription:t("转写文本"),embeddings:t("向量"),embedding:t("向量"),rerank:t("相关性排序"),decisions:t("结构化决策"),'3d':t("3D 资产")})[v]||v;}

document.addEventListener("starhub:languagechanged",()=>{if(selectedModel())refreshSearchControls();});
