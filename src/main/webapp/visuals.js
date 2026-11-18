'use strict';
// The model supplies bounded data only. It never supplies executable code or markup.
(function(root){
const instruction=`When a diagram with motion would help explain a changing process, or the user asks for a dynamic illustration, include one fenced star-visual JSON block alongside your normal answer. It is rendered as an interactive animation, so do not say you cannot create one. Use only this schema: {"version":1,"title":"short title","description":"what the animation represents","duration":6,"objects":[...]} in a 640 by 320 coordinate space. Allowed objects: {"type":"wave","label":"E","y":100,"amplitude":35,"cycles":2,"speed":1,"color":"#7258c7"}; {"type":"arrow","x":100,"y":150,"x2":180,"y2":150,"color":"#7258c7","label":"field"}; {"type":"line","x":100,"y":150,"x2":180,"y2":150,"color":"#7258c7"}; {"type":"circle","x":80,"y":120,"r":20,"color":"#7258c7","label":"charge"}; {"type":"rect","x":70,"y":100,"width":80,"height":40,"color":"#7258c7","label":"magnet"}; {"type":"text","x":100,"y":70,"text":"label","color":"#52436d"}. Objects except wave may optionally have "motion":{"dx":60,"dy":0,"phase":0} to oscillate sinusoidally. Wave speed is cycles per animation period; dx/dy are maximum displacements in pixels; phase is radians. At most 24 objects. Use valid JSON, no comments or code; colors must be hex. Choose diagrams and labels scientifically appropriate to the question. Omit the block when a visual would not help. Never use this format in JSON-only output.`;
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
 const reduced=typeof matchMedia==='function'&&matchMedia('(prefers-reduced-motion: reduce)').matches;let running=!reduced,last=0,time=0,frame=0;toggle.textContent=running?(root.t?root.t('暂停动画'):'Pause'):(root.t?root.t('播放动画'):'Play');
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
function enhance(element){for(const code of element.querySelectorAll('pre > code.language-star-visual')){const spec=validate(code.textContent);if(!spec)continue;code.parentElement.replaceWith(make(spec));}}
const api={instruction,validate,enhance};root.StarVisuals=api;if(typeof module==='object'&&module.exports)module.exports=api;
})(globalThis);
