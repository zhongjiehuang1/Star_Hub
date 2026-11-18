const {test}=require('node:test'),assert=require('node:assert/strict');
const {requested,resolve,imageModel}=require('../../main/webapp/image-intent.js');
test('routes direct image creation requests and leaves questions or code in chat',()=>{
 for(const text of ['生成一张女孩图','生成图片','请画一只小猫','请你画一只小猫','draw a cat','帮我生成一张风景图片','create an image of a girl','Please draw a picture of a cat','生成一张女孩图，背景不要画花','画一只猫，不要画狗','draw a cat without drawing a dog'])assert.equal(requested(text),true,text);
 for(const text of ['怎么生成一张女孩图','不要画一只小猫','请不要帮我生成图片','给我生成 SVG 代码','解释图像生成原理','how to create an image','这张图片里是什么',"please don't draw a cat",'do not generate an image'])assert.equal(requested(text),false,text);
});
test('distinguishes drawing requests from idioms, conclusions and requests for code',()=>{
 for(const text of ['画龙点睛是什么意思？','请解释画蛇添足是什么意思','画蛇添足是什么意思？','Draw a conclusion about why the sky is blue.','Please draw conclusions from these figures.','生成 SVG 代码','请用 SVG 画一个猫','生成图片提示词','生成一张图片的提示词','生成女孩图的提示词','生成图片的代码','generate SVG code','create a prompt for a cat'])assert.equal(requested(text),false,text);
 for(const text of ['生成一张女孩图，她穿着写有 CODE 的衣服','生成一张图片，画面是一个 SVG 标志','Generate an image of a computer showing code.','Please draw a poster with the word HTML on it','画一个解释画龙点睛的海报'])assert.equal(requested(text),true,text);
});
test('recognizes explicit English image wishes without treating questions or prompts as generation',()=>{
 for(const text of ['I want an image of a cat',"I'd like a picture of a cat",'I’d like a picture of a cat without flowers','I would like a photo showing a dog','I want an image of a computer showing code','I want a picture','I want an image of a cat, do not draw flowers'])assert.equal(requested(text),true,text);
 for(const text of ["I don't want an image of a cat",'I want an image prompt for a cat','I want an image explained','I want to know how to generate an image','Can you tell me how to create an image?'])assert.equal(requested(text),false,text);
});
test('image model selection uses only integrated OpenAI models',()=>{
 const catalog={models:[{id:'external',provider:'openai',categories:['image'],inputs:['text'],execution:'external'},{id:'image',provider:'openai',categories:['image'],inputs:['text'],selectionTier:'flagship'}]};
 assert.equal(imageModel(catalog,'openai').id,'image');assert.equal(imageModel(catalog,'gemini'),null);assert.equal(imageModel({models:[]},'openai'),null);
});

function image(){return {role:'assistant',content:'Image generated.',mediaRefs:[{type:'image',fileId:'11111111-1111-4111-8111-111111111111'}]};}
test('resolves another image and edits against the latest completed image in this conversation',()=>{
 const messages=[{role:'user',content:'画一个女孩'},image()];
 for(const text of ['再生成一张','请再来一张','重新生成一张','再画一张，背景是蓝色','Generate another image','One more picture']){
  const result=resolve(text,messages);assert.equal(result?.kind,'variation',text);assert.equal(result.sourceIndex,1);assert.deepEqual(result.prompts,['画一个女孩']);
 }
 for(const text of ['把背景改成蓝色','背景换成蓝色','把她的头发改成红色','把头发染成红色','让她微笑','把图里的人换成男孩','给女孩加个帽子','加个帽子','给她戴一个帽子','不要背景了','改成蓝色','修改这张图片，把衣服变白','Make the background blue','Please add a hat','Make her smile','Give her a hat']){
  const result=resolve(text,messages);assert.equal(result?.kind,'edit',text);assert.equal(result.sourceIndex,1);
 }
});
test('keeps ordinary questions, negation, incomplete images, and other conversations from triggering paid edits',()=>{
 const messages=[{role:'user',content:'画一个女孩'},image()];
 for(const text of ['这张图片是什么？','请描述这张图片','背景是什么颜色？','不要生成图片','请不要再生成图片','不要修改背景','How do I edit this image?','What is in the image?','画龙点睛是什么意思？'])assert.equal(resolve(text,messages),null,text);
 assert.deepEqual(resolve('再生成一张',[]),{kind:'missing',intent:'variation'});
 assert.equal(resolve('背景换成蓝色',[{role:'assistant',incomplete:true,mediaRefs:[{type:'image',fileId:'id'}]}]),null);
 assert.deepEqual(resolve('把这张图片的背景换成蓝色',[]),{kind:'missing',intent:'edit'});
 assert.equal(resolve('背景换成蓝色',[{role:'user',content:'写一个网页'},{role:'assistant',content:'这是网页代码'}]),null);
 assert.deepEqual(resolve('背景换成蓝色',[],{hasAttachment:true}),{kind:'missing',intent:'edit'});
 assert.deepEqual(resolve('把我上传的图片背景改成蓝色',[],{hasAttachment:true}),{kind:'missing',intent:'edit'});
 assert.equal(resolve('把上一张图换背景',messages)?.kind,'edit');
 assert.equal(resolve('这张图片里是什么？',[],{hasAttachment:true}),null);
 assert.equal(resolve('请描述这张图片',[],{hasAttachment:true}),null);
 assert.deepEqual(resolve('画一只猫',[]),{kind:'new'});
 const afterText=[...messages,{role:'user',content:'解释一下'},{role:'assistant',content:'说明'}];
 assert.equal(resolve('背景换成蓝色',afterText),null);
 assert.equal(resolve('把上一张图片的背景换成蓝色',afterText)?.kind,'edit');
});
test('a follow-up uses the latest image and its image prompt chain, stopping at a new subject',()=>{
 const messages=[{role:'user',content:'画一只狗'},image(),{role:'user',content:'画一个女孩'},image(),{role:'user',content:'把背景改成蓝色'},image(),{role:'user',content:'再生成一张'},image()];
 const result=resolve('给她戴一个帽子',messages);
 assert.equal(result.sourceIndex,7);assert.deepEqual(result.prompts,['画一个女孩','把背景改成蓝色','再生成一张']);
 assert.equal(resolve('再生成一张',[...messages,{role:'assistant',incomplete:true,content:''}]).sourceIndex,7);
});
test('long image histories and descriptions retain the original subject for length validation',()=>{
 const original='画一个女孩，'+ '紫'.repeat(4500),messages=[{role:'user',content:original},image()];
 for(let i=0;i<8;i++)messages.push({role:'user',content:'把背景改成颜色'+i},image());
 const result=resolve('再生成一张',messages);assert.equal(result.prompts.length,9);assert.equal(result.prompts[0],original);assert.equal(result.prompts.at(-1),'把背景改成颜色7');
});
