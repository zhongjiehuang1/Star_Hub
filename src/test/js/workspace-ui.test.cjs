const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const src=fs.readFileSync(path.join(__dirname,'../../main/webapp/workspace-ui.js'),'utf8');
function makeWorkspace(saved){
 const handlers={},observers=[],writes=[];
 function el(){return {value:'',textContent:'',hidden:false,disabled:false,inert:false,attrs:{},handlers:{},classList:{values:new Set(),contains(x){return this.values.has(x)},toggle(x,force=!this.values.has(x)){force?this.values.add(x):this.values.delete(x)},remove(x){this.values.delete(x)}},addEventListener(n,fn){(this.handlers[n]??=[]).push(fn)},setAttribute(n,v){this.attrs[n]=v},contains(x){return x===this},focus(){this.focused=true},fire(n,e={}){for(const fn of this.handlers[n]||[])fn(e)}}}
 const ids=Object.fromEntries(['historySearch','history','historyNoMatches','menuToggle','sidebar','sidebarClose','quickModel','browseModels','historySearchLabel','historySearchHelp','quickModelName','model','workspaceLayout','workspaceActions'].map(id=>[id,el()]));
 const rows=['First conversation','另一个对话'].map(title=>{const e=el();e.title={textContent:title};e.querySelector=()=>e.title;return e});
 ids.history.querySelectorAll=()=>rows;ids.history.querySelector=()=>null;
 const wide={matches:true,addEventListener(n,fn){this.changed=fn}};
 const doc={documentElement:{lang:'en'},body:{classList:el().classList,dataset:{}},getElementById:id=>ids[id],querySelector(){return null;},addEventListener(n,fn){(handlers[n]??=[]).push(fn)},fire(n,e={}){for(const fn of handlers[n]||[])fn(e)}};
 const actionsSummary=el();ids.workspaceActions.querySelector=()=>actionsSummary;
 ids.browseModels.click=()=>{ids.browseModels.clicks=(ids.browseModels.clicks||0)+1};
 class MutationObserver{constructor(fn){this.fn=fn}observe(target,options){observers.push({target,options,fn:this.fn})}}
 const StarStore={preference:(key,fallback)=>saved??fallback,write(key,value){writes.push([key,value]);return true;}};
 vm.runInNewContext(src,{document:doc,MutationObserver,matchMedia:()=>wide,StarStore});doc.fire('DOMContentLoaded');
 return {ids,rows,wide,doc,observers,writes,actionsSummary};
}
test('search filters loaded titles without changing chats and reacts to history render',()=>{
 const {ids,rows,observers}=makeWorkspace();ids.historySearch.value=' FIRST ';ids.historySearch.fire('input');assert.equal(rows[0].hidden,false);assert.equal(rows[1].hidden,true);assert.equal(ids.historyNoMatches.hidden,true);
 ids.historySearch.value='absent';ids.historySearch.fire('input');assert.equal(ids.historyNoMatches.hidden,false);
 ids.historySearch.value='对话';observers.find(x=>x.target===ids.history).fn();assert.equal(rows[0].hidden,true);assert.equal(rows[1].hidden,false);assert.equal(ids.historyNoMatches.hidden,true);
 ids.historySearch.value='';ids.historySearch.fire('input');assert(rows.every(x=>!x.hidden));
});
test('model shortcut delegates to existing library and respects disabled state',()=>{
 const {ids,doc,observers}=makeWorkspace();ids.model.value='model';ids.model.selectedOptions=[{textContent:'A model'}];doc.fire('starhub:modelchanged');assert.equal(ids.quickModelName.textContent,'A model');ids.quickModel.fire('click');assert.equal(ids.browseModels.clicks,1);
 ids.browseModels.disabled=true;observers.find(x=>x.target===ids.browseModels).fn();assert.equal(ids.quickModel.disabled,true);ids.quickModel.fire('click');assert.equal(ids.browseModels.clicks,1);
});
test('floating sidebar starts hidden, dismisses on Escape and restores focus',()=>{
 const {ids,doc}=makeWorkspace();
 assert.equal(doc.body.dataset.layout,'floating');
 assert.equal(ids.sidebar.inert,true);
 ids.menuToggle.fire('click');
 assert.equal(ids.sidebar.inert,false);
 assert.equal(ids.menuToggle.attrs['aria-expanded'],'true');
 assert.equal(ids.sidebarClose.focused,true);
 doc.fire('keydown',{key:'Escape'});
 assert.equal(ids.sidebar.inert,true);
 assert.equal(ids.menuToggle.attrs['aria-expanded'],'false');
 assert.equal(ids.menuToggle.focused,true);
 doc.fire('starhub:sidebar-open');
 assert.equal(ids.sidebar.inert,false);
 doc.fire('pointerdown',{target:ids.historySearch});
 assert.equal(ids.sidebar.inert,true);
});
test('layout choice persists and a docked sidebar becomes a drawer on mobile',()=>{
 const {ids,wide,doc,actionsSummary,writes}=makeWorkspace('docked');
 assert.equal(ids.sidebar.inert,false);
 ids.menuToggle.fire('click');
 assert.equal(ids.sidebar.inert,true);
 ids.menuToggle.fire('click');
 wide.matches=false;
 wide.changed();
 assert.equal(ids.sidebar.inert,true);
 ids.menuToggle.fire('click');
 assert.equal(ids.sidebar.inert,false);
 ids.history.fire('click',{target:{closest:()=>true}});
 assert.equal(ids.sidebar.inert,true);
 ids.workspaceLayout.value='floating';
 ids.workspaceLayout.fire('change');
 assert.equal(doc.body.dataset.layout,'floating');
 assert.deepEqual(writes,[['workspaceLayout','floating']]);
 ids.workspaceActions.open=true;
 doc.fire('keydown',{key:'Escape'});
 assert.equal(ids.workspaceActions.open,false);
 assert.equal(actionsSummary.focused,true);
});
test('new labels switch language without touching conversation data',()=>{
 const {ids,doc}=makeWorkspace();doc.documentElement.lang='zh-CN';doc.fire('starhub:languagechanged');assert.equal(ids.historySearchLabel.textContent,'你的对话');assert.equal(ids.quickModel.attrs['aria-label'],'选择模型');assert.equal(ids.quickModelName.textContent,'选择模型');
});
