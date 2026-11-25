const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const web=path.join(__dirname,'../../main/webapp');
function deferred(){let resolve;const promise=new Promise(done=>resolve=done);return {promise,resolve};}
function accountFixture(){
 const calls=[],pending=[],elements=new Map(),keys=new Map();
 const ctx=vm.createContext({crypto:require('node:crypto').webcrypto,sessionStorage:{getItem(){return null;},setItem(){},removeItem(){}},keys,StarStore:{setGuestMode(){},saveKeys(){}},$:id=>{if(!elements.has(id))elements.set(id,{});return elements.get(id);},t:x=>x,document:{addEventListener(){},dispatchEvent(){}},CustomEvent:class{},fetch:(url)=>{calls.push(url);const d=deferred();pending.push(d);return d.promise;}});
 vm.runInContext(fs.readFileSync(path.join(web,'account.js'),'utf8'),ctx);
 return {calls,pending,keys,run:s=>vm.runInContext(s,ctx)};
}
const response=(email,extra={})=>({ok:true,headers:{get:()=> 'dual'},json:async()=>({signedIn:true,email,keys:{example:'synthetic-key'},...extra})});
test('account restore shares only its active revision and consumes its bootstrap once',async()=>{
 const f=accountFixture(),first=f.run('restoreAccount(true)'),second=f.run('restoreAccount(true)');assert.equal(first,second);assert.equal(f.calls.length,1);assert.equal(f.calls[0],'api/account?bootstrap=1');
 f.pending[0].resolve(response('owner@example.test',{workspaceBootstrap:{paginationProtocol:1,chats:[]}}));await first;
 assert.equal(f.run('consumeWorkspaceBootstrap().confirmedDurability'),'dual');assert.equal(f.run('consumeWorkspaceBootstrap()'),null);
 const fresh=f.run('restoreAccount(true)');assert.equal(f.calls.length,2);f.pending[1].resolve(response('owner@example.test'));await fresh;assert.equal(f.run('consumeWorkspaceBootstrap()'),null);
});
test('a same-account login revision rejects a pre-login restore even when its reply arrives last',async()=>{
 const f=accountFixture(),old=f.run('restoreAccount(true)');f.run('accountIdentityRevision++');const current=f.run('restoreAccount(true)');
 f.pending[1].resolve(response('owner@example.test',{workspaceBootstrap:{marker:'current'}}));await current;
 f.pending[0].resolve(response('owner@example.test',{keys:{example:'stale-key'},workspaceBootstrap:{marker:'stale'}}));assert.equal(await old,false);assert.equal(f.keys.get('example'),'synthetic-key');assert.equal(f.run('consumeWorkspaceBootstrap().marker'),'current');
});
test('failed account restore is not cached and an old failure cannot clear a newer in-flight restore',async()=>{
 const f=accountFixture(),old=f.run('restoreAccount(true)');f.run('accountIdentityRevision++');const current=f.run('restoreAccount(true)');f.pending[0].resolve({ok:false});assert.equal(await old,false);assert.equal(f.run('restoreAccount(true)'),current);
 f.pending[1].resolve({ok:false});assert.equal(await current,false);const retry=f.run('restoreAccount(true)');assert.equal(f.calls.length,3);f.pending[2].resolve(response('owner@example.test'));await retry;
});
test('startup preloads catalog, renders authenticated chats early, and gates readiness on guest hydration',async()=>{
 const source=fs.readFileSync(path.join(web,'app.js'),'utf8'),init=source.slice(source.indexOf('async function init() {'),source.indexOf('\ninit();'));
 const guest=deferred(),events=[],elements=new Map();let domReady,selectedModel;
 const ctx=vm.createContext({window:{},ready:false,accountEmail:'owner@example.test',keys:new Map(),restoredSelection:{provider:'deepseek'},settings:new Map([['deepseek','deepseek-flash'],['openai','gpt-6.2-sol']]),publicInsecure:false,t:x=>x,CustomEvent:class{constructor(type){this.type=type;}},document:{addEventListener(type,handler){domReady=handler;},dispatchEvent(event){events.push(event.type);}},fetch:async url=>{events.push(url);return {ok:true,json:async()=>({providers:[{id:'openai',name:'OpenAI'}],models:[]})};},restoreAccount:async boot=>{assert.equal(boot,true);events.push('account');},StarStore:{initGuest(){events.push('guest');return guest.promise;}},initCloudSession:async()=>{events.push('chats');return true;},localizedCatalog:x=>x,updateAvailableDirections(){},setupLibrary(){},selectProvider(model){selectedModel=model;},showError:e=>events.push(e),Option:class{constructor(label,value){this.value=value;}},$:id=>{if(!elements.has(id))elements.set(id,{replaceChildren(...options){this.value=options[0]?.value??'';},classList:{add(){}},lastChild:{}});return elements.get(id);}});
 vm.runInContext(init,ctx);const running=vm.runInContext('init()',ctx);domReady();await new Promise(done=>setImmediate(done));assert.deepEqual(events,['api/catalog','account','guest','chats']);assert.equal(vm.runInContext('ready',ctx),false);guest.resolve(true);await running;assert.equal(vm.runInContext('ready',ctx),true);assert(events.includes('starhub:ready'));
 assert.equal(elements.get('provider').value,'openai');assert.equal(selectedModel,'gpt-6.2-sol');
});
test('an older plain restore cannot overwrite a newer bundled restore in the same account revision',async()=>{
 const f=accountFixture(),plain=f.run('restoreAccount()'),bundled=f.run('restoreAccount(true)');f.pending[1].resolve(response('owner@example.test',{workspaceBootstrap:{marker:'latest'}}));await bundled;f.pending[0].resolve(response('owner@example.test',{keys:{example:'older-key'}}));assert.equal(await plain,false);assert.equal(f.keys.get('example'),'synthetic-key');assert.equal(f.run('consumeWorkspaceBootstrap().marker'),'latest');
});

test('a signed-out restore retains the current account context until reauthentication',async()=>{
 const f=accountFixture(),first=f.run('restoreAccount()');f.pending[0].resolve(response('owner@example.test',{displayName:'Example Name'}));await first;assert.equal(f.run('accountDisplayName'),'Example Name');
 f.run('requestReauthentication=email=>{reauthEmail=email;}');const second=f.run('restoreAccount()');f.pending[1].resolve({ok:true,json:async()=>({signedIn:false})});await second;assert.equal(f.run('accountDisplayName'),'Example Name');assert.equal(f.run('reauthEmail'),'owner@example.test');
});
