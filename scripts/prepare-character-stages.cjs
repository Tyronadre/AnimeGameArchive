// Build a reviewed model allowlist and complete idle motions from numbered packs.
// Does not modify source PMX/VMD files. No package dependencies.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const library = process.env.GENSHIN_MODEL_DIRECTORY || 'E:/Modding/Genshin/models/02_translated';
const motionRoot = path.join(root, '.tmp/model-download-test/animations');
const output = path.join(root, 'src/main/resources/static/character-stage');
const normalize = s => s.toLowerCase().replace(/[^a-z0-9]/g, '');
const hash = b => crypto.createHash('sha256').update(b).digest('hex');
const readText = b => b.toString('ascii').split('\0')[0];
const sjis = new TextDecoder('shift_jis');
function pmx(file) {
    const b = fs.readFileSync(file); if (b.toString('ascii',0,4) !== 'PMX ') throw Error('Invalid PMX');
    let o = 8; const length=b[o++], h=[...b.subarray(o,o+length)]; o+=length;
    const int=()=>{const n=b.readInt32LE(o);o+=4;return n;};
    const string=()=>{const n=int();const s=b.subarray(o,o+n).toString(h[0]?'utf8':'utf16le');o+=n;return s;};
    const name=string(), english=string(); string();string();const vertices=int();
    for(let i=0;i<vertices;i++) {o+=32+16*h[1];const type=b[o++];o+=type===0?h[5]:type===1?2*h[5]+4:type===3?2*h[5]+40:4*h[5]+16;o+=4;}
    const indices=int();o+=indices*h[2];const textures=Array.from({length:int()},string);
    return {file,name,english,vertices,textures};
}
function walk(directory) {
    return fs.readdirSync(directory,{withFileTypes:true}).flatMap(e=>e.isSymbolicLink()?[]:
        e.isDirectory()?walk(path.join(directory,e.name)):e.name.toLowerCase().endsWith('.pmx')?[pmx(path.join(directory,e.name))]:[]);
}
const male=new Set(['bennett','durin','flins','gaming','ifa','kinich','xingqiu','lohen','lorn','ororon','sethos','dahlia','talia']);
const child=new Set(['iansan','kachina','sigewinne']);
const aliases={'Female Traveler Lumine':'lumine','Hu Tao - Lantern Rite':'hutao','Lantern Rite Ganyu':'ganyu',
    'Lantern Rite Shenhe':'shenhe','Lantern Rite Xingqiu':'xingqiu','Kirara - Phantom in Boots':'kirara'};
const models=[];
for(const folder of fs.readdirSync(library,{withFileTypes:true}).filter(e=>e.isDirectory())) {
    const title=folder.name.replace(/_by_Genshin_Impact_.*$/,'').replace(/[【】]/g,'');
    const key=aliases[title]||normalize(title.replace(/ Skin$/,''));
    const candidates=walk(path.join(library,folder.name)).sort((a,b)=>b.vertices-a.vertices);
    const model=key==='skirk'?candidates.find(m=>path.basename(m.file)==='丝柯克.pmx')||candidates[0]:candidates[0];
    if(!model)continue;
    const folderPath=path.join(library,folder.name);
    const assets=[model.file,...model.textures.filter(Boolean).map(t=>path.resolve(path.dirname(model.file),t.replaceAll('\\','/')))];
    const relative=assets.map(file=>path.relative(folderPath,file).split(path.sep).join('/'));
    if(relative.some(p=>p.startsWith('../'))||assets.some(p=>!fs.existsSync(p))) {
        console.warn('Skipping incomplete model:',title,assets.filter(p=>!fs.existsSync(p)));continue;
    }
    const keys=[key];
    if(key==='lumine')keys.push('travelerlumine');
    if(/^[a-zA-Z]+$/.test(model.english)&&!['NewMMDModel','PlayerGirl'].includes(model.english))keys.push(normalize(model.english));
    models.push({key,aliases:[...new Set(keys)],directory:folder.name,model:relative[0],assets:[...new Set(relative)],
        profile:male.has(key)?'male':child.has(key)?'child':'female',variant:title,sourceSha256:hash(fs.readFileSync(model.file))});
}
// Prefer the base outfit when both base and skin are present.
models.sort((a,b)=>a.variant.includes('Skin')-b.variant.includes('Skin')||a.key.localeCompare(b.key));
const selected=models.filter((m,i)=>models.findIndex(n=>n.key===m.key)===i);
const fitted=path.join(root,'.tmp/fitted-lumine-stage/lumine.pmx');
if(fs.existsSync(fitted)) {
    const model=pmx(fitted),directory=path.dirname(fitted),dest=path.join(output,'models/lumine');
    const assets=[fitted,...model.textures.filter(Boolean).map(t=>path.resolve(directory,t.replaceAll('\\','/')))];
    const relative=assets.map(file=>path.relative(directory,file).split(path.sep).join('/'));
    if(relative.some(p=>p.startsWith('../'))||assets.some(p=>!fs.existsSync(p)))throw Error('Incomplete fitted Lumine export');
    assets.forEach((file,i)=>{const target=path.join(dest,relative[i]);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(file,target);});
    const entry=selected.find(m=>m.key==='lumine');
    const originalHash=entry.sourceSha256;
    const notice=path.join(library,entry.directory,'readme【一定要看】.txt');
    if(fs.existsSync(notice))fs.copyFileSync(notice,path.join(dest,'MODEL-README.txt'));
    fs.writeFileSync(path.join(dest,'provenance.json'),JSON.stringify({character:'Lumine',originalDirectory:entry.directory,
        originalSourceSha256:originalHash,exportSource:'.tmp/loader-lumine-source.blend',
        fittedSkirtClearance:.15,sourceSceneUnchanged:true,exportScript:'scripts/export-fitted-lumine-stage.py'},null,2)+'\n');
    Object.assign(entry,{bundled:true,directory:'models/lumine',model:'lumine.pmx',assets:relative,sourceSha256:hash(fs.readFileSync(fitted)),fittedSkirtClearance:true});
}
fs.mkdirSync(path.join(root,'src/main/resources/data'),{recursive:true});
fs.writeFileSync(path.join(root,'src/main/resources/data/character-stages.json'),JSON.stringify({models:selected},null,2)+'\n');

function vmd(file) {
    const b=fs.readFileSync(path.join(motionRoot,file));
    if(!readText(b.subarray(0,30)).startsWith('Vocaloid Motion Data'))throw Error('Invalid VMD');
    const tracks=new Map(); let end=0;
    for(let i=0,n=b.readUInt32LE(50);i<n;i++) {
        const record=Buffer.from(b.subarray(54+i*111,54+(i+1)*111)), name=sjis.decode(record.subarray(0,15)).split('\0')[0], frame=record.readUInt32LE(15);
        if(!tracks.has(name))tracks.set(name,[]);tracks.get(name).push(record);end=Math.max(end,frame);
    }
    for(const records of tracks.values())records.sort((a,b)=>a.readUInt32LE(15)-b.readUInt32LE(15));
    return {tracks,end,bytes:b};
}
const profiles={
    female:['2_Female_Motions/3_Center_Bone_Movement_Motions/202_Female-Walk-Forward_to_Stop(Requires_UpperBody2).vmd','2_Female_Motions/2_Idle_Motions/290L-290L_Female-Idle_L(Requires_UpperBody2).vmd'],
    male:['1_Male_Motions/3_Center_Bone_Movement_Motions/102_Male_A-Walk-Forward_to_Stop(Requires_UpperBody2).vmd','1_Male_Motions/2_Idle_Motions/190-190_Male_A-Idle(Requires_UpperBody2).vmd'],
    child:['3_Child_Motions/3_Center_Bone_Movement_Motions/302_Child-Walk-Forward_to_Idle(Requires_UpperBody2).vmd','3_Child_Motions/2_Idle_Motions/390-390_Child-Idle(Requires_UpperBody2).vmd'],
};
fs.mkdirSync(path.join(output,'motions'),{recursive:true});const provenance={};
for(const [profile,[basePath,idlePath]] of Object.entries(profiles)) {
    const base=vmd(basePath),idle=vmd(idlePath), records=[];
    const center=base.tracks.get('センター').at(-1),travel=center.readFloatLE(27);
    for(const [name,frames] of base.tracks) {
        if(idle.tracks.has(name))continue;
        const r=Buffer.from(frames.at(-1));r.writeUInt32LE(0,15);
        if(['センター','左足ＩＫ','右足ＩＫ'].includes(name))r.writeFloatLE(r.readFloatLE(27)-travel,27);
        records.push(r);
    }
    let loopSeamTracks=0;
    for(const frames of idle.tracks.values()) {
        frames.sort((a,b)=>a.readUInt32LE(15)-b.readUInt32LE(15));
        if(frames.length>1) {
            const first=frames[0],last=frames.at(-1),endFrame=idle.end;
            const positionDelta=Math.hypot(first.readFloatLE(19)-last.readFloatLE(19),first.readFloatLE(23)-last.readFloatLE(23),first.readFloatLE(27)-last.readFloatLE(27));
            const dot=Math.min(1,Math.abs(first.readFloatLE(31)*last.readFloatLE(31)+first.readFloatLE(35)*last.readFloatLE(35)+first.readFloatLE(39)*last.readFloatLE(39)+first.readFloatLE(43)*last.readFloatLE(43)));
            if(last.readUInt32LE(15)<endFrame||positionDelta>1e-5||2*Math.acos(dot)>1e-4) {
                const seam=Buffer.from(first);seam.writeUInt32LE(endFrame,15);
                const existing=frames.findIndex(r=>r.readUInt32LE(15)===endFrame);
                if(existing>=0)frames[existing]=seam;else frames.push(seam);
                frames.sort((a,b)=>a.readUInt32LE(15)-b.readUInt32LE(15));loopSeamTracks++;
            }
        }
        records.push(...frames);
    }
    const header=Buffer.alloc(54);header.write('Vocaloid Motion Data 0002');header.write('Archive idle',30);header.writeUInt32LE(records.length,50);
    fs.writeFileSync(path.join(output,'motions',profile+'-idle.vmd'),Buffer.concat([header,...records,Buffer.alloc(16)]));
    provenance[profile]={baseSource:basePath,baseSourceSha256:hash(base.bytes),motionSource:idlePath,sourceSha256:hash(idle.bytes),frames:idle.end,
        fps:30,basePoseFrame:base.end,baseForwardTravelRemoved:true,loopSeamFrame:idle.end,loopSeamCorrectedTracks:loopSeamTracks};
}
fs.writeFileSync(path.join(output,'motions/provenance.json'),JSON.stringify(provenance,null,2)+'\n');
const vendor=path.join(output,'vendor');fs.mkdirSync(vendor,{recursive:true});
const vendorHashes={};
for(const name of ['babylon.js','babylon.mmd.min.js']) {
    const file=fs.readFileSync(path.join(root,'.tmp/model-download-test',name));fs.writeFileSync(path.join(vendor,name),file);vendorHashes[name]=hash(file);
}
const wasmFiles=fs.readdirSync(path.join(root,'.tmp/model-download-test')).filter(name=>/^[a-f0-9]{20}\.wasm$/i.test(name)).sort();
for(const name of wasmFiles) {
    const file=fs.readFileSync(path.join(root,'.tmp/model-download-test',name));fs.writeFileSync(path.join(vendor,name),file);vendorHashes[name]=hash(file);
}
fs.writeFileSync(path.join(vendor,'sources.json'),JSON.stringify({source:'Existing local PMX Character Studio runtime; unmodified bundles',sha256:vendorHashes,
    projects:['https://github.com/BabylonJS/Babylon.js','https://github.com/noname0310/babylon-mmd']},null,2)+'\n');
console.log(JSON.stringify({models:selected.length,profiles:Object.keys(profiles),vendorHashes},null,2));
