const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
test('switching languages refreshes the workspace without the removed batch UI',()=>{
 const source=fs.readFileSync(path.join(__dirname,'../../main/webapp/experience.js'),'utf8');
 const start=source.indexOf("document.addEventListener('starhub:languagechanged',()=>{"),end=source.indexOf('\nif(ready)startExperience();',start);
 assert(start>=0&&end>start);const elements=new Map(),handlers={},calls=[];
 function element(id){if(!elements.has(id))elements.set(id,{value:'',options:[],open:false,querySelectorAll:()=>[]});return elements.get(id);}
 const rawCatalog={providers:[],models:[],categories:{}},context=vm.createContext({ready:true,rawCatalog,catalog:rawCatalog,providers:[],language:'en',historySignature:'',costNames:{},effortNames:{},
  document:{addEventListener:(name,fn)=>{handlers[name]=fn;}},$:element,localizedCatalog:x=>x,selectedModel:()=>null,t:x=>x,uiText:x=>x,
  setupLibrary:()=>calls.push('library'),updateConnection:()=>calls.push('connection'),refreshCurrentApi:()=>{},refreshReasoningControl:()=>{},translateInterface:()=>calls.push('translate'),refreshConnectionSummary:()=>{},render:()=>calls.push('render')});
 vm.runInContext(source.slice(start,end),context);handlers['starhub:languagechanged']();context.language='zh';handlers['starhub:languagechanged']();
 assert.deepEqual(calls,['library','connection','translate','render','library','connection','translate','render']);
});
