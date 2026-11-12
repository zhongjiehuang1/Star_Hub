(function(root,factory){'use strict';if(typeof module==='object'&&module.exports)module.exports=factory();else root.StarChatPages=factory();})(typeof globalThis!=='undefined'?globalThis:this,function(){
 'use strict';
 async function collect(request,isCurrentOwner=()=>true){
  const chats=[],ids=new Set();let offset=0,snapshot=null,total=null,durability='dual';
  for(let page=0;page<500;page++){
   if(!isCurrentOwner())throw Error('The account changed while conversations were loading.');
   const result=await request({action:'listChats',paginationProtocol:1,offset,...(snapshot?{snapshot}:{})});
   if(!isCurrentOwner())throw Error('The account changed while conversations were loading.');
   if(result.paginationProtocol!==1||!/^[0-9a-f]{64}$/.test(result.snapshot||'')||!Number.isSafeInteger(result.total)||result.total<0||result.total>500
      ||!Array.isArray(result.chats)||result.chats.length>4||!['dual','single'].includes(result.confirmedDurability))throw Error('Conversation page could not be confirmed.');
   if(snapshot&&(snapshot!==result.snapshot||total!==result.total))throw Error('The conversation catalog changed. Reload before continuing.');
   snapshot=result.snapshot;total=result.total;if(result.confirmedDurability==='single')durability='single';
   for(const item of result.chats){
    if(!item||typeof item.id!=='string'||!/^[A-Za-z0-9_-]{1,100}$/.test(item.id)||ids.has(item.id)||typeof item.title!=='string'
       ||!Number.isSafeInteger(item.revision)||item.revision<1||!item.data||typeof item.data!=='object')throw Error('Conversation page contains an invalid or repeated record.');
    ids.add(item.id);chats.push(item);
   }
   if(chats.length>total)throw Error('Conversation catalog count is inconsistent.');
   if(result.nextOffset===null){if(chats.length!==total)throw Error('Conversation catalog ended early.');return {chats,confirmedDurability:durability};}
   if(!Number.isSafeInteger(result.nextOffset)||result.nextOffset!==offset+result.chats.length||result.nextOffset<=offset||result.nextOffset>=total)throw Error('Conversation page did not advance safely.');
   offset=result.nextOffset;
  }
  throw Error('Conversation page count exceeded its limit.');
 }
 return {collect};
});
