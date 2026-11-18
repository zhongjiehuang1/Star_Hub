'use strict';
// Keep live voice controls compact without changing other task controls.
(function(){
 let wasRealtime=false;
 function sync(){
  const task=document.getElementById('task'),options=document.getElementById('taskOptions'),panel=document.getElementById('realtimeDetails');
  if(!task||!options||!panel)return;
  const realtime=task.value==='realtime';
  options.classList.toggle('realtime-options',realtime);
  if(realtime!==wasRealtime){options.open=!realtime;if(realtime)panel.open=false;}
  wasRealtime=realtime;
 }
 document.addEventListener('starhub:modelchanged',sync);
})();
