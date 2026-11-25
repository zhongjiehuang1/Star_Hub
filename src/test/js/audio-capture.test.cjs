const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../../main/webapp/audio-capture.js'),'utf8');
function capture(rate){let Processor;const sent=[];class Base{constructor(){this.port={postMessage:value=>sent.push(value)};}}const ctx=vm.createContext({AudioWorkletProcessor:Base,sampleRate:rate,registerProcessor:(_name,value)=>{Processor=value;},Int16Array,Number,Math});vm.runInContext(source,ctx);return {worklet:new Processor(),sent};}
test('capture emits mono 24 kHz PCM and a local audio level at common device rates',()=>{
 for(const rate of [16000,24000,44100,48000]){const {worklet,sent}=capture(rate),samples=new Float32Array(rate/10).fill(0.25);worklet.process([[samples]],[]);worklet.flush();const bytes=sent.filter(x=>x.audio).map(x=>new Uint8Array(x.audio));assert.equal(bytes.reduce((sum,x)=>sum+x.length,0),4800,`rate ${rate}`);assert.ok(sent.every(x=>Math.abs(x.level-0.25)<0.0001));const view=new DataView(bytes[0].buffer);assert.ok(Math.abs(view.getInt16(0,true)-8191)<=1);}
});
test('silence stays zero and audio levels never go to the provider as text',()=>{
 const {worklet,sent}=capture(48000);worklet.process([[new Float32Array(4800)]],[]);worklet.flush();assert.equal(sent.length,1);assert.equal(sent[0].level,0);assert.ok(new Uint8Array(sent[0].audio).every(x=>x===0));
});
