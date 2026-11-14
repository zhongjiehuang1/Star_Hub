'use strict';
// Model output is untrusted: Markdown is sanitized before trusted local KaTeX renders math.
function renderMarkdown(element, text, citations = []) {
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
  const formulas=new Map(),nonce=Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('');
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
  const parser=new marked.Marked({gfm:true,breaks:true});parser.use({extensions:[block,inline]});
  element.innerHTML=DOMPurify.sanitize(parser.parse(source),{FORBID_TAGS:['img','style','iframe','form'],FORBID_ATTR:['style'],ALLOWED_URI_REGEXP:/^(?:(?:https?):|#)/i});
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
