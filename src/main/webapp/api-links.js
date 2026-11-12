'use strict';
function apiAddress(label,url,{method='',template=false}={}){
  const box=element('div',undefined,'api-address');
  const heading=element('div',undefined,'api-address-heading');heading.append(element('span',label));
  if(method)heading.append(element('code',method,'api-method'));box.append(heading);
  const line=element('div',undefined,'api-address-line');
  const target=element(!template&&/^https:\/\//.test(url)?'a':'code',url);
  if(target.tagName==='A'){target.href=url;target.target='_blank';target.rel='noopener noreferrer';}
  const copy=element('button',t("复制"));copy.type='button';copy.setAttribute('aria-label',t("复制{0}",label));
  copy.onclick=async()=>{try{await navigator.clipboard.writeText(url);copy.textContent=t("已复制");}catch{copy.textContent=t("请选择网址复制");}setTimeout(()=>copy.textContent=t("复制"),1800);};
  line.append(target,copy);box.append(line);
  if(template)box.append(element('small',t("花括号内的标识在实际请求中替换为官方 API 返回的任务或文件 ID。")));
  return box;
}
function appendPlatformLinks(root,p){
  if(!p)return;
  for(const [label,key]of [[t("官方网站 / 控制台"),'website'],[t("申请 / 管理 API Key"),'keyUrl'],[t("API 基础地址"),'baseUrl'],[t("官方 API 文档"),'docsUrl']])if(p[key])root.append(apiAddress(label,p[key]));
  if(p.checkUrl)root.append(apiAddress(t('连接检查地址'),p.checkUrl,{method:'GET'}));
  if(p.note)root.append(element('p',p.note,'api-note'));
}
function appendRoutes(root,m,task){
  const entries=task?[[task,m.apiRoutes?.[task]||[]]]:Object.entries(m.apiRoutes||{});
  for(const [kind,routes]of entries){
    for(const route of routes)root.append(apiAddress(`${catalog.categories[kind]||kind} · ${route.label}`,route.url,route));
  }
}
function appendModelApiLinks(root,m){
  root.append(element('h3',t("实际 API 网址与密钥入口")));
  root.append(element('p',t("模型厂商：{0}；官方厂商：{1}。",m.vendorName,m.providerName),'api-note'));
  appendRoutes(root,m);appendPlatformLinks(root,providers.find(p=>p.id===m.provider));
  for(const [name,url]of [[t("平台模型目录 / 接口说明"),m.source],[t("模型厂商官方文档"),m.officialSource]])if(url)root.append(apiAddress(name,url));
}
function refreshCurrentApi(){
  const m=selectedModel(),p=providers.find(p=>p.id===$('provider').value);let route=m?.apiRoutes?.[$('task').value]?.[0];if($('task').value==='chat'&&$('webSearch').checked&&!$('webSearch').disabled&&((m?.provider==='deepseek'&&m.id==='deepseek-v4-pro')||(m?.provider==='xai'&&m.id==='grok-4.7')))route={method:'POST',url:p.baseUrl+'/responses'};
  $('browseApis').disabled=!catalog;$('currentApiUrl').textContent=route?`${route.method} · ${route.url}`:t("请选择模型以查看实际调用地址");
  $('currentApiUrl').removeAttribute('href');if(route?.url.startsWith('https://'))$('currentApiUrl').href=route.url;
  $('currentKeyUrl').textContent=p?.keyUrl?t("密钥入口 · {0}",p.keyUrl):'';
  $('currentKeyUrl').removeAttribute('href');if(p?.keyUrl)$('currentKeyUrl').href=p.keyUrl;
}
function showApiDirectory(){
  const m=selectedModel(),root=$('apiCurrent');root.replaceChildren();
  if(m){
    root.append(element('h3',t("当前模型 · {0}",m.name)),element('p',t("模型厂商：{0} / 官方厂商：{1}",m.vendorName,m.providerName),'api-note'));
    appendRoutes(root,m,$('task').value);appendPlatformLinks(root,providers.find(p=>p.id===m.provider));
    const realtime=$('task').value==='realtime';
    const relay=new URL(realtime?'realtime':m.batch?'api/batch':'api/run',document.baseURI);if(realtime)relay.protocol=location.protocol==='https:'?'wss:':'ws:';
    root.append(apiAddress(t("本站转发入口"),relay.href,{method:realtime?'WebSocket':'POST'}));
    root.append(element('p',t("请求先到本站转发入口，再由服务器发送到上方平台接口。网址不包含你的 API Key。"),'api-note'));
  }
  const directory=$('apiDirectory');directory.replaceChildren();
  for(const p of providers){const section=element('details',undefined,'api-platform');const title=element('summary',p.name);if(p.baseUrl)title.append(element('span',new URL(p.baseUrl).hostname));section.append(title);appendPlatformLinks(section,p);directory.append(section);}
  $('apiDialog').showModal();
}
document.addEventListener('starhub:modelchanged',refreshCurrentApi);
$('browseApis').addEventListener('click',showApiDirectory);
$('closeApis').addEventListener('click',()=>$('apiDialog').close());
if(catalog)refreshCurrentApi();

$('webSearch').addEventListener('change',refreshCurrentApi);
