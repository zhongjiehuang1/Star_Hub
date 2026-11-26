'use strict';
let accountEmail='',accountDisplayName='',accountKeyRing=[],reauthEmail='',accountIdentityRevision=0,lastDisplayedAccount='';
function notifyAccountContext(){document.dispatchEvent(new CustomEvent('starhub:account-changed'));}
const pendingAccountOperations=new Map();
// These small POSTs share the entry's account-request slot. Preserve their
// order and discard queued requests if the signed-in identity has changed.
let accountPostTail=Promise.resolve();
function sendAccountPost(url,options){
 const owner=accountEmail, revision=accountIdentityRevision;
 const pending=accountPostTail.then(()=>{
  if(owner!==accountEmail || revision!==accountIdentityRevision)throw Error(t('账户已更改，请刷新后重试。'));
  return fetch(url,options);
 });
 accountPostTail=pending.then(()=>{},()=>{});
 return pending;
}
async function accountRequest(body,resolveKnown=true){
 if(!window.StarHubDesktopConnection?.pending&&['login','logout','changePassword'].includes(body.action)){accountWorkspaceBootstrap=null;accountIdentityRevision++;if(body.action!=='logout')notifyAccountContext();if(typeof clearGeneratedPreviews==='function')clearGeneratedPreviews();}
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
   const response=await sendAccountPost('api/account',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify({action:'loginStatus',email:body.email,operationId:body.operationId})}),known=await response.json();
   if(response.ok&&known.operationId===body.operationId&&known.state==='COMMITTED'&&[200,401,429].includes(known.status)&&mode(response.headers?.get('X-Four-Project-Durability')))return known;
  }else if(typeof workspaceRequest==='function'){
   const known=await workspaceRequest({action:'requestStatus',operationId:body.operationId});
   if(known.operationId===body.operationId&&known.state==='COMMITTED'&&mode(known.confirmedDurability)&&Number.isInteger(known.status)&&(known.status>=200&&known.status<300||[400,403,429].includes(known.status)))return known;
  }
  return null;
 }
 try{
  const owner=accountEmail, epoch=accountIdentityRevision;
  const options={method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify(body.action==='login'?body:{...body,expectedEmail:owner})};
  let r=await sendAccountPost('api/account',options);
  if(body.action==='logout'){
   for(let attempt=0;r.status===429 && attempt<3;attempt++){
    const info=await r.clone().json().catch(()=>null);
    if(info && !['SERVER_BUSY','MEMORY_BUSY','ATOMIC_WRITER_BUSY'].includes(info.code))break;
    const retry=Number(r.headers.get('Retry-After'));
    await new Promise(resolve=>setTimeout(resolve,1000*(retry>0 && retry<=10?retry:attempt+1)));
    if(accountEmail!==owner || accountIdentityRevision!==epoch)throw Error(t('账户已更改，请刷新后重试。'));
    r=await sendAccountPost('api/account',options);
   }
  }
  const d=await r.json();
  const confirmed=mutation&&d.operationId===body.operationId&&d.operationState==='COMMITTED'&&mode(r.headers?.get('X-Four-Project-Durability'));
  if(!r.ok){
   if(confirmed)retire();
   if(['CLUSTER_UNAVAILABLE','OPERATION_PENDING'].includes(d.code)&&typeof setClusterAvailability==='function')setClusterAvailability(false);
   const error=Error(['CLUSTER_UNAVAILABLE','OPERATION_PENDING'].includes(d.code)?t('服务器故障，请等待恢复'):(d.error||d.message||'Account request failed'));error.status=r.status;error.code=d.code;error.operationState=d.operationState;error.confirmed=confirmed;throw error;
  }
  if(mutation&&!confirmed){const error=Error('The account operation is not durably confirmed. Keep its original request identity.');error.status=503;error.code='OPERATION_PENDING';throw error;}
  if(confirmed)retire();if(body.action==='login'&&d.ok===false){const error=Error(t('登录状态已结束，请重新登录原账户。'));error.status=401;error.code=d.code;throw error;}return d;
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

let accountRestoreFlight=null,accountWorkspaceBootstrap=null;
function consumeWorkspaceBootstrap(){const value=accountWorkspaceBootstrap;accountWorkspaceBootstrap=null;return value&&value.email===accountEmail&&value.epoch===accountIdentityRevision?value.page:null;}
function restoreAccount(bootstrap=false){
 const epoch=accountIdentityRevision;
 if(accountRestoreFlight?.epoch===epoch&&accountRestoreFlight.bootstrap===bootstrap)return accountRestoreFlight.promise;
 accountWorkspaceBootstrap=null;
 const flight={epoch,bootstrap};
 flight.promise=(async()=>{
 try {const r=await fetch(bootstrap?'api/account?bootstrap=1':'api/account',{cache:'no-store'});if(!r.ok)return false;const data=await r.json();if(epoch!==accountIdentityRevision||accountRestoreFlight!==flight)return false;if(accountEmail&&(!data.signedIn||data.email!==accountEmail)){requestReauthentication(accountEmail);return false;}accountWorkspaceBootstrap=null;const durability=r.headers?.get('X-Four-Project-Durability');if(bootstrap&&data.signedIn&&data.workspaceBootstrap){if(!['dual','single'].includes(durability))return false;accountWorkspaceBootstrap={email:data.email,epoch,page:{...data.workspaceBootstrap,confirmedDurability:durability}};}accountEmail=data.signedIn?data.email:'';accountDisplayName=data.signedIn&&typeof data.displayName==='string'?data.displayName:'';accountKeyRing=data.keyRing||[];StarStore.setGuestMode(!data.signedIn);if(data.signedIn){keys.clear();for(const [p,k] of Object.entries(data.keys||{}))keys.set(p,k);StarStore.saveKeys(keys);}accountDisplay();if(typeof StarSessionIdle!=='undefined')StarSessionIdle.sync();return Boolean(data.signedIn);}catch{return false;}
 })().finally(()=>{if(accountRestoreFlight===flight)accountRestoreFlight=null;});
 accountRestoreFlight=flight;return flight.promise;
}
function localizedAccountName(name){if(language!=='zh')return name;switch(name){case 'Zhongjie Huang':return '黄中杰';case 'Rong Huang':return '黄蓉';default:return name;}}
function accountDisplay(){if(lastDisplayedAccount!==accountEmail){lastDisplayedAccount=accountEmail;notifyAccountContext();if(typeof queueRender==='function')queueRender();}const signed=Boolean(accountEmail),name=localizedAccountName(accountDisplayName);$('accountButton').textContent=signed?(name||accountEmail):t('登录');$('accountProfile').textContent=name?`${name} · ${accountEmail}`:accountEmail;$('accountLogout').textContent=t('退出登录');$('accountLogout').hidden=!signed;$('accountSaveKey').hidden=!signed;$('openLibrary').hidden=!signed;$('privacyHeading').textContent=signed?t('账户空间'):t('访客会话');$('privacyDetail').textContent=signed?t('聊天和已保存的密钥存放在账户中'):t('登录后可将聊天和文件保存到账户');$('setupKeyHelp').textContent=signed?t('在官方控制台创建 API 密钥并粘贴。要长期保存，请在账户密钥管理中保存。'):t('在官方控制台创建 API 密钥并粘贴。密钥在本标签页刷新后保留，关闭后无法再访问。');}
function requestReauthentication(email,voiceInterrupted=false){if(!email||email!==accountEmail)return;reauthEmail=email;showError(t('登录状态已结束。请重新登录原账户以保存当前页面的更改，暂时不要刷新。'));$('accountEmail').value=email;$('accountPassword').value='';$('accountError').textContent=t(voiceInterrupted?'登录状态已结束，实时语音可能中断；请重新登录原账户。':'登录状态已结束，请重新登录原账户。');if(!$('accountDialog').open)$('accountDialog').showModal();}
$('accountButton').onclick=()=>{if(reauthEmail){requestReauthentication(reauthEmail);return;}if(accountEmail){openAccountSettings();return;}$('accountError').textContent='';$('accountDialog').showModal();};
$('accountClose').onclick=()=>$('accountDialog').close();
$('accountForm').onsubmit=async e=>{e.preventDefault();$('accountSubmit').disabled=true;$('accountError').textContent='';
 if(window.StarHubDesktopConnection?.pending){
  try{await accountRequest({action:'login',email:$('accountEmail').value,password:$('accountPassword').value});$('accountDialog').close();await window.StarHubDesktopConnection.refresh();}
  catch(error){$('accountError').textContent=errorText(error.message);}
  finally{$('accountPassword').value='';$('accountSubmit').disabled=false;}
  return;
 }
 if(reauthEmail){try{const email=reauthEmail;if($('accountEmail').value.trim().toLowerCase()!==email.toLowerCase())throw Error(t('请使用原账户重新登录，以保存当前页面的更改。'));await accountRequest({action:'login',email,password:$('accountPassword').value});$('accountPassword').value='';if(!await restoreAccount()||accountEmail!==email)throw Error(t('无法恢复账户，请重试。'));reauthEmail='';cloudFailed=false;await saveCloudChats();$('accountDialog').close();showError('');if(typeof StarSessionIdle!=='undefined'){StarSessionIdle.notice('登录成功');StarSessionIdle.changed();}}catch(error){$('accountError').textContent=errorText(error.message);}finally{$('accountSubmit').disabled=typeof clusterAvailable!=='undefined'&&!clusterAvailable;}return;}
 const priorKeys=new Map(keys);try{const guest=accountEmail?[]:chats;await accountRequest({action:'login',email:$('accountEmail').value,password:$('accountPassword').value});$('accountPassword').value='';await restoreAccount(true);if(!accountEmail)throw Error('Could not load account.');cloudHydrating=true;updateConnection();$('accountDialog').close();await accountChatsOnLogin(guest);if(ready)selectProvider();if(typeof StarSessionIdle!=='undefined'){StarSessionIdle.notice('登录成功');StarSessionIdle.changed();}}catch(error){if(accountEmail&&!cloudReady){try{await accountRequest({action:'logout'});}catch{}accountEmail='';accountDisplayName='';keys.clear();for(const [p,k] of priorKeys)keys.set(p,k);StarStore.saveKeys(keys);accountDisplay();if(ready)selectProvider();}if($('accountDialog').open)$('accountError').textContent=error.message;else showError(error.message);}finally{cloudHydrating=Boolean(current?.hydrating);updateConnection();$('accountSubmit').disabled=typeof clusterAvailable!=='undefined'&&!clusterAvailable;}};
function accountLogoutError(error){return error.code==='INSUFFICIENT_STORAGE'?t('服务器存储空间不足，未能退出登录；当前账户仍保持登录，请稍后重试。'):error.message;}
$('accountLogout').onclick=async()=>{try{await saveCloudChats();await accountRequest({action:'logout'});cloudReady=false;cloudFailed=false;const guest=StarStore.loadHistory();chats=guest?.chats||[];current=undefined;if(chats.length)activateChat(chats.find(c=>c.id===guest.currentId)||chats[0]);else newChat();keys.clear();StarStore.setGuestMode(true);StarStore.saveKeys(keys);accountEmail='';accountDisplayName='';accountDisplay();if(ready)selectProvider();if(typeof StarSessionIdle!=='undefined'){StarSessionIdle.sync();StarSessionIdle.notice('已退出登录');StarSessionIdle.changed();}}catch(error){showError(accountLogoutError(error));}};
$('accountSaveKey').onclick=()=>{const key=$('setupKey').value;$('setupDialog').close();openAccountSettings(true);$('accountKey').value=key;};
function openAccountSettings(showKeys=false){
 $('keyVault').open=showKeys;
 const name=localizedAccountName(accountDisplayName);$('accountProfile').textContent=name?`${name} · ${accountEmail}`:accountEmail;$('settingsStatus').textContent='';
 $('accountProvider').replaceChildren(...providers.map(p=>new Option(p.name,p.id)));$('accountProvider').value=$('provider').value;
 $('accountKey').value='';$('keyLabelInput').value='';renderKeyRing();document.dispatchEvent(new CustomEvent('starhub:account-settings-opening'));$('accountSettings').showModal();$('accountSettings').scrollTop=0;
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
$('closeAccountSettings').onclick=()=>{document.dispatchEvent(new CustomEvent('starhub:account-settings-closing'));$('accountSettings').close();};
$('accountSettings').onclose=()=>{
 $('passwordForm').reset();
 $('keyForm').reset();
 $('passwordSettings').open=false;
 $('keyVault').open=false;
};
$('passwordForm').onsubmit=async e=>{e.preventDefault();try{await accountRequest({action:'changePassword',currentPassword:$('oldPassword').value,newPassword:$('newPassword').value});$('oldPassword').value='';$('newPassword').value='';$('settingsStatus').textContent=t('密码已更新。');}catch(error){$('settingsStatus').textContent=error.message;}};
$('keyForm').onsubmit=async e=>{e.preventDefault();try{const provider=$('accountProvider').value,key=$('accountKey').value.trim(),label=$('keyLabelInput').value.trim();await accountRequest({action:'addKey',provider,key,label});$('accountKey').value='';$('keyLabelInput').value='';await restoreAccount();if($('provider').value===provider){$('apiKey').value=keys.get(provider)||'';updateConnection();}renderKeyRing();$('settingsStatus').textContent=t('已保存并选择密钥。');}catch(error){$('settingsStatus').textContent=errorText(error.message);}};

document.addEventListener("starhub:languagechanged",()=>{accountDisplay();if($("accountSettings").open)renderKeyRing();if($("libraryDialog").open)refreshFiles();});
