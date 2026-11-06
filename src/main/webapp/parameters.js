'use strict';
let parameterDefinitions=[];
const parameterNames={two_stems_v1:t("人声 / 伴奏双音轨"),six_stems_v1:t("六音轨"),json_object:t("JSON 对象"),json_schema:'JSON Schema',low:t("低"),medium:t("中"),high:t("高"),auto:t("自动"),transparent:t("透明"),opaque:t("不透明"),enabled:t("开启"),disabled:t("关闭"),adaptive:t("自动思考"),between_tools:t("不预先思考"),near_field:t("近场麦克风"),far_field:t("远场麦克风"),mp3:'MP3',pcm:t("PCM 原始音频"),wav:'WAV',verbose_json:t("详细 JSON（含时间信息）"),diarized_json:t("JSON（区分说话人）"),text:t("纯文本"),srt:t("SRT 字幕"),vtt:t("VTT 字幕")};
function parameterSnapshot(){
  const values={};for(const spec of parameterDefinitions){const input=$('param-'+spec.key);if(!input||input.value==='')continue;
    if(spec.type==='boolean')values[spec.key]=input.value==='true';
    else if(spec.type==='enum')values[spec.key]=spec.values.find(v=>String(v)===input.value);
    else if(['number','integer'].includes(spec.type))values[spec.key]=Number(input.value);
    else values[spec.key]=input.value;
  }return values;
}
function parameterEnabled(spec,values){return Object.entries(spec.when||{}).every(([key,allowed])=>{const value=key==='$effort'?($('effort').value==='default'?selectedModel()?.reasoningDefault:$('effort').value):values[key];return allowed.some(v=>v==='$set'?value!==undefined&&value!=='':v===value);});}
function refreshParameterConditions(){
  const values=parameterSnapshot();let count=0;
  for(const spec of parameterDefinitions){const input=$('param-'+spec.key),active=parameterEnabled(spec,values);if(spec.restrictedValues){for(const option of input.options){const rule=spec.restrictedValues[option.value];option.hidden=option.disabled=Boolean(rule&&!parameterEnabled({when:rule},values));}if(input.selectedOptions[0]?.disabled)input.value='';}input.closest('label').hidden=!active;input.disabled=!active||busy||realtimeActive;if(active&&input.value!=='')count++;}
  $('parameterStatus').textContent=count?t("已设置 {0} 项",count):t("平台默认");
}
function configureParameters(){
  const model=selectedModel();parameterDefinitions=model?.execution==='external'?[]:model?.settings?.[$('task').value]||[];
  $('modelSettings').hidden=!parameterDefinitions.length;$('parameterFields').replaceChildren();$('parameterError').textContent='';
  $('parameterModel').textContent=model?`${model.providerName} · ${model.name} · ${catalog.categories[$('task').value]}`:'';
  $('parameterSources').replaceChildren();
  for(const url of model?.parameterSources||[]){const a=element('a',t("参数依据 ↗"));a.href=url;a.target='_blank';a.rel='noopener noreferrer';$('parameterSources').append(a);}
  for(const spec of parameterDefinitions){
    const label=element('label',undefined,'parameter-field');label.htmlFor='param-'+spec.key;label.append(element('span',spec.label));let input;
    if(spec.type==='enum'||spec.type==='boolean'){
      input=element('select');input.append(new Option(t("平台默认"),''));
      for(const value of spec.type==='boolean'?[true,false]:spec.values)input.append(new Option(typeof value==='boolean'?(value?t("开启"):t("关闭")):(parameterNames[value]?`${uiText(parameterNames[value])} · ${value}`:String(value)),String(value)));
    }else if(['json','lines'].includes(spec.type)||spec.maxLength>2000){input=element('textarea');input.rows=spec.type==='json'?5:3;input.placeholder=spec.example||(spec.type==='lines'?t("每行一条"):t("留空使用平台默认"));}
    else{input=element('input');input.type=['integer','number'].includes(spec.type)?'number':spec.type==='image'?'file':spec.type==='url'?'url':'text';if(spec.type==='image')input.accept='image/png,image/jpeg,image/webp';input.placeholder=t("平台默认");}
    if(spec.min!==undefined)input.min=spec.min;if(spec.max!==undefined)input.max=spec.max;if(spec.type==='integer')input.step='1';if(spec.type==='number')input.step='any';if(spec.maxLength)input.maxLength=spec.maxLength;
    input.id='param-'+spec.key;input.dataset.parameter=spec.key;input.addEventListener('input',refreshParameterConditions);input.addEventListener('change',refreshParameterConditions);
    const range=spec.min!==undefined?t("范围 {0}–{1}。 ",spec.min.toLocaleString(),spec.max.toLocaleString()):'';
    label.append(input,element('small',range+(spec.help||t("留空使用平台默认。"))));if(['json','lines','text','url','image'].includes(spec.type))label.classList.add('parameter-wide');$('parameterFields').append(label);
  }
  refreshParameterConditions();if($('parameterDialog').open)$('parameterDialog').close();
}
async function collectParameters(){
  const raw=parameterSnapshot(),values={};
  try{
    for(const spec of parameterDefinitions){
      const input=$('param-'+spec.key);if(!parameterEnabled(spec,raw))continue;
      if(input.validity?.badInput)throw new Error(t("{0}需要有效数值。",spec.label));
      if(input.value===''){if(spec.required)throw new Error(t("请填写{0}。",spec.label));continue;}
      let value=raw[spec.key];
      if(spec.type==='json'){try{value=JSON.parse(input.value);}catch{throw new Error(t("{0}不是有效 JSON。",spec.label));}if(!value||typeof value!=='object'||Array.isArray(value))throw new Error(t("{0}需要 JSON 对象。",spec.label));}
      if(spec.type==='lines'){value=input.value.split('\n').map(v=>v.trim()).filter(Boolean);if(!value.length||value.length>spec.maxItems)throw new Error(t("{0}最多 {1} 条。",spec.label,spec.maxItems));}
      if(spec.type==='image')value=await readFileData(input.files[0]);
      if(['integer','number'].includes(spec.type)&&(!Number.isFinite(value)||(spec.type==='integer'&&!Number.isInteger(value))||value<spec.min||value>spec.max))throw new Error(t("{0}需要 {1}–{2} 范围内的{3}。",spec.label,spec.min,spec.max,spec.type==='integer'?t("整数"):t("数值")));
      if(spec.pattern){const pattern=new RegExp(`^(?:${spec.pattern})$`);if((Array.isArray(value)?value:[value]).some(v=>!pattern.test(v)))throw new Error(t("{0}的格式不正确。",spec.label));}
      values[spec.key]=value;
    }
    if(values.background==='transparent'&&values.output_format==='jpeg')throw new Error(t("透明背景请选择 PNG 或 WebP。"));
    if(values.size&&(values.resolution||values.aspect_ratio))throw new Error(t("精确尺寸与分辨率 / 比例请选择一种设置方式。"));
    if(selectedModel()?.provider==='anthropic'&&values.thinking_budget>=(values.max_tokens||4096))throw new Error(t("思考预算必须小于最大输出 token；默认输出上限为 4096。"));
    $('parameterError').textContent='';return values;
  }catch(error){$('parameterError').textContent=error.message;if(!$('parameterDialog').open)$('parameterDialog').showModal();throw error;}
}
$('modelSettings').onclick=()=>{refreshParameterConditions();$('parameterDialog').showModal();};
$('closeParameters').onclick=()=>$('parameterDialog').close();
$('applyParameters').onclick=async()=>{try{await collectParameters();$('parameterDialog').close();}catch{}};
$('resetParameters').onclick=()=>{for(const spec of parameterDefinitions)$('param-'+spec.key).value='';$('parameterError').textContent='';refreshParameterConditions();};
$('effort').addEventListener('change',refreshParameterConditions);
document.addEventListener('starhub:modelchanged',configureParameters);
if(ready)configureParameters();
