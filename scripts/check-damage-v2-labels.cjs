const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('src/main/resources/static/js/damage-calculator-v2.js','utf8');
const functions=['elementalInput','inputClassName','inputTooltip','inputToken'].map(name=>{
 const start=source.indexOf('    function '+name+'(');assert.ok(start>=0);
 const end=source.indexOf('\n    function ',start+1);return source.slice(start,end);
}).join('\n');
for(const element of ['Pyro','Hydro','Cryo','Electro','Dendro','Anemo','Geo','Physical']){
 const inputs=new Map([
  ['elementalDamageBonus',{label:'Old Dendro bonus',initialValueSource:{valueKey:'stat:'+element.toLowerCase()+'_dmg_'}}],
  ['enemyResistance',{label:'Old Dendro RES'}],
  ['pyroDamageBonus',{label:'Pyro bonus',initialValueSource:{valueKey:'stat:pyro_dmg_'}}],
  ['enemyPyroResistance',{label:'Enemy Pyro RES'}],
 ]);
 const context=vm.createContext({profile:{element,effects:[]},inputById:inputs,
  calculation:{inputs:{},effectiveStats:{},statChanges:[]},inputTokenClass:{},inputHintTag:{},
  hintCatalog:new Map([['elementalDamageBonus',{name:'Dendro bonus',tooltip:'Elemental damage bonus.'}],['enemyResistance',{name:'Dendro RES',tooltip:'Resistance to this hit.'}]]),
  numberFormatter:new Intl.NumberFormat('en'),formatInput:(_,v)=>String(v),inputSource:()=>'',hint:()=>'',
  token:(text,className,tooltip)=>({text,className,tooltip})});
 vm.runInContext(functions,context);
 for(const [id,kind,suffix] of [['elementalDamageBonus','bonus','DMG Bonus'],['enemyResistance','resistance','RES']]){
  const token=vm.runInContext(`inputToken('${id}','10%')`,context);
  assert.ok(token.tooltip.startsWith(element+' '+suffix),token.tooltip);
  assert.ok(token.className.includes('element-'+element.toLowerCase()),token.className);
  const infused=vm.runInContext(`inputToken('${id}','10%',{element:'Pyro',kind:'${kind}'})`,context);
  assert.ok(infused.tooltip.startsWith('Pyro '+suffix),infused.tooltip);
  assert.ok(infused.className.includes('element-pyro'));
 }
 assert.ok(vm.runInContext(`inputToken('enemyPyroResistance','10%')`,context).tooltip.startsWith('Pyro RES'));
 assert.ok(vm.runInContext(`inputToken('pyroDamageBonus','15%')`,context).tooltip.startsWith('Pyro DMG Bonus'));
}
console.log('Exact calculation label checks passed: all elements, foreign bonuses/resistance, and infusion overrides.');
