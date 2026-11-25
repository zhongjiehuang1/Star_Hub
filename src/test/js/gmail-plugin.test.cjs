const test=require('node:test'),assert=require('node:assert/strict');
const gmail=require('../../main/webapp/gmail-plugin.js');
class Element{
 constructor(tag){this.tagName=tag;this.children=[];this.listeners={};this.attributes={};this._text='';this.hidden=false;this.value='';this.disabled=false;}
 set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+this.children.map(c=>c.textContent).join('');}
 setAttribute(k,v){this.attributes[k]=v;}append(...els){this.children.push(...els);}replaceChildren(...els){this.children=[...els];this._text='';}
 addEventListener(n,fn){(this.listeners[n]||=[]).push(fn);}remove(){}fire(n){for(const f of this.listeners[n]||[])f({preventDefault(){}});}
}
class Document{
 constructor(){this.events={};}createElement(tag){return new Element(tag);}addEventListener(n,fn){(this.events[n]||=[]).push(fn);}
 removeEventListener(n,fn){this.events[n]=(this.events[n]||[]).filter(x=>x!==fn);}fire(n){for(const f of this.events[n]||[])f();}
}
function all(root,pred){return [root,...root.children.flatMap(x=>all(x,pred))].filter(pred);}
function find(root,text){const values=all(root,x=>x.tagName==='button'&&x.textContent===text);assert.equal(values.length,1,`button ${text}`);return values[0];}
const turn=()=>new Promise(resolve=>setImmediate(resolve));
const fakeMessage=(overrides={})=>({id:'abc123',from:'A <a@example.test>',subject:'<img src=x onerror=alert(1)>',date:'Today',snippet:'Preview',body:'hello from email',truncated:false,...overrides});
const ok=value=>({ok:true,json:async()=>value});

test('OAuth browser destination is exact, readonly, state-bound and not arbitrary',()=>{
 const base='https://accounts.google.com/o/oauth2/v2/auth',u=new URL(base);Object.entries({client_id:'123.apps.googleusercontent.com',redirect_uri:'https://starhub.example/api/gmail/callback',response_type:'code',scope:'https://www.googleapis.com/auth/gmail.readonly',state:'a'.repeat(43),code_challenge:'b'.repeat(43),code_challenge_method:'S256',access_type:'offline',prompt:'consent'}).forEach(([k,v])=>u.searchParams.set(k,v));
 assert.ok(gmail.safeAuthorizationUrl(u.href,'https://starhub.example'));
 const bad=new URL(u);bad.hostname='evil.example';assert.equal(gmail.safeAuthorizationUrl(bad.href,'https://starhub.example'),'');
 bad.hostname='accounts.google.com';bad.searchParams.set('scope','https://www.googleapis.com/auth/gmail.send');assert.equal(gmail.safeAuthorizationUrl(bad.href,'https://starhub.example'),'');
 bad.searchParams.set('scope','https://www.googleapis.com/auth/gmail.readonly');bad.searchParams.set('redirect_uri','https://evil.example/callback');assert.equal(gmail.safeAuthorizationUrl(bad.href,'https://starhub.example'),'');
 bad.searchParams.set('redirect_uri','https://starhub.example/api/gmail/callback');bad.searchParams.append('state','c'.repeat(43));assert.equal(gmail.safeAuthorizationUrl(bad.href,'https://starhub.example'),'');
});

test('unconfigured Gmail is explicit and never opens Google automatically',async()=>{
 const doc=new Document(),container=new Element('div');let navigations=0,calls=0;
 const ui=gmail.mount(container,{document:doc,location:{origin:'https://starhub.example',assign(){navigations++;}},identity:()=>({id:'owner',revision:1}),providerLabel:()=>'',sendSummary:async()=>{},fetch:async()=>{calls++;return ok({available:false,state:'NOT_CONFIGURED'});}});
 assert.equal(calls,0);await ui.refresh();assert.match(ui.root.textContent,/not configured/i);assert.equal(navigations,0);ui.destroy();
});

test('email is rendered as text and summary requires an explicit provider confirmation',async()=>{
 const doc=new Document(),container=new Element('div'),mail=fakeMessage();let approved=false,consents=0,sends=[];
 const fetcher=async url=>url.endsWith('/search')?ok({messages:[{...mail,body:''}],nextPageToken:''}):url.endsWith('/message')?ok(mail):ok({available:true,state:'CONNECTED'});
 const ui=gmail.mount(container,{document:doc,location:{origin:'https://starhub.example',assign(){}},identity:()=>({id:'owner',revision:1}),providerLabel:()=> 'OpenAI',sendSummary:async(prompt,info)=>sends.push({prompt,info}),confirmSummary:async info=>{consents++;assert.equal(info.provider,'OpenAI');return approved;},fetch:fetcher});
 const form=all(ui.root,x=>x.tagName==='form')[0];form.fire('submit');await turn();find(ui.root,'View').fire('click');await turn();
 assert.match(ui.root.textContent,/<img src=x onerror=alert\(1\)>/);assert.equal(all(ui.root,x=>x.tagName==='img').length,0);
 find(ui.root,'Summarize this email').fire('click');await turn();assert.equal(consents,1);assert.equal(sends.length,0);
 approved=true;find(ui.root,'Summarize this email').fire('click');await turn();assert.equal(sends.length,1);assert.match(sends[0].prompt,/hello from email/);assert.match(sends[0].prompt,/不可信/);assert.equal(sends[0].info.provider,'OpenAI');
});

test('account changes during JSON parsing discard selected mailbox data',async()=>{
 const doc=new Document(),container=new Element('div');let owner={id:'one',revision:1},resolveBody;
 const pending=new Promise(resolve=>resolveBody=resolve);
 const ui=gmail.mount(container,{document:doc,location:{origin:'https://starhub.example',assign(){}},identity:()=>owner,providerLabel:()=> 'OpenAI',sendSummary:async()=>{},fetch:async()=>({ok:true,json:()=>pending})});
 all(ui.root,x=>x.tagName==='form')[0].fire('submit');await turn();owner={id:'two',revision:2};doc.fire('starhub:account-changed');resolveBody({messages:[fakeMessage({body:''})],nextPageToken:''});await turn();
 assert.equal(all(ui.root,x=>x.tagName==='article').length,0);assert.doesNotMatch(ui.root.textContent,/a@example.test/);
});

test('pagination is explicit and preserves the original search query',async()=>{
 const doc=new Document(),container=new Element('div');let sent=[];
 const ui=gmail.mount(container,{document:doc,location:{origin:'https://starhub.example',assign(){}},identity:()=>({id:'owner',revision:1}),providerLabel:()=> 'OpenAI',sendSummary:async()=>{},fetch:async(url,opts)=>{const body=JSON.parse(opts.body);sent.push(body);return ok({messages:[fakeMessage({id:sent.length===1?'one':'two',body:''})],nextPageToken:sent.length===1?'page-token':''});}});
 const form=all(ui.root,x=>x.tagName==='form')[0],input=all(ui.root,x=>x.tagName==='input')[0];input.value='is:unread';form.fire('submit');await turn();const more=find(ui.root,'Load more');assert.equal(more.hidden,false);more.fire('click');await turn();
 assert.equal(sent.length,2);assert.equal(sent[1].query,'is:unread');assert.equal(sent[1].pageToken,'page-token');assert.equal(more.hidden,true);assert.equal(all(ui.root,x=>x.tagName==='article').length,2);
});

test('unknown authorization requires explicit confirmation of external Google revocation',async()=>{
 const doc=new Document(),container=new Element('div');let approved=false,posts=0;
 const ui=gmail.mount(container,{document:doc,location:{origin:'https://starhub.example',assign(){}},identity:()=>({id:'owner',revision:1}),providerLabel:()=> 'OpenAI',sendSummary:async()=>{},confirmGoogleRevocation:async()=>approved,fetch:async(url)=>{if(url.endsWith('/status'))return ok({available:true,state:'UNKNOWN'});posts++;return ok({state:'DISCONNECTED'});}});
 await ui.refresh();const reviewed=find(ui.root,'I revoked access in Google');assert.equal(reviewed.hidden,false);
 const link=all(ui.root,x=>x.tagName==='a')[0];assert.equal(link.href,'https://myaccount.google.com/connections');
 reviewed.fire('click');await turn();assert.equal(posts,0);approved=true;reviewed.fire('click');await turn();assert.equal(posts,1);assert.equal(reviewed.hidden,true);
});

test('in-flight exchange blocks automatic reconnection and cannot be acknowledged as revoked',async()=>{
 const doc=new Document(),container=new Element('div');const ui=gmail.mount(container,{document:doc,location:{origin:'https://starhub.example',assign(){}},identity:()=>({id:'owner',revision:1}),providerLabel:()=>'',sendSummary:async()=>{},fetch:async()=>ok({available:true,state:'EXCHANGING'})});
 await ui.refresh();assert.match(ui.root.textContent,/automatic reconnection is unavailable/i);assert.equal(find(ui.root,'I revoked access in Google').hidden,true);
});

test('session mismatch produces a clear reauthentication message',async()=>{
 const doc=new Document(),container=new Element('div');let sentHeaders;
 const ui=gmail.mount(container,{document:doc,location:{origin:'https://starhub.example',assign(){}},identity:()=>({id:'old@example.test',revision:1}),providerLabel:()=>'',sendSummary:async()=>{},fetch:async(_url,opts)=>{sentHeaders=opts.headers;return {ok:false,status:401,json:async()=>({error:'session changed'})}}});
 await ui.refresh();assert.equal(sentHeaders['X-Starhub-Expected-Account'],'old@example.test');assert.match(ui.root.textContent,/session changed or expired/i);
});

test('switching the conversation while consent is pending prevents a send',async()=>{
 const doc=new Document(),container=new Element('div'),mail=fakeMessage();let chat='one',approve,sends=0;
 const consent=new Promise(resolve=>approve=resolve);
 const fetch=async url=>url.endsWith('/search')?ok({messages:[{...mail,body:''}],nextPageToken:''}):ok(mail);
 const ui=gmail.mount(container,{document:doc,location:{origin:'https://starhub.example',assign(){}},identity:()=>({id:'owner',revision:1}),contextToken:()=>chat,providerLabel:()=> 'OpenAI',sendSummary:async()=>sends++,confirmSummary:()=>consent,fetch});
 all(ui.root,x=>x.tagName==='form')[0].fire('submit');await turn();find(ui.root,'View').fire('click');await turn();find(ui.root,'Summarize this email').fire('click');await turn();chat='two';approve(true);await turn();assert.equal(sends,0);
});
