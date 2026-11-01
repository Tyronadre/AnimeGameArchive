// Assemble and palette-compress Blender's RGBA PNGs without an image-tool dependency.
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const companion = ['--companion-idle', '--companion-start', '--companion-stop'].includes(process.argv[2]);
const clip = companion ? process.argv[2].slice('--companion-'.length) : null;
const out = path.join(root, 'src/main/resources/static', companion ? 'companion' : 'startup');
const width = 256, height = 320, count = clip === 'stop' ? 26 : companion ? 22 : 18;
const prefix = companion ? `lumine-${clip}` : 'lumine-sprint';

function readRgba(file) {
    const png = fs.readFileSync(file), data = [];
    let w, h;
    for (let offset = 8; offset < png.length;) {
        const length = png.readUInt32BE(offset), type = png.toString('ascii', offset + 4, offset + 8);
        const chunk = png.subarray(offset + 8, offset + 8 + length);
        if (type === 'IHDR') {
            w = chunk.readUInt32BE(0); h = chunk.readUInt32BE(4);
            if (chunk[8] !== 8 || chunk[9] !== 6 || chunk[12] !== 0) throw Error('Expected non-interlaced 8-bit RGBA PNG');
        }
        if (type === 'IDAT') data.push(chunk);
        offset += length + 12;
    }
    if (w !== width || h !== height) throw Error('Unexpected frame size');
    const packed = zlib.inflateSync(Buffer.concat(data)), pixels = Buffer.alloc(w * h * 4);
    const stride = w * 4;
    function paeth(a, b, c) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }
    for (let y = 0; y < h; y++) {
        const filter = packed[y * (stride + 1)];
        for (let x = 0; x < stride; x++) {
            const index = y * stride + x, a = x >= 4 ? pixels[index - 4] : 0;
            const b = y ? pixels[index - stride] : 0, c = y && x >= 4 ? pixels[index - stride - 4] : 0;
            const prediction = [0, a, b, Math.floor((a + b) / 2), paeth(a, b, c)][filter];
            if (prediction === undefined) throw Error('Invalid PNG filter');
            pixels[index] = (packed[y * (stride + 1) + x + 1] + prediction) & 255;
        }
    }
    return pixels;
}
const frameDirectory = clip && clip !== 'idle' ? `.tmp/lumine-companion-${clip}-frames` : companion ? '.tmp/lumine-companion-frames' : '.tmp/lumine-loader-frames';
const frames = Array.from({length: count}, (_, i) => readRgba(path.join(root, frameDirectory, String(i).padStart(2, '0') + '.png')));
const histogram = new Map();
function keyOf(b, o) { return ((b[o] >> 3) << 14) | ((b[o + 1] >> 3) << 9) | ((b[o + 2] >> 3) << 4) | (b[o + 3] >> 4); }
for (const frame of frames) for (let o = 0; o < frame.length; o += 4) {
    if (frame[o + 3] < 8) continue;
    const key = keyOf(frame, o);
    let color = histogram.get(key);
    if (!color) histogram.set(key, color = {key, n: 0, sum: [0, 0, 0, 0]});
    color.n++;
    for (let i = 0; i < 4; i++) color.sum[i] += frame[o + i];
}
const colors = [...histogram.values()].map(c => ({...c, rgba: c.sum.map(v => v / c.n)}));
function box(items) {
    const ranges = [0, 1, 2, 3].map(i => Math.max(...items.map(c => c.rgba[i])) - Math.min(...items.map(c => c.rgba[i])));
    const axis = ranges.indexOf(Math.max(...ranges));
    return {items, axis, score: items.length > 1 ? ranges[axis] * Math.sqrt(items.reduce((n, c) => n + c.n, 0)) : 0};
}
const boxes = [box(colors)];
while (boxes.length < 255) {
    boxes.sort((a, b) => b.score - a.score);
    if (!boxes[0].score) break;
    const {items, axis} = boxes.shift();
    items.sort((a, b) => a.rgba[axis] - b.rgba[axis]);
    const half = items.reduce((n, c) => n + c.n, 0) / 2;
    let population = 0, split = 1;
    for (; split < items.length; split++) { population += items[split - 1].n; if (population >= half) break; }
    split = Math.min(split, items.length - 1);
    boxes.push(box(items.slice(0, split)), box(items.slice(split)));
}
const palette = [[0, 0, 0, 0], ...boxes.map(({items}) => {
    const population = items.reduce((n, c) => n + c.n, 0);
    return [0, 1, 2, 3].map(i => Math.round(items.reduce((sum, c) => sum + c.sum[i], 0) / population));
})];
const indices = new Map();
for (const color of colors) {
    let best = 1, distance = Infinity;
    for (let i = 1; i < palette.length; i++) {
        const delta = color.rgba.reduce((sum, value, channel) => sum + (value - palette[i][channel]) ** 2, 0);
        if (delta < distance) { distance = delta; best = i; }
    }
    indices.set(color.key, best);
}
const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcTable[n] = c; }
function chunk(type, payload) {
    const name = Buffer.from(type), body = Buffer.concat([name, payload]), result = Buffer.alloc(payload.length + 12);
    result.writeUInt32BE(payload.length); body.copy(result, 4);
    let crc = 0xffffffff; for (const b of body) crc = crcTable[(crc ^ b) & 255] ^ (crc >>> 8);
    result.writeUInt32BE((crc ^ 0xffffffff) >>> 0, result.length - 4);
    return result;
}
function writePng(file, usedFrames) {
    const w = width * usedFrames.length, rows = Buffer.alloc((w + 1) * height);
    for (let y = 0; y < height; y++) for (let f = 0; f < usedFrames.length; f++) for (let x = 0; x < width; x++) {
        const frame = usedFrames[f], o = (y * width + x) * 4;
        rows[y * (w + 1) + 1 + f * width + x] = frame[o + 3] < 8 ? 0 : indices.get(keyOf(frame, o));
    }
    const header = Buffer.alloc(13); header.writeUInt32BE(w); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 3;
    fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header),
        chunk('PLTE', Buffer.from(palette.flatMap(c => c.slice(0, 3)))), chunk('tRNS', Buffer.from(palette.map(c => c[3]))),
        chunk('IDAT', zlib.deflateSync(rows, {level: 9})), chunk('IEND', Buffer.alloc(0))]));
}
fs.mkdirSync(out, {recursive: true});
writePng(path.join(out, prefix + '.png'), frames);
writePng(path.join(out, prefix + '-poster.png'), [frames[0]]);
const motionSource = companion
    ? '2_Female_Motions/' + {
        idle: '2_Idle_Motions/290L-290L_Female-Idle_L(Requires_UpperBody2).vmd',
        start: '4_Master_Root_Movement_Motions/290L-220_Female-Idle_L_to_Jog-Forward(Requires_UpperBody2).vmd',
        stop: '4_Master_Root_Movement_Motions/220-end_Female-Jog-Forward_to_Idle(Requires_UpperBody2).vmd',
    }[clip]
    : '2_Female_Motions/3_Center_Bone_Movement_Motions/241_Female-Sprint_A-Forward(Requires_UpperBody2).vmd';
const start = clip === 'start' ? 83 : companion ? 67 : Number(process.argv[2] || 120);
const source = fs.readFileSync(path.join(root, '.tmp/model-download-test/animations', motionSource));
fs.writeFileSync(path.join(out, prefix + '-source.json'), JSON.stringify({character: 'Lumine', motionAuthor: 'PAC_MMD', motionSource,
    sourceSha256: crypto.createHash('sha256').update(source).digest('hex'), frames: count, frameWidth: width, frameHeight: height,
    fps: companion ? 10 : 30, blenderFrames: [start, start + (count - 1) * (companion ? 3 : 1)], inPlace: true,
    ...(companion ? {basePoseSource: '2_Female_Motions/3_Center_Bone_Movement_Motions/202_Female-Walk-Forward_to_Stop(Requires_UpperBody2).vmd', basePoseFrame: 333, basePoseForwardTravelRemoved: true,
        ...(clip !== 'idle' ? {loop: false, sourceFrames: clip === 'start' ? [16, 78] : [0, 75], masterTravelRemoved: true, strideScale: .94, cameraBlend: clip === 'start' ? 'idle-to-sprint' : 'sprint-to-idle'} : {})}
        : {originalArmSwing: true, strideScale: .94})}, null, 2) + '\n');
console.log(JSON.stringify({spriteBytes: fs.statSync(path.join(out, prefix + '.png')).size, paletteColors: palette.length, frames: count}));
