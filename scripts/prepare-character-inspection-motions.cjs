// Copy a small reviewed set of numbered locomotion cycles for the in-page inspector.
// Root translation is removed so characters animate in place. Source VMDs stay untouched.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const motionRoot = path.join(root, '.tmp/model-download-test/animations');
const output = path.join(root, 'src/main/resources/static/character-stage/motions');
const profiles = {
    female: {folder:'2_Female_Motions', motions:[
        ['walk','Walk','3_Center_Bone_Movement_Motions/201_Female-Walk-Forward(Requires_UpperBody2).vmd'],
        ['jog','Jog','3_Center_Bone_Movement_Motions/221_Female-Jog-Forward(Requires_UpperBody2).vmd'],
        ['sprint','Sprint','3_Center_Bone_Movement_Motions/241_Female-Sprint_A-Forward(Requires_UpperBody2).vmd']]},
    male: {folder:'1_Male_Motions', motions:[
        ['walk','Walk','3_Center_Bone_Movement_Motions/101_Male_A-Walk-Forward(Requires_UpperBody2).vmd'],
        ['jog','Jog','3_Center_Bone_Movement_Motions/121_Male_A-Jog-Forward(Requires_UpperBody2).vmd'],
        ['sprint','Sprint','3_Center_Bone_Movement_Motions/141_Male_A-Sprint_A-Forward(Requires_UpperBody2).vmd']]},
    child: {folder:'3_Child_Motions', motions:[
        ['walk','Walk','3_Center_Bone_Movement_Motions/301_Child-Walk-Forward(Requires_UpperBody2).vmd'],
        ['jog','Jog','3_Center_Bone_Movement_Motions/321_Child-Jog-Forward(Requires_UpperBody2).vmd'],
        ['sprint','Sprint','3_Center_Bone_Movement_Motions/341_Child-Sprint-Forward(Requires_UpperBody2).vmd']]},
};
const sjis = new TextDecoder('shift_jis');
function prepare(profile, id, label, relative) {
    const source = path.join(motionRoot, profile.folder, relative);
    const bytes = fs.readFileSync(source);
    if (!bytes.toString('ascii', 0, 25).startsWith('Vocaloid Motion Data')) throw Error(`Invalid VMD: ${source}`);
    const count = bytes.readUInt32LE(50), tracks = new Map();
    for (let i = 0; i < count; i++) {
        const record = bytes.subarray(54 + i * 111, 54 + (i + 1) * 111);
        const name = sjis.decode(record.subarray(0, 15)).split('\0')[0];
        if (!tracks.has(name)) tracks.set(name, []);
        tracks.get(name).push(record);
    }
    const center = tracks.get('センター');
    if (!center?.length) throw Error(`No center bone in ${source}`);
    const centerTravel = center.map(frame => ({frame:frame.readUInt32LE(15), z:frame.readFloatLE(27)}))
        .sort((a,b)=>a.frame-b.frame);
    const origin = centerTravel[0].z;
    for (const [name, frames] of tracks) {
        if (!['センター', '左足ＩＫ', '右足ＩＫ'].includes(name)) continue;
        for (const frame of frames) {
            const frameNumber = frame.readUInt32LE(15);
            const travel = centerTravel.reduce((previous, current) => current.frame <= frameNumber ? current : previous, centerTravel[0]);
            frame.writeFloatLE(frame.readFloatLE(27) - (travel.z - origin), 27);
        }
    }
    const filename = `${profile.id}-${id}.vmd`;
    fs.writeFileSync(path.join(output, filename), bytes);
    const lastFrame = [...tracks.values()].flat().reduce((m,frame)=>Math.max(m,frame.readUInt32LE(15)),0);
    return {id, label, url:`/character-stage/motions/${filename}`, frames:lastFrame, loop:true,
        source:`${profile.folder}/${relative}`};
}
const catalog = {};
for (const [name, profile] of Object.entries(profiles)) {
    profile.id = name;
    catalog[name] = [
        {id:'idle',label:'Idle',url:`/character-stage/motions/${name}-idle.vmd`,
            frames:JSON.parse(fs.readFileSync(path.join(output,'provenance.json'),'utf8'))[name].frames,loop:true,
            source:JSON.parse(fs.readFileSync(path.join(output,'provenance.json'),'utf8'))[name].motionSource},
        ...profile.motions.map(([id,label,relative])=>prepare(profile,id,label,relative)),
    ];
}
fs.writeFileSync(path.join(output,'inspection-motions.json'),JSON.stringify(catalog,null,2)+'\n');
console.log(`Prepared ${Object.values(catalog).reduce((n, list)=>n+list.length,0)} profile-compatible motions.`);
