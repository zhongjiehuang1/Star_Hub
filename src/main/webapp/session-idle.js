'use strict';
// Session activity comes only from visible, trusted user input or detected speech.
const StarSessionIdle=(()=>{
  function create({owner,revision,visible,now,requestActivity,requestStatus,onExpired,onCountdown=()=>{},onRecovered=async()=>false,schedule=setTimeout,cancel=clearTimeout}){
    const throttleMs=2000;let token=0,timer=null,trailing=null,inflight=null,lastSent=-Infinity,lastInput=0,lastInputSpeech=false,activitySerial=0,expired=false,available=null;
    let deadline=null,countdownTimer=null;
    function renderCountdown(){
      if(countdownTimer!==null)cancel(countdownTimer);
      countdownTimer=null;
      if(available===false||!owner()||(!expired&&deadline===null)){onCountdown(null);return;}
      const remaining=expired?0:Math.max(0,deadline-now());
      const minutes=Math.ceil(remaining/60000);
      onCountdown({minutes,state:expired?'expired':minutes?'active':'checking'});
      if(remaining>0)countdownTimer=schedule(renderCountdown,remaining-(minutes-1)*60000);
    }
    function confirmRemaining(remaining){
      deadline=now()+remaining;
      renderCountdown();
      arm(remaining);
    }
    function disable(){available=false;deadline=null;clearTimers();renderCountdown();}
    function clearTimers(){if(timer!==null)cancel(timer);if(trailing!==null)cancel(trailing);timer=trailing=null;}
    function identity(){return {email:owner(),revision:revision(),token};}
    function current(id){return Boolean(id.email)&&id.email===owner()&&id.revision===revision()&&id.token===token&&!expired;}
    function arm(remaining){if(timer!==null)cancel(timer);timer=schedule(()=>{timer=null;check();},Math.max(100,Math.min(600000,remaining+100)));}
    function expire(id){if(!current(id))return;expired=true;clearTimers();renderCountdown();onExpired(id.email);}
    async function check(){
      if(available===false)return;
      const id=identity(),serial=activitySerial;
      if(expired){
        if(!id.email)return;
        try{const result=await requestStatus();if(!expired||id.token!==token||id.email!==owner()||id.revision!==revision())return;if(result?.disabled===true){disable();return;}
          if(result?.signedIn===true&&result.email===id.email&&Number.isFinite(result.remainingMs)&&result.remainingMs>0)await onRecovered(id.email);
        }catch{}return;
      }
      if(!current(id))return;
      try{const result=await requestStatus();if(!current(id)||serial!==activitySerial)return;if(result?.disabled===true){disable();return;}
        if(result?.signedIn===false){available=true;expire(id);return;}
        if(result?.signedIn!==true||typeof result.email!=='string'||!result.email||!Number.isFinite(result.remainingMs)||result.remainingMs<=0)throw Error('Invalid session state');
        available=true;if(inflight||trailing!==null){arm(2500);return;}
        if(result.email!==id.email){expire(id);return;}
        confirmRemaining(result.remainingMs);
      }catch{if(current(id)&&serial===activitySerial)arm(15000);}
    }
    async function send(){
      if(inflight||(!visible()&&!lastInputSpeech)||!owner()||expired)return;
      const id=identity(),serial=activitySerial,age=Math.max(0,Math.min(5000,Math.floor(now()-lastInput)));
      if(age>=5000){check();return;}
      const flight={};inflight=flight;lastSent=now();
      try{const result=await requestActivity(id.email,age);if(!current(id))return;
        if(result?.signedIn===false){expire(id);return;}
        if(result?.signedIn!==true||result.email!==id.email||!Number.isFinite(result.remainingMs)||result.remainingMs<=0)throw Error('Invalid activity response');
        if(serial===activitySerial)confirmRemaining(result.remainingMs);
      }catch{if(current(id))check();}
      finally{if(inflight===flight)inflight=null;if(current(id)&&serial!==activitySerial)queue();}
    }
    function queue(){if((!visible()&&!lastInputSpeech)||!owner()||expired)return;if(trailing!==null)cancel(trailing);const delay=Math.max(0,throttleMs-(now()-lastSent));const id=identity();trailing=schedule(()=>{trailing=null;if(current(id))send();},delay);}
    function activity(trusted,speech=false){if(available!==true||!trusted||(!visible()&&!speech)||!owner()||expired)return;lastInput=now();lastInputSpeech=speech;activitySerial++;queue();}
    function sync(){token++;expired=false;deadline=null;clearTimers();renderCountdown();lastSent=-Infinity;inflight=null;if(owner())check();}
    function onVisibility(){if(visible()&&owner()){renderCountdown();check();}else if(!lastInputSpeech&&trailing!==null){cancel(trailing);trailing=null;}}
    return {activity,check,sync,onVisibility};
  }
  if(typeof document==='undefined')return {create};
  let noticeTimer=null;
  function notice(message){const element=document.getElementById('sessionNotice');if(!element)return;clearTimeout(noticeTimer);element.textContent=t(message);element.hidden=false;noticeTimer=setTimeout(()=>{element.hidden=true;},5000);}
  async function activityRequest(email,age){const response=await sendAccountPost('api/account',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-Starhub-Client':'web'},body:JSON.stringify({action:'userActivity',expectedEmail:email,activityAgeMs:age})});if(!response.ok){const error=Error('Session activity failed');error.status=response.status;throw error;}return response.json();}
  async function statusRequest(){const response=await fetch('api/account?sessionStatus=1',{cache:'no-store',credentials:'same-origin'});if(response.status===404)return {disabled:true};if(!response.ok)throw Error('Session status unavailable');return response.json();}
  let countdown=null;
  function displayCountdown(value){
    countdown=value;
    const badge=document.getElementById('accountSessionBadge');
    if(!badge)return;
    badge.hidden=!value;
    if(!value)return;
    badge.dataset.state=value.state;
    badge.querySelector('.session-countdown').textContent=t('{0} 分钟',value.minutes);
    const status=badge.querySelector('.session-state');
    const label=t({active:'生效中',expired:'已过期',checking:'确认中'}[value.state]);
    if(status.textContent!==label)status.textContent=label;
  }
  document.addEventListener('starhub:languagechanged',()=>displayCountdown(countdown));
  const manager=create({onCountdown:displayCountdown,owner:()=>accountEmail,revision:()=>accountIdentityRevision,visible:()=>!document.hidden,now:()=>performance.now(),requestActivity:activityRequest,requestStatus:statusRequest,onExpired:email=>{const voiceInterrupted=typeof expireRealtime==='function'&&expireRealtime();requestReauthentication(email,voiceInterrupted);},onRecovered:async email=>{
    if(reauthEmail!==email||!await restoreAccount()||accountEmail!==email)return false;
    cloudFailed=false;await saveCloudChats();reauthEmail='';if($('accountDialog').open)$('accountDialog').close();showError('');notice('登录成功');return true;
  }});
  for(const name of ['pointerdown','keydown','wheel','touchstart']){
    document.addEventListener(name,event=>{
      // An explicit sign-out ends the session; it should never renew it.
      if(event.target?.closest?.('#accountLogout'))return;
      manager.activity(event.isTrusted);
    },{capture:true,passive:true});
  }
  document.addEventListener('visibilitychange',manager.onVisibility);
  document.addEventListener('DOMContentLoaded',()=>manager.sync(),{once:true});
  let channel=null;try{if(typeof BroadcastChannel!=='undefined'){channel=new BroadcastChannel('starhub.account-session');channel.onmessage=event=>{if(event.data?.type==='changed')manager.check();};}}catch{}
  function changed(){try{channel?.postMessage({type:'changed'});}catch{}}
  return {...manager,notice,changed,create};
})();
if(typeof module!=='undefined')module.exports=StarSessionIdle;
