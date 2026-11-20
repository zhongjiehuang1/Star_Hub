'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.join(__dirname,'../../main/webapp');
const source=fs.readFileSync(path.join(root,'advanced.js'),'utf8');
function deviceHarness(enumerateDevices){
 const listeners={},select={value:'',items:[],replaceChildren(...items){this.items=items;this.value='';}},details={open:false,addEventListener(name,fn){listeners[name]=fn;}};
 const context=vm.createContext({navigator:{mediaDevices:{enumerateDevices,addEventListener(name,fn){listeners[name]=fn;}}},document:{createElement(){return {value:'',textContent:''}},addEventListener(name,fn){listeners[name]=fn;}},$:id=>id==='realtimeMicrophone'?select:id==='realtimeDetails'?details:null,t:(text,...args)=>text.replace(/\{(\d+)\}/g,(_,i)=>args[i]),realtimeNotice:message=>listeners.notice=message});
 vm.runInContext(source.slice(source.indexOf('let realtimeMicDevicesRevision=0;'),source.indexOf('function realtimeUi()')),context);
 return {context,select,listeners};
}
test('device list preserves selection and updates device labels after permission without starting a microphone',async()=>{
 let devices=[{kind:'audioinput',deviceId:'m1',label:''},{kind:'audiooutput',deviceId:'out',label:'Speaker'}];
 const {context,select}=deviceHarness(async()=>devices);await vm.runInContext('refreshRealtimeMicrophones()',context);
 assert.deepEqual(select.items.map(x=>[x.value,x.textContent]),[['','系统默认麦克风'],['m1','麦克风 1']]);
 select.value='m1';devices=[{kind:'audioinput',deviceId:'default',label:'Default'},{kind:'audioinput',deviceId:'m1',label:'USB microphone'}];
 await vm.runInContext('refreshRealtimeMicrophones()',context);assert.equal(select.value,'m1');assert.equal(select.items[1].textContent,'USB microphone');
 devices=[];await vm.runInContext('refreshRealtimeMicrophones()',context);assert.equal(select.value,'');
});
test('a stale device enumeration cannot overwrite a newer result',async()=>{
 const pending=[];const {context,select}=deviceHarness(()=>new Promise(resolve=>pending.push(resolve)));
 const a=vm.runInContext('refreshRealtimeMicrophones()',context),b=vm.runInContext('refreshRealtimeMicrophones()',context);
 pending[1]([{kind:'audioinput',deviceId:'new',label:'New'}]);await b;pending[0]([{kind:'audioinput',deviceId:'old',label:'Old'}]);await a;
 assert.equal(select.items[1].value,'new');
});
test('device enumeration failure leaves the default input available',async()=>{
 const {context,select}=deviceHarness(async()=>{throw Error('unavailable')});await vm.runInContext('refreshRealtimeMicrophones()',context);assert.equal(select.value,'');
});
test('an explicitly chosen Chinese transcription language survives rebuilding voice settings',()=>{
 const app=fs.readFileSync(path.join(root,'app.js'),'utf8');const start=app.indexOf("if(task==='realtime'&&m.outputs.includes('audio')){"),end=app.indexOf("if(['speech','music','realtime','audiofx']",start);assert(start>=0&&end>start);
 const generation={children:[],append(x){this.children.push(x)},replaceChildren(){this.children=[]}};
 const document={createElement:()=>({children:[],value:'',append(...x){this.children.push(...x)},addEventListener(n,fn){this[n]=fn}})};
 const ctx=vm.createContext({m:{outputs:['audio']},task:'realtime',document,$:()=>generation,t:x=>x,Option:function(text,value){return {text,value}}});
 vm.runInContext(`let realtimeRecognitionLanguage='auto';function rebuild(){ $('generationOptions').replaceChildren();${app.slice(start,end)} }`,ctx);
 vm.runInContext('rebuild()',ctx);let select=generation.children[1].children[0];assert.equal(select.value,'auto');select.value='zh';select.change();vm.runInContext('rebuild()',ctx);select=generation.children[1].children[0];assert.equal(select.value,'zh');
});
test('selected input is requested explicitly and weak speech is not reported as complete silence',async()=>{
 let constraints;const levels=[],sent=[],timers=[];const track={readyState:'live',addEventListener(){},stop(){}},stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
 const audio={state:'running',audioWorklet:{addModule:async()=>{}},resume:async()=>{},destination:{},createMediaStreamSource:()=>({connect(){},disconnect(){}})};
 const state={ready:true,recording:false,stopping:false,transcription:false,audio,playing:new Set()};
 class Worklet{constructor(){this.port={}}connect(){}disconnect(){}}
 const context=vm.createContext({rt:state,navigator:{mediaDevices:{getUserMedia:async value=>{constraints=value;return stream}}},selectedRealtimeMicrophone:()=> 'usb-input',refreshRealtimeMicrophones(){},AudioWorkletNode:Worklet,performance:{now:()=>100},Uint8Array,btoa:value=>Buffer.from(value,'binary').toString('base64'),t:x=>x,realtimeUi(){},realtimeNotice(){},realtimeState(){},realtimeMicStatus:value=>levels.push(value),rtSend:event=>sent.push(event),interruptRealtime(){},endRealtime(){},setTimeout:fn=>{timers.push(fn);return 1},clearTimeout(){}});
 vm.runInContext(source.slice(source.indexOf('async function beginRecording(){'),source.indexOf('async function finishRecording(){')),context);
 await vm.runInContext('beginRecording()',context);assert.equal(constraints.audio.deviceId.exact,'usb-input');
 state.capture.port.onmessage({data:{audio:new Uint8Array([1,2]).buffer,level:0.001}});assert.equal(levels.at(-1),'麦克风声音很小，请检查输入音量或选择其他麦克风。');assert.equal(sent.length,1);
 state.capture.port.onmessage({data:{audio:new Uint8Array([1,2]).buffer,level:0.1}});assert.equal(levels.at(-1),'麦克风输入正常');
});
test('manual transcription audio volume alone does not count as account activity',async()=>{
 let activity=0;const track={readyState:'live',addEventListener(){},stop(){}},stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
 const audio={state:'running',audioWorklet:{addModule:async()=>{}},resume:async()=>{},destination:{},createMediaStreamSource:()=>({connect(){},disconnect(){}})};
 const state={ready:true,recording:false,stopping:false,transcription:true,model:{id:'gpt-live-transcribe'},audio,playing:new Set()};
 class Worklet{constructor(){this.port={}}connect(){}disconnect(){}}
 const context=vm.createContext({rt:state,StarSessionIdle:{activity(){activity++;}},navigator:{mediaDevices:{getUserMedia:async()=>stream}},selectedRealtimeMicrophone:()=>'',refreshRealtimeMicrophones(){},AudioWorkletNode:Worklet,performance:{now:()=>100},Uint8Array,btoa:value=>Buffer.from(value,'binary').toString('base64'),t:x=>x,realtimeUi(){},realtimeNotice(){},realtimeState(){},realtimeMicStatus(){},rtSend(){},endRealtime(){},setTimeout:()=>1,clearTimeout(){}});
 vm.runInContext(source.slice(source.indexOf('async function beginRecording(){'),source.indexOf('async function finishRecording(){')),context);
 await vm.runInContext('beginRecording()',context);state.capture.port.onmessage({data:{audio:new Uint8Array(4800).buffer,level:0.5}});assert.equal(activity,0);
});
test('a late microphone permission grant is released after session expiry',async()=>{
 let grant,stopped=0;const pending=new Promise(resolve=>{grant=resolve;});
 const track={stop(){stopped++;}},stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
 const state={ready:true,recording:false,stopping:false,audio:{audioWorklet:{}}};
 const context=vm.createContext({rt:state,navigator:{mediaDevices:{getUserMedia:()=>pending}},t:x=>x,realtimeUi(){}});
 vm.runInContext(source.slice(source.indexOf('async function beginRecording(){'),source.indexOf('async function finishRecording(){')),context);
 const starting=vm.runInContext('beginRecording()',context);state.idleEnded=true;state.stopping=false;grant(stream);await starting;
 assert.equal(stopped,1);assert.equal(state.recording,false);assert.equal(state.stream,undefined);
});
test('capture watchdog reports a later audio stall and track mute instead of stale input status',async()=>{
 let now=0,resumes=0;const events={},levels=[],timers=[],details={open:false};const track={readyState:'live',muted:false,addEventListener(name,fn){events[name]=fn;},stop(){}},stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
 const audio={state:'running',audioWorklet:{addModule:async()=>{}},resume:async()=>{resumes++;},destination:{},createMediaStreamSource:()=>({connect(){},disconnect(){}})};
 const state={ready:true,recording:false,stopping:false,transcription:false,model:{id:'gpt-live-1'},audio};
 class Worklet{constructor(){this.port={}}connect(){}disconnect(){}}
 const ctx=vm.createContext({rt:state,$:id=>id==='realtimeDetails'?details:null,navigator:{mediaDevices:{getUserMedia:async()=>stream}},selectedRealtimeMicrophone:()=>'',refreshRealtimeMicrophones(){},AudioWorkletNode:Worklet,performance:{now:()=>now},Uint8Array,btoa:value=>Buffer.from(value,'binary').toString('base64'),t:x=>x,realtimeUi(){},realtimeNotice(){},realtimeState(){},realtimeMicStatus:value=>levels.push(value),rtSend(){},endRealtime(){},setTimeout:fn=>{timers.push(fn);return timers.length;},clearTimeout(){}});
 vm.runInContext(source.slice(source.indexOf('async function beginRecording(){'),source.indexOf('async function finishRecording(){')),ctx);await ctx.beginRecording();
 now=100;state.capture.port.onmessage({data:{audio:new Uint8Array(4).buffer,level:0.1}});assert.equal(levels.at(-1),'麦克风输入正常');
 now=5000;timers.shift()();assert.equal(levels.at(-1),'麦克风无音频数据，请检查浏览器或系统输入设备。');assert.equal(details.open,true);
 events.mute();assert.match(levels.at(-1),/暂不可用/);state.capture.port.onmessage({data:{audio:new Uint8Array(4).buffer,level:0}});assert.match(levels.at(-1),/暂不可用/);
 events.unmute();now=5100;state.capture.port.onmessage({data:{audio:new Uint8Array(4).buffer,level:0.1}});assert.equal(levels.at(-1),'麦克风输入正常');
 audio.state='suspended';now=9000;timers.shift()();assert.match(levels.at(-1),/音频处理已暂停/);assert.equal(resumes,2);
 now=13000;timers.shift()();assert.equal(resumes,2,'do not repeatedly resume the same interruption');
 audio.state='running';now=13100;state.capture.port.onmessage({data:{audio:new Uint8Array(4).buffer,level:0.1}});audio.state='suspended';now=17000;timers.shift()();assert.equal(resumes,3,'a new interruption gets one recovery attempt');
});
test('switching microphone while recording pauses then restarts with the selected input',async()=>{
 let change,finish;const done=new Promise(resolve=>{finish=resolve;});const state={ready:true,recording:true,stopping:false};const calls=[];
 const controls={recordRealtime:{},interruptRealtime:{},realtimeMicrophone:{addEventListener(name,fn){if(name==='change')change=fn;}}};
 const ctx=vm.createContext({rt:state,$:id=>controls[id],finishRecording:async()=>{calls.push('pause');state.stopping=true;await done;state.recording=false;state.stopping=false;},beginRecording:async()=>calls.push('restart'),interruptRealtime(){}});
 vm.runInContext(source.slice(source.indexOf("$('recordRealtime').onclick"),source.indexOf('async function settleRealtimeReceipt')),ctx);
 const switching=change();assert.deepEqual(calls,['pause']);finish();await switching;assert.deepEqual(calls,['pause','restart']);
});
