'use strict';
let clusterAvailable=false;
let cloudReady=false,cloudQueue=Promise.resolve(),cloudFailed=false,librarySelection=new Map();
let fileLibraryPage=1,pendingUploadPage=1,fileLibraryRequest=0,fileLibraryResult=null,fileLibraryOwner=null,fileLibraryEpoch=null,displayedServerId=null;
const fileLibraryChecked=new Set(),FILE_LIBRARY_PAGE_SIZE=100;
const pendingFileOperations=new Map();
function workspaceOwner(expectedEmail,epoch){if(!expectedEmail||accountEmail!==expectedEmail||(epoch!==undefined&&accountIdentityRevision!==epoch)){const error=Error(t('账户已更改，请在原账户中查询未完成操作。'));error.code='ACTOR_CHANGED';error.status=409;throw error;}}
function workspaceOperation(key){
 let operation=pendingFileOperations.get(key);if(operation)return operation;
 try{operation=sessionStorage.getItem(key);if(operation&&!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(operation))throw Error('Invalid stored operation identity');if(!operation){operation=crypto.randomUUID();sessionStorage.setItem(key,operation);if(sessionStorage.getItem(key)!==operation)throw Error('Operation identity was not retained');}}
 catch{const error=Error(t('浏览器无法保存操作编号；请启用当前标签页的会话存储后重试。'));error.code='OPERATION_ID_NOT_RETAINED';throw error;}
 pendingFileOperations.set(key,operation);return operation;
}
function completeWorkspaceOperation(key,operation){try{sessionStorage.removeItem(key);if(sessionStorage.getItem(key)!==null)return;}catch{return;}if(pendingFileOperations.get(key)===operation)pendingFileOperations.delete(key);}
function setClusterAvailability(available){clusterAvailable=available;const banner=$('clusterUnavailable');if(banner)banner.hidden=available;for(const id of ['libraryUpload','useLibraryFiles','accountSubmit']){const control=$(id);if(control)control.disabled=!available;}}
async function checkClusterHealth(){try{const response=await fetch('api/cluster-health',{cache:'no-store',credentials:'same-origin'}),data=await response.json();setClusterAvailability(response.ok&&data.status==='ready');}catch{setClusterAvailability(false);}}
async function workspaceRequest(data){
 const expectedEmail=accountEmail,epoch=accountIdentityRevision;workspaceOwner(expectedEmail,epoch);
 if(['upload','deleteFile','trashFile','restoreFile','saveChat','deleteChat'].includes(data.action)&&!data.operationId){
  const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify({...data,owner:expectedEmail}))))).map(x=>x.toString(16).padStart(2,'0')).join('');workspaceOwner(expectedEmail,epoch);
  const key='starhub.file-operation.'+hash,operation=workspaceOperation(key);
  data={...data,operationId:operation};
  try{
   let result;
   if(data.action==='upload'){
    const content=Uint8Array.from(atob(data.data),c=>c.charCodeAt(0)),digest=new Uint8Array(await crypto.subtle.digest('SHA-256',content));
    const reservation=await sendWorkspaceRequest({action:'reserveUpload',operationId:operation,files:[{name:data.name,mime:data.mime,size:content.length,sha256:btoa(String.fromCharCode(...digest))}]},expectedEmail,epoch);
    if(reservation.state==='COMMITTED')result=reservation;
    else if(reservation.state==='RESERVED')result=await sendWorkspaceRequest(data,expectedEmail,epoch);
    else{const error=Error(t('上传预留尚未确认，请使用原操作重试。'));error.code=reservation.state==='CANCELLED'?'OPERATION_ABORTED':'OPERATION_PENDING';throw error;}
   }else result=await sendWorkspaceRequest(data,expectedEmail,epoch);
   completeWorkspaceOperation(key,operation);return result;
  }
  catch(error){if(error.code==='OPERATION_ABORTED')completeWorkspaceOperation(key,operation);error.operationId=operation;throw error;}
 }
 return sendWorkspaceRequest(data,expectedEmail,epoch);
}
async function sendWorkspaceRequest(data,expectedEmail,epoch=accountIdentityRevision){workspaceOwner(expectedEmail,epoch);const r=await fetch('api/workspace',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify({...data,expectedEmail})});let d=await r.json();workspaceOwner(expectedEmail,epoch);if(!r.ok){const error=Error(['CLUSTER_UNAVAILABLE','OPERATION_PENDING'].includes(d.code)?t('服务器故障，请等待恢复'):(d.error||d.message||t('账户存储失败')));error.status=r.status;error.code=d.code;if(d.code==='CLUSTER_UNAVAILABLE'||d.code==='OPERATION_PENDING')setClusterAvailability(false);throw error;}
 const durability=r.headers?.get('X-Four-Project-Durability');if(!['dual','single'].includes(durability)||(data.operationId&&['upload','deleteFile','trashFile','restoreFile','saveChat','deleteChat'].includes(data.action)&&(d.operationId!==data.operationId||d.operationState!=='COMMITTED'))){const error=Error(t('服务器故障，请等待恢复'));error.code='OPERATION_PENDING';error.operationId=data.operationId;throw error;}Object.defineProperty(d,'confirmedDurability',{value:durability,enumerable:false});return d;}
function forgetFileOperation(operation){
 for(const [key,value] of pendingFileOperations)if(value===operation){pendingFileOperations.delete(key);try{sessionStorage.removeItem(key);}catch{}}
 try{for(let i=sessionStorage.length-1;i>=0;i--){const key=sessionStorage.key(i);if(key?.startsWith('starhub.file-operation.')&&sessionStorage.getItem(key)===operation)sessionStorage.removeItem(key);}}catch{}
}

function serialCloud(fn){const result=cloudQueue.then(fn);cloudQueue=result.catch(()=>{});return result;}
function cleanMessage(m){return {role:m.role,content:m.content||'',provider:m.provider||'',providerName:m.providerName||'',model:m.model||'',providerOperationId:/^[0-9a-f-]{36}$/.test(m.providerOperationId||'')?m.providerOperationId:undefined,fileIds:m.fileIds||[],imageCount:m.imageCount||0,mediaRefs:StarProviderMedia.references(m.mediaRefs||m.media||[]),imageSaveFailed:Boolean(m.imageSaveFailed),incomplete:Boolean(m.incomplete||m.revealing),reasoningSummary:m.reasoningSummary||'',thinkingSeconds:m.thinkingSeconds,details:m.details&&(m.details.webCitations||m.details.webSearchUsed)?{webCitations:m.details.webCitations||[],webSearchUsed:!!m.details.webSearchUsed,googleWidget:m.details.googleWidget||''}:undefined};}
function chatData(c){return {draft:c.draft||'',messages:c.messages.filter(m=>typeof m.content==='string'&&(m.content||/^[0-9a-f-]{36}$/.test(m.providerOperationId||''))).map(cleanMessage)};}
async function cloudUpload(data,name){const comma=data.indexOf(','),mime=data.slice(5,comma).split(';')[0],encoded=data.slice(comma+1);if(comma<0||!data.startsWith('data:')||!data.slice(0,comma).endsWith(';base64'))throw Error(t('不支持的文件'));return (await workspaceRequest({action:'upload',name,mime,data:encoded})).id;}
async function readCloudFile(id){const f=await workspaceRequest({action:'readFile',id});return {...f,url:`data:${f.mime};base64,${f.data}`};}
async function uploadSubmittedFiles(data,files,expectedEmail=accountEmail,active=()=>true){const epoch=accountIdentityRevision;function check(){workspaceOwner(expectedEmail,epoch);if(!active())throw Error('The original file conversation changed.');}check();const ids=[];for(let i=0;i<data.length;i++){check();const key=data[i];if(librarySelection.has(key))ids.push(librarySelection.get(key));else{const id=await cloudUpload(key,files[i]?.name||`attachment-${i+1}`);check();ids.push(id);}}check();librarySelection.clear();return ids;}
async function hydrateChat(c){const email=accountEmail,epoch=accountIdentityRevision;workspaceOwner(email,epoch);for(const m of c.messages){workspaceOwner(email,epoch);if(m.fileIds?.length){let list=[];for(const id of m.fileIds){try{const file=await readCloudFile(id);workspaceOwner(email,epoch);list.push(file.url);}catch(error){workspaceOwner(email,epoch);m.attachmentOmitted=true;}}if(m.imageCount){m.images=list.slice(0,m.imageCount);m.attachments=list.slice(m.imageCount);}else m.attachments=list;}if(m.mediaRefs?.length)m.mediaRefs=StarProviderMedia.references(m.mediaRefs);if(m.imageSaveFailed)m.warning=t('图片已生成，但未能保存到文件库；请在刷新前下载图片。');}workspaceOwner(email,epoch);c.hydrated=true;}
async function loadCloudChats(){const owner=accountEmail,epoch=accountIdentityRevision;workspaceOwner(owner,epoch);const result=await StarChatPages.collect(workspaceRequest,()=>Boolean(owner)&&accountEmail===owner&&accountIdentityRevision===epoch);workspaceOwner(owner,epoch);const resultChats=[];for(const item of result.chats){if(!item.data||!Array.isArray(item.data.messages))continue;const c={id:item.id,title:item.title,revision:item.revision,draft:item.data.draft||'',messages:item.data.messages};c.savedPayload=JSON.stringify({title:c.title,data:chatData(c)});c.savedDurability=result.confirmedDurability;c.hydrated=false;resultChats.push(c);}return resultChats;}
async function saveCloudChats(){if(!accountEmail||!cloudReady)return;if(cloudFailed)throw Error(t('云端保存失败。刷新前请导出此对话。'));const email=accountEmail,epoch=accountIdentityRevision,queuedChats=[...chats];await serialCloud(async()=>{workspaceOwner(email,epoch);for(const c of queuedChats){workspaceOwner(email,epoch);if(!c.messages.length)continue;const data=chatData(c),signature=JSON.stringify({title:c.title,data});if(c.savedPayload===signature)continue;try{const r=await workspaceRequest({action:'saveChat',id:c.id,title:c.title,data,revision:c.revision||0});workspaceOwner(email,epoch);c.revision=r.revision;c.savedPayload=signature;c.savedDurability=r.confirmedDurability;}catch(e){if(accountEmail===email&&accountIdentityRevision===epoch){cloudFailed=true;if(e.status===401)requestReauthentication(email);else showError(e.message+t('更改仍保留在当前页面。'));}throw e;}}});}
async function saveCloudChatsConfirmed(expectedEmail,chatId){
 const epoch=accountIdentityRevision;
 if(!expectedEmail||accountEmail!==expectedEmail||!cloudReady||cloudFailed)throw Error('The original account workspace is not ready for confirmed saving.');
 let target=chats.find(c=>c.id===chatId);if(!target||!target.messages.some(m=>/^[0-9a-f-]{36}$/.test(m.providerOperationId||'')))throw Error('The provider result placeholder is unavailable.');
 await saveCloudChats();target=chats.find(c=>c.id===chatId);
 if(accountEmail!==expectedEmail||accountIdentityRevision!==epoch||!cloudReady||cloudFailed||!target||target.savedPayload!==JSON.stringify({title:target.title,data:chatData(target)})||!['dual','single'].includes(target.savedDurability))throw Error('The provider result is not confirmed in the original account workspace.');
 return {durability:target.savedDurability};
}
async function deleteCloudChat(c){if(!accountEmail||!cloudReady)return;const email=accountEmail,epoch=accountIdentityRevision;await serialCloud(async()=>{workspaceOwner(email,epoch);if(!c.revision)return;await workspaceRequest({action:'deleteChat',id:c.id,revision:c.revision});workspaceOwner(email,epoch);c.revision=0;c.savedPayload='';});}
async function importGeneratedImage(ref,sourceOperation,expectedEmail,epoch){
 function check(){workspaceOwner(expectedEmail);if(typeof accountIdentityRevision!=='undefined'&&accountIdentityRevision!==epoch)throw Error('The import account changed. Original image references are retained.');}
 check();if(!/^[0-9a-f-]{36}$/.test(sourceOperation||'')||!ref.mediaRef||ref.mediaRef.operationId!==sourceOperation)throw Error('The original guest image reference is unavailable.');
 const original={operationId:sourceOperation,mediaId:ref.mediaRef.mediaId,ownerTab:StarStore.providerTab(),expectedEmail};
 const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(original))))).map(value=>value.toString(16).padStart(2,'0')).join('');check();
 const importOperationId=workspaceOperation('starhub.guest-image-import.'+hash);
 async function send(action){
  check();const response=await fetch('api/provider-media',{method:'POST',credentials:'same-origin',cache:'no-store',redirect:'error',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify({...original,guestImport:true,action,importOperationId})});check();
  const value=await response.json();check();if(!response.ok){const error=Error(value.message||value.error||'Guest image import is unconfirmed. Keep the original request.');error.code=value.code;throw error;}
  if(value.operationId!==sourceOperation||value.mediaId!==original.mediaId||value.importOperationId!==importOperationId||!['dual','single'].includes(response.headers.get('X-Four-Project-Durability'))||response.headers.get('X-Starhub-Import-Operation-ID')!==importOperationId||response.headers.get('X-Starhub-Import-State')!==value.state)throw Error('The image import is unconfirmed. Keep its original identity.');
  return value;
 }
 let value=await send('import-status');if(['ABSENT','RESERVED'].includes(value.state))value=await send('import');
 if(value.state!=='COMMITTED'||!/^[0-9a-f-]{36}$/.test(value.fileId||''))throw Error('The original image import is pending or cancelled. Guest references are retained.');
 // Keep the stable mapping after confirmation: retrying a partially imported
 // conversation must reuse its existing files instead of uploading them twice.
 return {type:'image',mime:value.mime||ref.mediaRef.mimeType,fileId:value.fileId,importOperationId};
}

function guestAttachmentData(url){
 const match=/^data:([a-z0-9.+/-]+);base64,([A-Za-z0-9+/]*={0,2})$/i.exec(typeof url==='string'?url:'');
 const allowed=new Set(['image/png','image/jpeg','image/webp','audio/mpeg','audio/wav','audio/x-wav','audio/mp4','audio/webm','application/pdf','text/plain']),maximum=4*1024*1024;
 if(!match||!allowed.has(match[1].toLowerCase())||match[2].length>Math.ceil(maximum/3)*4)throw Error('This guest attachment cannot be safely imported. Ordinary files must be supported base64 data of at most 4 MiB. The original guest content is retained.');
 let raw;try{raw=atob(match[2]);}catch{throw Error('The original guest attachment encoding is invalid. Guest content is retained.');}
 if(!raw.length||raw.length>maximum||btoa(raw)!==match[2])throw Error('The original guest attachment is empty, oversized or has ambiguous encoding. Guest content is retained.');
 return {mime:match[1].toLowerCase(),encoded:match[2],bytes:Uint8Array.from(raw,char=>char.charCodeAt(0))};
}
function guestAttachments(message){const images=message.images?.length?message.images:(message.image?[message.image]:[]),extra=message.attachments||[];return {images,all:[...images,...extra]};}
function checkGuestImportable(chats){
 for(const chat of chats)for(const message of chat.messages){
  if((message.media||[]).some(item=>item?.type==='image'&&StarProviderMedia.references([item]).length!==1))throw Error('This guest conversation contains an old inline generated image that cannot be imported safely. Keep the original guest conversation and download that image before importing. No guest content was removed.');
  for(const item of guestAttachments(message).all)guestAttachmentData(item);
 }
}
async function importGuestAttachment(url,sourceChatId,messageIndex,attachmentIndex,expectedEmail,epoch){
 function check(){workspaceOwner(expectedEmail);if(accountIdentityRevision!==epoch)throw Error('The import account changed. The original attachment identity and guest content are retained.');}
 function identityFailure(){const error=Error('Guest attachment import identity storage is unavailable or changed. Keep the original guest content; no new operation was substituted.');error.code='IMPORT_IDENTITY_UNAVAILABLE';return error;}
 check();if(typeof sourceChatId!=='string'||!sourceChatId.length||sourceChatId.length>100||!Number.isInteger(messageIndex)||messageIndex<0||messageIndex>=1000||!Number.isInteger(attachmentIndex)||attachmentIndex<0||attachmentIndex>=64)throw identityFailure();
 const file=guestAttachmentData(url),name='Guest-attachment-'+(messageIndex+1)+'-'+(attachmentIndex+1);
 const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',file.bytes));check();
 const metadata={name,mime:file.mime,size:file.bytes.length,sha256:btoa(String.fromCharCode(...digest))};
 async function hash(value){const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(value))));check();return Array.from(digest,byte=>byte.toString(16).padStart(2,'0')).join('');}
 const source={owner:expectedEmail,sourceChatId,messageIndex,attachmentIndex},slot=await hash(source);check();const fingerprint=await hash({source,metadata});check();
 const key='starhub.guest-attachment-import.'+slot,uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;let retained;
 function remember(value){check();const text=JSON.stringify(value);try{sessionStorage.setItem(key,text);if(sessionStorage.getItem(key)!==text)throw identityFailure();}catch{throw identityFailure();}}
 try{
  const text=sessionStorage.getItem(key);
  if(text===null){retained={version:1,operationId:crypto.randomUUID(),fingerprint};remember(retained);}
  else{retained=JSON.parse(text);if(!retained||retained.version!==1||!uuid.test(retained.operationId||'')||retained.fingerprint!==fingerprint||Object.keys(retained).some(field=>!['version','operationId','fingerprint','fileId'].includes(field))||(retained.fileId!==undefined&&!uuid.test(retained.fileId)))throw identityFailure();}
 }catch{throw identityFailure();}
 const operationId=retained.operationId;
 function validate(value){
  check();if(value.operationId!==operationId||!['dual','single'].includes(value.confirmedDurability)||!Array.isArray(value.ids)||value.ids.length!==1||!uuid.test(value.ids[0]||'')||value.id!==value.ids[0]||!['RESERVED','COMMITTED','CANCELLING','CANCELLED'].includes(value.state)||value.ok!==(value.state==='COMMITTED')||(retained.fileId&&retained.fileId!==value.id))throw Error('The original attachment import is unconfirmed. Its operation identity is retained.');
  if(!retained.fileId){retained={...retained,fileId:value.id};remember(retained);}return value;
 }
 async function status(){check();const value=await sendWorkspaceRequest({action:'operationStatus',operationId},expectedEmail);check();return validate(value);}
 try{
  check();let value=validate(await sendWorkspaceRequest({action:'reserveUpload',operationId,files:[metadata]},expectedEmail));check();
  if(value.state==='COMMITTED')return value.id;
  if(value.state!=='RESERVED')throw Error('The original attachment import is pending cancellation or cancelled. It will not be replaced with another operation.');
  try{value=validate(await sendWorkspaceRequest({action:'upload',operationId,name,mime:file.mime,data:file.encoded},expectedEmail));check();}
  catch(error){check();try{const known=await status();if(known.state==='COMMITTED')return known.id;}catch(statusError){check();}throw error;}
  if(value.state!=='COMMITTED')throw Error('The original attachment import is not committed. Keep its operation identity.');return value.id;
 }catch(error){error.operationId=operationId;throw error;}
 // Retain the slot/fingerprint/UUID/file mapping even after a child succeeds.
 // A later attachment or chat save can fail; retry must recover this same file.
}

function canonicalImportValue(value){if(Array.isArray(value))return value.map(canonicalImportValue);if(value&&typeof value==='object')return Object.fromEntries(Object.keys(value).sort().filter(key=>value[key]!==undefined).map(key=>[key,canonicalImportValue(value[key])]));return value;}
function importedChatSignature(chat){return JSON.stringify(canonicalImportValue({title:chat.title,data:chatData(chat)}));}
async function guestChatTarget(chat,expectedEmail,epoch){
 function check(){workspaceOwner(expectedEmail);if(accountIdentityRevision!==epoch)throw Error('The import account changed. Original guest conversations and identities are retained.');}
 function conflict(){const error=Error('The original guest conversation or its import identity changed. Keep both copies and resolve the original import; no new conversation ID was substituted.');error.code='GUEST_CHAT_IMPORT_CONFLICT';return error;}
 check();if(typeof chat.id!=='string'||!chat.id.length||chat.id.length>100)throw conflict();
 async function hash(value){const input=new TextEncoder().encode(JSON.stringify(canonicalImportValue(value)));const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',input));check();return Array.from(digest,byte=>byte.toString(16).padStart(2,'0')).join('');}
 const slot=await hash({owner:expectedEmail,sourceChatId:chat.id});check();
 // Content and attachment data bind the immutable source; temporary Blob URLs,
 // rendering flags and generated preview position are deliberately excluded.
 const fingerprint=await hash({owner:expectedEmail,sourceChatId:chat.id,title:chat.title,data:chatData(chat),attachments:chat.messages.map(message=>({images:guestAttachments(message).images,attachments:message.attachments||[]}))});check();
 const key='starhub.guest-chat-import.'+slot,uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
 try{
  const text=sessionStorage.getItem(key);let identity;
  if(text===null){identity={version:1,targetChatId:crypto.randomUUID(),fingerprint};const encoded=JSON.stringify(identity);sessionStorage.setItem(key,encoded);if(sessionStorage.getItem(key)!==encoded)throw conflict();}
  else{identity=JSON.parse(text);if(!identity||identity.version!==1||!uuid.test(identity.targetChatId||'')||identity.fingerprint!==fingerprint||Object.keys(identity).some(field=>!['version','targetChatId','fingerprint'].includes(field)))throw conflict();}
  check();return identity.targetChatId;
 }catch(error){check();throw conflict();}
}

async function accountChatsOnLogin(guest){const importOwner=accountEmail,importEpoch=accountIdentityRevision;function checkImportOwner(){workspaceOwner(importOwner);if(accountIdentityRevision!==importEpoch)throw Error('The import account changed. Guest content has been retained.');}cloudReady=false;cloudFailed=false;const cloud=await loadCloudChats();checkImportOwner();const importCandidates=guest.map(chat=>({...chat,messages:chat.messages.map(message=>({...message,fileIds:message.fileIds?.slice(),images:message.images?.slice(),attachments:message.attachments?.slice(),mediaRefs:StarProviderMedia.references(message.mediaRefs||message.media||[]),media:(message.media||[]).map(item=>({...item,mediaRef:item.mediaRef?{...item.mediaRef}:undefined}))}))}));let take=false;if(guest.some(c=>c.messages.length))take=await askImport();checkImportOwner();
 if(take){checkGuestImportable(importCandidates);const selected=importCandidates.filter(c=>c.messages.length),targets=new Map();
  // Verify every retained target intent before the first file copy or chat save.
  for(const c of selected){checkImportOwner();const target=await guestChatTarget(c,importOwner,importEpoch);checkImportOwner();targets.set(c,{sourceChatId:c.id,target});}
  for(const c of selected){const {sourceChatId,target}=targets.get(c);c.id=target;c.revision=0;delete c.savedPayload;delete c.savedDurability;
   for(const [messageIndex,m] of c.messages.entries()){checkImportOwner();const originalRefs=StarProviderMedia.references(m.mediaRefs||m.media||[]);if(originalRefs.some(ref=>ref.mediaRef)){const imported=[];for(const ref of originalRefs){checkImportOwner();imported.push(ref.mediaRef?await importGeneratedImage(ref,m.providerOperationId,importOwner,importEpoch):ref);checkImportOwner();}m.mediaRefs=imported;StarProviderMedia.release(m.media||[]);m.media=[];}const {images,all}=guestAttachments(m);if(all.length){const ids=[];for(const [attachmentIndex,item] of all.entries()){checkImportOwner();ids.push(await importGuestAttachment(item,sourceChatId,messageIndex,attachmentIndex,importOwner,importEpoch));checkImportOwner();}m.fileIds=ids;m.imageCount=images.length;}}
   checkImportOwner();const existing=cloud.find(item=>item.id===target);
   if(existing){if(importedChatSignature(existing)!==importedChatSignature(c)){const error=Error('The previously imported account conversation has changed. Both the account and original guest conversations are retained; it will not be overwritten.');error.code='GUEST_CHAT_IMPORT_CONFLICT';throw error;}}
   else cloud.push(c);
  }
 }
 checkImportOwner();chats=cloud;current=undefined;if(chats.length)activateChat(chats[0]);else newChat();cloudReady=true;if(take)await saveCloudChats();}
function askImport(){return new Promise(resolve=>{const d=$('importDialog');d.oncancel=e=>e.preventDefault();$('importCheck').checked=false;$('importConfirm').onclick=()=>{const yes=$('importCheck').checked;d.close();resolve(yes);};d.showModal();});}
async function initCloudSession(){if(!accountEmail)return false;const email=accountEmail,epoch=accountIdentityRevision;cloudReady=false;const loaded=await loadCloudChats();workspaceOwner(email,epoch);chats=loaded;current=undefined;if(chats.length)activateChat(chats[0]);else newChat();cloudReady=true;return true;}
async function loadServerIdentity(){
 const badge=$('serverBadge');badge.hidden=true;
 try{const response=await fetch('api/server',{cache:'no-store',credentials:'same-origin'});if(!response.ok)return;const info=await response.json();if(!Number.isSafeInteger(info.serverId)||info.serverId<1)return;displayedServerId=info.serverId;badge.textContent=t('服务器 {0}',displayedServerId);badge.hidden=false;}catch{displayedServerId=null;}
}
function renderFileLibraryStatus(){
 if(!fileLibraryResult)return;
 const r=fileLibraryResult,total=r.total,pages=Math.max(1,Math.ceil(total/r.pageSize));
 $('libraryUsage').textContent=t('{0} / {1} GB · 包含回收箱和待完成上传',(r.used/1_000_000_000).toFixed(3),(r.quota/1_000_000_000).toLocaleString(undefined,{maximumFractionDigits:3}));
 const start=total===0?0:(r.page-1)*r.pageSize+1,end=total===0?0:start+r.files.length-1;
 $('fileLibraryPageInfo').textContent=t('{0}–{1} / {2} 个文件 · 第 {3} / {4} 页',start,end,total,r.page,pages);
 $('fileLibrarySelection').textContent=t('已选择 {0} 个文件',fileLibraryChecked.size);
 $('fileLibraryPrevious').disabled=r.page<=1;$('fileLibraryNext').disabled=!r.hasNext;
}
async function openLibrary(){if(!accountEmail){showError(t('请登录后使用文件库。'));return;}const d=$('libraryDialog');if(!d.open){fileLibraryChecked.clear();d.showModal();}await refreshFiles(1);}
async function refreshFiles(page=fileLibraryPage){
 const request=++fileLibraryRequest,email=accountEmail,epoch=accountIdentityRevision,out=$('libraryFiles');
 const libraryContextCurrent=()=>request===fileLibraryRequest&&email===accountEmail&&epoch===accountIdentityRevision;
 if(fileLibraryOwner!==email||fileLibraryEpoch!==epoch){fileLibraryOwner=email;fileLibraryEpoch=epoch;fileLibraryResult=null;pendingUploadPage=1;fileLibraryChecked.clear();librarySelection.clear();out.replaceChildren();$('libraryUsage').textContent='';$('fileLibraryPageInfo').textContent='';$('fileLibrarySelection').textContent='';}
 $('fileLibraryPrevious').disabled=true;$('fileLibraryNext').disabled=true;out.setAttribute('aria-busy','true');
 try{
  const r=await workspaceRequest({action:'listFiles',page,pageSize:FILE_LIBRARY_PAGE_SIZE,pendingPage:pendingUploadPage});
  if(request!==fileLibraryRequest||email!==accountEmail||epoch!==accountIdentityRevision)return;
  if(!Array.isArray(r.files)||!Number.isSafeInteger(r.total)||r.total<0||!Number.isSafeInteger(r.page)||r.page<1||!Number.isSafeInteger(r.pageSize)||r.pageSize<1||!Number.isFinite(r.used)||r.used<0||!Number.isFinite(r.quota)||r.quota<=0)throw Error(t('账户存储失败'));
  const lastPage=Math.max(1,Math.ceil(r.total/r.pageSize));
  if(r.page>lastPage){await refreshFiles(lastPage);return;}
  fileLibraryPage=r.page;fileLibraryResult=r;out.replaceChildren();
  if(Array.isArray(r.pendingImageGenerations)&&r.pendingImageGenerations.length){
   const heading=document.createElement('div');heading.className='file-row';heading.textContent=t('生成图片的待完成请求已计入预留配额。请在原标签页查询结果，不要重复发送。');out.append(heading);
   for(const pending of r.pendingImageGenerations){const row=document.createElement('div');row.className='file-row';row.textContent=String(pending.operationId||'')+' · '+((pending.reservedBytes||0)/1_000_000).toFixed(3)+' MB · '+String(pending.state||'UNKNOWN');out.append(row);}
  }
  if(Array.isArray(r.pendingUploads)&&r.pendingUploads.length){
   pendingUploadPage=r.pendingPage||1;
   const heading=document.createElement('div');heading.className='file-row';heading.textContent=t('待完成上传：{0} 项，预留 {1} MB。',r.pendingTotal||r.pendingUploads.length,((r.reservedBytes||0)/1_000_000).toFixed(3));out.append(heading);
   for(const pending of r.pendingUploads){
    const row=document.createElement('div');row.className='file-row';const label=document.createElement('span');label.textContent=t('等待完成：{0}',(pending.files||[]).map(file=>file.name).join(', '));
    const cancel=document.createElement('button');cancel.type='button';cancel.textContent=t('取消待完成上传');cancel.onclick=async()=>{
     if(!libraryContextCurrent())return;
     if(!confirm(t('取消这项上传并释放预留空间？已完成的文件不会删除。')))return;cancel.disabled=true;
     try{const result=await workspaceRequest({action:'cancelUpload',operationId:pending.operationId});if(['CANCELLED','COMMITTED'].includes(result.state))forgetFileOperation(pending.operationId);$('libraryMessage').textContent=t(result.state==='COMMITTED'?'上传已完成，文件已保留。':'已取消待完成上传并释放预留空间。');await refreshFiles();}catch(error){$('libraryMessage').textContent=error.message;}finally{cancel.disabled=false;}
    };row.append(label,cancel);out.append(row);
   }
   if((r.pendingTotal||0)>100){const controls=document.createElement('div');controls.className='file-row';for(const direction of [-1,1]){const button=document.createElement('button');button.type='button';button.textContent=t(direction<0?'上一批待完成上传':'下一批待完成上传');button.disabled=direction<0?pendingUploadPage<=1:!r.pendingHasNext;button.onclick=()=>{pendingUploadPage+=direction;refreshFiles();};controls.append(button);}out.append(controls);}
  }
  for(const f of r.files){
   if(f.trashed)fileLibraryChecked.delete(f.id);
   const row=document.createElement('div');row.className='file-row';
   const label=document.createElement('label'),check=document.createElement('input');check.type='checkbox';check.value=f.id;check.disabled=f.trashed;check.className='file-choice';check.checked=fileLibraryChecked.has(f.id);
   check.onchange=()=>{if(!libraryContextCurrent())return;if(check.checked)fileLibraryChecked.add(f.id);else fileLibraryChecked.delete(f.id);renderFileLibraryStatus();};
   label.append(check,document.createTextNode(` ${f.name} (${(f.size/1024).toFixed(1)} KiB)${f.trashed?' · '+t('回收箱'):''}`));
   const toggle=document.createElement('button');toggle.type='button';toggle.textContent=f.trashed?t('恢复'):t('移入回收箱');
   toggle.onclick=async()=>{if(!libraryContextCurrent())return;toggle.disabled=true;try{await workspaceRequest({action:f.trashed?'restoreFile':'trashFile',id:f.id});fileLibraryChecked.delete(f.id);await refreshFiles();}catch(e){$('libraryMessage').textContent=e.message;}finally{toggle.disabled=false;}};
   row.append(label,toggle);
   if(f.trashed){const del=document.createElement('button');del.type='button';del.textContent=t('永久删除');del.onclick=async()=>{if(!libraryContextCurrent())return;if(!confirm(t('确认永久删除此文件？')))return;del.disabled=true;try{await workspaceRequest({action:'deleteFile',id:f.id});fileLibraryChecked.delete(f.id);await refreshFiles();}catch(e){$('libraryMessage').textContent=e.message;}finally{del.disabled=false;}};row.append(del);}
   out.append(row);
  }
  renderFileLibraryStatus();
 }catch(e){if(request===fileLibraryRequest&&email===accountEmail&&epoch===accountIdentityRevision){$('libraryMessage').textContent=e.message;renderFileLibraryStatus();}}
 finally{if(request===fileLibraryRequest&&epoch===accountIdentityRevision)out.setAttribute('aria-busy','false');}
}
$('openLibrary').onclick=openLibrary;$('closeFileLibrary').onclick=()=>$('libraryDialog').close();
$('fileLibraryPrevious').onclick=()=>refreshFiles(Math.max(1,fileLibraryPage-1));$('fileLibraryNext').onclick=()=>refreshFiles(fileLibraryPage+1);
$('libraryUpload').onchange=async()=>{const files=[...$('libraryUpload').files],email=accountEmail,epoch=accountIdentityRevision;const check=()=>workspaceOwner(email,epoch);$('libraryMessage').textContent=t('上传中…');try{check();for(const f of files){check();if(f.size>4*1024*1024)throw Error(t('每个文件最多 4 MiB。'));const data=await readFileData(f);check();await cloudUpload(data,f.name);check();} $('libraryMessage').textContent=t('已上传。');await refreshFiles(1);}catch(e){if(accountEmail===email&&accountIdentityRevision===epoch)$('libraryMessage').textContent=e.message;}finally{if(accountEmail===email&&accountIdentityRevision===epoch)$('libraryUpload').value='';}};
$('useLibraryFiles').onclick=async()=>{const email=accountEmail,epoch=accountIdentityRevision,ids=[...fileLibraryChecked];const check=()=>workspaceOwner(email,epoch);if(!ids.length||ids.length>4){$('libraryMessage').textContent=t('请选择 1–4 张图片。');return;}try{check();let files=[];for(const id of ids){const file=await readCloudFile(id);check();files.push({...file,id});}check();if(files.some(f=>!['image/png','image/jpeg','image/webp'].includes(f.mime))||files.reduce((n,f)=>n+atob(f.data).length,0)>4*1024*1024)throw Error(t('聊天支持 1–4 张 PNG、JPEG 或 WebP 图片，总计不超过 4 MiB。'));if(!selectedModel()?.inputs.includes('image')||$('task').value!=='chat')throw Error(t('请先选择支持图片的聊天模型。'));newChat();const transfer=new DataTransfer();librarySelection.clear();for(const f of files){const bytes=Uint8Array.from(atob(f.data),c=>c.charCodeAt(0));transfer.items.add(new File([bytes],f.name,{type:f.mime}));librarySelection.set(f.url,f.id);}$('inputFile').files=transfer.files;$('inputFile').dispatchEvent(new Event('change'));$('libraryDialog').close();}catch(e){$('libraryMessage').textContent=e.message;}};
document.addEventListener('starhub:account-changed',()=>{fileLibraryRequest++;fileLibraryEpoch=null;fileLibraryResult=null;fileLibraryChecked.clear();librarySelection.clear();$('libraryFiles').replaceChildren();for(const id of ['libraryUsage','fileLibraryPageInfo','fileLibrarySelection','libraryMessage'])$(id).textContent='';});
document.addEventListener('starhub:languagechanged',()=>{renderFileLibraryStatus();if(displayedServerId!==null)$('serverBadge').textContent=t('服务器 {0}',displayedServerId);});
loadServerIdentity();

checkClusterHealth();
setInterval(()=>{if(!document.hidden)checkClusterHealth();},15000);
