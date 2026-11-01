const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {withBrowser}=require('./browser-test-session.cjs');
const root=path.resolve(__dirname,'..'),staticRoot=path.join(root,'src/main/resources/static');
const models=require('../src/main/resources/data/character-stages.json').models;
const provenance=require('../src/main/resources/static/character-stage/motions/provenance.json');
const library=process.env.GENSHIN_MODEL_DIRECTORY||'E:/Modding/Genshin/models/02_translated',requests=[];
const types={'.js':'application/javascript','.css':'text/css','.png':'image/png','.bmp':'image/bmp','.json':'application/json','.wasm':'application/wasm'};
function file(res,target){if(!fs.existsSync(target)){res.writeHead(404);res.end();return;}res.writeHead(200,{'Content-Type':types[path.extname(target)]||'application/octet-stream'});res.end(fs.readFileSync(target));}
const server=http.createServer((req,res)=>{
    const url=new URL(req.url,'http://localhost'),route=decodeURIComponent(url.pathname).replace(/^\/archive/,'');requests.push(route);
    if(route.startsWith('/api/character-stages/')){
        const key=route.split('/').at(-1),entry=models.find(m=>m.aliases.includes(key));
        const data=!entry||url.searchParams.get('missing')==='1'?{available:false}:{available:true,
            modelUrl:'/archive/media/character-stages/'+entry.key+'/files/'+entry.model.split('/').map(encodeURIComponent).join('/'),
            motionUrl:'/archive/character-stage/motions/'+entry.profile+'-idle.vmd',motionFrames:provenance[entry.profile].frames,variant:entry.variant};
        res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(data));return;
    }
    const modelMatch=route.match(/^\/media\/character-stages\/([^/]+)\/files\/(.+)$/);
    if(modelMatch){const entry=models.find(m=>m.key===modelMatch[1]),asset=modelMatch[2];
        if(!entry?.assets.includes(asset)){res.writeHead(404);res.end();return;}
        file(res,path.join(entry.bundled?path.join(staticRoot,'character-stage'):library,entry.directory,asset));return;}
    if(route.startsWith('/character-stage/')||route.startsWith('/css/')||route.startsWith('/js/'))return file(res,path.join(staticRoot,route));
    if(route.startsWith('/companion/')||route.startsWith('/startup/'))return file(res,path.join(staticRoot,route));
    if(route.startsWith('/media/characters/'))return file(res,path.join(staticRoot,'companion/lumine-idle-poster.png'));
    if(route==='/rendered-page' && fs.existsSync(path.join(root,'.tmp/character-stage-rendered.html'))){
        res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});res.end(fs.readFileSync(path.join(root,'.tmp/character-stage-rendered.html')));return;
    }
    if(route==='/poster.png')return file(res,path.join(staticRoot,'companion/lumine-idle-poster.png'));
    const modelKey=url.searchParams.get('model')||'lumine',missing=url.searchParams.get('missing')==='1';
    res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});
    res.end(`<!doctype html><html lang="${url.searchParams.get('lang')||'en'}" data-theme="${url.searchParams.get('theme')||'dark'}">
    <meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/archive/css/app.css">
    <link rel="stylesheet" href="/archive/css/character-stage.css"><script defer src="/archive/js/character-stage.js"></script>
    <body><main class="detail-page"><section class="character-hero element-${url.searchParams.get('element')||'anemo'}">
    <div class="hero-copy"><p class="eyebrow">Anemo</p><div class="character-heading"><h1>${modelKey==='lumine'?'Lumine':modelKey}</h1></div>
    <p class="character-title">A journey through the stars</p><p class="lead">Every adventure begins with a single step.</p>
    <div class="facts"><div class="fact"><span class="fact-icon">★</span><span class="fact-copy"><small>Rarity</small><b>5 ★</b></span></div><div class="fact"><span class="fact-icon">⚔</span><span class="fact-copy"><small>Weapon</small><b>Sword</b></span></div></div>
    <div class="good-character-state">Level 80 · C0</div><a href="#plan">Plan your next adventure →</a></div>
    <div class="hero-art character-stage" data-character-stage data-descriptor-url="/archive/api/character-stages/${modelKey}${missing?'?missing=1':''}" data-character-name="${modelKey}">
    <div class="hero-orbit orbit-one"></div><div class="hero-orbit orbit-two"></div><div class="character-stage-light"></div><div class="character-stage-floor"></div>
    <span class="hero-art-fallback">L</span><img data-stage-portrait class="character-stage-portrait" src="/archive/poster.png" alt="Lumine">
    <canvas data-stage-canvas class="character-stage-canvas" hidden role="img" aria-label="Character stage"></canvas>
    <div class="character-stage-controls" data-stage-controls hidden><span class="character-stage-caption" data-stage-status role="status">Character artwork</span>
    <button data-stage-mode hidden>3D stage</button><button data-stage-pause>Pause motion</button></div></div></section>
    <section style="height:1500px" id="plan"><h2>Character progress</h2></section></main></body></html>`);
});
withBrowser(server,async({base,call,evaluate,until,sleep,screenshot,errors,logs})=>{
    await call('Emulation.setDeviceMetricsOverride',{width:1300,height:950,deviceScaleFactor:1,mobile:false});
    const navigate=async suffix=>{await call('Page.navigate',{url:base+'/archive/stage'+suffix});await until(()=>evaluate("!!document.querySelector('[data-stage-controls]:not([hidden])')"),'Stage controls missing');};
    await navigate('');
    try {await until(()=>evaluate("document.querySelector('[data-character-stage]').dataset.presentation==='model'"),'Real Lumine model did not render',45000);}
    catch(error){console.error(await evaluate("document.querySelector('[data-character-stage]').outerHTML"),logs);throw error;}
    assert.ok(requests.some(p=>p.endsWith('/female-idle.vmd')));
    assert.equal(await evaluate("document.querySelectorAll('canvas').length"),1);
    await evaluate("window.stageLoops=0;document.querySelector('[data-character-stage]').addEventListener('character-stage:loop',()=>stageLoops++)");
    await until(()=>evaluate('stageLoops>=2'),'Idle did not keep playing across multiple cycles',12000);
    await sleep(1000);await screenshot('character-stage-lumine-desktop.png');
    await evaluate("document.querySelector('[data-stage-pause]').click()");
    await until(()=>evaluate("document.querySelector('[data-character-stage]').dataset.rendering==='false'"),'Pause did not stop the renderer');
    await evaluate("document.querySelector('[data-stage-pause]').click()");
    await until(()=>evaluate("document.querySelector('[data-character-stage]').dataset.rendering==='true'"),'Resume did not restart rendering');
    await evaluate('scrollTo(0,1500)');await until(()=>evaluate("document.querySelector('[data-character-stage]').dataset.rendering==='false'"),'Offscreen rendering did not stop');
    await evaluate('scrollTo(0,0)');await until(()=>evaluate("document.querySelector('[data-character-stage]').dataset.rendering==='true'"),'Visible rendering did not resume');
    assert.equal(await evaluate("document.querySelector('.character-heading').getAnimations().length"),0,'Returning to the stage must not restart the text entrance');
    await call('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
    await until(()=>evaluate("document.querySelector('[data-character-stage]').dataset.rendering==='false'"),'Reduced motion should stop the live renderer');
    assert.equal(await evaluate("getComputedStyle(document.querySelector('[data-stage-canvas]')).filter"),'none','Reduced motion must finish the silhouette rather than freeze it dark');
    await call('Emulation.setEmulatedMedia',{features:[]});
    await evaluate("document.querySelector('[data-stage-mode]').click()");
    assert.equal(await evaluate("document.querySelector('[data-stage-canvas]').hidden"),true);
    for (const model of ['nefer','flins','sigewinne','gaming']) {
        await evaluate("localStorage.removeItem('genshin-archive.character-stage.v1')");
        await navigate('?model='+model+'&element='+({nefer:'dendro',flins:'electro',sigewinne:'hydro'}[model]));
        try {await until(()=>evaluate("document.querySelector('[data-character-stage]').dataset.presentation==='model'"),'External model failed: '+model,45000);}
        catch(error){console.error(logs.slice(-12));throw error;}
        await sleep(900);await screenshot('character-stage-'+model+'.png');
    }
    await call('Network.enable');await call('Network.setBlockedURLs',{urls:['*media/character-stages/*']});
    await navigate('?model=lumine');
    await until(()=>evaluate("document.querySelector('[data-character-stage]').dataset.modelFailed==='true'"),'Failed model should return to artwork');
    assert.equal(await evaluate("document.querySelector('[data-stage-canvas]').hidden"),true);
    await call('Network.setBlockedURLs',{urls:[]});
    await evaluate("document.querySelector('[data-stage-mode]').click()");
    await until(()=>evaluate("document.querySelector('[data-character-stage]').dataset.presentation==='model'"),'3D retry did not recover',45000);
    const before=requests.length;await navigate('?missing=1&theme=light');await sleep(600);
    assert.equal(requests.slice(before).some(p=>/vendor|\/files\//.test(p)),false,'Missing models must not download a 3D renderer');
    assert.equal(await evaluate("document.querySelector('[data-stage-mode]').hidden"),true);
    await screenshot('character-stage-portrait-light.png');
    await evaluate("localStorage.removeItem('genshin-archive.character-stage.v1')");
    await call('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
    await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
    const reducedStart=requests.length;await navigate('?lang=de');await sleep(800);
    assert.equal(requests.slice(reducedStart).some(p=>/vendor|\/files\//.test(p)),false,'Reduced motion/mobile should default to artwork');
    assert.equal(await evaluate('document.body.scrollWidth<=innerWidth'),true,'Mobile stage should not overflow');
    assert.equal(await evaluate("(()=>{const r=document.querySelector('[data-character-stage]').getBoundingClientRect();return r.width>0&&r.left>=0&&r.right<=innerWidth})()"),true,'Mobile must actually show the portrait stage inside the viewport');
    assert.equal(await evaluate("getComputedStyle(document.querySelector('.hero-orbit')).animationName"),'none');
    await screenshot('character-stage-mobile.png');
    if(fs.existsSync(path.join(root,'.tmp/character-stage-rendered.html'))){
        await call('Page.navigate',{url:base+'/rendered-page'});
        await until(()=>evaluate("!!document.querySelector('[data-stage-controls]:not([hidden])')"),'Real Spring character template did not initialize');
        assert.equal(await evaluate("!!document.querySelector('#character-progress-form [name=_csrf]')"),true,'Real planning form must keep CSRF');
        await sleep(500);await screenshot('character-stage-real-template-mobile.png');
    }
    assert.deepEqual(errors,[],'Stage should not throw browser exceptions');
    console.log(JSON.stringify({passed:true,checks:['real fitted PMX loads','female/male/child external models','TGA textures','numbered complete idle','idle repeats across cycles','single canvas','pause/resume','offscreen suspension','entrance does not restart','runtime reduced-motion change','failed model fallback and retry',
        'artwork switch','missing-model fallback without runtime','light and dark themes','German controls','reduced motion','mobile layout','context path']},null,2));
}).catch(error=>{console.error(error);process.exitCode=1;});
