// Explicit combat recipes, not a natural-language parser. Run with --check to compare without writing.
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..'),out=path.join(root,'src/main/resources/data/damage-formulas-v2/weapons');
const snapshot=JSON.parse(fs.readFileSync(path.join(root,'build/generated/bundled-catalog/data/bundled-catalog-snapshot.json'),'utf8'));
const excluded=new Set(['Name','Region','Source','Type','Secondary Attributes','Secondary Stat','Version Released','Gallery']);
const clean=s=>s.replace(/<[^>]*>/g,'').replace(/&nbsp;/g,' ').replace(/&amp;/g,'&').replace(/&gt;/g,'>').replace(/&lt;/g,'<');
const catalogue=snapshot.hoyolabWeaponPages.map(page=>{
 const meta=snapshot.hoyolabWeapons.find(w=>String(w.entryPageId)===String(page.id));
 const fields=page.modules.flatMap(m=>m.components).filter(c=>c.component_id==='baseInfo').flatMap(c=>JSON.parse(c.data).list);
 const passive=fields.filter(f=>!excluded.has(f.key)&&!f.value.join('').startsWith('$[')&&f.value.join('').length>55);
 return {key:page.name.toLowerCase().replace(/[^a-z0-9]/g,''),name:page.name,rarity:Number(meta.filters.weapon_rarity?.[0]?.[0]),
  source:`https://wiki.hoyolab.com/pc/genshin/entry/${page.id}`,description:passive.map(f=>clean(f.value.join(' '))).join(' ')};
});
const C=value=>({type:'constant',value}),E=x=>typeof x==='number'?C(x):x,I=input=>({type:'input',input}),L=id=>I('local:'+id),W=id=>I('wearer:'+id),X=input=>({type:'context',input});
const op=(type,...operands)=>({type,operands:operands.map(E)}),add=(...v)=>op('add',...v),mul=(...v)=>op('multiply',...v),min=(...v)=>op('min',...v),max=(...v)=>op('max',...v),clamp=(v,lo,hi)=>min(hi,max(lo,v));
const R=(a,b=a*2)=>add(a,mul((b-a)/4,add(L('refinement'),-1)));
// Piecewise-linear interpolation; controls have integer steps, so each rank/stack is exact.
const table=(input,values,start=0)=>add(values[0],...values.slice(1).map((v,i)=>mul(v-values[i],clamp(add(input,-start-i),0,1))));
const rank=values=>table(L('refinement'),values,1),hex=add(1,mul(.75,clamp(add(X('hexereiCount'),-1),0,1))),moon=clamp(add(X('moonsignLevel'),-1),0,1);
const NA=['normalAttack'],CA=['chargedAttack'],NC=[...NA,...CA],NCP=[...NC,'plungingAttack'],SK=['elementalSkill'],BU=['elementalBurst'],SB=[...SK,...BU],PL=['plungingAttack'];
const normal={damageKinds:['talent','artifactProc']},elemental={...normal,elements:['Pyro','Hydro','Electro','Cryo','Dendro','Anemo','Geo']};
const target=tags=>tags?{...normal,anyTags:tags}:normal, lunar=tag=>({damageKinds:['lunar'],requiredTags:[tag]}),stellar=tag=>({damageKinds:['stellar'],requiredTags:[tag]});
const bonus=(value,targets=normal)=>({targets,damageBonus:{unit:'percentPoints',expression:E(value)}});
const crit=(value,tags,field='additionalDamage')=>({targets:target(tags),crit:{[field]:{unit:'percentPoints',expression:E(value)}}});
const flat=(value,tags)=>({targets:target(tags),base:E(value)});
const stat=(name,value,phase=0)=>({stat:name,operation:'flatAdd',value:E(value),phase});
const pct=(name,value)=>stat(name,mul(.01,value,I('recipient:'+{attack:'baseAttack',maxHp:'baseHp',defense:'baseDefense'}[name])));
let current;
function control(id,label,maxValue=1,defaultValue=0,step=1,minValue=0){
 if(!current.inputs.some(x=>x.id===id))current.inputs.push({id,label,defaultValue,min:minValue,max:maxValue,step});return L(id);
}
const stacks=(maxValue,id='stacks',label='Active passive stacks')=>control(id,label,maxValue);
const toggle=(id='active',label='Passive buff active for this hit')=>control(id,label);
function offField(){const value=toggle('offField','Wearer is off field for this hit');current.inputs.find(i=>i.id==='offField').defaultContext='offField';return value;}
const condition=(id,value=1,type='inputAtLeast')=>({type,input:'local:'+id,value});
const ctxCondition=(input,value)=>({type:'contextAtLeast',input:'context:'+input,value});
function effect(id,{stats=[],mods=[],conditions=[],recipient='wearer',description='',group,priority,formulas=[]}={}){
 const e={id,name:current.name+' · '+id.replace(/([A-Z])/g,' $1'),source:'Weapon',category:'weapon',defaultEnabled:true,
  description:(description||current.description)+' Combat snapshot: set the controls for this hit; durations, stack consumption, healing, energy and cooldowns are not simulated.',
  activation:{event:conditions.length?'buffActive':'always',conditions},statChanges:stats,formulaModifiers:mods};
 if(group){e.stackingGroup=group;e.stackingPriority=E(priority??L('refinement'));}
 current.effects.push({recipient,effect:e,...(formulas.length?{formulas}:{})});
}
const permanent=(stats=[],mods=[])=>effect('passive',{stats,mods});
function active(stats=[],mods=[],id='active',label='Passive buff active for this hit',extra={}){toggle(id,label);effect(id,{stats,mods,conditions:[condition(id)],...extra});}
const elementalBonus=value=>bonus(value,elemental);
function proc(value,{id='proc',statName='attack',conditions=[],critRate=false,description,enabled=true}={}){
 if(enabled){toggle(id,'Weapon damage proc available for this hit');conditions=[...conditions,condition(id)];}
 effect(id,{conditions,description,formulas:[{id,label:current.name+' · '+id,damageType:'directPhysical',infusionRule:'locked',tags:['weaponProc','noReaction'],base:mul(.01,value,W(statName)),...(critRate?{crit:{additionalRate:{unit:'percentPoints',expression:C(100)}}}:{})}]});
}
const recipes=new Map();function define(keys,fn){for(const key of keys.split(' ')){if(recipes.has(key))throw Error('Duplicate recipe '+key);recipes.set(key,fn);}}
const nonDamage=new Set();function utility(keys){for(const key of keys.split(' ')){nonDamage.add(key);define(key,()=>effect('utility',{description:current.description+' This passive has no direct per-hit damage modifier.'}));}}
// Previously authored and verified definitions remain the source of truth for these four weapons.
const preserved=new Set(['athousandfloatingdreams','reliquaryoftruth','nightweaverslookingglass','nocturnescurtaincall']);

define('crimsonmoonssemblance',()=>{control('bondOfLife','Bond of Life (% of Max HP)',200,0,.1);effect('bondOfLife',{mods:[bonus(R(12,28))],conditions:[condition('bondOfLife',.001)]});effect('greaterBond',{mods:[bonus(R(24,56))],conditions:[condition('bondOfLife',30)]});});
define('aquilafavonia',()=>{permanent([pct('attack',R(20))]);proc(R(200,320));});
define('skywardharp',()=>{permanent([stat('critDamage',R(20))]);proc(125);});
define('skywardatlas',()=>{permanent([],[elementalBonus(R(12))]);proc(R(160));});
define('skywardspine',()=>{permanent([stat('critRate',R(8))]);proc(R(40,100));});
define('skywardpride',()=>{permanent([],[bonus(R(8))]);proc(R(80));});
define('skywardblade',()=>{permanent([stat('critRate',R(4))]);proc(R(20));});
define('staffofhoma',()=>{permanent([pct('maxHp',R(20)),stat('attack',mul(.01,R(.8),W('maxHp')),10)]);active([stat('attack',mul(.01,R(1,1.8),W('maxHp')),10)],[],'lowHp','Current HP below 50%');});
define('primordialjadecutter',()=>permanent([pct('maxHp',R(20)),stat('attack',mul(.01,R(1.2),W('maxHp')),10)]));
define('engulfinglightning',()=>{active([stat('energyRecharge',R(30,50))],[],'burst','Burst ER buff active (12s)');effect('rechargeConversion',{stats:[pct('attack',min(R(80,120),mul(.01,R(28),max(0,add(W('energyRecharge'),-100)))))].map(s=>({...s,phase:10}))});});
define('redhornstonethresher',()=>permanent([pct('defense',R(28))],[flat(mul(.01,R(40),W('defense')),NC)]));
define('everlastingmoonglow',()=>permanent([stat('healingBonus',R(10))],[flat(mul(.01,R(1,3),W('maxHp')),NA)]));
define('lightoffoliarincision',()=>{permanent([stat('critRate',R(4))]);active([],[flat(mul(.01,R(120),W('elementalMastery')),[...NA,...SK])]);});
define('hunterspath',()=>{permanent([],[elementalBonus(R(12))]);active([],[flat(mul(.01,R(160),W('elementalMastery')),CA)],'hunt','Tireless Hunt active (hit/10s allowance remains)');});
define('staffofthescarletsands',()=>{const s=stacks(3);permanent([stat('attack',mul(.01,add(R(52),mul(R(28),s)),W('elementalMastery')),10)]);});
define('keyofkhajnisut',()=>{const s=stacks(3);permanent([pct('maxHp',R(20)),stat('elementalMastery',mul(.01,R(.12),s,W('maxHp')),10)]);effect('grandHymn',{stats:[stat('elementalMastery',mul(.01,R(.2),W('maxHp')),10)],conditions:[condition('stacks',3)],recipient:'party',group:'keyOfKhajNisut.party'});});
define('wolfsgravestone',()=>{permanent([pct('attack',R(20))]);active([pct('attack',R(40))],[],'partyBuff','Party ATK buff after hitting a target below 30% HP (12s)',{recipient:'party',group:'wolfsGravestone.party'});});
define('aquasimulacra',()=>{permanent([pct('maxHp',R(16))]);active([],[bonus(R(20))],'nearby','An opponent is nearby');});
define('lostprayertothesacredwinds',()=>permanent([],[elementalBonus(mul(R(8),stacks(4)))]));
define('primordialjadewingedspear',()=>{const s=stacks(7);permanent([pct('attack',mul(R(3.2,6),s))]);effect('maximumStacks',{mods:[bonus(R(12))],conditions:[condition('stacks',7)]});});
define('vortexvanquisher theunforged summitshaper memoryofdust',()=>{const s=stacks(5),shield=toggle('shield','Protected by a shield');permanent([stat('shieldStrength',R(20)),pct('attack',mul(R(4),s,add(1,shield)))]);});
define('thunderingpulse',()=>{const s=stacks(3);permanent([pct('attack',R(20))],[bonus(mul(table(s,[0,12,24,40]),R(1,2)),target(NA))]);});
define('mistsplitterreforged',()=>{const s=stacks(3);permanent([],[elementalBonus(R(12)),bonus(mul(table(s,[0,8,16,28]),R(1,2)),{...normal,elements:['wearer']})]);});
define('amosbow',()=>{const s=stacks(5,'flightSteps','Arrow flight time (0.1s steps)');permanent([],[bonus(add(R(12),mul(R(8),s)),target(NC))]);});
define('polarstar',()=>{const s=stacks(4);permanent([pct('attack',mul(table(s,[0,10,20,30,48]),R(1,2)))],[bonus(R(12),target(SB))]);});
define('calamityqueller',()=>{const s=stacks(6);permanent([pct('attack',mul(R(3.2),s,add(1,offField())))],[elementalBonus(R(12))]);});
define('harangeppakufutsu',()=>{permanent([],[elementalBonus(R(12))]);permanentBuff('ripplingUpheaval',[bonus(mul(R(20),stacks(2,'consumedStacks','Wavespike stacks consumed (buff active)')),target(NA))]);});
function permanentBuff(id,mods){effect(id,{mods});}
define('kagurasverity',()=>{const s=stacks(3);permanent([],[bonus(mul(R(12),s),target(SK))]);effect('threeDances',{conditions:[condition('stacks',3)],mods:[elementalBonus(R(12))]});});
for(const [key,base] of [['freedomsworn',10],['elegyfortheend',60],['songofbrokenpines',16]])define(key,()=>{
 if(key==='freedomsworn')permanent([],[bonus(R(base))]);else permanent([key==='elegyfortheend'?stat('elementalMastery',R(base)):pct('attack',R(base))]);
 active([pct('attack',R(20))],[],'movement','Millennial Movement party buff active (12s)',{recipient:'party',group:'millennialMovement.attack'});
 if(key==='freedomsworn')effect('resistance',{mods:[bonus(R(16),target(NCP))],conditions:[condition('movement')],recipient:'party',group:'millennialMovement.normalChargedPlunge'});
 if(key==='elegyfortheend')effect('farewell',{stats:[stat('elementalMastery',R(100))],conditions:[condition('movement')],recipient:'party',group:'millennialMovement.mastery'});
});
define('tulaytullahsremembrance',()=>{const progress=control('progress','Normal Attack buff progress (0–10; each step = one second or half a hit)',10,0,.1);permanent([],[bonus(mul(R(4.8),progress),target(NA))]);});
define('beaconofthereedsea',()=>{active([pct('attack',R(20))],[],'skillHit','Skill hit buff active (8s)');active([pct('attack',R(20))],[],'damageTaken','Damage taken buff active (8s)');active([pct('maxHp',R(32))],[],'unshielded','Not protected by a shield');});
define('jadefallssplendor',()=>active([],[bonus(min(R(12,44),mul(R(.3,1.1),W('maxHp'),.001)),{...normal,elements:['wearer']})]));
define('thefirstgreatmagic',()=>permanent([pct('attack',mul(R(16),min(3,add(1,X('sameElementAllies')))))],[bonus(R(16),target(CA))]));
define('tomeoftheeternalflow',()=>{permanent([pct('maxHp',R(16))]);permanentBuff('hpChanges',[bonus(mul(R(14,30),stacks(3)),target(CA))]);});
define('cashflowsupervision',()=>{const s=stacks(3);permanent([pct('attack',R(16))],[bonus(mul(R(16),s),target(NA)),bonus(mul(R(14),s),target(CA))]);});
define('splendoroftranquilwaters',()=>permanent([pct('maxHp',mul(R(14),stacks(2,'partyHpChanges','Other members HP-change stacks')))],[bonus(mul(R(8),stacks(3,'ownHpChanges','Own HP-change stacks')),target(SK))]));
define('verdict',()=>permanent([pct('attack',R(20))],[bonus(mul(R(18),stacks(2,'seals','Unconsumed Seals')),target(SK))]));
define('cranesechoingcall',()=>active([],[bonus(R(28,80),target(PL))],'plungeBuff','Party Plunging Attack buff active (20s)',{recipient:'party',group:'cranesEchoingCall'}));
define('urakumisugiri',()=>{const f=add(1,toggle('geoHit','Nearby active character dealt Geo DMG (15s)'));permanent([pct('defense',R(20))],[bonus(mul(R(16),f),target(NA)),bonus(mul(R(24),f),target(SK))]);});
define('silvershowerheartstrings',()=>{const s=stacks(3,'remedy','Remedy stacks');permanent([pct('maxHp',mul(table(s,[0,12,24,40]),R(1,2)))]);active([],[crit(R(28),BU,'additionalRate')],'burstCrit','3-stack Burst CRIT buff active (includes 4s grace period)');});
define('absolution',()=>permanent([stat('critDamage',R(20))],[bonus(mul(R(16),stacks(3)))]));
define('lumidouceelegy',()=>permanent([pct('attack',R(15,31))],[bonus(mul(R(18,38),stacks(2)))]));
define('fangofthemountainking',()=>permanent([],[bonus(mul(R(10),stacks(6)),target(SB))]));
define('surfsup',()=>permanent([pct('maxHp',R(20))],[bonus(mul(R(12),stacks(4,'summer','Scorching Summer stacks')),target(NA))]));
define('peakpatrolsong',()=>{const s=stacks(2);permanent([pct('defense',mul(R(8),s))],[elementalBonus(mul(R(10),s))]);active([],[elementalBonus(min(R(25.6),mul(R(8),W('defense'),.001)))],'partyBuff','Party elemental bonus active after reaching/refreshing 2 stacks (15s)',{recipient:'party',group:'peakPatrolSong'});});
define('astralvulturescrimsonplumage',()=>{active([pct('attack',R(24))],[],'swirl','Swirl / Stellar Swirl ATK buff active (12s)');const amount=mul(table(min(2,X('differentElementAllies')),[0,20,48]),R(1,2));permanent([],[bonus(amount,target(CA)),bonus(mul(.5,amount),target(BU))]);});
define('athousandblazingsuns',()=>{const f=add(1,mul(.75,toggle('nightsoul','In Nightsoul Blessing')));active([stat('critDamage',mul(R(20),f)),pct('attack',mul(R(28),f))],[],'brilliance','Scorching Brilliance active');});
define('starcallerswatch',()=>{permanent([stat('elementalMastery',R(100))]);active([],[bonus(R(28))],'mirror','Mirror of Night active (15s)',{recipient:'activeCharacter',group:'starcallersWatch'});});
define('sunnymorningsleepin',()=>{for(const [id,n] of [['swirl',120],['skillHit',96],['burstHit',32]])active([stat('elementalMastery',R(n))],[],id,id+' buff active');});
define('vividnotions',()=>{permanent([pct('attack',R(28))]);active([],[crit(R(28),PL)],'dawn','Dawn First Hue active');active([],[crit(R(40),PL)],'twilight','Twilight Splendor active');});
define('symphonistofscents',()=>{permanent([pct('attack',mul(R(12),add(1,offField())))]);active([pct('attack',R(32))],[],'sweetEchoes','Wearer Sweet Echoes active after healing (3s)');active([pct('attack',R(32))],[],'healedActive','Active character was healed by this wearer (3s)',{recipient:'activeOther',group:'symphonistOfScents.healed'});});
define('azurelight',()=>{active([pct('attack',R(24))],[],'skill','Skill ATK buff active (12s)');toggle('zeroEnergy','Wearer has zero Energy');effect('zeroEnergy',{conditions:[condition('skill'),condition('zeroEnergy')],stats:[pct('attack',R(24)),stat('critDamage',R(40))]});});
define('fracturedhalo',()=>{active([pct('attack',R(24))],[],'skillOrBurst','Skill/Burst ATK buff active (20s)');active([],[bonus(R(40),lunar('lunarCharged'))],'edict','Electrifying Edict active after creating a shield (20s)',{recipient:'party',group:'fracturedHalo.edict'});});
define('bloodsoakedruins',()=>{active([],[bonus(R(36,84),lunar('lunarCharged'))],'burst','Within 3.5s after Burst');active([stat('critDamage',R(28))],[],'requiem','Requiem of Ruin active (6s)');});
define('athameartis',()=>{permanent([],[crit(R(16),BU)]);active([pct('attack',mul(R(20),hex))],[],'blade','Blade of the Daylight Hours active');effect('partyBlade',{conditions:[condition('blade')],stats:[pct('attack',mul(R(16),hex))],recipient:'activeOther',group:'athameArtis.party'});});
define('thedaybreakchronicles',()=>{for(const [id,t] of [['normal',NA],['skill',SK],['burst',BU]])effect(id,{mods:[bonus(mul(R(10),control(id,id+' DMG bonus steps (0–6; adjust for time and hits)',6,0,.1)),target(t))]});});
define('lightbearingmoonshard',()=>{permanent([pct('defense',R(20))]);active([],[bonus(R(64),lunar('lunarCrystallize'))],'skill','Within 5s after Skill');});
define('gestofthemightywolf',()=>{const s=stacks(4,'hymn','Four Winds Hymn stacks');permanent([stat('critDamage',mul(R(7.5,15.5),s,clamp(add(X('hexereiCount'),-1),0,1)))],[bonus(mul(R(7.5,15.5),s))]);});
define('goldenfrostboundoath',()=>{permanent([pct('defense',R(16))]);active([],[bonus(R(40),{...normal,elements:['Geo']}),bonus(R(40),lunar('lunarCrystallize'))],'favor','Frost Fae Favor active (6s)');toggle('moondrift','Moondrifts nearby');effect('mischief',{conditions:[condition('favor'),condition('moondrift')],mods:[bonus(R(20),{...normal,elements:['Geo']}),bonus(R(20),lunar('lunarCrystallize'))],recipient:'others',group:'goldenFrostboundOath.party'});});
define('angelosheptades',()=>{permanent([pct('attack',R(12))]);toggle('pathfinder','Pathfinder Light active after creating a shield (20s)');toggle('recipientOffField','Selected damage character is off field (Hexerei extension)');const amount=min(R(26,58),mul(R(10,22),W('attack'),.001));effect('pathfinder',{conditions:[condition('pathfinder'),condition('recipientOffField',0,'inputAtMost')],mods:[bonus(amount)],recipient:'activeCharacter',group:'angelosHeptades.pathfinder'});effect('hexereiExtension',{conditions:[condition('pathfinder'),condition('recipientOffField'),ctxCondition('hexereiCount',2),ctxCondition('recipientHexerei',1)],mods:[bonus(mul(.5,amount))],recipient:'party',group:'angelosHeptades.pathfinder'});});
define('disasterandremorse',()=>{active([],[bonus(mul(R(40),hex),target(NC))],'unforgivable','Unforgivable active');active([],[bonus(mul(R(40),hex),target(SB))],'irreparable','Irreparable active');});
define('ateaspoonoftranscendence',()=>{permanent([pct('attack',R(28))]);permanentBuff('transcendence',[bonus(mul(R(16),stacks(3)),{damageKinds:['stellar'],anyTags:['stellarConduct','stellarSwirl']})]);});
define('whitelakefrostfeather',()=>{const s=stacks(3);permanent([pct('attack',mul(R(8),s))]);effect('threeStacks',{conditions:[condition('stacks',3)],mods:[{targets:stellar('stellarGlimmer'),crit:{additionalDamage:{unit:'percentPoints',expression:R(50,110)}}}]});});
define('exaiphanesblade',()=>{const elements=control('resonatedElements','Elements the Traveler has resonated with',7);effect('traveler',{conditions:[ctxCondition('wearerIsTraveler',1)],stats:[stat('critDamage',mul(rank([0,6,6,6,6]),elements))]});toggle('hit','ATK buff after hitting an opponent active (8s)');effect('hit',{conditions:[ctxCondition('wearerIsTraveler',1),condition('hit')],stats:[pct('attack',rank([16,20,24,32,40]))]});});
define('beyondthechrysalis',()=>{active([stat('critDamage',R(56,120))],[],'devotion','Winds of Devotion active (10s)');active([],[bonus(R(36),stellar('stellarSwirl'))],'defiance','Winds of Defiance active (10s)');});
define('hymnofthemaelstrom',()=>{const s=stacks(3,'vintage','Vatsamonga Vintage stacks'),f=add(1,mul(.75,toggle('frozenOrSwirl','Frozen/Stellar Swirl enhancement active (5s)')));permanent([stat('healingBonus',R(4)),pct('maxHp',mul(R(4),s,f))]);effect('activeAttack',{recipient:'activeCharacter',group:'hymnOfTheMaelstrom',stats:[{...pct('attack',mul(s,f,min(R(8),mul(R(.4),max(0,add(W('maxHp'),-40000)),.001)))),phase:10}]});});

// Four-star weapons: each control describes the assumed combat state, not an uptime simulation.
define('breezebornerefrain',()=>{permanent([stat('energyRecharge',R(20))]);active([],[bonus(R(24),stellar('stellarGlimmer'))],'viper','Thus Lied the Viper active (12s)',{recipient:'party',group:'breezeborneRefrain'});});
define('wintersheavyheart',()=>{const radiance=toggle('radiance','Radiance: Stellar Glimmer active'),count=min(4,add(X('cryoCount'),X('electroCount')));permanent([stat('elementalMastery',add(mul(R(24),X('cryoCount'),add(1,mul(-1,radiance))),mul(R(20),count,radiance))),pct('attack',mul(R(4.8),X('electroCount'),add(1,mul(-1,radiance))))],[bonus(mul(R(6),count,radiance),stellar('stellarGlimmer'))]);});
define('silverlight',()=>permanent([stat('elementalMastery',mul(R(52),stacks(2)))]));
define('newbough',()=>{const s=stacks(3),f=toggle('radiance','Radiance: Stellar Glimmer active');permanent([pct('attack',mul(s,add(R(4),mul(R(2),f)))),stat('elementalMastery',mul(R(20),s,add(1,mul(-1,f))))],[bonus(mul(R(8),s,f),stellar('stellarGlimmer'))]);});
define('jadevista',()=>permanent([stat('elementalMastery',mul(R(64),min(3,X('sameElementAllies')))),pct('attack',mul(R(12),min(X('differentElementAllies'),max(0,add(3,mul(-1,X('sameElementAllies')))))))]));
define('covenantoffrostandsnow',()=>active([stat('elementalMastery',R(120))]));
define('clashofkings',()=>active([pct('attack',R(20)),stat('elementalMastery',R(100))]));
define('echoesoftheheart',()=>{active([stat('elementalMastery',R(60))],[],'reaction','Elemental Reaction buff active');active([],[bonus(R(16),stellar('stellarGlimmer'))],'glimmer','Stellar Glimmer buff active');});
define('bladeofatonement',()=>{active([stat('elementalMastery',R(64))],[],'reaction','Elemental Reaction buff active');active([pct('attack',R(16))],[],'glimmer','Stellar Glimmer buff active');});
define('emberwell',()=>{active([pct('attack',R(16))],[],'reaction','Elemental Reaction buff active');active([],[bonus(R(16),stellar('stellarGlimmer'))],'glimmer','Stellar Glimmer buff active');});
define('frostbreath',()=>active([pct('attack',R(20))],[],'reaction','Cryo/Hydro-related reaction buff active (15s)'));
define('songofthevigil',()=>active([pct('attack',R(20))],[],'glimmer','Stellar Glimmer ATK buff active (12s)'));
define('forgedbythegoldenmelody',()=>{control('movement','Current movement: 0 none, 1 ATK, 2 EM, 3 Stellar Glimmer',3);control('contrapuntal','Contrapuntal movement: 0 none, 1 ATK, 2 EM, 3 Stellar Glimmer',3);for(const id of ['movement','contrapuntal'])for(const [n,s,m] of [[1,[pct('attack',R(18))],[]],[2,[stat('elementalMastery',R(120))],[]],[3,[],[bonus(R(28),stellar('stellarGlimmer'))]]])effect(id+n,{conditions:[condition(id,n),condition(id,n,'inputAtMost')],stats:s,mods:m});});
define('hereticsmoltenblade',()=>{const distance=control('distance','Travel-distance bonus progress (0 = minimum, 1 = maximum)',1,0,.01);active([pct('attack',mul(R(18),add(1,distance)))],[],'gleam','Gleam of First Light active (14s; on-field)');});
define('rainbowserpentsrainbow',()=>active([pct('attack',R(28))],[],'offFieldHit','ATK buff after an off-field hit active (8s)'));
define('dawningfrost',()=>{active([stat('elementalMastery',R(72))],[],'chargedHit','Charged hit EM buff active (10s)');active([stat('elementalMastery',R(48))],[],'skillHit','Skill hit EM buff active (10s)');});
define('sacrificersstaff',()=>{const s=stacks(3);permanent([pct('attack',mul(R(8),s)),stat('energyRecharge',mul(R(6),s))]);});
define('etherlightspindlelute',()=>active([stat('elementalMastery',R(100))]));
define('snarehook masterkey',()=>active([stat('elementalMastery',mul(R(60),add(1,moon)))]));
define('serenityscall',()=>active([pct('maxHp',mul(R(16),add(1,moon)))]));
define('blackmarrowlantern prospectorsshovel',()=>{const bloom=current.key==='blackmarrowlantern';permanent([],[bonus(R(48),{damageKinds:['reaction'],requiredTags:[bloom?'bloom':'electroCharged']}),bonus(mul(R(12),add(1,moon)),lunar(bloom?'lunarBloom':'lunarCharged'))]);});
define('moonweaversdawn',()=>{control('energyCapacity','Wearer maximum Energy capacity',120,60);permanent([],[bonus(R(20),target(BU))]);effect('capacity60',{conditions:[condition('energyCapacity',60,'inputAtMost')],mods:[bonus(R(16),target(BU))]});effect('capacity40',{conditions:[condition('energyCapacity',40,'inputAtMost')],mods:[bonus(R(12),target(BU))]});});
define('flameforgedinsight',()=>active([stat('elementalMastery',R(60))],[],'reaction','Qualifying Electro-Charged/Bloom/Crystallize or Lunar reaction buff active'));
define('sequenceofsolitude ashgravendrinkinghorn',()=>proc(R(40),{statName:'maxHp'}));
define('tamayurateinoohanashi',()=>active([pct('attack',R(20))]));
define('flowerwreathedfeathers',()=>permanent([],[bonus(mul(R(6),stacks(6,'aiming','Aiming stacks (0.5s each)')),target(CA))]));
define('waveridingwhirl',()=>active([pct('maxHp',add(R(20),mul(R(12),min(2,X('hydroCount')))))]));
define('calamityofeshu',()=>active([],[bonus(R(20),target(NC)),crit(R(8),NC,'additionalRate')],'shield','Protected by a shield'));
define('mountainbracingbolt',()=>{permanent([],[bonus(R(12),target(SK))]);active([],[bonus(R(12),target(SK))],'allySkill','Other party member used a Skill (8s)');});
define('fruitfulhook',()=>{permanent([],[crit(R(16),PL,'additionalRate')]);active([],[bonus(R(16),target(NCP))],'plunge','After Plunging Attack hit (10s)');});
define('sturdybone',()=>active([],[flat(mul(.01,R(16),W('attack')),NA)],'sprint','Sprint buff active (7s / 18 hits)'));
define('chainbreaker',()=>{permanent([pct('attack',mul(R(4.8),X('natlanOrDifferentElementCount')))]);effect('threeMembers',{conditions:[ctxCondition('natlanOrDifferentElementCount',3)],stats:[stat('elementalMastery',R(24))]});});
define('ringofyaxche',()=>active([],[bonus(min(R(16),mul(R(.6,1),W('maxHp'),.001)),target(NA))]));
define('footprintoftherainbow fluteofezpitzal',()=>active([pct('defense',R(16))]));
define('earthshaker',()=>active([],[bonus(R(16),target(SK))],'pyroReaction','Party Pyro-related reaction buff active (8s)'));
define('cloudforged',()=>permanent([stat('elementalMastery',mul(R(40),stacks(2)))]));
utility('dialoguesofthedesertsages rightfulreward prototypeamber favoniuslance favoniuscodex favoniuswarbow favoniusgreatsword favoniussword sacrificialfragments sacrificialbow sacrificialgreatsword sacrificialsword amenomakageuchi');
define('ultimateoverlordsmegamagicsword',()=>{const progress=control('melusines','Melusine support progress (0 to 1)',1,0,.01);permanent([pct('attack',mul(R(12),add(1,progress)))]);});
define('swordofnarzissenkreuz',()=>{toggle('noArkhe','Wearer does not have an innate Arkhe');proc(R(160),{conditions:[condition('noArkhe')]});});
define('balladoftheboundlessblue',()=>{const s=stacks(3);permanent([],[bonus(mul(R(8),s),target(NA)),bonus(mul(R(6),s),target(CA))]);});
define('rangegauge prospectorsdrill',()=>{const s=stacks(3,'consumedSymbols','Symbols consumed (Struggle active)');permanent([pct('attack',mul(R(3,7),s))],[elementalBonus(mul(R(7,13),s))]);});
define('portablepowersaw thedockhandsassistant',()=>permanent([stat('elementalMastery',mul(R(40),stacks(3,'consumedSymbols','Symbols consumed (Roused active)')))]));
define('sacrificialjade',()=>active([pct('maxHp',R(32)),stat('elementalMastery',R(40))],[],'jade','Off-field buff active (including first 10s on-field)'));
define('scionoftheblazingsun',()=>{proc(R(60));active([],[bonus(R(28),target(CA))],'heartsearer','Target affected by Heartsearer');});
define('balladofthefjords',()=>effect('threeElements',{conditions:[ctxCondition('distinctElements',3)],stats:[stat('elementalMastery',R(120))]}));
define('talkingstick',()=>{active([pct('attack',R(16))],[],'pyro','Affected by Pyro buff active (15s)');active([],[elementalBonus(R(12))],'otherAura','Hydro/Cryo/Electro/Dendro aura buff active (15s)');});
define('wolffang',()=>{permanent([],[bonus(R(16),target(SB)),crit(mul(R(2),stacks(4,'skillStacks','Skill CRIT stacks')),SK,'additionalRate'),crit(mul(R(2),stacks(4,'burstStacks','Burst CRIT stacks')),BU,'additionalRate')]);});
define('fleuvecendreferryman',()=>{permanent([],[crit(R(8),SK,'additionalRate')]);active([stat('energyRecharge',R(16))]);});
define('flowingpurity',()=>{active([],[elementalBonus(R(8))],'skill','Elemental Skill buff active (15s)');const cleared=control('clearedBond','Bond of Life HP cleared (within buff duration)',100000,0,100);permanentBuff('bondCleared',[elementalBonus(min(R(12),mul(R(2),cleared,.001)))]);});
define('songofstillness',()=>active([],[bonus(R(16))],'healed','Healed buff active (8s)'));
define('tidalshadow',()=>active([pct('attack',R(24))],[],'healed','Healed buff active (8s)'));
define('finaleofthedeep',()=>{active([pct('attack',R(12))]);const cleared=control('clearedBond','Bond of Life HP cleared (within buff duration)',100000,0,100);effect('bondCleared',{stats:[stat('attack',min(R(150),mul(R(2.4),cleared,.01)))]});});
define('ibispiercer',()=>permanent([stat('elementalMastery',mul(R(40),stacks(2)))]));
define('mailedflower missivewindspear',()=>active([pct('attack',R(12)),stat('elementalMastery',R(48))]));
define('toukaboushigure',()=>active([],[bonus(R(16))],'parasol','This target has Cursed Parasol'));
define('wanderingevenstar makhairaaquamarine xiphosmoonlight',()=>{const er=current.key==='xiphosmoonlight',v=mul(er?R(.036):mul(.01,R(24)),W('elementalMastery'));permanent([stat(er?'energyRecharge':'attack',v,10)]);effect('partyShare',{recipient:'others',stats:[stat(er?'energyRecharge':'attack',mul(.3,v),10)]});});
define('forestregalia sapwoodblade moonpiercer',()=>{const atk=current.key==='moonpiercer';active([atk?pct('attack',R(16)):stat('elementalMastery',R(60))],[],'leaf','Active character picked up the Leaf (12s)',{recipient:'activeCharacter',group:atk?'leafOfRevival':'leafOfConsciousness'});});
define('kingssquire',()=>{active([stat('elementalMastery',R(60,140))],[],'teachings','Teachings of the Forest active');proc(R(100,180));});
define('fruitoffulfillment',()=>{const s=stacks(5);permanent([stat('elementalMastery',mul(R(24,36),s)),pct('attack',mul(-5,s))]);});
define('endoftheline',()=>proc(R(80)));
define('kagotsurubeisshin',()=>{active([pct('attack',15)]);proc(180);});
define('fadingtwilight',()=>permanent([],[bonus(mul(table(control('state','State: 1 Evengleam, 2 Afterglow, 3 Dawnblaze',3,1,1,1),[6,10,14],1),R(1,2)))]));
define('dragonspinespear snowtombedstarsilver frostbearer',()=>{const cryo=toggle('cryo','Target affected by Cryo');proc(add(R(80,140),mul(cryo,R(120,220))));});
define('blackclifflongsword blackcliffagate blackcliffslasher blackcliffwarbow blackcliffpole',()=>permanent([pct('attack',mul(R(12),stacks(3)))]));
define('theblacksword',()=>permanent([],[bonus(R(20),target(NC))]));
define('windblumeode',()=>active([pct('attack',R(16))]));
define('rainslasher lionsroar dragonsbane',()=>active([],[bonus(R(20,36))],'aura','Target has one of the elements specified by this weapon'));
define('swordofdescension',()=>{toggle('playstation','PlayStation-only passive enabled');effect('traveler',{conditions:[condition('playstation'),ctxCondition('wearerIsTraveler',1)],stats:[stat('attack',66)]});proc(200,{conditions:[condition('playstation')]});});
define('ironsting',()=>permanent([],[bonus(mul(R(6),stacks(2)))]));
define('compoundbow',()=>permanent([pct('attack',mul(R(4),stacks(4)))]));
define('thebell',()=>active([],[bonus(R(12))],'shield','Protected by a shield'));
define('cinnabarspindle',()=>active([],[flat(mul(.01,R(40),W('defense')),SK)],'available','Cinnabar bonus available for this Skill hit (1.5s cooldown)'));
define('prototypecrescent',()=>active([pct('attack',R(36))],[],'weakPoint','Weak-point hit buff active (10s)'));
define('prototypestarglitter',()=>permanent([],[bonus(mul(R(8),stacks(2)),target(NC))]));
define('prototyperancour whiteblind',()=>{const s=stacks(4),v=mul(R(current.key==='whiteblind'?6:4),s);permanent([pct('attack',v),pct('defense',v)]);});
define('prototypearchaic',()=>proc(R(240)));
define('oathsworneye',()=>active([stat('energyRecharge',R(24))]));
define('luxurioussealord',()=>{permanent([],[bonus(R(12),target(BU))]);proc(R(100));});
define('serpentspine',()=>permanent([],[bonus(mul(R(6,10),stacks(5)))]));
define('theviridescenthunt',()=>proc(R(40),{description:current.description+' The displayed result is one Cyclone tick, not all eight ticks.'}));
define('festeringdesire thecatch',()=>{const t=current.key==='thecatch'?BU:SK;permanent([],[bonus(R(16),target(t)),crit(R(6),t,'additionalRate')]);});
define('thestringless',()=>permanent([],[bonus(R(24),target(SB))]));
define('theflute',()=>proc(R(100)));
define('hamayumi',()=>{const f=add(1,toggle('fullEnergy','Energy is full'));permanent([],[bonus(mul(R(16),f),target(NA)),bonus(mul(R(12),f),target(CA))]);});
define('hakushinring',()=>{for(const element of ['Electro','Pyro','Hydro','Cryo','Dendro','Anemo','Geo']){const id=element.toLowerCase();toggle(id,element+' participated in the wearer Electro reaction (buff active)');effect(id,{conditions:[condition(id),ctxCondition('recipientElement.'+element,1)],mods:[bonus(R(10),{...normal,elements:[element]})],recipient:'party',group:'hakushinRing.'+element});}});
define('thewidsith',()=>{control('song','Theme: 0 none, 1 Recitative, 2 Aria, 3 Interlude',3);for(const [n,s,m] of [[1,[pct('attack',R(60))],[]],[2,[],[elementalBonus(R(48))]],[3,[stat('elementalMastery',R(240))],[]]])effect('theme'+n,{conditions:[condition('song',n),condition('song',n,'inputAtMost')],stats:s,mods:m});});
define('crescentpike',()=>proc(R(20)));
define('katsuragikirinagamasa kitaincrossspear',()=>permanent([],[bonus(R(6),target(SK))]));
define('mouunsmoon wavebreakersfin akuoumaru',()=>permanent([],[bonus(min(R(40),mul(R(.12),control('teamEnergyCapacity','Combined maximum Energy capacity of the selected party',500,240))),target(BU))]));
define('thealleyflash',()=>active([],[bonus(R(12))],'undamaged','Damage bonus active (no damage taken in past 5s)'));
define('wineandsong',()=>active([pct('attack',R(20))],[],'sprint','Sprint ATK buff active (5s)'));
define('alleyhunter',()=>permanent([],[bonus(mul(R(2),stacks(10,'stacks','Off-field damage bonus stacks remaining')))]));
define('eyeofperception',()=>proc(R(240,360)));
define('predator',()=>{toggle('playstation','PlayStation-only passive enabled');const s=stacks(2);effect('strongStrike',{conditions:[condition('playstation')],mods:[bonus(mul(10,s),target(NC))]});effect('aloy',{conditions:[condition('playstation'),ctxCondition('wearerIsAloy',1)],stats:[stat('attack',66)]});});
define('rust',()=>permanent([],[bonus(R(40),target(NA)),bonus(-10,target(CA))]));
define('mitternachtswaltz solarpearl',()=>{const pearl=current.key==='solarpearl';active([],[bonus(R(20),target(pearl?SB:SK))],'normalHit','Normal hit buff active');active([],[bonus(R(20),target(NA))],'skillHit','Skill'+(pearl?' / Burst':'')+' hit buff active');});
define('royalbow royallongsword royalgrimoire royalspear royalgreatsword',()=>permanent([stat('critRate',mul(R(8),stacks(5)))]));
define('dodocotales',()=>{active([],[bonus(R(16),target(CA))],'normalHit','Normal hit buff active (6s)');active([pct('attack',R(8))],[],'chargedHit','Charged hit buff active (6s)');});
define('lithicspear lithicblade',()=>permanent([pct('attack',mul(R(7,11),X('liyueCount'))),stat('critRate',mul(R(3,7),X('liyueCount')))]));
define('deathmatch',()=>{control('opponents','Nearby opponents',20,1,1,1);effect('multiple',{conditions:[condition('opponents',2)],stats:[pct('attack',R(16)),pct('defense',R(16))]});effect('single',{conditions:[condition('opponents',1,'inputAtMost')],stats:[pct('attack',R(24))]});});
define('mappamare',()=>permanent([],[elementalBonus(mul(R(8),stacks(2)))]));

// Three-star and starter weapons.
define('blacktassel',()=>active([],[bonus(R(40))],'slime','Target is a slime'));
define('harbingerofdawn',()=>active([stat('critRate',R(14))],[],'healthy','Current HP above 90%'));
define('ravenbow magicguide bloodtaintedgreatsword coolsteel',()=>active([],[bonus(R(12))],'aura','Target has one of the elements specified by this weapon'));
define('skyridersword',()=>active([pct('attack',R(12))]));
define('skyridergreatsword',()=>permanent([pct('attack',mul(R(6,10),stacks(4)))]));
define('ferrousshadow',()=>{const hp=control('hpPercent','Current HP (%)',100,100,.1);const eligible=clamp(mul(1000,add(R(70,90),mul(-1,hp))),0,1);permanent([],[bonus(mul(R(30,50),eligible),target(CA))]);});
define('halberd',()=>proc(R(160,320)));
define('thrillingtalesofdragonslayers',()=>active([pct('attack',R(24))],[],'handoff','Active character received the switch buff (10s)',{recipient:'activeOther',group:'thrillingTalesOfDragonSlayers'}));
define('emeraldorb darkironsword',()=>active([pct('attack',R(20))],[],'reaction','Qualifying elemental reaction buff active (12s)'));
define('sharpshootersoath',()=>active([],[bonus(R(24),target(CA)),crit(100,CA,'additionalRate')],'weakSpot','This Charged Attack hits a weak spot'));
define('whitetassel',()=>permanent([],[bonus(R(24),target(NA))]));
define('twinnephrite',()=>active([pct('attack',R(12,20))],[],'defeat','After defeating an opponent (15s)'));
define('slingshot',()=>{const fast=toggle('fast','Normal/Charged shot hit within 0.3s');permanent([],[bonus(add(-10,mul(add(R(36,60),10),fast)),target(NC))]);});
define('filletblade',()=>proc(R(240,400)));
define('messenger',()=>proc(R(100),{critRate:true,description:current.description+' The proc is a separate guaranteed critical Physical hit.'}));
define('debateclub',()=>proc(R(60)));
utility('whiteirongreatsword travelershandysword otherworldlystory recurvebow');

const missing=catalogue.filter(w=>w.rarity>=3&&!preserved.has(w.key)&&!recipes.has(w.key));
if(missing.length)throw Error('Missing explicit recipes: '+missing.map(w=>w.key).join(', '));
const stale=[...recipes.keys()].filter(key=>!catalogue.some(w=>w.key===key));if(stale.length)throw Error('Unknown recipe keys: '+stale.join(', '));
const changed=[];
for(const w of catalogue){
 if(preserved.has(w.key))continue;
 current={...w,inputs:[{id:'refinement',label:'Weapon refinement',defaultValue:1,min:1,max:5,step:1}],effects:[]};
 recipes.get(w.key)?.();
 const document={schemaVersion:2,key:w.key,name:w.name,inputs:current.inputs,effects:current.effects,sources:[w.source,...(['halberd','messenger'].includes(w.key)?['https://library.keqingmains.com/equipment/weapons/'+(w.key==='halberd'?'polearms':'bows')]:[])]};
 // Only expose controls used by effects. Starter weapons have no passive controls.
 document.inputs=document.inputs.filter(input=>JSON.stringify(document.effects).includes('local:'+input.id+'"'));
 const text=JSON.stringify(document,null,2)+'\n',file=path.join(out,w.key+'.json');
 if(!fs.existsSync(file)||fs.readFileSync(file,'utf8').replace(/\r\n/g,'\n')!==text){changed.push(w.key);if(!process.argv.includes('--check'))fs.writeFileSync(file,text);}
}
console.log(`${catalogue.length} catalogue weapons; ${preserved.size} existing definitions preserved; ${changed.length} ${process.argv.includes('--check')?'differ':'written'}.`);
if(process.argv.includes('--check')&&changed.length){console.error(changed.join(', '));process.exitCode=1;}
