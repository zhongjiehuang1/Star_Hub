const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../main/webapp/app.js'),'utf8');
const start=source.indexOf('async function deleteChat(chat) {'),end=source.indexOf('function updateConnection()',start);assert(start>=0&&end>start);
function fixture({owner='owner@example.test',confirm=true,deleteCloud=async()=>{},saveHistory=()=>true,flush=async()=>{}}={}){
 const errors=[],saved=[],cloudCalls=[],scheduled=[],chat={id:'one',title:'A chat',messages:[{role:'user',content:'hello'}],draft:''},other={id:'two',title:'Other',messages:[],draft:''};
 const context=vm.createContext({window:{confirm:()=>confirm},busy:false,realtimeActive:false,chats:[chat,other],current:chat,accountEmail:owner,accountIdentityRevision:7,historySaveTimer:0,clearTimeout(){},crypto:require('node:crypto').webcrypto,
  t:(text,...args)=>text.replace(/\{(\d+)\}/g,(_,n)=>args[n]),$:(id)=>id==='prompt'?{value:'draft'}:{},
  setBusy:value=>context.busy=value,deleteCloudChat:async item=>{cloudCalls.push(item);return deleteCloud(item)},StarStore:{saveHistory:(...args)=>{saved.push(args);return saveHistory(...args)},flushConfirmed:flush},
  invalidateGeneratedPreviews(){},releaseGeneratedPreview(){},activateChat:item=>context.current=item,render(){},showError:message=>errors.push(message),scheduleHistorySave:()=>scheduled.push(context.chats.map(x=>x.id))
 });
 vm.runInContext(source.slice(start,end),context);return {context,chat,other,errors,saved,cloudCalls,scheduled,run:code=>vm.runInContext(code,context)};
}
test('canceling confirmation leaves the conversation and storage untouched',async()=>{
 const f=fixture({confirm:false});await f.run('deleteChat(chats[0])');assert.equal(f.context.chats.length,2);assert.equal(f.cloudCalls.length,0);assert.equal(f.saved.length,0);assert.equal(f.context.busy,false);
});
test('account deletion waits for confirmation from storage and rejects duplicate clicks',async()=>{
 let release;const gate=new Promise(resolve=>release=resolve),f=fixture({deleteCloud:()=>gate});
 const deleting=f.run('deleteChat(chats[0])');assert.equal(f.context.busy,true);assert.equal(f.context.chats.length,2);assert.equal(f.chat.deleting,true);await f.run('deleteChat(chats[0])');assert.equal(f.cloudCalls.length,1);
 release();await deleting;assert.equal(f.context.chats.length,1);assert.equal(f.context.current,f.other);assert.equal(f.context.busy,false);assert.equal(f.chat.deleting,undefined);assert.deepEqual(Array.from(f.scheduled[0]),['two']);
});
test('failed deletion keeps the conversation and reports the error',async()=>{
 const f=fixture({deleteCloud:async()=>{throw Error('stale revision')}});await f.run('deleteChat(chats[0])');assert.equal(f.context.chats.length,2);assert.equal(f.context.current,f.chat);assert.equal(f.errors[0],'stale revision');assert.equal(f.chat.deleting,undefined);assert.deepEqual(Array.from(f.scheduled[0]),['one','two']);
});
test('deleting the last conversation opens a new blank conversation',async()=>{
 const f=fixture();f.context.chats=[f.chat];await f.run('deleteChat(chats[0])');assert.equal(f.context.chats.length,1);assert.notEqual(f.context.current.id,'one');assert.equal(f.context.current.messages.length,0);
});
test('guest deletion waits for durable history before hiding the conversation',async()=>{
 let release;const gate=new Promise(resolve=>release=resolve),f=fixture({owner:'',flush:()=>gate});const deleting=f.run('deleteChat(chats[0])');
 assert.equal(f.context.chats.length,2);assert.deepEqual(Array.from(f.saved[0][0],x=>x.id),['two']);release();await deleting;assert.equal(f.context.chats.length,1);assert.equal(f.context.current,f.other);
});
test('guest persistence failure and account changes do not remove the current conversation',async()=>{
 const f=fixture({owner:'',flush:async()=>{throw Error('not confirmed')}});await f.run('deleteChat(chats[0])');assert.equal(f.context.chats.length,2);assert.match(f.errors[0],/删除结果尚未确认/);assert.deepEqual(Array.from(f.scheduled[0]),['one','two']);
 let release;const gate=new Promise(resolve=>release=resolve),g=fixture({deleteCloud:()=>gate});const deleting=g.run('deleteChat(chats[0])');g.context.accountEmail='another@example.test';g.context.accountIdentityRevision++;g.context.chats=[g.other];g.context.current=g.other;release();await deleting;assert.equal(g.context.chats.length,1);assert.equal(g.context.current,g.other);assert.equal(g.scheduled.length,0);
});
