'use strict';
// The network and presentation run independently: bursts still appear left to right.
class ReplyReveal {
  constructor(message) {
    this.message=message;this.pending='';this.finished=false;this.immediate=false;this.frame=0;this.last=performance.now();
    this.segmenter=typeof Intl.Segmenter==='function'?new Intl.Segmenter(undefined,{granularity:'grapheme'}):null;
    message.content='';message.displayText='';message.revealing=true;message.reveal=this;
    this.drained=new Promise(resolve=>this.resolve=resolve);
  }
  append(text) {
    if(!text)return;
    this.message.content+=text;this.pending+=text;
    if(this.immediate)this.flush();else this.schedule();
  }
  schedule(){if(!this.frame)this.frame=setTimeout(()=>this.tick(),33);}
  tick(){
    this.frame=0;
    const now=performance.now(),elapsed=Math.min(100,now-this.last);this.last=now;
    const amount=Math.max(1,Math.min(24,Math.floor(elapsed*(65+this.pending.length/24)/1000)));
    const prefix=this.pending.slice(0,amount*12+32);
    const parts=this.segmenter?Array.from(this.segmenter.segment(prefix),s=>s.segment):Array.from(prefix);
    const piece=parts.slice(0,amount).join('');
    this.message.displayText+=piece;this.pending=this.pending.slice(piece.length);queueRender();
    if(this.pending)this.schedule();else if(this.finished)this.complete();
  }
  flush(){
    clearTimeout(this.frame);this.frame=0;this.message.displayText=this.message.content;this.pending='';queueRender();
    if(this.finished)this.complete();
  }
  showAll(){this.immediate=true;this.flush();}
  finish(){this.finished=true;if(!this.pending)this.complete();else this.schedule();return this.drained;}
  complete(){clearTimeout(this.frame);this.frame=0;this.message.revealing=false;delete this.message.reveal;delete this.message.displayText;queueRender();this.resolve();}
}
async function readReplyEvents(response,onDelta,onReasoning=()=>{}){
  if(!response.body)throw new Error(t("浏览器未提供可读取的回复流，请更新浏览器后重试。"));
  const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='',event='message',data=[];
  function dispatch(){
    if(!data.length){event='message';return;}
    const kind=event;event='message';let payload;
    try{payload=JSON.parse(data.join('\n'));}catch{throw new Error(t("收到无法解析的回复片段。"));}data=[];
    if(kind==='error'){const failure=new Error(payload.error||t("回复连接中断，已保留收到的文字。"));failure.operationId=payload.operationId;failure.operationState=payload.operationState;throw failure;}
    if(kind==='delta'&&typeof payload.text==='string')onDelta(payload.text);
    if(kind==='reasoning'&&typeof payload.text==='string')onReasoning(payload.text);
    if(kind==='done')return payload;
  }
  try{
    while(true){
      const chunk=await reader.read();buffer+=decoder.decode(chunk.value||new Uint8Array(),{stream:!chunk.done});
      let boundary;
      while((boundary=buffer.indexOf('\n'))>=0){
        const line=buffer.slice(0,boundary).replace(/\r$/,'');buffer=buffer.slice(boundary+1);
        if(!line){const result=dispatch();if(result)return result;}
        else if(line.startsWith('event:'))event=line.slice(6).trim();
        else if(line.startsWith('data:'))data.push(line.slice(5).replace(/^ /,''));
        if(data.join('').length>1024*1024)throw new Error(t("回复片段过大，连接已结束。"));
      }
      if(buffer.length>1024*1024)throw new Error(t("回复片段过大，连接已结束。"));
      if(chunk.done)throw new Error(t("回复连接提前中断，已保留收到的文字。"));
    }
  }finally{await reader.cancel().catch(()=>{});reader.releaseLock();}
}
