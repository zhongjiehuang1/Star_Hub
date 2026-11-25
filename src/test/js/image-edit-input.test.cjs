const {test}=require('node:test'),assert=require('node:assert/strict');
const {create,dimensions,fromDataUrl,MAX_SOURCE,MAX_INPUT}=require('../../main/webapp/image-edit-input.js');
function png(width=640,height=640,size=32){const bytes=new Uint8Array(size);bytes.set([137,80,78,71,13,10,26,10]);bytes.set([73,72,68,82],12);const v=new DataView(bytes.buffer);v.setUint32(16,width);v.setUint32(20,height);return new Blob([bytes],{type:'image/png'});}
function fixture(source,{encode}={}){
 let closed=0,canvases=0,draws=0;const calls=[];
 const canvas={width:0,height:0,getContext:()=>({clearRect(){},drawImage(){draws++;}}),toBlob(cb,type,quality){calls.push({type,quality,width:this.width,height:this.height});cb(encode?encode(calls.length):new Blob([new Uint8Array(128)],{type:'image/webp'}));}};
 const api=create({createImageBitmap:async()=>({width:640,height:640,close(){closed++;}}),makeCanvas:()=>{canvases++;return canvas;},readDataUrl:async b=>`data:${b.type};base64,${Buffer.from(await b.arrayBuffer()).toString('base64')}`});
 return {api,source,calls,stats:()=>({closed,canvases,draws})};
}
const owner=url=>url==='blob:owned';
test('uses exact original bytes for a bounded verified image',async()=>{
 const source=png(),f=fixture(source);const r=await f.api.prepare('blob:owned',{blob:source,owns:owner});
 assert.equal(r.wasConverted,false);assert.equal(r.wasResized,false);assert.equal(r.width,640);assert.equal(r.height,640);
 assert.equal(r.dataUrl,`data:image/png;base64,${Buffer.from(await source.arrayBuffer()).toString('base64')}`);
 assert.deepEqual(f.stats(),{closed:1,canvases:0,draws:0});
});
test('encodes a large source at its original size first, preserving transparency support',async()=>{
 const f=fixture(png(640,640,MAX_INPUT));const r=await f.api.prepare('blob:owned',{blob:f.source,owns:owner});
 assert.equal(r.wasConverted,true);assert.equal(r.wasResized,false);assert.equal(r.width,640);assert.equal(r.height,640);assert.match(r.dataUrl,/^data:image\/webp;base64,/);
 assert.deepEqual(f.calls,[{type:'image/webp',quality:0.95,width:640,height:640}]);assert.equal(f.stats().closed,1);
});
test('uses bounded resizing only when full-size encodings cannot fit',async()=>{
 const tooLarge=new Blob([new Uint8Array(MAX_INPUT)],{type:'image/webp'});
 const f=fixture(png(640,640,MAX_INPUT),{encode:n=>n<=3?tooLarge:new Blob([new Uint8Array(64)],{type:'image/webp'})});
 const r=await f.api.prepare('blob:owned',{blob:f.source,owns:owner});assert.equal(r.wasConverted,true);assert.equal(r.wasResized,true);assert.equal(r.width,512);assert.equal(r.height,512);assert.equal(f.calls.length,4);
});
test('rejects unowned URLs, oversized sources, malformed or giant image headers before decoding',async()=>{
 const f=fixture(png());await assert.rejects(f.api.prepare('https://example.com/x',{blob:f.source,owns:()=>true}));await assert.rejects(f.api.prepare('blob:other',{blob:f.source,owns:owner}));assert.equal(f.stats().closed,0);
 const large=png(640,640,MAX_SOURCE+1);await assert.rejects(fixture(large).api.prepare('blob:owned',{blob:large,owns:owner}));
 const malformed=new Blob(['not png'],{type:'image/png'});await assert.rejects(fixture(malformed).api.prepare('blob:owned',{blob:malformed,owns:owner}));
 const giant=png(8192,8192);await assert.rejects(fixture(giant).api.prepare('blob:owned',{blob:giant,owns:owner}));
});
test('cancels after ownership or conversation changes across asynchronous work',async()=>{
 let unblock,closed=false;const gate=new Promise(r=>unblock=r),controller=new AbortController();let active=true;
 const f=create({createImageBitmap:async()=>{await gate;return {width:640,height:640,close(){closed=true;}};},makeCanvas(){throw Error('unexpected');},readDataUrl(){throw Error('unexpected');}});
 const pending=f.prepare('blob:owned',{blob:png(),owns:owner,active:()=>active,signal:controller.signal});await new Promise(resolve=>setImmediate(resolve));active=false;unblock();await assert.rejects(pending,/已取消/);assert.equal(closed,true);
 const g=fixture(png());controller.abort();await assert.rejects(g.api.prepare('blob:owned',{blob:g.source,owns:owner,signal:controller.signal}));assert.equal(g.stats().closed,0);
});
test('cleans up decoded image if the conversation changes during encoding',async()=>{
 let unblock,closed=false,active=true;const gate=new Promise(r=>unblock=r),source=png(640,640,MAX_INPUT);
 const f=create({createImageBitmap:async()=>({width:640,height:640,close(){closed=true;}}),makeCanvas:()=>({width:0,height:0,getContext:()=>({clearRect(){},drawImage(){}}),toBlob:async cb=>{await gate;cb(new Blob(['x'],{type:'image/webp'}));}}),readDataUrl:()=>{throw Error('must not encode');}});
 const pending=f.prepare('blob:owned',{blob:source,owns:owner,active:()=>active});await new Promise(resolve=>setImmediate(resolve));active=false;unblock();await assert.rejects(pending,/已取消/);assert.equal(closed,true);
});
test('rejects invalid decodes and never strips transparency with a JPEG canvas fallback',async()=>{
 const source=png(640,640,MAX_INPUT);let closed=false;
 const invalid=create({createImageBitmap:async()=>({width:1,height:1,close(){closed=true;}}),makeCanvas(){throw Error('unexpected');},readDataUrl(){throw Error('unexpected');}});
 await assert.rejects(invalid.prepare('blob:owned',{blob:source,owns:owner}),/尺寸校验/);assert.equal(closed,true);
 const f=fixture(source,{encode:()=>new Blob(['x'],{type:'image/jpeg'})});await assert.rejects(f.api.prepare('blob:owned',{blob:source,owns:owner}),/过大/);assert.equal(f.calls.length,21);assert.equal(f.stats().closed,1);
});
test('decodes only bounded PNG/JPEG/WebP data URLs without fetch',async()=>{
 const source=png(),encoded=`data:image/png;base64,${Buffer.from(await source.arrayBuffer()).toString('base64')}`,blob=fromDataUrl(encoded);
 assert.equal(blob.type,'image/png');assert.deepEqual(Buffer.from(await blob.arrayBuffer()),Buffer.from(await source.arrayBuffer()));
 for(const bad of ['https://example.com/image.png','data:text/html;base64,SGk=','data:image/png;base64,A===','data:image/png;base64,A!AA','data:image/png;base64,'])assert.throws(()=>fromDataUrl(bad));
 assert.throws(()=>fromDataUrl('data:image/png;base64,'+'A'.repeat(Math.ceil(MAX_SOURCE/3)*4+4)));
});
test('recognizes bounded JPEG and WebP headers and rejects unsupported payloads',()=>{
 const jpeg=Uint8Array.from([255,216,255,192,0,11,8,0,64,0,32,1,1,17,0,255,217]);assert.deepEqual(dimensions(jpeg,'image/jpeg'),{width:32,height:64});
 const webp=new Uint8Array(30);webp.set(Buffer.from('RIFF'),0);webp.set(Buffer.from('WEBPVP8X'),8);webp[24]=31;webp[27]=63;assert.deepEqual(dimensions(webp,'image/webp'),{width:32,height:64});
 assert.throws(()=>dimensions(webp,'image/png'));assert.throws(()=>dimensions(jpeg,'image/webp'));
});
