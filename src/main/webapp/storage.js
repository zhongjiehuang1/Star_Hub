'use strict';
// Guest conversations, credentials, and attachments are scoped to this tab.
const StarStore = (() => {
  const PREFIX='starhub.v1.', storage=()=>window.localStorage;
  function read(name,fallback){try{const value=storage().getItem(PREFIX+name);return value===null?fallback:JSON.parse(value);}catch{return fallback;}}
  function write(name,value){try{storage().setItem(PREFIX+name,JSON.stringify(value));return true;}catch{return false;}}
  function remove(name){try{storage().removeItem(PREFIX+name);return true;}catch{return false;}}
  function preference(name,fallback){return read(name,fallback);}
  function selection(){const value=read('selection',{});return value&&typeof value==='object'&&!Array.isArray(value)?value:{};}
  function saveSelection(provider,models){return write('selection',{provider,models:Object.fromEntries([...models].filter(([p,m])=>typeof p==='string'&&typeof m==='string'))});}
  function historyEnabled(){return read('historyEnabled',false)===true;}
  function setHistoryEnabled(enabled){
    if(!enabled){const removed=remove('history');const saved=write('historyEnabled',false);return removed&&saved;}
    return write('historyEnabled',true);
  }
  let guestState={},guestMode=true,guestReady=false,tabId='',epoch='',lastEpochChange=0,lastVersion=0,timer,inflight=0,chain=Promise.resolve();
  const dirty=new Map(), lastQueued=new Map(), legacyNames={keys:'keys',history:'history',pending:'pendingFile'};
  function setGuestMode(enabled){if(enabled&&!guestMode){try{window.sessionStorage.removeItem(PREFIX+'keys');}catch{}}guestMode=enabled;}
  function randomId(){return crypto.randomUUID().replaceAll('-','');}
  function nextVersion(){lastVersion=Math.max(lastVersion+1,Date.now()*1000);return lastVersion;}
  async function isolateTab(){
    if(typeof BroadcastChannel==='undefined')return;
    const owner=randomId();let collision=false;const channel=new BroadcastChannel('starhub.guest.tabs');
    channel.onmessage=event=>{const m=event.data;if(!m||m.tab!==tabId||m.owner===owner)return;if(m.type==='probe')channel.postMessage({type:'occupied',tab:tabId,owner});else if(m.type==='occupied')collision=true;};
    channel.postMessage({type:'probe',tab:tabId,owner});await new Promise(resolve=>setTimeout(resolve,120));
    if(collision){tabId=randomId();window.sessionStorage.setItem(PREFIX+'tabId',tabId);}
    window.addEventListener('pagehide',()=>channel.close(),{once:true});
  }
  async function request(body){const r=await fetch('api/guest-session',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify({tab:tabId,...body})});if(!r.ok)throw Error('guest session unavailable');const result=await r.json();if(result.epoch&&epoch&&result.epoch!==epoch){epoch=result.epoch;const now=Date.now();if(now-lastEpochChange<10000)throw Error('session cookie unavailable');lastEpochChange=now;for(const field of ['history','keys','pending'])if(guestState[field]!==undefined&&(field!=='keys'||guestMode))queue(field,guestState[field],true);}else if(result.epoch)epoch=result.epoch;return result;}
  function reportFailure(){window.dispatchEvent(new CustomEvent('starhub:guest-storage-error'));}
  async function writeField(field,data,version){const tx=randomId();if(data.length>12000000)throw Error('guest data too large');await request({action:'begin',field,tx,version});for(let i=0;i<data.length;i+=100000)await request({action:'chunk',field,tx,index:i/100000,data:data.slice(i,i+100000)});await request({action:'commit',field,tx});}
  function flush(){clearTimeout(timer);timer=null;if(!guestReady||!dirty.size)return chain;const entries=[...dirty];dirty.clear();inflight++;chain=chain.then(async()=>{for(let i=0;i<entries.length;i++){const [field,item]=entries[i];try{await writeField(field,item.data,item.version);}catch(error){for(const [key,pending] of entries.slice(i))if(!dirty.has(key))dirty.set(key,pending);throw error;}}}).catch(reportFailure).finally(()=>{inflight--;});return chain;}
  function queue(field,value,force=false){if(!guestReady)return;const data=JSON.stringify(value);if(!force&&lastQueued.get(field)===data)return;lastQueued.set(field,data);dirty.set(field,{data,version:nextVersion()});clearTimeout(timer);timer=setTimeout(flush,150);}
  function snapshotRequest(){const values={};for(const name of (guestMode?['history','keys','pending']:['pending']))if(guestState[name]!==undefined)values[name]=guestState[name];return {tab:tabId,action:'snapshot',tx:randomId(),version:nextVersion(),values};}
  function unloadNeedsWait(){if(!guestReady||(!dirty.size&&!inflight))return false;return new TextEncoder().encode(JSON.stringify(snapshotRequest())).length>60000;}
  function flushOnPagehide(){
    if(!guestReady||(!dirty.size&&!inflight))return;const body=snapshotRequest(),json=JSON.stringify(body);
    if(new TextEncoder().encode(json).length>60000){flush();return;}
    try{window.sessionStorage.setItem(PREFIX+'pendingFlush',body.tx);fetch('api/guest-session',{method:'POST',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:json,keepalive:true}).then(r=>{if(!r.ok)throw Error();if(window.sessionStorage.getItem(PREFIX+'pendingFlush')===body.tx)window.sessionStorage.removeItem(PREFIX+'pendingFlush');}).catch(reportFailure);}catch{reportFailure();}
  }
  async function initGuest(){
    try{tabId=window.sessionStorage.getItem(PREFIX+'tabId')||'';if(!/^[0-9a-f]{32}$/.test(tabId)){tabId=randomId();window.sessionStorage.setItem(PREFIX+'tabId',tabId);}await isolateTab();const after=window.sessionStorage.getItem(PREFIX+'pendingFlush')||'';for(let i=0;;i++){guestState=await request({action:'get',after});if(!guestState.pendingFlush)break;if(i>=30){reportFailure();break;}await new Promise(resolve=>setTimeout(resolve,100));}if(after)window.sessionStorage.removeItem(PREFIX+'pendingFlush');guestReady=true;
      for(const [field,name] of Object.entries(legacyNames)){const raw=window.sessionStorage.getItem(PREFIX+name);if(raw===null||guestState[field]!==undefined||(field==='keys'&&!guestMode))continue;try{const value=JSON.parse(raw);await writeField(field,JSON.stringify(value),nextVersion());guestState[field]=value;window.sessionStorage.removeItem(PREFIX+name);}catch{reportFailure();}}
      for(const name of ['history','keys','pending'])if(guestState[name]!==undefined)lastQueued.set(name,JSON.stringify(guestState[name]));
      return true;
    }catch{guestReady=false;reportFailure();return false;}
  }
  function saveKeys(keys){guestState.keys=Object.fromEntries(keys);if(guestMode)queue('keys',guestState.keys);return guestReady;}
  function loadKeys(){const v=guestState.keys;return new Map(Object.entries(v&&typeof v==='object'?v:{}).filter(([k,val])=>/^[a-z0-9_-]{1,40}$/.test(k)&&typeof val==='string'&&val.length<=4096));}
  function packData(url){
    const match=/^(data:[^,;]+;base64,)([A-Za-z0-9+/=]+)$/.exec(url||'');if(!match)return null;
    try{const raw=atob(match[2]);let out='';for(let i=0;i<raw.length;i+=2)out+=String.fromCharCode((raw.charCodeAt(i)<<8)|(i+1<raw.length?raw.charCodeAt(i+1):0));return {prefix:match[1],size:raw.length,data:out};}catch{return null;}
  }
  function unpackData(p){
    if(!p||typeof p.prefix!=='string'||!/^data:[a-z0-9.+/-]+;base64,$/i.test(p.prefix)||typeof p.data!=='string'||!Number.isInteger(p.size)||p.size<0||p.size>32*1024*1024||p.data.length!==Math.ceil(p.size/2))return '';
    let raw='';for(let i=0;i<p.data.length;i++){const c=p.data.charCodeAt(i);raw+=String.fromCharCode(c>>8);if(i*2+1<p.size)raw+=String.fromCharCode(c&255);}return p.prefix+btoa(raw);
  }
  function savePending(files){try{const entries=(files||[]).map(f=>({name:f.name,type:f.type,data:packData(f.data)}));if(entries.some(f=>!f.data))return false;guestState.pending=entries;queue('pending',entries);return guestReady;}catch{return false;}}
  function loadPending(){try{const list=guestState.pending;if(!Array.isArray(list))return [];return list.slice(0,20).map(p=>({name:String(p.name||'file').slice(0,255),type:String(p.type||''),data:unpackData(p.data)})).filter(p=>p.data);}catch{return [];}}
  function snapshot(chats,currentId,session=false){
    return {version:1,currentId,chats:chats.map(c=>({id:c.id,title:c.title,draft:c.draft||'',messages:c.messages.filter(m=>typeof m.content==='string'&&m.content.length).map(m=>({role:m.role,content:m.content,provider:m.provider||'',model:m.model||'',providerName:m.providerName||'',details:m.details&&(m.details.webCitations||m.details.webSearchUsed)?{webCitations:(m.details.webCitations||[]).map(({url,title,start_index,end_index})=>({url,title,start_index,end_index})),webSearchUsed:!!m.details.webSearchUsed,googleWidget:m.details.googleWidget||''}:undefined,...(session&&m.image?{sessionImage:packData(m.image)}:{}),...(session&&m.images?.length?{sessionImages:m.images.map(packData)}:{}),...(session&&m.attachments?.length?{sessionAttachments:m.attachments.map(packData)}:{}),...(session&&m.media?.length?{sessionMedia:m.media.filter(x=>x&&typeof x.url==='string').map(x=>({type:x.type,filename:x.filename,url:x.url.startsWith('data:')?undefined:x.url,packed:packData(x.url)}))}:{}),attachmentOmitted:Boolean(m.attachmentOmitted||(!session&&(m.image||m.images?.length||m.attachments?.length||m.media?.length))||(session&&((m.image&&!packData(m.image))||m.attachments?.some(x=>!packData(x))||m.media?.some(x=>!x?.url?.startsWith('https://')&&!packData(x?.url))))),reasoningSummary:m.reasoningSummary||'',thinkingSeconds:m.thinkingSeconds,incomplete:Boolean(m.incomplete||m.revealing)}))}))};
  }
  function saveHistory(chats,currentId){
    const data=snapshot(chats,currentId);
    if(data.chats.length>500||data.chats.some(c=>c.messages.length>1000||c.messages.some(m=>m.content.length>500000))||JSON.stringify(data).length>2000000)return false;
    guestState.history=snapshot(chats,currentId,true);if(JSON.stringify(guestState.history).length>12000000)return false;queue('history',guestState.history);return guestReady;
  }
  function loadHistory(){
    let value=guestState.history||null;
    let legacy=false;if(!value&&historyEnabled()){value=read('history',null);legacy=Boolean(value);}
    if(!value||value.version!==1||!Array.isArray(value.chats)||value.chats.length>500||JSON.stringify(value).length>12000000)return null;
    const clean=[];const seen=new Set();
    for(const item of value.chats){
      if(!item||typeof item.id!=='string'||item.id.length>100||seen.has(item.id)||typeof item.title!=='string'||!Array.isArray(item.messages)||item.messages.length>1000)continue;
      seen.add(item.id);const messages=[];
      for(const m of item.messages){
        if(!m||!['user','assistant'].includes(m.role)||typeof m.content!=='string'||m.content.length>500000)continue;
        const record={role:m.role,content:m.content,reasoningSummary:typeof m.reasoningSummary==='string'?m.reasoningSummary.slice(0,100000):'',thinkingSeconds:Number.isFinite(m.thinkingSeconds)?m.thinkingSeconds:undefined,attachmentOmitted:m.attachmentOmitted===true,incomplete:m.incomplete===true};
        const image=unpackData(m.sessionImage);if(image)record.image=image;
        if(Array.isArray(m.sessionImages))record.images=m.sessionImages.slice(0,4).map(unpackData).filter(Boolean);
        if(Array.isArray(m.sessionAttachments))record.attachments=m.sessionAttachments.slice(0,20).map(unpackData).filter(Boolean);
        if(Array.isArray(m.sessionMedia))record.media=m.sessionMedia.slice(0,20).map(x=>({type:x.type,filename:x.filename,url:unpackData(x.packed)||(typeof x.url==='string'&&x.url.startsWith('https://')?x.url:'')})).filter(x=>x.url);
        if(Array.isArray(m.details?.webCitations)) record.details={webSearchUsed:m.details.webSearchUsed===true,googleWidget:typeof m.details.googleWidget==='string'?m.details.googleWidget.slice(0,100000):'',webCitations:m.details.webCitations.filter(c=>c&&typeof c.url==='string'&&typeof c.title==='string').slice(0,100).map(c=>({url:c.url.slice(0,4096),title:c.title.slice(0,500),start_index:c.start_index,end_index:c.end_index}))};
        for(const field of ['provider','model','providerName'])record[field]=typeof m[field]==='string'?m[field].slice(0,200):'';
        messages.push(record);
      }
      clean.push({id:item.id,title:item.title.slice(0,200),draft:typeof item.draft==='string'?item.draft.slice(0,30000):'',messages});
    }
    if(legacy&&clean.length){guestState.history=value;queue('history',value);write('historyEnabled',false);}
    return clean.length?{chats:clean,currentId:value.currentId}:null;
  }
  return {PREFIX,preference,write,selection,saveSelection,historyEnabled,setHistoryEnabled,snapshot,saveHistory,loadHistory,saveKeys,loadKeys,savePending,loadPending,initGuest,setGuestMode,flush,unloadNeedsWait,flushOnPagehide};
})();
if(typeof module!=='undefined')module.exports=StarStore;
