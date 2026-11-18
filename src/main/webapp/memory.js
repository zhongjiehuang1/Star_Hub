'use strict';
// Account memory is opt-in and stays separate from provider operation receipts.
const StarMemory=(()=>{
 let state=null,owner='',epoch=0,busy=false,statusText='',viewVersion=0,activityVersion=0;
 const zh=()=>typeof language!=='undefined'&&language==='zh';
 const label=(a,b)=>zh()?a:b;
 const element=(tag,text)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;};
 function current(){return owner&&owner===accountEmail&&epoch===accountIdentityRevision;}
 function status(message){statusText=message||'';const node=document.getElementById('memoryStatus');if(node)node.textContent=statusText;}
 function activity(token,version,zhText,enText){if(version!==activityVersion||!token||token.account!==accountEmail||token.accountRevision!==accountIdentityRevision)return;const node=document.getElementById('memoryActivity');if(node){node.textContent=label(zhText,enText);node.hidden=!node.textContent;}}
 function clearActivity(){activityVersion++;const node=document.getElementById('memoryActivity');if(node){node.textContent='';node.hidden=true;}}
 async function refresh(){
  const wanted=accountEmail,revision=accountIdentityRevision,view=viewVersion;if(!wanted)return;
  const next=await workspaceRequest({action:'listMemories'});if(viewVersion!==view||accountEmail!==wanted||accountIdentityRevision!==revision)return;
  owner=wanted;epoch=revision;state=next;render();
 }
 async function change(action,data={}){
  if(!current()||busy||!state)return;busy=true;const view=viewVersion;render();
  try{await workspaceRequest({action,memoryRevision:state.revision,...data});if(viewVersion===view&&current()){await refresh();if(viewVersion===view)status('');}}
  catch(error){if(viewVersion===view&&current()){status(errorText(error.message));if(error.code==='MEMORY_CHANGED')await refresh().catch(()=>{});}}
  finally{if(viewVersion===view){busy=false;render();}}
 }
 function render(){
  const host=document.getElementById('memoryPanel');if(!host)return;host.replaceChildren();
  const title=element('h3',label('记忆','Memory'));host.append(title);
  host.append(element('p',label('开启后，Star Hub 可从部分直接输入的文字聊天中保存称呼、偏好和明确要求记住的内容，并在之后的文字对话中使用。当前仅识别部分常见表达，不会记住所有内容。你可以随时查看、编辑、删除或关闭。','When enabled, Star Hub can save names, preferences, and explicit requests to remember from some directly entered text chats, and use them in future text chats. It currently recognizes only some common expressions and does not remember everything. You can review, edit, delete, or turn memory off at any time.')));
  host.append(element('p',label('可用上下文有限，记忆较多时优先使用较新保存的内容。','Context is limited; when there are many memories, newer saved entries are prioritized.')));
  host.append(element('p',label('记忆会发送给你选用的模型；自动提取会额外调用该模型并可能产生费用。模型的联网工具可能依据上下文搜索。系统尝试排除粘贴、拖放和附件，但无法识别所有输入方式或手动转述的内容。','Saved memory is sent to your selected model. Automatic extraction makes an additional model call and may incur charges. The model’s web tools may search using context. The system attempts to exclude pasted, dropped and attached content, but cannot identify every input method or manually retold content.')));
  if(!state||!current()){host.append(element('p',label('正在加载记忆设置…','Loading memory settings…')));const message=element('p',statusText);message.id='memoryStatus';message.setAttribute('role','status');host.append(message);return;}
  const toggle=element('button',state.enabled?label('关闭记忆','Turn off memory'):label('开启记忆','Turn on memory'));toggle.type='button';toggle.disabled=busy;toggle.setAttribute('aria-pressed',String(state.enabled));toggle.onclick=()=>change('setMemoryEnabled',{enabled:!state.enabled});host.append(toggle);
  const summary=element('p',state.enabled?label('记忆已开启。关闭后保留已存记录，但不再用于新对话。','Memory is on. Turning it off keeps saved entries but stops using them in new chats.'):label('记忆已关闭。已存记录仍可查看和删除。','Memory is off. Saved entries remain available to review and delete.'));host.append(summary);
  const list=element('ul');list.setAttribute('aria-label',label('已保存的记忆','Saved memories'));
  for(const entry of state.memories||[]){const row=element('li'),text=element('span',entry.text),edit=element('button',label('编辑','Edit')),del=element('button',label('删除','Delete'));edit.type=del.type='button';edit.disabled=del.disabled=busy;edit.onclick=()=>{const value=window.prompt(label('修改记忆','Edit memory'),entry.text);if(value!==null)change('editMemory',{memoryId:entry.id,text:value});};del.onclick=()=>{if(window.confirm(label('删除这条记忆？','Delete this memory?')))change('deleteMemory',{memoryId:entry.id});};row.append(text,document.createTextNode(' '),edit,document.createTextNode(' '),del);list.append(row);}
  if(!list.children.length)host.append(element('p',label('还没有保存的记忆。','No saved memories yet.')));else host.append(list);
  const message=element('p',statusText);message.id='memoryStatus';message.setAttribute('role','status');host.append(message);
 }
 function reset(){viewVersion++;state=null;owner='';epoch=0;busy=false;statusText='';render();}
 function open(){reset();const view=viewVersion,wanted=accountEmail,revision=accountIdentityRevision;if(!wanted)return;refresh().catch(e=>{if(viewVersion===view&&accountEmail===wanted&&accountIdentityRevision===revision)status(errorText(e.message));});}
 function install(){const settings=document.getElementById('accountSettings'),close=document.getElementById('closeAccountSettings');if(!settings||!close)return;const panel=element('section');panel.id='memoryPanel';settings.insertBefore(panel,close);const form=document.getElementById('chatForm');if(form){const notice=element('p');notice.id='memoryActivity';notice.hidden=true;notice.setAttribute('role','status');notice.setAttribute('aria-live','polite');form.before(notice);}document.addEventListener('starhub:account-settings-opening',open);document.addEventListener('starhub:account-settings-closing',reset);document.addEventListener('starhub:account-changed',()=>{reset();clearActivity();});settings.addEventListener('cancel',reset);settings.addEventListener('close',()=>{if(!settings.open)reset();});document.addEventListener('starhub:languagechanged',render);render();}
 function mayExtract(text){
  if(typeof text!=='string'||text.length>120||/[\r\n"“”‘’「」<>]/.test(text))return false;
  if(/邮件|收件|发件|转发|邮箱|信件|gmail|e-?mail|inbox|forwarded|密码|口令|验证码|密钥|身份证|护照|电话|手机|住址|地址|银行卡|疾病|诊断|药物|宗教|政治|password|secret|token|https?:\/\/|@|\d{6,}/i.test(text))return false;
  return /^(?:(?:(?:请)?(?:记住|记得))\s*我(?:叫|是|有|喜欢|爱好|偏好|习惯|使用|学习|擅长|从事|经营|专注于|计划|希望|需要)|以后(?:请)?(?:用|使用)(?:中文|汉语|英文|英语)(?=回答|回复|交流)|(?:我的名字是|请(?:以后|今后)?称呼我为?|以后请叫我|请叫我)|我(?:叫|是|有|喜欢|爱好|偏好|习惯|使用|学习|擅长|从事|经营|专注于|计划|希望|需要)|(?:please )?remember (?:that )?i (?:am|have|like|love|prefer|enjoy|use|study|work as|speciali[sz]e in|plan to|want|need)|(?:my name is|call me) |i (?:am|have|like|love|prefer|enjoy|use|study|work as|speciali[sz]e in|plan to|want|need))\s*.+$/i.test(text.normalize('NFKC').trim());
 }
 async function prepareSemantic(text,account,accountRevision,selection){
  if(!account||account!==accountEmail||accountRevision!==accountIdentityRevision||!mayExtract(text))return null;
  let snapshot,timer;try{snapshot=await Promise.race([workspaceRequest({action:'listMemories'}),new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Memory check timed out')),2000);})]);}catch(error){if(account===accountEmail&&accountRevision===accountIdentityRevision){const version=++activityVersion;activity({account,accountRevision},version,'无法及时检查记忆设置，本次未更新记忆；聊天可继续。','Could not check memory settings in time; memory was not updated. Chat can continue.');}throw error;}finally{clearTimeout(timer);}if(account!==accountEmail||accountRevision!==accountIdentityRevision||!snapshot.enabled)return null;
  return {text,account,accountRevision,revision:snapshot.revision,...selection};
 }
 async function rememberSemantic(token){
  if(!token||token.account!==accountEmail||token.accountRevision!==accountIdentityRevision)return;
  const version=++activityVersion;activity(token,version,'正在更新记忆；保存完成后新对话才能使用。','Updating memory; new chats can use it after saving completes.');
  try{
   let operation;try{operation=await StarProviderOperations.prepare('memory-extract',{provider:token.provider,model:token.model,apiKey:token.apiKey,text:token.text,expectedEmail:token.account,memoryRevision:token.revision,sourceChatId:token.sourceChatId,sourceMessageIndex:token.sourceMessageIndex});}
   catch(error){if(error.code==='PROVIDER_OPERATION_UNKNOWN'){activity(token,version,'已有记忆请求正在处理或尚未确认，本次未保存；可在请求记录中查看原请求。','A memory request is still running or unconfirmed, so this turn was not saved. Check the original request in request history.');return;}throw error;}
   const {response}=await StarProviderOperations.requestPrepared(operation,{endpoint:'memory-extract'});const result=await response.json();
   if(!response.ok){if([400,409].includes(response.status)&&['MEMORY_SOURCE_INVALID','MEMORY_CHANGED','MEMORY_DISABLED'].includes(result.code)){StarProviderOperations.discardRejected(operation);activity(token,version,'本次没有保存记忆：设置或来源已更改。','No memory was saved: settings or the source changed.');return;}if(result.operationState)StarProviderOperations.observe(operation.id,result);else StarProviderOperations.markUnknown(operation.id);throw Error('Memory extraction was not confirmed.');}
   StarProviderOperations.observe(operation.id,result);
   if(token.account!==accountEmail||token.accountRevision!==accountIdentityRevision)return;
   try{const saved=await workspaceRequest({action:'saveSemanticMemory',memoryRevision:token.revision,extraction:operation.body});StarProviderOperations.acknowledge(operation.id);activity(token,version,saved.saved?'记忆已更新，后续文字对话可使用。':'没有新增记忆。',saved.saved?'Memory updated and available to future text chats.':'No new memory was saved.');}
   catch(error){if(error.code==='MEMORY_CHANGED'||error.code==='MEMORY_DISABLED'||error.code==='MEMORY_SOURCE_INVALID'){StarProviderOperations.acknowledge(operation.id);activity(token,version,'本次没有保存记忆：设置或来源已更改。','No memory was saved: settings or the source changed.');}else throw error;}
  }catch(error){activity(token,version,'记忆更新未确认，请在请求记录中查看原结果；聊天可继续。','Memory update is unconfirmed. Check the original request in request history; chat can continue.');throw error;}
 }
 return {install,refresh,prepareSemantic,rememberSemantic};
})();
StarMemory.install();
