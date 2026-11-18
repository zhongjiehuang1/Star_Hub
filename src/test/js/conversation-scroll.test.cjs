const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../../main/webapp/app.js'),'utf8');
const begin=source.indexOf('function createConversationScrollController('),end=source.indexOf('\nconst conversationScroll=',begin);
assert(begin>=0&&end>begin);
function setup(){
 const listeners={},observed=[];let callback;
 const pane={scrollHeight:1000,clientHeight:300,_top:700,addEventListener(type,fn){listeners[type]=fn;},get scrollTop(){return this._top;},set scrollTop(value){this._top=Math.max(0,Math.min(value,this.scrollHeight-this.clientHeight));}};
 const ctx=vm.createContext({ResizeObserver:class{constructor(fn){callback=fn;}observe(target){observed.push(target);}}});
 vm.runInContext(source.slice(begin,end),ctx);const content={},controller=ctx.createConversationScrollController(pane,content);
 return {pane,controller,observed,resize:()=>callback(),event:(type,event={})=>listeners[type](event)};
}
test('streaming does not undo a small user scroll; following resumes only at the bottom',()=>{
 const {pane,controller,event}=setup();
 event('wheel',{deltaY:-1});pane.scrollTop=695;event('scroll');pane.scrollHeight=1020;
 assert.equal(controller.begin(false),false);controller.finish(false,true);assert.equal(pane.scrollTop,695);
 pane.scrollTop=710;event('scroll');assert.equal(controller.begin(false),false);
 pane.scrollTop=720;event('scroll');assert.equal(controller.begin(false),true);
 pane.scrollHeight=1040;controller.finish(controller.begin(false),true);assert.equal(pane.scrollTop,740);
});
test('media and viewport resizes follow only while the user follows',()=>{
 const {pane,controller,event,resize,observed}=setup();assert.equal(observed.length,2);
 pane.scrollHeight=1150;resize();assert.equal(pane.scrollTop,850);
 event('wheel',{deltaY:-3});pane.scrollTop=800;event('scroll');pane.scrollHeight=1250;resize();assert.equal(pane.scrollTop,800);
 pane.clientHeight=250;resize();assert.equal(pane.scrollTop,800);
 controller.finish(controller.begin(true),true);assert.equal(pane.scrollTop,1000);
});
test('touch, keyboard, and scrollbar upward movement stop following, with chat changes resetting it',()=>{
 for(const mode of ['touch','key','scrollbar']){
  const {pane,controller,event}=setup();
  if(mode==='touch'){event('touchstart',{touches:[{clientY:30}]});event('touchmove',{touches:[{clientY:40}]});}
  else if(mode==='key')event('keydown',{key:'PageUp'});
  else{pane.scrollTop=690;event('scroll');}
  assert.equal(controller.begin(false),false,mode);assert.equal(controller.begin(true),true,mode);
 }
});
test('only the known realtime weather source receives a visible fixed label',()=>{
 assert.match(source,/message\.details\?\.realtimeSource==='oai-weather'/);
 assert.match(source,/实时数据源：OpenAI 搜索天气数据（未提供网页链接）/);
});
test('removing old conversation rows suspends their maps first',()=>{
 const start=source.indexOf('  while (messages.children.length > index) {const row=messages.lastElementChild;');assert(start>=0);
 const end=source.indexOf('\n',start),statement=source.slice(start,end);const events=[];
 const row={querySelectorAll:selector=>(assert.equal(selector,'.city-map'),[{cityMapSuspend:()=>events.push('suspend')}]),remove(){events.push('remove');messages.children.pop();}};
 const messages={children:[{},row],lastElementChild:row};vm.runInNewContext(statement,{messages,index:1});assert.deepEqual(events,['suspend','remove']);
});
