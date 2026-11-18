"use strict";
// Isolated prototype adapter. The backend is deliberately not registered here.
function gmailProviderLabel(){
  if($('task').value!=='chat'||!$('model').value||selectedModel()?.execution==='external')return '';
  const p=providers.find(item=>item.id===$('provider').value);
  return p?`${p.name} · ${$('model').value}`:'';
}
(()=>{
  const open=$('openGmail'),dialog=$('gmailDialog'),container=$('gmailPluginRoot');let ui;
  const tr=(zh,en)=>language==='zh'?zh:en;
  function close(){ui?.clear();dialog.close();}
  function mount(){
    ui?.destroy();ui=StarGmailPlugin.mount(container,{
      identity:()=>({id:accountEmail,revision:accountIdentityRevision}),
      locale:()=>language,
      providerLabel:gmailProviderLabel,
      contextToken:()=>current?.id||'',
      confirmSummary:({provider,subject})=>confirm(tr(
        `将选中的邮件“${subject||'（无主题）'}”及当前对话上下文发送给 ${provider} 生成摘要？邮件内容会保存在当前对话中。`,
        `Send the selected email “${subject||'(No subject)'}” and the current conversation context to ${provider} for a summary? The email content will be saved in this conversation.`)),
      confirmDisconnect:()=>confirm(tr('断开 Gmail 并尝试撤销 Google 授权？','Disconnect Gmail and attempt to revoke Google access?')),
      confirmGoogleRevocation:()=>confirm(tr('你已在 Google 账户中撤销 Star Hub 的 Gmail 授权吗？确认后才能重新连接。','Have you revoked Star Hub Gmail access in your Google account? Confirm only after revoking it there.')),
      async sendSummary(text,context){
        if(!accountEmail||context.identity!==`${accountEmail}\u0000${accountIdentityRevision}`||(context.provider!==gmailProviderLabel()||context.contextToken!==current?.id))throw Error('Account, conversation or model changed');
        if(busy||cloudHydrating||!ready||publicInsecure||!current||current.messages.length>=59||!$('apiKey').value.trim()||text.length>30000){
          alert(tr(text.length>30000?'邮件内容过长，无法发送摘要。':'请先选择可用的文字对话模型，并等待当前任务完成。',text.length>30000?'This email is too long to summarize.':'Choose an available text model and wait for any current task to finish.'));return;
        }
        close();const event=new Event('submit',{bubbles:true,cancelable:true});Object.defineProperty(event,'gmailSummary',{value:{...context,text}});$('chatForm').dispatchEvent(event);
      }
    });
  }
  open.addEventListener('click',()=>{mount();dialog.showModal();ui.refresh();});
  $('closeGmail').addEventListener('click',close);
  dialog.addEventListener('close',()=>ui?.clear());
  document.addEventListener('starhub:account-changed',()=>{if(dialog.open){close();}});
  document.addEventListener('starhub:languagechanged',()=>{if(dialog.open){mount();ui.refresh();}});
})();
