'use strict';
(() => {
  let generation=0,currentSource='';
  function showRoute(){
    const m=selectedModel(),root=$('sourceRoute');root.replaceChildren();
    if(!m)return;
    root.append(element('strong',`${m.providerName} · ${m.name}`));
    appendRoutes(root,m,$('task').value);
  }
  async function getSource(url){
    const response=await fetch(url,{cache:'no-store',signal:AbortSignal.timeout(15000)});
    const value=await response.json();if(!response.ok)throw new Error(value.error||t("源码读取失败。"));return value;
  }
  async function loadFile(){
    const request=++generation;currentSource='';$('copySource').disabled=true;$('sourceText').textContent='';$('sourceHash').textContent='';$('sourceStatus').textContent=t("正在读取当前部署版本的源码…");
    try{
      const value=await getSource(`api/source?file=${encodeURIComponent($('sourceFile').value)}`);
      if(request!==generation)return;currentSource=value.content;$('sourceText').textContent=currentSource;
      $('sourceHash').textContent=`SHA-256 · ${value.sha256}`;$('sourceStatus').textContent=t("{0} · {1} 行 · 只读",value.name,currentSource.split('\n').length);
      $('sourceCode').scrollTop=0;$('sourceCode').scrollLeft=0;$('copySource').disabled=false;
    }catch(e){if(request===generation)$('sourceStatus').textContent=t("读取失败，请点击重新加载。");}
  }
  async function load(){
    const request=++generation;$('sourceStatus').textContent=t("正在读取源码清单…");$('sourceFile').disabled=true;$('copySource').disabled=true;$('sourceText').textContent='';$('sourceHash').textContent='';
    try{
      const value=await getSource('api/source');if(request!==generation)return;
      const previous=$('sourceFile').value;$('sourceFile').replaceChildren(...value.files.map(f=>new Option(f.name,f.name)));
      if(value.files.some(f=>f.name===previous))$('sourceFile').value=previous;
      $('sourceFile').disabled=false;await loadFile();
    }catch(e){if(request===generation)$('sourceStatus').textContent=t("读取失败，请点击重新加载。");}
  }
  $('viewCallSource').onclick=()=>{showRoute();$('sourceDialog').showModal();load();};
  $('closeSource').onclick=()=>$('sourceDialog').close();
  $('sourceDialog').addEventListener('close',()=>{generation++;});
  $('sourceFile').onchange=loadFile;$('reloadSource').onclick=load;
  $('copySource').onclick=async()=>{try{await navigator.clipboard.writeText(currentSource);$('sourceStatus').textContent=t("源码已复制。");}catch{$('sourceStatus').textContent=t("无法访问剪贴板，请选中源码复制。");}};
})();
