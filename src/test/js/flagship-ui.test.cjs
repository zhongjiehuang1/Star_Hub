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
test('homepage hides endpoint and source UI but keeps model details and account key controls',()=>{
 const html=fs.readFileSync(path.join(web,'index.html'),'utf8');
 for(const id of ['currentApi','viewCallSource','sourceDialog'])assert(!html.includes('id="'+id+'"'));
 assert(!html.includes('src="source-viewer.js'));for(const id of ['accountSaveKey','apiKey','modelDetails','connectProvider'])assert(html.includes('id="'+id+'"'));
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
test('library distinguishes unavailable directions/providers from user filter mismatches',()=>{
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));
 const controls={libraryDirection:{value:'video'},filterVendor:{value:''},filterProvider:{value:''},filterCost:{value:''}};
 const ctx=vm.createContext({catalog,$:id=>controls[id],t:x=>x});
 vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function selectProvider(')),ctx);
 vm.runInContext(source.slice(source.indexOf('function libraryEmptyMessage(){'),source.indexOf('function renderLibrary(){')),ctx);
 assert.match(vm.runInContext('libraryEmptyMessage()',ctx),/视频生成暂未接入/);
 controls.libraryDirection.value='image';controls.filterVendor.value='anthropic';assert.match(vm.runInContext('libraryEmptyMessage()',ctx),/暂无已接入模型/);
 controls.filterVendor.value='';assert.match(vm.runInContext('libraryEmptyMessage()',ctx),/减少筛选条件/);
});
test('advanced filters are closed initially and service wizard retains its simple label',()=>{
 const html=fs.readFileSync(path.join(web,'index.html'),'utf8');const details=html.match(/<details id="libraryMoreFilters"([^>]*)>([\s\S]*?)<\/details>/);
 assert(details);assert(!details[1].includes('open'));
 for(const id of ['filterCategory','filterSpecialty','filterInput','filterFamily','filterProvider','filterReasoning','filterCost','filterExecution'])assert(details[2].includes('id="'+id+'"'));
 for(const id of ['libraryDirection','librarySearch','filterVendor'])assert(!details[2].includes('id="'+id+'"'));
 assert(fs.readFileSync(path.join(web,'experience.js'),'utf8').includes("t('连接服务')"));
 assert(source.includes('个创作方向'));
});

test('defaults are explicit, order independent, and never override a saved usable choice',()=>{
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));
 const ctx=vm.createContext({catalog,t:x=>x});vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function selectProvider(')),ctx);
 assert.equal(vm.runInContext("preferredModelId('openai','general',directionModels('general').filter(m=>m.provider==='openai'))",ctx),'gpt-6.1-sol');
 assert.equal(vm.runInContext("preferredModelId('openai','image',directionModels('image').filter(m=>m.provider==='openai'))",ctx),'gpt-image-2.6-flare');
 assert.equal(vm.runInContext("preferredModelId('openai','voice',directionModels('voice').filter(m=>m.provider==='openai'))",ctx),'gpt-realtime-2.1-mini');
 assert.equal(vm.runInContext("preferredModelId('openai','general',directionModels('general').filter(m=>m.provider==='openai'),'gpt-6-astra')",ctx),'gpt-6-astra');
 assert.equal(vm.runInContext("preferredModelId('openai','voice',directionModels('voice').filter(m=>m.provider==='openai'),'gpt-transcribe')",ctx),'gpt-transcribe');
 assert.equal(vm.runInContext("preferredModelId('openai','general',directionModels('general').filter(m=>m.provider==='openai').reverse())",ctx),'gpt-6.1-sol');
 assert.equal(vm.runInContext("preferredModelId('openai','general',directionModels('general').filter(m=>m.provider==='openai'),'gpt-6.1-sol:batch')",ctx),'gpt-6.1-sol');
});
test('old saved batch model falls back to normal chat when the workspace starts',()=>{
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));
 const direction={value:'general',options:['general','image','video','voice'].map(value=>({value}))};Object.defineProperty(direction,'selectedOptions',{get(){return this.options.filter(o=>o.value===this.value)}});
 const library={...direction,options:direction.options.map(o=>({...o}))};Object.defineProperty(library,'selectedOptions',{get(){return this.options.filter(o=>o.value===this.value)}});
 const controls={provider:{value:'openai'},modelDirection:direction,libraryDirection:library,model:{value:'',replaceChildren(){this.value='';},append(){}},directionNotice:{},apiKey:{},toggleKey:{setAttribute(){}}};
 const settings=new Map([['openai','gpt-6.1-sol:batch']]),directionSettings=new Map([['openai|more','gpt-6.1-sol:batch']]);let configured;
 const ctx=vm.createContext({catalog,providers:catalog.providers,settings,directionSettings,keys:new Map(),language:'en',t:x=>x,showError(){},configureModel(){configured=controls.model.value;},selectedModel(){return catalog.models.find(m=>m.provider==='openai'&&m.id===controls.model.value);},$:id=>controls[id],document:{createElement(){return {append(){}}}},Option:function(label,value){this.value=value;}});
 vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function configureModel()')),ctx);
 vm.runInContext("selectProvider(settings.get('openai'))",ctx);
 assert.equal(direction.value,'general');assert.equal(configured,'gpt-6.1-sol');assert.equal(settings.get('openai'),'gpt-6.1-sol');
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
 assert(source.includes("key!=='embeddings'&&used.has(key)"));
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
test('capability filter only offers populated non-embedding tasks for the selected direction',()=>{
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));
 const filter={value:'',options:[],replaceChildren(...options){this.options=options;this.value=options[0]?.value||''}};
 const controls={filterCategory:filter,filterVendor:{value:''},filterProvider:{value:''},libraryDirection:{value:'voice'}};
 const ctx=vm.createContext({catalog,t:x=>x,$:id=>controls[id],Option:function(label,value){return {label,value}}});
 vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function selectProvider(')),ctx);
 vm.runInContext(source.slice(source.indexOf('function updateCapabilityFilter('),source.indexOf('function updateFamilies(')),ctx);
 vm.runInContext('updateCapabilityFilter()',ctx);assert(filter.options.some(o=>o.value==='realtime'));assert(!filter.options.some(o=>['embeddings','image','chat'].includes(o.value)));
});
test('encrypted Kimi context is bounded and only follows the same provider and model',()=>{
 const ctx=vm.createContext({});vm.runInContext(source.slice(source.indexOf('function validatedProviderContext('),source.indexOf('function primaryDirection(')),ctx);
 const run=js=>JSON.parse(vm.runInContext(`JSON.stringify(${js})`,ctx));
 const message="{role:'assistant',content:'answer',provider:'moonshot',model:'kimi-k3',providerContext:{provider:'moonshot',model:'kimi-k3',token:'sealed_context-123'}}";
 assert.equal(run(`outgoingHistoryMessage(${message},'moonshot','kimi-k3')`).providerContext.token,'sealed_context-123');
 assert.equal(run(`outgoingHistoryMessage(${message},'moonshot','kimi-k3.1-pro')`).providerContext,undefined);
 assert.equal(run(`outgoingHistoryMessage(${message},'openai','gpt-6.1-sol')`).providerContext,undefined);
 assert.equal(run(`outgoingHistoryMessage({...${message},role:'user'},'moonshot','kimi-k3')`).providerContext,undefined);
 assert.equal(run(`outgoingHistoryMessage({...${message},providerContext:{provider:'moonshot',model:'kimi-k3',token:'x'.repeat(140001)}},'moonshot','kimi-k3')`).providerContext,undefined);
 assert.equal(run(`outgoingHistoryMessage({...${message},providerContext:{provider:'moonshot',model:'kimi-k3',token:'wrong='}},'moonshot','kimi-k3')`).providerContext,undefined);
});
