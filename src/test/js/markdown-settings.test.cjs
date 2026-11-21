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

function parameterFixture(storage){
 const elements=new Map(),controls=new Map();
 function makeElement(tag='div'){
  let id='';const el={tag,children:[],dataset:{},classList:{add(){}},value:'',options:[],events:{},append(...children){for(const child of children){this.children.push(child);if(tag==='select'&&child&&'value'in child)this.options.push(child);if(child&&typeof child==='object')child.parent=this;}},replaceChildren(...children){this.children=[];this.append(...children);},addEventListener(type,handler){this.events[type]=handler;},closest(){return this.parent;}};
  Object.defineProperty(el,'id',{get:()=>id,set:value=>{id=value;elements.set(value,el);}});
  Object.defineProperty(el,'selectedOptions',{get:()=>el.options.filter(x=>x.value===el.value)});return el;
 }
 for(const id of ['task','effort','modelSettings','parameterFields','parameterError','parameterModel','parameterSources','parameterStatus','parameterDialog'])controls.set(id,makeElement());
 controls.get('task').value='chat';controls.get('effort').value='default';
 const specs=[{key:'temperature',type:'number',min:0,max:2},{key:'format',type:'enum',values:['a','b']},{key:'schema',type:'json'},{key:'reference',type:'image'}];
 const model={provider:'openai',id:'test-model',providerName:'OpenAI',name:'Test',settings:{chat:specs}};
 const ctx=vm.createContext({window:{sessionStorage:storage},accountEmail:'',selectedModel:()=>model,catalog:{categories:{chat:'Chat'}},busy:false,realtimeActive:false,
  $:id=>elements.get(id)||controls.get(id),t:(x,...args)=>x.replace(/\{(\d+)\}/g,(_,i)=>args[i]??''),uiText:x=>x,showError(){},
  element:(tag,text)=>{const el=makeElement(tag);el.textContent=text||'';return el;},Option:function(text,value){return {text,value:String(value)};}});
 const source=fs.readFileSync(path.join(web,'parameters.js'),'utf8');vm.runInContext(source.slice(0,source.indexOf('async function collectParameters()')),ctx);
 return {model,elements,run:code=>vm.runInContext(code,ctx)};
}

test('model parameters survive reload in their tab and reset on a model change',()=>{
 const data=new Map(),storage={getItem:key=>data.get(key)||null,setItem:(key,value)=>data.set(key,value),removeItem:key=>data.delete(key)};
 const first=parameterFixture(storage);first.run('configureParameters()');
 for(const [key,value] of Object.entries({temperature:'0.7',format:'b',schema:'{"type":"object"}',reference:'C:\\fakepath\\secret.png'}))first.elements.get('param-'+key).value=value;
 first.elements.get('param-schema').events.input();
 const saved=JSON.parse(data.get('starhub.v1.parameterSettings'));assert.deepEqual(saved.values,{temperature:'0.7',format:'b',schema:'{"type":"object"}'});
 const reload=parameterFixture(storage);reload.run('configureParameters()');assert.equal(reload.elements.get('param-temperature').value,'0.7');assert.equal(reload.elements.get('param-format').value,'b');assert.equal(reload.elements.get('param-schema').value,'{"type":"object"}');assert.equal(reload.elements.get('param-reference').value,'');
 reload.run("accountEmail='other@example.test';configureParameters()");assert.equal(reload.elements.get('param-temperature').value,'');
 reload.model.id='other-model';reload.run('configureParameters()');assert.equal(reload.elements.get('param-temperature').value,'');
 reload.model.id='test-model';reload.run('configureParameters()');assert.equal(reload.elements.get('param-temperature').value,'');
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
 const controls={task:select('chat'),effort:select('high'),modelSettings:{},selectedSummary:{},keyLabel:{},effortHelp:{}};
 const model={provider:'openai',id:'test',vendorName:'OpenAI',family:'Test',cost:'paid',keyLabel:'Key',categories:['chat','speech'],reasoning:['low','high'],reasoningKnown:true};
 const ctx=vm.createContext({ready:true,selectedModel:()=>model,modelControls:new Map(),$:id=>controls[id],StarStore:{write:(key,value)=>{stored.set(key,value);return true;}},showError(){},t:x=>x,uiText:x=>x,
  catalog:{categories:{chat:'Chat',speech:'Speech'}},costNames:{paid:'Paid'},effortNames:{low:'Low',high:'High'},Option:function(text,value){return {text,value};},configureTask(){},saveSettings(){},document:{dispatchEvent(){}},CustomEvent:class{}});
 vm.runInContext(source.slice(source.indexOf('function modelControlKey('),source.indexOf('function selectedModel()')),ctx);ctx.saveModelControls();
 ctx.modelControls=new Map(Object.entries(stored.get('modelControls')));controls.task.value='';controls.effort.value='';
 vm.runInContext(source.slice(source.indexOf('function configureModel()'),source.indexOf('function refreshSearchControls()')),ctx);ctx.configureModel();
 assert.equal(controls.task.value,'chat');assert.equal(controls.effort.value,'high');
});
