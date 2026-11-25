'use strict';
// Search results are untrusted. Preserve only bounded public HTTPS source links.
(function(root){
function safeCitation(item){
 if(!item||typeof item.url!=='string'||item.url.length>4096||typeof item.title!=='string')return null;
 try{
  const url=new URL(item.url),host=url.hostname.toLowerCase();
  if(url.protocol!=='https:'||url.username||url.password||url.port||host.includes(':')||!host.includes('.')||/^(?:\d+\.){3}\d+$/.test(host)||/(?:^|\.)(?:localhost|local|internal|test|invalid|onion)\.?$/.test(host))return null;
  const title=item.title.replace(/[\u0000-\u001f\u007f]/g,' ').trim().slice(0,500)||host;
  return {url:url.href,title,end_index:-1};
 }catch{return null;}
}
function handle(event,state,{notice,t}){
 if(!state||!event||event.type!=='starhub.web_search')return false;
 const callId=event.callId;if(typeof callId!=='string'||!/^[A-Za-z0-9_-]{1,150}$/.test(callId))return true;
 if(event.status==='searching'){state.pendingWebSearch={callId,citations:[],completed:false};notice(t('正在联网搜索…'));}
 else if(event.status==='completed'){
  if(state.pendingWebSearch?.callId!==callId)return true;
  const seen=new Set(),citations=[];
  for(const item of Array.isArray(event.citations)?event.citations.slice(0,40):[]){const c=safeCitation(item);if(c&&!seen.has(c.url)){seen.add(c.url);citations.push(c);if(citations.length===10)break;}}
  const realtimeSource=event.realtimeSource==='oai-weather'?'oai-weather':undefined;
  state.pendingWebSearch={callId,citations,realtimeSource,completed:true};notice(t('联网搜索完成，正在整理回答。'));
 }else if(event.status==='continuation'){
  if(state.pendingWebSearch?.callId===callId&&typeof event.responseId==='string'&&/^[A-Za-z0-9_-]{1,150}$/.test(event.responseId))state.pendingWebSearch.responseId=event.responseId;
 }else if(event.status==='failed'){if(state.pendingWebSearch?.callId===callId){state.pendingWebSearch=null;notice(t('联网搜索暂时失败，请稍后重试。'));}}
 else if(event.status==='cancelled'){if(state.pendingWebSearch?.callId===callId)state.pendingWebSearch=null;}
 else return false;
 return true;
}
function attach(message,state,responseId){const pending=state?.pendingWebSearch;if(!pending?.completed||!pending.responseId||pending.responseId!==responseId||message?.role!=='assistant')return;message.details={...(message.details||{}),webSearchUsed:true,webCitations:pending.citations,...(pending.realtimeSource==='oai-weather'?{realtimeSource:'oai-weather'}:{})};state.pendingWebSearch=null;}
function clear(state){if(state)state.pendingWebSearch=null;}
const api={safeCitation,handle,attach,clear};root.StarRealtimeSearch=api;if(typeof module==='object'&&module.exports)module.exports=api;
})(globalThis);
