const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const web=path.join(__dirname,'../../main/webapp');

test('only supported HTTPS image addresses can be embedded',()=>{
 const source=fs.readFileSync(path.join(web,'markdown.js'),'utf8');
 const ctx=vm.createContext({URL});vm.runInContext(source.slice(source.indexOf('function markdownImageUrl('),source.indexOf('function renderMarkdown(')),ctx);
 const validate=value=>ctx.markdownImageUrl(value);
 const image='https://upload.wikimedia.org/wikipedia/commons/7/70/Wang_Zhi%27an_%28journalist%29.png';
 assert.equal(validate(image),image);
 for(const value of ['javascript:alert(1)','data:image/png;base64,AA==','http://example.org/a.png','https://user:password@example.org/a.png','https://localhost/a.png','https://127.0.0.1/a.png','https://[::1]/a.png','https://example.local/a.png','https://example.org:8443/a.png','/relative.png'])assert.equal(validate(value),null,value);
});

test('the composer contains the reasoning picker and no advanced parameter controls',()=>{
 const html=fs.readFileSync(path.join(web,'index.html'),'utf8');
 const composer=html.match(/<form id="chatForm"[\s\S]*?<\/form>/)[0];
 assert.match(composer,/<span id="reasoningControl"[^>]* hidden>/);
 assert.match(composer,/<select id="effort" aria-label="Reasoning effort"/);
 assert.equal((html.match(/id="effort"/g)||[]).length,1);
 assert.doesNotMatch(html,/id="modelSettings"|id="parameterDialog"|src="parameters\.js/);
});

test('provider and direction changes remember the model they actually select',()=>{
 const source=fs.readFileSync(path.join(web,'app.js'),'utf8');
 const catalog=JSON.parse(fs.readFileSync(path.join(web,'../resources/catalog.json'),'utf8'));
 function direction(value){const el={value,options:['general','image','video','voice','more'].map(value=>({value}))};Object.defineProperty(el,'selectedOptions',{get(){return this.options.filter(o=>o.value===el.value);}});return el;}
 const model={value:'',replaceChildren(){this.value='';},append(){}};
 const controls={provider:{value:'openai'},model,modelDirection:direction('image'),libraryDirection:direction('general'),directionNotice:{},apiKey:{},toggleKey:{setAttribute(){}}};
 const settings=new Map([['openai','gpt-6.2-astra']]),directionSettings=new Map([['openai|general','gpt-6.2-astra']]);
 const ctx=vm.createContext({catalog,providers:catalog.providers,settings,directionSettings,keys:new Map(),language:'en',t:x=>x,$:id=>controls[id],
  selectedModel:()=>catalog.models.find(m=>m.provider===controls.provider.value&&m.id===model.value),
  document:{createElement(){return {append(option){if(!model.value)model.value=option.value;}};}},Option:function(text,value){return {text,value};},configureModel(){},showError(){}});
 vm.runInContext(source.slice(source.indexOf('function primaryDirection('),source.indexOf('function configureModel()')),ctx);
 ctx.selectProvider();assert.equal(model.value,'gpt-image-2.6-flare');assert.equal(settings.get('openai'),'gpt-image-2.6-flare');
 controls.modelDirection.value='general';ctx.selectProvider(settings.get('openai'));assert.equal(controls.modelDirection.value,'image');assert.equal(model.value,'gpt-image-2.6-flare');
 controls.provider.value='anthropic';ctx.selectProvider();assert.equal(settings.get('anthropic'),model.value);assert.equal(controls.modelDirection.value,'general');
});

test('reasoning effort and task choices are saved and restored for the selected model',()=>{
 const source=fs.readFileSync(path.join(web,'app.js'),'utf8'),stored=new Map();
 function select(value=''){return {value,options:[],replaceChildren(...options){this.options=options;this.value=options[0]?.value||'';},append(...options){this.options.push(...options);}};}
 const controls={task:select('chat'),effort:select('high'),reasoningControl:{dataset:{}}};
 Object.defineProperty(controls.effort,'selectedOptions',{get(){return this.options.filter(option=>option.value===this.value);}});
 const model={provider:'openai',id:'test',vendorName:'OpenAI',family:'Test',cost:'paid',keyLabel:'Key',categories:['chat','speech'],reasoning:['low','high'],reasoningKnown:true};
 const ctx=vm.createContext({ready:true,busy:false,realtimeActive:false,selectedModel:()=>model,modelControls:new Map(),$:id=>controls[id],StarStore:{write:(key,value)=>{stored.set(key,value);return true;}},showError(){},t:x=>x,uiText:x=>x,
  catalog:{categories:{chat:'Chat',speech:'Speech'}},costNames:{paid:'Paid'},effortNames:{low:'Low',high:'High'},Option:function(text,value){return {text,value};},configureTask(){},saveSettings(){},document:{dispatchEvent(){}},CustomEvent:class{}});
 vm.runInContext(source.slice(source.indexOf('function modelControlKey('),source.indexOf('function selectedModel()')),ctx);ctx.saveModelControls();
 ctx.modelControls=new Map(Object.entries(stored.get('modelControls')));controls.task.value='';controls.effort.value='';
 vm.runInContext(source.slice(source.indexOf('function refreshReasoningControl()'),source.indexOf('function refreshSearchControls()')),ctx);ctx.configureModel();
 assert.equal(controls.task.value,'chat');assert.equal(controls.effort.value,'high');
 ctx.refreshReasoningControl();assert.equal(controls.reasoningControl.hidden,false);assert.equal(controls.reasoningControl.dataset.custom,'true');
 assert.equal(controls.effort.disabled,false);
 ctx.busy=true;ctx.refreshReasoningControl();assert.equal(controls.effort.disabled,true);
 ctx.busy=false;controls.task.value='speech';ctx.refreshReasoningControl();assert.equal(controls.reasoningControl.hidden,true);
 controls.task.value='chat';model.reasoning=[];ctx.refreshReasoningControl();assert.equal(controls.reasoningControl.hidden,true);
});
