'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const StarStore=require('../../main/webapp/storage.js');
test('Kimi opaque context is retained only when role, origin and size are valid',()=>{
 const message={role:'assistant',provider:'moonshot',model:'kimi-k3',content:'answer',providerContext:{provider:'moonshot',model:'kimi-k3',token:'abcABC012_-'}};
 assert.deepEqual(StarStore.providerContext(message),message.providerContext);
 for(const altered of [{...message,role:'user'},{...message,provider:'qwen'},{...message,model:'other'}, {...message,providerContext:{...message.providerContext,token:'x'.repeat(140001)}},{...message,providerContext:{...message.providerContext,token:42}}]) assert.equal(StarStore.providerContext(altered),undefined);
 assert.throws(()=>StarStore.snapshot([{id:'chat',title:'test',messages:[{...message,providerContext:{...message.providerContext,token:'x'.repeat(140001)}}]}],'chat',true),/not changed/);
 message.continuationUnavailable=true;
 const data=StarStore.snapshot([{id:'chat',title:'test',messages:[message]}],'chat',true);
 assert.deepEqual(data.chats[0].messages[0].providerContext,message.providerContext);
 assert.equal(data.chats[0].messages[0].continuationUnavailable,true);
});
