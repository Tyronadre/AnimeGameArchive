(() => {
'use strict';
const root=document.querySelector('[data-formula-studio]'); if(!root)return;
const $=id=>document.getElementById('studio-'+id), api=root.dataset.api;
let files=[], path='', data=null, revision='', dirty=false, section='Overview', busy=false;
let catalog=[];
let sharedDamageTypes=[];
let standardInputTemplates={};
const autoIdentifiers=new WeakMap();
const panelIds=new WeakMap();let panelSequence=0;
function panelId(obj,suffix){if(!panelIds.has(obj))panelIds.set(obj,++panelSequence);return panelIds.get(obj)+':'+suffix;}
const panelStates=new Map(),viewRenderers=new WeakMap(),viewSubjects=new WeakMap(),cardItems=new WeakMap();let editOrigin=null;
function editFrom(control,action){const previous=editOrigin;editOrigin=control;try{return action();}finally{editOrigin=previous;}}
function editorView(element,obj,role,rebuild){element.dataset.studioView=panelId(obj,role);viewRenderers.set(element,rebuild);viewSubjects.set(element,obj);return element;}
function syncSummaries(){
 for(const summary of $('fields').querySelectorAll('.studio-expression-summary')){
  const expression=viewSubjects.get(summary.closest('[data-studio-view]'));if(expression)summary.textContent=expressionSummary(expression);
 }
 for(const card of $('fields').querySelectorAll('details')){const item=cardItems.get(card),caption=item&&(item.name||item.label||item.id||item.key);if(caption)card.querySelector(':scope > summary').textContent=caption;}
}
function identifyControls(container){
 const occurrences=new Map();
 for(const element of container.querySelectorAll('details,input,select,textarea,button')){
  let view=element.closest('[data-studio-view]');const ancestors=[];while(view){ancestors.unshift(view.dataset.studioView);view=view.parentElement?.closest('[data-studio-view]');}
  const label=element.closest('.studio-form-field')?.querySelector(':scope > span')?.textContent||element.closest('.studio-field')?.querySelector(':scope > .studio-field-header > span')?.textContent;
  const name=label||element.querySelector(':scope > summary')?.textContent||element.textContent||element.getAttribute('aria-label')||element.tagName;
  const base=`${ancestors.join('/')||section}:${element.tagName}:${name}`;
  const index=occurrences.get(base)||0;occurrences.set(base,index+1);
  if(element.tagName==='DETAILS'){element.dataset.studioCard||=base+':'+index;}
  else element.dataset.studioControl=base+':'+index;
 }
}
function rememberUI(){
 for(const panel of $('fields').querySelectorAll('details'))panelStates.set(panel.dataset.studioCard,panel.open);
 const active=document.activeElement;
 return {x:window.scrollX,y:window.scrollY,focus:active?.dataset.studioControl,start:active?.selectionStart,end:active?.selectionEnd};
}
function restoreUI(container,state){
 identifyControls(container);
 for(const panel of container.querySelectorAll('details'))if(panelStates.has(panel.dataset.studioCard))panel.open=panelStates.get(panel.dataset.studioCard);
 if(state?.focus){const control=[...$('fields').querySelectorAll('[data-studio-control]')].find(n=>n.dataset.studioControl===state.focus);
  if(control){control.focus({preventScroll:true});if(typeof state.start==='number'&&control.setSelectionRange&&['text','search','url','tel','password'].includes(control.type))control.setSelectionRange(state.start,state.end);}}
 if(state)window.scrollTo({left:state.x,top:state.y,behavior:'instant'});
}
const labels={base:'Base addition',baseDamageBonus:'Base damage bonus',baseDamageMultiplier:'Independent base multiplier',damageBonus:'Damage bonus',postBonusBase:'Post-bonus addition',formulaModifiers:'Damage modifiers',damageKinds:'Damage kinds',requiredTags:'All required tags',anyTags:'Any matching tag',defaultValue:'Default value',initialValueSource:'Saved build source',statChanges:'Stat changes',ownershipRequirements:'Constellation requirements',requiresEffects:'Required effects',excludedEffects:'Excluded effects',requiredEffects:'Required effects'};
const title=k=>labels[k]||k.replace(/([a-z])([A-Z])/g,'$1 $2').replace(/^./,c=>c.toUpperCase());
const node=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
const button=(text,fn)=>{const b=node('button',text);b.type='button';b.addEventListener('click',()=>editFrom(b,fn));return b;};
const expr=()=>({type:'constant',value:0});
const contribution=()=>({unit:'percentPoints',expression:expr()});
const effect=()=>({id:'newEffect',name:'New effect',source:'',description:'',category:'passive',recipient:'self',defaultEnabled:true,activation:{event:'always'},formulaModifiers:[]});
const formula=()=>({id:'newHit',label:'New hit',damageType:sharedDamageTypes.find(t=>t.kind!=='artifactProc')?.id||'',tags:[],base:expr()});
const input=()=>({id:'newInput',label:'New input',defaultValue:0,min:0,max:100,step:1});
const arrayKinds={inputs:'inputDefinition',effects:'effect',talents:'talent',formulas:'formula',damageTypes:'damageType',formulaModifiers:'modifier',statChanges:'statChange',metrics:'metric',conditions:'condition',operands:'expression',reactions:'reaction'};
const schemas={
 inputDefinition:{id:'',label:'',defaultValue:0,min:0,max:100,step:1,unit:'number',initialValueSource:{valueKey:''},section:'combat',hintTag:'',description:'',defaultContext:''},
 effect:{id:'',name:'',source:'',description:'',category:'passive',recipient:'self',excludeSource:false,impact:'',defaultEnabled:true,ownershipRequirements:{},requiresEffects:[],activation:{event:'always'},statChanges:[],formulaModifiers:[],metrics:[],stackingGroup:'',stackingPriority:expr(),effectTags:[]},
 wrapper:{pieces:2,recipient:'wearer',effect:effect(),formulas:[]},weaponWrapper:{recipient:'wearer',effect:effect()},
 talent:{key:'',name:'',formulas:[]},formula:{id:'',label:'',damageType:'',infusionRule:'physicalOnly',tags:[],requiredEffects:[],excludedEffects:[],base:expr(),damageBonus:contribution(),defenseMitigation:{},resistanceMitigation:{},crit:{}},
 modifier:{targets:{},conditions:[],requiresEffects:[],recipient:'self',excludeSource:false,base:expr(),baseDamageBonus:contribution(),baseDamageMultiplier:contribution(),damageBonus:contribution(),postBonusBase:expr(),defenseMitigation:{},resistanceMitigation:{},crit:{},elevation:contribution()},
 targets:{formulaIds:[],requiredTags:[],anyTags:[],elements:[],damageKinds:[]},
 statChange:{stat:'',operation:'flatAdd',value:expr(),phase:0,recipient:'self',excludeSource:false,minimum:0,maximum:100,conditions:[],requiresEffects:[]},
 metric:{label:'',expression:expr(),unit:'number',conditions:[]},condition:{type:'inputAtLeast',input:'',value:1,teamMember:''},
 damageType:{id:'',element:'Dendro',kind:'talent',defenseBehavior:'standard',appliesElementalDamageBonus:true,allowsCriticalHits:true,appliesOtherDamageBonus:true,resistanceInput:'enemyResistance',independentProc:false,innateDamageBonus:contribution()},
 activation:{event:'always',talentKey:'',conditions:[]}, initialValueSource:{valueKey:'',characterKey:''},
 defenseMitigation:{reduction:contribution(),ignore:contribution(),bypass:false},resistanceMitigation:{reduction:contribution()},crit:{additionalRate:contribution(),additionalDamage:contribution()},
 contribution:{expression:expr(),unit:'percentPoints'},expression:{type:'constant',value:0,input:'',talent:'',parameter:'',levelInput:'',characterKey:'',operands:[]},
 reaction:{key:'',id:'',name:'',kind:'transformative',triggerElements:[],requiredTeamElements:[],auraLabel:'',damageElement:'',description:'',requiredFormulaTagsAny:[],baseMultiplier:1,elementalMasteryCoefficient:16,elementalMasteryDenominator:2000}
};
const topSchema={hexereiCharacters:[],schemaVersion:3,key:'',name:'',characterKey:'',element:'Dendro',weaponType:'Catalyst',characterDataResource:'',moonsignContribution:0,statBindings:{},defaultTeam:[],inputs:[],damageTypes:[],talents:[],effects:[],sources:[],notes:[],talentParameters:{},characterLevelMultipliers:[],crystallizeLevelMultipliers:[],reactions:[]};
function changed(){dirty=true;$('dirty').textContent='Unsaved changes';controls();syncSummaries();}
function controls(){for(const id of ['save','validate','reload'])$(id).disabled=!data||busy;$('new').disabled=busy;}
function status(text,error=false){$('status').textContent=text;$('status').dataset.error=String(error);}
function select(options,value,onchange){const n=node('select');[...new Set([value,...options])].forEach(v=>{const o=node('option',title(String(v)));o.value=v;n.append(o);});n.value=value;n.addEventListener('change',()=>onchange(n.value));return n;}
function enumValues(key,kind){
 if(key==='unit')return ['number','percentPoints','ratio'];
 if(key==='type')return kind==='condition'?['inputAtLeast','inputAtMost','teamMember','contextAtLeast']:['constant','input','talentParameter','teamMax','add','multiply','divide','min','max',...(path.includes('/')?['context']:[])];
 if(key==='kind')return kind==='reaction'?['amplifying','additive','transformative','utility','shield']:['talent','lunar','stellar','reaction','artifactProc'];
 return {element:['Pyro','Hydro','Electro','Cryo','Anemo','Geo','Dendro','Physical'],damageElement:['Pyro','Hydro','Electro','Cryo','Anemo','Geo','Dendro','Physical'],weaponType:['Sword','Claymore','Polearm','Bow','Catalyst'],operation:['flatAdd','percentAdd'],defenseBehavior:['standard','bypass'],category:['passive','talent','team','constellation'],recipient:(['wrapper','weaponWrapper'].includes(kind)?['wearer','party','others']:['self','party','others','activeCharacter','enemy']),pieces:[1,2,4],damageType:sharedDamageTypes.map(t=>t.id)}[key];
}
function primitive(value,set,key,kind){
 const options=enumValues(key,kind);if(options?.length){const control=select(options,value,v=>editFrom(control,()=>{set(typeof value==='number'?Number(v):v);changed();if(key==='type')render();}));return control;}
 const n=node(['description','impact','notes'].includes(key)?'textarea':'input');
 if(typeof value==='boolean'){n.type='checkbox';n.checked=value;n.addEventListener('change',()=>{set(n.checked);changed();});}
 else {
 if(['input','levelInput','stat'].includes(key)){
  const list=node('datalist');list.id='studio-options-'+Math.random().toString(36).slice(2);
  const options=[...availableInputs().map(i=>i.id),...Object.keys(data.statBindings||{}),...(path.includes('/')?['wearer:elementalMastery','wearer:attack','recipient:baseAttack','local:stacks']:[])];
  options.forEach(v=>{const o=node('option');o.value=v;list.append(o);});root.append(list);n.setAttribute('list',list.id);
 }
 if(n.tagName==='INPUT')n.type=typeof value==='number'?'number':'text';if(typeof value==='number')n.step='any';n.value=value??'';n.addEventListener('input',()=>{if(typeof value==='number'&&!Number.isFinite(n.valueAsNumber))return;set(typeof value==='number'?n.valueAsNumber:n.value);changed();});}
 return n;
}
function defaultItem(kind){return ({inputDefinition:input,talent:()=>({key:'newTalent',name:'New talent',formulas:[]}),formula,effect,weaponWrapper:()=>({recipient:'wearer',effect:effect()}),wrapper:()=>({pieces:2,recipient:'wearer',effect:effect()}),modifier:()=>({targets:{}}),statChange:()=>({stat:'attack',operation:'flatAdd',value:expr()}),metric:()=>({label:'New metric',expression:expr()}),damageType:()=>({id:'newType',element:'Dendro'}),expression:expr,condition:()=>({type:'inputAtLeast',input:'',value:1}),reaction:()=>structuredClone(schemas.reaction)})[kind]?.()??'';}
function nestedKind(key,value){if(['base','postBonusBase','stackingPriority'].includes(key)||key==='value'&&typeof value==='object')return 'expression';if(['damageBonus','baseDamageBonus','baseDamageMultiplier','elevation','innateDamageBonus','reduction','ignore','additionalRate','additionalDamage'].includes(key))return 'contribution';return key;}
function arrayEditor(arr,key,numeric=false){
 const box=node('div');let kind=arrayKinds[key];if(key==='effects'&&path.startsWith('artifacts/'))kind='wrapper';if(key==='effects'&&path.startsWith('weapons/'))kind='weaponWrapper';
 arr.forEach((item,i)=>{const row=node('div',undefined,'studio-list-item');
 if(item&&typeof item==='object'){
 const card=node('details',undefined,'studio-card');card.dataset.studioCard=panelId(item,key);cardItems.set(card,item);card.open=arr.length===1;card.append(node('summary',item.name||item.label||item.id||item.key||`${title(kind)} ${i+1}`));card.append(objectEditor(item,kind));row.append(card);
 }else row.append(primitive(item,v=>arr[i]=v,key==='damageKinds'?'kind':key==='elements'?'element':key,''));
 const actions=node('div',undefined,'studio-item-actions');
 if(kind==='formula')actions.append(button('Duplicate',()=>{
  const copy=structuredClone(item);copy.id=uniqueId((item.id||'hit')+'Copy',allFormulas());copy.label=(item.label||'Damage instance')+' (copy)';
  arr.splice(i+1,0,copy);panelStates.set(panelId(copy,key),true);changed();render();
 }));
 actions.append(button('Remove',()=>{arr.splice(i,1);changed();render();}));row.append(actions);box.append(row);});
 box.append(button('+ Add '+title(kind||'value'),()=>{const item=kind?newItem(kind):numeric||typeof arr[0]==='number'||['characterLevelMultipliers','crystallizeLevelMultipliers'].includes(key)?0:'';arr.push(item);if(item&&typeof item==='object')panelStates.set(panelId(item,key),true);changed();render();}));return editorView(box,arr,'array:'+key,()=>arrayEditor(arr,key,numeric));
}
const layerTypes=[
 ['base','Base addition','Flat damage added to the base before damage bonuses.'],
 ['baseDamageBonus','Base damage bonus','Percentage added to the base multiplier.'],
 ['baseDamageMultiplier','Independent base multiplier','Each contribution applies a separate (1 + percentage) factor after base bonuses and flat base additions.'],
 ['damageBonus','Damage bonus','Percentage added to the damage bonus layer.'],
 ['postBonusBase','Post-bonus addition','Flat damage added after damage bonuses.'],
 ['defenseMitigation.reduction','DEF reduction','Percentage of enemy DEF removed; total reduction caps at 90%.'],
 ['defenseMitigation.ignore','DEF ignore','Percentage of remaining enemy DEF ignored.'],
 ['defenseMitigation.bypass','Bypass DEF','Ignore enemy DEF entirely for matching hits.'],
 ['resistanceMitigation.reduction','RES reduction','Percentage points subtracted from enemy resistance.'],
 ['crit.additionalRate','CRIT Rate','Additional CRIT Rate in percentage points.'],
 ['crit.additionalDamage','CRIT DMG','Additional CRIT DMG in percentage points.'],
 ['elevation','Elevation','Percentage added to the separate final multiplier.'],
 ['infusion','Weapon infusion','Changes eligible weapon attacks to a shared damage type before damage bonuses, resistance, and reactions are calculated.']
];
const expressionNames={constant:'Fixed number',input:'Character stat or input',talentParameter:'Talent multiplier',teamMax:'Highest team stat',context:'Team or equipment context',add:'Add (+)',multiply:'Multiply (×)',divide:'Divide (÷)',min:'Minimum / cap',max:'Maximum / floor'};
const logicalStats=['pyroDamageBonus','hydroDamageBonus','cryoDamageBonus','dendroDamageBonus','electroDamageBonus','geoDamageBonus','anemoDamageBonus','attack','baseAttack','maxHp','baseHp','defense','baseDefense','elementalMastery','critRate','critDamage','energyRecharge','elementalDamageBonus','physicalDamageBonus','characterLevel','normalTalentLevel','skillTalentLevel','burstTalentLevel','constellation'];
const recipientOptions=[['self','Self (source character)'],['party','Whole party'],['others','Other party members'],['activeCharacter','Active character'],['enemy','Enemy debuff']];
const events=['always','buffActive','burstActive','burstFieldActive','skillActive','skillUsed','burstUsed','normalAttackHit','chargedAttackHit','reactionTriggered','teamComposition'];
const knownTags=['stellarSwirl','stellarConduct','lunarBloom','normalAttack','chargedAttack','plungingAttack','plungeImpact','elementalSkill','elementalBurst','multiHit','infusedAttack','noReaction'];
function choice(options,value,apply){
 const n=node('select');const items=options.map(o=>Array.isArray(o)?o:[o,title(o)]);
 if(!items.some(o=>String(o[0])===String(value)))items.unshift([value,value||'Choose…']);
 items.forEach(([v,text])=>{const option=node('option',text);option.value=v;n.append(option);});n.value=value;
 n.addEventListener('change',()=>editFrom(n,()=>{apply(n.value);changed();render();}));return n;
}
function field(label,control,help=''){
 const wrap=node('label',undefined,'studio-form-field');wrap.append(node('span',label),control);if(help)wrap.append(node('small',help));return wrap;
}
function textControl(value,apply,multiline=false){const n=node(multiline?'textarea':'input');n.value=value??'';n.addEventListener('input',()=>{apply(n.value);changed();});return n;}
function numberControl(value,apply){const n=node('input');n.type='number';n.step='any';n.value=value??0;n.addEventListener('input',()=>{if(Number.isFinite(n.valueAsNumber)){apply(n.valueAsNumber);changed();}});return n;}
function checkbox(value,apply){const n=node('input');n.type='checkbox';n.checked=value;n.addEventListener('change',()=>{apply(n.checked);changed();});return n;}
function getLayer(obj,key){return key.split('.').reduce((o,k)=>o?.[k],obj);}
function setLayer(obj,key,value){const keys=key.split('.');let parent=obj;for(const k of keys.slice(0,-1))parent=parent[k]??=( {} );parent[keys.at(-1)]=value;}
function deleteLayer(obj,key){const [parent,child]=key.split('.');if(child){delete obj[parent][child];if(!Object.keys(obj[parent]).length)delete obj[parent];}else delete obj[parent];}
function layerExpression(value){return value?.expression||(value?.type?value:expr());}
function isFlatLayer(key){return ['base','postBonusBase'].includes(key);}
function newLayerValue(key,expression=expr(),unit='percentPoints'){return key==='infusion'?{damageType:'directPyro',weaponTypes:['Sword','Claymore','Polearm'],priority:0}:key==='defenseMitigation.bypass'?true:isFlatLayer(key)?expression:{expression,unit};}
function uniqueId(base,items){let id=base,n=2;const ids=new Set(items.map(x=>x.id||x.key));while(ids.has(id))id=base+n++;return id;}
function allFormulas(){const formulas=[];function visit(value){if(!value||typeof value!=='object')return;for(const [key,child] of Object.entries(value)){if(key==='formulas'&&Array.isArray(child))formulas.push(...child);else if(child&&typeof child==='object')visit(child);}}visit(data);return formulas;}
function newItem(kind){const item=defaultItem(kind);const collections={effect:data.effects||[],formula:allFormulas(),inputDefinition:availableInputs(),talent:data.talents||[],damageType:data.damageTypes||[]};if(item?.id)item.id=uniqueId(item.id,collections[kind]||[]);if(item?.key)item.key=uniqueId(item.key,collections[kind]||[]);if(['effect','formula'].includes(kind))autoIdentifiers.set(item,item.id);if(item?.effect)autoIdentifiers.set(item.effect,item.effect.id);return item;}
function inheritedInputs(){
 if(data.schemaVersion!==3)return [];
 return (standardInputTemplates[data.element]||[]).map(template=>{
  const logicalId=template.id;
  const existing=(data.inputs||[]).find(i=>i.id===(data.statBindings?.[logicalId]||logicalId))||
   (template.initialValueSource?(data.inputs||[]).find(i=>i.initialValueSource?.valueKey===template.initialValueSource.valueKey):null);
  return {...template,...existing,id:data.statBindings?.[logicalId]||existing?.id||logicalId,logicalId,
   defaultValue:data.inputDefaults?.[logicalId]??existing?.defaultValue??template.defaultValue,sharedDefault:template.defaultValue};
 });
}
function availableInputs(){return [...new Map([...inheritedInputs(),...(data.inputs||[])].map(i=>[i.id,i])).values()];}
function inputOptions(levels=false,character=''){
 let inputs=availableInputs();
 if(character&&character!==data.key){const source=catalog.find(c=>c.key===character);inputs=source?.inputs||[];}
 const entries=inputs.filter(i=>!levels||i.id.toLowerCase().includes('talent')||i.initialValueSource?.valueKey?.endsWith('Talent'));
 return [...new Map(entries.map(i=>{
  const reference=data.schemaVersion!==3?i.id:i.logicalId?(i.initialValueSource?'source:'+i.logicalId:'battle:'+i.id):'local:'+i.id;
  return [reference,[reference,`${i.logicalId?'Standard · ':''}${i.label||i.id}`]];
 })).values()];
}
function referenceControl(value,apply,options){
 const wrap=node('div',undefined,'studio-reference-control');wrap.append(choice(options,value,apply));
 const custom=node('details');custom.append(node('summary','Use an exact reference'),field('Reference ID',textControl(value,apply),'Use this for an external input or a custom reference.'));wrap.append(custom);return wrap;
}
function expressionSummary(e){
 if(!e)return '0';if(e.type==='constant')return String(e.value??0);
 if(e.type==='input')return availableInputs().find(i=>i.id===e.input||'source:'+i.logicalId===e.input||'local:'+i.id===e.input||'battle:'+i.id===e.input)?.label||e.input||'Choose input';
 if(e.type==='talentParameter')return `${e.characterKey||data.name||'Character'} · ${e.talent||'talent'} · ${e.parameter||'parameter'}`;
 if(e.type==='teamMax')return `Highest team ${e.input||'stat'}`;if(e.type==='context')return e.input||'Context';
 const parts=(e.operands||[]).map(expressionSummary);const signs={add:' + ',multiply:' × ',divide:' ÷ '};return signs[e.type]?`(${parts.join(signs[e.type])})`:`${e.type}(${parts.join(', ')})`;
}
function expressionEditor(e){
 const box=node('div',undefined,'studio-expression');
 const modes=['constant','input',...(path==='damage-types.json'?[]:['talentParameter','teamMax',...(data.schemaVersion===3||path.startsWith('artifacts/')||path.startsWith('weapons/')?['context']:[])]),'multiply','add','divide','min','max'];
 box.append(field('Value comes from',choice(modes.map(v=>[v,expressionNames[v]]),e.type,v=>{
 const old=structuredClone(e);Object.keys(e).forEach(k=>delete e[k]);e.type=v;
 if(v==='constant')e.value=old.value??0;
 else if(['input','context','teamMax'].includes(v))e.input=old.input||'';
 else if(v==='talentParameter')Object.assign(e,{characterKey:old.characterKey||data.characterKey||data.key||'',talent:old.talent||'combat3',parameter:old.parameter||'',levelInput:old.levelInput||(data.schemaVersion===3?'source:burstTalentLevel':'')});
 else e.operands=old.operands||[old,expr()];
 })));
 if(e.type==='constant')box.append(field('Number',numberControl(e.value,v=>e.value=v)));
 else if(e.type==='input'){
 let options=inputOptions();if(data.schemaVersion===3||path==='damage-types.json')options=[...options,...logicalStats.flatMap(stat=>[['source:'+stat,'Source character · '+title(stat)],['recipient:'+stat,'Buff recipient · '+title(stat)]])];if(path.startsWith('artifacts/')||path.startsWith('weapons/')){
 const stats=['attack','baseAttack','maxHp','baseHp','defense','baseDefense','elementalMastery','critRate','critDamage','energyRecharge','physicalDamageBonus'];
 options=[...options,...stats.flatMap(stat=>[['wearer:'+stat,'Wearer · '+title(stat)],['recipient:'+stat,'Recipient · '+title(stat)]]),...(data.inputs||[]).map(i=>['local:'+i.id,'Equipment control · '+i.label])];}
 box.append(field('Stat / input',referenceControl(e.input||'',v=>e.input=v,options)));
 }else if(e.type==='talentParameter'){
 const character=e.characterKey||data.characterKey||data.key;
 const source=catalog.find(c=>c.key===character);
 const characterOptions=[...new Map(catalog.filter(c=>data.schemaVersion!==3||c.key===data.key).map(c=>[c.key,[c.key,c.name]])).values()];
 box.append(field('Character',choice(characterOptions,character,v=>{e.characterKey=v;delete e.talent;delete e.parameter;delete e.levelInput;})));
 const talents=source?.talents||[];
 box.append(field('Talent',referenceControl(e.talent||'',v=>{e.talent=v;delete e.parameter;const key={combat1:'normalTalent',combat2:'skillTalent',combat3:'burstTalent'}[v];const matched=(character===data.key?availableInputs():source?.inputs||[]).find(i=>i.initialValueSource?.valueKey===key);if(data.schemaVersion===3&&key)e.levelInput='source:'+{normalTalent:'normalTalentLevel',skillTalent:'skillTalentLevel',burstTalent:'burstTalentLevel'}[key];else if(matched)e.levelInput=matched.id;},talents.map(t=>[t.key,`${{combat1:'Normal Attack',combat2:'Elemental Skill',combat3:'Elemental Burst'}[t.key]||t.key} · ${t.name}`]))));
 const parameters=talents.find(t=>t.key===e.talent)?.parameters||[];
 box.append(field('Scaling parameter',referenceControl(e.parameter||'',v=>e.parameter=v,parameters.map(p=>[p.key,`${p.label} · ${p.key}`]))));
 box.append(field('Read talent level from',referenceControl(e.levelInput||'',v=>e.levelInput=v,inputOptions(true,character)),'This uses the selected level at calculation time; it is not a fixed multiplier.'));
 const parameter=parameters.find(p=>p.key===e.parameter);if(parameter)box.append(node('p',`Talent table: ${parameter.values?.map(n=>Number(n.toFixed(4))).join(' · ')}`,'studio-help'));
 }else if(e.type==='context')box.append(field('Context',referenceControl(e.input||'',v=>e.input=v,['hexereiCount','sourceHexerei','recipientHexerei','sourceNonFontaineAllies','recipientPyroElectro','pyroElectroOnly','sameElementAllies','differentElementAllies','moonsignLevel','onField','offField','meleeWeapon','rangedWeapon'].map(k=>[k,title(k)]))));
 else if(e.type==='teamMax')box.append(field('Team stat',referenceControl(e.input||'',v=>e.input=v,['elementalMastery','attack','maxHp','defense'].map(k=>[k,title(k)]))));
 else {
 const operands=e.operands??=[];
 operands.forEach((operand,i)=>{const row=node('div',undefined,'studio-operand');row.append(node('strong',e.type==='divide'?(i===0?'Numerator':'Denominator'):`Term ${i+1}`),expressionEditor(operand));
 if(e.type!=='divide')row.append(button('Remove term',()=>{operands.splice(i,1);changed();render();}));box.append(row);});
 if(e.type!=='divide')box.append(button('+ Add term',()=>{operands.push(expr());changed();render();}));
 }
 box.append(node('p',expressionSummary(e),'studio-expression-summary'));
 return editorView(box,e,'expression',()=>expressionEditor(e));
}
function targetEditor(target){
 const box=node('div',undefined,'studio-targets');
 const mode=Object.keys(target).length===0?'all':target.damageKinds?.length===1&&Object.keys(target).length===1?target.damageKinds[0]:target.requiredTags?.length===1&&Object.keys(target).length===1?target.requiredTags[0]:'custom';
 const options=[['all','All damage'],['lunar','All Lunar damage'],['stellar','All Stellar damage'],['lunarBloom','Lunar-Bloom hits'],['talent','Ordinary talent damage'],...knownTags.filter(t=>t!=='lunarBloom').map(t=>[t,title(t)]),['custom','Custom combination…']];
 box.append(field('Applies to',choice(options,mode,v=>{if(v==='custom')return;Object.keys(target).forEach(k=>delete target[k]);if(['lunar','stellar','talent'].includes(v))target.damageKinds=[v];else if(v!=='all')target.requiredTags=[v];}),'Selectors combine with AND. An empty selector applies to every hit.'));
 const details=node('details');details.dataset.studioCard=panelId(target,'targets');details.open=mode==='custom';details.append(node('summary','Refine targets'),objectEditor(target,'targets',true));box.append(details);return editorView(box,target,'targets',()=>targetEditor(target));
}
function modifierEditor(modifier){
 const box=node('div',undefined,'studio-modifier');if(data.schemaVersion===3)box.append(recipientEditor(modifier,true));box.append(targetEditor(modifier.targets??={}));
 const active=layerTypes.filter(([key])=>getLayer(modifier,key)!==undefined&&getLayer(modifier,key)!==null);
 active.forEach(([key,label,help])=>{
 const change=node('section',undefined,'studio-change');change.dataset.modifierLayer=key;
 const options=layerTypes.filter(([other])=>other===key||getLayer(modifier,other)==null).map(([id,name])=>[id,name]);
 change.append(field('What changes?',choice(options,key,next=>{const old=getLayer(modifier,key),expression=structuredClone(layerExpression(old));deleteLayer(modifier,key);setLayer(modifier,next,newLayerValue(next,expression,old?.unit||'percentPoints'));}),help));
 const value=getLayer(modifier,key);
 if(key==='infusion'){
  change.append(field('Infused damage type',choice(sharedDamageTypes.filter(t=>(t.kind||'talent')==='talent').map(t=>[t.id,damageTypeLabel(t)]),value.damageType,v=>value.damageType=v)));
  const weapons=node('div',undefined,'studio-tag-choices');for(const weapon of ['Sword','Claymore','Polearm','Bow','Catalyst'])weapons.append(field(weapon,checkbox((value.weaponTypes||['Sword','Claymore','Polearm']).includes(weapon),enabled=>{value.weaponTypes??=['Sword','Claymore','Polearm'];if(enabled&&!value.weaponTypes.includes(weapon))value.weaponTypes.push(weapon);else if(!enabled)value.weaponTypes=value.weaponTypes.filter(w=>w!==weapon);})));change.append(weapons,field('Infusion priority',numberControl(value.priority??0,v=>value.priority=v),'Higher priority wins. Different infusions with equal priority require an explicit choice.'));
 }else if(key==='defenseMitigation.bypass')change.append(field('Bypass defense',checkbox(value,v=>setLayer(modifier,key,v))));
 else {
 if(!isFlatLayer(key))change.append(field('Amount is expressed as',choice([['percentPoints','Percentage points (20 means +20%)'],['ratio','Ratio (0.2 means +20%)']],value.unit||'percentPoints',v=>value.unit=v)));
 change.append(expressionEditor(isFlatLayer(key)?value:value.expression));
 }
 change.append(button('Remove this change',()=>{deleteLayer(modifier,key);changed();render();}));box.append(change);
 });
 const available=layerTypes.filter(([key])=>getLayer(modifier,key)==null);if(available.length){let chosen=available[0][0];const add=node('div',undefined,'studio-add');
 const plain=node('select');plain.setAttribute('aria-label','Damage change to add');available.forEach(([key,label])=>{const option=node('option',label);option.value=key;plain.append(option);});plain.addEventListener('change',()=>chosen=plain.value);
 add.append(plain,button('+ Add damage change',()=>{setLayer(modifier,chosen,newLayerValue(chosen));changed();render();}));box.append(add);}
 const extra=node('details',undefined,'studio-advanced');extra.dataset.studioCard=panelId(modifier,'conditions');extra.append(node('summary','Conditions & required effects'));
 for(const key of ['conditions','requiresEffects']){const value=modifier[key]||[];extra.append(node('h4',title(key)),arrayEditor(value,key));if(!(key in modifier)){
 // Attach empty arrays now so subsequent edits have a stable destination.
 modifier[key]=value;
 }}box.append(extra);
 return editorView(box,modifier,'modifier',()=>modifierEditor(modifier));
}
function updateGeneratedId(item,name){
 if(autoIdentifiers.get(item)!==item.id)return;
 const words=((data.key||'')+' '+name).match(/[a-zA-Z0-9]+/g)||['effect'];
 const stem=words.map((w,i)=>i?w[0].toUpperCase()+w.slice(1):w.toLowerCase()).join('');
 const peers=[...(data.effects||[]).map(e=>e.effect||e),...(data.talents||[]).flatMap(t=>t.formulas||[])].filter(e=>e!==item);
 item.id=uniqueId(stem,peers);autoIdentifiers.set(item,item.id);
}
function inputDefinitionEditor(definition){
 const box=node('div',undefined,'studio-effect-form'),grid=node('div',undefined,'studio-grid');
 grid.append(field('Display name',textControl(definition.label,v=>definition.label=v)),field('Units',choice(['number','percentPoints','ratio'],definition.unit||'number',v=>definition.unit=v)));
 for(const [key,label] of [['defaultValue','Starting value'],['min','Minimum'],['max','Maximum'],['step','Step size']])grid.append(field(label,numberControl(definition[key],v=>definition[key]=v)));
 box.append(grid);
 const source=definition.initialValueSource;
 const values=[['','Manual input'],['level','Saved character level'],['constellation','Owned constellation'],['normalTalent','Normal Attack level'],['skillTalent','Skill level'],['burstTalent','Burst level'],['totalAttack','Total ATK'],['baseAttack','Base ATK'],['totalHp','Max HP'],['baseHp','Base HP'],['totalDefense','Total DEF'],['stat:eleMas','Elemental Mastery'],['stat:critRate_','CRIT Rate'],['stat:critDMG_','CRIT DMG'],['stat:enerRech_','Energy Recharge']];
 box.append(field('Starting value source',referenceControl(source?.valueKey||'',v=>{if(v)definition.initialValueSource={...source,valueKey:v};else delete definition.initialValueSource;},values)));
 const advanced=node('details',undefined,'studio-advanced');advanced.dataset.studioCard=panelId(definition,'advanced');advanced.append(node('summary','Identifier, presentation & other fields'),objectEditor(definition,'inputDefinition',true));box.append(advanced);return box;
}
function characterInputsEditor(){
 const box=node('div');
 box.append(node('p','Character level, talent levels, constellation, combat stats, and enemy controls are always available. Only add inputs for character-specific mechanics such as stacks or combat states.','studio-help'));
 const standard=node('details',undefined,'studio-standard-inputs studio-card');standard.dataset.studioCard='standard-inputs';
 const inherited=inheritedInputs();standard.append(node('summary',`Standard inputs (${inherited.length}) · always available`),node('p','Use these directly in formulas and conditions. Preview defaults are optional; saved builds supply the character’s actual values.','studio-help'));
 const list=node('div',undefined,'studio-standard-list');
 inherited.forEach(definition=>{
  const row=node('div',undefined,'studio-standard-row'),identity=node('div');
  identity.append(node('strong',definition.label),node('small',definition.initialValueSource?`source:${definition.logicalId}`:`battle:${definition.id}`));
  const value=numberControl(definition.defaultValue,v=>{
   if(v===definition.sharedDefault){if(data.inputDefaults)delete data.inputDefaults[definition.logicalId];}
   else (data.inputDefaults??={})[definition.logicalId]=v;
   reset.disabled=!Object.hasOwn(data.inputDefaults||{},definition.logicalId);
  });value.min=definition.min;value.max=definition.max;value.step=definition.step;
  const reset=button('Reset',()=>{if(data.inputDefaults)delete data.inputDefaults[definition.logicalId];changed();render();});
  reset.disabled=!Object.hasOwn(data.inputDefaults||{},definition.logicalId);
  row.append(identity,field('Preview default',value),reset);list.append(row);
 });
 standard.append(list);box.append(standard,node('h3','Special character inputs'));
 if(!data.inputs.length)box.append(node('p','No special inputs defined. All standard inputs are already available.','studio-help'));
 box.append(arrayEditor(data.inputs,'inputs'));return editorView(box,data,'characterInputs',characterInputsEditor);
}
function conditionEditor(condition){
 const box=node('div',undefined,'studio-effect-form');
 const types=[['inputAtLeast','Input is at least'],['inputAtMost','Input is at most'],['teamMember','Character is in the team'],['contextAtLeast','Team / equipment context is at least']];
 box.append(field('Requirement',choice(types,condition.type,v=>{condition.type=v;delete condition.input;delete condition.teamMember;delete condition.value;if(v==='teamMember')condition.teamMember='';else {condition.input='';condition.value=1;}})));
 if(condition.type==='teamMember')box.append(field('Character',referenceControl(condition.teamMember||'',v=>condition.teamMember=v,[...new Map(catalog.map(c=>[c.key,[c.key,c.name]])).values()])));
 else {
 const options=condition.type==='contextAtLeast'?['hexereiCount','sourceHexerei','recipientHexerei','recipientPyroElectro','pyroElectroOnly','teamSize','moonsignLevel','hydroCryoCount',...['pyro','hydro','cryo','dendro','electro','anemo','geo'].map(e=>e+'Count')].map(k=>[k,title(k)]):inputOptions();
 box.append(field(condition.type==='contextAtLeast'?'Context':'Input',referenceControl(condition.input||'',v=>condition.input=v,options)),field('Threshold',numberControl(condition.value,v=>condition.value=v)));
 }return box;
}
function statChangeEditor(change){
 const box=node('div',undefined,'studio-effect-form');if(data.schemaVersion===3)box.append(recipientEditor(change,true));box.append(field('Stat to change',referenceControl(change.stat||'',v=>change.stat=v,data.schemaVersion===3?[...logicalStats.map(k=>['recipient:'+k,title(k)]),...(data.inputs||[]).map(i=>['local:'+i.id,'Source control · '+i.label])]:inputOptions())),field('Operation',choice([['flatAdd','Add a flat amount'],['percentAdd','Add a percentage of the phase-start stat']],change.operation||'flatAdd',v=>change.operation=v)));
 box.append(expressionEditor(change.value??=expr()),field('Evaluation stage',choice([[0,'0 · Base bonuses'],[1,'1 · Buffs using modified talent levels'],[10,'10 · Stat conversions']],change.phase??0,v=>change.phase=Number(v))));
 const advanced=node('details',undefined,'studio-advanced');advanced.dataset.studioCard=panelId(change,'advanced');advanced.append(node('summary','Conditions, bounds & dependencies'),objectEditor(change,'statChange',true));box.append(advanced);return box;
}

function recipientEditor(value,inherit){
 const box=node('div',undefined,'studio-recipient');
 box.append(field(inherit?'Recipient override':'Default recipient',choice(inherit?[['','Use effect default'],...recipientOptions]:recipientOptions,value.recipient|| (inherit?'':'self'),v=>{if(v)value.recipient=v;else delete value.recipient;})),field('Exclude the source character',checkbox(value.excludeSource||false,v=>value.excludeSource=v)));
 box.append(node('p','The selected damage character is treated as on field. Enemy debuffs use the shared enemy; damage selectors still control which hits match.','studio-help'));return box;
}
function effectEditor(effect){
 const box=node('div',undefined,'studio-effect-form'),identity=node('div',undefined,'studio-grid');
 identity.append(field('Effect name',textControl(effect.name,v=>{effect.name=v;updateGeneratedId(effect,v);})),field('Category',choice(['talent','passive','team','constellation'],effect.category||'passive',v=>effect.category=v)),field('Source',textControl(effect.source,v=>effect.source=v)),field('Enabled by default',checkbox(effect.defaultEnabled!==false,v=>effect.defaultEnabled=v)));
 box.append(identity);if(data.schemaVersion===3)box.append(recipientEditor(effect,false));box.append(field('Description / assumed combat state',textControl(effect.description,v=>effect.description=v,true)));
 const activation=effect.activation??={event:'always'};
 box.append(field('When is this effect available?',referenceControl(activation.event,v=>activation.event=v,events.map(e=>[e,title(e)])),'The event describes the assumed trigger. Add conditions below to enforce input or team requirements.'));
 box.append(node('h3','Damage changes'));
 const modifiers=effect.formulaModifiers??=[];box.append(arrayEditor(modifiers,'formulaModifiers'));
 const advanced=node('details',undefined,'studio-advanced');advanced.dataset.studioCard=panelId(effect,'advanced');advanced.append(node('summary','Activation conditions, stat changes & other fields'),objectEditor(effect,'effect',true));box.append(advanced);
 return editorView(box,effect,'effect',()=>effectEditor(effect));
}
const damageTypeLabel=t=>`${title(t.id)} · ${t.element} · ${title(t.kind||'talent')}`;
function damageTypeEditor(type){
 const box=node('div',undefined,'studio-formula-form');
 const settings=node('div',undefined,'studio-grid'),rules=node('div',undefined,'studio-tag-choices');
 settings.append(field('Type ID',textControl(type.id,v=>type.id=v),'Talents save this reference. Keep existing IDs when other files already use them.'),
  field('Element',choice(['Pyro','Hydro','Electro','Cryo','Anemo','Geo','Dendro','Physical'],type.element,v=>{
   if(!type.resistanceInput||['enemyResistance',`enemy${type.element}Resistance`].includes(type.resistanceInput))type.resistanceInput=`enemy${v}Resistance`;
   type.element=v;if(v==='Physical')type.appliesElementalDamageBonus=false;
  })),
  field('Damage kind',choice([['talent','Talent damage'],['lunar','Lunar damage'],['stellar','Stellar damage'],['reaction','Reaction damage'],['artifactProc','Artifact damage']],type.kind||'talent',v=>type.kind=v)),
  field('Enemy DEF',choice([['standard','Apply DEF'],['bypass','Ignore DEF']],type.defenseBehavior||'standard',v=>type.defenseBehavior=v)));
 rules.append(
  field('Use elemental DMG bonus',checkbox(type.appliesElementalDamageBonus!==false,v=>{type.appliesElementalDamageBonus=v;})),
  field('Use other DMG bonuses',checkbox(type.appliesOtherDamageBonus!==false,v=>{type.appliesOtherDamageBonus=v;})),
  field('Can critically hit',checkbox(type.allowsCriticalHits!==false,v=>{type.allowsCriticalHits=v;})));
 box.append(settings,rules,node('h3','Built-in damage bonus'));
 if(type.innateDamageBonus){
  box.append(field('Amount unit',choice([['percentPoints','Percentage points (20 = 20%)'],['ratio','Ratio (0.2 = 20%)']],type.innateDamageBonus.unit,v=>type.innateDamageBonus.unit=v)),expressionEditor(type.innateDamageBonus.expression),button('Remove built-in bonus',()=>{delete type.innateDamageBonus;changed();render();}));
 }else box.append(node('p','Optional scaling shared by every hit of this type, such as Lunar-Bloom’s EM bonus.','studio-help'),button('+ Add built-in bonus',()=>{type.innateDamageBonus=contribution();changed();render();}));
 const advanced=node('details',undefined,'studio-advanced');advanced.dataset.studioCard=panelId(type,'advanced');advanced.append(node('summary','Resistance input & independent damage'),
  field('Enemy resistance input',textControl(type.resistanceInput||`enemy${type.element}Resistance`,v=>type.resistanceInput=v),'The main element uses the existing enemy RES control. Other elements get a separate control automatically.'),
  field('Independent damage proc',checkbox(type.independentProc===true,v=>type.independentProc=v),'Keep this damage separate from attack-triggered reactions.'));
 box.append(advanced);return editorView(box,type,'damageType',()=>damageTypeEditor(type));
}
function formulaEditor(formula){
 const box=node('div',undefined,'studio-formula-form');
 box.append(field('Damage instance name',textControl(formula.label,v=>{formula.label=v;updateGeneratedId(formula,v);})),field('Damage type',choice(sharedDamageTypes.map(t=>[t.id,damageTypeLabel(t)]),formula.damageType,v=>formula.damageType=v),'Shared across all characters. Controls the element, DEF, CRIT, damage bonuses, and built-in scaling.'),
  field('External weapon infusion',choice([['physicalOnly','Allow on physical attacks'],['overrideable','Allow overriding this attack’s element'],['locked','Cannot be overridden']],formula.infusionRule||'physicalOnly',v=>formula.infusionRule=v)),
  button('Edit shared damage types',()=>load('damage-types.json')),node('h3','How is its base damage calculated?'),expressionEditor(formula.base??=expr()));
 const classification=node('div',undefined,'studio-tag-choices');
 const tags=formula.tags??=[];
 [...new Set([...knownTags,...tags])].forEach(tag=>classification.append(field(title(tag),checkbox(tags.includes(tag),enabled=>{if(enabled&&!tags.includes(tag))tags.push(tag);else if(!enabled)tags.splice(tags.indexOf(tag),1);} ))));
 box.append(node('h3','Damage classification'),classification);
 const advanced=node('details',undefined,'studio-advanced');advanced.dataset.studioCard=panelId(formula,'advanced');advanced.append(node('summary','Classification, availability & per-hit modifiers'),objectEditor(formula,'formula',true));box.append(advanced);return editorView(box,formula,'formula',()=>formulaEditor(formula));
}

function objectEditor(obj,kind,advanced=false){
 if(!advanced&&kind==='inputDefinition')return inputDefinitionEditor(obj);
 if(!advanced&&kind==='condition')return conditionEditor(obj);
 if(!advanced&&kind==='statChange')return statChangeEditor(obj);
 if(!advanced&&kind==='modifier')return modifierEditor(obj);
 if(!advanced&&kind==='expression')return expressionEditor(obj);
 if(!advanced&&kind==='effect')return effectEditor(obj);
 if(!advanced&&kind==='formula')return formulaEditor(obj);
 if(!advanced&&kind==='damageType')return damageTypeEditor(obj);

 const box=node('div',undefined,'studio-nested'), grid=node('div',undefined,'studio-grid');
 for(const [key,value] of Object.entries(obj)){
 const field=node('div',undefined,'studio-field'), complex=value!==null&&typeof value==='object';
 if(complex||['description','impact'].includes(key))field.classList.add('studio-wide');
 const heading=node('div',undefined,'studio-field-header');heading.append(node('span',title(key)));
 field.append(heading);
 if(Array.isArray(value))field.append(arrayEditor(value,key,kind==='talentParameters'));
 else if(complex)field.append(objectEditor(value,nestedKind(key,value)));
 else if(kind==='overview'&&key==='schemaVersion')field.append(node('output',String(value)));
 else field.append(primitive(value,v=>{
   if(kind==='expression'&&key==='type'){
    const old={...obj};Object.keys(obj).forEach(k=>delete obj[k]);obj.type=v;
    if(v==='constant')obj.value=old.value??0;
    else if(['input','teamMax','context'].includes(v))obj.input=old.input??'';
    else if(v==='talentParameter')Object.assign(obj,{talent:old.talent||'combat1',parameter:old.parameter||'param1',levelInput:old.levelInput||'normalTalentLevel',...(old.characterKey?{characterKey:old.characterKey}:{})});
    else obj.operands=old.operands||[expr(),expr()];
   }else obj[key]=v;
 },key,kind));
 grid.append(field);
 }
 box.append(grid);
 let schema=kind==='overview'?topSchema:schemas[kind];
 if(kind==='overview'){
  const fields=path==='damage-types.json'?['schemaVersion','damageTypes']:path==='team-rules.json'?['inputs','effects','hexereiCharacters']:path.startsWith('weapons/')?['schemaVersion','key','name','inputs','effects','sources']:path.startsWith('artifacts/')?['schemaVersion','key','name','inputs','effects','sources']:path==='elemental-reactions.json'?['schemaVersion','characterLevelMultipliers','crystallizeLevelMultipliers','reactions','sources']:Object.keys(topSchema).filter(k=>!['characterLevelMultipliers','crystallizeLevelMultipliers','reactions','damageTypes','hexereiCharacters'].includes(k));
  schema=Object.fromEntries(fields.map(k=>[k,topSchema[k]]));
 }

 if(schema&&data.schemaVersion!==3&&['effect','modifier','statChange'].includes(kind))schema=Object.fromEntries(Object.entries(schema).filter(([key])=>!['recipient','excludeSource'].includes(key)));
 const missing=schema?Object.keys(schema).filter(k=>!(k in obj)):[];
 const add=node('div',undefined,'studio-add');
 if(missing.length){let chosen=missing[0];const picker=select(missing,chosen,v=>chosen=v);picker.setAttribute('aria-label','Property to add');add.append(picker,button('+ Add field',()=>{obj[chosen]=structuredClone(schema[chosen]);changed();render();}));}
 if(['statBindings','ownershipRequirements','talentParameters'].includes(kind)){
  const name=node('input');name.placeholder=kind==='talentParameters'?'combat3.param1':'Input or stat ID';name.setAttribute('aria-label','New mapping key');add.append(name,button('+ Add mapping',()=>{const key=name.value.trim();if(!key||key in obj)return;obj[key]=kind==='ownershipRequirements'?1:kind==='talentParameters'?[]:'';changed();render();}));
 }
 box.append(add);return editorView(box,kind==='overview'?data:obj,'object:'+kind+':'+advanced,()=>objectEditor(obj,kind,advanced));
}
function renderTabs(){
 if(data.schemaVersion===3){data.inputs??=[];data.talents??=[];}
 const sections=['Overview',...['inputs','damageTypes','talents','effects','reactions'].filter(k=>Array.isArray(data?.[k])&&(k!=='damageTypes'||path==='damage-types.json'))];
 if(!sections.includes(section))section='Overview';$('tabs').replaceChildren();
 sections.forEach(key=>{const b=button(title(key)+(key==='Overview'?'':` (${data[key].length}${key==='inputs'&&data.schemaVersion===3?' special':''})`),()=>{section=key;render();});b.setAttribute('aria-current',String(section===key));$('tabs').append(b);});
 return sections;
}
function render({reset=false}={}){
 const state=reset?null:rememberUI();if(reset)panelStates.clear();
 const target=reset?null:editOrigin?.closest('[data-studio-view]');
 if(target&&$('fields').contains(target)&&viewRenderers.has(target)){
  const related=[...$('fields').querySelectorAll('[data-studio-view]')].filter(n=>viewSubjects.get(n)===viewSubjects.get(target));
  for(const view of related.filter(n=>!related.some(parent=>parent!==n&&parent.contains(n)))){
   view.querySelectorAll('input[list]').forEach(n=>document.getElementById(n.getAttribute('list'))?.remove());
   view.replaceWith(viewRenderers.get(view)());
  }
  renderTabs();restoreUI($('fields'),state);syncSummaries();return;
 }
 root.querySelectorAll('datalist').forEach(n=>n.remove());
 const sections=renderTabs();
 $('fields').replaceChildren();if(section==='Overview'){
 const overview={};Object.entries(data).forEach(([k,v])=>{if(!sections.includes(k)&&!(data.schemaVersion===3&&k==='inputDefaults'))overview[k]=v;});
 // Edits to this view write directly to the document.
 const proxy=new Proxy(overview,{set(t,k,v){t[k]=v;data[k]=v;return true;},deleteProperty(t,k){delete t[k];delete data[k];return true;}});
 $('fields').append(objectEditor(proxy,'overview'));
 }else if(section==='inputs'&&data.schemaVersion===3)$('fields').append(characterInputsEditor());
 else {
  if(path==='damage-types.json')$('fields').append(node('p','These types are shared by all characters and equipment. Saving a change updates every hit that selects this type. You cannot remove a type while a formula still uses it.','studio-help'));
  $('fields').append(arrayEditor(data[section],section));
 }
 restoreUI($('fields'),state);
}
async function request(url,body){const headers={'Accept':'application/json'};if(body){headers['Content-Type']='application/json';headers[root.dataset.csrfHeader]=root.dataset.csrfToken;}const r=await fetch(api+url,{method:body?'POST':'GET',headers,body:body?JSON.stringify(body):undefined});let payload;try{payload=await r.json();}catch{throw new Error('Could not read the server response. Check your connection and sign-in.');}if(!r.ok)throw new Error(payload.error||payload.message||`Request failed (${r.status})`);return payload;}
let newOptions=[],importedDocument=null,importReading=false;
function newStatus(text,error=false){$('new-status').textContent=text;$('new-status').dataset.error=String(error);}
function newKindChanged(){
 const character=$('new-kind').value==='character';
 for(const id of ['character-field','element-field','weapon-field'])$('new-'+id).hidden=!character;
}
function newPath(){return ({character:'',weapon:'weapons/',artifact:'artifacts/'})[$('new-kind').value]+$('new-key').value.trim()+'.json';}
async function openNew(){
 if(busy)return;importedDocument=null;$('new-form').reset();newKindChanged();
 $('new-character').replaceChildren(new Option('Custom character…',''));
 newStatus('Choose a character, enter details, or import calculation JSON.');$('new-dialog').showModal();
 try{newOptions=await request('/new-file-options');
  for(const item of newOptions){const option=new Option(item.name+(item.exists?' · already exists':''),item.key);option.disabled=item.exists;$('new-character').append(option);}
 }catch(e){newStatus('Character suggestions are unavailable. You can still enter details or import a file.');}
}
$('new').addEventListener('click',openNew);
$('new-kind').addEventListener('change',newKindChanged);
$('new-character').addEventListener('change',()=>{
 const item=newOptions.find(c=>c.key===$('new-character').value);if(!item)return;
 $('new-key').value=item.key;$('new-name').value=item.name;$('new-element').value=item.element;$('new-weapon').value=item.weaponType;
});
$('new-cancel').addEventListener('click',()=>{if(!busy)$('new-dialog').close();});
$('new-dialog').addEventListener('cancel',e=>{if(busy)e.preventDefault();});
$('new-import').addEventListener('change',async()=>{
 const file=$('new-import').files[0];if(!file){importedDocument=null;return;}
 importReading=true;$('new-create').disabled=true;
 try{
  if(file.size>2000000)throw new Error('This JSON file is too large.');
  const parsed=JSON.parse(await file.text());
  if(!parsed||typeof parsed!=='object'||Array.isArray(parsed)||!parsed.key||!parsed.name)throw new Error('Choose a calculation JSON file with a key and name, rather than a generator input packet.');
  importedDocument=parsed;$('new-kind').value=parsed.characterKey?'character':parsed.effects?.some(e=>'pieces' in e)?'artifact':$('new-kind').value==='artifact'?'artifact':'weapon';
  $('new-key').value=parsed.key;$('new-name').value=parsed.name;
  if(parsed.element)$('new-element').value=parsed.element;if(parsed.weaponType)$('new-weapon').value=parsed.weaponType;
  newKindChanged();newStatus('JSON loaded. Create file will validate it before saving.');
 }catch(e){importedDocument=null;$('new-import').value='';newStatus(e.message,true);}
 finally{importReading=false;$('new-create').disabled=false;}
});
$('new-form').addEventListener('submit',async e=>{
 e.preventDefault();if(busy||importReading)return;
 const nextPath=newPath(),key=$('new-key').value.trim(),name=$('new-name').value.trim(),kind=$('new-kind').value;
 if(!key||!name){newStatus('Enter a key and name.',true);return;}
 if(files.includes(nextPath)){newStatus('This file already exists. Open it from the library instead.',true);return;}
 if(dirty&&!window.confirm('Discard unsaved changes to the current file and create a new one?'))return;
 let draft=importedDocument?structuredClone(importedDocument):{schemaVersion:kind==='character'?3:2,key,name,inputs:[],effects:[],sources:[]};
 draft.key=key;draft.name=name;
 if(kind==='character'){
  draft.characterKey=key;draft.element=$('new-element').value;draft.weaponType=$('new-weapon').value;
  if(!importedDocument){const item=newOptions.find(c=>c.key===key);draft.characterDataResource=item?.characterDataResource||'';
   draft.talents=structuredClone(item?.talents||['normal','skill','burst'].map(key=>({key,name:title(key),formulas:[]})));
   draft.defaultTeam=[key];draft.inputDefaults={attack:1500,maxHp:30000,defense:1000,normalTalentLevel:10,skillTalentLevel:10,burstTalentLevel:10};}
 }
 busy=true;controls();$('new-create').disabled=true;$('new-cancel').disabled=true;newStatus('Validating the new file and team calculations…');
 try{
  const result=await request('/create',{path:nextPath,content:JSON.stringify(draft,null,2)+'\n'});
  data=JSON.parse(result.content);revision=result.revision;path=result.path;dirty=false;section='Overview';
  files=[...new Set([...files,path])].sort();$('search').value='';$('name').textContent=path;$('dirty').textContent='All changes saved';
  $('new-dialog').close();status(result.message);render({reset:true});
  request('/catalog').then(next=>{catalog=next;renderFiles();}).catch(()=>{});
 }catch(error){newStatus(error.message,true);}
 finally{busy=false;controls();$('new-create').disabled=false;$('new-cancel').disabled=false;renderFiles();}
});
async function load(next){if(busy)return;if(dirty&&!window.confirm('Discard unsaved changes to this file?'))return;busy=true;controls();try{const result=await request('/file?path='+encodeURIComponent(next));data=JSON.parse(result.content);revision=result.revision;path=next;dirty=false;section='Overview';$('name').textContent=next;$('dirty').textContent='All changes saved';status('Ready to edit. Changes stay in this form until you save.');render({reset:true});renderFiles();}catch(e){status(e.message,true);}finally{busy=false;controls();renderFiles();}}
function renderFiles(){const query=$('search').value.toLowerCase();$('files').replaceChildren();files.filter(f=>f.toLowerCase().includes(query)).forEach(f=>{const b=button(f,()=>load(f));const character=catalog.find(c=>c.file===f);if(character){const badge=node('small',character.damageAvailable?'Damage + support':'Support only','studio-file-badge');b.append(badge);}b.setAttribute('aria-current',String(f===path));b.disabled=busy;$('files').append(b);});}
async function submit(save){if(!data||busy)return;busy=true;controls();status(save?'Validating and saving…':'Checking formula definitions and team calculations…');try{const result=await request(save?'/save':'/validate',{path,content:JSON.stringify(data,null,2)+'\n',revision});if(save){revision=result.revision;dirty=false;$('dirty').textContent='All changes saved';request('/catalog').then(next=>{catalog=next;renderFiles();}).catch(()=>{});request('/damage-types').then(next=>{sharedDamageTypes=next;}).catch(()=>{});}status(result.message);}catch(e){status(e.message,true);}finally{busy=false;controls();}}
$('search').addEventListener('input',renderFiles);$('reload').addEventListener('click',()=>load(path));$('validate').addEventListener('click',()=>submit(false));$('save').addEventListener('click',()=>submit(true));
window.addEventListener('beforeunload',e=>{if(dirty){e.preventDefault();e.returnValue='';}});
window.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key==='s'){e.preventDefault();submit(true);}});
request('/damage-types').then(result=>{sharedDamageTypes=result;if(data)render();}).catch(error=>status(error.message,true));
request('/standard-inputs').then(result=>{standardInputTemplates=result;if(data)render();}).catch(error=>status(error.message,true));
request('/catalog').then(result=>{catalog=result;renderFiles();if(data)render();}).catch(()=>{});
request('/files').then(result=>{files=result;renderFiles();status('Choose a formula file from the library.');}).catch(e=>status(e.message,true));
})();
