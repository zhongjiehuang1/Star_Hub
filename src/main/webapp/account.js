'use strict';
let accountEmail='',accountKeyRing=[],reauthEmail='',accountIdentityRevision=0,lastDisplayedAccount='';
function notifyAccountContext(){document.dispatchEvent(new CustomEvent('starhub:account-changed'));}
const pendingAccountOperations=new Map();
async function accountRequest(body,resolveKnown=true){
 if(['login','logout','changePassword'].includes(body.action)){accountIdentityRevision++;notifyAccountContext();if(typeof clearGeneratedPreviews==='function')clearGeneratedPreviews();}
 const mutation=['login','changePassword','addKey','selectKey','deleteKey'].includes(body.action);let pendingKey;
 function retire(){if(pendingKey){pendingAccountOperations.delete(pendingKey);try{sessionStorage.removeItem(pendingKey);}catch{}}}
 const mode=value=>['dual','single'].includes(value);
 if(mutation&&!body.operationId){
  // Persist only an opaque request identity, never a password/API key or hash.
  pendingKey='starhub.account-operation.'+encodeURIComponent(JSON.stringify({owner:body.action==='login'?String(body.email||'').trim().toLowerCase():accountEmail,action:body.action,provider:body.provider||'',label:body.label||'',id:body.id||''}));
  let operation=pendingAccountOperations.get(pendingKey)||sessionStorage.getItem(pendingKey);
  if(operation&&!/^[0-9a-f-]{36}$/.test(operation))throw Error('The original account request identity is invalid. No new operation was sent.');
  if(!operation)operation=crypto.randomUUID();
  sessionStorage.setItem(pendingKey,operation);if(sessionStorage.getItem(pendingKey)!==operation)throw Error('Account request identity storage is unavailable. No operation was sent.');
  pendingAccountOperations.set(pendingKey,operation);body={...body,operationId:operation};
 }
 async function queryKnown(){
  if(body.action==='login'){
   const response=await fetch('api/account',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify({action:'loginStatus',email:body.email,operationId:body.operationId})}),known=await response.json();
   if(response.ok&&known.operationId===body.operationId&&known.state==='COMMITTED'&&[200,401,429].includes(known.status)&&mode(response.headers?.get('X-Four-Project-Durability')))return known;
  }else if(typeof workspaceRequest==='function'){
   const known=await workspaceRequest({action:'requestStatus',operationId:body.operationId});
   if(known.operationId===body.operationId&&known.state==='COMMITTED'&&mode(known.confirmedDurability)&&Number.isInteger(known.status)&&(known.status>=200&&known.status<300||[400,403,429].includes(known.status)))return known;
  }
  return null;
 }
 try{
  const r=await fetch('api/account',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify(body.action==='login'?body:{...body,expectedEmail:accountEmail})}),d=await r.json();
  const confirmed=mutation&&d.operationId===body.operationId&&d.operationState==='COMMITTED'&&mode(r.headers?.get('X-Four-Project-Durability'));
  if(!r.ok){
   if(confirmed)retire();
   if(['CLUSTER_UNAVAILABLE','OPERATION_PENDING'].includes(d.code)&&typeof setClusterAvailability==='function')setClusterAvailability(false);
   const error=Error(['CLUSTER_UNAVAILABLE','OPERATION_PENDING'].includes(d.code)?t('服务器故障，请等待恢复'):(d.error||d.message||'Account request failed'));error.status=r.status;error.code=d.code;error.operationState=d.operationState;error.confirmed=confirmed;throw error;
  }
  if(mutation&&!confirmed){const error=Error('The account operation is not durably confirmed. Keep its original request identity.');error.status=503;error.code='OPERATION_PENDING';throw error;}
  if(confirmed)retire();return d;
 }catch(error){
  error.operationId=body.operationId;
  // A changed payload may get a new ID only after the original outcome is proven.
  if(mutation&&body.operationId&&error.code==='OPERATION_CONFLICT'&&resolveKnown){
   let known;try{known=await queryKnown();}catch{}
   if(known){retire();const next={...body};delete next.operationId;return accountRequest(next,false);}
  }
  if(mutation&&body.action!=='login'&&body.operationId&&(!error.status||error.status>=500)){
   let known;try{known=await queryKnown();}catch{}
   if(known){retire();if(known.status>=200&&known.status<300)return known.result;const failure=Error(known.result?.error||'The original account request completed with a known failure.');failure.status=known.status;failure.operationId=body.operationId;failure.operationState='COMMITTED';failure.confirmed=true;throw failure;}
  }
  // Authentication/conflict errors do not prove an earlier request rolled back.
  throw error;
 }
}

async function restoreAccount(){
 try {const r=await fetch('api/account',{cache:'no-store'});if(!r.ok)return false;const data=await r.json();accountEmail=data.signedIn?data.email:'';accountKeyRing=data.keyRing||[];StarStore.setGuestMode(!data.signedIn);if(data.signedIn){keys.clear();for(const [p,k] of Object.entries(data.keys||{}))keys.set(p,k);StarStore.saveKeys(keys);}accountDisplay();return Boolean(data.signedIn);}catch{return false;}
}
function accountDisplay(){if(lastDisplayedAccount!==accountEmail){lastDisplayedAccount=accountEmail;notifyAccountContext();if(typeof queueRender==='function')queueRender();}const signed=Boolean(accountEmail);$('accountButton').textContent=signed?accountEmail:t('登录');$('accountLogout').textContent=t('退出登录');$('accountLogout').hidden=!signed;$('accountSaveKey').hidden=!signed;$('openLibrary').hidden=!signed;$('guestKeyHelp').hidden=signed;$('keyScope').textContent=signed?t('可保存到账户'):t('临时会话');$('privacyHeading').textContent=signed?t('账户空间'):t('访客会话');$('privacyDetail').textContent=signed?t('聊天和已保存的密钥存放在账户中'):t('登录后可将聊天和文件保存到账户');$('setupKeyHelp').textContent=signed?t('在官方控制台创建 API 密钥并粘贴。要长期保存，请在账户密钥管理中保存。'):t('在官方控制台创建 API 密钥并粘贴。密钥在本标签页刷新后保留，关闭后无法再访问。');}
function requestReauthentication(email){if(!email||email!==accountEmail)return;reauthEmail=email;showError(t('登录已过期。请重新登录原账户以保存当前页面的更改，暂时不要刷新。'));$('accountEmail').value=email;$('accountPassword').value='';$('accountError').textContent=t('登录已过期，请重新登录原账户。');if(!$('accountDialog').open)$('accountDialog').showModal();}
$('accountButton').onclick=()=>{if(reauthEmail){requestReauthentication(reauthEmail);return;}if(accountEmail){openAccountSettings();return;}$('accountError').textContent='';$('accountDialog').showModal();};
$('accountClose').onclick=()=>$('accountDialog').close();
$('accountForm').onsubmit=async e=>{e.preventDefault();$('accountSubmit').disabled=true;$('accountError').textContent='';
 if(reauthEmail){try{const email=reauthEmail;if($('accountEmail').value.trim().toLowerCase()!==email.toLowerCase())throw Error(t('请使用原账户重新登录，以保存当前页面的更改。'));await accountRequest({action:'login',email,password:$('accountPassword').value});$('accountPassword').value='';if(!await restoreAccount()||accountEmail!==email)throw Error(t('无法恢复账户，请重试。'));reauthEmail='';cloudFailed=false;await saveCloudChats();$('accountDialog').close();showError('');}catch(error){$('accountError').textContent=errorText(error.message);}finally{$('accountSubmit').disabled=typeof clusterAvailable!=='undefined'&&!clusterAvailable;}return;}
 const priorKeys=new Map(keys);try{const guest=accountEmail?[]:chats;await accountRequest({action:'login',email:$('accountEmail').value,password:$('accountPassword').value});$('accountPassword').value='';await restoreAccount();if(!accountEmail)throw Error('Could not load account.');cloudHydrating=true;updateConnection();$('accountDialog').close();await accountChatsOnLogin(guest);if(ready)selectProvider();}catch(error){if(accountEmail&&!cloudReady){try{await accountRequest({action:'logout'});}catch{}accountEmail='';keys.clear();for(const [p,k] of priorKeys)keys.set(p,k);StarStore.saveKeys(keys);accountDisplay();if(ready)selectProvider();}if($('accountDialog').open)$('accountError').textContent=error.message;else showError(error.message);}finally{cloudHydrating=Boolean(current?.hydrating);updateConnection();$('accountSubmit').disabled=typeof clusterAvailable!=='undefined'&&!clusterAvailable;}};
$('accountLogout').onclick=async()=>{try{await saveCloudChats();await accountRequest({action:'logout'});cloudReady=false;cloudFailed=false;const guest=StarStore.loadHistory();chats=guest?.chats||[];current=undefined;if(chats.length)activateChat(chats.find(c=>c.id===guest.currentId)||chats[0]);else newChat();keys.clear();StarStore.setGuestMode(true);StarStore.saveKeys(keys);accountEmail='';accountDisplay();if(ready)selectProvider();}catch(error){showError(error.message);}};
$('accountSaveKey').onclick=()=>{openAccountSettings();$('accountKey').value=$('apiKey').value;};
function openAccountSettings(){
 const name=accountEmail==='zhjpersonal83@gmail.com'?'Zhongjie Huang':accountEmail==='1466485899@qq.com'?'Rong Huang':'';
 $('accountProfile').textContent=`${name} · ${accountEmail}`;$('settingsStatus').textContent='';
 $('accountProvider').replaceChildren(...providers.map(p=>new Option(p.name,p.id)));$('accountProvider').value=$('provider').value;
 $('accountKey').value='';$('keyLabelInput').value='';renderKeyRing();$('accountSettings').showModal();
}
function renderKeyRing(){const list=$('accountKeyList');list.replaceChildren();const provider=$('accountProvider').value;
 for(const item of accountKeyRing.filter(k=>k.provider===provider)){const row=document.createElement('div');row.className='keyring-row';const name=document.createElement('span');name.textContent=(item.label==='Default'?t('默认密钥'):item.label)+(item.selected?t('（当前使用）'):'');row.append(name);
  if(!item.selected){const use=document.createElement('button');use.type='button';use.textContent=t('选择');use.onclick=()=>keyringAction('selectKey',provider,item.id);row.append(use);}
  const remove=document.createElement('button');remove.type='button';remove.textContent=t('删除');remove.onclick=()=>{if(confirm(t('确认删除此密钥？')))keyringAction('deleteKey',provider,item.id);};row.append(remove);list.append(row);
 }
 if(!list.childElementCount)list.textContent=t('此厂商尚未保存密钥。');
}
async function keyringAction(action,provider,id){try{await accountRequest({action,provider,id});await restoreAccount();if($('provider').value===provider){$('apiKey').value=keys.get(provider)||'';updateConnection();}renderKeyRing();$('settingsStatus').textContent=t('已更新密钥仓。');}catch(error){$('settingsStatus').textContent=errorText(error.message);}}
$('accountProvider').onchange=renderKeyRing;
$('closeAccountSettings').onclick=()=>$('accountSettings').close();
$('passwordForm').onsubmit=async e=>{e.preventDefault();try{await accountRequest({action:'changePassword',currentPassword:$('oldPassword').value,newPassword:$('newPassword').value});$('oldPassword').value='';$('newPassword').value='';$('settingsStatus').textContent=t('密码已更新。');}catch(error){$('settingsStatus').textContent=error.message;}};
$('keyForm').onsubmit=async e=>{e.preventDefault();try{const provider=$('accountProvider').value,key=$('accountKey').value.trim(),label=$('keyLabelInput').value.trim();await accountRequest({action:'addKey',provider,key,label});$('accountKey').value='';$('keyLabelInput').value='';await restoreAccount();if($('provider').value===provider){$('apiKey').value=keys.get(provider)||'';updateConnection();}renderKeyRing();$('settingsStatus').textContent=t('已保存并选择密钥。');}catch(error){$('settingsStatus').textContent=errorText(error.message);}};

document.addEventListener("starhub:languagechanged",()=>{accountDisplay();if($("accountSettings").open)renderKeyRing();if($("libraryDialog").open)refreshFiles();});
