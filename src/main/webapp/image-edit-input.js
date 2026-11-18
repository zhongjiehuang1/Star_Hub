'use strict';
// Prepare an authenticated generated-image preview for the bounded edit request.
// Image bytes are never persisted or fetched by this module.
(function(root){
const MAX_SOURCE=12*1024*1024, MAX_INPUT=4*1024*1024, MAX_PIXELS=16*1024*1024, MAX_EDGE=8192;
const MIMES=new Set(['image/png','image/jpeg','image/webp']);
function dimensions(bytes,mime){
 const b=bytes,view=new DataView(b.buffer,b.byteOffset,b.byteLength);
 let width=0,height=0;
 if(mime==='image/png'){
  if(b.length<24||![137,80,78,71,13,10,26,10].every((v,i)=>b[i]===v)||String.fromCharCode(...b.slice(12,16))!=='IHDR')throw Error('图片格式无效。');
  width=view.getUint32(16);height=view.getUint32(20);
 }else if(mime==='image/jpeg'){
  if(b.length<4||b[0]!==255||b[1]!==216)throw Error('图片格式无效。');
  let i=2;
  while(i+3<b.length){
   if(b[i++]!==255)throw Error('图片格式无效。');while(i<b.length&&b[i]===255)i++;
   const marker=b[i++];if(marker===217||marker===218)break;if(marker===1||(marker>=208&&marker<=215))continue;
   if(i+1>=b.length)break;const length=view.getUint16(i);if(length<2||i+length>b.length)break;
   if((marker>=192&&marker<=207)&&![196,200,204].includes(marker)){if(length<7)break;height=view.getUint16(i+3);width=view.getUint16(i+5);break;}i+=length;
  }
 }else if(mime==='image/webp'){
  if(b.length<30||String.fromCharCode(...b.slice(0,4))!=='RIFF'||String.fromCharCode(...b.slice(8,12))!=='WEBP')throw Error('图片格式无效。');
  const kind=String.fromCharCode(...b.slice(12,16));
  if(kind==='VP8X'){width=1+b[24]+(b[25]<<8)+(b[26]<<16);height=1+b[27]+(b[28]<<8)+(b[29]<<16);}
  else if(kind==='VP8L'&&b[20]===47){const bits=view.getUint32(21,true);width=(bits&16383)+1;height=((bits>>>14)&16383)+1;}
  else if(kind==='VP8 '&&b[23]===157&&b[24]===1&&b[25]===42){width=view.getUint16(26,true)&16383;height=view.getUint16(28,true)&16383;}
 }
 if(!width||!height||width>MAX_EDGE||height>MAX_EDGE||width*height>MAX_PIXELS)throw Error('图片尺寸过大或格式不受支持。');
 return {width,height};
}
function fromDataUrl(dataUrl){
 if(typeof dataUrl!=='string'||dataUrl.length>Math.ceil(MAX_SOURCE/3)*4+64)throw Error('原图片格式或大小不受支持。');
 const match=/^data:(image\/(?:png|jpeg|webp));base64,/i.exec(dataUrl);if(!match)throw Error('原图片格式或大小不受支持。');
 const encoded=dataUrl.slice(match[0].length);if(!encoded.length||encoded.length%4||!/^[A-Za-z0-9+/]*={0,2}$/.test(encoded))throw Error('图片格式无效。');
 const padding=encoded.endsWith('==')?2:encoded.endsWith('=')?1:0,length=encoded.length/4*3-padding;
 if(length<1||length>MAX_SOURCE)throw Error('原图片格式或大小不受支持。');
 const chunks=[];try{for(let offset=0;offset<encoded.length;offset+=32768){const raw=atob(encoded.slice(offset,offset+32768)),part=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)part[i]=raw.charCodeAt(i);chunks.push(part);}}catch{throw Error('图片格式无效。');}
 return new Blob(chunks,{type:match[1].toLowerCase()});
}
function create({createImageBitmap,makeCanvas,readDataUrl}){
 async function prepare(url,{blob,owns,active=()=>true,signal}={}){
  function check(){if(signal?.aborted||typeof active!=='function'||!active())throw Error('账户或对话已更改，图片编辑已取消。');if(typeof owns!=='function'||!owns(url))throw Error('原图片不可用，请重新打开原对话。');}
  if(typeof url!=='string'||!url.startsWith('blob:'))throw Error('原图片不可用，请重新打开原对话。');check();
  if(!(blob instanceof Blob))throw Error('原图片不可读取。');check();const mime=String(blob.type||'').toLowerCase();
  if(!MIMES.has(mime)||blob.size<1||blob.size>MAX_SOURCE)throw Error('原图片格式或大小不受支持。');
  const header=new Uint8Array(await blob.slice(0,Math.min(blob.size,512*1024)).arrayBuffer());check();const original=dimensions(header,mime);
  async function result(value,wasConverted,wasResized,width,height){
   check();if(value.size>=MAX_INPUT||value.size<1||!MIMES.has(value.type))throw Error('图片过大，无法用于编辑。');
   const dataUrl=await readDataUrl(value,signal);check();
   if(typeof dataUrl!=='string'||!/^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(dataUrl)||dataUrl.length>Math.ceil(MAX_INPUT/3)*4+64)throw Error('图片编码失败。');
   return {dataUrl,wasConverted,wasResized,width,height};
  }
  const bitmap=await createImageBitmap(blob);let canvas;
  try{
   check();if(bitmap.width!==original.width||bitmap.height!==original.height)throw Error('图片尺寸校验失败。');
   if(blob.size<MAX_INPUT)return await result(blob,false,false,original.width,original.height);
   canvas=makeCanvas();let width=bitmap.width,height=bitmap.height;
   for(let scale=0;scale<7;scale++){
    check();canvas.width=width;canvas.height=height;const ctx=canvas.getContext('2d');if(!ctx)throw Error('无法处理原图片。');
    ctx.clearRect(0,0,width,height);ctx.drawImage(bitmap,0,0,width,height);
    // WebP supports alpha; if unavailable browsers may return PNG instead.
    for(const quality of [0.95,0.90,0.85]){
     const encoded=await new Promise(resolve=>canvas.toBlob(resolve,'image/webp',quality));check();
     if(encoded&&['image/webp','image/png'].includes(encoded.type)&&encoded.size>0&&encoded.size<MAX_INPUT)return await result(encoded,true,scale>0,width,height);
    }
    const nextWidth=Math.max(1,Math.floor(width*0.8)),nextHeight=Math.max(1,Math.floor(height*0.8));
    if(nextWidth===width&&nextHeight===height)break;width=nextWidth;height=nextHeight;
   }
   throw Error('图片过大，无法用于编辑。');
  }finally{if(canvas){canvas.width=0;canvas.height=0;}bitmap.close?.();}
 }
 return {prepare};
}
function readDataUrl(blob,signal){return new Promise((resolve,reject)=>{
 const reader=new FileReader();const abort=()=>reader.abort();if(signal?.aborted){reject(Error('图片编辑已取消。'));return;}
 signal?.addEventListener('abort',abort,{once:true});reader.onload=()=>{signal?.removeEventListener('abort',abort);resolve(reader.result);};reader.onerror=reader.onabort=()=>{signal?.removeEventListener('abort',abort);reject(Error('图片读取失败。'));};reader.readAsDataURL(blob);
});}
const api={create,dimensions,fromDataUrl,MAX_SOURCE,MAX_INPUT};if(typeof module==='object'&&module.exports)module.exports=api;
if(root.document)root.StarImageEditInput={...create({createImageBitmap:(...args)=>root.createImageBitmap(...args),makeCanvas:()=>root.document.createElement('canvas'),readDataUrl}),fromDataUrl};
})(globalThis);
