'use strict';
const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const vm=require('node:vm');const path=require('node:path');
function harness(){
 const source=fs.readFileSync(path.join(__dirname,'../../main/webapp/app.js'),'utf8');const tracking=source.slice(source.indexOf("let memoryDirectInput="),source.indexOf("$('chatForm').addEventListener('submit'"));const activate=source.slice(source.indexOf('function activateChat(chat)'),source.indexOf('async function deleteChat(chat)'));
 const handlers={},prompt={value:'',addEventListener(name,fn){handlers[name]=fn;},focus(){}};const ctx={$:()=>prompt,accountEmail:'',accountIdentityRevision:0,chatActivation:0,current:null,realtimeActive:false,render(){},showError(){},invalidateGeneratedPreviews(){},releaseGeneratedPreview(){},scheduleLatestGeneratedPreview(){},updateConnection(){}};vm.createContext(ctx);vm.runInContext(tracking+'\n'+activate,ctx);return {ctx,prompt,handlers,read:()=>vm.runInContext('({direct:memoryDirectInput,tracked:memoryTrackedValue})',ctx)};
}
function type(x,value){x.handlers.beforeinput();x.prompt.value=value;x.handlers.input({isTrusted:true,inputType:'insertText'});}
test('switching to empty chat resets tracking, while restored drafts and cards remain unknown',()=>{
 const x=harness();type(x,'draft A');x.ctx.activateChat({draft:''});type(x,'我喜欢徒步');assert.equal(x.read().direct,true);assert.equal(x.read().tracked,'我喜欢徒步');
 x.ctx.activateChat({draft:'restored text'});assert.equal(x.read().direct,false);type(x,'restored text plus');assert.equal(x.read().direct,false);
 x.prompt.value='template';x.ctx.resetMemoryInputTracking();assert.equal(x.read().direct,false);x.prompt.value='';x.ctx.resetMemoryInputTracking();type(x,'我喜欢中文');assert.equal(x.read().direct,true);
});
test('paste remains unknown until the input is cleared',()=>{const x=harness();x.handlers.paste();x.handlers.beforeinput();x.prompt.value='pasted';x.handlers.input({isTrusted:true,inputType:'insertFromPaste'});assert.equal(x.read().direct,false);x.prompt.value='';x.ctx.resetMemoryInputTracking();type(x,'我叫小明');assert.equal(x.read().direct,true);});
