'use strict';
// Persist references only. Authenticated image bytes and object URLs stay in memory.
(function(root){
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,MIMES=new Set(['image/png','image/jpeg','image/webp']),MAX=12*1024*1024;
function references(items){const images=(Array.isArray(items)?items:[]).filter(item=>item?.type==='image');return images.map(item=>{
 if(UUID.test(item.fileId||''))return {type:'image',...(MIMES.has(item.mime)?{mime:item.mime}:{}),fileId:item.fileId,...(UUID.test(item.importOperationId||'')?{importOperationId:item.importOperationId}:{})};
 const ref=item.mediaRef;if(ref&&UUID.test(ref.operationId||'')&&UUID.test(ref.mediaId||'')&&MIMES.has(ref.mimeType))return {type:'image',mime:ref.mimeType,mediaRef:{operationId:ref.operationId,mediaId:ref.mediaId,mimeType:ref.mimeType}};
 return null;
}).filter(Boolean);}
function create({fetch,crypto,URL,Blob,actor,tab}){
 let generation=0,queue=Promise.resolve();const urls=new Set(),requests=new Set();
 function invalidate(){generation++;for(const request of requests)request.abort();requests.clear();}
 function clear(){invalidate();for(const url of urls)URL.revokeObjectURL(url);urls.clear();}
 function release(items){for(const item of items||[])if(urls.delete(item.url))URL.revokeObjectURL(item.url);}
 function owns(url){return urls.has(url);}
 async function hydrate(items,{operationId,expectedEmail=String(actor()),active=()=>true}={}){
  const refs=references(items),captured=generation,ownerTab=tab();
  function check(){if(generation!==captured||String(actor())!==expectedEmail||tab()!==ownerTab||!active())throw Error('账户或对话已更改，请在原对话中重新读取图片。');}
  if(!UUID.test(operationId||'')||!/^[0-9a-f]{32}$/.test(ownerTab))throw Error('原图片的读取身份无效。');
  check();if(refs.length>4)throw Error('每次最多预览四张图片。');const result=[];
  try{
   for(const ref of refs){
    if(ref.mediaRef&&ref.mediaRef.operationId!==operationId)throw Error('图片引用与原请求不一致。');
    const perform=async()=>{
     check();const controller=new AbortController();requests.add(controller);const timer=setTimeout(()=>controller.abort(),30000);let reader;
     try{
      const response=await fetch('api/provider-media',{method:'POST',credentials:'same-origin',cache:'no-store',redirect:'error',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify({operationId,ownerTab,expectedEmail,mediaId:ref.fileId||ref.mediaRef.mediaId,...(ref.importOperationId?{importOperationId:ref.importOperationId}:{})}),signal:controller.signal});check();
      const mime=(response.headers.get('Content-Type')||'').split(';')[0].trim().toLowerCase(),sha=response.headers.get('X-Content-SHA256'),length=response.headers.get('Content-Length');
      if(response.status!==200||!MIMES.has(mime)||(ref.mime&&ref.mime!==mime)||!['dual','single'].includes(response.headers.get('X-Four-Project-Durability'))||response.headers.get('X-Starhub-Operation-ID')!==operationId||response.headers.get('X-Starhub-Operation-State')!=='COMPLETED'||!/^[0-9a-f]{64}$/.test(sha||'')||(length!==null&&(!/^[1-9][0-9]*$/.test(length)||Number(length)>MAX))||(ref.importOperationId&&(response.headers.get('X-Starhub-Import-Operation-ID')!==ref.importOperationId||response.headers.get('X-Starhub-Import-State')!=='COMMITTED'))||!response.body)throw Error('图片暂时不可读取，请保留原请求并稍后重试。');
      reader=response.body.getReader();let total=0;const parts=[];
      while(true){const chunk=await reader.read();check();if(chunk.done)break;total+=chunk.value.byteLength;if(total>MAX)throw Error('图片超过允许大小。');parts.push(chunk.value);}
      if(!total||(length!==null&&total!==Number(length)))throw Error('图片传输未完成，请重新读取。');
      const bytes=new Uint8Array(total);let offset=0;for(const part of parts){bytes.set(part,offset);offset+=part.byteLength;}parts.length=0;
      const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),value=>value.toString(16).padStart(2,'0')).join('');check();if(digest!==sha)throw Error('图片内容校验失败，请重新读取。');
      const url=URL.createObjectURL(new Blob([bytes],{type:mime}));urls.add(url);return {...ref,url};
     }finally{clearTimeout(timer);controller.abort();requests.delete(controller);if(reader){await reader.cancel().catch(()=>{});reader.releaseLock();}}
    };
    const pending=queue.then(perform);queue=pending.catch(()=>{});result.push(await pending);check();
   }
   return result;
  }catch(error){release(result);throw error;}
 }
 return {hydrate,invalidate,clear,release,owns,references};
}
if(typeof module==='object'&&module.exports)module.exports={create,references,MAX};
if(root.document){root.StarProviderMedia=create({fetch:(...args)=>root.fetch(...args),crypto:root.crypto,URL:root.URL,Blob:root.Blob,actor:()=>typeof accountEmail==='string'?accountEmail:'',tab:()=>StarStore.providerTab()});root.document.addEventListener('starhub:account-changed',()=>root.StarProviderMedia.clear());}
})(globalThis);
