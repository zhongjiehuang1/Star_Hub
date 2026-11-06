'use strict';
let historySaveTimer,historySaveFailed=false,experienceReady=false;
let wizardStep=1,wizardGeneration=0,wizardChecking=false,wizardVerified=false,wizardBlocked=false,wizardController;
const verifiedKeys=new Map();
function persistHistory(){
  clearTimeout(historySaveTimer);if(!experienceReady)return;
  if(current)current.draft=$('prompt').value;
  if(accountEmail){saveCloudChats().catch(()=>{});return;}
  const saved=StarStore.saveHistory(chats,current?.id);if(!saved&&!historySaveFailed)showError(t('无法保存临时会话，请检查浏览器存储空间。'));historySaveFailed=!saved;
}
function scheduleHistorySave(){
  if(!experienceReady||busy||realtimeActive)return;
  clearTimeout(historySaveTimer);historySaveTimer=setTimeout(persistHistory,400);
}
function refreshConnectionSummary(){
  if(!catalog)return;const m=selectedModel(),p=providers.find(p=>p.id===$('provider').value);
  $('connectionSummary').textContent=m?`${p.name} · ${m.name}`:t('选择你的模型');
  $('connectProvider').textContent=$('apiKey').value.trim()?t('管理连接'):t('连接官方厂商');
  $('connectionState').textContent=verifiedKeys.get(p?.id)===$('apiKey').value.trim()&&$('apiKey').value.trim()?t('密钥已验证'):$('apiKey').value.trim()?t('已填写'):t('待配置');
  $('connectProvider').disabled=busy||realtimeActive||!ready;
}
function setupProvider(){return providers.find(p=>p.id===$('setupProvider').value);}
function setupModel(){const p=setupProvider();return catalog.models.find(m=>m.provider===p.id&&m.id===settings.get(p.id))||catalog.models.find(m=>m.provider===p.id&&!m.batch&&m.categories.includes('chat'));}
function refreshSetup(){
  if(!catalog)return;const p=setupProvider(),m=setupModel();
  for(let i=1;i<=3;i++){$('setupStep'+i).classList.toggle('active',i===wizardStep);$('setupStep'+i).setAttribute('aria-current',i===wizardStep?'step':'false');}
  $('setupChoose').hidden=wizardStep!==1;$('setupKeyStep').hidden=wizardStep!==2;$('setupCheckStep').hidden=wizardStep!==3;
  $('setupBack').hidden=wizardStep===1;$('setupNext').hidden=wizardStep===3;$('finishSetup').hidden=wizardStep!==3;
  $('finishSetup').disabled=wizardChecking||wizardBlocked;$('setupBack').disabled=wizardChecking;$('checkConnection').disabled=wizardChecking;
  $('finishSetup').textContent=wizardVerified?t('开始聊天'):t('暂不验证，继续使用');
  $('setupProviderNote').textContent=p?.note||'';$('setupKeyLink').href=p.keyUrl;$('setupKeyAddress').textContent=p.keyUrl;
  $('setupCheckAddress').textContent=p.checkUrl?'GET · '+p.checkUrl:'';
  $('setupModel').textContent=m?`${p.name} · ${m.name}`:'';
}
function setupError(message){$('setupError').hidden=!message;$('setupError').textContent=errorText(message);}
function openSetup(){
  if(!ready||busy||realtimeActive||publicInsecure)return;
  wizardStep=1;wizardVerified=false;wizardBlocked=false;setupError('');
  $('setupProvider').replaceChildren(...providers.map(p=>new Option(p.name,p.id)));$('setupProvider').value=$('provider').value;
  $('setupKey').value=keys.get($('setupProvider').value)||'';$('setupKey').type='password';$('setupToggleKey').textContent=t('显示');
  refreshSetup();$('setupTitle').textContent=StarStore.preference('setupDone',false)?t('管理连接'):t('连接你的第一个模型');if(!$('setupDialog').open)$('setupDialog').showModal();
}
async function checkConnection(){
  if(wizardChecking)return;wizardVerified=false;wizardBlocked=false;wizardChecking=true;
  const generation=++wizardGeneration,p=setupProvider(),key=$('setupKey').value.trim();
  wizardController=new AbortController();const timeout=setTimeout(()=>wizardController.abort(),25000);
  $('setupCheckStatus').textContent=t('正在检查官方 API…');refreshSetup();
  try{
    const response=await fetch('api/check',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},signal:wizardController.signal,body:JSON.stringify({provider:p.id,apiKey:key})});
    const result=await response.json();if(generation!==wizardGeneration)return;
    if(!response.ok){wizardBlocked=response.status===400||result.reason==='credentials';throw new Error(result.error||t('连接检查失败，请重试。'));}
    wizardVerified=result.status==='verified';
    $('setupCheckStatus').textContent=wizardVerified?t('官方 API 已接受密钥。可以开始使用。'):t('此厂商的免生成检查暂未接入。可以继续使用，首次发送时再确认权限。');
  }catch(e){if(generation===wizardGeneration)$('setupCheckStatus').textContent=e.name==='AbortError'?t('连接检查超时。可以稍后重试，或暂不验证继续使用。'):errorText(e.message);}
  finally{clearTimeout(timeout);if(generation===wizardGeneration){wizardChecking=false;refreshSetup();}}
}
$('connectProvider').onclick=openSetup;
$('closeSetup').onclick=()=>$('setupDialog').close();
$('skipSetup').onclick=()=>$('setupDialog').close();
$('setupDialog').addEventListener('close',()=>{wizardGeneration++;wizardController?.abort();wizardChecking=false;$('setupKey').value='';StarStore.write('setupDone',true);});
$('setupProvider').onchange=()=>{$('setupKey').value=keys.get($('setupProvider').value)||'';wizardVerified=false;wizardBlocked=false;refreshSetup();};
$('setupKey').oninput=()=>{wizardVerified=false;wizardBlocked=false;setupError('');};
$('setupToggleKey').onclick=()=>{const show=$('setupKey').type==='password';$('setupKey').type=show?'text':'password';$('setupToggleKey').textContent=show?t('隐藏'):t('显示');$('setupToggleKey').setAttribute('aria-label',show?t('隐藏 API Key'):t('显示 API Key'));};
$('setupNext').onclick=()=>{
  if(wizardStep===2&&!/^[\x21-\x7e]{1,4096}$/.test($('setupKey').value.trim())){setupError(t('请粘贴完整的有效 API Key。'));return;}
  setupError('');wizardStep++;refreshSetup();if(wizardStep===3)checkConnection();else $('setupKey').focus();
};
$('setupBack').onclick=()=>{wizardStep--;setupError('');refreshSetup();};
$('checkConnection').onclick=checkConnection;
$('finishSetup').onclick=()=>{
  if(wizardChecking||wizardBlocked)return;const p=setupProvider(),m=setupModel(),key=$('setupKey').value.trim();
  keys.set(p.id,key);if(!StarStore.saveKeys(keys))showError(t('无法保存临时会话，请检查浏览器存储空间。'));if(wizardVerified)verifiedKeys.set(p.id,key);
  $('modelSearch').value='';$('provider').value=p.id;selectProvider(m.id);
  $('connectionSettings').open=false;StarStore.write('settingsOpen',false);$('setupDialog').close();refreshConnectionSummary();$('prompt').focus({preventScroll:true});
};
$('connectionSettings').addEventListener('toggle',()=>{if(experienceReady)StarStore.write('settingsOpen',$('connectionSettings').open);});
$('prompt').addEventListener('input',scheduleHistorySave);
$('inputFile').addEventListener('change',async()=>{
  const files=[...$('inputFile').files];if(!files.length){StarStore.savePending(null);return;}
  try{if(files.reduce((n,f)=>n+f.size,0)>4*1024*1024)throw new Error(t('本次上传的附件合计最多 4 MB。'));const saved=await Promise.all(files.map(async f=>({name:f.name,type:f.type,data:await readFileData(f)})));if(!StarStore.savePending(saved)){StarStore.savePending(null);showError(t('无法保存临时会话，请检查浏览器存储空间。'));}}catch(e){showError(e.message);}
});
function restorePendingFile(){
  const saved=StarStore.loadPending();if(!saved.length)return;
  try{const transfer=new DataTransfer();for(const item of saved){const [meta,body]=item.data.split(',');const raw=atob(body),bytes=Uint8Array.from(raw,c=>c.charCodeAt(0));transfer.items.add(new File([bytes],item.name,{type:item.type||meta.slice(5).split(';')[0]}));}$('inputFile').files=transfer.files;}catch{showError(t('无法恢复临时附件，请重新选择文件。'));}
}
window.addEventListener('beforeunload',event=>{if(!accountEmail){persistHistory();if(StarStore.unloadNeedsWait()){event.preventDefault();event.returnValue='';}}});
window.addEventListener('pagehide',()=>{persistHistory();StarStore.flushOnPagehide();});
window.addEventListener('starhub:guest-storage-error',()=>showError(t('临时会话保存失败，请检查网络后重试。')));
window.addEventListener('online',()=>StarStore.flush());
document.addEventListener('visibilitychange',()=>{if(document.hidden)persistHistory();});
function startExperience(){
  if(experienceReady||!ready)return;
  $('connectionSettings').open=StarStore.preference('settingsOpen',false)===true;
  experienceReady=true;restorePendingFile();refreshConnectionSummary();
  // Keep the homepage and sign-in entry accessible to first-time visitors.
}
document.addEventListener('starhub:ready',startExperience);
document.addEventListener('starhub:modelchanged',refreshConnectionSummary);
document.addEventListener('starhub:languagechanged',()=>{
  if(!ready)return;
  const filterIds=['filterVendor','filterCategory','filterSpecialty','filterInput','filterFamily','filterProvider','filterReasoning','filterCost','filterExecution'];
  const filters=Object.fromEntries(filterIds.map(id=>[id,$(id).value]));
  catalog=localizedCatalog(rawCatalog);providers=catalog.providers;
  // Localize catalog labels without replacing controls containing user input.
  for(const option of $('provider').options)option.textContent=providers.find(p=>p.id===option.value)?.name||option.textContent;
  for(const option of $('setupProvider').options)option.textContent=providers.find(p=>p.id===option.value)?.name||option.textContent;
  for(const option of $('model').options){const m=catalog.models.find(m=>m.provider===$('provider').value&&m.id===option.value);if(m)option.textContent=m.name+(m.accessLabel?' · '+m.accessLabel:'');}
  for(const group of $('model').querySelectorAll('optgroup')){const m=catalog.models.find(m=>m.provider===$('provider').value&&m.id===group.firstElementChild?.value);if(m)group.label=`${m.vendorName} · ${m.family}`;}
  for(const option of $('task').options)option.textContent=catalog.categories[option.value]||option.textContent;
  for(const option of $('effort').options)option.textContent=option.value==='default'?t('平台默认'):`${uiText(effortNames[option.value])||option.value} (${option.value})`;
  const m=selectedModel();if(m){$('selectedSummary').textContent=`${m.vendorName} · ${m.family} · ${uiText(costNames[m.cost])}`;$('keyLabel').textContent=m.keyLabel;}
  parameterDefinitions=m?.settings?.[$('task').value]||[];
  for(const spec of parameterDefinitions){const input=$('param-'+spec.key);if(!input)continue;const label=input.closest('label');label.querySelector('span').textContent=spec.label;label.querySelector('small').textContent=(spec.min!==undefined?t('范围 {0}–{1}。 ',spec.min.toLocaleString(),spec.max.toLocaleString()):'')+(spec.help||t('留空使用平台默认。'));
    if(input.tagName==='SELECT')for(const option of input.options)option.textContent=option.value===''?t('平台默认'):spec.type==='boolean'?t(option.value==='true'?'开启':'关闭'):parameterNames[option.value]?`${uiText(parameterNames[option.value])} · ${option.value}`:option.value;
  }
  if(m)$('parameterModel').textContent=`${m.providerName} · ${m.name} · ${catalog.categories[$('task').value]}`;
  // Dynamic reasoning options must not accumulate on repeated switches.
  for(const option of [...$('filterReasoning').options].slice(4))option.remove();
  setupLibrary();for(const id of filterIds)if([...$(id).options].some(o=>o.value===uiText(filters[id])))$(id).value=uiText(filters[id]);
  updateConnection();refreshCurrentApi();refreshParameterConditions();renderBatches();
  // Translate labels in place: files, keys, prompts and unsent settings remain untouched.
  translateInterface();refreshConnectionSummary();historySignature='';render();
  if($('modelLibrary').open)renderLibrary();if($('detailDialog').open)showDetails(catalog.models.find(m=>m.uid===detailModel.uid));
  if($('setupDialog').open)refreshSetup();
});
if(ready)startExperience();
