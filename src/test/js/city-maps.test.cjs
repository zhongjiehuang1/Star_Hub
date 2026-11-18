const {test}=require('node:test');
const assert=require('node:assert/strict');
const maps=require('../../main/webapp/city-maps.js');

test('only explicit city map requests yield a small location query',()=>{
  for(const [input,query] of [['安徽省滁州市地图','安徽省滁州市'],['请展示谢菲尔德的地图','谢菲尔德'],['show me a map of Sheffield','Sheffield'],['Sheffield map','Sheffield']])assert.equal(maps.queryFor(input),query);
  for(const input of ['帮我查询天气','我家南京路1号地图','我的公司地图','酒店附近地图','安徽地图怎么画','画一张地图','A map of Sheffield\nand my password'])assert.equal(maps.queryFor(input),'');
});
test('map coordinates are constrained to verified numeric data',()=>{
  const item={id:'123',label:'Sheffield, England',latitude:53.38,longitude:-1.47};
  assert.equal(maps.validMatch(item),true);assert.equal(maps.validMatch({...item,latitude:'53.38'}),false);assert.equal(maps.validMatch({...item,longitude:181}),false);assert.equal(maps.validMatch({...item,id:'https://evil.example'}),false);
});

class Node{
  constructor(tag){this.tagName=tag;this.children=[];this.attributes={};this.parent=null;this.value='';this.textContent='';this.isConnected=true;}
  append(...nodes){for(const node of nodes){node.parent=this;this.children.push(node);}}
  setAttribute(name,value){this.attributes[name]=value;}
  querySelector(selector){const name=selector.slice(1);for(const child of this.children){if(child.className===name)return child;const match=child.querySelector?.(selector);if(match)return match;}return null;}
  remove(){if(this.parent)this.parent.children=this.parent.children.filter(c=>c!==this);}
  addEventListener(name,fn){this[name]=fn;}
}
global.document={createElement:tag=>new Node(tag)};
const mapsCreated=[],tilesCreated=[];
global.L={map(target,options){const instance={target,options,removed:false,setView(coords,zoom){this.coords=coords;this.zoom=zoom;return this;},invalidateSize(){},remove(){this.removed=true;}};mapsCreated.push(instance);return instance;},tileLayer(url,options){const layer={url,options,on(event,handler){this[event]=handler;return this;},addTo(map){this.map=map;return this;}};tilesCreated.push(layer);return layer;}};
const tick=()=>new Promise(resolve=>setTimeout(resolve,0));
test('deduplicates lookups and renders a lazy raster map with origin-only tile referrer',async()=>{
  let calls=0;global.fetch=async()=>{calls++;return {ok:true,json:async()=>({matches:[{id:'100',label:'Sheffield, England',latitude:53.38,longitude:-1.47}]})};};
  const message={},first=maps.create(message,'Sheffield'),second=maps.create({},'Sheffield');await tick();
  assert.equal(calls,1);assert.equal(message.mapChoice,undefined);assert.ok(first.querySelector('.city-map-frame'));assert.ok(second.querySelector('.city-map-frame'));
  const frame=first.querySelector('.city-map-frame');assert.equal(frame.tagName,'div');assert.equal(tilesCreated.at(-1).url,'https://tile.openstreetmap.org/{z}/{x}/{y}.png');assert.equal(tilesCreated.at(-1).options.referrerPolicy,'strict-origin-when-cross-origin');assert.equal(tilesCreated.at(-1).options.keepBuffer,1);
  assert.equal(mapsCreated.at(-1).options.scrollWheelZoom,false);
  const count=mapsCreated.length;first.cityMapResume();assert.equal(mapsCreated.length,count);first.cityMapSuspend();assert.equal(mapsCreated.at(-2).removed,true);first.cityMapResume();assert.equal(mapsCreated.length,count+1);
});
test('maps load tiles only when visible and release the instance when suspended',async()=>{
  let notify;global.IntersectionObserver=class {constructor(fn){notify=fn;}observe(){}disconnect(){}};
  try{
    global.fetch=async()=>({ok:true,json:async()=>({matches:[{id:'707',label:'Newcastle, England',latitude:54.97,longitude:-1.61}]})});
    const count=mapsCreated.length,box=maps.create({},'Newcastle');await tick();assert.equal(mapsCreated.length,count);
    notify([{isIntersecting:false}]);assert.equal(mapsCreated.length,count);
    notify([{isIntersecting:true}]);assert.equal(mapsCreated.length,count+1);
    const layer=tilesCreated.at(-1),failedOne={},failedTwo={};layer.tileerror({tile:failedOne});layer.tileerror({tile:failedTwo});assert.equal(box.querySelector('.city-map-tile-notice').hidden,false);
    layer.tileunload({tile:failedOne});assert.equal(box.querySelector('.city-map-tile-notice').hidden,false);layer.tileload({tile:failedTwo});assert.equal(box.querySelector('.city-map-tile-notice').hidden,true);
    box.cityMapSuspend();assert.equal(mapsCreated.at(-1).removed,true);
  }finally{delete global.IntersectionObserver;}
});
test('multiple results require an explicit choice and detached conversations cannot update selection',async()=>{
  global.fetch=async()=>({ok:true,json:async()=>({matches:[{id:'201',label:'Springfield, Illinois',latitude:39.8,longitude:-89.6},{id:'202',label:'Springfield, Massachusetts',latitude:42.1,longitude:-72.6}]})});
  let allowed=true,saved=0;const message={},box=maps.create(message,'Springfield',{canUpdate:()=>allowed,onChange:()=>saved++});await tick();
  assert.equal(box.querySelector('.city-map-frame'),null);const select=box.querySelector('.city-map-select');select.value='202';allowed=false;select.change();assert.equal(message.mapChoice,undefined);assert.equal(saved,0);
  allowed=true;select.change();assert.equal(message.mapChoice,'202');assert.equal(saved,1);assert.ok(box.querySelector('.city-map-frame'));
});
test('unknown or unavailable lookup does not invent a map',async()=>{
  global.fetch=async()=>({ok:true,json:async()=>({matches:[]})});const missing=maps.create({},'NowhereCity');await tick();assert.equal(missing.querySelector('.city-map-frame'),null);assert.match(missing.querySelector('.city-map-status').textContent,/not found/i);
  global.fetch=async()=>({ok:false});const failed=maps.create({},'UnavailableCity');await tick();assert.equal(failed.querySelector('.city-map-frame'),null);assert.match(failed.querySelector('.city-map-status').textContent,/unavailable/i);
});
