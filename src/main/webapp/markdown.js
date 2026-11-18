'use strict';
// Model output is untrusted: Markdown is sanitized before trusted local KaTeX renders math.
function markdownImageUrl(value) {
  if(typeof value!=='string'||value.length>4096||!/^https:\/\//i.test(value))return null;
  try {
    const url=new URL(value),host=url.hostname.toLowerCase();
    if(url.protocol!=='https:'||url.username||url.password||url.port||host.includes(':')||!host.includes('.')||/^(?:\d+\.){3}\d+$/.test(host)||/(?:^|\.)(?:localhost|local|internal|test|invalid|onion)\.?$/.test(host))return null;
    return url.href;
  } catch { return null; }
}
// Treat model-written SVG as an image, never as active markup in the page.
function markdownSvgImage(value) {
  if(typeof value!=='string'||value.length>200000||!/^\s*<svg(?:\s|>)/i.test(value)||!/<\/svg>\s*$/i.test(value)||/<!DOCTYPE|<!ENTITY/i.test(value))return null;
  try {
    const clean=DOMPurify.sanitize(value,{USE_PROFILES:{svg:true},FORBID_TAGS:['script','style','foreignObject','image','use','a','animate','animateMotion','animateTransform','set','filter','feImage'],FORBID_ATTR:['style','href','xlink:href']});
    const parsed=new DOMParser().parseFromString(clean,'image/svg+xml'),root=parsed.documentElement;
    if(root.localName!=='svg'||parsed.querySelector('parsererror'))return null;
    root.setAttribute('xmlns','http://www.w3.org/2000/svg');
    return 'data:image/svg+xml;charset=utf-8,'+encodeURIComponent(new XMLSerializer().serializeToString(root));
  } catch { return null; }
}
function renderMarkdown(element, text, citations = [], options = {}) {
  let source=String(text||'');
  const links=[];
  for(const c of citations) {
    let url;
    try { url=new URL(c.url); } catch { continue; }
    if(!['http:','https:'].includes(url.protocol))continue;
    const number=links.length+1;
    links.push({number,url:url.href,title:String(c.title||url.hostname),end:Number(c.end_index)});
  }
  for(const link of [...links].sort((a,b)=>b.end-a.end)) {
    if(Number.isInteger(link.end)&&link.end>=0&&link.end<=source.length)
      source=source.slice(0,link.end)+` [${link.number}](${link.url.replace(/[()\s]/g,encodeURIComponent)})`+source.slice(link.end);
  }
  const formulas=new Map(),images=new Map(),nonce=Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('');
  const token=(raw,math,display)=>({type:display?'starMathBlock':'starMathInline',raw,math,display});
  const placeholder=value=>{
    const id=`${nonce}-${formulas.size}`;formulas.set(id,value);
    return `<span data-star-math="${id}"></span>`;
  };
  const block={name:'starMathBlock',level:'block',start(src){const n=src.search(/(?:^|\n)\s{0,3}(?:\\\[|\$\$)/);return n<0?undefined:n;},tokenizer(src){
    const found=/^ {0,3}(?:\\\[([\s\S]*?)\\\]|\$\$([\s\S]*?)\$\$)[ \t]*(?:\n|$)/.exec(src);
    if(found)return token(found[0],found[1]??found[2],true);
  },renderer(value){return `<div class="math-block">${placeholder(value)}</div>\n`;}};
  const inline={name:'starMathInline',level:'inline',start(src){const n=src.search(/\\\(|\$(?!\$|\s)/);return n<0?undefined:n;},tokenizer(src){
    const found=/^\\\(([\s\S]*?)\\\)/.exec(src)||/^\$(?!\$|\s)((?:\\.|[^$\n])+?)(?<!\s)\$(?!\$)/.exec(src);
    if(found)return token(found[0],found[1],false);
  },renderer:placeholder};
  // CommonMark treats a closing CJK bracket before ** as punctuation; when
  // followed immediately by Chinese text it can leave the emphasis literal.
  // Tokenize this narrow form inline, so code spans, fenced code and links
  // continue to be handled by Marked before their contents are inspected.
  const cjkStrong={name:'starCjkStrong',level:'inline',start(src){const n=src.indexOf('**');return n<0?undefined:n;},tokenizer(src){
    const found=/^\*\*(?=\S)([^\n*]*[）】」』］])\*\*(?=[\u3400-\u9fff])/.exec(src);
    if(found)return {type:'starCjkStrong',raw:found[0],tokens:this.lexer.inlineTokens(found[1])};
  },renderer(value){return '<strong>'+this.parser.parseInline(value.tokens)+'</strong>';}};
  const parser=new marked.Marked({gfm:true,breaks:true});parser.use({extensions:[block,inline,cjkStrong],renderer:{code(value){
    if(options.images===false||images.size>=12||!['','svg','xml','html'].includes(String(value.lang||'').trim().toLowerCase()))return false;
    const url=markdownSvgImage(value.text);if(!url)return false;
    const id=`${nonce}-${images.size}`;images.set(id,{url,svgSource:value.text,alt:t('模型生成的图片')});return `<span data-star-image="${id}"></span>`;
  },image(value){
    const alt=String(value.text||'').slice(0,1000);
    if(images.size>=12)return alt.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const id=`${nonce}-${images.size}`;images.set(id,{url:markdownImageUrl(value.href),alt});return `<span data-star-image="${id}"></span>`;
  }}});
  element.innerHTML=DOMPurify.sanitize(parser.parse(source),{FORBID_TAGS:['img','style','iframe','form'],FORBID_ATTR:['style'],ALLOWED_URI_REGEXP:/^(?:(?:https?):|#)/i});
  for(const node of element.querySelectorAll('[data-star-image]')){
    const item=images.get(node.getAttribute('data-star-image'));
    if(!item){node.remove();continue;}
    if(!item.url||options.images===false){node.replaceWith(document.createTextNode(item.alt));continue;}
    if(item.svgSource){
      const container=document.createElement('div'),img=document.createElement('img'),details=document.createElement('details'),summary=document.createElement('summary'),pre=document.createElement('pre'),code=document.createElement('code');
      img.className='markdown-image';img.alt=item.alt;img.loading='lazy';img.decoding='async';img.src=item.url;
      summary.textContent=t('查看 SVG 源代码');code.textContent=item.svgSource;pre.append(code);details.append(summary,pre);container.append(img,details);node.replaceWith(container);continue;
    }
    const a=document.createElement('a'),img=document.createElement('img');
    a.href=item.url;a.target='_blank';a.rel='noopener noreferrer';
    img.className='markdown-image';img.alt=item.alt||t('图片');img.loading='lazy';img.decoding='async';img.crossOrigin='anonymous';img.referrerPolicy='no-referrer';
    img.addEventListener('error',()=>{a.textContent=`${img.alt} — ${t('图片无法加载，打开原图 ↗')}`;},{once:true});
    a.append(img);node.replaceWith(a);img.src=item.url;
  }
  for(const node of element.querySelectorAll('[data-star-math]')){
    const item=formulas.get(node.getAttribute('data-star-math'));node.removeAttribute('data-star-math');
    if(!item)continue;
    if(typeof katex==='undefined'||item.math.length>10000){node.textContent=item.math;continue;}
    try{katex.render(item.math,node,{displayMode:item.display,throwOnError:false,trust:false,maxExpand:1000,maxSize:20,strict:'ignore'});}catch{node.textContent=item.math;}
  }
  for(const a of element.querySelectorAll('a')) { a.target='_blank'; a.rel='noopener noreferrer'; }
  if(links.length){
    const list=document.createElement('div');list.className='web-citations';
    for(const link of links){const a=document.createElement('a');a.href=link.url;a.target='_blank';a.rel='noopener noreferrer';a.textContent=`[${link.number}] ${link.title}`;list.append(a);}
    element.append(list);
  }
}
