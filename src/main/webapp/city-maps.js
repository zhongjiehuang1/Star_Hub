'use strict';
// A deliberately narrow intent: a city name and an explicit request for a map.
// Do not send the full conversation, model text, addresses, or arbitrary URLs.
const StarCityMaps=(()=>{
  const cache=new Map(),pending=[];let active=0;
  function lookup(query){
    if(cache.has(query))return cache.get(query);
    const promise=new Promise((resolve,reject)=>{pending.push({query,resolve,reject});pump();});
    cache.set(query,promise);promise.catch(()=>{if(cache.get(query)===promise)cache.delete(query);});
    if(cache.size>100)cache.delete(cache.keys().next().value);
    return promise;
  }
  function pump(){
    while(active<2&&pending.length){
      const work=pending.shift();active++;
      const controller=new AbortController();let timeout;
      const request=fetch(`api/maps/cities?q=${encodeURIComponent(work.query)}`,{headers:{Accept:'application/json'},signal:controller.signal})
        .then(response=>{if(!response.ok)throw Error('map lookup failed');return response.json();})
      const deadline=new Promise((_,reject)=>{timeout=setTimeout(()=>{controller.abort();reject(Error('map lookup timed out'));},12000);});
      Promise.race([request,deadline]).then(work.resolve,work.reject).finally(()=>{clearTimeout(timeout);active--;pump();});
    }
  }
  function queryFor(text){
    if(typeof text!=='string'||text.length>100||/[\r\n]/.test(text))return '';
    let value=text.trim().replace(/[?？!！。]+$/u,'').trim();
    let match=value.match(/^(?:(?:请|麻烦|帮我|给我|给我看|帮我看|看看|看一下|看|查看|显示|展示|打开|搜索|查询|找一下|找|来一张|一张|一幅)\s*)*([\u3400-\u9fff]{2,24}?)(?:的)?地图$/u);
    if(match){value=match[1];return /(?:路线|导航|景点|酒店|餐厅|附近|位置|街道|小区|地址|家|公司|画|生成|一张|一幅)/u.test(value)?'':value;}
    match=value.match(/^(?:(?:please\s+)?(?:show(?:\s+me)?|display|open|find)\s+(?:me\s+)?(?:a\s+)?)?(?:a\s+)?map\s+of\s+([\p{L}][\p{L}\p{M}'’, .-]{0,49})$/iu)
      ||value.match(/^([\p{L}][\p{L}\p{M}'’, .-]{0,49})\s+map$/iu);
    if(!match)return '';
    value=match[1].trim();
    return /\b(?:directions|route|hotel|restaurant|near|address|street|home|office)\b/i.test(value)?'':value;
  }
  function validChoice(value){return typeof value==='string'&&/^\d{1,12}$/.test(value)?value:'';}
  function validMatch(item){
    return Boolean(item&&validChoice(String(item.id))&&typeof item.label==='string'&&item.label.length>0&&item.label.length<=160&&typeof item.latitude==='number'&&Number.isFinite(item.latitude)&&item.latitude>=-85&&item.latitude<=85&&typeof item.longitude==='number'&&Number.isFinite(item.longitude)&&item.longitude>=-180&&item.longitude<=180);
  }
  function create(message,query,{onChange=()=>{},canUpdate=()=>true,locale=()=> 'en'}={}){
    const box=document.createElement('section');box.className='city-map';box.setAttribute('aria-label',locale()==='zh'?'城市地图':'City map');
    const status=document.createElement('p');status.className='city-map-status';status.setAttribute('role','status');box.append(status);
    const label=(zh,en)=>locale()==='zh'?zh:en;
    let map=null,observer=null,target=null,point=null,tileNotice=null;
    function stopMap(){observer?.disconnect();observer=null;if(map){map.remove();map=null;}}
    function startMap(){
      if(map||!target||!point||!box.isConnected)return;
      if(typeof L==='undefined'){tileNotice.hidden=false;return;}
      try{
        map=L.map(target,{scrollWheelZoom:false}).setView([point.latitude,point.longitude],11);
        const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{
          minZoom:1,maxZoom:19,keepBuffer:1,updateWhenIdle:true,referrerPolicy:'strict-origin-when-cross-origin',
          attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        });
        const failedTiles=new Set();
        tiles.on('tileerror',event=>{failedTiles.add(event.tile);tileNotice.hidden=false;});
        const clearFailure=event=>{failedTiles.delete(event.tile);tileNotice.hidden=failedTiles.size===0;};
        tiles.on('tileload',clearFailure);tiles.on('tileunload',clearFailure);tiles.addTo(map);
      }catch(error){if(map){map.remove();map=null;}tileNotice.hidden=false;}
    }
    box.cityMapSuspend=stopMap;
    box.cityMapResume=()=>{
      if(!target||!box.isConnected)return;
      if(map){map.invalidateSize({pan:false});return;}
      if(observer)return;
      if(typeof IntersectionObserver==='undefined'){startMap();return;}
      observer=new IntersectionObserver(entries=>{if(entries.some(entry=>entry.isIntersecting)){observer?.disconnect();observer=null;startMap();}});
      observer.observe(target);
    };
    status.textContent=label('正在查找地图…','Finding map…');
    const show=(items,id=message.mapChoice)=>{
      const chosen=items.find(item=>String(item.id)===validChoice(id));
      if(!chosen)return;
      stopMap();box.querySelector('.city-map-view')?.remove();
      const view=document.createElement('div');view.className='city-map-view';
      const title=document.createElement('div');title.className='city-map-title';title.textContent=chosen.label;view.append(title);
      target=document.createElement('div');target.className='city-map-frame';target.setAttribute('role','region');target.setAttribute('aria-label',label(`${chosen.label}地图`,`Map of ${chosen.label}`));target.tabIndex=0;point=chosen;view.append(target);
      tileNotice=document.createElement('p');tileNotice.className='city-map-tile-notice';tileNotice.hidden=true;tileNotice.textContent=label('部分地图内容暂时无法加载，可通过下方链接查看。','Some map content could not be loaded. Use the link below to open it.');view.append(tileNotice);
      const credit=document.createElement('div');credit.className='city-map-credit';
      const attribution=document.createElement('a');attribution.href='https://www.openstreetmap.org/copyright';attribution.target='_blank';attribution.rel='noopener noreferrer';attribution.textContent='© OpenStreetMap contributors';
      const placeSource=document.createElement('a');placeSource.href='https://www.geonames.org/';placeSource.target='_blank';placeSource.rel='noopener noreferrer';placeSource.textContent=label('地点：GeoNames（CC BY 4.0）','Places: GeoNames (CC BY 4.0)');
      const external=document.createElement('a');external.href=`https://www.openstreetmap.org/#map=11/${chosen.latitude}/${chosen.longitude}`;external.target='_blank';external.rel='noopener noreferrer';external.textContent=label('在 OpenStreetMap 中打开','Open in OpenStreetMap');credit.append(attribution,placeSource,external);view.append(credit);box.append(view);
      box.cityMapResume();
    };
    function load(){
    box.querySelector('.city-map-retry')?.remove();status.textContent=label('正在查找地图…','Finding map…');
    lookup(query)
      .then(data=>{
        if(!data||!Array.isArray(data.matches)||data.matches.length>5)throw Error('invalid map result');
        const items=data.matches.filter(validMatch).map(item=>({...item,id:String(item.id)}));
        if(!items.length){status.textContent=label('没有找到这个城市的地图，请补充省份或国家。','City not found. Add a state, province, or country.');return;}
        if(items.length===1&&!data.truncated){status.textContent='';show(items,items[0].id);return;}
        status.textContent=data.truncated?label('匹配地点较多，请补充省份或国家后重试。','There are many matches. Add a state, province, or country.'):label('找到多个同名城市，请选择：','Several cities match. Choose one:');
        const select=document.createElement('select');select.className='city-map-select';select.setAttribute('aria-label',label('选择城市','Choose a city'));
        const placeholder=document.createElement('option');placeholder.value='';placeholder.textContent=label('选择城市','Choose a city');select.append(placeholder);
        for(const item of items){const option=document.createElement('option');option.value=item.id;option.textContent=item.label;select.append(option);}
        select.value=items.some(item=>item.id===validChoice(message.mapChoice))?message.mapChoice:'';
        select.addEventListener('change',()=>{if(!canUpdate())return;message.mapChoice=validChoice(select.value);stopMap();box.querySelector('.city-map-view')?.remove();target=null;point=null;if(message.mapChoice)show(items);onChange();});
        box.append(select);if(select.value)show(items);
      }).catch(()=>{status.textContent=label('地图暂时不可用，请稍后重试。','The map is temporarily unavailable. Please try again later.');const retry=document.createElement('button');retry.type='button';retry.className='city-map-retry';retry.textContent=label('重试','Retry');retry.addEventListener('click',load);box.append(retry);});
    }
    load();
    return box;
  }
  return {queryFor,validChoice,validMatch,create};
})();
if(typeof module!=='undefined')module.exports=StarCityMaps;
