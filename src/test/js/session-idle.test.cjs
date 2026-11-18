'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const {create}=require('../../main/webapp/session-idle.js');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function harness(){
 let now=100,seq=0,visible=true,owner='owner@example.test',revision=1,expired=0,recovered=0,status={signedIn:true,email:owner,remainingMs:600000},activityCalls=[],statusCalls=0;
 const timers=new Map();const schedule=(fn,ms)=>{const id=++seq;timers.set(id,{fn,at:now+ms});return id;},cancel=id=>timers.delete(id);
 const manager=create({owner:()=>owner,revision:()=>revision,visible:()=>visible,now:()=>now,schedule,cancel,
  requestActivity:async(email,age)=>{activityCalls.push({email,age});return status;},
  requestStatus:async()=>{statusCalls++;if(status instanceof Error)throw status;return status;},onExpired:()=>expired++,onRecovered:async()=>{recovered++;}});
 async function advance(ms){now+=ms;for(let step=0;step<50;step++){const item=[...timers].filter(([,v])=>v.at<=now).sort((a,b)=>a[1].at-b[1].at)[0];if(!item)break;timers.delete(item[0]);item[1].fn();await Promise.resolve();await Promise.resolve();}}
 return {manager,advance,get calls(){return activityCalls;},get expired(){return expired;},get recovered(){return recovered;},get statusCalls(){return statusCalls;},set status(v){status=v;},set visible(v){visible=v;},set owner(v){owner=v;},set revision(v){revision=v;}};
}
test('only genuine input sends activity and server status checks do not extend it',async()=>{
 const h=harness();h.manager.sync();await Promise.resolve();h.manager.activity(false);await h.advance(10);assert.equal(h.calls.length,0);
 h.manager.activity(true);await h.advance(10);assert.equal(h.calls.length,1);assert.equal(h.calls[0].email,'owner@example.test');assert.ok(h.calls[0].age<=10);
});
test('disabled idle support is probed once and never sends activity or repeated checks',async()=>{
 const h=harness();h.status={disabled:true};h.manager.sync();h.manager.activity(true);await Promise.resolve();await h.advance(600000);h.manager.activity(true);h.manager.onVisibility();await h.advance(600000);
 assert.equal(h.statusCalls,1);assert.equal(h.calls.length,0);
});
test('network failure does not falsely declare expiry, but authoritative signed-out status does',async()=>{
 const h=harness();h.manager.sync();await Promise.resolve();h.status=Error('offline');await h.manager.check();assert.equal(h.expired,0);
 h.status={signedIn:false};await h.manager.check();assert.equal(h.expired,1);
});
test('hidden tabs ignore synthetic and ordinary input, but actual continuing speech may count',async()=>{
 const h=harness();h.manager.sync();await Promise.resolve();h.visible=false;h.manager.activity(false,true);h.manager.activity(true);await h.advance(10);assert.equal(h.calls.length,0);
 h.manager.activity(true,true);await h.advance(10);assert.equal(h.calls.length,1);
});
test('a different account is treated as ended without switching the original draft owner',async()=>{
 const h=harness();h.manager.sync();await Promise.resolve();h.status={signedIn:true,email:'other@example.test',remainingMs:200000};await h.manager.check();assert.equal(h.expired,1);
});
test('a signed-out tab can recover only when the same account signs in elsewhere',async()=>{
 const h=harness();h.manager.sync();await Promise.resolve();h.status={signedIn:false};await h.manager.check();assert.equal(h.expired,1);
 h.status={signedIn:true,email:'other@example.test',remainingMs:500000};await h.manager.check();assert.equal(h.recovered,0);
 h.status={signedIn:true,email:'owner@example.test',remainingMs:500000};await h.manager.check();assert.equal(h.recovered,1);
});
test('expiry stops microphone input and queued realtime playback',()=>{
 const source=fs.readFileSync(path.join(__dirname,'../../main/webapp/advanced.js'),'utf8'),events=[];
 const state={recording:true,stopping:false,speechActive:true,playing:new Set(['audio']),stream:{getTracks:()=>[{stop:()=>events.push('track stopped')}]},source:{disconnect:()=>events.push('source disconnected')},capture:{disconnect:()=>events.push('capture disconnected')}};
 const context=vm.createContext({rt:state,t:x=>x,clearTimeout(){},realtimeMicStatus(){},realtimeNotice(){},realtimeUi(){},stopPlayback:s=>s.playing.clear()});
 vm.runInContext(source.slice(source.indexOf('function expireRealtime(){'),source.indexOf('function stopPlayback(')),context);vm.runInContext('expireRealtime()',context);
 assert.equal(state.idleEnded,true);assert.equal(state.recording,false);assert.equal(state.playing.size,0);assert.ok(events.includes('track stopped'));
});
