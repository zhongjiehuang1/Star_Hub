'use strict';
document.addEventListener('DOMContentLoaded',()=>{
  const notice=document.getElementById('cookieNotice');
  notice.hidden=StarStore.preference('cookieChoice','')==='necessary';
  document.getElementById('cookieSettings').addEventListener('click',()=>{notice.hidden=false;document.getElementById('acceptCookies').focus();});
  document.getElementById('acceptCookies').addEventListener('click',()=>{StarStore.write('cookieChoice','necessary');notice.hidden=true;});
});
