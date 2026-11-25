'use strict';
// One frame per pointer update; no continuous loop, network, layout reads or motion on touch.
(()=>{
  const reduce=matchMedia('(prefers-reduced-motion: reduce)'), fine=matchMedia('(hover: hover) and (pointer: fine)');
  let frame=0,target=null,x=0,y=0;
  function reset(){if(frame)cancelAnimationFrame(frame);frame=0;if(target){target.style.removeProperty('--sense-x');target.style.removeProperty('--sense-y');target=null;}}
  document.addEventListener('pointermove',event=>{
    if(document.hidden||reduce.matches||!fine.matches){reset();return;}
    const next=event.target.closest?.('.prompt-card,.model-card');
    if(next!==target){reset();target=next;}if(!target)return;
    x=event.clientX;y=event.clientY;
    if(!frame)frame=requestAnimationFrame(()=>{frame=0;if(!target||document.hidden)return;target.style.setProperty('--sense-x',x+'px');target.style.setProperty('--sense-y',y+'px');});
  },{passive:true});
  document.addEventListener('pointerleave',reset);document.addEventListener('visibilitychange',reset);
  reduce.addEventListener('change',reset);fine.addEventListener('change',reset);
})();
