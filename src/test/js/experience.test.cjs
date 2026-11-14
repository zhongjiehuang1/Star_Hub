const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const root=path.join(__dirname,'../../main/webapp');
function fixture(saved={},shared={}){
 const data=shared.data||new Map(Object.entries(saved)),session=shared.session||new Map(),server=shared.server||new Map();let fail=false;
 const sessionStorage={getItem:k=>session.get(k)??null,setItem:(k,v)=>session.set(k,v),removeItem:k=>session.delete(k)};
 const localStorage={getItem:k=>{if(fail)throw Error('disabled');return data.get(k)??null},setItem:(k,v)=>{if(fail)throw Error('quota');data.set(k,v)},removeItem:k=>{if(fail)throw Error('disabled');data.delete(k)}};
 const fetch=async(url,options)=>{const q=JSON.parse(options.body);let tab=server.get(q.tab);if(!tab){tab={values:{}};server.set(q.tab,tab)}if(q.action==='get')return {ok:true,headers:{get:()=> 'dual'},json:async()=>structuredClone(tab.values)};if(q.action==='begin'){tab.staging='';tab.field=q.field;tab.tx=q.tx}else if(q.action==='chunk')tab.staging+=q.data;else if(q.action==='commit')tab.values[q.field]=JSON.parse(tab.staging);return {ok:true,headers:{get:()=> 'dual'},json:async()=>({state:'COMMITTED',action:q.action,transactionId:q.tx,operationId:'11111111-1111-4111-8111-111111111111'})}};
 const context=vm.createContext({window:{localStorage,sessionStorage,dispatchEvent(){}},document:{addEventListener(){}},structuredClone,atob,btoa,fetch,crypto:require('node:crypto').webcrypto,setTimeout,clearTimeout,CustomEvent:class{}});
 for(const f of ['storage.js','i18n-data.js','i18n.js'])vm.runInContext(fs.readFileSync(path.join(root,f),'utf8'),context);return {data,session,server,run:s=>vm.runInContext(s,context),fail:()=>fail=true};
}
test('model selection remembers each provider without credentials',()=>{const f=fixture();f.run("StarStore.saveSelection('deepseek',new Map([['openai','gpt-4.1-mini'],['deepseek','deepseek-flash']]))");assert.equal(f.run('StarStore.selection().provider'),'deepseek');assert.equal(f.run('StarStore.selection().models.openai'),'gpt-4.1-mini');assert.deepEqual(Object.keys(JSON.parse(f.data.get('starhub.v1.selection'))),['provider','models']);});
test('English is default; explicit Chinese choice survives a reload',()=>{const f=fixture();assert.equal(f.run('language'),'en');assert.equal(f.run("t('下一步')"),'Next');assert.equal(f.run("t('已设置 {0} 项',3)"),'3 settings customized');assert.equal(f.run("uiText('找到 60 个接入项')"),'60 integrations found');const zh=fixture({'starhub.v1.language':'"zh"'});assert.equal(zh.run("uiText('Next')"),'下一步');assert.equal(zh.run("uiText('3 settings customized')"),'已设置 3 项');});
test('English catalog is complete and preserves API identities and Chinese originals',()=>{const f=fixture(),raw=JSON.parse(fs.readFileSync(path.join(root,'../resources/catalog.json'),'utf8'));f.run('raw='+JSON.stringify(raw));f.run('localized=localizedCatalog(raw)');const local=JSON.parse(f.run('JSON.stringify(localized)'));for(let i=0;i<raw.models.length;i++){const m=local.models[i];assert.equal(m.id,raw.models[i].id);assert.ok(m.description.length>30);for(const key of ['name','vendorName','family','providerName','description','executionNote','workbenchNote','keyLabel','accessLabel'])assert.ok(!/[\u3400-\u9fff]/.test(m[key]||''),`${m.id}: ${key}`);for(const specs of Object.values(m.settings||{}))for(const spec of specs)for(const key of ['label','help'])assert.ok(!/[\u3400-\u9fff]/.test(spec[key]||''),`${m.id}: ${spec.key} ${key}`);}assert.equal(f.run('raw.models[0].description'),raw.models[0].description);});



test('page element IDs are unique',()=>{const html=fs.readFileSync(path.join(root,'index.html'),'utf8');const ids=[...html.matchAll(/\bid="([^"]+)"/g)].map(m=>m[1]);assert.equal(new Set(ids).size,ids.length);});
test('guest state uses server session and restores only in the same tab',async()=>{const f=fixture();await f.run('StarStore.initGuest()');f.run("StarStore.saveKeys(new Map([['openai','test-key']]));StarStore.saveHistory([{id:'a',title:'chat',messages:[{role:'user',content:'hello'}]}],'a')");await f.run('StarStore.flush()');assert.equal(f.data.has('starhub.v1.history'),false);assert.equal(f.session.size,1);assert.ok(!JSON.stringify([...f.session]).includes('test-key'));const reload=fixture({}, {data:f.data,session:f.session,server:f.server});await reload.run('StarStore.initGuest()');assert.equal(reload.run("StarStore.loadKeys().get('openai')"),'test-key');assert.equal(reload.run('StarStore.loadHistory().chats[0].title'),'chat');const other=fixture({}, {data:f.data,server:f.server});await other.run('StarStore.initGuest()');assert.equal(other.run('StarStore.loadHistory()'),null);assert.equal(other.run('StarStore.loadKeys().size'),0);});
test('legacy data migrates without deleting permanent saved copy',async()=>{const original=JSON.stringify({version:1,currentId:'a',chats:[{id:'a',title:'old',messages:[{role:'user',content:'old'}]}]});const f=fixture({'starhub.v1.historyEnabled':'true','starhub.v1.history':original});await f.run('StarStore.initGuest()');assert.equal(f.run('StarStore.loadHistory().chats[0].title'),'old');await f.run('StarStore.flush()');assert.equal(f.data.get('starhub.v1.history'),original);assert.equal(f.run('StarStore.historyEnabled()'),false);const reload=fixture({}, {data:f.data,session:f.session,server:f.server});await reload.run('StarStore.initGuest()');assert.equal(reload.run('StarStore.loadHistory().chats[0].title'),'old');});
test('legacy tab keys and attachments migrate after successful save',async()=>{const f=fixture();f.session.set('starhub.v1.keys',JSON.stringify({openai:'legacy-key'}));f.session.set('starhub.v1.pendingFile',JSON.stringify([{name:'a.png',type:'image/png',data:{prefix:'data:image/png;base64,',size:3,data:String.fromCharCode(1,512)}}]));await f.run('StarStore.initGuest()');assert.equal(f.run("StarStore.loadKeys().get('openai')"),'legacy-key');assert.equal(f.run('StarStore.loadPending()[0].data'),'data:image/png;base64,AAEC');assert.equal(f.session.has('starhub.v1.keys'),false);assert.equal(f.session.has('starhub.v1.pendingFile'),false);});
test('account keys never overwrite guest session keys',async()=>{const f=fixture();await f.run('StarStore.initGuest()');f.run("StarStore.saveKeys(new Map([['openai','guest-key']]))");await f.run('StarStore.flush()');f.run("StarStore.setGuestMode(false);StarStore.saveKeys(new Map([['openai','account-key']]))");await f.run('StarStore.flush()');const reload=fixture({}, {data:f.data,session:f.session,server:f.server});await reload.run('StarStore.initGuest()');assert.equal(reload.run("StarStore.loadKeys().get('openai')"),'guest-key');});
test('existing signed-in key is not migrated into the guest session',async()=>{const f=fixture();f.session.set('starhub.v1.keys',JSON.stringify({openai:'account-key'}));f.run('StarStore.setGuestMode(false)');await f.run('StarStore.initGuest()');assert.equal(f.run('StarStore.loadKeys().size'),0);assert.ok(!JSON.stringify([...f.server.values()]).includes('account-key'));f.run('StarStore.setGuestMode(true)');assert.equal(f.session.has('starhub.v1.keys'),false);});

test('guest renewal retains the same operation ID after an unknown renewal response',async()=>{
 const f=fixture();f.run(`const originalGuestFetch=fetch;let renewalExpired=true,loseRenewal=true;globalThis.renewalCalls=[];
 fetch=async(url,options)=>{const q=JSON.parse(options.body);renewalCalls.push(q);if(q.action==='renew'){if(loseRenewal){loseRenewal=false;return {ok:false,status:503,json:async()=>({code:'GUEST_OPERATION_PENDING'})}}renewalExpired=false;return {ok:true,status:200,headers:{get:()=> 'dual'},json:async()=>({state:'COMMITTED',action:q.action,transactionId:q.tx,operationId:'11111111-1111-4111-8111-111111111111'})}}if(renewalExpired)return {ok:false,status:410,json:async()=>({code:'GUEST_SESSION_EXPIRED'})};return originalGuestFetch(url,options)};`);
 assert.equal(await f.run('StarStore.initGuest()'),false);assert.equal(await f.run('StarStore.initGuest()'),true);
 const renewals=JSON.parse(f.run("JSON.stringify(renewalCalls.filter(q=>q.action==='renew'))"));assert.equal(renewals.length,2);assert.equal(renewals[0].tx,renewals[1].tx);
});
test('guest upload retries preserve begin and chunk identities after a lost response',async()=>{
 const f=fixture();await f.run('StarStore.initGuest()');f.run(`const originalGuestFetch=fetch;let loseGuestChunk=true;globalThis.guestWrites=[];fetch=async(url,options)=>{const q=JSON.parse(options.body);guestWrites.push(q);if(q.action==='chunk'&&loseGuestChunk){loseGuestChunk=false;throw Error('Synthetic unknown chunk response')}return originalGuestFetch(url,options)};StarStore.saveKeys(new Map([['openai','test-only-retry-key']]));`);
 await f.run('StarStore.flush()');await f.run('StarStore.flush()');const writes=JSON.parse(f.run('JSON.stringify(guestWrites)'));
 const starts=writes.filter(q=>q.action==='begin'),chunks=writes.filter(q=>q.action==='chunk');assert.equal(starts.length,2);assert.equal(starts[0].tx,starts[1].tx);assert.equal(chunks[0].tx,chunks[1].tx);assert.equal(chunks[0].data,chunks[1].data);assert.equal(starts[0].version,starts[1].version);
});

test('guest confirmed flush rejects an unknown commit and retains the same staged request for recovery',async()=>{
 const f=fixture();await f.run('StarStore.initGuest()');f.run(`const originalConfirmedFetch=fetch;let loseConfirmedCommit=true;fetch=async(url,options)=>{const q=JSON.parse(options.body);if(q.action==='commit'&&loseConfirmedCommit){loseConfirmedCommit=false;throw Error('Synthetic unknown commit')}return originalConfirmedFetch(url,options)};StarStore.saveHistory([{id:'p',title:'pending',messages:[{role:'assistant',content:'',providerOperationId:'11111111-1111-4111-8111-111111111111'}]}],'p');`);
 await assert.rejects(f.run('StarStore.flushConfirmed()'));assert.equal(await f.run('StarStore.flushConfirmed()'),true);
 const reloaded=fixture({}, {data:f.data,session:f.session,server:f.server});await reloaded.run('StarStore.initGuest()');assert.equal(reloaded.run('StarStore.loadHistory().chats[0].messages[0].providerOperationId'),'11111111-1111-4111-8111-111111111111');assert.equal(reloaded.run('StarStore.loadHistory().chats[0].messages[0].content'),'');
});
test('a guest commit without current durability proof cannot confirm the provider placeholder',async()=>{
 const f=fixture();await f.run('StarStore.initGuest()');f.run(`const beforeMissingProof=fetch;let omitOnce=true;fetch=async(url,options)=>{const response=await beforeMissingProof(url,options),q=JSON.parse(options.body);if(q.action==='commit'&&omitOnce){omitOnce=false;response.headers={get:()=>null};}return response;};StarStore.saveHistory([{id:'a',title:'synthetic',messages:[{role:'assistant',content:'',providerOperationId:'11111111-1111-4111-8111-111111111111'}]}],'a');`);
 await assert.rejects(f.run('StarStore.flushConfirmed()'));assert.equal(await f.run('StarStore.flushConfirmed()'),true);
});
test('a terminal guest receipt for another transaction retains the original request',async()=>{
 const f=fixture();await f.run('StarStore.initGuest()');f.run(`const beforeWrongTransaction=fetch;let swapOnce=true;globalThis.confirmationTx=[];fetch=async(url,options)=>{const q=JSON.parse(options.body),response=await beforeWrongTransaction(url,options);if(q.action==='commit'){confirmationTx.push(q.tx);if(swapOnce){swapOnce=false;const json=response.json;response.json=async()=>({...await json(),transactionId:'f'.repeat(32)});}}return response;};StarStore.saveKeys(new Map([['openai','synthetic-test-only-key']]));`);
 await assert.rejects(f.run('StarStore.flushConfirmed()'));assert.equal(await f.run('StarStore.flushConfirmed()'),true);const calls=JSON.parse(f.run('JSON.stringify(confirmationTx)'));assert.equal(calls.length,2);assert.equal(calls[0],calls[1]);
});

test('hydrated empty pending is a semantic no-op without guest writes',async()=>{
 const f=fixture();await f.run('StarStore.initGuest()');f.run("globalThis.guestActions=[];const trackEmpty=fetch;fetch=async(u,o)=>{guestActions.push(JSON.parse(o.body).action);return trackEmpty(u,o)};StarStore.setGuestMode(false);StarStore.savePending(null);StarStore.savePending([])");
 await f.run('StarStore.whenSettled()');assert.equal(f.run('guestActions.length'),0);
});
test('clearing real or in-flight pending is preserved and ordered before account work',async()=>{
 const f=fixture();await f.run('StarStore.initGuest()');f.run("globalThis.guestActions=[];const trackPending=fetch;fetch=async(u,o)=>{guestActions.push(JSON.parse(o.body));return trackPending(u,o)};StarStore.savePending([{name:'synthetic.txt',type:'text/plain',data:'data:text/plain;base64,AQID'}])");
 const first=f.run('StarStore.flush()');f.run('StarStore.setGuestMode(false);StarStore.savePending(null)');await first;await f.run('StarStore.whenSettled()');
 const calls=JSON.parse(f.run('JSON.stringify(guestActions)'));assert.equal(calls.filter(q=>q.action==='commit').length,2);assert.notEqual(calls[0].tx,calls[3].tx);assert.equal(f.run('StarStore.loadPending().length'),0);
 const reload=fixture({}, {data:f.data,session:f.session,server:f.server});await reload.run('StarStore.initGuest()');assert.equal(reload.run('StarStore.loadPending().length'),0);
});
test('guest initialization is joined once and a real attachment selected during hydration is not lost',async()=>{
 const f=fixture();f.run("globalThis.getCount=0;const beforeBoot=fetch;fetch=async(u,o)=>{if(JSON.parse(o.body).action==='get'){getCount++;await new Promise(resolve=>globalThis.releaseBoot=resolve)}return beforeBoot(u,o)}");
 const init=f.run('StarStore.initGuest()'),again=f.run('StarStore.initGuest()');assert.equal(init,again);
 const settled=f.run('StarStore.whenSettled()');while(!f.run("typeof releaseBoot==='function'"))await new Promise(resolve=>setImmediate(resolve));f.run("StarStore.savePending([{name:'boot.txt',type:'text/plain',data:'data:text/plain;base64,AQID'}]);releaseBoot()");await Promise.all([init,settled]);
 assert.equal(f.run('getCount'),1);assert.equal(f.run('StarStore.loadPending()[0].name'),'boot.txt');
 const reload=fixture({}, {data:f.data,session:f.session,server:f.server});await reload.run('StarStore.initGuest()');assert.equal(reload.run('StarStore.loadPending()[0].name'),'boot.txt');
});
test('failed real pending retains its transaction for same-ID recovery rather than empty suppression',async()=>{
 const f=fixture();await f.run('StarStore.initGuest()');f.run("const beforePendingFailure=fetch;globalThis.pendingCalls=[];let failPending=true;fetch=async(u,o)=>{const q=JSON.parse(o.body);pendingCalls.push(q);if(q.action==='commit'&&failPending){failPending=false;throw Error('synthetic unknown commit')}return beforePendingFailure(u,o)};StarStore.savePending([{name:'keep.txt',type:'text/plain',data:'data:text/plain;base64,AQID'}])");
 await assert.rejects(f.run('StarStore.whenSettled()'));assert.equal(f.run('StarStore.loadPending()[0].name'),'keep.txt');await f.run('StarStore.whenSettled()');
 const commits=JSON.parse(f.run("JSON.stringify(pendingCalls.filter(q=>q.action==='commit'))"));assert.equal(commits.length,2);assert.equal(commits[0].tx,commits[1].tx);
});
test('hydration failure blocks settlement and provider confirmation without silently retrying',async()=>{
 const f=fixture();f.run("globalThis.guestGets=0;fetch=async()=>{guestGets++;throw Error('synthetic hydration failure')}");assert.equal(await f.run('StarStore.initGuest()'),false);await assert.rejects(f.run('StarStore.whenHydrated()'),/hydration/);await assert.rejects(f.run('StarStore.whenSettled()'),/hydration/);await assert.rejects(f.run('StarStore.flushConfirmed()'),/hydration/);assert.equal(f.run('guestGets'),1);
});
test('model selection preserves explicit choices by provider and direction without saving keys',()=>{
 const f=fixture();f.run("StarStore.saveSelection('openai',new Map([['openai','gpt-6-astra']]),new Map([['openai|general','gpt-6-astra'],['openai|image','gpt-image-2.6-sunburst']]))");
 assert.equal(f.run('StarStore.selection().directions["openai|general"]'),'gpt-6-astra');
 assert.equal(f.run('StarStore.selection().directions["openai|image"]'),'gpt-image-2.6-sunburst');
 assert(!f.data.get('starhub.v1.selection').includes('apiKey'));
});
test('a refused context save is visible and keeps the current conversation intact',()=>{
 const source=fs.readFileSync(path.join(root,'experience.js'),'utf8');const chat={id:'synthetic',draft:'',messages:[{role:'assistant',content:'preserved'}]};const errors=[];
 const ctx=vm.createContext({historySaveTimer:0,historySaveFailed:false,experienceReady:true,current:chat,chats:[chat],accountEmail:'',clearTimeout(){},$:()=>({value:'draft'}),t:x=>x,showError:value=>errors.push(value),StarStore:{saveHistory(){throw Error('Invalid model continuation. The conversation was not changed.')}}});
 vm.runInContext(source.slice(source.indexOf('function persistHistory(){'),source.indexOf('function scheduleHistorySave(){')),ctx);vm.runInContext('persistHistory()',ctx);
 assert.equal(chat.messages[0].content,'preserved');assert.match(errors[0],/conversation was not changed/);
});

test('malformed saved provider context is preserved and cannot be overwritten silently',async()=>{
 const f=fixture();await f.run('StarStore.initGuest()');
 const tab=[...f.server.values()][0], history={version:1,currentId:'a',chats:[{id:'a',title:'chat',messages:[{role:'assistant',provider:'moonshot',model:'kimi-k3',content:'answer',providerContext:{provider:'moonshot',model:'kimi-k3',token:'invalid='}}]}]};tab.values.history=structuredClone(history);
 const reload=fixture({}, {data:f.data,session:f.session,server:f.server});await reload.run('StarStore.initGuest()');
 assert.equal(reload.run('StarStore.loadHistory().chats[0].messages[0].providerContext.token'),'invalid=');
 assert.throws(()=>reload.run("const old=StarStore.loadHistory(); StarStore.saveHistory(old.chats,old.currentId)"),/not changed/);
 assert.deepEqual(tab.values.history,history);
});
