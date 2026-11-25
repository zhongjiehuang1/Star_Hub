'use strict';
// The model supplies bounded data only. It never supplies executable code or markup.
(function(root){
const conversationInstruction=`Answer the latest user request in the context of this conversation. For a short follow-up, use the most recent relevant topic and any correction the user made. Ask one brief question if the subject is unclear. Use the user's language, lead with the answer, and match the detail to the task. When the user asks for a diagram or visual explanation, include a labeled visual next to the part it explains.`;
const instruction=`Use labeled diagrams when parts, paths, mechanisms, or relationships are easier to understand visually. One well-labeled diagram is usually enough. Put each diagram immediately after the heading or introductory paragraph that it explains, before moving to the next idea. For multiple independent ideas, use separate diagrams adjacent to the relevant sections. Use up to three visuals total. Use arrows, labels and contrasting colors to explain relationships. Before emitting a visual, check that the screen positions, directions, signs and numerical values agree with the explanation. Keep all labels in the canvas. Use motion only when it conveys meaningful change. Use the answer language. Output geometry in fenced star-visual JSON blocks. Use only this schema: {"version":1,"title":"short title","description":"what the animation represents","duration":6,"objects":[...]} in a 640 by 320 coordinate space. Allowed objects: {"type":"wave","label":"E","y":100,"amplitude":35,"cycles":2,"speed":1,"color":"#7258c7"}; {"type":"arrow","x":100,"y":150,"x2":180,"y2":150,"color":"#7258c7","label":"field"}; {"type":"line","x":100,"y":150,"x2":180,"y2":150,"color":"#7258c7"}; {"type":"circle","x":80,"y":120,"r":20,"color":"#7258c7","label":"charge"}; {"type":"rect","x":70,"y":100,"width":80,"height":40,"color":"#7258c7","label":"magnet"}; {"type":"text","x":100,"y":70,"text":"label","color":"#52436d"}. Objects except wave may optionally have "motion":{"dx":60,"dy":0,"phase":0} to oscillate sinusoidally. Wave speed is cycles per animation period; dx/dy are maximum displacements in pixels; phase is radians. At most 24 objects. Use valid JSON, no comments or code; colors must be hex. Choose diagrams and labels scientifically appropriate to the question. Omit the block when a visual would not help. Never use this format in JSON-only output.`;
const explanationInstruction=`Use a fenced star-explain JSON block immediately after the relevant paragraph when an interactive workflow or a comparison would help. Use steps for a prose workflow and bars for nonnegative quantities with a common unit. Use {"version":1,"kind":"steps","title":"Title","items":[{"label":"Step","detail":"Brief explanation"}]} or {"version":1,"kind":"bars","title":"Title","unit":"optional unit","items":[{"label":"Category","value":12,"detail":"optional context"}]}. Include 2–8 items, use the user's language and never invent measurements. Prefer star-visual for mechanisms, geometry and labeled diagrams. Use at most three visuals overall and omit it for simple answers or structured-output requests.`;
function validateExplanation(raw){
 if(typeof raw!=='string'||raw.length>8000)return null;
 let spec;try{spec=JSON.parse(raw);}catch{return null;}
 const label=(value,max)=>typeof value==='string'&&value.trim().length>0&&value.length<=max;
 if(!spec||spec.version!==1||!['steps','bars'].includes(spec.kind)||!label(spec.title,120)||
    !Array.isArray(spec.items)||spec.items.length<2||spec.items.length>8)return null;
 if(spec.unit!==undefined&&!label(spec.unit,32))return null;
 for(const item of spec.items){
  if(!item||!label(item.label,80))return null;
  if((spec.kind==='steps'||item.detail!==undefined)&&!label(item.detail,400))return null;
  if(spec.kind==='bars'&&(!Number.isFinite(item.value)||item.value<0))return null;
 }
 return spec;
}
function makeExplanation(spec){
 const el=(tag,cls,value)=>{const node=document.createElement(tag);node.className=cls;if(value!==undefined)node.textContent=value;return node;};
 const panel=el('section','star-visual star-explanation');
 panel.append(el('strong','star-visual-header',spec.title));
 if(spec.kind==='steps'){
  const list=el('ol','star-explanation-steps');
  spec.items.forEach((item,index)=>{
   const row=el('li','');
   const step=el('details','');step.open=index===0;
   step.append(el('summary','',item.label),el('p','',item.detail));
   row.append(step);list.append(row);
  });
  panel.append(list);
 }else{
  const list=el('ul','star-explanation-bars');
  const max=Math.max(...spec.items.map(item=>item.value));
  const format=new Intl.NumberFormat(undefined,{maximumSignificantDigits:12});
  for(const item of spec.items){
   const row=el('li',''), meter=el('progress','');
   meter.max=max||1;meter.value=item.value;meter.setAttribute('aria-hidden','true');
   const heading=el('div','star-explanation-value');
   heading.append(el('span','',item.label),el('span','',format.format(item.value)+(spec.unit?' '+spec.unit:'')));
   row.append(heading,meter);
   if(item.detail)row.append(el('small','',item.detail));
   list.append(row);
  }
  panel.append(list);
 }
 return panel;
}
const voiceStopWords=new Set([
 'about','also','and','are','been','being','can','could','from','have','here','into','just','like','more',
 'only','over','same','some','that','the','their','them','then','there','these','they','this','those','through',
 'together','under','used','using','very','want','was','were','what','when','where','which','will','with','would','you','your',
 'chart','compare','comparison','let','diagram','explanation','figure','label','part','see','show','step','visual',
 '下面','两种','我们','来看','图中','可以','这个','这种','就是','的是','一个','一些','一下','这里','这样','然后','所以','因此','如何','什么','部分','示意','对比','步骤'
]);
function voiceTerms(text){
 const terms=new Set();
 for(const match of text.normalize('NFKC').toLowerCase().matchAll(/\p{Script=Han}+|(?:(?!\p{Script=Han})[\p{L}\p{N}])+/gu)){
  const word=match[0];
  if(/\p{Script=Han}/u.test(word)){
   const characters=[...word];
   for(let i=1;i<characters.length;i++){
    const term=characters[i-1]+characters[i];
    if(!voiceStopWords.has(term))terms.add(term);
   }
  }else{
   const term=word.replace(/(?<=[a-z]{3})s$/,'');
   if((term.length>=3||/^\d+$/.test(term))&&!voiceStopWords.has(term))terms.add(term);
  }
 }
 return terms;
}
function voiceVisual(raw){
 const scene=validate(raw),spec=scene||validateExplanation(raw);
 if(!spec)return null;
 const fence=scene?'star-visual':'star-explain';
 const cue=typeof spec.cue==='string'?spec.cue.slice(0,80):'';
 const names=scene?spec.objects.map(part=>part.label):spec.items.flatMap(item=>[item.label,String(item.value??'')]);
 const descriptions=scene?[spec.description,...spec.objects.map(part=>part.text)]:spec.items.map(item=>item.detail);
 const terms=new Map();
 for(const [texts,weight] of [[[spec.title,cue],4],[names,2],[descriptions,1]]){
  for(const term of voiceTerms(texts.filter(text=>typeof text==='string').join(' '))){
   if(!terms.has(term))terms.set(term,weight);
  }
 }
 return {title:spec.title,cue,terms,block:'```'+fence+'\n'+JSON.stringify(spec)+'\n```'};
}
function voiceSections(transcript){
 const sections=[];
 let start=0;
 function record(end){
  const text=transcript.slice(start,end);
  if(text.trim())sections.push({end,terms:voiceTerms(text)});
  start=end;
 }
 for(const match of transcript.matchAll(/(?:[。！？!?；;]+|[.]+(?=\s|$))["'”’）)」』]*|\n+/g))record(match.index+match[0].length);
 if(start<transcript.length)record(transcript.length);
 return sections;
}
function voicePlacements(transcript,visuals,sections){
 const placements=Array(visuals.length).fill(-1);
 const used=new Set();
 const normalized=transcript.toLowerCase();
 const substantive=sections.map((section,index)=>section.terms.size>=4?index:-1).filter(index=>index>=0);
 const candidates=substantive.length?substantive:sections.map((_,index)=>index);
 const frequencies=new Map();
 for(const section of sections){
  for(const term of section.terms)frequencies.set(term,(frequencies.get(term)||0)+1);
 }
 for(const [index,visual] of visuals.entries()){
  const cue=visual.cue.trim().toLowerCase();
  if(!cue)continue;
  const start=normalized.indexOf(cue);
  if(start<0)continue;
  const section=sections.findIndex(({end})=>end>=start+cue.length);
  if(section>=0){placements[index]=section;used.add(section);}
 }
 const matches=[];
 for(const [index,visual] of visuals.entries()){
  if(placements[index]>=0)continue;
  const options=[];
  for(const [section,entry] of sections.entries()){
   let score=0;
   for(const term of entry.terms){
    score+=(visual.terms.get(term)||0)/(1+0.4*(frequencies.get(term)-1));
   }
   if(entry.terms.size<4)score*=0.8;
   if(score>=3)options.push({section,score});
  }
  options.sort((a,b)=>b.score-a.score);
  if(options.length)matches.push({index,options});
 }
 matches.sort((a,b)=>b.options[0].score-a.options[0].score);
 for(const {index,options} of matches){
  const best=options[0];
  const choice=options.find(option=>!used.has(option.section)&&option.score>=best.score*0.7)||best;
  placements[index]=choice.section;
  used.add(choice.section);
 }
 // If a turn has no meaningful lexical overlap, spread the remaining figures
 // over substantive speech in their original order using any matched neighbors.
 for(const [index,section] of placements.entries()){
  if(section>=0)continue;
  const before=placements.slice(0,index).findLast(value=>value>=0)??-1;
  const after=placements.slice(index+1).find(value=>value>=0)??sections.length;
  const inOrder=candidates.filter(value=>value>before&&value<after&&!used.has(value));
  const unused=candidates.filter(value=>!used.has(value));
  const choices=inOrder.length?inOrder:unused.length?unused:candidates;
  const target=candidates[Math.floor((index+0.5)*candidates.length/visuals.length)];
  const nearest=choices.reduce((best,value)=>Math.abs(value-target)<Math.abs(best-target)?value:best);
  placements[index]=nearest;
  used.add(nearest);
 }
 return placements;
}
function placeVoiceVisuals(transcript,visuals){
 const sections=voiceSections(transcript);
 if(!sections.length)return visuals.map(visual=>visual.block).join('\n\n');
 const placements=voicePlacements(transcript,visuals,sections);
 const slots=new Map();
 for(const [index,visual] of visuals.entries()){
  const end=sections[placements[index]].end;
  if(!slots.has(end))slots.set(end,[]);
  slots.get(end).push(visual.block);
 }
 const parts=[];
 let previous=0;
 for(const [end,blocks] of [...slots].sort(([a],[b])=>a-b)){
  parts.push(transcript.slice(previous,end).trim(),...blocks);
  previous=end;
 }
 parts.push(transcript.slice(previous).trim());
 return parts.filter(Boolean).join('\n\n');
}
function addToVoiceReply(message,visual){
 if(!message.voiceVisuals){
  message.voiceVisuals=[];
  message.voiceTranscript=message.content;
 }
 if(message.voiceVisuals.length>=3||message.voiceVisuals.some(item=>item.block===visual.block))return;
 message.voiceVisuals.push(visual);
 message.content=placeVoiceVisuals(message.voiceTranscript,message.voiceVisuals);
}
function appendVoiceTranscript(message,text){
 if(!message.voiceVisuals){message.content+=text;return;}
 message.voiceTranscript+=text;
 message.content=placeVoiceVisuals(message.voiceTranscript,message.voiceVisuals);
}
const svgNS='http://www.w3.org/2000/svg',colors=['#6f59c7','#168b97','#dc8957','#598777','#be6080'];
function validate(raw){
 if(typeof raw!=='string'||raw.length>32000)return null;let v;try{v=JSON.parse(raw);}catch{return null;}
 const num=(x,min,max)=>typeof x==='number'&&Number.isFinite(x)&&x>=min&&x<=max;
 const str=(x,max)=>typeof x==='string'&&x.length<=max;
 if(!v||Array.isArray(v)||v.version!==1||!str(v.title,120)||!str(v.description,500)||!num(v.duration,1,30)||!Array.isArray(v.objects)||v.objects.length<1||v.objects.length>24)return null;
 for(const o of v.objects){
  if(!o||Array.isArray(o)||!['wave','line','arrow','circle','rect','text'].includes(o.type))return null;
  if(o.color!==undefined&&(typeof o.color!=='string'||!/^#[0-9a-f]{6}$/i.test(o.color)))return null;
  if(o.label!==undefined&&!str(o.label,100))return null;
  if(o.motion!==undefined&&(!o.motion||Array.isArray(o.motion)||!['dx','dy','phase'].every(k=>o.motion[k]===undefined||num(o.motion[k],-640,640))))return null;
  const fields=o.type==='wave'?['y','amplitude','cycles','speed']:o.type==='circle'?['x','y','r']:o.type==='rect'?['x','y','width','height']:o.type==='text'?['x','y']:['x','y','x2','y2'];
  if(!fields.every(k=>num(o[k],-1024,1024)))return null;
  if(o.type==='wave'&&(!num(o.amplitude,0,160)||!num(o.cycles,0.1,10)||!num(o.speed,-5,5)))return null;
  if(o.type==='circle'&&!num(o.r,1,150))return null;
  if(o.type==='rect'&&(!num(o.width,1,640)||!num(o.height,1,320)))return null;
  if(o.type==='text'&&!str(o.text,120))return null;
 }
 return v;
}
function make(spec){
 const el=(name,cls,text)=>{const n=document.createElement(name);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;};
 const svg=(name,attrs)=>{const n=document.createElementNS(svgNS,name);for(const [k,v] of Object.entries(attrs))n.setAttribute(k,String(v));return n;};
 const panel=el('section','star-visual'),header=el('div','star-visual-header'),title=el('strong','',spec.title),description=el('p','star-visual-description',spec.description);
 const stage=svg('svg',{viewBox:'0 0 640 320',role:'img','aria-label':spec.title+': '+spec.description});stage.classList.add('star-visual-stage');
 const controls=el('div','star-visual-controls'),toggle=el('button','',root.t?root.t('暂停动画'):'Pause'),label=el('label','',root.t?root.t('速度'):'Speed'),slider=el('input');toggle.type='button';slider.type='range';slider.min='0.25';slider.max='2';slider.step='0.25';slider.value='1';label.append(slider);controls.append(toggle,label);header.append(title);panel.append(header,description,stage,controls);
 const animated=spec.objects.some(o=>o.type==='wave'?o.speed!==0:o.motion&&(o.motion.dx||o.motion.dy));
 if(!animated)controls.remove();
 const reduced=typeof matchMedia==='function'&&matchMedia('(prefers-reduced-motion: reduce)').matches;let running=animated&&!reduced,last=0,time=0,frame=0;toggle.textContent=running?(root.t?root.t('暂停动画'):'Pause'):(root.t?root.t('播放动画'):'Play');
 const shapes=spec.objects.map((o,i)=>{const color=o.color||colors[i%colors.length],group=svg('g',{});stage.append(group);let shape;
  if(o.type==='wave'){shape=svg('path',{fill:'none',stroke:color,'stroke-width':4,'stroke-linecap':'round'});group.append(shape);}
  else if(o.type==='line'||o.type==='arrow'){shape=svg('line',{x1:o.x,y1:o.y,x2:o.x2,y2:o.y2,stroke:color,'stroke-width':4,'stroke-linecap':'round'});group.append(shape);if(o.type==='arrow'){const a=Math.atan2(o.y2-o.y,o.x2-o.x),size=12,points=[[o.x2,o.y2],[o.x2-size*Math.cos(a-0.5),o.y2-size*Math.sin(a-0.5)],[o.x2-size*Math.cos(a+0.5),o.y2-size*Math.sin(a+0.5)]];group.append(svg('polygon',{points:points.map(p=>p.join(',')).join(' '),fill:color}));}}
  else if(o.type==='circle'){shape=svg('circle',{cx:o.x,cy:o.y,r:o.r,fill:color,'fill-opacity':0.18,stroke:color,'stroke-width':3});group.append(shape);}
  else if(o.type==='rect'){shape=svg('rect',{x:o.x,y:o.y,width:o.width,height:o.height,rx:8,fill:color,'fill-opacity':0.2,stroke:color,'stroke-width':3});group.append(shape);}
  else{shape=svg('text',{x:o.x,y:o.y,fill:color,'font-size':18});shape.textContent=o.text;group.append(shape);}
  if(o.label){const x=o.type==='wave'?20:o.type==='rect'?o.x:o.x,y=o.type==='wave'?o.y-45:o.type==='rect'?o.y-9:o.y-12;const txt=svg('text',{x,y,fill:color,'font-size':15,'font-weight':600});txt.textContent=o.label;group.append(txt);}
  return {o,shape,group};
 });
 function draw(){for(const {o,shape,group} of shapes){const phase=time*2*Math.PI/spec.duration;if(o.motion){const f=Math.sin(phase+(o.motion.phase||0));group.setAttribute('transform',`translate(${(o.motion.dx||0)*f} ${(o.motion.dy||0)*f})`);}if(o.type==='wave'){let d='';for(let x=20;x<=620;x+=5){const y=o.y-o.amplitude*Math.sin((x-20)/600*2*Math.PI*o.cycles-phase*o.speed);d+=(x===20?'M':'L')+x+' '+y.toFixed(2)+' ';}shape.setAttribute('d',d);}}}
 function tick(now){frame=0;if(!panel.isConnected)return;if(running){if(last)time+=Math.min(0.1,(now-last)/1000)*Number(slider.value);last=now;draw();frame=requestAnimationFrame(tick);}}
 toggle.onclick=()=>{running=!running;last=0;toggle.textContent=running?(root.t?root.t('暂停动画'):'Pause'):(root.t?root.t('播放动画'):'Play');if(running&&!frame)frame=requestAnimationFrame(tick);};draw();if(running)frame=requestAnimationFrame(tick);return panel;
}
function enhance(element,ready=true){
 let count=0;
 for(const code of element.querySelectorAll('pre > code.language-star-visual, pre > code.language-star-explain')){
  const animation=code.classList.contains('language-star-visual');
  const spec=ready&&(animation?validate:validateExplanation)(code.textContent);
  if(spec&&count++<3)code.parentElement.replaceWith(animation?make(spec):makeExplanation(spec));
  else code.parentElement.remove();
 }
}
const api={conversationInstruction,instruction,explanationInstruction,voiceVisual,placeVoiceVisuals,addToVoiceReply,appendVoiceTranscript,validate,validateExplanation,enhance};root.StarVisuals=api;if(typeof module==='object'&&module.exports)module.exports=api;
})(globalThis);
