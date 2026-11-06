'use strict';
let language=StarStore.preference('language','en')==='zh'?'zh':'en';
const localePatterns=[];
const reverseLocale=new Map(Object.entries(UI_EN).map(([zh,en])=>[en,zh]));
function escapeLocale(s){return s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');}
for(const [zh,en] of Object.entries(UI_EN))if(/\{\d+\}/.test(zh)){
  const compile=text=>{const indices=[];let last=0,pattern='^';for(const match of text.matchAll(/\{(\d+)\}/g)){pattern+=escapeLocale(text.slice(last,match.index))+'([\\s\\S]*?)';indices.push(Number(match[1]));last=match.index+match[0].length;}return {regex:new RegExp(pattern+escapeLocale(text.slice(last))+'$'),indices};};
  localePatterns.push({zh,en,fromZh:compile(zh),fromEn:compile(en)});
}
function t(key,...args){
  const original=reverseLocale.get(key)||key;
  const template=language==='en'?(UI_EN[original]||key):original;
  return template.replace(/\{(\d+)\}/g,(all,index)=>index<args.length?String(args[index]):all);
}
function uiText(value){
  if(typeof value!=='string'||!value)return value;
  const leading=value.match(/^\s*/)[0],trailing=value.match(/\s*$/)[0],text=value.trim();
  if(Object.hasOwn(UI_EN,text)||reverseLocale.has(text))return leading+t(text)+trailing;
  for(const rule of localePatterns){
    const from=language==='en'?rule.fromZh:rule.fromEn,match=from.regex.exec(text);
    if(match){const args=[];from.indices.forEach((index,i)=>args[index]=match[i+1]);return leading+t(rule.zh,...args)+trailing;}
  }
  return value;
}
function errorText(message){
  let value=uiText(String(message||''));
  if(language==='en'&&/[\u3400-\u9fff]/.test(value)){
    for(const [zh,en] of Object.entries(UI_EN).filter(([k])=>!k.includes('{')).sort((a,b)=>b[0].length-a[0].length))if(zh.length>1)value=value.split(zh).join(en);
  }
  return value;
}
function translateInterface(root=document.body){
  const skip='script,style,textarea,pre,code,[data-no-i18n],.message-content,.history-chat,.generated-media';
  const walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT);const nodes=[];
  while(walker.nextNode())if(!walker.currentNode.parentElement?.closest(skip))nodes.push(walker.currentNode);
  for(const node of nodes){const value=uiText(node.nodeValue);if(value!==node.nodeValue)node.nodeValue=value;}
  for(const node of root.querySelectorAll('[placeholder],[title],[aria-label],[data-prompt]')){
    if(node.closest('[data-no-i18n],.message-content,pre'))continue;
    for(const name of ['placeholder','title','aria-label','data-prompt'])if(node.hasAttribute(name)){const old=node.getAttribute(name),value=uiText(old);if(value!==old)node.setAttribute(name,value);}
  }
  document.documentElement.lang=language==='en'?'en':'zh-CN';document.title=t('Star Hub · 让灵感连接更多可能');
}
function localizedCatalog(raw){
  const copy=structuredClone(raw);
  const fields=['name','vendorName','family','providerName','description','accessLabel','status','executionNote','workbenchNote','keyLabel','parameterNotice','coverage','noKeyNotice','label','help','note'];
  function visit(value){if(Array.isArray(value)){for(const row of value)if(row&&typeof row==='object')visit(row);return;}
    if(!value||typeof value!=='object')return;
    for(const [key,item]of Object.entries(value)){
      if(typeof item==='string'&&fields.includes(key))value[key]=uiText(item);
      else if(Array.isArray(item)&&['tags','subcategories'].includes(key))value[key]=item.map(uiText);
      else if(item&&typeof item==='object')visit(item);
    }
  }
  visit(copy);for(const key of Object.keys(copy.categories))copy.categories[key]=uiText(copy.categories[key]);
  if(language==='en')for(const model of copy.models){const source=raw.models.find(m=>m.uid===model.uid);if(source.descriptionEn)model.description=source.descriptionEn;}
  return copy;
}
function setLanguage(next){
  language=next==='zh'?'zh':'en';for(const id of ['language','setupLanguage']){const control=document.getElementById(id);if(control){control.value=language;control.setAttribute('aria-label',language==='en'?'Interface language':'界面语言');}}StarStore.write('language',language);translateInterface();
  document.dispatchEvent(new CustomEvent('starhub:languagechanged'));
}
document.addEventListener('DOMContentLoaded',()=>{translateInterface();for(const id of ['language','setupLanguage']){const control=document.getElementById(id);control.value=language;control.setAttribute('aria-label',t('界面语言'));control.addEventListener('change',()=>setLanguage(control.value));}});
