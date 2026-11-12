'use strict';
// Model output is untrusted. Parse Markdown, then sanitize before touching the DOM.
function renderMarkdown(element, text, citations = []) {
  let source=text;
  const links=[];
  for(const c of citations) {
    let url;
    try { url=new URL(c.url); } catch { continue; }
    if(!['http:','https:'].includes(url.protocol))continue;
    const number=links.length+1;
    links.push({number,url:url.href,title:String(c.title||url.hostname),end:Number(c.end_index)});
  }
  // Add citation markers at the provider's character offsets, from back to front.
  for(const link of [...links].sort((a,b)=>b.end-a.end)) {
    if(Number.isInteger(link.end)&&link.end>=0&&link.end<=source.length)
      source=source.slice(0,link.end)+` [${link.number}](${link.url.replace(/[()\s]/g,encodeURIComponent)})`+source.slice(link.end);
  }
  const html=marked.parse(source,{gfm:true,breaks:true});
  element.innerHTML=DOMPurify.sanitize(html,{FORBID_TAGS:['img','style','iframe','form'],FORBID_ATTR:['style'],ALLOWED_URI_REGEXP:/^(?:(?:https?):|#)/i});
  for(const a of element.querySelectorAll('a')) { a.target='_blank'; a.rel='noopener noreferrer'; }
  if(links.length){
    const list=document.createElement('div');list.className='web-citations';
    for(const link of links){const a=document.createElement('a');a.href=link.url;a.target='_blank';a.rel='noopener noreferrer';a.textContent=`[${link.number}] ${link.title}`;list.append(a);}
    element.append(list);
  }
}
