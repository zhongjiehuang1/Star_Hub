const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const web=path.join(__dirname,'../../main/webapp');
const marked=require(path.join(web,'vendor/marked.min.js'));
const ctx=vm.createContext({marked,URL,DOMPurify:{sanitize:s=>s},crypto:{getRandomValues:a=>a.fill(1)},t:s=>s});
vm.runInContext(fs.readFileSync(path.join(web,'markdown.js'),'utf8'),ctx);
function render(text){const element={innerHTML:'',querySelectorAll:()=>[]};ctx.renderMarkdown(element,text);return element.innerHTML;}
test('renders CJK bold ending in a full-width closing bracket before Chinese text',()=>{
 assert.match(render('将利率上调 **0.25个百分点（25个基点）**至这一水平。'),/<strong>0\.25个百分点（25个基点）<\/strong>至这一水平。/);
 assert.match(render('**第一项【甲】**和**第二项（乙）**均已完成。'),/<strong>第一项【甲】<\/strong>和<strong>第二项（乙）<\/strong>均已完成。/);
 assert.match(render('**bold** and **普通粗体**正常'),/<strong>bold<\/strong> and <strong>普通粗体<\/strong>正常/);
});
test('keeps literal markers in inline and fenced code and in link URLs',()=>{
 assert.match(render('`**a（b）**中`'),/<code>\*\*a（b）\*\*中<\/code>/);
 assert.match(render('```text\n**a（b）**中\n```'),/<code[^>]*>\*\*a（b）\*\*中\n<\/code>/);
 const html=render('[link](https://example.com/a**b（c）**中)');assert.match(html,/<a href="https:\/\/example\.com\/a\*\*b%EF%BC%88c%EF%BC%89\*\*%E4%B8%AD">link<\/a>/);
 assert.doesNotMatch(html,/<strong>/);
 assert.doesNotMatch(render('https://example.com/a**b（c）**中'),/<strong>/);
 assert.match(render('\\**a（b）**中'),/\*\*a（b）\*\*中/);
 assert.doesNotMatch(render('** a（b）**中'),/<strong>/);
});
