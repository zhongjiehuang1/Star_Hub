const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const web=path.join(__dirname,'../../main/webapp'),source=fs.readFileSync(path.join(web,'app.js'),'utf8');
test('current directions keep supported tools and reject old selections',()=>{
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));
 const ctx=vm.createContext({catalog});vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function selectProvider(')),ctx);
 assert(vm.runInContext("directionModels('general').length >= 8",ctx));
 assert(vm.runInContext("directionModels('image').some(m=>m.id==='gpt-image-2.6-flare')",ctx));
 assert(vm.runInContext("directionModels('voice').some(m=>m.id==='gpt-realtime-2.1-mini')",ctx));
 assert.equal(vm.runInContext("directionModels('video').length",ctx),0);
 assert(!catalog.models.some(m=>m.id==='gpt-4.1-mini'));assert.equal(vm.runInContext("directionModels('more').length",ctx),0);
});
test('homepage keeps advanced connection and pricing controls off the sidebar',()=>{
 const html=fs.readFileSync(path.join(web,'index.html'),'utf8');
 for(const id of ['currentApi','viewCallSource','sourceDialog'])assert(!html.includes('id="'+id+'"'));
 assert(!html.includes('src="source-viewer.js'));for(const id of ['accountSaveKey','apiKey','detailDialog','connectProvider'])assert(html.includes('id="'+id+'"'));
 const sidebar=html.match(/<aside class="sidebar"[\s\S]*?<\/aside>/)[0];
 assert.match(sidebar,/<select id="provider" hidden/);
 assert.match(sidebar,/<input id="apiKey"[^>]* hidden>/);
 assert.doesNotMatch(sidebar,/Official provider|API Key|Request history|Model details|Flagship picks/i);
 assert.match(html.match(/<dialog id="setupDialog"[\s\S]*?<\/dialog>/)[0],/id="providerRequestHistory"/);
 assert.match(html,/<details id="passwordSettings" class="account-disclosure"><summary>Change password<\/summary><form id="passwordForm">/);
 assert.match(html,/<details id="keyVault" class="account-disclosure"><summary>API key vault<\/summary>/);
 const api=vm.createContext({$:()=>null});const links=fs.readFileSync(path.join(web,'api-links.js'),'utf8');vm.runInContext(links.slice(links.indexOf('function refreshCurrentApi(){'),links.indexOf('function showApiDirectory(){')),api);assert.doesNotThrow(()=>vm.runInContext('refreshCurrentApi()',api));
});
test('pointer feedback has one pending frame and cancels on hidden or reduced motion',()=>{
 const handlers={},media=[],calls=[];let hidden=false;
 const target={style:{setProperty:(...x)=>calls.push(x),removeProperty:()=>{} }};
 const ctx=vm.createContext({matchMedia:()=>{const m={matches:media.length===1,addEventListener:(name,fn)=>m.change=fn};media.push(m);return m;},document:{get hidden(){return hidden;},addEventListener:(name,fn)=>handlers[name]=fn},requestAnimationFrame:fn=>{calls.push('frame');return 1},cancelAnimationFrame:()=>calls.push('cancel')});
 vm.runInContext(fs.readFileSync(path.join(web,'gentle-motion.js'),'utf8'),ctx);
 const event={target:{closest:()=>target},clientX:10,clientY:20};handlers.pointermove(event);handlers.pointermove(event);assert.equal(calls.filter(x=>x==='frame').length,1);
 hidden=true;handlers.visibilitychange();assert(calls.includes('cancel'));hidden=false;media[0].matches=true;handlers.pointermove(event);assert.equal(calls.filter(x=>x==='frame').length,1);
});
test('library explains unavailable directions',()=>{
 const ctx=vm.createContext({t:x=>x});
 vm.runInContext(source.slice(source.indexOf('function libraryEmptyMessage('),source.indexOf('function renderLibrary(){')),ctx);
 assert.match(vm.runInContext("libraryEmptyMessage('video')",ctx),/视频生成暂未接入/);
 assert.match(vm.runInContext("libraryEmptyMessage('image')",ctx),/暂无已接入模型/);
});
test('library only filters by creation direction and the service wizard retains its simple label',()=>{
 const html=fs.readFileSync(path.join(web,'index.html'),'utf8');
 const filters=html.match(/<aside class="library-filters">([\s\S]*?)<\/aside>/)[1];
 assert.deepEqual([...filters.matchAll(/<select id="([^"]+)"/g)].map(match=>match[1]),['libraryDirection']);
 assert.doesNotMatch(filters,/catalog-notice|<details\b|Model manufacturer|More filters|resetFilters/i);
 assert(fs.readFileSync(path.join(web,'experience.js'),'utf8').includes("t('连接服务')"));
 assert(source.includes('目前仅支持 OpenAI。'));
});

test('defaults are explicit, order independent, and never override a saved usable choice',()=>{
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));
 const ctx=vm.createContext({catalog,t:x=>x});vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function selectProvider(')),ctx);
 assert.equal(vm.runInContext("preferredModelId('openai','general',directionModels('general').filter(m=>m.provider==='openai'))",ctx),'gpt-6.2-sol');
 assert.equal(vm.runInContext("preferredModelId('openai','image',directionModels('image').filter(m=>m.provider==='openai'))",ctx),'gpt-image-2.6-flare');
 assert.equal(vm.runInContext("preferredModelId('openai','voice',directionModels('voice').filter(m=>m.provider==='openai'))",ctx),'gpt-realtime-2.1-mini');
 assert.equal(vm.runInContext("preferredModelId('openai','general',directionModels('general').filter(m=>m.provider==='openai'),'gpt-6.2-astra')",ctx),'gpt-6.2-astra');
 assert.equal(vm.runInContext("preferredModelId('openai','voice',directionModels('voice').filter(m=>m.provider==='openai'),'gpt-transcribe')",ctx),'gpt-transcribe');
 assert.equal(vm.runInContext("preferredModelId('openai','general',directionModels('general').filter(m=>m.provider==='openai').reverse())",ctx),'gpt-6.2-sol');
 assert.equal(vm.runInContext("preferredModelId('openai','general',directionModels('general').filter(m=>m.provider==='openai'),'gpt-6.2-sol:batch')",ctx),'gpt-6.2-sol');
});
test('old saved batch model falls back to normal chat when the workspace starts',()=>{
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));
 const direction={value:'general',options:['general','image','video','voice'].map(value=>({value}))};Object.defineProperty(direction,'selectedOptions',{get(){return this.options.filter(o=>o.value===this.value)}});
 const library={...direction,options:direction.options.map(o=>({...o}))};Object.defineProperty(library,'selectedOptions',{get(){return this.options.filter(o=>o.value===this.value)}});
 const controls={provider:{value:'openai'},modelDirection:direction,libraryDirection:library,model:{value:'',replaceChildren(){this.value='';},append(){}},directionNotice:{},apiKey:{},toggleKey:{setAttribute(){}}};
 const settings=new Map([['openai','gpt-6.2-sol:batch']]),directionSettings=new Map([['openai|more','gpt-6.2-sol:batch']]);let configured;
 const ctx=vm.createContext({catalog,providers:catalog.providers,settings,directionSettings,keys:new Map(),language:'en',t:x=>x,showError(){},configureModel(){configured=controls.model.value;},selectedModel(){return catalog.models.find(m=>m.provider==='openai'&&m.id===controls.model.value);},$:id=>controls[id],document:{createElement(){return {append(){}}}},Option:function(label,value){this.value=value;}});
 vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function configureModel()')),ctx);
 vm.runInContext("selectProvider(settings.get('openai'))",ctx);
 assert.equal(direction.value,'general');assert.equal(configured,'gpt-6.2-sol');assert.equal(settings.get('openai'),'gpt-6.2-sol');
});
test('batch has no user-facing creation or recovery controls',()=>{
 const html=fs.readFileSync(path.join(web,'index.html'),'utf8'),advanced=fs.readFileSync(path.join(web,'advanced.js'),'utf8');
 assert.doesNotMatch(html,/Batch jobs|Asynchronous batch|id="batch(?:Panel|Inputs|JobId|Jobs|Recovery)"|id="(?:submitBatch|loadBatches|recoverBatch|moreBatches)"/i);
 assert.doesNotMatch(advanced,/batchCall|batchJobs|submitBatch|loadBatches|recoverBatch|batchPollCursor/);
 assert.match(advanced,/function downloadJson\(/);
});
test('visible filters exclude batch and embedding models while preserving other categories',()=>{
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));const ctx=vm.createContext({catalog,t:x=>x});
 vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function selectProvider(')),ctx);
 assert(catalog.models.some(m=>m.categories.includes('embeddings')),'legacy backend remains supported');
 for(const direction of ['general','image','video','voice','more'])assert(!vm.runInContext(`directionModels('${direction}').some(m=>m.categories.includes('embeddings'))`,ctx));
 assert.equal(vm.runInContext("directionModels('more').length",ctx),0);for(const direction of ['general','image','video','voice'])assert(!vm.runInContext(`directionModels('${direction}').some(m=>m.batch)`,ctx));
 const html=fs.readFileSync(path.join(web,'index.html'),'utf8');assert(!/Batch &amp; vector tools/i.test(html));
});
test('unsupported directions are hidden and enabled only when an integrated model exists',()=>{
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));
 function control(){const c={value:'general',options:['general','image','video','voice','more'].map(value=>({value}))};Object.defineProperty(c,'selectedOptions',{get(){return this.options.filter(x=>x.value===this.value)}});return c;}
 const controls={modelDirection:control(),libraryDirection:control(),provider:{value:''}};const ctx=vm.createContext({catalog,t:x=>x,$:id=>controls[id]});
 vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function selectProvider(')),ctx);
 vm.runInContext('updateAvailableDirections()',ctx);assert.equal(controls.libraryDirection.options.find(x=>x.value==='video').hidden,true);assert.equal(controls.libraryDirection.options.find(x=>x.value==='more').hidden,true);
 catalog.models.push({provider:'test',id:'video',categories:['video'],batch:false});vm.runInContext('updateAvailableDirections()',ctx);assert.equal(controls.libraryDirection.options.find(x=>x.value==='video').disabled,false);
});
test('provider changes hide empty directions and recover from a stale selection',()=>{
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));
 function control(value){const c={value,options:['general','image','video','voice','more'].map(value=>({value}))};Object.defineProperty(c,'selectedOptions',{get(){return this.options.filter(x=>x.value===this.value)}});return c;}
 const controls={modelDirection:control('voice'),libraryDirection:control('general'),provider:{value:'anthropic'}};const ctx=vm.createContext({catalog,t:x=>x,$:id=>controls[id]});vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function selectProvider(')),ctx);
 vm.runInContext('updateAvailableDirections()',ctx);assert.equal(controls.modelDirection.options.find(x=>x.value==='voice').hidden,true);assert.equal(controls.modelDirection.value,'general');assert.equal(controls.libraryDirection.options.find(x=>x.value==='voice').hidden,false);
 controls.provider.value='openai';vm.runInContext('updateAvailableDirections()',ctx);assert.equal(controls.modelDirection.options.find(x=>x.value==='voice').hidden,false);
});
test('encrypted Kimi context is bounded and only follows the same provider and model',()=>{
 const ctx=vm.createContext({});vm.runInContext(source.slice(source.indexOf('function validatedProviderContext('),source.indexOf('function primaryDirection(')),ctx);
 const run=js=>JSON.parse(vm.runInContext(`JSON.stringify(${js})`,ctx));
 const message="{role:'assistant',content:'answer',provider:'moonshot',model:'kimi-k3',providerContext:{provider:'moonshot',model:'kimi-k3',token:'sealed_context-123'}}";
 assert.equal(run(`outgoingHistoryMessage(${message},'moonshot','kimi-k3')`).providerContext.token,'sealed_context-123');
 assert.equal(run(`outgoingHistoryMessage(${message},'moonshot','kimi-k3.1-pro')`).providerContext,undefined);
 assert.equal(run(`outgoingHistoryMessage(${message},'openai','gpt-6.2-sol')`).providerContext,undefined);
 assert.equal(run(`outgoingHistoryMessage({...${message},role:'user'},'moonshot','kimi-k3')`).providerContext,undefined);
 assert.equal(run(`outgoingHistoryMessage({...${message},providerContext:{provider:'moonshot',model:'kimi-k3',token:'x'.repeat(140001)}},'moonshot','kimi-k3')`).providerContext,undefined);
 assert.equal(run(`outgoingHistoryMessage({...${message},providerContext:{provider:'moonshot',model:'kimi-k3',token:'wrong='}},'moonshot','kimi-k3')`).providerContext,undefined);
});
