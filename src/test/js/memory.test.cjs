'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');const path=require('node:path');
function harness(){
 const calls=[],ops=[],document={getElementById(){return null;},addEventListener(){},createElement(){throw Error('unexpected DOM');}};
 const ctx={document,setTimeout,clearTimeout,accountEmail:'user@example.test',accountIdentityRevision:1,language:'en',window:{},errorText:x=>x,workspaceRequest:async body=>{calls.push(body);if(body.action==='listMemories')return {enabled:true,revision:7,memories:[]};if(body.action==='saveSemanticMemory')return {saved:1,revision:8};throw Error('unexpected request');},StarProviderOperations:{async prepare(kind,payload){ops.push(['prepare',kind]);return {id:'test-operation',body:{...payload,ownerTab:'test',operationId:'test-operation'}};},async requestPrepared(){ops.push(['dispatch']);return {response:{ok:true,status:200,async json(){return {operationId:'test-operation',operationState:'COMPLETED',durability:'single'};}}};},observe(...args){ops.push(['observe',...args]);},acknowledge(id){ops.push(['ack',id]);},discardRejected(){ops.push(['discard']);},markUnknown(){ops.push(['unknown']);}}};
 vm.createContext(ctx);vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/webapp/memory.js'),'utf8'),ctx);ctx.api=vm.runInContext('StarMemory',ctx);return {ctx,calls,ops};
}
test('semantic extraction captures consent and saves only a bound completed operation',async()=>{
 const {ctx,calls,ops}=harness();const selection={provider:'openai',model:'gpt-6.2-sol',apiKey:'synthetic-key',sourceChatId:'chat',sourceMessageIndex:0};
 const token=await ctx.api.prepareSemantic('我喜欢徒步旅行',ctx.accountEmail,1,selection);assert.equal(token.revision,7);
 await ctx.api.rememberSemantic(token);assert.deepEqual(ops.map(x=>x[0]),['prepare','dispatch','observe','ack']);
 assert.equal(calls[1].action,'saveSemanticMemory');assert.equal(calls[1].memoryRevision,7);assert.equal(calls[1].extraction.operationId,'test-operation');
});
test('mail-looking and sensitive input is not sent to the model',async()=>{
 const {ctx,calls}=harness();for(const text of ['我喜欢的邮件内容是徒步','我喜欢密码123456','我喜欢徒步\n发件人: test','他说我喜欢徒步'])assert.equal(await ctx.api.prepareSemantic(text,ctx.accountEmail,1,{}),null);assert.equal(calls.length,0);
});
test('common explicit memory and language preferences are eligible but unrelated questions are not',async()=>{
 const {ctx,calls}=harness();for(const text of ['请记住我喜欢中文回答','以后用中文回答','我的名字是小明','请称呼我为小明','Call me Alice','Please remember I prefer concise answers'])assert.ok(await ctx.api.prepareSemantic(text,ctx.accountEmail,1,{}),text);assert.equal(await ctx.api.prepareSemantic('今天谢菲尔德天气如何',ctx.accountEmail,1,{}),null);assert.equal(calls.length,6);
});
test('account change before extraction prevents the paid operation',async()=>{
 const {ctx,ops}=harness();const token=await ctx.api.prepareSemantic('我喜欢徒步旅行',ctx.accountEmail,1,{});ctx.accountIdentityRevision=2;await ctx.api.rememberSemantic(token);assert.equal(ops.length,0);
});
test('unknown paid result is not saved or silently sent twice',async()=>{
 const {ctx,calls,ops}=harness();ctx.StarProviderOperations.requestPrepared=async()=>{ops.push(['dispatch']);throw Error('network uncertain');};const token=await ctx.api.prepareSemantic('我喜欢徒步旅行',ctx.accountEmail,1,{});await assert.rejects(()=>ctx.api.rememberSemantic(token));assert.equal(ops.filter(x=>x[0]==='dispatch').length,1);assert.equal(calls.some(x=>x.action==='saveSemanticMemory'),false);
});
test('a slow memory check times out and never triggers a provider call',async()=>{
 const {ctx,ops}=harness();ctx.workspaceRequest=()=>new Promise(()=>{});ctx.setTimeout=fn=>{queueMicrotask(fn);return 1;};ctx.clearTimeout=()=>{};
 await assert.rejects(()=>ctx.api.prepareSemantic('我喜欢徒步旅行',ctx.accountEmail,1,{}),/timed out/);assert.equal(ops.length,0);
});
