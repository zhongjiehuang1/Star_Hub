const test=require('node:test'),assert=require('node:assert/strict'),{webcrypto}=require('node:crypto');
const {create,references,MAX}=require('../../main/webapp/provider-media.js');
const operation='11111111-1111-4111-8111-111111111111',file='22222222-2222-4222-8222-222222222222',tab='a'.repeat(32);
const sha=async bytes=>Buffer.from(await webcrypto.subtle.digest('SHA-256',bytes)).toString('hex');
function fixture(options={}){
 let owner='owner@example.test',ownerTab=tab,created=0;const revoked=[],bodies=[];
 const data=options.data||new Uint8Array([1,2,3]);
 const api=create({crypto:options.crypto||webcrypto,Blob,URL:{createObjectURL:()=>`blob:synthetic-${++created}`,revokeObjectURL:url=>revoked.push(url)},actor:()=>owner,tab:()=>ownerTab,fetch:async(url,request)=>{
  bodies.push(JSON.parse(request.body));if(options.fetch)return options.fetch(url,request);
  const headers={'Content-Type':'image/png','Content-Length':String(data.length),'X-Content-SHA256':await sha(data),'X-Starhub-Operation-ID':operation,'X-Starhub-Operation-State':'COMPLETED','X-Four-Project-Durability':'dual',...options.headers};
  return new Response(data,{status:200,headers});
 }});
 return {api,bodies,revoked,created:()=>created,owner:value=>owner=value,tab:value=>ownerTab=value};
}
const ref={type:'image',mime:'image/png',fileId:file},context={operationId:operation,expectedEmail:'owner@example.test'};
test('authenticated binary becomes a RAM URL while references contain no URL or bytes',async()=>{
 const f=fixture(),images=await f.api.hydrate([ref],context);assert.equal(f.api.owns(images[0].url),true);assert.deepEqual(references(images),[ref]);assert.equal(JSON.stringify(references(images)).includes('blob:'),false);
 assert.deepEqual(f.bodies[0],{operationId:operation,ownerTab:tab,expectedEmail:context.expectedEmail,mediaId:file});f.api.release(images);assert.equal(f.api.owns(images[0].url),false);assert.equal(f.revoked.length,1);
});
test('guest references retain original operation identity and reject a changed parent',async()=>{
 const f=fixture(),guest={type:'image',mediaRef:{operationId:operation,mediaId:file,mimeType:'image/png'}};await f.api.hydrate([guest],context);
 await assert.rejects(f.api.hydrate([{...guest,mediaRef:{...guest.mediaRef,operationId:file}}],context));assert.equal(f.bodies.length,1);
});
test('checksum, incomplete length and incomplete authority never publish an image',async()=>{
 for(const headers of [{'X-Content-SHA256':'0'.repeat(64)},{'Content-Length':'4'},{'X-Four-Project-Durability':'unknown'},{'X-Starhub-Operation-ID':file},{'X-Starhub-Operation-State':'SENT'}]){
  const f=fixture({headers});await assert.rejects(f.api.hydrate([ref],context));assert.equal(f.created(),0);
 }
});
test('account/tab change after dispatch refuses the body and never publishes a URL',async()=>{
 let f;f=fixture({fetch:async()=>{f.owner('another@example.test');return new Response(new Uint8Array([1]));}});await assert.rejects(f.api.hydrate([ref],context));assert.equal(f.created(),0);
 f=fixture();f.tab('b'.repeat(32));await assert.rejects(f.api.hydrate([ref],{...context,active:()=>false}));assert.equal(f.bodies.length,0);
});
test('clearing an owner during asynchronous digest invalidates the response',async()=>{
 let f;const crypto={subtle:{digest:async(...args)=>{const result=await webcrypto.subtle.digest(...args);f.api.clear();return result;}}};f=fixture({crypto});await assert.rejects(f.api.hydrate([ref],context));assert.equal(f.created(),0);
});
test('the exact 12 MiB image boundary passes and larger bodies are refused',async()=>{
 let f=fixture({data:new Uint8Array(MAX)});const images=await f.api.hydrate([ref],context);assert.equal(images.length,1);f.api.clear();assert.equal(f.revoked.length,1);
 f=fixture({data:new Uint8Array(MAX+1)});await assert.rejects(f.api.hydrate([ref],context));assert.equal(f.created(),0);
});
test('parallel callers serialize network reads and abandoned conversations publish nothing',async()=>{
 let active=0,peak=0,f;f=fixture({fetch:async()=>{active++;peak=Math.max(peak,active);await new Promise(resolve=>setTimeout(resolve,2));active--;return new Response(new Uint8Array([1,2,3]),{headers:{'Content-Type':'image/png','Content-Length':'3','X-Content-SHA256':await sha(new Uint8Array([1,2,3])),'X-Starhub-Operation-ID':operation,'X-Starhub-Operation-State':'COMPLETED','X-Four-Project-Durability':'single'}});}});
 const results=await Promise.all([f.api.hydrate([ref],context),f.api.hydrate([ref],context)]);assert.equal(peak,1);f.api.clear();assert.equal(f.revoked.length,2);assert.equal(results.length,2);
 await assert.rejects(f.api.hydrate([ref],{...context,active:()=>false}));
});
test('old histories retain all references but previews load at most four at a time',async()=>{assert.equal(references(Array(5).fill(ref)).length,5);const f=fixture();await assert.rejects(f.api.hydrate(Array(5).fill(ref),context));assert.equal(f.bodies.length,0);});
test('imported account references retain and transmit the original import receipt binding',async()=>{
 const importId='33333333-3333-4333-8333-333333333333',imported={...ref,importOperationId:importId};
 const f=fixture({headers:{'X-Starhub-Import-Operation-ID':importId,'X-Starhub-Import-State':'COMMITTED'}});
 const images=await f.api.hydrate([imported],context);assert.deepEqual(references(images),[imported]);assert.equal(f.bodies[0].importOperationId,importId);f.api.clear();
 const wrong=fixture();await assert.rejects(wrong.api.hydrate([imported],context));assert.equal(wrong.created(),0);
});
