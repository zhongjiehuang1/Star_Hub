'use strict';
// Route only explicit requests to create an image; ordinary questions stay in chat.
(function(root){
function chineseRequest(text){
 return text.trim().replace(/^(?:(?:请你|请|麻烦|帮我|给我|可以|能否|能不能|我想要|我想|我希望|要|帮忙)\s*)+/,'');
}
function englishRequest(text){
 return text.trim().replace(/^(?:(?:please|can you|could you|would you|i want you to)\s+)*/i,'');
}
function diagramRequested(text){
 const zh=chineseRequest(text),en=englishRequest(text);
 if(/^(?:不要|无需|别|不必|禁止)/.test(zh)||/^(?:do not|don't|never)\b/i.test(en))return false;
 if(/(?:图片文件|位图|\b(?:png|jpe?g|webp)\b)/i.test(text))return false;
 return /^(?:(?:再|继续|重新)\s*)?(?:画图|绘图|图解|图示)(?:\s*[。！!？?]?$|\s*(?:讲解|解释|说明|演示)|[:：])/.test(zh)
  || /^(?:用|通过|画|绘制|做|给|生成|制作)[\s\S]{0,35}?(?:图|图表|图示|图片)\s*(?:来|并)?\s*(?:讲解|解释|说明|演示)(?![^，。！？]*的(?:海报|插画|封面|壁纸))/.test(zh)
  || /^(?:把|将|用|画|绘制|生成|制作|做|给|解释|讲解|换|再来|再给)[\s\S]{0,80}?(?:流程图|示意图|时序图|结构图|图解|思维导图)(?!标志|图标|海报|封面)/.test(zh)
  || /^(?:(?:explain|illustrate|show|walk me through)\b[\s\S]{0,100}?\b(?:with|using|in)\s+(?:an?\s+|the\s+)?|(?:draw|make|create|give me|show me|sketch|generate)\s+(?:me\s+)?(?:an?\s+|the\s+)?)(?:(?:labeled|labelled|inline|simple|visual|process)\s+)*(?:diagram|flow\s?chart|schematic|mind map)\b/i.test(en)
  || /^(?:draw it|illustrate it)\s*[.!?]?$/i.test(en);
}
function requested(text){
 if(typeof text!=='string')return false;
 const value=text.trim();
 if(!value)return false;
 const start=chineseRequest(value);
 if(diagramRequested(value))return false;
 // A request for source code, an explanation, or a prompt belongs in chat;
 // these words may also describe content that should appear in an image.
 if(/^(?:如何|怎么|怎样)/.test(start)||/^(?:(?:please|can you|could you|would you)\s+)*how\s+(?:do|to|can|does)\b/i.test(value))return false;
 if(/^(?:用|使用|通过)\s*(?:svg|html|css)\b/i.test(start)||/^(?:生成|制作|创建|写|编写|输出|提供|给我)\s*(?:一段|一份|一个|一套|些)?\s*(?:(?:svg|html|css)\s*)?(?:代码|源码|源代码|提示词|教程)/i.test(start))return false;
 if(/^(?:生成|制作|创建|写|编写|输出|提供)[\s\S]{0,120}(?:图|图片|图像|图画|插画)(?:的)?(?:提示词|代码|源码|源代码)(?:[。！!？?\s]|$)/.test(start))return false;
 if(/^(?:(?:please|can you|could you|would you)\s+)*(?:write|produce|generate|create|make|give me)\s+(?:(?:me|the|an?|some)\s+)*(?:(?:svg|html|css)\s+)?(?:code|source|markup|prompt)\b/i.test(value))return false;
 if(/^画(?:龙点睛|蛇添足)(?:是什么意思|是什么含义|的意思|怎么理解|如何理解|[，,。；;？?\s]|$)/.test(start)||/^(?:(?:please|can you|could you|would you)\s+)*draw\s+(?:(?:a|the)\s+)?conclusions?\b/i.test(value))return false;
 // A leading prohibition cancels the request. Negative constraints later in a
 // positive prompt (e.g. "画一张女孩图，背景不要画花") describe the image.
 if(/^(?:不要|无需|别|不必|禁止)\s*(?:(?:为我|帮我|给我)\s*)?(?:生成|制作|创建|画|绘制)/.test(start)||/^(?:(?:please|can you|could you|would you)\s+)*(?:do not|don't|never)\s+(?:draw|paint|illustrate|generate|create|make)\b/i.test(value))return false;
 if(/^(?:画|绘制|画出|画个|画一|画张|画幅)/.test(start))return true;
 if(/^(?:生成|制作|创建|做|设计|给我|来)[\s\S]{0,160}(?:图|画|照片|画像|头像|壁纸|海报|封面)/.test(start))return true;
 if(/^(?:把|将)[\s\S]{0,120}(?:画成|做成|制成|生成(?:为)?)[\s\S]{0,20}(?:图片|图像|插画|海报|封面)/.test(start))return true;
 if(/^(?:(?:please)\s+)?(?:i\s+(?:want|would like)|i['’]d like)\s+(?:an?\s+)?(?:image|picture|photo(?:graph)?|illustration|portrait|artwork|drawing|poster|wallpaper)(?:\s+(?:of|with|showing|depicting|featuring|that\s+(?:shows|depicts|features))\b|[.!?]?\s*$)/i.test(value))return true;
 if(/^(?:(?:please|can you|could you|would you)\s+)*(?:draw|paint|illustrate)\s+\S/i.test(value))return true;
 if(/^turn\b[\s\S]{0,120}\binto\s+(?:an?\s+)?(?:image|picture|poster|illustration)\b/i.test(englishRequest(value)))return true;
 return /^(?:(?:please|can you|could you|would you)\s+)*(?:generate|create|make)\b[\s\S]{0,160}\b(?:image|picture|photo(?:graph)?|illustration|portrait|artwork|drawing|poster|wallpaper)\b/i.test(value);
}
// Inspect only the current conversation, and only completed assistant image
// results. The caller must still verify and load any referenced image bytes.
function hasImage(message){
 if(!message||message.role!=='assistant'||message.incomplete===true)return false;
 return [...(Array.isArray(message.mediaRefs)?message.mediaRefs:[]),...(Array.isArray(message.media)?message.media:[])].some(item=>item&&item.type==='image'&&(item.fileId||item.mediaRef||item.url));
}
function priorPrompts(messages,sourceIndex){
 const found=[];
 for(let i=sourceIndex;i>=0;i--){
  if(!hasImage(messages[i]))continue;
  const user=messages[i-1];if(!user||user.role!=='user'||typeof user.content!=='string')break;
  const text=user.content.trim();if(!text)break;
  found.unshift(text);
  // A fresh image request starts a new subject, so older image prompts do not
  // bleed into this one. "again" and edits build on the same subject.
  if(requested(text))break;
 }
 return found;
}
function followupKind(text){
 const value=text.trim();if(!value)return '';
 const start=value.replace(/^(?:(?:请你|请|麻烦|帮我|给我|可以|能否|能不能|我想要|我想|我希望|帮忙)\s*)+/,'');
 if(/^(?:不要|无需|别|不必|禁止)\s*(?:(?:再|继续)\s*)?(?:生成|制作|创建|画|绘制|修改|编辑|更改|调整|换)/.test(start)||/^(?:(?:please|can you|could you|would you)\s+)*(?:do not|don't|never)\s+(?:generate|create|draw|make|edit|change|modify|alter)\b/i.test(value))return '';
 if(/^(?:如何|怎么|怎样|为什么|是否|能否解释)/.test(start)||/^(?:(?:please|can you|could you|would you)\s+)*(?:how|why|what|explain|describe|tell me)\b/i.test(value))return '';
 const en=englishRequest(value);
 const replace=/^(?:(?:再|另|换)\s*(?:来|给我)?|(?:再|继续|重新)\s*(?:帮我|给我)?\s*做)\s*(?:一|另)?\s*(?:张|幅|个)\s*(?:吧|看看)?(?:[，,。！!？?]|$)/;
 if(replace.test(start)||/^(?:(?:give|show|make|draw|paint|generate|create|try)\s+(?:me\s+)?)?(?:another one|one more(?: one)?)(?:\s*,|\s*[.!?]?\s*$)/i.test(en))return 'contextual-variation';
 if(/^(?:(?:再|继续|重新)\s*(?:帮我|给我)?\s*(?:生成|画|绘制|做|来)|(?:再|另|换)\s*(?:来|生成|画)?)\s*(?:一|另|几)?\s*(?:张|幅|个|些)?\s*(?:图片|图像|图画|插画|照片|头像|海报|图(?!解|示|表))/.test(start)
  || /^(?:再|继续|重新)\s*(?:帮我|给我)?\s*(?:生成|画|绘制)\s*(?:一|另|几)?\s*(?:张|幅)\s*(?:[，,。！!？?]|$)/.test(start))return 'variation';
 if(/^(?:(?:generate|create|make|draw|paint)\s+(?:me\s+)?)?(?:another|one more)\s+(?:image|picture|photo|illustration|portrait|drawing)\b/i.test(en))return 'variation';
 // Modify commands are deliberately anchored; "这张图片是什么" and
 // comments about editing must stay in the normal conversation path.
 if(/^(?:(?:把|将)\s*)?(?:我上传的|上传的|附件|这张|上张|上一张|刚才(?:的)?(?:那张)?|原图|图片|图像|图里|画里|画面|背景|人物|女孩|男孩|她|他|它|头发|颜色|衣服|天空|风格|尺寸|构图|亮度|光线|色调|左边|右边|上面|下面|中间)[\s\S]{0,160}(?:改成|改为|换成|换为|变成|变为|染成|调整为|调整成|更改为|去掉|移除|删除|增加|添加|加上|换掉|调亮|调暗|调深|调浅|缩小|放大|变亮|变暗|修改|编辑|替换|换个|换一|换背景)/.test(start))return 'edit';
 if(/^(?:把|将)?\s*(?:背景|头发|衣服|颜色|风格|光线|画面|人物|女孩|男孩)[\s\S]{0,100}(?:改|换|变|加|去掉|移除|调整)/.test(start))return 'edit';
 if(/^(?:给(?:她|他|它|女孩|男孩|这个人物|这张图|图片)|让(?:她|他|它|人物|女孩|男孩|背景|画面))[\s\S]{0,100}(?:戴|穿|加|增加|添加|换|变|改|更|微笑|笑|闭眼|睁眼|站|坐|转身|挥手|露出)/.test(start)||/^(?:改成|改为|换成|换为|变成|变为)(?:[\s\S]{1,100})$/.test(start)||/^不要(?:背景|文字|水印|帽子|花|树|边框|人物)(?:了|[。！!\s]|$)/.test(start))return 'edit';
 if(/^(?:修改|编辑|调整|更改|美化|替换|移除|去掉|添加|加上|加个|加一|换掉|换个|换一)[\s\S]{0,120}(?:图片|图像|图|画面|背景|人物|女孩|男孩|头发|颜色|衣服|天空|风格|光线|色调|文字|眼睛|帽子|花|树)/.test(start))return 'edit';
 if(/^(?:(?:please|can you|could you|would you)\s+)*(?:edit|modify|alter|change|replace|remove|add|make)\s+(?:the\s+|this\s+|that\s+|her\s+|his\s+|its\s+|a\s+|an\s+)?(?:image|picture|photo|illustration|background|hair|clothes|dress|sky|color|colour|style|lighting|hat|flowers?|eyes?|face|person|girl|boy|it)\b/i.test(value))return 'edit';
 if(/^(?:(?:please|can you|could you|would you)\s+)*(?:give\s+(?:her|him|the girl|the boy)\s+(?:a|an)\s+|make\s+(?:her|him|the girl|the boy)\s+(?:smile|laugh|stand|sit|wear)\b)/i.test(value))return 'edit';
 return '';
}
function needsSubject(text){
 const zh=chineseRequest(text),en=englishRequest(text);
 return /^(?:画|绘制|生成|制作|创建|做|来)\s*(?:一?[张幅个]\s*)?(?:图|图片|图像|图画|插画|照片|海报)(?:\s*(?:来|并)?\s*(?:讲解|解释|说明|演示))?[。！!？?\s]*$/.test(zh)
  || /^(?:用|通过)\s*(?:一张\s*)?(?:图|图片|图示)\s*(?:讲解|解释|说明|演示)[。！!？?\s]*$/.test(zh)
  || /(?:上面|上述|前面|刚才|上一条|前一条)(?:的|讲的|说的|内容|那个|这个|提到的)/.test(zh)
  || /^(?:draw|paint|illustrate|generate|create|make|give me|i want|i would like|i['’]d like)\s+(?:me\s+)?(?:an?\s+|the\s+)?(?:image|picture|photo|illustration|drawing|poster|diagram)[.!?\s]*$/i.test(en)
  || /\b(?:the above|previous (?:answer|message|explanation)|what (?:we|you) (?:just )?(?:discussed|explained|said)|(?:draw|illustrate) it)\b/i.test(en);
}
function recentImageContext(messages){
 return messages.slice(-6).flatMap(message=>{
  if(!message||message.incomplete||hasImage(message)||message.jobId||!['user','assistant'].includes(message.role)||typeof message.content!=='string')return [];
  const content=message.content.replace(/^```star-(?:visual|explain)[^\n]*\n[\s\S]*?^```[ \t]*$/gm,'').trim();
  if(!content||/^(?:image generated\.?|图片已生成[。.]?|已生成图片[。.]?)$/i.test(content)||(message.role==='user'&&needsSubject(content)))return [];
  return [{role:message.role,content:content.slice(0,2000)}];
 });
}
function resolve(text,messages,{hasAttachment=false,imageTask=false}={}){
 if(typeof text!=='string'||!Array.isArray(messages))return null;
 const diagram=diagramRequested(text);
 if(diagram&&!imageTask)return null;
 let kind=followupKind(text);
 const latestAssistant=messages.findLastIndex(message=>message?.role==='assistant');
 if(kind==='contextual-variation'){
  // Inline diagrams and text replies use the chat context for "another one".
  if(!imageTask&&!hasImage(messages[latestAssistant]))return null;
  kind='variation';
 }
 let sourceIndex=-1;for(let i=messages.length-1;i>=0;i--)if(hasImage(messages[i])){sourceIndex=i;break;}
 if(kind){
  const explicit=/(?:这张|上张|上一张|刚才(?:的)?(?:那张)?|原图|这幅|上幅|图片|图像|画面|this image|this picture|that image|that picture|previous image|previous picture|last image|last picture|the image|the picture|the photo)/i.test(text);
  // A background or color change can refer to code, a document, or another
  // topic. Without an image, leave vague edits to the conversational model.
  if(sourceIndex<0)return kind==='variation'||explicit||hasAttachment?{kind:'missing',intent:kind}:null;
  // A vague edit is contextual only when the latest assistant turn is the
  // image. An explicit reference to an image can reach an earlier turn.
  if(kind==='edit'&&latestAssistant!==sourceIndex&&!explicit)return null;
  return {kind,sourceIndex,prompts:priorPrompts(messages,sourceIndex)};
 }
 if(!requested(text)&&!(imageTask&&diagram))return null;
 if(needsSubject(text)){
  const context=recentImageContext(messages);
  return context.length?{kind:'new',context}:{kind:'missing-subject'};
 }
 return {kind:'new'};
}
function imageModel(catalog,provider){
 if(provider!=='openai'||!catalog||!Array.isArray(catalog.models))return null;
 const models=catalog.models.filter(m=>m.provider===provider&&m.execution!=='external'&&!m.batch&&Array.isArray(m.categories)&&m.categories.includes('image')&&Array.isArray(m.inputs)&&m.inputs.includes('text'));
 const preferred=catalog.featured?.preferredDefault?.[provider]?.image||catalog.featured?.primary?.[provider]?.image;
 return models.find(m=>m.id===preferred||m.uid===preferred)||models.find(m=>m.selectionTier==='flagship')||models[0]||null;
}
const api={requested,resolve,imageModel};root.StarImageIntent=api;if(typeof module==='object'&&module.exports)module.exports=api;
})(globalThis);
