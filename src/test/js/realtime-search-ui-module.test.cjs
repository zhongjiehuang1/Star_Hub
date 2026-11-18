const {test}=require('node:test'),assert=require('node:assert/strict');
const search=require('../../main/webapp/realtime-search-ui.js');
test('live search attaches bounded public citations only to the resulting assistant turn',()=>{
 const rt={},notices=[];const env={notice:x=>notices.push(x),t:x=>x};
 search.handle({type:'starhub.web_search',status:'searching',callId:'call_1'},rt,env);
 search.handle({type:'starhub.web_search',status:'completed',callId:'call_1',citations:[{url:'https://example.com/a',title:'A'},{url:'https://example.com/a',title:'Duplicate'},{url:'http://example.com/',title:'Insecure'},{url:'https://127.0.0.1/',title:'Local'},{url:'https://name:secret@example.com/',title:'Credentials'}]},rt,env);
 search.handle({type:'starhub.web_search',status:'continuation',callId:'call_1',responseId:'response_1'},rt,env);
 const user={role:'user'},unrelated={role:'assistant'},answer={role:'assistant'};search.attach(user,rt,'response_1');assert.equal(user.details,undefined);search.attach(unrelated,rt,'response_2');assert.equal(unrelated.details,undefined);search.attach(answer,rt,'response_1');
 assert.equal(answer.details.webSearchUsed,true);assert.deepEqual(answer.details.webCitations,[{url:'https://example.com/a',title:'A',end_index:-1}]);assert.equal(rt.pendingWebSearch,null);assert.equal(notices.length,2);
});
test('failed or interrupted searches cannot attach stale citations',()=>{
 const rt={},env={notice(){},t:x=>x};search.handle({type:'starhub.web_search',status:'searching',callId:'old'},rt,env);search.clear(rt);
 search.handle({type:'starhub.web_search',status:'searching',callId:'new'},rt,env);search.handle({type:'starhub.web_search',status:'completed',callId:'old',citations:[{url:'https://example.com',title:'Source'}]},rt,env);
 const a={role:'assistant'};search.attach(a,rt);assert.equal(a.details,undefined);assert.equal(rt.pendingWebSearch.callId,'new');
 search.handle({type:'starhub.web_search',status:'failed',callId:'new'},rt,env);assert.equal(rt.pendingWebSearch,null);
});
test('only the recognized live weather source is attached to the matching answer',()=>{
 const rt={},env={notice(){},t:x=>x},event={type:'starhub.web_search',callId:'call_weather'};
 search.handle({...event,status:'searching'},rt,env);
 search.handle({...event,status:'completed',realtimeSource:'oai-weather'},rt,env);
 search.handle({...event,status:'continuation',responseId:'response_weather'},rt,env);
 const other={role:'assistant'},answer={role:'assistant'};search.attach(other,rt,'response_other');assert.equal(other.details,undefined);search.attach(answer,rt,'response_weather');assert.equal(answer.details.realtimeSource,'oai-weather');
 search.handle({...event,status:'searching'},rt,env);search.handle({...event,status:'completed',realtimeSource:'attacker-value'},rt,env);search.handle({...event,status:'continuation',responseId:'response_next'},rt,env);const next={role:'assistant'};search.attach(next,rt,'response_next');assert.equal(next.details.realtimeSource,undefined);
});
