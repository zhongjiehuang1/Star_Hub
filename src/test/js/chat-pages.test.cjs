const test=require('node:test'),assert=require('node:assert/strict');
const {collect}=require('../../main/webapp/chat-pages.js');
const snapshot='a'.repeat(64);
const item=i=>({id:'chat-'+i,title:'Synthetic '+i,revision:1,data:{messages:[]}});
const page=(items,total,nextOffset,extra={})=>({paginationProtocol:1,snapshot,total,chats:items,nextOffset,confirmedDurability:'dual',...extra});
test('all 500 allowed conversations arrive through bounded pages',async()=>{
 let calls=0;const value=await collect(async input=>{assert.equal(input.paginationProtocol,1);if(input.offset)assert.equal(input.snapshot,snapshot);calls++;
  const next=Math.min(500,input.offset+4);return page(Array.from({length:next-input.offset},(_,i)=>item(input.offset+i)),500,next===500?null:next,input.offset===4?{confirmedDurability:'single'}:{});});
 assert.equal(value.chats.length,500);assert.equal(calls,125);assert.equal(value.confirmedDurability,'single');
});
test('a changed catalog rejects the complete load instead of publishing earlier pages',async()=>{
 let calls=0,published=null;await assert.rejects(async()=>{published=await collect(async()=>++calls===1?page([item(1)],2,1):page([item(2)],2,null,{snapshot:'b'.repeat(64)}));},/catalog changed/);assert.equal(published,null);
});
test('account changes stop pagination before accepting another owners records',async()=>{
 let owner=true,calls=0;await assert.rejects(collect(async()=>{calls++;owner=false;return page([item(1)],1,null);},()=>owner),/account changed/);assert.equal(calls,1);
});
test('missing protocol, duplicate ids and stalled cursors fail closed',async()=>{
 await assert.rejects(collect(async()=>page([],0,null,{paginationProtocol:undefined})),/confirmed/);
 let calls=0;await assert.rejects(collect(async()=>++calls===1?page([item(1)],2,1):page([item(1)],2,null)),/repeated/);
 await assert.rejects(collect(async()=>page([item(1)],2,0)),/advance/);
});
test('an empty catalog is confirmed and an incomplete or oversized catalog is rejected',async()=>{
 assert.deepEqual((await collect(async()=>page([],0,null))).chats,[]);
 await assert.rejects(collect(async()=>page([],1,null)),/ended early/);
 await assert.rejects(collect(async()=>page([],501,null)),/confirmed/);
});
