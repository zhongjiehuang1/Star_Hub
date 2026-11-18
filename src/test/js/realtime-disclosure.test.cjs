const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.join(__dirname,'../../main/webapp');
test('live controls and optional voice settings start collapsed, retain user choice, and preserve other task inputs',()=>{
 const listeners={},flags=new Set(),task={value:'chat'},options={open:true,classList:{toggle:(name,on)=>on?flags.add(name):flags.delete(name)}},panel={open:false};
 const nodes={task,taskOptions:options,realtimeDetails:panel};
 const document={getElementById:id=>nodes[id],addEventListener:(name,fn)=>listeners[name]=fn};
 vm.runInNewContext(fs.readFileSync(path.join(root,'realtime-disclosure.js'),'utf8'),{document});
 task.value='realtime';listeners['starhub:modelchanged']();assert.equal(options.open,false);assert.equal(panel.open,false);assert(flags.has('realtime-options'));
 options.open=true;panel.open=true;listeners['starhub:modelchanged']();assert.equal(options.open,true);assert.equal(panel.open,true);
 task.value='image';listeners['starhub:modelchanged']();assert.equal(options.open,true);assert(!flags.has('realtime-options'));
 task.value='realtime';listeners['starhub:modelchanged']();assert.equal(options.open,false);assert.equal(panel.open,false);
});
test('collapsed live panel keeps connection, microphone and errors accessible in named native disclosures',()=>{
 const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
 const section=html.slice(html.indexOf('<section id="realtimePanel"'),html.indexOf('<form id="chatForm"'));
 assert.match(section,/<details id="realtimeDetails"><summary class="realtime-summary"><span id="realtimeTitle">Live voice<\/span><span id="realtimeState" role="status">/);
 assert.match(section,/<button id="connectRealtime"/);assert.match(section,/<button id="recordRealtime"/);assert.match(section,/<button id="interruptRealtime"/);assert.match(section,/<button id="endRealtime"/);
 assert.match(section,/<\/details>\s*<div id="realtimeMicLevel" role="status" aria-live="polite" hidden><\/div><p id="realtimeNotice" role="status">/);
 assert.match(html,/<details id="taskOptions" open><summary>Voice settings<\/summary>/);
});
