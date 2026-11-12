const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function fixture(handler){
 const stored=new Map(),requests=[],elements=new Map(),element=id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);};
 const context=vm.createContext({crypto:require('node:crypto').webcrypto,sessionStorage:{getItem:k=>stored.get(k)||null,setItem:(k,v)=>stored.set(k,v),removeItem:k=>stored.delete(k)},
  $:element,CustomEvent:class{constructor(type){this.type=type;}},document:{addEventListener(){},dispatchEvent(){}},t:k=>k,encodeURIComponent,
  fetch:async(url,options)=>{const body=JSON.parse(options.body);requests.push(body);return handler(body,requests.length);}});
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/webapp/account.js'),'utf8'),context);vm.runInContext("accountEmail='owner@example.test'",context);
 return {stored,requests,run:code=>vm.runInContext(code,context)};
}
const ok=body=>({ok:true,status:200,headers:{get:()=> 'dual'},json:async()=>({ok:true,operationId:body.operationId,operationState:'COMMITTED'})});
test('password operation identity survives lost replies and reauthentication without storing credentials',async()=>{
 const f=fixture((body,n)=>{if(n===1)throw Error('lost response');if(n===2)return {ok:false,status:401,json:async()=>({code:'AUTHENTICATION_REQUIRED'})};return ok(body);});
 const call="accountRequest({action:'changePassword',currentPassword:'old-synthetic-password',newPassword:'new-synthetic-password'})";
 await assert.rejects(f.run(call),/lost response/);await assert.rejects(f.run(call));
 assert.equal(f.stored.size,1);for(const [key,value] of f.stored){assert(!key.includes('old-synthetic-password'));assert(!key.includes('new-synthetic-password'));assert.match(value,/^[0-9a-f-]{36}$/);}
 await f.run(call);assert.equal(f.requests[0].operationId,f.requests[1].operationId);assert.equal(f.requests[1].operationId,f.requests[2].operationId);assert.equal(f.stored.size,0);
});
test('editing a key during an unresolved operation does not silently obtain another operation identity',async()=>{
 const f=fixture((body,n)=>{if(n===1)throw Error('unknown result');return {ok:false,status:409,json:async()=>({code:'OPERATION_CONFLICT',error:'Resolve the original operation'})};});
 await assert.rejects(f.run("accountRequest({action:'addKey',provider:'example',label:'primary',key:'synthetic-key-one'})"));
 await assert.rejects(f.run("accountRequest({action:'addKey',provider:'example',label:'primary',key:'synthetic-key-two'})"),/Resolve/);
 assert.equal(f.requests[0].operationId,f.requests[1].operationId);for(const [key] of f.stored){assert(!key.includes('synthetic-key-one'));assert(!key.includes('synthetic-key-two'));}
});
test('an edited unknown login obtains a new ID only after an explicit completed-status query',async()=>{
 const f=fixture((body,n)=>{if(n===1)throw Error('lost response');if(n===2)return {ok:false,status:409,json:async()=>({code:'OPERATION_CONFLICT'})};if(n===3)return {ok:true,status:200,headers:{get:()=> 'dual'},json:async()=>({operationId:body.operationId,state:'COMMITTED',status:401})};return ok(body);});
 await assert.rejects(f.run("accountRequest({action:'login',email:'user@example.test',password:'synthetic-old'})"));
 await f.run("accountRequest({action:'login',email:'user@example.test',password:'synthetic-new'})");
 assert.equal(f.requests[0].operationId,f.requests[1].operationId);assert.equal(f.requests[2].action,'loginStatus');assert(!('password' in f.requests[2]));assert.notEqual(f.requests[0].operationId,f.requests[3].operationId);
});
test('unknown login status never causes the client to obtain a fresh operation identity',async()=>{
 const f=fixture((body,n)=>{if(n===1)throw Error('lost response');if(n===2)return {ok:false,status:409,json:async()=>({code:'OPERATION_CONFLICT'})};return {ok:false,status:404,json:async()=>({code:'OPERATION_NOT_FOUND'})};});
 await assert.rejects(f.run("accountRequest({action:'login',email:'user@example.test',password:'synthetic-old'})"));
 await assert.rejects(f.run("accountRequest({action:'login',email:'user@example.test',password:'synthetic-new'})"));assert.equal(f.requests.length,3);assert.equal(f.requests[0].operationId,f.requests[1].operationId);
});
test('a success without matching operation identity and durability retains the original password request',async()=>{
 const f=fixture((body,n)=>n===1?{ok:true,status:200,json:async()=>({ok:true})}:ok(body));
 const call="accountRequest({action:'changePassword',currentPassword:'test-only-current',newPassword:'test-only-new-password'})";
 await assert.rejects(f.run(call),/durably confirmed/);assert.equal(f.stored.size,1);await f.run(call);assert.equal(f.requests[0].operationId,f.requests[1].operationId);assert.equal(f.stored.size,0);
});
test('password failure is retired only after a matching confirmed terminal receipt',async()=>{
 const f=fixture(body=>({ok:false,status:403,headers:{get:()=> 'single'},json:async()=>({error:'Test-only known password failure',operationId:body.operationId,operationState:'COMMITTED'})}));
 await assert.rejects(f.run("accountRequest({action:'changePassword',currentPassword:'test-only-wrong',newPassword:'test-only-new-password'})"),e=>e.status===403&&e.confirmed);assert.equal(f.stored.size,0);
});
test('editing an unknown password request first queries the original terminal outcome',async()=>{
 const f=fixture((body,n)=>{if(n===1)throw Error('test-only lost reply');if(n===2)return {ok:false,status:409,json:async()=>({code:'OPERATION_CONFLICT'})};return ok(body);});
 f.run("let statusQueries=0;workspaceRequest=async data=>++statusQueries===1?{state:'UNKNOWN'}:{operationId:data.operationId,state:'COMMITTED',status:403,result:{error:'test-only previous failure'},confirmedDurability:'dual'}");
 await assert.rejects(f.run("accountRequest({action:'changePassword',currentPassword:'test-only-wrong-one',newPassword:'test-only-new-password'})"));
 await f.run("accountRequest({action:'changePassword',currentPassword:'test-only-wrong-two',newPassword:'test-only-new-password'})");assert.equal(f.requests.length,3);assert.equal(f.requests[0].operationId,f.requests[1].operationId);assert.notEqual(f.requests[0].operationId,f.requests[2].operationId);
});
test('an unconfirmed original password query cannot authorize a fresh operation',async()=>{
 const f=fixture((body,n)=>{if(n===1)throw Error('test-only lost reply');return {ok:false,status:409,json:async()=>({code:'OPERATION_CONFLICT'})};});
 f.run("workspaceRequest=async data=>({operationId:data.operationId,state:'COMMITTED',status:403,result:{error:'test-only previous failure'}})");
 await assert.rejects(f.run("accountRequest({action:'changePassword',currentPassword:'test-only-wrong-one',newPassword:'test-only-new-password'})"));await assert.rejects(f.run("accountRequest({action:'changePassword',currentPassword:'test-only-wrong-two',newPassword:'test-only-new-password'})"));assert.equal(f.requests.length,2);assert.equal(f.requests[0].operationId,f.requests[1].operationId);assert.equal(f.stored.size,1);
});
test('unavailable identity storage rejects before sending any account mutation',async()=>{
 const f=fixture(body=>ok(body));f.run("sessionStorage.setItem=()=>{throw Error('test-only blocked storage');}");await assert.rejects(f.run("accountRequest({action:'login',email:'test@example.test',password:'test-only-secret'})"),/blocked storage/);assert.equal(f.requests.length,0);
});
