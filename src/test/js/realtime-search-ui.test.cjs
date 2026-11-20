const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const app=fs.readFileSync(path.join(__dirname,'../../main/webapp/app.js'),'utf8');
function controlsFor(model,task,language='en'){
 const controls={task:{value:task},searchControls:{},webSearch:{checked:true},searchLabel:{},searchHelp:{}};
 const context=vm.createContext({selectedModel:()=>model,language,realtimeActive:false,$:id=>controls[id]});
 vm.runInContext(app.slice(app.indexOf('function refreshSearchControls()'),app.indexOf('function configureTask()')),context);
 return {controls,context};
}
test('realtime conversation exposes opt-in web search before connecting',()=>{
 const {controls,context}=controlsFor({provider:'openai',id:'gpt-realtime-2.1',outputs:['audio']},'realtime');
 vm.runInContext('refreshSearchControls()',context);assert.equal(controls.searchControls.hidden,false);assert.equal(controls.webSearch.disabled,false);assert.match(controls.searchHelp.textContent,/charges/i);
 context.realtimeActive=true;vm.runInContext('refreshSearchControls()',context);assert.equal(controls.webSearch.disabled,true);
});
test('GPT Live exposes its search choice even while its text composer is hidden',()=>{
 const {controls,context}=controlsFor({provider:'openai',id:'gpt-live-1',outputs:['audio']},'realtime');
 vm.runInContext('refreshSearchControls()',context);assert.equal(controls.searchControls.hidden,false);assert.equal(controls.webSearch.disabled,false);assert.match(controls.searchHelp.textContent,/charges/i);
 const html=fs.readFileSync(path.join(__dirname,'../../main/webapp/index.html'),'utf8');const search=html.indexOf('id="searchControls"'),form=html.indexOf('<form id="chatForm"');assert(search>=0&&search<form);assert.match(html,/id="webSearch" type="checkbox" checked/);
 context.realtimeActive=true;vm.runInContext('refreshSearchControls()',context);assert.equal(controls.webSearch.disabled,true);
});
test('transcription and unsupported realtime providers do not expose the live search control',()=>{
 for(const model of [{provider:'openai',id:'gpt-live-transcribe',outputs:['text']},{provider:'other',id:'live',outputs:['audio']}]){
  const {controls,context}=controlsFor(model,'realtime');vm.runInContext('refreshSearchControls()',context);assert.equal(controls.searchControls.hidden,true);assert.equal(controls.webSearch.disabled,true);
 }
});
test('normal chat search remains available and localized',()=>{
 const {controls,context}=controlsFor({provider:'openai',id:'gpt-6.1-sol',outputs:['text']},'chat','zh');vm.runInContext('refreshSearchControls()',context);
 assert.equal(controls.searchControls.hidden,false);assert.equal(controls.webSearch.disabled,false);assert.equal(controls.searchLabel.textContent,'联网搜索');
});
